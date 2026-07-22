package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.MiscUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class HermesHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<HermesHost> FACTORY = new Factory();
    private final String baseUrl;
    private final String secret;
    private final Path cacheFilePath;
    private String sha1;
    private String url;
    private UUID uuid;

    private HermesHost(String serverUrl, String secret, Path cacheFilePath) {
        this.secret = secret;
        this.cacheFilePath = cacheFilePath;

        String normalized = serverUrl.trim();
        if (!normalized.startsWith("http")) {
            normalized = "https://" + normalized;
        }
        normalized = normalized.replaceAll("/+$", "");
        this.baseUrl = normalized + "/";

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
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to load Hermes cache disk", e);
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
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to persist Hermes cache", e);
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
                this.sha1 = MiscUtils.calculateLocalFileSha1(resourcePackPath);
                this.uuid = generateUUID(this.sha1);
                this.saveCacheToDisk();

                String existUrl = checkExists(this.sha1);
                if (existUrl != null) {
                    this.url = existUrl;
                    this.saveCacheToDisk();
                    return;
                }

                byte[] packData = Files.readAllBytes(resourcePackPath);
                String uploadUrl = uploadPack(this.sha1, packData);
                if (uploadUrl == null) {
                    throw new RuntimeException("Hermes upload returned no URL");
                }
                this.url = uploadUrl;
                this.saveCacheToDisk();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private String checkExists(String sha1) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + "v1/exists/" + sha1))
                .header("X-Pack-Secret", this.secret)
                .GET()
                .build();

        HttpResponse<String> response = HttpClientManager.get().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (json.has("exists") && json.get("exists").getAsBoolean() && json.has("url")) {
            return json.get("url").getAsString();
        }
        return null;
    }

    private String uploadPack(String sha1, byte[] data) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.baseUrl + "v1/upload/" + sha1))
                .header("X-Pack-Secret", this.secret)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();

        HttpResponse<String> response = HttpClientManager.get().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.has("url") ? json.get("url").getAsString() : null;
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
    public ResourcePackHostType<HermesHost> type() {
        return ResourcePackHosts.HERMES;
    }

    private static class Factory implements ResourcePackHostFactory<HermesHost> {
        private static final String[] USE_ENVIRONMENT_VARIABLES = new String[]{"use_environment_variables", "use-environment-variables"};
        private static final String[] SERVER_URL = new String[]{"server_url", "server-url"};
        private static final String[] CACHE_FILE_NAME = new String[]{"cache_file_name", "cache-file-name"};

        @Override
        public HermesHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String serverUrl = section.getNonEmptyString(SERVER_URL);
            String secret = useEnv ? getNonNullEnvironmentVariable(section, "CE_HERMES_SECRET") : section.getNonEmptyString("secret");
            Path cacheFilePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache")
                    .resolve(section.getValue(CACHE_FILE_NAME, it -> it.getAsNonEmptyString().replace("/", "_"), "hermes.json"));
            return new HermesHost(serverUrl, secret, cacheFilePath);
        }
    }
}
