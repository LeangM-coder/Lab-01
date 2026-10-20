package edu.itc.se.lab01.challenge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

class WordCount {
    // Small data holders
    record Config(Path dir, int workers, boolean virtual, int perFileDelayMs, boolean progress) {
    }
    record Result(boolean partial, long filesDone, long totalFiles, long words,
                  long elapsedNanos, Map<String, Long> counts, String mode) {
    }
    // One run of the pipeline
    static final class Run {
        // Compared by identity (==), so it can never clash with a real file.
        private static final Path POISON = Path.of("poison-pill");
        private final Config cfg;
        private final BlockingQueue<Path> queue = new ArrayBlockingQueue<>(16);
        private final ConcurrentHashMap<String, LongAdder> map = new ConcurrentHashMap<>();
        private final LongAdder words = new LongAdder();
        private final LongAdder filesDone = new LongAdder();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final CountDownLatch workersDone;
        private final List<Thread> workers = new ArrayList<>();
        private Thread scanner;
        private Thread reporter;
        private volatile long totalFiles;
        private volatile long startNanos;

        Run(Config cfg) {
            this.cfg = cfg;
            this.workersDone = new CountDownLatch(cfg.workers());
        }

        String mode() {
            return (cfg.virtual() ? "virtual" : "platform") + "(" + cfg.workers() + ")";
        }

        void start() throws IOException {
            try (Stream<Path> s = Files.walk(cfg.dir())) {
                totalFiles = s.filter(Files::isRegularFile).count();
            }
            startNanos = System.nanoTime();

            scanner = new Thread(this::scan, "scanner");

            for (int i = 0; i < cfg.workers(); i++) {
                String name = "worker-" + (i + 1);
                Thread t = cfg.virtual()
                        ? Thread.ofVirtual().name(name).unstarted(this::work)
                        : Thread.ofPlatform().name(name).unstarted(this::work);
                workers.add(t);
            }

            reporter = new Thread(this::report, "reporter");
            reporter.setDaemon(true);

            scanner.start();
            workers.forEach(Thread::start);
            if (cfg.progress()) {
                reporter.start();
            }
        }

        //scanner
        private void scan() {
            try (Stream<Path> s = Files.walk(cfg.dir())) {
                Iterator<Path> it = s.filter(Files::isRegularFile).iterator();
                while (it.hasNext()) {
                    queue.put(it.next()); // blocks when the queue is full = back-pressure
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // workers are interrupted too, no pills needed
            } catch (IOException | UncheckedIOException e) {
                System.err.println("scanner error: " + e.getMessage());
            }
            // One poison pill per worker.
            try {
                for (int i = 0; i < cfg.workers(); i++) {
                    queue.put(POISON);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // worker

        private void work() {
            try {
                // Check the interrupt flag between files.
                while (!Thread.currentThread().isInterrupted()) {
                    Path path;
                    try {
                        path = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // restore the flag
                        break;
                    }
                    if (path == POISON) {
                        break;
                    }
                    if (!processFile(path)) {
                        break; // interrupted in the middle of a file
                    }
                    filesDone.increment();
                }
            } finally {
                workersDone.countDown();
            }
        }

        /** Returns false if the thread was interrupted before the file was finished. */
        private boolean processFile(Path path) {
            if (cfg.perFileDelayMs() > 0) { // only used by tests to simulate slow disks
                try {
                    Thread.sleep(cfg.perFileDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {

                StringBuilder sb = new StringBuilder();
                String line;

                while ((line = in.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        return false;
                    }

                    // Split on non-letters and lower-case, by hand (no regex = faster).
                    int i = 0;
                    int n = line.length();
                    while (i <= n) {
                        int cp = i < n ? line.codePointAt(i) : ' ';
                        if (Character.isLetter(cp)) {
                            sb.appendCodePoint(Character.toLowerCase(cp));
                        } else if (!sb.isEmpty()) {
                            add(sb.toString());
                            sb.setLength(0);
                        }
                        i += Character.charCount(cp);
                    }
                }
            } catch (IOException e) {
                System.err.println("cannot read " + path + ": " + e.getMessage());
            }
            return true;
        }

        private void add(String word) {
            LongAdder adder = map.get(word);
            if (adder == null) {
                adder = map.computeIfAbsent(word, k -> new LongAdder());
            }
            adder.increment(); // lock-free
            words.increment();
        }

        // ----- reporter -----

        private void report() {
            try {
                while (true) {
                    Thread.sleep(500);
                    double sec = (System.nanoTime() - startNanos) / 1e9;
                    long w = words.sum();
                    System.out.printf("[%.1fs] files %d/%d  words %,d  %,d w/s  top: %s%n",
                            sec, filesDone.sum(), totalFiles, w, (long) (w / sec),
                            topString(3));
                }
            } catch (InterruptedException e) {
                // normal end
            }
        }

        private String topString(int n) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : top(liveCounts(), n)) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(String.format("%s(%,d)", e.getKey(), e.getValue()));
            }
            return sb.toString();
        }

        /** Reads the adders without locking the map. */
        private Map<String, Long> liveCounts() {
            Map<String, Long> copy = new HashMap<>();
            map.forEach((k, v) -> copy.put(k, v.sum()));
            return copy;
        }

        // ----- shutdown -----

        /** Called by the shutdown hook AND by the --timeout deadline. */
        void shutdown() {
            stopping.set(true);

            if (scanner != null) {
                scanner.interrupt();
            }
            workers.forEach(Thread::interrupt);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); // wait at most 2 s
            List<Thread> all = new ArrayList<>(workers);
            if (scanner != null) {
                all.add(scanner);
            }
            for (Thread t : all) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                try {
                    t.join(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        /** Waits for the work to finish; timeoutMs <= 0 means no deadline. */
        Result await(long timeoutMs) throws InterruptedException {
            boolean finished = true;
            if (timeoutMs <= 0) {
                workersDone.await();
            } else {
                finished = workersDone.await(timeoutMs, TimeUnit.MILLISECONDS);
            }

            if (!finished) {
                if (cfg.progress()) {
                    System.out.printf("[%.1fs] deadline reached - stopping%n",
                            (System.nanoTime() - startNanos) / 1e9);
                }
                shutdown();
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            List<Thread> all = new ArrayList<>(workers);
            all.add(scanner);
            for (Thread t : all) {
                t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            }

            reporter.interrupt();
            return snapshot();
        }

        Result snapshot() {
            long done = filesDone.sum();
            boolean partial = stopping.get() && done < totalFiles;
            return new Result(partial, done, totalFiles, words.sum(),
                    System.nanoTime() - startNanos, liveCounts(), mode());
        }
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    /** Highest counts first; ties are broken alphabetically so the output is stable. */
    static List<Map.Entry<String, Long>> top(Map<String, Long> counts, int n) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return list.subList(0, Math.min(n, list.size()));
    }

    static void printResult(Result r, int topN, Path csv) throws IOException {
        double sec = r.elapsedNanos() / 1e9;
        String head = r.partial() ? "[PARTIAL]" : "DONE";
        System.out.printf("%s files=%d words=%,d elapsed=%.2fs mode=%s%n",
                head, r.filesDone(), r.words(), sec, r.mode());

        List<Map.Entry<String, Long>> top = top(r.counts(), topN);
        List<String> csvLines = new ArrayList<>();
        csvLines.add("rank,word,count,share_percent");

        System.out.printf("%5s  %-12s %12s %9s%n", "rank", "word", "count", "share");
        int rank = 1;
        for (Map.Entry<String, Long> e : top) {
            double share = r.words() == 0 ? 0 : 100.0 * e.getValue() / r.words();
            System.out.printf("%5d  %-12s %,12d %7.2f %%%n", rank, e.getKey(), e.getValue(), share);
            csvLines.add(String.format(java.util.Locale.ROOT, "%d,%s,%d,%.2f",
                    rank, e.getKey(), e.getValue(), share));
            rank++;
        }

        Files.write(csv, csvLines);
        System.out.println(csv.getFileName() + " written");
    }

    // ---------------------------------------------------------
    // main
    // ---------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--generate")) {
            // WordCount --generate <dir> [files=200] [wordsPerFile=50000]
            int files = args.length > 2 ? Integer.parseInt(args[2]) : 200;
            int wordsPerFile = args.length > 3 ? Integer.parseInt(args[3]) : 50_000;
            DataGenerator.generate(Path.of(args[1]), files, wordsPerFile, 2_000, 42);
            System.out.printf("wrote %d files x %,d words to %s%n", files, wordsPerFile, args[1]);
            return;
        }

        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("Usage: WordCount <dir> [--top N] [--timeout SECONDS] [--virtual] [--workers N]");
            System.err.println("       WordCount --generate <dir> [files] [wordsPerFile]");
            System.exit(2);
        }

        Path dir = Path.of(args[0]);
        int top = 10;
        long timeoutSec = 0;
        boolean virtual = false;
        int workers = Runtime.getRuntime().availableProcessors();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--timeout" -> timeoutSec = Long.parseLong(args[++i]);
                case "--virtual" -> virtual = true;
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(2);
                }
            }
        }

        if (!Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + dir);
            System.exit(2);
        }

        Run run = new Run(new Config(dir, workers, virtual, 0, true));
        Thread mainThread = Thread.currentThread();

        // Ctrl+C: same shutdown() as the deadline. Then wait for main to print the result.
        Thread hook = new Thread(() -> {
            run.shutdown();
            try {
                mainThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);

        run.start();
        Result result = run.await(timeoutSec * 1000);

        printResult(result, top, Path.of("result.csv"));

        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down (Ctrl+C), nothing to remove
        }
    }

    // ---------------------------------------------------------
    // Single-threaded reference (used by the tests)
    // ---------------------------------------------------------

    static final class SingleThreadedReference {

        public static Map<String, Long> count(Path dir) throws IOException {
            Map<String, Long> counts = new HashMap<>();

            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                        if (!w.isEmpty()) {
                            counts.merge(w, 1L, Long::sum);
                        }
                    }
                }
            }

            return counts;
        }
    }

    // ---------------------------------------------------------
    // Test data generator
    // ---------------------------------------------------------

    static final class DataGenerator {

        private static final String[] COMMON = {"the", "and", "of", "to", "a", "in", "is", "it"};

        public static void generate(Path dir, int files, int wordsPerFile, int vocabSize, long seed)
                throws IOException {

            Files.createDirectories(dir);
            Random random = new Random(seed);

            // Fixed vocabulary: a few real common words first, then random lower-case words.
            Set<String> set = new LinkedHashSet<>(List.of(COMMON));
            while (set.size() < vocabSize) {
                int len = 2 + random.nextInt(8);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    sb.append((char) ('a' + random.nextInt(26)));
                }
                set.add(sb.toString());
            }
            List<String> vocab = new ArrayList<>(set);

            for (int f = 0; f < files; f++) {
                Path file = dir.resolve(String.format("file-%05d.txt", f));

                try (BufferedWriter out = Files.newBufferedWriter(file)) {
                    for (int w = 0; w < wordsPerFile; w++) {
                        // r^3 makes low indexes (the common words) much more likely
                        int idx = (int) (vocab.size() * Math.pow(random.nextDouble(), 3));
                        out.write(vocab.get(idx));
                        out.write((w % 12 == 11) ? '\n' : ' ');
                    }
                    out.write('\n');
                }
            }
        }
    }
}