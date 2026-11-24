---
name: swarm-default
description: >-
  Default Swarm-first agent execution workflow for the TRAISms repository.
  MUST be loaded and followed automatically at the start of every non-trivial task
  in TRAISms (feature work, bug fixes, refactors, UI edits, tests, or release prep),
  even when the user did not explicitly type /swarm.
---

# TRAISms Default `/swarm` Coordinator Skill

In `TRAISms`, `/swarm` is the default agent execution mode for all non-trivial tasks. Reading this skill explicitly authorizes and instructs you to call the `swarm` tool to coordinate named workers on the shared Swarm board.

## Mandatory Coordinator Procedure

1. **Load `swarm_pane` and Title the Swarm:**
   - Read the built-in `swarm_pane` skill (`/home/dastoor/.gemini/antigravity/builtin/skills/swarm_pane/SKILL.md`) before adding workers.
   - Call `swarm(action="title", title="<4-8 word goal>")` to name the swarm in the UI.

2. **Split Work Cleanly Across Named Workers:**
   - Divide the task into non-overlapping slices owned by named workers (for example: `@classifier`, `@notifications`, `@ui`, `@crypto`, `@tests`, `@docs-release`).
   - Use `workspace="share"` for any worker that edits files or builds concurrently so workers never collide in the same working copy.
   - Use `workspace="inherit"` for read-only investigators, `@verifier`, and `@monitor`.
   - Leave `model` unset unless the user explicitly asks for another model.

3. **Always Include Verification (and Monitoring for Multi-Worker Waves):**
   - Always spawn a `@verifier` worker (`workspace="inherit"`) whose brief checks diffs, tests, version sync, and all TRAISms Core Invariants (`INTERNET` & `ACCESS_NETWORK_STATE` `tools:node="remove"`, `version.properties` single source of truth, and `release`/`debugConfig` signing configs).
   - For multi-worker swarms, you may also add a `@monitor` worker (`workspace="inherit"`) whose brief tells it to load `swarm_monitor`.

4. **Write the Swarm Overview Plan (`plan.json`):**
   - Immediately after adding workers, write `<appData>/brain/<conversation-id>/.system_generated/swarm/plan.json` following the `swarm_pane` schema (`goal`, `tasks`, `threads`).
   - Update `plan.json` whenever workers are added or task assignments change.

5. **Land Work & Clean Up Workers:**
   - Wait on the shared board (call no polling loops; board posts wake you automatically).
   - Before calling `swarm(action="kill", name="<worker>")` on any `workspace="share"` worker, merge or cherry-pick its committed changes into the coordinator workspace first, because killing a `share` worker deletes its working copy.
   - Once `@verifier` signs off and changes are landed in `main`, kill finished workers cleanly and report a concise summary to the user.

## When to Skip Starting a Swarm

- Pure Q&A, quick status checks, single-command lookups, or tiny one-line edits where spawning workers adds overhead with no parallelism or verification benefit.
- Whenever the user explicitly instructs you to work directly without a swarm.
