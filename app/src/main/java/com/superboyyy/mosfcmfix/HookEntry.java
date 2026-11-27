package com.superboyyy.mosfcmfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FCMFix MagicOS —— 作用域 android（system_server）与 com.hihonor.powergenie。修复国行 MagicOS 对 Google FCM 推送的限制。
 * 思路参考 kooritea/fcmfix 与 ByteJuno/fcmfix-coloros（MIT），荣耀部分按 MagicOS 11 反编译结果重写。
 *
 * 1. 夜间网络防火墙：PowerGenie 锁屏后调 INetworkManagementService.setFirewallChainEnabled(POWERSAVE=3, true)
 *    只放名单内应用联网（夜间只有微信 / 超级省电时为空）。拦截"其他进程开启 POWERSAVE 链"，
 *    关闭请求和 system_server 内部调用（安卓原生省电模式）放行。
 *
 * 2. 推送拉起：GMS 发出的 com.google.android.c2dm.intent.RECEIVE（目标明确）加 FLAG_INCLUDE_STOPPED_PACKAGES，
 *    让处于 stopped 状态的应用也能收到并被拉起。只认真实 GMS 发送方。
 *    Android 16 在 broadcastIntentWithFeature 里会复制 Intent，所以入口和 broadcastIntentLocked(Traced) 都 hook。
 *    例外名单：/data/system/fcmfix-magicos/push_exclude.txt（每行一个包名，# 注释，改完 10 秒内生效）。
 *
 * 3. 推送投递窗口：目标应用收到 FCM 后 30 秒内
 *    - PowerGenie 代理名单不扣它的 service/job（PGManagerService$LocalService.isServiceProxy → false）；
 *    - HN_USER_EXPERIENCE / HnJobResourceController 不拦它的 job（并 deoptimize 调用方，防内联）；
 *    - 推迟 PowerGenie 的冻结（HnFreezerController.setProcessFrozen reason=PG），窗口结束补发。
 *    触发：上面的 FCM 广播，以及 AMS.tempAllowlistUidLocked（高优先级 FCM 的临时豁免）。
 *    例外名单里的应用、以及关掉本功能时都不放行；Gmail 例外，始终放行（只受总开关控制）。
 *    窗口内还会：推迟 PowerGenie 的唤醒锁代理（HwPowerManagerService.proxyWakeLockByPidUid，窗口后补发）、
 *    跳过唤醒锁强制释放（forceReleaseWakeLockByPidUid，否则锁屏时 CPU 立刻睡着、同步失败）、推迟 IAWARE_FAST 冻结；
 *    窗口内开始的系统同步（SyncManager$ActiveSyncContext）跑完前窗口不关，同步结束 3 秒后关，最长开窗后 3 分钟。
 *
 * 4. 网络变化后检查推送连接：默认网络没变、但旁边有网络出现/消失时（GMS 只会延后检查，且检查闹钟会被荣耀推迟），
 *    给 GMS 发 com.google.android.intent.action.MCS_HEARTBEAT 让它立刻发一次心跳；连接已死则 GMS 马上重连。5 分钟最多一次。
 *
 * 5. 保留通知：应用被强停（REASON_PACKAGE_CHANGED）时，不删除它已经显示的通知；
 *    iAware 低内存快速查杀（ProcessCleaner.killProcessesSameUidFast）杀掉主进程后会调
 *    cleanPackageNotifications → INotificationManager.cancelAllNotifications（system_server 进程内，
 *    reason=REASON_APP_CANCEL_ALL），也一并拦下。应用自己调 cancelAll 时调用方是应用的 uid/pid，不受影响。
 *
 * 功能开关：/data/system/fcmfix-magicos/config.properties（管理界面写入；10 秒内生效）
 *   block_night_firewall / push_wake / delivery_window / reconnect / keep_notification = 1 或 0
 * 总开关（无需重启，5 秒内生效）：resetprop persist.mosfcmfix.off 1
 * 日志：/data/system/fcmfix-magicos.log（>1MB 轮转 .old）
 * 所有 hook 吞掉异常，绝不向 system_server 抛出。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "FCMFixMagicOS";
    private static final String OFF_PROP = "persist.mosfcmfix.off";
    private static final String GMS = "com.google.android.gms";
    /** Gmail 始终有 30 秒放行窗口，不受「应用」页开关和「推送后 30 秒放行」开关影响（总开关仍然有效） */
    private static final String GMAIL = "com.google.android.gm";
    private static final String FCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    private static final int FIREWALL_CHAIN_POWERSAVE = 3;
    private static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x20;
    private static final long WINDOW_MS = 30_000L;
    /** 窗口内有系统同步（SyncManager）在跑时自动延长，最长到开窗后 3 分钟；同步结束 3 秒后关 */
    private static final long WINDOW_MAX_MS = 180_000L;
    private static final long SYNC_TAIL_MS = 3_000L;
    private static final int FLAG_ONGOING_EVENT = 0x2;
    /** 窗口内的系统同步以 databaseError（internal-error）结束时，依次隔这些时间重试；系统默认对这种错误不重试 */
    private static final long[] SYNC_RETRY_DELAYS = {3_000L, 5_000L, 10_000L};

    private static final File DIR = new File("/data/system/fcmfix-magicos");
    private static final File EXCLUDE_FILE = new File(DIR, "push_exclude.txt");
    private static final File CONFIG_FILE = new File(DIR, "config.properties");
    private static final int REASON_PACKAGE_CHANGED = 5;
    private static final int REASON_APP_CANCEL_ALL = 9;

    // 功能开关（默认全开）
    private static volatile java.util.Properties sConfig = new java.util.Properties();
    private static volatile long sConfigMtime = -1L;
    private static volatile long sConfigCheckedAt = -1L;
    private static final File LOG_FILE = new File("/data/system/fcmfix-magicos.log");
    private static final Object LOG_LOCK = new Object();

    private static Handler sHandler;
    private static volatile int sGmsUid = -1;
    private static volatile int sGmailUid = -1;
    private static final Map<Integer, String[]> sUidPkgCache = new ConcurrentHashMap<>();

    private static volatile boolean sOff;
    private static volatile long sOffCheckedAt = -1L;

    // 例外名单
    private static volatile Set<String> sExclude = Collections.emptySet();
    private static volatile long sExcludeMtime = -1L;
    private static volatile long sExcludeCheckedAt = -1L;

    // 投递窗口：包名 / uid → 截止时间（elapsedRealtime）
    private static final Map<String, Long> sPkgWindow = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> sUidWindow = new ConcurrentHashMap<>();
    // 窗口内被推迟的 PG 冻结：pid → [controller, uid, reason]
    private static final Map<String, Object[]> sDeferredFreeze = new ConcurrentHashMap<>();
    /** 窗口内被推迟的 PowerGenie 唤醒锁代理：pid -> {HwPowerManagerService, uid} */
    private static final Map<Integer, Object[]> sDeferredWlProxy = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> sUidWindowStart = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> sUidLastOpen = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> sSyncing = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> sSyncRetries = new ConcurrentHashMap<>();

    // 防火墙拦截日志限频
    private static volatile long sLastBlockLog = 0L;
    private static volatile int sBlockedSinceLog = 0;
    // 每个窗口的日志去重
    private static final Set<String> sLogged = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static volatile long sLastReconnect = 0L;

    private static Method sJsPkg;
    private static Method sJsUid;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (PG_PKG.equals(lpparam.packageName)) {
            installPowerGenieHooks(lpparam.classLoader, lpparam.processName);
            return;
        }
        if (!"android".equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;
        log("loading in system_server (0.7.1)");
        try {
            HandlerThread t = new HandlerThread(TAG);
            t.start();
            sHandler = new Handler(t.getLooper());
            sHandler.postDelayed(RESOLVE_GMS, 15_000L);
            sHandler.postDelayed(HOOK_SOCKET_DESTROY, 20_000L);
            sHandler.postDelayed(REGISTER_NET, 60_000L);
            sHandler.post(ENSURE_FILES);
        } catch (Throwable e) {
            log("handler init failed: " + e);
        }

        hookNightFirewall(cl);
        hookPushWake(cl);
        hookDeliveryWindow(cl);
        hookKeepNotification(cl);
    }

    // ================================================================ 5. 保留通知

    private static void hookKeepNotification(ClassLoader cl) {
        safe("NotificationManagerService.cancelAllNotificationsInt", () -> {
            Class<?> nms = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", cl);
            int n = 0;
            for (Method m : nms.getDeclaredMethods()) {
                if (!"cancelAllNotificationsInt".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                // MagicOS 11 / Android 17：(int callingUid, int callingPid, String pkg, String channelId,
                //                          int mustHaveFlags, int mustNotHaveFlags, int userId, int reason)
                if (ps.length != 8 || ps[2] != String.class || ps[7] != int.class) {
                    log("skip unsupported cancelAllNotificationsInt: " + m);
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!feature("keep_notification") || isOff()) return;
                            String pkg = (String) p.args[2];
                            int reason = (Integer) p.args[7];
                            if (pkg == null || isExcluded(pkg)) return;
                            int userId = (Integer) p.args[6];
                            String src;
                            if (reason == REASON_PACKAGE_CHANGED) {
                                src = "force stop";
                            } else if (reason == REASON_APP_CANCEL_ALL
                                    && (Integer) p.args[0] == Process.SYSTEM_UID
                                    && (Integer) p.args[1] == Process.myPid()) {
                                src = "iaware process clean";
                            } else {
                                return;
                            }
                            if (reason == REASON_PACKAGE_CHANGED && pkgUid(pkg, userId < 0 ? 0 : userId) < 0) return; // 真卸载
                            p.args[4] = ((Integer) p.args[4]) | FLAG_ONGOING_EVENT; // 只清常驻通知
                            logEvery("kn:" + reason + ":" + pkg, 600_000L, "keep notifications of " + pkg + " (" + src + ", reason=" + reason + ")");
                        } catch (Throwable e) {
                            logOnce("kne", "keep notification hook: " + e);
                        }
                    }
                });
                n++;
            }
            if (n == 0) throw new NoSuchMethodError("cancelAllNotificationsInt");
        });
    }

    /** 读功能开关，缺省为开 */
    private static boolean feature(String key) {
        long now = SystemClock.elapsedRealtime();
        if (sConfigCheckedAt < 0 || now - sConfigCheckedAt > 10_000L) {
            sConfigCheckedAt = now;
            try {
                long mt = CONFIG_FILE.lastModified();
                if (mt != sConfigMtime) {
                    sConfigMtime = mt;
                    java.util.Properties pr = new java.util.Properties();
                    if (CONFIG_FILE.exists()) {
                        FileInputStream in = new FileInputStream(CONFIG_FILE);
                        pr.load(in);
                        in.close();
                    }
                    sConfig = pr;
                    log("config loaded: " + pr);
                }
            } catch (Throwable e) {
                logOnce("cfe", "read config: " + e);
            }
        }
        return !"0".equals(sConfig.getProperty(key, "1").trim());
    }

    // ================================================================ 1. 夜间防火墙

    private static void hookNightFirewall(ClassLoader cl) {
        safe("NetworkManagementService.setFirewallChainEnabled", () -> {
            Class<?> nms = XposedHelpers.findClass("com.android.server.net.NetworkManagementService", cl);
            XposedHelpers.findAndHookMethod(nms, "setFirewallChainEnabled", int.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        int chain = (Integer) p.args[0];
                        boolean enable = (Boolean) p.args[1];
                        if (chain != FIREWALL_CHAIN_POWERSAVE || isOff() || !feature("block_night_firewall")) return;
                        int pid = Binder.getCallingPid();
                        if (pid == Process.myPid()) {
                            log("allow powersave chain " + (enable ? "ON" : "OFF") + " <- system_server");
                            return;
                        }
                        if (!enable) return;
                        p.setResult(null);
                        long now = SystemClock.elapsedRealtime();
                        sBlockedSinceLog++;
                        if (now - sLastBlockLog >= 5 * 60_000L) {
                            log("BLOCK powersave chain ON <- " + procName(pid) + " (x" + sBlockedSinceLog + ")");
                            sBlockedSinceLog = 0;
                            sLastBlockLog = now;
                        }
                    } catch (Throwable e) {
                        log("chain hook error: " + e);
                    }
                }
            });
        });
    }

    // ================================================================ 2. 推送拉起

    private static void hookPushWake(ClassLoader cl) {
        // 入口：Binder 调用方就是发送方
        for (String cls : new String[]{"com.android.server.am.ActivityManagerService", "com.android.server.am.BroadcastController"}) {
            final String name = cls.substring(cls.lastIndexOf('.') + 1);
            safe(name + ".broadcastIntentWithFeature", () -> {
                Class<?> c = XposedHelpers.findClass(cls, cl);
                int n = 0;
                for (Method m : c.getDeclaredMethods()) {
                    if (!"broadcastIntentWithFeature".equals(m.getName())) continue;
                    final int idx = intentIndex(m);
                    if (idx < 0) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                int uid = Binder.getCallingUid();
                                onFcmBroadcast(p.args[idx], uid > 0 && uid == sGmsUid, "entry");
                            } catch (Throwable e) {
                                logOnce("bce", "broadcast entry hook: " + e);
                            }
                        }
                    });
                    n++;
                }
                if (n == 0) throw new NoSuchMethodError("broadcastIntentWithFeature");
            });
        }
        // 内部：broadcastIntentLocked(Traced)，用 callerPackage 判断发送方
        safe("BroadcastController.broadcastIntentLocked*", () -> {
            Class<?> c = XposedHelpers.findClass("com.android.server.am.BroadcastController", cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"broadcastIntentLocked".equals(mn) && !"broadcastIntentLockedTraced".equals(mn)) continue;
                final int idx = intentIndex(m);
                if (idx < 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            boolean fromGms = p.args.length > 1 && GMS.equals(p.args[1]);
                            if (!fromGms) {
                                int uid = Binder.getCallingUid();
                                fromGms = uid > 0 && uid == sGmsUid;
                            }
                            onFcmBroadcast(p.args[idx], fromGms, "locked");
                        } catch (Throwable e) {
                            logOnce("bcl", "broadcast locked hook: " + e);
                        }
                    }
                });
                n++;
            }
            if (n == 0) throw new NoSuchMethodError("broadcastIntentLocked");
        });
    }

    private static void onFcmBroadcast(Object o, boolean fromGms, String stage) {
        if (!(o instanceof Intent)) return;
        Intent it = (Intent) o;
        if (!FCM_RECEIVE.equals(it.getAction())) return;
        String target = it.getPackage();
        if (target == null && it.getComponent() != null) target = it.getComponent().getPackageName();
        if (target == null || GMS.equals(target)) return;
        if (!fromGms || isOff()) return;
        boolean gmail = GMAIL.equals(target);
        boolean excluded = isExcluded(target);
        if (excluded) {
            logOnce("ex:" + target, "push to excluded " + target + ", not waking" + (gmail ? " (gmail still gets window)" : ""));
        } else if (feature("push_wake") && (it.getFlags() & FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
            it.addFlags(FLAG_INCLUDE_STOPPED_PACKAGES);
        }
        if (!gmail && (excluded || !feature("delivery_window"))) return;
        if ("entry".equals(stage) || !sPkgWindow.containsKey(target)) {
            openWindowPkg(target, "fcm " + stage);
        }
    }

    private static int intentIndex(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) if (ps[i] == Intent.class) return i;
        return -1;
    }

    // ================================================================ 3. 投递窗口

    private static void hookDeliveryWindow(ClassLoader cl) {
        safe("JobStatus", () -> {
            Class<?> js = XposedHelpers.findClass("com.android.server.job.controllers.JobStatus", cl);
            sJsPkg = js.getDeclaredMethod("getSourcePackageName");
            sJsUid = js.getDeclaredMethod("getSourceUid");
            sJsPkg.setAccessible(true);
            sJsUid.setAccessible(true);
            XposedBridge.hookAllMethods(js, "isHnUserExperienceReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (Boolean.TRUE.equals(p.getResult())) return;
                        String pkg = jobPkg(p.thisObject);
                        if (pkg != null && inWindow(pkg, jobUid(p.thisObject))) {
                            p.setResult(Boolean.TRUE);
                            logOnce("ux:" + pkg, "allow HN_USER_EXPERIENCE job of " + pkg);
                        }
                    } catch (Throwable e) {
                        logOnce("uxe", "ux hook: " + e);
                    }
                }
            });
        });

        safe("HnJobResourceController", () -> {
            Class<?> rc = XposedHelpers.findClass("com.android.server.job.controllers.HnJobResourceController", cl);
            XposedBridge.hookAllMethods(rc, "checkIfRestrictLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Boolean.TRUE.equals(p.getResult()) || p.args == null || p.args.length == 0) return;
                        String pkg = jobPkg(p.args[0]);
                        if (pkg != null && inWindow(pkg, jobUid(p.args[0]))) {
                            p.setResult(Boolean.FALSE);
                            logOnce("rc:" + pkg, "unrestrict HnJobResource job of " + pkg);
                        }
                    } catch (Throwable e) {
                        logOnce("rce", "rc hook: " + e);
                    }
                }
            });
        });

        safe("deoptimize job callers", () -> {
            int n = deopt(XposedHelpers.findClass("com.android.server.job.JobSchedulerService", cl), "isReadyToBeExecutedLocked")
                    + deopt(XposedHelpers.findClass("com.android.server.job.JobConcurrencyManager", cl), "stopNonReadyActiveJobsLocked");
            log("deoptimized " + n + " methods");
        });

        safe("PGManagerService.isServiceProxy", () -> {
            Class<?> ls = XposedHelpers.findClass("com.android.server.pg.PGManagerService$LocalService", cl);
            XposedBridge.hookAllMethods(ls, "isServiceProxy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Boolean.TRUE.equals(p.getResult())) return;
                        String pkg = (p.args.length > 1 && p.args[1] instanceof String) ? (String) p.args[1] : null;
                        ComponentName cn = (p.args.length > 0 && p.args[0] instanceof ComponentName) ? (ComponentName) p.args[0] : null;
                        String hit = null;
                        if (pkg != null && inWindow(pkg, -1)) hit = pkg;
                        else if (cn != null && inWindow(cn.getPackageName(), -1)) hit = cn.getPackageName();
                        if (hit != null) {
                            p.setResult(Boolean.FALSE);
                            logOnce("px:" + hit + cn, "unproxy " + cn + " src=" + pkg);
                        }
                    } catch (Throwable e) {
                        logOnce("pxe", "proxy hook: " + e);
                    }
                }
            });
        });

        safe("AMS.tempAllowlistUidLocked", () -> {
            Class<?> ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", cl);
            XposedBridge.hookAllMethods(ams, "tempAllowlistUidLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length < 4 || !(p.args[0] instanceof Integer) || isOff()) return;
                        String why = String.valueOf(p.args[3]);
                        if (!why.contains(FCM_RECEIVE)) return;
                        int uid = (Integer) p.args[0];
                        if (uid < 10000 || uid == sGmsUid) return;
                        if (uid == sGmailUid) {
                            openWindowUid(uid, "temp-allowlist, gmail");
                            return;
                        }
                        if (!feature("delivery_window")) return;
                        String[] pkgs = sUidPkgCache.get(uid);
                        if (pkgs != null) {
                            if (!allExcluded(pkgs)) openWindowUid(uid, "temp-allowlist");
                            return;
                        }
                        // 第一次见到这个 uid：在自己的线程里查包名（不在 AMS 锁里调 PMS），再决定是否放行
                        final Handler h = sHandler;
                        if (h != null) h.post(() -> {
                            String[] ps = uidPkgs(uid);
                            if (ps.length == 0) return;
                            sUidPkgCache.put(uid, ps);
                            if (!allExcluded(ps)) openWindowUid(uid, "temp-allowlist");
                        });
                    } catch (Throwable e) {
                        logOnce("tae", "allowlist hook: " + e);
                    }
                }
            });
        });

        safe("HwPowerManagerService wakelock proxy", () -> {
            Class<?> pmc = XposedHelpers.findClass("com.android.server.power.HwPowerManagerService", cl);
            Method px = pmc.getDeclaredMethod("proxyWakeLockByPidUid", int.class, int.class, boolean.class);
            XposedBridge.hookMethod(px, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        int pid = (Integer) p.args[0];
                        int uid = (Integer) p.args[1];
                        boolean proxy = (Boolean) p.args[2];
                        if (!proxy) {
                            sDeferredWlProxy.remove(pid);
                            return;
                        }
                        if (uid < 10000 || !uidInWindow(uid)) return;
                        sDeferredWlProxy.put(pid, new Object[]{p.thisObject, uid});
                        p.setResult(null);
                        logEvery("wlp:" + uid, 20_000L, "defer wakelock proxy uid=" + uid + " pid=" + pid);
                        scheduleReplay(uid);
                    } catch (Throwable e) {
                        logOnce("wlpe", "wakelock proxy hook: " + e);
                    }
                }
            });
            Method fr = pmc.getDeclaredMethod("forceReleaseWakeLockByPidUid", int.class, int.class);
            XposedBridge.hookMethod(fr, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        int pid = (Integer) p.args[0];
                        int uid = (Integer) p.args[1];
                        if (uid < 10000 || !uidInWindow(uid)) return;
                        p.setResult(null);
                        logEvery("wlr:" + uid, 20_000L, "keep wakelock (block force release) uid=" + uid + " pid=" + pid);
                    } catch (Throwable e) {
                        logOnce("wlre", "wakelock release hook: " + e);
                    }
                }
            });
        });

        safe("SyncManager.ActiveSyncContext", () -> {
            Class<?> asc = XposedHelpers.findClass("com.android.server.content.SyncManager$ActiveSyncContext", cl);
            XposedBridge.hookAllConstructors(asc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        int uid = XposedHelpers.getIntField(p.thisObject, "mSyncAdapterUid");
                        onSyncStart(uid, syncName(p.thisObject));
                    } catch (Throwable e) {
                        logOnce("sse", "sync start hook: " + e);
                    }
                }
            });
            Class<?> sh = XposedHelpers.findClass("com.android.server.content.SyncManager$SyncHandler", cl);
            XposedBridge.hookAllMethods(sh, "runSyncFinishedOrCanceledH", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object ctx = p.args[1];
                        int uid = XposedHelpers.getIntField(ctx, "mSyncAdapterUid");
                        Object r = p.args[0];
                        boolean tracked = sSyncing.containsKey(uid);
                        boolean err = r != null && (Boolean) XposedHelpers.callMethod(r, "hasError");
                        boolean dbErr = r != null && XposedHelpers.getBooleanField(r, "databaseError");
                        String res = r == null ? "canceled" : (err ? "error " + r : "ok");
                        long took = SystemClock.elapsedRealtime() - XposedHelpers.getLongField(ctx, "mStartTime");
                        onSyncEnd(uid, syncName(ctx) + " " + res + " (" + took + "ms)");
                        if (tracked && dbErr) maybeRetrySync(uid, ctx);
                        else if (tracked && r != null && !err) sSyncRetries.remove(uid);
                    } catch (Throwable e) {
                        logOnce("see", "sync end hook: " + e);
                    }
                }
            });
        });

        safe("HnFreezerController.setProcessFrozen", () -> {
            Class<?> fc = XposedHelpers.findClass("com.android.server.am.HnFreezerController", cl);
            XposedBridge.hookAllMethods(fc, "setProcessFrozen", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length < 4) return;
                        int pid = (Integer) p.args[0];
                        int uid = (Integer) p.args[1];
                        boolean freeze = (Boolean) p.args[2];
                        Object reason = p.args[3];
                        if (!(reason instanceof Enum)) return;
                        String rn = ((Enum<?>) reason).name();
                        if (!"PG".equals(rn) && !"IAWARE_FAST".equals(rn)) return;
                        String key = pid + ":" + rn;
                        if (!freeze) {
                            sDeferredFreeze.remove(key);
                            return;
                        }
                        if (!uidInWindow(uid)) return;
                        sDeferredFreeze.put(key, new Object[]{p.thisObject, uid, reason, pid});
                        p.setResult(0);
                        logEvery("fz:" + key, 20_000L, "defer " + rn + " freeze uid=" + uid + " pid=" + pid);
                        scheduleReplay(uid);
                    } catch (Throwable e) {
                        logOnce("fze", "freeze hook: " + e);
                    }
                }
            });
        });
    }

    private static void openWindowPkg(String pkg, String why) {
        long until = SystemClock.elapsedRealtime() + WINDOW_MS;
        Long old = sPkgWindow.get(pkg);
        sPkgWindow.merge(pkg, until, Math::max);
        if (old == null || old < SystemClock.elapsedRealtime()) {
            clearLogKeys(pkg);
            log("open window " + pkg + " (" + why + ")");
        }
        // 异步补上 uid，供冻结推迟使用
        final Handler h = sHandler;
        if (h != null) h.post(() -> {
            int uid = pkgUid(pkg);
            if (uid > 0) {
                noteOpen(uid, until - WINDOW_MS, !uidInWindow(uid));
                sUidWindow.merge(uid, until, Math::max);
                publishWindows();
                scheduleReplay(uid);
            }
        });
    }

    private static void openWindowUid(int uid, String why) {
        long now = SystemClock.elapsedRealtime();
        long until = now + WINDOW_MS;
        boolean fresh = !uidInWindow(uid);
        noteOpen(uid, now, fresh);
        sUidWindow.merge(uid, until, Math::max);
        publishWindows();
        if (fresh) log("open window uid=" + uid + " (" + why + ")");
        final Handler h = sHandler;
        if (h != null) h.post(() -> {
            for (String pkg : uidPkgs(uid)) sPkgWindow.merge(pkg, until, Math::max);
        });
        scheduleReplay(uid);
    }

    private static boolean inWindow(String pkg, int uid) {
        if (isOff()) return false;
        long now = SystemClock.elapsedRealtime();
        Long d = pkg != null ? sPkgWindow.get(pkg) : null;
        if (d != null && now < d) return true;
        return uid > 0 && uidInWindow(uid);
    }

    private static boolean uidInWindow(int uid) {
        if (isOff()) return false;
        Long d = sUidWindow.get(uid);
        return d != null && SystemClock.elapsedRealtime() < d;
    }

    private static void noteOpen(int uid, long now, boolean fresh) {
        if (fresh || !sUidWindowStart.containsKey(uid)) {
            sUidWindowStart.put(uid, now);
            sSyncRetries.remove(uid);
        }
        sUidLastOpen.merge(uid, now, Math::max);
    }

    /** 把 uid 及其包的窗口设到 until（可缩短，用于同步结束后收尾） */
    private static void setWindow(int uid, long until) {
        sUidWindow.put(uid, until);
        publishWindows();
        String[] pkgs = sUidPkgCache.get(uid);
        if (pkgs == null) {
            pkgs = uidPkgs(uid);
            if (pkgs.length > 0) sUidPkgCache.put(uid, pkgs);
        }
        for (String pkg : pkgs) sPkgWindow.put(pkg, until);
    }

    private static void onSyncStart(int uid, String what) {
        if (uid < 10000 || !uidInWindow(uid)) return;
        sSyncing.merge(uid, 1, Integer::sum);
        long start = sUidWindowStart.getOrDefault(uid, SystemClock.elapsedRealtime());
        setWindow(uid, start + WINDOW_MAX_MS);
        log("sync start uid=" + uid + " " + what + ", window extended");
    }

    // ================================================================ 荣耀省电服务（PowerGenie）进程

    private static final String PG_PKG = "com.hihonor.powergenie";
    /** PowerGenie 进程里：最近收到 FCM 的 uid -> 截止时间（elapsedRealtime） */
    private static final Map<Integer, Long> sPgPushUntil = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sPgPkgUid = new ConcurrentHashMap<>();
    private static volatile boolean sPgHooked;
    /** PG 的 ActNet（core.actions.d）实例，第一次调用 o() 时记下，用于推送时解禁网络 */
    private static volatile Object sPgActNet;

    /**
     * PowerGenie 把应用打回休眠（H）时会经 pged 守护进程直接销毁该 uid 的全部 TCP 连接（日志 "cSockets"），
     * 不经过 system_server。锁屏时 Gmail 只有约 1 秒小憩，正在进行的同步请求被掐断 → ERR_CONNECTION_ABORTED →
     * internal-error。这里在 PG 进程内记下谁刚收到 FCM，推送后 30 秒内跳过对它的 cSockets。
     */
    private static void installPowerGenieHooks(ClassLoader cl, String proc) {
        if (sPgHooked) return;
        sPgHooked = true;
        log("loading in " + proc + " (powergenie)");
        safe("PG AshManager.handleBCNotify", () -> {
            Class<?> am = XposedHelpers.findClass("com.hihonor.powergenie.core.hibernation.AshManager", cl);
            XposedBridge.hookAllMethods(am, "handleBCNotify", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 2 || !FCM_RECEIVE.equals(p.args[0])) return;
                        String pkg = (String) p.args[1];
                        if (pkg == null || isOff()) return;
                        if (!GMAIL.equals(pkg) && (isExcluded(pkg) || !feature("delivery_window"))) return;
                        int uid = pgUid(pkg);
                        if (uid < 10000) return;
                        sPgPushUntil.put(uid, SystemClock.elapsedRealtime() + WINDOW_MS);
                        // 休眠时 PG 会以 ASH_DENY_FLAG 禁止该 uid 联网，推送叫醒（H→D）时并不解禁 → 同步零流量卡死
                        Object net = sPgActNet;
                        if (net != null) {
                            XposedHelpers.callMethod(net, "o", uid, false, 1);
                            logEvery("pgallow:" + uid, 20_000L, "allow network on push uid=" + uid);
                        }
                    } catch (Throwable e) {
                        logOnce("pgbc", "PG bc notify hook: " + e);
                    }
                }
            });
        });
        final XC_MethodHook skip = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    int uid = (Integer) p.args[0];
                    if (isOff()) return;
                    if (uid == pgUid(GMAIL)) {
                        p.setResult(null);
                        logEvery("pgcsg", 60_000L, "keep gmail sockets (skip PG cSockets)");
                        return;
                    }
                    long until = pgWindowUntil(uid);
                    if (SystemClock.elapsedRealtime() >= until) return;
                    p.setResult(null);
                    logEvery("pgcs:" + uid, 20_000L, "keep sockets (skip PG cSockets) uid=" + uid);
                } catch (Throwable e) {
                    logOnce("pgcse", "PG cSockets hook: " + e);
                }
            }
        };
        // 推送后的窗口内不让小憩（D）提前结束回到休眠（H）：states.c.K0(delay, z, z) 是安排 D→H 的唯一入口，
        // 默认邮件类应用锁屏只给 1 秒；这里把 delay 至少拉到窗口结束。H 的冻结/撤锁/掐连接/代理都因此推迟。
        safe("PG doze delayToH", () -> {
            Class<?> dz = XposedHelpers.findClass("com.hihonor.powergenie.core.hibernation.states.c", cl);
            int n = 0;
            for (Method m : dz.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (!"K0".equals(m.getName()) || ps.length != 3 || ps[0] != long.class
                        || ps[1] != boolean.class || ps[2] != boolean.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (isOff()) return;
                            Object rec = XposedHelpers.getObjectField(p.thisObject, "b");
                            String pkg = (String) XposedHelpers.callMethod(rec, "I1");
                            int uid = pgUid(pkg);
                            long left = pgWindowUntil(uid) - SystemClock.elapsedRealtime();
                            long delay = (Long) p.args[0];
                            if (left <= delay) return;
                            p.args[0] = left;
                            logEvery("pgnap:" + uid, 20_000L, "extend nap " + pkg + " " + delay + "ms -> " + left + "ms");
                        } catch (Throwable e) {
                            logOnce("pgnape", "PG nap hook: " + e);
                        }
                    }
                });
                n++;
            }
            if (n == 0) throw new NoSuchMethodError("states.c.K0(long,boolean,boolean)");
        });
        // D→H 还有 b1()/c1()/N0() 等直接 sendEmptyMessageDelayed(100) 的入口（亮屏、冷启动后等），不经过 K0。
        // 在 D 状态的 Handler（states.b）真正处理 100 时兜底：窗口内就按剩余时间重新排队，不进 H。
        safe("PG doze handler", () -> {
            Class<?> hb = XposedHelpers.findClass("com.hihonor.powergenie.core.hibernation.states.b", cl);
            XposedBridge.hookAllMethods(hb, "handleMessage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        android.os.Message msg = (android.os.Message) p.args[0];
                        if (msg.what != 100 || isOff()) return;
                        Object doze = XposedHelpers.getObjectField(p.thisObject, "a");
                        Object rec = XposedHelpers.getObjectField(doze, "b");
                        String pkg = (String) XposedHelpers.callMethod(rec, "I1");
                        int uid = pgUid(pkg);
                        long left = pgWindowUntil(uid) - SystemClock.elapsedRealtime();
                        if (left <= 0) return;
                        android.os.Handler h = (android.os.Handler) p.thisObject;
                        h.removeMessages(100);
                        h.sendEmptyMessageDelayed(100, left);
                        p.setResult(null);
                        logEvery("pgnap2:" + uid, 20_000L, "hold nap " + pkg + " +" + left + "ms (window)");
                    } catch (Throwable e) {
                        logOnce("pgnap2e", "PG doze handler hook: " + e);
                    }
                }
            });
        });
        // ActNet.o(uid, deny, flag)：PG 按 flag 记账并经 pged 下发 uid 级禁网。ASH_DENY_FLAG(1) 是休眠禁网：
        // Gmail 一律不禁（它冻结后本来就不发包），其他应用推送窗口内不禁。
        safe("PG ActNet deny network", () -> {
            Class<?> d = XposedHelpers.findClass("com.hihonor.powergenie.core.actions.d", cl);
            int n = 0;
            for (Method m : d.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (!"o".equals(m.getName()) || ps.length != 3 || ps[0] != int.class || ps[1] != boolean.class || ps[2] != int.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            sPgActNet = p.thisObject;
                            int uid = (Integer) p.args[0];
                            boolean deny = (Boolean) p.args[1];
                            int flag = (Integer) p.args[2];
                            if (!deny || (flag & 1) == 0 || isOff()) return;
                            boolean gmail = uid == pgUid(GMAIL);
                            if (!gmail && SystemClock.elapsedRealtime() >= pgWindowUntil(uid)) return;
                            int rest = flag & ~1;
                            if (rest == 0) p.setResult(null);
                            else p.args[2] = rest;
                            logEvery("pgdeny:" + uid, 60_000L, "keep network (skip PG ASH deny) uid=" + uid);
                        } catch (Throwable e) {
                            logOnce("pgdenye", "PG deny hook: " + e);
                        }
                    }
                });
                n++;
            }
            if (n == 0) throw new NoSuchMethodError("core.actions.d.o(int,boolean,int)");
        });
        // 名字没混淆的入口：AppActionService.closeSocketsForUid(uid) / closeSocketsForPid(uid, pid)
        safe("PG AppActionService.closeSockets", () -> {
            Class<?> aas = XposedHelpers.findClass("com.hihonor.powergenie.core.actions.AppActionService", cl);
            int n = XposedBridge.hookAllMethods(aas, "closeSocketsForUid", skip).size()
                    + XposedBridge.hookAllMethods(aas, "closeSocketsForPid", skip).size();
            if (n == 0) throw new NoSuchMethodError("closeSockets*");
        });
        // 混淆名兜底：core.actions.d.a(uid,pid) / b(uid,pid,port)（H 动作直接调用的路径）
        safe("PG ActNet cSockets", () -> {
            Class<?> d = XposedHelpers.findClass("com.hihonor.powergenie.core.actions.d", cl);
            int n = 0;
            for (Method m : d.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getReturnType() != void.class) continue;
                boolean b3 = "b".equals(m.getName()) && ps.length == 3 && ps[0] == int.class && ps[1] == int.class && ps[2] == int.class;
                boolean a2 = "a".equals(m.getName()) && ps.length == 2 && ps[0] == int.class && ps[1] == int.class;
                if (!b3 && !a2) continue;
                XposedBridge.hookMethod(m, skip);
                n++;
            }
            if (n == 0) throw new NoSuchMethodError("core.actions.d cSockets");
        });
    }

    /** 系统框架写入的推送窗口（含同步延长）：Settings.Global，值为 "uid:untilElapsed,..."；elapsedRealtime 全系统一致 */
    private static final String WINDOWS_SETTING = "mosfcmfix_push_windows";
    private static volatile Map<Integer, Long> sPgSharedWindows = Collections.emptyMap();
    private static volatile long sPgSharedReadAt = -1L;

    /** PG 进程内：本地记录（handleBCNotify）与系统框架共享窗口取较晚者 */
    private static long pgWindowUntil(int uid) {
        long local = sPgPushUntil.getOrDefault(uid, 0L);
        long now = SystemClock.elapsedRealtime();
        if (sPgSharedReadAt < 0 || now - sPgSharedReadAt > 300L) {
            sPgSharedReadAt = now;
            Map<Integer, Long> m = new java.util.HashMap<>();
            try {
                Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
                String v = android.provider.Settings.Global.getString(((Context) app).getContentResolver(), WINDOWS_SETTING);
                if (v != null) for (String part : v.split(",")) {
                    int c = part.indexOf(':');
                    if (c > 0) m.put(Integer.parseInt(part.substring(0, c)), Long.parseLong(part.substring(c + 1)));
                }
            } catch (Throwable e) {
                logOnce("pgsw", "PG read shared windows: " + e);
            }
            sPgSharedWindows = m;
        }
        return Math.max(local, sPgSharedWindows.getOrDefault(uid, 0L));
    }

    /** system_server 内：把当前有效的窗口写到 Settings.Global，供 PowerGenie 进程读取 */
    private static final Runnable PUBLISH_WINDOWS = () -> {
        try {
            long now = SystemClock.elapsedRealtime();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Long> e : sUidWindow.entrySet()) {
                if (e.getValue() <= now) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
            Context ctx = systemContext();
            if (ctx != null) android.provider.Settings.Global.putString(ctx.getContentResolver(), WINDOWS_SETTING, sb.toString());
        } catch (Throwable t) {
            logOnce("pubw", "publish windows: " + t);
        }
    };

    private static void publishWindows() {
        final Handler h = sHandler;
        if (h == null) return;
        h.removeCallbacks(PUBLISH_WINDOWS);
        h.post(PUBLISH_WINDOWS);
    }

    private static int pgUid(String pkg) {
        Integer c = sPgPkgUid.get(pkg);
        if (c != null) return c;
        try {
            Object app = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
            int u = ((android.content.Context) app).getPackageManager().getPackageUid(pkg, 0);
            sPgPkgUid.put(pkg, u);
            return u;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 用失败那次同步的账号、authority、extras 由 system_server 重新 requestSync（调用方是 system，权限校验可过） */
    private static void maybeRetrySync(final int uid, Object ctx) {
        try {
            final int n = sSyncRetries.getOrDefault(uid, 0);
            if (n >= SYNC_RETRY_DELAYS.length) {
                log("sync uid=" + uid + " still failing after " + n + " retries, give up");
                return;
            }
            Object op = XposedHelpers.getObjectField(ctx, "mSyncOperation");
            Object target = XposedHelpers.getObjectField(op, "target");
            final android.accounts.Account acct = (android.accounts.Account) XposedHelpers.getObjectField(target, "account");
            final String auth = (String) XposedHelpers.getObjectField(target, "provider");
            final android.os.Bundle extras = (android.os.Bundle) XposedHelpers.callMethod(op, "getClonedExtras");
            // 失败后 SyncManager 会加 backoff 并把加急降为普通任务，重试必须忽略 backoff 才会马上跑
            extras.putBoolean(android.content.ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
            final long delay = SYNC_RETRY_DELAYS[n];
            sSyncRetries.put(uid, n + 1);
            long now = SystemClock.elapsedRealtime();
            long start = sUidWindowStart.getOrDefault(uid, now);
            Long cur = sUidWindow.get(uid);
            long want = Math.min(start + WINDOW_MAX_MS, now + delay + SYNC_TAIL_MS + 2_000L);
            if (cur == null || cur < want) setWindow(uid, want);
            log("sync uid=" + uid + " " + auth + " internal-error, retry #" + (n + 1) + " in " + delay + "ms");
            final Handler h = sHandler;
            if (h == null) return;
            h.postDelayed(() -> {
                try {
                    if (isOff()) return;
                    android.content.ContentResolver.requestSync(acct, auth, extras);
                    log("sync retry #" + (n + 1) + " requested uid=" + uid + " " + auth);
                } catch (Throwable t) {
                    log("sync retry #" + (n + 1) + " failed: " + t);
                }
            }, delay);
        } catch (Throwable t) {
            log("sync retry setup: " + t);
        }
    }

    private static void onSyncEnd(int uid, String what) {
        Integer n = sSyncing.get(uid);
        if (n == null) return;
        if (n > 1) {
            sSyncing.put(uid, n - 1);
            return;
        }
        sSyncing.remove(uid);
        long now = SystemClock.elapsedRealtime();
        long start = sUidWindowStart.getOrDefault(uid, now);
        long last = sUidLastOpen.getOrDefault(uid, start);
        long until = Math.min(start + WINDOW_MAX_MS, Math.max(last + WINDOW_MS, now + SYNC_TAIL_MS));
        setWindow(uid, until);
        log("sync end uid=" + uid + " " + what + ", window closes in " + Math.max(0, until - now) + "ms");
        scheduleReplay(uid);
    }

    /** 窗口结束后补发被推迟的 PG 冻结 */
    private static void scheduleReplay(final int uid) {
        final Handler h = sHandler;
        if (h == null) return;
        Long d = sUidWindow.get(uid);
        long delay = (d == null ? 0 : d - SystemClock.elapsedRealtime()) + 500L;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (uidInWindow(uid)) {
                        Long d2 = sUidWindow.get(uid);
                        h.postDelayed(this, (d2 == null ? 0 : d2 - SystemClock.elapsedRealtime()) + 500L);
                        return;
                    }
                    for (Map.Entry<Integer, Object[]> e : sDeferredWlProxy.entrySet()) {
                        Object[] v = e.getValue();
                        if ((Integer) v[1] != uid) continue;
                        sDeferredWlProxy.remove(e.getKey());
                        XposedHelpers.callMethod(v[0], "proxyWakeLockByPidUid", e.getKey(), uid, true);
                        log("window closed, replay wakelock proxy uid=" + uid + " pid=" + e.getKey());
                    }
                    for (Map.Entry<String, Object[]> e : sDeferredFreeze.entrySet()) {
                        Object[] v = e.getValue();
                        if ((Integer) v[1] != uid) continue;
                        sDeferredFreeze.remove(e.getKey());
                        Object r = XposedHelpers.callMethod(v[0], "setProcessFrozen", v[3], uid, true, v[2]);
                        log("window closed, replay " + ((Enum<?>) v[2]).name() + " freeze uid=" + uid + " pid=" + v[3] + " result=" + r);
                    }
                } catch (Throwable t) {
                    log("replay: " + t);
                }
            }
        }, Math.max(delay, 500L));
    }

    // ================================================================ 4. 网络变化后检查推送连接
    //
    // GMS 自己会在"默认网络切换"时立刻在新网络上重连（实测 01:07:06 Active network switched → Connected），
    // 但默认网络没变、只是旁边多/少了一个网络（例如 Wi‑Fi 还连着、移动数据被拉起又拆掉）时，它只安排一次
    // 延后检查（"Reconnect alarm delay: 88685 ms"），这个闹钟会被荣耀推迟，于是出现"假在线"。
    // 这里只在这种情况下给 GMS 发一次心跳请求：连接好就几秒回来，连接已死则 GMS 立刻重连。5 分钟最多一次。

    private static final String MCS_HEARTBEAT = "com.google.android.intent.action.MCS_HEARTBEAT";

    private static final Runnable REGISTER_NET = new Runnable() {
        @Override
        public void run() {
            try {
                final Context ctx = systemContext();
                final ConnectivityManager cm = ctx == null ? null : (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) {
                    if (sHandler != null) sHandler.postDelayed(this, 30_000L);
                    return;
                }
                cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(Network network) {
                                onSideNetworkChanged(ctx, cm, network, "available");
                            }

                            @Override
                            public void onLost(Network network) {
                                onSideNetworkChanged(ctx, cm, network, "lost");
                            }
                        }, sHandler);
                log("registered network callback");
            } catch (Throwable e) {
                log("register net callback failed: " + e);
            }
        }
    };

    private static void onSideNetworkChanged(Context ctx, ConnectivityManager cm, Network network, String what) {
        try {
            Network def = cm.getActiveNetwork();
            // 默认网络本身变化交给 GMS 自己处理
            if (def == null || def.equals(network)) return;
            requestHeartbeat(ctx, "non-default network " + network + " " + what + ", default=" + def);
        } catch (Throwable e) {
            logOnce("nce", "net callback: " + e);
        }
    }

    private static void requestHeartbeat(final Context ctx, String why) {
        if (isOff() || !feature("reconnect")) return;
        long now = SystemClock.elapsedRealtime();
        if (now - sLastReconnect < 5 * 60_000L) return;
        sLastReconnect = now;
        sHandler.postDelayed(() -> {
            try {
                Intent i = new Intent(MCS_HEARTBEAT).setPackage(GMS);
                ctx.sendBroadcastAsUser(i, Process.myUserHandle());
                log("sent MCS_HEARTBEAT (" + why + ")");
            } catch (Throwable e) {
                log("send heartbeat failed: " + e);
            }
        }, 5_000L);
    }

    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (cur == null) return null;
            return (Context) XposedHelpers.callMethod(cur, "getSystemContext");
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ 公共

    /**
     * Gmail 的连接被系统强行销毁后（冻结时 ConnectivityService 的 InetDiagMessage.destroyLiveTcpSockets*），
     * 下次推送它仍复用死连接 → ERR_CONNECTION_ABORTED → GIG 同步卡死（不会自己重连）。
     * 这里让 Gmail 的 uid 不被销毁连接（冻结照常）。类在 connectivity APEX 的类加载器里，开机后再取。
     */
    private static volatile boolean sSocketHooked;
    private static final Runnable HOOK_SOCKET_DESTROY = new Runnable() {
        @Override
        public void run() {
            if (sSocketHooked) return;
            try {
                Object b = XposedHelpers.callStaticMethod(Class.forName("android.os.ServiceManager"), "getService", "connectivity");
                if (b == null) throw new IllegalStateException("connectivity not ready");
                ClassLoader ccl = b.getClass().getClassLoader();
                Class<?> idm = Class.forName("android.net.connectivity.com.android.net.module.util.netlink.InetDiagMessage", false, ccl);
                int n = 0;
                for (Method m : idm.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if ("destroyLiveTcpSocketsByOwnerUids".equals(m.getName()) && ps.length == 1 && Set.class.isAssignableFrom(ps[0])) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            @SuppressWarnings("unchecked")
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    int g = sGmailUid;
                                    if (g < 0 || isOff() || !(p.args[0] instanceof Set)) return;
                                    Set<Integer> uids = (Set<Integer>) p.args[0];
                                    if (!uids.contains(g)) return;
                                    Set<Integer> copy = new HashSet<>(uids);
                                    copy.remove(g);
                                    logEvery("sockd:" + g, 60_000L, "keep gmail sockets (skip destroy on freeze)");
                                    if (copy.isEmpty()) p.setResult(null);
                                    else p.args[0] = copy;
                                } catch (Throwable e) {
                                    logOnce("sockde", "socket destroy hook: " + e);
                                }
                            }
                        });
                        n++;
                    } else if ("destroyLiveTcpSockets".equals(m.getName()) && ps.length == 2
                            && Set.class.isAssignableFrom(ps[0]) && Set.class.isAssignableFrom(ps[1])) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            @SuppressWarnings("unchecked")
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    int g = sGmailUid;
                                    if (g < 0 || isOff()) return;
                                    Set<Integer> ex = p.args[1] instanceof Set ? new HashSet<>((Set<Integer>) p.args[1]) : new HashSet<>();
                                    if (ex.add(g)) {
                                        p.args[1] = ex;
                                        logEvery("sockr:" + g, 60_000L, "keep gmail sockets (exempt from range destroy)");
                                    }
                                } catch (Throwable e) {
                                    logOnce("sockre", "socket range destroy hook: " + e);
                                }
                            }
                        });
                        n++;
                    }
                }
                if (n == 0) throw new NoSuchMethodError("InetDiagMessage.destroyLiveTcpSockets*");
                sSocketHooked = true;
                log("hooked InetDiagMessage.destroyLiveTcpSockets (" + n + ")");
            } catch (Throwable e) {
                logOnce("sockh", "skip InetDiagMessage hook: " + e);
                if (sHandler != null) sHandler.postDelayed(this, 30_000L);
            }
        }
    };

    private static final Runnable RESOLVE_GMS = new Runnable() {
        @Override
        public void run() {
            int g = pkgUid(GMAIL);
            if (g > 0 && g != sGmailUid) {
                sGmailUid = g;
                log("gmail uid=" + g);
            }
            int u = pkgUid(GMS);
            if (u > 0) {
                sGmsUid = u;
                log("gms uid=" + u);
            }
            if ((u <= 0 || g <= 0) && sHandler != null) {
                sHandler.postDelayed(this, 30_000L);
            }
        }
    };

    private static final Runnable ENSURE_FILES = () -> {
        try {
            if (!DIR.exists()) DIR.mkdirs();
            if (!EXCLUDE_FILE.exists()) {
                FileOutputStream out = new FileOutputStream(EXCLUDE_FILE);
                out.write(("# FCMFix MagicOS 推送拉起例外名单：每行一个包名，这些应用处于停止状态时不会被推送拉起。\n"
                        + "# 修改后 10 秒内生效，无需重启。\n").getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable e) {
            log("ensure files: " + e);
        }
    };

    private static boolean isExcluded(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (sExcludeCheckedAt < 0 || now - sExcludeCheckedAt > 10_000L) {
            sExcludeCheckedAt = now;
            try {
                long mt = EXCLUDE_FILE.lastModified();
                if (mt != sExcludeMtime) {
                    sExcludeMtime = mt;
                    Set<String> s = new HashSet<>();
                    if (EXCLUDE_FILE.exists()) {
                        BufferedReader r = new BufferedReader(new FileReader(EXCLUDE_FILE));
                        String line;
                        while ((line = r.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty() && !line.startsWith("#")) s.add(line);
                        }
                        r.close();
                    }
                    sExclude = s;
                    log("push exclude list loaded: " + s.size() + " pkgs");
                }
            } catch (Throwable e) {
                logOnce("exe", "read exclude: " + e);
            }
        }
        return sExclude.contains(pkg);
    }

    private static int pkgUid(String pkg) {
        return pkgUid(pkg, 0);
    }

    private static int pkgUid(String pkg, int userId) {
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Object pm = XposedHelpers.callStaticMethod(ag, "getPackageManager");
            Object u = XposedHelpers.callMethod(pm, "getPackageUid", pkg, 0L, userId);
            return u instanceof Integer ? (Integer) u : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String syncName(Object activeSyncContext) {
        try {
            Object op = XposedHelpers.getObjectField(activeSyncContext, "mSyncOperation");
            Object target = XposedHelpers.getObjectField(op, "target");
            return String.valueOf(XposedHelpers.getObjectField(target, "provider"));
        } catch (Throwable e) {
            return "?";
        }
    }

    private static boolean allExcluded(String[] pkgs) {
        for (String p : pkgs) if (!isExcluded(p)) return false;
        return true;
    }

    private static String[] uidPkgs(int uid) {
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Object pm = XposedHelpers.callStaticMethod(ag, "getPackageManager");
            Object r = XposedHelpers.callMethod(pm, "getPackagesForUid", uid);
            return r instanceof String[] ? (String[]) r : new String[0];
        } catch (Throwable e) {
            return new String[0];
        }
    }

    private static String jobPkg(Object job) {
        try {
            return job == null || sJsPkg == null ? null : (String) sJsPkg.invoke(job);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int jobUid(Object job) {
        try {
            Object u = job == null || sJsUid == null ? null : sJsUid.invoke(job);
            return u instanceof Integer ? (Integer) u : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int deopt(Class<?> c, String name) {
        int n = 0;
        for (Member m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            try { XposedBridge.deoptimizeMethod(m); n++; } catch (Throwable e) { log("deopt " + name + ": " + e); }
        }
        return n;
    }

    private static String procName(int pid) {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/" + pid + "/cmdline");
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return "pid" + pid;
            int end = 0;
            while (end < n && b[end] != 0) end++;
            return new String(b, 0, end, "UTF-8");
        } catch (Throwable e) {
            return "pid" + pid;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static boolean isOff() {
        long now = SystemClock.elapsedRealtime();
        if (sOffCheckedAt >= 0 && now - sOffCheckedAt < 5_000L) return sOff;
        sOffCheckedAt = now;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = XposedHelpers.callStaticMethod(sp, "getBoolean", OFF_PROP, false);
            sOff = Boolean.TRUE.equals(v);
        } catch (Throwable e) {
            sOff = false;
        }
        return sOff;
    }

    private interface Body { void run() throws Throwable; }

    private static void safe(String name, Body b) {
        try {
            b.run();
            log("hooked " + name);
        } catch (Throwable e) {
            log("skip " + name + ": " + e);
        }
    }

    private static void clearLogKeys(String pkg) {
        for (String k : sLogged) if (k.contains(pkg)) sLogged.remove(k);
    }

    private static void logOnce(String key, String s) {
        if (sLogged.add(key)) log(s);
    }

    private static final Map<String, Long> sLogAt = new ConcurrentHashMap<>();

    /** 同一个 key 在 intervalMs 内只记一次 */
    private static void logEvery(String key, long intervalMs, String s) {
        long now = SystemClock.elapsedRealtime();
        Long last = sLogAt.get(key);
        if (last != null && now - last < intervalMs) return;
        sLogAt.put(key, now);
        log(s);
    }

    private static void log(String s) {
        try { Log.i(TAG, s); } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) { }
        synchronized (LOG_LOCK) {
            FileOutputStream out = null;
            try {
                if (LOG_FILE.length() > 1024 * 1024) {
                    File old = new File(LOG_FILE.getPath() + ".old");
                    old.delete();
                    LOG_FILE.renameTo(old);
                }
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                out = new FileOutputStream(LOG_FILE, true);
                out.write((ts + " " + s + "\n").getBytes("UTF-8"));
            } catch (Throwable ignored) {
            } finally {
                try { if (out != null) out.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
