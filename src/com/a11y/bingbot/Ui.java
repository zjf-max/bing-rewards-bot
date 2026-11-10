package com.a11y.bingbot;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 节点树遍历与属性便捷读取。纯静态，无状态。 */
public final class Ui {

    private Ui() {}

    public static List<AccessibilityNodeInfo> all(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        if (root == null) {
            return out;
        }
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 5000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) {
                continue;
            }
            out.add(n);
            try {
                int c = n.getChildCount();
                for (int i = 0; i < c; i++) {
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) {
                        q.add(ch);
                    }
                }
            } catch (Throwable t) {
                // 节点在遍历途中被系统回收，跳过这一支
            }
        }
        return out;
    }

    /** 深度优先、保持文档顺序 —— WebView 里卡片的兄弟序列要靠这个顺序判断。 */
    public static List<AccessibilityNodeInfo> allDfs(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        dfs(root, out, 0);
        return out;
    }

    private static void dfs(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 3000) {
            return;
        }
        out.add(n);
        try {
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                dfs(n.getChild(i), out, depth + 1);
            }
        } catch (Throwable t) {
            // 节点被回收，跳过
        }
    }

    public static String t(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        try {
            CharSequence c = n.getText();
            return c == null ? "" : c.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String d(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        try {
            CharSequence c = n.getContentDescription();
            return c == null ? "" : c.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String id(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        try {
            String s = n.getViewIdResourceName();
            return s == null ? "" : s;
        } catch (Throwable e) {
            return "";
        }
    }

    public static String cls(AccessibilityNodeInfo n) {
        if (n == null) {
            return "";
        }
        try {
            CharSequence c = n.getClassName();
            return c == null ? "" : c.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static Rect rect(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        if (n != null) {
            try {
                n.getBoundsInScreen(r);
            } catch (Throwable t) {
                r.setEmpty();
            }
        }
        return r;
    }

    public static boolean editable(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        if (cls(n).contains("EditText")) {
            return true;
        }
        try {
            return n.isEditable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clickable(AccessibilityNodeInfo n) {
        if (n == null) {
            return false;
        }
        try {
            return n.isClickable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasText(List<AccessibilityNodeInfo> ns, String text) {
        for (int i = 0; i < ns.size(); i++) {
            if (text.equals(t(ns.get(i)))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasTextContains(List<AccessibilityNodeInfo> ns, String text) {
        for (int i = 0; i < ns.size(); i++) {
            if (t(ns.get(i)).contains(text)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasWebView(List<AccessibilityNodeInfo> ns) {
        for (int i = 0; i < ns.size(); i++) {
            if (cls(ns.get(i)).contains("WebView")) {
                return true;
            }
        }
        return false;
    }

    public static AccessibilityNodeInfo findByText(List<AccessibilityNodeInfo> ns, String text) {
        for (int i = 0; i < ns.size(); i++) {
            if (text.equals(t(ns.get(i)))) {
                return ns.get(i);
            }
        }
        return null;
    }

    public static AccessibilityNodeInfo findByTextContains(List<AccessibilityNodeInfo> ns, String text) {
        for (int i = 0; i < ns.size(); i++) {
            if (t(ns.get(i)).contains(text)) {
                return ns.get(i);
            }
        }
        return null;
    }

    public static AccessibilityNodeInfo findByDescContains(List<AccessibilityNodeInfo> ns, String text) {
        for (int i = 0; i < ns.size(); i++) {
            if (d(ns.get(i)).contains(text)) {
                return ns.get(i);
            }
        }
        return null;
    }

    public static AccessibilityNodeInfo findByIdSuffix(List<AccessibilityNodeInfo> ns, String suffix) {
        for (int i = 0; i < ns.size(); i++) {
            if (id(ns.get(i)).endsWith(suffix)) {
                return ns.get(i);
            }
        }
        return null;
    }

    /** 简要页面签名，用于远程排错。 */
    public static String signature(AccessibilityNodeInfo root) {
        if (root == null) {
            return "root=null";
        }
        List<AccessibilityNodeInfo> ns = all(root);
        StringBuilder sb = new StringBuilder();
        sb.append("nodes=").append(ns.size());
        sb.append(" web=").append(hasWebView(ns) ? 1 : 0);
        int editCount = 0;
        for (int i = 0; i < ns.size(); i++) {
            if (editable(ns.get(i))) {
                editCount++;
            }
        }
        sb.append(" edit=").append(editCount);
        sb.append(" texts[");
        int shown = 0;
        for (int i = 0; i < ns.size() && shown < 6; i++) {
            String s = t(ns.get(i)).trim();
            if (s.length() == 0) {
                s = d(ns.get(i)).trim();
            }
            if (s.length() == 0) {
                continue;
            }
            if (s.length() > 18) {
                s = s.substring(0, 18);
            }
            sb.append(s).append('|');
            shown++;
        }
        sb.append("]");
        return sb.toString();
    }

    // ---------------------------------------------------------- 全量导出（自检用）

    public static String dumpTree(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        int[] counter = new int[]{0};
        walk(root, 0, sb, counter);
        return sb.toString();
    }

    private static void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] counter) {
        if (n == null || depth > 40 || counter[0] > 500) {
            return;
        }
        counter[0]++;
        Rect r = rect(n);
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        String cname = cls(n);
        int dot = cname.lastIndexOf('.');
        if (dot >= 0) {
            cname = cname.substring(dot + 1);
        }
        sb.append(cname);
        String vid = id(n);
        if (vid.length() > 0) {
            int slash = vid.indexOf('/');
            sb.append(" #").append(slash >= 0 ? vid.substring(slash + 1) : vid);
        }
        sb.append(" [").append(r.left).append(',').append(r.top)
                .append(',').append(r.right).append(',').append(r.bottom).append(']');
        if (clickable(n)) {
            sb.append(" CLICK");
        }
        if (editable(n)) {
            sb.append(" EDIT");
        }
        String tx = t(n);
        if (tx.length() > 0) {
            sb.append(" t=\"").append(cut(tx)).append('"');
        }
        String dc = d(n);
        if (dc.length() > 0) {
            sb.append(" d=\"").append(cut(dc)).append('"');
        }
        sb.append('\n');
        try {
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                walk(n.getChild(i), depth + 1, sb, counter);
            }
        } catch (Throwable e) {
            // 节点被回收，跳过
        }
    }

    private static String cut(String s) {
        String v = s.replace('\n', ' ').trim();
        return v.length() > 60 ? v.substring(0, 60) + "…" : v;
    }
}
