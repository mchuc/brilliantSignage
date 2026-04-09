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
APP_LABEL="brilliantSignage"
DEFAULT_JAR="$HOME/brilliantSignage.jar"
DEFAULT_WORKDIR="$HOME/brilliantSignage"
AUTOSTART_DIR="$HOME/.config/autostart"
CONFIG_DIR="$HOME/.config/$APP_LABEL"
BIN_DIR="$HOME/.local/bin"
LAUNCHER="$BIN_DIR/brilliant-signage-start.sh"
DESKTOP_FILE="$AUTOSTART_DIR/${APP_NAME}.desktop"
KIOSK_SCRIPT="$CONFIG_DIR/kiosk-prepare.sh"
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
    while true; do
        read -r -p "$prompt [y/Y/t/T/n/N, Enter=T]: " value
        value="${value:-T}"
        case "$value" in
            [TtYy]) return 0 ;;
            [Nn]) return 1 ;;
            *) echo "Use y/Y/t/T or n/N. / Uzyj y/Y/t/T albo n/N." ;;
        esac
    done
}

ask_yes_no_default_no() {
    local prompt="$1"
    local value
    while true; do
        read -r -p "$prompt [y/Y/t/T/n/N, Enter=N]: " value
        value="${value:-N}"
        case "$value" in
            [TtYy]) return 0 ;;
            [Nn]) return 1 ;;
            *) echo "Use y/Y/t/T or n/N. / Uzyj y/Y/t/T albo n/N." ;;
        esac
    done
}

is_valid_uuid() {
    local value="$1"
    [[ "$value" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}

echo "=== brilliantSignage installer / instalator brilliantSignage ==="
echo

if ! command -v java >/dev/null 2>&1; then
    echo "Java not found / Nie znaleziono Java. Install / Zainstaluj:"
    echo "sudo apt install default-jre"
    exit 1
fi

JAR_PATH="$(ask_default "Path to brilliantSignage.jar / Sciezka do brilliantSignage.jar" "$DEFAULT_JAR")"

if [[ ! -f "$JAR_PATH" ]]; then
    echo "File not found / Nie znaleziono pliku:"
    echo "$JAR_PATH"
    exit 1
fi

WORKDIR="$(ask_default "Working directory (--directory) / Katalog roboczy (--directory)" "$DEFAULT_WORKDIR")"
mkdir -p "$WORKDIR"

SCREEN_INDEX="$(ask_default "Screen index (--screen) / Indeks ekranu (--screen)" "0")"
SHOW_TIME="$(ask_default "Image display time (--show-time) / Czas wyswietlania obrazu (--show-time)" "15")"
PROGRESS_H="$(ask_default "Progress height (--progress-h) / Wysokosc paska postepu (--progress-h)" "5")"
PROGRESS_COLOR="$(ask_default "Progress color (--progress-color) / Kolor paska postepu (--progress-color)" "#000000")"

PREVENT_SLEEP="false"
if ask_yes_no_default_no "Enable prevent sleep? / Wlaczyc zapobieganie usypianiu?"; then
    PREVENT_SLEEP="true"
fi

MODE="offline"
SMIL_HUB=""
SMIL_PLAYER_NAME=""
SMIL_UUID=""
SMIL_REFRESH=""
SMIL_DEBUG="false"
KIOSK="false"

echo
echo "Kiosk mode prepares desktop session for signage / Tryb kiosk przygotowuje sesje desktopowa pod signage:"
echo "- disables blanking/lock (GNOME),"
echo "- wylacza wygaszanie/blokade (GNOME),"
echo "- disables some notifications/hot-corners,"
echo "- wylacza czesc powiadomien/hot-corners,"
echo "- on X11 also disables screensaver/DPMS via xset."
echo "- na X11 dodatkowo wylacza screensaver/DPMS przez xset."
if ask_yes_no_default_no "Enable kiosk desktop preparation? / Wlaczyc przygotowanie trybu kiosk?"; then
    KIOSK="true"
fi

echo
if ask_yes_no_default_yes "Run offline mode? / Uruchomic tryb offline?"; then
    MODE="offline"
else
    if ask_yes_no_default_yes "Connect to SMIL hub? / Polaczyc z hubem SMIL?"; then
        MODE="hub"
        echo
        echo "Download SMIL hub / Pobierz hub SMIL:"
        echo "https://github.com/garlic-signage/garlic-hub/"
        echo
        SMIL_HUB="$(ask_default "SMIL hub URL / URL huba SMIL" "http://192.168.1.30")"

        if ask_yes_no_default_no "Set player name? / Ustawic nazwe playera?"; then
            read -r -p "Player name / Nazwa playera: " SMIL_PLAYER_NAME
        fi

        if ask_yes_no_default_no "Set custom SMIL UUID for this run? / Ustawic niestandardowy SMIL UUID dla tego uruchomienia?"; then
            read -r -p "SMIL UUID: " SMIL_UUID
            if [[ -z "$SMIL_UUID" ]]; then
                echo "SMIL UUID cannot be empty / SMIL UUID nie moze byc pusty." >&2
                exit 1
            fi
            if ! is_valid_uuid "$SMIL_UUID"; then
                echo "Invalid SMIL UUID format / Nieprawidlowy format SMIL UUID. Use canonical UUID / Uzyj formatu: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" >&2
                exit 1
            fi
        fi

        if ask_yes_no_default_no "Set refresh seconds? / Ustawic sekundy odswiezania?"; then
            read -r -p "Refresh seconds / Sekundy odswiezania: " SMIL_REFRESH
        fi

        if ask_yes_no_default_no "Enable SMIL debug? / Wlaczyc debug SMIL?"; then
            SMIL_DEBUG="true"
        fi
    fi
fi

mkdir -p "$BIN_DIR" "$AUTOSTART_DIR" "$CONFIG_DIR"

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

if [[ "$KIOSK" == "true" ]]; then
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
echo "DONE / GOTOWE"
echo "Launcher / Skrypt startowy: $LAUNCHER"
echo "Autostart / Autostart: $DESKTOP_FILE"
echo "Kiosk mode / Tryb kiosk: $KIOSK"
[[ "$KIOSK" == "true" ]] && echo "Kiosk helper / Pomocniczy skrypt kiosk: $KIOSK_SCRIPT"
echo "Log / Log: $LOG_FILE"
echo
echo "Test run / Testowe uruchomienie:"
echo "$LAUNCHER"
