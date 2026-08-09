# Agent Room migration and rollback

## Baseline

- Database: `zhiban-agent.db`
- Current schema: v1
- Exported schema: `app/schemas/com.zhiban.rebuild.data.agent.AgentDatabase/1.json`
- This is the first database version; there is no legacy Room schema to migrate from.

Production uses Room's default strict migration behavior. Destructive fallback is intentionally not enabled. A future schema bump must add a concrete `Migration`, preserve the exported schemas, and pass `MigrationTestHelper` validation before release.

## Rollback

- Before the first production release, a failed debug-only v1 install may be reset by uninstalling the debug application.
- After a production release contains v1, downgrading to code that cannot read v1 is blocked. Roll back UI/runtime first and keep the database read-only.
- Data deletion is never used as a release rollback mechanism.
- Conversation cleanup is a separate transaction: scrub audit result bodies, remove run summaries, optionally remove explicit preferences/schedules, then delete the run. FK actions retain schedules, explicit preferences, and body-free security audits unless the user explicitly selects their deletion.

## Required evidence for v2+

1. Exported before/after schemas.
2. Forward migration instrumentation test.
3. FK, index, and nullability assertions.
4. Application build compatibility matrix.
5. A documented safe code rollback or a forward-fix release; never destructive downgrade.
