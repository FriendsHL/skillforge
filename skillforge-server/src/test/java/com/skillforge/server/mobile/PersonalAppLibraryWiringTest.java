package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PersonalAppLibraryWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(PersonalAppComponents.class)
            .withBean(PersonalAppLibraryRepository.class,
                    () -> mock(PersonalAppLibraryRepository.class));

    @Test
    void springCreatesLibraryServiceAndCursorCodecWithManagedObjectMapper() {
        assertThat(PersonalAppCursorCodec.class).hasAnnotation(Component.class);

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(PersonalAppCursorCodec.class);
            assertThat(context).hasSingleBean(PersonalAppLibraryService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = PersonalAppCursorCodec.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {PersonalAppCursorCodec.class, PersonalAppLibraryService.class}))
    static class PersonalAppComponents { }
}
