package com.a11y.bingbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class Scheduler {

    public static final String PREF = "bingbot";
    private static final String K_ENABLED = "enabled";
    private static final String K_HOUR = "hour";
    private static final String K_MINUTE = "minute";
    private static final String K_LAST = "lastRunDate";
    private static final String K_DWELL_MIN = "dwellMin";
    private static final String K_DWELL_MAX = "dwellMax";

    private Scheduler() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_ENABLED, v).apply();
    }

    public static int hour(Context c) {
        return prefs(c).getInt(K_HOUR, 9);
    }

    public static int minute(Context c) {
        return prefs(c).getInt(K_MINUTE, 0);
    }

    public static void setTime(Context c, int h, int m) {
        prefs(c).edit().putInt(K_HOUR, h).putInt(K_MINUTE, m).apply();
    }

    public static int dwellMin(Context c) {
        return prefs(c).getInt(K_DWELL_MIN, 5);
    }

    public static int dwellMax(Context c) {
        return prefs(c).getInt(K_DWELL_MAX, 8);
    }

    public static void setDwell(Context c, int lo, int hi) {
        prefs(c).edit().putInt(K_DWELL_MIN, lo).putInt(K_DWELL_MAX, hi).apply();
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static void markRunDone(Context c) {
        prefs(c).edit().putString(K_LAST, today()).apply();
    }

    public static boolean ranToday(Context c) {
        return today().equals(prefs(c).getString(K_LAST, ""));
    }

    private static PendingIntent pi(Context c) {
        Intent it = new Intent(c, AlarmReceiver.class);
        it.setAction("com.a11y.bingbot.RUN_DAILY");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, 1001, it, flags);
    }

    private static PendingIntent retryPi(Context c) {
        Intent it = new Intent(c, AlarmReceiver.class);
        it.setAction(AlarmReceiver.ACTION_RETRY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, 1002, it, flags);
    }

    /** 无障碍服务没连上时的短周期重试，用普通闹钟即可，不需要精确。 */
    public static void armRetry(Context c, long delayMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent p = retryPi(c);
        am.cancel(p);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + delayMs, p);
    }

    public static void cancelRetry(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(retryPi(c));
    }

    public static int retryCount(Context c) {
        return prefs(c).getInt("retryCount", 0);
    }

    public static void setRetryCount(Context c, int v) {
        prefs(c).edit().putInt("retryCount", v).apply();
    }

    public static long nextTriggerMillis(Context c) {
        Calendar now = Calendar.getInstance();
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour(c));
        at.set(Calendar.MINUTE, minute(c));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (!at.after(now)) {
            at.add(Calendar.DAY_OF_YEAR, 1);
        }
        return at.getTimeInMillis();
    }

    public static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent p = pi(c);
        am.cancel(p);
        if (!enabled(c)) {
            LogStore.add("定时已关闭");
            return;
        }
        long at = nextTriggerMillis(c);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            LogStore.add("定时已设置: " + fmt(at) + " (精确)");
        } catch (Throwable t) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
                LogStore.add("定时已设置: " + fmt(at) + " (窗口)");
            } catch (Throwable t2) {
                am.set(AlarmManager.RTC_WAKEUP, at, p);
                LogStore.add("定时已设置: " + fmt(at) + " (普通)");
            }
        }
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    /** 服务内的兜底轮询，每 30s 一次；只在一个时间窗内补跑，避免错过闹钟就彻底不跑。 */
    public static void tick(Context c) {
        if (!enabled(c)) {
            return;
        }
        Engine e = Engine.get();
        if (e.isRunning()) {
            return;
        }
        if (ranToday(c)) {
            return;
        }
        if (System.currentTimeMillis() < e.nextRetryAt) {
            return;
        }
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int target = hour(c) * 60 + minute(c);
        if (nowMin >= target && nowMin <= target + 120) {
            LogStore.add("进入定时窗口，开始执行");
            e.start("auto");
        }
    }
}
