package com.aurora.freecesswhitelist;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 模块入口（由 assets/xposed_init 指定）。
 *
 * 关键点：
 * 三星的 Freecess 逻辑全部跑在 system_server 进程里，
 * 而 LSPosed 给 system_server 传的包名就是 "android"，
 * 所以这里必须精确判断，绝不能注入到普通 App 进程。
 *
 * 整个方法体被 try-catch 包住：即使模块自身有 bug，
 * 也只是模块失效，不会把 SystemServer 带崩。
 */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        try {
            if (lpparam == null || !"android".equals(lpparam.packageName)) {
                return; // 只处理系统框架
            }
            Boot.install(lpparam.classLoader);
        } catch (Throwable t) {
            // 最外层兜底，任何异常都不允许冒泡回 system_server
            XLog.e("handleLoadPackage 异常（已吞掉）", t);
        }
    }
}
