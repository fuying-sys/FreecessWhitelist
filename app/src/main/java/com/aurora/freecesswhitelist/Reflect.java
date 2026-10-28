package com.aurora.freecesswhitelist;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 反射工具：从**任意的** Hook 参数里“挖”出包名。
 *
 * 这是整个模块能兼容不同 ROM/不同方法签名的核心：
 * 三星的冻结接口参数可能是 String、ProcessRecord、ProcessInfo、
 * FreecessInfo、List<ProcessRecord>、int[] uid ……我们无法预先枚举完，
 * 所以采取“递归嗅探任意对象上的 packageName / processName / info 字段”的策略。
 *
 * 设计原则：
 * · 任何一步失败都返回 null，绝不抛异常；
 * · 字段/方法查找结果缓存，避免高频反射；
 * · 深度、元素数量都设上限，防止在大集合上卡住 system_server。
 */
public final class Reflect {

    /** 形如 com.foo.bar 或 com.foo.bar:remote 的字符串才当包名 */
    private static final Pattern PACKAGE_LIKE = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+(:[A-Za-z0-9_.]+)?$");

    /** 直接就是包名/进程名的字段 */
    private static final String[] PKG_FIELDS = {
            "packageName", "mPackageName", "pkgName", "mPkgName",
            "processName", "mProcessName", "procName", "mProcName"
    };

    /** 可能“套着”包名的对象字段，需要递归进去 */
    private static final String[] NESTED_FIELDS = {
            "info", "mInfo", "appInfo", "mAppInfo", "applicationInfo", "mApplicationInfo",
            "processRecord", "mProcessRecord", "record", "mRecord", "proc", "mProc",
            "freecessInfo", "mFreecessInfo", "processInfo", "mProcessInfo", "pkg", "mPkg"
    };

    private static final String[] PKG_GETTERS = {"getPackageName", "getProcessName"};

    private static final String[] NESTED_GETTERS = {
            "getInfo", "getApplicationInfo", "getProcessRecord", "getProcessName"
    };

    private static final int MAX_DEPTH = 3;
    private static final int MAX_ELEMENTS = 64;
    private static final int MAX_RESULTS = 32;

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /**
     * 入口：把一次方法调用的所有参数“榨”成包名集合。
     * 返回值永不为 null（可能为空集），调用方无需判空。
     */
    public static Set<String> extractPackages(Object[] args) {
        Set<String> out = new LinkedHashSet<>();
        if (args == null || args.length == 0) return out;
        try {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (Object arg : args) {
                collect(arg, out, seen, 0);
            }
        } catch (Throwable t) {
            XLog.e("extractPackages 异常（已吞掉）", t);
        }
        return out;
    }

    private static void collect(Object o, Set<String> out,
                                IdentityHashMap<Object, Boolean> seen, int depth) {
        if (o == null || depth > MAX_DEPTH || out.size() > MAX_RESULTS) return;

        try {
            // 1) 字符串：直接判包名格式
            if (o instanceof CharSequence) {
                addIfPackage(out, o.toString());
                return;
            }

            // 2) 整数：大概率是 uid，交给 UidMap 尽力翻译
            if (o instanceof Integer) {
                Set<String> byUid = UidMap.packagesForUid((Integer) o);
                if (byUid != null) out.addAll(byUid);
                return;
            }

            // 3) 其它数字/布尔值没有包名信息
            if (o instanceof Number || o instanceof Boolean || o instanceof Character) return;

            Class<?> cls = o.getClass();

            // 4) 数组
            if (cls.isArray()) {
                int len = Math.min(Array.getLength(o), MAX_ELEMENTS);
                for (int i = 0; i < len; i++) {
                    Object v = Array.get(o, i);
                    if (v != null && v != o) collect(v, out, seen, depth + 1);
                }
                return;
            }

            // 5) 集合
            if (o instanceof Collection) {
                int i = 0;
                for (Object v : (Collection<?>) o) {
                    if (i++ > MAX_ELEMENTS) break;
                    if (v != null && v != o) collect(v, out, seen, depth + 1);
                }
                return;
            }

            // 6) Map：看 value 就够了
            if (o instanceof Map) {
                int i = 0;
                for (Object v : ((Map<?, ?>) o).values()) {
                    if (i++ > MAX_ELEMENTS) break;
                    if (v != null && v != o) collect(v, out, seen, depth + 1);
                }
                return;
            }

            // 7) 普通对象：环形引用保护后反射嗅探
            if (seen.put(o, Boolean.TRUE) != null) return;

            for (String name : PKG_FIELDS) {
                Object v = getField(o, name);
                if (v instanceof CharSequence) addIfPackage(out, v.toString());
            }
            for (String name : PKG_GETTERS) {
                Object v = invokeNoArg(o, name);
                if (v instanceof CharSequence) addIfPackage(out, v.toString());
            }
            for (String name : NESTED_FIELDS) {
                Object v = getField(o, name);
                if (v != null && v != o) collect(v, out, seen, depth + 1);
            }
            for (String name : NESTED_GETTERS) {
                Object v = invokeNoArg(o, name);
                if (v != null && v != o) collect(v, out, seen, depth + 1);
            }
        } catch (Throwable ignored) {
            // 单个对象嗅探失败直接放弃，不影响其它参数
        }
    }

    private static void addIfPackage(Set<String> out, String s) {
        if (s == null) return;
        String v = s.trim();
        if (v.length() < 3 || v.length() > 200) return;
        try {
            if (PACKAGE_LIKE.matcher(v).matches()) {
                out.add(v);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读字段（含父类），找不到返回 null */
    public static Object getField(Object target, String name) {
        if (target == null || name == null) return null;
        try {
            Class<?> cls = target.getClass();
            String key = cls.getName() + '#' + name;
            Field f = FIELD_CACHE.get(key);
            if (f == null) {
                f = findField(cls, name);
                if (f == null) return null;
                FIELD_CACHE.put(key, f);
            }
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /** 调无参方法（含父类），失败返回 null */
    public static Object invokeNoArg(Object target, String name) {
        if (target == null || name == null) return null;
        try {
            Class<?> cls = target.getClass();
            String key = cls.getName() + '#' + name + "()";
            Method m = METHOD_CACHE.get(key);
            if (m == null) {
                m = findNoArgMethod(cls, name);
                if (m == null) return null;
                METHOD_CACHE.put(key, m);
            }
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                if (m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
