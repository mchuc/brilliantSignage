import interfaces.ISettings;
import common.Player;
import common.Settings;
import common.SleepBlocker;
import common.SleepBlockerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class main {

    public static ISettings Settings = new Settings();

    public static void main(String[] args) {
        boolean showHelp = false;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                showHelp = true;
                break;
            }
        }

        if (showHelp) {
            printHelp();
            return;
        }

        String smilHub = null;
        String smilPlayerName = null;
        String smilUuidOverride = null;
        boolean smilDebug = false;
        boolean smilResetUuid = false;
        int smilRefreshSeconds = -1;
        int rotateTimeSeconds = 15;
        int progressHeightPx = 5;
        String progressColorHex = "#000000";
        boolean smilModeEnabled = false;
        boolean preventSleep = false;

        //start information
        System.out.println("***********");
        System.out.println("pl.afya.brilliantSignage");
        System.out.println("***********");
        System.out.println("Starting...");
        System.out.println("Detecting screens...");
        for (int i = 0; i < Settings.getScreensAvailable().length; i++) {
            System.out.println("Screen " + i + " detected: WxH: " + Settings.getScreensAvailable()[i].getWidth() + "x" + Settings.getScreensAvailable()[i].getHeight());
        }

        //args
        for (int i = 0; i < args.length; i++) {
            //screen to display
            if (args[i].startsWith("--screen=")) {
                try {
                    Settings.setScreenCurrent(Integer.parseInt(args[i].substring("--screen=".length())));
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid screen number provided in arguments: " + args[i]);
                    System.out.println("Available screens: " + Settings.getScreensAvailable().length);
                }
            }
            //working directory
            if (args[i].startsWith("--directory=")) {
                try {
                    Settings.setDirectory(args[i].substring("--directory=".length()));
                } catch (IllegalArgumentException e) {
                    System.out.println("Invalid directory provided in arguments: " + args[i]);
                    System.out.println("Using default directory instead.");
                } catch (IllegalStateException e) {
                    System.out.println("Failed to create cache directory");
                }
            }
            //smil hub address
            if (args[i].startsWith("--smil-hub=")) {
                String value = args[i].substring("--smil-hub=".length()).trim();
                if (!value.isEmpty()) {
                    smilHub = value;
                }
            }
            if (args[i].startsWith("--smil-player-name=")) {
                String value = args[i].substring("--smil-player-name=".length()).trim();
                if (!value.isEmpty()) {
                    smilPlayerName = value;
                }
            }
            if (args[i].startsWith("--smil-uuid=")) {
                String value = args[i].substring("--smil-uuid=".length()).trim();
                if (!value.isEmpty()) {
                    smilUuidOverride = value;
                }
            }
            if ("--smil-reset-uuid".equals(args[i])) {
                smilResetUuid = true;
            }
            //smil debug logs
            if ("--smil-debug".equals(args[i])) {
                smilDebug = true;
            }
            if (args[i].startsWith("--smil-debug=")) {
                String value = args[i].substring("--smil-debug=".length()).trim().toLowerCase();
                smilDebug = "1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value);
            }
            if (args[i].startsWith("--smil-refresh-seconds=")) {
                String value = args[i].substring("--smil-refresh-seconds=".length()).trim();
                try {
                    smilRefreshSeconds = Math.max(0, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    smilRefreshSeconds = -1;
                }
            }
            if (args[i].startsWith("--rotate-time=")) {
                String value = args[i].substring("--rotate-time=".length()).trim();
                try {
                    rotateTimeSeconds = Math.max(1, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    rotateTimeSeconds = 15;
                }
            }
            if (args[i].startsWith("--show-time=")) {
                String value = args[i].substring("--show-time=".length()).trim();
                try {
                    rotateTimeSeconds = Math.max(1, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    rotateTimeSeconds = 15;
                }
            }
            if (args[i].startsWith("--progress-h=")) {
                String value = args[i].substring("--progress-h=".length()).trim();
                try {
                    int parsed = Integer.parseInt(value);
                    progressHeightPx = Math.max(0, Math.min(20, parsed));
                } catch (NumberFormatException ignored) {
                    progressHeightPx = 5;
                }
            }
            if (args[i].startsWith("--progress-color=")) {
                String value = args[i].substring("--progress-color=".length()).trim();
                if (!value.isEmpty()) {
                    progressColorHex = value;
                }
            }
            if ("--prevent-sleep".equals(args[i])) {
                preventSleep = true;
            }
            if (args[i].startsWith("--prevent-sleep=")) {
                String value = args[i].substring("--prevent-sleep=".length()).trim();
                preventSleep = parseBooleanArg(value);
            }
        }


        if (smilResetUuid) {
            try {
                String resetUuid = Settings.resetSMILUuid();
                System.out.println("SMIL UUID reset in config.json: " + resetUuid);
            } catch (IllegalStateException e) {
                System.out.println("Failed to reset SMIL UUID: " + e.getMessage());
            }
        }
        if (smilUuidOverride != null) {
            try {
                Settings.setSMILUuidOverride(smilUuidOverride);
                System.out.println("SMIL UUID override: " + smilUuidOverride);
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid --smil-uuid value: " + smilUuidOverride);
            }
        }

        System.out.println("Selecting screen " + Settings.getScreenCurrent() + "...");
        System.out.println("Working directory: " + Settings.getDirectory());
        System.out.println("Rotate time: " + rotateTimeSeconds + "s");
        System.out.println("Progress bar height: " + progressHeightPx + "px");
        System.out.println("Progress bar color: " + progressColorHex);
        System.out.println("Prevent sleep: " + (preventSleep ? "enabled" : "disabled"));

        if (smilHub == null) {
            cleanupSmilPlaylistManifest(Settings.getDirectory());
        }

        if (smilHub != null) {
            smilModeEnabled = true;
            try {
                Settings.setSMILDebug(smilDebug);
                Settings.setSMILPlayerName(smilPlayerName);
                System.out.println("Using SMIL hub: " + smilHub);
                Settings.setSMILHost(smilHub);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // --smil-hub should be silent by default.
            }

            final boolean useSmilXmlRefresh = smilRefreshSeconds < 0;
            final int fixedRefreshSeconds = smilRefreshSeconds;
            if (useSmilXmlRefresh || smilRefreshSeconds > 0) {
                final String finalSmilHub = smilHub;
                Thread refreshThread = new Thread(() -> {
                    while (true) {
                        try {
                            int refreshIntervalSeconds = useSmilXmlRefresh
                                    ? Math.max(1, Settings.getSMILRecommendedRefreshSeconds())
                                    : fixedRefreshSeconds;
                            Thread.sleep(refreshIntervalSeconds * 1000L);
                            Settings.setSMILHost(finalSmilHub);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (IllegalArgumentException | IllegalStateException ignored) {
                            // Keep silent and retry on next interval.
                        }
                    }
                }, "smil-refresh");
                refreshThread.setDaemon(true);
                refreshThread.start();
            }
        }

        if (preventSleep) {
            SleepBlocker sleepBlocker = SleepBlockerFactory.createForCurrentOs();
            if (sleepBlocker.start()) {
                System.out.println("Prevent sleep enabled (display + system), backend: " + sleepBlocker.getBackendName());
                Runtime.getRuntime().addShutdownHook(new Thread(sleepBlocker::stop, "sleep-blocker-shutdown"));
            } else {
                System.out.println("Prevent sleep requested but unavailable on this system.");
            }
        }

        Player player = new Player(
                Settings.getScreenCurrent(),
                Settings.getScreenWidth(),
                Settings.getScreenHeight(),
                Settings.getDirectory(),
                rotateTimeSeconds,
                smilModeEnabled,
                progressHeightPx,
                progressColorHex
        );
        player.start();
    }

    private static void printHelp() {
        System.out.println("pl.afya.brilliantSignage - options:");
        System.out.println("  --help, -h");
        System.out.println("      Show this help and exit.");
        System.out.println("  --screen=<index>");
        System.out.println("      Select display index.");
        System.out.println("  --directory=<path>");
        System.out.println("      Working directory. CACHE is <path>/cache.");
        System.out.println("  --rotate-time=<seconds>");
        System.out.println("      Rotate local CACHE playlist every N seconds (default: 15).\n"
                + "      Deprecated alias of --show-time.");
        System.out.println("  --show-time=<seconds>");
        System.out.println("      Duration for each static image in CACHE mode (default: 15).\n"
                + "      Static videos try to use real duration via ffprobe.");
        System.out.println("  --progress-h=<px>");
        System.out.println("      Image progress bar height in pixels (default: 5, range: 0..20).\n"
                + "      0 hides progress bar.");
        System.out.println("  --progress-color=<#RRBBGG|#RRBBGGAA>");
        System.out.println("      Image progress bar color (default: #000000).\n"
                + "      Supports 6/8-hex plus short forms (3/4).\n"
                + "      Channel order: RRBBGG[AA].");
        System.out.println("  --prevent-sleep[=on|off|true|false|1|0]");
        System.out.println("      Prevent display sleep and system sleep while app is running.\n"
                + "      Supported backends: Windows and Linux (systemd-inhibit, Wayland fallback gnome-session-inhibit/dbus-send, X11 fallback xset rescue).\n"
                + "      No anti-sleep background worker starts unless prevent-sleep is enabled.");
        System.out.println("  --smil-hub=<http(s)://host[:port]>");
        System.out.println("      Enable SMIL sync from hub (/smil-index).");
        System.out.println("  --smil-player-name=<name>");
        System.out.println("      Override default player name (default: brilliantSignage-<IP>).\n"
                + "      Used in Signage-Agent NAME and in query parameter player=.\n"
                + "      Player UUID is persistent in <working-directory>/config.json.");
        System.out.println("  --smil-uuid=<uuid>");
        System.out.println("      Override SMIL UUID only for current run (does not modify config.json).");
        System.out.println("  --smil-reset-uuid");
        System.out.println("      Service option. Generate and persist a new UUID in <working-directory>/config.json.");
        System.out.println("  --smil-debug[=true,false,1,0,yes,on]");
        System.out.println("      Enable/disable SMIL debug output.");
        System.out.println("  --smil-refresh-seconds=<seconds>");
        System.out.println("      Override SMIL refresh interval.\n"
                + "      0 = disable background refresh,\n"
                + "      not set = use <meta http-equiv=\"Refresh\" content=\"N\"> from SMIL (fallback 60).");
    }

    private static void cleanupSmilPlaylistManifest(String cacheDirectory) {
        Path manifestPath = Paths.get(cacheDirectory).resolve(".smil-playlist.csv");
        try {
            Files.deleteIfExists(manifestPath);
        } catch (IOException ignored) {
            // Cleanup is best-effort only.
        }
    }

    private static boolean parseBooleanArg(String value) {
        String normalized = value.trim().toLowerCase();
        return "1".equals(normalized)
                || "true".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }
}
