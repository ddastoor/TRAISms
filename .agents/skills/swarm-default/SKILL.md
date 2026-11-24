---
name: swarm-default
description: >-
  Mandatory default /swarm coordinator workflow and non-interrupting prompt resumption
  protocol for TRAISms. MUST be loaded and followed on every non-trivial prompt in TRAISms,
  even when the user did not explicitly type /swarm, so immediate new prompts never stop
  or drop previous in-progress work.
---

# TRAISms Default `/swarm` Coordinator & Non-Interrupting Resumption Skill

In `TRAISms`, `/swarm` is the mandatory default execution mode for **every new prompt** on non-trivial tasks. Reading this skill explicitly authorizes and instructs you to call the `swarm` tool (`status`, `title`, `add`, `kill`, `model`) and board tools (`read_board`, `post_message`, `swarm_wait`).

## Core Guarantee: Immediate Prompts NEVER Stop Previous Work

When the user sends a new prompt immediately (interrupting a turn rather than queuing):
- **Swarm workers run in their own independent conversations and `share` worktrees**, so coordinator turn interrupts do not terminate active workers.
- **You MUST keep all previous unfinished work running and resume anything that was mid-dispatch or paused** alongside dispatching the new prompt.

## Mandatory Step-by-Step Coordinator Protocol (Every Prompt)

### 1. Check & Resume Existing / Interrupted Work First
At the start of **every** new prompt in a conversation:
1. If this conversation already has an active swarm, immediately call `swarm(action="status")` and `read_board(since=<last_seen_seq>)` in parallel (plus `swarm_pane` if not yet loaded).
2. Audit the conversation history and board state for **any unfinished work from previous user prompts**:
   - **Active (`working`) workers:** Leave them running untouched. **NEVER** `kill` an in-progress worker unless the user explicitly asks to cancel that task.
   - **Paused / `stopped` / `waiting` workers with unfinished tasks:** Immediately revive/continue them via `post_message` (`@<worker> ...`) so they finish their assignment.
   - **Interrupted coordinator turns (work from an earlier prompt that was not yet spawned before the new prompt arrived):** Spawn dedicated worker(s) (`swarm(action="add", ...)`) for that unfinished earlier prompt right alongside the worker(s) for the new prompt!

### 2. Act Strictly as Coordinator (Keep Coordinator Turns Fast)
- Do **not** perform heavy multi-file edits, builds, or test runs directly in the coordinator turn when a Swarm worker can execute them asynchronously.
- If no swarm exists yet in this conversation:
  1. Read `swarm_pane` (`/home/dastoor/.gemini/antigravity/builtin/skills/swarm_pane/SKILL.md`).
  2. Call `swarm(action="title", title="<4-8 word goal>")`.
- Dispatch each independent task slice to a named worker via `swarm(action="add", ...)`:
  - **`workspace="share"`** for any worker editing files or building concurrently (e.g., `@classifier`, `@notifications`, `@ui`, `@crypto`, `@tests`, `@docs-release`), so simultaneous prompts/workers operate in isolated git worktrees and never overwrite each other's working tree.
  - **`workspace="inherit"`** for read-only researchers, `@verifier`, and `@monitor`.
  - Leave `model` unset unless the user asks for a specific model.

### 3. Additive Multi-Prompt Planning (`plan.json`) & Verification
- Maintain `<appData>/brain/<conversation-id>/.system_generated/swarm/plan.json` additively:
  - Keep all active/unfinished worker tasks and `threads` from earlier prompts in `plan.json` while adding new worker tasks/threads for the latest prompt.
- Always include a `@verifier` worker (`workspace="inherit"`) to audit diffs, check tests, and verify all **TRAISms Core Invariants** (`INTERNET` & `ACCESS_NETWORK_STATE` `tools:node="remove"`, `version.properties` single source of truth, and `release`/`debugConfig` signing configs) across **every** active prompt thread before work is considered complete.
- Add `@monitor` (`workspace="inherit"`, instructed to load `swarm_monitor`) on multi-worker waves so stalled threads are automatically nudged.

### 4. Safe Landing & Clean Completion
- Reply concisely to the user after dispatching, explicitly confirming:
  1. Which worker(s) picked up the new prompt, and
  2. Which existing worker(s) are continuing/resuming previous in-progress work without interruption,
  ending with `[Open the Swarm UI](sidecar://swarm/swarm_board/?auto_open=false)` on its own line.
- When board messages wake you:
  - Always merge/cherry-pick a `workspace="share"` worker's committed branch/SHA into your working copy **before** calling `swarm(action="kill", name="<worker>")` (killing deletes its worktree).
  - Never push to a remote unless the user asks.
  - Only retire workers whose full assignment has been verified and landed.

## Narrow Exception (When Not to Spawn Workers)
- Skip spawning new workers **only** when the user explicitly asks not to use `/swarm`, or for a pure 1-turn factual question with zero file edits — **while still leaving any already-running Swarm workers from prior prompts untouched!**
