package com.skillforge.server.controller;

import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskService;
import org.junit.jupiter.api.Test;
import java.time.Instant; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat; import static org.mockito.Mockito.*;
class SessionTaskControllerTest {
    @Test void dashboardEndpointReturnsAllStatusesEnvelope(){
        SessionTaskService service=mock(SessionTaskService.class); var expected=new SessionTaskSnapshotResponse("s1",Map.of("deleted",1L),List.of(),Instant.EPOCH);
        when(service.snapshot("s1",7L,true)).thenReturn(expected);
        assertThat(new SessionTaskController(service).list("s1",7L)).isSameAs(expected); verify(service).snapshot("s1",7L,true);
    }
}
