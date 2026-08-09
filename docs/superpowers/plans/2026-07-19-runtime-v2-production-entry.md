# Runtime v2 Unique Production Entry Implementation Plan

> **For agentic workers:** Execute inline with TDD; do not modify legacy Room data or destructive migrations.

**Goal:** Make the real Debug and Release conversation UI use only Runtime v2 Gateway/Kernel/Projection, while retaining the runtime flag solely as a fail-closed write gate.

**Architecture:** `AgentConversationViewModel` owns one `V2AgentConversationBackend` and has no legacy `AgentRuntime` dependency or branch. Release and Debug both compile with Runtime v2 enabled; a disabled gate rejects staging/commands without falling back or writing legacy state. Legacy runtime classes remain test/migration compatibility code but have no production Hilt binding or UI caller.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, coroutines/StateFlow, Gradle BuildConfig, JUnit, Room instrumentation.

## Global Constraints

- No destructive Room migration and no dual write.
- UI must not call Provider, Tool, Room DAO, legacy `AgentRuntime`, or `DeterministicSchedulePlanner`.
- Flag OFF is fail-closed and creates zero new Runtime v2 or legacy writes.
- Debug and Release both build and route the production UI through Runtime v2.

### Task 1: Lock the production boundary with failing tests

**Files:**
- Modify: `app/src/test/java/com/zhiban/rebuild/ui/agent/AgentConversationViewModelTest.kt`
- Create: `app/src/test/java/com/zhiban/rebuild/ui/agent/ProductionRuntimeBoundaryTest.kt`

- [ ] Assert the ViewModel constructor and source contain no legacy `AgentRuntime`, feature branch, `plan/confirm/recover` calls, or old run ID persistence.
- [ ] Assert Debug and Release BuildConfig both enable v2 while the SPI flag remains available for fail-closed tests.
- [ ] Run focused tests and verify RED against the current dual-path implementation.

### Task 2: Remove the legacy production path

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/ui/agent/AgentConversationViewModel.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/di/AgentDataModule.kt`
- Modify: `app/build.gradle.kts`

- [ ] Remove legacy runtime, snapshot mapping, legacy active-run persistence and conditional routing from the ViewModel.
- [ ] Always initialize/dispatch through `V2AgentConversationBackend`.
- [ ] Remove the Hilt `AgentRuntime` provider; keep legacy classes only for migration/tests.
- [ ] Set Runtime v2 enabled in both Debug and Release; OFF remains injectable only in lower-level tests and fails closed.
- [ ] Run focused tests and verify GREEN.

### Task 3: Verify reverse reachability and recovery

**Files:**
- Modify: relevant ViewModel/Gateway unit tests only if gaps are found.

- [ ] Run Debug/Release unit tests and both assemblies.
- [ ] Run reverse scans proving production UI/DI have no legacy runtime/planner call path.
- [ ] Install Release or minified-equivalent artifact and exercise the real conversation entry, start, kill/relaunch catch-up, cancel/retry/approval where the active state allows.
- [ ] Record evidence and commit only #t25 files.

