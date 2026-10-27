# Copies the models that Google AI Edge Gallery already downloaded onto the phone for Smriti:
#   EmbeddingGemma-2 (search by meaning, image/audio/text vectors), Gemma-4 E2B (answers, sees images, hears speech), MobileActions-270M (phone actions).
# Apps cannot read each other's Android/data folder, but the adb shell user can.
#
#   scripts\install-gallery-model.ps1                 debug build of Smriti: copies straight into the app (run-as writes as the app)
#   scripts\install-gallery-model.ps1 -ToDownloads    ANY build (release too): copies to Download/Smriti-models, then in Smriti open
#                                                     Settings > AI models > Import and pick each file
param([string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", [string]$Serial = "", [switch]$ToDownloads)

$a = @(); if ($Serial) { $a = @("-s", $Serial) }
$gallery = "/sdcard/Android/data/com.google.ai.edge.gallery/files"
$wanted = @("embeddinggemma-2-740m_*SM8850*.litertlm", "gemma-4-E2B-it.litertlm", "mobile_actions.litertlm")
$dl = "/sdcard/Download/Smriti-models"

if ($ToDownloads) { & $Adb @a shell "mkdir -p $dl" } else { & $Adb @a shell "run-as com.smriti.app mkdir -p files/models" }

foreach ($pattern in $wanted) {
    $src = (& $Adb @a shell "find $gallery -name '$pattern' 2>/dev/null") | Select-Object -First 1
    if (-not $src) { Write-Warning "Not found in Gallery: $pattern. Download it in AI Edge Gallery first."; continue }
    $src = $src.Trim(); $name = Split-Path $src -Leaf
    $want = (& $Adb @a shell "stat -c %s '$src'").Trim()
    if ($ToDownloads) {
        $have = (& $Adb @a shell "stat -c %s '$dl/$name' 2>/dev/null")
        if ($have -and $have.Trim() -eq $want) { Write-Host "Already in Downloads: $name"; continue }
        Write-Host "Copying $name ($([math]::Round([long]$want / 1MB)) MB) to $dl ..."
        & $Adb @a shell "cp '$src' '$dl/$name'"
    } else {
        $have = (& $Adb @a shell "run-as com.smriti.app stat -c %s files/models/$name 2>/dev/null")
        if ($have -and $have.Trim() -eq $want) { Write-Host "Already installed: $name"; continue }
        Write-Host "Copying $name ($([math]::Round([long]$want / 1MB)) MB)..."
        & $Adb @a shell "cat '$src' | run-as com.smriti.app sh -c 'cat > files/models/$name'"
    }
}
if ($ToDownloads) { & $Adb @a shell "ls -la $dl"; Write-Host "Now open Smriti > Settings > AI models and import each file." }
else { & $Adb @a shell "run-as com.smriti.app ls -la files/models" }
Write-Host "Done."
