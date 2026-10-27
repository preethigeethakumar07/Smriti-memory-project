# Copies Qualcomm's Hexagon NPU runtime libraries out of the Google AI Edge Gallery app on a connected phone into
# app/src/main/jniLibs/arm64-v8a, so the EmbeddingGemma NPU model can run. They are not committed to git (Qualcomm's licence).
# Needs: USB debugging on, AI Edge Gallery installed (with the "EmbeddingGemma-2 NPU" model downloaded).
param([string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", [string]$Serial = "")

$a = @(); if ($Serial) { $a = @("-s", $Serial) }
$tmp = Join-Path $env:TEMP "gallery-npu"; New-Item -ItemType Directory -Force $tmp | Out-Null
$paths = (& $Adb @a shell pm path com.google.ai.edge.gallery) -replace "package:", "" | Where-Object { $_ -match "qualcomm_npu_runtime.*arm64" }
if (-not $paths) { Write-Error "Gallery's Qualcomm NPU split was not found. Install AI Edge Gallery and download an NPU model first."; exit 1 }
$apk = Join-Path $tmp "npu.apk"
& $Adb @a pull ($paths | Select-Object -First 1).Trim() $apk | Out-Null

$dst = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\arm64-v8a"; New-Item -ItemType Directory -Force $dst | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
foreach ($n in "libQnnHtp.so", "libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so", "libQnnSystem.so") {
    $e = $zip.GetEntry("lib/arm64-v8a/$n")
    if ($e) { [IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $dst $n), $true); Write-Host "copied $n" } else { Write-Warning "$n not in the Gallery split" }
}
$zip.Dispose()
