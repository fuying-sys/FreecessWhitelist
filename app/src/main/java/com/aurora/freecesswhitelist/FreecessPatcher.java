package com.aurora.freecesswhitelist;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 核心：给 Freecess 相关类里的“冻结动作 / 冻结决策”方法挂钩。
 *
 * 设计思路（很重要，决定了为什么不会崩 system_server）：
 *
 * 1. 不写死方法名。不同 ROM 的 FreecessController 方法名可能是
 *    freezeProcess / freezePackage / doFreeze / enterFreecess …… 我们按
 *    “方法名语义 + 返回值类型”分类，一次扫描类里所有方法，命中的才挂。
 *
 * 2. Hook 只做两件事：
 *    · 动作类（返回 void 的 freeze/kill/断网方法）→ 命中白名单就 setResult 跳过原方法；
 *    · 决策类（canFreeze / allowFreeze / shouldFreeze，返回 boolean）→ 命中白名单就返回 false，
 *      让 Freecess 自己在策略层就认为“这个应用不能冻”，从源头避免后续动作。
 *
 * 3. 找不到类、找不到方法、反射失败、参数解析失败 —— 全部静默跳过。
 *    最坏情况是“模块没生效”，而不是“系统崩溃”。
 *
 * 4. 所有 Hook 回调都被 try-catch 包住，异常只写日志，绝不外抛。
 */
public final class FreecessPatcher {

    /**
     * 候选类名（按 One UI 4.x 的常见位置排列）。
     * 即使这里全部猜错，ClassLoaderWatcher 也会在真实的 Freecess 类被加载时补挂。
     */
    private static final String[] CANDIDATE_CLASSES = {
            "com.android.server.freecess.FreecessController",
            "com.android.server.freecess.FreecessManagerService",
            "com.android.server.freecess.FreecessPolicy",
            "com.android.server.freecess.FreecessManager",
            "com.android.server.freecess.FreecessHandler",
            "com.android.server.am.FreecessController",
            "com.android.server.power.FreecessController",
            "com.samsung.android.server.freecess.FreecessController",
            "com.samsung.android.server.freecess.FreecessManagerService",
            "com.samsung.android.server.freecess.FreecessPolicy"
    };

    private static final int KIND_NONE = 0;
    /** 真正执行冻结/杀进程/断网的动作方法 */
    private static final int KIND_ACTION = 1;
    /** 返回 boolean 的“能不能冻”决策方法 */
    private static final int KIND_DECISION = 2;

    private static final Set<String> HOOKED_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final Set<String> HOOKED_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final AtomicInteger HOOK_COUNT = new AtomicInteger();

    /** 调试开关：把每一次“放行”的调用也打出来，方便确认模块确实看到了冻结请求 */
    private static final boolean LOG_ALL_ACTION_CALLS = true;

    private FreecessPatcher() {
    }

    public static int hookCount() {
        return HOOK_COUNT.get();
    }

    public static Set<String> hookedMethods() {
        return HOOKED_METHODS;
    }

    /** 按候选名尝试加载并挂钩（启动时、以及 SystemServer 关键节点各调一次） */
    public static void scanCandidates(ClassLoader cl) {
        if (cl == null) return;
        for (String name : CANDIDATE_CLASSES) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                hookClass(c);
            } catch (Throwable ignored) {
                // 类不存在是常态，静默
            }
        }
    }

    /**
     * 兜底扫描线程：每 5 秒重试一次候选类名，最长 10 分钟。
     *
     * 为什么需要它：
     * LSPosed 1.8.3（很多 ROM 内置、无法升级）对 Hook java.lang.ClassLoader 的支持
     * 不一定可靠；一旦 ClassLoaderWatcher 装不上，就只剩“候选类名 + SystemServer 补扫”
     * 两条路。这个线程不依赖任何 Hook，纯靠 Class.forName 轮询，属于最后一道保险：
     * 只要类名在候选列表里，哪怕类加载得晚，也一定会被挂上。
     *
     * 一旦成功挂到方法（hookCount > 0）立刻退出，不常驻、不耗电。
     */
    public static void startFallbackScanner(final ClassLoader cl) {
        if (cl == null) return;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    final long start = SystemClock.uptimeMillis();
                    final long deadline = start + 10 * 60 * 1000L;
                    boolean warned = false;

                    while (SystemClock.uptimeMillis() < deadline) {
                        try {
                            Thread.sleep(5000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (HOOK_COUNT.get() > 0) {
                            XLog.i("兜底轮询结束：已成功挂钩 " + HOOK_COUNT.get() + " 个方法");
                            return;
                        }
                        scanCandidates(cl);

                        long elapsed = SystemClock.uptimeMillis() - start;
                        if (!warned && elapsed > 60_000L) {
                            warned = true;
                            XLog.w("已等待 " + (elapsed / 1000) + " 秒仍未发现可挂钩的方法。"
                                    + "若日志中也没有『捕获到 Freecess 相关类』，"
                                    + "说明 LSPosed 没有把模块注入 system_server（检查启用状态/作用域/Shamiko 模式）");
                        }
                    }
                    if (HOOK_COUNT.get() == 0) {
                        XLog.w("兜底轮询已超时（10 分钟），仍未挂到任何方法；"
                                + "请把 services.jar 里 freecess 相关类名反馈给我，或改用日志排查");
                    }
                }
            }, "freecess-wl-scan");
            t.setDaemon(true);
            t.start();
            XLog.i("候选类兜底轮询已启动（每 5s 一次，最长 10 分钟）");
        } catch (Throwable t) {
            XLog.w("兜底轮询启动失败: " + t);
        }
    }

    /**
     * 给一个类挂上所有“冻结相关”方法。幂等，可重复调用。
     */
    public static void hookClass(Class<?> clazz) {
        if (clazz == null) return;

        String className;
        try {
            className = clazz.getName();
        } catch (Throwable t) {
            return;
        }
        if (className == null) return;

        // 同一个类只处理一次，避免重复挂钩
        if (!HOOKED_CLASSES.add(className)) return;

        try {
            Method[] methods = clazz.getDeclaredMethods();
            int installed = 0;
            for (Method m : methods) {
                if (m == null) continue;
                try {
                    if (m.isSynthetic() || m.isBridge()) continue;
                    int kind = classify(m);
                    if (kind == KIND_NONE) continue;
                    if (hookMethod(m, kind)) installed++;
                } catch (Throwable t) {
                    XLog.w("挂钩失败 " + className + "#" + safeName(m) + " : " + t);
                }
            }
            HOOK_COUNT.addAndGet(installed);
            if (installed > 0) {
                XLog.i("✔ 已在 " + className + " 上挂钩 " + installed + " 个方法");
            } else {
                XLog.i("· " + className + " 里没有需要挂钩的方法（可能是辅助类）");
            }
            // 处理器消息队列（可选增强，见 HandlerPatcher）
            HandlerPatcher.tryHook(clazz);
        } catch (Throwable t) {
            XLog.e("hookClass 失败: " + className, t);
        }
    }

    private static String safeName(Method m) {
        try {
            return m.getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 方法分类：只挑“冻结动作”和“冻结决策”，其余一律不碰。
     * 尤其不碰 isFrozen / isFreezed 这类“状态查询”，
     * 否则可能让 Freecess 的内部状态机错乱。
     */
    private static int classify(Method m) {
        String name;
        try {
            name = m.getName();
        } catch (Throwable t) {
            return KIND_NONE;
        }
        if (name == null || name.isEmpty()) return KIND_NONE;

        String n = name.toLowerCase(Locale.ROOT);
        Class<?> ret = m.getReturnType();
        boolean boolRet = (ret == boolean.class || ret == Boolean.class);

        boolean mentionsFreeze = n.contains("freez") || n.contains("freecess") || n.contains("recess");

        // ---- 1) 决策类：让 Freecess 自己判定“不能冻结” ----
        if (boolRet) {
            if (mentionsFreeze
                    && (n.startsWith("can") || n.startsWith("allow") || n.startsWith("should"))) {
                return KIND_DECISION;
            }
            if (n.startsWith("isfreezable") || n.startsWith("isfreezeable")
                    || n.startsWith("isallowfreeze") || n.startsWith("isfreezeallowed")) {
                return KIND_DECISION;
            }
        }

        // ---- 2) 明显不是“动作”的命名，直接放过 ----
        if (n.startsWith("un") || n.startsWith("is") || n.startsWith("get") || n.startsWith("has")
                || n.startsWith("set") || n.startsWith("on") || n.startsWith("handle")
                || n.startsWith("dump") || n.startsWith("print") || n.startsWith("log")
                || n.startsWith("notify") || n.startsWith("register") || n.startsWith("update")
                || n.startsWith("init")) {
            return KIND_NONE;
        }

        // ---- 3) 动作类 ----
        if (mentionsFreeze) return KIND_ACTION;                       // freezeProcess / doFreecess ...
        if (n.contains("kill") || n.contains("forcestop") || n.contains("force_stop")) {
            return KIND_ACTION;                                      // 冻结后杀进程的同族方法
        }
        if (n.contains("block") && (n.contains("network") || n.contains("internet")
                || n.contains("data") || n.contains("traffic"))) {
            return KIND_ACTION;                                      // 冻结时顺手断网的方法
        }
        return KIND_NONE;
    }

    private static boolean hookMethod(final Method method, final int kind) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Set<String> candidates = Reflect.extractPackages(param.args);
                    if (candidates.isEmpty()) return;

                    String hit = Config.firstProtected(candidates);
                    if (hit == null) {
                        // 非白名单：原样放行，只在调试开关打开时记一笔
                        if (LOG_ALL_ACTION_CALLS && kind == KIND_ACTION) {
                            XLog.i("放行 " + method.getName() + " " + candidates);
                        }
                        return;
                    }

                    if (kind == KIND_DECISION) {
                        XLog.i("⛔ 决策拦截 " + method.getName() + " -> false, pkg=" + hit);
                        param.setResult(Boolean.FALSE);
                    } else {
                        XLog.i("⛔ 动作拦截 " + method.getName()
                                + " pkg=" + hit + " 参数候选=" + candidates);
                        // void 方法传 null 即可跳过原方法；有返回值就返回类型零值
                        param.setResult(defaultValue(method.getReturnType()));
                    }
                } catch (Throwable t) {
                    // 回调里发生任何异常都只记录，绝不让它传回 system_server
                    XLog.e("Hook 回调异常（已忽略）: " + safeName(method), t);
                }
            }
        };

        try {
            XposedBridge.hookMethod(method, hook);
            HOOKED_METHODS.add(method.getDeclaringClass().getName() + "#" + method.getName());
            XLog.i("  hook -> " + method.getDeclaringClass().getSimpleName()
                    + "#" + method.getName() + " (" + (kind == KIND_DECISION ? "决策" : "动作") + ")");
            return true;
        } catch (Throwable t) {
            XLog.w("XposedBridge.hookMethod 失败: " + method.getName() + " : " + t);
            return false;
        }
    }

    /** 跳过原方法时按返回类型给出“安全默认值” */
    private static Object defaultValue(Class<?> type) {
        if (type == null || type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return Boolean.FALSE;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == char.class || type == Character.class) return (char) 0;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        return null;
    }
}
