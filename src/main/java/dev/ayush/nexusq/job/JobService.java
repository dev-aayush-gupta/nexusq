package dev.ayush.nexusq.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JobRepository jobRepository;

    @Transactional
    public UUID submit(String payload, JobPriority priority) {
        validateJson(payload);
        Job job = Job.builder()
                .payload(payload)
                .status(JobStatus.PENDING)
                .priority(priority != null ? priority : JobPriority.DEFAULT)
                .build();
        return jobRepository.save(job).getId();
    }

    private void validateJson(String payload) {
        try {
            MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not valid JSON: " + e.getOriginalMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<Job> findById(UUID id) {
        return jobRepository.findById(id);
    }

    // Claims the next PENDING job — sets it RUNNING and commits.
    // If the process dies before markCompleted(), the job stays RUNNING forever (Story 1.7's known gap).
    @Transactional
    public Optional<Job> claimNextPending() {
        return jobRepository
                .findByStatusForUpdateSkipLocked(JobStatus.PENDING, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(job -> {
                    job.setStatus(JobStatus.RUNNING);
                    return job;
                });
    }

    @Transactional
    public void markCompleted(UUID id) {
        jobRepository.findById(id).ifPresentOrElse(
                job -> job.setStatus(JobStatus.COMPLETED),
                () -> log.warn("markCompleted called for unknown job {} — already deleted?", id)
        );
    }
}
