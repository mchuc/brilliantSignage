#!/usr/bin/env bash

# ============================================================
# brilliantSignage - enterprise autostart installer
#
# Author: Marcin Chuć
# Version: 0.0.2
#
# EN:
# Enterprise installer for brilliantSignage.
# Non-interactive mode, configurable by CLI arguments.
# Can be deployed on many Linux desktops via SSH, Ansible,
# provisioning scripts or image templates.
#
# PL:
# Instalator enterprise dla brilliantSignage.
# Działa bez pytań interaktywnych, wszystko przez argumenty CLI.
# Nadaje się do wdrażania na wielu komputerach przez SSH,
# Ansible, skrypty provisioningowe lub obrazy systemu.
#
# ------------------------------------------------------------
# BASIC USAGE / PODSTAWOWE UŻYCIE
#
# 1. Copy brilliantSignage.jar to user home directory, e.g.:
#    /home/USER/brilliantSignage.jar
#
# 2. Copy this script as:
#    /home/USER/install-signage-enterprise.sh
#
# 3. Make executable:
#    chmod +x /home/USER/install-signage-enterprise.sh
#
# 4. Run example:
#    ./install-signage-enterprise.sh \
#      --jar=/home/USER/brilliantSignage.jar \
#      --mode=offline \
#      --directory=/home/USER/brilliantSignage \
#      --screen=0 \
#      --show-time=15 \
#      --progress-h=5 \
#      --progress-color="#000000" \
#      --prevent-sleep=true
#
# Example SMIL:
#    ./install-signage-enterprise.sh \
#      --jar=/home/USER/brilliantSignage.jar \
#      --mode=hub \
#      --smil-hub=http://192.168.1.30 \
#      --smil-player-name=Player-01 \
#      --smil-refresh-seconds=30 \
#      --smil-debug=true
#
# Optional kiosk mode:
#      --kiosk=true
#
# Creates:
# - launcher in ~/.local/bin
# - autostart in ~/.config/autostart
# - optional kiosk helper in ~/.config
#
# Logs:
# - ~/.brilliantSignage.log
# ============================================================

set -euo pipefail

APP_NAME="pl.afya.brilliantSignage"
APP_LABEL="brilliantSignage"
DEFAULT_JAR="$HOME/brilliantSignage.jar"
DEFAULT_WORKDIR="$HOME/brilliantSignage"
AUTOSTART_DIR="$HOME/.config/autostart"
BIN_DIR="$HOME/.local/bin"
CONFIG_DIR="$HOME/.config/$APP_LABEL"
LAUNCHER="$BIN_DIR/brilliant-signage-start.sh"
DESKTOP_FILE="$AUTOSTART_DIR/${APP_NAME}.desktop"
LOG_FILE="$HOME/.brilliantSignage.log"
KIOSK_SCRIPT="$CONFIG_DIR/kiosk-prepare.sh"

JAR_PATH="$DEFAULT_JAR"
MODE="offline"
WORKDIR="$DEFAULT_WORKDIR"
SCREEN_INDEX="0"
SHOW_TIME="15"
PROGRESS_H="5"
PROGRESS_COLOR="#000000"
PREVENT_SLEEP="false"
SMIL_HUB=""
SMIL_PLAYER_NAME=""
SMIL_UUID=""
SMIL_REFRESH=""
SMIL_DEBUG="false"
KIOSK="false"
INSTALL_AUTOSTART="true"
FORCE="false"

usage() {
    cat <<EOF
Usage:
  $0 [options]

Required:
  --jar=PATH                       Path to brilliantSignage.jar

Optional:
  --mode=offline|hub              Runtime mode (default: offline)
  --directory=PATH                Working directory (default: ~/brilliantSignage)
  --screen=N                      Screen index (default: 0)
  --show-time=N                   Image display time in seconds (default: 15)
  --progress-h=N                  Progress bar height in px (default: 5)
  --progress-color=VALUE          Progress color (default: #000000)
  --prevent-sleep=true|false|y|t|n  Enable prevent sleep (default: false)

SMIL hub options:
  --smil-hub=URL                  SMIL hub URL, required when --mode=hub
  --smil-player-name=NAME         Optional SMIL player name
  --smil-uuid=UUID                Optional SMIL UUID override for current run
                                   If not set, app uses/generates UUID automatically
  --smil-refresh-seconds=N        Optional forced refresh interval
  --smil-debug=true|false|y|t|n   Enable SMIL debug (default: false)

Deployment options:
  --kiosk=true|false|y|t|n        Enable kiosk desktop preparation (default: false)
  --install-autostart=true|false|y|t|n  Create autostart desktop entry (default: true)
  --force=true|false|y|t|n        Overwrite existing files (default: false)

Other:
  --help                          Show this help

Examples:
  $0 --jar=\$HOME/brilliantSignage.jar --mode=offline

  $0 --jar=\$HOME/brilliantSignage.jar \
     --mode=hub \
     --smil-hub=http://192.168.1.30 \
     --smil-player-name=FrontDesk-01 \
     --smil-refresh-seconds=30 \
     --smil-debug=true \
     --kiosk=true
EOF
}

bool_normalize() {
    case "${1,,}" in
        true|y|t) echo "true" ;;
        false|n) echo "false" ;;
        *)
            echo "Invalid boolean value: $1 (allowed: true|false|y|t|n)" >&2
            exit 1
            ;;
    esac
}

is_valid_uuid() {
    local value="$1"
    [[ "$value" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}

require_cmd() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd" >&2
        exit 1
    fi
}

write_file_if_allowed() {
    local target="$1"
    if [[ -e "$target" && "$FORCE" != "true" ]]; then
        echo "File exists, use --force=true to overwrite: $target" >&2
        exit 1
    fi
}

for arg in "$@"; do
    case "$arg" in
        --jar=*) JAR_PATH="${arg#*=}" ;;
        --mode=*) MODE="${arg#*=}" ;;
        --directory=*) WORKDIR="${arg#*=}" ;;
        --screen=*) SCREEN_INDEX="${arg#*=}" ;;
        --show-time=*) SHOW_TIME="${arg#*=}" ;;
        --progress-h=*) PROGRESS_H="${arg#*=}" ;;
        --progress-color=*) PROGRESS_COLOR="${arg#*=}" ;;
        --prevent-sleep=*) PREVENT_SLEEP="$(bool_normalize "${arg#*=}")" ;;
        --smil-hub=*) SMIL_HUB="${arg#*=}" ;;
        --smil-player-name=*) SMIL_PLAYER_NAME="${arg#*=}" ;;
        --smil-uuid=*) SMIL_UUID="${arg#*=}" ;;
        --smil-refresh-seconds=*) SMIL_REFRESH="${arg#*=}" ;;
        --smil-debug=*) SMIL_DEBUG="$(bool_normalize "${arg#*=}")" ;;
        --kiosk=*) KIOSK="$(bool_normalize "${arg#*=}")" ;;
        --install-autostart=*) INSTALL_AUTOSTART="$(bool_normalize "${arg#*=}")" ;;
        --force=*) FORCE="$(bool_normalize "${arg#*=}")" ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            usage
            exit 1
            ;;
    esac
done

require_cmd java

case "$MODE" in
    offline|hub) ;;
    *)
        echo "Invalid --mode. Allowed: offline, hub" >&2
        exit 1
        ;;
esac

if [[ ! -f "$JAR_PATH" ]]; then
    echo "JAR file not found: $JAR_PATH" >&2
    exit 1
fi

if [[ "$MODE" == "hub" && -z "$SMIL_HUB" ]]; then
    echo "When --mode=hub, --smil-hub=URL is required." >&2
    echo "Garlic Hub: https://github.com/garlic-signage/garlic-hub/" >&2
    exit 1
fi

if [[ -n "$SMIL_UUID" ]] && ! is_valid_uuid "$SMIL_UUID"; then
    echo "Invalid --smil-uuid format. Use canonical UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" >&2
    exit 1
fi

mkdir -p "$WORKDIR"
mkdir -p "$AUTOSTART_DIR"
mkdir -p "$BIN_DIR"
mkdir -p "$CONFIG_DIR"

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

    if [[ -n "$SMIL_PLAYER_NAME" ]]; then
        CMD+=("--smil-player-name=$SMIL_PLAYER_NAME")
    fi

    if [[ -n "$SMIL_UUID" ]]; then
        CMD+=("--smil-uuid=$SMIL_UUID")
    fi

    if [[ -n "$SMIL_REFRESH" ]]; then
        CMD+=("--smil-refresh-seconds=$SMIL_REFRESH")
    fi

    if [[ "$SMIL_DEBUG" == "true" ]]; then
        CMD+=("--smil-debug")
    fi
fi

write_file_if_allowed "$LAUNCHER"
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

if [[ "$INSTALL_AUTOSTART" == "true" ]]; then
    write_file_if_allowed "$DESKTOP_FILE"
    cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
Comment=Autostart $APP_NAME
Exec=$LAUNCHER
Terminal=false
X-GNOME-Autostart-enabled=true
StartupNotify=false
EOF
    chmod 644 "$DESKTOP_FILE"
fi

if [[ "$KIOSK" == "true" ]]; then
    write_file_if_allowed "$KIOSK_SCRIPT"
    cat > "$KIOSK_SCRIPT" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

# GNOME desktop preparation for kiosk-like behavior.
# User-level settings only.

if command -v gsettings >/dev/null 2>&1; then
    # Disable screen blanking / lock
    gsettings set org.gnome.desktop.session idle-delay 0 || true
    gsettings set org.gnome.desktop.screensaver lock-enabled false || true
    gsettings set org.gnome.desktop.screensaver ubuntu-lock-on-suspend false || true

    # Hide desktop notifications if available
    gsettings set org.gnome.desktop.notifications show-banners false || true

    # Disable hot corner if available
    gsettings set org.gnome.desktop.interface enable-hot-corners false || true

    # Optional: hide dock auto-hide behavior tuning if extension exists
    gsettings set org.gnome.shell.extensions.dash-to-dock autohide true || true
    gsettings set org.gnome.shell.extensions.dash-to-dock dock-fixed false || true
fi

# Best-effort X11 screen saver disable
if command -v xset >/dev/null 2>&1; then
    xset s off || true
    xset -dpms || true
    xset s noblank || true
fi
EOF
    chmod +x "$KIOSK_SCRIPT"

    KIOSK_DESKTOP="$AUTOSTART_DIR/${APP_LABEL}-kiosk.desktop"
    write_file_if_allowed "$KIOSK_DESKTOP"
    cat > "$KIOSK_DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=${APP_NAME}.kiosk
Comment=Prepare kiosk session
Exec=$KIOSK_SCRIPT
Terminal=false
X-GNOME-Autostart-enabled=true
StartupNotify=false
EOF
    chmod 644 "$KIOSK_DESKTOP"
fi

echo
echo "========================================"
echo "brilliantSignage enterprise install done"
echo "========================================"
echo "App name:           $APP_NAME"
echo "Mode:               $MODE"
echo "JAR:                $JAR_PATH"
echo "Workdir:            $WORKDIR"
echo "Launcher:           $LAUNCHER"
echo "Autostart enabled:  $INSTALL_AUTOSTART"
[[ "$INSTALL_AUTOSTART" == "true" ]] && echo "Desktop file:       $DESKTOP_FILE"
echo "Kiosk mode:         $KIOSK"
[[ "$KIOSK" == "true" ]] && echo "Kiosk helper:       $KIOSK_SCRIPT"
echo "Log file:           $LOG_FILE"
if [[ "$MODE" == "hub" ]]; then
    echo "SMIL hub:           $SMIL_HUB"
    [[ -n "$SMIL_PLAYER_NAME" ]] && echo "SMIL player name:   $SMIL_PLAYER_NAME"
    [[ -n "$SMIL_UUID" ]] && echo "SMIL UUID override: $SMIL_UUID"
    [[ -n "$SMIL_REFRESH" ]] && echo "SMIL refresh:       $SMIL_REFRESH"
    echo "SMIL debug:         $SMIL_DEBUG"
fi
echo
echo "Manual test:"
echo "  $LAUNCHER"
echo
echo "Log preview:"
echo "  tail -f $LOG_FILE"
