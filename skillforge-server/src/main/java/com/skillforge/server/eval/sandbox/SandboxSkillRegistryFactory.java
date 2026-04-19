package com.skillforge.server.eval.sandbox;

import com.skillforge.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

@Component
public class SandboxSkillRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(SandboxSkillRegistryFactory.class);

    public SkillRegistry buildSandboxRegistry(String evalRunId, String scenarioId) throws IOException {
        Path sandboxRoot = getSandboxRoot(evalRunId, scenarioId);
        Files.createDirectories(sandboxRoot);

        SkillRegistry sandbox = new SkillRegistry();
        sandbox.register(new SandboxedFileReadSkill(sandboxRoot));
        sandbox.register(new SandboxedFileWriteSkill(sandboxRoot));
        sandbox.register(new SandboxedGrepSkill(sandboxRoot));
        sandbox.register(new SandboxedGlobSkill(sandboxRoot));
        return sandbox;
    }

    public SkillRegistry buildSandboxRegistryWithSkills(String runId, String scenarioId,
                                                         List<com.skillforge.core.model.SkillDefinition> extraSkills) throws IOException {
        SkillRegistry registry = buildSandboxRegistry(runId, scenarioId);
        for (com.skillforge.core.model.SkillDefinition def : extraSkills) {
            registry.registerSkillDefinition(def);
        }
        return registry;
    }

    public Path getSandboxRoot(String evalRunId, String scenarioId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "eval", evalRunId, scenarioId);
    }

    public void cleanupSandbox(String evalRunId, String scenarioId) {
        Path root = getSandboxRoot(evalRunId, scenarioId);
        try {
            if (Files.exists(root)) {
                Files.walk(root)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.delete(path); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup sandbox: {}", root, e);
        }
    }
}
