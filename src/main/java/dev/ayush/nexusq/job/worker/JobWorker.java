package dev.ayush.nexusq.job.worker;

import dev.ayush.nexusq.job.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorker {

    private final JobService jobService;

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        jobService.claimNextPending().ifPresent(job -> {
            log.info("Processing job {}", job.getId());
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // If the process is killed between claim and here, the job stays RUNNING forever.
            // This is the known gap closed by leasing in Story 3.
            try {
                jobService.markCompleted(job.getId());
                log.info("Completed job {}", job.getId());
            } catch (Exception e) {
                log.error("Failed to complete job {} — job will remain RUNNING until Story 3 leasing recovery", job.getId(), e);
            }
        });
    }
}
