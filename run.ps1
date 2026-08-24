# Serve the built image and integrated auth-aware Minecraft relay on loopback.
# Use a process-only execution-policy bypass when needed:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 5) {
  throw 'mcweb: Windows PowerShell 5.1 or newer is required'
}
$McwebHome = if ($env:MCWEB_HOME) { $env:MCWEB_HOME } else { Join-Path $env:USERPROFILE '.mcweb' }
$NodeCommand = Get-Command node -ErrorAction SilentlyContinue
$Node = $null
if ($NodeCommand) {
  $NodeVersion = & $NodeCommand.Source -v 2>$null
  if ($NodeVersion -match '^v(\d+)\.' -and [int]$Matches[1] -ge 20) {
    $Node = $NodeCommand.Source
  }
}
if (-not $Node) { $Node = Join-Path $McwebHome 'node\node.exe' }
if (-not (Test-Path -LiteralPath $Node -PathType Leaf)) {
  throw 'mcweb: Node 20+ is not installed; run .\install.ps1 first'
}
$Loader = Join-Path $PSScriptRoot 'build\web-graal\graal\minecraft-client.js'
$Wasm = "$Loader.wasm"
if (-not (Test-Path -LiteralPath $Loader -PathType Leaf) -or
    -not (Test-Path -LiteralPath $Wasm -PathType Leaf)) {
  throw 'mcweb: no built image found; run .\build.ps1 first'
}
if (-not $env:MC_WEB_PORT) { $env:MC_WEB_PORT = '4199' }
$env:MCWEB_DISABLE_LOCAL_BUILD = '1'
Push-Location $PSScriptRoot
try {
  & $Node 'tools\dev-server.mjs' @args
  $RunExitCode = $LASTEXITCODE
} finally {
  Pop-Location
}
exit $RunExitCode
