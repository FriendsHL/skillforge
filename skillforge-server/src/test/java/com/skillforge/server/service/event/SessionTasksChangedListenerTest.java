package com.skillforge.server.service.event;

import com.skillforge.core.engine.ChatEventBroadcaster; import com.skillforge.server.dto.SessionTaskSnapshotResponse; import com.skillforge.server.service.SessionTaskService; import org.junit.jupiter.api.Test; import org.springframework.transaction.event.TransactionPhase; import org.springframework.transaction.event.TransactionalEventListener;
import java.time.Instant; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat; import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;
class SessionTasksChangedListenerTest {
    @Test void broadcastsBoundedSnapshotAfterCommit() throws Exception {
        var annotation=SessionTasksChangedListener.class.getMethod("onChanged",SessionTasksChangedEvent.class).getAnnotation(TransactionalEventListener.class);assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        SessionTaskService service=mock(SessionTaskService.class);ChatEventBroadcaster broadcaster=mock(ChatEventBroadcaster.class);when(service.snapshotForSystem("s1",true,SessionTaskService.MAX_TASKS_PER_SESSION)).thenReturn(new SessionTaskSnapshotResponse("s1",Map.of("total",0L),List.of(),Instant.EPOCH));
        new SessionTasksChangedListener(service,broadcaster).onChanged(new SessionTasksChangedEvent("s1"));verify(broadcaster).sessionTasksSnapshot(eq("s1"),argThat(payload->payload.keySet().containsAll(List.of("sessionId","summary","tasks","generatedAt"))));
    }
}
