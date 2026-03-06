# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned — Phase 4 Roadmap

#### 4.1 — Cluster-Aware Publishing
- Consistent-hash routing for multi-shard topologies
- Automatic failover when a shard becomes unavailable

#### 4.2 — Distributed Tracing (OpenTelemetry)
- Propagate W3C `traceparent` header across `RacerMessage` hops
- Auto-instrument `@RacerListener`, `@RacerStreamListener`, and `RacerChannelPublisher`

#### 4.3 — Rate Limiting
- Per-channel token-bucket limiter stored in Redis
- Configurable burst size and refill rate via `racer.rate-limit.*`

#### 4.4 — Racer Admin UI
- Actuator-backed REST endpoints for live listener stats, DLQ viewer, and circuit breaker state
- Optional embedded web console

---

## [1.1.0] - 2026-03-06

### Added

#### 3.1 — Message Deduplication
- `RacerDedupService` — idempotency via Redis `SET NX EX`; fails-open on Redis errors
- `@RacerListener(dedup = true)` — per-listener opt-in to dedup (requires `racer.dedup.enabled=true`)
- `racer.dedup.*` properties: `enabled`, `ttl-seconds` (default 300s), `key-prefix` (default `racer:dedup:`)

#### 3.2 — Circuit Breaker
- `RacerCircuitBreaker` — count-based sliding-window circuit breaker (no Resilience4j dependency)
  - States: `CLOSED` → `OPEN` → `HALF_OPEN` → `CLOSED`
  - Transitions: opens when failure rate ≥ threshold after window fills; re-opens on probe failure
- `RacerCircuitBreakerRegistry` — per-listener lazy circuit breaker registry
- `racer.circuit-breaker.*` properties: `enabled`, `failure-rate-threshold` (default 50%), `sliding-window-size` (default 10), `wait-duration-in-open-state-seconds` (default 30), `permitted-calls-in-half-open-state` (default 3)
- Circuit breaker applied to both `@RacerListener` and `@RacerStreamListener` dispatch pipelines

#### 3.3 — Back-pressure Signaling
- `RacerBackPressureMonitor` — monitors the Racer thread-pool queue fill ratio
  - Activates when fill ratio ≥ `racer.backpressure.queue-threshold`
  - Pauses Pub/Sub dispatch (`RacerListenerRegistrar.setBackPressureActive(true)`)
  - Slows XREADGROUP poll rate to `racer.backpressure.stream-poll-backoff-ms`
  - Reverses both when the queue drains
- `racer.backpressure.*` properties: `enabled`, `queue-threshold` (default 0.80), `check-interval-ms` (default 1000), `stream-poll-backoff-ms` (default 2000)
- Stream poll interval made fully dynamic — `RacerStreamListenerRegistrar` honours runtime overrides

#### 3.4 — Consumer Group Lag Dashboard
- `RacerConsumerLagMonitor` — periodic `XPENDING` scraper; exports one `racer.stream.consumer.lag` Micrometer gauge per (stream, group) pair
- WARN log when lag exceeds `racer.consumer-lag.lag-warn-threshold`
- `racer.consumer-lag.*` properties: `enabled`, `scrape-interval-seconds` (default 15), `lag-warn-threshold` (default 1000)

#### Infrastructure
- `racerListenerExecutor` exposed as a named `ThreadPoolExecutor` bean
- Thread-pool metrics (`racer.thread-pool.*`) registered automatically when Micrometer is present
- All Phase 3 beans are **off by default** via `@ConditionalOnProperty` — zero impact on existing deployments

#### Observability Polish
- `racer.circuit.breaker.state{listener}` Micrometer gauge — numeric state per listener: `0` = CLOSED, `1` = OPEN, `2` = HALF_OPEN
- `racer.backpressure.active` Micrometer gauge — `1` while back-pressure is in effect, `0` otherwise (live value, not snapshot)
- `racer.backpressure.events{state}` Micrometer counter — increments on each activation (`state=active`) and deactivation (`state=inactive`) transition
- `racer.dedup.duplicates{listener}` Micrometer counter — counts duplicate messages suppressed per listener

#### Graceful Shutdown (`SmartLifecycle`)
- `RacerListenerRegistrar` now implements `SmartLifecycle` (`phase = Integer.MAX_VALUE`) — unsubscribes from channels only after in-flight dispatches complete
- `RacerStreamListenerRegistrar` now implements `SmartLifecycle` — stops polling loops after pending stream-record processing drains
- In-flight request count tracked with `AtomicInteger`; `stop(Runnable)` is fully async (non-blocking via `boundedElastic`) so Spring's shutdown thread is never blocked
- Stopping gate added to both `dispatch()` and `processRecord()` — new messages are rejected gracefully when shutdown is in progress, not mid-stream
- `racer.shutdown.timeout-seconds` (default `30`) controls the maximum drain wait before forcing disposal

### Changed
- `@RacerListener` gains a `dedup` boolean attribute (default `false`)
- `RacerAutoConfiguration` wires optional dedup, circuit breaker, and back-pressure beans into both listener registrars via setter injection
- `RacerDedupService` now accepts an optional `RacerMetrics` reference; `checkAndMarkProcessed` overloaded with `(messageId, listenerId)` for per-listener duplicate counters
- `RacerCircuitBreakerRegistry` now accepts an optional `RacerMetrics` reference; registers the state gauge lazily on first `getOrCreate` call

---

## [1.0.0] - 2026-03-05

### Added

#### Core Messaging
- `@RacerListener` — Pub/Sub listener annotation with `SEQUENTIAL`, `CONCURRENT`, and `AUTO` concurrency modes
- `@RacerPublisher` — field-injectable publisher bean; supports `channel`, `sender`, `async`, and `ttl` properties
- `@EnableRacer` — entry-point annotation to activate all Racer infrastructure
- `RacerMessage` — envelope model with `id`, `sender`, `payload`, and `timestamp` fields
- `RacerPublisherRegistry` — runtime registry of all declared `@RacerPublisher` fields
- `RacerPublisherFieldProcessor` — BeanPostProcessor that injects `RacerChannelPublisher` instances

#### Redis Streams
- `@RacerStreamListener` — consumer-group stream listener backed by `XREADGROUP`
- `RacerStreamListenerRegistrar` — registers and starts all `@RacerStreamListener` subscriptions
- `RacerStreamPublisher` — typed `XADD` publisher for Redis Streams
- `RacerShardedStreamPublisher` — sharded stream publishing with configurable shard count
- `RacerRetentionService` — scheduled `XTRIM MAXLEN` to cap stream length

#### Request / Reply
- `@RacerClient` — declarative request/reply interface injected via `@EnableRacerClients`
- `@RacerResponder` — annotates a method as the reply handler for a request channel
- `RacerClientFactoryBean` — dynamic proxy implementing `@RacerClient` interfaces
- `RacerClientRegistrar` — scans for `@EnableRacerClients` and registers client beans
- `RacerResponderRegistrar` — wires responder methods to their reply channels
- `RacerRequest` / `RacerReply` — request and reply envelope models
- `RacerRequestReplyException` / `RacerRequestReplyTimeoutException` — typed exceptions

#### Content-Based Routing
- `@RacerRoute` — marks a class as a content-based router
- `@RacerRouteRule` — defines a SpEL condition + target channel for routing rules
- `RacerRouterService` — evaluates rules and dispatches messages to target channels

#### Dead Letter Queue
- `@RacerListener(dlq = ...)` — automatic DLQ routing on listener exception
- `DeadLetterQueueService` — enqueues failed messages with original channel and error details
- `DlqReprocessorService` — re-publishes individual or all DLQ entries back to origin channel
- `DeadLetterMessage` — DLQ envelope model with `originalChannel`, `errorMessage`, and `retryCount`
- `DlqController` — REST endpoints: `GET /api/dlq`, `POST /api/dlq/republish/one`, `POST /api/dlq/republish/all`

#### Priority Messaging
- `@RacerPriority` — specifies priority level (`HIGH`, `MEDIUM`, `LOW`) on a publisher or listener
- `PriorityLevel` — enum defining priority sub-channel suffix strategy
- `RacerPriorityPublisher` — publishes to priority-ranked sub-channels

#### Polling
- `@RacerPoll` — triggers a method on a configurable interval to publish messages
- `RacerPollRegistrar` — registers polling tasks with a dedicated scheduler

#### AOP Side-Effect Publishing
- `@PublishResult` — publishes a method's return value to a channel as a side effect
- `PublishResultAspect` — AOP advice that intercepts `@PublishResult` methods; inherits `sender` and `async` from the declaring `@RacerPublisher` field

#### Schema Validation
- `@RacerListener(schema = ...)` — validates incoming message payload against a JSON Schema
- `RacerSchemaRegistry` — loads and caches JSON Schema definitions
- `SchemaValidationException` / `SchemaViolation` — typed schema error models

#### Transactions
- `RacerTransaction` — groups multiple publishes into a single Redis `MULTI`/`EXEC` pipeline

#### Pipelining
- `RacerPipelinedPublisher` — sends a batch of messages in a single Redis pipeline round-trip

#### Adaptive Concurrency
- `ConcurrencyMode.AUTO` — AIMD adaptive concurrency tuner per listener
- `AdaptiveConcurrencyTuner` — increases permits on success, decreases on error; configurable `min`, `max`, `increment`, `decrement` via `racer.auto.*`

#### Observability
- `RacerMetrics` — Micrometer integration: `racer.listener.processed`, `racer.listener.failed` counters; thread pool gauges (`queue-depth`, `active-threads`, `pool-size`); `racer.stream.consumer.lag` gauge; `racer.auto.concurrency` gauge per listener
- `RacerHealthIndicator` — Spring Boot Actuator `ReactiveHealthIndicator` reporting Redis connectivity, active subscription count, DLQ depth (warn threshold via `racer.health.dlq-warn-threshold`), and thread pool utilization

#### Exception Hierarchy
- `RacerException` — base `RuntimeException` for all Racer errors
- `RacerPublishException` — failure publishing to Redis
- `RacerListenerException` — failure dispatching to a listener method
- `RacerDlqException` — failure enqueuing to the Dead Letter Queue
- `RacerConfigurationException` — startup-time misconfiguration

#### Configuration & Auto-Configuration
- `RacerProperties` — externalized configuration under `racer.*`; thread pool (`racer.thread-pool.*`), auto-concurrency (`racer.auto.*`), health (`racer.health.*`), stream consumer (`racer.consumer.*`)
- `RacerAutoConfiguration` — Spring Boot 3 auto-configuration registered via `AutoConfiguration.imports`
- `RacerWebAutoConfiguration` — optional web layer auto-configuration (conditional on WebFlux)
- `RacerHealthAutoConfiguration` — health indicator auto-configuration (conditional on Actuator)
- `spring-boot-configuration-processor` — generates `META-INF/spring-configuration-metadata.json` for IDE autocomplete on `racer.*` properties

#### REST API
- `ChannelRegistryController` — `GET /api/channels` lists all registered listener channels

#### Integration Tests
- `RacerIntegrationTestBase` — base class that starts a Redis container via Docker CLI and wires a full Spring context
- `PubSubIntegrationTest` — end-to-end pub/sub tests (SEQUENTIAL, CONCURRENT, AUTO modes)
- `DlqIntegrationTest` — DLQ routing on exception and `/api/dlq/republish/one` round-trip
- `StreamListenerIntegrationTest` — XADD → XREADGROUP consumer group delivery
- `RequestReplyIntegrationTest` — `@RacerClient` + `@RacerResponder` round-trip

#### Documentation & Examples
- `README.md` — full feature overview and quick-start guide
- `RACER-PROPERTIES.md` — complete reference for all `racer.*` configuration properties
- `TUTORIALS.md` — 20 step-by-step tutorials covering every feature
- `TUTORIAL-NEW-APP.md` — guide for integrating Racer into a new Spring Boot 3 application
- Docker Compose files: `compose.yaml` (standalone), `compose.sentinel.yaml` (Redis Sentinel), `compose.cluster.yaml` (Redis Cluster)

---

## [Unreleased]

<!-- Features merged to main but not yet released go here -->
