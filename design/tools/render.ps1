<#
.SYNOPSIS
  Rasterize transparent logo pages with headless Microsoft Edge.

.DESCRIPTION
  Edge's screenshot pipeline is only reliable when it is launched directly from
  PowerShell (piped stdio breaks the capture), so the logo toolchain writes
  HTML wrappers in Python and calls this script for rasterization.

  The manifest file has one entry per line:  <html path>|<png path>|<size>
  Relative paths are resolved against the manifest's directory.

.PARAMETER Manifest
  Path to a render manifest file.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File design\tools\render.ps1 .local\logo-render\manifest.txt
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Manifest
)

$ErrorActionPreference = 'Stop'
$edge = @(
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files\Microsoft\Edge\Application\msedge.exe'
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw 'Microsoft Edge not found' }

$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$base = Split-Path -Parent $manifestPath
$profile = Join-Path $base 'edge-profile'
New-Item -ItemType Directory -Force -Path $profile | Out-Null

$entries = Get-Content -LiteralPath $manifestPath | Where-Object { $_.Trim() -ne '' } | ForEach-Object {
    $parts = $_ -split '\|'
    if ($parts.Count -ne 3) { throw "Bad manifest line: $_" }
    $html = [System.IO.Path]::GetFullPath((Join-Path $base $parts[0].Trim()))
    $png = [System.IO.Path]::GetFullPath((Join-Path $base $parts[1].Trim()))
    [pscustomobject]@{ Html = $html; Png = $png; Size = [int]$parts[2].Trim() }
}
if (-not $entries) { throw "Manifest is empty: $manifestPath" }

foreach ($entry in $entries) {
    if (-not (Test-Path -LiteralPath $entry.Html)) { throw "Missing page: $($entry.Html)" }
    $pngDir = Split-Path -Parent $entry.Png
    New-Item -ItemType Directory -Force -Path $pngDir | Out-Null
    if (Test-Path -LiteralPath $entry.Png) { Remove-Item -LiteralPath $entry.Png -Force }
    $uri = ([uri]$entry.Html).AbsoluteUri
    # Edge reports successful captures on stderr. Windows PowerShell otherwise
    # treats that diagnostic as a terminating NativeCommandError under Stop.
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $edge --headless --disable-gpu --no-sandbox --hide-scrollbars `
            --force-device-scale-factor=1 "--window-size=$($entry.Size),$($entry.Size)" `
            '--default-background-color=00000000' "--user-data-dir=$profile" `
            "--screenshot=$($entry.Png)" $uri 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Edge render failed with exit code $LASTEXITCODE" }
    } finally { $ErrorActionPreference = $previousErrorPreference }
    $deadline = (Get-Date).AddSeconds(20)
    while (-not (Test-Path -LiteralPath $entry.Png) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 150
    }
    if (-not (Test-Path -LiteralPath $entry.Png)) { throw "Screenshot failed: $($entry.Html)" }
    Write-Output ("rendered {0} -> {1} ({2}px)" -f (Split-Path -Leaf $entry.Html), (Split-Path -Leaf $entry.Png), $entry.Size)
}
