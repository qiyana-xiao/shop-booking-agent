#Requires -Version 5.1
<#
店小约（Shop Booking Agent）一键启动脚本（便携版：适配 GitHub/Gitee 下载即用）

流程：环境自检（缺失可自动下载便携运行时到项目 runtime\）→ 首次运行向导生成 .env
      → 检测 MySQL → 按需启动后端(8080)/前端(5173) → 就绪检查 → 打开浏览器

用法：
    双击项目根目录「启动-店小约.bat」
    或：powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1

停止：
    关闭本脚本窗口（会连同后端/前端一起停止）
    或双击项目根目录「停止-店小约.bat」
#>

$ErrorActionPreference = 'Stop'

$root        = Split-Path -Parent $PSScriptRoot
$backend     = Join-Path $root 'backend-java'
$frontend    = Join-Path $root 'frontend'
$runtimeDir  = Join-Path $root 'runtime'
$logsDir     = Join-Path $root 'logs'
$jarName     = 'shop-booking-backend.jar'
$jarPath     = Join-Path $backend "target\$jarName"
$envFile     = Join-Path $backend '.env'
$envExample  = Join-Path $backend '.env.example'
$healthUrl   = 'http://127.0.0.1:8080/api/health/ready'
$frontendUrl = 'http://127.0.0.1:5173'

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Hint([string]$msg) { Write-Host "  [ ! ] $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "  [X] $msg" -ForegroundColor Red }

function Pause-Exit([int]$code = 1) {
    Write-Host ''
    Read-Host '按回车键关闭窗口' | Out-Null
    exit $code
}

function Test-Port([int]$port, [string]$hostName = '127.0.0.1') {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($hostName, $port)
        if ($task.Wait(1200) -and $client.Connected) { return $true }
        return $false
    } catch { return $false } finally { $client.Close() }
}

function Get-EnvMap([string]$file) {
    $map = @{}
    Get-Content $file -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $idx = $line.IndexOf('=')
            if ($idx -gt 0) {
                $k = $line.Substring(0, $idx).Trim()
                $v = $line.Substring($idx + 1).Trim()
                if ($k) { $map[$k] = $v }
            }
        }
    }
    return $map
}

# ---------- 运行时解析：优先使用系统已装环境，不可用时才回退项目 runtime\ 便携运行时 ----------
function Resolve-Java {
    $portable = Join-Path $runtimeDir 'java\bin\java.exe'
    $candidates = @()
    $sys = Get-Command java -ErrorAction SilentlyContinue
    if ($sys) { $candidates += @{ Exe = $sys.Source; Source = '系统 PATH' } }
    if (Test-Path $portable) { $candidates += @{ Exe = $portable; Source = '项目 runtime\ 便携运行时' } }
    foreach ($c in $candidates) {
        # 用 cmd 包装：java -version 往 stderr 写内容，避免 PowerShell 误判为异常
        $verLine = (cmd /c "`"$($c.Exe)`" -version 2>&1" | Select-Object -First 1)
        if ($verLine -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 17) {
                return @{ Exe = $c.Exe; Source = $c.Source; Major = $major }
            }
        }
    }
    return $null
}

function Resolve-Node {
    $portableDir = Join-Path $runtimeDir 'node'
    $portableNode = Join-Path $portableDir 'node.exe'
    $portableNpm  = Join-Path $portableDir 'npm.cmd'
    $candidates = @()
    $sysNode = Get-Command node -ErrorAction SilentlyContinue
    $sysNpm  = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $sysNpm) { $sysNpm = Get-Command npm -ErrorAction SilentlyContinue }
    if ($sysNode -and $sysNpm) {
        $candidates += @{ Node = $sysNode.Source; Npm = $sysNpm.Source; Source = '系统 PATH' }
    }
    if ((Test-Path $portableNode) -and (Test-Path $portableNpm)) {
        $candidates += @{ Node = $portableNode; Npm = $portableNpm; Source = '项目 runtime\ 便携运行时' }
    }
    foreach ($c in $candidates) {
        $ver = (& $c.Node --version)
        if ($ver -match 'v(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 18) {
                return @{ Node = $c.Node; Npm = $c.Npm; Source = $c.Source; Major = $major }
            }
        }
    }
    return $null
}

function Read-Password([string]$prompt) {
    $sec = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function New-RandomSecret {
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

# ---------- 首次运行：交互式生成 .env ----------
function Ensure-DotEnv {
    if (Test-Path $envFile) { return $true }
    Write-Step '首次运行：生成配置文件 backend-java\.env'
    if (-not (Test-Path $envExample)) {
        Write-Fail "未找到配置模板 $envExample"
        return $false
    }
    Write-Host '  按提示填写；直接回车使用 [方括号] 中的默认值。'
    $dbUser = Read-Host '  MySQL 用户名 [root]'
    if (-not $dbUser) { $dbUser = 'root' }
    while ($true) {
        $dbPass = Read-Password '  MySQL 密码（输入不回显）'
        if ($dbPass) { break }
        Write-Host '  密码不能为空，请重新输入' -ForegroundColor Red
    }

    $out = foreach ($line in (Get-Content $envExample -Encoding UTF8)) {
        if     ($line -match '^DB_USERNAME=')     { "DB_USERNAME=$dbUser" }
        elseif ($line -match '^DB_PASSWORD=')     { "DB_PASSWORD=$dbPass" }
        elseif ($line -match '^JWT_SECRET=')      { 'JWT_SECRET=' + (New-RandomSecret) }
        elseif ($line -match '^DEEPSEEK_API_KEY='){ 'DEEPSEEK_API_KEY=' }
        else { $line }
    }
    [IO.File]::WriteAllLines($envFile, $out, (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok "已生成 $envFile（含密码，已被 Git 忽略，不会上传）"
    Write-Hint 'DeepSeek API Key 不在命令行询问：启动后由老板在网页「店铺设置 → AI 客服设置」中填写并加密保存'
    return $true
}

# ---------- 桌面快捷方式：单一「店小约」切换式（未运行则启动，已运行则停止） ----------
function Ensure-DesktopShortcut {
    try {
        $desktop = [Environment]::GetFolderPath('Desktop')
        $ws = New-Object -ComObject WScript.Shell
        $psExe = "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe"
        $ico = Join-Path $root 'branding\shop-booking.ico'

        foreach ($legacy in @('店小约 - 启动.lnk', '店小约 - 停止.lnk')) {
            $old = Join-Path $desktop $legacy
            if (Test-Path $old) { Remove-Item $old -Force }
        }

        $lnk = $ws.CreateShortcut((Join-Path $desktop '店小约.lnk'))
        $lnk.TargetPath = $psExe
        $lnk.Arguments = "-NoExit -ExecutionPolicy Bypass -File `"$root\scripts\toggle.ps1`""
        $lnk.WorkingDirectory = $root
        if (Test-Path $ico) { $lnk.IconLocation = $ico }
        $lnk.Description = '店小约一键切换：未运行则启动，已运行则停止'
        $lnk.Save()
        Write-Ok '桌面快捷方式已就绪（店小约：双击启动，再点一次停止）'
    } catch {
        Write-Hint "桌面快捷方式创建失败（不影响运行）：$_"
    }
}

# ============================================================
Write-Host '================================' -ForegroundColor Cyan
Write-Host '  店小约 · 便携启动脚本' -ForegroundColor Cyan
Write-Host '================================' -ForegroundColor Cyan
Write-Host "  项目目录：$root"

# ---------- 1. 端口检查 ----------
Write-Step '检查端口占用'
foreach ($p in @(8080, 5173)) {
    if (Test-Port $p) {
        Write-Fail "端口 $p 被占用（服务可能已在运行）"
        Write-Host '  双击项目根目录「停止-店小约.bat」停止已有服务后重试' -ForegroundColor Red
        Pause-Exit
    }
}
Write-Ok '8080 / 5173 空闲'

# ---------- 2. 环境自检与自举 ----------
Write-Step '检查运行环境（Java 17+ / Node 18+）'

$java = Resolve-Java
if (-not $java) {
    Write-Hint '未找到可用的 Java 17+（Spring Boot 3 必需）'
    $ans = Read-Host '  是否自动下载便携版 JDK 17 到项目 runtime\ 文件夹？约 45MB，只需一次 [Y/n]'
    if (-not $ans -or $ans -match '^[Yy]') {
        & (Join-Path $PSScriptRoot 'setup-runtime.ps1') -Component java
        $java = Resolve-Java
    }
}
if (-not $java) {
    Write-Fail '没有可用的 Java 17+，无法启动后端'
    Write-Host '  手动安装指引：https://adoptium.net/ 或 https://mirrors.tuna.tsinghua.edu.cn/Adoptium/' -ForegroundColor Red
    Pause-Exit
}
Write-Ok "Java $($java.Major)（来自 $($java.Source)）"

$node = Resolve-Node
if (-not $node) {
    Write-Hint '未找到可用的 Node.js 18+（前端开发服务器必需）'
    $ans = Read-Host '  是否自动下载便携版 Node.js 22 到项目 runtime\ 文件夹？约 30MB，只需一次 [Y/n]'
    if (-not $ans -or $ans -match '^[Yy]') {
        & (Join-Path $PSScriptRoot 'setup-runtime.ps1') -Component node
        $node = Resolve-Node
    }
}
if (-not $node) {
    Write-Fail '没有可用的 Node.js 18+，无法启动前端'
    Write-Host '  手动安装指引：https://nodejs.org/ 或 https://registry.npmmirror.com/binary.html?path=node/' -ForegroundColor Red
    Pause-Exit
}
Write-Ok "Node $($node.Major)（来自 $($node.Source)）"

# ---------- 3. MySQL ----------
Write-Step '检查 MySQL'
if (-not (Test-Port 3306)) {
    Write-Fail 'MySQL(3306) 未启动或未安装'
    Write-Host '  本项目需要一个 MySQL 8.0 服务；Redis 为可选（未装自动降级，订单不丢）。' -ForegroundColor Red
    Write-Host '  安装方式（任选其一）：'
    Write-Host '    1. MySQL 官方安装包（推荐）：https://dev.mysql.com/downloads/installer/'
    Write-Host '    2. 免安装 zip 版：https://dev.mysql.com/downloads/mysql/ 解压后需自行初始化'
    Write-Host '  安装并启动 MySQL 后，重新双击启动即可。'
    Pause-Exit
}
Write-Ok 'MySQL(3306) 可达'

# ---------- 3.5 Redis（可选：对话短期记忆/限流/幂等；未装自动降级不丢订单，是否安装由用户选择） ----------
Write-Step '检查 Redis（可选组件）'
if (Test-Port 6379) {
    Write-Ok 'Redis(6379) 可达（使用已运行的 Redis）'
} else {
    $redisDir = Join-Path $runtimeDir 'redis'
    $redisExe = Join-Path $redisDir 'redis-server.exe'
    if (Test-Path $redisExe) {
        $ans = Read-Host '  检测到项目内便携版 Redis，本次启动是否运行它？(Y/n)'
        if ($ans -eq '' -or $ans -match '^[Yy]') {
            & (Join-Path $PSScriptRoot 'setup-redis.ps1') -Start
        } else {
            Write-Hint '已跳过 Redis：对话记忆/限流退化为进程内实现，订单不丢'
        }
    } else {
        Write-Hint 'Redis(6379) 未运行：可选组件，不装也能用（对话记忆/限流退化为进程内实现，不丢订单）'
        $ans = Read-Host '  是否现在下载便携版 Redis 到项目 runtime\（约 10MB，仅本项目可用）？(y/N)'
        if ($ans -match '^[Yy]') {
            & (Join-Path $PSScriptRoot 'setup-redis.ps1') -Start
        } else {
            Write-Hint '已跳过。之后想装可运行 scripts\setup-redis.ps1，或下次启动时再选'
        }
    }
}

# ---------- 4. 配置文件 ----------
if (-not (Ensure-DotEnv)) { Pause-Exit }
$cfg = Get-EnvMap $envFile

# ---------- 5. 后端 jar（仓库自带，无需 Maven） ----------
if (-not (Test-Path $jarPath)) {
    Write-Fail "未找到后端程序包 backend-java\target\$jarName"
    Write-Host '  正常情况下仓库自带已构建的 jar。若被删除，可任选：' -ForegroundColor Red
    Write-Host '    1. 重新下载完整项目'
    Write-Host '    2. 安装 JDK 17+ 与 Maven 后，在 backend-java 目录执行：mvn -DskipTests clean package'
    Pause-Exit
}
Write-Ok '后端 jar 已就绪（仓库自带预构建包，无需安装 Maven）'

# ---------- 6. 前端依赖 ----------
Write-Step '检查前端依赖'
if (Test-Path (Join-Path $frontend 'node_modules\vite')) {
    Write-Ok 'node_modules 已就绪'
} else {
    Write-Host '  首次运行：正在安装前端依赖（约 1-3 分钟，取决于网速）...'
    Push-Location $frontend
    try {
        cmd /c "`"$($node.Npm)`" install --no-fund --no-audit"
        if ($LASTEXITCODE -ne 0) { throw 'npm install 失败，请检查网络后重试' }
    } catch {
        Write-Fail $_
        Pop-Location
        Pause-Exit
    }
    Pop-Location
    Write-Ok '前端依赖安装完成'
}

# ---------- 7. 启动服务 ----------
Write-Step '启动后端 :8080'
foreach ($kv in $cfg.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($kv.Key, $kv.Value, 'Process')
}
$backendProc = Start-Process -FilePath $java.Exe -ArgumentList '-jar', $jarPath `
    -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logsDir 'backend.log') `
    -RedirectStandardError (Join-Path $logsDir 'backend-err.log')
Write-Ok "后端进程 PID $($backendProc.Id)，日志：logs\backend.log"

Write-Step '启动前端 :5173'
$frontendProc = Start-Process -FilePath $node.Npm -ArgumentList 'run', 'dev' `
    -WorkingDirectory $frontend -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logsDir 'frontend.log') `
    -RedirectStandardError (Join-Path $logsDir 'frontend-err.log')
Write-Ok "前端进程 PID $($frontendProc.Id)，日志：logs\frontend.log"

# ---------- 8. 就绪检查 ----------
Write-Step '等待后端就绪（最长 90 秒）'
$ready = $false
$dbIssue = $false
for ($i = 0; $i -lt 45; $i++) {
    if ($backendProc.HasExited) {
        Write-Fail '后端进程异常退出'
        Get-Content (Join-Path $logsDir 'backend.log') -Tail 20 -ErrorAction SilentlyContinue |
            ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
        Pause-Exit
    }
    try {
        $resp = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2
        if ($resp.StatusCode -eq 200) { $ready = $true; break }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        if ($code -eq 503) { $dbIssue = $true; break }
    }
    Start-Sleep -Seconds 2
}

if ($dbIssue) {
    Write-Fail '后端已启动，但数据库未就绪（HTTP 503）'
    Write-Host '  最常见原因：'
    Write-Host '    1. 尚未执行建库 SQL（shop_booking 库或表不存在）'
    Write-Host '    2. .env 中 MySQL 密码与实际不一致'
    $ans = Read-Host '  是否现在自动建库（用 .env 中的账号执行 database\sql\ 下的 SQL）？[Y/n]'
    if (-not $ans -or $ans -match '^[Yy]') {
        & (Join-Path $PSScriptRoot 'init-database.ps1')
        Write-Step '重新等待后端就绪（最长 60 秒）'
        for ($i = 0; $i -lt 30; $i++) {
            try {
                $resp = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2
                if ($resp.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 2
        }
    }
}

if (-not $ready) {
    Write-Fail '后端未就绪，请查看 logs\backend.log'
    Get-Content (Join-Path $logsDir 'backend.log') -Tail 20 -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    Pause-Exit
}
Write-Ok '后端就绪'

Write-Step '等待前端就绪'
for ($i = 0; $i -lt 30; $i++) {
    try {
        Invoke-WebRequest -Uri $frontendUrl -UseBasicParsing -TimeoutSec 2 | Out-Null
        break
    } catch { Start-Sleep -Seconds 1 }
}
Write-Ok '前端就绪'

# ---------- 9. 完成 ----------
Ensure-DesktopShortcut
Start-Process 'http://localhost:5173'

Write-Host ''
Write-Host '=====================================================' -ForegroundColor Green
Write-Host ' 店小约已启动！' -ForegroundColor Green
Write-Host ' 顾客对话入口   http://localhost:5173' -ForegroundColor Green
Write-Host ' 老板/店员登录   http://localhost:5173/login' -ForegroundColor Green
Write-Host ' 首次使用：注册 owner 账号 -> 完成开店向导 -> 生成档期' -ForegroundColor Green
Write-Host ' 停止服务：双击「停止-店小约.bat」或桌面「店小约」（再点一次即停止）' -ForegroundColor Green
Write-Host '=====================================================' -ForegroundColor Green
Write-Host ''
Write-Host '本窗口保持打开即保持服务运行；按 Ctrl+C 或关闭窗口停止全部服务。' -ForegroundColor DarkGray
Write-Host '查看后端日志：logs\backend.log' -ForegroundColor DarkGray

try {
    while ($true) {
        if ($backendProc.HasExited) { Write-Hint '后端进程已退出，即将停止'; break }
        if ($frontendProc.HasExited) { Write-Hint '前端进程已退出，即将停止'; break }
        Start-Sleep -Seconds 3
    }
} finally {
    Write-Host ''
    Write-Host '正在停止服务...' -ForegroundColor Cyan
    foreach ($p in @($backendProc, $frontendProc)) {
        if ($p -and -not $p.HasExited) {
            cmd /c "taskkill /PID $($p.Id) /T /F" | Out-Null
        }
    }
    foreach ($port in @(8080, 5173)) {
        $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        foreach ($c in $conns) {
            try { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
    Write-Host '已全部停止。' -ForegroundColor Cyan
}
