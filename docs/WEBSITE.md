# Website and screenshot gallery

The static site shares ArchiveBox's navigation/footer style with the Apple and Electron app sites. It uses local assets, system fonts, semantic HTML, and responsive CSS. The default canonical URL is `https://archivebox.github.io/android-archivebox/`; a custom domain is not assumed.

## Local preview

Requires Node.js 22+:

```sh
node scripts/build-site.mjs --baseurl / --allow-missing-screenshots
npx --yes serve _site
```

The explicit local-preview flag permits an empty gallery before real captures exist. It displays an honest pending-capture message, not simulated screens. It is disabled in CI.

## Release build

```sh
RELEASE_VERSION=0.1.0 node scripts/build-site.mjs \
  --screenshots-dir artifacts/screenshots --output _site --require-screenshots
```

`SCREENSHOTS_DIR` can provide the input directory instead. The default is `docs/screenshots`. `SITE_URL` overrides the canonical URL and default base path; `--baseurl` can set the serving path separately. Upload `_site` using GitHub Pages Actions. No Jekyll or third-party runtime is needed.

The default build is strict. Missing images, missing flows, invalid PNG dimensions, incorrect checksums, malformed metadata, or captures from a different `GITHUB_SHA` fail the build. `RELEASE_VERSION`, when supplied, must match the capture version. Release jobs must set both the SHA and version. Screenshot URLs include content hashes so a new capture does not reuse a browser's previous image cache.

## Capture contract

The screenshot producer writes real app PNGs and `manifest.json` into one directory. Capture the running app against a real ArchiveBox server through normal user-facing interactions. Do not generate app mockups, fabricate server responses, or copy captures from another release.

Required IDs (and corresponding `<id>.png` filenames): `onboarding`, `connections`, `discovery`, `library`, `search`, `snapshot`, `add`, `tags`, `share`, `share-saved`, `activity`, `settings`, `server-browser`.

Each manifest uses this shape; values below describe the fields and are not a usable capture:

```json
{
  "schemaVersion": 1,
  "commit": "the full 40-character captured Git commit",
  "appVersion": "0.1.0",
  "generatedAt": "ISO-8601 timestamp",
  "device": "actual emulator or device and Android API level",
  "backend": "actual ArchiveBox server version or image identifier",
  "workflowRun": {"url": "https://github.com/ArchiveBox/android-archivebox/actions/runs/123"},
  "screenshots": [
    {
      "id": "share",
      "file": "share.png",
      "title": "Share a link",
      "description": "Review a shared URL and add tags before saving.",
      "width": 1080,
      "height": 2400,
      "sha256": "SHA-256 digest of the actual PNG bytes"
    }
  ]
}
```

`workflowRun` is optional for local captures. Keep credentials masked by the actual app UI before capture. The site builder emits the original manifest alongside the images and lists version, commit, device, capture time, and workflow provenance in the gallery.

## Editing

- `docs/index.html`: landing-page content. The builder injects real library/share captures.
- `docs/header.html`, `docs/footer.html`, `docs/site-chrome.css`: shared ArchiveBox navigation and footer.
- `docs/style.css`: page and gallery presentation.
- `docs/assets`: official logo, favicons, and generic ArchiveBox OG image; see [branding credits](BRANDING.md).
- `scripts/build-site.mjs`: manifest validation and static generation.

Keep feature claims consistent with the implementation and [parity map](PARITY.md). Add capture coverage whenever a major screen is introduced. The APK CTA is the stable GitHub release asset `ArchiveBox-Android.apk`. Add a Google Play CTA only after its real listing URL exists.
