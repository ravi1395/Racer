# Racer — Reactive Redis Messaging

A multi-module Spring Boot application demonstrating reactive Redis messaging patterns:

- **Fire-and-forget Pub/Sub** — async and sync message publishing
- **Two-way Request-Reply** over both Pub/Sub and Redis Streams
- **Dead Letter Queue (DLQ)** with retry, republish, and age-based pruning
- **Sync vs Async** consumer mode switchable at runtime
- **Racer Annotations** — `@EnableRacer`, `@RacerPublisher`, `@PublishResult`, `@RacerPriority` for declarative, property-driven publishing
- **Multiple Channels** — declare unlimited named channels in `application.properties`
- **Durable Publishing** — `@PublishResult(durable = true)` writes to Redis Streams for at-least-once delivery
- **Content-Based Router** — `@RacerRoute` / `@RacerRouteRule` for regex-pattern message routing
- **Atomic Batch Publish** — `RacerTransaction.execute()` for ordered multi-channel publish
- **Pipelined Batch Publish** — `RacerPipelinedPublisher` / `/api/publish/batch-pipelined` issues all commands in parallel for maximum throughput
- **Consumer Scaling** — configurable concurrency per stream, named consumers, and key-based sharding via `RacerShardedStreamPublisher`
- **Message Priority** — `RacerPriorityPublisher` + `RacerPriorityConsumerService` route messages to `HIGH`/`NORMAL`/`LOW` sub-channels
- **Micrometer Metrics** — Prometheus/Actuator instrumentation for published/consumed/failed/DLQ/latency counters
- **Retention Service** — scheduled `XTRIM` + DLQ age-based eviction
- **High Availability** — Sentinel and Cluster Docker Compose topologies included

> **Building a new service?** Follow the **[New App from Scratch →](TUTORIAL-NEW-APP.md)** guide for a complete end-to-end walkthrough.
> **Want feature-level tutorials?** Browse the **[Tutorials →](TUTORIALS.md)** for step-by-step walkthroughs of every feature.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Project Structure](#project-structure)
3. [Prerequisites & Setup](#prerequisites--setup)
4. [Running the Application](#running-the-application)
5. [Configuration Reference](#configuration-reference)
6. [Racer Annotations](#racer-annotations)
   - [@EnableRacer](#enableracer)
   - [@RacerPublisher — field injection](#racerpublisher--field-injection)
   - [@PublishResult — method-level auto-publish](#publishresult--method-level-auto-publish)
   - [@RacerRoute — content-based routing](#racerroute--content-based-routing)
   - [@RacerPriority — message priority routing](#racerpriority--message-priority-routing)
   - [@RacerPoll — scheduled publishing](#racerpoll--scheduled-publishing)
   - [Multi-channel configuration](#multi-channel-configuration)
7. [Redis Keys & Channels Reference](#redis-keys--channels-reference)
8. [Message Schemas](#message-schemas)
9. [API Reference — Server (port 8080)](#api-reference--server-port-8080)
   - [Publish APIs](#publish-apis)
   - [Request-Reply APIs](#request-reply-apis)
   - [Router APIs](#router-apis)
   - [Channel Registry APIs](#channel-registry-apis)
10. [API Reference — Client (port 8081)](#api-reference--client-port-8081)
    - [Consumer APIs](#consumer-apis)
    - [DLQ APIs](#dlq-apis)
    - [Responder Status API](#responder-status-api)
11. [Observability & Metrics](#observability--metrics)
12. [High Availability](#high-availability)
13. [Consumer Scaling & Sharding](#consumer-scaling--sharding)
14. [Pipelined Publishing](#pipelined-publishing)
15. [Message Priority](#message-priority)
16. [End-to-End Flows](#end-to-end-flows)
17. [Extending the Application](#extending-the-application)
18. [Error Handling & DLQ Behaviour](#error-handling--dlq-behaviour)
19. [Comparison with Other Brokers](#comparison-with-other-brokers)
    - [Architecture at a Glance](#architecture-at-a-glance)
    - [Advantages of Racer](#advantages-of-racer)
    - [Disadvantages & Mitigations](#disadvantages--mitigations)
    - [When to Use What](#when-to-use-what)
20. [Roadmap & Implementation Status](#roadmap--implementation-status)
21. [Tutorials](TUTORIALS.md) *(separate file)*

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                   REDIS                                       │
│                                                                               │
│  Pub/Sub channels          Streams (durable)          Lists                  │
│  ─────────────────         ─────────────────────      ─────────────────      │
│  racer:messages            racer:stream:requests       racer:dlq             │
│  racer:notifications       racer:stream:response:*                           │
│  racer:reply:*             racer:<name>:stream  ←── @PublishResult(durable)  │
│  racer:<channel>  ←─ @RacerRoute routes here                                 │
└──────────┬─────────────────────────┬──────────────────────────┬─────────────┘
           │ pub/sub                 │ streams                   │ list ops
 ┌─────────┴────────────┐  ┌────────┴────────────┐  ┌──────────┴──────────────┐
 │   racer-server :8080  │  │  racer-server        │  │  racer-client (DLQ)     │
 │                       │  │  (durable writer)    │  │  RacerRetentionService  │
 │  PublisherService     │  └─────────────────────-┘  └─────────────────────-  ┘
 │  PubSubRequestReply   │         │
 │  StreamRequestReply   │         │ consume group
 │  RacerTransaction     │  ┌──────┴──────────────┐
 │  RouterController     │  │  racer-client :8081  │
 └───────────────────────┘  │  (durable reader)    │
           │ subscribe       │  RacerStreamConsumer  │
 ┌─────────┴────────────┐   └──────────────────────┘
 │   racer-client :8081  │
 │                       │
 │  ConsumerSubscriber   │◄─── RacerRouterService (content-based routing)
 │  PubSubResponder      │
 │  StreamResponder      │
 │  DLQ Services         │
 └───────────────────────┘

Metrics: RacerMetrics (Micrometer) wired into all publish/consume/DLQ paths
         → exposed via /actuator/metrics and /actuator/prometheus
```

| Module | Role | Port |
|--------|------|------|
| `racer-common` | Shared models, constants, Redis config | — |
| `racer-server` | Publisher, request-reply initiator | 8080 |
| `racer-client` | Subscriber, request-reply responder, DLQ | 8081 |

---

## Project Structure

```
racer/
├── pom.xml                          # Parent POM (packaging: pom)
├── compose.yaml                     # Docker Compose (single Redis)
├── compose.sentinel.yaml            # High-availability: Sentinel mode
├── compose.cluster.yaml             # High-availability: Cluster mode
│
├── racer-common/                    # Shared library
│   └── src/main/java/com/cheetah/racer/common/
│       ├── RedisChannels.java       # Channel/key constants
│       ├── annotation/
│       │   ├── EnableRacer.java         # Activates the annotation framework
│       │   ├── RacerPublisher.java      # Field injection annotation
│       │   ├── PublishResult.java       # Method auto-publish (+ durable mode)
│       │   ├── RacerRoute.java          # Content-based routing (container)
│       │   └── RacerRouteRule.java      # Per-rule: field, matches, to, sender
│       ├── aspect/
│       │   └── PublishResultAspect.java # AOP: pub/sub OR durable stream
│       ├── config/
│       │   ├── RedisConfig.java              # ReactiveRedisTemplate beans
│       │   ├── RacerAutoConfiguration.java   # Wires all beans
│       │   └── RacerProperties.java          # racer.* property binding (+ retention)
│       ├── metrics/
│       │   └── RacerMetrics.java        # Micrometer counters/timers/gauge
│       ├── model/
│       │   ├── RacerMessage.java        # Fire-and-forget message
│       │   ├── RacerRequest.java        # Request-reply request
│       │   ├── RacerReply.java          # Request-reply response
│       │   └── DeadLetterMessage.java
│       ├── processor/
│       │   └── RacerPublisherFieldProcessor.java  # BeanPostProcessor for @RacerPublisher
│       ├── publisher/
│       │   ├── RacerChannelPublisher.java       # Publisher interface
│       │   ├── RacerChannelPublisherImpl.java    # Pub/Sub implementation (+ metrics)
│       │   ├── RacerPublisherRegistry.java       # Multi-channel registry
│       │   └── RacerStreamPublisher.java         # Durable stream publisher (XADD)
│       ├── router/
│       │   └── RacerRouterService.java    # @PostConstruct scans @RacerRoute beans
│       └── tx/
│           └── RacerTransaction.java      # Atomic ordered multi-channel publish
│
├── racer-server/                    # Publisher / server module
│   └── src/main/java/com/cheetah/racer/server/
│       ├── RacerServerApplication.java   # @EnableRacer activated here
│       ├── config/
│       │   └── ServerRedisListenerConfig.java
│       ├── service/
│       │   ├── PublisherService.java
│       │   ├── PubSubRequestReplyService.java   # metrics timer
│       │   └── StreamRequestReplyService.java   # metrics timer
│       └── controller/
│           ├── PublisherController.java          # + /batch-atomic endpoint
│           ├── RequestReplyController.java
│           ├── RouterController.java             # GET /api/router/rules, POST /api/router/test
│           └── ChannelRegistryController.java
│
└── racer-client/                    # Consumer / client module
    └── src/main/java/com/cheetah/racer/client/
        ├── RacerClientApplication.java
        ├── config/
        │   └── RedisListenerConfig.java
        ├── service/
        │   ├── MessageProcessor.java            (interface)
        │   ├── SyncMessageProcessor.java
        │   ├── AsyncMessageProcessor.java
        │   ├── ConsumerSubscriber.java          # + router + metrics
        │   ├── PubSubResponderService.java
        │   ├── StreamResponderService.java
        │   ├── DeadLetterQueueService.java
        │   ├── DlqReprocessorService.java       # + metrics
        │   ├── RacerRetentionService.java       # @Scheduled XTRIM + DLQ age pruning
        │   └── RacerStreamConsumerService.java  # Consumer group reader for durable streams
        └── controller/
            ├── ConsumerController.java
            ├── DlqController.java               # + /trim + /retention-config
            └── ResponderController.java
```

---

## Prerequisites & Setup

| Requirement | Version |
|-------------|---------|
| Java | 21 (JDK 25 is installed but Lombok is incompatible) |
| Maven | 3.9+ |
| Redis | 7+ |
| Docker | Optional (for Redis via Compose) |

### Start Redis

**Via Docker Compose (recommended):**
```bash
docker compose -f compose.yaml up -d
```

**Via Homebrew:**
```bash
brew install redis
brew services start redis
```

**Verify Redis is up:**
```bash
redis-cli ping
# Expected: PONG
```

---

## Running the Application

Always set `JAVA_HOME` to JDK 21 before running.

### Step 1 — Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean install -DskipTests
```

### Step 2 — Run the Server (Terminal A)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-server -am spring-boot:run
```

Or via jar:
```bash
java -jar racer-server/target/racer-server-0.0.1-SNAPSHOT.jar
```

### Step 3 — Run the Client (Terminal B)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-client -am spring-boot:run
```

Or via jar:
```bash
java -jar racer-client/target/racer-client-0.0.1-SNAPSHOT.jar
```

---

## Configuration Reference

### racer-server (`racer-server/src/main/resources/application.properties`)

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` | HTTP port |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `racer.default-channel` | `racer:messages` | Fallback channel used when no alias is given |
| `racer.channels.<alias>.name` | — | Redis channel name for this alias |
| `racer.channels.<alias>.async` | `true` | Default async flag for this channel |
| `racer.channels.<alias>.sender` | `racer` | Default sender label for this channel |
| `racer.pipeline.enabled` | `false` | Enable pipelined batch publishing (R-9) |
| `racer.pipeline.max-batch-size` | `100` | Maximum messages per pipelined batch (R-9) |
| `racer.priority.enabled` | `false` | Enable priority sub-channel publishing (R-10) |
| `racer.priority.levels` | `HIGH,NORMAL,LOW` | Comma-separated priority level names, highest first (R-10) |
| `racer.priority.strategy` | `strict` | Drain strategy: `strict` or `weighted` (R-10) |
| `racer.priority.channels` | — | Comma-separated channel aliases eligible for priority routing (R-10) |
| `racer.poll.enabled` | `true` | Enable/disable all `@RacerPoll` pollers (R-11) |
| `management.endpoints.web.exposure.include` | `health,info` | Actuator endpoints to expose (add `metrics,prometheus`) |
| `management.metrics.tags.application` | — | Tag all metrics with app name |
| `logging.level.com.cheetah.racer` | `DEBUG` | Log level |

### racer-client (`racer-client/src/main/resources/application.properties`)

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8081` | HTTP port |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `racer.client.processing-mode` | `ASYNC` | Initial processing mode (`SYNC` or `ASYNC`) |
| `racer.default-channel` | `racer:messages` | Fallback channel |
| `racer.channels.<alias>.name` | — | Redis channel name for this alias |
| `racer.channels.<alias>.async` | `true` | Default async flag |
| `racer.channels.<alias>.sender` | `racer` | Default sender label |
| `racer.durable.stream-keys` | — | Comma-separated stream keys to consume with consumer groups |
| `racer.retention.stream-max-len` | `10000` | Max entries to keep in durable streams (XTRIM) |
| `racer.retention.dlq-max-age-hours` | `72` | DLQ entries older than this are pruned |
| `racer.retention.schedule-cron` | `0 0 * * * *` | Cron for automatic retention runs (hourly by default) |
| `racer.consumer.concurrency` | `1` | Number of concurrent consumer instances per stream (R-8) |
| `racer.consumer.name-prefix` | `consumer` | Prefix for generated consumer names, e.g. `consumer-0` (R-8) |
| `racer.consumer.poll-batch-size` | `1` | XREADGROUP COUNT — entries read per poll (R-8) |
| `racer.consumer.poll-interval-ms` | `200` | Milliseconds between polls when stream is empty (R-8) |
| `racer.sharding.enabled` | `false` | Enable key-based stream sharding (R-8) |
| `racer.sharding.shard-count` | `4` | Number of shard suffixes: `stream:0` … `stream:N-1` (R-8) |
| `racer.sharding.streams` | — | Comma-separated base stream keys to shard (R-8) |
| `racer.priority.enabled` | `false` | Enable priority sub-channel consumer (R-10) |
| `racer.priority.levels` | `HIGH,NORMAL,LOW` | Priority level names (R-10) |
| `racer.priority.strategy` | `strict` | `strict` (drain high first) or `weighted` (R-10) |
| `racer.priority.channels` | — | Comma-separated base Redis channel names to subscribe with priority (R-10) |
| `racer.pubsub.concurrency` | `256` | Max in-flight Pub/Sub messages processed concurrently (R-11) |
| `racer.poll.enabled` | `true` | Enable/disable all `@RacerPoll` pollers (R-11) |
| `management.endpoints.web.exposure.include` | `health,info` | Actuator endpoints to expose (add `metrics,prometheus`) |
| `logging.level.com.cheetah.racer` | `DEBUG` | Log level |

### High-Availability Redis (`application.properties` overrides)

**Sentinel mode:**
```properties
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=localhost:26379,localhost:26380,localhost:26381
# Remove the standalone host/port lines
```

**Cluster mode:**
```properties
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
# Remove the standalone host/port lines
```

---

## Racer Annotations

The annotation module adds a declarative, property-driven publishing layer on top of the reactive Redis infrastructure. Enable it once with `@EnableRacer` and then use field injection or method-level publishing anywhere in your Spring beans.

### `@EnableRacer`

Place on any `@SpringBootApplication` or `@Configuration` class. This single annotation imports `RacerAutoConfiguration` which registers:

| Bean | Purpose |
|------|---------|
| `RacerPublisherRegistry` | Holds one `RacerChannelPublisher` per configured channel alias |
| `PublishResultAspect` | AOP advice that intercepts `@PublishResult` methods |
| `RacerPublisherFieldProcessor` | `BeanPostProcessor` that injects `@RacerPublisher` fields |

```java
@SpringBootApplication
@EnableRacer
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

---

### `@RacerPublisher` — field injection

Annotate any `RacerChannelPublisher` field with the channel alias. The `RacerPublisherFieldProcessor` (a `BeanPostProcessor`) injects the correct publisher before the bean is initialised — **no `@Autowired` needed**.

```java
@Service
public class OrderService {

    @RacerPublisher("orders")         // → racer.channels.orders.name
    private RacerChannelPublisher ordersPublisher;

    @RacerPublisher("notifications")  // → racer.channels.notifications.name
    private RacerChannelPublisher notificationsPublisher;

    @RacerPublisher                   // no alias → racer.default-channel
    private RacerChannelPublisher defaultPublisher;

    public Mono<Void> placeOrder(Order order) {
        return ordersPublisher.publishAsync(order)
                .then(notificationsPublisher.publishAsync("Order placed: " + order.getId()))
                .then();
    }
}
```

**`RacerChannelPublisher` interface**

| Method | Returns | Description |
|--------|---------|-------------|
| `publishAsync(payload)` | `Mono<Long>` | Fire-and-forget; Long = subscriber count |
| `publishAsync(payload, sender)` | `Mono<Long>` | Same, custom sender label |
| `publishSync(payload)` | `Long` | Blocking until Redis confirms |
| `getChannelName()` | `String` | Redis channel name |
| `getChannelAlias()` | `String` | Alias as declared in properties |

---

### `@PublishResult` — method-level auto-publish

Annotate **any Spring-managed method**. The return value is automatically serialised and published to the configured channel as a side-effect. The HTTP caller / calling code receives the original return value unchanged.

```java
// Using a channel alias from properties
@PublishResult(channelRef = "orders", sender = "order-service", async = true)
public Mono<Order> createOrder(OrderRequest req) {
    return orderRepository.save(req.toOrder());
}

// Using a direct Redis channel name
@PublishResult(channel = "racer:audit", async = false)  // blocking for audit
public AuditRecord recordAudit(AuditEvent event) {
    return auditRepository.save(event.toRecord());
}

// Durable publishing — writes to a Redis Stream instead of Pub/Sub
// The client's RacerStreamConsumerService reads from this stream via consumer groups
@PublishResult(durable = true, streamKey = "racer:orders:stream", sender = "order-service")
public Mono<Order> createDurableOrder(OrderRequest req) {
    return orderRepository.save(req.toOrder());
}

// Works with Flux too — every emitted element is published
@PublishResult(channelRef = "notifications")
public Flux<Notification> broadcastAll() {
    return notificationService.getAll();
}
```

**Attribute reference**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `channel` | `String` | `""` | Direct Redis channel name (Pub/Sub). Takes priority over `channelRef`. |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>`. |
| `sender` | `String` | `"racer-publisher"` | Sender label embedded in the message envelope. |
| `async` | `boolean` | `true` | `true` = fire-and-forget; `false` = blocks until Redis confirms. |
| `durable` | `boolean` | `false` | When `true`, publishes to a **Redis Stream** (XADD) instead of Pub/Sub. |
| `streamKey` | `String` | `""` | The Redis Stream key to write to when `durable=true` (e.g. `racer:orders:stream`). |

**Resolution order:** `channel` (direct name) → `channelRef` (alias lookup) → default channel (`racer.default-channel`).

**Supported return types:**

| Return type | Behaviour |
|-------------|----------|
| `Mono<T>` | Taps into the reactive pipeline via `doOnNext` — no blocking |
| `Flux<T>` | Taps every element via `doOnNext` — no blocking |
| Any POJO / `void` | Published synchronously or asynchronously after return |

> **Important:** The annotated method must be on a **Spring proxy** (i.e. invoked from outside the bean). Self-invocation inside the same class bypasses the AOP proxy and `@PublishResult` will not fire.

---

### `@RacerRoute` — content-based routing

Apply `@RacerRoute` to a **`@Component`** (or any Spring bean). At startup `RacerRouterService` scans all beans, compiles the rules, and checks every inbound message against them before dispatching to a processor.

```java
@Component
@RacerRoute({
    @RacerRouteRule(field = "type",   matches = "^ORDER.*",          to = "racer:orders"),
    @RacerRouteRule(field = "type",   matches = "^NOTIFICATION.*",   to = "racer:notifications"),
    @RacerRouteRule(field = "sender", matches = "payment-service",   to = "racer:payments",
                    sender = "router")
})
public class OrderRouter {
    // no methods required — the annotation does all the work
}
```

**`@RacerRouteRule` attributes**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `field` | `String` | `""` | JSON field in the payload to evaluate (e.g. `"type"`, `"sender"`). |
| `matches` | `String` | `""` | Java regex applied to the field value. |
| `to` | `String` | `""` | Target Redis channel to forward the message to when the rule matches. |
| `sender` | `String` | `"racer-router"` | Sender label used when re-publishing to the target channel. |

**Runtime API:**
- `GET /api/router/rules` — list all compiled rules with their index, field, pattern and target.
- `POST /api/router/test` — dry-run: pass a message body and see which rule (if any) matches.

---

### `@RacerPriority` — message priority routing

Annotate a method alongside `@PublishResult` to tag the published message with a priority level. `RacerPriorityPublisher` (active when `racer.priority.enabled=true`) routes the message to the correct priority sub-channel.

**Sub-channel naming:**
```
racer:orders:priority:HIGH
racer:orders:priority:NORMAL
racer:orders:priority:LOW
```

**Usage:**
```java
@PublishResult(channelRef = "orders", sender = "checkout")
@RacerPriority(defaultLevel = "HIGH")
public RacerMessage placeUrgentOrder(OrderRequest req) {
    // If the returned RacerMessage has priority = null/blank,
    // defaultLevel ("HIGH") is used.
    return RacerMessage.create("racer:orders", req.toString(), "checkout", "HIGH");
}
```

**`@RacerPriority` attributes**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `defaultLevel` | `String` | `"NORMAL"` | Priority level used when the message's own `priority` field is blank. Must match one of the names in `racer.priority.levels`. |

**Built-in levels** (`PriorityLevel` enum):

| Level | Weight | Description |
|-------|--------|-------------|
| `HIGH` | 0 | Processed first |
| `NORMAL` | 1 | Default |
| `LOW` | 2 | Processed last |

**Publishing with priority via REST:**
```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"channel":"racer:orders","payload":"urgent-order","sender":"checkout","priority":"HIGH"}'
```

**Consumer side (`racer-client`):**

Enable `racer.priority.enabled=true` and configure `racer.priority.channels`. The `RacerPriorityConsumerService` subscribes to all priority sub-channels, buffers messages in a `PriorityBlockingQueue` ordered by level weight, and drains them in strict priority order.

```properties
# racer-client/application.properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=racer:orders,racer:notifications
```

---

### `@RacerPoll` — scheduled publishing

Annotate a no-arg method in any Spring bean to publish its return value to a Racer channel on a fixed schedule or cron expression. The method handles all data fetching or computation — `@RacerPoll` only deals with the scheduling and the publish destination.

**Fixed-rate example:**
```java
@Component
public class InventoryPoller {

    @RacerPoll(
        fixedRate = 30_000,              // every 30 seconds
        channel   = "racer:inventory",
        sender    = "inventory-poller"
    )
    public String fetchInventory() {
        // Your code fetches the data however you like
        return restClient.get("https://api.example.com/inventory");
    }
}
```

**Cron-based example with reactive return type:**
```java
@RacerPoll(
    cron       = "0 0/5 * * * *",       // every 5 minutes
    channelRef = "pricing",
    sender     = "price-poller"
)
public Mono<String> fetchPrices() {
    return webClient.get()
            .uri("https://api.example.com/prices")
            .retrieve()
            .bodyToMono(String.class);
}
```

**`@RacerPoll` attributes**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `fixedRate` | `long` | `10000` | Polling interval in milliseconds (ignored when `cron` is set) |
| `initialDelay` | `long` | `0` | Delay before first poll (ms) |
| `cron` | `String` | `""` | Spring cron expression (overrides `fixedRate` when non-empty) |
| `channel` | `String` | `""` | Direct Redis channel name to publish to |
| `channelRef` | `String` | `""` | Channel alias from `racer.channels.<alias>` |
| `sender` | `String` | `"racer-poller"` | Sender label on published messages |
| `async` | `boolean` | `true` | Whether to publish asynchronously |

**Supported return types:** `String` (as-is), any serializable object (JSON-encoded), `Mono<?>` (subscribed to), `void`/`null` (nothing published).

---

### Multi-channel configuration

Declare as many channel aliases as needed in `application.properties`. Each alias becomes a dedicated `RacerChannelPublisher` registered at startup.

```properties
# Default channel (used when no alias is specified)
racer.default-channel=racer:messages

# Orders channel — async, labelled with service name
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

# Notifications channel — async
racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=true
racer.channels.notifications.sender=notification-service

# Audit channel — blocking to guarantee delivery
racer.channels.audit.name=racer:audit
racer.channels.audit.async=false
racer.channels.audit.sender=audit-service
```

A log line is printed for each registered channel at startup:
```
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
[racer] Channel 'audit'         registered → 'racer:audit'
```

All registered channels (and their Redis names) are also queryable at runtime via `GET /api/channels`.

**Published message envelope**

Every `RacerChannelPublisher` wraps the payload in a lightweight JSON envelope before publishing:

```json
{
  "channel": "racer:orders",
  "sender":  "order-service",
  "payload": { ...your object... }
}
```

---

## Redis Keys & Channels Reference

| Redis Key / Channel | Type | Description |
|---------------------|------|-------------|
| `racer:messages` | Pub/Sub channel | Primary fire-and-forget + request-reply channel |
| `racer:notifications` | Pub/Sub channel | Broadcast-only notification channel |
| `racer:reply:<correlationId>` | Pub/Sub channel | Ephemeral per-request reply channel (auto-cleaned) |
| `racer:<alias>` | Pub/Sub channel | Dynamic channels created via `@RacerRoute` targets |
| `racer:<channel>:priority:<LEVEL>` | Pub/Sub channel | Priority sub-channels (R-10) — e.g. `racer:orders:priority:HIGH` |
| `racer:dlq` | List | Dead Letter Queue (LIFO push, FIFO pop) |
| `racer:stream:requests` | Stream | Request stream for streams-based request-reply |
| `racer:stream:response:<correlationId>` | Stream | Per-request response stream (auto-deleted after read) |
| `racer:<name>:stream` | Stream | **Durable stream** written by `@PublishResult(durable=true)` |
| `racer:<name>:stream:<n>` | Stream | **Sharded durable stream** shard `n` (R-8) — e.g. `racer:orders:stream:0` |

Consumer group on `racer:stream:requests`: **`racer-client-group`**  
Consumer group on durable streams: **`racer-durable-consumers`** (one per stream key in `racer.durable.stream-keys`)  
Consumer names within group: **`<namePrefix>-<index>`** e.g. `consumer-0`, `consumer-1` (configurable via `racer.consumer.*`)

---

## Message Schemas

### RacerMessage (fire-and-forget)

```json
{
  "id":         "uuid-auto-generated",
  "channel":    "racer:messages",
  "payload":    "your message content",
  "sender":     "racer-server",
  "timestamp":  "2026-03-01T10:00:00Z",
  "retryCount": 0,
  "priority":   "NORMAL"
}
```

> **`priority` field (R-10):** Optional. Accepted values: `HIGH`, `NORMAL`, `LOW` (or any custom level declared in `racer.priority.levels`). Defaults to `NORMAL` when absent. Used by `RacerPriorityPublisher` to route to the correct sub-channel.

### RacerRequest (request-reply)

```json
{
  "correlationId": "uuid-auto-generated",
  "channel":       "racer:messages",
  "payload":       "your request content",
  "sender":        "racer-server",
  "timestamp":     "2026-03-01T10:00:00Z",
  "replyTo":       "racer:reply:<correlationId>"
}
```

> For streams-based request-reply, `replyTo` is `racer:stream:response:<correlationId>`.

### RacerReply

```json
{
  "correlationId": "same-as-request",
  "payload":       "Processed: your request [echoed by racer-client]",
  "responder":     "racer-client",
  "success":       true,
  "errorMessage":  null,
  "timestamp":     "2026-03-01T10:00:01Z"
}
```

### DeadLetterMessage

```json
{
  "id":              "same-as-original-message-id",
  "originalMessage": { ...RacerMessage... },
  "errorMessage":    "Simulated processing failure",
  "exceptionClass":  "java.lang.RuntimeException",
  "failedAt":        "2026-03-01T10:00:02Z",
  "attemptCount":    1
}
```

---

## API Reference — Server (port 8080)

### Publish APIs

Base path: `/api/publish`

---

#### `POST /api/publish/async`

Publish a single message **non-blocking**. Returns immediately after enqueuing to Redis; the reactive chain completes in the background.

When `racer.priority.enabled=true` and a `priority` field is provided, the message is routed to the appropriate priority sub-channel via `RacerPriorityPublisher`.

**Request Body**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `payload` | string | Yes | — | The message content |
| `sender` | string | No | `racer-server` | Identifies who sent the message |
| `channel` | string | No | `racer:messages` | Target Pub/Sub channel |
| `priority` | string | No | — | Priority level: `HIGH`, `NORMAL`, or `LOW` (R-10, requires `racer.priority.enabled=true`) |

```json
{
  "payload":  "Urgent order",
  "sender":   "checkout",
  "channel":  "racer:orders",
  "priority": "HIGH"
}
```

**Response `200 OK`** (standard)

```json
{
  "status":      "published",
  "mode":        "async",
  "channel":     "racer:messages",
  "subscribers": 1
}
```

**Response `200 OK`** (with priority)

```json
{
  "status":      "published",
  "mode":        "async-priority",
  "channel":     "racer:orders",
  "priority":    "HIGH",
  "subscribers": 1
}
```

**curl example:**
```bash
# Standard
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"Hello async world","sender":"me"}'

# With priority (requires racer.priority.enabled=true)
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"channel":"racer:orders","payload":"urgent","sender":"checkout","priority":"HIGH"}'
```

---

#### `POST /api/publish/sync`

Publish a single message **blocking** — waits for Redis to confirm the publish before returning.

**Request Body** — same fields as `/async`

**Response `200 OK`**

```json
{
  "status":      "published",
  "mode":        "sync",
  "channel":     "racer:messages",
  "subscribers": 1
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/publish/sync \
  -H "Content-Type: application/json" \
  -d '{"payload":"Hello sync world","sender":"me"}'
```

---

#### `POST /api/publish/batch`

Publish **multiple messages** in one call. Each message is published asynchronously and all run in parallel.

**Request Body**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `payloads` | string[] | Yes | — | Array of message strings |
| `sender` | string | No | `racer-server` | Sender identifier |
| `channel` | string | No | `racer:messages` | Target channel |

```json
{
  "payloads": ["message one", "message two", "message three"],
  "sender":   "batch-producer",
  "channel":  "racer:messages"
}
```

**Response `200 OK`**

```json
{
  "status":       "published",
  "mode":         "async-batch",
  "channel":      "racer:messages",
  "messageCount": 3
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/publish/batch \
  -H "Content-Type: application/json" \
  -d '{"payloads":["msg1","msg2","msg3"],"sender":"batcher"}'
```

---

#### `POST /api/publish/batch-atomic`

Publish **multiple messages to different channels** as an **ordered, atomic sequence** using `RacerTransaction`. All messages are dispatched in the exact order provided via `Flux.concat` — no parallelism. 

**Request Body**

Array of publish items:

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `alias` | string | Yes | — | Channel alias from `racer.channels.<alias>` |
| `payload` | string | Yes | — | Message content |
| `sender` | string | No | `racer-tx` | Sender label |

```json
[
  { "alias": "orders",        "payload": "Order #100",  "sender": "checkout" },
  { "alias": "audit",         "payload": "Audit #100",  "sender": "checkout" },
  { "alias": "notifications", "payload": "Notify #100", "sender": "checkout" }
]
```

**Response `200 OK`**

```json
{
  "status":       "published",
  "mode":         "atomic-batch",
  "messageCount": 3,
  "subscriberCounts": [1, 1, 1]
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/publish/batch-atomic \
  -H "Content-Type: application/json" \
  -d '[
    {"alias":"orders","payload":"Order #1","sender":"checkout"},
    {"alias":"audit","payload":"Audit #1","sender":"checkout"}
  ]'
```

---

#### `POST /api/publish/batch-pipelined`

Publish **multiple payloads to a single channel** using parallel reactive merging (R-9 — Throughput Optimisation). All `PUBLISH` commands are issued concurrently; Lettuce (the reactive Redis driver) automatically pipelines them over one connection, reducing N round-trips to ~1.

Use this instead of `/api/publish/batch` when throughput matters more than per-message error isolation.

**Request Body** — same shape as `/api/publish/batch`

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `payloads` | string[] | Yes | — | Array of message strings |
| `sender` | string | No | `racer-server` | Sender identifier |
| `channel` | string | No | `racer:messages` | Target channel |

```json
{
  "payloads": ["order-1", "order-2", "order-3"],
  "sender":   "batch-producer",
  "channel":  "racer:orders"
}
```

**Response `200 OK`**

```json
{
  "status":           "published",
  "mode":             "pipelined-batch",
  "channel":          "racer:orders",
  "messageCount":     3,
  "subscriberCounts": [1, 1, 1]
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/publish/batch-pipelined \
  -H "Content-Type: application/json" \
  -d '{"payloads":["msg1","msg2","msg3"],"sender":"bench","channel":"racer:orders"}'
```

---

#### `POST /api/publish/batch-atomic-pipelined`

Same as `/api/publish/batch-atomic` (multi-channel, alias-based) but executed in **parallel pipelined mode** instead of sequential `Flux.concat` (R-9). Use when you need multi-channel batches with maximum throughput and can tolerate non-deterministic ordering.

**Request Body** — same as `/api/publish/batch-atomic`

**Response `200 OK`**

```json
{
  "status":           "published",
  "mode":             "atomic-batch-pipelined",
  "messageCount":     3,
  "subscriberCounts": [1, 1, 1]
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/publish/batch-atomic-pipelined \
  -H "Content-Type: application/json" \
  -d '[
    {"alias":"orders","payload":"Order #1","sender":"checkout"},
    {"alias":"audit","payload":"Audit #1","sender":"checkout"}
  ]'
```

---

### Router APIs

Base path: `/api/router`

Content-based routing is configured via `@RacerRoute` annotations (see [Racer Annotations](#racerroute--content-based-routing)). These endpoints let you inspect and test the compiled rules at runtime.

---

#### `GET /api/router/rules`

Returns all compiled routing rules registered from `@RacerRoute` beans.

**Response `200 OK`**

```json
[
  { "index": 0, "field": "type",   "pattern": "^ORDER.*",        "to": "racer:orders" },
  { "index": 1, "field": "type",   "pattern": "^NOTIFICATION.*", "to": "racer:notifications" },
  { "index": 2, "field": "sender", "pattern": "payment-service", "to": "racer:payments" }
]
```

**curl example:**
```bash
curl http://localhost:8080/api/router/rules
```

---

#### `POST /api/router/test`

Dry-run a message through the router without actually publishing it. Returns which rule (if any) would match.

**Request Body** — any JSON object (simulates the message payload)

```json
{ "type": "ORDER_CREATED", "id": "123", "amount": 99.99 }
```

**Response `200 OK`** (match found):

```json
{
  "matched": true,
  "ruleIndex": 0,
  "field":    "type",
  "pattern":  "^ORDER.*",
  "to":       "racer:orders"
}
```

**Response `200 OK`** (no match):

```json
{ "matched": false }
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/router/test \
  -H "Content-Type: application/json" \
  -d '{"type":"ORDER_CREATED","id":"42"}'
```

---

### Channel Registry APIs

Base path: `/api/channels`

These endpoints demonstrate the annotation-driven publishing infrastructure and expose the live channel registry.

---

#### `GET /api/channels`

Lists every channel alias registered in the `RacerPublisherRegistry`.

**Response `200 OK`**

```json
{
  "__default__":   { "channel": "racer:messages" },
  "orders":        { "channel": "racer:orders" },
  "notifications": { "channel": "racer:notifications" },
  "audit":         { "channel": "racer:audit" }
}
```

**curl example:**
```bash
curl http://localhost:8080/api/channels
```

---

#### `POST /api/channels/publish/{alias}`

Publish an arbitrary JSON body to the channel registered under `{alias}` using the injected `RacerChannelPublisher`.

**Path Parameter**

| Param | Description |
|-------|-------------|
| `alias` | Channel alias as declared in `racer.channels.<alias>`. Use `__default__` for the default channel. |

**Request Body** — any valid JSON object

```json
{ "orderId": "123", "item": "Widget", "qty": 5 }
```

**Response `200 OK`**

```json
{
  "published":   true,
  "alias":       "orders",
  "channel":     "racer:orders",
  "subscribers": 1
}
```

**curl examples:**
```bash
# Publish to the orders channel
curl -s -X POST http://localhost:8080/api/channels/publish/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"42","item":"Widget"}'

# Publish to notifications
curl -s -X POST http://localhost:8080/api/channels/publish/notifications \
  -H "Content-Type: application/json" \
  -d '{"message":"System maintenance at 03:00"}'

# Publish to default channel
curl -s -X POST http://localhost:8080/api/channels/publish/__default__ \
  -H "Content-Type: application/json" \
  -d '{"payload":"hello default"}'
```

---

#### `POST /api/channels/publish-annotated`

Live demonstration of `@PublishResult`. The method `buildOrderEvent()` is annotated with `@PublishResult(channelRef = "orders")` — its return value is **automatically published to `racer:orders`** as a side-effect. The HTTP caller receives the same object.

**Request Body** — any JSON object

```json
{ "item": "Gadget", "qty": 5 }
```

**Response `200 OK`** (same object, also published to `racer:orders`)

```json
{
  "item":        "Gadget",
  "qty":         5,
  "eventType":   "ORDER_CREATED",
  "processedAt": "2026-03-01T12:00:00Z",
  "source":      "racer-server"
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/channels/publish-annotated \
  -H "Content-Type: application/json" \
  -d '{"item":"Gadget","qty":5}'
```

---

#### `POST /api/channels/demo/orders`

Publishes body directly to `racer:orders` using the `@RacerPublisher("orders")` injected field.

```bash
curl -s -X POST http://localhost:8080/api/channels/demo/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"99","status":"CONFIRMED"}'
```

**Response `200 OK`**
```json
{ "channel": "racer:orders", "subscribers": 1 }
```

---

#### `POST /api/channels/demo/notifications`

Publishes body directly to `racer:notifications` using the `@RacerPublisher("notifications")` injected field.

```bash
curl -s -X POST http://localhost:8080/api/channels/demo/notifications \
  -H "Content-Type: application/json" \
  -d '{"message":"Order #99 shipped"}'
```

**Response `200 OK`**
```json
{ "channel": "racer:notifications", "subscribers": 1 }
```

---

### Request-Reply APIs

Base path: `/api/request`

The server sends a request and **waits synchronously** for the client to process it and send back a reply. If the client doesn't reply within the timeout, a `504` is returned.

---

#### `POST /api/request/pubsub`

Two-way request-reply over **Redis Pub/Sub**.

**How it works:**
1. Server creates a `RacerRequest` with a `correlationId`.
2. Sets `replyTo` = `racer:reply:<correlationId>` (ephemeral channel).
3. Subscribes to that reply channel.
4. Publishes the request to `racer:messages`.
5. Client receives it, processes it, publishes `RacerReply` back to `replyTo`.
6. Server receives the reply and returns the HTTP response.

**Request Body**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `payload` | string | Yes | — | The request content |
| `sender` | string | No | `racer-server` | Sender identifier |
| `timeoutSeconds` | int | No | `30` | How long to wait for a reply |

```json
{
  "payload":        "What is the status?",
  "sender":         "server",
  "timeoutSeconds": 15
}
```

**Response `200 OK` (success)**

```json
{
  "transport":    "pubsub",
  "correlationId":"550e8400-e29b-41d4-a716-446655440000",
  "success":      true,
  "reply":        "Processed: What is the status? [echoed by racer-client]",
  "responder":    "racer-client",
  "errorMessage": ""
}
```

**Response `504 Gateway Timeout` (no reply in time)**

```json
{
  "transport": "pubsub",
  "error":     "Did not observe any item or terminal signal within 30000ms"
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/request/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"ping","sender":"me","timeoutSeconds":10}'
```

**Trigger a failure reply** (payload containing "error"):
```bash
curl -s -X POST http://localhost:8080/api/request/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"this should error","sender":"me"}'
```

---

#### `POST /api/request/stream`

Two-way request-reply over **Redis Streams**.

**How it works:**
1. Server writes a request entry to stream `racer:stream:requests` with fields: `correlationId`, `replyTo`, `payload`.
2. Client (consumer group `racer-client-group`) reads the entry, processes it.
3. Client writes a reply entry to stream `racer:stream:response:<correlationId>`.
4. Server polls the response stream (every 200ms) until the reply appears or timeout expires.
5. Response stream is auto-deleted after reading.

**Request Body** — same fields as `/pubsub`

**Response `200 OK` (success)**

```json
{
  "transport":    "stream",
  "correlationId":"550e8400-e29b-41d4-a716-446655440000",
  "success":      true,
  "reply":        "Stream-processed: ping [by racer-client-stream]",
  "responder":    "racer-client-stream",
  "errorMessage": ""
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8080/api/request/stream \
  -H "Content-Type: application/json" \
  -d '{"payload":"ping via stream","sender":"me"}'
```

---

## API Reference — Client (port 8081)

### Consumer APIs

Base path: `/api/consumer`

---

#### `GET /api/consumer/status`

Returns the current state of the message consumer.

**Response `200 OK`**

```json
{
  "mode":           "ASYNC",
  "processedCount": 42,
  "failedCount":    3
}
```

| Field | Description |
|-------|-------------|
| `mode` | Current processing mode: `ASYNC` or `SYNC` |
| `processedCount` | Total messages successfully processed since startup |
| `failedCount` | Total messages that failed and were sent to the DLQ |

**curl example:**
```bash
curl http://localhost:8081/api/consumer/status
```

---

#### `PUT /api/consumer/mode?mode=SYNC`

Switch the consumer's processing mode at **runtime** without restarting.

| Mode | Behaviour |
|------|-----------|
| `ASYNC` | Non-blocking; uses reactive scheduler; concurrent processing |
| `SYNC` | Blocking (on `boundedElastic` scheduler); one at a time |

**Query Parameter**

| Param | Values | Description |
|-------|--------|-------------|
| `mode` | `SYNC`, `ASYNC` | Target mode (case-insensitive) |

**Response `200 OK`**

```json
{
  "status": "switched",
  "mode":   "SYNC"
}
```

**Response `400 Bad Request`** (invalid value)

```json
{
  "error": "Invalid mode: FOO. Must be SYNC or ASYNC."
}
```

**curl examples:**
```bash
# Switch to SYNC
curl -s -X PUT "http://localhost:8081/api/consumer/mode?mode=SYNC"

# Switch back to ASYNC
curl -s -X PUT "http://localhost:8081/api/consumer/mode?mode=ASYNC"
```

---

### DLQ APIs

Base path: `/api/dlq`

Messages that throw an exception during processing are automatically moved to the Dead Letter Queue (a Redis List, key: `racer:dlq`). The DLQ supports inspection, reprocessing, republishing, and clearing.

---

#### `GET /api/dlq/messages`

List all messages currently in the DLQ without removing them.

**Response `200 OK`** — JSON array of `DeadLetterMessage`

```json
[
  {
    "id": "550e8400-...",
    "originalMessage": {
      "id":         "550e8400-...",
      "channel":    "racer:messages",
      "payload":    "this will cause error",
      "sender":     "me",
      "timestamp":  "2026-03-01T10:00:00Z",
      "retryCount": 1
    },
    "errorMessage":  "Simulated processing failure for message: 550e8400-...",
    "exceptionClass": "java.lang.RuntimeException",
    "failedAt":       "2026-03-01T10:00:01Z",
    "attemptCount":   1
  }
]
```

**curl example:**
```bash
curl http://localhost:8081/api/dlq/messages
```

---

#### `GET /api/dlq/size`

Returns the number of messages currently in the DLQ.

**Response `200 OK`**

```json
{
  "dlqSize": 5
}
```

**curl example:**
```bash
curl http://localhost:8081/api/dlq/size
```

---

#### `GET /api/dlq/stats`

Returns combined DLQ size and reprocessing statistics.

**Response `200 OK`**

```json
{
  "queueSize":       3,
  "totalReprocessed": 7,
  "permanentlyFailed": 1
}
```

**curl example:**
```bash
curl http://localhost:8081/api/dlq/stats
```

---

#### `POST /api/dlq/reprocess/one?mode=ASYNC`

**Dequeue and directly reprocess** a single message from the DLQ.

- The message is popped from the DLQ.
- Processed by the active processor (SYNC or ASYNC).
- If successful: counted in `totalReprocessed`.
- If it fails again **and** `retryCount < MAX_RETRY_ATTEMPTS (3)**: re-enqueued.
- If `retryCount >= 3`: permanently discarded (logged), counted in `permanentlyFailed`.

**Query Parameter**

| Param | Values | Default | Description |
|-------|--------|---------|-------------|
| `mode` | `SYNC`, `ASYNC` | `ASYNC` | Which processor to use |

**Response `200 OK`**

```json
{
  "reprocessed":      true,
  "mode":             "ASYNC",
  "totalReprocessed": 1,
  "permanentlyFailed": 0
}
```

Returns `reprocessed: false` if the queue was empty.

**curl example:**
```bash
curl -s -X POST "http://localhost:8081/api/dlq/reprocess/one?mode=ASYNC"
```

---

#### `POST /api/dlq/reprocess/all?mode=ASYNC`

Reprocess **all messages** currently in the DLQ one by one.

**Query Parameter** — same as `/reprocess/one`

**Response `200 OK`**

```json
{
  "reprocessedCount": 5,
  "mode":             "ASYNC",
  "totalReprocessed": 12,
  "permanentlyFailed": 1
}
```

**curl example:**
```bash
curl -s -X POST "http://localhost:8081/api/dlq/reprocess/all?mode=SYNC"
```

---

#### `POST /api/dlq/republish/one`

**Republish** a single DLQ message back to its original Pub/Sub channel instead of processing it directly. This lets it flow through the normal pipeline (subscriber → processor) again.

- Increments `retryCount` on the original message.
- If `retryCount > MAX_RETRY_ATTEMPTS`: message is discarded.

**Response `200 OK`**

```json
{
  "republished": true,
  "subscribers": 1
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8081/api/dlq/republish/one
```

---

#### `DELETE /api/dlq/clear`

Remove **all messages** from the DLQ permanently. Use with caution.

**Response `200 OK`**

```json
{
  "cleared": true
}
```

**curl example:**
```bash
curl -s -X DELETE http://localhost:8081/api/dlq/clear
```

---

#### `POST /api/dlq/trim`

Trigger an **immediate on-demand retention run**: trims all configured durable streams to `racer.retention.stream-max-len` entries, and prunes DLQ entries older than `racer.retention.dlq-max-age-hours`.

**Response `200 OK`**

```json
{
  "status": "trimmed",
  "timestamp": "2026-03-01T10:00:00Z"
}
```

**curl example:**
```bash
curl -s -X POST http://localhost:8081/api/dlq/trim
```

---

#### `GET /api/dlq/retention-config`

Returns the current retention configuration being applied by `RacerRetentionService`.

**Response `200 OK`**

```json
{
  "streamMaxLen":     10000,
  "dlqMaxAgeHours":   72,
  "scheduleCron":     "0 0 * * * *"
}
```

**curl example:**
```bash
curl http://localhost:8081/api/dlq/retention-config
```

---

### Responder Status API

Base path: `/api/responder`

---

#### `GET /api/responder/status`

Returns how many request-reply interactions the client has handled since startup.

**Response `200 OK`**

```json
{
  "pubsub": {
    "repliesSent": 12
  },
  "stream": {
    "requestsProcessed": 8
  }
}
```

**curl example:**
```bash
curl http://localhost:8081/api/responder/status
```

---

## Observability & Metrics

Racer integrates with **Micrometer** via `RacerMetrics` (auto-configured when `micrometer-core` is on the classpath). Both `racer-server` and `racer-client` include `spring-boot-starter-actuator` and `micrometer-registry-prometheus`.

### Actuator endpoints

| Endpoint | Port | Description |
|----------|------|-------------|
| `GET /actuator/health` | 8080 / 8081 | Liveness check |
| `GET /actuator/info` | 8080 / 8081 | Build info |
| `GET /actuator/metrics` | 8080 / 8081 | All registered metric names |
| `GET /actuator/metrics/{name}` | 8080 / 8081 | Detail for one metric |
| `GET /actuator/prometheus` | 8080 / 8081 | Prometheus-format scrape endpoint |

Enable all relevant endpoints in `application.properties`:
```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.metrics.tags.application=${spring.application.name}
```

### Racer metrics

| Metric name | Type | Description |
|-------------|------|-------------|
| `racer.published` | Counter | Messages successfully published to Pub/Sub |
| `racer.published.stream` | Counter | Messages published to durable streams (XADD) |
| `racer.consumed` | Counter | Messages successfully processed by a consumer |
| `racer.failed` | Counter | Messages that threw an exception during processing |
| `racer.dlq.reprocessed` | Counter | DLQ messages successfully reprocessed |
| `racer.dlq.size` | Gauge | Current number of entries in `racer:dlq` |
| `racer.requestreply.latency` | Timer | Round-trip latency for request-reply operations |

All metrics include a `transport` tag (`pubsub` or `stream`) and an `application` tag set by `management.metrics.tags.application`.

### Checking metrics with curl

```bash
# List all metric names
curl http://localhost:8080/actuator/metrics | jq '.names[]' | grep racer

# Get detail for one metric
curl "http://localhost:8080/actuator/metrics/racer.published"

# Prometheus scrape (for Grafana / Prometheus integration)
curl http://localhost:8080/actuator/prometheus | grep racer
```

### Grafana quick-start

1. Add `http://localhost:8080/actuator/prometheus` as a Prometheus scrape target.
2. Import a generic Spring Boot Micrometer dashboard (e.g. Grafana dashboard ID **4701**).
3. Filter panels by `application="racer-server"` or `application="racer-client"`.

---

## High Availability

Racer ships two Docker Compose files for production-grade Redis deployments.

### Sentinel mode (recommended for most teams)

Provides automatic failover with one primary, one replica and three Sentinel nodes.

```bash
docker compose -f compose.sentinel.yaml up -d
```

Configure both applications to use Sentinel instead of a single host:
```properties
# Remove spring.data.redis.host / port lines and add:
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=localhost:26379,localhost:26380,localhost:26381
```

**Testing failover:**
```bash
# Stop the primary — Sentinel elects the replica as new primary within ~5 s
docker stop racer-redis-primary
# Watch Sentinel logs
docker logs -f racer-sentinel-1
# Restart survived services
docker start racer-redis-primary
```

### Cluster mode (horizontal scale-out)

A 6-node Redis Cluster (3 primaries + 3 replicas) with an auto-init container.

```bash
docker compose -f compose.cluster.yaml up -d
```

Configure cluster mode:
```properties
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
```

### Choosing a mode

| | Standalone (`compose.yaml`) | Sentinel | Cluster |
|---|---|---|---|
| **Failover** | ❌ | ✅ auto | ✅ auto |
| **Horizontal scale** | ❌ | ❌ | ✅ |
| **Complexity** | Low | Medium | High |
| **Pub/Sub** | ✅ | ✅ | ✅ (primary only) |
| **Streams** | ✅ | ✅ | ✅ |
| **Recommended for** | Dev / testing | Production (most teams) | Very large data sets |

> See [Tutorial 15](TUTORIALS.md#tutorial-15--high-availability-sentinel--cluster) for a full walkthrough.

---

## Consumer Scaling & Sharding

> **R-8 — Consumer Scaling & Horizontal Sharding**

By default a single consumer (`consumer-0`) reads from each stream. For high-throughput workloads you can:

1. **Increase concurrency** — spawn N named consumers inside one process, each issuing an independent `XREADGROUP COUNT <batchSize>` loop.
2. **Enable key-based sharding** — publish to `racer:<stream>:stream:<n>` shards using CRC-16/CCITT routing (`RacerShardedStreamPublisher`).

### Concurrency configuration

```properties
# racer-client/application.properties
racer.consumer.concurrency=4           # spawn consumer-0 … consumer-3
racer.consumer.name-prefix=worker      # worker-0 … worker-3
racer.consumer.poll-batch-size=10      # read 10 entries per XREADGROUP call
racer.consumer.poll-interval-ms=100    # poll every 100 ms when idle
```

### Sharding configuration

```properties
# racer-server/application.properties — publisher side
racer.sharding.enabled=true
racer.sharding.shard-count=4
racer.sharding.streams=racer:orders:stream,racer:events:stream
```

Publishing with a shard key:
```java
@Autowired RacerShardedStreamPublisher shardedPublisher;

shardedPublisher.publishToShard("racer:orders:stream", payload, sender, orderId)
    .subscribe();
// Routes to racer:orders:stream:0 … :3 based on CRC-16(orderId) % 4
```

**Consumer side** — add the concrete shard keys to `racer.durable.stream-keys`:
```properties
racer.durable.stream-keys=racer:orders:stream:0,racer:orders:stream:1,racer:orders:stream:2,racer:orders:stream:3
```

> See [Tutorial 16](TUTORIALS.md#tutorial-16--consumer-scaling--stream-sharding) for a full walkthrough.

---

## Pipelined Publishing

> **R-9 — Throughput Optimisation / Pipelining**

`RacerPipelinedPublisher` issues all `PUBLISH` commands in a batch concurrently via `Flux.flatMap(concurrency = N)`. Lettuce (the reactive Redis driver) automatically pipelines these commands over a single connection, collapsing N round-trips into approximately 1, which significantly increases throughput for bulk workloads.

### Comparison

| Endpoint | Execution model | Use when |
|----------|-----------------|----------|
| `POST /api/publish/batch` | Sequential `Flux.concat` | Order matters, low volume |
| `POST /api/publish/batch-pipelined` | Parallel `Flux.flatMap` (pipelined) | High throughput, single channel |
| `POST /api/publish/batch-atomic` | Sequential, multi-channel | Ordered cross-channel fanout |
| `POST /api/publish/batch-atomic-pipelined` | Parallel, multi-channel | Cross-channel, max throughput |

### Usage from Java

```java
@Autowired RacerPipelinedPublisher pipelinedPublisher;

List<String> payloads = IntStream.range(0, 1000)
    .mapToObj(i -> "event-" + i)
    .toList();

pipelinedPublisher.publishBatch("racer:orders", payloads, "producer")
    .doOnNext(counts -> log.info("Sent {} messages", counts.size()))
    .subscribe();
```

Cross-channel pipelined batch:
```java
var items = List.of(
    new RacerPipelinedPublisher.PipelineItem("racer:orders",  "order-1",   "checkout"),
    new RacerPipelinedPublisher.PipelineItem("racer:audit",   "audit-1",   "checkout"),
    new RacerPipelinedPublisher.PipelineItem("racer:metrics", "metric-1",  "checkout")
);
pipelinedPublisher.publishItems(items).subscribe();
```

### Using pipelined mode in `RacerTransaction`

```java
transaction.execute(tx -> {
    tx.publish("orders",  "order-1");
    tx.publish("audit",   "audit-1");
}, /* pipelined = */ true);  // all PUBLISH calls go through RacerPipelinedPublisher
```

> See [Tutorial 17](TUTORIALS.md#tutorial-17--pipelined-batch-publishing) for a full walkthrough.

---

## Message Priority

> **R-10 — Message Priority Queuing**

RacerMQ implements priority via separate Pub/Sub **sub-channels**, one per priority level. Publishers route messages to `{channel}:priority:{LEVEL}` and consumers drain messages from highest-priority channels first.

### Priority levels (`PriorityLevel` enum)

| Level | Weight | Sub-channel suffix |
|-------|--------|-------------------|
| `HIGH` | 0 | `:priority:HIGH` |
| `NORMAL` | 1 | `:priority:NORMAL` |
| `LOW` | 2 | `:priority:LOW` |

Lower weight = higher priority. Custom levels can be declared in `racer.priority.levels`.

### Server-side publishing

```properties
# racer-server/application.properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.channels=orders,notifications
```

```bash
curl -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"channel":"racer:orders","payload":"urgent order","sender":"checkout","priority":"HIGH"}'
```

This publishes to `racer:orders:priority:HIGH`.

### Client-side consumption

```properties
# racer-client/application.properties
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict
racer.priority.channels=racer:orders,racer:notifications
```

`RacerPriorityConsumerService` (auto-configured when `racer.priority.enabled=true`) subscribes to all priority sub-channels, buffers incoming messages in a `PriorityBlockingQueue`, and drains them in strict weight order:
1. All `HIGH` messages are processed before any `NORMAL` messages.
2. All `NORMAL` messages are processed before any `LOW` messages.

### `@RacerPriority` annotation

```java
@PublishResult(channelRef = "orders")
@RacerPriority(defaultLevel = "HIGH")
public RacerMessage submitOrder(OrderRequest req) {
    return RacerMessage.create("racer:orders", toJson(req), "checkout", "HIGH");
}
```

If the returned `RacerMessage.priority` is blank/null, `defaultLevel` from `@RacerPriority` is used as the fallback.

> See [Tutorial 18](TUTORIALS.md#tutorial-18--message-priority-channels) for a full walkthrough.

---

## End-to-End Flows

### Flow 1 — Fire-and-Forget (Pub/Sub async)

```
Client      POST /api/publish/async
Server      → RacerMessage created (id, timestamp auto-set)
            → Published to racer:messages
Client      ConsumerSubscriber receives message
            → Routed to AsyncMessageProcessor (default)
            → If success: processedCount++
            → If throws: DLQ.enqueue(message, error)
```

### Flow 2 — Request-Reply via Pub/Sub

```
Client      POST /api/request/pubsub {"payload":"ping"}
Server      → Generates correlationId = "abc123"
            → Subscribes to racer:reply:abc123
            → Publishes RacerRequest to racer:messages
Client      PubSubResponderService receives message
            → Detects replyTo is set → treats as request
            → Processes request (business logic)
            → Publishes RacerReply to racer:reply:abc123
Server      → Receives reply on racer:reply:abc123
            → HTTP 200 with reply payload returned
```

### Flow 3 — Request-Reply via Streams

```
Client      POST /api/request/stream {"payload":"ping"}
Server      → Generates correlationId = "xyz789"
            → Writes entry to racer:stream:requests
            → Polls racer:stream:response:xyz789 every 200ms
Client      StreamResponderService reads from racer:stream:requests
              (consumer group: racer-client-group, consumer: client-1)
            → Processes request
            → Writes RacerReply to racer:stream:response:xyz789
            → ACKs the stream entry
Server      → Detects reply in response stream
            → Deletes racer:stream:response:xyz789
            → HTTP 200 with reply payload returned
```

### Flow 4 — DLQ and Reprocessing

```
Message arrives → AsyncMessageProcessor.process() throws RuntimeException
                → failedCount++
                → DLQ.enqueue(message, error)
                → JSON written to racer:dlq (Redis List, leftPush)

Later:
POST /api/dlq/reprocess/one?mode=SYNC
                → Pops from racer:dlq (rightPop, FIFO)
                → retryCount++
                → If retryCount > 3: permanently discarded
                → Else: SyncMessageProcessor.process(message)
                → If succeeds: reprocessedCount++
                → If fails again: re-enqueued with new retryCount
```

---

## Extending the Application

### Add a custom message processor

1. Create a class in `racer-client` implementing `MessageProcessor`:

```java
@Slf4j
@Component("myProcessor")
public class MyCustomProcessor implements MessageProcessor {

    @Override
    public Mono<Void> process(RacerMessage message) {
        return Mono.fromRunnable(() -> {
            log.info("Custom processing: {}", message.getPayload());
            // your business logic here
        });
    }

    @Override
    public String getMode() { return "CUSTOM"; }
}
```

2. Inject it into `ConsumerSubscriber` alongside `syncProcessor` and `asyncProcessor`.

---

### Add a custom channel

**Option A — annotation-driven (recommended)**

1. Add the alias to `application.properties`:
```properties
racer.channels.inventory.name=racer:inventory
racer.channels.inventory.async=true
racer.channels.inventory.sender=inventory-service
```

2. Inject and use in any Spring bean:
```java
@RacerPublisher("inventory")
private RacerChannelPublisher inventoryPublisher;

// Publish imperatively
inventoryPublisher.publishAsync(stockEvent).subscribe();

// Or annotate the producing method
@PublishResult(channelRef = "inventory")
public Mono<StockEvent> reserveStock(StockRequest req) { ... }
```

**Option B — programmatic**

1. Add the channel constant to `RedisChannels.java` in `racer-common`.
2. Publish to it via `PublisherService.publishAsync("racer:inventory", payload, sender)`.
3. In `ConsumerSubscriber`, add a new subscription:

```java
listenerContainer
    .receive(ChannelTopic.of("racer:inventory"))
    .flatMap(this::handleMessage)
    .subscribe();
```

---

### Override request-reply processing logic

In `PubSubResponderService` and `StreamResponderService`, replace the `processRequest()` method body with real business logic:

```java
private String processRequest(RacerRequest request) {
    // Call your service, query DB, etc.
    return myService.handle(request.getPayload());
}
```

---

## Error Handling & DLQ Behaviour

| Scenario | Behaviour |
|----------|-----------|
| Processor throws any exception | Message is moved to DLQ with error details |
| Deserialization fails | Error is logged, message skipped (not DLQ'd) |
| DLQ reprocess fails again | Re-enqueued with incremented `retryCount` |
| `retryCount > 3` | Message permanently discarded, logged as error |
| Request-reply timeout | Server returns HTTP 504 with error message |
| Redis unavailable | Spring Boot reactive pipeline propagates error; check logs |

The maximum retry limit is controlled by `RedisChannels.MAX_RETRY_ATTEMPTS` (default: **3**).

To trigger DLQ intentionally for testing, include the word **"error"** anywhere in the `payload`. Both `SyncMessageProcessor` and `AsyncMessageProcessor` detect this and throw a `RuntimeException`.

```bash
# This message WILL fail and land in the DLQ
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"trigger an error here","sender":"tester"}'

# Confirm it's in the DLQ
curl http://localhost:8081/api/dlq/size

# Reprocess it
curl -s -X POST "http://localhost:8081/api/dlq/reprocess/one?mode=ASYNC"
```

---

## Comparison with Other Brokers

Racer is an **application-level messaging library** built on top of Redis.
This section explains how it compares architecturally to dedicated message brokers.

### Architecture at a Glance

| Dimension | **Racer** | **RabbitMQ** | **ActiveMQ** | **Apache Kafka** |
|-----------|-----------|-------------|-------------|------------------|
| **Core** | Library on Redis | Dedicated broker (Erlang) | Dedicated broker (Java/JMS) | Distributed commit log |
| **Protocol** | Redis Pub/Sub + Streams | AMQP 0-9-1, MQTT, STOMP | JMS, AMQP 1.0, STOMP | Custom binary protocol |
| **Deployment** | Redis (already in most stacks) | Separate cluster | Separate cluster | Multi-node cluster + ZooKeeper/KRaft |
| **Persistence** | Redis Streams + Lists (DLQ) with configurable XTRIM retention | Per-queue on disk | KahaDB / JDBC | Disk-backed partitioned log |
| **Routing** | `@RacerRoute` content-based routing + multi-channel fan-out | Exchanges → bindings → queues | Destinations, virtual topics | Topics → partitions |
| **Consumer groups** | Redis `XREADGROUP` + configurable concurrency + key-based sharding | Competing consumers on a queue | JMS shared subscriptions | Native consumer groups + partition rebalancing |
| **Message ordering** | Per-stream (single partition); strict priority drain within a channel | Per-queue | Per-queue | Per-partition |
| **Message priority** | ✅ `HIGH` / `NORMAL` / `LOW` sub-channels (`@RacerPriority`) | ✅ Native queue priority (0–255) | ✅ JMS message priority | ❌ No native priority; workaround: multiple topics |
| **Schema validation** | ✅ JSON Schema Draft-07 via `RacerSchemaRegistry` (opt-in) | ⚠️ Plugin or custom validator | ❌ No built-in | ✅ Confluent Schema Registry (Avro/JSON/Protobuf) |
| **Backpressure** | Project Reactor operators + configurable poll-batch-size | Channel-level QoS prefetch | JMS prefetch | Consumer fetch size |
| **Batch / pipeline publish** | ✅ `RacerPipelinedPublisher` (parallel) + `RacerTransaction` (ordered) | ⚠️ Publisher confirms, no true pipelining | ❌ Per-message send | ✅ Producer batching + linger.ms |
| **Reactive first-class** | ✅ Project Reactor end-to-end | ⚠️ Reactor RabbitMQ wrapper | ❌ Blocking JMS | ⚠️ Reactor Kafka wrapper |
| **High availability** | ✅ Redis Sentinel + Cluster (Docker Compose provided) | ✅ Mirrored queues / quorum queues | ✅ KahaDB replication | ✅ Native partition replication |
| **Deployment complexity** | Low (Redis + Spring Boot) | Medium (broker + management plugin) | Medium (broker + plugins) | High (brokers + ZooKeeper/KRaft) |

---

### Advantages of Racer

| Advantage | Detail |
|-----------|--------|
| **Zero infra overhead** | If you already run Redis, nothing extra to deploy — no Erlang runtime, no JVM broker, no ZooKeeper. |
| **Sub-millisecond latency** | Redis Pub/Sub delivers in-memory at ~0.1 ms. Dedicated brokers add network hops + disk I/O. |
| **Fully reactive** | Built on Project Reactor + Spring WebFlux end-to-end. RabbitMQ/ActiveMQ clients block threads by default. |
| **Annotation-driven DX** | `@RacerPublisher`, `@PublishResult`, `@EnableRacer` — zero boilerplate. No `ConnectionFactory → Channel → basicPublish` wiring. |
| **Embeddable as a library** | Ships as a Spring Boot starter JAR — import and go, no sidecar or agent. |
| **Request-reply built in** | First-class two-way communication over both Pub/Sub (ephemeral) and Streams (durable). |
| **Dual transport** | Same framework for fire-and-forget (Pub/Sub) and durable (Streams). No second system needed. |
| **Content-based routing** | `@RacerRoute` + `@RacerRouteRule` — declarative regex-pattern fan-out to named channels with zero routing code in business logic. |
| **Message priority** | `@RacerPriority` + `RacerPriorityConsumerService` — `HIGH`/`NORMAL`/`LOW` sub-channels with strict-order drain; no separate queue infrastructure needed. |
| **Pipelined batch publish** | `RacerPipelinedPublisher` issues all commands concurrently over a single Lettuce connection, collapsing N round-trips into ~1 for maximum throughput. |
| **Consumer sharding** | `RacerShardedStreamPublisher` distributes messages across N streams by CRC-16 key hash; `racer.consumer.concurrency` scales readers per stream. |
| **Schema validation** | `RacerSchemaRegistry` validates every message against a JSON Schema Draft-07 file at publish and consume time — opt-in via `racer.schema.enabled=true`. |
| **Retention lifecycle** | `RacerRetentionService` automatically trims streams (`XTRIM MAXLEN`) and prunes stale DLQ entries on a configurable cron schedule. |
| **Config-driven channels** | Add `racer.channels.payments.name=racer:payments` → channel exists at startup. No broker admin, no exchange bindings. |
| **Tiny footprint** | `racer-common` is 35 KB. Easy to audit, fork, and extend. |

---

### Disadvantages & Mitigations

| Disadvantage | Impact | Mitigation | Status |
|-------------|--------|------------|--------|
| **No exchange/routing layer** | Flat channel names only; no wildcards, header routing, or fan-out exchanges | Route manually by publishing to multiple channels | ✅ **Implemented** — `@RacerRoute` + `RacerRouterService` (R-1) |
| **Pub/Sub drops messages when no subscriber** | Messages lost if consumer is offline | Use Redis Streams for durable delivery | ✅ **Implemented** — `@PublishResult(durable=true)` + `RacerStreamConsumerService` (R-2) |
| **No built-in monitoring** | No management UI | Redis `INFO`/`XINFO` via `redis-cli` | ✅ **Implemented** — `RacerMetrics` + Actuator + Prometheus/Grafana (R-3) |
| **No message TTL / expiry** | Streams and DLQ grow indefinitely | `DELETE /api/dlq/clear` for manual cleanup | ✅ **Implemented** — `RacerRetentionService` — `@Scheduled` XTRIM + DLQ age pruning (R-4) |
| **No cross-channel transactions** | Can't atomically publish to multiple channels | Sequential publish (at-most-once) | ✅ **Implemented** — `RacerTransaction` + `/api/publish/batch-atomic` (R-5) |
| **Single Redis = single point of failure** | No built-in clustering at the broker level | Spring Data Redis supports Sentinel/Cluster natively | ✅ **Implemented** — `compose.sentinel.yaml` + `compose.cluster.yaml` (R-6) |
| **No schema registry** | Raw JSON; no schema evolution guards | `@JsonTypeInfo` versioned DTOs | ✅ **Implemented** — `RacerSchemaRegistry` JSON Schema Draft-07 validation on publish & consume paths; opt-in via `racer.schema.enabled=true`; REST API at `/api/schema` (R-7) |
| **Limited consumer scaling** | One stream = one partition; no auto-rebalancing | Multiple consumer group members share 1 stream | ✅ **Implemented** — `racer.consumer.concurrency` + `RacerShardedStreamPublisher` (R-8) |
| **Throughput ceiling** | Redis single-threaded per shard; dedicated brokers win at millions of msg/sec | 100K+ msg/sec easily handled for most apps | ✅ **Implemented** — `RacerPipelinedPublisher` + `/api/publish/batch-pipelined` (R-9) |
| **No message priority** | FIFO only | Use `async=false` for critical channels | ✅ **Implemented** — `RacerPriorityPublisher` + `RacerPriorityConsumerService` (R-10) |
| **No replay / offset seek** | Cannot re-read historical messages from an offset | Use `XRANGE` / `XREVRANGE` directly via `redis-cli` | ❌ Not planned — use Kafka when full replay is required |
| **No exactly-once semantics** | At-least-once delivery; duplicate messages possible on consumer restart | Idempotent consumers (deduplicate on `RacerMessage.id`) | ❌ Not planned — Redis MULTI/EXEC does not span network partitions |

---

### When to Use What

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Use Racer when...                                                           │
│  ✓ Redis is already in your stack                                            │
│  ✓ You want reactive, non-blocking messaging without a separate broker       │
│  ✓ You need sub-millisecond pub/sub + optional durability via Streams        │
│  ✓ You want content-based routing, message priority, and schema validation   │
│    without standing up a separate routing or schema-registry service         │
│  ✓ You need pipelined batch publishing or key-based consumer sharding        │
│  ✓ You want a library, not another infrastructure component to operate       │
│  ✓ Team is small and operational simplicity is a priority                   │
├──────────────────────────────────────────────────────────────────────────────┤
│  Use RabbitMQ when...                                                        │
│  ✓ You need per-message TTL, dead-letter exchanges, and quorum queues        │
│  ✓ You need multi-protocol support (MQTT for IoT, STOMP for web clients)    │
│  ✓ You want a management UI and alerting out of the box                     │
│  ✓ You need sophisticated exchange bindings between many heterogeneous       │
│    producers and consumers (topic, headers, fanout exchanges)                │
├──────────────────────────────────────────────────────────────────────────────┤
│  Use Apache Kafka when...                                                    │
│  ✓ You need millions of messages/sec with horizontal partition scaling       │
│  ✓ You need full log replay (re-read historical messages by offset)          │
│  ✓ You need exactly-once semantics and distributed transactions              │
│  ✓ You're building event-sourcing / CQRS / stream-processing architecture   │
│  ✓ You need a schema registry for Avro / Protobuf contract enforcement       │
├──────────────────────────────────────────────────────────────────────────────┤
│  Use ActiveMQ when...                                                        │
│  ✓ You need JMS compliance for enterprise Java integration                   │
│  ✓ You're integrating with legacy systems that speak JMS/STOMP               │
│  ✓ You need XA transactions (two-phase commit with a database)              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Roadmap & Implementation Status

All roadmap items have been **fully implemented**. Below is a summary of what was built for each item.

---

### ✅ R-1 — Content-Based Routing (`@RacerRoute` / `RacerRouterService`)

**Closes gap:** No exchange/routing layer

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `@RacerRoute` container annotation + `@RacerRouteRule` per-rule annotation (field, matches regex, to channel, sender)
- `RacerRouterService` — scans all beans with `@RacerRoute` at startup via `@PostConstruct`, compiles regex patterns, exposes `route(msg)` and `dryRun()` methods
- Hooked into `ConsumerSubscriber` — router check runs before local processor dispatch
- `RouterController` — `GET /api/router/rules` (view compiled rules) + `POST /api/router/test` (dry-run)

**Key files:** `RacerRoute.java`, `RacerRouteRule.java`, `RacerRouterService.java`, `RouterController.java`

---

### ✅ R-2 — Durable Publishing (`@PublishResult(durable = true)`)

**Closes gap:** Pub/Sub drops messages when no subscriber is active

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- Added `boolean durable()` and `String streamKey()` attributes to `@PublishResult`
- `RacerStreamPublisher` — writes to a Redis Stream via `XADD` instead of Pub/Sub
- `PublishResultAspect` updated to branch: `durable=true` → `RacerStreamPublisher`, else existing Pub/Sub path
- `RacerStreamConsumerService` — consumer group reader using `XREADGROUP`, dispatches to existing processors, DLQ on failure

**Configuration:**
```properties
racer.durable.stream-keys=racer:orders:stream,racer:audit:stream
```

**Key files:** `PublishResult.java`, `RacerStreamPublisher.java`, `RacerStreamConsumerService.java`

---

### ✅ R-3 — Micrometer Metrics

**Closes gap:** No built-in monitoring

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `RacerMetrics` — `@ConditionalOnClass(MeterRegistry.class)` bean with counters, timers, and a gauge
- Wired into `RacerChannelPublisherImpl`, `ConsumerSubscriber`, `DlqReprocessorService`, `PubSubRequestReplyService`, `StreamRequestReplyService`
- `spring-boot-starter-actuator` + `micrometer-registry-prometheus` added to `racer-server` and `racer-client` POMs
- Exposed at `/actuator/metrics` and `/actuator/prometheus`

**Metrics:** `racer.published`, `racer.published.stream`, `racer.consumed`, `racer.failed`, `racer.dlq.reprocessed`, `racer.dlq.size` (gauge), `racer.requestreply.latency` (timer)

**Key files:** `RacerMetrics.java`, server and client `pom.xml`, both `application.properties`

---

### ✅ R-4 — Retention Service (`RacerRetentionService`)

**Closes gap:** No message TTL / expiry

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `RetentionProperties` inner class added to `RacerProperties` (streamMaxLen, dlqMaxAgeHours, scheduleCron)
- `RacerRetentionService` — `@Scheduled` service that runs `XTRIM MAXLEN ~<n>` on all durable streams and removes DLQ entries older than the configured age
- `DlqController` extended with `POST /api/dlq/trim` (on-demand run) and `GET /api/dlq/retention-config`

**Configuration:**
```properties
racer.retention.stream-max-len=10000
racer.retention.dlq-max-age-hours=72
racer.retention.schedule-cron=0 0 * * * *
```

**Key files:** `RacerRetentionService.java`, `RacerProperties.java`, `DlqController.java`

---

### ✅ R-5 — Atomic Batch Publishing (`RacerTransaction`)

**Closes gap:** No cross-channel atomicity

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `RacerTransaction` — collects `(alias, payload, sender)` tuples in a list, executes all via `Flux.concat` for strict ordering
- Registered as a Spring bean in `RacerAutoConfiguration`
- `PublisherController` extended with `POST /api/publish/batch-atomic` — accepts an array of publish items, returns per-channel subscriber counts

**Key files:** `RacerTransaction.java`, `PublisherController.java`

---

### ✅ R-6 — High Availability (Sentinel & Cluster)

**Closes gap:** Single Redis = single point of failure

**Status:** **DONE** — Available since initial roadmap implementation.

**What was implemented:**
- `compose.sentinel.yaml` — 1 primary + 1 replica + 3 Sentinel nodes, ready for `docker compose up`
- `compose.cluster.yaml` — 6-node Redis Cluster (3 primaries + 3 replicas) with auto-init container
- HA configuration snippets added (commented block) in both `application.properties`
- See [High Availability](#high-availability) section and [Tutorial 15](TUTORIALS.md#tutorial-15--high-availability-sentinel--cluster)

**Key files:** `compose.sentinel.yaml`, `compose.cluster.yaml`

---

### Implementation summary

| # | Feature | Status | Key Artifact |
|---|---------|--------|--------------|
| R-1 | Content-Based Routing | ✅ Done | `@RacerRoute`, `RacerRouterService`, `RouterController` |
| R-2 | Durable Publish | ✅ Done | `@PublishResult(durable=true)`, `RacerStreamPublisher`, `RacerStreamConsumerService` |
| R-3 | Micrometer Metrics | ✅ Done | `RacerMetrics`, Actuator, Prometheus |
| R-4 | Retention & Pruning | ✅ Done | `RacerRetentionService`, `/api/dlq/trim` |
| R-5 | Atomic Batch Publish | ✅ Done | `RacerTransaction`, `/api/publish/batch-atomic` |
| R-6 | HA — Sentinel + Cluster | ✅ Done | `compose.sentinel.yaml`, `compose.cluster.yaml` |
| R-7 | Schema Registry | ✅ Implemented | `RacerSchemaRegistry` — JSON Schema Draft-07 validation on publish & consume paths; opt-in via `racer.schema.enabled=true`; REST API at `/api/schema` |
| R-8 | Consumer Scaling + Sharding | ✅ Done | `racer.consumer.concurrency`, `RacerShardedStreamPublisher` |
| R-9 | Throughput — Pipelining | ✅ Done | `RacerPipelinedPublisher`, `/api/publish/batch-pipelined` |
| R-10 | Message Priority | ✅ Done | `RacerPriorityPublisher`, `RacerPriorityConsumerService` |
| R-11 | Scheduled Publishing + Pub/Sub Concurrency | ✅ Done | `@RacerPoll`, `RacerPollRegistrar`, configurable `flatMap` concurrency |

---

### ✅ R-8 — Consumer Scaling + Key-Based Sharding

**Closes gap:** Limited consumer scaling — single hardcoded consumer per stream

**Status:** **DONE**

**What was implemented:**
- `RacerProperties.ConsumerProperties` — `racer.consumer.concurrency`, `name-prefix`, `poll-batch-size`, `poll-interval-ms`
- `RacerStreamConsumerService` refactored — spawns N consumers per stream (e.g. `consumer-0`, `consumer-1`, `consumer-2`), each as an independent polling subscription within the same consumer group; reads up to `poll-batch-size` entries per poll via the `COUNT` XREADGROUP argument
- `StreamResponderService` — consumer name now derived from `racer.consumer.name-prefix` (no longer hardcoded)
- `RacerShardedStreamPublisher` — shard-aware stream publisher; computes shard index via CRC-16/CCITT (same algorithm as Redis Cluster hash slots) modulo `racer.sharding.shard-count`; activated by `@ConditionalOnProperty(racer.sharding.enabled=true)`
- `ShardingProperties` — `racer.sharding.enabled`, `shard-count`, `streams`

**Configuration:**
```properties
racer.consumer.concurrency=3
racer.consumer.name-prefix=consumer
racer.consumer.poll-batch-size=10
racer.consumer.poll-interval-ms=200

racer.sharding.enabled=true
racer.sharding.shard-count=4
racer.sharding.streams=racer:orders:stream,racer:audit:stream
```

**Key files:** `RacerStreamConsumerService.java`, `RacerShardedStreamPublisher.java`, `RacerProperties.java`

---

### ✅ R-9 — Throughput Optimisation (Pipelining)

**Closes gap:** Every publish is a separate Redis round-trip; `RacerTransaction` is sequential

**Status:** **DONE**

**What was implemented:**
- `RacerPipelinedPublisher` — uses `Flux.mergeDelayError` to issue all PUBLISH commands concurrently; Lettuce (the reactive Redis driver) auto-pipelines concurrent commands over a single connection, reducing N round-trips to ~1
- `publishBatch(channel, payloads, sender)` — publishes a list of payloads to the same channel in parallel
- `publishItems(List<PipelineItem>)` — multi-channel pipeline batch (same behaviour as `RacerTransaction` but parallel)
- `RacerTransaction` upgraded — accepts an optional `RacerPipelinedPublisher`; `execute(configurer)` auto-promotes to pipeline when available; new `execute(configurer, pipelined)` overload for explicit control
- `PipelineProperties` — `racer.pipeline.enabled`, `max-batch-size`
- New REST endpoints:
  - `POST /api/publish/batch-pipelined` — parallel batch to single channel
  - `POST /api/publish/batch-atomic-pipelined` — parallel multi-channel batch

**Configuration:**
```properties
racer.pipeline.enabled=true
racer.pipeline.max-batch-size=100
```

**Key files:** `RacerPipelinedPublisher.java`, `RacerTransaction.java`, `PublisherController.java`

---

### ✅ R-10 — Message Priority

**Closes gap:** All channels are FIFO; no way to express message urgency

**Status:** **DONE**

**What was implemented:**
- `PriorityLevel` enum — `HIGH(0)`, `NORMAL(1)`, `LOW(2)` with numeric weight; `PriorityLevel.of(name)` resolves by name with `NORMAL` fallback
- `@RacerPriority` annotation — `defaultLevel` attribute for use alongside `@PublishResult`
- `RacerMessage.priority` field — `String`, defaults to `"NORMAL"`; backward-compatible (missing field → `NORMAL`)
- `RacerPriorityPublisher` — routes messages to sub-channels keyed `{baseChannel}:priority:{LEVEL}` (e.g. `racer:orders:priority:HIGH`)
- `RacerPriorityConsumerService` (racer-client) — subscribes to all configured priority sub-channels; buffers arriving messages in a `PriorityBlockingQueue<PrioritizedMessage>` ordered by weight; a drain loop running on `Schedulers.boundedElastic()` processes messages in strict priority order; active only when `racer.priority.enabled=true`
- `POST /api/publish/async` — accepts optional `"priority"` field; routes through `RacerPriorityPublisher` when present
- `PriorityProperties` — `racer.priority.enabled`, `levels`, `strategy`, `channels`

**Configuration:**
```properties
# server
racer.priority.enabled=true
racer.priority.levels=HIGH,NORMAL,LOW
racer.priority.strategy=strict

# client
racer.priority.enabled=true
racer.priority.channels=racer:orders,racer:notifications
```

**Key files:** `PriorityLevel.java`, `@RacerPriority.java`, `RacerPriorityPublisher.java`, `RacerPriorityConsumerService.java`

---

### ✅ R-11 — Scheduled Publishing & Pub/Sub Concurrency Control

**Closes gap:** No declarative way to trigger periodic data ingestion into Racer; Pub/Sub concurrency was hardcoded

**Status:** **DONE**

**What was implemented:**

#### `@RacerPoll` — Scheduled Publishing
- `@RacerPoll` annotation — marks a no-arg method as a scheduled publisher. The method handles all data fetching/computation; the annotation declares only the schedule (`fixedRate` / `cron`) and the destination (`channel` / `channelRef` / `sender`)
- `RacerPollRegistrar` (BeanPostProcessor) — scans all Spring beans for `@RacerPoll` methods at startup; spins up a reactive `Flux.interval` (fixed-rate) or cron-matched ticker per method; invokes the annotated method, unwraps `Mono<?>` return types, and publishes the result to the configured Racer channel
- Supports Spring property placeholders (`${…}`) in all string attributes
- Return types: `String` (as-is), any serializable object (JSON), `Mono<?>` (unwrapped), `void`/`null` (skipped)
- Metrics: `totalPolls` / `totalErrors` counters; optionally records via `RacerMetrics`
- `PollProperties` — `racer.poll.enabled`

#### Pub/Sub Concurrency Control
- `ConsumerSubscriber` — `flatMap` concurrency now configurable via `racer.pubsub.concurrency` (default 256); controls how many Pub/Sub messages are processed in-flight simultaneously
- `PubSubProperties` — `racer.pubsub.concurrency`

**Configuration:**
```properties
# Pub/Sub concurrent processing (racer-client)
racer.pubsub.concurrency=256

# Enable/disable all @RacerPoll pollers
racer.poll.enabled=true
```

**Key files:** `@RacerPoll.java`, `RacerPollRegistrar.java`, `RacerProperties.java`, `ConsumerSubscriber.java`
