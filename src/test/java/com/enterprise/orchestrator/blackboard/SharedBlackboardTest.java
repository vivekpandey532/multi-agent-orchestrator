package com.enterprise.orchestrator.blackboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SharedBlackboardTest {

    private SharedBlackboard blackboard;

    @BeforeEach
    void setUp() { blackboard = new SharedBlackboard(); }

    @Test
    void putAndGet_returnsStoredValue() {
        blackboard.put("s1", "key", "value");
        assertEquals(Optional.of("value"), blackboard.get("s1", "key"));
    }

    @Test
    void get_missingKey_returnsEmpty() {
        assertEquals(Optional.empty(), blackboard.get("s1", "missing"));
    }

    @Test
    void merge_addsMultipleEntries() {
        blackboard.merge("s1", Map.of("a", 1, "b", 2));
        assertEquals(Optional.of(1), blackboard.get("s1", "a"));
        assertEquals(Optional.of(2), blackboard.get("s1", "b"));
    }

    @Test
    void evict_removesSession() {
        blackboard.put("s1", "key", "value");
        blackboard.evict("s1");
        assertEquals(Optional.empty(), blackboard.get("s1", "key"));
    }

    @Test
    void snapshot_returnsUnmodifiableView() {
        blackboard.put("s1", "k", "v");
        Map<String, Object> snap = blackboard.snapshot("s1");
        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));
    }

    @Test
    void concurrentWrites_areThreadSafe() throws InterruptedException {
        int threads = 100;
        CountDownLatch latch = new CountDownLatch(threads);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, threads).forEach(i ->
                    executor.submit(() -> {
                        blackboard.put("s1", "key-" + i, i);
                        latch.countDown();
                    }));
            latch.await();
        }

        assertEquals(threads, blackboard.snapshot("s1").size());
    }

    @Test
    void sessionIsolation_preventsCrossLeak() {
        blackboard.put("s1", "key", "val1");
        blackboard.put("s2", "key", "val2");
        assertEquals(Optional.of("val1"), blackboard.get("s1", "key"));
        assertEquals(Optional.of("val2"), blackboard.get("s2", "key"));
    }
}
