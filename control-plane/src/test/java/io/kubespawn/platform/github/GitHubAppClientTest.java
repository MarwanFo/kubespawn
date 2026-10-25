package io.kubespawn.platform.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class GitHubAppClientTest {
    @TempDir Path directory;
    final ObjectMapper json=JsonMapper.builder().build();
    HttpServer server;
    KeyPair key;
    Path keyFile;
    String jwt;
    String lastAuthorization;
    JsonNode tokenRequest;
    JsonNode dispatchRequest;
    final AtomicInteger tokenRequests=new AtomicInteger();
    int getStatus=200;
    String getBody="{}";

    @BeforeEach void server() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair();
        keyFile=directory.resolve("test-app.pem"); Files.writeString(keyFile,pem("PRIVATE KEY",key.getPrivate().getEncoded()));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();
            String response=getBody; int status=getStatus;
            if (path.startsWith("/app/installations/")) {
                tokenRequests.incrementAndGet(); jwt=exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                tokenRequest=json.readTree(exchange.getRequestBody().readAllBytes());
                response="{\"token\":\"synthetic-installation-token\",\"expires_at\":\""+java.time.Instant.now().plusSeconds(3600)+"\"}"; status=201;
            } else {
                lastAuthorization=exchange.getRequestHeaders().getFirst("Authorization");
                if (path.endsWith("/dispatches")) { dispatchRequest=json.readTree(exchange.getRequestBody().readAllBytes()); status=204; response=""; }
                if (path.endsWith("/zip")) { exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/must-not-follow"); status=302; response=""; }
            }
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,status==204 ? -1 : body.length);
            if (status!=204) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { if (server!=null) server.stop(0); }

    @Test void appJwtIsSignedAndInstallationTokensAreRepositoryScopedAndCached() throws Exception {
        GitHubAppClient client=client();
        client.get(repository(),"/repos/example/app"); client.get(repository(),"/repos/example/app");
        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(tokenRequest.path("repository_ids").size()).isEqualTo(1);
        assertThat(tokenRequest.path("repository_ids").get(0).asLong()).isEqualTo(101);
        assertThat(tokenRequest.path("permissions").path("contents").asText()).isEqualTo("read");
        assertThat(tokenRequest.path("permissions").path("actions").asText()).isEqualTo("read");
        assertThat(lastAuthorization).isEqualTo("Bearer synthetic-installation-token");
        String[] parts=jwt.split("\\.");
        var verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(key.getPublic());
        verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        var claims=json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("iss").asText()).isEqualTo("123");
        assertThat(claims.path("exp").asLong()-claims.path("iat").asLong()).isEqualTo(600);
    }

    @Test void dispatchUsesASeparateActionsWriteTokenAndPinnedSourceInput() {
        GitHubAppClient client=client(); client.get(repository(),"/repos/example/app");
        client.dispatch(repository(),"a".repeat(40),"00000000-0000-0000-0000-000000000001");
        assertThat(tokenRequests.get()).isEqualTo(2);
        assertThat(tokenRequest.path("permissions").path("actions").asText()).isEqualTo("write");
        assertThat(dispatchRequest.path("ref").asText()).isEqualTo("main");
        assertThat(dispatchRequest.path("inputs").path("source_sha").asText()).isEqualTo("a".repeat(40));
    }

    @Test void artifactRedirectsCannotSendTokensToAnotherHostOrPlainHttp() {
        assertThatThrownBy(() -> client().artifact(repository(),505)).isInstanceOf(VerificationRejected.class)
            .extracting(error -> ((VerificationRejected)error).code()).isEqualTo("ARTIFACT_REDIRECT_REJECTED");
    }

    @Test void apiErrorsDoNotExposeResponseBodies() {
        getStatus=403; getBody="private credential from upstream";
        assertThatThrownBy(() -> client().get(repository(),"/repos/example/app"))
            .isInstanceOf(IllegalStateException.class).hasMessage("GitHub response unavailable.");
    }

    @Test void oversizedApiResponsesAreRejected() {
        getBody="x".repeat(1024*1024+1);
        assertThatThrownBy(() -> client().get(repository(),"/repos/example/app"))
            .isInstanceOf(VerificationRejected.class).extracting(error -> ((VerificationRejected)error).code()).isEqualTo("GITHUB_RESPONSE_TOO_LARGE");
    }

    @Test void githubPkcs1AndStandardPkcs8KeysResolveToTheSameRsaKey() throws Exception {
        byte[] encoded=key.getPrivate().getEncoded();
        // Extract the PKCS#8 privateKey OCTET STRING as a real PKCS#1 RSA key fixture.
        int[] offset={0}; field(encoded,offset); skip(encoded,offset); skip(encoded,offset); byte[] pkcs1=value(encoded,offset);
        assertThat(GitHubAppClient.readKey(pem("RSA PRIVATE KEY",pkcs1)).getModulus())
            .isEqualTo(GitHubAppClient.readKey(Files.readString(keyFile)).getModulus());
    }

    @Test void officialWebhookHmacVectorMatchesAndBodyChangesFail() {
        String signature="sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
        assertThat(GitHubWebhookService.validSignature("It's a Secret to Everybody",signature,"Hello, World!".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(GitHubWebhookService.validSignature("It's a Secret to Everybody",signature,"Changed".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    private GitHubAppClient client() { return new GitHubAppClient(new GitHubSettings(true,"123",keyFile.toString(),"test-webhook-secret-longer-than-32-characters"),json,URI.create("http://127.0.0.1:"+server.getAddress().getPort())); }
    private BuildModels.Repository repository() { return new BuildModels.Repository(101,202,"example/app","main",303,".github/workflows/preview-build.yaml","a".repeat(64),"ghcr.io/example/app-preview-api","ghcr.io/example/app-preview-web","b".repeat(40),"c".repeat(64),1,true); }
    private String pem(String type,byte[] bytes) { return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n"; }
    private int field(byte[] bytes,int[] offset) {
        offset[0]++; int length=bytes[offset[0]++]&255;
        if (length>127) { int count=length&127; length=0; for (int i=0;i<count;i++) length=(length<<8)|(bytes[offset[0]++]&255); }
        return length;
    }
    private void skip(byte[] bytes,int[] offset) { int length=field(bytes,offset); offset[0]+=length; }
    private byte[] value(byte[] bytes,int[] offset) { int length=field(bytes,offset); return Arrays.copyOfRange(bytes,offset[0],offset[0]+length); }
}
