package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.HashUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.momirealms.craftengine.core.pack.host.ResourcePackDownloadData;
import net.momirealms.craftengine.core.pack.host.ResourcePackHost;
import net.momirealms.craftengine.core.pack.host.ResourcePackHostFactory;
import net.momirealms.craftengine.core.pack.host.ResourcePackHostType;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class PolymathHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<PolymathHost> FACTORY = new Factory();
    private final String serverUrl;
    private final String secret;
    private final HttpClient httpClient;
    private String sha1;
    private String url;
    private UUID uuid;

    public PolymathHost(String serverUrl, String secret, ProxySelector proxy) {
        this.serverUrl = serverUrl;
        this.secret = secret;
        this.httpClient = HttpClient.newBuilder().proxy(proxy).build();
        this.readCacheFromDisk();
    }

    private void readCacheFromDisk() {
        Path cachePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache").resolve("polymath.json");
        if (!Files.exists(cachePath) || !Files.isRegularFile(cachePath)) return;

        try (InputStream is = Files.newInputStream(cachePath)) {
            Map<String, String> cache = GsonHelper.parseJson(is);

            this.sha1 = cache.get("sha1");
            this.uuid = generateUUID(this.sha1);
            this.url = cache.get("url");
        } catch (Exception e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "[Polymath] Failed to load cache from disk", e);
        }
    }

    private void saveCacheToDisk() {
        Map<String, String> cache = new HashMap<>();
        cache.put("sha1", this.sha1 != null ? this.sha1 : "");
        cache.put("url", this.url != null ? this.url : "");
        Path cachePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache").resolve("polymath.json");
        try {
            Files.createDirectories(cachePath.getParent());
            Files.writeString(
                    cachePath,
                    GsonHelper.toJson(cache),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "[Polymath] Failed to persist cache to disk", e);
        }
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(UUID player) {
        return CompletableFuture.completedFuture(List.of(ResourcePackDownloadData.of(this.url, this.uuid, this.sha1)));
    }

    @Override
    public CompletableFuture<Void> upload(Path resourcePackPath) {
        return CompletableFuture.runAsync(() -> {
            long uploadStart = System.currentTimeMillis();
            CraftEngineHosts.instance().getLogger().info("[Polymath] Uploading resource pack...");
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

                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                this.url = responseJson.get("url").getAsString();
                this.saveCacheToDisk();
                long uploadTime = System.currentTimeMillis() - uploadStart;
                CraftEngineHosts.instance().getLogger().info(String.format("[Polymath] Upload request completed in %sms", uploadTime));
            } catch (IOException | JsonSyntaxException  | InterruptedException e) {
                CraftEngineHosts.instance().getLogger().log(Level.WARNING, "[Polymath] Error during upload", e);
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

        @Override
        public PolymathHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String serverUrl = section.getNonEmptyString(SERVER_URL);
            String secret = useEnv ? getNonNullEnvironmentVariable(section, "CE_POLYMATH_SECRET") : section.getNonEmptyString("secret");
            ProxySelector proxy = getProxySelector(section.getSection("proxy"));
            return new PolymathHost(serverUrl, secret, proxy);
        }
    }
}
