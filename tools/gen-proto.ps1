param(
    [string]$ServerRoot = (Join-Path $PSScriptRoot '..\..\quizgate-server'),
    [string]$Protoc = $env:PROTOC,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverRootPath = (Resolve-Path -LiteralPath $ServerRoot).Path
$protoRoot = Join-Path $serverRootPath 'proto'
$protoSource = Join-Path $protoRoot 'quizgate.proto'
$runtimeVersion = '4.36.2'
$runtimeJar = Join-Path $repoRoot "app\libs\protobuf-javalite-$runtimeVersion.jar"
foreach ($required in @($protoSource, $runtimeJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing required file: $required" }
}
if (-not $Protoc) {
    $artifacts = Join-Path $serverRootPath 'target\protobuf-maven-plugin'
    if (Test-Path -LiteralPath $artifacts) {
        $compiler = Get-ChildItem -LiteralPath $artifacts -Recurse -File -Filter 'protoc-*.exe' |
            Where-Object Name -NotLike 'protoc-gen-*' | Select-Object -First 1
        if ($compiler) { $Protoc = $compiler.FullName }
    }
    if (-not $Protoc) {
        $command = Get-Command protoc -ErrorAction SilentlyContinue
        if ($command) { $Protoc = $command.Source }
    }
}
if (-not $Protoc -or -not (Test-Path -LiteralPath $Protoc -PathType Leaf)) {
    throw 'No protoc found. Run mvnw compile in the server first, or pass -Protoc / set PROTOC.'
}
$compilerVersion = (& $Protoc --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch '^libprotoc (4\.)?36\.2$') {
    throw "Expected protoc 36.2 for protobuf-javalite $runtimeVersion; received: $compilerVersion"
}

# Generate outside the tracked source tree. Failed generation never deletes existing sources.
$staging = Join-Path $repoRoot ('app\build\proto-sync\' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $staging | Out-Null
& $Protoc "--java_out=lite:$staging" "--proto_path=$protoRoot" quizgate.proto
if ($LASTEXITCODE -ne 0) { throw 'Protobuf generation failed; tracked sources were not modified.' }
$output = [IO.Path]::GetFullPath((Join-Path $repoRoot 'app\src\main\java-gen'))
if (-not $output.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Generated source output must remain inside the Android repository.'
}
$expected = @{}
foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File -Filter '*.java') {
    $relative = $file.FullName.Substring($staging.Length + 1)
    $expected[$relative] = $file.FullName
    if ([IO.File]::ReadAllText($file.FullName) -notmatch "Protobuf Java Version: $([regex]::Escape($runtimeVersion))") {
        throw "Unexpected generator runtime version in $relative"
    }
}
if ($expected.Count -eq 0) { throw 'protoc generated no Java sources.' }
function Normalized-Source([string]$path) {
    [IO.File]::ReadAllText($path).Replace("`r`n", "`n")
}
$differences = @()
foreach ($relative in $expected.Keys | Sort-Object) {
    $target = Join-Path $output $relative
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        $differences += "Missing: $relative"
    } elseif ((Normalized-Source $target) -cne (Normalized-Source $expected[$relative])) {
        $differences += "Changed: $relative"
    } else { continue }
    if (-not $Check) {
        New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
        Copy-Item -LiteralPath $expected[$relative] -Destination $target
    }
}
if (Test-Path -LiteralPath $output) {
    foreach ($file in Get-ChildItem -LiteralPath $output -Recurse -File -Filter '*.java') {
        $relative = $file.FullName.Substring($output.Length + 1)
        if (-not $expected.ContainsKey($relative)) {
            $differences += "Stale: $relative"
            if (-not $Check) { Remove-Item -LiteralPath $file.FullName }
        }
    }
}
if ($Check -and $differences.Count -gt 0) {
    $differences | Select-Object -First 20 | Write-Output
    throw "Generated sources differ in $($differences.Count) files. Run this script without -Check to update."
}
Write-Output "Protobuf $runtimeVersion source alignment passed: $($expected.Count) Java files; changed $($differences.Count)."
