#Requires -Version 5.1
<#
便携运行时下载脚本：把 JDK 17 / Node.js 22 下载并解压到项目 runtime\ 文件夹

下载后无需安装、不写注册表、不影响系统，仅本项目可用；
启动脚本会自动优先使用 runtime\ 中的运行时。

用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\setup-runtime.ps1                # 全部（java + node）
    powershell -ExecutionPolicy Bypass -File .\scripts\setup-runtime.ps1 -Component java
    powershell -ExecutionPolicy Bypass -File .\scripts\setup-runtime.ps1 -Component node

下载源均为国内镜像（清华 TUNA / 华为云 / npmmirror），失败时自动切换备用源。
runtime\ 已被 Git 忽略，不会上传到仓库。
#>

param(
    [ValidateSet('all', 'java', 'node')]
    [string]$Component = 'all'
)

$ErrorActionPreference = 'Stop'
$root       = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $root 'runtime'
$dlDir      = Join-Path $runtimeDir 'downloads'

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Hint([string]$msg) { Write-Host "  [ ! ] $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "  [X] $msg" -ForegroundColor Red }

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

# 下载（多源自动切换）→ 解压 → 把 zip 内唯一的顶层目录挪到目标位置
function Install-FromZip([string[]]$urls, [string]$label, [string]$targetDir, [string]$markerFile) {
    if (Test-Path $markerFile) {
        Write-Ok "$label 已存在，跳过（如需重装请先删除 $targetDir）"
        return $true
    }
    $zipName = ($label -replace '[^\w.-]', '_') + '.zip'
    $zip = Join-Path $dlDir $zipName
    $downloaded = $false
    foreach ($url in $urls) {
        Write-Step "下载 $label（来源：$url）"
        if (Invoke-Download $url $zip) {
            if ((Test-Path $zip) -and ((Get-Item $zip).Length -gt 1MB)) { $downloaded = $true; break }
        }
        Write-Hint '该源下载失败，尝试下一个备用源...'
    }
    if (-not $downloaded) {
        Write-Fail "所有下载源均失败，请手动下载以下任一地址并解压到 $targetDir ："
        foreach ($u in $urls) { Write-Host "      $u" -ForegroundColor Yellow }
        return $false
    }
    Write-Ok "下载完成（$([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB）"

    Write-Step "解压 $label"
    $tmp = Join-Path $dlDir 'extract'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) {
        Write-Fail '解压结果异常'
        return $false
    }
    if (Test-Path $targetDir) { Remove-Item $targetDir -Recurse -Force }
    Move-Item $inner.FullName $targetDir
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Write-Ok "已安装到 $targetDir"
    return $true
}

# ---------- JDK 17（Temurin JRE，仅需运行 jar，无需编译） ----------
function Install-PortableJava {
    return Install-FromZip `
        -Urls @(
            'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/windows/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip',
            'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip',
            'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse'
        ) `
        -Label 'JDK 17 (JRE)' `
        -TargetDir (Join-Path $runtimeDir 'java') `
        -MarkerFile (Join-Path $runtimeDir 'java\bin\java.exe')
}

# ---------- Node.js 22 LTS（含 npm） ----------
function Install-PortableNode {
    return Install-FromZip `
        -Urls @(
            'https://registry.npmmirror.com/-/binary/node/v22.16.0/node-v22.16.0-win-x64.zip',
            'https://nodejs.org/dist/v22.16.0/node-v22.16.0-win-x64.zip'
        ) `
        -Label 'Node.js 22' `
        -TargetDir (Join-Path $runtimeDir 'node') `
        -MarkerFile (Join-Path $runtimeDir 'node\node.exe')
}

Write-Host '================================' -ForegroundColor Cyan
Write-Host '  便携运行时安装（仅本项目可用）' -ForegroundColor Cyan
Write-Host '================================' -ForegroundColor Cyan

$ok = $true
if ($Component -in @('all', 'java')) {
    if (-not (Install-PortableJava)) { $ok = $false }
}
if ($Component -in @('all', 'node')) {
    if (-not (Install-PortableNode)) { $ok = $false }
}

if ($ok) {
    Write-Host ''
    Write-Ok '运行时安装完成，重新运行启动脚本即可使用'
} else {
    Write-Host ''
    Write-Fail '部分组件安装失败，请按上方提示手动处理'
    exit 1
}
