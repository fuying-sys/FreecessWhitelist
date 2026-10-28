# 参与贡献

这个项目最有价值的贡献不是代码，而是**不同机型上的日志**。

## 一、报告"某个机型不生效"（最有用的 Issue）

请按 `bug_report.yml` 模板填，核心是三样：

1. 机型 + One UI 版本（`adb shell getprop ro.build.version.oneui`）
2. 系统里有没有这套服务（`adb shell "service list | grep -i freecess"`）
3. `adb logcat -d -s FreecessWL` 的完整输出

### 三种日志对应三种修法

| 日志 | 含义 | 需要改哪里 |
|---|---|---|
| 有 `捕获到 Freecess 相关类`，有 `hook ->` | 已挂上，病因可能不是 Freecess | 提供完整 `logcat` 再看 |
| 有 `捕获到 ...`，没有 `hook ->` | 类名对了，方法名不在规则里 | 改 `FreecessPatcher.classify()` 加一条命名规则 |
| 完全没有日志 | 模块没注入 system_server | 查 LSPosed 启用状态 / 作用域 / Shamiko 模式 |

### 附上真实类结构（强烈建议）

用 jadx 打开 `services.jar`，搜索 `freecess`，把类名 + 方法签名贴出来
（**请不要把反编译代码提交到仓库**，只贴 `类名#方法名(参数类型)` 就够）。

## 二、提交代码

1. Fork → 新建分支（`feat/xxx` / `fix/xxx`）→ 改 → PR；
2. 提交前确认：
   - CI 是否绿（`Build APK`）；
   - 新增 Hook 同样遵守两条铁律：**全部 try-catch**、**找不到就静默跳过**；
   - 不要在 Hook 回调里做磁盘 IO、binder 调用或长耗时操作；
3. 白名单改动优先走 `/data/adb/freecess-whitelist.conf`，不要写死进代码。

## 三、不要提交的东西

- `local.properties`（含个人 SDK 路径）
- `.idea/`、`.gradle/`、`app/build/`
- 任何 keystore / 签名文件
- 三星反编译代码、`services.jar`、厂商资源
- 设备序列号、IMEI、日志里带个人信息的片段

## 四、行为准则

- 只讨论"让自己的设备更好用"；不讨论绕过付费、DRM、反作弊；
- 报 bug 请给出可复现步骤，不要只写"不生效"。
