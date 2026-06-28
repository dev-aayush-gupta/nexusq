# Story 1 — Manual Test Plan

> Assumptions: Docker containers up, app running on port 8080, Postgres accessible on 5433.
> Log results in `story-1-test-results.md` as you go.

---

## Phase 1 — Verify infrastructure

### 1.1 App health
```
GET http://localhost:8080/actuator/health
```
**Expect:** `status: UP` with `db`, `redis`, `kafka` all `UP` individually.

### 1.2 Flyway ran cleanly
Connect to Postgres:
```bash
psql -h localhost -p 5433 -U nexusq -d nexusq
```
Then run:
```sql
SELECT version, description, success FROM flyway_schema_history;
```
**Expect:** One row — version `1`, description `create jobs table`, success `true`.

### 1.3 Jobs table exists and is empty
```sql
SELECT * FROM jobs;
```
**Expect:** `0 rows`

### 1.4 Index exists
```sql
SELECT indexname FROM pg_indexes WHERE tablename = 'jobs';
```
**Expect:** `jobs_pkey` and `idx_jobs_status_created_at`

---

## Phase 2 — Submit a job

### 2.1 Submit with priority
```
POST http://localhost:8080/jobs
Content-Type: application/json

{
    "payload": "{\"type\": \"email\", \"to\": \"ayush@example.com\"}",
    "priority": "HIGH"
}
```
**Expect:** `201 Created` with body `{"id": "<uuid>"}`. Save this UUID for Phase 3 and 4.

### 2.2 Submit without priority — should default to DEFAULT
```
POST http://localhost:8080/jobs
Content-Type: application/json

{
    "payload": "{\"type\": \"sms\", \"to\": \"+91999999999\"}"
}
```
**Expect:** `201 Created`. Verify in DB that `priority = DEFAULT`.

### 2.3 Submit with blank payload — validation failure
```
POST http://localhost:8080/jobs
Content-Type: application/json

{
    "payload": ""
}
```
**Expect:** `400 Bad Request`.

### 2.4 Submit with invalid priority enum
```
POST http://localhost:8080/jobs
Content-Type: application/json

{
    "payload": "{\"type\": \"test\"}",
    "priority": "URGANT"
}
```
**Expect:** `400 Bad Request` — Jackson rejects unknown enum value.

---

## Phase 3 — Watch the database

Run this query in psql repeatedly after each phase:
```sql
SELECT id, status, priority, created_at, updated_at
FROM jobs
ORDER BY created_at DESC;
```

| Moment | Expected status |
|---|---|
| Right after POST | `PENDING` |
| Within 500ms | `RUNNING` |
| After 10 seconds | `COMPLETED` |

Also verify:
- `created_at` stays the same across all three checks
- `updated_at` changes on every status transition

---

## Phase 4 — Query job status via GET

Use the UUID saved from 2.1.

### 4.1 Query while PENDING
Hit immediately after POST, before 500ms elapses:
```
GET http://localhost:8080/jobs/<uuid>
```
**Expect:** `status: PENDING`

### 4.2 Query while RUNNING
Hit within the 10 second processing window:
```
GET http://localhost:8080/jobs/<uuid>
```
**Expect:** `status: RUNNING`

### 4.3 Query after COMPLETED
Hit after 10 seconds:
```
GET http://localhost:8080/jobs/<uuid>
```
**Expect:** `status: COMPLETED`, `createdAt` same as 4.1, `updatedAt` changed.

### 4.4 Query unknown ID
```
GET http://localhost:8080/jobs/00000000-0000-0000-0000-000000000000
```
**Expect:** `404 Not Found` with message `Job not found: 00000000-...`

---

## Phase 5 — Actuator checks

### 5.1 Scheduled tasks registered
```
GET http://localhost:8080/actuator/scheduledtasks
```
**Expect:** `JobWorker.poll` visible with `fixedDelay: 500`

### 5.2 Live SQL logging toggle
Turn SQL logging ON:
```
POST http://localhost:8080/actuator/loggers/org.hibernate.SQL
Content-Type: application/json

{"configuredLevel": "DEBUG"}
```
Submit a job, watch SQL appear in terminal. Then turn it OFF:
```
POST http://localhost:8080/actuator/loggers/org.hibernate.SQL
Content-Type: application/json

{"configuredLevel": "OFF"}
```
**Expect:** SQL disappears from terminal without restart.

---

## Phase 6 — Failure scenario (Story 1.7)

This reproduces the known gap that Story 3 will fix.

### Step 1 — Submit a job
```
POST http://localhost:8080/jobs
Content-Type: application/json

{"payload": "{\"type\": \"failure-test\"}"}
```
Save the UUID.

### Step 2 — Watch terminal until you see
```
INFO  JobWorker : Processing job <uuid>
```
The job is now `RUNNING` in the database. You have 10 seconds.

### Step 3 — Kill the app immediately
`Ctrl+C` in the terminal running the app.

### Step 4 — Verify stuck job in psql
```sql
SELECT id, status FROM jobs WHERE id = '<uuid>';
```
**Expect:** `status = RUNNING`

### Step 5 — Restart the app and wait 5 seconds
```
GET http://localhost:8080/jobs/<uuid>
```
**Expect:** Still `RUNNING` — the worker never picks it up because it only claims `PENDING` jobs.

**This is the intentional failure. Document it in `story-1-test-results.md`.**

---

## Checklist

| # | Test | Pass/Fail |
|---|---|---|
| 1.1 | Health endpoint — all components UP | |
| 1.2 | Flyway history — V1 success | |
| 1.3 | Jobs table empty on fresh start | |
| 1.4 | Compound index exists | |
| 2.1 | POST with priority returns 201 + UUID | |
| 2.2 | POST without priority defaults to DEFAULT | |
| 2.3 | POST with blank payload returns 400 | |
| 2.4 | POST with invalid priority returns 400 | |
| 3.1 | DB shows PENDING → RUNNING → COMPLETED | |
| 3.2 | created_at unchanged, updated_at changes | |
| 4.1 | GET returns PENDING immediately after POST | |
| 4.2 | GET returns RUNNING during processing | |
| 4.3 | GET returns COMPLETED after processing | |
| 4.4 | GET unknown ID returns 404 | |
| 5.1 | Actuator shows JobWorker.poll scheduled | |
| 5.2 | SQL logging toggles live without restart | |
| 6.1 | Job stays RUNNING after worker killed mid-job | |
| 6.2 | Restarted app never recovers stuck job | |
