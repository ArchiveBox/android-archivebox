# Android and Apple feature scope

This is the implementation map, not a claim that all flows have passed device acceptance. See [VALIDATION.md](VALIDATION.md) for recorded evidence. Server pages depend on the connected ArchiveBox version and the authenticated user’s permissions.

| Experience in ArchiveBox.app | Android implementation | Scope |
| --- | --- | --- |
| First-run guide, existing-server shortcut, reopen guide | Native Compose setup guide | Includes persistent dismissal |
| Connect with URL and API key | Native Connection Settings | Checks the actual server |
| Share URLs with tags | Android share intent and native bottom sheet | Tag suggestions and creation, recent tags, server submission |
| Add multiple URLs and choose persona | Native Add URLs screen | Server handles the actual archiving |
| Search by title, URL, or tag | Native search | Uses the connected server, not an offline index |
| Browse saved snapshots and outputs | In-app server pages and native search results | Server UI supplies the full collection browser |
| Open and share original or archived URLs | Search result actions and app links | Only the selected server is used |
| Crawls, schedules, archive results, tags | In-app server pages | Same server routes, adapted by the server for mobile |
| Users, personas, API keys, webhooks, processes, machines, binaries, plugins, workers, logs | In-app server pages | Requires appropriate server permissions |
| AI Agent | In-app server page | Depends on server configuration |
| Connection discovery | Native LAN candidate checks and explicit tailnet hints on port 5759 | No complete tailnet peer enumeration |
| Light/dark appearance and larger devices | Material 3 and adaptive navigation | Phone/tablet acceptance must be recorded separately |
| App actions | Android share intents, deep links, launcher shortcuts | Android integration rather than Apple App Intents |
| Siri, Spotlight, Handoff, widgets and Control Center | No direct implementation | Android equivalents beyond launcher shortcuts remain future work |
| Safari/browser extension bundled with app | Separate ArchiveBox browser extension | Not bundled into the Android APK |
| macOS companion running a local server | Connect to an existing remote server | Android does not run ArchiveBox Server locally |
| Offline archive downloads or queued submissions | No offline archive or queue | Saving and browsing need a reachable server |

Full Apple platform feature parity is not claimed. The initial scope focuses on shared core workflows and Android sharing, discovery, and distribution.
