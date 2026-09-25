#Requires -Version 5.1
<#
店小约环境体检脚本（只读诊断，不做任何修改）

逐项检查启动所需的环境与文件，输出一目了然的状态表，
下载项目后可先运行本脚本确认缺什么。

用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\check-env.ps1
    或双击「体检-店小约.bat」
#>

$ErrorActionPreference = 'Continue'
$root       = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $root 'runtime'
$jarPath    = Join-Path $root 'backend-java\target\shop-booking-backend.jar'
$envFile    = Join-Path $root 'backend-java\.env'

function Test-Port([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', $port)
        if ($task.Wait(1000) -and $client.Connected) { return $true }
        return $false
    } catch { return $false } finally { $client.Close() }
}

function Get-JavaInfo {
    foreach ($exe in @((Join-Path $runtimeDir 'java\bin\java.exe'), (Get-Command java -ErrorAction SilentlyContinue).Source)) {
        if ($exe -and (Test-Path $exe)) {
            $verLine = (cmd /c "`"$exe`" -version 2>&1" | Select-Object -First 1)
            if ($verLine -match 'version "([\d.]+)"') {
                $source = if ($exe -like "$runtimeDir*") { 'runtime\ 便携' } else { '系统' }
                return @{ Ver = $Matches[1]; Source = $source }
            }
        }
    }
    return $null
}

function Get-NodeInfo {
    $portable = Join-Path $runtimeDir 'node\node.exe'
    $sys = Get-Command node -ErrorAction SilentlyContinue
    foreach ($exe in @($portable, $sys.Source)) {
        if ($exe -and (Test-Path $exe)) {
            $ver = (& $exe --version)
            if ($ver -match 'v([\d.]+)') {
                $source = if ($exe -eq $portable) { 'runtime\ 便携' } else { '系统' }
                return @{ Ver = $Matches[1]; Source = $source }
            }
        }
    }
    return $null
}

$rows = New-Object System.Collections.Generic.List[object]

$java = Get-JavaInfo
if ($java) {
    $ok = [int]($java.Ver.Split('.')[0]) -ge 17
    $rows.Add([pscustomobject]@{ 项目 = 'Java'; 状态 = if ($ok) { 'OK' } else { '版本过低' }; 详情 = "$($java.Ver)（$($java.Source)）需 17+" })
} else {
    $rows.Add([pscustomobject]@{ 项目 = 'Java'; 状态 = '缺失'; 详情 = '启动脚本可自动下载便携版' })
}

$node = Get-NodeInfo
if ($node) {
    $ok = [int]($node.Ver.Split('.')[0]) -ge 18
    $rows.Add([pscustomobject]@{ 项目 = 'Node.js'; 状态 = if ($ok) { 'OK' } else { '版本过低' }; 详情 = "$($node.Ver)（$($node.Source)）需 18+" })
} else {
    $rows.Add([pscustomobject]@{ 项目 = 'Node.js'; 状态 = '缺失'; 详情 = '启动脚本可自动下载便携版' })
}

$rows.Add([pscustomobject]@{ 项目 = 'MySQL 服务(3306)'; 状态 = if (Test-Port 3306) { 'OK' } else { '未运行' }; 详情 = '必需；未装见 README 安装指引' })
$rows.Add([pscustomobject]@{ 项目 = 'Redis 服务(6379)'; 状态 = if (Test-Port 6379) { 'OK' } else { '未运行' }; 详情 = '可选，未装自动降级订单不丢；可运行 scripts\setup-redis.ps1 安装便携版' })

$envState = if (Test-Path $envFile) { 'OK' } else { '未创建' }
$rows.Add([pscustomobject]@{ 项目 = '配置 .env'; 状态 = $envState; 详情 = if ($envState -eq 'OK') { $envFile } else { '首次启动会自动引导创建' } })

$jarState = if (Test-Path $jarPath) { 'OK' } else { '缺失' }
$rows.Add([pscustomobject]@{ 项目 = '后端 jar'; 状态 = $jarState; 详情 = if ($jarState -eq 'OK') { "大小 $([math]::Round((Get-Item $jarPath).Length / 1MB, 1)) MB" } else { '需用 Maven 构建或重新下载' } })

$nmState = if (Test-Path (Join-Path $root 'frontend\node_modules\vite')) { 'OK' } else { '未安装' }
$rows.Add([pscustomobject]@{ 项目 = '前端依赖'; 状态 = $nmState; 详情 = if ($nmState -eq 'OK') { 'node_modules 已就绪' } else { '首次启动自动 npm install' } })

foreach ($p in @(8080, 5173)) {
    if (Test-Port $p) {
        $rows.Add([pscustomobject]@{ 项目 = "端口 $p"; 状态 = '被占用'; 详情 = '服务可能已在运行，或被其他程序占用' })
    } else {
        $rows.Add([pscustomobject]@{ 项目 = "端口 $p"; 状态 = '空闲'; 详情 = '' })
    }
}

Write-Host ''
Write-Host '================ 店小约 环境体检 ================' -ForegroundColor Cyan
$rows | Format-Table 项目, 状态, 详情 -AutoSize
Write-Host '说明：Java / Node 缺失时无需手动安装，直接双击「启动-店小约.bat」，'
Write-Host '      启动脚本会询问后自动下载便携版到 runtime\（仅本项目可用）。'
Write-Host ''
Read-Host '按回车键关闭窗口' | Out-Null
