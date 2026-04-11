package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.HashUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class GitHubHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<GitHubHost> FACTORY = new Factory();
    private static final String GITHUB_API = "https://api.github.com";
    private final String owner;
    private final String repo;
    private final String token;
    private final String branch;
    private final String uploadPath;
    private final Path cacheFilePath;
    private String cachedSha1;
    private String downloadUrl;

    private GitHubHost(String owner, String repo, String token, String branch, String uploadPath, Path cacheFilePath) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.branch = branch;
        this.uploadPath = uploadPath;
        this.cacheFilePath = cacheFilePath;
        this.readCacheFromDisk();
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        return CompletableFuture.completedFuture(List.of(ResourcePackDownloadData.of(
                this.downloadUrl, UUID.nameUUIDFromBytes(this.cachedSha1.getBytes(StandardCharsets.UTF_8)), this.cachedSha1
        )));
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public CompletableFuture<Void> upload(Path resourcePackPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                this.cachedSha1 = HashUtils.calculateLocalFileSha1(resourcePackPath);
                this.saveCacheToDisk();

                String sha = null;
                String checkUrl = String.format("%s/repos/%s/%s/contents/%s?ref=%s",
                        GITHUB_API, owner, repo, uploadPath, branch);

                HttpRequest checkRequest = HttpRequest.newBuilder()
                        .uri(URI.create(checkUrl))
                        .header("Authorization", "token " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .GET()
                        .build();

                HttpResponse<String> checkResponse = HttpClientManager.get().send(checkRequest, HttpResponse.BodyHandlers.ofString());

                if (checkResponse.statusCode() == 200) {
                    JsonObject existingFile = JsonParser.parseString(checkResponse.body()).getAsJsonObject();
                    sha = existingFile.get("sha").getAsString();
                }

                byte[] fileContent = Files.readAllBytes(resourcePackPath);
                String contentBase64 = Base64.getEncoder().encodeToString(fileContent);

                String uploadUrl = String.format("%s/repos/%s/%s/contents/%s",
                        GITHUB_API, owner, repo, uploadPath);

                JsonObject uploadBody = new JsonObject();
                uploadBody.addProperty("message", "Upload resource pack");
                uploadBody.addProperty("content", contentBase64);
                uploadBody.addProperty("branch", branch);
                if (sha != null) {
                    uploadBody.addProperty("sha", sha);
                }

                HttpRequest uploadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .header("Authorization", "token " + token)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(uploadBody.toString()))
                        .build();

                HttpResponse<String> uploadResponse = HttpClientManager.get().send(uploadRequest, HttpResponse.BodyHandlers.ofString());

                if (uploadResponse.statusCode() == 200 || uploadResponse.statusCode() == 201) {
                    JsonObject responseJson = JsonParser.parseString(uploadResponse.body()).getAsJsonObject();
                    this.downloadUrl = responseJson.get("content").getAsJsonObject().get("download_url").getAsString();
                    saveCacheToDisk();
                } else {
                    throw new RuntimeException("Upload failed with status " + uploadResponse.statusCode());
                }
            } catch (IOException | InterruptedException e) {
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
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to load GitHub cache disk", e);
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
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to persist GitHub cache", e);
        }
    }

    @Override
    public boolean canUpload() {
        return true;
    }

    @Override
    public ResourcePackHostType<GitHubHost> type() {
        return ResourcePackHosts.GITHUB;
    }

    private static class Factory implements ResourcePackHostFactory<GitHubHost> {
        private static final String[] USE_ENVIRONMENT_VARIABLES = new String[]{"use_environment_variables", "use-environment-variables"};
        private static final String[] CACHE_FILE_NAME = new String[] {"cache_file_name", "cache-file-name"};

        @Override
        public GitHubHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String owner = section.getNonEmptyString("owner");
            String repo = section.getNonEmptyString("repo");
            String token = useEnv ? getNonNullEnvironmentVariable(section, "CE_GITHUB_TOKEN") : section.getNonEmptyString("token");
            String branch = section.getValue("branch", ConfigValue::getAsNonEmptyString, "main");
            String uploadPath = section.getNonEmptyString("path");
            Path cacheFilePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache")
                    .resolve(section.getValue(CACHE_FILE_NAME, it -> it.getAsNonEmptyString().replace("/", "_"), "github.json"));
            return new GitHubHost(owner, repo, token, branch, uploadPath, cacheFilePath);
        }
    }
}