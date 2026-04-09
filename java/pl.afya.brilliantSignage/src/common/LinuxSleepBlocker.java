package common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinuxSleepBlocker implements SleepBlocker {

    private static final long RESCUE_TICK_MS = 20000L;

    private Process inhibitProcess;
    private Process waylandInhibitProcess;
    private Thread rescueThread;
    private volatile boolean rescueRunning;
    private volatile String activeBackendName = "linux-unsupported";
    private volatile long waylandDbusCookie = -1L;

    @Override
    public synchronized boolean start() {
        if (inhibitProcess != null && inhibitProcess.isAlive()) {
            activeBackendName = "linux-systemd-inhibit";
            return true;
        }

        if (waylandInhibitProcess != null && waylandInhibitProcess.isAlive()) {
            activeBackendName = "linux-wayland-gnome-session-inhibit";
            return true;
        }

        if (waylandDbusCookie > 0) {
            activeBackendName = "linux-wayland-dbus-screensaver";
            return true;
        }

        if (rescueThread != null && rescueThread.isAlive()) {
            activeBackendName = "linux-xset-rescue";
            return true;
        }

        if (startPrimaryInhibit()) {
            activeBackendName = "linux-systemd-inhibit";
            return true;
        }

        if (startWaylandFallback()) {
            activeBackendName = "linux-wayland-gnome-session-inhibit";
            return true;
        }

        if (startWaylandDbusFallback()) {
            activeBackendName = "linux-wayland-dbus-screensaver";
            return true;
        }

        if (startRescueFallback()) {
            activeBackendName = "linux-xset-rescue";
            return true;
        }

        activeBackendName = "linux-unsupported";
        return false;
    }

    private boolean startPrimaryInhibit() {
        if (!hasCommand("systemd-inhibit")) {
            return false;
        }

        try {
            inhibitProcess = new ProcessBuilder(
                    "systemd-inhibit",
                    "--what=idle:sleep",
                    "--who=brilliantSignage",
                    "--why=Signage playback",
                    "sh",
                    "-c",
                    "while :; do sleep 3600; done"
            ).start();
            return inhibitProcess.isAlive();
        } catch (IOException ignored) {
            inhibitProcess = null;
            return false;
        }
    }

    private boolean startRescueFallback() {
        if (!isX11Available() || !hasCommand("xset")) {
            return false;
        }

        rescueRunning = true;
        rescueThread = new Thread(() -> {
            while (rescueRunning) {
                applyRescueState();
                try {
                    Thread.sleep(RESCUE_TICK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "linux-sleep-rescue");
        rescueThread.setDaemon(true);
        rescueThread.start();
        return true;
    }

    private boolean startWaylandFallback() {
        if (!isWaylandSession() || !hasCommand("gnome-session-inhibit")) {
            return false;
        }

        try {
            waylandInhibitProcess = new ProcessBuilder(
                    "gnome-session-inhibit",
                    "--inhibit",
                    "idle,suspend",
                    "--reason",
                    "Signage playback",
                    "sh",
                    "-c",
                    "while :; do sleep 3600; done"
            ).start();
            return waylandInhibitProcess.isAlive();
        } catch (IOException ignored) {
            waylandInhibitProcess = null;
            return false;
        }
    }

    private boolean startWaylandDbusFallback() {
        if (!isWaylandSession() || !hasCommand("dbus-send")) {
            return false;
        }
        long cookie = requestScreenSaverInhibitCookie();
        if (cookie <= 0) {
            return false;
        }
        waylandDbusCookie = cookie;
        return true;
    }

    private long requestScreenSaverInhibitCookie() {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "dbus-send",
                    "--session",
                    "--print-reply",
                    "--dest=org.freedesktop.ScreenSaver",
                    "/ScreenSaver",
                    "org.freedesktop.ScreenSaver.Inhibit",
                    "string:brilliantSignage",
                    "string:Signage playback"
            ).start();

            String output = readProcessOutput(process);
            if (process.waitFor() != 0) {
                return -1L;
            }
            return parseDbusCookie(output);
        } catch (IOException e) {
            return -1L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void stopWaylandDbusFallback() {
        if (waylandDbusCookie <= 0 || !hasCommand("dbus-send")) {
            waylandDbusCookie = -1L;
            return;
        }

        Process process = null;
        try {
            process = new ProcessBuilder(
                    "dbus-send",
                    "--session",
                    "--print-reply",
                    "--dest=org.freedesktop.ScreenSaver",
                    "/ScreenSaver",
                    "org.freedesktop.ScreenSaver.UnInhibit",
                    "uint32:" + waylandDbusCookie
            ).start();
            process.waitFor();
        } catch (IOException e) {
            // Best-effort only.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            waylandDbusCookie = -1L;
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private long parseDbusCookie(String output) {
        Pattern p = Pattern.compile("uint(?:32|64)\\s+([0-9]+)");
        Matcher m = p.matcher(output == null ? "" : output);
        if (!m.find()) {
            return -1L;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void applyRescueState() {
        runShell("xset s off -dpms");
        runShell("xset s noblank");
        if (hasCommand("xdg-screensaver")) {
            runShell("xdg-screensaver reset");
        }
    }

    @Override
    public synchronized void stop() {
        if (inhibitProcess != null) {
            inhibitProcess.destroy();
            inhibitProcess = null;
        }

        if (waylandInhibitProcess != null) {
            waylandInhibitProcess.destroy();
            waylandInhibitProcess = null;
        }

        stopWaylandDbusFallback();

        stopRescueFallback();

        activeBackendName = "linux-unsupported";
    }

    private synchronized void stopRescueFallback() {
        rescueRunning = false;
        if (rescueThread != null) {
            rescueThread.interrupt();
            rescueThread = null;
        }
        if (isX11Available() && hasCommand("xset")) {
            // Best effort restore of default screen saver and DPMS behavior.
            runShell("xset s default");
            runShell("xset +dpms");
        }
    }

    private boolean hasCommand(String command) {
        Process probe = null;
        try {
            probe = new ProcessBuilder("sh", "-c", "command -v " + command + " >/dev/null 2>&1").start();
            return probe.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (probe != null) {
                probe.destroy();
            }
        }
    }

    private boolean isX11Available() {
        String display = System.getenv("DISPLAY");
        if (display == null || display.trim().isEmpty()) {
            return false;
        }
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        return sessionType == null || !"wayland".equals(sessionType.toLowerCase(Locale.ROOT));
    }

    private boolean isWaylandSession() {
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        return sessionType != null && "wayland".equals(sessionType.toLowerCase(Locale.ROOT));
    }

    private void runShell(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", command).start();
            process.waitFor();
        } catch (IOException e) {
            // Best-effort only.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public boolean isSupported() {
        return hasCommand("systemd-inhibit")
                || (isWaylandSession() && hasCommand("gnome-session-inhibit"))
                || (isWaylandSession() && hasCommand("dbus-send"))
                || (isX11Available() && hasCommand("xset"));
    }

    @Override
    public String getBackendName() {
        return activeBackendName;
    }
}





