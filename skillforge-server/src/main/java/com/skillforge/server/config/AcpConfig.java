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
public class AcpConfig {

    private static final Logger log = LoggerFactory.getLogger(AcpConfig.class);


    // ───────────────────────── ACP-EXTERNAL-AGENT (P1a-2) ─────────────────────────

    /** cc-dialect {@code session/update} translator. P3 adds a codex translator. */
    @Bean
    public AcpUpdateTranslator acpUpdateTranslator() {
        return new CcAcpUpdateTranslator();
    }


    /** P2-1: pure OTLP-JSON logs parser (no Spring deps). */
    @Bean
    public com.skillforge.server.acp.otlp.OtlpLogsParser otlpLogsParser() {
        return new com.skillforge.server.acp.otlp.OtlpLogsParser();
    }


    /**
     * P2-1: bounded executor that runs OTLP ingest (parse → bind → PII-filter →
     * persist) OFF the receiver/cc-export hot path. AbortPolicy + small queue so a
     * flood sheds telemetry (warn-logged) rather than blocking cc or piling memory.
     */
    @Bean(name = "otlpIngestExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor otlpIngestExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "otlp-ingest-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }


    /**
     * P2-2: translates each persisted, PII-filtered cc event into a span on the cc
     * sub-session's trace, reusing the existing {@link com.skillforge.observability.api.LlmTraceStore}
     * (no new trace store/table). Idempotent (deterministic span ids) and resilient
     * (never throws — a span-write failure can not break P2-1 ingest).
     */
    @Bean
    public com.skillforge.server.acp.otlp.CcEventSpanTranslator ccEventSpanTranslator(
            com.skillforge.observability.api.LlmTraceStore llmTraceStore) {
        return new com.skillforge.server.acp.otlp.CcEventSpanTranslator(llmTraceStore);
    }


    /** P2-1: binds + persists cc OTLP events to the cc sub-session (PII-filtered). */
    @Bean
    public com.skillforge.server.acp.otlp.OtlpIngestService otlpIngestService(
            com.skillforge.server.acp.otlp.OtlpLogsParser otlpLogsParser,
            com.skillforge.server.repository.AcpCcEventRepository acpCcEventRepository,
            com.skillforge.server.repository.SessionRepository sessionRepository,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Qualifier("otlpIngestExecutor")
            ThreadPoolExecutor otlpIngestExecutor,
            com.skillforge.server.acp.otlp.CcEventSpanTranslator ccEventSpanTranslator) {
        return new com.skillforge.server.acp.otlp.OtlpIngestService(
                otlpLogsParser, acpCcEventRepository, sessionRepository, objectMapper,
                otlpIngestExecutor, ccEventSpanTranslator);
    }


    /** Per-run AcpClient factory — spawns a fresh ACP adapter process per run
     *  (adapter package chosen per-agent by the runner: cc vs codex vs …). */
    @Bean
    public AcpClientFactory acpClientFactory(ObjectMapper objectMapper,
                                             AcpUpdateTranslator acpUpdateTranslator) {
        return new ProcessAcpClientFactory(objectMapper, acpUpdateTranslator);
    }


    /**
     * Worker pool for the ACP permission bridge: it runs the blocking confirmation
     * latch wait OFF the cc reader thread (J-W3).
     *
     * <p>security-W3: BOUNDED (max {@code permissionWaitMaxThreads}, default 16) with
     * an {@code AbortPolicy} so a flood of permission requests can NOT exhaust
     * threads (DoS). On rejection the bridge catches {@link
     * java.util.concurrent.RejectedExecutionException} and responds {@code cancelled}
     * inline so the cc session never hangs. Broader rate-limiting is deferred to P1c.
     */
    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ExecutorService acpPermissionWaitExecutor(
            AcpRunnerProperties acpRunnerProperties) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        int max = acpRunnerProperties.getPermissionWaitMaxThreads();
        return new java.util.concurrent.ThreadPoolExecutor(
                0, max,
                60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "acp-perm-wait-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }


    @Bean
    public com.skillforge.server.acp.AcpPermissionBridge acpPermissionBridge(
            com.skillforge.core.engine.confirm.PendingConfirmationRegistry pendingConfirmationRegistry,
            ChatEventBroadcaster broadcaster,
            java.util.concurrent.ExecutorService acpPermissionWaitExecutor,
            AcpRunnerProperties acpRunnerProperties) {
        return new com.skillforge.server.acp.AcpPermissionBridge(
                pendingConfirmationRegistry, broadcaster, acpPermissionWaitExecutor,
                acpRunnerProperties.getPermissionTimeoutSeconds());
    }


    /**
     * P2-3a: dedicated single-thread scheduler that defers cc-trace finalization by a
     * short grace delay (so late cc OTLP events land first) without blocking the runner
     * thread. Daemon thread so it never holds up JVM shutdown.
     */
    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ScheduledExecutorService acpTraceFinalizeScheduler() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acp-trace-finalize");
            t.setDaemon(true);
            return t;
        });
    }


    @Bean
    public AcpAgentRunner acpAgentRunner(AcpClientFactory acpClientFactory,
                                         SessionService sessionService,
                                         AgentRepository agentRepository,
                                         ChatEventBroadcaster broadcaster,
                                         ObjectMapper objectMapper,
                                         AcpRunnerProperties acpRunnerProperties,
                                         com.skillforge.server.acp.AcpPermissionBridge acpPermissionBridge,
                                         @org.springframework.beans.factory.annotation.Qualifier("chatLoopExecutor")
                                         ThreadPoolExecutor chatLoopExecutor,
                                         SubAgentRegistry subAgentRegistry,
                                         org.springframework.context.ApplicationEventPublisher eventPublisher,
                                         com.skillforge.observability.api.LlmTraceStore llmTraceStore,
                                         java.util.concurrent.ScheduledExecutorService acpTraceFinalizeScheduler) {
        // P1c-1: SubAgent-mode deps (executor + registry + event publisher) so
        // runAsSubAgent runs cc async on a given child session and delivers its result
        // back to the parent/channel by reusing the existing pump.
        // P2-3a: trace-finalize deps (store + scheduler) so a finished cc run stamps its
        // sub-session trace terminal status/duration/counts instead of showing
        // running/0/0 forever.
        return new AcpAgentRunner(acpClientFactory, sessionService, agentRepository,
                broadcaster, objectMapper, acpRunnerProperties, acpPermissionBridge,
                chatLoopExecutor, subAgentRegistry, eventPublisher,
                llmTraceStore, acpTraceFinalizeScheduler);
    }
}
