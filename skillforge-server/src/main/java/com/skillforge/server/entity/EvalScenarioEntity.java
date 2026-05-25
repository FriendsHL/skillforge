package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "t_eval_scenario")
@EntityListeners(AuditingEntityListener.class)
public class EvalScenarioEntity {

    // EVAL-DATASET-LAYER V1 (V109): closed enum of source_type values. Use these
    // constants to flag scenarios at creation time instead of magic strings.
    // CHECK constraint chk_eval_scenario_source_type enforces at DB layer.
    public static final String SOURCE_TYPE_BENCHMARK = "benchmark";
    public static final String SOURCE_TYPE_SESSION_DERIVED = "session_derived";
    public static final String SOURCE_TYPE_MANUAL = "manual";
    public static final Set<String> ALLOWED_SOURCE_TYPES = Set.of(
            SOURCE_TYPE_BENCHMARK, SOURCE_TYPE_SESSION_DERIVED, SOURCE_TYPE_MANUAL);

    // EVAL-DATASET-LAYER V1 (V109): closed enum of purpose values. Orthogonal
    // to source_type — a benchmark scenario can be used as baseline_anchor or
    // ablation; a session_derived scenario is typically regression.
    public static final String PURPOSE_BASELINE_ANCHOR = "baseline_anchor";
    public static final String PURPOSE_REGRESSION = "regression";
    public static final String PURPOSE_ABLATION = "ablation";
    public static final Set<String> ALLOWED_PURPOSES = Set.of(
            PURPOSE_BASELINE_ANCHOR, PURPOSE_REGRESSION, PURPOSE_ABLATION);

    @Id
    private String id;

    /**
     * EVAL-DATASET-LAYER V1 (V109): nullable so benchmark scenarios (no
     * specific agent owner) are legal. Older session_derived rows keep their
     * non-null value; benchmark/manual rows may leave this null when the
     * scenario is cross-agent.
     */
    @Column(length = 36)
    private String agentId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String category = "session_derived";

    @Column(length = 16)
    private String split = "held_out";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String task;

    @Column(length = 32)
    private String oracleType = "llm_judge";

    @Column(columnDefinition = "TEXT")
    private String oracleExpected;

    @Column(nullable = false, length = 32)
    private String status = "draft";

    @Column(length = 36)
    private String sourceSessionId;

    @Column(columnDefinition = "TEXT")
    private String extractionRationale;

    /**
     * EVAL-V2 M2: JSON-encoded array of {@code {role, content}} for multi-turn cases.
     * NULL means single-turn (use {@link #task} / {@link #oracleExpected} via the legacy path).
     * Stored as TEXT (not JSONB) so we keep parser ownership in the service layer; the
     * Spring-managed {@code ObjectMapper} handles encode/decode with all modules registered.
     */
    @Column(columnDefinition = "TEXT")
    private String conversationTurns;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(length = 64)
    private String parentScenarioId;

    /**
     * V5 EVAL-DYNAMIC-USER-SIM Phase 1.1 (V84): 6 business-semantic fields used
     * by Phase 1.2 UserSimulatorAgent to drive multi-turn trials with persona +
     * business goal context. All nullable — historical scenarios pre-V84 keep
     * them NULL and existing eval flows still work.
     */
    @Column(columnDefinition = "TEXT")
    private String businessGoal;

    @Column(columnDefinition = "TEXT")
    private String successCriteria;

    @Column(columnDefinition = "TEXT")
    private String userPersona;

    @Column(columnDefinition = "TEXT")
    private String userConstraints;

    @Column(columnDefinition = "TEXT")
    private String failureSignals;

    @Column(columnDefinition = "TEXT")
    private String expectedOutcome;

    /**
     * EVAL-DATASET-LAYER V1 (V109): closed enum source_type — disambiguates
     * benchmark / session_derived / manual scenarios for dataset composition
     * + baseline pass-rate heuristics. NOT NULL at the DB layer after V109's
     * back-fill UPDATE for the 6 historical rows.
     */
    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    /**
     * EVAL-DATASET-LAYER V1 (V109): free-form reference to the origin record,
     * e.g. {@code "gaia/lv1/001"}, {@code "session:5f3f1923-..."}, or
     * {@code "manual:user-1/...''}. Nullable — historical rows leave NULL;
     * new benchmark/session_derived/manual scenarios should populate.
     */
    @Column(name = "source_ref", length = 256)
    private String sourceRef;

    /**
     * EVAL-DATASET-LAYER V1 (V109): closed enum purpose — orthogonal to
     * source_type. NOT NULL at DB layer.
     */
    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    /**
     * BEHAVIOR-RULE-AB-EVAL V1 (V114): JSONB array of hint tags used by
     * {@code BehaviorRuleAbEvalService} to split a dataset into "target"
     * (scenarios whose hints overlap the rule's target_trigger_tags) vs
     * "regression" subsets. Empty list (default) = scenario is regression-
     * only. Non-null at the DB layer with default {@code '[]'::jsonb}.
     */
    @Column(name = "rule_trigger_hints", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> ruleTriggerHints = new ArrayList<>();

    /**
     * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117): JSONB array of agent role
     * tags (closed enum, see {@code AgentRoleConstants}). Consumed by
     * {@code BehaviorRuleAbEvalService.runAsync} to compute the target subset
     * (scenarios matching the rule_owner_agent's role) and regression subset
     * (scenarios tagged {@code 'general'}). Non-null at the DB layer with
     * default {@code '[]'::jsonb}; V117 backfills all 49 pre-existing rows
     * via {@code t_agent.name} ILIKE heuristics that mirror
     * {@code AgentRoleResolver} — KEEP THE TWO IN SYNC.
     */
    @Column(name = "applicable_agent_roles", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> applicableAgentRoles = new ArrayList<>();

    @CreatedDate
    private Instant createdAt;

    private Instant reviewedAt;

    public EvalScenarioEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSplit() {
        return split;
    }

    public void setSplit(String split) {
        this.split = split;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getOracleType() {
        return oracleType;
    }

    public void setOracleType(String oracleType) {
        this.oracleType = oracleType;
    }

    public String getOracleExpected() {
        return oracleExpected;
    }

    public void setOracleExpected(String oracleExpected) {
        this.oracleExpected = oracleExpected;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getExtractionRationale() {
        return extractionRationale;
    }

    public void setExtractionRationale(String extractionRationale) {
        this.extractionRationale = extractionRationale;
    }

    public String getConversationTurns() {
        return conversationTurns;
    }

    public void setConversationTurns(String conversationTurns) {
        this.conversationTurns = conversationTurns;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getParentScenarioId() {
        return parentScenarioId;
    }

    public void setParentScenarioId(String parentScenarioId) {
        this.parentScenarioId = parentScenarioId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    // V5 EVAL-DYNAMIC-USER-SIM Phase 1.1 (V84) 6 business-semantic fields.

    public String getBusinessGoal() {
        return businessGoal;
    }

    public void setBusinessGoal(String businessGoal) {
        this.businessGoal = businessGoal;
    }

    public String getSuccessCriteria() {
        return successCriteria;
    }

    public void setSuccessCriteria(String successCriteria) {
        this.successCriteria = successCriteria;
    }

    public String getUserPersona() {
        return userPersona;
    }

    public void setUserPersona(String userPersona) {
        this.userPersona = userPersona;
    }

    public String getUserConstraints() {
        return userConstraints;
    }

    public void setUserConstraints(String userConstraints) {
        this.userConstraints = userConstraints;
    }

    public String getFailureSignals() {
        return failureSignals;
    }

    public void setFailureSignals(String failureSignals) {
        this.failureSignals = failureSignals;
    }

    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    public void setExpectedOutcome(String expectedOutcome) {
        this.expectedOutcome = expectedOutcome;
    }

    // EVAL-DATASET-LAYER V1 (V109) getters/setters for source_type/source_ref/purpose.

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    // BEHAVIOR-RULE-AB-EVAL V1 (V114) getter/setter for rule_trigger_hints.

    public List<String> getRuleTriggerHints() {
        return ruleTriggerHints;
    }

    public void setRuleTriggerHints(List<String> ruleTriggerHints) {
        this.ruleTriggerHints = ruleTriggerHints == null ? new ArrayList<>() : ruleTriggerHints;
    }

    // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117) getter/setter for applicable_agent_roles.

    public List<String> getApplicableAgentRoles() {
        return applicableAgentRoles;
    }

    public void setApplicableAgentRoles(List<String> applicableAgentRoles) {
        this.applicableAgentRoles = applicableAgentRoles == null ? new ArrayList<>() : applicableAgentRoles;
    }
}
