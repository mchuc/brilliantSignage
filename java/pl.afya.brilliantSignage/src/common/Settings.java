package common;

import interfaces.ISettings;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;

public class Settings implements ISettings {

    private static final String APP_DIRECTORY_NAME = "brilliantSignage";
    private static final String CONFIG_FILE_NAME = "config.json";

    private int screenSelected = -1;// -1 means no screen selected
    private Screen[] screens; // list of avaible screens
    private String directorySelected = null;
    private boolean smilDebugEnabled = false;
    private String smilIndexEtag = null;
    private Set<URI> lastSmilMediaUris = new LinkedHashSet<>();
    private String smilPlayerNameOverride = null;
    private String smilPlayerUuid = null;
    private String smilPlayerUuidOverride = null;
    private int smilRecommendedRefreshSeconds = 60;
    private Map<String, Long> lastSmilDurationsByFile = new LinkedHashMap<>();

    public Settings() {
        // Detect screens on initialization
        screens = detectScreens();
        if (screens.length > 0) {
            screenSelected = 0;
        }
        //create cache directory
        setDirectory(null);
    }

    @Override
    public int getScreenCurrent() {
        return this.screenSelected;
    }

    @Override
    public void setScreenCurrent(int screenNumber) {
        if (screenNumber >= 0 && screenNumber < detectScreens().length) {
            this.screenSelected = screenNumber;
        } else {
            throw new IllegalArgumentException("Screen number out of range. Available screens: " + detectScreens().length);
        }
    }

    @Override
    public Screen[] getScreensAvailable() {
        return this.screens;
    }

    @Override
   public void setDirectory(String directory) {
        java.nio.file.Path targetDirectory;

        if (directory == null || directory.trim().isEmpty()) {
            targetDirectory = java.nio.file.Paths.get(
                    System.getProperty("user.home"),
                    "brilliantSignage",
                    "cache"
            );
        } else {
            java.nio.file.Path baseDirectory = java.nio.file.Paths.get(directory.trim());
            if (!java.nio.file.Files.exists(baseDirectory) || !java.nio.file.Files.isDirectory(baseDirectory)) {
                throw new IllegalArgumentException("No directory: " + baseDirectory);
            }
            targetDirectory = baseDirectory.resolve("cache");
        }

        try {
            if (java.nio.file.Files.exists(targetDirectory) && !java.nio.file.Files.isDirectory(targetDirectory)) {
                throw new IllegalArgumentException("Path exists, but is not a directory: " + targetDirectory);
            }

            if (java.nio.file.Files.notExists(targetDirectory)) {
                java.nio.file.Files.createDirectories(targetDirectory);
            }

            this.directorySelected = targetDirectory.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to create cache directory", e);
        }
    }

    @Override
    public String getDirectory() {
        return this.directorySelected;
    }

    @Override
    public void setSMILDebug(boolean enabled) {
        this.smilDebugEnabled = enabled;
    }

    @Override
    public void setSMILPlayerName(String playerName) {
        if (playerName == null) {
            this.smilPlayerNameOverride = null;
            return;
        }

        String normalized = playerName.trim();
        if (normalized.isEmpty()) {
            this.smilPlayerNameOverride = null;
            return;
        }

        // Keep safe ASCII subset for headers and query parameters.
        this.smilPlayerNameOverride = normalized.replaceAll("[^a-zA-Z0-9._ -]", "_");
    }

    @Override
    public void setSMILUuidOverride(String playerUuid) {
        if (playerUuid == null || playerUuid.trim().isEmpty()) {
            this.smilPlayerUuidOverride = null;
            return;
        }

        String normalized = playerUuid.trim();
        try {
            this.smilPlayerUuidOverride = UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid UUID format for --smil-uuid", e);
        }
    }

    @Override
    public String resetSMILUuid() {
        String generatedUuid = UUID.randomUUID().toString();
        String machineFingerprint = buildMachineFingerprint();
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            writeConfigWithUuid(configPath, generatedUuid, machineFingerprint);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reset SMIL UUID in config.json", e);
        }
        this.smilPlayerUuid = generatedUuid;
        return generatedUuid;
    }

    @Override
    public int getSMILRecommendedRefreshSeconds() {
        return this.smilRecommendedRefreshSeconds;
    }

    @Override
    public String setSMILHost(String host) {
        URI origin = validateHost(host);
        String playerIdentity = buildPlayerIdentity();
        String playerUuid = getEffectivePlayerUuid();
        String signageAgent = buildSignageAgent(playerIdentity, playerUuid);
        URI smilIndex = withPlayerQuery(origin.resolve("/smil-index"), playerIdentity, playerUuid);
        Path cacheDirectory = Paths.get(this.directorySelected);

        logSmilDebug("Connecting to hub: " + origin);
        logSmilDebug("Player identity: " + playerIdentity);
        logSmilDebug("Player UUID: " + playerUuid);
        logSmilDebug("Signage-Agent: " + signageAgent);
        logSmilDebug("Fetching SMIL index: " + smilIndex);

        try {
            Set<URI> mediaUris = collectMediaUrisFromSmil(smilIndex, origin, signageAgent, playerIdentity, playerUuid);
            logSmilDebug("SMIL processing complete, media entries found: " + mediaUris.size());
            int downloaded = downloadMediaFiles(mediaUris, cacheDirectory, signageAgent, playerIdentity, playerUuid);
            writeSmilPlaylistManifest(cacheDirectory, lastSmilDurationsByFile);
            logSmilDebug("Sync complete, files copied/updated in cache: " + downloaded);
            return "Player " + playerIdentity + ": downloaded " + downloaded + " file(s) from " + origin;
        } catch (IOException e) {
            logSmilDebug("SMIL sync failed: " + e.getMessage());
            throw new IllegalStateException("Failed to download SMIL resources from: " + smilIndex, e);
        }
    }

    private URI validateHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be empty");
        }

        try {
            URI uri = new URI(host.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Host must start with http:// or https://");
            }
            if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                throw new IllegalArgumentException("Host name is required");
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            }

            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null, uri.getHost(), port, "/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid host URL: " + host, e);
        }
    }

    private Set<URI> collectMediaUrisFromSmil(URI startUri, URI allowedOrigin, String signageAgent, String playerIdentity, String playerUuid) throws IOException {
        Set<URI> mediaUris = new LinkedHashSet<>();
        Map<String, Long> durationsByFile = new LinkedHashMap<>();
        Set<URI> visitedXml = new HashSet<>();
        ArrayDeque<URI> queue = new ArrayDeque<>();
        queue.add(startUri);

        int maxXmlDocuments = 20;
        boolean firstDocument = true;
        while (!queue.isEmpty() && visitedXml.size() < maxXmlDocuments) {
            URI current = queue.removeFirst();
            if (!visitedXml.add(current)) {
                continue;
            }

            logSmilDebug("Downloading SMIL/XML document: " + current);

            byte[] xmlContent;
            try {
                if (firstDocument) {
                        xmlContent = httpGetSmilIndexBytes(current, signageAgent, playerIdentity, playerUuid);
                    if (xmlContent == null) {
                        if (lastSmilMediaUris.isEmpty()) {
                            throw new IOException("SMIL index not modified (304), but no cached playlist exists yet");
                        }
                        logSmilDebug("SMIL index not modified (304), reusing cached media list: " + lastSmilMediaUris.size());
                        return new LinkedHashSet<>(lastSmilMediaUris);
                    }
                } else {
                        xmlContent = httpGetBytes(current, signageAgent, playerIdentity, playerUuid);
                }
            } catch (IOException e) {
                logSmilDebug("Failed to download SMIL/XML document: " + current + " -> " + e.getMessage());
                if (firstDocument) {
                    throw e;
                }
                continue;
            }
            firstDocument = false;

            if (current.equals(startUri)) {
                updateRecommendedRefreshFromSmil(xmlContent);
            }

            List<SmilToken> tokens = extractXmlTokens(xmlContent);
            logSmilDebug("Parsed document, extracted tokens: " + tokens.size());
            for (SmilToken token : tokens) {
                URI resolved = resolveToSameOrigin(current, token.path, allowedOrigin);
                if (resolved == null) {
                    continue;
                }

                if (isXmlLikeResource(resolved)) {
                    if (!visitedXml.contains(resolved)) {
                        queue.addLast(resolved);
                    }
                    continue;
                }

                if (isSupportedMediaResource(resolved)) {
                    mediaUris.add(resolved);
                    if (token.durationMs > 0) {
                        String fileName = extractSafeFileName(resolved);
                        durationsByFile.put(fileName, token.durationMs);
                    }
                }
            }
        }

        logSmilDebug("Total XML/SMIL documents visited: " + visitedXml.size());
        lastSmilMediaUris = new LinkedHashSet<>(mediaUris);
        lastSmilDurationsByFile = new LinkedHashMap<>(durationsByFile);

        return mediaUris;
    }

    private List<SmilToken> extractXmlTokens(byte[] xmlContent) {
        List<SmilToken> tokens = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xmlContent));
            walkNode(document.getDocumentElement(), tokens);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse SMIL/XML document", e);
        }

        return tokens;
    }

    private void walkNode(Node node, List<SmilToken> tokens) {
        if (node == null) {
            return;
        }

        Long durationMs = null;
        NamedNodeMap attrs = node.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Node attr = attrs.item(i);
                if ("dur".equalsIgnoreCase(attr.getNodeName())) {
                    durationMs = parseSmilDurationToMillis(attr.getNodeValue());
                    break;
                }
            }
            for (int i = 0; i < attrs.getLength(); i++) {
                Node attr = attrs.item(i);
                String attrName = attr.getNodeName();
                if ("src".equalsIgnoreCase(attrName) || "href".equalsIgnoreCase(attrName)) {
                    addTokenIfPathLike(attr.getNodeValue(), durationMs, tokens);
                }
            }
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            walkNode(children.item(i), tokens);
        }
    }

    private void addTokenIfPathLike(String value, Long durationMs, List<SmilToken> tokens) {
        if (value == null) {
            return;
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/")) {
            tokens.add(new SmilToken(trimmed, durationMs == null ? -1L : durationMs));
            return;
        }

        // Relative paths in SMIL, e.g. media/video.mp4
        if (trimmed.contains("/") || trimmed.contains(".")) {
            tokens.add(new SmilToken(trimmed, durationMs == null ? -1L : durationMs));
        }
    }

    private long parseSmilDurationToMillis(String rawDuration) {
        if (rawDuration == null) {
            return -1L;
        }

        String value = rawDuration.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return -1L;
        }

        try {
            if (value.endsWith("ms")) {
                return Math.max(1L, Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            if (value.endsWith("s")) {
                double seconds = Double.parseDouble(value.substring(0, value.length() - 1).trim());
                return Math.max(1L, Math.round(seconds * 1000.0));
            }
            if (value.endsWith("m")) {
                double minutes = Double.parseDouble(value.substring(0, value.length() - 1).trim());
                return Math.max(1L, Math.round(minutes * 60_000.0));
            }
            if (value.endsWith("h")) {
                double hours = Double.parseDouble(value.substring(0, value.length() - 1).trim());
                return Math.max(1L, Math.round(hours * 3_600_000.0));
            }

            String[] parts = value.split(":");
            if (parts.length == 3) {
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return Math.max(1L, Math.round((hours * 3600.0 + minutes * 60.0 + seconds) * 1000.0));
            }
        } catch (NumberFormatException ignored) {
            return -1L;
        }

        return -1L;
    }

    private URI resolveToSameOrigin(URI base, String token, URI allowedOrigin) {
        try {
            URI candidate = base.resolve(token);
            if (candidate.getScheme() == null || candidate.getHost() == null) {
                return null;
            }

            boolean sameScheme = allowedOrigin.getScheme().equalsIgnoreCase(candidate.getScheme());
            boolean sameHost = allowedOrigin.getHost().equalsIgnoreCase(candidate.getHost());
            boolean samePort = allowedOrigin.getPort() == candidate.getPort();
            if (!sameScheme || !sameHost || !samePort) {
                return null;
            }

            String normalizedPath = candidate.getPath() == null ? "/" : candidate.getPath();
            return new URI(candidate.getScheme(), null, candidate.getHost(), candidate.getPort(), normalizedPath, candidate.getQuery(), null);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isXmlLikeResource(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".xml") || path.endsWith(".smil") || path.endsWith("/smil-index/") || path.endsWith("/smil-index");
    }

    private boolean isSupportedMediaResource(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".mp4") || path.endsWith(".mov");
    }

    private int downloadMediaFiles(Set<URI> mediaUris, Path cacheDirectory, String signageAgent, String playerIdentity, String playerUuid) throws IOException {
        if (Files.notExists(cacheDirectory)) {
            Files.createDirectories(cacheDirectory);
        }

        int updated = 0;
        for (URI mediaUri : mediaUris) {
            String fileName = extractSafeFileName(mediaUri);
            Path target = cacheDirectory.resolve(fileName).normalize();
            if (!target.startsWith(cacheDirectory.normalize())) {
                writeCacheSyncLog(cacheDirectory, "SKIPPED_INVALID_PATH", mediaUri, fileName, "Target escaped CACHE directory");
                continue;
            }

            logSmilDebug("Preparing media file: " + mediaUri + " -> " + target.getFileName());
            writeCacheSyncLog(cacheDirectory, "ATTEMPT", mediaUri, fileName, "Preparing download");

            URI mediaUriWithPlayer = withPlayerQuery(mediaUri, playerIdentity, playerUuid);
            Path tempFile = Files.createTempFile(cacheDirectory, "smil-sync-", ".tmp");
            try {
                try {
                    logSmilDebug("Downloading media: " + mediaUriWithPlayer);
                    httpDownloadToFile(mediaUriWithPlayer, tempFile, signageAgent, playerUuid);
                    if (replaceIfDifferent(tempFile, target)) {
                        updated++;
                        logSmilDebug("Copied to cache: " + target.getFileName());
                        writeCacheSyncLog(cacheDirectory, "DOWNLOADED", mediaUriWithPlayer, fileName, "Copied/updated in CACHE");
                    } else {
                        logSmilDebug("No changes detected, keeping cached file: " + target.getFileName());
                        writeCacheSyncLog(cacheDirectory, "SKIPPED_UNCHANGED", mediaUriWithPlayer, fileName, "Already up-to-date");
                    }
                } catch (IOException ignored) {
                    logSmilDebug("Skipping media due to download error: " + mediaUriWithPlayer + " -> " + ignored.getMessage());
                    writeCacheSyncLog(cacheDirectory, "ERROR", mediaUriWithPlayer, fileName, ignored.getMessage());
                    // Skip single broken media file and continue syncing remaining resources.
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        return updated;
    }

    private boolean replaceIfDifferent(Path downloadedTemp, Path target) throws IOException {
        if (Files.notExists(target)) {
            logSmilDebug("File not present in cache, copying new file: " + target.getFileName());
            moveReplacing(downloadedTemp, target);
            return true;
        }

        logSmilDebug("Comparing cached file with downloaded file: " + target.getFileName());
        if (Files.mismatch(downloadedTemp, target) == -1L) {
            return false;
        }

        logSmilDebug("File content changed, replacing cache file: " + target.getFileName());
        moveReplacing(downloadedTemp, target);
        return true;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String extractSafeFileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return "media-" + System.currentTimeMillis();
        }

        String raw = path.substring(path.lastIndexOf('/') + 1);
        if (raw.trim().isEmpty()) {
            return "media-" + System.currentTimeMillis();
        }

        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private byte[] httpGetBytes(URI uri, String signageAgent, String playerIdentity, String playerUuid) throws IOException {
        URI uriWithPlayer = withPlayerQuery(uri, playerIdentity, playerUuid);
        HttpURLConnection connection = openHttpConnection(uriWithPlayer, signageAgent, playerUuid);
        int code = connection.getResponseCode();
        logSmilDebug("HTTP GET " + uriWithPlayer + " -> " + code);
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " for " + uri);
        }

        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private byte[] httpGetSmilIndexBytes(URI uri, String signageAgent, String playerIdentity, String playerUuid) throws IOException {
        URI uriWithPlayer = withPlayerQuery(uri, playerIdentity, playerUuid);
        HttpURLConnection connection = openHttpConnection(uriWithPlayer, signageAgent, playerUuid);
        if (smilIndexEtag != null && !smilIndexEtag.isEmpty()) {
            connection.setRequestProperty("If-None-Match", smilIndexEtag);
            logSmilDebug("If-None-Match: " + smilIndexEtag);
        }

        int code = connection.getResponseCode();
        logSmilDebug("HTTP GET " + uriWithPlayer + " -> " + code);
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return null;
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " for " + uri);
        }

        String receivedEtag = connection.getHeaderField("ETag");
        if (receivedEtag != null && !receivedEtag.trim().isEmpty()) {
            smilIndexEtag = receivedEtag.trim();
            logSmilDebug("Received ETag: " + smilIndexEtag);
        }

        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void httpDownloadToFile(URI uri, Path targetFile, String signageAgent, String playerUuid) throws IOException {
        HttpURLConnection connection = openHttpConnection(uri, signageAgent, playerUuid);
        int code = connection.getResponseCode();
        logSmilDebug("HTTP GET " + uri + " -> " + code);
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " for " + uri);
        }

        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HttpURLConnection openHttpConnection(URI uri, String signageAgent, String playerUuid) throws IOException {
        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("User-Agent", signageAgent);
        connection.setRequestProperty("X-Signage-Agent", signageAgent);
        connection.setRequestProperty("X-Signage-UUID", playerUuid);
        return connection;
    }

    private URI withPlayerQuery(URI uri, String playerIdentity, String playerUuid) {
        try {
            String encodedPlayer = URLEncoder.encode(playerIdentity, StandardCharsets.UTF_8);
            String encodedUuid = URLEncoder.encode(playerUuid, StandardCharsets.UTF_8);
            String query = uri.getQuery();
            String nextQuery = appendQueryParam(query, "player", encodedPlayer);
            nextQuery = appendQueryParam(nextQuery, "playerUuid", encodedUuid);
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), nextQuery, uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot build player query for URI: " + uri, e);
        }
    }

    private String appendQueryParam(String existingQuery, String key, String encodedValue) {
        if (existingQuery == null || existingQuery.isEmpty()) {
            return key + "=" + encodedValue;
        }
        return existingQuery + "&" + key + "=" + encodedValue;
    }

    private String buildPlayerIdentity() {
        if (smilPlayerNameOverride != null && !smilPlayerNameOverride.isEmpty()) {
            return smilPlayerNameOverride;
        }
        return "brilliantSignage-" + resolveExternalIp();
    }

    private String buildSignageAgent(String playerIdentity, String playerUuid) {
        return "GAPI/1.0 (UUID:" + playerUuid + "; NAME:" + playerIdentity + ") brilliantSignage-java/v0.1 (MODEL:Garlic)";
    }

    private String loadOrCreatePlayerUuid() {
        Path configPath = getConfigPath();
        Path appDirectory = configPath.getParent();
        String machineFingerprint = buildMachineFingerprint();

        try {
            Files.createDirectories(appDirectory);
            if (Files.exists(configPath) && Files.isRegularFile(configPath)) {
                String existingJson = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                String existingUuid = extractUuidFromConfig(existingJson);
                String existingFingerprint = extractConfigString(existingJson, "machineFingerprint");
                if (existingUuid != null && machineFingerprint.equals(existingFingerprint)) {
                    return existingUuid;
                }
            }

            String generatedUuid = UUID.randomUUID().toString();
            writeConfigWithUuid(configPath, generatedUuid, machineFingerprint);
            return generatedUuid;
        } catch (IOException e) {
            // If config cannot be persisted, still provide a valid runtime identity.
            return UUID.randomUUID().toString();
        }
    }

    private String getEffectivePlayerUuid() {
        if (smilPlayerUuidOverride != null && !smilPlayerUuidOverride.isEmpty()) {
            return smilPlayerUuidOverride;
        }
        if (smilPlayerUuid == null || smilPlayerUuid.isEmpty()) {
            smilPlayerUuid = loadOrCreatePlayerUuid();
        }
        return smilPlayerUuid;
    }

    private Path getConfigPath() {
        return getWorkingDirectoryPath().resolve(CONFIG_FILE_NAME);
    }

    private Path getWorkingDirectoryPath() {
        if (directorySelected != null && !directorySelected.trim().isEmpty()) {
            Path cachePath = Paths.get(directorySelected).normalize();
            Path parent = cachePath.getParent();
            if (parent != null) {
                return parent;
            }
            return cachePath;
        }
        return Paths.get(System.getProperty("user.home"), APP_DIRECTORY_NAME);
    }

    private String extractUuidFromConfig(String json) {
        String candidate = extractConfigString(json, "playerUuid");
        if (candidate == null) {
            return null;
        }
        try {
            return UUID.fromString(candidate).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractConfigString(String json, String fieldName) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        Pattern valuePattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = valuePattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim();
    }

    private void writeConfigWithUuid(Path configPath, String uuid, String machineFingerprint) throws IOException {
        String json = "{\n"
                + "  \"playerUuid\": \"" + uuid + "\",\n"
                + "  \"machineFingerprint\": \"" + machineFingerprint + "\"\n"
                + "}\n";
        Path temp = Files.createTempFile(configPath.getParent(), "config-", ".tmp");
        try {
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            moveReplacing(temp, configPath);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String buildMachineFingerprint() {
        String host = "unknown-host";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // Best-effort only.
        }

        String mac = resolveFirstMacAddress();
        String raw = System.getProperty("os.name", "unknown-os")
                + "|" + System.getProperty("os.arch", "unknown-arch")
                + "|" + host
                + "|" + mac;
        return sha256Hex(raw);
    }

    private String resolveFirstMacAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length == 0) {
                    continue;
                }

                StringBuilder sb = new StringBuilder();
                for (byte b : mac) {
                    if (sb.length() > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            }
        } catch (SocketException ignored) {
            // Best-effort only.
        }
        return "unknown-mac";
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String resolveExternalIp() {
        String[] providers = new String[]{
                "https://api.ipify.org",
                "https://ifconfig.me/ip",
                "https://icanhazip.com"
        };

        for (String provider : providers) {
            String ip = fetchPublicIp(provider);
            if (isValidPublicIpv4(ip)) {
                return ip;
            }
        }

        String localFallback = resolveFirstNonLoopbackIpv4();
        if (localFallback != null) {
            return localFallback;
        }

        return "unknown-ip";
    }

    private String fetchPublicIp(String providerUrl) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(providerUrl).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[256];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    return out.toString(StandardCharsets.UTF_8).trim();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private boolean isValidPublicIpv4(String ip) {
        if (ip == null) {
            return false;
        }
        if (!ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return false;
        }

        String[] parts = ip.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }

        if (octets[0] == 10 || octets[0] == 127) {
            return false;
        }
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return false;
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return false;
        }
        if (octets[0] == 169 && octets[1] == 254) {
            return false;
        }
        if (octets[0] == 0) {
            return false;
        }

        return true;
    }

    private String resolveFirstNonLoopbackIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String hostAddress = address.getHostAddress();
                    if (!address.isLoopbackAddress() && hostAddress != null && hostAddress.indexOf(':') < 0) {
                        return hostAddress;
                    }
                }
            }
        } catch (SocketException ignored) {
            return null;
        }
        return null;
    }

    private void logSmilDebug(String message) {
        if (smilDebugEnabled) {
            System.out.println("[SMIL-DEBUG] " + message);
        }
    }

    private void updateRecommendedRefreshFromSmil(byte[] xmlContent) {
        Integer refreshSeconds = extractRefreshSecondsFromSmil(xmlContent);
        if (refreshSeconds != null && refreshSeconds > 0) {
            this.smilRecommendedRefreshSeconds = refreshSeconds;
            logSmilDebug("SMIL refresh from XML meta: " + refreshSeconds + "s");
        }
    }

    private Integer extractRefreshSecondsFromSmil(byte[] xmlContent) {
        String xml = new String(xmlContent, StandardCharsets.UTF_8);
        Pattern firstOrder = Pattern.compile("(?i)<meta[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"']?(\\d+)");
        Pattern secondOrder = Pattern.compile("(?i)<meta[^>]*content\\s*=\\s*[\"']?(\\d+)[\"']?[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?");

        Matcher first = firstOrder.matcher(xml);
        if (first.find()) {
            return parsePositiveInt(first.group(1));
        }

        Matcher second = secondOrder.matcher(xml);
        if (second.find()) {
            return parsePositiveInt(second.group(1));
        }

        return null;
    }

    private Integer parsePositiveInt(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeCacheSyncLog(Path cacheDirectory, String status, URI uri, String fileName, String detail) {
        Path logFile = cacheDirectory.resolve("smil-sync.log");
        String line = Instant.now() + " | " + status + " | " + fileName + " | " + uri + " | " + detail + System.lineSeparator();
        try {
            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging must never break SMIL sync.
        }
    }

    private void writeSmilPlaylistManifest(Path cacheDirectory, Map<String, Long> durationsByFile) {
        Path manifest = cacheDirectory.resolve(".smil-playlist.csv");
        StringBuilder content = new StringBuilder();
        content.append("filename,durationMs").append(System.lineSeparator());
        for (Map.Entry<String, Long> entry : durationsByFile.entrySet()) {
            content.append(entry.getKey()).append(",").append(entry.getValue()).append(System.lineSeparator());
        }

        try {
            Files.write(manifest, content.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // Manifest is optional helper for player timings.
        }
    }

    private static class SmilToken {
        private final String path;
        private final long durationMs;

        private SmilToken(String path, long durationMs) {
            this.path = path;
            this.durationMs = durationMs;
        }
    }

    @Override
    public int getScreenWidth() {
        if (screenSelected >= 0) {
            return screens[screenSelected].getWidth();
        } else {
            throw new IllegalArgumentException("No screen selected");
        }
    }

    @Override
    public int getScreenHeight() {
        if (screenSelected >= 0) {
            return screens[screenSelected].getHeight();
        } else {
            throw new IllegalArgumentException("No screen selected");
        }
    }

    public Screen[] detectScreens() {
        java.awt.GraphicsDevice[] screens = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getScreenDevices();
        Screen[] avaibleScreens = new Screen[screens.length];
        for (int i = 0; i < screens.length; i++) {
            avaibleScreens[i] = new Screen(i, screens[i].getDisplayMode().getWidth(), screens[i].getDisplayMode().getHeight());
        }
        return avaibleScreens;
    }

}
