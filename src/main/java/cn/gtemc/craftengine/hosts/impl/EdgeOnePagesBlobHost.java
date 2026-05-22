package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.MiscUtils;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
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

public final class EdgeOnePagesBlobHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<EdgeOnePagesBlobHost> FACTORY = new Factory();
    private final String apiUrl;
    private final String apiSecret;
    private final String storePath;
    private final Path cacheFilePath;
    private String sha1;

    private EdgeOnePagesBlobHost(String apiUrl, String apiSecret, String storePath, Path cacheFilePath) {
        this.apiUrl = apiUrl;
        this.apiSecret = apiSecret;
        this.storePath = storePath;
        this.cacheFilePath = cacheFilePath;
        this.readCacheFromDisk();
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        if (this.sha1 == null || this.sha1.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.completedFuture(List.of(ResourcePackDownloadData.of(
                this.apiUrl + "/download?key=" + this.storePath, UUID.nameUUIDFromBytes(this.sha1.getBytes(StandardCharsets.UTF_8)), this.sha1
        )));
    }

    @Override
    public CompletableFuture<Void> upload(Path resourcePackPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                this.sha1 = MiscUtils.calculateLocalFileSha1(resourcePackPath);
                this.saveCacheToDisk();
                byte[] fileData = Files.readAllBytes(resourcePackPath);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(this.apiUrl + "/upload?key=" + this.storePath))
                        .header("X-Auth-Key", this.apiSecret)
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(fileData))
                        .build();
                HttpResponse<String> response = HttpClientManager.get().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to upload resource pack, status: " + response.statusCode() + ", body: " + response.body());
                }
                CraftEngineHosts.instance().getLogger().info("Upload successful, path: " + GsonHelper.parseJson(response.body()).get("key"));
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean canUpload() {
        return true;
    }

    @Override
    public ResourcePackHostType<EdgeOnePagesBlobHost> type() {
        return ResourcePackHosts.EDGEONE_PAGES_BLOB;
    }

    private void readCacheFromDisk() {
        if (!Files.exists(this.cacheFilePath) || !Files.isRegularFile(this.cacheFilePath)) return;
        try (InputStream is = Files.newInputStream(this.cacheFilePath)) {
            Map<String, String> cache = GsonHelper.parseJson(is);
            this.sha1 = cache.get("sha1");
        } catch (Exception e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to load EdgeOnePagesBlob cache disk", e);
        }
    }

    private void saveCacheToDisk() {
        Map<String, String> cache = new HashMap<>();
        cache.put("sha1", this.sha1 != null ? this.sha1 : "");
        try {
            Files.createDirectories(this.cacheFilePath.getParent());
            Files.writeString(
                    this.cacheFilePath,
                    GsonHelper.toJson(cache),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to persist EdgeOnePagesBlob cache", e);
        }
    }

    private static class Factory implements ResourcePackHostFactory<EdgeOnePagesBlobHost> {
        private static final String[] USE_ENVIRONMENT_VARIABLES = new String[]{"use_environment_variables", "use-environment-variables"};
        private static final String[] CACHE_FILE_NAME = new String[] {"cache_file_name", "cache-file-name"};
        private static final String[] API_URL = new String[]{"api_url", "api-url"};
        private static final String[] API_SECRET = new String[]{"api_secret", "api-secret"};
        private static final String[] STORE_PATH = new String[]{"store_path", "store-path"};

        @Override
        public EdgeOnePagesBlobHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String apiUrl = section.getNonEmptyString(API_URL);
            String apiSecret = useEnv ? getNonNullEnvironmentVariable(section, "CE_EDGEONE_PAGES_BLOB_API_SECRET") : section.getNonEmptyString(API_SECRET);
            String storePath = section.getValue(STORE_PATH, ConfigValue::getAsNonEmptyString, "resource_pack.zip");
            Path cacheFilePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache")
                    .resolve(section.getValue(CACHE_FILE_NAME, it -> it.getAsNonEmptyString().replace("/", "_"), "edgeone_pages_blob.json"));
            return new EdgeOnePagesBlobHost(apiUrl, apiSecret, storePath, cacheFilePath);
        }
    }
}
