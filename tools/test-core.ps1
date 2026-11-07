param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$KotlinHome,
    [Parameter(Mandatory = $true)][string]$JunitJar,
    [Parameter(Mandatory = $true)][string]$HamcrestJar
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$buildRoot = Join-Path $repoRoot ('app\build\core-tests\' + [guid]::NewGuid().ToString('N'))
$protoClasses = Join-Path $buildRoot 'proto'
$testClasses = Join-Path $buildRoot 'classes'
$tempRoot = Join-Path $buildRoot 'tmp'
New-Item -ItemType Directory -Force $protoClasses,$testClasses,$tempRoot | Out-Null
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$kotlinLib = Join-Path $KotlinHome 'lib'
$protoJar = Join-Path $repoRoot 'app\libs\protobuf-javalite-4.36.2.jar'
foreach ($required in @($java,$javac,$protoJar,$JunitJar,$HamcrestJar,(Join-Path $kotlinLib 'kotlin-compiler.jar'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing dependency: $required" }
}
function Write-SourceList($path, $sources) {
    [IO.File]::WriteAllLines($path, [string[]]@($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }))
}
$protoList = Join-Path $buildRoot 'proto-sources.txt'
Write-SourceList $protoList (Get-ChildItem (Join-Path $repoRoot 'app\src\main\java-gen') -Recurse -Filter *.java | ForEach-Object FullName)
& $javac --release 17 -encoding UTF-8 -cp $protoJar -d $protoClasses "@$protoList"
if ($LASTEXITCODE -ne 0) { throw 'Protobuf compilation failed' }
$sourceRoot = Join-Path $repoRoot 'app\src\main\java\com\quizgate\spike'
$sources = @(Get-ChildItem (Join-Path $sourceRoot 'question') -Filter *.kt | ForEach-Object FullName)
$sources += @('monitor\SessionTracker.kt','monitor\SessionStore.kt','monitor\GateHealth.kt',
    'monitor\GateEventStore.kt','monitor\UsageEventStore.kt','net\ApiClient.kt','net\ReportPayloads.kt',
    'net\RemoteQuestionRepository.kt','net\UsageUploader.kt') | ForEach-Object { Join-Path $sourceRoot $_ }
$tests = @(Get-ChildItem (Join-Path $repoRoot 'app\src\test') -Recurse -Filter *.kt)
$sources += $tests | ForEach-Object FullName
$kotlinList = Join-Path $buildRoot 'kotlin-sources.txt'
Write-SourceList $kotlinList $sources
$classpath = @($protoClasses,$protoJar,(Join-Path $kotlinLib 'kotlin-stdlib.jar'),$JunitJar,$HamcrestJar) -join ';'
& $java -cp "$kotlinLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath $classpath -d $testClasses "@$kotlinList"
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed' }
$testNames = $tests | ForEach-Object {
    $source = Get-Content -LiteralPath $_.FullName -Raw
    if ($source -match 'package\s+([\w.]+)') { $Matches[1] + '.' + $_.BaseName }
}
& $java "-Djava.io.tmpdir=$tempRoot" -cp "$testClasses;$classpath" org.junit.runner.JUnitCore @testNames
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
Write-Output 'Core JVM tests passed. This does not build an APK or verify Android UI/ROM behavior.'
