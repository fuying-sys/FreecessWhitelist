package com.aurora.freecesswhitelist;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * uid -> 包名 的只读映射（尽力而为）。
 *
 * 为什么需要它：
 * 如果三星的冻结方法签名里只有 int uid（例如 freezeProcess(int uid, ...)），
 * 单靠对象反射拿不到包名，白名单就会失效。这里在启动时异步把
 * /data/system/packages.list 读进内存（system_server 本来就有权限），
 * 之后 Hook 回调里只做一次 HashMap 查询，没有 IO、没有 binder 调用，
 * 因此不存在“在冻结路径上重新进 PMS 导致死锁”的风险。
 *
 * 读不到就直接返回 null，Hook 侧按“放行”处理（宁可漏拦，也不冒险）。
 */
public final class UidMap {

    private static final String PACKAGES_LIST = "/data/system/packages.list";

    /** uid 小于 10000 的是系统/守护进程，不参与映射，避免把标志位误当 uid */
    private static final int MIN_APP_UID = 10000;

    private static volatile Map<Integer, Set<String>> sByUid;

    private UidMap() {
    }

    public static void startAsync() {
        Worker.submit(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    private static void load() {
        Map<Integer, Set<String>> map = new HashMap<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(PACKAGES_LIST));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                try {
                    int uid = Integer.parseInt(parts[1]);
                    Set<String> set = map.get(uid);
                    if (set == null) {
                        set = new HashSet<>();
                        map.put(uid, set);
                    }
                    set.add(parts[0]);
                } catch (Throwable ignored) {
                    // 单行异常不影响整体
                }
            }
            for (Map.Entry<Integer, Set<String>> e : map.entrySet()) {
                e.setValue(Collections.unmodifiableSet(e.getValue()));
            }
            sByUid = Collections.unmodifiableMap(map);
            XLog.i("uid 映射已加载: " + map.size() + " 个 uid");
        } catch (Throwable t) {
            XLog.w("uid 映射加载失败（不影响包名匹配）: " + t);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 查不到返回 null，调用方按“未知”处理 */
    public static Set<String> packagesForUid(int uid) {
        if (uid < MIN_APP_UID) return null;
        Map<Integer, Set<String>> map = sByUid;
        if (map == null) return null;
        try {
            return map.get(uid);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
