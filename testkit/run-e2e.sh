#!/usr/bin/env bash
#
# MultiLogin x Velocity end-to-end login test (Linux / macOS).
#
#   1. starts a fake Yggdrasil (returns a fixed profile from /session/minecraft/hasJoined)
#   2. starts a real Velocity proxy with the plugin under test
#   3. runs LoginProbe, a minimal Minecraft client that performs the full login handshake
#   4. asserts the UUID/name carried by LoginSuccess
#
# Prerequisites:
#   javac -encoding UTF-8 -d testkit/classes testkit/FakeYggdrasil.java testkit/LoginProbe.java
#
# Usage:
#   ./testkit/run-e2e.sh --jar dist/MultiLogin-Velocity-3.5.1.jar --server-dir run/velocity-3.5.1
#
# Exit code 0 = PASS.
#
set -euo pipefail

JAR=""
SERVER_DIR=""
PROXY_PORT=25588
YGG_PORT=18080
USERNAME="TestPlayer"
EXPECTED_UUID="11111111-2222-3333-4444-555555555555"
PROTOCOL=767
JAVA_BIN="${JAVA:-java}"
DENY_PATHS=""
FORCED_NAME=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)            JAR="$2"; shift 2 ;;
    --server-dir)     SERVER_DIR="$2"; shift 2 ;;
    --proxy-port)     PROXY_PORT="$2"; shift 2 ;;
    --ygg-port)       YGG_PORT="$2"; shift 2 ;;
    --username)       USERNAME="$2"; shift 2 ;;
    --expected-uuid)  EXPECTED_UUID="$2"; shift 2 ;;
    --protocol)       PROTOCOL="$2"; shift 2 ;;
    --java)           JAVA_BIN="$2"; shift 2 ;;
    --deny-paths)     DENY_PATHS="$2"; shift 2 ;;
    --forced-name)    FORCED_NAME="$2"; shift 2 ;;
    -h|--help)        sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$JAR" ]]; then echo "--jar is required" >&2; exit 2; fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
CLASSES="$ROOT/testkit/classes"
LOG_DIR="$ROOT/run/logs"
SERVER_DIR="${SERVER_DIR:-$ROOT/run/velocity-3.5.1}"

mkdir -p "$LOG_DIR"

[[ -f "$JAR" ]] || { echo "plugin jar not found: $JAR" >&2; exit 2; }
command -v "$JAVA_BIN" >/dev/null 2>&1 || { echo "java not found: $JAVA_BIN" >&2; exit 2; }
[[ -f "$CLASSES/LoginProbe.class" ]] || {
  echo "test kit not compiled. run: javac -encoding UTF-8 -d testkit/classes testkit/*.java" >&2
  exit 2
}
[[ -d "$SERVER_DIR" ]] || { echo "server dir not found: $SERVER_DIR" >&2; exit 2; }

YGG_LOG="$LOG_DIR/fake-yggdrasil.log"
PROXY_LOG="$LOG_DIR/velocity.log"
rm -f "$YGG_LOG" "$PROXY_LOG"

PROXY_PID=""
YGG_PID=""
cleanup() {
  [[ -n "$PROXY_PID" ]] && kill "$PROXY_PID" 2>/dev/null || true
  [[ -n "$YGG_PID" ]] && kill "$YGG_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Replace any stale plugin jar so the proxy cannot pick up an old build.
find "$SERVER_DIR/plugins" -maxdepth 1 -name '*.jar' -delete 2>/dev/null || true
cp "$JAR" "$SERVER_DIR/plugins/"

echo "==> starting fake Yggdrasil (127.0.0.1:$YGG_PORT)"
"$JAVA_BIN" -cp "$CLASSES" FakeYggdrasil \
  "$YGG_PORT" "${EXPECTED_UUID//-/}" "$USERNAME" "$DENY_PATHS" "$FORCED_NAME" \
  >"$YGG_LOG" 2>&1 &
YGG_PID=$!
sleep 2

echo "==> starting Velocity (127.0.0.1:$PROXY_PORT)"
( cd "$SERVER_DIR" && exec "$JAVA_BIN" -jar velocity.jar ) >"$PROXY_LOG" 2>&1 &
PROXY_PID=$!

echo "==> waiting for the proxy to come up"
for _ in $(seq 1 90); do
  sleep 1
  if grep -qE 'Listening on|Done \(' "$PROXY_LOG" 2>/dev/null; then break; fi
  if ! kill -0 "$PROXY_PID" 2>/dev/null; then echo "Velocity exited early" >&2; break; fi
done

echo "==> running login probe (protocol=$PROTOCOL user=$USERNAME)"
set +e
"$JAVA_BIN" -cp "$CLASSES" LoginProbe "$PROXY_PORT" "$PROTOCOL" "$USERNAME" "$EXPECTED_UUID"
RESULT=$?
set -e

echo
echo "===== Velocity log (key lines) ====="
grep -E 'multilogin|MultiLogin|ERROR|Exception|Listening|Done \(' "$PROXY_LOG" 2>/dev/null | tail -n 60 || true
echo
echo "===== fake Yggdrasil log ====="
tail -n 20 "$YGG_LOG" 2>/dev/null || true

echo
if [[ "$RESULT" -eq 0 ]]; then
  echo "==> E2E RESULT: PASS"
else
  echo "==> E2E RESULT: FAIL (exit=$RESULT)"
fi
exit "$RESULT"
