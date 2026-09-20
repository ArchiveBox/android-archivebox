# Releases, signing, and screenshots

Every push to `main` runs one serialized workflow: resolve the version → compile/lint/test → run an Android emulator against a real ArchiveBox server → validate every gallery image → build a signed APK and AAB → publish a GitHub release. Pull requests run the same verification and screenshot capture without access to signing secrets or publishing permissions. Failed tests, missing captures, and signing failures block release publication. A separate `pages.yml` workflow publishes website edits immediately with the last verified gallery, then refreshes it after successful app CI; see [Website](WEBSITE.md).

## Repository configuration

The public `ArchiveBox/android-archivebox` repository has been created, GitHub Pages is configured at [android.archivebox.io](https://android.archivebox.io/), and all four permanent release-signing secrets below are installed. Publication still depends on the workflow passing its build, device, and screenshot checks. Google Play publication is separate.

For maintainers restoring this setup or configuring a fork:

1. Create `ArchiveBox/android-archivebox` and push `main`.
2. Enable GitHub Pages with **GitHub Actions** as its source. Allow the `main` branch in the `github-pages` environment.
3. Create and back up a permanent Android signing keystore. Preserve this identity: changing it prevents existing beta installs from receiving updates. Never commit it.
4. Add repository Actions secrets:

   | Secret | Value |
   | --- | --- |
   | `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the permanent `.jks` or `.keystore` file |
   | `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
   | `ANDROID_KEY_ALIAS` | Release signing alias |
   | `ANDROID_KEY_PASSWORD` | Password for that key |

The workflow restores the keystore only in the signing job, verifies both artifacts, and removes the temporary file afterwards. It fails if secrets are absent; it never creates disposable release keys or substitutes a debug key.

The APK is available under the stable asset name `ArchiveBox-Android.apk`. The AAB is `ArchiveBox-Android.aab`, ready for a Play Console upload. Publishing to Google Play requires the developer account, app listing, store review, and Play App Signing/upload-key setup; this workflow does not claim a store release or upload without that setup. Select the permanent signing identity with the intended Play distribution model in mind.

## Version behavior

`version.properties` specifies the initial version or an intentional new release line. `scripts/release-version.mjs` inspects full git tag history, uses that base when newer than all tags, otherwise increments the largest `vMAJOR.MINOR.PATCH` tag. Rerunning the same commit reuses its existing release tag. There are no generated version commits, so no commit loop.

`versionCode = major × 1,000,000 + minor × 1,000 + patch`, with minor/patch limited to 0–999. The result increases with every release and stays below Android's maximum. Build jobs receive the resolved values through Gradle properties. The release tag is created only after verification; publication uses a draft until every download has uploaded successfully.

Stable downloads:

- `https://github.com/ArchiveBox/android-archivebox/releases/latest/download/ArchiveBox-Android.apk`
- `https://github.com/ArchiveBox/android-archivebox/releases/latest/download/ArchiveBox-Android.aab`
- `https://github.com/ArchiveBox/android-archivebox/releases/latest/download/ArchiveBox-Android-screenshots.tar.gz`

## Run the real Android journey locally

Install Java 17 or newer, the Android SDK and emulator, `uv`, Node.js, and pnpm. The app supports API 28 or newer; this capture suite uses an API 37 Google APIs emulator with the standard launcher, including its real widget picker. With the ArchiveBox checkout's environment installed:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
bash scripts/start-screenshot-server.sh ../archivebox /tmp/archivebox-android-capture
bash scripts/capture-screenshots.sh /tmp/archivebox-android-capture artifacts/screenshots
node scripts/build-site.mjs --screenshots-dir artifacts/screenshots --require-screenshots
```

Use a **new, empty** data directory on each run. The bootstrap creates a normal superuser, installs real capture dependencies, archives `https://example.com` and `https://archivebox.io`, and authenticates through the normal API. It leaves the server running on `127.0.0.1:5759`; stop the PID in the data directory's `server.pid` when done. The capture script uses `adb reverse` to reach that server and uninstalls/reinstalls only the ArchiveBox test app, removing stale widget actions from earlier runs. Use a dedicated test emulator or device; its ArchiveBox app settings will be removed. It never clears a user's server collection. Network access is required for the actual capture dependencies and websites.

The instrumentation journey enters credentials through Settings, rejects an invalid key without saving it, confirms automatic LAN discovery and real server tag suggestions, receives Android intents, saves a unique URL, checks persisted server state, rotates the device, edits tags, and undoes the test save. It also checks that changing server origin clears the API key. A separate launcher test installs the actual widget through the Android widget picker and verifies its Search and Save actions. Screenshots come from the real emulator screen, not previews or hand-drawn examples. API keys remain masked in captures. Capture provenance records the app commit/version, backend revision, device, dimensions, and each PNG's SHA-256. The website build checks this manifest and requires all declared screens from the same revision.

The mandatory gallery covers 34 screens: onboarding, the Docker setup guide, home, connection configuration, discovery, saved pages, search, an archived page, Add URLs, tags, the Android share sheet, a saved confirmation, activity, settings, the home-screen widget, dark mode, tablet navigation, and every collection and administration route. The visual-profile test changes Android's actual night mode and display configuration, checks the rendered dark background and visible navigation rail, and restores the original configuration even after failure. Each successful release carries the capture archive and deploys its fresh gallery.
