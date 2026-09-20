# Validation

## Verified locally — 2026-09-20

The app was exercised against a real ArchiveBox collection, with normal CLI initialization, API authentication, actual Chrome/wget captures of `example.com` and `archivebox.io`, and the server's own administration pages. No server responses, capture tools, or Android handlers were mocked.

Environment: macOS ARM64; JDK 25 locally (CI uses JDK 21); Gradle 9.3.1; Android Gradle Plugin 9.1.1; Kotlin 2.2.10; Compose BOM 2026.09.00. The API 37 Google APIs ARM64 emulator used a 1080 × 2400 phone display at 420 dpi, plus a 1280 × 800 / 160 dpi tablet profile. App minimum API level is 28.

The local backend checkout was `7a5ad4c99ad796fd90aea7535e983297cb9f28b5`. Hosted captures use the pinned revision in [ci.yml](../.github/workflows/ci.yml), recorded in each screenshot manifest.

### Build and automated device journeys

- `./gradlew lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` passed. Seven unit tests exercise real address/URL/tag parsing and replay-origin boundaries.
- The two core instrumentation journeys passed in 80.621 seconds and produced 32 real device captures. They cover rejected credentials without saving, successful connection, automatic discovery of the real server at the emulator's LAN address on port 5759, server tag autocomplete, native search, and visible archived-image rendering.
- A real exported Android share intent submitted a unique URL. Tests verified persisted server snapshot/tag data, rotation, tag editing, and Undo removing that submission while preserving earlier captures.
- Tests exercised every collection/administration route, a readable AI provider-setup dialog, changing server origin without retaining its API key, the native Docker setup guide, and both actions of a widget installed through Android's actual widget picker.
- The release capture suite additionally includes automated dark-mode and tablet profiles, bringing required coverage to 34 images. Their per-revision result is recorded by the workflow and capture manifest.

Rendering checks exposed a real WebView defect: without explicit `MATCH_PARENT` layout parameters, CSS percentage-height documents and replay frames collapsed to zero height. The app now supplies the bounded viewport; device screenshots and the rendered-image bounds verify the correction. The local emulator's automatic software GPU also produced stale duplicated tiles despite correct Chromium rendering; restarting with the host GPU corrected the actual screen output without disabling app hardware acceleration.

### Signed APK installation and update

Release minification/resource shrinking, `assembleRelease`, and `bundleRelease` passed. APK signature verification and AAB `jarsigner -verify` passed; artifacts are approximately 2.8 MB and 3.9 MB respectively.

Installed a signed version `0.0.9` / code `9`, connected through the real UI, then installed version `0.1.0` / code `1000` over it using `adb install -r`. The encrypted connection survived the update. The updated release successfully loaded native search and the archived page, accepted an external share intent, saved a unique URL with a `signed-release` tag, and removed it through the visible Undo confirmation. Real API reads verified exactly one saved snapshot and then zero after Undo. Dark phone appearance and the tablet navigation rail were visually inspected. No Android runtime crash was logged during this smoke test.

Permanent signing certificate SHA-256:

```text
dd63b33570be7d6c8076999c802039b711ec0cbeaf4a0a5dc0277ed5eb229dfd
```

### Website and release evidence

The populated 32-image local site was opened in actual Google Chrome at 1440 × 1100 and 390 × 844. All images and anchors loaded; there was no horizontal overflow, JavaScript error, or failed HTTP request. Apps-menu navigation, Escape dismissal, FAQ expansion, and gallery links passed. The corrected snapshot, AI dialog, and Activity screenshots were visually reviewed.

The public site is [android.archivebox.io](https://android.archivebox.io/), with HTTPS enforced. Hosted build/release acceptance is recorded per revision in [GitHub Actions](https://github.com/ArchiveBox/android-archivebox/actions/workflows/ci.yml). A successful release includes the signed APK/AAB and capture archive; the [published gallery manifest](https://android.archivebox.io/screenshots/manifest.json) records the exact app version, source commit, backend revision, workflow, dimensions, and PNG checksums. Local validation does not substitute for that hosted record.

## Limits of this evidence

- No physical Android phone or tablet, Android 9 device, or physical Wi-Fi/router discovery run was used. API 37 emulator discovery and configurable tailnet candidates are implemented; live discovery across a physical tailnet has not been accepted. Android cannot read the separate Tailscale app's private peer inventory.
- The AI page check validates the real provider-setup interface. It does not configure a paid model provider or send a model request.
- Replay-origin unit tests cover split-host navigation rules; a production HTTPS deployment with wildcard snapshot subdomains was not used in this local device run.
- Google Play publication requires an actual developer account, listing, signing enrollment, declarations, and review. GitHub beta distribution is the configured release route; no Play listing is claimed.
