# Validation

This repository is under initial implementation. Source availability and a designed CI workflow are not evidence that device flows or published releases have passed.

Before calling the first release accepted, record the actual commit, toolchain, device/API level, backend version, commands, results, and screenshots for:

- Debug and signed release build, unit tests, Android lint, and instrumentation tests.
- A real ArchiveBox server connection and authentication, including rejected credentials.
- External Android sharing, existing/new/recent tags, persona selection, successful server submission, and saved server data.
- Native search, pagination, original/archive links, server collection and administration pages.
- LAN discovery on port 5759 and reachable tailnet candidates; physical network acceptance separately from an emulator.
- First-run guide, existing-server shortcut, dismissal/reopen, theme changes, accessibility, phone and tablet layout.
- Signed APK installation and update with the same signing key, plus AAB generation.
- Main-branch version bump, release assets, screenshot refresh, and deployed GitHub Pages navigation on desktop and mobile.

## Evidence so far

No completed Android device acceptance is recorded in this document yet. The implementation team will add verified results as checks finish. A first Google Play release additionally needs a real developer account, listing, production signing setup, policy declarations, and review; no Play listing is claimed.

### Website preview — 2026-09-20

Built the local site with `node scripts/build-site.mjs --baseurl / --allow-missing-screenshots` and opened it in real headless Google Chrome through Playwright. The landing page and empty-gallery preview were visually inspected at 1440 × 1100 and 390 × 844; neither had horizontal overflow or JavaScript page errors. The Apps menu and Escape dismissal and FAQ expansion worked. This validates the local layout with explicitly pending captures, not the gallery with final app screenshots or a deployed Pages site.
