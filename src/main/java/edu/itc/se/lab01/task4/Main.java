package edu.itc.se.lab01.task4;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class Main {
    //  CPU-BOUND PRIME COUNTER

    static long countPrimes(long from, long to) {
        long count = 0;

        for (long n = Math.max(from, 2); n < to; n++) {
            boolean prime = true;

            for (long d = 2; d * d <= n; d++) {
                if (n % d == 0) {
                    prime = false;
                    break;
                }
            }

            if (prime) {
                count++;
            }
        }

        return count;
    }

    static class PrimeCounter implements Callable<Long> {

        private final long from;
        private final long to;

        PrimeCounter(long from, long to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public Long call() {
            return countPrimes(from, to);
        }
    }



    // I/O-BOUND FAKE DOWNLOAD

    static class FakeDownload implements Callable<String> {

        private final String url;
        private final int id;

        FakeDownload(String url, int id) {
            this.url = url;
            this.id = id;
        }

        @Override
        public String call() throws Exception {

            // Every 50th download fails
            if (id % 50 == 0) {
                throw new IOException("boom");
            }

            // Download #1 takes 5 seconds
            if (id == 1) {
                Thread.sleep(5000);
            } else {
                Thread.sleep(200);
            }

            int length = url.length();

            return url + " -> " + length;
        }
    }



    // DOWNLOAD TEST


    static void runDownloads(
            String name,
            ExecutorService executor) {

        long t0 = System.nanoTime();

        List<Future<String>> futures = new ArrayList<>();

        // Submit 500 downloads
        for (int i = 1; i <= 500; i++) {

            String url =
                    "https://example.com/file/" + i;

            futures.add(
                    executor.submit(
                            new FakeDownload(url, i)
                    )
            );
        }

        int ok = 0;
        int failed = 0;
        int timedOut = 0;

        // Collect results
        for (int i = 0; i < futures.size(); i++) {

            Future<String> future = futures.get(i);

            try {

                // First download has a 1-second timeout
                if (i == 0) {

                    future.get(
                            1,
                            TimeUnit.SECONDS
                    );

                } else {

                    future.get();
                }

                ok++;

            } catch (TimeoutException e) {

                timedOut++;

                future.cancel(true);

            } catch (ExecutionException e) {

                failed++;
            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
                break;
            }
        }

        long elapsed =
                (System.nanoTime() - t0) / 1_000_000;

        System.out.printf(
                "%s : ok=%d failed=%d timedOut=%d in %d ms%n",
                name,
                ok,
                failed,
                timedOut,
                elapsed
        );

        // JDK 25: ExecutorService implements AutoCloseable
        executor.close();
    }



    // MAIN


    public static void main(String[] args) throws Exception {

        final long LIMIT = 4_000_000;
        final int CHUNKS = 16;

        // =====================================================
        // SINGLE-THREADED CPU TEST
        // =====================================================

        long t0 = System.nanoTime();

        long single =
                countPrimes(0, LIMIT);

        System.out.printf(
                "single-threaded: %d primes in %d ms%n",
                single,
                (System.nanoTime() - t0) / 1_000_000
        );


        // =====================================================
        // MULTI-THREADED CPU TEST
        // =====================================================

        t0 = System.nanoTime();

        int processors =
                Runtime.getRuntime().availableProcessors();

        try (ExecutorService pool =
                     Executors.newFixedThreadPool(processors)) {

            List<Future<Long>> futures =
                    new ArrayList<>();

            long chunkSize =
                    LIMIT / CHUNKS;

            // Split 0 ... 4,000,000 into 16 chunks
            for (int i = 0; i < CHUNKS; i++) {

                long from =
                        i * chunkSize;

                long to;

                if (i == CHUNKS - 1) {
                    to = LIMIT;
                } else {
                    to = from + chunkSize;
                }

                futures.add(
                        pool.submit(
                                new PrimeCounter(
                                        from,
                                        to
                                )
                        )
                );
            }

            long total = 0;

            // Collect Future results
            for (Future<Long> future : futures) {
                total += future.get();
            }

            System.out.printf(
                    "pool (%d threads): %d primes in %d ms%n",
                    processors,
                    total,
                    (System.nanoTime() - t0) / 1_000_000
            );
        }


        // =====================================================
        // I/O TEST: 20 PLATFORM THREADS
        // =====================================================

        runDownloads(
                "downloads on fixed(20) platform threads",
                Executors.newFixedThreadPool(20)
        );


        // =====================================================
        // I/O TEST: VIRTUAL THREADS
        // =====================================================

        runDownloads(
                "downloads on virtual threads",
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }
}