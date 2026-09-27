param(
    [Parameter(Mandatory = $true)]
    [string] $JavapPath,
    [Parameter(Mandatory = $true)]
    [string] $WebStormPath
)

# Offline artifact inspection only; never attach to the running IDE.
$ErrorActionPreference = 'Stop'
$bunJarPath = Join-Path $WebStormPath 'plugins\javascript-bun\lib\javascript-bun.jar'
if (-not (Test-Path -LiteralPath $JavapPath -PathType Leaf)) {
    throw "Bytecode viewer not found: $JavapPath"
}
if (-not (Test-Path -LiteralPath $bunJarPath -PathType Leaf)) {
    throw "Bun plugin JAR not found: $bunJarPath"
}

$workspacePath = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $workspacePath 'build\phase-0\api'
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$inspectionJarPath = Join-Path $outputPath 'javascript-bun.jar'
Copy-Item -LiteralPath $bunJarPath -Destination $inspectionJarPath -Force
$classes = @(
    'com.intellij.javascript.bun.runConfiguration.run.BunRunConfigurationType',
    'com.intellij.javascript.bun.runConfiguration.run.BunRunConfiguration',
    'com.intellij.javascript.bun.runConfiguration.run.BunRunConfigurationOptions',
    'com.intellij.javascript.bun.settings.BunSettingsService',
    'com.intellij.javascript.bun.settings.BunSettingsService$Companion',
    'com.intellij.javascript.bun.settings.BunRuntimeProvider',
    'com.intellij.javascript.bun.settings.BunRuntimeType',
    'com.intellij.javascript.bun.BunDebugAdapterSupportProvider'
)

foreach ($className in $classes) {
    # -private lets us distinguish inaccessible members; it does not access
    # live objects or grant permission to call private/internal APIs.
    $dump = & $JavapPath -private -verbose -classpath $inspectionJarPath $className 2>&1
    $viewerExitCode = $LASTEXITCODE
    $dumpPath = Join-Path $outputPath "$className.txt"
    $dump | Set-Content -LiteralPath $dumpPath -Encoding utf8
    if ($viewerExitCode -ne 0) {
        throw "Bytecode viewer failed for $className (exit $viewerExitCode). See $dumpPath"
    }
    Write-Output "Inspected $className -> $dumpPath"
}
