package com.skillforge.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionRecoveryDefaultTest {

    @Test
    void legacyRecoveryPayloadIsDefaultOffInJavaConfiguration() throws Exception {
        Method factory = java.util.Arrays.stream(CompactionConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("recoveryPayloadBuilder"))
                .findFirst()
                .orElseThrow();

        String enabledExpression = java.util.Arrays.stream(factory.getParameterAnnotations())
                .flatMap(java.util.Arrays::stream)
                .filter(annotation -> annotation.annotationType() == Value.class)
                .map(annotation -> ((Value) annotation).value())
                .filter(value -> value.contains("compact.recovery.enabled"))
                .findFirst()
                .orElseThrow();

        assertThat(enabledExpression)
                .isEqualTo("${skillforge.compact.recovery.enabled:false}");
    }
}
