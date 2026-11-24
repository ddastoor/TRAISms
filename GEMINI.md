# TRAISms Project Rules

## Default Agent Mode: `/swarm` on Every Prompt (Zero-Dropped-Work Protocol)

For this repository (`TRAISms`), **`/swarm` is the mandatory default mode for every new prompt** on non-trivial tasks (feature implementation, multi-file edits, refactors, bug investigations, UI changes, tests, or release work) — even when the user does not explicitly prefix their message with `/swarm`:

1. **Mandatory First Step — Load `.agents/skills/swarm-default/SKILL.md` & `swarm_pane`:**
   - At the start of every non-trivial prompt, you **MUST** read `.agents/skills/swarm-default/SKILL.md` and `swarm_pane` (if not already loaded in this conversation), and act strictly as the **Swarm Coordinator** (`swarm(action="title", ...)`, `swarm(action="status")`, `read_board`, `swarm(action="add", ...)`).
   - Delegate all edits, builds, investigations, and verification to named background Swarm workers (`workspace: "share"` for concurrent editors so simultaneous tasks never collide; `workspace: "inherit"` for read-only investigators, `@verifier`, and `@monitor`) so coordinator turns stay fast and non-blocking.

2. **Immediate Prompts Must NEVER Stop or Drop Previous In-Progress Work:**
   - When the user sends a new prompt immediately (interrupting an active turn instead of queuing):
     - **Treat every new prompt as additive** unless the user explicitly cancels an earlier task.
     - **NEVER kill, stop, or abandon active or unfinished Swarm workers** from prior prompts.
     - First inspect `swarm(action="status")`, `read_board`, and prior conversation prompts:
       - Leave `working` workers running untouched.
       - Revive any `stopped` or `waiting` worker with unfinished work via `post_message`.
       - If an earlier coordinator turn was interrupted before its worker(s) were spawned, immediately spawn worker(s) for **both** that unfinished prior prompt and the new prompt in parallel (`workspace: "share"`), keeping all active threads in `<appData>/brain/<conversation-id>/.system_generated/swarm/plan.json`.
   - Always have `@verifier` verify every active thread before completion, and merge `workspace: "share"` worker commits into your working copy **before** calling `swarm(action="kill")`.

3. **Auto-Commit & Auto-Push Every Finished Change:**
   - Going forward, whenever a change is finished and verified by `@verifier`, the coordinator **must** automatically commit all landed changes with an appropriate, descriptive commit message and run `git push` to `origin` automatically **without asking the user** (`workspace="share"` workers commit locally in their worktrees with an appropriate, descriptive commit message without pushing; the coordinator pushes automatically, and if missing or rejected credentials block `git push`, report the exact error clearly).

4. **When to skip spawning new workers:**
   - Pure Q&A, quick status checks, or when the user explicitly asks to work directly without `/swarm` — while still keeping any existing background Swarm workers from previous prompts running uninterrupted.

## TRAISms Core Project Invariants (Apply to All Workers)

- **100% Offline Guarantee:** Never introduce `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`. Keep both explicitly stripped with `tools:node="remove"` in `app/src/main/AndroidManifest.xml`.
- **Unified Versioning:** `version.properties` (`VERSION_NAME` and `VERSION_CODE`) is the single source of truth used by `app/build.gradle.kts`, the in-app **Settings → About TRAISms** card, and `.github/workflows/release-apk.yml`. Bump `version.properties` whenever cutting a new release so the APK metadata, APK filename, and GitHub Release tag stay in sync.
- **Signing Configs:** Do not remove or break the `release` and `debugConfig` signing configs in `app/build.gradle.kts`.
