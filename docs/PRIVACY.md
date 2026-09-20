# Privacy

ArchiveBox for Android is a client for a server you choose. It does not require an ArchiveBox-operated cloud account.

- URLs you submit, tags, personas, and search queries are sent to your configured ArchiveBox server. That server’s administrator controls access, storage, and retention.
- The connection is stored on your Android device. Credentials are not included in app links or shared archive URLs. Avoid including API keys in bug reports.
- Discovery checks network candidates on port 5759. A discovery check does not authenticate with your saved API key. Tailnet names you enter or import from Tailscale status JSON are resolved through the device’s configured network and DNS.
- The server’s web interface is displayed in an embedded browser. Your server and pages you open may load their own resources and have their own privacy policies.
- Archiving runs on the server, which contacts the websites you ask it to save and any external services enabled by its configuration. Review [ArchiveBox security and privacy settings](https://github.com/ArchiveBox/ArchiveBox/wiki/Security-Overview), including submission to Archive.org.
- GitHub hosts source, downloads, and the website, and receives ordinary requests when you use those services. README badges are served by Shields.io. The built website uses local assets and system fonts.
- The app does not provide offline archive synchronization or a durable submission queue. Keep the server reachable until it confirms your submission.

Use HTTPS for servers outside a trusted private network. HTTP remains available for self-hosted LAN connections, where requests can be observed by others with access to that network. A Tailscale connection requires the separate Tailscale app and the relevant tailnet access rules.

For privacy questions, use [ArchiveBox’s community forum](https://zulip.archivebox.io/) or the [project issue tracker](https://github.com/ArchiveBox/android-archivebox/issues) without posting private URLs or credentials.
