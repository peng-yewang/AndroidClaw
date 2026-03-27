<#
.SYNOPSIS
    用于 AI Agent 快速检查、部署拉取日志的高信噪比反馈控制台脚本
.DESCRIPTION
    1. 编译并且安装 (免去开发者打理 gradle 任务)
    2. 提取最近 3 分钟的 Logcat 数据 (只关注 AndroidClaw, AutomationEngine 或 LogManager 的核心业务流)。
#>

param (
    [switch]$BuildAndInstall = $false,
    [switch]$RunLogLoop = $true
)

Write-Host "[Harness] 初始化环境..." -ForegroundColor Cyan

If ($BuildAndInstall) {
    Write-Host "[Harness] 正在自动编译并推送到测试设备..." -ForegroundColor Yellow
    
    # Run gradle command
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "cmd.exe"
    $pinfo.Arguments = "/c .\gradlew assembleDebug installDebug --quiet"
    $pinfo.WorkingDirectory = $PSScriptRoot + "\.."
    $pinfo.RedirectStandardOutput = $true
    
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $pinfo
    $p.Start() | Out-Null
    $output = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    
    If ($p.ExitCode -ne 0) {
        Write-Host "❌ 编译安装失败！请检查以下报错，Agent可基于此返回进行修正：" -ForegroundColor Red
        Write-Host $output
        Exit $p.ExitCode
    } else {
        Write-Host "✅ 安装成功！" -ForegroundColor Green
    }
}

If ($RunLogLoop) {
    Write-Host "[Harness] 提取纯粹的业务日志反馈闭环 (Feedback Loop)..." -ForegroundColor Yellow
    # 这里为了简便，获取最新的 300 行关于任务核心打印出来的关键内容
    adb logcat -d -t 5000 > "$PSScriptRoot\temp_log.txt" 2>$null
    
    $filtered = Select-String -Path "$PSScriptRoot\temp_log.txt" -Pattern "LogManager|AutomationEngine|NodeFinder|TaskScript|Exception"
    
    # 截取近期的记录提供给上下文
    $logs = $filtered | Select-Object -Last 100
    
    If ($logs.Count -gt 0) {
        Write-Host "════════════════════════════════════════════════" -ForegroundColor Cyan
        Write-Host "📊 获得的最新 Harness 业务轨迹：" -ForegroundColor Cyan
        Write-Host "════════════════════════════════════════════════" -ForegroundColor Cyan
        $logs | ForEach-Object {
            If ($_ -match "ERROR|Exception") {
                Write-Host $_ -ForegroundColor Red
            } ElseIf ($_ -match "SUCCESS") {
                Write-Host $_ -ForegroundColor Green
            } ElseIf ($_ -match "WARN") {
                Write-Host $_ -ForegroundColor Yellow
            } Else {
                Write-Host $_ -ForegroundColor Gray
            }
        }
    } Else {
        Write-Host "⚠️ 未提取到业务层面的日志，可能任务尚未启动，或日志被清理。" -ForegroundColor Yellow
    }
    
    Remove-Item -Path "$PSScriptRoot\temp_log.txt" -ErrorAction SilentlyContinue
}

Write-Host "[Harness] 反馈闭环已准备完毕。" -ForegroundColor Cyan
