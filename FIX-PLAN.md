# Racer Library — Remaining Fix Plan

**Date:** 2026-03-19
**Based on:** NotifyHub `RETEST-RESULTS.md` (post-A1/A2/A4/A6 fixes)
**Scope:** Racer library issues only (not NotifyHub application bugs B1–B4 or C1)

---

## Status After Latest Fix

| ID | Severity | Status | Description |
|----|----------|--------|-------------|
| A1 | CRITICAL | ✅ FIXED | Router infinite loop — `evaluate()` and `DefaultRouteContext.publishTo()` now respect `routed` flag |
| A2 | CRITICAL | ✅ FIXED | `RacerMessage.id` null — `MessageEnvelopeBuilder` generates UUID + timestamp |
| A4 | HIGH | ✅ FIXED | Interceptors for `@RacerStreamListener` — chain injected into stream registrar |
| A6 | LOW | ✅ FIXED | Circuit breaker gauge NaN — strong reference + null-safe value |
| A3 | HIGH | ⚠️ PARTIAL | Dedup attribute exists but uses envelope UUID, not business ID |
| A5 | MEDIUM | ❌ OPEN | `/api/dlq` returns 404 |
| N1 | CRITICAL | ❌ NEW | `@RacerListener` Pub/Sub dispatch never invokes handlers |
| N2 | HIGH | ❌ NEW | `ConcurrencyMode.CONCURRENT` causes CPU busy-loop |
| N3 | MEDIUM | ❌ NEW | Missing actuator metrics (`racer.dlq.size`, `racer.dedup.duplicates`) |

**Remaining: 5 issues (1 critical, 1 high, 1 high-partial, 2 medium)**

---

## Issue Analysis & Fix Plans

### N1. [CRITICAL] `@RacerListener` Pub/Sub Dispatch Broken

**Symptom:** All `@RacerListener` workers (PushWorker, SmsWorker, AuditCollector) receive zero messages. Redis `PUBSUB NUMSUB` confirms subscribers exist and `PUBLISH` returns 1, but handler methods never fire. The `RacerTransaction` path DOES deliver (Exercise 4 showed 118K+ push/SMS entries).

**Root Cause Analysis:**

The reactive chain from `listenerContainer.receive()` → `flatMap` → `dispatch()` → `method.invoke()` is silently broken. Investigation of the code reveals multiple plausible failure points:

1. **Deserialization silent failure** (`RacerListenerRegistrar.java:475-480`):
   `objectMapper.readValue(rawJson, RacerMessage.class)` catches all exceptions and returns `Mono.empty()`. If the JSON envelope structure doesn't match `RacerMessage` fields (e.g., `MessageEnvelopeBuilder` wraps payload inside `{"channel":..., "sender":..., "payload":...}` but `RacerMessage` expects flat fields), deserialization fails silently. The ERROR log may be swallowed if log level is too high.

2. **Router short-circuit** (`RacerListenerRegistrar.java:512-527`):
   In `dispatchChecked()`, if the router evaluates a message and returns `RouteDecision.FORWARDED`, the handler is skipped entirely:
   ```java
   if (routeDecision == RouteDecision.FORWARDED) {
       return Mono.empty(); // handler never fires
   }
   ```
   If global routing rules match ALL incoming messages on a channel (e.g., a broadcast rule), every message gets forwarded and local processing is always skipped.

3. **Subscription death after first error** (`RacerListenerRegistrar.java:345-354`):
   The SEQUENTIAL/CONCURRENT path has no `onErrorResume` in the flatMap chain (unlike AUTO mode which has `.onErrorResume`). If the first `dispatch()` call throws an exception that propagates past `Mono.defer()`, the outer `flatMap` terminates the entire subscription. Subsequent messages are silently lost because the subscriber is dead:
   ```java
   // SEQUENTIAL/CONCURRENT path — NO error recovery
   listenerContainer.receive(ChannelTopic.of(resolvedChannel))
       .flatMap(msg -> dispatch(...), effectiveConcurrency)  // exception kills subscription
       .subscribe(v -> {}, ex -> log.error(...));  // logs but doesn't resubscribe
   ```
   Compare with AUTO mode which has per-message `.onErrorResume(ex -> Mono.empty())`.

**Proposed Fix:**

```
File: RacerListenerRegistrar.java (lines 342-354)

Add per-message error recovery to the SEQUENTIAL/CONCURRENT path,
matching the error handling in the AUTO-mode path:
```

```java
// BEFORE (current code):
Disposable sub = listenerContainer
    .receive(ChannelTopic.of(resolvedChannel))
    .flatMap(
        msg -> dispatch(bean, method, msg, listenerId, resolvedChannel, dedupEnabled),
        effectiveConcurrency)
    .subscribe(...);

// AFTER (with error recovery):
Disposable sub = listenerContainer
    .receive(ChannelTopic.of(resolvedChannel))
    .flatMap(
        msg -> dispatch(bean, method, msg, listenerId, resolvedChannel, dedupEnabled)
            .onErrorResume(ex -> {
                log.warn("[RACER-LISTENER] '{}' skipping message after error: {}",
                        listenerId, ex.getMessage());
                return Mono.empty();
            }),
        effectiveConcurrency)
    .subscribe(...);
```

**Verification:**
- Unit test: Mock `dispatch()` to throw on first call, verify second message still processes
- Integration test: Publish message via `publishAsync()`, assert handler fires
- Check startup logs for `[RACER-LISTENER] Registered` lines confirming subscription
- Add DEBUG log at entry of `dispatch()` to trace message receipt

**Impact:** Fixes exercises 3, 6, 7, 8, 9, 10

---

### N2. [HIGH] `ConcurrencyMode.CONCURRENT` CPU Busy-Loop

**Symptom:** Setting `@RacerListener(mode = ConcurrencyMode.CONCURRENT, concurrency = 4)` causes 150%+ CPU with zero messages processed. Reverting to SEQUENTIAL stops the spin.

**Root Cause Analysis:**

The CONCURRENT path (`RacerListenerRegistrar.java:338-354`) uses bare `flatMap(fn, concurrency)` without scheduler gating:

```java
listenerContainer.receive(ChannelTopic.of(resolvedChannel))
    .flatMap(msg -> dispatch(...), effectiveConcurrency)
    .subscribe(...);
```

Compare with AUTO mode (`lines 304-330`) which wraps dispatch in `Mono.fromCallable().subscribeOn(listenerScheduler)` with semaphore-based flow control.

The CONCURRENT path has two issues:
1. **No scheduler isolation** — `dispatch()` contains `Mono.fromCallable(() -> method.invoke(...)).subscribeOn(listenerScheduler)`, but the error recovery Mono (missing — see N1) would complete instantly on the event loop. If dispatch keeps returning errored/empty Monos rapidly, the flatMap spins processing completions without actual work.
2. **No error recovery** (linked to N1) — if dispatch throws synchronously (outside the Mono.defer), the flatMap restarts processing immediately, creating a tight loop.

**Proposed Fix:**

```
File: RacerListenerRegistrar.java (lines 338-354)

Add scheduler isolation and error recovery to match AUTO mode:
```

```java
// AFTER:
int effectiveConcurrency = ann.mode() == ConcurrencyMode.SEQUENTIAL
    ? 1
    : Math.max(1, ann.concurrency());

Disposable sub = listenerContainer
    .receive(ChannelTopic.of(resolvedChannel))
    .flatMap(msg ->
        Mono.fromCallable(() -> msg)
            .subscribeOn(listenerScheduler)
            .flatMap(m ->
                dispatch(bean, method, m, listenerId, resolvedChannel, dedupEnabled))
            .onErrorResume(ex -> {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("[RACER-LISTENER] '{}' skipping message after error: {}",
                        listenerId, ex.getMessage());
                return Mono.empty();
            }),
        effectiveConcurrency)
    .subscribe(
        v  -> {},
        ex -> log.error("[RACER-LISTENER] Fatal subscription error on '{}': {}",
                listenerId, ex.getMessage(), ex));
```

**Verification:**
- Set `@RacerListener(mode = CONCURRENT, concurrency = 4)` on a test worker
- Measure CPU — must stay below 10% idle (no messages)
- Send messages — verify all processed with correct concurrency

**Impact:** Unblocks B4 (PushWorker CONCURRENT mode in NotifyHub)

---

### A5. [MEDIUM] Built-in `/api/dlq` Returns 404

**Symptom:** `GET /api/dlq` returns 404 despite `racer.web.dlq-enabled=true`.

**Root Cause Analysis:**

Two issues identified:

1. **Endpoint path mismatch** — `DlqController` maps `@RequestMapping("/api/dlq")` at the class level with sub-paths:
   - `GET /api/dlq/messages` (list all)
   - `GET /api/dlq/size` (count)
   - `POST /api/dlq/republish/one`
   - `POST /api/dlq/republish/all`
   - `DELETE /api/dlq/purge`

   There is **no handler for `GET /api/dlq`** — the correct URL is `/api/dlq/messages`. This may be a documentation/tutorial issue rather than a code bug.

2. **Conditional bean may not activate** — `RacerWebAutoConfiguration.dlqController()` requires:
   - `racer.web.dlq-enabled=true` (property)
   - `DeadLetterQueueService.class` bean present
   - `DlqReprocessorService.class` bean present
   - `DispatcherHandler.class` on classpath
   - Reactive web application context

   While the `@AutoConfigureAfter(RacerAutoConfiguration.class)` ensures ordering, the `@ConditionalOnBean` check can still fail if the bean definitions aren't processed in the expected order due to Spring's internal bean factory evaluation.

**Proposed Fix:**

```
File: RacerWebAutoConfiguration.java (line 60)

Option A — Add matchIfMissing to default DLQ endpoint to ON:
  @ConditionalOnProperty(name = "racer.web.dlq-enabled", havingValue = "true", matchIfMissing = true)

Option B — Add a root GET handler to DlqController:
```

```java
// File: DlqController.java — add root endpoint
@GetMapping
public Flux<DeadLetterMessage> getRootMessages() {
    return dlqService.peekAll();
}
```

**Recommended:** Apply Option B (add root `GET /api/dlq` handler). If the DlqController bean IS created but the URL is wrong, this fixes it. If the bean ISN'T created, also investigate the `@ConditionalOnBean` evaluation by adding a debug log:

```java
// File: RacerWebAutoConfiguration.java — add startup diagnostic
@Bean
@ConditionalOnProperty(name = "racer.web.dlq-enabled", havingValue = "true")
@ConditionalOnBean({DeadLetterQueueService.class, DlqReprocessorService.class})
public DlqController dlqController(...) {
    log.info("[racer-web] DLQ controller activated at /api/dlq/**");
    return new DlqController(dlqService, reprocessorService);
}
```

**Verification:**
- `GET /api/dlq` → should return `[]` (or list of messages)
- `GET /api/dlq/messages` → same
- `GET /api/dlq/size` → `{"dlqSize": 0}`
- Check startup logs for `[racer-web] DLQ controller activated`

---

### A3. [HIGH — PARTIAL] Dedup Uses Envelope UUID Instead of Business ID

**Symptom:** Every message envelope gets a fresh `UUID.randomUUID()` as its `id` via `MessageEnvelopeBuilder`. When `RacerDedupService.checkAndMarkProcessed(message.getId(), listenerId)` runs, it checks this envelope UUID — which is unique per publish — so duplicates are never suppressed.

**Root Cause:**

```
File: MessageEnvelopeBuilder.java (lines 44, 69, 83)

All three build methods generate: envelope.put("id", UUID.randomUUID().toString())
```

When the same business payload is published twice (e.g., same order-id retry), each publish creates a distinct envelope UUID. The dedup service uses this envelope UUID as the dedup key, so it never sees duplicates.

**Proposed Fix — Propagate caller-supplied ID:**

The fix should allow the publisher to pass through a business ID that survives the envelope. Two changes needed:

**Change 1:** Add optional `messageId` parameter to `MessageEnvelopeBuilder`:

```java
// MessageEnvelopeBuilder.java
public static Mono<String> build(ObjectMapper objectMapper,
                                  String channel, String sender, Object payload,
                                  boolean routed, @Nullable String messageId) {
    return Mono.fromCallable(() -> {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", messageId != null ? messageId : UUID.randomUUID().toString());
        // ... rest unchanged
    });
}
```

**Change 2:** Add `publishAsync` overload with explicit message ID on `RacerChannelPublisher`:

```java
// RacerChannelPublisher.java
default Mono<Long> publishAsync(Object payload, String sender, String messageId) {
    return publishAsync(payload, sender); // default ignores messageId
}
```

**Change 3:** Extract business ID from payload if `@RacerListener(dedupKey = "$.orderId")` is set (optional enhancement — can defer):

```java
// RacerListener.java
String dedupKey() default "";  // JSONPath to extract dedup key from payload
```

**Verification:**
- Publish same payload with same explicit `messageId` twice
- Assert dedup suppresses the second delivery
- Assert `racer.dedup.duplicates` metric increments

---

### N3. [MEDIUM] Missing Actuator Metrics

**Symptom:** `racer.dlq.size` and `racer.dedup.duplicates` metrics are absent from `/actuator/metrics`.

**Root Cause:**

1. **`racer.dlq.size` gauge never registered** — `RacerMetrics.registerDlqSizeGauge(Supplier)` exists (line 99) but is **never called** anywhere in `RacerAutoConfiguration`. Compare with `registerThreadPoolGauges()` which IS called at line 242.

2. **`racer.dedup.duplicates` counter exists but never increments** — The counter is defined in `RacerMetrics.recordDedupDuplicate()` (line 268) and IS called by `RacerDedupService`. However, since dedup never actually suppresses duplicates (A3 — envelope UUID issue), the counter stays at 0 and doesn't appear in `/actuator/metrics` (Micrometer only exposes counters after first increment).

3. **`racer.listener.processed` / `racer.listener.failed` don't exist** — These metric names are from the tutorial but the actual metric names are `racer.messages.consumed` and `racer.messages.failed`. This is a documentation mismatch, not a code bug.

**Proposed Fix:**

**Fix 1:** Wire `registerDlqSizeGauge` in `RacerAutoConfiguration`:

```java
// File: RacerAutoConfiguration.java — after dlqReprocessorService bean (around line 325)

@Bean
@ConditionalOnBean(RacerMetrics.class)
public Object racerDlqMetricsRegistration(
        DeadLetterQueueService deadLetterQueueService,
        RacerMetrics racerMetrics) {
    racerMetrics.registerDlqSizeGauge(() ->
        deadLetterQueueService.size().block(Duration.ofSeconds(2)));
    return "dlq-metrics-registered";  // marker bean
}
```

> **Note:** Using `.block()` in a gauge supplier is acceptable because Micrometer scrapes gauges synchronously on a metrics thread. Alternatively, cache the size value and update it periodically.

**Fix 2:** Fix A3 first — once dedup actually suppresses duplicates, `racer.dedup.duplicates` will auto-appear.

**Fix 3:** Update tutorial documentation to reference the correct metric names:
- `racer.messages.consumed` (not `racer.listener.processed`)
- `racer.messages.failed` (not `racer.listener.failed`)

**Verification:**
- `GET /actuator/metrics/racer.dlq.size` → `{"measurements":[{"statistic":"VALUE","value":0.0}]}`
- After DLQ capture: size value > 0
- After dedup suppression (post-A3 fix): `GET /actuator/metrics/racer.dedup.duplicates` appears

---

## Fix Priority & Execution Order

```
Phase 1 — CRITICAL (unblocks 7 exercises)
  ├── N1: Add error recovery to SEQUENTIAL/CONCURRENT subscription path
  └── N2: Add scheduler isolation to CONCURRENT mode

Phase 2 — HIGH (dedup correctness)
  └── A3: Propagate business ID through MessageEnvelopeBuilder

Phase 3 — MEDIUM (endpoints & observability)
  ├── A5: Add root GET handler to DlqController + verify bean activation
  └── N3: Wire registerDlqSizeGauge in RacerAutoConfiguration
```

**Dependency graph:**
```
N1 ──→ (fixes exercises 3, 6, 7, 8, 9, 10)
  │
  ├── N2 (independent, but same code region — fix together)
  │
  ├── A5 (independent)
  │
  ├── A3 ──→ N3 (dedup metrics depend on dedup working)
  │
  └── N3 (DLQ gauge is independent of N1)
```

**Estimated files to modify:**
| File | Issues |
|------|--------|
| `RacerListenerRegistrar.java` | N1, N2 |
| `MessageEnvelopeBuilder.java` | A3 |
| `RacerChannelPublisher.java` | A3 |
| `RacerChannelPublisherImpl.java` | A3 |
| `DlqController.java` | A5 |
| `RacerAutoConfiguration.java` | N3 |
| `RacerRouterServiceTest.java` | N1 (mock updates) |
| `RacerListenerRegistrarTest.java` | N1, N2 (new test cases) |
