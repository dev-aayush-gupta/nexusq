# NexusQ — Learnings Journal

> Concepts that were new or clarified during this project, written as personal study notes. Not design decisions (those live in `decisions.md`) — these are foundational concepts worth revisiting before interviews or when something feels fuzzy.

---

## Lombok

### What it is
Lombok is an annotation processor that runs at **compile time** and generates boilerplate Java code — getters, setters, constructors, builders — directly into the compiled `.class` file. Your source file stays clean. There is no runtime dependency — Lombok is completely gone by the time the app starts.

### Annotations used on `Job.java` and why all four must coexist

| Annotation | What it generates | Why it's needed |
|---|---|---|
| `@Getter` | `getId()`, `getPayload()` etc. for every field | Read field values anywhere in the app |
| `@Setter` | `setStatus()`, `setPayload()` etc. for every field | Hibernate and the worker need to mutate the entity |
| `@Builder` | A fluent builder: `Job.builder().payload(...).build()` | Clean, readable object construction without positional constructors |
| `@NoArgsConstructor` | `public Job() {}` | JPA/Hibernate requires this to instantiate entities when loading rows from the database |
| `@AllArgsConstructor` | Constructor taking every field as a parameter | `@Builder` needs this internally to set all fields at build time |

**The critical constraint:** `@Builder` suppresses Java's default no-arg constructor. JPA needs the no-arg constructor. So you must explicitly add `@NoArgsConstructor` to bring it back. But `@NoArgsConstructor` + `@Builder` without `@AllArgsConstructor` causes a Lombok compile error. All four are load-bearing — remove any one and something breaks at compile or runtime.

### `@RequiredArgsConstructor`
Used on `JobService`, `JobController`, `JobWorker`. Generates a constructor taking all `final` fields as parameters. Spring sees this constructor and injects dependencies automatically — this is constructor injection, preferred over `@Autowired` on fields because dependencies are explicit and the class is testable without Spring.

### `@Slf4j`
Used on `JobWorker`. Generates:
```java
private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
```
You write `log.info(...)` directly without declaring the logger yourself.

---

## Flyway

### The problem it solves
Your Java code and database schema evolve together, but without tooling, schema changes are applied manually and inconsistently — your laptop has columns your teammate's doesn't, and production gets them at deploy time only if someone remembers to run the SQL. Flyway makes database changes code: written in SQL files, committed to git, applied automatically and consistently on every environment.

### The core idea
Every schema change is a new `.sql` file with a version number:
```
V1__create_jobs_table.sql
V2__add_published_at.sql
V3__create_dedup_keys_table.sql
```
These files are **ordered**, **permanent**, and **cumulative**. Running all of them from scratch on a fresh database always produces exactly the same schema as a database that has been running for months.

### How Flyway tracks what has already run
Flyway creates a table called `flyway_schema_history` in your database. Every time a migration runs successfully, Flyway writes a row to this table with the version number, description, and a **checksum** of the file content.

On every startup:
1. Scan `db/migration/` for all `.sql` files
2. Compare against `flyway_schema_history`
3. Run only the ones not yet recorded
4. Record each successful run

### The checksum — why you can never edit a migration
When Flyway runs a migration, it stores a checksum of the file. On every subsequent startup, it recomputes the checksum and compares. If they don't match — you edited the file after it ran — Flyway refuses to start the app:
```
Migration V1 failed checksum validation.
```
**The rule: a migration that has been run is immutable. Forever.** If you made a mistake in `V1`, write a `V2` that corrects it.

### The startup sequence with Spring Boot
```
App starts → Flyway runs migrations → Hibernate validates schema → App ready
```
Flyway always runs before Hibernate. This is why `ddl-auto=validate` is safe — by the time Hibernate checks, Flyway has already applied everything.

### The git analogy

| Git | Flyway |
|---|---|
| Commit | Migration file |
| Commit hash | Checksum |
| `git log` | `flyway_schema_history` |
| Never rewrite history | Never edit a run migration |
| Clone + all commits = current state | Fresh DB + all migrations = current schema |

---

## JPA and Hibernate basics

### What they are
**JPA** (Jakarta Persistence API) is a specification — a set of interfaces and annotations that define how Java objects map to database tables. **Hibernate** is the most popular implementation of JPA. Spring Boot wires Hibernate as the JPA provider automatically.

### Key annotations

**`@Entity`** — tells Hibernate this class maps to a database table.

**`@Table(name = "jobs")`** — specifies the table name. Without it, Hibernate uses the class name.

**`@Id`** — marks the primary key field.

**`@GeneratedValue(strategy = GenerationType.UUID)`** — Hibernate generates a UUID in Java before the insert. You know the ID before hitting the database.

**`@Column`** — customises the column mapping. Key attributes:
- `nullable = false` — adds `NOT NULL` constraint
- `updatable = false` — Hibernate never includes this column in UPDATE statements (used on `created_at`)
- `columnDefinition = "jsonb"` — overrides the inferred column type with an exact SQL type

**`@Enumerated(EnumType.STRING)`** — stores enum values as their name (`"PENDING"`) not their ordinal position (`0`). Always use STRING — ordinal breaks silently if you ever reorder the enum.

**`@CreationTimestamp`** — Hibernate sets this field to the current time on insert and never touches it again.

**`@UpdateTimestamp`** — Hibernate sets this field to the current time on every save.

### `ddl-auto` values

| Value | What Hibernate does |
|---|---|
| `update` | Auto-alters schema to match entities — dangerous with Flyway |
| `validate` | Checks schema matches entities, throws if not — correct with Flyway |
| `create-drop` | Recreates schema on startup, drops on shutdown — useful in tests |
| `none` | Does nothing |

---

## Spring Data JPA — JpaRepository

Extending `JpaRepository<Job, UUID>` gives you standard database operations for free — `save()`, `findById()`, `findAll()`, `delete()` etc. Spring generates the implementation at runtime. You write zero implementation code.

Custom methods can be declared in the interface using:
- **Method naming convention** — `findByStatus(JobStatus status)` — Spring derives the query from the method name
- **`@Query` annotation** — write JPQL (Java Persistence Query Language) manually for complex queries. JPQL uses class names and field names, not table/column names. Hibernate translates to SQL.

---

## Database locking — Pessimistic vs Optimistic

### Optimistic locking
Assume conflicts are rare. Read rows freely. At update time, check if anyone modified the row since you read it (via a `version` column). If yes, abort and retry. No lock held during processing.

**Good for:** Low-contention reads with occasional conflicts.
**Bad for:** Job queues — workers competing for the same row is the normal case, not a rare edge case. Constant retries under load.

### Pessimistic locking
Assume conflicts will happen. Acquire a lock the moment you read the row. Hold it until your transaction commits. Nobody else can touch that row while you hold it.

**`PESSIMISTIC_WRITE`** maps to `FOR UPDATE` in SQL — locks against both reads and writes from other transactions.

### `SKIP LOCKED`
Without it, `FOR UPDATE` makes a second worker **wait** for the lock to release — workers block each other.

With `SKIP LOCKED`, a second worker skips the locked row entirely and moves to the next available one. Workers never block each other. Throughput scales with worker count.

In Spring Data JPA:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
```
The `-2` is Hibernate's internal constant for SKIP LOCKED.

### What happens when a lock-holder dies
A `FOR UPDATE` lock is **transaction-scoped**. It lives in the database's memory, not the application's. The moment the database connection dies — process crash, network drop, JVM crash — Postgres detects it, rolls back the incomplete transaction, and releases all locks. The row returns to its pre-lock state automatically. No manual cleanup needed.

---

## `@Transactional`

Marks a method so that Spring wraps it in a database transaction. The transaction opens when the method is called and commits when it returns normally. If an unchecked exception is thrown, the transaction rolls back.

### `@Transactional(readOnly = true)`
A hint to Spring and the database that no writes will happen. Postgres can skip acquiring write locks and use a slightly optimised read path. Use this on all read-only service methods.

### Transaction boundaries matter
In `JobWorker`, two separate `@Transactional` methods are used intentionally:
1. `claimNextPending()` — claim the job, set RUNNING, **commit**
2. `markCompleted()` — set COMPLETED, **commit**

Processing happens between them, outside any transaction. This is correct — holding a transaction open across seconds of processing would exhaust the connection pool. The gap between the two commits is an accepted failure window (closed by leasing in Story 3).

---

## Java `Optional`

`Optional<T>` represents a value that may or may not exist — Java's alternative to returning `null`. It forces you to handle the empty case explicitly.

### Key methods

**`.map(fn)`** — if a value is present, transform it with `fn`. If empty, stay empty. The transformation is skipped entirely on an empty Optional.

**`.ifPresent(fn)`** — if a value is present, run `fn`. If empty, do nothing.

**`.orElseThrow(fn)`** — if a value is present, return it. If empty, throw the exception returned by `fn`.

**`.stream()`** — converts an Optional to a Stream of 0 or 1 elements, enabling use of stream pipeline methods like `findFirst()`.

### Why stream then findFirst in `claimNextPending()`
The repository returns `List<Job>` (required by Spring Data JPA when using `Pageable`). Converting to a stream and calling `findFirst()` produces an `Optional<Job>` — the idiomatic way to say "give me the first element if one exists, otherwise nothing." Then `.map()` chains the status update only if a job was found.

---

## Java Records

Records are a Java 16+ language feature for immutable data carriers. Java automatically generates the constructor, getters, `equals()`, `hashCode()`, and `toString()`.

```java
public record JobResponse(UUID id, String payload, JobStatus status) {}
```

Key differences from regular classes:
- **Immutable** — fields are `final`, no setters
- **Accessors use field names** — `response.payload()` not `response.getPayload()`
- **No Lombok needed** — the language does what Lombok does for data classes

**Use records for DTOs, not entities.** Entities need to be mutable (Hibernate sets fields after construction). DTOs just carry data in and out of the API layer — immutability is a feature, not a limitation.

---

## `@Scheduled` and `@EnableScheduling`

### `@EnableScheduling`
Opt-in to Spring's scheduling subsystem. Without it, Spring sees `@Scheduled` annotations and silently ignores them — no error, no warning, methods simply never run. Add it to any `@Configuration` class (typically the main application class).

### `@Scheduled(fixedDelay = 500)`
Fires the method 500ms **after the previous execution finishes**. The next run waits for the current one to complete.

**`fixedDelay` vs `fixedRate`:**
- `fixedDelay = 500` — wait 500ms after current run ends, then start next
- `fixedRate = 500` — fire every 500ms on a wall clock, regardless of whether the previous run finished

`fixedRate` causes pile-up if a run takes longer than the interval. `fixedDelay` always leaves a clean gap. For a job worker where each poll can take seconds, `fixedDelay` is correct.

### `InterruptedException` during shutdown
When the app shuts down, Spring interrupts the scheduler thread. If the worker is mid-`Thread.sleep()`, `InterruptedException` is thrown. Correct handling:
```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // restore the interrupted flag
    return;                             // exit cleanly
}
```
`Thread.currentThread().interrupt()` is important — catching `InterruptedException` clears the interrupted flag. Restoring it signals to the rest of the shutdown chain that interruption was requested. Skipping it is a subtle bug.

---

## JSONB vs TEXT vs BLOB in Postgres

| Type | What Postgres knows about the content | Validates? | Queryable inside? | Indexable inside? |
|---|---|---|---|---|
| `BLOB` | Nothing — raw bytes | No | No | No |
| `TEXT` | Nothing — raw string | No | No | No |
| `JSONB` | Full JSON structure | Yes — rejects invalid JSON | Yes — `payload->>'field'` | Yes — GIN index |

**When to use what:**
- `BLOB` — binary data (images, files). Rare in application databases.
- `TEXT` — arbitrary string data where format varies (JSON, XML, CSV). Application validates.
- `JSONB` — structured JSON where you want database-level validation and/or querying inside the document.

**The XML limitation:** `JSONB` rejects non-JSON content at the database level. If a system needs to store XML, CSV, or mixed-format payloads, `TEXT` with application-layer validation is the correct choice — the queue becomes format-agnostic and format enforcement moves to the boundary where it belongs.

---

## Hibernate `validate` is one-directional

`ddl-auto = validate` checks that every field mapped in the entity has a corresponding column in the database. It does **not** check the reverse — extra columns in the database that have no matching entity field are silently ignored. Hibernate never reads or writes those columns, so it has no reason to care they exist.

**Practical implication:** you can safely run a Flyway migration to add a column before updating the entity. The app keeps running against the old schema until the entity catches up. This enables zero-downtime schema changes in production — deploy the migration first, verify the column exists, then deploy the code that maps it.

The failure direction is the opposite: add a field to the entity without a migration, and Hibernate throws at startup because it looks for a column that doesn't exist.

---

## Compound indexes in Postgres

A compound index covers multiple columns together:
```sql
CREATE INDEX idx_jobs_status_created_at ON jobs (status, created_at);
```

This serves queries that filter on the first column and sort on the second:
```sql
WHERE status = 'PENDING' ORDER BY created_at ASC
```

**Column order matters:** Put the equality filter column first (`status`), the sort column second (`created_at`). This lets Postgres jump directly to matching status rows and read them pre-sorted. Reversing the order makes the index useless for this query pattern.

**vs two separate indexes:** A single compound index on `(status, created_at)` serves this specific query better than two separate indexes on `status` and `created_at` individually. Postgres would have to merge two index scans to satisfy both the filter and the sort with separate indexes.
