#Requires -Version 5.1
<#
Brand icon generator for ShopXiaoYue (self-drawn, no external tools).

Output:
    branding\shop-booking.ico    desktop shortcut icon (256/48/32/16 PNG entries)
    frontend\public\favicon.png  web favicon (256px)

Design: teal gradient rounded square + centered white glyph "yue".
Re-run anytime to regenerate.
NOTE: keep this file ASCII-only (PowerShell 5.1 reads BOM-less files as ANSI).
#>

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root   = $PSScriptRoot | Split-Path
$icoDir = $PSScriptRoot
$public = Join-Path $root 'frontend\public'
New-Item $public -ItemType Directory -Force | Out-Null

$GLYPH = [string][char]0x7EA6   # CJK glyph "yue" (U+7EA6)

$FONT_CANDIDATES = @('Microsoft YaHei UI', 'Microsoft YaHei', 'SimHei')
$installed = (New-Object System.Drawing.Text.InstalledFontCollection).Families | ForEach-Object { $_.Name }
$FONT_FAMILY = $FONT_CANDIDATES | Where-Object { $installed -contains $_ } | Select-Object -First 1
if (-not $FONT_FAMILY) { $FONT_FAMILY = 'Arial' }

function New-BrandBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        $g.Clear([System.Drawing.Color]::Transparent)

        # rounded square: #0d9488 -> #14b8a6 diagonal gradient (frontend theme colors)
        $radius = [int]($size * 0.22)
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $d = $radius * 2
        $path.AddArc(0, 0, $d, $d, 180, 90)
        $path.AddArc($size - 1 - $d, 0, $d, $d, 270, 90)
        $path.AddArc($size - 1 - $d, $size - 1 - $d, $d, $d, 0, 90)
        $path.AddArc(0, $size - 1 - $d, $d, $d, 90, 90)
        $path.CloseFigure()
        $rect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
        $c1 = [System.Drawing.Color]::FromArgb(255, 0x0d, 0x94, 0x88)
        $c2 = [System.Drawing.Color]::FromArgb(255, 0x14, 0xb8, 0xa6)
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, 45.0)
        $g.FillPath($brush, $path)
        $brush.Dispose()

        # centered white bold glyph; shift up 4% to fix CJK visual center
        $fontPx = [int]($size * 0.56)
        $font = New-Object System.Drawing.Font($FONT_FAMILY, $fontPx,
            [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $sf = New-Object System.Drawing.StringFormat
        $sf.Alignment = [System.Drawing.StringAlignment]::Center
        $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
        $textRect = New-Object System.Drawing.RectangleF(0, -([float]($size * 0.04)), $size, $size)
        $g.DrawString($GLYPH, $font, [System.Drawing.Brushes]::White, $textRect, $sf)
        $font.Dispose()
        $sf.Dispose()
        return $bmp
    } finally {
        $g.Dispose()
    }
}

function Get-PngBytes([System.Drawing.Bitmap]$bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    return $ms.ToArray()
}

# ---------- 1. bitmaps ----------
$sizes = @(256, 48, 32, 16)
$entries = @()
foreach ($s in $sizes) {
    $bmp = New-BrandBitmap $s
    if ($s -eq 256) {
        $bmp.Save((Join-Path $public 'favicon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    }
    $entries += ,@{ Size = $s; Bytes = (Get-PngBytes $bmp) }
    $bmp.Dispose()
}

# ---------- 2. assemble ICO (embedded PNG entries, native since Vista) ----------
$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([UInt16]0)                     # reserved
$bw.Write([UInt16]1)                     # type: icon
$bw.Write([UInt16]$entries.Count)        # entry count

$offset = 6 + 16 * $entries.Count
foreach ($e in $entries) {
    $dim = if ($e.Size -ge 256) { 0 } else { $e.Size }   # 256 encoded as 0
    $bw.Write([Byte]$dim)                 # width
    $bw.Write([Byte]$dim)                 # height
    $bw.Write([Byte]0)                    # palette
    $bw.Write([Byte]0)                    # reserved
    $bw.Write([UInt16]1)                  # color planes
    $bw.Write([UInt16]32)                 # bits per pixel
    $bw.Write([UInt32]$e.Bytes.Length)    # data length
    $bw.Write([UInt32]$offset)            # data offset
    $offset += $e.Bytes.Length
}
foreach ($e in $entries) { $bw.Write($e.Bytes) }
$bw.Flush()

$icoPath = Join-Path $icoDir 'shop-booking.ico'
[System.IO.File]::WriteAllBytes($icoPath, $ms.ToArray())
$bw.Dispose(); $ms.Dispose()

Write-Host "[OK] $icoPath ($($sizes -join '/') px)" -ForegroundColor Green
Write-Host "[OK] $public\favicon.png (256px)" -ForegroundColor Green
Write-Host "     font: $FONT_FAMILY"
