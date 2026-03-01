# Racer — Tutorials

Step-by-step guides for every feature of the Racer messaging framework.
Each tutorial is self-contained and builds on a running Racer instance.

---

## Table of Contents

1. [Tutorial 1 — Getting Started: Boot Redis + Run Racer](#tutorial-1--getting-started-boot-redis--run-racer)
2. [Tutorial 2 — Fire-and-Forget Publishing](#tutorial-2--fire-and-forget-publishing)
3. [Tutorial 3 — Consuming Messages (Sync & Async Modes)](#tutorial-3--consuming-messages-sync--async-modes)
4. [Tutorial 4 — Dead Letter Queue & Reprocessing](#tutorial-4--dead-letter-queue--reprocessing)
5. [Tutorial 5 — Two-Way Request-Reply over Pub/Sub](#tutorial-5--two-way-request-reply-over-pubsub)
6. [Tutorial 6 — Two-Way Request-Reply over Redis Streams](#tutorial-6--two-way-request-reply-over-redis-streams)
7. [Tutorial 7 — Annotation-Driven Publishing (@RacerPublisher & @PublishResult)](#tutorial-7--annotation-driven-publishing-racerpublisher--publishresult)
8. [Tutorial 8 — Multiple Channels with Property Configuration](#tutorial-8--multiple-channels-with-property-configuration)
9. [Tutorial 9 — Using Racer as a Library in a New Project](#tutorial-9--using-racer-as-a-library-in-a-new-project)
10. [Tutorial 10 — Content-Based Routing (@RacerRoute)](#tutorial-10--content-based-routing-racerroute)
11. [Tutorial 11 — Durable Publishing (@PublishResult durable=true)](#tutorial-11--durable-publishing-publishresult-durabletrue)
12. [Tutorial 12 — Metrics & Observability (Actuator + Prometheus)](#tutorial-12--metrics--observability-actuator--prometheus)
13. [Tutorial 13 — Retention & DLQ Pruning](#tutorial-13--retention--dlq-pruning)
14. [Tutorial 14 — Atomic Batch Publishing (RacerTransaction)](#tutorial-14--atomic-batch-publishing-racertransaction)
15. [Tutorial 15 — High Availability (Sentinel & Cluster)](#tutorial-15--high-availability-sentinel--cluster)

---

## Tutorial 1 — Getting Started: Boot Redis + Run Racer

### What you'll learn
- Start Redis via Docker Compose
- Build all three Racer modules
- Launch `racer-server` (port 8080) and `racer-client` (port 8081)
- Confirm everything is connected

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 (set via `JAVA_HOME`) |
| Maven | 3.9+ |
| Docker Desktop | Any recent version |

> **Why JDK 21?** Racer uses Lombok 1.18.x which is incompatible with JDK 25's compiler internals.
> If you have multiple JDKs installed, pin to 21 for every terminal session.

---

### Step 1 — Start Redis

Open **Terminal A**:
```bash
cd /path/to/racer
docker compose -f compose.yaml up -d
```

Verify Redis is up:
```bash
docker ps | grep redis
redis-cli ping          # expected: PONG
```

The `compose.yaml` starts Redis 7-alpine on port `6379` with a named volume `redis-data`
so data survives container restarts.

---

### Step 2 — Build all modules

Open **Terminal B**, pin JDK 21:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
# export JAVA_HOME=/usr/lib/jvm/java-21-openjdk     # Linux

cd /path/to/racer
mvn clean install -DskipTests
```

Expected output:
```
[INFO] racer .............................................. SUCCESS
[INFO] racer-common ....................................... SUCCESS
[INFO] racer-starter ...................................... SUCCESS
[INFO] racer-server ....................................... SUCCESS
[INFO] racer-client ....................................... SUCCESS
[INFO] BUILD SUCCESS
```

> **Library JARs installed to local Maven repo**
> `mvn install` publishes all five modules to `~/.m2/repository/com/cheetah/`.
> Any other project on your machine can now import `racer-starter` (which pulls in
> `racer-common` and all required transitive dependencies) just by adding the
> dependency below — no manual JAR copying needed:
> ```xml
> <dependency>
>     <groupId>com.cheetah</groupId>
>     <artifactId>racer-starter</artifactId>
>     <version>0.0.1-SNAPSHOT</version>
> </dependency>
> ```
> See [Tutorial 9](#tutorial-9--using-racer-as-a-library-in-a-new-project) for a
> complete walkthrough of building a fresh application this way.

---

### Step 3 — Run racer-server

In **Terminal B**:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-server -am spring-boot:run
```

You should see:
```
Started RacerServerApplication in X.XXX seconds
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
[racer] Channel 'audit'         registered → 'racer:audit'
```

---

### Step 4 — Run racer-client

Open **Terminal C**:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd /path/to/racer
mvn -pl :racer-client -am spring-boot:run
```

You should see:
```
Started RacerClientApplication in X.XXX seconds
ConsumerSubscriber: Listening on racer:messages and racer:notifications
StreamResponderService: Consumer group 'racer-client-group' ready
```

---

### Step 5 — Smoke test

```bash
# Is the server up?
curl http://localhost:8080/api/channels
# → {"__default__":{"channel":"racer:messages"}, ...}

# Is the client up?
curl http://localhost:8081/api/consumer/status
# → {"mode":"ASYNC","processedCount":0,"failedCount":0}
```

**Racer is running.** Continue to the next tutorials.

---

## Tutorial 2 — Fire-and-Forget Publishing

### What you'll learn
- Publish a single message asynchronously
- Publish a single message synchronously (blocking)
- Publish a batch of messages in one call
- Observe messages being consumed by the client

### Prerequisites
Tutorial 1 complete (both server and client running).

---

### Step 1 — Async publish

The server publishes to Redis and returns immediately; the reactive chain completes in the background.

```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"Hello from async","sender":"tutorial-2"}' | jq
```

Expected response:
```json
{
  "status":      "published",
  "mode":        "async",
  "channel":     "racer:messages",
  "subscribers": 1
}
```

In **Terminal C** (racer-client log) you'll see:
```
AsyncMessageProcessor: Processing message <id>: Hello from async
```

---

### Step 2 — Sync publish

The server waits for Redis to confirm delivery before returning the HTTP response.

```bash
curl -s -X POST http://localhost:8080/api/publish/sync \
  -H "Content-Type: application/json" \
  -d '{"payload":"Hello from sync","sender":"tutorial-2"}' | jq
```

Expected response:
```json
{
  "status":      "published",
  "mode":        "sync",
  "channel":     "racer:messages",
  "subscribers": 1
}
```

> **Tip:** Use sync when you need confirmation that at least one subscriber received the message
> before proceeding (e.g. audit trails, critical alerts).

---

### Step 3 — Batch publish

Send multiple messages in a single HTTP call. Each message is published in parallel.

```bash
curl -s -X POST http://localhost:8080/api/publish/batch \
  -H "Content-Type: application/json" \
  -d '{
    "payloads": ["batch msg 1", "batch msg 2", "batch msg 3"],
    "sender":   "tutorial-2",
    "channel":  "racer:messages"
  }' | jq
```

Expected response:
```json
{
  "status":       "published",
  "mode":         "async-batch",
  "channel":      "racer:messages",
  "messageCount": 3
}
```

In the client log you'll see three separate processing lines.

---

### Step 4 — Target a specific channel

```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{
    "payload": "Stock alert: low inventory",
    "sender":  "inventory-service",
    "channel": "racer:notifications"
  }' | jq
```

> The client subscribes to both `racer:messages` and `racer:notifications`,
> so the message will be processed regardless of channel.

---

### Step 5 — Verify processed count

```bash
curl -s http://localhost:8081/api/consumer/status | jq
```

```json
{
  "mode":           "ASYNC",
  "processedCount": 5,
  "failedCount":    0
}
```

---

## Tutorial 3 — Consuming Messages (Sync & Async Modes)

### What you'll learn
- Understand the difference between SYNC and ASYNC processor modes
- Switch the processing mode at runtime without restarting
- Observe how failures are routed to the DLQ

### Prerequisites
Tutorial 1 complete.

---

### Step 1 — Check the current mode

```bash
curl -s http://localhost:8081/api/consumer/status | jq
```

```json
{ "mode": "ASYNC", "processedCount": 0, "failedCount": 0 }
```

Default is `ASYNC` (set by `racer.client.processing-mode=ASYNC` in `application.properties`).

---

### Step 2 — ASYNC mode behaviour

ASYNC mode uses a non-blocking reactive scheduler. Messages are processed concurrently,
each on Project Reactor's default scheduler. There is no artificial thread blocking.

```bash
# Send a few messages
for i in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8080/api/publish/async \
    -H "Content-Type: application/json" \
    -d "{\"payload\":\"async message $i\",\"sender\":\"tutorial-3\"}" > /dev/null
done
```

Watch the client log — you'll see all five being processed in rapid, potentially interleaved,
sequence without waiting for each other.

---

### Step 3 — Switch to SYNC mode

```bash
curl -s -X PUT "http://localhost:8081/api/consumer/mode?mode=SYNC" | jq
```

```json
{
  "status": "switched",
  "mode":   "SYNC"
}
```

SYNC mode uses `Schedulers.boundedElastic()` and processes messages one at a time
on a thread-pool worker. Each message blocks until complete before the next starts.

---

### Step 4 — Observe SYNC processing

```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:8080/api/publish/async \
    -H "Content-Type: application/json" \
    -d "{\"payload\":\"sync message $i\",\"sender\":\"tutorial-3\"}" > /dev/null
done
```

In the client log, messages now appear strictly in order, one after another.

---

### Step 5 — Trigger a failure

Both processors have a built-in failure trigger: if the payload contains the word `"error"`,
they throw a `RuntimeException`. The failed message is routed to the DLQ.

```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"this will error","sender":"tutorial-3"}' | jq
```

Then check the client stats:
```bash
curl -s http://localhost:8081/api/consumer/status | jq
# failedCount should now be 1
curl -s http://localhost:8081/api/dlq/size | jq
# { "dlqSize": 1 }
```

---

### Step 6 — Switch back to ASYNC

```bash
curl -s -X PUT "http://localhost:8081/api/consumer/mode?mode=ASYNC" | jq
```

> Runtime mode switching is atomic (backed by `AtomicReference`) — safe under concurrent load
> with no restart required.

---

## Tutorial 4 — Dead Letter Queue & Reprocessing

### What you'll learn
- Understand how messages land in the DLQ
- Inspect DLQ contents
- Reprocess messages directly
- Republish messages back through the pipeline
- Understand the max-retry limit

### Prerequisites
Tutorial 1 complete. Optionally run Tutorial 3 Step 5 to pre-seed the DLQ.

---

### Step 1 — Seed the DLQ

Send three error-triggering messages:
```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:8080/api/publish/async \
    -H "Content-Type: application/json" \
    -d "{\"payload\":\"error message $i\",\"sender\":\"tutorial-4\"}" > /dev/null
done

sleep 1  # give the client time to process and DLQ them
curl -s http://localhost:8081/api/dlq/size | jq
# { "dlqSize": 3 }
```

---

### Step 2 — Inspect DLQ contents

```bash
curl -s http://localhost:8081/api/dlq/messages | jq
```

Each entry is a `DeadLetterMessage`:
```json
[
  {
    "id":              "<uuid>",
    "originalMessage": {
      "id":         "<uuid>",
      "payload":    "error message 1",
      "sender":     "tutorial-4",
      "retryCount": 0
    },
    "errorMessage":   "Simulated processing failure for message: <uuid>",
    "exceptionClass": "java.lang.RuntimeException",
    "failedAt":       "2026-03-01T12:00:00Z",
    "attemptCount":   1
  },
  ...
]
```

---

### Step 3 — View stats

```bash
curl -s http://localhost:8081/api/dlq/stats | jq
```

```json
{
  "queueSize":        3,
  "totalReprocessed": 0,
  "permanentlyFailed": 0
}
```

---

### Step 4 — Reprocess one message (SYNC mode)

Popping from the DLQ and reprocessing directly:

```bash
curl -s -X POST "http://localhost:8081/api/dlq/reprocess/one?mode=SYNC" | jq
```

```json
{
  "reprocessed":      false,
  "mode":             "SYNC",
  "totalReprocessed": 0,
  "permanentlyFailed": 0
}
```

> `reprocessed: false` because the payload still contains `"error"` — the processor rejects it again.
> The message will be re-enqueued with an incremented `retryCount`.

---

### Step 5 — Fix the payload and reprocess

The DLQ is a Redis List. To demonstrate successful reprocessing, send a clean message directly
to the DLQ by first publishing it, letting it fail, then manually reprocessing:

```bash
# 1. Send a fixable message with a benign payload
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"fixable payload","sender":"tutorial-4"}'
```

This message processes successfully (no "error" keyword). Let's instead show reprocessing of
a message that was previously fixed — switch to a payload without "error":

```bash
# Reprocess all (those that don't contain "error" will succeed)
curl -s -X POST "http://localhost:8081/api/dlq/reprocess/all?mode=ASYNC" | jq
```

```json
{
  "reprocessedCount": 0,
  "mode":             "ASYNC",
  "totalReprocessed": 0,
  "permanentlyFailed": 3
}
```

Because all three still contain "error", they exhaust their retry budget
(`MAX_RETRY_ATTEMPTS = 3`) and are permanently discarded.

---

### Step 6 — Republish a DLQ message back to the channel

Instead of reprocessing in-place, republish the message back to its original channel.
This routes it through the subscriber + processor pipeline again.

First add a new error message to the DLQ:
```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"error republish test","sender":"tutorial-4"}'
sleep 1

# Republish it
curl -s -X POST http://localhost:8081/api/dlq/republish/one | jq
```

```json
{
  "republished": true,
  "subscribers": 1
}
```

The message flows through `ConsumerSubscriber` again with an incremented `retryCount`.

---

### Step 7 — Clear the DLQ

When you want a clean slate:
```bash
curl -s -X DELETE http://localhost:8081/api/dlq/clear | jq
# { "cleared": true }
curl -s http://localhost:8081/api/dlq/size | jq
# { "dlqSize": 0 }
```

---

### DLQ retry lifecycle summary

```
Message fails processing
    → retryCount = 0, enqueued in racer:dlq

POST /api/dlq/reprocess/one
    → retryCount++
    → if retryCount > MAX_RETRY_ATTEMPTS (3): permanently discarded
    → if retryCount ≤ 3 and processing fails: re-enqueued

POST /api/dlq/republish/one
    → retryCount++
    → if retryCount > MAX_RETRY_ATTEMPTS: permanently discarded
    → else: message re-published to original channel
```

---

## Tutorial 5 — Two-Way Request-Reply over Pub/Sub

### What you'll learn
- Issue a request from the server and wait for the client's reply
- Understand the ephemeral reply channel pattern
- Handle timeouts
- Trigger and observe error replies

### Prerequisites
Tutorial 1 complete (both server and client running).

### How it works

```
racer-server                       Redis                     racer-client
────────────────────────────────────────────────────────────────────────
POST /api/request/pubsub
  → creates correlationId = "abc"
  → subscribes to racer:reply:abc ──────────────────────────────→
  → publishes RacerRequest
    to racer:messages ─────────────→ Pub/Sub ──────────────────→ PubSubResponderService
                                                                  detects replyTo != null
                                                                  processes request
                                  ←── publishes RacerReply ─────
                                     to racer:reply:abc
  ← HTTP 200 with reply ─────────────────────────────────────────
```

---

### Step 1 — Send a basic request

```bash
curl -s -X POST http://localhost:8080/api/request/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"ping","sender":"tutorial-5"}' | jq
```

Expected response:
```json
{
  "transport":     "pubsub",
  "correlationId": "550e8400-...",
  "success":       true,
  "reply":         "Processed: ping [echoed by racer-client]",
  "responder":     "racer-client",
  "errorMessage":  ""
}
```

The round-trip (server → Redis → client → Redis → server) completes before the HTTP response.

---

### Step 2 — Custom timeout

The default timeout is 30 seconds. Shorten it for testing:
```bash
curl -s -X POST http://localhost:8080/api/request/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"fast ping","sender":"tutorial-5","timeoutSeconds":5}' | jq
```

---

### Step 3 — Trigger a failure reply

The responder returns an error reply when the payload contains "error":
```bash
curl -s -X POST http://localhost:8080/api/request/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"this will error","sender":"tutorial-5"}' | jq
```

```json
{
  "transport":    "pubsub",
  "correlationId":"...",
  "success":      false,
  "reply":        null,
  "responder":    "racer-client",
  "errorMessage": "Processing failed: this will error"
}
```

---

### Step 4 — Simulate a timeout

Stop `racer-client` (Ctrl-C in Terminal C), then send a request:
```bash
curl -s -X POST http://localhost:8080/api/request/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"nobody home","timeoutSeconds":3}' | jq
```

```json
{
  "transport": "pubsub",
  "error":     "Did not observe any item or terminal signal within 3000ms"
}
```

HTTP status `504 Gateway Timeout` is returned.

Restart `racer-client` before continuing.

---

### Step 5 — Send multiple concurrent requests

Because each request uses a unique `correlationId` and ephemeral reply channel,
concurrent requests do not interfere:

```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:8080/api/request/pubsub \
    -H "Content-Type: application/json" \
    -d "{\"payload\":\"concurrent request $i\"}" &
done
wait
```

All three will complete independently.

---

## Tutorial 6 — Two-Way Request-Reply over Redis Streams

### What you'll learn
- Send requests via Redis Streams instead of Pub/Sub
- Understand consumer groups and message acknowledgement (ACK)
- Compare Streams vs Pub/Sub for request-reply

### Prerequisites
Tutorial 1 complete.

### How it works

```
racer-server                         Redis                       racer-client
──────────────────────────────────────────────────────────────────────────────
POST /api/request/stream
  → creates correlationId = "xyz"
  → XADD racer:stream:requests ──────────────────────────────→ StreamResponderService
                                                                (consumer group: racer-client-group,
                                                                 consumer: client-1)
                                                                processes request
                                   ←── XADD ─────────────────── XADD racer:stream:response:xyz
  polls racer:stream:response:xyz
  (every 200ms, up to timeout)
  ← HTTP 200 with reply ──────────────────────────────────────
                                     XACK racer:stream:requests  (entry acknowledged)
                                     DEL racer:stream:response:xyz (cleanup)
```

**Key differences vs Pub/Sub:**
- Messages persist in the stream until ACK'd — no message loss if the client restarts mid-flight
- Consumer groups allow multiple client instances to share load
- Each entry is a durable record, not a transient broadcast

---

### Step 1 — Send a stream request

```bash
curl -s -X POST http://localhost:8080/api/request/stream \
  -H "Content-Type: application/json" \
  -d '{"payload":"stream ping","sender":"tutorial-6"}' | jq
```

Expected response:
```json
{
  "transport":     "stream",
  "correlationId": "550e8400-...",
  "success":       true,
  "reply":         "Stream-processed: stream ping [by racer-client-stream]",
  "responder":     "racer-client-stream",
  "errorMessage":  ""
}
```

---

### Step 2 — Observe with redis-cli

In a separate terminal, watch the streams:
```bash
redis-cli
> XLEN racer:stream:requests
(integer) 0           # 0 because the entry was ACK'd and delivered
> KEYS racer:stream:response:*
(empty list)          # response stream was deleted after read
```

---

### Step 3 — Trigger a failure reply

```bash
curl -s -X POST http://localhost:8080/api/request/stream \
  -H "Content-Type: application/json" \
  -d '{"payload":"stream error test","sender":"tutorial-6"}' | jq
```

```json
{
  "transport":    "stream",
  "correlationId":"...",
  "success":      false,
  "reply":        null,
  "responder":    "racer-client-stream",
  "errorMessage": "Stream processing failed: stream error test"
}
```

---

### Step 4 — Simulate no consumer (timeout)

Stop `racer-client`, then:
```bash
curl -s -X POST http://localhost:8080/api/request/stream \
  -H "Content-Type: application/json" \
  -d '{"payload":"orphaned","timeoutSeconds":3}' | jq
```

```json
{
  "transport": "stream",
  "error":     "Did not observe any item or terminal signal within 3000ms"
}
```

The request entry remains in `racer:stream:requests` (not ACK'd) — when the client restarts
it will **automatically pick up unacknowledged entries** and process them.

Restart `racer-client` and verify:
```bash
curl -s http://localhost:8081/api/responder/status | jq
# requestsProcessed count will have incremented
```

### Step 5 — Check responder status

```bash
curl -s http://localhost:8081/api/responder/status | jq
```

```json
{
  "pubsub":  { "repliesSent":        3 },
  "stream":  { "requestsProcessed":  3 }
}
```

---

## Tutorial 7 — Annotation-Driven Publishing (@RacerPublisher & @PublishResult)

### What you'll learn
- Activate the Racer annotation framework with `@EnableRacer`
- Inject channel publishers into any Spring bean with `@RacerPublisher`
- Automatically publish method return values with `@PublishResult`
- Use the Channel Registry API to inspect and publish to channels

### Prerequisites
Tutorial 1 complete. Both modules already have `@EnableRacer` active.

---

### Step 1 — Inspect registered channels

The `RacerPublisherRegistry` registers a publisher for every alias at startup.
Query it at runtime:

```bash
curl -s http://localhost:8080/api/channels | jq
```

```json
{
  "__default__":   { "channel": "racer:messages" },
  "orders":        { "channel": "racer:orders" },
  "notifications": { "channel": "racer:notifications" },
  "audit":         { "channel": "racer:audit" }
}
```

---

### Step 2 — Publish to a named channel via the API

```bash
curl -s -X POST http://localhost:8080/api/channels/publish/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","item":"Widget","qty":3}' | jq
```

```json
{
  "published":   true,
  "alias":       "orders",
  "channel":     "racer:orders",
  "subscribers": 1
}
```

Internally, `ChannelRegistryController` has a field:
```java
@RacerPublisher("orders")
private RacerChannelPublisher ordersPublisher;
```
That field was injected automatically by `RacerPublisherFieldProcessor` — no `@Autowired` needed.

---

### Step 3 — Live @PublishResult demo

`POST /api/channels/publish-annotated` calls a method annotated with `@PublishResult`.
The return value is published to `racer:orders` as a side-effect:

```bash
curl -s -X POST http://localhost:8080/api/channels/publish-annotated \
  -H "Content-Type: application/json" \
  -d '{"item":"Gadget","qty":5}' | jq
```

**What happens under the hood:**
1. WebFlux calls `ChannelRegistryController.publishAnnotated(body)`
2. That delegates to `buildOrderEvent(body)`, which is annotated:
   ```java
   @PublishResult(channelRef = "orders", sender = "channel-controller", async = true)
   public Mono<Map<String, Object>> buildOrderEvent(Map<String, Object> request) { ... }
   ```
3. `PublishResultAspect` wraps the returned `Mono` with `.doOnNext(value → publish to racer:orders)`
4. The HTTP caller receives the enriched map
5. The same map is also published to `racer:orders` in the background

Response:
```json
{
  "item":        "Gadget",
  "qty":         5,
  "eventType":   "ORDER_CREATED",
  "processedAt": "2026-03-01T12:00:00Z",
  "source":      "racer-server"
}
```

---

### Step 4 — Use @RacerPublisher in your own service

> **Works in any project — not just racer-server.**
> The snippets below show `racer-server` as the host, but they apply equally to any
> Spring Boot application that has `racer-starter` on its classpath.
> See [Tutorial 9](#tutorial-9--using-racer-as-a-library-in-a-new-project) for a
> full new-project setup.

Add this to any service that has `racer-starter` as a dependency:

```java
@Service
public class ShipmentService {

    // Injected automatically — no @Autowired, no constructor injection needed
    @RacerPublisher("notifications")
    private RacerChannelPublisher notificationsPublisher;

    @RacerPublisher   // → default channel (racer:messages)
    private RacerChannelPublisher defaultPublisher;

    public Mono<Shipment> ship(Order order) {
        Shipment shipment = Shipment.from(order);
        return shipmentRepository.save(shipment)
                // Notify via the notifications channel
                .flatMap(saved -> notificationsPublisher
                        .publishAsync(Map.of("event", "SHIPPED", "orderId", order.getId()))
                        .thenReturn(saved));
    }
}
```

---

### Step 5 — Use @PublishResult in your own service

```java
@Service
public class InventoryService {

    // Every object returned by reserveStock() is automatically
    // published to racer:audit (blocking — guaranteed delivery)
    @PublishResult(channelRef = "audit", async = false, sender = "inventory-service")
    public StockReservation reserveStock(String sku, int qty) {
        StockReservation reservation = inventoryRepository.reserve(sku, qty);
        return reservation;   // ← this value is published to racer:audit
    }

    // Works with reactive return types too
    @PublishResult(channelRef = "orders")
    public Mono<Order> fulfillOrder(OrderRequest request) {
        return orderRepository.save(request.toOrder());
        // ← the Order inside the Mono is published to racer:orders
    }
}
```

> **Self-invocation warning:** Calling an `@PublishResult` method from within the **same class**
> bypasses the Spring AOP proxy — the annotation will not fire. Call it from another bean,
> or inject `ApplicationContext` and look up `self` to force proxy invocation.

---

### Step 6 — Demo injected publisher endpoints

```bash
# Publish to orders via injected @RacerPublisher("orders") field
curl -s -X POST http://localhost:8080/api/channels/demo/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-002","status":"CONFIRMED"}' | jq
# → { "channel": "racer:orders", "subscribers": 1 }

# Publish to notifications via injected @RacerPublisher("notifications") field
curl -s -X POST http://localhost:8080/api/channels/demo/notifications \
  -H "Content-Type: application/json" \
  -d '{"message":"Your order has shipped"}' | jq
# → { "channel": "racer:notifications", "subscribers": 1 }
```

---

## Tutorial 8 — Multiple Channels with Property Configuration

### What you'll learn
- Declare and manage multiple Redis channels from `application.properties`
- Understand the `async` and `sender` channel-level defaults
- Add a new channel without touching any Java code
- Observe channel registration at startup

### Prerequisites
Tutorial 1 complete.

> **Using `application.properties` in an external project**
> Everything in this tutorial applies verbatim to any project that imports `racer-starter`.
> Drop the same `racer.channels.*` properties into your own `application.properties`
> and the channels are registered automatically — no configuration class needed.
> See [Tutorial 9](#tutorial-9--using-racer-as-a-library-in-a-new-project) for a
> complete working example.

---

### Step 1 — Understand the current channel config

Open `racer-server/src/main/resources/application.properties`:

```properties
racer.default-channel=racer:messages

racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=order-service

racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=true
racer.channels.notifications.sender=notification-service

racer.channels.audit.name=racer:audit
racer.channels.audit.async=false        # ← blocking: waits for Redis confirmation
racer.channels.audit.sender=audit-service
```

| Alias | Redis channel | async | sender |
|-------|---------------|-------|--------|
| *(default)* | `racer:messages` | — | `racer` |
| `orders` | `racer:orders` | `true` | `order-service` |
| `notifications` | `racer:notifications` | `true` | `notification-service` |
| `audit` | `racer:audit` | `false` | `audit-service` |

---

### Step 2 — Add a new channel (no Java changes)

Add these lines to `racer-server/src/main/resources/application.properties`:

```properties
racer.channels.payments.name=racer:payments
racer.channels.payments.async=false
racer.channels.payments.sender=payment-gateway
```

Rebuild and restart the server:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-server -am spring-boot:run
```

Startup log now includes:
```
[racer] Channel 'payments' registered → 'racer:payments'
```

---

### Step 3 — Verify via the registry API

```bash
curl -s http://localhost:8080/api/channels | jq
```

```json
{
  "__default__":  { "channel": "racer:messages" },
  "orders":       { "channel": "racer:orders" },
  "notifications":{ "channel": "racer:notifications" },
  "audit":        { "channel": "racer:audit" },
  "payments":     { "channel": "racer:payments" }
}
```

---

### Step 4 — Publish to the new channel

```bash
curl -s -X POST http://localhost:8080/api/channels/publish/payments \
  -H "Content-Type: application/json" \
  -d '{"txId":"TXN-9001","amount":199.99,"currency":"USD"}' | jq
```

```json
{
  "published":   true,
  "alias":       "payments",
  "channel":     "racer:payments",
  "subscribers": 0
}
```

> `subscribers: 0` because the client is not yet subscribed to `racer:payments`.
> See below to add a subscriber.

---

### Step 5 — Subscribe the client to the new channel

In `racer-client`, open `ConsumerSubscriber.java` and add:

```java
// In the @PostConstruct / init method, add:
listenerContainer
    .receive(ChannelTopic.of("racer:payments"))
    .flatMap(msg -> handleMessage(msg, "payments"))
    .doOnError(e -> log.error("[payments subscriber] Error: {}", e.getMessage()))
    .subscribe();
```

Alternatively, add to `application.properties` and let a generic multi-channel subscriber
(if you build one) pick it up dynamically. See _Extending the Application_ in `README.md`.

---

### Step 6 — Use @RacerPublisher with the new channel

Once the new alias is declared in properties, you can inject the publisher immediately:

```java
@Service
public class PaymentService {

    @RacerPublisher("payments")
    private RacerChannelPublisher paymentsPublisher;

    public Mono<Payment> charge(ChargeRequest req) {
        return paymentGateway.charge(req)
                .flatMap(payment -> paymentsPublisher
                        .publishAsync(payment)
                        .thenReturn(payment));
    }
}
```

Or annotate the method:
```java
@PublishResult(channelRef = "payments", sender = "payment-gateway", async = false)
public Mono<Payment> charge(ChargeRequest req) {
    return paymentGateway.charge(req);
}
```

**Zero Java changes to `racer-common`** — the new channel exists purely through config.

---

### Step 7 — Channel-level async flag

The `async` flag controls the default `publishSync` vs `publishAsync` call in `RacerChannelPublisherImpl`:

| `async=true` | Returns immediately after sending data to Redis |
| `async=false` | Blocks until Redis confirms the publish (`.block(10s)`) |

For critical channels (payments, audit), always use `async=false` to ensure delivery
confirmation before your service method returns.

---

### Summary: channel configuration cheat-sheet

```properties
# Minimum required per channel:
racer.channels.<alias>.name=racer:<your-key>

# Optional (with defaults):
racer.channels.<alias>.async=true            # default: true
racer.channels.<alias>.sender=my-service     # default: "racer"
```

Rules:
- `alias` = the string you pass to `@RacerPublisher("alias")` or `@PublishResult(channelRef = "alias")`
- `name` = the actual Redis Pub/Sub channel key
- Aliases are case-sensitive
- Missing `name` → channel skipped at startup with a warning log
- Unknown alias in `@RacerPublisher` → falls back to default channel (logged as warning)

---

## Tutorial 9 — Using Racer as a Library in a New Project

### What you'll learn
- Create a brand-new Spring Boot application from scratch
- Import `racer-starter` as a Maven dependency
- Configure channels in `application.properties`
- Inject publishers with `@RacerPublisher`
- Auto-publish return values with `@PublishResult`
- Send messages from your new app and observe them in a running `racer-client`

### Prerequisites
- Tutorial 1 **Step 2** complete — `mvn clean install` run inside the Racer repo so all
  JARs are in your local Maven cache (`~/.m2`)
- Redis running (`docker compose -f /path/to/racer/compose.yaml up -d`)
- `racer-client` running on port 8081 (`mvn -pl :racer-client -am spring-boot:run`)

> **No need to run `racer-server` for this tutorial.**
> Your new application replaces it as the publisher.

---

### Step 1 — Create the project skeleton

Use [Spring Initializr](https://start.spring.io) or create the files manually.
The minimum dependencies you need from Initializr: **Spring Reactive Web**.

Create the directory layout:

```
my-racer-app/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/example/myapp/
        │       ├── MyRacerAppApplication.java
        │       ├── service/
        │       │   ├── OrderService.java
        │       │   └── NotificationService.java
        │       └── controller/
        │           └── OrderController.java
        └── resources/
            └── application.properties
```

---

### Step 2 — Write the POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.3</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>my-racer-app</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>my-racer-app</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- Racer starter — brings in racer-common, reactive Redis, AOP, Jackson -->
        <dependency>
            <groupId>com.cheetah</groupId>
            <artifactId>racer-starter</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>

        <!-- WebFlux for reactive HTTP endpoints -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Lombok (optional, but matches what Racer uses) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

> **Why `racer-starter` and not `racer-common` directly?**
> `racer-starter` is a thin aggregator that also declares the transitive dependencies
> (reactive Redis, AOP, Jackson) that `racer-common` needs.
> It mirrors the Spring Boot starter pattern — one line in your POM and you're done.

---

### Step 3 — Configure `application.properties`

```properties
# ── Server ──────────────────────────────────────────────────────────────
server.port=8090

# ── Redis ────────────────────────────────────────────────────────────────
spring.data.redis.host=localhost
spring.data.redis.port=6379

# ── Racer ────────────────────────────────────────────────────────────────
# Default channel (used when no alias is specified)
racer.default-channel=racer:messages

# Named channel: orders
racer.channels.orders.name=racer:orders
racer.channels.orders.async=true
racer.channels.orders.sender=my-order-service

# Named channel: notifications (blocking — waits for Redis confirmation)
racer.channels.notifications.name=racer:notifications
racer.channels.notifications.async=false
racer.channels.notifications.sender=my-notification-service
```

---

### Step 4 — Main application class

```java
package com.example.myapp;

import com.cheetah.racer.common.annotation.EnableRacer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRacer          // activates RacerAutoConfiguration, AOP, registry, field processor
public class MyRacerAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyRacerAppApplication.class, args);
    }
}
```

> **`@EnableRacer` is optional** when `racer-starter` is on the classpath, because the
> `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file
> registers `RacerAutoConfiguration` automatically.
> Add it explicitly when you want self-documenting intent, or when your project
> does not use the starter (e.g. you import `racer-common` directly).

---

### Step 5 — OrderService — `@PublishResult` and `@RacerPublisher`

```java
package com.example.myapp.service;

import com.cheetah.racer.common.annotation.PublishResult;
import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    /**
     * Injected automatically by RacerPublisherFieldProcessor —
     * no @Autowired, no constructor wiring needed.
     */
    @RacerPublisher("notifications")
    private RacerChannelPublisher notificationsPublisher;

    /**
     * The Map returned here is also published to racer:orders as a side-effect.
     * The HTTP caller still receives the full return value.
     */
    @PublishResult(channelRef = "orders", sender = "my-order-service", async = true)
    public Mono<Map<String, Object>> placeOrder(String item, int qty) {
        Map<String, Object> order = Map.of(
                "orderId",   UUID.randomUUID().toString(),
                "item",      item,
                "qty",       qty,
                "status",    "CREATED",
                "createdAt", Instant.now().toString()
        );
        // Return value is automatically published to racer:orders by @PublishResult
        return Mono.just(order);
    }

    /**
     * Manual publish using @RacerPublisher-injected field.
     */
    public Mono<Void> notifyShipped(String orderId) {
        Map<String, Object> event = Map.of(
                "event",   "ORDER_SHIPPED",
                "orderId", orderId,
                "at",      Instant.now().toString()
        );
        return notificationsPublisher.publishAsync(event).then();
    }
}
```

---

### Step 6 — NotificationService — default channel

```java
package com.example.myapp.service;

import com.cheetah.racer.common.annotation.RacerPublisher;
import com.cheetah.racer.common.publisher.RacerChannelPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class NotificationService {

    // No alias → injects the default channel publisher (racer:messages)
    @RacerPublisher
    private RacerChannelPublisher defaultPublisher;

    public Mono<Long> broadcast(String message) {
        return defaultPublisher.publishAsync(Map.of("message", message));
    }
}
```

---

### Step 7 — REST controller

```java
package com.example.myapp.controller;

import com.example.myapp.service.NotificationService;
import com.example.myapp.service.OrderService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;
    private final NotificationService notificationService;

    public OrderController(OrderService orderService,
                           NotificationService notificationService) {
        this.orderService = orderService;
        this.notificationService = notificationService;
    }

    /** Place an order — return value is published to racer:orders via @PublishResult */
    @PostMapping("/orders")
    public Mono<Map<String, Object>> placeOrder(@RequestBody Map<String, Object> body) {
        String item = (String) body.get("item");
        int qty = ((Number) body.get("qty")).intValue();
        return orderService.placeOrder(item, qty);
    }

    /** Manually trigger a shipment notification to racer:notifications */
    @PostMapping("/orders/{orderId}/ship")
    public Mono<Map<String, String>> shipOrder(@PathVariable String orderId) {
        return orderService.notifyShipped(orderId)
                .thenReturn(Map.of("status", "notified", "orderId", orderId));
    }

    /** Broadcast a message to the default channel (racer:messages) */
    @PostMapping("/broadcast")
    public Mono<Map<String, Object>> broadcast(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        return notificationService.broadcast(message)
                .map(subscribers -> Map.of(
                        "message",     message,
                        "subscribers", subscribers,
                        "channel",     "racer:messages"
                ));
    }
}
```

---

### Step 8 — Build and run

```bash
cd my-racer-app
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean package -DskipTests
java -jar target/my-racer-app-1.0.0-SNAPSHOT.jar
```

Or run directly with Maven:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Startup log should include:
```
Started MyRacerAppApplication in X.XXX seconds
[racer] Default channel registered: 'racer:messages'
[racer] Channel 'orders'        registered → 'racer:orders'
[racer] Channel 'notifications' registered → 'racer:notifications'
```

---

### Step 9 — Test it end-to-end

Make sure `racer-client` is running on port 8081 (it subscribes to `racer:messages` and
`racer:notifications`). Then:

**Place an order (published to racer:orders via @PublishResult):**
```bash
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{"item":"Widget","qty":3}' | jq
```

```json
{
  "orderId":   "550e8400-...",
  "item":      "Widget",
  "qty":       3,
  "status":    "CREATED",
  "createdAt": "2026-03-01T12:00:00Z"
}
```

In a Redis client you can verify the message was published:
```bash
redis-cli SUBSCRIBE racer:orders
# (in another terminal:)
curl -s -X POST http://localhost:8090/api/orders \
  -H "Content-Type: application/json" \
  -d '{"item":"Gadget","qty":1}'
```

**Ship an order (published to racer:notifications via @RacerPublisher):**
```bash
curl -s -X POST http://localhost:8090/api/orders/ORD-123/ship | jq
```

```json
{ "status": "notified", "orderId": "ORD-123" }
```

`racer-client` log will show the notification being processed on the `racer:notifications` channel.

**Broadcast to the default channel:**
```bash
curl -s -X POST http://localhost:8090/api/broadcast \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello from my-racer-app"}' | jq
```

```json
{
  "message":     "Hello from my-racer-app",
  "subscribers": 1,
  "channel":     "racer:messages"
}
```

---

### Step 10 — Check racer-client received everything

```bash
curl -s http://localhost:8081/api/consumer/status | jq
```

```json
{
  "mode":           "ASYNC",
  "processedCount": 3,
  "failedCount":    0
}
```

---

### What you built

```
my-racer-app (port 8090)                Redis               racer-client (port 8081)
──────────────────────────────────────────────────────────────────────────────────────
POST /api/orders
  → OrderService.placeOrder()
    @PublishResult intercepts ─────────→ racer:orders ──────────────────────────────→
  ← HTTP 200 (order map)

POST /api/orders/{id}/ship
  → notificationsPublisher.publishAsync ─→ racer:notifications ────────────────────→
  ← HTTP 200

POST /api/broadcast
  → defaultPublisher.publishAsync ──────→ racer:messages ─────────────────────────→
  ← HTTP 200
```

---

### Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Could not find artifact com.cheetah:racer-starter` | Run `mvn clean install -DskipTests` inside the Racer repo first |
| `@RacerPublisher` field is `null` at runtime | Ensure the bean is a Spring-managed `@Component`/`@Service` — not instantiated with `new` |
| `@PublishResult` method never publishes | The method must be called via the Spring proxy (from another bean, not from within the same class) |
| `WRONGTYPE Operation against a key` in Redis | A key was previously used as a different data type; flush with `redis-cli FLUSHDB` |
| `Connection refused` on Redis | Redis is not running — `docker compose -f /path/to/racer/compose.yaml up -d` |
| `@EnableRacer` not found | Add `racer-starter` (or `racer-common`) to your POM |

---

## Next Steps

| Feature | Docs |
|---------|------|
| Full API reference | [README.md — API Reference](README.md#api-reference--server-port-8080) |
| Message schemas | [README.md — Message Schemas](README.md#message-schemas) |
| End-to-end flow diagrams | [README.md — End-to-End Flows](README.md#end-to-end-flows) |
| Extending Racer | [README.md — Extending the Application](README.md#extending-the-application) |
| Error handling & DLQ behaviour table | [README.md — Error Handling](README.md#error-handling--dlq-behaviour) |
| Observability | [README.md — Observability & Metrics](README.md#observability--metrics) |
| High Availability | [README.md — High Availability](README.md#high-availability) |

---

## Tutorial 10 — Content-Based Routing (@RacerRoute)

### What you'll learn
- Define routing rules with `@RacerRoute` and `@RacerRouteRule`
- How `RacerRouterService` evaluates regex rules against inbound message fields
- Inspect compiled rules at runtime via `GET /api/router/rules`
- Dry-run routing with `POST /api/router/test`

### Prerequisites
- Racer running (both modules) — see Tutorial 1
- At least two channel aliases configured (e.g. `orders` and `notifications`)

---

### Step 1 — Define a router bean

Create a `@Component` class in `racer-server` (or your own library module) annotated with `@RacerRoute`:

```java
@Component
@RacerRoute({
    @RacerRouteRule(field = "type", matches = "^ORDER.*",        to = "racer:orders"),
    @RacerRouteRule(field = "type", matches = "^NOTIFICATION.*", to = "racer:notifications"),
    @RacerRouteRule(field = "sender", matches = "payment-svc",   to = "racer:payments",
                    sender = "router")
})
public class MessageRouter { }
```

- `field` — any top-level key in the JSON payload
- `matches` — a Java regex applied to that field's value
- `to` — the target Redis channel/key if the rule fires
- Rules are evaluated in order; the **first match wins**

---

### Step 2 — Restart and verify compiled rules

Restart `racer-server`, then list the compiled rules:

```bash
curl -s http://localhost:8080/api/router/rules | python3 -m json.tool
```

Expected output:
```json
[
  { "index": 0, "field": "type",   "pattern": "^ORDER.*",        "to": "racer:orders" },
  { "index": 1, "field": "type",   "pattern": "^NOTIFICATION.*", "to": "racer:notifications" },
  { "index": 2, "field": "sender", "pattern": "payment-svc",     "to": "racer:payments" }
]
```

---

### Step 3 — Dry-run a message

Use `POST /api/router/test` to see which rule (if any) would match:

```bash
# Should match rule index 0
curl -s -X POST http://localhost:8080/api/router/test \
  -H "Content-Type: application/json" \
  -d '{"type":"ORDER_CREATED","id":"123","amount":49.99}' | python3 -m json.tool
```

Expected:
```json
{
  "matched":   true,
  "ruleIndex": 0,
  "field":     "type",
  "pattern":   "^ORDER.*",
  "to":        "racer:orders"
}
```

```bash
# Should not match
curl -s -X POST http://localhost:8080/api/router/test \
  -H "Content-Type: application/json" \
  -d '{"type":"UNKNOWN","id":"999"}' | python3 -m json.tool
```

Expected:
```json
{ "matched": false }
```

---

### Step 4 — Publish a real message and observe routing

Publish a message whose `type` starts with `ORDER`:

```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"{\"type\":\"ORDER_PLACED\",\"id\":\"42\"}","sender":"checkout"}'
```

In `racer-client` logs you should see the message dispatched to the `racer:orders` consumer group rather than the default processing path.

---

### What you built
A content-based message router that evaluates inbound messages against regex rules and forwards them to the correct Redis channel — equivalent to RabbitMQ topic exchange semantics, without leaving the Redis ecosystem.

---

## Tutorial 11 — Durable Publishing (@PublishResult durable=true)

### What you'll learn
- Use `@PublishResult(durable=true)` to write to a Redis Stream instead of Pub/Sub
- Configure `racer-client` to consume durable streams via consumer groups
- Verify guaranteed delivery when the consumer was offline at publish time

### Prerequisites
- Racer running (both modules) — see Tutorial 1

---

### Step 1 — Annotate a method for durable publish

In your service (or `racer-server`'s `PublisherService`), add:

```java
@PublishResult(durable = true, streamKey = "racer:orders:stream", sender = "order-svc")
public Mono<String> placeOrder(String orderJson) {
    // your business logic
    return Mono.just(orderJson);
}
```

- `durable = true` switches from `PUBLISH` (Pub/Sub) to `XADD` (Stream)
- `streamKey` is the Redis key of the stream to write to
- The return value is still passed through to the caller unchanged

---

### Step 2 — Configure the client to consume the stream

In `racer-client/src/main/resources/application.properties`:

```properties
racer.durable.stream-keys=racer:orders:stream
```

`RacerStreamConsumerService` will automatically create a consumer group named `racer-durable-group` on this stream and begin reading entries.

---

### Step 3 — Publish with the consumer offline

Stop `racer-client`:
```bash
# Press Ctrl+C in the racer-client terminal
```

Publish a durable order message:
```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"{\"type\":\"ORDER_DURABLE\",\"id\":\"101\"}","sender":"test"}'
```

Verify the entry was written to the stream:
```bash
redis-cli XLEN racer:orders:stream
# Expected: 1
redis-cli XRANGE racer:orders:stream - +
```

---

### Step 4 — Restart the client and verify delivery

Restart `racer-client`:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl :racer-client -am spring-boot:run
```

Watch the logs — within a few seconds you should see:
```
[racer-durable] Consumed entry from racer:orders:stream: {"type":"ORDER_DURABLE","id":"101"}
```

Verify the entry has been acknowledged (no pending entries):
```bash
redis-cli XPENDING racer:orders:stream racer-durable-group - + 10
# Expected: (empty list or [])
```

---

### What you built
At-least-once guaranteed delivery: the message was stored in a Redis Stream while the consumer was offline, and was processed exactly once after it came back online.

---

## Tutorial 12 — Metrics & Observability (Actuator + Prometheus)

### What you'll learn
- Access Spring Boot Actuator health and metrics endpoints
- Query individual Racer metrics by name
- Scrape the Prometheus endpoint for integration with Grafana

### Prerequisites
- Racer running (both modules)
- Both `application.properties` files have:
  ```properties
  management.endpoints.web.exposure.include=health,info,metrics,prometheus
  ```

---

### Step 1 — Check health

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
curl -s http://localhost:8081/actuator/health | python3 -m json.tool
```

Expected:
```json
{ "status": "UP" }
```

---

### Step 2 — List all metric names

```bash
curl -s http://localhost:8080/actuator/metrics | python3 -m json.tool | grep racer
```

You should see entries like:
```
"racer.published",
"racer.consumed",
"racer.failed",
"racer.dlq.reprocessed",
"racer.dlq.size",
"racer.requestreply.latency"
```

---

### Step 3 — Query a specific metric

Generate some traffic first:
```bash
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/publish/async \
    -H "Content-Type: application/json" \
    -d "{\"payload\":\"hello $i\",\"sender\":\"tutorial\"}" > /dev/null
done
```

Now query the publish counter:
```bash
curl -s "http://localhost:8080/actuator/metrics/racer.published" | python3 -m json.tool
```

Expected:
```json
{
  "name": "racer.published",
  "measurements": [{ "statistic": "COUNT", "value": 10.0 }],
  "availableTags": [{ "tag": "transport", "values": ["pubsub"] }]
}
```

---

### Step 4 — Prometheus scrape

```bash
curl -s http://localhost:8080/actuator/prometheus | grep "^racer"
```

Expected output (sample):
```
racer_published_total{application="racer-server",transport="pubsub",} 10.0
racer_consumed_total{application="racer-client",mode="ASYNC",} 10.0
racer_dlq_size{application="racer-client",} 0.0
```

This endpoint is ready to be scraped by Prometheus. Add to your `prometheus.yml`:
```yaml
scrape_configs:
  - job_name: racer-server
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']
  - job_name: racer-client
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8081']
```

---

### Step 5 — Request-reply latency

Send a request-reply and check the latency timer:
```bash
curl -s -X POST http://localhost:8080/api/request-reply/pubsub \
  -H "Content-Type: application/json" \
  -d '{"payload":"ping","sender":"tutorial"}' | python3 -m json.tool

curl -s "http://localhost:8080/actuator/metrics/racer.requestreply.latency" | python3 -m json.tool
```

---

### What you built
Full operational visibility into Racer. Every publish, consume, failure, and round-trip latency is tracked in Micrometer and exportable to any observability backend.

---

## Tutorial 13 — Retention & DLQ Pruning

### What you'll learn
- Configure automatic stream trimming and DLQ age-based pruning
- Trigger an immediate on-demand retention run
- Inspect current retention settings via the REST API

### Prerequisites
- Racer running (both modules)
- Some messages published (to generate DLQ entries, trigger processing failures)

---

### Step 1 — Configure retention in racer-client

Edit `racer-client/src/main/resources/application.properties`:

```properties
# Keep at most 100 entries per durable stream (for demo purposes)
racer.retention.stream-max-len=100

# Prune DLQ entries older than 1 hour
racer.retention.dlq-max-age-hours=1

# Run every minute (for demo) — change to hourly (0 0 * * * *) for production
racer.retention.schedule-cron=0 * * * * *
```

Restart `racer-client`.

---

### Step 2 — View current retention config

```bash
curl -s http://localhost:8081/api/dlq/retention-config | python3 -m json.tool
```

Expected:
```json
{
  "streamMaxLen":   100,
  "dlqMaxAgeHours": 1,
  "scheduleCron":   "0 * * * * *"
}
```

---

### Step 3 — Generate some DLQ entries

Force processing failures by publishing a malformed payload (the consumer will fail and push to DLQ):
```bash
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/api/publish/async \
    -H "Content-Type: application/json" \
    -d '{"payload":"__FORCE_FAIL__","sender":"tutorial"}' > /dev/null
done
```

Check DLQ depth:
```bash
curl -s http://localhost:8081/api/dlq/size
redis-cli LLEN racer:dlq
```

---

### Step 4 — Trigger immediate trim

Rather than waiting for the scheduler, trigger manually:
```bash
curl -s -X POST http://localhost:8081/api/dlq/trim | python3 -m json.tool
```

Expected:
```json
{
  "status":    "trimmed",
  "timestamp": "2026-03-01T10:00:00Z"
}
```

Check `racer-client` logs for:
```
[racer-retention] Trimmed stream racer:orders:stream to max 100 entries
[racer-retention] Pruned 5 DLQ entries older than 1 hour(s)
```

---

### Step 5 — Verify DLQ is pruned

```bash
redis-cli LLEN racer:dlq
# Expected: 0 (entries were recent, but were pruned by the max-age rule)
```

> **Note:** The age-based prune only removes entries where `failedAt` is older than `dlqMaxAgeHours`. If your test entries were just created, adjust `dlq-max-age-hours=0` or wait.

---

### What you built
Automatic memory management for Redis. Durable streams stay bounded and old DLQ entries are evicted on a schedule — preventing unbounded growth in production.

---

## Tutorial 14 — Atomic Batch Publishing (RacerTransaction)

### What you'll learn
- Publish to multiple channels in a guaranteed ordered sequence with `RacerTransaction`
- Use the `POST /api/publish/batch-atomic` endpoint
- Understand the ordering guarantee vs. parallel `/batch`

### Prerequisites
- Racer running (both modules)
- Multiple channel aliases configured (`orders`, `audit`, `notifications`)

---

### Step 1 — Understand the difference

| Endpoint | Execution | Order guaranteed |
|----------|-----------|-----------------|
| `POST /api/publish/batch` | Parallel (`Flux.merge`) | ❌ No |
| `POST /api/publish/batch-atomic` | Sequential (`Flux.concat`) | ✅ Yes |

Use `batch-atomic` when the processing order matters — e.g. you need the audit event recorded before the notification is sent.

---

### Step 2 — Publish a batch-atomic request

```bash
curl -s -X POST http://localhost:8080/api/publish/batch-atomic \
  -H "Content-Type: application/json" \
  -d '[
    {"alias":"orders",        "payload":"Order #50 placed",  "sender":"checkout"},
    {"alias":"audit",         "payload":"Audit log #50",     "sender":"checkout"},
    {"alias":"notifications", "payload":"Your order is in!", "sender":"checkout"}
  ]' | python3 -m json.tool
```

Expected response:
```json
{
  "status":           "published",
  "mode":             "atomic-batch",
  "messageCount":     3,
  "subscriberCounts": [1, 1, 1]
}
```

---

### Step 3 — Observe ordering in client logs

In `racer-client` logs, the three messages arrive in exact order:
```
[racer] Received on racer:orders        → Order #50 placed
[racer] Received on racer:audit         → Audit log #50
[racer] Received on racer:notifications → Your order is in!
```

---

### Step 4 — Programmatic usage

To use `RacerTransaction` directly in your own Spring bean:

```java
@Autowired
private RacerTransaction racerTx;

racerTx.execute(tx -> {
    tx.publish("orders",        "Order #50 placed",  "checkout");
    tx.publish("audit",         "Audit log #50",     "checkout");
    tx.publish("notifications", "Your order is in!", "checkout");
}).subscribe(counts -> log.info("Subscriber counts: {}", counts));
```

---

### What you built
Ordered fan-out: three channels receive the same batch of messages in strict order, using a single transaction-like call with no parallelism between publishes.

---

## Tutorial 15 — High Availability (Sentinel & Cluster)

### What you'll learn
- Start Redis in **Sentinel** mode for automatic failover
- Start Redis in **Cluster** mode for horizontal scale-out
- Configure `application.properties` for each HA mode
- Simulate a primary failover and verify Racer reconnects

### Prerequisites
- Docker Desktop running
- Racer built (`mvn clean install -DskipTests`)

---

### Part A — Sentinel Mode

Sentinel mode provides automatic failover with 1 primary, 1 replica, and 3 Sentinel nodes.

#### Step A-1 — Start the Sentinel stack

```bash
docker compose -f compose.sentinel.yaml up -d
```

Verify all containers are healthy:
```bash
docker ps | grep racer
# Expected: racer-redis-primary, racer-redis-replica, racer-sentinel-1/2/3 — all Up
```

Verify Sentinel can see the primary:
```bash
docker exec racer-sentinel-1 redis-cli -p 26379 SENTINEL masters
# Look for: name=mymaster, status=ok
```

#### Step A-2 — Configure application.properties

In both `application.properties` files, **comment out** the standalone lines and **add**:

```properties
# Comment out standalone mode:
# spring.data.redis.host=localhost
# spring.data.redis.port=6379

# Enable Sentinel:
spring.data.redis.sentinel.master=mymaster
spring.data.redis.sentinel.nodes=localhost:26379,localhost:26380,localhost:26381
```

Restart both Racer modules.

#### Step A-3 — Publish and verify

```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"sentinel test","sender":"tutorial"}'
# Expected: {"status":"published","subscribers":1,...}
```

#### Step A-4 — Simulate failover

Stop the primary:
```bash
docker stop racer-redis-primary
```

Watch Sentinel logs — a new primary should be elected within ~5 seconds:
```bash
docker logs -f racer-sentinel-1
# Look for: +elected-leader, +promoted-slave, +switch-master
```

Publish again — Racer should reconnect automatically:
```bash
curl -s -X POST http://localhost:8080/api/publish/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"after failover","sender":"tutorial"}'
```

Restart the original primary (it will re-join as a replica):
```bash
docker start racer-redis-primary
```

---

### Part B — Cluster Mode

Redis Cluster provides horizontal sharding across 6 nodes (3 primaries + 3 replicas).

#### Step B-1 — Start the cluster

```bash
docker compose -f compose.cluster.yaml up -d
```

Wait ~10 seconds for the auto-init container to configure cluster slots, then verify:
```bash
docker exec racer-cluster-node-1 redis-cli -p 7001 CLUSTER INFO | grep cluster_state
# Expected: cluster_state:ok
```

#### Step B-2 — Configure application.properties

```properties
# Comment out standalone mode:
# spring.data.redis.host=localhost
# spring.data.redis.port=6379

# Enable Cluster:
spring.data.redis.cluster.nodes=localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005,localhost:7006
```

Restart both Racer modules and test as in Step A-3.

---

### Part C — Reverting to standalone

When done with HA testing, switch back to standalone mode:
```bash
docker compose -f compose.sentinel.yaml down   # or compose.cluster.yaml
docker compose -f compose.yaml up -d
```

Restore `application.properties` to the standalone settings.

---

### What you built
Production-grade Redis deployments that survive single-node failures (Sentinel) or support horizontal scale-out (Cluster) — with zero changes to Racer's application code.

