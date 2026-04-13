# Racer v2.0.0 Migration Guide

This document lists every breaking change in v2.0.0 and the steps required to update your application.

---

## Breaking Changes

### 1. `RacerRouterService.dryRun(Object)` removed

The single-argument `dryRun` overload that accepted only a payload was deprecated in v1.6.0 (task 3.5) and has been removed in v2.0.0.

**Before (v1.x):**
```java
RouteDecision decision = routerService.dryRun(payload);
```

**After (v2.0):**
```java
// Pass null for any source you do not want to evaluate
RouteDecision decision = routerService.dryRun(payload, null, null);

// Or supply sender / messageId to evaluate SENDER and ID rules as well:
RouteDecision decision = routerService.dryRun(payload, "my-service", "msg-abc");
```

**Why?** The old method silently skipped `SENDER` and `ID` routing rules, giving incorrect results when those rule types were configured. The three-argument form evaluates all rule sources and skips any source whose argument is `null`.

---

---

### 2. `RacerMessage.priority` changed from `String` to `PriorityLevel`

The `priority` field and factory method parameter have been changed from `String` to the type-safe `PriorityLevel` enum.

**Before (v1.x):**
```java
// Builder
RacerMessage msg = RacerMessage.builder()
        .priority("HIGH")
        .build();

// Factory method
RacerMessage msg = RacerMessage.create(channel, payload, sender, "HIGH");

// Getter still returns String
String p = msg.getPriority(); // "HIGH"
```

**After (v2.0):**
```java
// Builder
RacerMessage msg = RacerMessage.builder()
        .priority(PriorityLevel.HIGH)
        .build();

// Factory method
RacerMessage msg = RacerMessage.create(channel, payload, sender, PriorityLevel.HIGH);

// Getter returns PriorityLevel
PriorityLevel p = msg.getPriority(); // PriorityLevel.HIGH
```

**JSON compatibility:** Existing wire-format JSON with `"priority":"HIGH"` is still deserialized correctly via Jackson's `@JsonCreator` on `PriorityLevel.fromString()`. Unknown values fall back to `NORMAL` with a warning log.

---

### 3. `@PublishResult.priority()` changed from `String` to `PriorityLevel`

**Before (v1.x):**
```java
@PublishResult(channel = "racer:orders", priority = "HIGH")
public Order placeOrder(...) { … }
```

**After (v2.0):**
```java
@PublishResult(channel = "racer:orders", priority = PriorityLevel.HIGH)
public Order placeOrder(...) { … }
```

The default value has changed from `""` (empty string) to `PriorityLevel.NONE` (a new sentinel that means "no priority override"; the method's `@RacerPriority` annotation is consulted instead).

---

### 4. `@RacerPriority.defaultLevel()` changed from `String` to `PriorityLevel`

**Before (v1.x):**
```java
@RacerPriority(defaultLevel = "HIGH")
public class OrderService { … }
```

**After (v2.0):**
```java
@RacerPriority(defaultLevel = PriorityLevel.HIGH)
public class OrderService { … }
```

---

### 5. `RacerPriorityPublisher.publish()` priority parameter changed from `String` to `PriorityLevel`

**Before (v1.x):**
```java
publisher.publish("orders", payload, "svc", "HIGH");
```

**After (v2.0):**
```java
publisher.publish("orders", payload, "svc", PriorityLevel.HIGH);
```

---

### 6. `RacerMessageBuilder.priority()` in racer-test changed from `String` to `PriorityLevel`

**Before (v1.x):**
```java
RacerMessageBuilder.forChannel("racer:orders")
        .priority("HIGH")
        .build();
```

**After (v2.0):**
```java
RacerMessageBuilder.forChannel("racer:orders")
        .priority(PriorityLevel.HIGH)
        .build();
```

---

### 7. `RacerPublisherRegistry` — constructor overloads removed

The 3-argument, 4-argument, and 5-argument constructors were removed. Only the full 6-argument constructor remains.

**Before (v1.x):**
```java
// 3-arg (no metrics)
new RacerPublisherRegistry(props, template, mapper)

// 4-arg (metrics only)
new RacerPublisherRegistry(props, template, mapper, Optional.of(metrics))

// 5-arg (metrics + schema)
new RacerPublisherRegistry(props, template, mapper, metricsOpt, schemaOpt)
```

**After (v2.0) — production wiring (unchanged, still uses the 6-arg constructor):**
```java
new RacerPublisherRegistry(props, template, mapper, metricsOpt, schemaOpt, rateLimiterOpt)
```

**After (v2.0) — test code:**
```java
// Use the static factory for test convenience
RacerPublisherRegistry registry = RacerPublisherRegistry.forTesting(props, template, mapper);
```
`forTesting()` passes `Optional.empty()` for all optional dependencies (no metrics, no schema validation, no rate limiting).

---

### 8. `RacerCircuitBreakerRegistry` — no-metrics constructor removed

The single-argument constructor `RacerCircuitBreakerRegistry(RacerProperties)` was removed. The remaining constructor now accepts a `RacerMetricsPort` parameter (non-nullable) instead of `@Nullable RacerMetrics`.

**Before (v1.x):**
```java
new RacerCircuitBreakerRegistry(props)
```

**After (v2.0):**
```java
new RacerCircuitBreakerRegistry(props, new NoOpRacerMetrics())
// or, with real metrics:
new RacerCircuitBreakerRegistry(props, racerMetrics)
```

The constructor parameter type changed from `@Nullable RacerMetrics` to `RacerMetricsPort`, so both `NoOpRacerMetrics` and `RacerMetrics` are accepted directly.

---

### 9. `RacerRouterService` — sync methods removed, reactive methods renamed

The deprecated synchronous routing methods were removed and the reactive variants were renamed to take their place.

**Removed methods:**
- `RouteDecision route(RacerMessage)` — deprecated since 1.3.0
- `RouteDecision evaluate(RacerMessage, List<CompiledRouteRule>)` — deprecated since 1.3.0

**Renamed methods (signature unchanged, only name):**
- `routeReactive(RacerMessage)` → `route(RacerMessage)` (returns `Mono<RouteDecision>`)
- `evaluateReactive(RacerMessage, List<CompiledRouteRule>)` → `evaluate(RacerMessage, List<CompiledRouteRule>)` (returns `Mono<RouteDecision>`)

**Before (v1.x sync usage):**
```java
RouteDecision decision = routerService.route(message);
RouteDecision decision = routerService.evaluate(message, rules);
```

**Before (v1.x reactive usage):**
```java
Mono<RouteDecision> decision = routerService.routeReactive(message);
Mono<RouteDecision> decision = routerService.evaluateReactive(message, rules);
```

**After (v2.0):**
```java
// Reactive (preferred):
Mono<RouteDecision> decision = routerService.route(message);
Mono<RouteDecision> decision = routerService.evaluate(message, rules);

// Blocking (if you must):
RouteDecision decision = routerService.route(message).block();
```
