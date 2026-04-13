# Phase 3 — v1.6.0: Per-Listener Config & DX Polish

**Goal:** Make the library production-tunable for multi-listener services and fix DX paper cuts.

**Estimated effort:** ~2 weeks

## Tasks

| ID | Task | Files Changed | Breaking? |
|----|------|---------------|-----------|
| [3.1](3.1-per-listener-circuit-breaker.md) | Per-Listener Circuit Breaker Configuration | `RacerProperties`, `RacerCircuitBreakerRegistry` | No |
| [3.2](3.2-dlq-ordering-consistency.md) | DLQ Ordering Consistency | `DeadLetterQueueService` | **Behavior change** |
| [3.3](3.3-backpressure-recovery.md) | Back-Pressure Recovery | `RacerBackPressureMonitor` | No |
| [3.4](3.4-pipeline-docs.md) | Processing Pipeline Documentation | `README.md` | No |
| [3.5](3.5-router-dryrun-fix.md) | Router Dry-Run Fix | `RacerRouterService` | No (deprecation only) |

## Migration Notes

- **3.2** changes `peekAll()` ordering from newest-first to oldest-first (FIFO). Any consumer code that relies on newest-first ordering must be updated. Document in release notes.
