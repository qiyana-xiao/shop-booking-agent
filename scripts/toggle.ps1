#Requires -Version 5.1
<#
店小约一键切换脚本（桌面快捷方式「店小约」指向这里）

一个入口，两种动作：
    未运行 -> 启动（后端 8080 / 前端 5173，就绪后自动打开浏览器；窗口保持打开即服务保持运行）
    已运行 -> 停止（释放 8080 / 5173 端口，结束相关进程树）

用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\toggle.ps1
    日常使用：双击桌面「店小约」快捷方式（首次启动后自动创建）
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Test-Port([int]$port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

if (Test-Port 8080 -or Test-Port 5173) {
    Write-Host ''
    Write-Host '==> 检测到店小约正在运行，执行停止...' -ForegroundColor Cyan
    & (Join-Path $root 'scripts\stop.ps1') -NoPause
    Write-Host ''
    Write-Host '提示：再次双击桌面「店小约」或「启动-店小约.bat」可重新启动。' -ForegroundColor DarkGray
    Write-Host '按任意键关闭窗口...'
    try { $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown') } catch { }
} else {
    Write-Host ''
    Write-Host '==> 店小约未运行，开始启动...' -ForegroundColor Cyan
    & (Join-Path $root 'scripts\start.ps1')
}
