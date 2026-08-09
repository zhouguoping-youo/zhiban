# Runtime v2 UI Projection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace direct legacy runtime calls with a versioned Runtime Command/Event projection layer that supports deterministic replay, command conflict handling, pending user operations, and existing Compose UI compatibility.

**Architecture:** Add immutable UI-facing Runtime SPI types in a separate file, a centralized pure reducer that owns sequence/revision projection, and a `RuntimeUiClient` port consumed by the ViewModel. Use a test fake until the Room/EventStore implementation is available; Compose never reads Kernel/store/DAO directly.

**Tech Stack:** Kotlin, Coroutines Flow, Android ViewModel, JUnit4, existing Compose UI.

## Global Constraints

- Runtime v2 ADR-002 is authoritative.
- UI sends only Runtime Command and consumes AgentEvent/projection.
- `lastAppliedSequence` and revision are mandatory replay/CAS boundaries.
- Unknown schema is read-only; PendingUserOperation is durable and fail-closed.
- No Provider, Tool, Room DAO, credential, permission, or exported-component access from UI.

---

### Task 1: UI Runtime contracts and deterministic reducer

**Files:**
- Create: `app/src/main/java/com/zhiban/rebuild/runtime/spi/RuntimeUiContracts.kt`
- Create: `app/src/main/java/com/zhiban/rebuild/ui/agent/projection/AgentSessionReducer.kt`
- Test: `app/src/test/java/com/zhiban/rebuild/ui/agent/projection/AgentSessionReducerTest.kt`

**Interfaces:**
- Produces: `RuntimeUiCommand`, `RuntimeUiEvent`, `CommandReceipt`, `SessionProjection`, `RuntimeUiClient`, and `AgentSessionReducer.reduce`.

- [ ] Write RED tests for monotonic replay, duplicate delta suppression, command conflict projection, approvals, budgets/sources, unknown schema, and pending user operation lifecycle.
- [ ] Run the focused test and verify failures are caused by missing contracts/reducer.
- [ ] Add minimal immutable contracts and pure reducer.
- [ ] Run focused tests and refactor while green.
- [ ] Commit the independently reviewable projection core.

### Task 2: ViewModel command adapter and recovery subscription

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/ui/agent/AgentConversationViewModel.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/ui/agent/AgentConversationUiState.kt`
- Create: `app/src/test/java/com/zhiban/rebuild/ui/agent/AgentConversationViewModelV2Test.kt`

**Interfaces:**
- Consumes: `RuntimeUiClient.dispatch`, `getSessionProjection`, and `observeSession(afterSequenceExclusive)`.
- Produces: existing `StateFlow<AgentConversationUiState>` without direct `AgentRuntime` calls.

- [ ] Write RED tests for start/approve/reject/cancel/retry/resume, duplicate click receipts, gap-free recovery, and projection-to-existing-UI mapping.
- [ ] Verify RED, then implement the minimal adapter using the SPI fake.
- [ ] Verify focused and existing UI tests.
- [ ] Commit the ViewModel migration.

### Task 3: Pending user operation wiring and regression gate

**Files:**
- Modify: `app/src/main/java/com/zhiban/rebuild/ui/agent/AgentConversationRoute.kt`
- Modify: `app/src/main/java/com/zhiban/rebuild/ui/agent/AgentConversationUiState.kt`
- Test: `app/src/test/java/com/zhiban/rebuild/ui/agent/AgentConversationUiStateTest.kt`

**Interfaces:**
- Consumes: projected pending operation request IDs.
- Produces: completed/cancelled/expired result Commands; no direct runtime/store mutation.

- [ ] Write RED tests for permission/picker cancellation, expiry, process recovery, and one-result-per-request behavior.
- [ ] Implement Activity-result bridging to Runtime Commands.
- [ ] Run unit tests plus Debug/Release builds and verify existing Home→问问 behavior.
- [ ] Commit, attach review context, and hand off to architecture/testing.
