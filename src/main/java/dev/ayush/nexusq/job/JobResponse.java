package dev.ayush.nexusq.job;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String payload,
        JobStatus status,
        JobPriority priority,
        Instant createdAt,
        Instant updatedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getPayload(),
                job.getStatus(),
                job.getPriority(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
