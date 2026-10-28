#requires -Version 5.1
<#
    一键验证脚本：
      1. 清空 logcat
      2. 模拟息屏（KEYCODE_POWER）
      3. 等待 30 秒让 Freecess 跑一轮
      4. 只打印模块日志（tag = FreecessWL）

    用法：pwsh -File tools\verify.ps1 [-WaitSeconds 30]
    前置：adb 可用且手机已授权（adb devices 能看到设备）
#>

param(
    [int]$WaitSeconds = 30
)

$ErrorActionPreference = 'Stop'

Write-Host '== 1/4 检查设备 ==' -ForegroundColor Cyan
adb devices
adb wait-for-device

Write-Host '== 2/4 清空日志 ==' -ForegroundColor Cyan
adb logcat -c

Write-Host '== 3/4 息屏，等待 Freecess 决策 ==' -ForegroundColor Cyan
adb shell input keyevent 26
Start-Sleep -Seconds $WaitSeconds

Write-Host '== 4/4 模块日志 ==' -ForegroundColor Cyan
adb logcat -d -s FreecessWL

Write-Host ''
Write-Host '判据：' -ForegroundColor Yellow
Write-Host '  出现 “⛔ 动作拦截 ... pkg=be.mygod.vpnhotspot”  -> 模块生效'
Write-Host '  出现 “放行 ... [com.xxx]”                      -> 其它应用未被误伤'
Write-Host '  完全没有任何输出                                -> 模块未启用/未重启，见 README 第 7 节'
