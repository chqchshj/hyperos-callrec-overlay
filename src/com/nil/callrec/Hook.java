package com.nil.callrec;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 接通电话 / 三方应用（微信、QQ…）语音通话开始时，把录音机的
 * 「录音中 · 实时转文字」小窗浮到通话页面上，并自动把转写面板打开。
 *
 * 两条触发链：
 *   1) 普通电话：com.android.incallui 的 RecorderNotificationMgr#setRecordIconVisibility(true)
 *      → 录音发起方 = com.android.incallui
 *   2) 微信等三方通话：com.miui.audiomonitor（MIUI「应用通话录音」服务）
 *      MiuiVoipRecorderService#createUIManger 只在系统判定「可以录音」时才被调用
 *      → 录音发起方 = com.miui.audiomonitor
 *      录音机里的 RecorderActivityProxy 专门为 com.miui.audiomonitor 写了特判分支，
 *      所以同一套窗口/转写逻辑可以直接复用。
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "CallRecAuto: ";
    private static final int L = 20, T = 110, R = 1240, B = 2259;   // 画布尺寸，实际显示 = ×FREEFORM_SCALE
    private static final float FREEFORM_SCALE = 0.47f;   // 目标小窗矩形
    private static final int WINDOWING_FREEFORM = 5;

    private static final String PKG_INCALL = "com.android.incallui";
    private static final String PKG_RECORDER = "com.android.soundrecorder";
    private static final String PKG_AUDIOMONITOR = "com.miui.audiomonitor";

    /** 走「应用通话录音」的三方 App，与 MIUI 设置页里的白名单一致。 */
    private static final String[] VOIP_APPS = {
            "com.tencent.mm",            // 微信
            "com.tencent.mobileqq",      // QQ
            "com.tencent.tim",           // TIM
            "com.tencent.wework",        // 企业微信
            "com.alibaba.android.rimet", // 钉钉
            "com.ss.android.lark",       // 飞书
            "com.tencent.wemeet.app",    // 腾讯会议
            "com.xiaomi.mimobile",       // 小米办公/米聊
    };

    private static long sLastLaunchMs = 0L;      // 普通电话去抖
    private static long sLastVoipLaunchMs = 0L;  // 应用通话去抖
    private static ClassLoader sCl;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            if (PKG_INCALL.equals(lp.packageName)) {
                hookInCall(lp);
            } else if (PKG_RECORDER.equals(lp.packageName)) {
                hookRecorder(lp);
            } else if (PKG_AUDIOMONITOR.equals(lp.packageName)) {
                hookAudioMonitor(lp);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "handleLoadPackage failed: " + t);
        }
    }

    // ---------- 1) InCallUI 侧：普通电话录音一开始就拉起录音机小窗 ----------
    private void hookInCall(final XC_LoadPackage.LoadPackageParam lp) {
        sCl = lp.classLoader;
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    boolean visible = (Boolean) param.args[0];
                    if (!visible) return;                 // 只有开始录音时是 true
                    long now = System.currentTimeMillis();
                    if (now - sLastLaunchMs < 15000L) return;
                    sLastLaunchMs = now;
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            launchRecorderWindow(resolveContext(), PKG_INCALL, 0);
                        }
                    }, 150L);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "hook cb error: " + t);
                }
            }
        };
        XposedHelpers.findAndHookMethod("com.android.incallui.recorder.RecorderNotificationMgr",
                lp.classLoader, "setRecordIconVisibility", boolean.class, cb);
        XposedBridge.log(TAG + "InCallUI hook installed");
    }

    // ---------- 2) 应用通话录音侧：微信等三方通话 ----------
    private void hookAudioMonitor(XC_LoadPackage.LoadPackageParam lp) {
        sCl = lp.classLoader;
        final String svc = "com.miui.audiomonitor.voiprecord.MiuiVoipRecorderService";

        // createUIManger(requestState, pkg) 只在系统判定「这通通话可以录音」之后才调用，
        // 比 onStartCommand 可靠（来电响铃阶段也会走 onStartCommand）。
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object pkgArg = param.args.length > 1 ? param.args[1] : null;
                    String pkg = pkgArg instanceof String ? (String) pkgArg : null;
                    XposedBridge.log(TAG + "audiomonitor createUIManger pkg=" + pkg
                            + " state=" + param.args[0]);
                    if (!isVoipApp(pkg)) return;
                    final Context ctx = param.thisObject instanceof Context
                            ? (Context) param.thisObject : resolveContext();
                    if (ctx == null) {
                        XposedBridge.log(TAG + "audiomonitor context is null, skip");
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (now - sLastVoipLaunchMs < 15000L) return;
                    sLastVoipLaunchMs = now;
                    final String fromPkg = PKG_AUDIOMONITOR;
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            launchRecorderWindow(ctx, fromPkg, 0);
                        }
                    }, 800L);   // 等它真正开始录音，录音机才认领得到
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "audiomonitor cb error: " + t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(svc, lp.classLoader,
                    "createUIManger", int.class, String.class, cb);
            XposedBridge.log(TAG + "audiomonitor hook installed (createUIManger)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook createUIManger failed: " + t + " , fallback to onStartCommand");
            try {
                XposedHelpers.findAndHookMethod(svc, lp.classLoader,
                        "onStartCommand", Intent.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Intent it = (Intent) param.args[0];
                                    if (it == null || param.thisObject == null) return;
                                    String pkg = it.getStringExtra("pkg");
                                    int state = it.getIntExtra("state", -1);
                                    XposedBridge.log(TAG + "audiomonitor onStartCommand pkg=" + pkg
                                            + " state=" + state);
                                    if (state != 1 || !isVoipApp(pkg)) return;
                                    long now = System.currentTimeMillis();
                                    if (now - sLastVoipLaunchMs < 15000L) return;
                                    sLastVoipLaunchMs = now;
                                    final Context ctx = (Context) param.thisObject;
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override public void run() {
                                            launchRecorderWindow(ctx, PKG_AUDIOMONITOR, 0);
                                        }
                                    }, 1500L);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "audiomonitor onStartCommand error: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + "audiomonitor hook installed (onStartCommand)");
            } catch (Throwable t2) {
                XposedBridge.log(TAG + "audiomonitor fallback hook failed: " + t2);
            }
        }
    }

    private static boolean isVoipApp(String pkg) {
        if (pkg == null) return false;
        for (String p : VOIP_APPS) if (p.equals(pkg)) return true;
        return false;
    }

    private void launchRecorderWindow(Context ctx, String fromPkg, int extraDelayMs) {
        try {
            if (ctx == null) {
                XposedBridge.log(TAG + "context is null, skip");
                return;
            }
            Intent it = new Intent("miui.intent.action.recorder.record");
            it.setPackage(PKG_RECORDER);
            it.putExtra("extra_is_from_notify", true);
            Bundle bd = new Bundle();
            bd.putBoolean("extra_from_component", true);
            bd.putString("extra_from_package_name", fromPkg);   // ★ 必须是真实发起方
            it.putExtra("extra_data_bundle", bd);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            Bundle opts = null;
            try {
                Object ao = ActivityOptions.makeBasic();
                ActivityOptions.class.getMethod("setLaunchBounds", Rect.class)
                        .invoke(ao, new Rect(L, T, R, B));
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                        .invoke(ao, WINDOWING_FREEFORM);
                // MIUI: 小窗按比例的真正做法 —— 画布铺满 + freeformScale 缩放 + normalFreeForm=false（mini）
                Object inj = ActivityOptions.class.getMethod("getActivityOptionsInjector").invoke(ao);
                inj.getClass().getMethod("setFreeformScale", float.class).invoke(inj, FREEFORM_SCALE);
                inj.getClass().getMethod("setNormalFreeForm", boolean.class).invoke(inj, false);
                opts = (Bundle) ActivityOptions.class.getMethod("toBundle").invoke(ao);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "activity options failed: " + t);
            }
            ctx.startActivity(it, opts);
            XposedBridge.log(TAG + "recorder window launched from=" + fromPkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "launch failed: " + t);
        }
    }

    private static Context resolveContext() {
        // 1) InCallApp.getInstance()
        try {
            Class<?> c = XposedHelpers.findClass("com.android.incallui.InCallApp", sCl);
            Object o = XposedHelpers.callStaticMethod(c, "getInstance");
            if (o instanceof Context) return (Context) o;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "InCallApp.getInstance failed: " + t);
        }
        // 2) ActivityThread.currentApplication()
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object o = at.getMethod("currentApplication").invoke(null);
            if (o instanceof Context) return (Context) o;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ActivityThread.currentApplication failed: " + t);
        }
        return null;
    }

    // ---------- 3) 录音机侧：把「显示文本」按下去，切到实时转写面板 ----------
    private void hookRecorder(XC_LoadPackage.LoadPackageParam lp) {
        sCl = lp.classLoader;
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) {
                    String fromPkg = fromPackageOf(param.args.length > 0 ? param.args[0] : null);
                    scheduleShowText((Activity) param.thisObject, fromPkg);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("com.android.soundrecorder.SoundRecorder",
                    lp.classLoader, "onCreate", Bundle.class, cb);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook onCreate failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("com.android.soundrecorder.SoundRecorder",
                    lp.classLoader, "onNewIntent", Intent.class, cb);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook onNewIntent failed: " + t);
        }
        XposedBridge.log(TAG + "SoundRecorder hook installed");
    }

    /** 从启停 Intent 的嵌套 Bundle 里取发起方包名；onCreate 的 Bundle 不是 Intent。 */
    private static String fromPackageOf(Object arg) {
        try {
            if (!(arg instanceof Intent)) return null;
            Bundle bd = ((Intent) arg).getBundleExtra("extra_data_bundle");
            if (bd == null) return null;
            return bd.getString("extra_from_package_name");
        } catch (Throwable t) {
            return null;
        }
    }

    private void scheduleShowText(final Activity act, final String fromPkg) {
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final boolean voip = PKG_AUDIOMONITOR.equals(fromPkg);
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    tries[0]++;
                    if (!isRecording()) {
                        if (android.os.SystemClock.elapsedRealtime() - t0 < 5000L) h.postDelayed(this, 150L);
                        return;
                    }
                    View root = act.getWindow().getDecorView();
                    if (findByDesc(root, "隐藏文本框") != null) return;   // 已经是文字模式
                    View btn = findByDesc(root, "显示文本");
                    if (btn != null && btn.isShown()) {
                        String id = "?";
                        try {
                            id = act.getResources().getResourceEntryName(btn.getId());
                        } catch (Throwable ignore) {
                        }
                        btn.performClick();
                        XposedBridge.log(TAG + "clicked 显示文本 id=" + id + " (try " + tries[0]
                                + ", +" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms)");
                        return;
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "showText error: " + t);
                }
                if (android.os.SystemClock.elapsedRealtime() - t0 < 5000L) h.postDelayed(this, 150L);
            }
        };
        // 应用通话的 ASR 起得比电话慢，默认晚一点
        int delayMs = voip ? 1800 : 1500;
        try {
            delayMs = android.provider.Settings.Global.getInt(act.getContentResolver(),
                    voip ? "callrec_voip_click_delay_ms" : "callrec_click_delay_ms", delayMs);
        } catch (Throwable ignore) {
        }
        XposedBridge.log(TAG + "click scheduled in " + delayMs + "ms (from=" + fromPkg + ")");
        h.postDelayed(r, delayMs);
    }

    /** 录音机进程内判断“现在是否正在录音”，不需要任何权限。 */
    private static boolean isRecording() {
        try {
            Class<?> c = XposedHelpers.findClass("com.xiaomi.xms.ai.recorder.AIRecorderManager", sCl);
            Object mgr = XposedHelpers.getStaticObjectField(c, "instance");
            Object r = XposedHelpers.callMethod(mgr, "isRecording");
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "isRecording failed: " + t);
            return false;
        }
    }

    private static View findByDesc(View v, String desc) {
        if (v == null) return null;
        CharSequence cd = v.getContentDescription();
        if (cd != null && desc.contentEquals(cd)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByDesc(g.getChildAt(i), desc);
                if (r != null) return r;
            }
        }
        return null;
    }
}
