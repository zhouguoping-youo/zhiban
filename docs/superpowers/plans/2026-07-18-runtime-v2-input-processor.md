# Runtime v2 Text Input and Command Processor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stage user text privately and consume Start commands through a durable, recoverable Kernel processor without leaking raw text or bypassing typed state transitions.

**Architecture:** Add a Room v3 input-staging table behind `TextInputGateway`; raw text is referenced only by a random opaque ID. `KernelCommandProcessor` claims Inbox work under a lease and uses one Room transaction to read staging, append only length/digest facts, advance the typed Run state, persist projection/result, and delete raw text.

**Tech Stack:** Kotlin, Room, Coroutines, Hilt, Android backup rules, JUnit4 instrumentation.

## Global Constraints

- app-private Room same database; UTF-8 input max 64 KiB; TTL max 24 hours.
- inputRef is at least 128-bit random and unguessable.
- Raw text never enters Command/Event/receipt/log/metric/crash/export/network.
- Consume transaction deletes raw only after persistent Run/Input facts and command result succeed; rollback preserves it.
- Feature Flag OFF stages/processes nothing and legacy/v2 cannot dual-write.
- No new encryption, credential, SecretRedactor, permission, or exported-component path.

---

### Task 1: Room v3 staging table and safe TextInputGateway

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/data/agent/AgentDatabase.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/store/RuntimeEntities.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/store/RuntimeDaos.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/spi/RuntimeInputContracts.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/store/RoomTextInputGateway.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeInputProcessorTest.kt`

- [ ] Write RED tests for 128-bit refs, UTF-8 boundary, TTL, OFF zero-write, and no raw text outside staging.
- [ ] Verify RED from missing contracts/table.
- [ ] Add migration 2→3, DAO, random reference, digest, and expiry cleanup.
- [ ] Verify focused tests GREEN.

### Task 2: Lease-fenced Start processor transaction

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/runtime/store/RoomRuntimeStore.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/kernel/KernelCommandProcessor.kt`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeInputProcessorTest.kt`

- [ ] Write RED tests for claim→consume→typed transition→projection/result→delete, rollback preservation, duplicate scan, illegal command fail-closed, and file reopen recovery.
- [ ] Verify RED.
- [ ] Implement minimal processor and single transaction; unsupported actions fail closed without domain facts.
- [ ] Verify focused tests GREEN.

### Task 3: Hilt flag and backup exclusion evidence

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/di/AgentDataModule.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Test: `app/src/androidTest/java/com/zhiban/rebuild/runtime/store/RuntimeInputProcessorTest.kt`

- [ ] Write RED checks for allowBackup=false and explicit database-domain exclusions.
- [ ] Add Hilt bindings, mutually exclusive feature flag, and backup/data extraction rules.
- [ ] Run migration, focused, Debug/Release, full instrumentation, and diff checks.
- [ ] Commit independently and hand off to architecture/frontend/test.
