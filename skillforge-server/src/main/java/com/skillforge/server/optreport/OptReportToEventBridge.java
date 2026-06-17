package com.skillforge.server.optreport;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.optreport.dto.OptReportIssueDto;
import com.skillforge.server.optreport.dto.OptReportSummaryJson;
import com.skillforge.server.optreport.dto.OptReportSummaryParser;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * OPT-REPORT-V1.2 (2026-05-23): bridge between the new "Generate Report"
 * route and the existing attribution / A-B pipeline.
 *
 * <p>Operator clicks "Convert to Event" on a structured issue in the
 * report detail panel → controller calls {@link #convertIssueToEvent} →
 * we INSERT a new {@code t_optimization_event} row at
 * {@code stage=proposal_pending} carrying back-pointer columns
 * ({@code source_report_id} / {@code source_issue_id}). From there the
 * legacy Pending Approvals queue picks it up like any other curator-
 * authored proposal.
 *
 * <p>Idempotency: the {@code (source_report_id, source_issue_id)} pair
 * is the dedupe key. Re-clicking the button returns the existing event
 * row with {@code alreadyConverted=true}.
 *
 * <p>{@code patternId} is set to {@code null} on report-derived events
 * (V101 ALTER drops NOT NULL). Downstream
 * {@code AttributionApprovalService.dispatchSkillSurface /
 * dispatchPromptSurface} require a non-null patternId at approve time —
 * V1.2 accepts that limitation, documented in {@code index.md §V1.2 §2}.
 * Operator can still see / read / reject the proposal in the dashboard,
 * just can't take it through to A-B until V2 lifts the patternId
 * dependency.
 *
 * <p>{@code other} / {@code unclear} surfaces are rejected at the
 * bridge — the legacy A/B pipeline only accepts {@code skill /
 * prompt / behavior_rule}, and silently inserting a row that can't be
 * actioned would be noise.
 */
@Service
public class OptReportToEventBridge {

    private static final Logger log = LoggerFactory.getLogger(OptReportToEventBridge.class);

    /** Match {@code ProposeOptimizationTool.COOLDOWN_DURATION}. */
    static final Duration COOLDOWN_DURATION = Duration.ofHours(24);

    /** Distinguishes report-derived events from curator-authored ones. */
    static final String CHANGE_TYPE_FROM_REPORT = "from_opt_report";

    private final FlywheelRunRepository reportRepository;
    private final OptimizationEventRepository eventRepository;
    private final OptReportSummaryParser summaryParser;
    private final Clock clock;

    public OptReportToEventBridge(FlywheelRunRepository reportRepository,
                                  OptimizationEventRepository eventRepository,
                                  OptReportSummaryParser summaryParser,
                                  Clock clock) {
        this.reportRepository = reportRepository;
        this.eventRepository = eventRepository;
        this.summaryParser = summaryParser;
        this.clock = clock;
    }

    /**
     * Convert one issue from {@code summary_json.topIssues} into a new
     * {@code t_optimization_event} row. Idempotent on
     * {@code (reportId, issueId)}.
     *
     * @return ({@link OptimizationEventEntity entity}, {@code alreadyConverted}).
     * @throws NoSuchElementException if {@code reportId} doesn't exist
     *                                or no issue with {@code issueId} is
     *                                present in the report's summary.
     * @throws IllegalStateException  if the report is not in
     *                                {@code completed} status.
     * @throws IllegalArgumentException if the issue's
     *                                {@code suspectSurface} is {@code other}
     *                                or {@code unclear} (caller should 400)
     *                                or summary_json is schema-invalid.
     */
    @Transactional
    public ConvertResult convertIssueToEvent(String reportId, String issueId) {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("reportId is required");
        }
        if (issueId == null || issueId.isBlank()) {
            throw new IllegalArgumentException("issueId is required");
        }

        FlywheelRunEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Report not found: id=" + reportId));

        if (!FlywheelRunEntity.STATUS_COMPLETED.equals(report.getStatus())) {
            throw new IllegalStateException(
                    "Report status must be 'completed' to convert issues; got: "
                            + report.getStatus() + " (reportId=" + reportId + ")");
        }

        // Parse summary_json — may throw IllegalArgumentException for
        // schema violations; caller (controller) maps to 400.
        OptReportSummaryJson summary = summaryParser.parse(report.getSummaryJson());
        OptReportIssueDto issue = summary.topIssues().stream()
                .filter(i -> issueId.equals(i.id()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Issue not found in report: reportId=" + reportId
                                + ", issueId=" + issueId));

        // V1.3+: route OptEvent by effectiveSurface (fixSurface || suspectSurface).
        // The LLM may identify "root-cause surface" (where the agent was when
        // it errored, e.g. skill=Bash) but the actual fix lands elsewhere
        // (behavior_rule="加 pwd 检查"). effectiveSurface() gives the fix
        // location. Falls back to suspectSurface for legacy reports / LLM
        // that hasn't split them.
        String effective = issue.effectiveSurface();
        if (!OptReportIssueDto.CONVERTIBLE_SURFACES.contains(effective)) {
            throw new IllegalArgumentException(
                    "Issue effectiveSurface='" + effective
                            + "' (suspectSurface='" + issue.suspectSurface()
                            + "', fixSurface='" + issue.fixSurface()
                            + "') cannot be auto-converted to an OptimizationEvent. "
                            + "Allowed: " + OptReportIssueDto.CONVERTIBLE_SURFACES
                            + ". Operator should write a manual OptEvent for "
                            + "other/unclear surfaces.");
        }

        // Idempotency: short-circuit if we already converted this issue.
        Optional<OptimizationEventEntity> existing = eventRepository
                .findFirstBySourceReportIdAndSourceIssueId(reportId, issueId);
        if (existing.isPresent()) {
            log.info("OptReportToEventBridge.convertIssueToEvent: idempotent return "
                            + "for reportId={} issueId={} eventId={}",
                    reportId, issueId, existing.get().getId());
            return new ConvertResult(existing.get(), true);
        }

        Instant now = clock.instant();
        OptimizationEventEntity event = new OptimizationEventEntity();
        event.setAgentId(report.getAgentId());
        // patternId left null — see class javadoc.
        // V1.3+: surfaceType = effectiveSurface (fixSurface || suspectSurface)
        // so downstream A/B path matches the *fix* location, not the
        // *root-cause* location.
        event.setSurfaceType(effective);
        event.setChangeType(CHANGE_TYPE_FROM_REPORT);
        event.setDescription(buildDescription(issue));
        // expectedImpact column also gets the raw issue.expectedImpact (if
        // any) so the existing dashboard column shows the impact bullet
        // separately from the long-form description.
        if (issue.expectedImpact() != null && !issue.expectedImpact().isBlank()) {
            event.setExpectedImpact(issue.expectedImpact());
        }
        event.setConfidence(BigDecimal.valueOf(issue.confidence())
                .setScale(2, RoundingMode.HALF_UP));
        event.setRisk(mapSeverityToRisk(issue.severity()));
        event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        event.setCooldownExpiresAt(now.plus(COOLDOWN_DURATION));
        event.setSourceReportId(reportId);
        event.setSourceIssueId(issueId);
        // createdAt / updatedAt populated by @PrePersist on the entity.

        OptimizationEventEntity saved = eventRepository.save(event);
        log.info("OptReportToEventBridge.convertIssueToEvent: created eventId={} "
                        + "from reportId={} issueId={} surface={} severity={} confidence={}",
                saved.getId(), reportId, issueId,
                issue.suspectSurface(), issue.severity(), issue.confidence());
        return new ConvertResult(saved, false);
    }

    /**
     * Build a single {@code description} TEXT blob from the structured
     * issue. {@code title} as headline; the body prefers the V1.6 (G4)
     * {@code rootCause} + {@code proposedFix} split (richer than a single
     * line) and falls back to {@code suggestion} for legacy / V1.5 reports
     * that didn't emit the split. {@code expectedImpact} appended only if
     * present (the dedicated {@code expected_impact} column also holds it —
     * duplicating here keeps the dashboard's existing "description-only"
     * tooltips meaningful without joining a second column).
     *
     * <p>All field reads are null-safe: V1.6 demoted {@code suggestion} to
     * optional, so {@code issue.suggestion()} may be null. The parser
     * guarantees at least one of suggestion / rootCause is present, but this
     * method stays defensive in case a DTO is built outside the parser.
     */
    static String buildDescription(OptReportIssueDto issue) {
        StringBuilder sb = new StringBuilder();
        sb.append(issue.title().trim());

        boolean hasRootCause = issue.rootCause() != null && !issue.rootCause().isBlank();
        boolean hasProposedFix = issue.proposedFix() != null && !issue.proposedFix().isBlank();

        if (hasRootCause || hasProposedFix) {
            // V1.6 (G4): prefer rootCause + proposedFix.
            if (hasRootCause) {
                sb.append("\n\nRoot cause: ").append(issue.rootCause().trim());
            }
            if (hasProposedFix) {
                sb.append("\n\nProposed fix: ").append(issue.proposedFix().trim());
            }
        } else if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
            // Legacy / V1.5 reports: single-line suggestion fallback.
            sb.append("\n\n").append(issue.suggestion().trim());
        }

        if (issue.expectedImpact() != null && !issue.expectedImpact().isBlank()) {
            sb.append("\n\nExpected: ").append(issue.expectedImpact().trim());
        }
        return sb.toString();
    }

    /**
     * V1.2: report issue {@code severity} → optimization event {@code risk}
     * column. They use the same {@code high}/{@code medium}/{@code low}
     * vocabulary by design — a 1-1 map keeps the dashboard Risk filter
     * consistent across report-derived and curator-derived events.
     */
    static String mapSeverityToRisk(String severity) {
        return switch (severity) {
            case "high" -> OptimizationEventEntity.RISK_HIGH;
            case "medium" -> OptimizationEventEntity.RISK_MEDIUM;
            case "low" -> OptimizationEventEntity.RISK_LOW;
            // Defensive — parser already enforces enum; this only hits if
            // a caller bypasses the parser. Default to medium (matches
            // the LLM's "moderate confidence" intuition).
            default -> OptimizationEventEntity.RISK_MEDIUM;
        };
    }

    /**
     * Result envelope so the controller can distinguish "newly created"
     * from "already converted" without comparing timestamps.
     */
    public record ConvertResult(OptimizationEventEntity event, boolean alreadyConverted) {}

    /**
     * V1.2 r2 fix (moved from Controller per java-reviewer HIGH): parse
     * {@code summary_json.topIssues} and tag each issue with
     * {@code alreadyConverted: true|false} based on prior
     * {@code t_optimization_event} rows pointing back at the report. Returns
     * an empty list when the report has no V1.2-schema summary (legacy
     * V1.0/V1.1 reports), letting the FE fall back to the markdown-only
     * view.
     *
     * <p>{@code @Transactional(readOnly = true)} per java-reviewer W3 —
     * the single {@code findBySourceReportId} call hits the
     * {@code idx_opt_event_source_report} partial index.
     *
     * <p>Per-issue keys in the returned list mirror {@link OptReportIssueDto}
     * fields, plus the three FE-only fields:
     * <ul>
     *   <li>{@code alreadyConverted: boolean}</li>
     *   <li>{@code convertedEventId: Long | null} (renamed from V1.2 r1's
     *       {@code eventId} to match the FE type contract in
     *       {@code optReport.ts:OptReportIssue})</li>
     *   <li>{@code convertible: boolean} — surface ∈ skill/prompt/behavior_rule</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> enrichTopIssues(String reportId, String summaryJson) {
        OptReportSummaryJson parsed;
        try {
            parsed = summaryParser.parse(summaryJson);
        } catch (IllegalArgumentException e) {
            log.warn("OptReportToEventBridge.enrichTopIssues: parse failed for reportId={} ({}); "
                    + "FE will render markdown-only view", reportId, e.getMessage());
            return Collections.emptyList();
        }
        if (parsed.topIssues().isEmpty()) {
            return Collections.emptyList();
        }
        // Single indexed lookup. Bounded by issues-per-report (V1: ≤ 20).
        List<OptimizationEventEntity> existing = eventRepository.findBySourceReportId(reportId);
        Map<String, Long> issueIdToEventId = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (OptimizationEventEntity e : existing) {
            String iid = e.getSourceIssueId();
            if (iid == null) continue;
            if (!seen.add(iid)) {
                log.warn("OptReportToEventBridge.enrichTopIssues: duplicate sourceIssueId={} for "
                                + "reportId={} (eventIds={}, {}); keeping first",
                        iid, reportId, issueIdToEventId.get(iid), e.getId());
                continue;
            }
            issueIdToEventId.put(iid, e.getId());
        }

        List<Map<String, Object>> out = new ArrayList<>(parsed.topIssues().size());
        for (OptReportIssueDto issue : parsed.topIssues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", issue.id());
            m.put("title", issue.title());
            m.put("severity", issue.severity());
            m.put("sessionCount", issue.sessionCount());
            m.put("exampleSessionIds", issue.exampleSessionIds());
            m.put("suspectSurface", issue.suspectSurface());
            // V1.3+: surface 二元拆分 — 根因 vs 修复落点。FE 显示根因，convert 路由
            // 用 effective (fixSurface || suspectSurface)。
            m.put("fixSurface", issue.fixSurface());
            m.put("effectiveSurface", issue.effectiveSurface());
            m.put("confidence", issue.confidence());
            // V1.6 (G4): suggestion was demoted to optional. Coerce null → ""
            // so the FE (which types suggestion as a non-null string) never
            // NPEs / renders "null". rootCause/proposedFix carry the richer
            // content when present.
            m.put("suggestion", issue.suggestion() == null ? "" : issue.suggestion());
            m.put("expectedImpact", issue.expectedImpact());
            // V1.6 (G4) facets: friction classification (6-enum or null),
            // recurrence (t_session_pattern.member_count weighting, ≥1), and
            // the rootCause/proposedFix split. Keys always present (null OK for
            // legacy reports) so the FE can render them uniformly.
            m.put("friction", issue.friction());
            m.put("recurrence", issue.recurrence());
            m.put("rootCause", issue.rootCause());
            m.put("proposedFix", issue.proposedFix());
            // V1.5+: 强制对照现有 customRules / skills / prompt 段，区分
            // "new" / "modify" / "duplicate"。null OK (legacy reports / LLM
            // 没区分 → FE 按 "new" 处理保持向后兼容)。targetRuleText 仅在
            // actionType ∈ {modify, duplicate} 时 LLM 引用原文。
            m.put("actionType", issue.actionType());
            m.put("targetRuleText", issue.targetRuleText());
            Long evtId = issueIdToEventId.get(issue.id());
            m.put("alreadyConverted", evtId != null);
            // V1.2 r2 fix (BLOCKER-2): renamed eventId → convertedEventId to
            // match the FE OptReportIssue.convertedEventId field name.
            m.put("convertedEventId", evtId);
            // V1.3+: convertibility checked against effectiveSurface, not
            // suspectSurface — so an issue with suspectSurface=skill (root
            // cause) + fixSurface=behavior_rule still routes correctly.
            m.put("convertible", OptReportIssueDto.CONVERTIBLE_SURFACES
                    .contains(issue.effectiveSurface()));
            out.add(m);
        }
        return out;
    }
}
