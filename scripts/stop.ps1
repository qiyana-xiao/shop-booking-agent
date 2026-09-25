#Requires -Version 5.1
<#
店小约一键停止脚本

作用：结束监听 8080（后端）与 5173（前端）的进程及其子进程树，并清理本项目
      后端 java 进程的残留实例，释放端口。

用法：
    双击项目根目录「停止-店小约.bat」
    或：powershell -ExecutionPolicy Bypass -File .\scripts\stop.ps1 [-NoPause]
参数：
    -NoPause  结束后不等待按键（供 toggle.ps1 调用时使用）
#>

param([switch]$NoPause)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$jarMarker = 'shop-booking-backend.jar'

Write-Host ''
Write-Host '==> 正在停止店小约（后端 8080 / 前端 5173）...' -ForegroundColor Cyan

$found = $false
foreach ($port in @(8080, 5173)) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Host "  [OK] 端口 $port 无进程在运行" -ForegroundColor Green
        continue
    }
    $found = $true
    foreach ($procId in ($conns | Select-Object -ExpandProperty OwningProcess -Unique)) {
        $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
        $name = if ($p) { $p.ProcessName } else { '未知进程' }
        Write-Host "  [..] 结束端口 $port 上的 $name (PID $procId)" -ForegroundColor Yellow
        cmd /c "taskkill /PID $procId /T /F" | Out-Null
    }
    Start-Sleep -Milliseconds 800
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "  [X] 端口 $port 仍被占用，请检查是否有其他程序使用该端口" -ForegroundColor Red
    } else {
        Write-Host "  [OK] 端口 $port 已释放" -ForegroundColor Green
    }
}

# 兜底：清掉命令行里挂着本项目 jar 的残留 java 进程（不监听端口的异常退出场景）
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$jarMarker*" } |
    ForEach-Object {
        Write-Host "  [..] 结束残留后端进程 (PID $($_.ProcessId))" -ForegroundColor Yellow
        cmd /c "taskkill /PID $($_.ProcessId) /T /F" | Out-Null
    }

# 关闭本项目拉起的便携版 Redis（仅匹配 runtime\redis 路径，绝不影响系统安装的 Redis 服务）
$portableRedisDir = Join-Path $root 'runtime\redis'
Get-CimInstance Win32_Process -Filter "Name='redis-server.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -like "$portableRedisDir*" } |
    ForEach-Object {
        Write-Host "  [..] 结束项目内便携版 Redis (PID $($_.ProcessId))" -ForegroundColor Yellow
        cmd /c "taskkill /PID $($_.ProcessId) /T /F" | Out-Null
        $found = $true
    }

Write-Host ''
if ($found) {
    Write-Host '店小约已全部停止。' -ForegroundColor Green
} else {
    Write-Host '没有发现正在运行的店小约服务。' -ForegroundColor Green
}

if (-not $NoPause) {
    Write-Host ''
    Write-Host '按任意键关闭窗口...'
    try { $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown') } catch { }
}
