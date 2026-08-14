# Prompt Catalog Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Center-managed Prompt installation and deterministic UNIFIED/PRE/POST lifecycle support without polluting shared session context.

**Architecture:** Keep Prompt catalog persistence and artifacts separate from Skill catalog. Relay heartbeats synchronize an atomic, revisioned local Prompt catalog; each task snapshots that catalog. `RemoteCcRelayService` assembles the fixed responsibility prompt, UNIFIED prompts, shared context, and PRE prompts, while a private second runner call handles POST finalization before publication.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5/Mockito, Python 3 CLI, SHA-256.

---

### Task 1: Add Prompt catalog domain and Center APIs

**Files:**
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/entity/AiPromptEntity.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/repository/AiPromptRepository.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/model/PromptType.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/model/PromptCatalogEntry.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/model/PromptCatalogResponse.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/model/PromptCatalogDigestResponse.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/model/PromptInstallResponse.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/service/PromptCatalogService.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/service/PromptCatalogServiceImpl.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagentskill/controller/PromptCatalogController.java`
- Create: `src/test/java/com/webank/wedatasphere/wdsavs/aiagent/service/PromptCatalogServiceImplTest.java`

- [ ] **Step 1: Write failing catalog tests**

Test installation of UTF-8 content, replacement by ID, `type/order/promptId` ordering, digest stability, INVALID tombstones, and rejection of invalid IDs/types/empty content.

- [ ] **Step 2: Run the focused test and verify RED**

Run:
```bash
gradlew test --tests '*PromptCatalogServiceImplTest'
```
Expected: compilation/test failure because Prompt catalog classes do not exist.

- [ ] **Step 3: Implement catalog persistence and artifact safety**

Use an entity equivalent to `AiSkillEntity` with columns `prompt_id`, `type`, `prompt_order`, `sha256`, `status`, `content_path`, `content_size`, `created_at`, and `updated_at`. Store content below `${wdsavs.ai.prompt.directory:./prompts}` and write to a UUID staging file before atomic replacement. Validate ID with `[a-z0-9][a-z0-9-]{0,63}`, type with `PromptType`, non-negative order, UTF-8/no NUL content, and a 1 MiB content limit. Calculate catalog SHA-256 from length-prefixed `promptId/type/order/sha256/status` records sorted by type order and ID.

- [ ] **Step 4: Add Center endpoints and verify GREEN**

Implement `POST /api/prompt/catalog/install` with headers `X-CCRelay-Prompt-Id`, `X-CCRelay-Prompt-Type`, and `X-CCRelay-Prompt-Order`, `GET /api/prompt/catalog`, `GET /digest`, `GET /{promptId}/content`, and `DELETE /{promptId}`. Only ACTIVE records expose content URLs. Run the focused test and the existing Skill catalog tests.

### Task 2: Add Python CLI Prompt commands

**Files:**
- Modify: `codex-skill/ccrelay/scripts/ccrelay_skill.py`
- Modify: `scripts/test_ccrelay_cli.py`

- [ ] **Step 1: Write failing CLI tests**

Add parser and request tests for `prompt install <file> --id --type --order`, `prompt list`, and `prompt remove`; assert UTF-8 bytes are uploaded with the three metadata headers and invalid input fails before HTTP.

- [ ] **Step 2: Run the focused Python tests and verify RED**

Run:
```bash
python scripts/test_ccrelay_cli.py CcRelayCliTest.test_prompt_install_sends_metadata
```
Expected: failure because `prompt` is not registered.

- [ ] **Step 3: Implement CLI commands**

Add `add_prompt`, `install_prompt`, `list_prompts`, and `remove_prompt` beside the existing Skill commands. Use `Path.read_bytes()`, strict UTF-8 validation, the existing `request` helper, and no content logging. Reuse the existing ID regex and add `PROMPT_TYPES` plus integer order validation.

- [ ] **Step 4: Verify CLI GREEN and full Python suite**

Run:
```bash
python scripts/test_ccrelay_cli.py CcRelayCliTest.test_prompt_install_sends_metadata
python scripts/test_ccrelay_cli.py
```

### Task 3: Add local Prompt metadata and heartbeat synchronization

**Files:**
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RelayPromptMetadata.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RelayPromptMetadataStore.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemotePromptSyncCoordinator.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteCcRelayProperties.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteCcRelayServer.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteSkillSyncCoordinator.java`
- Create: `src/test/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemotePromptSyncCoordinatorTest.java`

- [ ] **Step 1: Write failing synchronization tests**

Cover no-center inactive behavior, ACTIVE content download and SHA-256 validation, metadata persistence with `INSTALLING` then `INSTALLED`, INVALID/missing removal, and a failed update retaining the last installed revision.

- [ ] **Step 2: Run focused test and verify RED**

Run:
```bash
gradlew test --tests '*RemotePromptSyncCoordinatorTest'
```
Expected: compilation failure because Prompt sync classes do not exist.

- [ ] **Step 3: Implement atomic local sync**

Persist local metadata as a small JSON file under the configured working directory. Fetch `/api/prompt/catalog/digest`, then `/api/prompt/catalog`; download content to staging, verify exact SHA-256, atomically move it into the configured Prompt directory, and update metadata only after all ACTIVE entries succeed. Keep the previous revision on failure. Mark center INVALID/missing entries INVALID and delete their files after a successful catalog pass.

- [ ] **Step 4: Wire sync into Relay lifecycle and verify GREEN**

Use `promptDirectory` and `promptMetadataPath` properties with defaults under the Relay working directory. Trigger Prompt sync from the existing heartbeat/scheduled lifecycle at the same cadence as Skill sync, but keep the two catalogs independent. Run focused Prompt/Skill sync tests.

### Task 4: Add immutable task Prompt snapshots and UNIFIED context

**Files:**
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/PromptSnapshot.java`
- Create: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/PromptSnapshotProvider.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteSessionContextStateStore.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteCcRelayService.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteCcExecutionRequest.java`
- Modify: `src/test/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteCcRelayServicePromptTest.java`
- Create: `src/test/java/com/webank/wedatasphere/wdsavs/aiagent/remote/PromptSnapshotProviderTest.java`

- [ ] **Step 1: Write failing snapshot/context tests**

Assert deterministic type ordering, one immutable revision per task, no snapshot Prompt changes after catalog mutation, fixed responsibilities before UNIFIED, and no Prompt content in the shared-context payload.

- [ ] **Step 2: Run tests and verify RED**

Run:
```bash
gradlew test --tests '*PromptSnapshotProviderTest' --tests '*RemoteCcRelayServicePromptTest'
```
Expected: compilation/assertion failures for missing snapshot support.

- [ ] **Step 3: Implement snapshot provider and state persistence**

Read only `INSTALLED` local Prompt metadata/content, calculate the immutable revision and three digests, and expose ordered blocks. Extend Session state JSON with `appliedPromptRevision` and `appliedUnifiedDigest`; do not persist PRE/POST into shared context.

- [ ] **Step 4: Assemble task prompt and handle UNIFIED rotation**

At task boundary obtain one snapshot. Add fixed Relay responsibilities first, then UNIFIED, then existing shared final responses, then PRE. If the stored UNIFIED digest differs, create/replay a new model Session and save the revision only after successful Session initialization. Keep title requests on their existing special path without Relay or Prompt injections. Run focused tests.

### Task 5: Implement PRE injection and POST finalization

**Files:**
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/ReactAgentRunner.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/LocalClaudeCodeCommandRunner.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/ClaudeCodeStreamCollector.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/RemoteCcRelayService.java`
- Modify: `src/main/java/com/webank/wedatasphere/wdsavs/aiagent/remote/ReactEventWriter.java`
- Create: `src/test/java/com/webank/wedatasphere/wdsavs/aiagent/remote/ReactAgentRunnerPromptLifecycleTest.java`
- Modify: `src/test/java/com/webank/wedatasphere/wdsavs/aiagent/remote/ClaudeCodeStreamCollectorTest.java`

- [ ] **Step 1: Write failing lifecycle tests**

Test PRE is appended once before the formal ReAct call, the task uses one snapshot across both phases, no-POST uses one runner call, POST uses the same model Session for a second call, finalization disables tools/collaboration, candidate events are suppressed, and finalization failure returns `POST_FINALIZATION_FAILED` without publication.

- [ ] **Step 2: Run focused tests and verify RED**

Run:
```bash
gradlew test --tests '*ReactAgentRunnerPromptLifecycleTest' --tests '*ClaudeCodeStreamCollectorTest'
```
Expected: compilation/assertion failures because lifecycle arguments and draft mode are absent.

- [ ] **Step 3: Implement private lifecycle calls**

Add explicit execution phase/draft flags to `RemoteCcExecutionRequest`. Pass PRE as a private context block before ReAct. Collect the first answer without emitting `AGENT_MESSAGE`/`AGENT_RESULT` or publishing to Center. For ACTIVE POST blocks, append them and call the same Session with `--resume`, `--tools ""`, and collaboration disabled; publish only the second answer. Do not call finalization when POST is empty.

- [ ] **Step 4: Verify lifecycle GREEN**

Run focused lifecycle tests, then the existing ReAct, session runner, stream collector, and remote prompt tests.

### Task 6: Documentation, regression coverage, and release validation

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `codex-skill/ccrelay/SKILL.md`
- Modify: `scripts/test_ccrelay_cli.py`
- Modify: relevant Java tests under `src/test/java`

- [ ] **Step 1: Document Prompt commands and lifecycle**

Add human-readable examples for installing/listing/removing prompts and explain that UNIFIED is a stable prefix, PRE runs before each Agent ReAct, POST performs private finalization, and only the finalized answer enters shared context.

- [ ] **Step 2: Run focused and complete verification**

Run:
```bash
gradlew test --tests '*Prompt*' --tests '*RemoteSkill*' --tests '*ReactAgentRunner*' --tests '*ClaudeCode*'
python scripts/test_ccrelay_cli.py
gradlew test
gradlew build
```

- [ ] **Step 3: Review generated artifacts and worktree**

Run `git diff --check`, inspect `git status --short`, confirm Prompt content is absent from shared-context event serialization, and verify only `v0.1.2-dev` files changed. Commit implementation with a message beginning `#AI commit#`.
