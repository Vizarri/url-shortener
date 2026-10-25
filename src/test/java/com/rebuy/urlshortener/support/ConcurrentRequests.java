package com.rebuy.urlshortener.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Starts concurrent test actions together and cancels unfinished work on failure. */
public final class ConcurrentRequests {

    private ConcurrentRequests() {
    }

    /**
     * Releases actions through one start gate and waits with bounded timeouts.
     *
     * @param actions independent actions, each using its own request data
     * @return results in input order, regardless of completion order
     * @param <T> action result type
     * @throws Exception if an action, synchronization, or result wait fails
     */
    public static <T> List<T> run(List<Callable<T>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (Callable<T> action : actions) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent actions were not released.");
                        }
                        return action.call();
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent actions did not reach the start gate.");
                }
                start.countDown();

                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(20, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                start.countDown();
                for (Future<T> future : futures) {
                    future.cancel(true);
                }
            }
        }
    }
}
