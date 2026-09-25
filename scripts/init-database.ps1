#Requires -Version 5.1
<#
店小约数据库初始化脚本：按编号顺序执行 database\sql\ 下的全部 SQL

连接账号取自 backend-java\.env（DB_USERNAME / DB_PASSWORD / DB_URL 中的主机与端口），
执行前会先做连通性与账号测试；SQL 文件本身可重复执行（CREATE ... IF NOT EXISTS），
已初始化过的库重复执行不会丢数据。

用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\init-database.ps1
#>

$ErrorActionPreference = 'Stop'

$root     = Split-Path -Parent $PSScriptRoot
$backend  = Join-Path $root 'backend-java'
$envFile  = Join-Path $backend '.env'
$sqlDir   = Join-Path $root 'database\sql'

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Hint([string]$msg) { Write-Host "  [ ! ] $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "  [X] $msg" -ForegroundColor Red }

function Pause-Exit([int]$code = 1) {
    Write-Host ''
    Read-Host '按回车键关闭窗口' | Out-Null
    exit $code
}

function Get-EnvMap([string]$file) {
    $map = @{}
    Get-Content $file -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $idx = $line.IndexOf('=')
            if ($idx -gt 0) { $map[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim() }
        }
    }
    return $map
}

# ---------- 1. 读取 .env ----------
if (-not (Test-Path $envFile)) {
    Write-Fail "未找到 $envFile"
    Write-Host '  请先运行启动脚本（scripts\start.ps1）完成首次配置' -ForegroundColor Red
    Pause-Exit
}
$cfg = Get-EnvMap $envFile
$dbUser = $cfg['DB_USERNAME']
$dbPass = $cfg['DB_PASSWORD']
$dbHost = '127.0.0.1'
$dbPort = 3306
if ($cfg['DB_URL'] -match 'jdbc:mysql://([^:/]+):(\d+)') {
    $dbHost = $Matches[1]; $dbPort = [int]$Matches[2]
}
if (-not $dbUser -or -not $dbPass) {
    Write-Fail '.env 中缺少 DB_USERNAME / DB_PASSWORD'
    Pause-Exit
}

# ---------- 2. 查找 mysql 客户端 ----------
Write-Step '查找 mysql 客户端'
$mysqlExe = $null
$cmd = Get-Command mysql -ErrorAction SilentlyContinue
if ($cmd) { $mysqlExe = $cmd.Source }
if (-not $mysqlExe) {
    foreach ($pattern in @(
        'C:\Program Files\MySQL\*\bin\mysql.exe',
        'C:\Program Files (x86)\MySQL\*\bin\mysql.exe',
        'C:\xampp\mysql\bin\mysql.exe',
        'D:\MySQL\*\bin\mysql.exe',
        'D:\mysql\*\bin\mysql.exe'
    )) {
        $found = Get-Item $pattern -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1
        if ($found) { $mysqlExe = $found.FullName; break }
    }
}
if (-not $mysqlExe) {
    Write-Fail '未找到 mysql 客户端命令'
    Write-Host '  解决方式（任选其一）：'
    Write-Host '    1. MySQL 安装目录的 bin 文件夹加入 PATH 后重试'
    Write-Host '    2. 使用 Navicat / DBeaver / MySQL Workbench 等工具，'
    Write-Host ("       手动按顺序执行 {0} 下的全部 SQL 文件" -f $sqlDir)
    Pause-Exit
}
Write-Ok "mysql 客户端：$mysqlExe"

# ---------- 3. 连接测试 ----------
Write-Step '测试数据库连接'
$env:MYSQL_PWD = $dbPass
try {
    cmd /c "`"$mysqlExe`" --default-character-set=utf8mb4 -h $dbHost -P $dbPort -u $dbUser -e `"SELECT VERSION();`""
    if ($LASTEXITCODE -ne 0) { throw '连接失败' }
} catch {
    Write-Fail '无法连接 MySQL：请确认服务已启动、.env 中账号密码正确'
    Pause-Exit
}
Write-Ok "连接成功（$dbUser@$dbHost`:$dbPort）"

# ---------- 4. 执行 SQL ----------
$sqlFiles = Get-ChildItem $sqlDir -Filter '*.sql' | Sort-Object Name
if ($sqlFiles.Count -eq 0) {
    Write-Fail "未找到 SQL 文件：$sqlDir"
    Pause-Exit
}

foreach ($f in $sqlFiles) {
    Write-Step "执行 $($f.Name)"
    cmd /c "`"$mysqlExe`" --default-character-set=utf8mb4 -h $dbHost -P $dbPort -u $dbUser < `"$($f.FullName)`""
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "执行失败：$($f.FullName)"
        Write-Host '  请检查上方错误信息；SQL 均可重复执行，修复后重跑本脚本即可'
        Pause-Exit
    }
    Write-Ok "$($f.Name) 完成"
}

$env:MYSQL_PWD = $null
Write-Host ''
Write-Ok "数据库初始化完成（共 $($sqlFiles.Count) 个 SQL 文件，库 shop_booking）"
Write-Host '  重新双击「启动-店小约.bat」启动即可'
