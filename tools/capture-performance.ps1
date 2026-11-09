param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$Serial,
    [ValidateRange(1,20)][int]$Samples = 3,
    [ValidateRange(5,60)][int]$IntervalSeconds = 30
)
$ErrorActionPreference = 'Stop'
$adbArgs = @(); if ($Serial) { $adbArgs = @('-s', $Serial) }
$deviceState = & $Adb @adbArgs get-state 2>$null
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') { throw 'Select one running test device; use -Serial if several are connected.' }
$target = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $target) { throw 'Choose a new output directory to preserve earlier measurements.' }
New-Item -ItemType Directory -Path $target | Out-Null
function Capture([string]$Name, [string[]]$Arguments) {
    $value = & $Adb @adbArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB capture failed: $Name" }
    $value | Set-Content -LiteralPath (Join-Path $target $Name) -Encoding UTF8
}
Capture 'version.txt' @('shell','dumpsys','package','com.quizgate.spike')
for ($sample = 1; $sample -le $Samples; $sample++) {
    Capture "meminfo-$sample.txt" @('shell','dumpsys','meminfo','com.quizgate.spike')
    if ($sample -lt $Samples) { Start-Sleep -Seconds $IntervalSeconds }
}
Capture 'gfxinfo.txt' @('shell','dumpsys','gfxinfo','com.quizgate.spike','framestats')
@{ capturedAt = [DateTime]::UtcNow.ToString('o'); samples = $Samples; intervalSeconds = $IntervalSeconds
    packageName = 'com.quizgate.spike'; scope = 'This app only. No frame reset, force-stop or game/session changes. Gfxinfo does not measure overlay-only gameplay or another app.' } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $target 'capture.json') -Encoding UTF8
Write-Output "Saved $target. Also manually export in-app diagnostics for CPU, queue delay, HTTP and operation timings."
