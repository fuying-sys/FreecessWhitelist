package com.aurora.freecesswhitelist;

import android.os.Message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 可选增强：拦截 FreecessHandler 里的“冻结消息”。
 *
 * 有些 ROM 的 FreecessController 本身只负责收集状态，
 * 真正下发冻结的是 FreecessHandler.handleMessage(Message)。
 * 我们不可能预先知道消息 what 的数值，所以：
 *   1) 反射扫描该 Handler 类（及其外部类）里名字含 FREEZ 的 static int 常量，
 *      自动区分“冻结”和“解冻”两组 what；
 *   2) 只拦截“冻结组”的消息，且只有消息体里能解析出白名单包名时才跳过；
 *   3) 解冻消息一律放行，避免应用被冻住之后解不开。
 *
 * 如果类的常量命名不含 FREEZ（拿不到 what），这里会主动放弃，宁可不拦。
 * 需要关闭本增强：把 ENABLED 改成 false。
 */
public final class HandlerPatcher {

    private static final boolean ENABLED = true;

    private static final Set<String> HOOKED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private HandlerPatcher() {
    }

    public static void tryHook(Class<?> clazz) {
        if (!ENABLED || clazz == null) return;

        String className;
        try {
            className = clazz.getName();
        } catch (Throwable t) {
            return;
        }
        if (className == null) return;

        String lower = className.toLowerCase(Locale.ROOT);
        if (!lower.contains("freecess") || !lower.contains("handler")) return;
        if (!HOOKED.add(className)) return;

        try {
            Method handle = findHandleMessage(clazz);
            if (handle == null) return;

            final Set<Integer> freezeWhats = new HashSet<>();
            final Set<Integer> unfreezeWhats = new HashSet<>();
            for (Class<?> k = clazz; k != null; k = k.getEnclosingClass()) {
                collectWhats(k, freezeWhats, unfreezeWhats);
            }

            if (freezeWhats.isEmpty()) {
                XLog.i("· " + className + " 未找到 FREEZE_* 常量，跳过消息级拦截");
                return;
            }

            XLog.i("处理器 " + className + "：冻结 what=" + freezeWhats
                    + "，解冻 what=" + unfreezeWhats);

            XposedBridge.hookMethod(handle, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        Object first = param.args[0];
                        if (!(first instanceof Message)) return;

                        Message msg = (Message) first;
                        if (unfreezeWhats.contains(msg.what)) return;   // 解冻必须放行
                        if (!freezeWhats.contains(msg.what)) return;    // 只处理明确的冻结消息

                        Set<String> pkgs = Reflect.extractPackages(new Object[]{msg.obj});
                        String hit = Config.firstProtected(pkgs);
                        if (hit == null) return;

                        XLog.i("⛔ 拦截冻结消息 what=" + msg.what + " pkg=" + hit);
                        param.setResult(null);   // handleMessage 返回 void
                    } catch (Throwable t) {
                        XLog.e("HandlerPatcher 回调异常（已忽略）", t);
                    }
                }
            });
            XLog.i("  hook -> " + className + "#handleMessage");
        } catch (Throwable t) {
            XLog.w("HandlerPatcher 失败: " + className + " : " + t);
        }
    }

    /** 从 clazz 开始沿父类找 handleMessage，且不允许走到 android.os.Handler 上 */
    private static Method findHandleMessage(Class<?> clazz) {
        for (Class<?> k = clazz; k != null && k != Object.class; k = k.getSuperclass()) {
            String kn = "";
            try {
                kn = k.getName().toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {
            }
            if (kn.startsWith("android.os.") || kn.startsWith("java.")) break;  // 绝不 hook 框架基类
            try {
                Method m = k.getDeclaredMethod("handleMessage", Message.class);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /** 收集 static int 常量：名字含 FREEZ 且不含 UNFREEZ/THAW/RELEASE 的算冻结 */
    private static void collectWhats(Class<?> c, Set<Integer> freeze, Set<Integer> unfreeze) {
        try {
            for (Field f : c.getDeclaredFields()) {
                try {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t != int.class && t != Integer.class) continue;

                    String n = f.getName().toUpperCase(Locale.ROOT);
                    if (!n.contains("FREEZ")) continue;

                    boolean isUnfreeze = n.contains("UNFREEZ") || n.contains("UN_FREEZ")
                            || n.contains("THAW") || n.contains("RELEASE") || n.contains("RESUME");

                    f.setAccessible(true);
                    int value = f.getInt(null);
                    if (isUnfreeze) {
                        unfreeze.add(value);
                    } else {
                        freeze.add(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
