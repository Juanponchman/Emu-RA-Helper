# Contributing to EmuHelper

Thanks for taking the time to contribute. EmuHelper is in early alpha, and outside feedback is genuinely valuable — bugs you hit, rough edges that annoy you, and features you wish existed all help shape where the project goes.

---

## Reporting bugs

Please open a [GitHub issue](https://github.com/mayusi/EmuHelper/issues) using the **Bug report** template. To make it actionable, include:

- **Android version** and device model
- **App version** (visible on the home screen)
- **Reproduction steps** — the exact sequence that triggers the problem
- **Expected vs. actual behavior**
- **Logcat output** if you can capture it (filter on `EmuHelper` or the package name)

The more specific the report, the faster it gets fixed.

---

## Suggesting features

Open an issue using the **Feature request** template. Describe the problem you're trying to solve, your proposed solution, and any alternatives you considered. No proposal is too small.

---

## Pull requests

Small, focused pull requests are easiest to review. A PR that fixes one bug or adds one well-scoped feature is much more likely to be merged than a sweeping change.

### Steps

1. Fork the repository and create a branch from `main`.
2. Make your changes.
3. Verify the project compiles cleanly:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. Open a PR against `main` with a clear description of what changed and why.

### Important rule — no endpoint configuration or content lists

The catalog file (`Catalog.kt`) is **operator-supplied and git-ignored by design**. This is intentional: the project ships with no bundled endpoints, and none should ever appear in version control.

**PRs must not:**
- Add, reference, or hard-code any source URLs or endpoint addresses
- Commit `Catalog.kt` or any derivative of it
- Include content catalogs, metadata files, or lists of resources to download

This rule is non-negotiable. PRs that include any of the above will be closed.

---

## Code style

Follow the conventions already present in the codebase:

- Kotlin idioms over Java-style patterns
- Jetpack Compose for all UI
- Hilt for dependency injection — don't bypass it with manual construction
- DataStore for persistent settings; `EncryptedSharedPreferences` for anything sensitive
- Keep composables small and focused; lift state to the appropriate ViewModel

If in doubt, look at the surrounding code and match it.

---

## Questions

Not sure whether something is worth a PR? Open an issue first and ask. Better to discuss it early than to put in work that doesn't fit the project direction.
