package com.a11y.bingbot;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动化主引擎。逻辑与已验证的 PC 版一致，只是把「adb dump + input tap」
 * 换成了「无障碍节点树 + 手势派发」。
 */
public class Engine {

    private static final Engine INSTANCE = new Engine();

    public static Engine get() {
        return INSTANCE;
    }

    private static final Pattern META =
            Pattern.compile("^\\s*\\d+\\s*(小时|分钟|天|分|秒)\\s*$");
    private static final Pattern TOTAL = Pattern.compile("^\\d+/\\d+$");
    private static final Pattern P_EARNED =
            Pattern.compile("已赚取(?:的)?\\s*(\\d+)\\s*积分");
    private static final Pattern P_NEED =
            Pattern.compile("需要\\s*(\\d+)\\s*积分");

    private static final String[] AD_DOMAINS = {
            "1688.com", "taobao.com", "tmall.com", "jd.com", "aliyun.com",
            "alibaba.com", "pinduoduo", "xiaomiyoupin", "s.click", "tmall.hk"
    };

    private static final String[] SUBMIT_NAMES = {
            "输入法右下角", "输入法右下角(偏下)", "输入法回车", "页面搜索按钮", "系统深链"
    };

    private final Random rnd = new Random();
    private final Set<String> readDone = new HashSet<String>();
    private volatile boolean stop;
    private volatile boolean running;
    public volatile long nextRetryAt = 0L;

    private int dwellMin = 5;
    private int dwellMax = 8;

    private boolean didAny;
    private int rewardsDone;
    private boolean sawAllDone;

    private Engine() {}

    public boolean isRunning() {
        return running;
    }

    /** 具名内部类：本项目刻意不使用匿名类（打包工具的匿名类解析有缺陷）。 */
    private static final class Worker implements Runnable {
        private final Engine engine;
        private final String mode;

        Worker(Engine engine, String mode) {
            this.engine = engine;
            this.mode = mode;
        }

        @Override
        public void run() {
            BingA11yService svc = BingA11yService.get();
            try {
                engine.runInternal(mode);
            } catch (Throwable t) {
                LogStore.add("异常终止: " + android.util.Log.getStackTraceString(t));
            } finally {
                engine.running = false;
                if (svc != null && (engine.didAny || engine.sawAllDone)) {
                    Scheduler.markRunDone(svc);
                    LogStore.add("今日执行记录已写入，定时任务今天不再重复");
                }
                LogStore.add("=== 结束 ===");
            }
        }
    }

    public synchronized boolean start(final String mode) {
        if (running) {
            LogStore.add("任务正在运行，忽略本次请求");
            return false;
        }
        running = true;
        stop = false;
        didAny = false;
        sawAllDone = false;
        rewardsDone = 0;
        Thread th = new Thread(new Worker(this, mode), "bing-engine");
        th.setDaemon(true);
        th.start();
        return true;
    }

    public void stop() {
        stop = true;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // 忽略
        }
    }

    // ------------------------------------------------------------ 主流程

    private void runInternal(String mode) {
        BingA11yService svc = BingA11yService.get();
        if (svc == null) {
            LogStore.add("无障碍服务未连接，无法执行");
            return;
        }
        LogStore.add("=== 开始 (模式=" + mode + ") ===");
        if ("dump".equals(mode)) {
            svc.dumpWindows(40);
            LogStore.add("appRoot: " + Ui.signature(svc.appRoot()));
            return;
        }
        svc.wakeScreen();
        sleep(900);

        if (!svc.isUnlocked()) {
            LogStore.add("! 手机锁屏中，无法点击。解锁后会自动重试（5 分钟后）");
            nextRetryAt = System.currentTimeMillis() + 5 * 60 * 1000L;
            return;
        }

        if (!svc.launchBing()) {
            LogStore.add("! 无法启动必应");
            return;
        }
        sleep(2600);

        dwellMin = Scheduler.dwellMin(svc);
        dwellMax = Scheduler.dwellMax(svc);

        boolean force = "force".equals(mode);
        boolean onlyRewards = "rewards".equals(mode);

        Prog before = null;
        if (!force) {
            before = doRewardsTasks(svc);
            if (onlyRewards) {
                LogStore.add("=== 仅签到/活动模式，结束 ===");
                return;
            }
        }
        if (before == null) {
            before = readProgress(svc);
        }
        if (before != null) {
            LogStore.add("当前进度: " + before);
            if (before.allDone && !force) {
                LogStore.add("今日任务已全部完成，无需执行");
                sawAllDone = true;
                return;
            }
        }

        int nSearch = 5;
        int nRead = 10;
        int readCur = 0;
        if (force) {
            nSearch = 1;
            nRead = 1;
        } else if (before != null) {
            if (before.sMax > 0 && before.sCur >= 0) {
                nSearch = Math.max(0, (before.sMax - before.sCur + 2) / 3);
            }
            if (before.rMax > 0 && before.rCur >= 0) {
                nRead = Math.max(0, (before.rMax - before.rCur + 2) / 3);
                readCur = before.rCur;
            }
        }
        LogStore.add("计划: 搜索 " + nSearch + " 次, 阅读 " + nRead + " 篇");

        int okS = 0;
        for (int i = 0; i < nSearch; i++) {
            if (stop) {
                break;
            }
            String kw = Keywords.next();
            LogStore.add("搜索 " + (i + 1) + "/" + nSearch + ": " + kw);
            long t0 = SystemClock.elapsedRealtime();
            if (doSearch(svc, kw)) {
                okS++;
                didAny = true;
                LogStore.add("  用时 " + (SystemClock.elapsedRealtime() - t0) / 1000.0 + "s");
            }
            sleep(700 + rnd.nextInt(900));
        }

        int okR = 0;
        for (int i = 0; i < nRead; i++) {
            if (stop) {
                break;
            }
            LogStore.add("阅读 " + (i + 1) + "/" + nRead);
            if (doRead(svc)) {
                okR++;
                didAny = true;
            }
            sleep(700 + rnd.nextInt(1200));

            if (i == 0 && !force) {
                Prog p = readProgress(svc);
                if (p != null) {
                    int delta = p.rCur - readCur;
                    if (delta > 0) {
                        LogStore.add("  ✓ 短停留已结算 (+" + delta + ")，维持快节奏");
                    } else {
                        LogStore.add("  ! 短停留未结算，切成长停留 30-44s");
                        dwellMin = 30;
                        dwellMax = 44;
                    }
                }
            }
        }

        LogStore.add("=== 结束：搜索 " + okS + "/" + nSearch + ", 阅读 " + okR + "/" + nRead
                + ", 奖励任务 " + rewardsDone + " 项 ===");
        Prog after = readProgress(svc);
        if (after != null) {
            LogStore.add("最终进度: " + after);
            if (after.allDone) {
                sawAllDone = true;
            }
        }
    }

    // ------------------------------------------------------------ 状态识别

    private String pageKind(AccessibilityNodeInfo root) {
        if (root == null) {
            return "none";
        }
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        boolean web = Ui.hasWebView(ns);
        boolean edit = false;
        boolean box = false;
        boolean panel = false;
        boolean rewards = false;
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            if (Ui.editable(n)) {
                edit = true;
            }
            if (Ui.id(n).endsWith("sa_search_box")) {
                box = true;
            }
            String blob = Ui.t(n) + "|" + Ui.d(n);
            if (blob.contains("搜索以赚取") || blob.contains("阅读以赚取")) {
                rewards = true;
            }
            String t = Ui.t(n);
            if ("搜索资讯".equals(t)) {
                return "article";
            }
            if ("所有应用".equals(t)) {
                panel = true;
            }
        }
        if (rewards) {
            return "rewards";
        }
        if (edit) {
            return "search";
        }
        // 首页一定有搜索框；只有「所有应用」没有搜索框的是应用面板
        if (box) {
            return "home";
        }
        if (panel) {
            return "panel";
        }
        if (web) {
            return "web";
        }
        return "other";
    }

    private String kindOf(BingA11yService svc) {
        return pageKind(svc.appRoot());
    }

    /** 回必应首页：只按返回键，绝不按到桌面去碰别的应用。 */
    private boolean ensureHome(BingA11yService svc) {
        for (int i = 0; i < 12; i++) {
            if (stop) {
                return false;
            }
            AccessibilityNodeInfo root = svc.appRoot();
            if (root == null) {
                svc.launchBing();
                sleep(1800);
                continue;
            }
            String k = pageKind(root);
            if ("home".equals(k)) {
                return true;
            }
            svc.back();
            sleep(850);
        }
        return false;
    }

    private AccessibilityNodeInfo findSearchBox(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            if (!Ui.id(n).endsWith("sa_search_box")) {
                continue;
            }
            Rect r = Ui.rect(n);
            if (r.width() <= 0 || r.height() <= 0) {
                continue;
            }
            if (r.top < bestTop) {
                bestTop = r.top;
                best = n;
            }
        }
        return best;
    }

    private boolean hasSearchBox(List<AccessibilityNodeInfo> ns) {
        for (int i = 0; i < ns.size(); i++) {
            if (Ui.id(ns.get(i)).endsWith("sa_search_box")) {
                return true;
            }
        }
        return false;
    }

    /** 信息流滚动后搜索框会跑出屏幕，先滚回顶部再取；坐标无效就视为不可用。 */
    private AccessibilityNodeInfo ensureSearchBox(BingA11yService svc) {
        for (int i = 0; i < 5; i++) {
            AccessibilityNodeInfo box = findSearchBox(svc.appRoot());
            if (box != null) {
                Rect r = Ui.rect(box);
                if (r.width() > 0 && r.height() > 0 && r.top >= 0 && r.bottom <= svc.screenH()) {
                    return box;
                }
            }
            AccessibilityNodeInfo sv = findNodeByIdSuffix(svc.appRoot(), "sa_home_scroll_view");
            if (sv == null) {
                break;
            }
            boolean ok = sv.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (!ok) {
                break;
            }
            sleep(500);
        }
        return findSearchBox(svc.appRoot());
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        for (int i = 0; i < ns.size(); i++) {
            if (Ui.editable(ns.get(i))) {
                return ns.get(i);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo waitEditable(BingA11yService svc, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (stop) {
                return null;
            }
            AccessibilityNodeInfo e = findEditable(svc.appRoot());
            if (e != null) {
                return e;
            }
            sleep(350);
        }
        return null;
    }

    // ------------------------------------------------------------ 搜索

    private boolean doSearch(BingA11yService svc, String kw) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (stop) {
                return false;
            }
            if (!ensureHome(svc)) {
                LogStore.add("  ! 回不到首页");
                continue;
            }
            AccessibilityNodeInfo box = ensureSearchBox(svc);
            if (box == null) {
                LogStore.add("  ! 找不到搜索框  " + Ui.signature(svc.appRoot()));
                continue;
            }
            Rect br = Ui.rect(box);
            svc.tap(br.centerX(), br.centerY());
            sleep(1200);

            AccessibilityNodeInfo edit = waitEditable(svc, 4500);
            if (edit == null) {
                LogStore.add("  ! 没等到输入框  (" + Ui.signature(svc.appRoot()) + ")");
                continue;
            }
            // 关键：必须先用真实点击让输入法挂到这个输入框上。
            // 只做 ACTION_SET_TEXT 时输入法没有 servedView，回车不会生效。
            Rect er = Ui.rect(edit);
            if (er.width() > 0 && er.height() > 0) {
                svc.tap(er.centerX(), er.centerY());
                sleep(900);
                edit = findEditable(svc.appRoot());
            }
            if (edit == null) {
                LogStore.add("  ! 点击后输入框消失");
                continue;
            }
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            sleep(200);
            if (!setText(edit, kw)) {
                LogStore.add("  ! 写入文本失败");
                continue;
            }
            sleep(500);

            String got = readEditText(svc);
            if (got == null || !norm(got).contains(norm(kw))) {
                LogStore.add("  ! 文本校验失败 实际=" + got);
                continue;
            }

            if (!submitAndWait(svc, kw)) {
                LogStore.add("  ! 提交后仍未进入结果页  (" + Ui.signature(svc.appRoot()) + ")");
                continue;
            }
            LogStore.add("  ✓ 搜索成功: " + kw);
            return true;
        }
        LogStore.add("  ! 搜索连续失败，导出窗口结构用于定位");
        svc.dumpWindows(60);
        return false;
    }

    private String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.US).replace(" ", "").replace("\u00a0", "");
    }

    private boolean setText(AccessibilityNodeInfo n, String text) {
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Throwable t) {
            LogStore.add("  setText 异常: " + t);
            return false;
        }
    }

    private String readEditText(BingA11yService svc) {
        AccessibilityNodeInfo e = findEditable(svc.appRoot());
        if (e == null) {
            return null;
        }
        return Ui.t(e);
    }

    private boolean submitAndWait(BingA11yService svc, String kw) {
        for (int s = 0; s < SUBMIT_NAMES.length; s++) {
            if (stop) {
                return false;
            }
            if (s == 4) {
                if (!openSearchLink(svc, kw)) {
                    continue;
                }
                if (waitResult(svc, 4500)) {
                    LogStore.add("  提交方式: 系统深链");
                    return true;
                }
                continue;
            }
            if (!submitOnce(svc, s)) {
                continue;
            }
            if (waitResult(svc, 3300)) {
                LogStore.add("  提交方式: " + SUBMIT_NAMES[s]);
                return true;
            }
        }
        return false;
    }

    /** 微软自家 scheme，直接落到 BrowserActivity 结果页，完全不经过输入法。 */
    private boolean openSearchLink(BingA11yService svc, String kw) {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            it.setData(android.net.Uri.parse("bing://search?q=" + android.net.Uri.encode(kw)));
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(it);
            LogStore.add("  已用深链发起搜索");
            sleep(900);
            return true;
        } catch (Throwable t) {
            LogStore.add("  深链失败: " + t);
            return false;
        }
    }

    /** 输入法窗口范围：微信输入法把整块键盘做成一个节点，只能按坐标点。 */
    private Rect imeBounds(BingA11yService svc) {
        try {
            List<AccessibilityWindowInfo> ws = svc.getWindows();
            if (ws == null) {
                return null;
            }
            for (int i = 0; i < ws.size(); i++) {
                AccessibilityWindowInfo w = ws.get(i);
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect b = new Rect();
                    w.getBoundsInScreen(b);
                    if (b.width() > 0 && b.height() > 0) {
                        return b;
                    }
                }
            }
        } catch (Throwable t) {
            // 忽略
        }
        return null;
    }

    private boolean submitOnce(BingA11yService svc, int strategy) {
        if (strategy == 2) {
            AccessibilityNodeInfo e = findEditable(svc.appRoot());
            if (e == null) {
                return false;
            }
            e.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return svc.imeEnter(e);
        }
        if (strategy == 0 || strategy == 1) {
            Rect b = imeBounds(svc);
            if (b == null) {
                return false;
            }
            int x = b.right - (int) (b.width() * 0.06);
            int y = strategy == 0
                    ? b.bottom - (int) (b.height() * 0.105)
                    : b.bottom - (int) (b.height() * 0.075);
            LogStore.add("  点输入法右下角 (" + x + "," + y + ")");
            svc.tap(x, y);
            return true;
        }
        AccessibilityNodeInfo root = svc.appRoot();
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            if (!Ui.clickable(n)) {
                continue;
            }
            String s = Ui.t(n) + "|" + Ui.d(n);
            if ("搜索".equals(s) || "搜索|".equals(s) || "|搜索".equals(s)
                    || "Search".equalsIgnoreCase(s)) {
                svc.click(n);
                return true;
            }
        }
        return false;
    }

    private boolean waitResult(BingA11yService svc, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (stop) {
                return false;
            }
            AccessibilityNodeInfo root = svc.appRoot();
            if (root != null) {
                List<AccessibilityNodeInfo> ns = Ui.all(root);
                if (Ui.hasWebView(ns)) {
                    sleep(900);
                    return true;
                }
                boolean edit = false;
                for (int i = 0; i < ns.size(); i++) {
                    if (Ui.editable(ns.get(i))) {
                        edit = true;
                        break;
                    }
                }
                if (!edit && !hasSearchBox(ns)) {
                    sleep(700);
                    return true;
                }
            }
            sleep(420);
        }
        return false;
    }

    // ------------------------------------------------------------ 阅读

    private boolean doRead(BingA11yService svc) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (stop) {
                return false;
            }
            if (!ensureHome(svc)) {
                LogStore.add("  ! 回不到信息流");
                continue;
            }
            ensureFeedExpanded(svc);

            AccessibilityNodeInfo card = null;
            for (int k = 0; k < 4 && card == null; k++) {
                card = pickClickableCard(pickNewsCards(svc));
                if (card == null) {
                    LogStore.add("  ! 当前屏无可用卡片，继续下滑");
                    svc.swipeUp();
                    sleep(1100);
                }
            }
            if (card == null) {
                continue;
            }

            String title = Ui.d(card);
            long t0 = SystemClock.elapsedRealtime();
            svc.click(card);
            if (!waitArticle(svc, 6500)) {
                LogStore.add("  x 未进入文章页 (" + kindOf(svc) + ")  " + Ui.signature(svc.appRoot()));
                svc.back();
                sleep(1100);
                continue;
            }
            long openMs = SystemClock.elapsedRealtime() - t0;

            if (isAdPage(Ui.all(svc.appRoot()))) {
                LogStore.add("  x 命中广告页，返回重试");
                svc.back();
                sleep(1100);
                continue;
            }
            readDone.add(title);

            int dwell = dwellMin + rnd.nextInt(Math.max(1, dwellMax - dwellMin + 1));
            LogStore.add("  阅读 " + dwell + "s 「" + shortTitle(title) + "」");
            long end = SystemClock.elapsedRealtime() + dwell * 1000L;
            while (SystemClock.elapsedRealtime() < end) {
                sleep(1200 + rnd.nextInt(1100));
                svc.swipeSmall();
            }
            svc.back();
            sleep(1200);
            svc.swipeUp();
            sleep(650);
            LogStore.add("  ✓ 完成一篇（打开 " + (openMs / 1000.0) + "s + 停留 " + dwell + "s）");
            return true;
        }
        return false;
    }

    private String shortTitle(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 18 ? t.substring(0, 18) : t;
    }

    private boolean waitArticle(BingA11yService svc, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (stop) {
                return false;
            }
            AccessibilityNodeInfo root = svc.appRoot();
            if (root != null) {
                List<AccessibilityNodeInfo> ns = Ui.all(root);
                if (Ui.hasText(ns, "搜索资讯")) {
                    sleep(500);
                    return true;
                }
                if (Ui.hasWebView(ns) && !hasSearchBox(ns)) {
                    sleep(500);
                    return true;
                }
            }
            sleep(400);
        }
        return false;
    }

    private boolean isAdPage(List<AccessibilityNodeInfo> ns) {
        for (int i = 0; i < ns.size(); i++) {
            String s = (Ui.t(ns.get(i)) + " " + Ui.d(ns.get(i))).toLowerCase(Locale.US);
            if (s.length() < 4) {
                continue;
            }
            for (int k = 0; k < AD_DOMAINS.length; k++) {
                if (s.contains(AD_DOMAINS[k])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 新闻卡识别：按文档顺序扫描兄弟序列，不依赖坐标。
     * WebView 里真新闻块是 [可点标题] [来源] [时间] ...，广告块是 [可点标题] [广告] [广告选项]，
     * 时间文本只在真新闻里出现——用这一点区分，比看坐标和关键词都稳。
     */
    private List<AccessibilityNodeInfo> pickNewsCards(BingA11yService svc) {
        List<AccessibilityNodeInfo> dfs = Ui.allDfs(svc.appRoot());
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        for (int i = 0; i < dfs.size(); i++) {
            AccessibilityNodeInfo n = dfs.get(i);
            if (!Ui.clickable(n)) {
                continue;
            }
            String d = Ui.d(n);
            if (d.length() < 12 || d.contains("广告")) {
                continue;
            }
            if (readDone.contains(d)) {
                continue;
            }
            boolean news = false;
            boolean ad = false;
            int limit = Math.min(dfs.size(), i + 18);
            for (int j = i + 1; j < limit; j++) {
                AccessibilityNodeInfo m = dfs.get(j);
                if (Ui.clickable(m) && Ui.d(m).length() >= 12) {
                    break;
                }
                if (Ui.d(m).contains("广告选项") || "广告".equals(Ui.t(m))) {
                    ad = true;
                    break;
                }
                if (META.matcher(Ui.t(m).trim()).matches()) {
                    news = true;
                    break;
                }
            }
            if (news && !ad) {
                out.add(n);
            }
        }
        return out;
    }

    /** 只挑真正有屏幕坐标的卡（WebView 里未渲染的节点坐标全是 0）。 */
    private AccessibilityNodeInfo pickClickableCard(List<AccessibilityNodeInfo> cards) {
        List<AccessibilityNodeInfo> visible = new ArrayList<AccessibilityNodeInfo>();
        for (int i = 0; i < cards.size(); i++) {
            Rect r = Ui.rect(cards.get(i));
            if (r.width() >= 200 && r.height() >= 40) {
                visible.add(cards.get(i));
            }
        }
        if (visible.isEmpty()) {
            return null;
        }
        return visible.get(rnd.nextInt(Math.min(2, visible.size())));
    }

    /** 首页信息流默认只占底部一条，先上滑把它展开，卡片才会正常渲染坐标。 */
    private void ensureFeedExpanded(BingA11yService svc) {
        int h = svc.screenH();
        int x = svc.screenW() / 2;
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo feed = findNodeByIdSuffix(svc.appRoot(), "sa_hp_feed_container");
            if (feed != null) {
                Rect r = Ui.rect(feed);
                if (r.height() > h * 0.42) {
                    return;
                }
            }
            svc.swipe(x, (int) (h * 0.90), x, (int) (h * 0.78), 380);
            sleep(750);
        }
    }

    private AccessibilityNodeInfo findNodeByIdSuffix(AccessibilityNodeInfo root, String suffix) {
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        for (int i = 0; i < ns.size(); i++) {
            if (Ui.id(ns.get(i)).endsWith(suffix)) {
                return ns.get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 进度

    static class Prog {
        int sCur = -1;
        int sMax = -1;
        int rCur = -1;
        int rMax = -1;
        int tCur = -1;
        int tMax = -1;
        boolean allDone;

        @Override
        public String toString() {
            return "搜索 " + sCur + "/" + sMax + "  阅读 " + rCur + "/" + rMax
                    + "  今日 " + tCur + "/" + tMax + (allDone ? "  [已全满]" : "");
        }
    }

    private Integer firstInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (m.find()) {
            try {
                return Integer.valueOf(Integer.parseInt(m.group(1)));
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private Prog readProgress(BingA11yService svc) {
        if (!ensureHome(svc)) {
            LogStore.add("  ! 进度：回不到首页");
            return null;
        }
        // 首页上就可能直接有 Rewards 入口；没有再去底部「应用」面板找
        AccessibilityNodeInfo rw = Ui.findByTextContains(Ui.all(svc.appRoot()), "Rewards");
        if (rw == null) {
            AccessibilityNodeInfo grid = findGridEntry(svc);
            if (grid == null) {
                LogStore.add("  ! 进度：找不到应用入口  " + Ui.signature(svc.appRoot()));
                return null;
            }
            svc.click(grid);
            sleep(1300);
            for (int i = 0; i < 5 && rw == null; i++) {
                if (stop) {
                    return null;
                }
                rw = Ui.findByTextContains(Ui.all(svc.appRoot()), "Rewards");
                if (rw == null) {
                    rw = Ui.findByTextContains(Ui.all(svc.appRoot()), "积分");
                }
                if (rw == null) {
                    sleep(600);
                }
            }
        }
        if (rw == null) {
            LogStore.add("  ! 进度：面板里找不到 Rewards  " + Ui.signature(svc.appRoot()));
            svc.back();
            sleep(900);
            return null;
        }
        svc.click(rw);
        sleep(2600);

        Prog p = parseRewards(svc);
        svc.back();
        sleep(1100);
        if (p != null) {
            saveKnown(p, svc);
        }
        return p;
    }

    // ------------------------------------------------------------ 签到 / 每日活动

    private static final Pattern P_DAY_LABEL = Pattern.compile("^Day\\s*\\d+$");
    private static final Pattern P_PURE_NUM = Pattern.compile("^\\d{1,3}$");

    /** 奖励页特征：有「今日积分」标题，或存在「搜索以赚取」卡片。 */
    private boolean isRewardsPage(BingA11yService svc) {
        AccessibilityNodeInfo root = svc.appRoot();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            String d = Ui.d(n);
            // 「拼图进度: N 块」只出现在奖励页；面板上的「今日积分」不算
            if (d.contains("拼图进度") || d.contains("搜索以赚取") || d.contains("阅读以赚取")) {
                return true;
            }
            if ("签入".equals(Ui.t(n).trim())) {
                return true;
            }
        }
        return false;
    }

    /** 点完之后页面是异步渲染的，轮询等它出来。 */
    private boolean waitRewards(BingA11yService svc, int rounds) {
        for (int i = 0; i < rounds; i++) {
            sleep(800);
            if (isRewardsPage(svc)) {
                return true;
            }
        }
        return false;
    }

    /** 进入 Rewards 页并停在页面上。优先走「应用面板」这条已验证过的路径。 */
    private boolean openRewards(BingA11yService svc) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (stop) {
                return false;
            }
            if (isRewardsPage(svc)) {
                return true;
            }
            if (!ensureHome(svc)) {
                LogStore.add("  ! 奖励页: 回不到首页 " + Ui.signature(svc.appRoot()));
                return false;
            }
            // 面板路径：底部「应用」网格 → Rewards 图标
            AccessibilityNodeInfo grid = findGridEntry(svc);
            if (grid == null) {
                LogStore.add("  ! 奖励页: 找不到应用面板入口 " + Ui.signature(svc.appRoot()));
            } else {
                svc.click(grid);
                sleep(1500);
                List<AccessibilityNodeInfo> pns = Ui.all(svc.appRoot());
                // 磁贴是带 desc 的可点击 LinearLayout；里面的文字标签点了会走浏览器
                AccessibilityNodeInfo pin = Ui.findByDescContains(pns, "Rewards");
                if (pin == null) {
                    pin = Ui.findByTextContains(pns, "Rewards");
                }
                if (pin != null) {
                    Rect pr = Ui.rect(pin);
                    LogStore.add("  点 Rewards 磁贴 (" + (pr == null ? "?" : pr.centerX() + "," + pr.centerY()) + ")");
                    svc.click(pin);
                    if (waitRewards(svc, 6)) {
                        return true;
                    }
                    LogStore.add("  ! 点了 Rewards 但没进页面 " + Ui.signature(svc.appRoot()));
                } else {
                    LogStore.add("  ! 面板里没有 Rewards 图标 " + Ui.signature(svc.appRoot()));
                }
                svc.back();
                sleep(900);
            }
            // 备用路径：个人资料 → Microsoft Rewards
            AccessibilityNodeInfo root = svc.appRoot();
            AccessibilityNodeInfo prof = root == null ? null : Ui.findByDescContains(Ui.all(root), "个人资料");
            if (prof != null) {
                svc.click(prof);
                sleep(1700);
                AccessibilityNodeInfo mr = Ui.findByTextContains(Ui.all(svc.appRoot()), "Microsoft Rewards");
                if (mr != null) {
                    svc.click(mr);
                    if (waitRewards(svc, 6)) {
                        return true;
                    }
                } else {
                    LogStore.add("  ! 个人资料里没有 Microsoft Rewards " + Ui.signature(svc.appRoot()));
                }
                svc.back();
                sleep(900);
            }
        }
        return false;
    }

    /**
     * 签到。三张连击卡（签入/搜索/每日活动）的圆点 x 坐标完全一样，
     * 所以只能靠「Day N」标签定位当天那一格，再往正上方点圆圈。
     */
    private boolean doCheckIn(BingA11yService svc) {
        AccessibilityNodeInfo root = svc.appRoot();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        AccessibilityNodeInfo day1 = null;
        for (int i = 0; i < ns.size(); i++) {
            if ("Day 1".equals(Ui.t(ns.get(i)).trim())) {
                day1 = ns.get(i);
                break;
            }
        }
        // 签到完成后这一行会收成一行，Day 标签消失
        if (day1 == null) {
            LogStore.add("签到: 该行已收起，今天签到过了");
            return false;
        }
        Rect r1 = Ui.rect(day1);
        if (r1 == null) {
            return false;
        }
        // 临时诊断：把「签入」这一行的所有节点打出来
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            Rect r = Ui.rect(n);
            if (r == null) {
                continue;
            }
            if (r.centerY() < r1.centerY() - 160 || r.centerY() > r1.centerY() + 120) {
                continue;
            }
            LogStore.add("  [dbg] " + Ui.cls(n) + " " + r.left + "," + r.top + " t=" + Ui.t(n)
                    + " d=" + Ui.d(n));
        }
        List<AccessibilityNodeInfo> nums = new ArrayList<AccessibilityNodeInfo>();
        List<AccessibilityNodeInfo> row = new ArrayList<AccessibilityNodeInfo>();
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            String t = Ui.t(n).trim();
            Rect r = Ui.rect(n);
            if (r == null) {
                continue;
            }
            if (P_PURE_NUM.matcher(t).matches()) {
                nums.add(n);
                continue;
            }
            if (P_DAY_LABEL.matcher(t).matches() && Math.abs(r.centerY() - r1.centerY()) < 60) {
                row.add(n);
            }
        }
        // 已经打勾的格子只剩对勾图标、没有数字；今天和未来的格子都有数字。
        // 所以「第一个上方带数字的格子」就是今天。
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo targetNum = null;
        int dayNo = 0;
        for (int i = 0; i < row.size(); i++) {
            AccessibilityNodeInfo n = row.get(i);
            Rect r = Ui.rect(n);
            if (r == null) {
                continue;
            }
            dayNo++;
            for (int k = 0; k < nums.size(); k++) {
                Rect m = Ui.rect(nums.get(k));
                if (m == null) {
                    continue;
                }
                if (Math.abs(m.centerX() - r.centerX()) < 50
                        && m.centerY() > r.top - (int) (r.height() * 3.2)
                        && m.centerY() < r.top) {
                    target = n;
                    targetNum = nums.get(k);
                    break;
                }
            }
            if (target != null) {
                break;
            }
        }
        if (target == null || targetNum == null) {
            LogStore.add("签到: 找不到今天那一格");
            return false;
        }
        Rect rt = Ui.rect(target);
        int cy = rt.top - (int) (rt.height() * 1.4);
        String before = Ui.t(targetNum).trim();
        LogStore.add("签到: 点 Day " + dayNo + " 圆圈 (" + rt.centerX() + "," + cy + ") 面值 " + before);
        svc.tap(rt.centerX(), cy);
        sleep(2800);
        AccessibilityNodeInfo root2 = svc.appRoot();
        if (root2 == null) {
            return false;
        }
        List<AccessibilityNodeInfo> ns2 = Ui.all(root2);
        if (!Ui.hasText(ns2, "Day 1")) {
            LogStore.add("  ✓ 签到成功（该行已收起）");
            return true;
        }
        boolean stillNum = false;
        for (int i = 0; i < ns2.size(); i++) {
            AccessibilityNodeInfo n = ns2.get(i);
            if (!P_PURE_NUM.matcher(Ui.t(n).trim()).matches()) {
                continue;
            }
            Rect m = Ui.rect(n);
            if (m == null) {
                continue;
            }
            if (Math.abs(m.centerX() - rt.centerX()) < 50
                    && m.centerY() > rt.top - (int) (rt.height() * 3.2)
                    && m.centerY() < rt.top) {
                stillNum = true;
                break;
            }
        }
        LogStore.add(stillNum ? "  签到: 点了但数字还在，可能没成功" : "  ✓ 签到成功（数字已变对勾）");
        return !stillNum;
    }

    /** 找一张还没做的每日活动卡：点开后会直接搜索一次，+10 分。 */
    private AccessibilityNodeInfo findEarnCard(BingA11yService svc) {
        AccessibilityNodeInfo root = svc.appRoot();
        if (root == null) {
            return null;
        }
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        int sh = svc.screenH();
        AccessibilityNodeInfo best = null;
        int bestArea = 0;
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            String d = Ui.d(n);
            if (!d.contains("赚取 10 积分") || d.contains("已赚取")) {
                continue;
            }
            if (!Ui.clickable(n)) {
                continue;
            }
            Rect r = Ui.rect(n);
            if (r == null) {
                continue;
            }
            int w = r.width();
            int h = r.height();
            if (w < 600 || h < 130) {
                continue;
            }
            int cy = r.centerY();
            if (cy < sh * 0.2 || cy > sh - 260) {
                continue;
            }
            int area = w * h;
            if (area > bestArea) {
                bestArea = area;
                best = n;
            }
        }
        return best;
    }

    private int doDailyCards(BingA11yService svc) {
        int done = 0;
        Set<String> seen = new HashSet<String>();
        for (int round = 0; round < 8; round++) {
            if (stop) {
                break;
            }
            AccessibilityNodeInfo card = null;
            for (int s = 0; s < 4 && card == null; s++) {
                card = findEarnCard(svc);
                if (card == null) {
                    svc.swipeSmall();
                    sleep(1000);
                }
            }
            if (card == null) {
                break;
            }
            String desc = Ui.d(card);
            String title = (desc == null || desc.length() == 0) ? "" : desc.split(",")[0].trim();
            if (title.length() == 0 || seen.contains(title)) {
                break;
            }
            seen.add(title);
            Rect r = Ui.rect(card);
            LogStore.add("活动 " + (round + 1) + ": " + title + " [" + r.left + "," + r.top
                    + " " + r.right + "," + r.bottom + "]");
            // 刚滚动完页面还在惯性滑动，第一下点击会被吃掉，先停一下
            sleep(1300);
            svc.tap(r.centerX(), r.centerY());
            sleep(2800);
            if (isRewardsPage(svc)) {
                LogStore.add("  首点没反应，补点一次");
                svc.tap(r.centerX(), r.centerY() - (int) (r.height() * 0.15));
                sleep(3000);
            }
            if (isRewardsPage(svc)) {
                LogStore.add("  ! 仍然没有跳转，跳过这张");
                svc.swipeSmall();
                sleep(900);
                continue;
            }
            svc.back();
            sleep(2400);
            if (!isRewardsPage(svc)) {
                if (!openRewards(svc)) {
                    LogStore.add("  ! 回不到奖励页，停止");
                    break;
                }
            }
            done++;
            LogStore.add("  ✓ 已领取");
        }
        return done;
    }

    /** 打开奖励页 → 签到 → 领完活动卡 → 解析进度 → 退回。返回进度，失败返回 null。 */
    private Prog doRewardsTasks(BingA11yService svc) {
        if (!openRewards(svc)) {
            LogStore.add("! 打不开奖励页");
            return null;
        }
        sleep(900);
        // 页面可能停在上次的位置，先回到顶部
        for (int i = 0; i < 3; i++) {
            svc.swipe(600, (int) (svc.screenH() * 0.30), 600, (int) (svc.screenH() * 0.80), 380);
            sleep(400);
        }
        sleep(500);
        if (doCheckIn(svc)) {
            rewardsDone++;
        }
        sleep(700);
        int n = doDailyCards(svc);
        if (n > 0) {
            rewardsDone += n;
            LogStore.add("每日活动领取 " + n + " 张");
        }
        for (int i = 0; i < 4; i++) {
            svc.swipe(600, (int) (svc.screenH() * 0.30), 600, (int) (svc.screenH() * 0.78), 380);
            sleep(420);
        }
        sleep(600);
        Prog p = parseRewards(svc);
        svc.back();
        sleep(1100);
        if (p != null) {
            saveKnown(p, svc);
        }
        return p;
    }

    private void saveKnown(Prog p, BingA11yService svc) {
        try {
            android.content.SharedPreferences sp = Scheduler.prefs(svc);
            android.content.SharedPreferences.Editor ed = sp.edit();
            if (p.sMax > 0) {
                ed.putInt("sMax", p.sMax);
            }
            if (p.rMax > 0) {
                ed.putInt("rMax", p.rMax);
            }
            ed.apply();
        } catch (Throwable t) {
            // 忽略
        }
    }

    private int knownMax(BingA11yService svc, String key, int def) {
        try {
            return Scheduler.prefs(svc).getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private Prog parseRewards(BingA11yService svc) {
        Prog p = new Prog();
        boolean sSeen = false;
        boolean rSeen = false;
        for (int round = 0; round < 4; round++) {
            if (stop) {
                return null;
            }
            AccessibilityNodeInfo root = svc.appRoot();
            if (root == null) {
                return null;
            }
            List<AccessibilityNodeInfo> ns = Ui.all(root);
            for (int i = 0; i < ns.size(); i++) {
                AccessibilityNodeInfo n = ns.get(i);
                String blob = Ui.d(n) + "|" + Ui.t(n);
                if (!sSeen && blob.contains("搜索以赚取")) {
                    sSeen = true;
                    Integer cur = firstInt(P_EARNED, blob);
                    Integer need = firstInt(P_NEED, blob);
                    if (cur != null) {
                        p.sCur = cur.intValue();
                    }
                    if (need != null) {
                        p.sMax = need.intValue();
                    }
                }
                if (!rSeen && blob.contains("阅读以赚取")) {
                    rSeen = true;
                    Integer cur = firstInt(P_EARNED, blob);
                    Integer need = firstInt(P_NEED, blob);
                    if (cur != null) {
                        p.rCur = cur.intValue();
                    }
                    if (need != null) {
                        p.rMax = need.intValue();
                    }
                }
                String tt = Ui.t(n).trim();
                if (TOTAL.matcher(tt).matches()) {
                    int slash = tt.indexOf('/');
                    try {
                        int a = Integer.parseInt(tt.substring(0, slash));
                        int b = Integer.parseInt(tt.substring(slash + 1));
                        if (b > p.tMax) {
                            p.tCur = a;
                            p.tMax = b;
                        }
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
            }
            if (sSeen || rSeen) {
                break;
            }
            svc.swipeUp();
            sleep(1300);
        }

        int ks = knownMax(svc, "sMax", 30);
        int kr = knownMax(svc, "rMax", 30);
        if (p.sMax <= 0 && p.sCur >= 0) {
            p.sMax = ks;
        }
        if (p.rMax <= 0 && p.rCur >= 0) {
            p.rMax = kr;
        }
        // 卡片完成后会从列表消失：用「另一张还在」推断它已满
        if (!sSeen && rSeen) {
            p.sCur = ks;
            p.sMax = ks;
        }
        if (!rSeen && sSeen) {
            p.rCur = kr;
            p.rMax = kr;
        }
        if (!sSeen && !rSeen) {
            if (p.tMax > 0 && p.tCur >= p.tMax) {
                p.sCur = ks;
                p.sMax = ks;
                p.rCur = kr;
                p.rMax = kr;
                p.allDone = true;
            }
        }
        if (p.sMax > 0 && p.rMax > 0 && p.sCur >= p.sMax && p.rCur >= p.rMax) {
            p.allDone = true;
        }
        if (!sSeen && !rSeen && !p.allDone) {
            return null;
        }
        return p;
    }

    private AccessibilityNodeInfo findGridEntry(BingA11yService svc) {
        AccessibilityNodeInfo root = svc.appRoot();
        if (root == null) {
            return null;
        }
        List<AccessibilityNodeInfo> ns = Ui.all(root);
        int h = svc.screenH();
        int w = svc.screenW();
        int barTop = (int) (h * 0.88);

        // 优先按文案找，且只认底部导航栏里的
        String[] names = {"所有应用", "全部应用", "应用", "更多"};
        for (int k = 0; k < names.length; k++) {
            for (int i = 0; i < ns.size(); i++) {
                AccessibilityNodeInfo n = ns.get(i);
                String label = Ui.t(n) + "|" + Ui.d(n);
                if (!label.contains(names[k])) {
                    continue;
                }
                if (Ui.rect(n).centerY() < barTop) {
                    continue;
                }
                AccessibilityNodeInfo c = n;
                for (int up = 0; up < 4 && c != null; up++) {
                    if (Ui.clickable(c)) {
                        return c;
                    }
                    c = c.getParent();
                }
                return n;
            }
        }

        // 兜底：底部导航最右侧那一格
        AccessibilityNodeInfo best = null;
        int bestCx = -1;
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < ns.size(); i++) {
            AccessibilityNodeInfo n = ns.get(i);
            if (!Ui.clickable(n)) {
                continue;
            }
            Rect r = Ui.rect(n);
            if (r.top < barTop || r.width() < 50 || r.width() > w * 0.45) {
                continue;
            }
            cand.append('[').append(r.centerX()).append(',').append(r.centerY()).append(']');
            if (r.centerX() > bestCx) {
                bestCx = r.centerX();
                best = n;
            }
        }
        if (best != null) {
            LogStore.add("  底部栏候选位置: " + cand);
        }
        return best;
    }
}
