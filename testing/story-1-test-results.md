# Story 1 — Test Results

> Fill this in as you run through `story-1-manual-test-plan.md`.
> Be honest — note unexpected behaviour, not just pass/fail.
> This becomes your reference when Story 3 asks "what exactly broke in Story 1?"

---

## Run 1 — Date: 2026-06-29

### Infrastructure
| Check | Result | Notes |
|---|---|---|
| Health — all UP | PASS | db and redis UP. Kafka absent — see observations. |
| Flyway V1 success | PASS | |
| Jobs table empty | PASS | |
| Index exists | PASS | |

### Submission
| Check | Result | Notes |
|---|---|---|
| POST with priority | PASS | 201 + UUID returned correctly |
| POST defaults to DEFAULT | PASS | Verified in Postgres |
| POST blank payload → 400 | PASS | |
| POST invalid priority → 400 | PASS | |

### Lifecycle
| Check | Result | Notes |
|---|---|---|
| PENDING → RUNNING → COMPLETED | PASS | Verified in Postgres |
| created_at stable, updated_at changes | PASS | |
| GET PENDING | PASS | |
| GET RUNNING | PASS | |
| GET COMPLETED | PASS | |
| GET unknown → 404 | PASS | |

### Actuator
| Check | Result | Notes |
|---|---|---|
| poll() visible in scheduledtasks | | Not tested |
| SQL toggle works live | | Not tested |

### Failure scenario
| Check | Result | Notes |
|---|---|---|
| Job stuck RUNNING after kill | PASS | Killed app mid-processing, job stayed RUNNING |
| Restarted app never recovers it | PASS | Restarted app, job remained RUNNING permanently |

### Observations
- Kafka health indicator absent from `/actuator/health` even with `management.health.kafka.enabled=true`. Not DOWN — simply not registered. Likely Spring Boot 4.x behavior change where `KafkaAdmin` bean is no longer auto-created from `spring.kafka.bootstrap-servers` alone. Kafka not in use until Story 9 — revisit then when the actual consumer/producer wires up `KafkaAdmin`.
- `@JdbcTypeCode(SqlTypes.JSON)` required on `payload` field alongside `@Column(columnDefinition = "jsonb")`. Without it Hibernate binds the value as VARCHAR and Postgres rejects it. Both annotations serve different purposes — `columnDefinition` controls DDL, `@JdbcTypeCode` controls runtime JDBC binding.
- Flyway dependency alone (`flyway-core` + `flyway-database-postgresql`) insufficient in Spring Boot 4.x — `spring-boot-flyway` module required for auto-configuration to trigger.

---

<!-- Copy the Run 1 block above for each new test session -->
