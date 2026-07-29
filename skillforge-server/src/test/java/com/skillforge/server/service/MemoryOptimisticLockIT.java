package com.skillforge.server.service;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.repository.MemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryOptimisticLockIT extends AbstractPostgresIT {

    @Autowired private MemoryRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentContentUpdatesCannotSilentlyOverwriteEachOther() throws Exception {
        MemoryEntity memory = new MemoryEntity();
        memory.setUserId(1L);
        memory.setType("knowledge");
        memory.setTitle("CAS");
        memory.setContent("v0");
        memory = repository.saveAndFlush(memory);
        Long id = memory.getId();

        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> updateAfterBarrier(
                    requiresNew, id, "first", loaded, commit, successes, conflicts));
            var second = pool.submit(() -> updateAfterBarrier(
                    requiresNew, id, "second", loaded, commit, successes, conflicts));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            commit.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
    }

    private void updateAfterBarrier(
            TransactionTemplate tx,
            Long id,
            String content,
            CountDownLatch loaded,
            CountDownLatch commit,
            AtomicInteger successes,
            AtomicInteger conflicts) {
        try {
            tx.executeWithoutResult(status -> {
                MemoryEntity current = repository.findById(id).orElseThrow();
                loaded.countDown();
                await(commit);
                current.setContent(content);
                repository.saveAndFlush(current);
            });
            successes.incrementAndGet();
        } catch (OptimisticLockingFailureException ex) {
            conflicts.incrementAndGet();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("barrier interrupted", ex);
        }
    }
}
