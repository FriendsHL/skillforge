package com.skillforge.server.session;

import com.skillforge.core.engine.durability.LoopDurabilityScope;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps one fenced loop lease alive while its Provider/Tool work is in progress. */
@Service
public class SessionLoopLeaseHeartbeat {

    static final long HEARTBEAT_INTERVAL_SECONDS = 30L;

    private static final Logger log = LoggerFactory.getLogger(SessionLoopLeaseHeartbeat.class);

    private final SessionLoopAdmissionService admissionService;
    private final ScheduledExecutorService scheduler;

    @Autowired
    public SessionLoopLeaseHeartbeat(SessionLoopAdmissionService admissionService) {
        this(admissionService, Executors.newScheduledThreadPool(1, daemonThreadFactory()));
    }

    SessionLoopLeaseHeartbeat(
            SessionLoopAdmissionService admissionService,
            ScheduledExecutorService scheduler) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public Handle start(LoopDurabilityScope scope) {
        Objects.requireNonNull(scope, "scope");
        admissionService.renew(scope);
        AtomicReference<RuntimeException> authorityFailure = new AtomicReference<>();
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                admissionService.renew(scope);
                authorityFailure.set(null);
            } catch (RuntimeException failure) {
                authorityFailure.set(failure);
                log.warn("Durable loop heartbeat failed: sessionId={} fence={}",
                        scope.sessionId(), scope.loopFence());
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return new Handle(scope, admissionService, task, authorityFailure);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "session-loop-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
    }

    public static final class Handle implements AutoCloseable {
        private final LoopDurabilityScope scope;
        private final SessionLoopAdmissionService admissionService;
        private final ScheduledFuture<?> task;
        private final AtomicReference<RuntimeException> authorityFailure;
        private boolean closed;

        private Handle(
                LoopDurabilityScope scope,
                SessionLoopAdmissionService admissionService,
                ScheduledFuture<?> task,
                AtomicReference<RuntimeException> authorityFailure) {
            this.scope = scope;
            this.admissionService = admissionService;
            this.task = task;
            this.authorityFailure = authorityFailure;
        }

        /** Synchronous boundary check used before terminal Session mutation. */
        public synchronized SessionLoopAdmissionService.LeaseAck assertAuthoritative() {
            if (closed) throw new IllegalStateException("Durable loop heartbeat is closed");
            SessionLoopAdmissionService.LeaseAck ack = admissionService.renew(scope);
            authorityFailure.set(null);
            return ack;
        }

        public RuntimeException lastFailure() {
            return authorityFailure.get();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            task.cancel(false);
        }
    }
}
