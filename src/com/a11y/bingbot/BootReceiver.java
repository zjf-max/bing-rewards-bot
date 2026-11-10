package com.a11y.bingbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LogStore.add("开机/更新广播，重设定时");
        Scheduler.arm(context);
    }
}
