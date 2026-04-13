# Phase 4 — v2.0.0: Type Safety & API Cleanup (Breaking)

**Goal:** Clean up API debt accumulated through v1.x. This is the major-version bump where breaking changes are allowed.

**Estimated effort:** ~2 weeks

## Tasks

| ID | Task | Files Changed | Breaking? |
|----|------|---------------|-----------|
| [4.1](4.1-type-safe-priority.md) | Type-Safe Priority in `RacerMessage` | `RacerMessage`, `PriorityLevel`, `RacerPriorityPublisher`, `MessageEnvelopeBuilder`, `PublishResultAspect`, `PublishResult`, `RacerPriority`, `RacerListenerRegistrar` | **Yes** — Java API changes |
| [4.2](4.2-remove-router-sync-path.md) | Remove Deprecated Router Sync Path | `RacerRouterService`, `RacerListenerRegistrar` | **Yes** — method removal |
| [4.3](4.3-consolidate-constructors.md) | Consolidate Constructor Overloads | `RacerPublisherRegistry`, `RacerCircuitBreakerRegistry` | **Yes** — constructors removed |
| [4.4](4.4-remove-deprecated-dryrun.md) | Remove Deprecated `dryRun(Object)` | `RacerRouterService` | **Yes** — method removal |

## Prerequisites

- Phase 3 task [3.5](../phase-3/3.5-router-dryrun-fix.md) must be completed before 4.4 (it introduces the deprecation that 4.4 removes)

## Migration Guide

A `MIGRATION-2.0.md` file must be published alongside this release covering:

1. `RacerMessage.getPriority()` now returns `PriorityLevel` instead of `String`
2. `RacerRouterService.route()` is now reactive (was synchronous) — update all call sites
3. `RacerRouterService.evaluate()` is now reactive — update all call sites
4. `RacerPublisherRegistry` constructors consolidated — use `RacerPublisherRegistry.forTesting()` in tests
5. `RacerCircuitBreakerRegistry` no-metrics constructor removed — pass `new NoOpRacerMetrics()` explicitly
6. `RacerRouterService.dryRun(Object)` removed — use `dryRun(Object, String, String)` instead
