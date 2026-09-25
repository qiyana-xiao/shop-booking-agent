#Requires -Version 5.1
<#
便携版 Redis 安装脚本（可选组件）

Redis 是本项目的可选依赖：不安装也能正常启动（限流放行 / 会话缓存自动降级，
核心功能不受影响）。是否安装由用户决定，本脚本负责下载与启动。

本脚本把 Windows 版 Redis（tporadowski 移植版 5.0.14.1，社区最常用的 Windows 原生版）
下载解压到项目 runtime\redis：
- 仅本项目可用，不写注册表、不注册 Windows 服务，删除项目文件夹即彻底清除
- 系统已在运行 Redis（6379 在线）或已装过本便携版时，自动跳过下载

用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\setup-redis.ps1             # 仅下载安装
    powershell -ExecutionPolicy Bypass -File .\scripts\setup-redis.ps1 -Start     # 安装并立即启动
    powershell -ExecutionPolicy Bypass -File .\scripts\setup-redis.ps1 -Uninstall # 删除项目内便携版

下载失败时自动切换备用源；runtime\ 已被 Git 忽略，不会上传到仓库。
#>

param(
    [switch]$Start,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$root       = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $root 'runtime'
$redisDir   = Join-Path $runtimeDir 'redis'
$redisExe   = Join-Path $redisDir 'redis-server.exe'
$confFile   = Join-Path $redisDir 'portable.conf'
$logsDir    = Join-Path $root 'logs'
$dlDir      = Join-Path $runtimeDir 'downloads'

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Hint([string]$msg) { Write-Host "  [ ! ] $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "  [X] $msg" -ForegroundColor Red }

function Test-Port([int]$port, [string]$hostName = '127.0.0.1') {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($hostName, $port)
        if ($task.Wait(1200) -and $client.Connected) { return $true }
        return $false
    } catch { return $false } finally { $client.Close() }
}

function Invoke-Download([string]$url, [string]$destFile) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destFile) | Out-Null
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -L --fail --connect-timeout 20 --retry 2 -o $destFile $url
        if ($LASTEXITCODE -eq 0) { return $true }
    } else {
        $wc = New-Object System.Net.WebClient
        try { $wc.DownloadFile($url, $destFile); return $true } catch { }
    }
    return $false
}

function Write-PortableConf {
    # 纯缓存用途：只监听本机回环、禁用 RDB/AOF 落盘（数据可再生，避免 runtime 目录生成快照文件）
    $conf = @(
        'bind 127.0.0.1',
        'port 6379',
        'save ""',
        'appendonly no',
        'maxmemory 128mb',
        'maxmemory-policy allkeys-lru'
    )
    [IO.File]::WriteAllLines($confFile, $conf, (New-Object System.Text.UTF8Encoding($false)))
}

function Start-PortableRedis {
    if (Test-Port 6379) {
        Write-Ok 'Redis(6379) 已在线，无需重复启动'
        return $true
    }
    if (-not (Test-Path $redisExe)) {
        Write-Fail '未找到便携版 Redis，请先安装（去掉 -Start 运行本脚本）'
        return $false
    }
    if (-not (Test-Path $confFile)) { Write-PortableConf }
    New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
    Write-Step '启动便携版 Redis(6379)'
    Start-Process -FilePath $redisExe -ArgumentList 'portable.conf' `
        -WorkingDirectory $redisDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logsDir 'redis.log') `
        -RedirectStandardError (Join-Path $logsDir 'redis-err.log')
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Milliseconds 600
        if (Test-Port 6379) {
            Write-Ok 'Redis 已启动（停止项目时会一并关闭；日志：logs\redis.log）'
            return $true
        }
    }
    Write-Fail 'Redis 启动后 6379 未监听，请查看 logs\redis-err.log'
    return $false
}

# ---------- 卸载 ----------
if ($Uninstall) {
    Write-Step '卸载项目内便携版 Redis'
    Get-CimInstance Win32_Process -Filter "Name='redis-server.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -like "$redisDir*" } |
        ForEach-Object {
            Write-Host "  [..] 结束便携版 Redis 进程 (PID $($_.ProcessId))" -ForegroundColor Yellow
            cmd /c "taskkill /PID $($_.ProcessId) /T /F" | Out-Null
        }
    if (Test-Path $redisDir) {
        Remove-Item $redisDir -Recurse -Force
        Write-Ok "已删除 $redisDir"
    } else {
        Write-Hint '未安装过便携版 Redis，无需卸载'
    }
    exit 0
}

Write-Host '=====================================' -ForegroundColor Cyan
Write-Host '  便携版 Redis 安装（可选，仅本项目可用）' -ForegroundColor Cyan
Write-Host '=====================================' -ForegroundColor Cyan

# 已在运行的 Redis（系统安装或其他来源）→ 直接使用，无需下载
if (Test-Port 6379) {
    Write-Ok 'Redis(6379) 已在线，直接使用现有 Redis，无需安装'
    if ($Start) { Write-Hint '已在线，无需启动' }
    exit 0
}

# PATH 中装有 redis-server → 系统已有，提示用户自行启动，不重复下载
$sysRedis = Get-Command redis-server -ErrorAction SilentlyContinue
if ($sysRedis) {
    Write-Hint "检测到系统已安装 Redis（$($sysRedis.Source)），但服务未启动"
    Write-Host '  请自行启动系统 Redis 服务；本项目不会代为启动，也不重复下载便携版。' -ForegroundColor Yellow
    if ($Start) { Write-Hint '系统 Redis 未启动，本次跳过' }
    exit 0
}

# 已安装过便携版 → 跳过下载
if (Test-Path $redisExe) {
    Write-Ok "便携版 Redis 已存在：$redisDir（重装请先执行 -Uninstall）"
    if ($Start) { $null = Start-PortableRedis }
    exit 0
}

# ---------- 下载（多源自动切换） ----------
$urls = @(
    'https://ghfast.top/https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip',
    'https://mirror.ghproxy.com/https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip',
    'https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip'
)
$zip = Join-Path $dlDir 'redis-win64.zip'
$downloaded = $false
foreach ($url in $urls) {
    Write-Step "下载便携版 Redis（来源：$url）"
    if (Invoke-Download $url $zip) {
        if ((Test-Path $zip) -and ((Get-Item $zip).Length -gt 3MB)) { $downloaded = $true; break }
    }
    Write-Hint '该源下载失败，尝试下一个备用源...'
}
if (-not $downloaded) {
    Write-Fail '所有下载源均失败，可稍后重试，或自行安装 Redis（本项目没有 Redis 也能运行，自动降级）'
    exit 1
}
Write-Ok "下载完成（$([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB）"

# ---------- 解压 ----------
Write-Step '解压到项目 runtime\redis'
$tmp = Join-Path $dlDir 'redis-extract'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Expand-Archive -Path $zip -DestinationPath $tmp -Force
# tporadowski zip 为平铺结构；兼容可能出现的唯一顶层目录
$src = $tmp
$inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
$files = Get-ChildItem $tmp -File
if (-not $files -and $inner -and (Get-ChildItem $inner.FullName -File)) { $src = $inner.FullName }
New-Item -ItemType Directory -Force -Path $redisDir | Out-Null
Copy-Item (Join-Path $src '*') $redisDir -Recurse -Force
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $zip -Force -ErrorAction SilentlyContinue

if (-not (Test-Path $redisExe)) {
    Write-Fail '解压结果异常（未找到 redis-server.exe）'
    exit 1
}
Write-PortableConf
Write-Ok "已安装到 $redisDir"

if ($Start) {
    $null = Start-PortableRedis
} else {
    Write-Hint '安装完成。下次启动项目时脚本会询问是否运行它；或直接运行：本脚本加 -Start 参数'
}
