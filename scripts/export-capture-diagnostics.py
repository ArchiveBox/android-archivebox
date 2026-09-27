"""Export only public-page Chrome and screenshot hook output after capture failure."""

import json
import re
import sqlite3
import sys
from pathlib import Path


PUBLIC_URLS = {"https://example.com", "https://archivebox.io"}
CAPTURE_HOOKS = {
    "on_CrawlSetup__89_chrome_kill_zombies.js",
    "on_CrawlSetup__90_chrome_launch.daemon.bg.js",
    "on_CrawlSetup__91_chrome_wait.js",
    "on_Snapshot__00_chrome_launch.daemon.bg.js",
    "on_Snapshot__01_chrome_tab.daemon.bg.js",
    "on_Snapshot__30_chrome_navigate.js",
    "on_Snapshot__51_screenshot.js",
}
CDP_URL = re.compile(r"wss?://127\.0\.0\.1:\d+/devtools/browser/[0-9a-fA-F-]+")
PERSONA_PATH = re.compile(r"/[^\r\n]*/\.persona/[^\r\n]*")
API_SECRET = re.compile(r"(?i)(authorization:\s*bearer\s+|x-archivebox-api-key:\s*|api[_-]?token[=: ]+)\S+")


def redact(text: str) -> str:
    text = CDP_URL.sub("ws://127.0.0.1:<port>/devtools/browser/<redacted>", text)
    text = PERSONA_PATH.sub("<persona-path>", text)
    return API_SECRET.sub(r"\1<redacted>", text)


def main() -> int:
    data_dir = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    database = data_dir / "index.sqlite3"
    output_dir.mkdir(parents=True, exist_ok=True)
    if not database.is_file():
        print("No initialized capture database; no hook diagnostics to export.")
        return 0

    exported = 0
    with sqlite3.connect(f"file:{database}?mode=ro", uri=True) as connection:
        rows = connection.execute(
            """
            SELECT id, cmd, status, exit_code, stdout, stderr, started_at, ended_at
            FROM machine_process
            WHERE process_type = 'hook'
            ORDER BY started_at, id
            """,
        )
        for process_id, command_json, status, exit_code, stdout, stderr, started_at, ended_at in rows:
            try:
                command = json.loads(command_json or "[]")
            except (TypeError, json.JSONDecodeError):
                continue
            hook = next((Path(str(arg)).name for arg in command if Path(str(arg)).name.startswith("on_")), "")
            if hook not in CAPTURE_HOOKS:
                continue

            urls = [str(arg).removeprefix("--url=") for arg in command if str(arg).startswith("--url=")]
            if urls and any(url.rstrip("/") not in PUBLIC_URLS for url in urls):
                continue
            if hook.startswith("on_Snapshot__") and not urls:
                continue

            safe_hook = re.sub(r"[^A-Za-z0-9_.-]", "_", hook.removesuffix(".js"))
            prefix = f"{exported:02d}-{safe_hook}-{process_id}"
            (output_dir / f"{prefix}.stdout.log").write_text(redact(stdout or ""), encoding="utf-8")
            (output_dir / f"{prefix}.stderr.log").write_text(redact(stderr or ""), encoding="utf-8")
            metadata = {
                "hook": hook,
                "url": urls[0] if urls else None,
                "status": status,
                "exitCode": exit_code,
                "startedAt": started_at,
                "endedAt": ended_at,
            }
            (output_dir / f"{prefix}.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
            exported += 1

    crawl_chrome_logs = data_dir / "archive" / "users" / "android-captures" / "crawls"
    if crawl_chrome_logs.is_dir():
        for index, path in enumerate(sorted(crawl_chrome_logs.glob("**/chrome/on_CrawlSetup__90_chrome_launch.daemon.bg.*.stderr.log"))):
            (output_dir / f"chrome-launch-{index:02d}.stderr.log").write_text(
                redact(path.read_text(encoding="utf-8", errors="replace")),
                encoding="utf-8",
            )

    print(f"Exported {exported} public Chrome/screenshot hook records to {output_dir}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
