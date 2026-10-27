# End-to-end smoke test on a connected phone. Exits non-zero if anything is wrong.
#   1. unit tests (app + dashboard)          2. install + launch
#   3. dashboard served over USB             4. protocol check (hello, live events, sessions, commands)
#   5. fault injection: break a stage, prove the app stays up, re-arm and recover
param([string]$Device = "")
# native tools (npx, gradle, adb) print progress to stderr; success is judged by exit codes and log checks below
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Resolve-Path "$PSScriptRoot\.."
$a = @(); if ($Device) { $a = @("-s", $Device) }
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
function Fail($m) { Write-Host "FAIL: $m" -ForegroundColor Red; exit 1 }
function Cmd($c) { & $adb @a shell "am broadcast -a com.smriti.app.DEBUG -p com.smriti.app $c" | Out-Null; Start-Sleep -Milliseconds 1800 }

Write-Host "== unit tests"
& "$root\gradlew.bat" -p $root --console=plain testDebugUnitTest assembleDebug; if ($LASTEXITCODE) { Fail "gradle" }
Push-Location "$root\dashboard"; npx vitest run; if ($LASTEXITCODE) { Pop-Location; Fail "dashboard tests" }; Pop-Location

Write-Host "== install and launch"
& $adb @a install -r "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb @a shell pm grant com.smriti.app android.permission.CAMERA
& $adb @a shell pm grant com.smriti.app android.permission.RECORD_AUDIO
& $adb @a logcat -c
& $adb @a shell am start -n com.smriti.app/.ui.MainActivity | Out-Null
Start-Sleep 12
if (-not (& $adb @a shell pidof com.smriti.app)) { Fail "app is not running" }

Write-Host "== dashboard over USB"
Cmd "--es cmd dashboard --es on true"
& $adb @a forward tcp:8765 tcp:8765 | Out-Null
$r = Invoke-WebRequest -Uri http://localhost:8765/ -UseBasicParsing
if ($r.StatusCode -ne 200) { Fail "site not served" }
Push-Location "$root\dashboard"; node scripts/ws-check.mjs ws://localhost:8765/ws 9; if ($LASTEXITCODE) { Pop-Location; Fail "protocol check" }; Pop-Location

Write-Host "== fault injection"
Cmd "--es cmd fault --es stage label --es on true"
1..4 | ForEach-Object { Cmd "--es cmd describe" }
$log = & $adb @a logcat -d -s SmritiBus:D
if (-not ($log | Select-String "label DISABLED.*tripped after 3")) { Fail "stage did not trip" }
if (-not (& $adb @a shell pidof com.smriti.app)) { Fail "app died during fault injection" }
Cmd "--es cmd clearFaults"
Cmd "--es cmd rearm --es stage label"
Cmd "--es cmd describe"
$log = & $adb @a logcat -d -s SmritiBus:D
if (-not ($log | Select-String "label OK.*label\(s\)")) { Fail "stage did not recover after re-arm" }
Write-Host "SMOKE OK" -ForegroundColor Green
