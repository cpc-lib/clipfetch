#!/usr/bin/env bash
# ClipFetch file gateway - Linux TEST build launcher (daemon mode, no systemd).
# Usage: ./start-gateway-linux.sh {start|stop|restart|status}
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

CONF="gateway-linux.conf"
PID_FILE="gateway.pid"
LOG_FILE="gateway.log"

load_config() {
    if [ ! -f "$CONF" ]; then
        echo "Missing config: $CONF" >&2
        exit 1
    fi
    while IFS='=' read -r key value || [ -n "${key:-}" ]; do
        # Tolerate CRLF left by Windows editors and surrounding whitespace.
        key="${key%$'\r'}"
        value="${value%$'\r'}"
        key="$(printf '%s' "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        value="$(printf '%s' "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        [ -z "$key" ] && continue
        case "$key" in
            '#'*) continue ;;
        esac
        case "$key" in
            QBT_FILE_*) export "$key=$value" ;;
            *) echo "Unexpected config key: $key" >&2; exit 1 ;;
        esac
    done < "$CONF"

    : "${QBT_FILE_ROOT:?Missing key: QBT_FILE_ROOT}"
    : "${QBT_FILE_GATEWAY_SECRET:?Missing key: QBT_FILE_GATEWAY_SECRET}"
    if [ "${#QBT_FILE_GATEWAY_SECRET}" -lt 32 ] || [[ "$QBT_FILE_GATEWAY_SECRET" == CHANGE_ME* ]]; then
        echo "Set a random secret at least 32 characters long before starting." >&2
        exit 1
    fi
    if [ ! -d "$QBT_FILE_ROOT" ]; then
        echo "qBittorrent download folder not found: $QBT_FILE_ROOT" >&2
        exit 1
    fi
}

is_running() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid="$(cat "$PID_FILE")"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

start() {
    if is_running; then
        echo "Already running (PID $(cat "$PID_FILE"))."
        return 0
    fi
    load_config
    echo "Starting ClipFetch file gateway (TEST build, any source IP) at ${QBT_FILE_GATEWAY_BIND:-0.0.0.0}:${QBT_FILE_GATEWAY_PORT:-18766}"
    nohup python3 -u gateway.py >>"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
    sleep 1
    if is_running; then
        echo "Started (PID $(cat "$PID_FILE")), log: $LOG_FILE"
    else
        echo "Failed to start, see $LOG_FILE" >&2
        rm -f "$PID_FILE"
        exit 1
    fi
}

stop() {
    if ! is_running; then
        echo "Not running."
        rm -f "$PID_FILE"
        return 0
    fi
    local pid
    pid="$(cat "$PID_FILE")"
    kill "$pid"
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "Still alive after SIGTERM, sending SIGKILL (PID $pid)"
        kill -9 "$pid"
    fi
    rm -f "$PID_FILE"
    echo "Stopped."
}

status() {
    if is_running; then
        echo "Running (PID $(cat "$PID_FILE")), log: $LOG_FILE"
    else
        echo "Not running."
        return 1
    fi
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    restart) stop; start ;;
    status) status ;;
    *) echo "Usage: $0 {start|stop|restart|status}" >&2; exit 1 ;;
esac
