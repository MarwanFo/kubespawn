package io.kubespawn.platform.github;

import tools.jackson.databind.JsonNode;

public interface GitHubGateway {
    boolean configured();
    JsonNode get(BuildModels.Repository repository, String path);
    byte[] workflow(BuildModels.Repository repository, String revision);
    byte[] artifact(BuildModels.Repository repository, long artifactId);
    void dispatch(BuildModels.Repository repository, String sourceSha, String requestId);
}
