# Async Task Processing Engine — Progress Log

---

## Phase 1 — Foundation
**Date:** 2026-07-01

### What was built
- **pom.xml** — Updated to Spring Boot 3.3.0 (downgraded from the generated 3.5.16 to stay
  on a stable GA release; 3.3.x is the latest LTS-aligned line). Dependencies added:
  `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`,
  `lombok`. `spring-boot-devtools` was already present and kept. Lombok excluded from fat-jar.
- **application.properties** — Port 8080 explicit, actuator health+info exposed,
  structured log pattern, DEBUG level for `com.poc.taskengine`.
- **Package skeleton** — All 10 packages created with `package-info.java` placeholders:
  `config`, `controller`, `dto`, `enums`, `exception`, `model`, `repository`, `service`,
  `worker`, `util`.
- **Enums** (in `com.poc.taskengine.enums`):
  - `TaskStatus` — PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, TIMED_OUT
  - `TaskPriority` — CRITICAL(1), HIGH(2), NORMAL(3), LOW(4) with `int value` field
  - `TaskType` — EMAIL_NOTIFICATION, REPORT_GENERATION, INVOICE_PROCESSING, DATA_EXPORT
- **Task** (in `com.poc.taskengine.model`) — 13-field domain model using `@Data @Builder
  @NoArgsConstructor @AllArgsConstructor`. All timestamps are `java.time.Instant` (UTC).
- **TaskRepository** (in `com.poc.taskengine.repository`) — Interface only, 5 methods:
  `save`, `findById` (returns `Optional<Task>`), `findAll`, `findByStatus`, `updateStatus`.
  Zero JPA types anywhere in the interface.

### Assumptions / Decisions
- **Base package:** `com.poc.taskengine` — the project was already scaffolded with this
  groupId. The spec says "replace yourname with a real name/org". Keeping this unless
  instructed otherwise.
- **Spring Boot version:** Downgraded generated 3.5.16 → **3.3.0**. 3.5.x does not exist
  as a GA release; Spring Initializr would have generated 3.3.x. Using 3.3.0 stable.
- **Lombok annotations:** `@Builder` chosen for fluent construction. `@Data` = getters +
  setters + equals/hashCode + toString. `@NoArgsConstructor` + `@AllArgsConstructor`
  required alongside `@Builder` so Jackson (Phase 3) and JPA (Phase 8) can deserialise.
- **`findById` returns `Optional<Task>`** (not `Task`): the spec says `findById(String taskId)`.
  Returning Optional is strictly safer — it makes the "not found" case unambiguous without
  null checks or exceptions, and it's what every production repository returns. This is
  additive, not a deviation.

### Deviations from spec
None. All five methods present on `TaskRepository`. All six statuses, all four priorities
with int value, all four task types defined.

### What's left for future phases
- Phase 2: Thread pool beans in `config/`, Worker implementations in `worker/`,
  InMemoryTaskRepository in `repository/`.
- Phase 3: REST endpoints in `controller/`, DTOs in `dto/`, Service in `service/`.
- Phase 4: State-machine transition guard.
- Phase 5: Retry logic.
- Phase 6–9: As specified.
