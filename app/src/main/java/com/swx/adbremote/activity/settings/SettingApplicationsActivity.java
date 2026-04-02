package com.swx.adbremote.activity.settings;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.swx.adbremote.R;
import com.swx.adbremote.adapter.SettingApplicationsAdapter;
import com.swx.adbremote.components.AppOperateDialog;
import com.swx.adbremote.components.QuestionDialog;
import com.swx.adbremote.database.DBManager;
import com.swx.adbremote.entity.AppItem;
import com.swx.adbremote.utils.ADBConnectUtil;
import com.swx.adbremote.utils.BeanUtil;
import com.swx.adbremote.utils.Constant;
import com.swx.adbremote.utils.MetricsUtil;
import com.swx.adbremote.utils.RecyclerViewItemEqspa;
import com.swx.adbremote.utils.SharedData;
import com.swx.adbremote.utils.ThreadPoolService;
import com.swx.adbremote.utils.ToastUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <a href="https://www.cnblogs.com/wjtaigwh/p/6543354.html">Android -- 实现RecyclerView可拖拽item</a>
 */
public class SettingApplicationsActivity extends AppCompatActivity implements View.OnClickListener {
    private SettingApplicationsAdapter mRvAdapter;
    private ItemTouchHelper mItemTouchHelper;
    private QuestionDialog questionDialog;
    private AppOperateDialog operateDialog;

    public static final int WHAT_DELETE = -1;
    public static final int WHAT_ADD = -2;
    public static final int WHAT_LIST = -3;
    public static final int WHAT_UPDATE = -4;
    public static final int WHAT_DEVICE_APPS = -5;

    private int selectPosition = 0;
    private Integer selectAppId;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting_applications);
        init();
        initEvent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initData();
        loadAppsFromDevice(true);
    }

    private void init() {
        RecyclerView mRv = findViewById(R.id.rv_setting_application);
        mRv.setLayoutManager(new LinearLayoutManager(this));
        mRvAdapter = new SettingApplicationsAdapter(this);
        mItemTouchHelper = new ItemTouchHelper(callback);
        mRv.setAdapter(mRvAdapter);
        mItemTouchHelper.attachToRecyclerView(mRv);

        mRv.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final int unit = MetricsUtil.dp2px(4);

            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);
                RecyclerViewItemEqspa.equilibriumAssignmentOfLinear(unit, outRect, view, parent);
            }
        });

        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (msg.what == WHAT_LIST) {
                    List<AppItem> appItems = BeanUtil.castList(msg.obj, AppItem.class);
                    mRvAdapter.setData(appItems);
                } else if (msg.what == WHAT_ADD) {
                    mRvAdapter.addData((AppItem) msg.obj);
                    if (operateDialog != null) {
                        operateDialog.hide();
                    }
                } else if (msg.what == WHAT_DELETE) {
                    mRvAdapter.remove(selectPosition);
                    if (questionDialog != null) {
                        questionDialog.hide();
                    }
                } else if (msg.what == WHAT_UPDATE) {
                    mRvAdapter.updateData(msg.arg1, (AppItem) msg.obj);
                    if (operateDialog != null) {
                        operateDialog.hide();
                    }
                } else if (msg.what == WHAT_DEVICE_APPS) {
                    List<String> packageNames = BeanUtil.castList(msg.obj, String.class);
                    showDeviceAppsDialog(packageNames);
                }
            }
        };
    }

    private void initEvent() {
        findViewById(R.id.btn_setting_app_back).setOnClickListener(this);
        findViewById(R.id.btn_setting_add_app).setOnClickListener(this);
        mRvAdapter.setOnItemLongClickListener(vh -> mItemTouchHelper.startDrag(vh));
        mRvAdapter.setOnItemClickListener(new SettingApplicationsAdapter.OnItemClickListener() {
            @Override
            public void onRemoveClick(int position, Integer id) {
                selectAppId = id;
                selectPosition = position;
                showQuestionDialog();
            }

            @Override
            public void onEditClick(int position, AppItem app) {
                selectPosition = position;
                showOperateDialog(app);
            }

            @Override
            public void onAddToQuickAccessClick(int position, AppItem app) {
                addSelectedPackage(app.getUrl());
            }
        });
    }

    private void initData() {
        ThreadPoolService.newTask(() -> {
            List<AppItem> apps = DBManager.getInstance().getAppManager().list();
            if (!apps.isEmpty()) {
                Collections.sort(apps, (a1, a2) -> a1.getPriority() - a2.getPriority());
                Message message = new Message();
                message.obj = apps;
                message.what = WHAT_LIST;
                handler.sendMessage(message);
            }
        });
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.btn_setting_app_back) {
            finish();
        } else if (id == R.id.btn_setting_add_app) {
            loadAppsFromDevice(false);
        }
    }

    private void loadAppsFromDevice(boolean silent) {
        ADBConnectUtil.listInstalledPackages((success, msg) -> {
            if (!success) {
                if (!silent) {
                    ToastUtil.showShort(getString(R.string.text_connection_failed));
                }
                return;
            }
            List<String> packageNames = parseInstalledPackages(msg);
            if (packageNames.isEmpty()) {
                return;
            }
            List<AppItem> appItems = new ArrayList<>();
            for (String packageName : packageNames) {
                AppItem appItem = new AppItem("", packageName, packageName);
                appItems.add(appItem);
            }
            Message message = new Message();
            message.what = WHAT_LIST;
            message.obj = appItems;
            handler.sendMessage(message);
        });
    }

    private List<String> parseInstalledPackages(String msg) {
        List<String> packageNames = new ArrayList<>();
        if (msg == null || msg.trim().isEmpty()) {
            return packageNames;
        }
        Pattern pattern = Pattern.compile("package:([\\w.]+)");
        Matcher matcher = pattern.matcher(msg);
        while (matcher.find()) {
            packageNames.add(matcher.group(1));
        }
        Collections.sort(packageNames);
        return packageNames;
    }

    private void showDeviceAppsDialog(List<String> packageNames) {
        String[] items = packageNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.text_add_apps)
                .setItems(items, (dialogInterface, i) -> addSelectedPackage(packageNames.get(i)))
                .setNegativeButton(R.string.text_cancel, null)
                .show();
    }

    private void addSelectedPackage(String packageName) {
        ThreadPoolService.newTask(() -> {
            if (DBManager.getInstance().getAppManager().isExist(packageName)) {
                ToastUtil.showToastThread(getString(R.string.text_empty));
                return;
            }
            AppItem appItem = new AppItem("", packageName, packageName);
            appItem.setPriority(mRvAdapter.getItemCount());
            AppItem saved = DBManager.getInstance().getAppManager().insert(appItem);
            if (saved == null) {
                ToastUtil.showToastThread(getString(R.string.text_update_failed));
                return;
            }
            Message message = new Message();
            message.what = WHAT_ADD;
            message.obj = saved;
            handler.sendMessage(message);
        });
    }

    private void showOperateDialog(AppItem app) {
        if (operateDialog == null) {
            operateDialog = new AppOperateDialog(this);
            operateDialog.setOnButtonClickListener(new AppOperateDialog.OnButtonClickListener() {
                @Override
                public void onPositiveClick(Integer id, String name, String icon, String url) {
                    AppItem appItem = new AppItem(id, icon, name, url);
                    handleOperateDialogConfirm(appItem);
                }

                @Override
                public void onNegativeClick(View view) {
                    operateDialog.hide();
                }
            });
        }
        if (app != null) {
            operateDialog.setData(app);
        }
        operateDialog.show();
    }

    private void handleOperateDialogConfirm(AppItem appItem) {
        ThreadPoolService.newTask(() -> {
            Message message = new Message();
            if (appItem.getId() == null) {
                message.obj = DBManager.getInstance().getAppManager().insert(appItem);
                message.what = WHAT_ADD;
            } else {
                DBManager.getInstance().getAppManager().update(appItem);
                message.obj = appItem;
                message.what = WHAT_UPDATE;
                message.arg1 = selectPosition;
            }
            handler.sendMessage(message);
        });
    }

    private void showQuestionDialog() {
        if (questionDialog == null) {
            questionDialog = QuestionDialog.build(this, this.getString(R.string.text_remove_app), this.getString(R.string.text_remove_app_info));
            questionDialog.setOnButtonClickListener(new QuestionDialog.OnButtonClickListener() {
                @Override
                public void onPositiveClick(View view) {
                    handleQuestionDialogConfirm();
                }

                @Override
                public void onNegativeClick(View view) {
                    questionDialog.hide();
                }
            });
        }
        questionDialog.show();
    }

    private void handleQuestionDialogConfirm() {
        ThreadPoolService.newTask(() -> {
            DBManager.getInstance().getAppManager().delete(selectAppId);
            Message message = new Message();
            message.what = WHAT_DELETE;
            message.arg1 = selectPosition;
            handler.sendMessage(message);
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveAppOrder();
        SharedData.getInstance().put(Constant.KEY_QUICK_ACCESS_ORDER_CHANGE, true).commit();
    }

    private void saveAppOrder() {
        ThreadPoolService.newTask(() -> {
            List<AppItem> appItems = mRvAdapter.setOrder();
            DBManager.getInstance().getAppManager().batchUpdateOrder(appItems);
        });
    }

    ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            final int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            final int swipeFlags = 0;
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            int fromPosition = viewHolder.getAdapterPosition();
            int toPosition = target.getAdapterPosition();
            mRvAdapter.swap(fromPosition, toPosition);
            mRvAdapter.notifyItemMoved(fromPosition, toPosition);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {

        }

        @Override
        public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
            if (viewHolder != null && actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                viewHolder.itemView.setBackgroundResource(R.color.btn_press_bg_color);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setBackgroundResource(R.color.btn_bg_color);
        }
    };
}
