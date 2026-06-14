# Conservative Student Profile Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow every non-clarification ReAct request to propose profile updates while rejecting updates that lack explicit evidence in the current user message.

**Architecture:** The router exposes `updateStudentProfile` only to ReAct runs. The ReAct engine passes the trusted current user message through `ToolInvokeContext`; the profile tool validates evidence, confidence, reason, and dimension-specific self-report signals before calling the existing incremental profile service. Prompt instructions guide the model, while server-side validation remains authoritative.

**Tech Stack:** Java 21, Spring Boot, LangChain4j, JUnit 5, Mockito, Maven

---

### Task 1: Route profile updates to non-clarification ReAct runs

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/route/IntentRouteService.java`
- Test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/route/IntentRouteServiceTest.java`

- [ ] **Step 1: Write failing routing tests**

Add assertions showing that knowledge QA, problem help, diagrams, progress review,
task management, and the default ReAct fallback include `updateStudentProfile`.
Add assertions showing that planning, resource generation, and clarification do not.

- [ ] **Step 2: Run routing tests and verify RED**

Run:

```powershell
mvn -pl smartorder-agent -am -Dtest=IntentRouteServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failures because existing ReAct tool lists do not include
`updateStudentProfile`.

- [ ] **Step 3: Add one post-routing tool policy**

After slot validation and clarification handling, append
`updateStudentProfile` when `runMode == REACT` and
`source != CLARIFICATION`. Copy the existing immutable list into a mutable list,
avoid duplicates, and assign it back to the route result.

- [ ] **Step 4: Run routing tests and verify GREEN**

Run the Task 1 Maven command again. Expected: all routing tests pass.

### Task 2: Pass trusted current-message evidence to tools

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/ToolInvokeContext.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/SelfManagedReActEngine.java`

- [ ] **Step 1: Extend the tool context**

Add:

```java
private String currentUserMessage;
```

to `ToolInvokeContext`.

- [ ] **Step 2: Populate it in the main ReAct engine**

When building `ToolInvokeContext`, add:

```java
.currentUserMessage(request.getMessage())
```

Do not accept this value from LLM arguments.

- [ ] **Step 3: Compile the agent module**

Run:

```powershell
mvn -pl smartorder-agent -am -DskipTests compile
```

Expected: build succeeds.

### Task 3: Enforce conservative evidence validation

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileTool.java`
- Create: `smartorder-agent/src/test/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileToolTest.java`

- [ ] **Step 1: Write failing tool tests**

Cover:

```text
explicit weakness evidence -> one updateProfileDimension call
two valid dimensions -> one updateProfileDimensions call
evidence absent from current message -> rejected, zero service calls
confidence below 0.8 -> rejected, zero service calls
"解释一下死锁" -> rejected as weakness evidence
"这次用流程图解释" -> rejected as preference evidence
blank reason -> rejected
unsupported dimension -> rejected
```

- [ ] **Step 2: Run tool tests and verify RED**

Run:

```powershell
mvn -pl smartorder-agent -am -Dtest=UpdateStudentProfileToolTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation or assertions fail because `evidence`, `confidence`,
and trusted-message validation do not exist.

- [ ] **Step 3: Make request types deserializable and testable**

Change the request DTO to mutable fields with a no-argument constructor:

```java
@Data
public static class UpdateStudentProfileRequest {
    private List<DimensionUpdate> dimensionUpdate;
}

@Data
public static class DimensionUpdate {
    private String dimensionKey;
    private Map<String, Object> delta;
    private String evidence;
    private Double confidence;
    private String reason;
}
```

- [ ] **Step 4: Add atomic validation before service calls**

Validate every item before constructing service DTOs:

```text
current message present
supported dimension
non-empty delta
non-empty evidence contained verbatim in current message
confidence in [0.8, 1.0]
non-empty reason
dimension-specific explicit self-report signal present in evidence
```

If any item fails, return:

```java
Map.of(
    "success", false,
    "rejected", true,
    "error", validationMessage
)
```

and do not call the profile service.

- [ ] **Step 5: Run tool tests and verify GREEN**

Run the Task 3 Maven command again. Expected: all tool tests pass.

### Task 4: Align the model prompt with server policy

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/memory/ConversationMemoryService.java`

- [ ] **Step 1: Add conservative update rules**

State that:

```text
ordinary questions and one-off format requests are not profile evidence
evidence must be copied verbatim from the current user message
confidence must be at least 0.8
insufficient evidence means do not call the update tool
multiple valid dimensions should be sent in one call
```

- [ ] **Step 2: Run focused agent tests**

Run:

```powershell
mvn -pl smartorder-agent -am -Dtest=IntentRouteServiceTest,UpdateStudentProfileToolTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all focused tests pass.

### Task 5: Verify the full change

**Files:**
- Verify all modified files above.

- [ ] **Step 1: Run all agent tests**

```powershell
mvn -pl smartorder-agent -am -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Check the diff**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only the planned implementation, tests, plan,
and the previously untracked profile tool are present.

- [ ] **Step 3: Request code review**

Review the implementation against
`docs/superpowers/specs/2026-06-10-conservative-student-profile-update-design.md`,
then address all critical or important findings before completion.
