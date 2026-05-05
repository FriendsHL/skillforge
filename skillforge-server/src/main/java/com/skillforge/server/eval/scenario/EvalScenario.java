package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalScenario {

    /**
     * EVAL-V2 Q3: where this scenario was loaded from.
     * <ul>
     *   <li>{@code classpath} — bundled jar resource under {@code eval/scenarios/}</li>
     *   <li>{@code home}      — home dir override (BaseScenarioService.getHomeDir())</li>
     *   <li>{@code db}        — promoted from {@code t_eval_scenario} (per-agent draft)</li>
     * </ul>
     * <p>Not deserialised from JSON; set by {@link ScenarioLoader} at load time so the
     * UI can label "system" vs "user-added" scenarios. {@code @JsonIgnore} on read so
     * a hand-edited JSON's stale {@code source} field can't override the loader.
     */
    public static final String SOURCE_CLASSPATH = "classpath";
    public static final String SOURCE_HOME = "home";
    public static final String SOURCE_DB = "db";

    private String id;
    private String name;
    private String description;
    private String category;
    private String split;
    private String task;
    private ScenarioSetup setup;
    private ScenarioOracle oracle;
    private long performanceThresholdMs = 30000;
    private List<String> toolsHint;
    private int maxLoops = 10;
    private List<String> tags;

    /** Set by ScenarioLoader at load time; not part of the on-disk JSON. */
    @JsonIgnore
    private String source;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScenarioSetup {
        private Map<String, String> files;

        public Map<String, String> getFiles() { return files; }
        public void setFiles(Map<String, String> files) { this.files = files; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScenarioOracle {
        private String type;
        private String expected;
        private List<String> expectedList;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getExpected() { return expected; }
        public void setExpected(String expected) { this.expected = expected; }

        public List<String> getExpectedList() { return expectedList; }
        public void setExpectedList(List<String> expectedList) { this.expectedList = expectedList; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSplit() { return split; }
    public void setSplit(String split) { this.split = split; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public ScenarioSetup getSetup() { return setup; }
    public void setSetup(ScenarioSetup setup) { this.setup = setup; }

    public ScenarioOracle getOracle() { return oracle; }
    public void setOracle(ScenarioOracle oracle) { this.oracle = oracle; }

    public long getPerformanceThresholdMs() { return performanceThresholdMs; }
    public void setPerformanceThresholdMs(long performanceThresholdMs) { this.performanceThresholdMs = performanceThresholdMs; }

    public List<String> getToolsHint() { return toolsHint; }
    public void setToolsHint(List<String> toolsHint) { this.toolsHint = toolsHint; }

    public int getMaxLoops() { return maxLoops; }
    public void setMaxLoops(int maxLoops) { this.maxLoops = maxLoops; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
