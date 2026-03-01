# racer-starter

Spring Boot Starter for **Racer** — reactive Redis messaging with annotations.

## Usage

Add a single dependency to any Spring Boot project:

```xml
<dependency>
    <groupId>com.cheetah</groupId>
    <artifactId>racer-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.cheetah:racer-starter:0.0.1-SNAPSHOT")
```

## What you get

| Feature | Description |
|---------|-------------|
| `@EnableRacer` | Explicit opt-in (still works, but no longer required) |
| `@RacerPublisher` | Field-inject a `RacerChannelPublisher` for any named channel |
| `@PublishResult` | Auto-publish method return values to Redis channels |
| `RacerProperties` | `racer.default-channel`, `racer.channels.<alias>.*` config |
| `RacerPublisherRegistry` | Programmatic access to all registered channel publishers |
| Models | `RacerMessage`, `RacerRequest`, `RacerReply`, `DeadLetterMessage` |
| `ReactiveRedisTemplate` beans | Pre-configured String and JSON templates |

## Minimal example

```properties
# application.properties
spring.data.redis.host=localhost
spring.data.redis.port=6379

racer.default-channel=racer:messages
racer.channels.orders.name=racer:orders
```

```java
@SpringBootApplication
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}

@Service
class OrderService {

    @RacerPublisher("orders")
    private RacerChannelPublisher ordersPublisher;

    public Mono<Void> placeOrder(Order order) {
        return ordersPublisher.publishAsync(order).then();
    }

    @PublishResult(channelRef = "orders")
    public Mono<Order> createOrder(OrderRequest req) {
        return orderRepo.save(req.toOrder());
    }
}
```

## Importing server/client services as libraries

`racer-server` and `racer-client` also produce importable library JARs
(in addition to executable Boot JARs). To import server-side services:

```xml
<dependency>
    <groupId>com.cheetah</groupId>
    <artifactId>racer-server</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

To import client-side services (consumer, DLQ, responders):

```xml
<dependency>
    <groupId>com.cheetah</groupId>
    <artifactId>racer-client</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

The executable Boot JARs are available with classifier `exec`:
```bash
java -jar racer-server-0.0.1-SNAPSHOT-exec.jar
java -jar racer-client-0.0.1-SNAPSHOT-exec.jar
```
