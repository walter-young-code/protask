# Build the Inno Setup wizard installer (ProTaskMCP-Setup.exe)
# Usage: npm run package-setup
# Requires: Inno Setup 6 (winget install JRSoftware.InnoSetup)

$ErrorActionPreference = 'Stop'

$mcpRoot   = Split-Path -Parent $PSScriptRoot
$installer = $PSScriptRoot
$bundle    = Join-Path $mcpRoot 'dist-bundle\protask-mcp.mjs'

# ---- 1. Locate ISCC ----
$isccCandidates = @(
  "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
  'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
  'C:\Program Files\Inno Setup 6\ISCC.exe'
)
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 (ISCC.exe) not found. Install it via: winget install JRSoftware.InnoSetup' }
Write-Host "[1/3] ISCC: $iscc" -ForegroundColor Cyan

# ---- 2. Ensure bundle exists ----
Write-Host "[2/3] Checking bundle ..." -ForegroundColor Cyan
if (-not (Test-Path $bundle)) {
  Push-Location $mcpRoot
  try { npm run bundle | Out-Null } finally { Pop-Location }
}
if (-not (Test-Path $bundle)) { throw "bundle missing: $bundle" }

# ---- 3. Compile ----
Write-Host "[3/3] Compiling setup ..." -ForegroundColor Cyan
& $iscc /Q (Join-Path $installer 'protask-mcp.iss')
if ($LASTEXITCODE -ne 0) { throw "ISCC failed with exit code $LASTEXITCODE" }

$setup = Join-Path $installer 'output\ProTaskMCP-Setup.exe'
$size = [math]::Round((Get-Item $setup).Length / 1KB, 1)
Write-Host ""
Write-Host "Done: $setup ($size KB)" -ForegroundColor Green
Write-Host "Distribute this setup to users; a step-by-step wizard will guide the installation."
