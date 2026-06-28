package dev.ayush.nexusq.job;

import jakarta.validation.constraints.NotBlank;

public record JobSubmitRequest(
        @NotBlank String payload,
        JobPriority priority
) {}
