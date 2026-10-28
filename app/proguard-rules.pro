# 模块入口类不能被混淆/R8 删除（本工程默认不开启 minify，这里只是兜底）
-keep class com.aurora.freecesswhitelist.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-dontwarn android.**
