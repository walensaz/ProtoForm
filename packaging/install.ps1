param(
    [string]$Version = $(if ($env:PROTOFORM_VERSION) { $env:PROTOFORM_VERSION } else { "latest" }),
    [string]$Repo = $(if ($env:PROTOFORM_REPO) { $env:PROTOFORM_REPO } else { "walensaz/ProtoForm" }),
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA "ProtoForm")
)

$ErrorActionPreference = "Stop"
$Token = if ($env:GITHUB_TOKEN) { $env:GITHUB_TOKEN } elseif ($env:GH_TOKEN) { $env:GH_TOKEN } else { $null }
$ApiHeaders = @{ "User-Agent" = "ProtoForm-Installer" }
if ($Token) {
    $ApiHeaders["Authorization"] = "Bearer $Token"
}

try {
    if ($Version -eq "latest") {
        $Release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $ApiHeaders
        $Tag = $Release.tag_name
    } else {
        $Tag = if ($Version.StartsWith("v")) { $Version } else { "v$Version" }
        if ($Token) {
            $Release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$Tag" -Headers $ApiHeaders
        }
    }
} catch {
    throw "Failed to query GitHub release for $Repo ($($_.Exception.Message)). Ensure the repository is public and has a published (non-draft) release, or set `$env:GITHUB_TOKEN."
}

if ([string]::IsNullOrWhiteSpace($Tag)) {
    throw "Could not resolve ProtoForm release tag for $Repo."
}

$CleanVersion = $Tag.TrimStart("v")
$AssetName = "ProtoForm-$CleanVersion-windows-x64-portable.zip"
$BaseUrl = "https://github.com/$Repo/releases/download/$Tag"
$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("protoform-install-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

function Get-ReleaseAsset {
    param([string]$FileName, [string]$OutPath)
    if ($Token -and $Release) {
        $Asset = $Release.assets | Where-Object { $_.name -eq $FileName } | Select-Object -First 1
        if ($Asset) {
            $DlHeaders = @{
                "User-Agent"    = "ProtoForm-Installer"
                "Authorization" = "Bearer $Token"
                "Accept"        = "application/octet-stream"
            }
            Invoke-WebRequest -Uri $Asset.url -Headers $DlHeaders -OutFile $OutPath -UseBasicParsing
            return
        }
    }
    Invoke-WebRequest -Uri "$BaseUrl/$FileName" -OutFile $OutPath -UseBasicParsing
}

try {
    $ZipPath = Join-Path $TempDir $AssetName
    Write-Host "Downloading ProtoForm $CleanVersion from $BaseUrl/$AssetName ..." -ForegroundColor Cyan
    Get-ReleaseAsset -FileName $AssetName -OutPath $ZipPath

    try {
        $SumsPath = Join-Path $TempDir "SHA256SUMS.txt"
        Get-ReleaseAsset -FileName "SHA256SUMS.txt" -OutPath $SumsPath
        $ExpectedLine = Get-Content $SumsPath | Where-Object { $_ -match "\s$([regex]::Escape($AssetName))$" } | Select-Object -First 1
        if ($ExpectedLine) {
            $ExpectedHash = ($ExpectedLine -split "\s+")[0].ToLowerInvariant()
            $ActualHash = (Get-FileHash -Path $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($ActualHash -ne $ExpectedHash) {
                throw "SHA-256 checksum mismatch for $AssetName (expected $ExpectedHash, got $ActualHash)."
            }
            Write-Host "Verified SHA-256 checksum." -ForegroundColor Green
        }
    } catch {
        Write-Warning "Skipping checksum verification: $($_.Exception.Message)"
    }

    $ExtractDir = Join-Path $TempDir "extracted"
    Expand-Archive -Path $ZipPath -DestinationPath $ExtractDir -Force
    $SourceAppDir = Join-Path $ExtractDir "ProtoForm"
    if (-not (Test-Path $SourceAppDir)) {
        $SourceAppDir = $ExtractDir
    }

    if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    Copy-Item -Path (Join-Path $SourceAppDir "*") -Destination $InstallDir -Recurse -Force

    $ExePath = Join-Path $InstallDir "ProtoForm.exe"
    $BinDir = Join-Path $InstallDir "bin"
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    "@echo off`r`nstart `"`" `"$ExePath`" %*`r`n" | Set-Content -Path (Join-Path $BinDir "protoform.cmd") -Encoding ASCII

    # Add user-scoped Start Menu shortcut (no admin required)
    $StartMenuDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
    $ShortcutPath = Join-Path $StartMenuDir "ProtoForm.lnk"
    $WshShell = New-Object -ComObject WScript.Shell
    $Shortcut = $WshShell.CreateShortcut($ShortcutPath)
    $Shortcut.TargetPath = $ExePath
    $Shortcut.WorkingDirectory = $InstallDir
    $Shortcut.IconLocation = "$ExePath,0"
    $Shortcut.Description = "ProtoForm Dynamic gRPC Desktop Client"
    $Shortcut.Save()

    # Add bin directory to User PATH if absent
    $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ([string]::IsNullOrEmpty($UserPath)) { $UserPath = "" }
    $PathParts = $UserPath -split ";" | Where-Object { $_ -ne "" }
    if ($PathParts -notcontains $BinDir) {
        $NewPath = ($PathParts + $BinDir) -join ";"
        [Environment]::SetEnvironmentVariable("Path", $NewPath, "User")
    }

    Write-Host "Installed ProtoForm $CleanVersion to $InstallDir" -ForegroundColor Green
    Write-Host "Launch from the Start Menu ('ProtoForm') or run 'protoform' in a new terminal." -ForegroundColor Green
} finally {
    if (Test-Path $TempDir) { Remove-Item -Recurse -Force $TempDir }
}
