package com.a11y.bingbot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements View.OnClickListener {

    private TextView svcStatus;
    private TextView batStatus;
    private TextView lastStatus;
    private EditText timeInput;
    private CheckBox autoBox;
    private TextView logView;
    private Button saveBtn;
    private Button runBtn;
    private Button clearBtn;
    private Button scanBtn;

    private final Handler ui = new Handler();

    private static final class Poller implements Runnable {
        private final MainActivity act;

        Poller(MainActivity act) {
            this.act = act;
        }

        @Override
        public void run() {
            if (act.logView != null) {
                act.logView.setText(LogStore.dump());
            }
            act.ui.postDelayed(this, 900L);
        }
    }

    private Poller poller;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        poller = new Poller(this);
        setContentView(buildUi());
        refresh();

        // 允许用 adb 直接触发，便于无人值守验证：
        //   adb shell am start -n com.a11y.bingbot/.MainActivity --es mode force
        //   adb shell am start -n com.a11y.bingbot/.MainActivity --ez auto true --ei hour 9 --ei minute 30
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleIntent(it);
    }

    /** 统一处理 adb 传入的参数：mode 立刻跑一次，auto/hour/minute 改定时配置。 */
    private void handleIntent(Intent it) {
        if (it == null) {
            return;
        }
        boolean needArm = false;
        int h = it.getIntExtra("hour", -1);
        int m = it.getIntExtra("minute", -1);
        if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
            Scheduler.setTime(this, h, m);
            LogStore.add(String.format(Locale.US, "时间已设为 %02d:%02d", h, m));
            needArm = true;
        }
        if (it.hasExtra("auto")) {
            Scheduler.setEnabled(this, it.getBooleanExtra("auto", false));
            needArm = true;
        }
        if (needArm) {
            if (Scheduler.enabled(this)) {
                Scheduler.arm(this);
            } else {
                LogStore.add("定时已关闭");
            }
        }
        if (it.getBooleanExtra("reset", false)) {
            Scheduler.prefs(this).edit().remove("lastRunDate").apply();
            LogStore.add("已清除今日执行记录");
        }
        String mode = it.getStringExtra("mode");
        if (mode != null && mode.length() > 0) {
            ui.postDelayed(new Starter(mode), 1200L);
        }
        refresh();
    }

    private static final class Starter implements Runnable {
        private final String mode;

        Starter(String mode) {
            this.mode = mode;
        }

        @Override
        public void run() {
            Engine.get().start(mode);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.postDelayed(poller, 300L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poller);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(12));
        root.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("Bing 积分助手");
        title.setTextSize(20);
        title.setTextColor(0xFF111111);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("本机无障碍自动化 · 不依赖电脑");
        sub.setTextSize(12);
        sub.setTextColor(0xFF888888);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.bottomMargin = dp(14);
        root.addView(sub, subLp);

        svcStatus = statusRow(root, "无障碍服务");
        batStatus = statusRow(root, "电池优化");
        lastStatus = statusRow(root, "执行记录");

        TextView timeLabel = new TextView(this);
        timeLabel.setText("每天自动运行时间（24 小时制）");
        timeLabel.setTextSize(13);
        timeLabel.setTextColor(0xFF444444);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(-1, -2);
        tlLp.topMargin = dp(12);
        root.addView(timeLabel, tlLp);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);

        timeInput = new EditText(this);
        timeInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        timeInput.setTextSize(16);
        timeInput.setGravity(Gravity.CENTER);
        timeRow.addView(timeInput, new LinearLayout.LayoutParams(dp(96), dp(46)));

        saveBtn = new Button(this);
        saveBtn.setText("保存时间");
        saveBtn.setOnClickListener(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-2, -2);
        svLp.leftMargin = dp(10);
        timeRow.addView(saveBtn, svLp);
        root.addView(timeRow);

        autoBox = new CheckBox(this);
        autoBox.setText("启用每日自动运行");
        autoBox.setTextSize(14);
        autoBox.setOnClickListener(this);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(-1, -2);
        abLp.topMargin = dp(8);
        root.addView(autoBox, abLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.topMargin = dp(8);
        root.addView(btnRow, brLp);

        runBtn = new Button(this);
        runBtn.setText("立即运行");
        runBtn.setOnClickListener(this);
        btnRow.addView(runBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        clearBtn = new Button(this);
        clearBtn.setText("清空日志");
        clearBtn.setOnClickListener(this);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(0, -2, 1f);
        clLp.leftMargin = dp(8);
        btnRow.addView(clearBtn, clLp);

        scanBtn = new Button(this);
        scanBtn.setText("扫描当前页面（排错用）");
        scanBtn.setOnClickListener(this);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(6);
        root.addView(scanBtn, scLp);

        TextView tip = new TextView(this);
        tip.setText("提示：到点前请保持手机解锁、屏幕可亮。小米系统需把本应用的省电策略设为「无限制」，并允许自启动。");
        tip.setTextSize(11);
        tip.setTextColor(0xFFAA6633);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.topMargin = dp(10);
        tipLp.bottomMargin = dp(8);
        root.addView(tip, tipLp);

        ScrollView sc = new ScrollView(this);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(0xFF333333);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(0xFFFFFFFF);
        sc.addView(logView, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    private TextView statusRow(LinearLayout parent, String name) {
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(11), dp(12), dp(11));
        tv.setBackgroundColor(0xFFFFFFFF);
        tv.setOnClickListener(this);
        tv.setTag(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        parent.addView(tv, lp);
        return tv;
    }

    @Override
    public void onClick(View v) {
        if (v == svcStatus) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                toast("无法打开无障碍设置");
            }
            return;
        }
        if (v == batStatus) {
            requestIgnoreBattery();
            return;
        }
        if (v == lastStatus) {
            LogStore.add("今天是否已执行: " + (Scheduler.ranToday(this) ? "是" : "否"));
            return;
        }
        if (v == saveBtn) {
            saveTime();
            return;
        }
        if (v == autoBox) {
            Scheduler.setEnabled(this, autoBox.isChecked());
            Scheduler.arm(this);
            refresh();
            return;
        }
        if (v == runBtn) {
            if (BingA11yService.get() == null) {
                toast("请先开启无障碍服务");
                return;
            }
            if (Engine.get().isRunning()) {
                toast("正在运行中");
                return;
            }
            Engine.get().start("manual");
            toast("已开始，可切到必应观察");
            return;
        }
        if (v == clearBtn) {
            LogStore.clear();
            logView.setText("");
            return;
        }
        if (v == scanBtn) {
            scanPage();
        }
    }

    /** 把必应当前界面的控件树导出成文件，便于远程定位选择器。 */
    private void scanPage() {
        BingA11yService svc = BingA11yService.get();
        if (svc == null) {
            toast("请先开启无障碍服务");
            return;
        }
        android.view.accessibility.AccessibilityNodeInfo root = svc.appRoot();
        if (root == null) {
            LogStore.add("扫描失败：必应不在前台，请先打开必应");
            toast("请先把必应切到前台");
            return;
        }
        String head = "扫描 " + Ui.signature(root);
        String tree = Ui.dumpTree(root);
        LogStore.add(head);
        String[] lines = tree.split("\n");
        for (int i = 0; i < Math.min(30, lines.length); i++) {
            LogStore.add("  " + lines[i]);
        }
        if (lines.length > 30) {
            LogStore.add("  ... 共 " + lines.length + " 行，完整内容已写入文件");
        }
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "dump.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(head + "\n\n" + tree);
            w.close();
            LogStore.add("已导出: " + f.getAbsolutePath());
            toast("已导出到应用私有目录");
        } catch (Throwable t) {
            LogStore.add("写文件失败: " + t);
        }
    }

    private void requestIgnoreBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已忽略电池优化，无需再设置");
                return;
            }
            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Throwable t) {
            toast("请到系统设置里手动允许后台运行");
        }
    }

    private void saveTime() {
        String s = timeInput.getText().toString().trim().replace('：', ':');
        String[] parts = s.split(":");
        if (parts.length != 2) {
            toast("格式应为 09:00");
            return;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                throw new NumberFormatException();
            }
            Scheduler.setTime(this, h, m);
            Scheduler.arm(this);
            refresh();
            toast(String.format(Locale.US, "已设为每天 %02d:%02d", h, m));
        } catch (Throwable t) {
            toast("时间不合法");
        }
    }

    private void refresh() {
        boolean on = BingA11yService.get() != null;
        svcStatus.setText("无障碍服务：" + (on ? "已开启 ✓" : "未开启 — 点这里去开启"));
        svcStatus.setTextColor(on ? 0xFF1B7F3B : 0xFFB3261E);

        boolean ign = false;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            ign = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            // 忽略
        }
        batStatus.setText("电池优化：" + (ign ? "已忽略 ✓" : "未忽略 — 点这里设置"));
        batStatus.setTextColor(ign ? 0xFF1B7F3B : 0xFFB3261E);

        String last = Scheduler.prefs(this).getString("lastRunDate", "无");
        lastStatus.setText("执行记录：今日" + (Scheduler.ranToday(this) ? "已执行" : "未执行")
                + "   上次执行日 " + last);
        lastStatus.setTextColor(0xFF333333);

        timeInput.setText(String.format(Locale.US, "%02d:%02d",
                Scheduler.hour(this), Scheduler.minute(this)));
        autoBox.setChecked(Scheduler.enabled(this));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
