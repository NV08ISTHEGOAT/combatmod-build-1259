<#
    Builds dist\WaveOptimizer.exe from WaveOptimizer.ps1 (Windows only).

      powershell -ExecutionPolicy Bypass -File build.ps1

    1. Renders the vector logo defined in the app's XAML into a multi-size WaveOptimizer.ico
    2. Compiles the script into a single admin-elevating .exe with ps2exe (installed on first run)
#>
param([string]$Version = '1.1.0.0')

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$src  = Join-Path $root 'WaveOptimizer.ps1'
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase

# ---------- 1. icon: render the app's own logo at every Windows icon size ----------
$text = Get-Content $src -Raw
$grad = [regex]::Match($text, '(?s)<LinearGradientBrush x:Key="LogoGrad".*?</LinearGradientBrush>').Value
$logo = [regex]::Match($text, '(?s)<DrawingImage x:Key="Logo">.*?</DrawingImage>').Value
if (-not $grad -or -not $logo) { throw 'Logo XAML not found in WaveOptimizer.ps1' }
$dict = [Windows.Markup.XamlReader]::Parse(
    "<ResourceDictionary xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>$grad$logo</ResourceDictionary>")
$image = $dict['Logo']

function Get-LogoPng([int]$size) {
    $dv = New-Object Windows.Media.DrawingVisual
    $dc = $dv.RenderOpen(); $dc.DrawImage($image, (New-Object Windows.Rect 0, 0, $size, $size)); $dc.Close()
    $rtb = New-Object Windows.Media.Imaging.RenderTargetBitmap $size, $size, 96, 96, ([Windows.Media.PixelFormats]::Pbgra32)
    $rtb.Render($dv)
    $enc = New-Object Windows.Media.Imaging.PngBitmapEncoder
    $enc.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($rtb))
    $ms = New-Object IO.MemoryStream; $enc.Save($ms)
    , $ms.ToArray()
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs  = @(foreach ($s in $sizes) { , (Get-LogoPng $s) })
[IO.File]::WriteAllBytes((Join-Path $dist 'logo.png'), $pngs[-1])

# ICO container with PNG-compressed entries (supported since Windows Vista)
$icoPath = Join-Path $dist 'WaveOptimizer.ico'
$bw = New-Object IO.BinaryWriter ([IO.File]::Create($icoPath))
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dim = [byte]($sizes[$i] % 256)              # 0 means 256
    $bw.Write($dim); $bw.Write($dim); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32)
    $bw.Write([uint32]$pngs[$i].Length); $bw.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $bw.Write([byte[]]$p) }
$bw.Close()
Write-Host "Icon    : $icoPath ($($sizes -join ', ') px)"

# ---------- 2. compile ----------
if (-not (Get-Module -ListAvailable -Name ps2exe)) {
    Write-Host 'Installing ps2exe from the PowerShell Gallery...'
    if (-not (Get-PackageProvider -ListAvailable -Name NuGet -ErrorAction SilentlyContinue)) {
        Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Scope CurrentUser -Force | Out-Null
    }
    Set-PSRepository -Name PSGallery -InstallationPolicy Trusted
    Install-Module -Name ps2exe -Scope CurrentUser -Force
}
Import-Module ps2exe

# Console subsystem on purpose: the app hides its own console at startup, and powercfg/netsh calls
# then run silently instead of flashing a new console window each time (which -noConsole would do).
$exe = Join-Path $dist 'WaveOptimizer.exe'
Invoke-ps2exe -inputFile $src -outputFile $exe -iconFile $icoPath -requireAdmin -STA -noConfigFile `
    -title 'WaveOptimizer' -product 'WaveOptimizer' -description 'WaveOptimizer - Windows gaming optimizer' `
    -company 'WaveOptimizer' -copyright 'WaveOptimizer' -version $Version

if (-not (Test-Path $exe)) { throw 'ps2exe did not produce WaveOptimizer.exe' }
$hash = (Get-FileHash $exe -Algorithm SHA256).Hash
Set-Content -Path (Join-Path $dist 'WaveOptimizer.exe.sha256') -Value "$hash  WaveOptimizer.exe" -Encoding ASCII
Write-Host ('Built   : {0} ({1:N0} KB)' -f $exe, ((Get-Item $exe).Length / 1KB))
Write-Host "SHA256  : $hash"
