package com.skillforge.server.history;

import com.skillforge.core.skill.SkillContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentSessionHistoryScopeTest {

    @Test
    void derivesIdentityOnlyFromTrustedSkillContextAndCarriesPreIntentFrontier() {
        SkillContext context = new SkillContext(null, "session-current", 42L);
        context.setToolUseId("history-call-1");

        CurrentSessionHistoryScope scope = CurrentSessionHistoryScope.from(
                context, 7L, 101L, 55L);

        assertThat(scope.sessionId()).isEqualTo("session-current");
        assertThat(scope.userId()).isEqualTo(42L);
        assertThat(scope.historyEpoch()).isEqualTo(7L);
        assertThat(scope.preIntentMaxMessageId()).isEqualTo(101L);
        assertThat(scope.preIntentMaxSeq()).isEqualTo(55L);
        assertThat(scope.currentToolUseId()).isEqualTo("history-call-1");
        assertThat(CurrentSessionHistoryScope.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test
    void rejectsMissingIdentityAndImpossibleFrontierBeforeRepositoryAccess() {
        assertThatThrownBy(() -> CurrentSessionHistoryScope.from(
                new SkillContext(null, null, 42L), 0, -1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current Session");
        assertThatThrownBy(() -> CurrentSessionHistoryScope.from(
                new SkillContext(null, "s1", null), 0, -1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current user");
        assertThatThrownBy(() -> CurrentSessionHistoryScope.from(
                new SkillContext(null, "s1", 42L), -1, -1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history epoch");
        assertThatThrownBy(() -> CurrentSessionHistoryScope.from(
                new SkillContext(null, "s1", 42L), 0, 101, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontier");
    }
}
