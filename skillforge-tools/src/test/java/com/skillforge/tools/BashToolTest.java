package com.skillforge.tools;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BashToolTest {

    @TempDir
    Path tempDir;

    private final BashTool tool = new BashTool();

    @Test
    void returnsSuccessForZeroExit() {
        SkillResult result = execute("printf 'ok'");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("ok");
    }

    @Test
    void returnsErrorWithExitCodeAndOutputForNonZeroExit() {
        SkillResult result = execute("printf 'failed'; exit 7");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("exit code 7").contains("failed");
    }

    @Test
    void timesOutEvenWhenProcessKeepsStdoutOpen() {
        SkillResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> execute("printf 'started'; sleep 10", 100));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("timed out").contains("100ms");
    }

    @Test
    void rejectsTimeoutOutsideSupportedRangeBeforeStartingProcess() {
        SkillResult tooSmall = execute("printf 'must-not-run'", 99);
        SkillResult tooLarge = execute("printf 'must-not-run'", 600_001);

        assertThat(tooSmall.isSuccess()).isFalse();
        assertThat(tooSmall.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(tooSmall.getError()).contains("between 100 and 600000");
        assertThat(tooLarge.isSuccess()).isFalse();
        assertThat(tooLarge.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    void rejectsNonNumericTimeoutAsValidationError() {
        SkillResult result = tool.execute(
                Map.of("command", "printf 'must-not-run'", "timeout", "1000"),
                context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("timeout must be an integer");
    }

    @Test
    void rejectsFractionalTimeoutAsValidationError() {
        SkillResult result = tool.execute(
                Map.of("command", "printf 'must-not-run'", "timeout", 100.5),
                context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("timeout must be an integer");
    }

    @Test
    void boundsLargeOutputWithoutBreakingUtf8() {
        SkillResult result = execute(
                "i=0; while [ \"$i\" -lt 20000 ]; do printf '中文'; i=$((i+1)); done");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("[output truncated, exceeded 50000 bytes]");
        assertThat(result.getOutput()).doesNotContain("\uFFFD");
        assertThat(result.getOutput().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(50_100);
    }

    @Test
    void keepsOrdinaryBuildEnvironmentAvailable() {
        SkillResult result = execute("printf '%s' \"$PATH\"");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isNotBlank();
    }

    @Test
    void removesSensitiveEnvironmentFromChildProcess() {
        SkillResult result = execute(
                "env | cut -d= -f1 | while IFS= read -r name; "
                        + "do case \"$name\" in *API_KEY*|*APIKEY*|*TOKEN*|*SECRET*|*PASSWORD*|*PASSWD*"
                        + "|*CREDENTIAL*|*PRIVATE_KEY*) printf '%s\\n' \"$name\";; esac; done");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isBlank();
    }

    @Test
    void terminatesDescendantProcessesOnTimeout() throws Exception {
        Path pidFile = tempDir.resolve("child.pid");
        SkillResult result = execute(
                "sleep 30 & child=$!; printf '%s' \"$child\" > child.pid; wait \"$child\"",
                300);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("timed out");
        long childPid = Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void doesNotWaitIndefinitelyWhenDetachedChildKeepsOutputOpen() {
        SkillResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> execute("sleep 10 & printf 'parent-finished'"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("parent-finished");
    }

    @Test
    void schemaAndDescriptionExposeOnlyExecutionContract() {
        assertThat(tool.getDescription())
                .contains("exit status")
                .contains("600000")
                .doesNotContain("Use Read instead")
                .doesNotContain("Git safety");
        assertThatNoException().isThrownBy(tool::getToolSchema);
    }

    private SkillResult execute(String command) {
        return tool.execute(Map.of("command", command), context());
    }

    private SkillResult execute(String command, int timeout) {
        return tool.execute(Map.of("command", command, "timeout", timeout), context());
    }

    private SkillContext context() {
        return new SkillContext(tempDir.toString(), "bash-tool-test", 1L);
    }
}
