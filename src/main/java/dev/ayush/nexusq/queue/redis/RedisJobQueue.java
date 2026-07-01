package dev.ayush.nexusq.queue.redis;

import dev.ayush.nexusq.queue.JobQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisJobQueue implements JobQueue {

    private static final String QUEUE_KEY = "job_queue";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void push(UUID jobId) {
        redisTemplate.opsForList().leftPush(QUEUE_KEY, jobId.toString());
    }

    @Override
    public Optional<UUID> pop() {
        String jobId = redisTemplate.opsForList().rightPop(QUEUE_KEY);
        return Optional.ofNullable(jobId).map(UUID::fromString);
    }
}
