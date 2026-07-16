# sleepwalker-readline-fixture-ctl: control the remote readline fixture
# over SSH.
#
# Sends a JSON operation to the fixture's Unix socket on the observer
# host and prints the JSON response.
#
# Usage:
#   sleepwalker-readline-fixture-ctl <ssh_target> <operation> [timeout] [identity] [known_hosts]
#
# Operations:
#   describe       - return fixture identity and ABI version
#   reset          - deterministically clear the Readline buffer and barrier
#   await_barrier  - wait for next F24 barrier (optional [timeout] ms, default 5000)
#   snapshot       - return current buffer, point, mark, generation
#   health         - return process, VT, keymap, and barrier status
#   shutdown       - stop the fixture process and clean up socket
#
# Each operation produces one JSON response line on stdout.
# Exit code 0 on success, 1 on timeout or error.
{ lib, writeShellScriptBin, openssh, coreutils, gnugrep, gnused, socat }:

let
  primPath = lib.makeBinPath [ openssh coreutils gnugrep gnused socat ];
  SOCKET_PATH = "/tmp/sleepwalker-readline-fixture-v1.sock";
in
writeShellScriptBin "sleepwalker-readline-fixture-ctl" ''
  set -euo pipefail
  export PATH="${primPath}:$PATH"

  SSH_TARGET="''${1:?}"
  OPERATION="''${2:?}"
  TIMEOUT="''${3:-}"
  IDENTITY="''${4:-}"
  KNOWN_HOSTS="''${5:-}"
  SOCKET_PATH="${SOCKET_PATH}"

  # Build SSH args
  SSH_ARGS=()
  if [ -n "$IDENTITY" ]; then
    SSH_ARGS+=(-i "$IDENTITY")
  fi
  if [ -n "$KNOWN_HOSTS" ]; then
    SSH_ARGS+=(-o "UserKnownHostsFile=$KNOWN_HOSTS")
    SSH_ARGS+=(-o "StrictHostKeyChecking=no")
  fi
  SSH_ARGS+=(-o "BatchMode=yes")

  case "$OPERATION" in
    describe)
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "echo '{\"operation\":\"describe\"}' | \
         timeout 5 socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null" \
        || { echo "{\"status\":\"error\",\"message\":\"fixture not reachable\"}" >&2; exit 1; }
      ;;

    reset)
      OUT=$(ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "echo '{\"operation\":\"reset\"}' | \
         timeout 5 socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null") \
        || { echo "{\"status\":\"error\",\"message\":\"fixture not reachable\"}" >&2; exit 1; }
      echo "$OUT"
      # Reset deliberately execs a fresh fixture process to discard all
      # Readline state. Do not return until the replacement control socket
      # accepts a health request; callers may issue setText immediately.
      RESTART_READY=false
      for _ in $(seq 1 50); do
        HEALTH=$(ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
          "echo '{\"operation\":\"health\"}' | \
           timeout 1 socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null" \
          2>/dev/null) || HEALTH=""
        if printf '%s' "$HEALTH" | grep -q '"ok":true'; then
          RESTART_READY=true
          break
        fi
        sleep 0.1
      done
      if [ "$RESTART_READY" != true ]; then
        echo "{\"status\":\"error\",\"message\":\"fixture restart timeout\"}" >&2
        exit 1
      fi
      ;;

    await_barrier)
      TIMEOUT_MS="''${TIMEOUT:-5000}"
      REQ="{\"operation\":\"await_barrier\",\"timeout_ms\":$TIMEOUT_MS}"
      OUT=$(ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "echo '$REQ' | \
         timeout $(( TIMEOUT_MS / 1000 + 2 )) socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null") \
        || { echo "{\"status\":\"error\",\"message\":\"fixture not reachable\"}" >&2; exit 1; }
      echo "$OUT"
      # If the response contains "timeout", exit 1
      if echo "$OUT" | grep -q '"status":"timeout"'; then
        exit 1
      fi
      ;;

    snapshot)
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "echo '{\"operation\":\"snapshot\"}' | \
         timeout 5 socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null" \
        || { echo "{\"status\":\"error\",\"message\":\"fixture not reachable\"}" >&2; exit 1; }
      ;;

    health)
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "echo '{\"operation\":\"health\"}' | \
         timeout 5 socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null" \
        || { echo "{\"status\":\"error\",\"message\":\"fixture not reachable\"}" >&2; exit 1; }
      ;;

    shutdown)
      OUT=$(ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "echo '{\"operation\":\"shutdown\"}' | \
         timeout 5 socat - UNIX-CONNECT:$SOCKET_PATH 2>/dev/null") \
        || true  # Fixture may exit before socat closes
      if [ -n "$OUT" ]; then
        echo "$OUT"
      fi
      # Ensure the process is dead
      ssh "''${SSH_ARGS[@]}" "$SSH_TARGET" \
        "sudo pkill -f '^/tmp/sleepwalker-readline-fixture /dev/tty' 2>/dev/null || true"
      ;;

    *)
      echo "Usage: $0 <ssh_target> <describe|reset|await_barrier|snapshot|health|shutdown> [timeout] [identity] [known_hosts]" >&2
      exit 1
      ;;
  esac
''
