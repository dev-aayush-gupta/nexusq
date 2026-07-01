package dev.ayush.nexusq.worker;

import dev.ayush.nexusq.job.Job;
import dev.ayush.nexusq.job.JobService;
import dev.ayush.nexusq.job.JobStatus;
import dev.ayush.nexusq.queue.JobQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorker {

    private final JobQueue jobQueue;
    private final JobService jobService;

    @Scheduled(fixedDelayString = "${nexusq.worker.poll-interval-ms:2000}")
    public void poll() {
        Optional<UUID> jobId = jobQueue.pop();

        if (jobId.isEmpty()) {
            return;
        }

        UUID id = jobId.get();

        Optional<Job> jobOpt = jobService.findById(id);

        if (jobOpt.isEmpty()) {
            log.warn("Popped job {} from queue but it does not exist in Postgres — skipping", id);
            return;
        }

        Job job = jobOpt.get();

        if (job.getStatus() != JobStatus.PENDING) {
            log.warn("Popped job {} from queue but status is {} not PENDING — skipping", id, job.getStatus());
            return;
        }

        jobService.markRunning(id);
        log.info("Processing job {}", id);

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // If the process is killed between markRunning and here, the job stays RUNNING forever.
        // This is the known gap closed by leasing in Story 3.
        try {
            jobService.markCompleted(id);
            log.info("Completed job {}", id);
        } catch (Exception e) {
            log.error("Failed to complete job {} — job will remain RUNNING until Story 3 leasing recovery", id, e);
        }
    }
}
