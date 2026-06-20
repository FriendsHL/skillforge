package com.skillforge.server.config;

import com.skillforge.server.acp.AcpRunnerProperties;
import com.skillforge.server.skill.SkillImportProperties;
import com.skillforge.server.security.skill.SkillSecurityScanProperties;
import com.skillforge.server.memory.transcript.MemoryTranscriptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        LlmProperties.class,
        LifecycleHooksScriptProperties.class,
        SessionMessageStoreProperties.class,
        MemoryProperties.class,
        SkillImportProperties.class,
        SkillSecurityScanProperties.class,
        EvalUserSimulatorProperties.class,
        WebToolsProperties.class,
        MemoryTranscriptProperties.class,
        EvolveThresholdProperties.class,
        AcpRunnerProperties.class,
        SkillConsolidatorProperties.class
})
public class SkillForgeConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillForgeConfig.class);
}
