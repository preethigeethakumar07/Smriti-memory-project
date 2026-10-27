# Builds a shareable APK with a new version number and copies it to dist\.
#   scripts\package-apk.ps1                 -> bumps BUILD (0.3.4, 0.3.5, ...) and writes dist\Smriti-v0.3.4.apk
#   scripts\package-apk.ps1 -Minor 4        -> starts a new line (VERSION=0.4) and resets nothing else
#   scripts\package-apk.ps1 -NoBump         -> rebuild the current number
param([string]$Minor = "", [switch]$NoBump, [string]$Note = "")
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
Push-Location $root
try {
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  $props = @{}
  Get-Content version.properties | ForEach-Object { if ($_ -match '^(\w+)=(.*)$') { $props[$Matches[1]] = $Matches[2] } }
  if ($Minor) { $props["VERSION"] = "0.$Minor" }
  if (-not $NoBump) { $props["BUILD"] = [string]([int]$props["BUILD"] + 1) }
  "VERSION=$($props['VERSION'])`nBUILD=$($props['BUILD'])" | Set-Content version.properties -Encoding ascii
  $name = "$($props['VERSION']).$($props['BUILD'])"

  # the in-process Kotlin compiler avoids the daemon crashes seen with the LiteRT-LM dependency
  .\gradlew.bat testDebugUnitTest assembleRelease --offline -q "-Pkotlin.compiler.execution.strategy=in-process"
  if ($LASTEXITCODE -ne 0) { throw "build failed" }

  New-Item -ItemType Directory -Force dist | Out-Null
  $src = Get-ChildItem app\build\outputs\apk\release\*.apk | Select-Object -First 1
  # name carries the version AND when it was built, so two builds of the same version never overwrite each other
  $now = Get-Date
  $dst = "dist\Smriti-v$name-$($now.ToString('yyyyMMdd-HHmm')).apk"
  Copy-Item $src.FullName $dst -Force
  $stamp = $now.ToString("yyyy-MM-dd HH:mm")
  $commit = (git rev-parse --short HEAD 2>$null)
  Add-Content dist\CHANGELOG.txt "v$name  $stamp  $(Split-Path $dst -Leaf)  commit $commit  $Note"
  $mb = [math]::Round((Get-Item $dst).Length / 1MB, 1)
  Write-Host "Built $dst ($mb MB), versionCode $($props['BUILD'])"
} finally {
  Pop-Location
}
