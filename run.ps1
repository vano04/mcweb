# The single Windows PowerShell entrypoint for the standard local
# build-and-run flow. Use a process-only execution-policy bypass when needed:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 5) {
  throw 'mcweb: Windows PowerShell 5.1 or newer is required'
}
$Installer = Join-Path $PSScriptRoot 'tools\install.ps1'
if (-not (Test-Path -LiteralPath $Installer -PathType Leaf)) {
  throw "mcweb: tools/install.ps1 was not found beside $PSScriptRoot"
}
& $Installer --run @args
exit $LASTEXITCODE
