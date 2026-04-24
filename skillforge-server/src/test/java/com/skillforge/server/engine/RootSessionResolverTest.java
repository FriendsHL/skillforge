package com.skillforge.server.engine;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RootSessionResolverTest {

    private SessionEntity make(String id, String parent) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setParentSessionId(parent);
        return s;
    }

    @Test
    @DisplayName("root: parent null → returns self")
    void rootReturnsSelf() {
        SessionService svc = mock(SessionService.class);
        when(svc.getSession("root")).thenReturn(make("root", null));
        RootSessionResolver r = new RootSessionResolver(svc);
        assertThat(r.resolveRoot("root")).isEqualTo("root");
    }

    @Test
    @DisplayName("child 1-level: returns parent")
    void oneLevel() {
        SessionService svc = mock(SessionService.class);
        when(svc.getSession("child")).thenReturn(make("child", "root"));
        when(svc.getSession("root")).thenReturn(make("root", null));
        RootSessionResolver r = new RootSessionResolver(svc);
        assertThat(r.resolveRoot("child")).isEqualTo("root");
    }

    @Test
    @DisplayName("5-level chain walks correctly to root")
    void fiveLevel() {
        SessionService svc = mock(SessionService.class);
        when(svc.getSession("l0")).thenReturn(make("l0", null));
        when(svc.getSession("l1")).thenReturn(make("l1", "l0"));
        when(svc.getSession("l2")).thenReturn(make("l2", "l1"));
        when(svc.getSession("l3")).thenReturn(make("l3", "l2"));
        when(svc.getSession("l4")).thenReturn(make("l4", "l3"));
        RootSessionResolver r = new RootSessionResolver(svc);
        assertThat(r.resolveRoot("l4")).isEqualTo("l0");
    }

    @Test
    @DisplayName("depth > 10: falls back to input sessionId (W10 conservative)")
    void depthCap() {
        SessionService svc = mock(SessionService.class);
        // a chain that never bottoms out
        Map<String, SessionEntity> chain = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            chain.put("l" + i, make("l" + i, "l" + (i + 1)));
        }
        when(svc.getSession(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> chain.get(inv.getArgument(0)));
        RootSessionResolver r = new RootSessionResolver(svc);
        assertThat(r.resolveRoot("l0")).isEqualTo("l0"); // falls back to input
    }

    @Test
    @DisplayName("self-loop (parent==self): falls back to input")
    void selfLoop() {
        SessionService svc = mock(SessionService.class);
        when(svc.getSession("x")).thenReturn(make("x", "x"));
        RootSessionResolver r = new RootSessionResolver(svc);
        assertThat(r.resolveRoot("x")).isEqualTo("x");
    }

    @Test
    @DisplayName("getSession throws mid-walk: falls back to original input")
    void midWalkException() {
        SessionService svc = mock(SessionService.class);
        when(svc.getSession("child")).thenReturn(make("child", "missing"));
        when(svc.getSession("missing")).thenThrow(new RuntimeException("not found"));
        RootSessionResolver r = new RootSessionResolver(svc);
        assertThat(r.resolveRoot("child")).isEqualTo("child");
    }

    @Test
    @DisplayName("null input → null output")
    void nullInput() {
        RootSessionResolver r = new RootSessionResolver(mock(SessionService.class));
        assertThat(r.resolveRoot(null)).isNull();
    }
}
