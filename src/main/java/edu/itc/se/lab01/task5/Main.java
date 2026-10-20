package edu.itc.se.lab01.task5;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Main {

    private static final int ACCOUNT_COUNT = 8;
    private static final int INITIAL_BALANCE = 500;
    private static final int THREAD_COUNT = 8;
    private static final int TRANSFERS_PER_THREAD = 10_000;

    // =========================================================
    // Account for the broken and ordered-lock implementations
    // =========================================================

    static class Account {
        final int id;
        int balance;

        Account(int id, int balance) {
            this.id = id;
            this.balance = balance;
        }
    }

    // =========================================================
    // Account for the tryLock implementation
    // =========================================================

    static class LockAccount {
        final int id;
        int balance;
        final ReentrantLock lock = new ReentrantLock();

        LockAccount(int id, int balance) {
            this.id = id;
            this.balance = balance;
        }
    }

    // =========================================================
    // Create accounts
    // =========================================================

    static Account[] createAccounts() {
        Account[] accounts = new Account[ACCOUNT_COUNT];

        for (int i = 0; i < accounts.length; i++) {
            accounts[i] = new Account(i, INITIAL_BALANCE);
        }

        return accounts;
    }

    static LockAccount[] createLockAccounts() {
        LockAccount[] accounts = new LockAccount[ACCOUNT_COUNT];

        for (int i = 0; i < accounts.length; i++) {
            accounts[i] = new LockAccount(i, INITIAL_BALANCE);
        }

        return accounts;
    }

    // =========================================================
    // Calculate total balance
    // =========================================================

    static int totalBalance(Account[] accounts) {
        int total = 0;

        for (Account account : accounts) {
            synchronized (account) {
                total += account.balance;
            }
        }

        return total;
    }

    static int totalLockBalance(LockAccount[] accounts) {
        int total = 0;

        // Lock all accounts in ascending order while reading.
        for (LockAccount account : accounts) {
            account.lock.lock();
        }

        try {
            for (LockAccount account : accounts) {
                total += account.balance;
            }
        } finally {
            for (int i = accounts.length - 1; i >= 0; i--) {
                accounts[i].lock.unlock();
            }
        }

        return total;
    }

    // =========================================================
    // Step 1: Broken transfer that can cause deadlock
    // =========================================================

    static void brokenTransfer(Account from, Account to, int amount) {
        synchronized (from) {
            // Give another thread a chance to acquire the other account.
            Thread.yield();

            synchronized (to) {
                if (from.balance >= amount) {
                    from.balance -= amount;
                    to.balance += amount;
                }
            }
        }
    }

    // =========================================================
    // Step 2: Watchdog detects deadlocked threads
    // =========================================================

    static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            ThreadMXBean bean =
                    ManagementFactory.getThreadMXBean();

            while (!Thread.currentThread().isInterrupted()) {
                long[] deadlockedIds = bean.findDeadlockedThreads();

                if (deadlockedIds != null) {
                    var threadInfo = bean.getThreadInfo(deadlockedIds);

                    StringBuilder names = new StringBuilder();

                    for (var info : threadInfo) {
                        if (info != null) {
                            if (!names.isEmpty()) {
                                names.append(", ");
                            }

                            names.append(info.getThreadName());
                        }
                    }

                    System.out.println(
                            "[watchdog] DEADLOCK detected between: "
                                    + names
                    );

                    return;
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "deadlock-watchdog");

        watchdog.setDaemon(true);
        watchdog.start();
    }

    static void runDeadlock() {
        Account[] accounts = createAccounts();

        startWatchdog();

        Random random = new Random();
        Thread[] workers = new Thread[THREAD_COUNT];

        for (int i = 0; i < workers.length; i++) {
            final int workerId = i;

            workers[i] = new Thread(() -> {
                Random localRandom = new Random();

                while (!Thread.currentThread().isInterrupted()) {
                    int fromIndex = localRandom.nextInt(accounts.length);
                    int toIndex = localRandom.nextInt(accounts.length);

                    if (fromIndex == toIndex) {
                        continue;
                    }

                    brokenTransfer(
                            accounts[fromIndex],
                            accounts[toIndex],
                            1
                    );
                }
            }, "transfer-" + workerId);

            workers[i].start();
        }

        System.out.println(
                "[main] Broken transfer started. "
                        + "Wait for the watchdog to detect a deadlock."
        );
        System.out.println(
                "[main] The program may hang. Press Ctrl+C after "
                        + "capturing the jstack output."
        );

        // Keep the main thread alive while workers run.
        try {
            for (Thread worker : workers) {
                worker.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================
    // Step 3: Fix A - acquire locks in a consistent order
    // =========================================================

    static void orderedTransfer(Account first, Account second, int amount) {
        Account lower = first.id < second.id ? first : second;
        Account higher = first.id < second.id ? second : first;

        synchronized (lower) {
            synchronized (higher) {
                if (first.balance >= amount) {
                    first.balance -= amount;
                    second.balance += amount;
                }
            }
        }
    }

    static void runOrdered() throws InterruptedException {
        Account[] accounts = createAccounts();
        Thread[] workers = new Thread[THREAD_COUNT];

        long start = System.nanoTime();

        for (int i = 0; i < workers.length; i++) {
            final int workerId = i;

            workers[i] = new Thread(() -> {
                Random random = new Random();

                for (int j = 0; j < TRANSFERS_PER_THREAD; j++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    int fromIndex = random.nextInt(accounts.length);
                    int toIndex = random.nextInt(accounts.length);

                    if (fromIndex == toIndex) {
                        continue;
                    }

                    orderedTransfer(
                            accounts[fromIndex],
                            accounts[toIndex],
                            1
                    );
                }
            }, "ordered-transfer-" + workerId);

            workers[i].start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        long elapsedMs =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        int total = totalBalance(accounts);
        int expected = ACCOUNT_COUNT * INITIAL_BALANCE;

        System.out.println("\n# OrderedBank");
        System.out.printf(
                "transfers=%d total=%d (expected %d) in %d ms%n",
                THREAD_COUNT * TRANSFERS_PER_THREAD,
                total,
                expected,
                elapsedMs
        );
    }

    // =========================================================
    // Step 4: Fix B - ReentrantLock.tryLock
    // =========================================================

    static final AtomicLong retryCount = new AtomicLong();

    static boolean tryLockTransfer(
            LockAccount from,
            LockAccount to,
            int amount,
            Random random
    ) throws InterruptedException {

        boolean fromLocked = false;
        boolean toLocked = false;

        try {
            fromLocked = from.lock.tryLock(10, TimeUnit.MILLISECONDS);

            if (!fromLocked) {
                retryCount.incrementAndGet();
                return false;
            }

            toLocked = to.lock.tryLock(10, TimeUnit.MILLISECONDS);

            if (!toLocked) {
                retryCount.incrementAndGet();
                return false;
            }

            if (from.balance >= amount) {
                from.balance -= amount;
                to.balance += amount;
            }

            return true;

        } finally {
            if (toLocked) {
                to.lock.unlock();
            }

            if (fromLocked) {
                from.lock.unlock();
            }

            // Back off after a failed attempt.
            if (!fromLocked || !toLocked) {
                TimeUnit.MILLISECONDS.sleep(random.nextInt(1, 11));
            }
        }
    }

    static void runTryLock() throws InterruptedException {
        LockAccount[] accounts = createLockAccounts();
        Thread[] workers = new Thread[THREAD_COUNT];

        retryCount.set(0);

        long start = System.nanoTime();

        for (int i = 0; i < workers.length; i++) {
            final int workerId = i;

            workers[i] = new Thread(() -> {
                Random random = new Random();
                int completed = 0;

                while (completed < TRANSFERS_PER_THREAD
                        && !Thread.currentThread().isInterrupted()) {

                    int fromIndex = random.nextInt(accounts.length);
                    int toIndex = random.nextInt(accounts.length);

                    if (fromIndex == toIndex) {
                        continue;
                    }

                    try {
                        boolean success = tryLockTransfer(
                                accounts[fromIndex],
                                accounts[toIndex],
                                1,
                                random
                        );

                        if (success) {
                            completed++;
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "trylock-transfer-" + workerId);

            workers[i].start();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        long elapsedMs =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        int total = totalLockBalance(accounts);
        int expected = ACCOUNT_COUNT * INITIAL_BALANCE;

        System.out.println("\n# TryLockBank");
        System.out.printf(
                "transfers=%d retries=%d total=%d (expected %d) in %d ms%n",
                THREAD_COUNT * TRANSFERS_PER_THREAD,
                retryCount.get(),
                total,
                expected,
                elapsedMs
        );
    }

    // =========================================================
    // Step 5: Cooperative cancellation and graceful shutdown
    // =========================================================

    static void runShutdown() throws InterruptedException {
        Account[] accounts = createAccounts();
        Thread[] workers = new Thread[THREAD_COUNT];
        AtomicLong[] completed = new AtomicLong[THREAD_COUNT];

        for (int i = 0; i < completed.length; i++) {
            completed[i] = new AtomicLong();
        }

        for (int i = 0; i < workers.length; i++) {
            final int workerId = i;

            workers[i] = new Thread(() -> {
                Random random = new Random();

                while (!Thread.currentThread().isInterrupted()) {
                    int fromIndex = random.nextInt(accounts.length);
                    int toIndex = random.nextInt(accounts.length);

                    if (fromIndex == toIndex) {
                        continue;
                    }

                    orderedTransfer(
                            accounts[fromIndex],
                            accounts[toIndex],
                            1
                    );

                    completed[workerId].incrementAndGet();
                }

                System.out.printf(
                        "transfer-%d stopped after %d transfers%n",
                        workerId,
                        completed[workerId].get()
                );
            }, "transfer-" + workerId);

            workers[i].start();
        }

        long runDurationMs = 2_000;
        Thread.sleep(runDurationMs);

        System.out.printf(
                "\n[main] interrupting %d workers after %d ms%n",
                THREAD_COUNT,
                runDurationMs
        );

        for (Thread worker : workers) {
            worker.interrupt();
        }

        for (Thread worker : workers) {
            worker.join();
        }

        int total = totalBalance(accounts);
        int expected = ACCOUNT_COUNT * INITIAL_BALANCE;

        boolean allStopped = true;

        for (Thread worker : workers) {
            if (worker.isAlive()) {
                allStopped = false;
                break;
            }
        }

        System.out.printf(
                "all workers stopped=%s total=%d (expected %d)%n",
                allStopped,
                total,
                expected
        );
    }

    // =========================================================
    // Main - choose which demonstration to run
    // =========================================================

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length == 0 ? "ordered" : args[0];

        switch (mode.toLowerCase()) {
            case "deadlock" -> runDeadlock();
            case "ordered" -> runOrdered();
            case "trylock" -> runTryLock();
            case "shutdown" -> runShutdown();

            default -> {
                System.out.println("Usage:");
                System.out.println("  java Main deadlock");
                System.out.println("  java Main ordered");
                System.out.println("  java Main trylock");
                System.out.println("  java Main shutdown");
            }
        }
    }
}
