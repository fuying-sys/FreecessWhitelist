package com.aurora.freecesswhitelist;

/**
 * 装配入口：按“先轻后重、层层兜底”的顺序安装所有机制。
 *
 * 顺序有讲究：
 *   1. 先初始化白名单（要读一次配置文件，趁现在做，别留到 Hook 回调里）；
 *   2. 异步加载 uid 映射（磁盘 IO）；
 *   3. 立即按候选类名扫一遍（大多数 ROM 能直接命中）；
 *   4. 装上 ClassLoader 监听（防类名变化，最重要的一层兜底）；
 *   5. 在 SystemServer 启动节点补扫（防加载时机太晚）；
 *   6. 起一个不依赖任何 Hook 的轮询线程（LSPosed 1.8.3 上的最后一道保险）。
 *
 * 每一步都单独 try-catch：某一层失败，其余层仍然工作。
 */
public final class Boot {

    public static final String VERSION = "1.0.1";

    private Boot() {
    }

    public static void install(ClassLoader cl) {
        XLog.i("================ Freecess 白名单 v" + VERSION + " 注入 system_server ================");

        try {
            Config.init();
        } catch (Throwable t) {
            XLog.e("Config.init 失败", t);
        }

        try {
            UidMap.startAsync();
        } catch (Throwable t) {
            XLog.e("UidMap 启动失败", t);
        }

        try {
            FreecessPatcher.scanCandidates(cl);
        } catch (Throwable t) {
            XLog.e("候选类扫描失败", t);
        }

        try {
            ClassLoaderWatcher.install();
        } catch (Throwable t) {
            XLog.e("ClassLoaderWatcher 安装失败", t);
        }

        try {
            SystemServerWatcher.install(cl);
        } catch (Throwable t) {
            XLog.e("SystemServerWatcher 安装失败", t);
        }

        try {
            FreecessPatcher.startFallbackScanner(cl);
        } catch (Throwable t) {
            XLog.e("兜底轮询启动失败", t);
        }

        XLog.i("安装完成：已挂钩方法数 = " + FreecessPatcher.hookCount()
                + "，等待 Freecess 类被加载…");
    }
}
