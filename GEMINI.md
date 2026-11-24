# TRAISms Project Rules

## Default Agent Mode: `/swarm` (Mandatory Swarm-First Coordination)

For this repository (`TRAISms`), **`/swarm` is the default mode for all agents** on any non-trivial task (feature implementation, multi-file edits, refactors, bug investigations, UI changes, tests, or release work) — even when the user does not explicitly prefix their message with `/swarm`:

1. **Mandatory First Step — Load `.agents/skills/swarm-default/SKILL.md` & `swarm_pane`:**
   - At the start of every non-trivial task, you **MUST** read `.agents/skills/swarm-default/SKILL.md` and the built-in `swarm_pane` skill before making edits.
   - Follow `.agents/skills/swarm-default/SKILL.md` to initialize the Swarm immediately with `swarm(action="title", ...)` and spawn named workers via `swarm(action="add", ...)`.
   - Split the task into non-overlapping pieces owned by named workers (e.g., `@classifier`, `@ui`, `@crypto`, `@tests`, `@docs-release`).
   - Give workers editing or building concurrently their own working copy (`workspace: "share"`); keep read-only investigators and verifiers on `workspace: "inherit"`.
   - Write the swarm overview plan to `<appData>/brain/<conversation-id>/.system_generated/swarm/plan.json` as documented in `swarm_pane`.
   - Always include a `@verifier` worker to review diffs, verify Android/security invariants, and check tests before reporting completion.
   - Keep all coordination on the shared Swarm board (`@name` / `@all` mentions), land `workspace: "share"` worker commits into your working copy before killing workers, and summarize progress cleanly for the user.

2. **When to skip `/swarm`:**
   - Pure Q&A, quick status checks, single-command lookups, or tiny single-file/one-line edits where spawning workers adds overhead with no parallelism or verification benefit.
   - Whenever the user explicitly asks to work directly without a swarm.

## TRAISms Core Project Invariants (Apply to All Workers)

- **100% Offline Guarantee:** Never introduce `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`. Keep both explicitly stripped with `tools:node="remove"` in `app/src/main/AndroidManifest.xml`.
- **Unified Versioning:** `version.properties` (`VERSION_NAME` and `VERSION_CODE`) is the single source of truth used by `app/build.gradle.kts`, the in-app **Settings → About TRAISms** card, and `.github/workflows/release-apk.yml`. Bump `version.properties` whenever cutting a new release so the APK metadata, APK filename, and GitHub Release tag stay in sync.
- **Signing Configs:** Do not remove or break the `release` and `debugConfig` signing configs in `app/build.gradle.kts`.
