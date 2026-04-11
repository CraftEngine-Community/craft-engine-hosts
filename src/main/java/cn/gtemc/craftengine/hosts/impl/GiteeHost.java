package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.HashUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

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

public final class GiteeHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<GiteeHost> FACTORY = new Factory();
    private static final String GITEE_API = "https://gitee.com/api/v5";
    private final String owner;
    private final String repo;
    private final String token;
    private final String uploadPath;
    private final Path cacheFilePath;
    private String cachedSha1;
    private String downloadUrl;

    private GiteeHost(String owner, String repo, String token, String uploadPath, Path cacheFilePath) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.uploadPath = uploadPath;
        this.cacheFilePath = cacheFilePath;
        this.readCacheFromDisk();
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(UUID player) {
        return CompletableFuture.completedFuture(List.of(ResourcePackDownloadData.of(
                this.downloadUrl, UUID.nameUUIDFromBytes(this.cachedSha1.getBytes(StandardCharsets.UTF_8)), this.cachedSha1
        )));
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public CompletableFuture<Void> upload(Path resourcePackPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                long uploadStart = System.currentTimeMillis();
                CraftEngineHosts.instance().getLogger().info("[Gitee] Uploading resource pack...");
                this.cachedSha1 = HashUtils.calculateLocalFileSha1(resourcePackPath);
                this.saveCacheToDisk();

                String sha = null;
                String checkUrl = String.format("%s/repos/%s/%s/contents/%s",
                        GITEE_API, owner, repo, uploadPath);

                HttpRequest checkRequest = HttpRequest.newBuilder()
                        .uri(URI.create(checkUrl))
                        .header("Authorization", "token " + token)
                        .GET()
                        .build();

                HttpResponse<String> checkResponse = HttpClientManager.get().send(checkRequest, HttpResponse.BodyHandlers.ofString());

                if (checkResponse.statusCode() == 200 || checkResponse.statusCode() == 201) {
                    JsonObject existingFile = JsonParser.parseString(checkResponse.body()).getAsJsonObject();
                    sha = existingFile.get("sha").getAsString();
                }

                byte[] fileContent = Files.readAllBytes(resourcePackPath);
                String contentBase64 = Base64.getEncoder().encodeToString(fileContent);

                String uploadUrl = String.format("%s/repos/%s/%s/contents/%s",
                        GITEE_API, owner, repo, uploadPath);

                JsonObject uploadBody = new JsonObject();
                uploadBody.addProperty("message", "Upload resource pack");
                uploadBody.addProperty("content", contentBase64);
                if (sha != null) {
                    uploadBody.addProperty("sha", sha);
                }

                HttpRequest uploadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .header("Authorization", "token " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(uploadBody.toString()))
                        .build();

                HttpResponse<String> uploadResponse = HttpClientManager.get().send(uploadRequest, HttpResponse.BodyHandlers.ofString());

                if (uploadResponse.statusCode() == 200 || uploadResponse.statusCode() == 201) {
                    JsonObject responseJson = JsonParser.parseString(uploadResponse.body()).getAsJsonObject();
                    this.downloadUrl = responseJson.getAsJsonObject("content").get("download_url").getAsString();
                    saveCacheToDisk();

                    long uploadTime = System.currentTimeMillis() - uploadStart;
                    CraftEngineHosts.instance().getLogger().info(String.format("[Gitee] Upload request completed in %s ms", uploadTime));
                } else {
                    CraftEngineHosts.instance().getLogger().warning("[Gitee] Upload failed with status " + uploadResponse.statusCode() + ": " + uploadResponse.body());
                    throw new RuntimeException("Upload failed with status " + uploadResponse.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                CraftEngineHosts.instance().getLogger().log(Level.WARNING, "[Gitee] Error during upload: ", e);
                throw new RuntimeException(e);
            }
        });
    }

    private void readCacheFromDisk() {
        if (!Files.exists(this.cacheFilePath) || !Files.isRegularFile(this.cacheFilePath)) return;

        try (InputStream is = Files.newInputStream(this.cacheFilePath)) {
            Map<String, String> cache = GsonHelper.parseJson(is);

            this.cachedSha1 = cache.get("sha1");
            this.downloadUrl = cache.get("download_url");
        } catch (Exception e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "[Gitee] Failed to load cache from disk", e);
        }
    }

    private void saveCacheToDisk() {
        Map<String, String> cache = new HashMap<>();
        cache.put("sha1", this.cachedSha1 != null ? this.cachedSha1 : "");
        cache.put("download_url", this.downloadUrl != null ? this.downloadUrl : "");
        try {
            Files.createDirectories(this.cacheFilePath.getParent());
            Files.writeString(
                    this.cacheFilePath,
                    GsonHelper.toJson(cache),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "[Gitee] Failed to persist cache to disk", e);
        }
    }

    @Override
    public boolean canUpload() {
        return true;
    }

    @Override
    public ResourcePackHostType<GiteeHost> type() {
        return ResourcePackHosts.GITEE;
    }

    private static class Factory implements ResourcePackHostFactory<GiteeHost> {
        private static final String[] USE_ENVIRONMENT_VARIABLES = new String[]{"use_environment_variables", "use-environment-variables"};
        private static final String[] CACHE_FILE_NAME = new String[] {"cache_file_name", "cache-file-name"};

        @Override
        public GiteeHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String owner = section.getNonEmptyString("owner");
            String repo = section.getNonEmptyString("repo");
            String token = useEnv ? getNonNullEnvironmentVariable(section, "CE_GITEE_TOKEN") : section.getNonEmptyString("token");
            String uploadPath = section.getNonEmptyString("path");
            Path cacheFilePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache")
                    .resolve(section.getValue(CACHE_FILE_NAME, it -> it.getAsNonEmptyString().replace("/", "_"), "gitee.json"));
            return new GiteeHost(owner, repo, token, uploadPath, cacheFilePath);
        }
    }
}