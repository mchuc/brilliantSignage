package common;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Player {

    private static final long DEFAULT_ROTATE_MS = 15000L;

    private final int screenIndex;
    private final int screenWidth;
    private final int screenHeight;
    private final Path cacheDirectory;
    private final boolean smilMode;
    private final long imageDisplayMs;
    private final long videoPlaceholderMs;
    private final int progressHeightPx;
    private final Color progressColor;
    private final Map<Path, Long> videoDurationCache = new HashMap<>();

    private JFrame frame;
    private JLayeredPane layeredPane;
    private JLabel mediaLabel;
    private JPanel progressBar;
    private Cursor hiddenCursor;
    private Cursor defaultCursor;

    public Player(int screenIndex, int screenWidth, int screenHeight, String cacheDirectory, int rotateSeconds, boolean smilMode, int progressHeightPx, String progressColorHex) {
        this.screenIndex = screenIndex;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.cacheDirectory = Paths.get(cacheDirectory);
        this.smilMode = smilMode;
        long configuredMs = rotateSeconds > 0 ? rotateSeconds * 1000L : DEFAULT_ROTATE_MS;
        this.imageDisplayMs = configuredMs;
        this.videoPlaceholderMs = configuredMs;
        this.progressHeightPx = Math.max(0, Math.min(20, progressHeightPx));
        this.progressColor = parseProgressColor(progressColorHex);
    }

    public void start() {
        try {
            initFullScreenWindow();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize player window", e);
        }

        Thread playlistThread = new Thread(() -> {
            while (true) {
                List<PlaylistEntry> playlist = buildPlaylist();
                if (playlist.isEmpty()) {
                    setPlaybackCursorHidden(true);
                    showText("CACHE is empty\nAdd .jpg, .png, .mov or .mp4 files\n\n" + AppInfo.APP_SIGNATURE, Color.WHITE);
                    sleepMs(3000L);
                    continue;
                }

                for (PlaylistEntry item : playlist) {
                    Path mediaPath = item.path;
                    String ext = getFileExtension(mediaPath.getFileName().toString());
                    if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)) {
                        setPlaybackCursorHidden(true);
                        DisplayRect rect = showImage(mediaPath);
                        sleepWithImageProgress(item.durationMs, rect);
                    } else {
                        setPlaybackCursorHidden(true);
                        // Swing does not provide native video playback; keep playlist order and timing.
                        showText("Video: " + mediaPath.getFileName(), Color.LIGHT_GRAY);
                        hideProgressBar();
                        sleepMs(item.durationMs);
                    }
                }
            }
        }, "playlist-loop");

        playlistThread.setDaemon(true);
        playlistThread.start();
    }

    private void initFullScreenWindow() throws Exception {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (devices.length == 0) {
            throw new IllegalStateException("No screen device found");
        }
        if (screenIndex < 0 || screenIndex >= devices.length) {
            throw new IllegalArgumentException("Screen index out of range: " + screenIndex);
        }

        GraphicsDevice selectedDevice = devices[screenIndex];

        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("brilliantSignage-player");
            frame.setUndecorated(true);
            frame.setResizable(false);
            frame.setBackground(Color.BLACK);
            frame.getContentPane().setBackground(Color.BLACK);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setIconImages(new ArrayList<>());

            layeredPane = new JLayeredPane();
            layeredPane.setLayout(null);
            layeredPane.setBackground(Color.BLACK);
            layeredPane.setOpaque(true);

            mediaLabel = new JLabel("", SwingConstants.CENTER);
            mediaLabel.setOpaque(true);
            mediaLabel.setBackground(Color.BLACK);
            mediaLabel.setForeground(Color.WHITE);
            mediaLabel.setFont(new Font("SansSerif", Font.BOLD, 28));

            progressBar = new JPanel();
            progressBar.setBackground(progressColor);
            progressBar.setVisible(false);

            defaultCursor = Cursor.getDefaultCursor();
            hiddenCursor = createHiddenCursor();

            mediaLabel.setBounds(0, 0, screenWidth, screenHeight);
            layeredPane.add(mediaLabel, Integer.valueOf(0));
            layeredPane.add(progressBar, Integer.valueOf(1));
            frame.setContentPane(layeredPane);

            frame.setSize(screenWidth, screenHeight);
            selectedDevice.setFullScreenWindow(frame);
            frame.setVisible(true);
            setPlaybackCursorHidden(true);
        });
    }

    private List<PlaylistEntry> buildPlaylist() {
        if (smilMode) {
            List<PlaylistEntry> fromManifest = buildPlaylistFromSmilManifest();
            if (!fromManifest.isEmpty()) {
                return fromManifest;
            }
        }
        return buildStaticPlaylist();
    }

    private List<PlaylistEntry> buildStaticPlaylist() {
        List<PlaylistEntry> mediaFiles = new ArrayList<>();
        if (!Files.exists(cacheDirectory) || !Files.isDirectory(cacheDirectory)) {
            return mediaFiles;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(cacheDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedMedia)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> mediaFiles.add(new PlaylistEntry(path, resolveStaticDurationMs(path))));
        } catch (IOException ignored) {
            return new ArrayList<>();
        }

        return mediaFiles;
    }

    private List<PlaylistEntry> buildPlaylistFromSmilManifest() {
        List<PlaylistEntry> entries = new ArrayList<>();
        Path manifest = cacheDirectory.resolve(".smil-playlist.csv");
        if (!Files.exists(manifest) || !Files.isRegularFile(manifest)) {
            return entries;
        }

        try {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",", 2);
                if (parts.length < 2) {
                    continue;
                }

                String fileName = parts[0].trim();
                long durationMs = parseLongOrDefault(parts[1].trim(), imageDisplayMs);
                Path path = cacheDirectory.resolve(fileName).normalize();
                if (Files.exists(path) && Files.isRegularFile(path) && isSupportedMedia(path)) {
                    entries.add(new PlaylistEntry(path, Math.max(1L, durationMs)));
                }
            }
        } catch (IOException ignored) {
            return new ArrayList<>();
        }

        return entries;
    }

    private boolean isSupportedMedia(Path path) {
        String ext = getFileExtension(path.getFileName().toString());
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "mov".equals(ext) || "mp4".equals(ext);
    }

    private DisplayRect showImage(Path imagePath) {
        try {
            BufferedImage source = ImageIO.read(imagePath.toFile());
            if (source == null) {
                setPlaybackCursorHidden(true);
                showText("Cannot load image: " + imagePath.getFileName(), Color.RED);
                hideProgressBar();
                return null;
            }

            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            double scale = Math.min((double) screenWidth / sourceWidth, (double) screenHeight / sourceHeight);
            int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
            int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
            int targetX = Math.max(0, (screenWidth - targetWidth) / 2);
            int targetY = Math.max(0, (screenHeight - targetHeight) / 2);

            Image scaled = source.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
            ImageIcon icon = new ImageIcon(scaled);

            SwingUtilities.invokeLater(() -> {
                mediaLabel.setText("");
                mediaLabel.setIcon(icon);
                mediaLabel.setHorizontalAlignment(SwingConstants.CENTER);
                mediaLabel.setVerticalAlignment(SwingConstants.CENTER);
                mediaLabel.setBackground(Color.BLACK);
            });
            return new DisplayRect(targetX, targetY, targetWidth, targetHeight);
        } catch (IOException e) {
            setPlaybackCursorHidden(true);
            showText("Cannot load image: " + imagePath.getFileName(), Color.RED);
            hideProgressBar();
            return null;
        }
    }

    private Cursor createHiddenCursor() {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        BufferedImage cursorImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        return toolkit.createCustomCursor(cursorImage, new Point(0, 0), "hidden-cursor");
    }

    private void setPlaybackCursorHidden(boolean hidden) {
        SwingUtilities.invokeLater(() -> {
            if (frame == null) {
                return;
            }

            Cursor cursorToApply = hidden ? hiddenCursor : defaultCursor;
            if (cursorToApply == null) {
                cursorToApply = Cursor.getDefaultCursor();
            }

            frame.setCursor(cursorToApply);
            if (layeredPane != null) {
                layeredPane.setCursor(cursorToApply);
            }
            if (mediaLabel != null) {
                mediaLabel.setCursor(cursorToApply);
            }
        });
    }

    private void showText(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            mediaLabel.setIcon(null);
            mediaLabel.setText("<html><div style='text-align:center;'>"
                    + text.replace("\n", "<br/>") + "</div></html>");
            mediaLabel.setForeground(color);
            mediaLabel.setBackground(Color.BLACK);
        });
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    private void sleepMs(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepWithImageProgress(long durationMs, DisplayRect rect) {
        if (durationMs <= 0) {
            hideProgressBar();
            return;
        }

        long start = System.currentTimeMillis();
        long end = start + durationMs;
        while (true) {
            long now = System.currentTimeMillis();
            long remaining = end - now;
            if (remaining <= 0) {
                break;
            }

            if (rect != null && progressHeightPx > 0) {
                double leftFraction = Math.max(0.0, Math.min(1.0, (double) remaining / (double) durationMs));
                updateProgressBar(rect, leftFraction);
            }

            sleepMs(Math.min(100L, remaining));
        }
        hideProgressBar();
    }

    private void updateProgressBar(DisplayRect rect, double leftFraction) {
        int fullWidth = rect.width;
        int visibleWidth = (int) Math.round(fullWidth * leftFraction);
        int x = rect.x;
        int y = rect.y + rect.height - progressHeightPx;
        int h = progressHeightPx;

        SwingUtilities.invokeLater(() -> {
            if (progressBar == null) {
                return;
            }
            if (h <= 0 || visibleWidth <= 0) {
                progressBar.setVisible(false);
                return;
            }
            progressBar.setBounds(x, y, visibleWidth, h);
            progressBar.setVisible(true);
        });
    }

    private void hideProgressBar() {
        SwingUtilities.invokeLater(() -> {
            if (progressBar != null) {
                progressBar.setVisible(false);
            }
        });
    }

    private long resolveStaticDurationMs(Path mediaPath) {
        String ext = getFileExtension(mediaPath.getFileName().toString());
        if ("mov".equals(ext) || "mp4".equals(ext)) {
            return resolveVideoDurationMs(mediaPath);
        }
        return imageDisplayMs;
    }

    private long resolveVideoDurationMs(Path mediaPath) {
        Long cached = videoDurationCache.get(mediaPath);
        if (cached != null) {
            return cached;
        }

        long durationMs = probeVideoDurationMs(mediaPath);
        if (durationMs <= 0) {
            durationMs = videoPlaceholderMs;
        }
        videoDurationCache.put(mediaPath, durationMs);
        return durationMs;
    }

    private long probeVideoDurationMs(Path mediaPath) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    mediaPath.toString()
            );
            pb.redirectErrorStream(true);
            process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                int exit = process.waitFor();
                if (exit == 0 && line != null && !line.trim().isEmpty()) {
                    double seconds = Double.parseDouble(line.trim());
                    return Math.max(1L, Math.round(seconds * 1000.0));
                }
            }
        } catch (Exception ignored) {
            return -1L;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return -1L;
    }

    private long parseLongOrDefault(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static class PlaylistEntry {
        private final Path path;
        private final long durationMs;

        private PlaylistEntry(Path path, long durationMs) {
            this.path = path;
            this.durationMs = durationMs;
        }
    }

    private static class DisplayRect {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private DisplayRect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private Color parseProgressColor(String raw) {
        if (raw == null) {
            return Color.BLACK;
        }

        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }

        value = value.replaceAll("[^0-9a-fA-F]", "");
        if (value.isEmpty()) {
            return Color.BLACK;
        }

        // Normalize shorter values to supported lengths.
        if (value.length() == 1) {
            value = "0" + value + "00" + value + "00" + value;
        } else if (value.length() == 2) {
            value = value + "00" + value + "00";
        } else if (value.length() == 3 || value.length() == 4) {
            StringBuilder expanded = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                expanded.append(c).append(c);
            }
            value = expanded.toString();
        } else if (value.length() == 5) {
            value = value + "0";
        } else if (value.length() == 7) {
            value = value + "0";
        } else if (value.length() > 8) {
            value = value.substring(0, 8);
        }

        try {
            if (value.length() == 6) {
                int r = Integer.parseInt(value.substring(0, 2), 16);
                int b = Integer.parseInt(value.substring(2, 4), 16);
                int g = Integer.parseInt(value.substring(4, 6), 16);
                return new Color(r, g, b);
            }
            if (value.length() == 8) {
                int r = Integer.parseInt(value.substring(0, 2), 16);
                int b = Integer.parseInt(value.substring(2, 4), 16);
                int g = Integer.parseInt(value.substring(4, 6), 16);
                int a = Integer.parseInt(value.substring(6, 8), 16);
                return new Color(r, g, b, a);
            }
        } catch (NumberFormatException ignored) {
            return Color.BLACK;
        }

        return Color.BLACK;
    }
}
