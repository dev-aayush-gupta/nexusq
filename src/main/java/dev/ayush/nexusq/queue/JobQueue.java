package dev.ayush.nexusq.queue;

import java.util.Optional;
import java.util.UUID;

public interface JobQueue {

    void push(UUID jobId);

    Optional<UUID> pop();
}
