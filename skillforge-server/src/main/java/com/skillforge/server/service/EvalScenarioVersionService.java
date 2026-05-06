package com.skillforge.server.service;

import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class EvalScenarioVersionService {

    private final EvalScenarioDraftRepository repository;

    public EvalScenarioVersionService(EvalScenarioDraftRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EvalScenarioEntity> listLatestScenarios(String agentId) {
        List<EvalScenarioEntity> rows = repository.findByAgentIdOrderByCreatedAtDesc(agentId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, EvalScenarioEntity> byId = indexById(rows);
        Map<String, EvalScenarioEntity> latestByFamily = new LinkedHashMap<>();
        for (EvalScenarioEntity row : rows) {
            String familyId = familyRootId(row, byId);
            EvalScenarioEntity prior = latestByFamily.get(familyId);
            if (prior == null || compareScenarioVersion(row, prior) < 0) {
                latestByFamily.put(familyId, row);
            }
        }
        List<EvalScenarioEntity> latest = new ArrayList<>(latestByFamily.values());
        latest.sort(Comparator
                .comparing(EvalScenarioEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(EvalScenarioEntity::getVersion, Comparator.nullsLast(Comparator.reverseOrder())));
        return latest;
    }

    @Transactional(readOnly = true)
    public List<EvalScenarioEntity> listVersions(String scenarioId) {
        EvalScenarioEntity current = repository.findById(scenarioId)
                .orElseThrow(() -> new NoSuchElementException("Scenario not found: " + scenarioId));
        List<EvalScenarioEntity> rows = repository.findByAgentIdOrderByCreatedAtDesc(current.getAgentId());
        Map<String, EvalScenarioEntity> byId = indexById(rows);
        String familyId = familyRootId(current, byId);
        return rows.stream()
                .filter(row -> Objects.equals(familyRootId(row, byId), familyId))
                .sorted(this::compareScenarioVersion)
                .toList();
    }

    @Transactional
    public EvalScenarioEntity createVersion(String scenarioId, Map<String, Object> overrides) {
        EvalScenarioEntity source = repository.findById(scenarioId)
                .orElseThrow(() -> new NoSuchElementException("Scenario not found: " + scenarioId));
        List<EvalScenarioEntity> family = listVersions(scenarioId);
        int nextVersion = family.stream()
                .map(EvalScenarioEntity::getVersion)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1) + 1;

        EvalScenarioEntity created = new EvalScenarioEntity();
        created.setId(UUID.randomUUID().toString());
        created.setAgentId(source.getAgentId());
        created.setName(stringOverride(overrides, "name", source.getName()));
        created.setDescription(stringOverride(overrides, "description", source.getDescription()));
        created.setCategory(stringOverride(overrides, "category", source.getCategory()));
        created.setSplit(stringOverride(overrides, "split", source.getSplit()));
        created.setTask(stringOverride(overrides, "task", source.getTask()));
        created.setOracleType(stringOverride(overrides, "oracleType", source.getOracleType()));
        created.setOracleExpected(stringOverride(overrides, "oracleExpected", source.getOracleExpected()));
        created.setSourceSessionId(source.getSourceSessionId());
        created.setExtractionRationale(stringOverride(overrides, "extractionRationale", source.getExtractionRationale()));
        created.setConversationTurns(stringOverride(overrides, "conversationTurns", source.getConversationTurns()));
        created.setVersion(nextVersion);
        created.setParentScenarioId(source.getId());
        created.setStatus(stringOverride(overrides, "status", "active"));
        created.setReviewedAt("draft".equals(created.getStatus()) ? null : Instant.now());
        return repository.save(created);
    }

    private Map<String, EvalScenarioEntity> indexById(List<EvalScenarioEntity> rows) {
        Map<String, EvalScenarioEntity> byId = new HashMap<>();
        for (EvalScenarioEntity row : rows) {
            byId.put(row.getId(), row);
        }
        return byId;
    }

    private String familyRootId(EvalScenarioEntity row, Map<String, EvalScenarioEntity> byId) {
        String currentId = row.getId();
        String parentId = row.getParentScenarioId();
        Set<String> seen = new java.util.HashSet<>();
        seen.add(currentId);
        while (parentId != null && !parentId.isBlank()) {
            if (!seen.add(parentId)) {
                break;
            }
            EvalScenarioEntity parent = byId.get(parentId);
            if (parent == null) {
                return parentId;
            }
            currentId = parent.getId();
            parentId = parent.getParentScenarioId();
        }
        return currentId;
    }

    private int compareScenarioVersion(EvalScenarioEntity left, EvalScenarioEntity right) {
        int versionCmp = Integer.compare(
                right.getVersion() == null ? 0 : right.getVersion(),
                left.getVersion() == null ? 0 : left.getVersion()
        );
        if (versionCmp != 0) {
            return versionCmp;
        }
        Instant leftCreated = left.getCreatedAt();
        Instant rightCreated = right.getCreatedAt();
        if (leftCreated == null && rightCreated == null) {
            return 0;
        }
        if (leftCreated == null) {
            return 1;
        }
        if (rightCreated == null) {
            return -1;
        }
        return rightCreated.compareTo(leftCreated);
    }

    private static String stringOverride(Map<String, Object> overrides, String key, String fallback) {
        if (overrides == null || !overrides.containsKey(key)) {
            return fallback;
        }
        Object value = overrides.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
