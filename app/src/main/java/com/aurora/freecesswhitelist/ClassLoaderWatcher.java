package com.aurora.freecesswhitelist;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 兜底发现机制：监听 system_server 的类加载，凡是名字里带 “freecess” 的类，
 * 一被加载就自动挂钩 —— 这样即使三星把类放在
 * com.samsung.android.server.freecess、或者换了包名、换了类名，
 * 也依然能被抓到，不需要改代码重编。
 *
 * 安全性设计：
 * · 只在启动后的 10 分钟窗口内工作，之后自动卸载 Hook，避免长期拖慢类加载；
 * · 先做一次极廉价的字符串过滤（绝大多数类一次 indexOf 就返回了）；
 * · 命中后不立即挂钩，而是丢给 Worker 线程延迟 150ms 再挂 ——
 *   避免在 ART 类加载流程内部调用 hookMethod 引发挂起重入；
 * · 全部逻辑 try-catch，异常只写日志。
 */
public final class ClassLoaderWatcher {

    /** 监听窗口：10 分钟。Freecess 相关类通常在开机 1~2 分钟内就会被加载 */
    private static final long WATCH_WINDOW_MS = 10 * 60 * 1000L;

    private static volatile long sDeadline;

    private static volatile boolean sInstalled;

    private static volatile XC_MethodHook.Unhook sUnhook;

    /** 已发现过的类名，避免同一个类被 loadClass 多次触发时重复打日志/重复派发 */
    private static final java.util.Set<String> SEEN =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private ClassLoaderWatcher() {
    }

    public static void install() {
        if (sInstalled) return;
        sInstalled = true;
        sDeadline = SystemClock.uptimeMillis() + WATCH_WINDOW_MS;
        try {
            // ClassLoader.loadClass(String, boolean) 是所有类加载的必经之路
            Method m = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            sUnhook = XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        onClassLoaded(param);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XLog.i("ClassLoader 监听已安装（窗口 " + (WATCH_WINDOW_MS / 60000) + " 分钟）");
        } catch (Throwable t) {
            XLog.e("ClassLoader 监听安装失败（改用候选类名扫描）", t);
        }
    }

    private static void onClassLoaded(XC_MethodHook.MethodHookParam param) {
        if (param == null || param.args == null || param.args.length < 1) return;
        if (param.hasThrowable()) return;

        Object a0 = param.args[0];
        if (!(a0 instanceof String)) return;
        String name = (String) a0;
        if (name == null || name.length() < 8) return;

        // 极廉价预过滤：Freecess / freecess / FreeCess 都至少包含 "ecess"
        if (name.indexOf("ecess") < 0 && name.indexOf("eCess") < 0 && name.indexOf("ECess") < 0) {
            return;
        }
        if (!name.toLowerCase(Locale.ROOT).contains("freecess")) return;

        if (SystemClock.uptimeMillis() > sDeadline) {
            stopAsync();
            return;
        }

        Object result = param.getResult();
        if (!(result instanceof Class)) return;

        if (!SEEN.add(name)) return;

        final Class<?> clazz = (Class<?>) result;
        XLog.i("捕获到 Freecess 相关类: " + name);
        Worker.submit(new Runnable() {
            @Override
            public void run() {
                FreecessPatcher.hookClass(clazz);
            }
        });
    }

    private static void stopAsync() {
        XC_MethodHook.Unhook u = sUnhook;
        if (u == null) return;
        sUnhook = null;
        Worker.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    u.unhook();
                    XLog.i("ClassLoader 监听窗口结束，已卸载");
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
