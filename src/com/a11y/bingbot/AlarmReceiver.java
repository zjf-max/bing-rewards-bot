package com.a11y.bingbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.a11y.bingbot.RUN_DAILY";
    public static final String ACTION_RETRY = "com.a11y.bingbot.RETRY";

    private static final int MAX_RETRY = 10;
    private static final long RETRY_DELAY_MS = 30000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        boolean retry = ACTION_RETRY.equals(action);

        LogStore.add(retry ? "定时重试" : "定时闹钟触发");
        if (!retry) {
            Scheduler.arm(context);
        }

        if (Scheduler.ranToday(context)) {
            LogStore.add("今日已执行过，跳过");
            Scheduler.cancelRetry(context);
            Scheduler.setRetryCount(context, 0);
            return;
        }
        if (Engine.get().isRunning()) {
            LogStore.add("引擎正在运行，跳过");
            return;
        }
        if (BingA11yService.get() == null) {
            // 服务被系统杀掉/被强制停止后不会自己回来，靠短周期重试把它等回来。
            // 顺带把主界面拉起来，部分机型会借此重新绑定无障碍服务。
            int n = Scheduler.retryCount(context);
            if (n >= MAX_RETRY) {
                LogStore.add("! 重试 " + MAX_RETRY + " 次仍未连上无障碍，请手动打开一次本应用");
                Scheduler.setRetryCount(context, 0);
                return;
            }
            Scheduler.setRetryCount(context, n + 1);
            LogStore.add("无障碍服务未连接，" + (RETRY_DELAY_MS / 1000) + "s 后第 "
                    + (n + 1) + "/" + MAX_RETRY + " 次重试");
            Scheduler.armRetry(context, RETRY_DELAY_MS);
            try {
                Intent ui = new Intent(context, MainActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(ui);
            } catch (Throwable t) {
                // 后台不允许启动界面时忽略，重试闹钟仍然有效
            }
            return;
        }
        Scheduler.setRetryCount(context, 0);
        Scheduler.cancelRetry(context);
        Engine.get().start("auto");
    }
}
