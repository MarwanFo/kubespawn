package io.kubespawn.platform.provisioning;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KubernetesClientContractTest {
    @Test void namespaceLookupUsesTheCoreClusterScopedEndpoint() throws Exception {
        var path=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            path.set(exchange.getRequestURI().getPath());
            byte[] response="{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"code\":404,\"reason\":\"NotFound\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(404,response.length); exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try(var client=new KubernetesClientBuilder().withConfig(new ConfigBuilder(Config.empty())
                .withMasterUrl("http://127.0.0.1:"+server.getAddress().getPort()).build()).build()) {
            assertThat(KubernetesProvisioner.operation(client,"v1","Namespace","ks-test",null).get()).isNull();
            assertThat(path.get()).isEqualTo("/api/v1/namespaces/ks-test");
        } finally { server.stop(0); }
    }
}
