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

## Template (for future entries)

```markdown
## [Story N] — One-line decision title

**Decision:** What you chose
**Alternatives considered:** What else you looked at
**Why not:** Why you rejected the alternatives
**Revisit if:** Condition under which you'd reconsider
```
