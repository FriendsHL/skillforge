package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);
    private static final String SCENARIOS_PATH = "eval/scenarios/";

    private final ObjectMapper objectMapper;

    public ScenarioLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvalScenario> loadAll() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + SCENARIOS_PATH + "*.json");
            List<EvalScenario> scenarios = new ArrayList<>();
            for (Resource resource : resources) {
                try {
                    EvalScenario scenario = objectMapper.readValue(resource.getInputStream(), EvalScenario.class);
                    scenarios.add(scenario);
                    log.debug("Loaded scenario: {}", scenario.getId());
                } catch (Exception e) {
                    log.error("Failed to load scenario from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
            log.info("Loaded {} eval scenarios", scenarios.size());
            return scenarios;
        } catch (Exception e) {
            log.error("Failed to load eval scenarios", e);
            return Collections.emptyList();
        }
    }
}
