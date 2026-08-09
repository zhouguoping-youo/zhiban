# Runtime v2 Kernel and Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the durable Room-backed Runtime v2 store, fencing recovery, and serialized SessionActor foundation required by #runtime-v2 + #t3.

**Architecture:** Keep the current single Android app module for this delivery, but enforce `runtime/spi`, `runtime/store`, and `runtime/kernel` package boundaries. Extend the existing Agent Room database so command receipt, events, run state, tool execution, and local business writes can share one transaction; the in-memory actor is only an ordered executor and never the source of truth.

**Tech Stack:** Kotlin 2.x, Coroutines/Flow, Room/KSP, kotlinx.serialization, JUnit4, AndroidX Room testing; minSdk 26.

## Global Constraints

- Follow ADR-002 Accepted at commit `1b3e912`.
- Write tests first and observe the expected failure before production code.
- No Provider, Context, UI, or domain DAO imports into Kernel public contracts.
- No new dependency, credential path, permission, Service, or external network behavior.
- Preserve existing Agent Runtime v1 tables and data with Room migration 1→2.

---

### Task 1: Versioned SPI and Room schema

**Files:**
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/spi/RuntimeContracts.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/store/RuntimeEntities.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/store/RuntimeDaos.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/data/agent/AgentDatabase.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeStoreMigrationTest.kt`

**Interfaces:**
- Produces versioned identifiers, command/event envelopes, run states, Room entities, and DAOs used by later tasks.

- [ ] Write a migration test that creates schema v1, inserts legacy data, migrates to v2, verifies preservation, new tables, unique `(sessionId, sequence)`, and foreign keys.
- [ ] Run the migration test and verify RED because migration/tables do not exist.
- [ ] Implement the minimal contracts, entities, DAOs, database version 2, and explicit migration.
- [ ] Run migration/schema tests and verify GREEN.

### Task 2: CommandInbox and append-first EventStore

**Files:**
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/store/RoomRuntimeStore.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RoomRuntimeStoreTest.kt`

**Interfaces:**
- Consumes Runtime DAOs/entities from Task 1.
- Produces `acceptCommand`, `claimCommand`, `appendEvent`, `completeCommand`, `snapshot`, and replay APIs.

- [ ] Write failing tests for duplicate command replay, transactionally allocated sequence, event uniqueness, and persisted command result.
- [ ] Run tests and verify expected RED.
- [ ] Implement minimal transactional store operations.
- [ ] Run tests and verify GREEN.

### Task 3: Lease/fencing recovery and tool result idempotency

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/store/RoomRuntimeStore.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RoomRuntimeStoreTest.kt`

**Interfaces:**
- Produces atomic `claimSession`, `renewLease`, fenced append, expired inbox reclaim, and idempotent tool result lookup.

- [ ] Write failing tests for two competing owners, stale epoch rejection, expired CLAIMED inbox reclaim, and edited canonical payload producing a different key.
- [ ] Run tests and verify expected RED.
- [ ] Implement CAS lease/fencing and stable tool execution/result persistence.
- [ ] Run tests and verify GREEN.

### Task 4: SessionActor and state-machine recovery

**Files:**
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/kernel/RuntimeStateMachine.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/kernel/SessionActor.kt`
- Test: `app/src/test/java/com/zhiban/rebuild/runtime/kernel/RuntimeStateMachineTest.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/kernel/SessionActorTest.kt`

**Interfaces:**
- Consumes RuntimeStore only.
- Produces ordered command handling, legal transitions, cancellation precedence, retry attempts, restart replay, and projection sequence.

- [ ] Write failing pure state-machine tests for legal/illegal transitions and cancel/success races.
- [ ] Implement minimal deterministic transition reducer and verify GREEN.
- [ ] Write failing actor tests for same-session serialization, duplicate command, retry without duplicate output, and restart replay.
- [ ] Implement the actor mailbox and recovery scanner; verify GREEN.
- [ ] Run Debug/Release unit tests, instrumentation tests, builds, `git diff --check`, then commit only #t3 files.
