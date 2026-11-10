package com.a11y.bingbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

public class BingA11yService extends AccessibilityService {

    public static final String PKG = "com.microsoft.bing";
    public static final String MAIN_ACT = "com.microsoft.sapphire.app.main.SapphireMainActivity";

    private static volatile BingA11yService INSTANCE;

    private int sw = 1200;
    private int sh = 2670;
    private Thread ticker;

    public static BingA11yService get() {
        return INSTANCE;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        measure();
        LogStore.add("无障碍服务已连接  屏幕 " + sw + "x" + sh);
        Scheduler.arm(this);
        startTicker();
        // 断连期间如果错过了闹钟，这里补跑（tick 只在设定时间后的 2 小时窗口内动作）
        Scheduler.tick(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 全轮询驱动，不依赖事件回调
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        INSTANCE = null;
        LogStore.add("无障碍服务已断开");
        return super.onUnbind(intent);
    }

    private void measure() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                sw = dm.widthPixels;
                sh = dm.heightPixels;
            }
        } catch (Throwable t) {
            LogStore.add("取屏幕尺寸失败: " + t);
        }
    }

    public int screenW() {
        return sw;
    }

    public int screenH() {
        return sh;
    }

    /** 优先取必应所在窗口的根节点；前台不是必应时返回 null。 */
    public AccessibilityNodeInfo appRoot() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                AccessibilityNodeInfo fallback = null;
                for (int i = 0; i < ws.size(); i++) {
                    AccessibilityWindowInfo w = ws.get(i);
                    if (w == null) {
                        continue;
                    }
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) {
                        continue;
                    }
                    CharSequence p = r.getPackageName();
                    if (p == null || !PKG.contentEquals(p)) {
                        continue;
                    }
                    if (w.isActive() || w.isFocused()) {
                        return r;
                    }
                    if (fallback == null) {
                        fallback = r;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            }
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                CharSequence p = r.getPackageName();
                if (p != null && PKG.contentEquals(p)) {
                    return r;
                }
            }
        } catch (Throwable t) {
            LogStore.add("取窗口失败: " + t);
        }
        return null;
    }

    public boolean inBing() {
        return appRoot() != null;
    }

    public boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(clampX(x), clampY(y));
            GestureDescription.StrokeDescription st =
                    new GestureDescription.StrokeDescription(p, 0, 55);
            GestureDescription g = new GestureDescription.Builder().addStroke(st).build();
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            LogStore.add("点击失败: " + t);
            return false;
        }
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        try {
            Path p = new Path();
            p.moveTo(clampX(x1), clampY(y1));
            p.lineTo(clampX(x2), clampY(y2));
            GestureDescription.StrokeDescription st =
                    new GestureDescription.StrokeDescription(p, 0, Math.max(60, ms));
            GestureDescription g = new GestureDescription.Builder().addStroke(st).build();
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            LogStore.add("滑动失败: " + t);
            return false;
        }
    }

    private int clampX(int x) {
        return Math.max(1, Math.min(sw - 2, x));
    }

    private int clampY(int y) {
        return Math.max(1, Math.min(sh - 2, y));
    }

    /** 点节点中心；坐标不可用时退回 ACTION_CLICK。 */
    public boolean click(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        try {
            Rect r = Ui.rect(n);
            if (r.width() > 0 && r.height() > 0 && r.top >= 0 && r.bottom <= sh + 4) {
                return tap(r.centerX(), r.centerY());
            }
            return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            LogStore.add("点击节点失败: " + t);
            return false;
        }
    }

    public void back() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void swipeUp() {
        int x = sw / 2 + (int) (Math.random() * 120) - 60;
        swipe(x, (int) (sh * 0.74), x, (int) (sh * 0.34), 400);
    }

    public void swipeSmall() {
        int x = sw / 2 + (int) (Math.random() * 140) - 70;
        swipe(x, (int) (sh * 0.78), x, (int) (sh * 0.46), 340);
    }

    /** 触发输入法的「回车/搜索」动作。常量在 AccessibilityAction 上，不在节点类上。 */
    public boolean imeEnter(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        try {
            int id = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
            return n.performAction(id);
        } catch (Throwable t) {
            LogStore.add("IME 回车失败: " + t);
            return false;
        }
    }

    public boolean isUnlocked() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && !km.isKeyguardLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 把所有窗口（含输入法窗口）的控件树打到日志，排查点击目标用。 */
    public void dumpWindows(int maxLinesEach) {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            LogStore.add("窗口数: " + (ws == null ? 0 : ws.size()));
            if (ws == null) {
                return;
            }
            for (int i = 0; i < ws.size(); i++) {
                AccessibilityWindowInfo w = ws.get(i);
                if (w == null) {
                    continue;
                }
                AccessibilityNodeInfo r = w.getRoot();
                String pkg = "?";
                if (r != null && r.getPackageName() != null) {
                    pkg = r.getPackageName().toString();
                }
                LogStore.add("-- 窗口" + i + " type=" + w.getType() + " active=" + w.isActive()
                        + " focused=" + w.isFocused() + " pkg=" + pkg);
                if (r == null) {
                    continue;
                }
                String[] lines = Ui.dumpTree(r).split("\n");
                for (int k = 0; k < Math.min(maxLinesEach, lines.length); k++) {
                    LogStore.add("   " + lines[k]);
                }
            }
        } catch (Throwable t) {
            LogStore.add("dumpWindows 失败: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    public void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            if (pm.isInteractive()) {
                return;
            }
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE, "bingbot:run");
            wl.acquire(10 * 60 * 1000L);
            LogStore.add("已尝试点亮屏幕");
        } catch (Throwable t) {
            LogStore.add("点屏失败: " + t);
        }
    }

    public boolean launchBing() {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.setClassName(PKG, MAIN_ACT);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            startActivity(it);
            return true;
        } catch (Throwable t) {
            LogStore.add("启动必应失败: " + t);
            try {
                Intent it = getPackageManager().getLaunchIntentForPackage(PKG);
                if (it != null) {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                    return true;
                }
            } catch (Throwable t2) {
                LogStore.add("兜底启动也失败: " + t2);
            }
            return false;
        }
    }

    private static final class Ticker implements Runnable {
        private final BingA11yService svc;

        Ticker(BingA11yService svc) {
            this.svc = svc;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(30000L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    Scheduler.tick(svc);
                } catch (Throwable t) {
                    LogStore.add("定时检查异常: " + t);
                }
            }
        }
    }

    /** 轮询兜底：闹钟被系统吞掉时也能在窗口期内补跑。 */
    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = new Thread(new Ticker(this), "bing-ticker");
        ticker.setDaemon(true);
        ticker.start();
    }
}
