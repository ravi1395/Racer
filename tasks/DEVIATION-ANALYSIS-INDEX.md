# Deviation Analysis Index

Comprehensive audit of Phase 1 & 2 implementation against the IMPLEMENTATION-PLAN.md.

**Status:** 8 deviations found. No erroneous code; all are either low-risk or require intentional remediation before Phase 3.

**Generated:** 2026-04-13  
**Commits analysed:** `9f7a280` (Phase 1), `ec9536c` (Phase 2)

---

## Phase 1 Deviations (2 issues)

**Location:** `tasks/phase-1/deviations.md`

| Problem | Severity | Type | Verdict |
|---------|----------|------|---------|
| D1 · Misleading commit message for `DeadLetterMessage` changes | Low | Documentation | Code is correct; message overstates change |
| D2 · Stream cleanup in `doFinally()` (better than planned) | Negligible | Implementation detail | Strict improvement; no action needed |

**Effort to resolve:** Optional cosmetic fix for D1 (amend commit message).

---

## Phase 2 Deviations (6 issues across 3 focus areas)

### Maven & Build Infrastructure

**Location:** `tasks/phase-2/deviations-maven-versioning.md`

| Problem | Severity | Type | Action |
|---------|----------|------|--------|
| D1 · No multi-module parent POM | Medium | Structural | Create parent POM or BOM before Phase 3 |
| D5 · Unplanned `@Conditional` guards in `RedisConfig` | Low | Emergent necessity | Document and accept |

**Effort to resolve:** D1 = 30 min–1 hour. D5 = documentation only.

---

### Testing & API Surface

**Location:** `tasks/phase-2/deviations-testing-api.md`

| Problem | Severity | Type | Action |
|---------|----------|------|--------|
| D2 · No test classes in `racer-test` module | Medium | Missing coverage | Write 5 test classes (4–6 hours) |
| D3 · `@RacerTest.channels()` is non-functional | Medium | False affordance | Implement feature or remove attribute (1–2 hours) |

**Effort to resolve:** D2 = 4–6 hours. D3 = 1–2 hours.

---

### API Design & Encapsulation

**Location:** `tasks/phase-2/deviations-api-surface.md`

| Problem | Severity | Type | Action |
|---------|----------|------|--------|
| D4 · Test harness API exposed on production classes | Low | Design trade-off | Add `@VisibleForTesting` (optional; 15 min) |
| D6 · Extra `fireAtStreamListener()` overload (not planned) | Negligible | Positive extension | Accept as-is; no action |

**Effort to resolve:** D4 = 15 min (optional). D6 = none.

---

## Summary by Priority

### 🔴 **Critical** (resolve before v1.5.0 release)
- **D2** — `racer-test` has zero test coverage. Untested test infrastructure ships.
- **D3** — `@RacerTest.channels()` is a false affordance. Implement or remove it.

**Combined effort:** 5–8 hours

### 🟡 **High** (resolve before Phase 3 work begins)
- **D1** (Maven) — No multi-module/BOM structure creates version alignment debt.

**Effort:** 30 min–1 hour

### 🟢 **Low** (optional / defer)
- **D1** (Phase 1) — Commit message overstates change. Optional cleanup.
- **D4** — Annotate test harness API with `@VisibleForTesting` for IDE clarity.
- **D5** — Document the emergent `RedisConfig` changes.
- **D6** — Accept as positive extension.

**Effort:** 15–30 min combined

---

## Recommended remediation order

1. **Before v1.5.0 release:**
   - Write 5 test classes for `racer-test` (D2) — 4–6 hours
   - Implement or remove `@RacerTest.channels()` (D3) — 1–2 hours

2. **Before Phase 3 starts:**
   - Create root parent POM or BOM module (D1, Maven) — 30 min–1 hour

3. **Nice-to-have:**
   - Add `@VisibleForTesting` annotations (D4) — 15 min
   - Update plan doc with `RedisConfig` notes (D5) — 10 min
   - Amend Phase 1 commit message (D1, Phase 1) — 5 min

---

## File manifest

```
tasks/
├── DEVIATION-ANALYSIS-INDEX.md          (this file)
├── phase-1/
│   └── deviations.md                    (D1 + D2)
└── phase-2/
    ├── deviations-maven-versioning.md   (D1 + D5)
    ├── deviations-testing-api.md        (D2 + D3)
    └── deviations-api-surface.md        (D4 + D6)
```

Each file covers 2 problems with context, consequences, and remediation options.

---

## Overall assessment

✅ **No erroneous code was introduced in either phase.**

All deviations are either:
- **Spec drift** (planned feature not fully implemented, e.g. D3)
- **Missing coverage** (test infrastructure untested, e.g. D2)
- **Structural debt** (no multi-module build, e.g. D1 Maven)
- **Emergent solutions** (unplanned but correct, e.g. D5)

The library is **safe to use** but should address D2 and D3 before releasing v1.5.0, and D1 before starting Phase 3.
