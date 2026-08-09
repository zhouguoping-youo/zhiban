# Runtime v2 Production Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose durable CAS command acceptance and gap-free projection/event catch-up without exposing Room or legacy Runtime to UI.

**Architecture:** Public gateway contracts live in `runtime-spi`; an internal Room adapter owns CommandInbox/EventStore transactions and Room invalidation Flow. Hilt binds only the gateway interfaces while the database/store remain internal.

**Tech Stack:** Kotlin, Room, Coroutines Flow, Hilt, JUnit4 Android instrumentation.

## Global Constraints

- Runtime v2 ADR-002 is authoritative; minSdk remains 26.
- Start/Approve/Reject/Cancel/Retry/Resume use persistent CommandInbox and CAS revision.
- Duplicate command IDs return the persisted receipt; conflicting input or revision never writes an Event.
- UI cannot reference `RoomRuntimeStore`, DAO, or legacy `AgentRuntime`.
- No Provider, credential, SecretRedactor, permission, or exported-component changes.

---

### Task 1: Gateway contracts and CAS command transaction

**Files:**
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/spi/RuntimeGatewayContracts.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/store/RoomRuntimeStore.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeGatewayTest.kt`

**Interfaces:**
- Produces: `RuntimeCommandGateway.accept(RuntimeUiCommand)`, durable `CommandReceipt`.

- [ ] Write failing tests for six commands, duplicate receipt, payload conflict, stale revision, and zero-write conflict.
- [ ] Run the focused instrumentation test and verify RED from missing gateway contracts.
- [ ] Add minimal contracts and same-transaction inbox/event/CAS implementation.
- [ ] Run the focused tests and refactor while green.

### Task 2: Gap-free snapshot and observation

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/spi/RuntimeGatewayContracts.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/store/RuntimeDaos.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/store/RoomRuntimeGateways.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeGatewayTest.kt`

**Interfaces:**
- Produces: `RuntimeProjectionGateway.snapshotAndObserve(sessionId, projectionName, afterSequenceExclusive)` returning snapshot plus cumulative ordered event batches.

- [ ] Write failing cold-start and write-between-snapshot-and-collection tests.
- [ ] Verify RED from missing Room Flow adapter.
- [ ] Add Room invalidation Flow and snapshot adapter with monotonic sequence filtering.
- [ ] Verify no lost or duplicate sequence in focused tests.

### Task 3: Hilt binding and regression gate

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/di/AgentDataModule.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeGatewayTest.kt`

**Interfaces:**
- Produces: injectable `RuntimeCommandGateway` and `RuntimeProjectionGateway`; no injectable Room DAO/store.

- [ ] Write a failing Hilt boundary/visibility test where practical and compile-check public SPI.
- [ ] Bind internal Room adapter to both public gateway interfaces.
- [ ] Run Debug/Release unit tests, builds, full instrumentation, and `git diff --check`.
- [ ] Commit independently and hand off to frontend then architecture/test.
