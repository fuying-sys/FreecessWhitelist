package com.aurora.freecesswhitelist;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 时机兜底：在 SystemServer 启动的几个关键节点上再扫一次候选类。
 *
 * 因为 handleLoadPackage 执行得很早，某些 ROM 的服务类可能在
 * 我们的候选扫描之后才被加载；在这些节点补扫一次成本极低（幂等）。
 *
 * 所有反射查找都用 try-catch 包住，方法不存在就跳过。
 */
public final class SystemServerWatcher {

    private static final String[] TRIGGER_METHODS = {
            "startOtherServices",   // 大部分系统服务在这里创建
            "startBootstrapServices"
    };

    private SystemServerWatcher() {
    }

    public static void install(final ClassLoader cl) {
        try {
            Class<?> systemServer = Class.forName("com.android.server.SystemServer", false, cl);
            for (String name : TRIGGER_METHODS) {
                try {
                    final Method m = systemServer.getDeclaredMethod(name);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                XLog.i("SystemServer." + name + " 完成，补扫 Freecess 类");
                                FreecessPatcher.scanCandidates(cl);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    XLog.i("已挂钩 SystemServer#" + name);
                } catch (Throwable ignored) {
                    // 该方法名在本 ROM 上不存在，跳过
                }
            }
        } catch (Throwable t) {
            XLog.w("SystemServer 触发点挂钩失败（不影响其它机制）: " + t);
        }
    }
}
