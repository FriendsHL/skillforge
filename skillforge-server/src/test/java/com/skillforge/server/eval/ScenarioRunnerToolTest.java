package com.skillforge.server.eval;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.EvalSessionEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.repository.EvalSessionRepository;
import com.skillforge.server.repository.SessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioRunnerToolTest {

    @Mock
    private EvalSessionRepository evalSessionRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SandboxSkillRegistryFactory sandboxFactory;
    @Mock
    private EvalEngineFactory engineFactory;
    @Mock
    private ChatEventBroadcaster broadcaster;
    @Mock
    private java.util.concurrent.ExecutorService evalLoopExecutor;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Query query;
    @Mock
    private SkillRegistry skillRegistry;
    @Mock
    private AgentLoopEngine engine;

    private ScenarioRunnerTool tool;

    @BeforeEach
    void setUp() {
        tool = new ScenarioRunnerTool(
                evalSessionRepository,
                sessionRepository,
                sandboxFactory,
                engineFactory,
                broadcaster,
                evalLoopExecutor,
                transactionManager
        );
        ReflectionTestUtils.setField(tool, "entityManager", entityManager);

    }

    @Test
    @DisplayName("runScenarioMultiTurn_turnEngineThrows_stillRewritesTraceOrigin")
    void runScenarioMultiTurn_turnEngineThrows_stillRewritesTraceOrigin() throws Exception {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        EvalScenario scenario = new EvalScenario();
        scenario.setId("sc-multi");
        scenario.setName("Multi-turn");
        scenario.setConversationTurns(List.of(
                new EvalScenario.ConversationTurn("user", "help me")
        ));

        AgentDefinition agent = new AgentDefinition();
        agent.setId("agent-1");
        agent.setName("Eval Agent");

        when(sandboxFactory.buildSandboxRegistry("eval-1", "sc-multi")).thenReturn(skillRegistry);
        when(sandboxFactory.getSandboxRoot("eval-1", "sc-multi")).thenReturn(Path.of("/tmp"));
        when(engineFactory.buildEvalEngine(skillRegistry)).thenReturn(engine);
        when(engine.run(any(), anyString(), any(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evalSessionRepository.save(any(EvalSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ScenarioRunResult result = tool.runScenarioMultiTurn(
                "eval-1",
                scenario,
                agent,
                11L,
                22L,
                new MultiTurnTranscript()
        );

        assertThat(result.getStatus()).isEqualTo("ERROR");
        verify(entityManager, times(1)).createNativeQuery(anyString());
        verify(sandboxFactory, times(1)).cleanupSandbox("eval-1", "sc-multi");
    }

    @Test
    void updateEvalSessionStatus_timeout_writesStructuredHarnessFailure() {
        SessionEntity session = new SessionEntity();
        session.setId("eval-session");
        session.setRuntimeStatus("running");
        when(sessionRepository.findById("eval-session")).thenReturn(Optional.of(session));

        ReflectionTestUtils.invokeMethod(
                tool, "updateEvalSessionStatus", "eval-session", "timeout");

        assertThat(session.getRuntimeStatus()).isEqualTo("error");
        assertThat(session.getRuntimeFailureSource()).isEqualTo("harness");
        assertThat(session.getRuntimeFailureCode()).isEqualTo("EVAL_SCENARIO_TIMEOUT");
        assertThat(session.isRuntimeRetryable()).isFalse();
        assertThat(session.getRuntimeSideEffects()).isEqualTo("possible");
        assertThat(session.getRuntimeError()).isEqualTo("The evaluation scenario timed out.");
    }

    @Test
    void updateEvalSessionStatus_completed_clearsStaleFailureFact() {
        SessionEntity session = new SessionEntity();
        session.setId("eval-session");
        session.setRuntimeStatus("error");
        session.setRuntimeFailureSource("harness");
        session.setRuntimeFailureCode("EVAL_SCENARIO_FAILED");
        session.setRuntimeRetryable(false);
        session.setRuntimeSideEffects("possible");
        session.setRuntimeError("stale");
        when(sessionRepository.findById("eval-session")).thenReturn(Optional.of(session));

        ReflectionTestUtils.invokeMethod(
                tool, "updateEvalSessionStatus", "eval-session", "completed");

        assertThat(session.getRuntimeStatus()).isEqualTo("idle");
        assertThat(session.getRuntimeFailureSource()).isNull();
        assertThat(session.getRuntimeFailureCode()).isNull();
        assertThat(session.isRuntimeRetryable()).isFalse();
        assertThat(session.getRuntimeSideEffects()).isNull();
        assertThat(session.getRuntimeError()).isNull();
    }
}
