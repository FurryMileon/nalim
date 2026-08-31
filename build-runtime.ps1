param(
    [string]$Output = (Join-Path $PSScriptRoot "nalim-runtime")
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "JAVA_HOME must point to the JDK that will be packaged"
}

$jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
$releaseFile = Join-Path $env:JAVA_HOME "release"
$modulePath = Join-Path $env:JAVA_HOME "jmods"
if (-not (Test-Path -LiteralPath $jlink -PathType Leaf) -or
    -not (Test-Path -LiteralPath $releaseFile -PathType Leaf) -or
    -not (Test-Path -LiteralPath $modulePath -PathType Container)) {
    throw "JAVA_HOME is not a complete JDK: $env:JAVA_HOME"
}

$release = Get-Content -LiteralPath $releaseFile -Raw
$versionMatch = [regex]::Match($release, 'JAVA_VERSION="([0-9]+)')
if (-not $versionMatch.Success) {
    throw "Could not read JAVA_VERSION from $releaseFile"
}
$feature = [int]$versionMatch.Groups[1].Value
if ($feature -lt 21) {
    throw "The Nalim runtime image requires JDK 21 or newer"
}
if (Test-Path -LiteralPath $Output) {
    throw "Output already exists: $Output. Remove it explicitly before rebuilding."
}

if ($feature -lt 25) {
    $launchOptions = "-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"
} else {
    $launchOptions = "--add-modules=jdk.internal.vm.ci --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED"
}
if ($feature -ge 26) {
    $launchOptions += " -XX:-UseCodeCacheFlushing"
}

$jlinkArgs = @(
    "--module-path", $modulePath,
    "--add-modules", "ALL-MODULE-PATH",
    "--add-options=$launchOptions",
    "--compress=zip-6",
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--output", $Output
)

& $jlink @jlinkArgs
if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

$created = (Resolve-Path -LiteralPath $Output).Path
Write-Host "Created Nalim JDK $feature runtime: $created"
Write-Host "Baked JVM options: $launchOptions"
