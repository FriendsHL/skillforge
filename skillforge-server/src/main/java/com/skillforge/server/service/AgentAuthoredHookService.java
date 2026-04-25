package com.skillforge.server.service;

import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.hook.HookMethodResolver;
import com.skillforge.server.repository.AgentAuthoredHookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AgentAuthoredHookService {

    private static final int MIN_TIMEOUT_SEC = 1;
    private static final int MAX_TIMEOUT_SEC = 300;

    private final AgentAuthoredHookRepository repository;
    private final HookMethodResolver methodResolver;

    public AgentAuthoredHookService(AgentAuthoredHookRepository repository,
                                    HookMethodResolver methodResolver) {
        this.repository = repository;
        this.methodResolver = methodResolver;
    }

    @Transactional(readOnly = true)
    public List<AgentAuthoredHookEntity> listForTarget(Long targetAgentId) {
        return repository.findByTargetAgentIdOrderByIdAsc(targetAgentId);
    }

    @Transactional(readOnly = true)
    public List<AgentAuthoredHookEntity> findDispatchable(Long targetAgentId, HookEvent event) {
        if (targetAgentId == null || event == null) {
            return List.of();
        }
        return repository.findByTargetAgentIdAndEventAndReviewStateAndEnabledTrueOrderByIdAsc(
                targetAgentId,
                event.wireName(),
                AgentAuthoredHookEntity.STATE_APPROVED);
    }

    @Transactional
    public AgentAuthoredHookEntity propose(ProposeRequest req) {
        validateProposal(req);
        HookEvent event = HookEvent.fromWire(req.event());
        if (event == null) {
            throw new IllegalArgumentException("invalid hook event: " + req.event());
        }
        FailurePolicy policy = FailurePolicy.fromJson(req.failurePolicy());
        if (req.async() && policy != FailurePolicy.CONTINUE) {
            throw new IllegalArgumentException("async agent-authored hooks must use CONTINUE failurePolicy");
        }
        HookMethodResolver.MethodTarget target = methodResolver.resolveProposalTarget(req.methodTarget());
        boolean duplicate = repository.existsByTargetAgentIdAndEventAndMethodKindAndMethodIdAndMethodRefAndReviewStateIn(
                req.targetAgentId(),
                event.wireName(),
                target.methodKind(),
                target.methodId(),
                target.methodRef(),
                List.of(AgentAuthoredHookEntity.STATE_PENDING, AgentAuthoredHookEntity.STATE_APPROVED));
        if (duplicate) {
            throw new IllegalArgumentException("duplicate pending/approved hook binding for target/event/method");
        }

        AgentAuthoredHookEntity e = new AgentAuthoredHookEntity();
        e.setTargetAgentId(req.targetAgentId());
        e.setAuthorAgentId(req.authorAgentId());
        e.setAuthorSessionId(req.authorSessionId());
        e.setEvent(event.wireName());
        e.setMethodKind(target.methodKind());
        e.setMethodId(target.methodId());
        e.setMethodRef(target.methodRef());
        e.setMethodVersionHash(target.methodVersionHash());
        e.setArgsJson(methodResolver.argsToJson(req.args()));
        e.setTimeoutSeconds(clampTimeout(req.timeoutSeconds()));
        e.setFailurePolicy(policy.name());
        e.setAsync(req.async());
        e.setDisplayName(req.displayName());
        e.setDescription(req.description());
        e.setParentHookId(req.parentHookId());
        e.setReviewState(AgentAuthoredHookEntity.STATE_PENDING);
        e.setEnabled(true);
        return repository.save(e);
    }

    @Transactional
    public AgentAuthoredHookEntity approve(Long id, Long reviewerUserId, String reviewNote) {
        AgentAuthoredHookEntity e = findRequired(id);
        if (!AgentAuthoredHookEntity.STATE_PENDING.equals(e.getReviewState())) {
            throw new IllegalArgumentException("can only approve PENDING hooks, current=" + e.getReviewState());
        }
        methodResolver.validateStoredTarget(e);
        e.setReviewState(AgentAuthoredHookEntity.STATE_APPROVED);
        e.setReviewedByUserId(reviewerUserId);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(reviewNote);
        return repository.save(e);
    }

    @Transactional
    public AgentAuthoredHookEntity reject(Long id, Long reviewerUserId, String reviewNote) {
        AgentAuthoredHookEntity e = findRequired(id);
        if (!AgentAuthoredHookEntity.STATE_PENDING.equals(e.getReviewState())) {
            throw new IllegalArgumentException("can only reject PENDING hooks, current=" + e.getReviewState());
        }
        e.setReviewState(AgentAuthoredHookEntity.STATE_REJECTED);
        e.setReviewedByUserId(reviewerUserId);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(reviewNote);
        e.setEnabled(false);
        return repository.save(e);
    }

    @Transactional
    public AgentAuthoredHookEntity retire(Long id, Long reviewerUserId, String reviewNote) {
        AgentAuthoredHookEntity e = findRequired(id);
        e.setReviewState(AgentAuthoredHookEntity.STATE_RETIRED);
        e.setReviewedByUserId(reviewerUserId);
        e.setReviewedAt(Instant.now());
        e.setReviewNote(reviewNote);
        e.setEnabled(false);
        return repository.save(e);
    }

    @Transactional
    public AgentAuthoredHookEntity setEnabled(Long id, boolean enabled) {
        AgentAuthoredHookEntity e = findRequired(id);
        if (!AgentAuthoredHookEntity.STATE_APPROVED.equals(e.getReviewState())) {
            throw new IllegalArgumentException("only APPROVED hooks can be enabled/disabled");
        }
        e.setEnabled(enabled);
        return repository.save(e);
    }

    @Transactional
    public void recordExecution(Long id, boolean success, String errorMessage) {
        if (id == null) {
            return;
        }
        repository.findById(id).ifPresent(e -> {
            e.setUsageCount(e.getUsageCount() + 1);
            if (success) {
                e.setSuccessCount(e.getSuccessCount() + 1);
                e.setLastError(null);
            } else {
                e.setFailureCount(e.getFailureCount() + 1);
                e.setLastError(errorMessage);
            }
            e.setLastExecutedAt(Instant.now());
            repository.save(e);
        });
    }

    public HookEntry toHookEntry(AgentAuthoredHookEntity entity) {
        return methodResolver.toHookEntry(entity);
    }

    private AgentAuthoredHookEntity findRequired(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent-authored hook not found: id=" + id));
    }

    private static void validateProposal(ProposeRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (req.targetAgentId() == null) {
            throw new IllegalArgumentException("targetAgentId is required");
        }
        if (req.authorAgentId() == null) {
            throw new IllegalArgumentException("authorAgentId is required");
        }
        if (req.event() == null || req.event().isBlank()) {
            throw new IllegalArgumentException("event is required");
        }
        if (req.methodTarget() == null || req.methodTarget().isBlank()) {
            throw new IllegalArgumentException("methodTarget is required");
        }
    }

    private static int clampTimeout(Integer timeoutSeconds) {
        int value = timeoutSeconds != null ? timeoutSeconds : 30;
        if (value < MIN_TIMEOUT_SEC) return MIN_TIMEOUT_SEC;
        if (value > MAX_TIMEOUT_SEC) return MAX_TIMEOUT_SEC;
        return value;
    }

    public record ProposeRequest(
            Long targetAgentId,
            Long authorAgentId,
            String authorSessionId,
            String event,
            String methodTarget,
            String displayName,
            String description,
            Integer timeoutSeconds,
            String failurePolicy,
            boolean async,
            Object args,
            Long parentHookId
    ) {}
}
