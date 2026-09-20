#!/usr/bin/env bash
# A disposable real collection; nothing here intercepts or replaces the server API.
set -euo pipefail
umask 077
backend=$(cd "${1:?Usage: start-screenshot-server.sh BACKEND_CHECKOUT EMPTY_DATA_DIRECTORY}" && pwd)
mkdir -p "${2:?A fresh data directory is required}"
data=$(cd "$2" && pwd)
if [[ -n "$(ls -A "$data")" ]]; then
    echo "Refusing to reuse a nonempty screenshot collection: $data" >&2
    exit 1
fi
port=${SCREENSHOT_SERVER_PORT:-5759}
export BASE_URL="http://127.0.0.1:$port" BIND_ADDR="127.0.0.1:$port"
export SCREENSHOT_USERNAME=android-captures
export SCREENSHOT_PASSWORD
SCREENSHOT_PASSWORD=$(openssl rand -hex 24)
uv run --no-sync --project "$backend" python - "$port" <<'PY'
import socket, sys
with socket.socket() as sock:
    sock.bind(('127.0.0.1', int(sys.argv[1])))
PY
cd "$data"
abx() { uv run --no-sync --project "$backend" archivebox "$@"; }
abx init --quick
git -C "$backend" rev-parse HEAD > "$data/backend-revision"
# User creation uses the supported Django command; authentication below uses the public API.
export DJANGO_SUPERUSER_USERNAME="$SCREENSHOT_USERNAME"
export DJANGO_SUPERUSER_PASSWORD="$SCREENSHOT_PASSWORD"
export DJANGO_SUPERUSER_EMAIL=android-captures@example.invalid
abx manage createsuperuser --noinput
opencode_port=$(uv run --no-sync --project "$backend" python - <<'PYPORT'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PYPORT
)
abx config --set SEARCH_BACKEND_ENGINE=sqlite SEARCH_BACKEND_SONIC_ENABLED=False \
    SEARCH_BACKEND_SQLITE_ENABLED=True PLUGINS=title,headers,wget,screenshot,search_backend_sqlite,opencode \
    OPENCODE_ENABLED=True "OPENCODE_PORT=$opencode_port"
abx persona create 'Research browser'
abx install opencode --binproviders=env,pnpm
abx install chrome wget title headers screenshot
abx add --depth=0 --tag=reference,research --plugins=title,headers,wget,screenshot,search_backend_sqlite \
    https://example.com https://archivebox.io
server_pid=''
complete=0
cleanup() {
    if [[ "$complete" != 1 && -n "$server_pid" ]]; then
        kill "$server_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT
nohup uv run --no-sync --project "$backend" archivebox server "$BIND_ADDR" \
    > "$data/server.log" 2>&1 < /dev/null &
server_pid=$!
printf '%s\n' "$server_pid" > "$data/server.pid"
ready=0
for _attempt in $(seq 1 60); do
    if ! kill -0 "$server_pid" 2>/dev/null; then
        tail -100 "$data/server.log" >&2
        exit 1
    fi
    if curl --fail --silent --max-time 2 "$BASE_URL/api/v1/openapi.json" > "$data/openapi.json"; then
        ready=1
        break
    fi
    sleep 1
done
[[ "$ready" == 1 ]] || { tail -100 "$data/server.log" >&2; exit 1; }
export SCREENSHOT_DATA="$data"
uv run --no-sync --project "$backend" python - <<'PY'
import json, os, pathlib, urllib.request
root = pathlib.Path(os.environ['SCREENSHOT_DATA'])
base = os.environ['BASE_URL']
images = list(root.glob('archive/**/screenshot/screenshot.png'))
assert len(images) == 2 and all(image.stat().st_size > 0 for image in images), 'Real Chrome screenshots are missing'
body = json.dumps({'username': os.environ['SCREENSHOT_USERNAME'], 'password': os.environ['SCREENSHOT_PASSWORD']}).encode()
request = urllib.request.Request(base + '/api/v1/auth/get_api_token', body, {'Content-Type': 'application/json'})
with urllib.request.urlopen(request, timeout=15) as response:
    auth = json.load(response)
assert auth['success'] and auth['token'], 'API authentication failed'
token = auth['token']
request = urllib.request.Request(base + '/api/v1/core/snapshots', headers={'X-ArchiveBox-API-Key': token})
with urllib.request.urlopen(request, timeout=15) as response:
    snapshots = json.load(response)
assert snapshots['count'] == 2, snapshots['count']
assert all(item['title'] for item in snapshots['items']), 'Real capture titles are missing'
(root / 'api-token').write_text(token)
(root / 'server-url').write_text(base)
if os.environ.get('GITHUB_ACTIONS') == 'true':
    print('::add-mask::' + token)
print(f'Real server ready: {base}; {snapshots["count"]} archived pages.')
PY
complete=1
