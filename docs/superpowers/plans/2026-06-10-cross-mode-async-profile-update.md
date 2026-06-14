# Cross-Mode Async Profile Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract and persist explicit student-profile evidence for every agent run mode without adding latency to the main chat response.

**Architecture:** `AgentOrchestratorImpl` submits the current message to a dedicated `ProfileUpdateCoordinator` immediately after persistence. The coordinator uses a cheap local prefilter, runs LLM extraction on a dedicated bounded executor, and delegates final validation and persistence to the existing `UpdateStudentProfileTool`.

**Tech Stack:** Java 21, Spring Boot, LangChain4j, MyBatis-Plus, JUnit 5, Mockito, Maven

---

### Task 1: Define asynchronous coordinator behavior

**Files:**
- Create: `smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinatorTest.java`
- Create: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinator.java`

- [x] Write failing tests proving submission is non-blocking, ordinary questions are skipped, valid candidates reach the existing tool, and rejected executor submissions do not escape.
- [x] Run `mvn -pl smartorder-agent -am -Dtest=ProfileUpdateCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test` and verify RED.
- [x] Implement the minimal coordinator with a dedicated injected `Executor`.
- [x] Run the focused test and verify GREEN.

### Task 2: Extract profile candidates

**Files:**
- Create: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCandidateExtractor.java`
- Create: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/LlmProfileUpdateCandidateExtractor.java`
- Create: `smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/LlmProfileUpdateCandidateExtractorTest.java`

- [x] Write failing tests for strict JSON parsing, fenced JSON parsing, and empty candidates.
- [x] Run the extractor test and verify RED.
- [x] Implement a current-message-only prompt and Fastjson parsing.
- [x] Run the extractor test and verify GREEN.

### Task 3: Wire the cross-mode side path

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/config/AgentExecutorConfig.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/AgentOrchestratorImpl.java`
- Create: `smartorder-agent/src/test/java/com/smartorder/edu/agent/orchestrator/AgentOrchestratorImplTest.java`

- [x] Write a failing ordering test proving message persistence precedes profile submission and profile submission precedes routing.
- [x] Run the orchestrator test and verify RED.
- [x] Add a bounded `profileUpdateExecutor` and submit from the orchestrator.
- [x] Run the orchestrator test and verify GREEN.

### Task 4: Remove ReAct-only profile ownership

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/route/IntentRouteService.java`
- Modify: `smartorder-agent/src/test/java/com/smartorder/edu/agent/route/IntentRouteServiceTest.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/memory/ConversationMemoryService.java`

- [x] Change routing tests to expect original mode-specific tools without `updateStudentProfile`.
- [x] Run routing tests and verify RED.
- [x] Remove `applyReActToolPolicy` and remove profile-update instructions from the main ReAct prompt.
- [x] Run routing tests and verify GREEN.

### Task 5: Cover the user's planning evidence

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileTool.java`
- Modify: `smartorder-agent/src/test/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileToolTest.java`

- [x] Add failing tests for `我准备考研` and `大二的时候没有怎么学会数据结构`.
- [x] Extend basic-goal and weakness evidence signals minimally.
- [x] Run focused profile tests and verify GREEN.
- [x] Run `mvn -pl smartorder-agent -am -Dsurefire.failIfNoSpecifiedTests=false test`.
- [x] Run `git diff --check` and review the final diff.
