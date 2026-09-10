package com.skillforge.server.history.query;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHistoryQueryTransactionContractTest {

    @Test
    void searchAndRead_useReadOnlyRepeatableReadTransactions() throws Exception {
        for (String method : new String[]{"search", "read"}) {
            Transactional transaction = java.util.Arrays.stream(
                            SessionHistoryQueryService.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(method))
                    .findFirst().orElseThrow()
                    .getAnnotation(Transactional.class);

            assertThat(transaction).isNotNull();
            assertThat(transaction.readOnly()).isTrue();
            assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        }
    }
}
