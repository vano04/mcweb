# Install the source-only MC-Web distribution without git or administrator
# access. Run with a process-only policy bypass on Windows PowerShell:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install.ps1
[CmdletBinding()]
param(
  [switch]$DryRun,
  [switch]$Help,
  [string]$InstallDir = ""
)

$ErrorActionPreference = 'Stop'
$Repository = 'vano04/mcweb'
$Ref = 'main'
$ArchiveUrl = "https://github.com/$Repository/archive/refs/heads/$Ref.zip"
$MaxArchiveBytes = 134217728
if (-not $InstallDir) {
  if ($env:MCWEB_INSTALL_DIR) { $InstallDir = $env:MCWEB_INSTALL_DIR }
  else { $InstallDir = Join-Path $env:USERPROFILE '.mcweb\project' }
}

function Say([string]$Message) { Write-Host "mcweb: $Message" }
function Die([string]$Message) { throw "mcweb: $Message" }

if ($Help) {
  Write-Host 'Usage: .\install.ps1 [-DryRun] [-InstallDir path]'
  Write-Host 'Set MCWEB_INSTALL_DIR to choose a different destination.'
  exit 0
}

$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)
$ParentDir = Split-Path -Parent $InstallDir
if ($DryRun) {
  Say 'dry-run: no downloads or writes'
  Say "repository: https://github.com/$Repository"
  Say "ref: $Ref"
  Say "archive: $ArchiveUrl"
  Say "destination: $InstallDir"
  Say "after install: Set-Location '$InstallDir'; .\run.ps1"
  exit 0
}

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
  Die 'curl.exe is required (included with supported Windows)'
}
New-Item -ItemType Directory -Path $ParentDir -Force | Out-Null

if (Test-Path -LiteralPath $InstallDir) {
  $MarkerPath = Join-Path $InstallDir '.mcweb-install.json'
  if (-not (Test-Path -LiteralPath $MarkerPath -PathType Leaf)) {
    Die "refusing to overwrite existing directory without ${MarkerPath}: $InstallDir"
  }
  $Marker = Get-Content -LiteralPath $MarkerPath -Raw | ConvertFrom-Json
  if ($Marker.repository -ne $Repository) {
    Die "refusing to overwrite a directory not installed by MC-Web: $InstallDir"
  }
}

$WorkDir = Join-Path $ParentDir ('.mcweb-install-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $WorkDir | Out-Null
$Archive = Join-Path $WorkDir 'mcweb.zip'
$Extracted = Join-Path $WorkDir 'extracted'
New-Item -ItemType Directory -Path $Extracted | Out-Null
$Moved = $false
try {
  Say "downloading $Repository@$Ref"
  $EffectiveUrl = (& curl.exe --fail --silent --show-error --location `
    --max-redirs 3 --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 300 `
    --max-filesize $MaxArchiveBytes -o $Archive -w '%{url_effective}' $ArchiveUrl).Trim()
  if ($LASTEXITCODE -ne 0) { Die 'could not download the GitHub source archive' }
  $AllowedEffectiveUrls = @(
    $ArchiveUrl,
    "https://codeload.github.com/$Repository/zip/refs/heads/$Ref"
  )
  if ($AllowedEffectiveUrls -notcontains $EffectiveUrl) {
    Die "GitHub archive redirected outside the pinned repository: $EffectiveUrl"
  }
  if ((Get-Item -LiteralPath $Archive).Length -gt $MaxArchiveBytes) {
    Die "GitHub archive exceeds the $MaxArchiveBytes-byte cap"
  }

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $Zip = [System.IO.Compression.ZipFile]::OpenRead($Archive)
  try {
    $Entries = @($Zip.Entries)
    if (-not $Entries.Count) { Die 'GitHub archive is empty' }
    foreach ($Entry in $Entries) {
      $Name = $Entry.FullName.Replace('\', '/')
      if ($Name.StartsWith('/') -or $Name -match '(^|/)\.\.(\/|$)') {
        Die "GitHub archive contains an unsafe path: $Name"
      }
    }
    $RootNames = @($Entries | ForEach-Object { ($_.FullName.Replace('\', '/') -split '/')[0] } | Sort-Object -Unique)
    if ($RootNames.Count -ne 1 -or $RootNames[0] -ne "mcweb-$Ref") {
      Die "unexpected GitHub archive root: $($RootNames -join ', ')"
    }
  } finally {
    $Zip.Dispose()
  }

  Expand-Archive -LiteralPath $Archive -DestinationPath $Extracted -Force
  $Stage = Join-Path $Extracted "mcweb-$Ref"
  if (-not (Test-Path -LiteralPath (Join-Path $Stage 'README.md') -PathType Leaf)) {
    Die 'source archive is missing README.md'
  }
  if (-not (Test-Path -LiteralPath (Join-Path $Stage 'tools\mcweb-install.mjs') -PathType Leaf)) {
    Die 'source archive is missing the local installer'
  }
  if (-not (Test-Path -LiteralPath (Join-Path $Stage 'run.ps1') -PathType Leaf)) {
    Die 'source archive is missing run.ps1'
  }
  @{
    schema = 1
    repository = $Repository
    ref = $Ref
    managedBy = 'mcweb/install.ps1'
  } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Stage '.mcweb-install.json') -Encoding UTF8

  $Backup = $null
  if (Test-Path -LiteralPath $InstallDir) {
    $Backup = "$InstallDir.backup.$(Get-Date -Format yyyyMMddHHmmssfff)"
    while (Test-Path -LiteralPath $Backup) { $Backup = "$Backup.$PID" }
    Move-Item -LiteralPath $InstallDir -Destination $Backup
  }
  try {
    Move-Item -LiteralPath $Stage -Destination $InstallDir
    $Moved = $true
  } catch {
    if ($Backup -and (Test-Path -LiteralPath $Backup) -and -not (Test-Path -LiteralPath $InstallDir)) {
      Move-Item -LiteralPath $Backup -Destination $InstallDir
    }
    throw
  }
  Say "installed source checkout at $InstallDir"
  if ($Backup) { Say "previous managed checkout kept at $Backup" }
  Say "next: Set-Location '$InstallDir'; .\run.ps1"
} finally {
  if (-not $Moved -and (Test-Path -LiteralPath $WorkDir)) {
    Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
  } elseif (Test-Path -LiteralPath $WorkDir) {
    Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}
