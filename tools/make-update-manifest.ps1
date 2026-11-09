param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [Parameter(Mandatory = $true)][string]$Output,
    [string]$Notes = '',
    [string]$Aapt,
    [switch]$AllowDebugHttp
)
$ErrorActionPreference = 'Stop'
$uri = [uri]$ApkUrl
$localHost = $uri.Host -in @('localhost','127.0.0.1','10.0.2.2','192.168.1.76')
if (-not $uri.IsAbsoluteUri -or -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
    ($uri.Scheme -ne 'https' -and -not ($AllowDebugHttp -and $uri.Scheme -eq 'http' -and $localHost))) {
    throw 'APK URL requires HTTPS without credentials, query or fragment; local debug HTTP needs -AllowDebugHttp.'
}
if ($Notes.Length -gt 4000) { throw 'Notes exceed 4000 characters.' }
$apkFile = Get-Item -LiteralPath $Apk
if ($apkFile.PSIsContainer -or $apkFile.Length -lt 1 -or $apkFile.Length -gt 200MB) { throw 'APK must be a file no larger than 200 MiB.' }
if (-not $Aapt) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { throw 'Set ANDROID_HOME or pass -Aapt.' }
    $Aapt = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'aapt.exe' } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $Aapt -or -not (Test-Path -LiteralPath $Aapt)) { throw 'Existing SDK aapt.exe not found.' }
$badging = (& $Aapt dump badging $apkFile.FullName) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'aapt could not inspect APK.' }
if ($badging -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'") { throw 'APK version information missing.' }
$packageName = $Matches[1]; $versionCode = [long]$Matches[2]; $versionName = $Matches[3]
if ($packageName -ne 'com.quizgate.spike') { throw 'Unexpected application ID.' }
if ($versionCode -lt 1 -or -not $versionName -or $versionName.Length -gt 80) { throw 'Invalid application version.' }
if ($badging -notmatch "(?m)^sdkVersion:'(\d+)'") { throw 'APK minimum SDK missing.' }
$minSdk = [int]$Matches[1]
if ($minSdk -lt 26 -or $minSdk -gt 100) { throw 'Unsupported minimum SDK.' }
$manifest = [ordered]@{ schemaVersion = 1; versionCode = $versionCode; versionName = $versionName; minSdk = $minSdk
    sizeBytes = $apkFile.Length; sha256 = (Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    apkUrl = $ApkUrl; notes = $Notes }
$json = $manifest | ConvertTo-Json
$utf8 = New-Object System.Text.UTF8Encoding($false)
if ($utf8.GetByteCount($json) -gt 16384) { throw 'Manifest exceeds 16 KiB.' }
$outputPath = [IO.Path]::GetFullPath($Output)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, $json, $utf8)
Write-Output "Wrote $outputPath. Verify APK signing separately; this tool does not sign, upload or publish."
