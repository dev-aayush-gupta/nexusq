package dev.ayush.nexusq.publisher;

import dev.ayush.nexusq.job.Job;
import dev.ayush.nexusq.job.JobRepository;
import dev.ayush.nexusq.queue.JobQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobQueuePublisher {

    private static final int BATCH_SIZE = 10;

    private final JobRepository jobRepository;
    private final JobQueue jobQueue;

    @Transactional
    @Scheduled(fixedDelayString = "${nexusq.publisher.interval-ms:1000}")
    public void publish() {
        List<Job> jobs = jobRepository.findUnpublishedPending(PageRequest.of(0, BATCH_SIZE));

        if (jobs.isEmpty()) {
            return;
        }

        log.info("Publisher found {} unpublished job(s) — pushing to Redis", jobs.size());

        for (Job job : jobs) {
            jobQueue.push(job.getId());
            job.setPublishedAt(Instant.now());
            log.info("Published job [id={}, priority={}, payload={}] to queue", job.getId(), job.getPriority(), job.getPayload());
        }
    }
}
