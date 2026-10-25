package io.kubespawn.platform.provisioning;

import static io.kubespawn.platform.provisioning.ProvisioningModels.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.kubespawn.platform.worker.JobLease;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Local development adapter. Each remote mutation rechecks the durable intent/lease. */
@Component
public class KubernetesProvisioner implements ProvisioningGateway {
    static final String OWNER="kubespawn.io/environment-id";
    static final String FENCE="kubespawn.io/fencing-token";
    static final String APPLICATION_SPEC_HASH="kubespawn.io/application-spec-sha256";
    static final Pattern PREVIEW_ASSET=Pattern.compile("(?:src|href)=\\\"([^\\\"]*assets/[^\\\"]+)\\\"");
    private final ProvisioningSettings settings;
    private final ObjectMapper json;
    private final KubernetesClient client;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public KubernetesProvisioner(ProvisioningSettings settings,ObjectMapper json) {
        this.settings=settings; this.json=json;
        try {
            if (settings.enabled) {
                Config config=Config.fromKubeconfig(Files.readString(settings.kubeconfig));
                var builder=new ConfigBuilder(config).withRequestTimeout(5000).withConnectionTimeout(3000)
                    .withRequestRetryBackoffLimit(0);
                if (!settings.server.isBlank()) builder.withMasterUrl(settings.server);
                client=new KubernetesClientBuilder().withConfig(builder.build()).build();
            } else client=null;
        } catch (Exception failure) { throw new IllegalArgumentException("Cannot load the local controller kubeconfig."); }
    }

    @Override public Observation validate(Plan plan) {
        if (!templateHash(settings.chart).equals(plan.templateHash())) return waitFor("TEMPLATE_APPROVAL_REQUIRED","Enroll the current workload chart before provisioning.");
        if (!plan.repository().equals("marwanfo/kubespawn")) return waitFor("APPLICATION_CONTRACT_REQUIRED","This local demo supports only the reviewed KubeSpawn repository.");
        return null;
    }
    @Override public Observation reconcile(Plan plan,JobLease lease,BooleanSupplier current) {
        var blocked=validate(plan); if (blocked!=null) return blocked;
        guard(current);
        baseline(plan,lease,current);
        String application=application(lease);
        String repository="https://github.com/"+plan.repository()+".git";
        put(resource("argoproj.io/v1alpha1","AppProject",application,"argocd",lease,
            "spec",m("sourceRepos",List.of(repository),"destinations",List.of(m("server","https://kubernetes.default.svc","namespace",plan.namespace())),
                "clusterResourceWhitelist",List.of(),"clusterResourceBlacklist",List.of(m("group","*","kind","*")),
                "namespaceResourceWhitelist",List.of(m("group","apps","kind","Deployment"),m("group","apps","kind","StatefulSet"),
                    m("group","","kind","Service"),m("group","","kind","ConfigMap"),m("group","","kind","PersistentVolumeClaim"),m("group","batch","kind","Job")))),lease,current);
        registerNamespace(plan.namespace(),true,current);
        Map<String,Object> values=m("environmentId",plan.environmentId().toString(),"runtimeProfile","kubespawn-demo",
            "api",m("image",m("repository",plan.apiImage(),"digest",plan.apiDigest()),"healthPath","/actuator/health/readiness","livenessPath","/actuator/health/liveness"),
            "web",m("image",m("repository",plan.webImage(),"digest",plan.webDigest()),"healthPath","/__kubespawn/health","livenessPath","/__kubespawn/health"));
        var app=resource("argoproj.io/v1alpha1","Application",application,"argocd",lease,"spec",m(
            "project",application,"destination",m("server","https://kubernetes.default.svc","namespace",plan.namespace()),
            "source",m("repoURL",repository,"targetRevision",plan.chartRevision(),"path","templates/preview-small",
                "helm",m("releaseName","preview","valuesObject",values)),
            "syncPolicy",m("automated",m("prune",true,"selfHeal",true),"syncOptions",List.of("FailOnSharedResource=true"))));
        metadata(app).put("finalizers",List.of("resources-finalizer.argocd.argoproj.io"));
        String specHash=sha256(app.get("spec"));
        @SuppressWarnings("unchecked") var appAnnotations=(Map<String,String>)metadata(app).get("annotations");
        appAnnotations.put(APPLICATION_SPEC_HASH,specHash);
        var before=get("argoproj.io/v1alpha1","Application",application,"argocd");
        boolean beforeIsDesired=before!=null && before.getMetadata().getAnnotations()!=null
            && specHash.equals(before.getMetadata().getAnnotations().get(APPLICATION_SPEC_HASH));
        var beforeStatus=beforeIsDesired ? status(before) : json.createObjectNode();
        put(app,lease,current);
        // Updating the fencing annotation can briefly hide CRD status. A status from the exact
        // desired spec is still authoritative; otherwise read the newly written object.
        var observed=beforeIsDesired ? before : get("argoproj.io/v1alpha1","Application",application,"argocd");
        var status=beforeIsDesired ? beforeStatus : status(observed);
        boolean synced=status.path("sync").path("status").asText().equals("Synced")
            && status.path("sync").path("revision").asText().equals(plan.chartRevision());
        boolean healthy=status.path("health").path("status").asText().equals("Healthy");
        if (synced && healthy) {
            route(plan,lease,current);
            if (!previewRoutingReady()) return waitFor("GATEWAY_PENDING","Workloads are healthy. Waiting for the shared preview Gateway.");
            var observedRoute=get("gateway.networking.k8s.io/v1","HTTPRoute","preview-route",plan.namespace());
            if (!routeReady(observedRoute)) return waitFor("ROUTE_PENDING","Workloads are healthy. Waiting for the preview route to be accepted.");
            String token=previewAccessToken(plan.namespace());
            if (!edgeProbe(plan.environmentId(),token)) return waitFor("EDGE_PROBE_PENDING","Route accepted. Waiting for authenticated edge health and access denial checks.");
            return new Observation("EDGE_READY","Authenticated preview route is accepted and healthy.",false,true,plan.sourceSha());
        }
        if (status.path("health").path("status").asText().equals("Degraded")) return waitFor("WORKLOADS_DEGRADED","Argo reports degraded workloads. Inspect the namespace events.");
        return waitFor("ARGO_SYNC_PENDING","Namespace baseline established. Waiting for Argo sync and workload health.");
    }

    private void baseline(Plan plan,JobLease lease,BooleanSupplier current) {
        var namespace=resource("v1","Namespace",plan.namespace(),null,lease);
        labels(namespace).put("pod-security.kubernetes.io/enforce","restricted");
        labels(namespace).put("pod-security.kubernetes.io/enforce-version","v1.35");
        labels(namespace).put("pod-security.kubernetes.io/audit","restricted");
        labels(namespace).put("pod-security.kubernetes.io/warn","restricted");
        labels(namespace).put("kubespawn.io/preview-route","allowed");
        put(namespace,lease,current);
        String ns=plan.namespace();
        put(resource("v1","ServiceAccount","preview-workload",ns,lease,"automountServiceAccountToken",false),lease,current);
        put(resource("v1","ResourceQuota","preview-quota",ns,lease,"spec",m("hard",m(
            "requests.cpu","1","limits.cpu","2","requests.memory","2Gi","limits.memory","4Gi",
            "requests.ephemeral-storage","2Gi","limits.ephemeral-storage","4Gi","requests.storage","5Gi",
            "count/pods","10","count/services","5","count/secrets","5","count/configmaps","10","count/jobs.batch","5","count/persistentvolumeclaims","2"))),lease,current);
        put(resource("v1","LimitRange","preview-defaults",ns,lease,"spec",m("limits",List.of(m("type","Container",
            "default",m("cpu","500m","memory","512Mi","ephemeral-storage","256Mi"),
            "defaultRequest",m("cpu","100m","memory","128Mi","ephemeral-storage","128Mi"))))),lease,current);
        policy("default-deny",ns,lease,current,m("podSelector",m(),"policyTypes",List.of("Ingress","Egress")));
        policy("allow-dns",ns,lease,current,m("podSelector",m(),"policyTypes",List.of("Egress"),"egress",List.of(m(
            "to",List.of(m("namespaceSelector",m("matchLabels",m("kubernetes.io/metadata.name","kube-system")),"podSelector",m("matchLabels",m("k8s-app","kube-dns")))),
            "ports",List.of(m("protocol","UDP","port",53),m("protocol","TCP","port",53))))));
        for (var link:List.of(new String[]{"web","api","8080"},new String[]{"api","database","5432"})) {
            policy("allow-"+link[0]+"-to-"+link[1],ns,lease,current,m("podSelector",selector(link[0]),"policyTypes",List.of("Egress"),
                "egress",List.of(m("to",List.of(m("podSelector",selector(link[1]))),"ports",List.of(m("protocol","TCP","port",Integer.parseInt(link[2])))))));
            policy("allow-"+link[1]+"-from-"+link[0],ns,lease,current,m("podSelector",selector(link[1]),"policyTypes",List.of("Ingress"),
                "ingress",List.of(m("from",List.of(m("podSelector",selector(link[0]))),"ports",List.of(m("protocol","TCP","port",Integer.parseInt(link[2])))))));
        }
        policy("allow-gateway-to-web",ns,lease,current,m("podSelector",selector("web"),"policyTypes",List.of("Ingress"),
            "ingress",List.of(m("from",List.of(m("namespaceSelector",m("matchLabels",m("kubernetes.io/metadata.name","envoy-gateway-system")))),
                "ports",List.of(m("protocol","TCP","port",8080))))));
        ensureRuntimeSecret(ns,lease,current);
        put(resource("rbac.authorization.k8s.io/v1","RoleBinding","preview-deployer",ns,lease,
            "roleRef",m("apiGroup","rbac.authorization.k8s.io","kind","ClusterRole","name","kubespawn-preview-deployer"),
            "subjects",List.of(m("kind","ServiceAccount","name","argocd-application-controller","namespace","argocd"))),lease,current);
    }

    @Override public Observation cleanup(String namespace,JobLease lease,BooleanSupplier current) {
        guard(current);
        String app=application(lease);
        if (get("gateway.networking.k8s.io/v1","HTTPRoute","preview-route",namespace)!=null) {
            remove("gateway.networking.k8s.io/v1","HTTPRoute","preview-route",namespace,lease,current);
            return waitFor("ROUTE_CLEANUP_PENDING","Preview access removed. Waiting for route deletion.");
        }
        if (get("argoproj.io/v1alpha1","Application",app,"argocd")!=null) {
            remove("argoproj.io/v1alpha1","Application",app,"argocd",lease,current);
            return waitFor("ARGO_CLEANUP_PENDING","Waiting for Argo's resource finalizer to remove workloads.");
        }
        registerNamespace(namespace,false,current);
        if (get("v1","Namespace",namespace,null)!=null) {
            remove("v1","Namespace",namespace,null,lease,current);
            return waitFor("NAMESPACE_CLEANUP_PENDING","Waiting for Kubernetes to confirm namespace and namespaced resource removal.");
        }
        if (get("argoproj.io/v1alpha1","AppProject",app,"argocd")!=null) {
            remove("argoproj.io/v1alpha1","AppProject",app,"argocd",lease,current);
            return waitFor("PROJECT_CLEANUP_PENDING","Waiting for the environment's Argo project to disappear.");
        }
        // Continue polling tombstones: a late remote effect from an old lease is discovered and removed.
        return new Observation("CLEANUP_CONFIRMED","Kubernetes confirms the namespace, Application, and project are absent.",true);
    }

    private void ensureRuntimeSecret(String namespace,JobLease lease,BooleanSupplier current) {
        var existing=get("v1","Secret","preview-runtime",namespace);
        var values=new LinkedHashMap<String,Object>();
        if (existing!=null) {
            owned(existing,lease);
            var secret=client.secrets().inNamespace(namespace).withName("preview-runtime").get();
            if (secret.getData()!=null) secret.getData().forEach((key,value)->
                values.put(key,new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8)));
        }
        var random=new SecureRandom();
        for (String key:List.of("DATABASE_PASSWORD","KUBESPAWN_DEV_TOKEN","PREVIEW_ACCESS_TOKEN")) if (!values.containsKey(key)) {
            byte[] token=new byte[32]; random.nextBytes(token); values.put(key,HexFormat.of().formatHex(token));
        }
        put(resource("v1","Secret","preview-runtime",namespace,lease,"type","Opaque","stringData",values),lease,current);
    }

    private void route(Plan plan,JobLease lease,BooleanSupplier current) {
        String prefix=settings.previewPath(plan.environmentId());
        put(resource("gateway.networking.k8s.io/v1","HTTPRoute","preview-route",plan.namespace(),lease,"spec",m(
            "parentRefs",List.of(m("name",settings.gatewayName,"namespace",settings.gatewayNamespace,"sectionName","http")),
            "rules",List.of(m("matches",List.of(m("path",m("type","PathPrefix","value",prefix))),
                "filters",List.of(m("type","URLRewrite","urlRewrite",m("path",m("type","ReplacePrefixMatch","replacePrefixMatch","/")))),
                "backendRefs",List.of(m("name","preview-web","port",8080)))))),lease,current);
    }

    @Override public boolean previewRoutingReady() {
        if (!settings.enabled || client==null) return false;
        try {
            var gateway=get("gateway.networking.k8s.io/v1","Gateway",settings.gatewayName,settings.gatewayNamespace);
            return gateway!=null && condition(status(gateway).path("conditions"),"Programmed",gateway.getMetadata().getGeneration());
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean routeReady(GenericKubernetesResource route) {
        if (route==null) return false;
        var parents=status(route).path("parents");
        if (!parents.isArray()) return false;
        for (var parent:parents) {
            var ref=parent.path("parentRef");
            if (ref.path("name").asText().equals(settings.gatewayName)
                    && ref.path("namespace").asText().equals(settings.gatewayNamespace)
                    && condition(parent.path("conditions"),"Accepted",route.getMetadata().getGeneration())
                    && condition(parent.path("conditions"),"ResolvedRefs",route.getMetadata().getGeneration())) return true;
        }
        return false;
    }

    private boolean condition(tools.jackson.databind.JsonNode conditions,String type,Long generation) {
        if (!conditions.isArray()) return false;
        for (var condition:conditions) if (condition.path("type").asText().equals(type)
                && condition.path("status").asText().equals("True")
                && generation!=null && condition.path("observedGeneration").asLong(-1)==generation) return true;
        return false;
    }

    @Override public String previewAccessToken(String namespace) {
        if (!settings.enabled || client==null) throw new IllegalStateException("Preview routing is disabled.");
        var secret=client.secrets().inNamespace(namespace).withName("preview-runtime").get();
        if (secret==null || secret.getData()==null || !secret.getData().containsKey("PREVIEW_ACCESS_TOKEN"))
            throw new IllegalStateException("Preview access is not initialized.");
        return new String(Base64.getDecoder().decode(secret.getData().get("PREVIEW_ACCESS_TOKEN")),StandardCharsets.UTF_8);
    }

    private boolean edgeProbe(java.util.UUID environment,String token) {
        try {
            URI target=URI.create(settings.previewInternalUrl(environment));
            int denied=http.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
            String cookie="kubespawn_preview="+token;
            var page=http.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                .header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            var asset=PREVIEW_ASSET.matcher(page.body());
            if (denied!=401 || page.statusCode()<200 || page.statusCode()>=400 || !asset.find()) return false;
            int assetStatus=http.send(HttpRequest.newBuilder(target.resolve(asset.group(1))).timeout(Duration.ofSeconds(5))
                .header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
            int apiStatus=http.send(HttpRequest.newBuilder(target.resolve("api/v1/platform")).timeout(Duration.ofSeconds(5))
                .header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
            return assetStatus>=200 && assetStatus<400 && apiStatus>=200 && apiStatus<400;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private void registerNamespace(String namespace,boolean add,BooleanSupplier current) {
        guard(current);
        var secret=client.secrets().inNamespace("argocd").withName("kubespawn-local-cluster").get();
        if (secret==null) throw new IllegalStateException("Local Argo cluster credentials are missing.");
        String currentNamespaces=new String(Base64.getDecoder().decode(secret.getData().get("namespaces")),StandardCharsets.UTF_8);
        var names=new java.util.TreeSet<>(List.of(currentNamespaces.split(",")));
        names.remove("argocd"); names.remove("kubespawn-unassigned"); names.remove("");
        if (add) names.add(namespace); else names.remove(namespace);
        if (names.isEmpty()) names.add("kubespawn-unassigned");
        String nextNamespaces=String.join(",",names);
        if (nextNamespaces.equals(currentNamespaces)) return;
        guard(current);
        secret.getData().put("namespaces",Base64.getEncoder().encodeToString(nextNamespaces.getBytes(StandardCharsets.UTF_8)));
        client.secrets().inNamespace("argocd").resource(secret).lockResourceVersion().replace();
    }

    private void policy(String name,String ns,JobLease lease,BooleanSupplier current,Map<String,Object> spec) {
        put(resource("networking.k8s.io/v1","NetworkPolicy",name,ns,lease,"spec",spec),lease,current);
    }
    private Map<String,Object> selector(String component) { return m("matchLabels",m("app.kubernetes.io/name",component,"app.kubernetes.io/instance","preview")); }
    private GenericKubernetesResource get(String api,String kind,String name,String namespace) {
        return operation(client,api,kind,name,namespace).get();
    }
    private void put(Map<String,Object> value,JobLease lease,BooleanSupplier current) {
        guard(current);
        var desired=Serialization.unmarshal(json.writeValueAsString(value),GenericKubernetesResource.class);
        var op=operation(client,desired.getApiVersion(),desired.getKind(),desired.getMetadata().getName(),desired.getMetadata().getNamespace());
        var existing=op.get();
        guard(current);
        if (existing==null) { op.create(desired); return; }
        owned(existing,lease);
        if (existing.getMetadata().getDeletionTimestamp()!=null) throw new IllegalStateException("Owned resource is terminating.");
        // Compare-and-swap: a worker with an older fencing token cannot overwrite a newer write.
        if (Long.parseLong(existing.getMetadata().getAnnotations().getOrDefault(FENCE,"0"))>lease.environmentToken()) throw new IllegalStateException("Newer Kubernetes fence observed.");
        desired.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
        desired.getMetadata().setUid(existing.getMetadata().getUid());
        client.genericKubernetesResources(context(desired.getApiVersion(),desired.getKind())).resource(desired).lockResourceVersion().replace();
    }
    private void remove(String api,String kind,String name,String namespace,JobLease lease,BooleanSupplier current) {
        guard(current); var existing=get(api,kind,name,namespace); if (existing==null) return; owned(existing,lease); guard(current);
        client.genericKubernetesResources(context(api,kind)).resource(existing).lockResourceVersion().delete();
    }
    static io.fabric8.kubernetes.client.dsl.Resource<GenericKubernetesResource> operation(KubernetesClient client,String api,String kind,String name,String namespace) {
        var resources=client.genericKubernetesResources(context(api,kind));
        return namespace==null ? resources.withName(name) : resources.inNamespace(namespace).withName(name);
    }
    private void owned(GenericKubernetesResource resource,JobLease lease) {
        if (resource.getMetadata().getLabels()==null || !lease.environmentId().toString().equals(resource.getMetadata().getLabels().get(OWNER)))
            throw new IllegalStateException("Resource ownership conflict.");
    }
    private tools.jackson.databind.JsonNode status(GenericKubernetesResource resource) {
        return resource==null ? json.createObjectNode() : json.readTree(Serialization.asJson(resource)).path("status");
    }
    private String sha256(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch(Exception failure) { throw new IllegalStateException("Cannot hash the desired Argo application."); }
    }
    static ResourceDefinitionContext context(String api,String kind) {
        String group=api.contains("/") ? api.substring(0,api.indexOf('/')) : "";
        String version=api.substring(api.indexOf('/')+1);
        String plural=switch(kind) { case "NetworkPolicy"->"networkpolicies"; default->kind.toLowerCase()+"s"; };
        return new ResourceDefinitionContext.Builder().withGroup(group).withVersion(version).withKind(kind).withPlural(plural).withNamespaced(!kind.equals("Namespace")).build();
    }
    private Map<String,Object> resource(String api,String kind,String name,String namespace,JobLease lease,Object... extra) {
        var meta=m("name",name,"labels",m("app.kubernetes.io/managed-by","kubespawn",OWNER,lease.environmentId().toString()),
            "annotations",m(FENCE,Long.toString(lease.environmentToken()),"kubespawn.io/intent-version",Long.toString(lease.intentVersion())));
        if (namespace!=null) meta.put("namespace",namespace);
        var value=m("apiVersion",api,"kind",kind,"metadata",meta); value.putAll(m(extra)); return value;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> metadata(Map<String,Object> value) { return (Map<String,Object>)value.get("metadata"); }
    @SuppressWarnings("unchecked") private Map<String,Object> labels(Map<String,Object> value) { return (Map<String,Object>)metadata(value).get("labels"); }
    static Map<String,Object> m(Object... pairs) { var value=new LinkedHashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2) value.put((String)pairs[i],pairs[i+1]); return value; }
    private static void guard(BooleanSupplier current) { if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) throw new IllegalStateException("Intent or lease changed."); }
    private static String application(JobLease lease) { return "ks-"+lease.environmentId().toString().replace("-",""); }
    private static Observation waitFor(String stage,String message) { return new Observation(stage,message,false); }
    static String templateHash(Path root) {
        try {
            var files=new TreeMap<String,Path>();
            try (var paths=Files.walk(root)) { paths.filter(Files::isRegularFile).forEach(path->files.put(root.relativize(path).toString().replace('\\','/'),path)); }
            var hash=MessageDigest.getInstance("SHA-256");
            for (var file:files.entrySet()) {
                hash.update((file.getKey()+"\0").getBytes(StandardCharsets.UTF_8));
                hash.update(Files.readString(file.getValue()).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(Exception failure) { throw new IllegalStateException("Cannot validate the approved local workload chart."); }
    }
    @PreDestroy void close() { if (client!=null) client.close(); }
}
