package cn.gtemc.craftengine.hosts.impl;

import cn.gtemc.craftengine.CraftEngineHosts;
import cn.gtemc.craftengine.hosts.ResourcePackHosts;
import cn.gtemc.craftengine.util.GsonHelper;
import cn.gtemc.craftengine.util.MiscUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.concurrent.*;
import java.util.logging.Level;

public final class EdgeOnePagesHost implements ResourcePackHost {
    public static final ResourcePackHostFactory<EdgeOnePagesHost> FACTORY = new Factory();
    private final String url;
    private final String endpoint;
    private final String apiToken;
    private final String projectId;
    private final Path cacheFilePath;
    private String cachedSha1;

    public EdgeOnePagesHost(String url, String endpoint, String apiToken, String projectId, Path cacheFilePath) {
        this.url = url;
        this.endpoint = endpoint;
        this.apiToken = apiToken;
        this.projectId = projectId;
        this.cacheFilePath = cacheFilePath;
        this.readCacheFromDisk();
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        return CompletableFuture.completedFuture(List.of(ResourcePackDownloadData.of(
                this.url, UUID.nameUUIDFromBytes(this.cachedSha1.getBytes(StandardCharsets.UTF_8)), this.cachedSha1
        )));
    }

    @Override
    public CompletableFuture<Void> upload(Path resourcePackPath) {
        return CompletableFuture.runAsync(() -> {
            this.cachedSha1 = MiscUtils.calculateLocalFileSha1(resourcePackPath);
            this.saveCacheToDisk();
            try {
                byte[] fileData = Files.readAllBytes(resourcePackPath);
                String objectKey = uploadToCos(this.endpoint, this.apiToken, this.projectId, fileData);
                createAndWait(this.endpoint, this.apiToken, this.projectId, objectKey);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean canUpload() {
        return true;
    }

    @Override
    public ResourcePackHostType<EdgeOnePagesHost> type() {
        return ResourcePackHosts.EDGEONE_PAGES;
    }

    private static String uploadToCos(String endpoint, String token, String projectId, byte[] fileData) throws Exception {
        JsonObject reqData = new JsonObject();
        reqData.addProperty("ProjectId", projectId);
        JsonObject resp = callApi(endpoint, token, "DescribePagesCosTempToken", reqData);

        if (!resp.has("Credentials") || !resp.has("Bucket") || !resp.has("Region") || !resp.has("TargetPath")) {
            throw new RuntimeException("Failed to obtain COS temporary credentials, response: " + resp);
        }

        JsonObject credentials = resp.getAsJsonObject("Credentials");

        if (!credentials.has("TmpSecretId") || !credentials.has("TmpSecretKey") || !credentials.has("Token")) {
            throw new RuntimeException("Failed to obtain COS temporary credentials, response: " + credentials);
        }

        String secretId = credentials.get("TmpSecretId").getAsString();
        String secretKey = credentials.get("TmpSecretKey").getAsString();
        String sessionToken = credentials.get("Token").getAsString();
        String bucket = resp.get("Bucket").getAsString();
        String region = resp.get("Region").getAsString();

        String targetPath = resp.get("TargetPath").getAsString().replaceAll("^/+", "").replaceAll("/+$", "");

        String objectKey = targetPath + "/index.html";

        String[] authAndUrl = buildCosAuth(secretId, secretKey, sessionToken, bucket, region, objectKey);
        String authorization = authAndUrl[0];
        String url = authAndUrl[1];

        CraftEngineHosts.instance().getLogger().info("Uploading to COS: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/zip")
                .header("x-cos-security-token", sessionToken)
                .header("Authorization", authorization)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileData))
                .build();

        HttpResponse<String> putResp = HttpClientManager.get().send(request, HttpResponse.BodyHandlers.ofString());
        if (putResp.statusCode() >= 400) {
            throw new RuntimeException("COS upload failed: " + putResp.statusCode() + " " + putResp.body());
        }

        CraftEngineHosts.instance().getLogger().info("Upload successful");
        return objectKey;
    }

    private static void createAndWait(String endpoint, String token, String projectId, String objectKey) throws Exception {
        JsonObject createReq = new JsonObject();
        createReq.addProperty("ProjectId", projectId);
        createReq.addProperty("ViaMeta", "Upload");
        createReq.addProperty("Provider", "Upload");
        createReq.addProperty("Env", "Production");
        createReq.addProperty("DistType", "File");
        createReq.addProperty("TempBucketPath", objectKey);
        createReq.addProperty("BuildFrom", "CLI");

        JsonObject createResp = callApi(endpoint, token, "CreatePagesDeployment", createReq);
        if (!createResp.has("DeploymentId")) {
            throw new RuntimeException("Failed to create deployment, response: " + createResp);
        }
        String deploymentId = createResp.get("DeploymentId").getAsString();
        CraftEngineHosts.instance().getLogger().info("Deployment created, DeploymentId=" + deploymentId + ", waiting for completion...");

        JsonObject pollReq = new JsonObject();
        pollReq.addProperty("ProjectId", projectId);
        pollReq.addProperty("Offset", 0);
        pollReq.addProperty("Limit", 50);
        pollReq.addProperty("OrderBy", "CreatedOn");
        pollReq.addProperty("Order", "Desc");

        CompletableFuture<Boolean> checkFuture = new CompletableFuture<>();
        @SuppressWarnings("resource")
        ScheduledFuture<?> future = CraftEngineHosts.instance().scheduler().scheduleWithFixedDelay(() -> {
            try {
                JsonObject body = callApi(endpoint, token, "DescribePagesDeployments", pollReq);
                if (!body.has("Deployments")) {
                    throw new RuntimeException("Failed to query deployment list, response: " + body);
                }
                JsonArray records = body.getAsJsonArray("Deployments");

                JsonObject deployment = null;
                if (records != null) {
                    for (JsonElement recordElem : records) {
                        JsonObject r = recordElem.getAsJsonObject();
                        if (deploymentId.equals(r.get("DeploymentId").getAsString())) {
                            deployment = r;
                            break;
                        }
                    }
                }

                if (deployment == null) { // 没有应该就是部署好了
                    checkFuture.complete(true);
                    return;
                }

                String status = deployment.get("Status").getAsString();
                CraftEngineHosts.instance().getLogger().info("Current status: " + status);

                String lowerStatus = status.toLowerCase();
                if (!lowerStatus.equals("process") && !lowerStatus.equals("pending")) {
                    if (!lowerStatus.equals("success")) {
                        checkFuture.completeExceptionally(new RuntimeException("Deployment failed, status=" + status));
                    } else {
                        checkFuture.complete(true);
                    }
                }
            } catch (Exception e) {
                checkFuture.completeExceptionally(e);
            }
        }, 0, 5, TimeUnit.SECONDS);

        try {
            checkFuture.get(10, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new RuntimeException("Deployment timeout");
        } finally {
            future.cancel(true);
        }
    }

    private static String[] buildCosAuth(String secretId, String secretKey, String sessionToken,
                                         String bucket, String region, String objectKey) throws Exception {
        long start = (System.currentTimeMillis() / 1000) - 1;
        long end = start + 900;
        String signTime = start + ";" + end;

        String host = bucket + ".cos." + region + ".myqcloud.com";
        String encodedPath = "/" + MiscUtils.urlEncode(objectKey).replace("%2F", "/");
        String url = "https://" + host + encodedPath;

        Map<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-cos-security-token", sessionToken);

        String headerKeys = String.join(";", headers.keySet());
        List<String> headerKeyValues = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerKeyValues.add(entry.getKey() + "=" + MiscUtils.urlEncode(headers.get(entry.getKey())));
        }
        String headerStr = String.join("&", headerKeyValues);

        String httpString = "put\n" + encodedPath + "\n\n" + headerStr + "\n";
        String stringToSign = "sha1\n" + signTime + "\n" + MiscUtils.sha1(httpString) + "\n";

        String signKey = MiscUtils.hmacSha1(secretKey, signTime);
        String signature = MiscUtils.hmacSha1(signKey, stringToSign);

        String authorization = "q-sign-algorithm=sha1&q-ak=" + secretId +
                "&q-sign-time=" + signTime + "&q-key-time=" + signTime +
                "&q-header-list=" + headerKeys + "&q-url-param-list=" +
                "&q-signature=" + signature;

        return new String[]{authorization, url};
    }

    private static JsonObject callApi(String endpoint, String token, String action, JsonObject data) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("Action", action);
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            payload.add(entry.getKey(), entry.getValue());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GsonHelper.toJson(payload)))
                .build();

        HttpResponse<String> response = HttpClientManager.get().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP request failed: " + response.statusCode() + " " + response.body());
        }

        JsonObject body = GsonHelper.get().fromJson(response.body(), JsonObject.class);
        JsonElement codeElem = body.get("Code");
        int code = codeElem != null ? codeElem.getAsInt() : -1;

        if (code != 0) {
            throw new RuntimeException(action + " failed: " + response.body());
        }

        if (!body.has("Data")) {
            throw new RuntimeException(action + " failed: " + response.body());
        }

        JsonObject bodyData = body.getAsJsonObject("Data");

        if (!bodyData.has("Response")) {
            throw new RuntimeException(action + " failed: " + response.body());
        }

        return bodyData.getAsJsonObject("Response");
    }

    private void readCacheFromDisk() {
        if (!Files.exists(this.cacheFilePath) || !Files.isRegularFile(this.cacheFilePath)) return;
        try (InputStream is = Files.newInputStream(this.cacheFilePath)) {
            Map<String, String> cache = GsonHelper.parseJson(is);
            this.cachedSha1 = cache.get("sha1");
        } catch (Exception e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to load EdgeOnePages cache disk", e);
        }
    }

    private void saveCacheToDisk() {
        Map<String, String> cache = new HashMap<>();
        cache.put("sha1", this.cachedSha1 != null ? this.cachedSha1 : "");
        try {
            Files.createDirectories(this.cacheFilePath.getParent());
            Files.writeString(
                    this.cacheFilePath,
                    GsonHelper.toJson(cache),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            CraftEngineHosts.instance().getLogger().log(Level.WARNING, "Failed to persist EdgeOnePages cache", e);
        }
    }

    private static class Factory implements ResourcePackHostFactory<EdgeOnePagesHost> {
        private static final String[] USE_ENVIRONMENT_VARIABLES = new String[]{"use_environment_variables", "use-environment-variables"};
        private static final String[] API_TOKEN = new String[]{"api_token", "api-token"};
        private static final String[] PROJECT_ID = new String[]{"project_id", "project-id"};
        private static final String[] CACHE_FILE_NAME = new String[] {"cache_file_name", "cache-file-name"};
        private static final String[] IS_INTERNATIONAL = new String[] {"is_international", "is-international"};

        @Override
        public EdgeOnePagesHost create(ConfigSection section) {
            boolean useEnv = section.getBoolean(USE_ENVIRONMENT_VARIABLES);
            String url = section.getNonEmptyString("url");
            String endpoint;
            if (section.containsKey("endpoint")) {
                endpoint = section.getNonEmptyString("endpoint");
            } else {
                endpoint = section.getBoolean(IS_INTERNATIONAL) ? "https://pages-api.edgeone.ai/v1" : "https://pages-api.cloud.tencent.com/v1";
            }
            String apiToken = useEnv ? getNonNullEnvironmentVariable(section, "CE_EDGEONE_PAGES_API_TOKEN") : section.getNonEmptyString(API_TOKEN);
            String projectId = section.getNonEmptyString(PROJECT_ID);
            Path cacheFilePath = CraftEngineHosts.instance().dataFolderPath().resolve("cache")
                    .resolve(section.getValue(CACHE_FILE_NAME, it -> it.getAsNonEmptyString().replace("/", "_"), "edgeone_pages.json"));
            return new EdgeOnePagesHost(url, endpoint, apiToken, projectId, cacheFilePath);
        }
    }
}
