# scripts/

Release-automation helpers for EmuHelper maintainers.

---

## release.ps1

Automates the per-release busywork: build, test, hash, and draft release notes.

**It never commits, pushes, or publishes anything on its own.**

### Requirements

- PowerShell 5+ (Windows, or `pwsh` on macOS/Linux)
- Java in `PATH` (for Gradle)
- `git` in `PATH`
- `gh` CLI installed and authenticated (only needed for the final publish step, not the script itself)

### Usage

Run from the **repo root**:

```powershell
# Build + test using the current version in build.gradle.kts:
.\scripts\release.ps1 -Version 0.2.2

# Also bump versionCode (+1) and versionName in build.gradle.kts before building:
.\scripts\release.ps1 -Version 0.2.2 -Bump
```

### What it does

| Step | Description |
|------|-------------|
| 1    | Validates you are in the repo root |
| 2    | Warns if not on `main` branch |
| 3    | Warns if the working tree is dirty |
| 4    | Reads current `versionCode`/`versionName` from `app/build.gradle.kts`; optionally bumps with `-Bump` |
| 5    | Runs `gradlew clean :app:assembleDebug` — fails hard if build fails |
| 6    | Runs `gradlew :app:testDebugUnitTest` — fails hard if tests fail |
| 7    | Copies `app/build/outputs/apk/debug/app-debug.apk` → `EmuHelper-v<version>.apk` |
| 8    | Computes SHA-256 of the copied APK |
| 9    | Collects commits since the last git tag (`git log <tag>..HEAD --pretty="- %s"`) |
| 10   | Writes `_relnotes_v<version>.md` — a complete release-notes draft with SHA-256 footer |
| 11   | Prints the exact `gh release create` command to run when ready |

### After the script

1. Review and edit `_relnotes_v<version>.md`.
2. Run the printed `gh release create` command to publish.

The script is **idempotent** — running it twice for the same version just overwrites the APK copy and notes file.
