package com.skillforge.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.compact.recovery.FileStateCache;
import com.skillforge.core.compact.recovery.RecoveryPayloadBuilder;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.acp.AcpAgentRunner;
import com.skillforge.server.acp.AcpClientFactory;
import com.skillforge.server.acp.AcpRunnerProperties;
import com.skillforge.server.acp.AcpUpdateTranslator;
import com.skillforge.server.acp.CcAcpUpdateTranslator;
import com.skillforge.server.acp.ProcessAcpClientFactory;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.SafetySkillHook;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.server.hook.LifecycleHookLoopAdapter;
import com.skillforge.server.hook.LifecycleHookSkillAdapter;
import com.skillforge.server.hook.GetAgentHooksTool;
import com.skillforge.server.hook.ProposeHookBindingTool;
import com.skillforge.core.llm.EmbeddingProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.llm.OpenAiEmbeddingProvider;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.tools.BashTool;
import com.skillforge.tools.CodeReviewTool;
import com.skillforge.tools.CodeSandboxTool;
import com.skillforge.tools.FileEditTool;
import com.skillforge.tools.FileReadTool;
import com.skillforge.tools.FileWriteTool;
import com.skillforge.tools.GlobTool;
import com.skillforge.tools.GrepTool;
import com.skillforge.tools.WebFetchTool;
import com.skillforge.tools.WebSearchTool;
import com.skillforge.tools.webfetch.WebFetchConfig;
import com.skillforge.tools.websearch.WebSearchConfig;
import com.skillforge.server.code.CompiledMethodService;
import com.skillforge.server.code.RegisterCompiledMethodTool;
import com.skillforge.server.code.RegisterScriptMethodTool;
import com.skillforge.server.code.ScriptMethodService;
import com.skillforge.server.skill.ImportSkillTool;
import com.skillforge.server.skill.SkillImportProperties;
import com.skillforge.server.skill.SkillImportService;
import com.skillforge.server.skill.TodoStore;
import com.skillforge.server.security.skill.SkillSecurityScanProperties;
import com.skillforge.server.reminder.TodoListSource;
import com.skillforge.server.tool.TodoWriteTool;
import com.skillforge.server.tool.MemoryDetailTool;
import com.skillforge.server.tool.MemorySearchTool;
import com.skillforge.server.tool.MemoryTool;
import com.skillforge.server.tool.AgentDiscoveryTool;
import com.skillforge.server.tool.CreateAgentTool;
import com.skillforge.server.tool.GetAgentConfigTool;
import com.skillforge.server.tool.GetSessionMessagesTool;
import com.skillforge.server.tool.GetTraceTool;
import com.skillforge.server.tool.SubAgentTool;
import com.skillforge.server.tool.TeamCreateTool;
import com.skillforge.server.tool.TeamKillTool;
import com.skillforge.server.tool.TeamListTool;
import com.skillforge.server.tool.TeamSendTool;
import com.skillforge.server.tool.UpdateAgentTool;
import com.skillforge.server.service.AgentAuthoredHookService;
import com.skillforge.server.service.AgentMutationService;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.AgentTargetResolver;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.EmbeddingService;
import com.skillforge.server.service.LifecycleHookViewService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.UserConfigService;
import com.skillforge.server.memory.transcript.MemoryTranscriptProperties;
import com.skillforge.server.memory.transcript.SessionTranscriptProvider;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.tool.memorysynth.ClusterMemoriesTool;
import com.skillforge.server.tool.memorysynth.CreateMemoryProposalTool;
import com.skillforge.server.tool.memorysynth.ListActiveUsersTool;
import com.skillforge.server.tool.memorysynth.ListMemoryCandidatesTool;
import com.skillforge.server.tool.memorysynth.ListRecentSessionTranscriptsTool;
import com.skillforge.server.tool.memorycontext.ListRelevantMemoriesTool;
import com.skillforge.server.tool.scheduling.CreateScheduledTaskTool;
import com.skillforge.server.tool.scheduling.DeleteScheduledTaskTool;
import com.skillforge.server.tool.scheduling.GetScheduledTaskTool;
import com.skillforge.server.tool.scheduling.ListScheduledTasksTool;
import com.skillforge.server.tool.scheduling.UpdateScheduledTaskTool;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.tool.sessionannotation.SpanBehaviorStatsTool;
import com.skillforge.server.tool.optreport.GetToolCallSequenceTool;
import com.skillforge.server.tool.optreport.LoadErrorSpanBatchTool;
import com.skillforge.server.tool.optreport.LoadSessionBatchTool;
import com.skillforge.server.tool.optreport.RecordBatchAnnotationsTool;
import com.skillforge.server.tool.optreport.WriteOptReportTool;
import com.skillforge.server.optreport.OptReportService;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.CollabRunService;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.weixin.WeixinChannelAdapter;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import com.skillforge.server.tool.channel.SendChannelFileTool;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class CompactionConfig {

    private static final Logger log = LoggerFactory.getLogger(CompactionConfig.class);


    /**
     * P9-5: process-wide cache of file content read/written/edited per session.  Feeds the
     * post-compact recovery payload (RecoveryPayloadBuilder).  Singleton — file tools and
     * the agent loop engine share the same instance.
     *
     * <p>The cache uses the same {@code max-tokens-per-file} value as the builder so a yaml
     * change on this single property propagates through both write-side truncation (here)
     * and read-side budget (builder).  Single source of truth.
     */
    @Bean
    public FileStateCache fileStateCache(
            @Value("${skillforge.compact.recovery.max-tokens-per-file:5000}") int maxTokensPerFile) {
        return new FileStateCache(maxTokensPerFile);
    }


    /**
     * P9-5: builds the recovery {@link com.skillforge.core.model.Message} appended after the
     * compact summary.  Configuration via {@code skillforge.compact.recovery.*} properties.
     *
     * <p>Note: {@code maxTokensPerFile} is read from the same property as
     * {@link #fileStateCache(int)} so write-side truncation and read-side budget stay in sync.
     */
    @Bean
    public RecoveryPayloadBuilder recoveryPayloadBuilder(
            FileStateCache fileStateCache,
            @Value("${skillforge.compact.recovery.enabled:true}") boolean enabled,
            @Value("${skillforge.compact.recovery.max-files:5}") int maxFiles,
            @Value("${skillforge.compact.recovery.max-tokens-per-file:5000}") int maxTokensPerFile) {
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(fileStateCache);
        builder.setEnabled(enabled);
        builder.setMaxFiles(maxFiles);
        builder.setMaxTokensPerFile(maxTokensPerFile);
        return builder;
    }


    @Bean
    public CompactableToolRegistry compactableToolRegistry() {
        return new CompactableToolRegistry();
    }


    @Bean
    public LightCompactStrategy lightCompactStrategy(CompactableToolRegistry compactableToolRegistry) {
        return new LightCompactStrategy(compactableToolRegistry);
    }


    @Bean
    public FullCompactStrategy fullCompactStrategy() {
        return new FullCompactStrategy();
    }
}
