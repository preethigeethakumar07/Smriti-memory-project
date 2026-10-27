# Builds the dashboard website into app/src/main/assets/dashboard so the phone can serve it.
# Run after any change under dashboard/, then rebuild the app.
$ErrorActionPreference = "Stop"
Push-Location "$PSScriptRoot\..\dashboard"
try {
  if (-not (Test-Path node_modules)) { npm install --no-audit --no-fund }
  npx vitest run
  npm run build
  Write-Host "Dashboard built into app/src/main/assets/dashboard"
} finally {
  Pop-Location
}
