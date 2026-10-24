package com.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.context.annotation.Import;
/**
 * Proves the migration is real: both the HTTP request thread and the application
 * executor run on virtual threads. The probe controller exists only in the test tree.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(VirtualThreadsIT.ThreadProbe.class)
@Testcontainers
class VirtualThreadsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @RestController
    static class ThreadProbe {
        @GetMapping("/test-probe/thread")
        boolean requestThreadIsVirtual() {
            return Thread.currentThread().isVirtual();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor executor;

    @Test
    @DisplayName("An HTTP request is handled on a virtual thread")
    void requestPathRunsOnVirtualThread() {
        Boolean virtual = restTemplate.getForObject("/test-probe/thread", Boolean.class);
        assertThat(virtual).isTrue();
    }

    @Test
    @DisplayName("The application executor runs tasks on virtual threads")
    void applicationExecutorRunsOnVirtualThreads() throws Exception {
        boolean virtual = executor.submit(() -> Thread.currentThread().isVirtual()).get();
        assertThat(virtual).isTrue();
    }
}