param([string]$DevEcoHome = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $DevEcoHome) {
    $keys = @('HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*')
    $install = Get-ItemProperty $keys -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq 'DevEco Studio' } | Select-Object -First 1
    $DevEcoHome = $install.InstallLocation
}
if (-not $DevEcoHome -or -not (Test-Path -LiteralPath (Join-Path $DevEcoHome 'tools\hvigor\bin\hvigorw.bat'))) {
    throw 'DevEco Studio was not found. Pass -DevEcoHome with its installation directory.'
}
$oldSdk = $env:DEVECO_SDK_HOME
$oldJava = $env:JAVA_HOME
$oldPath = $env:PATH
Push-Location (Join-Path $root 'harmonyApp')
try {
    $env:DEVECO_SDK_HOME = Join-Path $DevEcoHome 'sdk'
    $env:JAVA_HOME = Join-Path $DevEcoHome 'jbr'
    $env:PATH = (Join-Path $DevEcoHome 'jbr\bin') + ';' + (Join-Path $DevEcoHome 'tools\node') + ';' + (Join-Path $DevEcoHome 'tools\ohpm\bin') + ';' + $oldPath
    & (Join-Path $DevEcoHome 'tools\hvigor\bin\hvigorw.bat') --mode module -p product=default -p module=entry@default assembleHap --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "HarmonyOS build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:DEVECO_SDK_HOME = $oldSdk
    $env:JAVA_HOME = $oldJava
    $env:PATH = $oldPath
}
