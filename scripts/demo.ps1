# One command for the judges' demo: install the latest debug build, switch the dashboard on, forward the port
# over USB and open the website. Works with no network at all (airplane mode is fine).
param(
  [string]$Device = "",          # adb serial; empty = the only connected device
  [switch]$SkipInstall
)
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = Resolve-Path "$PSScriptRoot\.."
$a = @(); if ($Device) { $a = @("-s", $Device) }

if (-not $SkipInstall) {
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  & "$root\gradlew.bat" -p $root --console=plain assembleDebug
  & $adb @a install -r "$root\app\build\outputs\apk\debug\app-debug.apk"
  & $adb @a shell pm grant com.smriti.app android.permission.CAMERA
  & $adb @a shell pm grant com.smriti.app android.permission.RECORD_AUDIO
}
& $adb @a shell am start -n com.smriti.app/.ui.MainActivity | Out-Null
Start-Sleep 10                     # models load and warm up; never cold-start in front of judges
& $adb @a shell "am broadcast -a com.smriti.app.DEBUG -p com.smriti.app --es cmd dashboard --es on true" | Out-Null
Start-Sleep 2
& $adb @a forward tcp:8765 tcp:8765
Start-Process "http://localhost:8765/#flow"
Write-Host "Dashboard: http://localhost:8765  (USB, no network needed)"
