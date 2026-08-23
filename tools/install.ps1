# Bootstrap the local MC-Web toolchain on Windows PowerShell 5.1+.
#
# Run from a cloned checkout:
#   .\tools\install.ps1 --build
#   .\tools\install.ps1 --mc-dir "$env:APPDATA\.minecraft" --build
# Developer tools and the verified CDN cache are installed under
# %USERPROFILE%\.mcweb (or MCWEB_HOME). Accounts and generated image bytes are
# never downloaded.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 5) {
  Write-Host 'mcweb: Windows PowerShell 5.1 or newer is required' -ForegroundColor Red
  exit 1
}

$NodeVersion = 'v24.19.0'
if ($env:MCWEB_HOME) { $McwebHome = $env:MCWEB_HOME } else { $McwebHome = Join-Path $env:USERPROFILE '.mcweb' }

function Say($Message) { Write-Host "mcweb: $Message" }

$DownloadAttempts = 4
$RetryDelaySeconds = 1
$ConnectTimeoutSeconds = 15
$RequestTimeoutSeconds = 120
$MaxNodeBytes = 536870912
$MaxTextBytes = 1048576
$MaxRedirects = 3

$ProcessArchitecture = ([string]$env:PROCESSOR_ARCHITECTURE).ToUpperInvariant()
$HostArchitecture = ([string]$env:PROCESSOR_ARCHITEW6432).ToUpperInvariant()
if (-not $HostArchitecture) { $HostArchitecture = $ProcessArchitecture }
switch ($ProcessArchitecture) {
  'AMD64' { $NodePlat = 'win-x64' }
  'ARM64' { $NodePlat = 'win-arm64' }
  'X86' {
    switch ($HostArchitecture) {
      'AMD64' { $NodePlat = 'win-x64' }
      'ARM64' { $NodePlat = 'win-arm64' }
      default { Write-Host "mcweb: unsupported Windows host architecture: $HostArchitecture" -ForegroundColor Red; exit 1 }
    }
  }
  'ARM' {
    if ($HostArchitecture -eq 'ARM64') { $NodePlat = 'win-arm64' }
    else { Write-Host "mcweb: unsupported Windows host architecture: $HostArchitecture" -ForegroundColor Red; exit 1 }
  }
  default { Write-Host "mcweb: unsupported process architecture: $ProcessArchitecture" -ForegroundColor Red; exit 1 }
}

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { Write-Host 'mcweb: curl.exe is required (included with supported Windows)' -ForegroundColor Red; exit 1 }
if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) { Write-Host 'mcweb: tar.exe is required (included with supported Windows)' -ForegroundColor Red; exit 1 }

$ArchiveName = "node-$NodeVersion-$NodePlat.zip"
$ArchiveUrl = "https://nodejs.org/dist/$NodeVersion/$ArchiveName"
$ChecksumUrl = "https://nodejs.org/dist/$NodeVersion/SHASUMS256.txt"
$TempFiles = [System.Collections.Generic.List[string]]::new()

function Cleanup-Temps {
  foreach ($path in $TempFiles) {
    if ($path) { Remove-Item -LiteralPath $path -Force -Recurse -ErrorAction SilentlyContinue }
  }
  $TempFiles.Clear()
}

function Die($Message) {
  Cleanup-Temps
  Write-Host "mcweb: $Message" -ForegroundColor Red
  exit 1
}

# PowerShell does not have POSIX EXIT traps.  Catch terminating failures from
# file creation, extraction, and atomic moves so every tracked partial is
# removed before the wrapper exits; explicit Die calls remain the normal path.
trap {
  Cleanup-Temps
  Write-Host "mcweb: $($_.Exception.Message)" -ForegroundColor Red
  exit 1
}

$WrapperArgs = @($args)
function Has-Arg([string]$Wanted) {
  return $WrapperArgs -contains $Wanted
}

if ((Has-Arg '--dry-run') -and ((Has-Arg '--build') -or (Has-Arg '--run') -or
    (Has-Arg '--verify') -or (Has-Arg '--download') -or (Has-Arg '--download-only'))) {
  Die '--dry-run cannot be combined with --build, --run, --verify, --download, or --download-only'
}

function Has-McDir {
  foreach ($argument in $WrapperArgs) {
    if ($argument -eq '--mc-dir' -or $argument.StartsWith('--mc-dir=')) { return $true }
  }
  return $false
}
if ((Has-Arg '--download') -or (Has-Arg '--download-only')) {
  if ((Has-McDir) -or (Has-Arg '--local-only')) {
    Die '--download and --download-only cannot be combined with --mc-dir or --local-only'
  }
}

function New-NodeTemp {
  $path = Join-Path $McwebHome ".mcweb-node-$([guid]::NewGuid().ToString('N')).tmp"
  New-Item -ItemType File -Path $path -Force | Out-Null
  $TempFiles.Add($path)
  return $path
}

function Assert-NodeUrl([string]$Raw, [ValidateSet('archive', 'checksum')][string]$Kind) {
  $expected = if ($Kind -eq 'archive') { $ArchiveUrl } else { $ChecksumUrl }
  $uri = $null
  if (-not [Uri]::TryCreate($Raw, [UriKind]::Absolute, [ref]$uri)) {
    Die "Node $Kind URL is not valid: $Raw"
  }
  if ($uri.Scheme -ne 'https' -or $uri.Host.ToLowerInvariant() -ne 'nodejs.org' -or
      ($uri.Port -ne -1 -and $uri.Port -ne 443) -or $uri.UserInfo -or
      $uri.Query -or $uri.Fragment -or $uri.AbsoluteUri -ne $expected) {
    Die "Node $Kind URL is outside the pinned vendor path: $Raw"
  }
  return $uri.AbsoluteUri
}

function Resolve-NodeLocation([string]$Current, [string]$Location, [string]$Kind) {
  if (-not $Location) { Die "Node $Kind redirect has no Location header" }
  $next = [Uri]::new(([Uri]$Current), $Location).AbsoluteUri
  return Assert-NodeUrl $next $Kind
}

function Get-HeaderStatus([string]$Path) {
  $status = 0
  foreach ($line in @(Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue)) {
    if ($line -match '^HTTP/\S+\s+(\d{3})') { $status = [int]$Matches[1] }
  }
  return $status
}

function Get-HeaderLocation([string]$Path) {
  $location = ''
  foreach ($line in @(Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue)) {
    if ($line -match '^(?i:Location):\s*(.+?)\s*$') { $location = $Matches[1].Trim() }
  }
  return $location
}

function Is-TransientStatus([int]$Status) {
  return $Status -eq 408 -or $Status -eq 429 -or ($Status -ge 500 -and $Status -le 599)
}

function Get-NodeHeaders([string]$Url, [string]$HeaderPath, [string]$ErrorPath) {
  for ($attempt = 1; $attempt -le $DownloadAttempts; $attempt++) {
    [System.IO.File]::WriteAllText($HeaderPath, '')
    [System.IO.File]::WriteAllText($ErrorPath, '')
    & curl.exe -fsS --max-redirs 0 --connect-timeout $ConnectTimeoutSeconds `
      --max-time $RequestTimeoutSeconds -D $HeaderPath -o NUL $Url 2> $ErrorPath
    $exitCode = $LASTEXITCODE
    $status = Get-HeaderStatus $HeaderPath
    if ($exitCode -eq 0 -and (($status -ge 200 -and $status -le 299) -or ($status -ge 300 -and $status -le 399))) {
      return $status
    }
    if ($status -gt 0 -and -not (Is-TransientStatus $status)) { return 0 }
    if ($attempt -lt $DownloadAttempts) { Start-Sleep -Seconds $RetryDelaySeconds }
  }
  return 0
}

function Resolve-NodeUrl([string]$Initial, [string]$Kind, [string]$HeaderPath, [string]$ErrorPath) {
  $current = Assert-NodeUrl $Initial $Kind
  for ($hop = 0; $hop -le $MaxRedirects; $hop++) {
    $status = Get-NodeHeaders $current $HeaderPath $ErrorPath
    if ($status -ge 200 -and $status -le 299) { return $current }
    if ($status -lt 300 -or $status -gt 399 -or $hop -ge $MaxRedirects) { return $null }
    $current = Resolve-NodeLocation $current (Get-HeaderLocation $HeaderPath) $Kind
  }
  return $null
}

function Download-NodeFile([string]$Initial, [string]$Kind, [string]$Target, [long]$MaxBytes, [string]$ExpectedSha256,
  [string]$HeaderPath, [string]$ErrorPath) {
  $current = Resolve-NodeUrl $Initial $Kind $HeaderPath $ErrorPath
  if (-not $current) { return 1 }
  $redirects = 0
  for ($attempt = 1; $attempt -le $DownloadAttempts; $attempt++) {
    [System.IO.File]::WriteAllBytes($Target, [byte[]]@())
    [System.IO.File]::WriteAllText($HeaderPath, '')
    [System.IO.File]::WriteAllText($ErrorPath, '')
    & curl.exe -fsS --max-redirs 0 --connect-timeout $ConnectTimeoutSeconds `
      --max-time $RequestTimeoutSeconds --max-filesize $MaxBytes `
      -D $HeaderPath -o $Target $current 2> $ErrorPath
    $exitCode = $LASTEXITCODE
    $status = Get-HeaderStatus $HeaderPath
    if ($exitCode -eq 0 -and $status -ge 300 -and $status -le 399) {
      if ($redirects -ge $MaxRedirects) { return 1 }
      $current = Resolve-NodeLocation $current (Get-HeaderLocation $HeaderPath) $Kind
      $redirects++
      continue
    }
    if ($exitCode -eq 0 -and $status -ge 200 -and $status -le 299) {
      $bytes = (Get-Item -LiteralPath $Target).Length
      if ($bytes -gt $MaxBytes) { return 2 }
      if ($ExpectedSha256) {
        $got = (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($got -ne $ExpectedSha256.ToLowerInvariant()) {
          if ($attempt -lt $DownloadAttempts) { Start-Sleep -Seconds $RetryDelaySeconds; continue }
          return 1
        }
      }
      return 0
    }
    if ($exitCode -eq 63) { return 2 }
    if ($status -gt 0 -and -not (Is-TransientStatus $status)) { return 1 }
    if ($attempt -lt $DownloadAttempts) { Start-Sleep -Seconds $RetryDelaySeconds }
  }
  return 1
}

function Commit-Atomic([string]$Source, [string]$Destination) {
  try {
    if (Test-Path -LiteralPath $Destination) {
      [System.IO.File]::Replace($Source, $Destination, $null, $true)
    } else {
      [System.IO.File]::Move($Source, $Destination)
    }
  } catch [System.IO.IOException] {
    if (Test-Path -LiteralPath $Destination) {
      [System.IO.File]::Replace($Source, $Destination, $null, $true)
    } else {
      throw
    }
  }
}

$Node = ''
if (-not $env:MCWEB_FORCE_DOWNLOAD) {
  $systemNode = Get-Command node -ErrorAction SilentlyContinue
  if ($systemNode) {
    $version = & node -v 2>$null
    if ($version -match '^v(\d+)\.' -and [int]$Matches[1] -ge 20) {
      $Node = $systemNode.Source
      Say "using system node $version"
    }
  }
}

$NodeDir = Join-Path $McwebHome 'node'
$NodeExe = Join-Path $NodeDir 'node.exe'
if (-not $Node -and -not (Test-Path $NodeExe) -and (Has-Arg '--dry-run')) {
  Say 'dry-run: no downloads or writes'
  Say "would download Node $NodeVersion from $ArchiveUrl"
  Say "would verify $ChecksumUrl"
  Cleanup-Temps
  exit 0
}
if (-not $Node -and -not (Test-Path $NodeExe)) {
  New-Item -ItemType Directory -Force -Path $McwebHome | Out-Null
  $Archive = Join-Path $McwebHome $ArchiveName
  $Header = New-NodeTemp
  $ErrorFile = New-NodeTemp
  $Sums = New-NodeTemp
  $sumResult = Download-NodeFile $ChecksumUrl 'checksum' $Sums $MaxTextBytes '' $Header $ErrorFile
  if ($sumResult -eq 2) { Die "Node checksum list exceeds the $MaxTextBytes-byte cap" }
  if ($sumResult -ne 0) { Die 'could not download the pinned Node checksum list' }
  $Want = ''
  foreach ($line in @(Get-Content -LiteralPath $Sums)) {
    $fields = $line -split '\s+'
    if ($fields.Length -ge 2 -and $fields[-1] -eq $ArchiveName) { $Want = $fields[0] }
  }
  if ($Want -notmatch '^[0-9A-Fa-f]{64}$') { Die "Node did not publish a valid checksum for $ArchiveName" }
  $reusable = $false
  if (Test-Path -LiteralPath $Archive) {
    $archiveItem = Get-Item -LiteralPath $Archive
    if ($archiveItem.Length -le $MaxNodeBytes -and
        (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -eq $Want.ToLowerInvariant()) {
      $reusable = $true
      Say "reusing verified Node archive $Archive"
    }
  }
  if (-not $reusable) {
    Say "downloading Node $NodeVersion ($NodePlat)"
    $NodeTemp = New-NodeTemp
    $downloadResult = Download-NodeFile $ArchiveUrl 'archive' $NodeTemp $MaxNodeBytes $Want $Header $ErrorFile
    if ($downloadResult -eq 2) { Die "Node archive exceeds the $MaxNodeBytes-byte cap" }
    if ($downloadResult -ne 0) { Die "could not download and verify $ArchiveName" }
    Commit-Atomic $NodeTemp $Archive
  }
  $ExtractDir = Join-Path $McwebHome ".mcweb-node-extract-$([guid]::NewGuid().ToString('N'))"
  New-Item -ItemType Directory -Path $ExtractDir | Out-Null
  $TempFiles.Add($ExtractDir)
  & tar.exe -xf "$Archive" -C "$ExtractDir" --strip-components=1
  if ($LASTEXITCODE -ne 0) { Die "could not extract $ArchiveName" }
  if (-not (Test-Path (Join-Path $ExtractDir 'node.exe'))) { Die 'Node archive did not contain node.exe' }
  if (Test-Path -LiteralPath $NodeDir) { Remove-Item -LiteralPath $NodeDir -Force -Recurse }
  [System.IO.Directory]::Move($ExtractDir, $NodeDir)
  $null = $TempFiles.Remove($ExtractDir)
  Remove-Item -LiteralPath $Archive -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $Sums -Force -ErrorAction SilentlyContinue
}
if (-not $Node) { $Node = $NodeExe }
if (-not (Test-Path $Node)) { Die "Node is not executable at $Node" }
Say "node $(& $Node -v)"

$Installer = Join-Path $PSScriptRoot 'mcweb-install.mjs'
if (-not (Test-Path $Installer)) {
  Die 'run this script from the cloned public/mcweb checkout; tools/mcweb-install.mjs was not found'
}
Cleanup-Temps
& $Node $Installer @args
$exitCode = $LASTEXITCODE
Cleanup-Temps
exit $exitCode
