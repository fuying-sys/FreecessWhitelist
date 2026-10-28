package com.aurora.freecesswhitelist;

import android.util.Log;

/**
 * 统一日志出口。
 *
 * 为什么单独封装：system_server 里任何一次异常抛出都可能被当成致命错误，
 * 所以连 Log.i 这种“不可能失败”的调用也包一层 try-catch。
 *
 * 查看日志：adb logcat -s FreecessWL
 */
public final class XLog {

    private static final String TAG = "FreecessWL";

    /** 调试期保持 true；确认稳定后改成 false 可彻底静音 */
    private static final boolean ENABLED = true;

    private XLog() {
    }

    public static void i(String msg) {
        if (!ENABLED) return;
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void w(String msg) {
        if (!ENABLED) return;
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        if (!ENABLED) return;
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg) {
        e(msg, null);
    }
}
