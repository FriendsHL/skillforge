package com.skillforge.server.session;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionLoopLeaseHeartbeatWiringTest {

    @Test
    void springContext_selectsProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SessionLoopAdmissionService.class,
                    () -> mock(SessionLoopAdmissionService.class));
            context.register(SessionLoopLeaseHeartbeat.class);
            context.refresh();

            assertThat(context.getBean(SessionLoopLeaseHeartbeat.class)).isNotNull();
        }
    }
}
