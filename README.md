# Freecess 白名单（LSPosed 模块）

> **Keep Samsung One UI from freezing your hotspot / VPN apps while the screen is off.**
> LSPosed module · Samsung One UI 4.x / 5.x · whitelist-based, fails safe (no-op when the target classes are absent).

用 LSPosed Hook 三星 `FreecessController`，实现**白名单式拦截**：只阻止系统冻结你指定的应用，其余应用照常被 Freecess 管理。

> 典型场景：VPNHotspot / Clash 用户，息屏后热点被 Freecess 冻结、上游 `wlan0` 被切断 —— 本模块把这两个包放行，热点就能一直活着。

当前版本：**1.0.1**（适配 LSPosed 1.8.3，含不依赖 Hook 的兜底轮询）

## 兼容性（先看这个）

| 条件 | 是否支持 |
|---|---|
| 三星 One UI 4.0 / 4.1（Android 12）、One UI 5.x（Android 13） | ✅ 主要目标 |
| 三星 One UI 3.x 及更早（Android 11-） | ⚠️ 系统里没有 Freecess，装了不生效 |
| 三星 One UI 6+（Android 14+） | ⚠️ 机制可能重构，需实测；LSPosed 也需较新版本 |
| 非三星品牌（小米 / 华为 / OPPO / vivo / Pixel …） | ❌ 各有各的冻结机制，本模块不适用 |
| 必需环境 | **root（Magisk）+ LSPosed 1.8.x 及以上**，作用域只勾「系统框架」 |
| minSdk / ABI | Android 8.0+，纯 Java 无 native，32/64 位均可 |

**先确认这台机器有没有目标**：

```bash
adb shell getprop ro.build.version.oneui     # 4.1 / 5.1 ...
adb shell "service list | grep -i freecess"  # 有输出才说明系统里有这套服务
```

装好后日志里出现 `hook -> ... (动作)` 才算真的挂上了，详见第 5 节。

---

## 1. 为什么这么写（核心思路）

| 问题 | 处理方式 |
|---|---|
| 三星没公开 `FreecessController` 的方法名，不同 ROM 还可能不一样 | **不写死方法名**：扫描类里所有方法，按“方法名语义 + 返回值”分类命中 |
| 冻结入口可能是 `freezeProcess` / `freezePackage` / `doFreeze` / `enterFreecess`… | 名字含 `freez` / `freecess` / `recess` 的 void 方法都视为**动作**，白名单命中就 `setResult` 跳过 |
| 有的版本先在策略层判断“能不能冻” | `canFreeze` / `allowFreeze` / `shouldFreeze` / `isFreezable` 这类 **boolean 决策**方法，白名单命中直接返回 `false`，从源头劝退 |
| 类名/包名可能不是 `com.android.server.freecess.*` | ① 先按候选类名扫；② **Hook `ClassLoader.loadClass`**，任何名字带 `freecess` 的类一被加载就自动挂钩；③ 在 `SystemServer.startOtherServices` 后再补扫一次 |
| 参数可能是 `String` / `ProcessRecord` / `List<ProcessRecord>` / `int uid` | 反射递归嗅探任意对象的 `packageName` / `processName` / `info` 字段；`int` 走 `/data/system/packages.list` 建立的 uid→包名映射 |
| **绝不能把 SystemServer 搞崩** | 所有 Hook 回调、反射、类加载监听全部 `try-catch(Throwable)`；找不到类/方法就静默跳过；回调异常只写日志 |

### 三层保险

```
① 候选类名扫描（Boot.install 时立刻执行）
② ClassLoader 监听（10 分钟窗口，覆盖所有“名字里带 freecess”的类）
③ SystemServer 启动节点补扫（startOtherServices 完成后）
④ 候选类轮询线程（每 5 秒一次，最长 10 分钟；不依赖任何 Hook，
   专为 LSPosed 1.8.3 这类旧框架兜底）
```

### 为什么 ClassLoader 监听要“延迟 150ms 再挂钩”

`ClassLoader.loadClass` 的 after 回调仍然处在 ART 的类加载流程中，此时直接调用
`XposedBridge.hookMethod` 可能触发线程挂起/重入。所以回调里只做字符串判断，
真正的挂钩动作丢给 `Worker` 后台线程延迟执行 —— 冻结决策发生在息屏后，
这点延迟完全不影响效果，却换来了稳定性。

---

## 2. 工程结构

```
FreecessWhitelist/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── tools/verify.ps1                     # 一键抓日志验证脚本
└── app/
    ├── build.gradle                     # compileOnly Xposed API
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml          # xposedmodule / xposedscope=android
        ├── assets/xposed_init           # 入口类名
        ├── res/values/strings.xml
        ├── res/values/arrays.xml        # xposed_scope = android
        └── java/com/aurora/freecesswhitelist/
            ├── MainHook.java            # IXposedHookLoadPackage，只注入 system_server
            ├── Boot.java                # 装配顺序
            ├── Config.java              # 白名单（内置 + /data/adb 额外配置）
            ├── Reflect.java             # 从任意参数里“挖”包名
            ├── UidMap.java              # uid -> 包名（读 packages.list）
            ├── Worker.java              # 延迟执行的后台线程
            ├── FreecessPatcher.java     # ★ 核心：动作/决策方法挂钩
            ├── HandlerPatcher.java      # 可选：拦截 FreecessHandler 的冻结消息
            ├── ClassLoaderWatcher.java  # ★ 兜底：动态发现 Freecess 类
            └── SystemServerWatcher.java # 时机兜底：启动节点补扫
```

---

## 3. 编译

### 方式 A：Android Studio（推荐）

1. `File → Open` 打开 `FreecessWhitelist` 目录，等待 Gradle 同步（会自动下载 AGP 7.4.2 / Gradle 7.6）；
2. `Build → Build Bundle(s)/APK(s) → Build APK(s)`；
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 方式 B：命令行

```bash
cd FreecessWhitelist
gradle wrapper --gradle-version 7.6   # 首次生成 gradlew（仓库里没带 wrapper jar）
./gradlew assembleDebug
```

> `release` 构建复用了 debug 签名（见 `app/build.gradle`），LSPosed 只要求 APK 已签名，
> 自用没必要单独做 keystore。

### 方式 C：不装 Android Studio（GitHub Actions）

仓库自带 `.github/workflows/build.yml`：

- push 到 `main` / 提 PR 时自动编译，产物在 **Actions → 对应运行 → Artifacts**；
- 打 `v*` tag 时自动把 APK 附到 **Release**。

也就是说：**Fork 一次，云端就替你编好了**，本地连 JDK 都不用装。

---

## 4. 安装与启用

1. `adb install -r app-debug.apk`（或直接拷到手机安装）；
2. 打开 **LSPosed 管理器 → 模块**，勾选「Freecess 白名单」；
3. 点进模块的**作用域**，只勾选 **系统框架（android）**，不要勾任何普通 App；
4. **重启手机**（system_server 的 Hook 必须重启才生效）；
5. Shamiko 保持**默认（黑名单）模式**：如果开了「白名单模式」，它会把 LSPosed 从
   system_server 里一起隐藏，表现就是“模块已启用但日志一行都没有”。这一条在
   1.8.3 这种旧版 + ROM 内置 LSPosed 的环境里尤其常见。

装完记得**关掉之前的妥协方案**：开发者选项里的「不锁定屏幕」、`svc power stayon ac`。
否则你无法判断到底是模块起作用还是屏幕没灭。

---

## 5. 验证

> **LSPosed 1.8.3（ROM 内置、无法升级）的兼容性**：本模块刻意只使用**经典 Xposed API 82**
> （`assets/xposed_init` + `XposedBridge.hookMethod`），`xposedminversion` 也写的 82，
> 1.8.3 完全支持，不需要也不要求升级 LSPosed。1.8.3 上唯一可能失效的是
> “Hook `ClassLoader`”这一层（旧版本对 system_server 里的 boot class 方法挂钩支持不稳），
> 所以另外加了**不依赖任何 Hook 的候选类轮询**（每 5 秒一次，最长 10 分钟），
> 即使 ClassLoader 监听装不上也能兜住。

```powershell
# 清日志 -> 息屏 -> 等 30 秒 -> 抓模块日志
pwsh -File tools/verify.ps1
```

或手动：

```bash
adb logcat -c
adb shell input keyevent 26     # 息屏
# 等 30 秒，让 Freecess 跑一轮
adb logcat -d -s FreecessWL
```

正常应看到类似：

```
I FreecessWL: ================ Freecess 白名单 v1.0.1 注入 system_server ================
I FreecessWL: 白名单: [be.mygod.vpnhotspot, com.follow.clash]
I FreecessWL: 已挂钩 SystemServer#startOtherServices
I FreecessWL: ClassLoader 监听已安装（窗口 10 分钟）
I FreecessWL: 候选类兜底轮询已启动（每 5s 一次，最长 10 分钟）
I FreecessWL: 捕获到 Freecess 相关类: com.android.server.freecess.FreecessController
I FreecessWL:   hook -> FreecessController#freezeProcess (动作)
I FreecessWL: ✔ 已在 com.android.server.freecess.FreecessController 上挂钩 3 个方法
I FreecessWL: 兜底轮询结束：已成功挂钩 3 个方法
I FreecessWL: ⛔ 动作拦截 freezeProcess pkg=be.mygod.vpnhotspot 参数候选=[be.mygod.vpnhotspot]
I FreecessWL: 放行 freezeProcess [com.tencent.mm]        ← 其它应用照常冻结
```

关键判据：

- 出现 `⛔ 动作拦截 ... pkg=be.mygod.vpnhotspot`：模块生效；
- 出现 `放行 ... [其它包名]`：白名单没有误伤；
- 有 `hook ->` 但没有 `⛔ 动作拦截`：Hook 挂上了但冻结没走这条路（或息屏后没触发），
  抓完整 `adb logcat` 再看 Freecess 的实际调用；
- 一句话都没有：见第 7 节「模块没日志」。

---

## 6. 可调项

| 位置 | 作用 |
|---|---|
| `Config.java` → `DEFAULT_PACKAGES` | 内置白名单，增删后重新编译 |
| `/data/adb/freecess-whitelist.conf` | 免编译加白名单，一行一个包名，`#` 注释；改完**重启**生效 |
| `FreecessPatcher.LOG_ALL_ACTION_CALLS` | 是否打印“放行”日志，调好后可设 `false` |
| `HandlerPatcher.ENABLED` | 关闭消息级拦截（若怀疑它影响了 Freecess 内部状态机） |
| `ClassLoaderWatcher.WATCH_WINDOW_MS` | 类加载监听窗口，默认 10 分钟 |
| `FreecessPatcher.startFallbackScanner` | 兜底轮询（5s/次，10 分钟），成功挂钩后自动退出 |
| `XLog.ENABLED` | 总日志开关 |

---

## 7. 故障排查

**Q1：日志里完全没有 `FreecessWL`**
按这个顺序查（1.8.3 + ROM 内置 LSPosed 尤其要按顺序来）：
1. 模块页是否**已启用**，作用域是否只有「系统框架」；
2. 是否**整机重启**过（不是热重启、不是只重启 LSPosed 管理器）；
3. Shamiko 是否被切到了**白名单模式**（会连 system_server 一起隐藏），切回默认；
4. `adb logcat -d | findstr /i "LSPosed"` 看框架有没有报
   `module ... requires newer version` 之类的拒绝原因；
5. 管理器是 ROM 内置版时，新装的模块可能不刷新——`adb shell am force-stop <管理器包名>`
   后重开，或直接重启手机再看。

**Q2：有 `捕获到 Freecess 相关类`，但没有任何 `hook ->`**
说明类里没有方法命中我们的语义规则。把下面这段日志发出来即可定位：
```
adb logcat -d -s FreecessWL | findstr "捕获到"
```
然后用 jadx 打开 `services.jar`，搜索 `freecess`，把真实方法名贴出来，
只要往 `FreecessPatcher.classify()` 里加一条规则即可（不需要动别的代码）。

**Q3：`⛔ 动作拦截` 有了，但热点还是会断**
说明断网不是（只）由 Freecess 触发，按顺序排查：
1. `adb shell dumpsys wifi | findstr /i "screen suspend power"` —— 看 wlan0 是否进了 Wi-Fi 挂起；
2. `adb shell dumpsys deviceidle whitelist` —— 确认 VPNHotspot 在 Doze 白名单；
3. `adb logcat -d | findstr /i "freecess wlan0 tether"` —— 看是否有别的服务在拆 tether；
4. VPNHotspot 自身设置里打开「保持 Wi-Fi 唤醒 / wakelock」类选项。
4.1 若确认是 Wi-Fi 省电把 wlan0 挂起，再在模块里补一个针对 Wi-Fi 挂起的 Hook
（这是我们下一步的扩展点，不是当前模块能覆盖的部分）。

**Q4：模块导致异常 / 想立刻回滚**
LSPosed 管理器里**取消勾选模块 → 重启**即可，模块不会在系统里留下任何持久化改动
（它不写系统分区、不改数据库、不注册服务）。

**Q5：想确认是哪个 uid**
`/data/adb/freecess-whitelist.conf` 里可以写包名；若日志里出现 `uid=xxxxx` 但你不知道是谁，
`adb shell cmd package list packages --uid 10123` 可反查。

---

## 8. 安全边界（重要）

- 本模块**只读**系统状态、**只挂载 Hook**，不修改 `services.jar`、不写 `/system`、不常驻服务；
- 所有 Hook 逻辑都包在 `try-catch (Throwable)` 里，异常仅写 logcat；
- 反射查找全部走 `Class.forName(name, false, cl)` + 逐方法 try-catch，找不到就跳过；
- 冻结回调里**不做磁盘 IO、不做 binder 调用**（uid 映射在启动时异步建好，回调只查内存 Map），
  不存在“在 AMS 锁内回调 PMS 导致死锁”的风险；
- 最坏情况：模块完全不生效，系统行为与未安装时一致。

---

## 9. 许可与免责

- 代码以 **GPL-3.0** 发布（见仓库根目录 `LICENSE`）。修改、再分发请保留原作者与许可证；
- 本模块与三星电子**没有任何关系**（Not affiliated with Samsung）；文中出现的
  `Samsung / One UI / Freecess` 仅用于说明兼容性，不表示官方支持或授权；
- 仓库**不包含**任何三星专有代码、`services.jar` 反编译产物或厂商资源；
- 仅供个人设备自用与研究。root、解锁 bootloader 会导致 Knox 熔断、保修失效，
  并可能影响 Samsung Pay / 银行类应用，风险自负；
- 请勿将本项目用于绕过付费内容、DRM、游戏反作弊或任何违法用途。

---

## 10. 参与贡献

不同机型的方法名可能不同 —— **一份日志就能让这个模块多支持一个机型**。
详见 [CONTRIBUTING.md](CONTRIBUTING.md)。报告时请附上：

```bash
adb shell getprop ro.build.version.oneui
adb shell "service list | grep -i freecess"
adb logcat -d -s FreecessWL
```
