package com.skillforge.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionHistoryProperties")
class SessionHistoryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("defaults keep every production capability disabled and wire budget at 32K")
    void defaults_keepProductionCapabilitiesDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SessionHistoryProperties properties = context.getBean(SessionHistoryProperties.class);

            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isCheckpointEnvelopeEnabled()).isFalse();
            assertThat(properties.isSearchIndexEnabled()).isFalse();
            assertThat(properties.getMaxProviderWireChars()).isEqualTo(32_000);
            assertThat(properties.isRecoveryEvalEnabled()).isFalse();
            assertThat(properties.isCheckpointEnvelopeEffective()).isFalse();
        });
    }

    @Test
    @DisplayName("checkpoint envelope is effective only when master and envelope switches are both on")
    void checkpointEnvelopeEffective_requiresMasterAndEnvelope() {
        runner.withPropertyValues(
                        "skillforge.session-history.enabled=true",
                        "skillforge.session-history.checkpoint-envelope-enabled=true")
                .run(context -> assertThat(context.getBean(SessionHistoryProperties.class)
                        .isCheckpointEnvelopeEffective()).isTrue());

        runner.withPropertyValues("skillforge.session-history.checkpoint-envelope-enabled=true")
                .run(context -> assertThat(context.getBean(SessionHistoryProperties.class)
                        .isCheckpointEnvelopeEffective()).isFalse());
    }

    @Test
    @DisplayName("all frozen kebab-case names bind without adding a search visibility flag")
    void frozenKebabCaseNames_bind() {
        runner.withPropertyValues(
                        "skillforge.session-history.enabled=true",
                        "skillforge.session-history.checkpoint-envelope-enabled=true",
                        "skillforge.session-history.search-index-enabled=true",
                        "skillforge.session-history.max-provider-wire-chars=8192",
                        "skillforge.session-history.recovery-eval-enabled=true")
                .run(context -> {
                    SessionHistoryProperties properties = context.getBean(SessionHistoryProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isCheckpointEnvelopeEnabled()).isTrue();
                    assertThat(properties.isSearchIndexEnabled()).isTrue();
                    assertThat(properties.getMaxProviderWireChars()).isEqualTo(8_192);
                    assertThat(properties.isRecoveryEvalEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("wire budget outside 1..32000 fails configuration binding")
    void maxProviderWireChars_outOfBounds_failsStartup() {
        runner.withPropertyValues("skillforge.session-history.max-provider-wire-chars=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("skillforge.session-history.max-provider-wire-chars=32001")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("non-contract search visibility flag fails closed instead of silently binding")
    void unknownSearchVisibilityFlag_failsStartup() {
        runner.withPropertyValues("skillforge.session-history.search-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void masterOnRequiresBothAuthoritativeRowStoreFlags() {
        ApplicationContextRunner integrated = new ApplicationContextRunner()
                .withUserConfiguration(SkillForgeConfig.class);
        for (String flag : new String[]{"row-read-enabled", "row-write-enabled"}) {
            integrated.withPropertyValues(
                            "skillforge.session-history.enabled=true",
                            "skillforge.session-message-store." + flag + "=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseMessage(
                                "Session History requires row-read-enabled=true and row-write-enabled=true");
                    });
        }
        integrated.withPropertyValues("skillforge.session-history.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
        integrated.withPropertyValues(
                        "skillforge.session-message-store.row-read-enabled=false",
                        "skillforge.session-message-store.row-write-enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SessionHistoryProperties.class)
    static class PropertiesConfiguration {
    }
}
