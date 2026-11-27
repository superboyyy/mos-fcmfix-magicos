package com.superboyyy.mosfcmfix;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * FCMFix MagicOS 管理界面：状态 / 应用 / 日志。所有读写都通过 root（su）完成，模块本身不联网。
 * 纯代码布局，配色取系统动态色（Material You），跟随深浅色。
 */
public class MainActivity extends Activity {

    static final String VERSION = "0.7.1";
    private static final String DIR = "/data/system/fcmfix-magicos";
    private static final String CONFIG = DIR + "/config.properties";
    private static final String EXCLUDE = DIR + "/push_exclude.txt";
    private static final String LOG = "/data/system/fcmfix-magicos.log";
    private static final String OFF_PROP = "persist.mosfcmfix.off";
    private static final String FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";

    private static final String[][] FEATURES = {
            {"block_night_firewall", "阻止锁屏 / 夜间断网", "锁屏后系统不再切断应用的网络，谷歌推送一直在线"},
            {"push_wake", "推送拉起已停止的应用", "被系统停掉的应用也能收到推送；不需要生效的应用可在「应用」页排除"},
            {"delivery_window", "推送后 30 秒放行", "收到推送后 30 秒内不冻结这个应用、不让手机中途睡着；同步没做完会自动延长，最长 3 分钟。Gmail 始终放行，不受这个开关影响"},
            {"reconnect", "Wi-Fi / 数据切换时检查推送", "Wi-Fi 和移动数据其中一个断开或恢复时，检查谷歌推送是否还连着，避免显示在线却收不到"},
            {"keep_notification", "应用被停时保留通知", "应用进程被系统停掉或杀掉时，不会清除已有通知"},
    };

    // ---- 配色
    private boolean mDark;
    private int cBg, cSurface, cSurface2, cOn, cOnVar, cAccent, cAccentBox, cOnAccentBox, cGood, cBad, cWarn, cDivider;
    private float mDensity;

    private FrameLayout mContent;
    private final List<TextView> mTabs = new ArrayList<>();
    private TextView mStatusPill;
    private int mTab = 0;

    // ---- 应用页状态
    private List<AppRow> mAllApps;
    private Set<String> mExcluded = new HashSet<>();
    private boolean mRootOk;
    private String mQuery = "";
    private int mFilter = 0; // 0 全部 1 已停止 2 不拉起

    @Override
    protected void onCreate(Bundle b) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(b);
        mDensity = getResources().getDisplayMetrics().density;
        initColors();

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        // ---- 头部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(22), dp(18), dp(18), dp(6));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(tv("MOS推送修复", 28, cOn, true));
        TextView sub = tv("谷歌 FCM 推送修复 · v" + VERSION, 13, cOnVar, false);
        sub.setPadding(dp(1), dp(2), 0, 0);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mStatusPill = pill("读取中", cSurface2, cOnVar);
        head.addView(mStatusPill);
        root.addView(head);

        // ---- 分段标签
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(cSurface2, 22));
        String[] names = {"状态", "应用", "日志"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView tab = tv(names[i], 15, cOnVar, false);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(9), 0, dp(9));
            tab.setOnClickListener(v -> show(idx));
            seg.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            mTabs.add(tab);
        }
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.setMargins(dp(16), dp(10), dp(16), dp(6));
        root.addView(seg, segLp);

        mContent = new FrameLayout(this);
        root.addView(mContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        styleSystemBars();
        show(0);
    }

    private void initColors() {
        mDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (mDark) {
            cBg = sys(android.R.color.system_neutral1_900, 0xFF121316);
            cSurface = sys(android.R.color.system_neutral1_800, 0xFF1E2024);
            cSurface2 = sys(android.R.color.system_neutral2_800, 0xFF2A2D33);
            cOn = sys(android.R.color.system_neutral1_50, 0xFFF0F0F3);
            cOnVar = sys(android.R.color.system_neutral2_300, 0xFFB4B8C0);
            cAccent = sys(android.R.color.system_accent1_200, 0xFFA8C7FA);
            cAccentBox = sys(android.R.color.system_accent1_700, 0xFF284777);
            cOnAccentBox = sys(android.R.color.system_accent1_100, 0xFFD6E3FF);
            cGood = 0xFF5BD68E;
            cBad = 0xFFFF7A70;
            cWarn = 0xFFF2BE4F;
            cDivider = 0x1FFFFFFF;
        } else {
            cBg = sys(android.R.color.system_neutral1_50, 0xFFF4F5F8);
            cSurface = sys(android.R.color.system_neutral1_10, 0xFFFFFFFF);
            cSurface2 = sys(android.R.color.system_neutral2_100, 0xFFE6E8EE);
            cOn = sys(android.R.color.system_neutral1_900, 0xFF1A1C1F);
            cOnVar = sys(android.R.color.system_neutral2_600, 0xFF5C6068);
            cAccent = sys(android.R.color.system_accent1_600, 0xFF2F5EA8);
            cAccentBox = sys(android.R.color.system_accent1_100, 0xFFD6E3FF);
            cOnAccentBox = sys(android.R.color.system_accent1_900, 0xFF0B2350);
            cGood = 0xFF1C9E58;
            cBad = 0xFFD43B33;
            cWarn = 0xFFB97800;
            cDivider = 0x14000000;
        }
    }

    @SuppressWarnings("deprecation")
    private void styleSystemBars() {
        try {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(mDark ? 0 : light, light);
            }
        } catch (Throwable ignored) {
        }
    }

    private void show(int idx) {
        mTab = idx;
        for (int i = 0; i < mTabs.size(); i++) {
            TextView t = mTabs.get(i);
            boolean on = i == idx;
            t.setBackground(on ? round(cSurface, 18) : null);
            t.setTextColor(on ? cOn : cOnVar);
            t.setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT);
        }
        mContent.removeAllViews();
        if (idx == 0) showStatus();
        else if (idx == 1) showApps();
        else showLog();
    }

    // ================================================================ 状态页

    private void showStatus() {
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(2), dp(16), dp(28));
        sv.addView(box);
        mContent.addView(sv);
        LinearLayout loading = card();
        loading.addView(tv("正在读取状态…", 15, cOnVar, false));
        box.addView(loading);

        new Thread(() -> {
            String script = "L=" + LOG + "\n"
                    + "ss=$(pidof system_server); st=$(cut -d' ' -f22 /proc/$ss/stat 2>/dev/null); bt=$(grep btime /proc/stat | cut -d' ' -f2)\n"
                    + "[ -n \"$st\" ] && echo \"SS_START=$((bt + st / 100))\"\n"
                    + "echo \"YEAR=$(date +%Y)\"\n"
                    + "last=$(grep -n 'loading in system_server' $L 2>/dev/null | tail -1 | cut -d: -f1)\n"
                    + "if [ -n \"$last\" ]; then tail -n +$last $L | grep -E 'loading in system_server|^.{19}hooked |^.{19}skip [^=:]+: ' | sed 's/^/HOOK /'; fi\n"
                    + "echo \"GMS=$(dumpsys activity service com.google.android.gms/.gcm.GcmService 2>/dev/null | grep -m1 'Is client connected' | sed 's/.*: //')\"\n"
                    + "echo \"BLOCKS=$(grep 'BLOCK powersave' $L 2>/dev/null | sed -n 's/.*(x\\([0-9]*\\)).*/\\1/p' | awk '{s+=$1} END{print s+0}')\"\n"
                    + "echo \"PUSHTODAY=$(grep \"^$(date +%m-%d)\" $L 2>/dev/null | grep 'open window' | grep -vc 'open window uid=')\"\n"
                    + "echo \"LASTPUSH=$(grep 'open window' $L 2>/dev/null | grep -v 'open window uid=' | tail -1)\"\n"
                    + "echo \"OFF=$(getprop " + OFF_PROP + ")\"\n"
                    + "cat " + CONFIG + " 2>/dev/null | sed 's/^/CFG /'\n";
            final String out = Root.run(script);
            final int[] stopped = countStoppedFcmApps();
            runOnUiThread(() -> {
                if (mTab == 0 && !isFinishing()) renderStatus(box, out, stopped);
            });
        }).start();
    }

    private void renderStatus(LinearLayout box, String out, int[] stopped) {
        box.removeAllViews();
        if (out == null) {
            setPill("需要 root", cWarn);
            LinearLayout c = card();
            c.addView(tv("需要 root 权限", 18, cOn, true));
            TextView d = tv("请打开 Root 管理器（KernelSU / Magisk / APatch）的超级用户授权，允许「MOS推送修复」使用 root，然后点上面的「状态」刷新。", 14, cOnVar, false);
            d.setPadding(0, dp(6), 0, 0);
            d.setLineSpacing(dp(2), 1f);
            c.addView(d);
            box.addView(c);
            return;
        }
        Map<String, String> kv = new HashMap<>();
        List<String> hooks = new ArrayList<>();
        final Properties cfg = new Properties();
        for (String line : out.split("\n")) {
            if (line.startsWith("HOOK ")) hooks.add(line.substring(5));
            else if (line.startsWith("CFG ")) {
                String l = line.substring(4);
                int e = l.indexOf('=');
                if (e > 0 && !l.startsWith("#")) cfg.setProperty(l.substring(0, e).trim(), l.substring(e + 1).trim());
            } else {
                int e = line.indexOf('=');
                if (e > 0) kv.put(line.substring(0, e), line.substring(e + 1));
            }
        }
        String loadLine = null;
        for (String h : hooks) if (h.contains("loading in system_server")) loadLine = h;
        boolean loaded = false;
        if (loadLine != null) {
            long t = parseLogTime(loadLine, kv.get("YEAR"));
            long ss = parseLong(kv.get("SS_START"));
            loaded = t > 0 && ss > 0 && t >= (ss - 300) * 1000L;
        }
        int ok = 0;
        List<String> skips = new ArrayList<>();
        for (String h : hooks) {
            if (h.contains(" hooked ")) ok++;
            if (h.matches("^.{19}skip [^=:]+: .*")) skips.add(h.length() > 19 ? h.substring(19) : h);
        }
        final boolean off = "1".equals(kv.get("OFF")) || "true".equals(kv.get("OFF"));
        boolean running = loaded && !off;

        if (!loaded) setPill("未生效", cWarn);
        else if (off) setPill("已暂停", cWarn);
        else setPill("运行中", cGood);

        // ---- 总状态
        LinearLayout hero = card();
        hero.setBackground(round(running ? cAccentBox : cSurface, 24));
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        int heroOn = running ? cOnAccentBox : cOn;
        hero.addView(tv(!loaded ? "本次开机还没生效" : off ? "已暂停" : "推送修复运行中", 21, heroOn, true));
        TextView hd = tv(!loaded
                        ? "在 LSPosed 里启用本模块（作用域：系统框架、荣耀省电精灵），然后重启手机。"
                        : (ok + " 项拦截已挂载" + (skips.isEmpty() ? "，全部正常" : "，" + skips.size() + " 项因固件不匹配跳过")),
                14, running ? withAlpha(cOnAccentBox, 0xCC) : cOnVar, false);
        hd.setPadding(0, dp(6), 0, 0);
        hero.addView(hd);
        if (!skips.isEmpty()) {
            TextView sk = tv(TextUtils.join("\n", skips), 12, cWarn, false);
            sk.setPadding(0, dp(8), 0, 0);
            hero.addView(sk);
        }
        box.addView(hero);

        // ---- 数据格
        String gms = kv.get("GMS");
        LinearLayout row1 = gridRow();
        row1.addView(tile("谷歌推送", "true".equals(gms) ? "已连接" : "false".equals(gms) ? "未连接" : "未知",
                "true".equals(gms) ? cGood : "false".equals(gms) ? cBad : cOnVar), tileLp(true));
        row1.addView(tile("今日推送", nz(kv.get("PUSHTODAY")) + " 次", cOn), tileLp(false));
        box.addView(row1);
        LinearLayout row2 = gridRow();
        row2.addView(tile("已停止的推送应用", stopped[1] + " / " + stopped[0], stopped[1] > 0 ? cWarn : cOn), tileLp(true));
        row2.addView(tile("拦截断网", nz(kv.get("BLOCKS")) + " 次", cOn), tileLp(false));
        box.addView(row2);

        String last = kv.get("LASTPUSH");
        if (last != null && !last.isEmpty()) {
            LinearLayout lc = card();
            lc.setOrientation(LinearLayout.HORIZONTAL);
            lc.setGravity(Gravity.CENTER_VERTICAL);
            lc.addView(tv("最近一次推送", 14, cOnVar, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            lc.addView(tv(prettyPush(last), 15, cOn, true));
            box.addView(lc);
        }

        // ---- 开关
        box.addView(section("功能"));
        LinearLayout sw = card();
        sw.setPadding(dp(18), dp(2), dp(12), dp(2));
        sw.addView(switchRow("总开关", "关闭后所有功能暂停，几秒内生效", !off, on -> new Thread(() -> {
            String r = Root.run("v=" + (on ? "0" : "1") + "; resetprop " + OFF_PROP + " $v 2>/dev/null || setprop " + OFF_PROP + " $v; echo OK");
            toast(r == null ? "需要 root" : (on ? "已启用" : "已暂停"));
            if (r != null) runOnUiThread(() -> setPill(on ? "运行中" : "已暂停", on ? cGood : cWarn));
        }).start()));
        for (final String[] f : FEATURES) {
            sw.addView(divider());
            sw.addView(switchRow(f[1], f[2], !"0".equals(cfg.getProperty(f[0], "1")), on -> {
                cfg.setProperty(f[0], on ? "1" : "0");
                saveConfig(cfg);
            }));
        }
        box.addView(sw);

        TextView foot = tv("功能开关修改后约 10 秒生效，无需重启\n日志：" + LOG, 12, cOnVar, false);
        foot.setPadding(dp(6), dp(16), dp(6), 0);
        foot.setLineSpacing(dp(3), 1f);
        box.addView(foot);
    }

    private int[] countStoppedFcmApps() {
        List<AppRow> rows = loadFcmApps(false);
        int s = 0;
        for (AppRow r : rows) if (r.stopped) s++;
        return new int[]{rows.size(), s};
    }

    private void saveConfig(Properties c) {
        StringBuilder sb = new StringBuilder("# FCMFix MagicOS feature switches (1 on / 0 off)\n");
        for (String[] f : FEATURES) sb.append(f[0]).append('=').append(c.getProperty(f[0], "1")).append('\n');
        writeRootFile(CONFIG, sb.toString(), "已保存，约 10 秒生效");
    }

    /** "MM-dd HH:mm:ss.SSS open window pkg (why)" → "Gmail · 14:03" */
    private String prettyPush(String line) {
        try {
            String time = line.substring(6, 11);
            String rest = line.substring(line.indexOf("open window ") + 12);
            String pkg = rest.contains(" ") ? rest.substring(0, rest.indexOf(' ')) : rest;
            String label = pkg;
            try {
                label = String.valueOf(getPackageManager().getApplicationInfo(pkg, 0).loadLabel(getPackageManager()));
            } catch (Throwable ignored) {
            }
            String today = new SimpleDateFormat("MM-dd", Locale.US).format(new Date());
            return label + " · " + (line.startsWith(today) ? "" : line.substring(0, 5) + " ") + time;
        } catch (Throwable e) {
            return line;
        }
    }

    // ================================================================ 应用页

    private static final class AppRow {
        String pkg, label;
        Drawable icon;
        boolean stopped, wake;
    }

    private void showApps() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), 0);
        mContent.addView(box);

        final EditText search = new EditText(this);
        search.setHint("搜索应用或包名");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(cOn);
        search.setHintTextColor(cOnVar);
        search.setBackground(round(cSurface, 22));
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.setText(mQuery);
        box.addView(search);

        final LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(10), 0, 0);
        box.addView(chips);

        final TextView summary = tv("正在读取应用列表…", 12, cOnVar, false);
        summary.setPadding(dp(4), dp(10), dp(4), dp(6));
        box.addView(summary);

        final ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        lv.setClipToPadding(false);
        lv.setPadding(0, 0, 0, dp(24));
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final List<AppRow> shown = new ArrayList<>();
        final BaseAdapter ad = appAdapter(shown);
        lv.setAdapter(ad);

        final Runnable refilter = () -> {
            shown.clear();
            if (mAllApps == null) return;
            String q = mQuery.trim().toLowerCase(Locale.ROOT);
            int st = 0, ex = 0;
            for (AppRow r : mAllApps) {
                if (r.stopped) st++;
                if (!r.wake) ex++;
                if (mFilter == 1 && !r.stopped) continue;
                if (mFilter == 2 && r.wake) continue;
                if (!q.isEmpty() && !r.label.toLowerCase(Locale.ROOT).contains(q) && !r.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
                shown.add(r);
            }
            summary.setText(mAllApps.size() + " 个应用使用谷歌推送 · " + st + " 个已停止 · " + ex + " 个已排除"
                    + (mRootOk ? "\n关闭开关 = 跳过推送拉起、30 秒放行与保留通知（Gmail 仍保留 30 秒放行）" : "\n⚠ 没有 root 权限，改动无法保存"));
            ad.notifyDataSetChanged();
        };

        String[] cn = {"全部", "已停止", "已排除"};
        final List<TextView> chipViews = new ArrayList<>();
        for (int i = 0; i < cn.length; i++) {
            final int idx = i;
            TextView c = tv(cn[i], 13, cOnVar, false);
            c.setPadding(dp(16), dp(7), dp(16), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chips.addView(c, lp);
            chipViews.add(c);
            c.setOnClickListener(v -> {
                mFilter = idx;
                styleChips(chipViews);
                refilter.run();
            });
        }
        styleChips(chipViews);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                mQuery = s.toString();
                refilter.run();
            }
        });
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos >= shown.size()) return;
            AppRow r = shown.get(pos);
            r.wake = !r.wake;
            if (r.wake) mExcluded.remove(r.pkg);
            else mExcluded.add(r.pkg);
            refilter.run();
            saveExclude();
        });

        if (mAllApps != null) {
            refilter.run();
            return;
        }
        new Thread(() -> {
            final List<AppRow> rows = loadFcmApps(true);
            String ex = Root.run("cat " + EXCLUDE + " 2>/dev/null; echo __END__");
            final Set<String> excluded = new HashSet<>();
            if (ex != null) for (String l : ex.split("\n")) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#") && !"__END__".equals(l)) excluded.add(l);
            }
            for (AppRow r : rows) r.wake = !excluded.contains(r.pkg);
            final boolean rootOk = ex != null;
            runOnUiThread(() -> {
                mAllApps = rows;
                mExcluded = excluded;
                mRootOk = rootOk;
                refilter.run();
            });
        }).start();
    }

    private void styleChips(List<TextView> chips) {
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i);
            boolean on = i == mFilter;
            c.setBackground(on ? round(cAccentBox, 16) : stroke(cOnVar, 16));
            c.setTextColor(on ? cOnAccentBox : cOnVar);
            c.setTypeface(on ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT);
        }
    }

    private BaseAdapter appAdapter(final List<AppRow> shown) {
        return new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout row = (LinearLayout) cv;
                if (row == null) row = newAppRow();
                AppRow r = shown.get(i);
                ((ImageView) row.getChildAt(0)).setImageDrawable(r.icon);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                LinearLayout line1 = (LinearLayout) col.getChildAt(0);
                ((TextView) line1.getChildAt(0)).setText(r.label);
                line1.getChildAt(1).setVisibility(r.stopped ? View.VISIBLE : View.GONE);
                ((TextView) col.getChildAt(1)).setText("com.google.android.gm".equals(r.pkg) ? "推送后始终放行 30 秒 · " + r.pkg : r.pkg);
                ((Switch) row.getChildAt(2)).setChecked(r.wake);
                row.getChildAt(0).setAlpha(r.wake ? 1f : 0.45f);
                col.setAlpha(r.wake ? 1f : 0.6f);
                return row;
            }
        };
    }

    private LinearLayout newAppRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 背景上下各缩 3dp，形成卡片之间的缝隙
        row.setBackground(new InsetDrawable(round(cSurface, 18), 0, dp(3), 0, dp(3)));
        row.setPadding(dp(14), dp(15), dp(10), dp(15));

        ImageView iv = new ImageView(this);
        row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = tv("", 16, cOn, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        line1.addView(name, nameLp);
        TextView badge = tv("已停止", 11, cWarn, true);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setBackground(round(withAlpha(cWarn, 0x2A), 10));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.setMarginStart(dp(8));
        line1.addView(badge, bLp);
        col.addView(line1);
        TextView pkg = tv("", 12, cOnVar, false);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pkg.setPadding(0, dp(2), 0, 0);
        col.addView(pkg);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch s = makeSwitch();
        s.setClickable(false);
        s.setFocusable(false);
        row.addView(s);
        return row;
    }

    private List<AppRow> loadFcmApps(boolean withIcons) {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> ris = pm.queryBroadcastReceivers(new Intent(FCM_RECEIVE), PackageManager.MATCH_DISABLED_COMPONENTS);
        Set<String> seen = new HashSet<>();
        List<AppRow> rows = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai.uid < 10000 || "com.google.android.gms".equals(pkg)) continue;
                AppRow r = new AppRow();
                r.pkg = pkg;
                r.label = String.valueOf(ai.loadLabel(pm));
                if (withIcons) r.icon = ai.loadIcon(pm);
                r.stopped = (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
                rows.add(r);
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(rows, (a, b) -> {
            if (a.stopped != b.stopped) return a.stopped ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return rows;
    }

    private void saveExclude() {
        List<String> list = new ArrayList<>(mExcluded);
        Collections.sort(list);
        StringBuilder sb = new StringBuilder("# FCMFix MagicOS push-wake exclude list, one package per line\n");
        for (String p : list) sb.append(p).append('\n');
        writeRootFile(EXCLUDE, sb.toString(), list.isEmpty() ? "已保存：所有推送应用均启用修复" : "已保存：已排除 " + list.size() + " 个应用");
    }

    // ================================================================ 日志页

    private void showLog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(16));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = tv("最近 300 行", 13, cOnVar, false);
        title.setPadding(dp(4), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView copy = pill("复制", cSurface2, cOn);
        TextView refresh = pill("刷新", cAccentBox, cOnAccentBox);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMarginEnd(dp(8));
        bar.addView(copy, gap);
        bar.addView(refresh);
        box.addView(bar);

        final ScrollView sv = new ScrollView(this);
        sv.setBackground(round(cSurface, 20));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        svLp.topMargin = dp(10);
        final TextView tv = tv("正在读取…", 11, cOn, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(dp(14), dp(12), dp(14), dp(16));
        sv.addView(tv);
        box.addView(sv, svLp);
        mContent.addView(box);

        final String[] raw = {""};
        final Runnable load = () -> new Thread(() -> {
            final String out = Root.run("tail -n 300 " + LOG + " 2>/dev/null; echo __END__");
            runOnUiThread(() -> {
                if (out == null) {
                    tv.setText("需要 root 权限");
                    return;
                }
                raw[0] = out.replace("__END__", "").trim();
                tv.setText(raw[0].isEmpty() ? "（还没有日志）" : colorize(raw[0]));
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            });
        }).start();
        refresh.setOnClickListener(v -> load.run());
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("FCMFix log", raw[0]));
            toast("已复制");
        });
        load.run();
    }

    private CharSequence colorize(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : text.split("\n")) {
            int start = sb.length();
            sb.append(line).append('\n');
            int color = 0;
            if (line.contains("BLOCK")) color = cAccent;
            else if (line.contains(" skip ") || line.contains("error") || line.contains("failed")) color = cBad;
            else if (line.contains("open window") || line.contains(" hooked ")) color = cGood;
            else if (line.contains("loading in system_server")) color = cWarn;
            if (line.length() > 18) {
                sb.setSpan(new ForegroundColorSpan(cOnVar), start, start + 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (color != 0) sb.setSpan(new ForegroundColorSpan(color), start + 18, sb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    // ================================================================ 组件

    private interface OnToggle { void on(boolean checked); }

    private View switchRow(String title, String desc, boolean checked, final OnToggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(title, 16, cOn, false));
        TextView d = tv(desc, 12, cOnVar, false);
        d.setPadding(0, dp(3), dp(12), 0);
        d.setLineSpacing(dp(1), 1f);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch s = makeSwitch();
        s.setChecked(checked);
        s.setOnCheckedChangeListener((v, on) -> cb.on(on));
        row.addView(s);
        row.setOnClickListener(v -> s.toggle());
        return row;
    }

    private Switch makeSwitch() {
        Switch s = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{mDark ? cOnAccentBox : Color.WHITE, mDark ? cOnVar : Color.WHITE}));
        s.setTrackTintList(new ColorStateList(states, new int[]{cAccent, mDark ? cSurface2 : withAlpha(cOnVar, 0x55)}));
        return s;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(round(cSurface, 24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout gridRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout.LayoutParams tileLp(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (first) lp.setMarginEnd(dp(10));
        return lp;
    }

    private View tile(String label, String value, int valueColor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(cSurface, 22));
        c.addView(tv(label, 12, cOnVar, false));
        TextView v = tv(value, 22, valueColor, true);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        return c;
    }

    private TextView section(String s) {
        TextView t = tv(s, 13, cOnVar, true);
        t.setPadding(dp(6), dp(22), 0, 0);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cDivider);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return v;
    }

    private TextView pill(String s, int bg, int fg) {
        TextView t = tv(s, 13, fg, true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(round(bg, 14));
        return t;
    }

    private void setPill(String s, int color) {
        mStatusPill.setText("● " + s);
        mStatusPill.setTextColor(color);
        mStatusPill.setBackground(round(withAlpha(color, 0x2A), 14));
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private Drawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Drawable stroke(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(Math.max(1, dp(1)), withAlpha(color, 0x55));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int sys(int res, int fallback) {
        try {
            return getColor(res);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(float v) {
        return Math.round(v * mDensity);
    }

    // ================================================================ 工具

    private void writeRootFile(String path, String content, String okMsg) {
        new Thread(() -> {
            String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String r = Root.run("mkdir -p " + DIR + " && echo '" + b64 + "' | base64 -d > " + path + ".tmp"
                    + " && mv " + path + ".tmp " + path
                    + " && chown system:system " + DIR + " " + path + " && chmod 700 " + DIR + " && chmod 600 " + path
                    + " && echo OK");
            toast(r != null && r.contains("OK") ? okMsg : "保存失败（需要 root）");
        }).start();
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "0" : s;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable e) { return -1; }
    }

    /** 日志行开头 "MM-dd HH:mm:ss.SSS" → epoch ms */
    private static long parseLogTime(String line, String year) {
        try {
            String ts = line.substring(0, 18);
            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(year.trim() + "-" + ts);
            long t = d.getTime();
            if (t > System.currentTimeMillis() + 86_400_000L) {
                Calendar c = Calendar.getInstance();
                c.setTime(d);
                c.add(Calendar.YEAR, -1);
                t = c.getTimeInMillis();
            }
            return t;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 以 root 执行一段 shell 脚本，返回 stdout；拿不到 root 返回 null */
    static final class Root {
        static String run(String script) {
            Process p = null;
            try {
                p = new ProcessBuilder("su").start();
                p.getOutputStream().write((script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
                p.getOutputStream().close();
                String out = readAll(p.getInputStream());
                int code = p.waitFor();
                if (code != 0 && out.isEmpty()) return null;
                return out.trim();
            } catch (Throwable e) {
                return null;
            } finally {
                if (p != null) p.destroy();
            }
        }

        private static String readAll(InputStream in) throws java.io.IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
