# NexusQ — Decisions Log

> Dated entries for every real design decision and rejected alternative, written in the moment — not reconstructed later for interview prep. See `STORIES.md` for the full story roadmap and project context.

---

## [Story 0] — Single Maven module to start

**Decision:** Stay single-module through Story 0 onward. Revisit only when a real seam appears — e.g. around Story 6 (state machine formalization), or sooner if the codebase actually demands it.

**Alternatives considered:** Splitting into multiple modules now (e.g. `nexusq-core`, `nexusq-storage`, `nexusq-worker`, `nexusq-app`), mirroring FluxGuard's multi-module structure.

**Why not:** No code exists yet to reveal real module boundaries — splitting now would be guessing at seams, not designing around discovered ones. The setup cost of multi-module (separate `pom.xml` per module, explicit cross-module dependency declarations, more annoying shared Testcontainers config) isn't justified when there's zero business logic to show for it. FluxGuard's modules split along genuinely independent reuse boundaries (e.g. the rate-limiting algorithm core is plausibly a standalone library); NexusQ's pieces — leasing, storage, worker pools — are more tightly coupled by nature and the real boundaries aren't visible yet.

**Revisit if:** A natural seam appears (e.g. wanting to reuse leasing/storage logic outside Spring Boot), or the single `src/main/java` package structure starts feeling tangled — most likely around Story 5–6.

---

## [Story 0] — Postgres and Redis host ports remapped to default+1

**Decision:** Map both Dockerized containers' host-side ports to one above their defaults — Postgres `5433:5432`, Redis `6380:6379`. In both cases the container's internal process still listens on its normal default port; only the host-facing mapping changed.

**Alternatives considered:** Stopping the local Postgres/Redis installs for the duration of NexusQ development; leaving both on default ports and hoping for the best.

**Why not:** Local installs of both Postgres and Redis (via Homebrew) were already bound to their default ports on `localhost` — confirmed for Postgres via `lsof -i :5432` showing two separate listeners (the local install and Docker's proxy). Both technically "ran" without erroring on `docker compose up`, which is exactly the danger: connecting to `localhost:5432`/`localhost:6379` could silently hit either instance depending on timing, with no obvious signal that something was wrong. Stopping the local installs was rejected because it's a blunt, easy-to-forget toggle that would also break unrelated local work depending on those instances.

**Revisit if:** The local Postgres/Redis installs are ever removed entirely, at which point reverting to the default port mappings would be slightly more conventional (though not necessary).

---

---

## [Story 1] — Package-by-feature structure over package-by-layer

**Decision:** Organise code under `job/` (feature) with sub-packages like `job/worker/`, rather than top-level `controller/`, `service/`, `repository/` layers.

**Alternatives considered:** Classic layered structure — `controller/JobController.java`, `service/JobService.java`, `repository/JobRepository.java`.

**Why not:** Package-by-layer groups files by what they technically are, not what they do together. As Stories 2–10 add relay, leasing, DLQ, and Kafka, each feature gets its own sibling package (`relay/`, `leasing/`, `dlq/`) that is self-contained. In a layered structure, a single feature's files are spread across four directories — a change to the relay touches `controller/`, `service/`, `repository/`, and `model/` simultaneously. Package-by-feature keeps related files co-located and makes the blast radius of a change obvious.

**Revisit if:** A genuine cross-feature abstraction emerges that doesn't belong to any one feature — at which point a `common/` or `shared/` package is the right addition, not a full restructure.

---

## [Story 1] — `payload` stored as JSONB not TEXT

**Decision:** Store job payload as `JSONB` in Postgres, mapped as a raw `String` in Java.

**Alternatives considered:** `TEXT` (dumb string, no validation), `Map<String, Object>` with a JPA converter (structured but format-coupled), `BLOB` (opaque bytes).

**Why not:** `TEXT` accepts any string including malformed JSON — Postgres has no way to validate or query inside it. `BLOB` is entirely opaque. `Map<String, Object>` adds a conversion layer between Java and Postgres and couples the storage format to Jackson's map representation. JSONB validates at insert time, supports `->>`  field queries, and supports GIN indexing for future payload-based filtering — all at zero cost today.

**Trade-off accepted:** JSONB rejects non-JSON payloads at the database level. If NexusQ ever needs to carry XML, CSV, or binary payloads, the column would need to change to `TEXT` with application-layer validation, or a `content_type` field added alongside a `TEXT` column. The right boundary for a general-purpose queue is probably `TEXT` with app-layer validation — JSONB is the defensible choice for this project's scope.

**Revisit if:** A requirement for non-JSON payload formats appears.

---

## [Story 1] — `status` and `priority` as Java enums stored as strings

**Decision:** `JobStatus` and `JobPriority` are Java enums, stored in Postgres as `VARCHAR` using `@Enumerated(EnumType.STRING)`.

**Alternatives considered:** Plain `String` fields on the entity (no enum); enums stored as integers using `@Enumerated(EnumType.ORDINAL)` (JPA default).

**Why not plain String:** A string field allows any value including typos — `"RUNING"`, `"pending"`, `"done"` — all compile and persist silently. An enum makes invalid states unrepresentable at compile time.

**Why not ORDINAL:** JPA's default `EnumType.ORDINAL` stores `PENDING=0`, `RUNNING=1`, `COMPLETED=2` etc. Reordering the enum values — a trivial refactor — silently corrupts every existing row. `EnumType.STRING` stores `"PENDING"`, `"RUNNING"` — reordering is safe, and the database is human-readable without a lookup table.

**Revisit if:** Never for ORDINAL. The plain String question may be revisited in Story 6 when the GoF State pattern replaces the enum entirely — at that point the Java type changes but the `VARCHAR` column stays.

---

## [Story 1] — UUID generated in Java, not the database

**Decision:** Use `@GeneratedValue(strategy = GenerationType.UUID)` — Hibernate generates the UUID in Java before the insert.

**Alternatives considered:** Database-generated UUID via `DEFAULT gen_random_uuid()` in Postgres; database sequence (`BIGSERIAL`).

**Why not database UUID:** With a database-generated UUID, you must do the insert first and then ask the database for the ID it assigned. You cannot pre-register the job in Redis, build a response, or do anything with the ID before persisting. Java-generated UUIDs are known before the insert — useful from Story 2 onward when the outbox relay needs to reference the ID before Redis push.

**Why not BIGSERIAL:** Sequential integer IDs are guessable — `GET /jobs/1`, `GET /jobs/2`. UUID is not. For an API that returns job IDs to callers, non-guessable IDs are a basic security property.

**Revisit if:** Performance profiling shows UUID primary key causing index fragmentation under very high insert volume — at which point UUIDv7 (time-ordered UUID) is the fix, not a revert to sequences.

---

## [Story 1] — `TIMESTAMPTZ` over `TIMESTAMP` for all time columns

**Decision:** Use `TIMESTAMPTZ` (timestamp with time zone) for `created_at` and `updated_at`.

**Alternatives considered:** `TIMESTAMP` (no timezone), `BIGINT` epoch milliseconds.

**Why not TIMESTAMP:** Postgres stores `TIMESTAMP` as a local value with no timezone context. If the database server's timezone ever changes — OS reconfiguration, cloud migration, DST — every stored timestamp silently shifts meaning. `TIMESTAMPTZ` stores in UTC internally and converts on read, so the stored value is always unambiguous regardless of server configuration.

**Why not BIGINT epoch:** Readable in code but opaque in the database — you cannot use Postgres date functions, range queries, or dashboard tools without conversion. `TIMESTAMPTZ` is directly queryable.

**Revisit if:** Never. This is always the right choice in Postgres.

---

## [Story 1] — Flyway for schema management, Hibernate in validate mode

**Decision:** Flyway owns all schema changes via versioned SQL migration files. Hibernate's `ddl-auto` is set to `validate` — it checks but never modifies the schema.

**Alternatives considered:** `ddl-auto=update` (Hibernate auto-alters schema); `ddl-auto=create-drop` (recreate on every startup); manual schema management with no tooling.

**Why not `update`:** Hibernate's `update` is blind to history — it compares the current entity state to the current schema and runs whatever ALTER TABLE closes the gap. It doesn't produce repeatable SQL files, doesn't track what has run, and produces different results depending on the current schema state. Two developers starting from different schema states get different ALTER TABLE statements. Flyway migrations are append-only, committed to git, and always produce the same final schema from any starting point.

**Why not manual:** No auditability, no repeatability, no safety net for fresh environments.

**Revisit if:** Never for manual. The `ddl-auto` question may be revisited in test configuration — Testcontainers tests may use a separate profile with `create-drop` for isolation.

---

## [Story 1] — `FOR UPDATE SKIP LOCKED` for worker job claiming

**Decision:** Workers claim jobs using `SELECT FOR UPDATE SKIP LOCKED` — a database-level pessimistic write lock with skip-locked behaviour.

**Alternatives considered:** Optimistic locking with a `version` column; application-level status-check-and-update without database locking; Redis-based claiming (deferred to Story 2).

**Why not optimistic locking:** Optimistic locking assumes conflicts are rare and retries on collision. In a job queue, multiple workers competing for the same row is the normal operating condition, not a rare edge case. Under any real concurrency, optimistic locking produces constant retry storms.

**Why not status-check-and-update without locking:** A worker reads a `PENDING` row, another worker reads the same row before the first has updated it to `RUNNING` — both claim the same job. `FOR UPDATE` makes the second worker block (or skip with `SKIP LOCKED`) until the first has committed, making the claim atomic at the database level.

**Why `SKIP LOCKED` specifically:** Without it, `FOR UPDATE` makes a second worker wait blocked for the lock to release. `SKIP LOCKED` makes the second worker skip the locked row and claim the next available one instead — workers never block each other, throughput scales with worker count.

**Revisit if:** Story 2 adds Redis as the hot queue — workers will pop from Redis instead of polling Postgres directly. The `FOR UPDATE SKIP LOCKED` pattern moves to the outbox relay at that point.

---

## [Story 1] — Two-transaction worker design

**Decision:** The worker uses two separate transactions — `claimNextPending()` commits `RUNNING` status, then `markCompleted()` commits `COMPLETED` status — with job processing happening between them outside any transaction.

**Alternatives considered:** Single long transaction spanning the full claim-process-complete lifecycle; claim and complete in one transaction with processing inside.

**Why not single transaction:** A transaction holds a database connection for its entire duration. If processing takes 1–30 seconds, a single transaction holds a connection for that entire time. Under any real worker concurrency, the connection pool is exhausted. Additionally, the `FOR UPDATE` lock would be held for the full processing duration — serialising all worker activity through the lock even when using `SKIP LOCKED`.

**Trade-off accepted:** The gap between the two transactions is an explicit failure window — if the worker dies after `claimNextPending()` commits but before `markCompleted()`, the job stays `RUNNING` forever. This is the known gap documented in Story 1.7 and closed by leasing in Story 3.

**Revisit if:** Story 3 — the two-transaction design is retained but a heartbeat is added to the processing phase to close the failure window.

---

## [Story 1] — `fixedDelay` over `fixedRate` for worker polling

**Decision:** `@Scheduled(fixedDelay = 500)` — the worker waits 500ms after each execution finishes before starting the next.

**Alternatives considered:** `@Scheduled(fixedRate = 500)` — fires every 500ms on a wall-clock schedule regardless of execution time.

**Why not fixedRate:** If a job takes 2 seconds to process, `fixedRate` queues the next poll call while the current one is still running. Calls pile up. Under sustained load this leads to a growing backlog of scheduled executions, increasing memory pressure and potentially causing overlapping executions of the same worker. `fixedDelay` always leaves a clean gap between executions regardless of how long each took.

**Revisit if:** Story 4's multi-tier worker pools may use different intervals per tier — `CRITICAL` tier workers might poll more aggressively than `LOW` tier workers.

---

## [Story 1] — Separate `JobResponse` DTO, entity not exposed directly

**Decision:** The controller returns `JobResponse` — a dedicated record — not the `Job` entity directly.

**Alternatives considered:** Returning the `Job` entity directly from the controller; using a Jackson `@JsonIgnore` on internal fields.

**Why not entity directly:** Any field added to `Job` for internal reasons — `published_at` (Story 2's outbox relay), `lease_id` (Story 3), `retry_count` (Story 5) — immediately leaks into the API response. The API shape becomes coupled to the database schema. `@JsonIgnore` is a partial fix but requires annotating every internal field individually and still leaves the entity boundary porous. `JobResponse` is a stable, explicit API contract that evolves independently of the entity.

**Revisit if:** Never. The entity-to-DTO boundary is a permanent architectural line in this project.

---

## [Story 1] — `@JdbcTypeCode(SqlTypes.JSON)` required alongside `@Column(columnDefinition = "jsonb")`

**Decision:** Annotate the `payload` field with both `@JdbcTypeCode(SqlTypes.JSON)` and `@Column(columnDefinition = "jsonb")`.

**Why:** `@Column(columnDefinition = "jsonb")` only affects DDL — it controls what Hibernate writes in a CREATE TABLE statement. It says nothing about how to bind values at runtime. Without `@JdbcTypeCode(SqlTypes.JSON)`, Hibernate binds the String value as `character varying` at the JDBC layer, and Postgres rejects it with a type mismatch error. `@JdbcTypeCode` is the runtime instruction; `columnDefinition` is the schema instruction. Both are required.

**Revisit if:** Payload type changes — if we ever move from `String` to `Map<String, Object>` or a typed DTO, the `@JdbcTypeCode(SqlTypes.JSON)` stays but the handling changes.

---

## [Story 1] — Spring Boot 4.x requires `spring-boot-flyway` module explicitly

**Decision:** Add `spring-boot-flyway` as an explicit dependency alongside `flyway-core` and `flyway-database-postgresql`.

**Why:** In Spring Boot 3.x, Flyway auto-configuration lived in `spring-boot-autoconfigure` and triggered automatically when `flyway-core` was on the classpath. In Spring Boot 4.x, auto-configurations have been split into individual modules. Flyway's wiring moved to `spring-boot-flyway` — which nothing pulls in transitively. Without it, the Flyway JARs are present but auto-configuration never triggers, so migrations never run and Hibernate's `validate` fails at startup.

**Revisit if:** Never — this is a permanent requirement for Spring Boot 4.x.

---

## [Story 2] — Postgres-first write ordering (outbox pattern)

**Decision:** `POST /jobs` writes only to Postgres (`published_at = NULL`). A separate `JobQueuePublisher` scheduler polls for unpublished PENDING jobs and pushes them to Redis, then sets `published_at = now()`.

**Alternatives considered:** Writing to Redis directly in `POST /jobs` (Redis-first); writing to both atomically in the request handler.

**Why not Redis-first:** If the Postgres write fails after a Redis push, Redis has a job that no durable record backs. On worker pop the job_id resolves to nothing in Postgres — silent data loss. Postgres-first guarantees a durable row always exists before anything reaches the queue.

**Why not write to both in the request handler:** Cross-system atomicity (Postgres + Redis in one request) is not achievable without a distributed transaction. If Redis is down at submission time, the request fails unnecessarily — the queue being temporarily unavailable should not block job acceptance. The outbox relay decouples submission availability from queue availability.

**Revisit if:** Never for the ordering principle. The relay mechanism is reused as-is by Story 9 (Kafka ingestion).

---

## [Story 2] — `job_queue` as Redis key name (not tier-prefixed yet)

**Decision:** Use `job_queue` as the Redis list key for Story 2. Story 4 renames this to `job_queue:{tier}` (e.g. `job_queue:critical`, `job_queue:low`) when per-tier pools are introduced.

**Alternatives considered:** Using `queue:default` now to match the final naming convention from the start.

**Why not now:** The rename in Story 4 is deliberate — it should be a visible, intentional change that signals the introduction of the tier concept. Using a generic key now keeps Story 2 simple and makes the Story 4 structural change obvious rather than a silent extension.

**Revisit if:** Story 4.

---

## [Story 2] — RPOP over BRPOP for worker polling

**Decision:** Worker uses non-blocking `RPOP` inside a `@Scheduled(fixedDelay)` loop, not `BRPOP`.

**Alternatives considered:** `BRPOP` (blocking pop — server holds the connection until a job arrives).

**Why not BRPOP:**
- Holds a Redis connection open for the full wait duration — can't be returned to the pool, starving other operations (relay, heartbeats in Story 3, dedup in Story 8) under load.
- Blocks the calling thread — incompatible with Spring's shared `@Scheduled` thread pool without a dedicated `ExecutorService` per worker.
- Graceful shutdown requires custom interrupt handling; `@Scheduled` shuts down cleanly for free.
- No natural backpressure — BRPOP fires the moment a job arrives even if the worker is already at capacity; RPOP's polling loop provides backpressure inherently.

**Revisit if:** Story 4 introduces dedicated per-tier thread pools — at that point BRPOP becomes viable and worth reconsidering for lower idle latency.

---

## [Story 2] — `RelayTarget` / `JobQueue` interface introduced with one implementation

**Decision:** Introduce a `JobQueue` interface (push/pop contract) with `RedisJobQueue` as its sole implementation in Story 2.

**Alternatives considered:** Concrete `RedisJobQueue` class only, extract interface in Story 9 when a second implementation actually exists (YAGNI).

**Why now:** Story 9 will introduce a second writer through the same relay. Having the interface in place means Story 9 adds a new implementation without touching any existing class — the Open/Closed principle pays off concretely. The cost of the interface today is near-zero; retrofitting it in Story 9 requires touching every call site.

**Revisit if:** Never — the interface earns its keep in Story 9.

---

## [Story 2] — Package structure: `queue/` and `publisher/` as top-level siblings to `job/`

**Decision:** Redis queue code lives in `queue/redis/`, the outbox publisher in `publisher/` — both top-level packages, not sub-packages of `job/`.

**Alternatives considered:** `job/queue/` and `job/publisher/` as sub-packages under `job/`.

**Why not under `job/`:** The relay and queue are infrastructure that the job domain uses — they are not part of the job domain itself. Putting them under `job/` would imply the job feature owns its own queue and scheduler, which is false. As sibling packages they are clearly shared infrastructure with no ownership coupling.

**Revisit if:** A genuine multi-module split around Story 6 — at that point `queue/` and `publisher/` become natural candidates for a shared infrastructure module.

---

## [Story 2] — Known gaps accepted, deferred to future stories

The following failure scenarios are understood, documented, and deliberately deferred:

**Gap 1 — Worker pops job_id, then Postgres is unreachable.**
The job_id is gone from Redis (pop is destructive). The job is still PENDING in Postgres with `published_at` already set — the relay won't re-publish it. The job is invisible to all recovery paths until manual intervention. Closed by Story 3 leasing, which tracks the relay-to-worker handoff.

**Gap 2 — Worker crashes after pop, before marking RUNNING.**
Same stuck state as Gap 1. The job is PENDING in Postgres, not in Redis, with `published_at` set. Story 3's reaper recovers RUNNING jobs; this PENDING-but-orphaned case is also addressed once the full lease lifecycle is in place.

**Gap 3 — Double-push on relay crash.**
Relay pushes to Redis, then crashes before `published_at` is committed. Next relay run sees `published_at IS NULL` and pushes again. Two copies of the same job_id in Redis → two workers process the same job. Closed by Story 3 leasing (only one worker wins the atomic claim) and fully sealed by Story 8 idempotency (side effect fires exactly once regardless).

**Why accepted now:** Attempting to close these gaps in Story 2 would require leasing or idempotency logic that belongs to Stories 3 and 8 by design. Building them early means designing against unknown constraints; building them in order means designing against discovered ones.

---

## Template (for future entries)

```markdown
## [Story N] — One-line decision title

**Decision:** What you chose
**Alternatives considered:** What else you looked at
**Why not:** Why you rejected the alternatives
**Revisit if:** Condition under which you'd reconsider
```
