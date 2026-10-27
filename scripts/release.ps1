# Cuts a numbered version: updates version.properties, commits, tags it, and pushes the branch and the tag to GitHub.
# Every tag is a point you can go back to (see docs/VERSIONING.md).
#
#   scripts\release.ps1 -Version 1.1.0 -Message "Voice interruption and calendar actions"
#   scripts\release.ps1 -Version 1.1.0 -Message "..." -NoPush     # commit and tag locally only
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [Parameter(Mandatory = $true)][string]$Message,
    [switch]$NoPush
)
# git prints harmless warnings (line endings) on stderr; PowerShell must not treat those as failures, so the exit code decides
$ErrorActionPreference = "Continue"
Set-Location (Join-Path $PSScriptRoot "..")
function G { & git @args 2>&1 | ForEach-Object { "$_" } | Write-Host; if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed" } }

$tag = "v$Version"
if (git tag -l $tag) { Write-Error "Tag $tag already exists. Pick the next version number."; exit 1 }

# version.properties drives versionName/versionCode: MAJOR.MINOR in VERSION, a rising BUILD number as versionCode
$props = Get-Content version.properties | Where-Object { $_ -match "=" }
$build = [int](($props | Where-Object { $_ -like "BUILD=*" }) -replace "BUILD=", "") + 1
$parts = $Version.Split(".")
"VERSION=$($parts[0]).$($parts[1])`nBUILD=$build" | Set-Content version.properties -Encoding ascii

G add -A
G commit -m "$Message ($tag)" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
G tag -a $tag -m "$Message"
Write-Host "Committed and tagged $tag (build $build)."
if (-not $NoPush) { G push origin HEAD; G push origin $tag; Write-Host "Pushed $tag." }
