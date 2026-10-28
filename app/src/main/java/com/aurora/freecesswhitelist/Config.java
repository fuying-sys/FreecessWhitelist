package com.aurora.freecesswhitelist;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 白名单。
 *
 * 默认只保护两个包：
 *   be.mygod.vpnhotspot  —— VPNHotspot（热点共享本体）
 *   com.follow.clash     —— Clash 客户端（上游代理）
 *
 * 额外名单可写在 /data/adb/freecess-whitelist.conf（一行一个包名，# 开头是注释）。
 * 该文件只在启动时读一次并缓存，绝不在冻结回调里做磁盘 IO。
 *
 * 匹配规则：进程名带 ":xxx" 后缀会自动截断成包名再比对，
 * 例如 be.mygod.vpnhotspot:remote 也会命中 be.mygod.vpnhotspot。
 */
public final class Config {

    private static final String EXTRA_CONF = "/data/adb/freecess-whitelist.conf";

    private static final Set<String> DEFAULT_PACKAGES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "be.mygod.vpnhotspot",
                    "com.follow.clash"
            )));

    /** volatile + 不可变集合：Hook 回调里只读，无锁 */
    private static volatile Set<String> sPackages = DEFAULT_PACKAGES;

    private Config() {
    }

    /** 只在模块加载时调用一次 */
    public static void init() {
        Set<String> merged = new LinkedHashSet<>(DEFAULT_PACKAGES);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(EXTRA_CONF));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                merged.add(line);
            }
        } catch (Throwable t) {
            // 文件不存在 / 无权限都算正常，静默使用内置名单
            XLog.w("未读取到额外白名单文件（可忽略）: " + t);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            sPackages = Collections.unmodifiableSet(merged);
            XLog.i("白名单: " + sPackages);
        } catch (Throwable ignored) {
        }
    }

    public static Set<String> packages() {
        return sPackages;
    }

    /** 判断某个包名/进程名是否受保护 */
    public static boolean isProtected(String name) {
        if (name == null || name.isEmpty()) return false;
        Set<String> set = sPackages;
        if (set.contains(name)) return true;
        int idx = name.indexOf(':');
        if (idx > 0) {
            return set.contains(name.substring(0, idx));
        }
        return false;
    }

    /** 从一组候选名里挑出第一个命中白名单的，便于打日志 */
    public static String firstProtected(Set<String> names) {
        if (names == null || names.isEmpty()) return null;
        try {
            for (String n : names) {
                if (isProtected(n)) return n;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
