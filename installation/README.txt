# brilliantSignage scripts pack

Files:
- install-signage.sh — interactive installer
- install-signage-enterprise.sh — non-interactive / enterprise installer

## EN

### Interactive
```bash
chmod +x install-signage.sh
./install-signage.sh
```

### Enterprise offline
```bash
chmod +x install-signage-enterprise.sh
./install-signage-enterprise.sh \
  --jar="$HOME/brilliantSignage.jar" \
  --mode=offline \
  --directory="$HOME/brilliantSignage" \
  --screen=0 \
  --show-time=15 \
  --progress-h=5 \
  --progress-color="#000000" \
  --prevent-sleep=true \
  --force=true
```

### Enterprise hub
```bash
./install-signage-enterprise.sh \
  --jar="$HOME/brilliantSignage.jar" \
  --mode=hub \
  --directory="$HOME/brilliantSignage" \
  --screen=0 \
  --show-time=15 \
  --progress-h=5 \
  --progress-color="#000000" \
  --prevent-sleep=true \
  --smil-hub="http://192.168.1.30" \
  --smil-player-name="$(hostname)" \
  --smil-uuid="123e4567-e89b-12d3-a456-426614174000" \
  --smil-refresh-seconds=30 \
  --smil-debug=false \
  --kiosk=true \
  --force=true
```

`--smil-uuid` is optional. If not provided, app uses existing UUID from config or generates one automatically.

## PL

### Wersja interaktywna
```bash
chmod +x install-signage.sh
./install-signage.sh
```

### Wersja enterprise offline
```bash
chmod +x install-signage-enterprise.sh
./install-signage-enterprise.sh \
  --jar="$HOME/brilliantSignage.jar" \
  --mode=offline \
  --directory="$HOME/brilliantSignage" \
  --screen=0 \
  --show-time=15 \
  --progress-h=5 \
  --progress-color="#000000" \
  --prevent-sleep=true \
  --force=true
```

### Wersja enterprise hub
```bash
./install-signage-enterprise.sh \
  --jar="$HOME/brilliantSignage.jar" \
  --mode=hub \
  --directory="$HOME/brilliantSignage" \
  --screen=0 \
  --show-time=15 \
  --progress-h=5 \
  --progress-color="#000000" \
  --prevent-sleep=true \
  --smil-hub="http://192.168.1.30" \
  --smil-player-name="$(hostname)" \
  --smil-uuid="123e4567-e89b-12d3-a456-426614174000" \
  --smil-refresh-seconds=30 \
  --smil-debug=false \
  --kiosk=true \
  --force=true
```

`--smil-uuid` jest opcjonalny. Jeśli go nie podasz, aplikacja użyje UUID z configu lub wygeneruje nowy automatycznie.

