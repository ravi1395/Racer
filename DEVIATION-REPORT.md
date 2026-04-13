# Deviation Report: Phase 1 & Phase 2 Implementation vs. Plan

**Generated:** 2026-04-13  
**Commits analysed:**
- `9f7a280` — Phase 1 (v1.4.0): Strict validation, deser DLQ, cleanup resilience, configurable poll interval
- `ec9536c` — Phase 2 (v1.5.0): Test support module (`racer-test`)

**Overall verdict:** Both phases are substantially correct. No erroneous logic was introduced. The deviations below are structural gaps or spec drift, not bugs.

---

## Phase 1 — v1.4.0

### P1-D1 · `model/DeadLetterMessage.java` not changed despite commit message claiming it was

**Severity:** Low (misleading documentation, not broken code)

The commit message states: *"DeadLetterMessage.from() now captures `error.getMessage()` with a fallback to the exception class simple name when the message is null."*

The file `model/DeadLetterMessage.java` does not appear in the Phase 1 diff stat. Inspecting the current state of the file confirms the `from()` method already contained the correct logic:

```java
// line 31 — already present before Phase 1
.errorMessage(error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName())
```

The plan for 1.2 said *"Verify the `reason` field is populated with the full exception message"* — this verification was correct (the field was already right), but the commit message overstates what was changed. No code is wrong; the commit message is inaccurate.

**Action required:** None for correctness. Optionally amend the commit message to say "verified" rather than "changed".

---

### P1-D2 · `RacerClientFactoryBean` cleanup placed in `doFinally` (not the location the plan showed)

**Severity:** Negligible (behaviour is correct; location is better than the plan)

The plan showed the cleanup call being applied inline at the same level as the reply-polling chain:

```java
// Plan's implied structure:
redisTemplate.delete(responseStreamKey)
    .doOnError(...)
    .onErrorResume(...)
    .subscribe();
```

The implementation placed it inside `.doFinally(signal -> ...)`, which is the correct reactive pattern — it guarantees cleanup runs on success, error *and* cancellation (e.g. timeout). The plan's prose described this intent but the code snippet didn't show `doFinally`. The implementation is strictly better.

**Action required:** None.

---

## Phase 2 — v1.5.0

### P2-D1 · No multi-module Maven parent POM (structural deviation)

**Severity:** Medium

The plan's module layout implied a shared parent POM managing both `racer` and `racer-test`:

```
racer/
├── pom.xml          # existing library
racer-test/
├── pom.xml          # NEW — test support
```

The implementation made `racer-test` a **completely independent Maven project**:
- `racer-test/pom.xml` inherits from `spring-boot-starter-parent` directly (same as `racer/pom.xml`)
- The root `pom.xml` has no `<modules>` section
- The main `racer` project has no knowledge of `racer-test`

**Consequence:** Version alignment between the two artifacts is entirely manual. A consumer who uses `racer:1.4.0` and `racer-test:1.5.0` has no BOM or parent to enforce compatibility. If Phase 3 bumps `racer` to 1.6.0, `racer-test` must be separately updated and a consumer must update two version declarations by hand.

**Recommended fix:** Either (a) introduce a root parent POM with `<modules>` for both artifacts, or (b) add a `racer-bom` POM module that consumers can import. This is a structural debt item, not an emergency.

---

### P2-D2 · No test classes in the `racer-test` module itself

**Severity:** Medium

The Phase 2 Summary in the plan stated that each sub-item (2.2 through 2.6) should produce "1 class + 1 test". The `racer-test` module has no Java files under `src/test/java/` at all.

| Component | Expected test class | Present? |
|-----------|---------------------|----------|
| `InMemoryRacerPublisher` | `InMemoryRacerPublisherTest` | No |
| `InMemoryRacerPublisherRegistry` | `InMemoryRacerPublisherRegistryTest` | No |
| `RacerTestHarness` | `RacerTestHarnessTest` | No |
| `@RacerTest` / `RacerTestAutoConfiguration` | Context-load test | No |
| `RacerMessageBuilder` | `RacerMessageBuilderTest` | No |

**Consequence:** The test-support module ships untested. Assertion helpers in `InMemoryRacerPublisher` (e.g. `assertMessageCount`, `assertPayload`) and the `RacerTestHarness.fireAt` paths have no automated coverage.

**Recommended fix:** Add the five missing test classes before cutting the v1.5.0 tag. These are plain JUnit 5 / Mockito tests — no Redis or Testcontainers needed.

---

### P2-D3 · `@RacerTest.channels()` attribute is non-functional

**Severity:** Medium (spec drift — documented behaviour not implemented)

The plan specified:

> *"Channel aliases to register in the in-memory publisher registry. Defaults to the aliases defined in `application.properties`."*

The implementation's Javadoc explicitly says:

> *"Note: this attribute is informational metadata — the actual publisher registration uses `InMemoryRacerPublisherRegistry.init()`, which reads from `RacerProperties`. To make additional channels available, configure them via `application.properties` or inline `@TestPropertySource` rather than relying on this attribute alone."*

So the attribute is present on the annotation but does nothing at runtime. A test author who writes `@RacerTest(channels = {"foo", "bar"})` expecting those channels to be pre-registered will be silently surprised when `getTestPublisher("foo")` returns `null`.

**Recommended fix:** Either implement the wiring (pass `channels()` into `InMemoryRacerPublisherRegistry.init()` so it registers the extra aliases), or remove the attribute from the annotation to avoid the false affordance. Keeping a non-functional attribute is the worst outcome.

---

### P2-D4 · Production classes expose test-harness API surface

**Severity:** Low (design trade-off, not a bug)

The plan for 2.4 said:

> *"Requires refactoring in `RacerListenerRegistrar`: Extract the message-processing pipeline ... into a package-private `processMessage(listenerId, RacerMessage)` method."*

The implementation made `processMessage` and the `ListenerRegistration` record **public** on `RacerListenerRegistrar` and `RacerStreamListenerRegistrar`. The same is true of `getListenerRegistrations()`. These are production classes whose public API is part of the library contract.

**Consequence:** Library consumers can now call `listenerRegistrar.processMessage(...)` outside of tests, which was not intended. The `ListenerRegistration` record is also part of the public API surface.

**Recommended fix (optional):** Annotate these methods/types with `@VisibleForTesting` (Guava or a local equivalent) and add a Javadoc note that they are internal. Alternatively, scope the test integration behind a package-private interface that only `racer-test` implements — but that would require a more invasive refactor. The current state is acceptable for a library that doesn't enforce strong encapsulation.

---

### P2-D5 · Unplanned changes to `RedisConfig.java`

**Severity:** Low (emergent necessity, not erroneous)

Two conditional annotations were added to `RedisConfig.java` in the Phase 2 commit that were not in either phase's plan:

```java
// reactiveStringRedisTemplate
@ConditionalOnMissingBean(name = "reactiveStringRedisTemplate")
@ConditionalOnBean(ReactiveRedisConnectionFactory.class)

// reactiveRacerMessageRedisTemplate
@ConditionalOnBean(ReactiveRedisConnectionFactory.class)
```

These changes are correct and necessary — without them, the `racer-test` context would crash trying to create Redis beans when no `ReactiveRedisConnectionFactory` is available. However, they were not mentioned in the plan.

**Consequence:** Minor spec drift. The changes are safe and improve overall configurability (the library now plays nicely in no-Redis contexts). No action required, but `IMPLEMENTATION-PLAN.md` should be updated to record this as part of Phase 2.

---

### P2-D6 · Additional `fireAtStreamListener` overload not in plan

**Severity:** Negligible (extension, not deviation)

The plan specified one `fireAtStream(String streamKey, String group, RacerMessage)` method in `RacerTestHarness`. The implementation also added:

```java
public Mono<Void> fireAtStreamListener(String listenerId, RacerMessage message)
```

This is an additive convenience method and does not contradict the plan.

---

## Summary Table

| ID | Phase | Severity | Category | Action |
|----|-------|----------|----------|--------|
| P1-D1 | Phase 1 | Low | Misleading commit message | None (optional message fix) |
| P1-D2 | Phase 1 | Negligible | Better than planned | None |
| P2-D1 | Phase 2 | Medium | Missing parent POM / BOM | Add root POM or BOM module |
| P2-D2 | Phase 2 | Medium | Missing tests | Write 5 test classes in `racer-test` |
| P2-D3 | Phase 2 | Medium | Non-functional API | Implement or remove `@RacerTest.channels()` |
| P2-D4 | Phase 2 | Low | Public test API on prod classes | Add `@VisibleForTesting` / Javadoc |
| P2-D5 | Phase 2 | Low | Unplanned code change | Update plan doc |
| P2-D6 | Phase 2 | Negligible | Positive extension | None |

**No erroneous code was found.** All logic paths are sound. The three Medium items (P2-D1, P2-D2, P2-D3) should be resolved before Phase 3 work begins.
