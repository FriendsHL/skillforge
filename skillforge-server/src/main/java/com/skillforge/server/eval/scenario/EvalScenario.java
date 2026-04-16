package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalScenario {

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
}
