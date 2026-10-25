package io.kubespawn.platform.github;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class GitHubAppClient implements GitHubGateway {
    private final GitHubSettings settings;
    private final ObjectMapper json;
    private final URI api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final RSAPrivateKey privateKey;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private record Token(String value, Instant expires) {}

    @Autowired
    public GitHubAppClient(GitHubSettings settings, ObjectMapper json) { this(settings, json, URI.create("https://api.github.com")); }

    GitHubAppClient(GitHubSettings settings, ObjectMapper json, URI api) {
        this.settings=settings; this.json=json; this.api=api;
        try {
            if (settings.enabled && Files.size(settings.privateKeyFile)>16*1024) throw new IllegalArgumentException("Key too large.");
            privateKey=settings.enabled ? readKey(Files.readString(settings.privateKeyFile)) : null;
        } catch (Exception invalid) { throw new IllegalArgumentException("Cannot load the configured GitHub App RSA private key."); }
    }

    @Override public boolean configured() { return settings.enabled; }

    @Override public JsonNode get(BuildModels.Repository repository, String path) {
        return readJson(send(api.resolve(path), "GET", token(repository, false), null), 1024*1024);
    }

    @Override public byte[] workflow(BuildModels.Repository repository, String revision) {
        JsonNode result=get(repository, "/repos/" + repository.name() + "/contents/" + repository.workflowPath() + "?ref=" + revision);
        if (!result.path("encoding").asText().equals("base64")) throw new VerificationRejected("WORKFLOW_FORMAT_REJECTED");
        byte[] bytes=Base64.getMimeDecoder().decode(result.path("content").asText());
        if (bytes.length>128*1024) throw new VerificationRejected("WORKFLOW_TOO_LARGE");
        return bytes;
    }

    @Override public byte[] artifact(BuildModels.Repository repository, long artifactId) {
        var response=send(api.resolve("/repos/" + repository.name() + "/actions/artifacts/" + artifactId + "/zip"),
            "GET", token(repository, false), null);
        try (var ignored=response.body()) {
            if (response.statusCode()!=302) throw new IllegalStateException("GitHub artifact download unavailable.");
            URI download=URI.create(response.headers().firstValue("Location").orElseThrow());
            String host=download.getHost();
            if (!"https".equals(download.getScheme()) || download.getUserInfo()!=null || download.getPort()!=-1
                    || host==null || !(host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com")
                    || host.endsWith(".githubusercontent.com"))) {
                throw new VerificationRejected("ARTIFACT_REDIRECT_REJECTED");
            }
            // Signed download URL only. Never forward installation credentials across hosts.
            return readBytes(send(download, "GET", null, null), 512*1024);
        } catch (VerificationRejected rejected) { throw rejected; }
        catch (Exception failure) { throw new IllegalStateException("GitHub artifact download unavailable."); }
    }

    @Override public void dispatch(BuildModels.Repository repository, String sourceSha, String requestId) {
        String body=json.writeValueAsString(Map.of("ref", repository.branch(),
            "inputs", Map.of("source_sha", sourceSha, "request_id", requestId)));
        var response=send(api.resolve("/repos/" + repository.name() + "/actions/workflows/" + repository.workflowId() + "/dispatches"),
            "POST", token(repository, true), body);
        if (response.statusCode()==200) {
            if (readJson(response,16*1024).path("workflow_run_id").asLong()<1) throw new IllegalStateException("GitHub workflow dispatch unavailable.");
            return;
        }
        try (var ignored=response.body()) {
            if (response.statusCode()!=204) throw new IllegalStateException("GitHub workflow dispatch unavailable.");
        } catch (Exception failure) { throw new IllegalStateException("GitHub workflow dispatch unavailable."); }
    }

    private synchronized String token(BuildModels.Repository repository, boolean write) {
        if (!configured()) throw new IllegalStateException("GitHub integration is disabled.");
        String scope=repository.installationId() + ":" + repository.id() + ":" + write;
        Token existing=tokens.get(scope);
        if (existing!=null && existing.expires().isAfter(Instant.now().plusSeconds(60))) return existing.value();
        String body=json.writeValueAsString(Map.of("repository_ids", List.of(repository.id()),
            "permissions", Map.of("contents", "read", "actions", write ? "write" : "read")));
        JsonNode result=readJson(send(api.resolve("/app/installations/" + repository.installationId() + "/access_tokens"),
            "POST", jwt(), body), 16*1024);
        String value=result.path("token").asText();
        Instant expires=Instant.parse(result.path("expires_at").asText());
        if (value.isBlank() || !expires.isAfter(Instant.now().plusSeconds(60))) throw new IllegalStateException("GitHub token unavailable.");
        tokens.put(scope, new Token(value, expires));
        return value;
    }

    private String jwt() {
        try {
            long now=Instant.now().getEpochSecond();
            var encoder=Base64.getUrlEncoder().withoutPadding();
            String header=encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String claims=encoder.encodeToString(json.writeValueAsBytes(Map.of("iat", now-60, "exp", now+540, "iss", settings.appId)));
            String signing=header + "." + claims;
            Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(privateKey);
            signer.update(signing.getBytes(StandardCharsets.US_ASCII));
            return signing + "." + encoder.encodeToString(signer.sign());
        } catch (Exception failure) { throw new IllegalStateException("GitHub App authentication unavailable."); }
    }

    private HttpResponse<InputStream> send(URI uri, String method, String token, String body) {
        try {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "KubeSpawn/0.4.0");
            if (token!=null) request.header("Authorization", "Bearer " + token);
            if (body!=null) request.header("Content-Type", "application/json");
            return http.send(request.method(method, body==null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub request interrupted."); }
        catch (Exception failure) { throw new IllegalStateException("GitHub request unavailable."); }
    }

    private JsonNode readJson(HttpResponse<InputStream> response, int limit) {
        byte[] bytes=readBytes(response,limit);
        try { return json.readTree(bytes); }
        catch (Exception invalid) { throw new IllegalStateException("GitHub JSON response unavailable."); }
    }

    private byte[] readBytes(HttpResponse<InputStream> response, int limit) {
        try (InputStream body=response.body()) {
            if (response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("GitHub API request unsuccessful.");
            byte[] data=body.readNBytes(limit+1);
            if (data.length>limit) throw new VerificationRejected("GITHUB_RESPONSE_TOO_LARGE");
            return data;
        } catch (VerificationRejected rejected) { throw rejected; }
        catch (Exception failure) { throw new IllegalStateException("GitHub response unavailable."); }
    }

    static RSAPrivateKey readKey(String pem) throws Exception {
        if (pem.length()>16*1024) throw new IllegalArgumentException("Key too large.");
        boolean pkcs1=pem.contains("-----BEGIN RSA PRIVATE KEY-----");
        if (!pkcs1 && !pem.contains("-----BEGIN PRIVATE KEY-----")) throw new IllegalArgumentException("RSA PEM required.");
        byte[] bytes=Base64.getMimeDecoder().decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        if (pkcs1) {
            var contents=new ByteArrayOutputStream();
            contents.writeBytes(HexFormat.of().parseHex("020100300d06092a864886f70d0101010500"));
            contents.writeBytes(der(4, bytes));
            bytes=der(0x30, contents.toByteArray());
        }
        var key=(RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        if (key.getModulus().bitLength()<2048) throw new IllegalArgumentException("RSA key too small.");
        return key;
    }

    private static byte[] der(int tag, byte[] bytes) {
        var output=new ByteArrayOutputStream(); output.write(tag);
        if (bytes.length<128) output.write(bytes.length);
        else { int count=(32-Integer.numberOfLeadingZeros(bytes.length)+7)/8; output.write(0x80|count);
            for (int i=count-1; i>=0; i--) output.write(bytes.length >> (i*8)); }
        output.writeBytes(bytes); return output.toByteArray();
    }
}
