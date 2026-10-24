<#
.SYNOPSIS
  Regenerate every 岐黄课表 logo artefact from design/tools/logo_spec.py.

.DESCRIPTION
  Three steps are required because headless Edge must be launched from
  PowerShell for its screenshot pipeline to work:

    1. spec   - write SVG masters, Android vector XML and render pages
    2. render - rasterise the pages with headless Edge
    3. export - write PNG/ICO sets, Android mipmaps, previews and lockups

  Requires Python with Pillow, Microsoft Edge and the CJK fonts
  shipped with Windows (Microsoft YaHei, DengXian).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File design\tools\build-logo.ps1
#>
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $root
try {
    & $Python 'design\tools\build_logo.py' spec
    if ($LASTEXITCODE -ne 0) { throw 'logo spec stage failed' }

    & powershell -NoProfile -ExecutionPolicy Bypass -File 'design\tools\render.ps1' '.local\logo-render\manifest.txt'
    if ($LASTEXITCODE -ne 0) { throw 'logo render stage failed' }

    & $Python 'design\tools\build_logo.py' export
    if ($LASTEXITCODE -ne 0) { throw 'logo export stage failed' }

    Write-Output 'logo assets rebuilt'
}
finally {
    Pop-Location
}
