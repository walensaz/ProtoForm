param(
    [ValidateSet("app-image", "msi", "exe", "all")]
    [string]$InstallerType = "app-image",
    [string]$Version = "1.0.0",
    [switch]$SkipBuild,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $RepoRoot

try {
    if (-not $SkipBuild) {
        if ($SkipTests) {
            Write-Host "Building ProtoForm fat JAR (skipping tests)..." -ForegroundColor Cyan
            & sbt assembly
        } else {
            Write-Host "Running tests and building ProtoForm fat JAR..." -ForegroundColor Cyan
            & sbt test assembly
        }
        if ($LASTEXITCODE -ne 0) { throw "sbt build failed with exit code $LASTEXITCODE" }
    }

    $JarPath = Join-Path $RepoRoot "target\scala-3.3.3\protoform-$Version.jar"
    if (-not (Test-Path $JarPath)) {
        $FallbackJar = Get-ChildItem (Join-Path $RepoRoot "target\scala-3.3.3") -Filter "protoform-*.jar" | Select-Object -First 1
        if ($null -eq $FallbackJar) { throw "Could not locate assembled ProtoForm JAR in target\scala-3.3.3" }
        $JarPath = $FallbackJar.FullName
    }

    $StageDir = Join-Path $RepoRoot "packaging\build\input"
    $DistDir  = Join-Path $RepoRoot "dist"
    if (Test-Path $StageDir) { Remove-Item -Recurse -Force $StageDir }
    New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

    $JarName = Split-Path $JarPath -Leaf
    Copy-Item $JarPath (Join-Path $StageDir $JarName)

    $IconPath = Join-Path $RepoRoot "packaging\icons\protoform.ico"
    if (-not (Test-Path $IconPath)) {
        Write-Host "Generating ProtoForm icon assets..." -ForegroundColor Cyan
        & python (Join-Path $RepoRoot "packaging\generate_icons.py")
    }

    # Minimal JRE modules required for JavaFX, Netty gRPC + TLS, Logback XML, AWT Desktop, and Google Auth
    $ReducedModules = @(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.security.jgss",
        "java.security.sasl",
        "java.sql",
        "java.xml",
        "jdk.crypto.ec",
        "jdk.unsupported"
    ) -join ","

    $JlinkOpts = "--strip-native-commands --strip-debug --no-header-files --no-man-pages"

    $CommonArgs = @(
        "--name", "ProtoForm",
        "--app-version", $Version,
        "--vendor", "ProtoForm",
        "--description", "Cross-platform dynamic gRPC desktop client",
        "--input", $StageDir,
        "--main-jar", $JarName,
        "--main-class", "net.protoform.Launcher",
        "--icon", $IconPath,
        "--add-modules", $ReducedModules,
        "--jlink-options", $JlinkOpts,
        "--java-options", "-Xmx1024m",
        "--java-options", "-Dfile.encoding=UTF-8"
    )

    if ($InstallerType -eq "app-image" -or $InstallerType -eq "all") {
        $AppImageOut = Join-Path $DistDir "ProtoForm"
        if (Test-Path $AppImageOut) { Remove-Item -Recurse -Force $AppImageOut }
        Write-Host "Creating reduced-JRE Windows app-image..." -ForegroundColor Cyan
        & jpackage @CommonArgs --type app-image --dest $DistDir
        if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE" }

        $ZipPath = Join-Path $DistDir "ProtoForm-$Version-windows-x64-portable.zip"
        if (Test-Path $ZipPath) { Remove-Item -Force $ZipPath }
        Compress-Archive -Path $AppImageOut -DestinationPath $ZipPath -CompressionLevel Optimal
        Write-Host "Created portable bundle: $ZipPath" -ForegroundColor Green
    }

    if ($InstallerType -in @("msi", "exe", "all")) {
        $HasWix = (Get-Command wix, candle -ErrorAction SilentlyContinue) -ne $null
        if (-not $HasWix) {
            throw "WiX Toolset (wix.exe or candle.exe/light.exe) is required on PATH to build Windows .msi/.exe installers."
        }
        $Types = if ($InstallerType -eq "all") { @("msi", "exe") } else { @($InstallerType) }
        foreach ($T in $Types) {
            Write-Host "Creating reduced-JRE Windows $T installer..." -ForegroundColor Cyan
            & jpackage @CommonArgs `
                --type $T `
                --dest $DistDir `
                --win-dir-chooser `
                --win-menu `
                --win-menu-group "ProtoForm" `
                --win-shortcut `
                --win-shortcut-prompt `
                --win-per-user-install
            if ($LASTEXITCODE -ne 0) { throw "jpackage $T failed with exit code $LASTEXITCODE" }
        }
    }

    Write-Host "Packaging complete. Outputs in: $DistDir" -ForegroundColor Green
} finally {
    Pop-Location
}
