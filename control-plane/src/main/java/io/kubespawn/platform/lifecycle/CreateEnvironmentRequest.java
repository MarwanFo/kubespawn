package io.kubespawn.platform.lifecycle;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateEnvironmentRequest(
    @NotBlank @Size(max = 255)
    @Pattern(regexp = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", message = "Use owner/repository, without a URL")
    String repository,
    @NotBlank @Size(max = 255)
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*", message = "Use a branch or revision with letters, numbers, dots, slashes, underscores, or hyphens")
    String ref,
    @Min(1) @Max(24) int ttlHours
) {}
