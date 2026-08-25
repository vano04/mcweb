# Build and package the local browser image, installing missing dependencies.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 5) {
  throw 'mcweb: Windows PowerShell 5.1 or newer is required'
}
$Installer = Join-Path $PSScriptRoot 'tools\install.ps1'
if (-not (Test-Path -LiteralPath $Installer -PathType Leaf)) {
  throw "mcweb: tools/install.ps1 was not found beside $PSScriptRoot"
}
& $Installer --build @args
exit $LASTEXITCODE
