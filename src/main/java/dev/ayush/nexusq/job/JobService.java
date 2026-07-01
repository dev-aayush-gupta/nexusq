package dev.ayush.nexusq.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional
    public void markRunning(UUID id) {
        jobRepository.findById(id).ifPresentOrElse(
                job -> job.setStatus(JobStatus.RUNNING),
                () -> log.warn("markRunning called for unknown job {} — already deleted?", id)
        );
    }

    @Transactional
    public void markCompleted(UUID id) {
        jobRepository.findById(id).ifPresentOrElse(
                job -> job.setStatus(JobStatus.COMPLETED),
                () -> log.warn("markCompleted called for unknown job {} — already deleted?", id)
        );
    }
}
