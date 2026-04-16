package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);
    private static final String SCENARIOS_PATH = "eval/scenarios/";

    private final ObjectMapper objectMapper;
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;

    public ScenarioLoader(ObjectMapper objectMapper,
                          EvalScenarioDraftRepository evalScenarioDraftRepository) {
        this.objectMapper = objectMapper;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
    }

    public List<EvalScenario> loadAll() {
        // Use a map keyed by id for dedup; classpath scenarios take precedence
        Map<String, EvalScenario> scenarioMap = new LinkedHashMap<>();

        // 1. Load classpath scenarios
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + SCENARIOS_PATH + "*.json");
            for (Resource resource : resources) {
                try {
                    EvalScenario scenario = objectMapper.readValue(resource.getInputStream(), EvalScenario.class);
                    scenarioMap.put(scenario.getId(), scenario);
                    log.debug("Loaded scenario: {}", scenario.getId());
                } catch (Exception e) {
                    log.error("Failed to load scenario from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load eval scenarios from classpath", e);
        }

        // 2. Load active DB scenarios (additive — classpath ids take precedence)
        try {
            List<EvalScenarioEntity> dbScenarios = evalScenarioDraftRepository.findByStatus("active");
            for (EvalScenarioEntity entity : dbScenarios) {
                if (!scenarioMap.containsKey(entity.getId())) {
                    scenarioMap.put(entity.getId(), toEvalScenario(entity));
                    log.debug("Loaded DB scenario: {}", entity.getId());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load eval scenarios from DB", e);
        }

        log.info("Loaded {} eval scenarios (classpath + DB)", scenarioMap.size());
        return new ArrayList<>(scenarioMap.values());
    }

    private EvalScenario toEvalScenario(EvalScenarioEntity entity) {
        EvalScenario scenario = new EvalScenario();
        scenario.setId(entity.getId());
        scenario.setName(entity.getName());
        scenario.setDescription(entity.getDescription());
        scenario.setCategory(entity.getCategory());
        scenario.setSplit(entity.getSplit());
        scenario.setTask(entity.getTask());

        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType(entity.getOracleType());
        oracle.setExpected(entity.getOracleExpected());
        scenario.setOracle(oracle);

        return scenario;
    }
}
