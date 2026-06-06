# EmuHelper

**EmuHelper is an Android app that helps you get your favourite emulation games onto your device — quickly and in one place.**

Browse retro libraries by console, build reusable download lists, and pull your games down with a fast, multi-connection download manager that sorts everything into clean per‑console folders ready for your emulators.

> **Status: Alpha (v0.1.0-alpha).** This is the first public release. Expect rough edges and please report issues.

---

## Features

- **Two ways to get games** — build a saved **list** to download later, or **instant install** to grab games right now.
- **Browse by console** — PS1, PS2, PSP, Vita, N64, GameCube, Wii / Wii U, 3DS, DS, SNES, GBA, GB, Genesis, Dreamcast, Saturn, Arcade, BIOS and more.
- **Powerful picking** — search, region filters (USA / EUR / JPN), size filters, sorting, and multi‑select across consoles.
- **Saved lists** — name, reuse, and **export / import** lists as `.json` files to share or back up.
- **Fast download manager**
  - Multi‑connection (segmented) downloads with mirror fail‑over.
  - Live per‑file progress, speed and ETA.
  - Runs in the **background** via a foreground service — leave the app and downloads keep going (force‑closing stops them).
  - **Auto‑extracts `.zip`** archives and files everything into per‑console folders (e.g. `ROMs/SNES/`).
- **Settings** — tune connection count, a "max throughput" mode, a built‑in **network speed test**, and device info so you can tell whether a slow download is your Wi‑Fi or the app.
- **Stays signed in** — your login is stored **encrypted on‑device** and restored automatically, so you don't sign in every time.
- **Modern UI** — Jetpack Compose, Material 3, light/dark themes, smooth transitions.

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose · Material 3
- **DI:** Hilt (Dagger)
- **Navigation:** Navigation‑Compose
- **Networking:** OkHttp
- **Storage:** DataStore Preferences · EncryptedSharedPreferences (security‑crypto) · Storage Access Framework (SAF)
- **Serialization:** kotlinx.serialization
- **Min SDK:** 29 · **Target/Compile SDK:** 35 · **Java:** 17

---

## Building from source

This repository **does not include the source URL list** (see *Sources* below). To build, create your own `Catalog.kt` from the committed template:

```bash
# 1. Clone
git clone https://github.com/mayusi/EmuHelper.git
cd EmuHelper

# 2. Create the source list from the template
cp app/src/main/java/io/github/mayusi/emuhelper/data/config/Catalog.kt.template \
   app/src/main/java/io/github/mayusi/emuhelper/data/config/Catalog.kt

# 3. Open Catalog.kt and fill in the IA_LINKS map with the source URLs you
#    want the app to scan (it compiles and runs with empty lists too).

# 4. Build
./gradlew :app:assembleDebug
```

The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

> Prefer not to build? Grab the prebuilt APK from the [Releases](https://github.com/mayusi/EmuHelper/releases) page.

---

## Sources

`Catalog.kt` (the file holding the collection URLs the app scans) is intentionally **kept out of this repository** and is git‑ignored. The committed `Catalog.kt.template` provides the full structure with empty lists so the project still compiles. Bring your own sources.

---

## Legal & disclaimer

EmuHelper is a **download manager / front‑end**. It hosts no content and ships with no source URLs. You are responsible for what you download and for complying with the laws of your country and the terms of any service you use. Only download games you legally own. The authors are not responsible for misuse.

---

## License

Released under the **MIT License** — see [LICENSE](LICENSE).

---

## Credits

Built by **mayusi**.

Development was assisted by **Anthropic's Claude** (used as an AI coding assistant for help with implementation, debugging, and documentation).
