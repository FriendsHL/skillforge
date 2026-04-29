package com.skillforge.observability.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OBS-1 §2.4 / §3.5 / §10.1 (R3-WN6).
 *
 * <p>**关键设计**：本模块**只**贡献一个 {@link Jackson2ObjectMapperBuilderCustomizer} bean，
 * 不再 publish {@code @Bean ObjectMapper}。
 *
 * <p>原因：如果发布 {@code @Bean ObjectMapper}，会触发
 * {@code JacksonAutoConfiguration.@ConditionalOnMissingBean(ObjectMapper.class)} 让
 * Spring Boot 默认 ObjectMapper bean 不再注册，server 端遍布 5+ 处 {@code @Autowired ObjectMapper}
 * 会拿到本模块的 mapper（缺 application.yml: spring.jackson.* 配置），引发隐性回归。
 *
 * <p>用 customizer 参与 Spring Boot Jackson auto-config 的 builder 链，既保留 yaml 配置，
 * 又把 OBS-1 需要的 {@code JavaTimeModule} + {@code WRITE_DATES_AS_TIMESTAMPS=false} 追加上。
 */
@Configuration
public class ObservabilityJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer observabilityJacksonCustomizer() {
        return builder -> builder
                .modulesToInstall(JavaTimeModule.class)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
