package com.a11y.bingbot;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** 进程内日志缓冲：界面轮询显示，同时打到 logcat 便于 adb 远程排错。 */
public final class LogStore {
    public static final String TAG = "BingBot";
    private static final int MAX = 800;
    private static final LinkedList<String> LINES = new LinkedList<String>();
    private static volatile String lastLine = "";

    private LogStore() {}

    public static void add(String s) {
        String line;
        synchronized (LINES) {
            line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + s;
            LINES.add(line);
            while (LINES.size() > MAX) {
                LINES.removeFirst();
            }
            lastLine = line;
        }
        Log.i(TAG, s);
    }

    public static String dump() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            for (String s : LINES) {
                sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    public static String last() {
        return lastLine;
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
            lastLine = "";
        }
    }
}
