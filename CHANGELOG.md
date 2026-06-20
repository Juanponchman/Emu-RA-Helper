# Changelog

All notable changes to EmuHelper are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project follows a
semantic-style versioning scheme while in alpha.

## [0.8.0] - 2026-06-20

### Added
- **Download several games at full speed at once.** When you download multiple
  files together, the engine now assigns each one to a *different* Internet
  Archive mirror datacenter, so two games download in parallel each at full
  speed instead of fighting over the same mirror and splitting it. The more
  independent mirrors a batch can use, the more it downloads at once.
- **Resume after an interruption — even a crash or reboot.** Downloads now keep
  their progress on disk. If the app is killed, the network drops, or the device
  restarts mid-download, you pick up from where you left off instead of starting
  a multi-GB file over. (A finished file is still checksum-verified end to end,
  so a resumed download can never leave you with a corrupt file.)
- **Safer overnight batches.** Two new safeguards for big, leave-it-running
  downloads: if you've turned on Wi-Fi-only, dropping to mobile data now actually
  pauses the batch (and resumes when Wi-Fi returns); and if the device gets hot,
  the engine automatically eases off the number of connections to let it cool,
  then ramps back up — so a long overnight download won't cook a handheld.

### Changed
- **Public-search safety notice.** The "search all of the Internet Archive"
  feature now shows a one-time notice (and a small always-present reminder) that
  results come from the public Archive, where anyone can upload, so they aren't
  curated or vetted like the built-in collections. EmuHelper still verifies every
  download's checksum for integrity. Download content you recognise and trust.
- **Gentler update reminders.** If a new version is out and you've dismissed the
  prompt but are still on an older build, the app now gives one low-key reminder
  per day (at most) instead of going silent forever — so you don't get stuck on
  an old version, without being nagged.

## [0.7.0] - 2026-06-19

### Added
- **Xbox and Xbox 360 support.** Two big new consoles are now wired up. Xbox 360
  ships with the full Redump alphabetical disc set, Title Updates, the Digital
  collections, plus DLC, XBLIG, XBLA, and a Rock Band DLC pack (66 sources).
  Xbox brings the complete Redump alphabetical disc set (34 sources). Both show
  up in the category picker like every other console.

### Changed
- **Faster downloads, now on by default.** The multi-mirror download engine has
  been rebuilt around how the Internet Archive actually serves files. It opens a
  few *warm*, reused HTTP/2 connections per mirror and spreads the work across
  the Archive's independent mirror datacenters, instead of opening many cold
  connections that each restart from scratch. On-device testing showed it
  matches or beats the old downloader while running cooler on handhelds, so it's
  now the default. You can still switch back to the simpler single-path
  downloader in Settings if you ever need to.

## [0.6.1] - 2026-06-18

### Added
- **On-device library.** A new "On-device library" view (Home menu) walks your
  download folder and shows everything you've already pulled, grouped by
  category folder, with file sizes and a running total. Files that aren't in
  your download history are flagged as "not tracked", and you can **verify** any
  file on demand - it re-hashes the file on disk and checks it against the
  checksum it was downloaded with, so you can confirm a file isn't corrupt
  (it's opt-in per file, to spare your battery on big libraries).
- **Per-list download folders.** Each saved list can now have its own
  destination folder, overriding the global one - so you can keep, say, PS1 on
  internal storage and N64 on the SD card. Set it from the list's menu
  ("Set download folder"), or clear it back to the default. Downloads (and
  retries) from that list land in the folder you pinned.
- **Favourite consoles.** Star the categories you use most and they float to the
  top of the category picker, so you're not scrolling past everything you don't
  use every time. Independent of the "remember last selection" tick.

## [0.6.0] - 2026-06-17

### Added
- **Search all of the Internet Archive.** A new search lets you find any public
  item across the whole archive.org - not just the configured collections - and
  download it through the normal flow. (Home menu -> Search the Internet Archive.)
- **Add from a link.** Paste any archive.org item link (or identifier) and the
  app pulls that item's files straight into the picker to select and download.
  Great for links shared in the Discord or found elsewhere.

## [0.5.5] - 2026-06-17

### Fixed
- **More reliable resume.** The interrupted-batch queue is now saved before a
  download starts, so a crash during the very first file still lets you resume.
- **Wi-Fi-only now holds mid-download.** If you start on Wi-Fi and drop to mobile
  data, downloads auto-pause (and resume when Wi-Fi returns) instead of silently
  using cellular - so the data-cap protection actually works the whole time.
- **Confirmations before destructive taps.** Cancelling a whole batch and signing
  out now ask first (easy to mis-tap on a handheld), and "clear history" is styled
  as a destructive action.
- **Cancelling a batch is snappier** - it no longer fires needless network probes
  for files that had not started, and the source health check now stops its
  in-flight checks immediately when cancelled.
- A notice now appears if saved lists or history ever fail to load (instead of
  silently showing empty), download history entries no longer collide, a small
  memory cache is cleared between batches, the login screen submits on the
  keyboard Done key, and several accessibility/touch-target fixes.

## [0.5.4] — 2026-06-16

### Fixed
- **Login status now updates live on the home screen.** After v0.5.3 you could
  still briefly see "not signed in" on the home screen even though you were
  logged in (it corrected itself if you visited another screen and came back).
  The home screen now reflects your real login state immediately and updates
  the instant a background sign-in completes — no need to navigate away and
  back.

## [0.5.3] — 2026-06-16

### Fixed
- **Stay logged in.** After signing in once, the app now trusts your saved
  session immediately on startup instead of racing a timer — so it no longer
  needlessly re-logs-in (the "it takes a moment") or briefly shows a signed-out
  state. If your session ever does expire, it re-authenticates silently in the
  background; you should only ever see the login screen again if your saved
  password stops working.
- **No more "No files found" flash.** Starting a download no longer flashes the
  empty "No files found" screen for a split second, and pressing Back after a
  download no longer lands on a stale, empty picker — Back now returns home.

### Changed
- **Adaptive engine: faster mode (experimental).** The adaptive download engine
  can now open more connections across more mirrors at once and "race" the last
  few slow chunks at the end of a file, to cut the long tail. Still off by
  default (Settings → Experimental). Honest note: gains depend on your
  connection and how many fast mirrors a file has — if you're already maxing
  your own bandwidth, more connections won't beat that ceiling.

## [0.5.2] — 2026-06-16

### Fixed
- **Adaptive download engine no longer fails partway through.** The previous
  version's "this mirror is slow" detector was far too trigger-happy under
  normal multi-connection load and counted those false alarms against the
  same budget as real errors, so big downloads could fail around the halfway
  mark. Now: slow-mirror migrations never count as failures (only genuine
  network errors do), the "slow" decision uses a realistic, decaying baseline
  and must be sustained before acting, and it stays off entirely until it has
  enough real data. The engine also can't get stuck or orphan a piece of a
  file anymore. Backed by new tests covering exactly these cases.

> Still experimental and off by default — Settings → Experimental — but it
> should now actually help instead of failing.

## [0.5.1] — 2026-06-16

### Added
- **Community & support** — EmuHelper now has a Discord. A one-time welcome
  prompt on first launch invites you to join, and the link is always available
  in the About screen (and the README). Come say hi.

## [0.5.0] — 2026-06-16

### Added
- **Experimental adaptive download engine** (Settings, off by default). On the
  Internet Archive, individual mirrors throttle hard and inconsistently — this
  engine splits each file into many small chunks pulled from a shared queue by a
  pool of connections, so fast mirrors do more work and a slow one only holds up
  one small chunk (requeued elsewhere) instead of bottlenecking the whole file.
  Can meaningfully speed up large downloads. Turn it on in Settings to try it.

### Notes
- The engine is fully opt-in; with it off, downloads behave exactly as before.
  The 24-connection safety cap, resume, and MD5 verification all still apply.
  Backed by new unit tests proving the chunked transfer reconstructs files
  exactly even under connection failures.

## [0.4.0] — 2026-06-16

### Added
- **Download integrity check** — each downloaded file is now verified against
  the Internet Archive's published MD5. A corrupt download is caught and marked
  "File corrupt — retry" instead of silently leaving a broken file.
- **Wi-Fi-only mode** (Settings) — block downloads on mobile data, with a clear
  prompt and a "download anyway" option, so a big batch can't burn a data cap.

### Changed
- Big jank reduction during downloads on low-RAM handhelds (the download list no
  longer churns the UI on every progress tick), plus a bounded metadata cache and
  lighter game-picker/source-health screens.
- Clearer README (it's an Internet Archive download manager; the prebuilt APK is
  ready to use) with a live downloads badge.

### Fixed
- Save-list screen scrolls/pads for the keyboard so the Save button isn't clipped.
- Refresh-sources button shows progress; signup web view and error-log list are
  hardened against crashes; a catalog-merge fallback no longer blanks a hint.

## [0.3.2] — 2026-06-11

### Fixed
- Landscape display on handheld devices: several screens (including sign-in)
  centered their content and clipped the bottom off-screen with no way to
  scroll. They now scroll so every button and link is reachable — notably the
  "Log In", "Skip login", and "Create an account" options on the sign-in screen.

## [0.3.1] — 2026-06-11

### Added
- A guided "create an account" path for new users: a link on the sign-in
  screen leads to a short explainer and an in-app signup page (with an
  "open in browser" fallback), so people without an account can make one
  without getting stuck at the login wall.

## [0.3.0] — 2026-06-11

### Added
- **Updatable source catalog (optional).** The source list can now be refreshed
  from a remote file at runtime, so it can change without shipping a new app
  build. It's off by default and the built-in catalog is always the fallback —
  a remote update can only add or replace sources, never remove the built-in
  ones, and any failed or invalid fetch leaves the catalog untouched.
- **Source health check** (in About): pings each configured endpoint and reports
  which are reachable, so dead sources are easy to spot.
- **Local error log** (in About): an on-device, no-network record of errors that
  can be viewed, copied, or shared for diagnosis.

### Internal
- A release helper script that builds, tests, hashes, and drafts release notes.

## [0.2.1] — 2026-06-11

### Added
- Re-download from history: tap a past download to fetch it again.
- Search and sort in the saved-list library, plus a creation date on each list.
- Settings: "Reset to defaults" for speed controls and a Storage section to
  view, change, or revert the download folder.
- A confirmation prompt before downloading a batch that may not fit in the
  available space, and a rough scan-size hint when choosing categories.

## [0.2.0] — 2026-06-11

### Performance
- Faster, smoother downloads: throttled progress aggregation, cached archive
  metadata (no more refetching the same listing per file), signal-based pause
  (instant resume), buffer reuse, and lighter UI hot paths.

### Added
- Live download progress in the notification (count, %, speed).
- Tappable history entries — open the download folder or copy a filename.
- Cancel/retry in the file-staging flow and cancel for the speed test.
- First unit-test suite (81 tests) for the project's core logic.

### Changed
- Clearer sign-in prompt and empty-state guidance on the home screen.
- A scan that finds nothing now offers go-back and retry instead of a dead end.

## [0.1.9] — 2026-06-11

### Security
- The in-app updater now verifies updates before installing: it only accepts
  APKs from the official GitHub release host and checks a published SHA-256
  when available, discarding anything that doesn't match.
- Stored credentials are never written to unencrypted storage as a fallback.
- Hardened archive extraction against path-escape, and scoped session cookies
  strictly to their source.

### Added
- Resume an interrupted download batch after a crash or restart.
- "Already downloaded" indicator on files you've fetched before.
- Search across all scanned categories at once.
- Import a saved list from a URL.

### Fixed
- Several download-engine races (duplicate retries, history miscounts).
- Update download can now be cancelled; partial files are cleaned up.
- Clear "storage full" message; retry re-checks folder access first.
- Version comparison handles pre-release tags; assorted UI state fixes.

## [0.1.8] — 2026-06-11

### Added
- **In-app updates** — the update system now shows the new release's patch
  notes inside the app, downloads the new build with a progress bar, and hands
  it to the installer so you can update without leaving the app. Routes you to
  grant install permission if needed, and falls back to opening the release page
  if a build has no installable asset.
- A dismissed update is remembered per version, so it won't keep re-appearing
  until a newer release is out.

## [0.1.7] — 2026-06-11

### Added
- **In-app update check** — on launch (at most once a day) the app checks for a
  newer release and shows a dismissible "update available" banner.
- **About screen** — version, project links, and a manual "check for updates"
  button, reachable from the home menu.
- **Download history** — completed and failed transfers are logged and viewable,
  with a clear-history action.
- **Notification controls** — pause/resume and cancel a running batch from the
  download notification.
- **Theme toggle** — choose System / Light / Dark in Settings.
- **Rename saved lists** from the library.
- **Free-space readout** with a "may not fit" warning before downloading.
- Remembers your last selection so it's pre-ticked next time.

### Changed
- Cleans up orphaned temporary download files on launch.
- UI polish: unified corner radius and spacing, smooth list animations, a
  friendlier empty state in the picker, one-tap Settings on the home screen,
  a step indicator in the guided file-staging flow, and accessibility fixes.

## [0.1.6] — 2026-06-08

### Changed
- Repository hygiene & project-health pass: added contributing, security, and
  code-of-conduct guidelines, issue and pull-request templates, and this
  changelog.
- Internal cleanup and tidying across the project.

## [0.1.5] — 2026-06-07

### Changed
- **Privacy & stability:** stopped writing sensitive details to the device log;
  hardened optimized release builds for serialized data.
- **More reliable downloads:** detect lost storage-folder access up front
  instead of failing late; verify file size mid-transfer to prevent corrupt
  files; "Retry failed" now preserves each file's target folder.
- **UX:** confirm before deleting a saved list; "Open folder" button after a
  batch finishes; request notification permission on Android 13+; corrected a
  settings slider range.
- Full README overhaul.

## [0.1.4] — 2026-06-06

### Changed
- Archive extraction is now **off by default**.
- Case-insensitive folder matching: downloads merge into an existing folder of a
  different letter case instead of creating a duplicate.
- The "already downloaded, skip" check and per-file overwrite are now
  case-insensitive as well.

## [0.1.3] — 2026-06-06

### Added
- Guided file-staging helper: copies files you select from your own device into
  another app's import folder, with on-screen instructions.
- Project logo.

### Changed
- Dead-code cleanup.

## [0.1.2] — 2026-06-06

### Added
- App version shown on the home screen.

[Releases]: https://github.com/mayusi/EmuHelper/releases
