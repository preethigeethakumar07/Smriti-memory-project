# Versions and rolling back

Every release is a git **tag** (`v1.0.0`, `v1.1.0`, ...). A tag never moves, so you can always get back to exactly what was shipped.

## Making a new version

```powershell
scripts\release.ps1 -Version 1.1.0 -Message "what changed"
```

It bumps `version.properties`, commits everything, creates the annotated tag and pushes both to GitHub.
Use `-NoPush` to keep it local. Pick the number like this: fix = `1.0.1`, new feature = `1.1.0`, big change = `2.0.0`.

To also share an APK: `scripts\package-apk.ps1` (it writes `dist/Smriti-v<version>.apk`; APKs are too big for git, so attach
them to the GitHub release for that tag instead).

## Seeing the versions

```powershell
git tag -l -n1            # every version with its one-line message
git log --oneline --decorate
```

## Going back

Look at an old version without changing anything (safe):

```powershell
git switch --detach v1.0.0      # you are now exactly at that version; build and test it
git switch master               # come back
```

Keep working from an old version on its own branch (safe, nothing is lost):

```powershell
git switch -c rollback-from-1.0.0 v1.0.0
```

Undo recent versions but keep their history (recommended on `master`):

```powershell
git revert --no-commit v1.0.0..HEAD
git commit -m "Roll back to v1.0.0"
```

Throw away everything after a version (destructive; only on your own copy, and it needs a force push to publish):

```powershell
git reset --hard v1.0.0
```

## What is not in git

- **Models** (`*.litertlm`): they live on the phone. Download them in Google AI Edge Gallery, then run
  `scripts\install-gallery-model.ps1` to copy them into Smriti.
- **Qualcomm NPU libraries** (`app/src/main/jniLibs`): not ours to redistribute. `scripts\fetch-qnn-libs.ps1` copies them from
  the Gallery app on your phone. Without them the app still builds and runs; only the NPU embedding model is unavailable.
- **APKs**: attach to GitHub releases.
