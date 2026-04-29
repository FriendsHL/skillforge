package com.skillforge.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.skillforge")
@EntityScan({"com.skillforge.server.entity", "com.skillforge.observability.entity"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories({
        "com.skillforge.server.repository",
        "com.skillforge.observability.repository"
})
@EnableJpaAuditing
@EnableScheduling
@EnableAsync
public class SkillForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillForgeApplication.class, args);
    }
}
