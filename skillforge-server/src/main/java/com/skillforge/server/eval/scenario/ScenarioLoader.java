package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class ScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);
    private static final String SCENARIOS_PATH = "eval/scenarios/";

    private final ObjectMapper objectMapper;
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;
    private final BaseScenarioService baseScenarioService;

    public ScenarioLoader(ObjectMapper objectMapper,
                          EvalScenarioDraftRepository evalScenarioDraftRepository,
                          BaseScenarioService baseScenarioService) {
        this.objectMapper = objectMapper;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
        this.baseScenarioService = baseScenarioService;
    }

    public List<EvalScenario> loadAll() {
        // Use a map keyed by id for dedup. Resolution precedence (FIRST wins on
        // collision; later sources only fill in missing ids):
        //   1. classpath  — immutable seed shipped with the jar (canonical)
        //   2. home dir   — operator-/agent-added base scenarios (EVAL-V2 Q2)
        //   3. DB         — per-agent EvalScenarioEntity rows, status=active
        //
        // Why classpath wins: the seed scenarios are the contractual baseline a
        // build's eval suite is benchmarked against. If a user adds a home-dir
        // scenario with a colliding id (rare but possible — e.g. a Tool-driven
        // AddEvalScenario that picked an id already taken), we keep the seed
        // and log a warning rather than silently overriding the baseline.
        Map<String, EvalScenario> scenarioMap = new LinkedHashMap<>();

        // 1. Load classpath scenarios
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + SCENARIOS_PATH + "*.json");
            for (Resource resource : resources) {
                try {
                    EvalScenario scenario = objectMapper.readValue(resource.getInputStream(), EvalScenario.class);
                    scenario.setSource(EvalScenario.SOURCE_CLASSPATH);
                    scenarioMap.put(scenario.getId(), scenario);
                    log.debug("Loaded classpath scenario: {}", scenario.getId());
                } catch (Exception e) {
                    log.error("Failed to load scenario from {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load eval scenarios from classpath", e);
        }

        // 2. Load home dir scenarios (Q2 — base dataset dual-path)
        try {
            Path homeDir = baseScenarioService != null ? baseScenarioService.getHomeDir() : null;
            if (homeDir != null && Files.isDirectory(homeDir)) {
                try (Stream<Path> stream = Files.list(homeDir)) {
                    List<Path> jsonFiles = stream
                            .filter(p -> p.getFileName().toString().endsWith(".json"))
                            .filter(Files::isRegularFile)
                            .toList();
                    for (Path file : jsonFiles) {
                        try {
                            EvalScenario scenario = objectMapper.readValue(file.toFile(), EvalScenario.class);
                            if (scenario.getId() == null || scenario.getId().isBlank()) {
                                log.warn("Skipping home scenario {} — id missing/blank", file.getFileName());
                                continue;
                            }
                            if (scenarioMap.containsKey(scenario.getId())) {
                                log.warn("Home scenario id={} (file={}) shadowed by classpath; ignoring home copy",
                                        scenario.getId(), file.getFileName());
                                continue;
                            }
                            scenario.setSource(EvalScenario.SOURCE_HOME);
                            scenarioMap.put(scenario.getId(), scenario);
                            log.debug("Loaded home-dir scenario: {} from {}", scenario.getId(), file);
                        } catch (Exception e) {
                            log.error("Failed to load home-dir scenario from {}: {}", file, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan home-dir scenarios", e);
        }

        // 3. Load active DB scenarios
        try {
            List<EvalScenarioEntity> dbScenarios = evalScenarioDraftRepository.findByStatus("active");
            for (EvalScenarioEntity entity : dbScenarios) {
                if (!scenarioMap.containsKey(entity.getId())) {
                    EvalScenario scenario = toEvalScenario(entity);
                    scenario.setSource(EvalScenario.SOURCE_DB);
                    scenarioMap.put(entity.getId(), scenario);
                    log.debug("Loaded DB scenario: {}", entity.getId());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load eval scenarios from DB", e);
        }

        log.info("Loaded {} eval scenarios (classpath + home + DB)", scenarioMap.size());
        return new ArrayList<>(scenarioMap.values());
    }

    /**
     * EVAL-V2 Q2/Q3: load only "base" scenarios (classpath + home dir) without
     * touching {@code t_eval_scenario}. Used by the {@code GET
     * /api/eval/scenarios/base} endpoint feeding the "Base dataset" tab in
     * DatasetBrowser. DB-stored scenarios are surfaced separately by the
     * per-agent path which calls {@link EvalScenarioDraftRepository} directly.
     */
    public List<EvalScenario> loadBaseScenarios() {
        Map<String, EvalScenario> scenarioMap = new LinkedHashMap<>();
        // classpath
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + SCENARIOS_PATH + "*.json");
            for (Resource resource : resources) {
                try {
                    EvalScenario scenario = objectMapper.readValue(resource.getInputStream(), EvalScenario.class);
                    scenario.setSource(EvalScenario.SOURCE_CLASSPATH);
                    scenarioMap.put(scenario.getId(), scenario);
                } catch (Exception e) {
                    log.error("Failed to load classpath scenario {}: {}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to enumerate classpath base scenarios", e);
        }
        // home dir
        try {
            Path homeDir = baseScenarioService != null ? baseScenarioService.getHomeDir() : null;
            if (homeDir != null && Files.isDirectory(homeDir)) {
                try (Stream<Path> stream = Files.list(homeDir)) {
                    List<Path> jsonFiles = stream
                            .filter(p -> p.getFileName().toString().endsWith(".json"))
                            .filter(Files::isRegularFile)
                            .toList();
                    for (Path file : jsonFiles) {
                        try {
                            EvalScenario scenario = objectMapper.readValue(file.toFile(), EvalScenario.class);
                            if (scenario.getId() == null || scenario.getId().isBlank()) continue;
                            if (scenarioMap.containsKey(scenario.getId())) continue; // classpath wins
                            scenario.setSource(EvalScenario.SOURCE_HOME);
                            scenarioMap.put(scenario.getId(), scenario);
                        } catch (Exception e) {
                            log.error("Failed to parse home scenario {}: {}", file, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan home base scenarios dir", e);
        }
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

        // EVAL-V2 M2: propagate multi-turn turns from the entity's JSON-encoded
        // String column. Parse failures only log — a malformed row should not
        // cause the whole eval batch to crash; the scenario simply degrades to
        // single-turn behaviour (conversationTurns=null).
        String turnsJson = entity.getConversationTurns();
        if (turnsJson != null && !turnsJson.isBlank()) {
            try {
                List<EvalScenario.ConversationTurn> turns = objectMapper.readValue(
                        turnsJson, new TypeReference<List<EvalScenario.ConversationTurn>>() {});
                scenario.setConversationTurns(turns);
            } catch (Exception e) {
                log.warn("Failed to parse conversation_turns for scenario id={}: {}",
                        entity.getId(), e.getMessage());
            }
        }

        return scenario;
    }
}
