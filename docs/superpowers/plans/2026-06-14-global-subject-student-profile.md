# Global And Subject Student Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy single-table student profile backend with explicit global and per-subject profile models matching the latest SQL, then migrate Agent profile reads and writes to the new dimensions and trusted subject context.

**Architecture:** `student_global_profile` and `student_subject_profile` get separate entities and mappers, while `IStudentProfileService` remains the business-facing boundary with explicit global/subject methods. Agent requests carry an optional host-provided `subjectId`; profile extraction stays asynchronous, but scope validation and all database writes remain authoritative on the server.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus 3.5, Jackson JSON type handlers, JUnit 5, Mockito, Maven

---

## Execution Notes

- The worktree already contains unrelated and overlapping user changes, including profile JSON templates and Agent profile wording. Do not reset, checkout, or overwrite them.
- Before each task, run `git status --short` and inspect the current diff of every file being modified.
- Commit only files belonging to the completed task. Do not include the staged deletion of `AgentOrchestratorImplTest.java` until Task 3 deliberately recreates the test for the current constructor.
- Run Maven from `D:\develop\code\smartorder\smartorder-backend`.
- In PowerShell, quote `-Dtest=...` and `-Dsurefire.failIfNoSpecifiedTests=false`.

## File Structure

### Student module

- Create `smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentGlobalProfile.java`: exact mapping of `student_global_profile`.
- Create `smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentSubjectProfile.java`: exact mapping of `student_subject_profile`.
- Create `smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentGlobalProfileMapper.java`: global profile persistence.
- Create `smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentSubjectProfileMapper.java`: subject profile persistence.
- Replace `smartorder-student/src/main/java/com/smartorder/edu/student/service/IStudentProfileService.java`: explicit global/subject contract.
- Replace `smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImpl.java`: scoped lookup, creation, merge, validation, transaction, and optimistic retry.
- Modify `smartorder-student/src/main/java/com/smartorder/edu/student/listener/UserRegisteredEventListener.java`: create only the global profile.
- Replace `smartorder-student/src/main/java/com/smartorder/edu/student/controller/StudentProfileController.java`: new global and subject endpoints.
- Delete legacy `StudentProfile.java` and `StudentProfileMapper.java` after all references move.

### Common module

- Modify `smartorder-common/src/main/java/com/smartorder/edu/common/config/MPConfig.java`: enable MyBatis-Plus optimistic locking.

### Agent module

- Modify `AgentChatRequest`, `AgentRuntimeContext`, and `ToolInvokeContext`: trusted optional subject context.
- Modify `AgentOrchestratorImpl`, `SelfManagedReActEngine`, `PlanAndExecuteEngine`, and `ProfileUpdateCoordinator`: propagate subject context and preserve asynchronous cross-mode updates.
- Replace `GetStudentProfileTool` and `UpdateStudentProfileTool`: new read bundle, scope validation, and new dimension names.
- Modify `LlmProfileUpdateCandidateExtractor` and `ProfileUpdateCandidateExtractor`: scope-aware candidate extraction.
- Create `ProfileCompletenessEvaluator`: testable planning-profile completeness logic.
- Modify `prompts/system/main-agent.md`: new dimensions and scope rules.

## Task 1: Add Exact Database Models And Optimistic Locking

**Files:**
- Create: `smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentGlobalProfile.java`
- Create: `smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentSubjectProfile.java`
- Create: `smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentGlobalProfileMapper.java`
- Create: `smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentSubjectProfileMapper.java`
- Modify: `smartorder-common/src/main/java/com/smartorder/edu/common/config/MPConfig.java`
- Test: `smartorder-student/src/test/java/com/smartorder/edu/student/domain/entity/StudentProfileMappingTest.java`

- [ ] **Step 1: Write the failing mapping test**

```java
class StudentProfileMappingTest {

    @Test
    void mapsGlobalAndSubjectProfilesToNewTables() throws Exception {
        assertEquals("student_global_profile",
                StudentGlobalProfile.class.getAnnotation(TableName.class).value());
        assertEquals("student_subject_profile",
                StudentSubjectProfile.class.getAnnotation(TableName.class).value());
        assertNotNull(StudentGlobalProfile.class
                .getDeclaredField("profileVersion").getAnnotation(Version.class));
        assertNotNull(StudentSubjectProfile.class
                .getDeclaredField("profileVersion").getAnnotation(Version.class));
        assertNotNull(StudentSubjectProfile.class.getDeclaredField("knowledgeMastery"));
        assertNotNull(StudentGlobalProfile.class.getDeclaredField("subjectMasteryOverview"));

        List<InnerInterceptor> interceptors =
                new MPConfig().pmInterceptor().getInterceptors();
        assertTrue(interceptors.stream()
                .anyMatch(OptimisticLockerInnerInterceptor.class::isInstance));
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn -pl smartorder-student -am "-Dtest=StudentProfileMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation fails because the two new entity classes do not exist.

- [ ] **Step 3: Implement the two entities**

Use the exact SQL columns. Every JSON field must use:

```java
@TableField(typeHandler = JacksonTypeHandler.class)
private Map<String, Object> learningBackground;
```

Both version fields must use:

```java
@Version
private Integer profileVersion;
```

Use:

```java
@Data
@NoArgsConstructor
@TableName(value = "student_global_profile", autoResultMap = true)
public class StudentGlobalProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    @Version
    private Integer profileVersion;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningBackground;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningGoals;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> subjectMasteryOverview;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> misconceptionProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningPreference;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metacognitiveProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningBehavior;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> currentLearningState;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dimensionMetadata;
    private String profileSummary;
    private BigDecimal profileConfidenceScore;
    private BigDecimal profileQualityScore;
    private String lastUpdateReason;
    private LocalDateTime lastAggregatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Data
@NoArgsConstructor
@TableName(value = "student_subject_profile", autoResultMap = true)
public class StudentSubjectProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long subjectId;
    @Version
    private Integer profileVersion;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningBackground;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningGoals;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> knowledgeMastery;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> abilityProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> misconceptionProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningPreference;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metacognitiveProfile;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> learningBehavior;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> currentLearningState;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dimensionMetadata;
    private String profileSummary;
    private BigDecimal profileConfidenceScore;
    private BigDecimal profileCompleteness;
    private String lastUpdateReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastAssessedAt;
    private LocalDateTime nextRefreshAt;
}
```

- [ ] **Step 4: Add the two mappers**

```java
@Mapper
public interface StudentGlobalProfileMapper extends BaseMapper<StudentGlobalProfile> {
}

@Mapper
public interface StudentSubjectProfileMapper extends BaseMapper<StudentSubjectProfile> {
}
```

- [ ] **Step 5: Enable optimistic locking**

Add the interceptor before pagination in `MPConfig`:

```java
mpInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
mpInterceptor.addInnerInterceptor(new PaginationInnerInterceptor());
```

- [ ] **Step 6: Run the mapping test and verify GREEN**

Run the command from Step 2.

Expected: `StudentProfileMappingTest` passes.

- [ ] **Step 7: Commit**

```powershell
git add smartorder-common/src/main/java/com/smartorder/edu/common/config/MPConfig.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentGlobalProfile.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentSubjectProfile.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentGlobalProfileMapper.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentSubjectProfileMapper.java `
  smartorder-student/src/test/java/com/smartorder/edu/student/domain/entity/StudentProfileMappingTest.java
git commit -m "feat(student): map global and subject profiles"
```

## Task 2: Replace The Student Profile Service And HTTP Boundary

**Files:**
- Modify: `smartorder-student/src/main/java/com/smartorder/edu/student/service/IStudentProfileService.java`
- Replace: `smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImpl.java`
- Modify: `smartorder-student/src/main/java/com/smartorder/edu/student/listener/UserRegisteredEventListener.java`
- Replace: `smartorder-student/src/main/java/com/smartorder/edu/student/controller/StudentProfileController.java`
- Modify: `smartorder-student/src/main/java/com/smartorder/edu/student/domain/dto/ProfileInitDTO.java`
- Test: `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImplTest.java`
- Modify test: `smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImplNLTest.java`
- Test: `smartorder-student/src/test/java/com/smartorder/edu/student/listener/UserRegisteredEventListenerTest.java`
- Test: `smartorder-student/src/test/java/com/smartorder/edu/student/controller/StudentProfileControllerTest.java`

- [ ] **Step 1: Write failing service, listener, and controller tests**

Service tests must cover first subject creation, invalid scoped dimensions,
recursive Map merge, list replacement, one optimistic-lock retry, ownership
checks, and atomic mixed-scope routing:

```java
@Test
void createsSubjectProfileOnFirstSubjectUpdate() {
    when(subjectMapper.selectOne(any())).thenReturn(null);
    when(subjectMapper.insert(any())).thenReturn(1);

    service.updateSubjectDimensions(
            7L, 101L,
            List.of(new DimensionUpdateDTO(
                    "knowledge_mastery",
                    Map.of("items", List.of(Map.of("knowledgePointId", "kp-1"))),
                    "首次诊断")));

    verify(subjectMapper).insert(argThat(profile ->
            profile.getUserId().equals(7L)
                    && profile.getSubjectId().equals(101L)
                    && profile.getKnowledgeMastery() != null));
}

@Test
void retriesOneOptimisticConflict() {
    when(globalMapper.selectOne(any()))
            .thenReturn(globalProfile(7L, 2), globalProfile(7L, 3));
    when(globalMapper.updateById(any())).thenReturn(0, 1);

    service.updateGlobalDimensions(
            7L,
            List.of(new DimensionUpdateDTO(
                    "learning_goals", Map.of("status", "IN_PROGRESS"), "目标更新")));

    verify(globalMapper, times(2)).updateById(any());
}
```

Registration test:

```java
listener.onUserRegistered(new UserRegisteredEvent(this, 7L));
verify(service).saveGlobalProfile(argThat(profile ->
        profile.getUserId().equals(7L)
                && profile.getProfileVersion() == 1));
verify(service, never()).saveSubjectProfile(any());
```

Controller tests set JWT claims through `ThreadLocalUtils`, then verify all six
new routes and that authenticated `userId` plus path `subjectId` overwrite
request-body values.

- [ ] **Step 2: Run the combined tests and verify RED**

```powershell
mvn -pl smartorder-student -am "-Dtest=StudentProfileServiceImplTest,StudentProfileServiceImplNLTest,UserRegisteredEventListenerTest,StudentProfileControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation fails because the old service, listener, and controller
still use `StudentProfile`.

- [ ] **Step 3: Replace the service contract**

```java
public interface IStudentProfileService {
    StudentGlobalProfile getGlobalProfile(Long userId);
    List<StudentSubjectProfile> listSubjectProfiles(Long userId);
    StudentSubjectProfile getSubjectProfile(Long userId, Long subjectId);
    void saveGlobalProfile(StudentGlobalProfile profile);
    void saveSubjectProfile(StudentSubjectProfile profile);
    void initGlobalProfile(Long userId, ProfileInitDTO dto);
    void updateGlobalDimensions(Long userId, List<DimensionUpdateDTO> updates);
    void updateSubjectDimensions(Long userId, Long subjectId,
                                 List<DimensionUpdateDTO> updates);
    void updateDimensions(Long userId, Long subjectId,
                          List<DimensionUpdateDTO> globalUpdates,
                          List<DimensionUpdateDTO> subjectUpdates);
    String convertProfileToNaturalLanguage(Map<String, Object> profileMap);
    String convertDimensionToNaturalLanguage(
            String dimensionKey, Map<String, Object> dimensionData);
    record DimensionUpdateDTO(
            String dimensionKey, Map<String, Object> delta, String reason) {}
}
```

- [ ] **Step 4: Implement scoped persistence and explicit dimension access**

Use switches, not reflection. Global writable dimensions exclude
`subject_mastery_overview`; subject writable dimensions include
`knowledge_mastery`.

```java
private Map<String, Object> globalDimension(
        StudentGlobalProfile profile, String key) {
    return switch (key) {
        case "learning_background" -> profile.getLearningBackground();
        case "learning_goals" -> profile.getLearningGoals();
        case "ability_profile" -> profile.getAbilityProfile();
        case "misconception_profile" -> profile.getMisconceptionProfile();
        case "learning_preference" -> profile.getLearningPreference();
        case "metacognitive_profile" -> profile.getMetacognitiveProfile();
        case "learning_behavior" -> profile.getLearningBehavior();
        case "current_learning_state" -> profile.getCurrentLearningState();
        default -> throw new IllegalArgumentException(
                "不支持的全局画像维度: " + key);
    };
}
```

Direct updates must load by ID and reject mismatched `userId`; subject updates
must also reject mismatched `subjectId`. Require `profileVersion` for an
existing-row save and throw a conflict when `updateById` returns zero:

```java
StudentSubjectProfile stored = subjectProfileMapper.selectById(profile.getId());
if (stored == null
        || !userId.equals(stored.getUserId())
        || !subjectId.equals(stored.getSubjectId())) {
    throw new IllegalArgumentException("学科画像不存在或不属于当前用户");
}
if (profile.getProfileVersion() == null) {
    throw new IllegalArgumentException("更新画像时必须携带 profileVersion");
}
profile.setUserId(userId);
profile.setSubjectId(subjectId);
if (subjectProfileMapper.updateById(profile) != 1) {
    throw new IllegalStateException("画像版本已变化，请刷新后重试");
}
```

Global saves use the same ownership and version checks, except they only
validate `userId`.

- [ ] **Step 5: Implement one transactional mixed-scope entry**

```java
@Transactional
public void updateDimensions(
        Long userId,
        Long subjectId,
        List<DimensionUpdateDTO> globalUpdates,
        List<DimensionUpdateDTO> subjectUpdates) {
    if (globalUpdates != null && !globalUpdates.isEmpty()) {
        doUpdateGlobalDimensions(userId, globalUpdates);
    }
    if (subjectUpdates != null && !subjectUpdates.isEmpty()) {
        if (subjectId == null || subjectId <= 0) {
            throw new IllegalArgumentException("单科画像更新缺少有效 subjectId");
        }
        doUpdateSubjectDimensions(userId, subjectId, subjectUpdates);
    }
}
```

Each internal update retries exactly once when optimistic update returns zero.
Map values merge recursively; arrays and scalar values replace.

- [ ] **Step 6: Migrate initialization and registration**

Registration initializes every global JSON field to an empty Map, sets version
`1`, confidence and quality to zero, and creates no subject profile.

`initGlobalProfile` splits DTO values:

```java
Map<String, Object> background = nonBlankMap(
        "identityType", dto.getIdentity(),
        "grade", dto.getGrade(),
        "targetDirection", dto.getDirection(),
        "targetDirectionDetail", dto.getDirectionDetail(),
        "currentLevel", dto.getLevel(),
        "currentLevelDetail", dto.getLevelDetail(),
        "selfDescription", dto.getSelfDescription());

Map<String, Object> goal = nonBlankMap(
        "goalType", dto.getGoal(),
        "description", dto.getGoalDetail(),
        "deadlinePreference", dto.getGoalDeadline(),
        "declaredDailyStudyTime", dto.getDailyStudyTime());
if (!goal.isEmpty()) {
    goal.put("status", "NOT_STARTED");
}
profile.setLearningBackground(background);
profile.setLearningGoals(goal.isEmpty()
        ? new LinkedHashMap<>()
        : Map.of("goals", List.of(goal)));
profile.setProfileConfidenceScore(new BigDecimal("30.00"));
profile.setProfileQualityScore(calculateGlobalQuality(profile));
profile.setLastUpdateReason("引导页初始化");
saveGlobalProfile(profile);
```

Add the helpers used above:

```java
private Map<String, Object> nonBlankMap(Object... keyValues) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
        String key = (String) keyValues[index];
        Object value = keyValues[index + 1];
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            values.put(key, value);
        }
    }
    return values;
}

private BigDecimal calculateGlobalQuality(StudentGlobalProfile profile) {
    long populatedDimensions = Stream.of(
                    profile.getLearningBackground(),
                    profile.getLearningGoals(),
                    profile.getAbilityProfile(),
                    profile.getMisconceptionProfile(),
                    profile.getLearningPreference(),
                    profile.getMetacognitiveProfile(),
                    profile.getLearningBehavior(),
                    profile.getCurrentLearningState())
            .filter(value -> value != null && !value.isEmpty())
            .count();
    return BigDecimal.valueOf(populatedDimensions * 100.0 / 8)
            .setScale(2, RoundingMode.HALF_UP);
}
```

- [ ] **Step 7: Replace the controller and natural-language labels**

Expose:

```text
GET  /student-profile/global
PUT  /student-profile/global
POST /student-profile/global/init
GET  /student-profile/subjects
GET  /student-profile/subjects/{subjectId}
PUT  /student-profile/subjects/{subjectId}
```

Natural-language conversion recognizes only the new SQL dimension names:
`learning_background`, `learning_goals`, `subject_mastery_overview`,
`knowledge_mastery`, `ability_profile`, `misconception_profile`,
`learning_preference`, `metacognitive_profile`, `learning_behavior`,
`current_learning_state`, and `dimension_metadata`.

- [ ] **Step 8: Run the combined tests and verify GREEN**

Run the command from Step 2.

Expected: all student profile service, listener, controller, and conversion
tests pass.

- [ ] **Step 9: Commit**

```powershell
git add smartorder-student/src/main/java/com/smartorder/edu/student/service/IStudentProfileService.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImpl.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/listener/UserRegisteredEventListener.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/controller/StudentProfileController.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/domain/dto/ProfileInitDTO.java `
  smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImplTest.java `
  smartorder-student/src/test/java/com/smartorder/edu/student/service/impl/StudentProfileServiceImplNLTest.java `
  smartorder-student/src/test/java/com/smartorder/edu/student/listener/UserRegisteredEventListenerTest.java `
  smartorder-student/src/test/java/com/smartorder/edu/student/controller/StudentProfileControllerTest.java
git commit -m "refactor(student): migrate global and subject profile backend"
```

## Task 3: Restore Cross-Mode Async Updates And Propagate Trusted Subject Context

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/model/dto/AgentChatRequest.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/trace/AgentRuntimeContext.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/ToolInvokeContext.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/AgentOrchestratorImpl.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/SelfManagedReActEngine.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinator.java`
- Test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/orchestrator/AgentOrchestratorImplTest.java`
- Modify test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinatorTest.java`

- [ ] **Step 1: Recreate the failing orchestrator ordering test**

Use the current constructor dependencies, including
`AgentConversationAttachmentService`, and add `ProfileUpdateCoordinator`.
Assert this order:

```java
InOrder order = inOrder(memoryService, profileUpdateCoordinator, routeService);
order.verify(memoryService).saveUserMessage(
        "conversation-1", 7L, message, null);
order.verify(profileUpdateCoordinator).submit(
        7L, 101L, "conversation-1", anyString(), message);
order.verify(routeService).route(any(RouteContext.class));
```

- [ ] **Step 2: Add failing subject-context assertions**

In `ProfileUpdateCoordinatorTest`, capture `ToolInvokeContext` and assert:

```java
assertEquals(101L, context.getSubjectId());
```

Add a ReAct engine test or focused context-factory test that verifies:

```java
assertEquals(request.getSubjectId(), toolContext.getSubjectId());
assertEquals(request.getMessage(), toolContext.getCurrentUserMessage());
```

- [ ] **Step 3: Run tests and verify RED**

```powershell
mvn -pl smartorder-agent -am "-Dtest=AgentOrchestratorImplTest,ProfileUpdateCoordinatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation or verification fails because `subjectId` is not yet propagated and the orchestrator no longer submits profile updates.

- [ ] **Step 4: Add subject context fields**

Reject nonpositive values before building runtime context:

```java
if (request.getSubjectId() != null && request.getSubjectId() <= 0) {
    throw new IllegalArgumentException("subjectId 必须为正数");
}
```

Then add:

```java
private Long subjectId;
```

to `AgentChatRequest`, `AgentRuntimeContext`, and `ToolInvokeContext`.

Do not add `subjectId` to `UpdateStudentProfileRequest`.

- [ ] **Step 5: Restore orchestrator submission**

Inject `ProfileUpdateCoordinator`, then immediately after saving the user
message:

```java
profileUpdateCoordinator.submit(
        request.getUserId(),
        request.getSubjectId(),
        conversationId,
        context.getTraceId(),
        request.getMessage()
);
```

Build runtime context with:

```java
.subjectId(request.getSubjectId())
```

- [ ] **Step 6: Propagate context into tool calls**

In `SelfManagedReActEngine`:

```java
.subjectId(request.getSubjectId())
.currentUserMessage(request.getMessage())
```

In `ProfileUpdateCoordinator`, accept `subjectId` and build:

```java
.subjectId(subjectId)
```

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: both tests pass and ordering remains save, submit, route.

- [ ] **Step 8: Commit**

```powershell
git add smartorder-agent/src/main/java/com/smartorder/edu/agent/model/dto/AgentChatRequest.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/trace/AgentRuntimeContext.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/ToolInvokeContext.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/AgentOrchestratorImpl.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/SelfManagedReActEngine.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinator.java `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/orchestrator/AgentOrchestratorImplTest.java `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinatorTest.java
git commit -m "feat(agent): propagate profile subject context"
```

## Task 4: Migrate Profile Reads And Planning Completeness

**Files:**
- Replace: `smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/impl/GetStudentProfileTool.java`
- Create: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileCompletenessEvaluator.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/PlanAndExecuteEngine.java`
- Test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/tool/impl/GetStudentProfileToolTest.java`
- Test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileCompletenessEvaluatorTest.java`

- [ ] **Step 1: Write failing profile read tests**

```java
@Test
void returnsOnlyGlobalProfileWithoutSubjectContext() {
    when(service.getGlobalProfile(7L)).thenReturn(global);

    Map<String, Object> result = executeWithSubject(null);

    assertSame(global, result.get("globalProfile"));
    assertNull(result.get("subjectProfile"));
    verify(service, never()).getSubjectProfile(anyLong(), anyLong());
}

@Test
void returnsGlobalAndCurrentSubjectProfile() {
    when(service.getGlobalProfile(7L)).thenReturn(global);
    when(service.getSubjectProfile(7L, 101L)).thenReturn(subject);

    Map<String, Object> result = executeWithSubject(101L);

    assertSame(subject, result.get("subjectProfile"));
}
```

- [ ] **Step 2: Write failing completeness tests**

```java
@Test
void requiresClarificationWhenGlobalCoreSectionsAreEmpty() {
    StudentGlobalProfile profile = new StudentGlobalProfile();
    profile.setLearningBackground(Map.of());
    profile.setLearningGoals(Map.of());
    profile.setLearningPreference(Map.of());

    assertTrue(evaluator.needsClarification(profile));
}

@Test
void acceptsPopulatedGlobalProfile() {
    profile.setLearningBackground(Map.of("grade", 2));
    profile.setLearningGoals(Map.of("goals", List.of(Map.of("goalType", "EXAM"))));
    profile.setLearningPreference(Map.of("resourceTypePriority", List.of("TEXT")));
    profile.setProfileConfidenceScore(new BigDecimal("50"));

    assertFalse(evaluator.needsClarification(profile));
}
```

- [ ] **Step 3: Run tests and verify RED**

```powershell
mvn -pl smartorder-agent -am "-Dtest=GetStudentProfileToolTest,ProfileCompletenessEvaluatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: tests fail because reads still return the legacy profile and the evaluator does not exist.

- [ ] **Step 4: Replace the read tool**

Return:

```java
Map<String, Object> result = new LinkedHashMap<>();
result.put("globalProfile", profileService.getGlobalProfile(userId));
result.put("subjectProfile", subjectId == null
        ? null
        : profileService.getSubjectProfile(userId, subjectId));
return result;
```

The request type stays empty. Read both IDs only from `ToolInvokeContext`.

- [ ] **Step 5: Extract completeness evaluation**

Implement:

```java
public boolean needsClarification(StudentGlobalProfile profile) {
    if (profile == null) {
        return true;
    }
    boolean emptyCore = isEmpty(profile.getLearningBackground())
            && isEmpty(profile.getLearningGoals())
            && isEmpty(profile.getLearningPreference());
    if (emptyCore) {
        return true;
    }
    BigDecimal confidence = profile.getProfileConfidenceScore();
    return confidence != null && confidence.compareTo(new BigDecimal("30")) < 0;
}
```

- [ ] **Step 6: Update planning prefetch and child requests**

In `preFetchProfile`, pass:

```java
.subjectId(request.getSubjectId())
```

When creating internal task requests:

```java
taskRequest.setSubjectId(context.getSubjectId());
```

Replace private JSON field inspection with parsed bundle extraction:

```java
JSONObject bundle = JSON.parseObject(profileJson);
StudentGlobalProfile global = bundle.getObject(
        "globalProfile", StudentGlobalProfile.class);
return profileCompletenessEvaluator.needsClarification(global);
```

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: profile read and completeness tests pass.

- [ ] **Step 8: Commit**

```powershell
git add smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/impl/GetStudentProfileTool.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileCompletenessEvaluator.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/orchestrator/PlanAndExecuteEngine.java `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/tool/impl/GetStudentProfileToolTest.java `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileCompletenessEvaluatorTest.java
git commit -m "refactor(agent): read scoped student profiles"
```

## Task 5: Migrate The Validated Profile Update Tool

**Files:**
- Replace: `smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileTool.java`
- Modify test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileToolTest.java`

- [ ] **Step 1: Replace legacy test fixtures with new dimensions**

Add separate tests:

```java
@Test
void writesGlobalLearningGoal() {
    DimensionUpdate update = update(
            "GLOBAL",
            "learning_goals",
            Map.of("goals", List.of(Map.of("goalType", "EXAM"))),
            "我准备考研");

    Map<String, Object> result = execute(update, null, "我准备考研");

    assertEquals(true, result.get("success"));
    verify(service).updateDimensions(eq(7L), isNull(), any(), any());
}

@Test
void writesSubjectKnowledgeMasteryWithTrustedSubject() {
    DimensionUpdate update = update(
            "SUBJECT",
            "knowledge_mastery",
            Map.of("items", List.of(Map.of("knowledgePointId", "kp-1"))),
            "我不会链表反转");

    execute(update, 101L, "我不会链表反转");

    verify(service).updateDimensions(eq(7L), eq(101L), any(), any());
}

@Test
void rejectsSubjectUpdateWithoutTrustedSubject() {
    Map<String, Object> result = execute(subjectUpdate(), null, "我不会链表反转");

    assertRejected(result, "subjectId");
    verifyNoInteractions(service);
}
```

Retain and rename tests for:

- evidence not in current message.
- confidence below `0.8`.
- nonfinite confidence.
- ordinary explanation request.
- one-off format preference.
- blank reason.
- unsupported dimensions.
- entire batch rejected when one item is invalid.

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl smartorder-agent -am "-Dtest=UpdateStudentProfileToolTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: tests fail because `scope` and the new service methods do not exist in the tool.

- [ ] **Step 3: Add scope and new dimension whitelists**

```java
private static final Set<String> GLOBAL_DIMENSIONS = Set.of(
        "learning_background",
        "learning_goals",
        "ability_profile",
        "misconception_profile",
        "learning_preference",
        "metacognitive_profile",
        "learning_behavior",
        "current_learning_state"
);

private static final Set<String> SUBJECT_DIMENSIONS = Set.of(
        "learning_background",
        "learning_goals",
        "knowledge_mastery",
        "ability_profile",
        "misconception_profile",
        "learning_preference",
        "metacognitive_profile",
        "learning_behavior",
        "current_learning_state"
);
```

Add to `DimensionUpdate`:

```java
@Description("画像作用域：GLOBAL 或 SUBJECT")
private String scope;
```

- [ ] **Step 4: Validate and partition the whole batch**

Validate every item before writes, then partition:

```java
List<DimensionUpdateDTO> globalUpdates = new ArrayList<>();
List<DimensionUpdateDTO> subjectUpdates = new ArrayList<>();
```

Rules:

- `GLOBAL` uses only `GLOBAL_DIMENSIONS`.
- `SUBJECT` uses only `SUBJECT_DIMENSIONS`.
- `SUBJECT` requires `context.getSubjectId()`.
- `subject_mastery_overview` and `dimension_metadata` are always rejected.
- request parameters cannot contain or override `userId` or `subjectId`.

- [ ] **Step 5: Write through the atomic service method**

```java
profileService.updateDimensions(
        userId,
        context.getSubjectId(),
        globalUpdates,
        subjectUpdates
);
```

The service method owns one MySQL transaction. If subject persistence fails,
the global update rolls back with it. The existing profile executor remains
single-threaded.

- [ ] **Step 6: Update evidence signal keys**

At minimum:

```java
"learning_background" -> identity and stable background phrases
"learning_goals" -> exam, plan, target, deadline phrases
"knowledge_mastery" -> explicit knows/does-not-know phrases
"misconception_profile" -> repeated error and persistent confusion phrases
"learning_preference" -> stable preference phrases
"metacognitive_profile" -> planning, self-monitoring, reflection phrases
"learning_behavior" -> stable routine and efficiency phrases
"current_learning_state" -> current difficulty, anxiety, fatigue, engagement phrases
```

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: all update-tool tests pass.

- [ ] **Step 8: Commit**

```powershell
git add smartorder-agent/src/main/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileTool.java `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/tool/impl/UpdateStudentProfileToolTest.java
git commit -m "refactor(agent): validate scoped profile updates"
```

## Task 6: Make Candidate Extraction Scope-Aware

**Files:**
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCandidateExtractor.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/LlmProfileUpdateCandidateExtractor.java`
- Modify: `smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinator.java`
- Modify: `smartorder-agent/src/main/resources/prompts/system/main-agent.md`
- Modify test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/LlmProfileUpdateCandidateExtractorTest.java`
- Modify test: `smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinatorTest.java`

- [ ] **Step 1: Write failing extraction tests**

```java
@Test
void asksForGlobalScopeWhenNoSubjectContextExists() {
    extractor.extract("我准备考研", false);
    verify(chatModel).chat(contains("只允许输出 GLOBAL"));
}

@Test
void allowsSubjectScopeWhenTrustedSubjectExists() {
    extractor.extract("我不会链表反转", true);
    verify(chatModel).chat(contains("允许输出 GLOBAL 或 SUBJECT"));
}

@Test
void parsesNewLearningGoalDimension() {
    when(chatModel.chat(anyString())).thenReturn("""
        {
          "dimensionUpdate": [{
            "scope": "GLOBAL",
            "dimensionKey": "learning_goals",
            "delta": {"goals": [{"goalType": "EXAM"}]},
            "evidence": "我准备考研",
            "confidence": 0.95,
            "reason": "用户明确表达考试目标"
          }]
        }
        """);

    assertEquals("learning_goals",
            extractor.extract("我准备考研", false)
                    .getDimensionUpdate().get(0).getDimensionKey());
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
mvn -pl smartorder-agent -am "-Dtest=LlmProfileUpdateCandidateExtractorTest,ProfileUpdateCoordinatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation fails because `extract` does not accept subject-context availability.

- [ ] **Step 3: Change the extractor contract**

```java
UpdateStudentProfileRequest extract(
        String currentUserMessage,
        boolean subjectContextAvailable);
```

Call it from the coordinator with:

```java
candidateExtractor.extract(
        currentUserMessage,
        subjectId != null && subjectId > 0);
```

- [ ] **Step 4: Replace the extraction prompt**

The prompt must enumerate only the new dimensions and state:

```text
后端是否提供可信科目上下文：是/否
为“否”时只允许 scope=GLOBAL。
为“是”时可根据证据选择 GLOBAL 或 SUBJECT。
不得输出 subjectId，不得猜测科目 ID。
学习目标写入 learning_goals，不得写入 learning_background。
具体知识点掌握写入 SUBJECT/knowledge_mastery。
跨科目易错模式写入 GLOBAL/misconception_profile；
当前科目的具体错误写入 SUBJECT/misconception_profile。
```

- [ ] **Step 5: Update the main Agent prompt**

Replace old references such as `weakness_profile` and
`preference_profile` with the new dimension names. Explicitly state that
`subjectId` is host-controlled.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: extractor and coordinator tests pass.

- [ ] **Step 7: Commit**

```powershell
git add smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCandidateExtractor.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/LlmProfileUpdateCandidateExtractor.java `
  smartorder-agent/src/main/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinator.java `
  smartorder-agent/src/main/resources/prompts/system/main-agent.md `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/LlmProfileUpdateCandidateExtractorTest.java `
  smartorder-agent/src/test/java/com/smartorder/edu/agent/profile/ProfileUpdateCoordinatorTest.java
git commit -m "refactor(agent): extract scoped profile candidates"
```

## Task 7: Remove The Legacy Model And Run Full Regression

**Files:**
- Delete: `smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentProfile.java`
- Delete: `smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentProfileMapper.java`
- Verify: `smartorder-student/src/main/resources/sql/student_profile.sql`
- Verify all profile-related Java, test, prompt, and documentation references

- [ ] **Step 1: Add a legacy-reference guard test**

Create:

`smartorder-student/src/test/java/com/smartorder/edu/student/domain/entity/LegacyStudentProfileRemovalTest.java`

```java
@Test
void legacyStudentProfileClassIsRemoved() {
    assertThrows(ClassNotFoundException.class, () ->
            Class.forName("com.smartorder.edu.student.domain.entity.StudentProfile"));
}
```

- [ ] **Step 2: Run the guard test and verify RED**

```powershell
mvn -pl smartorder-student -am "-Dtest=LegacyStudentProfileRemovalTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: test fails because the legacy class still exists.

- [ ] **Step 3: Delete old entity and mapper**

Delete only after all production references have moved:

```text
smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentProfile.java
smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentProfileMapper.java
```

- [ ] **Step 4: Search for forbidden legacy references**

Run:

```powershell
Get-ChildItem -Path smartorder-student/src/main/java,smartorder-agent/src/main/java,smartorder-agent/src/main/resources/prompts -Recurse -File -Include *.java,*.md |
  Where-Object { $_.FullName -notmatch '\\target\\' } |
  Select-String -Pattern 'StudentProfileMapper|domain.entity.StudentProfile|basic_profile|knowledge_profile|preference_profile|weakness_profile|full_profile_snapshot'
```

Expected: no production-code or active-prompt matches.

- [ ] **Step 5: Verify the latest SQL is still authoritative**

Confirm it contains only:

```sql
CREATE TABLE `student_subject_profile`
CREATE TABLE `student_global_profile`
```

and does not recreate `student_profile`.

- [ ] **Step 6: Run focused profile regression**

```powershell
mvn -pl smartorder-student,smartorder-agent -am "-Dtest=StudentProfileMappingTest,StudentProfileServiceImplTest,StudentProfileControllerTest,UserRegisteredEventListenerTest,StudentProfileServiceImplNLTest,LegacyStudentProfileRemovalTest,AgentOrchestratorImplTest,ProfileUpdateCoordinatorTest,LlmProfileUpdateCandidateExtractorTest,GetStudentProfileToolTest,ProfileCompletenessEvaluatorTest,UpdateStudentProfileToolTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: all named profile tests pass.

- [ ] **Step 7: Run module regression**

```powershell
mvn -pl smartorder-student,smartorder-agent -am "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: `BUILD SUCCESS`. If unrelated existing tests fail, record them
separately and rerun the focused profile suite to keep feature verification
visible.

- [ ] **Step 8: Check diff hygiene**

```powershell
git diff --check
git status --short
```

Expected:

- no whitespace errors.
- no frontend files modified.
- no accidental deletion of profile JSON templates.
- only intended backend files and pre-existing unrelated user changes remain.

- [ ] **Step 9: Commit**

```powershell
git add smartorder-student/src/main/java/com/smartorder/edu/student/domain/entity/StudentProfile.java `
  smartorder-student/src/main/java/com/smartorder/edu/student/mapper/StudentProfileMapper.java `
  smartorder-student/src/test/java/com/smartorder/edu/student/domain/entity/LegacyStudentProfileRemovalTest.java
git commit -m "refactor(student): remove legacy profile model"
```

## Final Acceptance Criteria

- Backend compiles against `student_global_profile` and
  `student_subject_profile` only.
- No production code maps or queries the old `student_profile` table.
- Registration and initialization create/update the global profile.
- Subject profiles are created only with a trusted positive `subjectId`.
- Global and subject JSON dimensions use the latest SQL names.
- Agent reads return global plus optional current-subject profile.
- Agent updates cannot receive model-generated `userId` or `subjectId`.
- Missing subject context prevents all `SUBJECT` writes.
- Profile extraction remains asynchronous and cannot block the main answer.
- Evidence and confidence validation remain enforced.
- Focused profile tests and the student/agent module suites pass.
- No frontend files are changed.
