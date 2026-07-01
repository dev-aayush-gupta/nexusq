package dev.ayush.nexusq.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class JobIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("nexusq")
            .withUsername("nexusq")
            .withPassword("nexusq");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.7.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAllInBatch();
    }

    // Story 1.6 — submit a job, wait until COMPLETED, assert final state
    @Test
    void submit_jobCompletesSuccessfully() {
        UUID id = jobService.submit("{\"type\":\"email\"}", JobPriority.DEFAULT);

        await()
                .atMost(30, SECONDS)
                .pollInterval(1, SECONDS)
                .until(() -> jobService.findById(id)
                        .map(job -> job.getStatus() == JobStatus.COMPLETED)
                        .orElse(false));

        Job completed = jobService.findById(id).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.getCreatedAt()).isNotNull();
        assertThat(completed.getUpdatedAt()).isAfter(completed.getCreatedAt());
    }

    // Story 1.7 — a job left RUNNING is never recovered (no leasing yet)
    @Test
    void runningJob_isNeverPickedUpByWorker() throws InterruptedException {
        // Simulate a worker that claimed a job but crashed before completing it —
        // insert directly as RUNNING, bypassing the normal claim flow.
        // The publisher filters by status = PENDING so this row is never pushed to Redis.
        Job stuck = jobRepository.save(Job.builder()
                .payload("{\"type\":\"stuck\"}")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.DEFAULT)
                .build());

        // Wait 5 seconds — covers multiple publisher cycles (1s) and worker poll cycles (2s).
        // Neither should touch a RUNNING job.
        Thread.sleep(5_000);

        Job afterWait = jobService.findById(stuck.getId()).orElseThrow();
        assertThat(afterWait.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    // Story 2.6 — job submitted while Redis is unavailable is still delivered after Redis recovers.
    // Uses pause/unpause instead of stop/start to preserve the container port — Testcontainers
    // assigns a new random port on every start(), which Spring's connection factory cannot follow
    // at runtime. Pausing freezes the Redis process; the port stays the same on unpause.
    @Test
    void job_isDelivered_afterRedisRecovery() throws InterruptedException {
        DockerClientFactory.instance().client()
                .pauseContainerCmd(redis.getContainerId()).exec();

        // Submit while Redis is paused — lands in Postgres with published_at = NULL
        UUID id = jobService.submit("{\"type\":\"recovery-test\"}", JobPriority.DEFAULT);

        // Wait long enough for the publisher to have attempted and been blocked by the paused Redis
        Thread.sleep(3_000);

        // Confirm job is still PENDING — publisher is blocked pushing to Redis, published_at not set
        Job beforeRecovery = jobService.findById(id).orElseThrow();
        assertThat(beforeRecovery.getStatus()).isEqualTo(JobStatus.PENDING);

        // Unpause Redis — same port, blocked publisher push completes, transaction commits
        DockerClientFactory.instance().client()
                .unpauseContainerCmd(redis.getContainerId()).exec();

        // Publisher delivers the job, worker picks it up and completes it
        await()
                .atMost(30, SECONDS)
                .pollInterval(1, SECONDS)
                .until(() -> jobService.findById(id)
                        .map(job -> job.getStatus() == JobStatus.COMPLETED)
                        .orElse(false));

        Job completed = jobService.findById(id).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.getPublishedAt()).isNotNull();
    }
}
