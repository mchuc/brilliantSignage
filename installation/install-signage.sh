#!/usr/bin/env bash

# ============================================================
# brilliantSignage - interactive autostart installer
#
# Author: Marcin Chuć
# Version: 0.0.1
#
# ===================== INSTRUCTIONS ==========================
# EN:
# 1. Copy brilliantSignage.jar to your home directory:
#    /home/USER/brilliantSignage.jar
#
# 2. Copy this script to your home directory:
#    install-signage.sh
#
# 3. Make it executable:
#    chmod +x install-signage.sh
#
# 4. Run:
#    ./install-signage.sh
#
# PL:
# 1. Skopiuj plik brilliantSignage.jar do katalogu domowego:
#    /home/USER/brilliantSignage.jar
#
# 2. Skopiuj ten skrypt do katalogu domowego jako:
#    install-signage.sh
#
# 3. Nadaj uprawnienia:
#    chmod +x install-signage.sh
#
# 4. Uruchom:
#    ./install-signage.sh
#
# ============================================================
# WHAT THIS SCRIPT DOES:
# - creates launcher in ~/.local/bin
# - creates autostart in ~/.config/autostart
# - configures offline / SMIL hub mode
# - logs output to ~/.brilliantSignage.log
# ============================================================

set -euo pipefail

APP_NAME="pl.afya.brilliantSignage"
DEFAULT_JAR="$HOME/brilliantSignage.jar"
DEFAULT_WORKDIR="$HOME/brilliantSignage"
AUTOSTART_DIR="$HOME/.config/autostart"
BIN_DIR="$HOME/.local/bin"
LAUNCHER="$BIN_DIR/brilliant-signage-start.sh"
DESKTOP_FILE="$AUTOSTART_DIR/${APP_NAME}.desktop"
LOG_FILE="$HOME/.brilliantSignage.log"

ask_default() {
    local prompt="$1"
    local default_value="$2"
    local value
    read -r -p "$prompt [$default_value]: " value
    echo "${value:-$default_value}"
}

ask_yes_no_default_yes() {
    local prompt="$1"
    local value
    read -r -p "$prompt [T/n]: " value
    value="${value:-T}"
    [[ "$value" =~ ^[TtYy]$ ]]
}

ask_yes_no_default_no() {
    local prompt="$1"
    local value
    read -r -p "$prompt [t/N]: " value
    value="${value:-N}"
    [[ "$value" =~ ^[TtYy]$ ]]
}

is_valid_uuid() {
    local value="$1"
    [[ "$value" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}

echo "=== brilliantSignage installer ==="
echo

if ! command -v java >/dev/null 2>&1; then
    echo "Java not found. Install:"
    echo "sudo apt install default-jre"
    exit 1
fi

JAR_PATH="$(ask_default "Path to brilliantSignage.jar" "$DEFAULT_JAR")"

if [[ ! -f "$JAR_PATH" ]]; then
    echo "File not found:"
    echo "$JAR_PATH"
    exit 1
fi

WORKDIR="$(ask_default "Working directory (--directory)" "$DEFAULT_WORKDIR")"
mkdir -p "$WORKDIR"

SCREEN_INDEX="$(ask_default "Screen index (--screen)" "0")"
SHOW_TIME="$(ask_default "Image display time (--show-time)" "15")"
PROGRESS_H="$(ask_default "Progress height (--progress-h)" "5")"
PROGRESS_COLOR="$(ask_default "Progress color (--progress-color)" "#000000")"

PREVENT_SLEEP="false"
if ask_yes_no_default_no "Enable prevent sleep?"; then
    PREVENT_SLEEP="true"
fi

MODE="offline"
SMIL_HUB=""
SMIL_PLAYER_NAME=""
SMIL_UUID=""
SMIL_REFRESH=""
SMIL_DEBUG="false"

echo
if ask_yes_no_default_yes "Run offline mode?"; then
    MODE="offline"
else
    if ask_yes_no_default_yes "Connect to SMIL hub?"; then
        MODE="hub"
        echo
        echo "Download SMIL hub:"
        echo "https://github.com/garlic-signage/garlic-hub/"
        echo
        SMIL_HUB="$(ask_default "SMIL hub URL" "http://192.168.1.30")"

        if ask_yes_no_default_no "Set player name?"; then
            read -r -p "Player name: " SMIL_PLAYER_NAME
        fi

        if ask_yes_no_default_no "Set custom SMIL UUID for this run?"; then
            read -r -p "SMIL UUID: " SMIL_UUID
            if [[ -z "$SMIL_UUID" ]]; then
                echo "SMIL UUID cannot be empty." >&2
                exit 1
            fi
            if ! is_valid_uuid "$SMIL_UUID"; then
                echo "Invalid SMIL UUID format. Use canonical UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" >&2
                exit 1
            fi
        fi

        if ask_yes_no_default_no "Set refresh seconds?"; then
            read -r -p "Refresh seconds: " SMIL_REFRESH
        fi

        if ask_yes_no_default_no "Enable SMIL debug?"; then
            SMIL_DEBUG="true"
        fi
    fi
fi

mkdir -p "$BIN_DIR" "$AUTOSTART_DIR"

CMD=(java -jar "$JAR_PATH")
CMD+=("--directory=$WORKDIR")
CMD+=("--screen=$SCREEN_INDEX")
CMD+=("--show-time=$SHOW_TIME")
CMD+=("--progress-h=$PROGRESS_H")
CMD+=("--progress-color=$PROGRESS_COLOR")

if [[ "$PREVENT_SLEEP" == "true" ]]; then
    CMD+=("--prevent-sleep=true")
fi

if [[ "$MODE" == "hub" ]]; then
    CMD+=("--smil-hub=$SMIL_HUB")
    [[ -n "$SMIL_PLAYER_NAME" ]] && CMD+=("--smil-player-name=$SMIL_PLAYER_NAME")
    [[ -n "$SMIL_UUID" ]] && CMD+=("--smil-uuid=$SMIL_UUID")
    [[ -n "$SMIL_REFRESH" ]] && CMD+=("--smil-refresh-seconds=$SMIL_REFRESH")
    [[ "$SMIL_DEBUG" == "true" ]] && CMD+=("--smil-debug")
fi

{
    echo '#!/usr/bin/env bash'
    echo 'set -euo pipefail'
    echo 'export DISPLAY=${DISPLAY:-:0}'
    echo 'export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}'
    echo 'cd "$HOME"'
    echo 'sleep 8'
    printf 'exec '
    printf '%q ' "${CMD[@]}"
    printf '>> %q 2>&1\n' "$LOG_FILE"
} > "$LAUNCHER"

chmod +x "$LAUNCHER"

cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
Exec=$LAUNCHER
Terminal=false
X-GNOME-Autostart-enabled=true
EOF

chmod 644 "$DESKTOP_FILE"

echo
echo "DONE"
echo "Launcher: $LAUNCHER"
echo "Autostart: $DESKTOP_FILE"
echo "Log: $LOG_FILE"
echo
echo "Test run:"
echo "$LAUNCHER"
