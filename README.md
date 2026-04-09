# brilliantSignage

easy to start, easy to manage

Java signage player with two modes:
- Static CACHE playback
- SMIL hub sync (`/smil-index`)

## Get Source from GitHub

If you download repository/archive from GitHub:

```zsh
git clone https://github.com/mchuc/brilliantSignage.git
cd brilliantSignage/java/pl.afya.brilliantSignage
```

Or for ZIP archive, unpack and enter the same folder:

```zsh
cd brilliantSignage/java/pl.afya.brilliantSignage
```

## Build and Run JAR

```zsh
mkdir -p out
javac -d out src/main.java src/common/*.java src/interfaces/*.java
jar --create --file brilliantSignage.jar --main-class main -C out .
java -jar brilliantSignage.jar --help
```

## CLI Options

- `--help`, `-h`
- `--screen=<index>`
- `--directory=<path>`
- `--show-time=<seconds>`
- `--rotate-time=<seconds>` (alias, deprecated)
- `--progress-h=<px>`
- `--progress-color=<#RRBBGG|#RRBBGGAA>`
- `--prevent-sleep[=on|off|true|false|1|0]`
- `--smil-hub=<http(s)://host[:port]>`
- `--smil-player-name=<name>`
- `--smil-uuid=<uuid>`
- `--smil-reset-uuid`
- `--smil-debug[=true|false|1|0|yes|on]`
- `--smil-refresh-seconds=<seconds>`

`--prevent-sleep` keeps both display and system awake while app is running.
Current backends: Windows and Linux (`systemd-inhibit`, Wayland fallback `gnome-session-inhibit`/`dbus-send`, X11 fallback `xset` rescue).
No anti-sleep background worker is started unless `--prevent-sleep` is enabled.

## Mode 1: Static CACHE Playback

Without `--smil-hub`, player loops files from CACHE:
- `.jpg`, `.jpeg`, `.png`, `.mov`, `.mp4`

Examples:

```zsh
java -jar brilliantSignage.jar --directory="/path/to/workdir" --show-time=15
```

Behavior in static mode:
- You can choose display with `--screen=<index>`
- Content is automatically fitted to the selected screen dimensions
- Images: displayed for `--show-time`
- Image progress bar at the bottom of displayed image:
  - `--progress-h=5` by default
  - `--progress-h=0` hides progress
  - max height is `20px`
  - `--progress-color=#000000` by default (black)
  - supported short forms: `#RBG` and `#RBGA`
  - channel order is `RRBBGG[AA]`
- Videos: tries real video duration via `ffprobe`; if unavailable, falls back to `--show-time`
- If running without `--smil-hub`, old `cache/.smil-playlist.csv` is removed automatically

## Mode 2: SMIL Hub Sync

```zsh
java -jar brilliantSignage.jar --smil-hub="http://host:port"
```

Behavior:
- Fetches `/smil-index`
- Parses media URLs and `dur` from SMIL XML
- Downloads/updates files in CACHE
- Saves sync details to `cache/smil-sync.log`
- Stores playlist timing manifest in `cache/.smil-playlist.csv`
- Playback duration for each item follows SMIL XML duration (`dur`)
- Existing `cache/.smil-playlist.csv` is kept in hub mode, also when hub connection is temporarily unavailable

Override player identity name:

```zsh
java -jar brilliantSignage.jar --smil-hub="http://host:port" --smil-player-name="MyPlayer-01"
```

SMIL instance UUID is persistent and is stored in working directory:
- default working directory: `~/brilliantSignage/config.json` (`playerUuid`)
- custom working directory (`--directory=/path/to/workdir`): `/path/to/workdir/config.json`

`config.json` stores also machine fingerprint. If this config is copied to another computer,
player detects mismatch and auto-generates a new UUID, so each machine is registered as separate device.

`--smil-player-name` changes display name only; UUID stays stable unless config is deleted.

Service options:
- `--smil-uuid=<uuid>`: override UUID only for current run (runtime only)
- `--smil-reset-uuid`: generate and persist a new UUID in `config.json`

## SMIL Refresh Interval

- If `--smil-refresh-seconds` is not set: use SMIL XML meta refresh (`<meta http-equiv="Refresh" content="N" />`), fallback `60`
- `--smil-refresh-seconds=0`: disable background SMIL refresh
- `--smil-refresh-seconds=<N>`: force refresh every N seconds

Example:

```zsh
java -jar brilliantSignage.jar --smil-hub="http://host:port" --smil-refresh-seconds=30 --smil-debug
```

