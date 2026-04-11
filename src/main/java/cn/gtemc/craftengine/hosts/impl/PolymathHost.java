package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.HashUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class PolymathHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<PolymathHost> FACTORY = new Factory();
    private final String serverUrl;
    private final String secret;
    private final Path cacheFilePath;
    private String sha1;
    private String url;
    private UUID uuid;

    private PolymathHost(String serverUrl, String secret, Path cacheFilePath) {
        this.serverUrl = serverUrl;
        this.secret = secret;
        this.cacheFilePath = cacheFilePath;
        this.readCacheFromDisk();
    }

    private void readCacheFromDisk() {
        if (!Files.exists(this.cacheFilePath) || !Files.isRegularFile(this.cacheFilePath)) return;

        try (InputStream is = Files.newInputStream(this.cacheFilePath)) {
            Map<String, String> cache = GsonHelper.parseJson(is);

            this.sha1 = cache.get("sha1");
            this.uuid = generateUUID(this.sha1);
            this.url = cache.get("url");
        } catch (Exception e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to load Polymath cache disk", e);
        }
    }

    private void saveCacheToDisk() {
        Map<String, String> cache = new HashMap<>();
        cache.put("sha1", this.sha1 != null ? this.sha1 : "");
        cache.put("url", this.url != null ? this.url : "");
        try {
            Files.createDirectories(this.cacheFilePath.getParent());
            Files.writeString(
                    this.cacheFilePath,
                    GsonHelper.toJson(cache),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to persist Polymath cache", e);
        }
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        return CompletableFuture.completedFuture(List.of(ResourcePackDownloadData.of(this.url, this.uuid, this.sha1)));
    }

    @Override
    public CompletableFuture<Void> upload(Path resourcePackPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                this.sha1 = HashUtils.calculateLocalFileSha1(resourcePackPath);
                this.uuid = generateUUID(this.sha1);
                this.saveCacheToDisk();
                String boundary = UUID.randomUUID().toString();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(this.serverUrl))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(buildMultipartBody(resourcePackPath, boundary))
                        .build();

                HttpResponse<String> response = HttpClientManager.get().send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                this.url = responseJson.get("url").getAsString();
                this.saveCacheToDisk();
            } catch (IOException | JsonSyntaxException  | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private HttpRequest.BodyPublisher buildMultipartBody(Path filePath, String boundary) throws IOException {
        List<byte[]> parts = new ArrayList<>();

        String idPart = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"id\"\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\n" +
                this.secret + "\r\n";
        parts.add(idPart.getBytes(StandardCharsets.UTF_8));

        String filePart = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"pack\"; filename=\"" + filePath.getFileName() + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n";
        parts.add(filePart.getBytes(StandardCharsets.UTF_8));

        parts.add(Files.readAllBytes(filePath));

        String endBoundary = "\r\n--" + boundary + "--\r\n";
        parts.add(endBoundary.getBytes(StandardCharsets.UTF_8));

        return HttpRequest.BodyPublishers.ofByteArrays(parts);
    }

    private UUID generateUUID(String sha1) {
        if (sha1 == null || sha1.isEmpty()) {
            return null;
        }
        return UUID.nameUUIDFromBytes(sha1.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean canUpload() {
        return true;
    }

    @Override
    public ResourcePackHostType<PolymathHost> type() {
        return ResourcePackHosts.POLYMATH;
    }

    private static class Factory implements ResourcePackHostFactory<PolymathHost> {
        private static final String[] USE_ENVIRONMENT_VARIABLES = new String[]{"use_environment_variables", "use-environment-variables"};
        private static final String[] SERVER_URL = new String[]{"server_url", "server-url"};
        private static final String[] CACHE_FILE_NAME = new String[] {"cache_file_name", "cache-file-name"};

        @Override
        public PolymathHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String serverUrl = section.getNonEmptyString(SERVER_URL);
            String secret = useEnv ? getNonNullEnvironmentVariable(section, "CE_POLYMATH_SECRET") : section.getNonEmptyString("secret");
            Path cacheFilePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache")
                    .resolve(section.getValue(CACHE_FILE_NAME, it -> it.getAsNonEmptyString().replace("/", "_"), "polymath.json"));
            return new PolymathHost(serverUrl, secret, cacheFilePath);
        }
    }
}
