package com.skillforge.server.mobile;

import com.skillforge.server.dto.SessionTaskSnapshotResponse; import com.skillforge.server.service.TeamTaskGraphService; import org.junit.jupiter.api.Test; import org.springframework.mock.web.MockHttpServletRequest; import org.springframework.web.server.ResponseStatusException;
import java.time.Instant; import java.util.List; import java.util.Map; import java.util.Set; import java.util.UUID;
import static org.assertj.core.api.Assertions.*; import static org.mockito.Mockito.*;
class MobileSessionTaskControllerTest {
    @Test void mobileEndpointUsesPrincipalAndChatReadScope(){
        TeamTaskGraphService service=mock(TeamTaskGraphService.class);var expected=new SessionTaskSnapshotResponse("s1",Map.of(),List.of(),Instant.EPOCH);when(service.snapshot("s1",7L,true,false)).thenReturn(expected);
        MockHttpServletRequest request=new MockHttpServletRequest();request.setAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE,new MobileDevicePrincipal(UUID.randomUUID(),7L,"iPhone",Set.of("chat:read")));
        assertThat(new MobileSessionTaskController(service).list("s1",request)).isSameAs(expected);verify(service).snapshot("s1",7L,true,false);
    }
    @Test void missingScopeIsForbidden(){
        MockHttpServletRequest request=new MockHttpServletRequest();request.setAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE,new MobileDevicePrincipal(UUID.randomUUID(),7L,"iPhone",Set.of()));
        assertThatThrownBy(()->new MobileSessionTaskController(mock(TeamTaskGraphService.class)).list("s1",request)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
    }
}
