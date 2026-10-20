# Lab 01 - Task 4: ExecutorService, Futures and Virtual Threads

## What this lab is about

Instead of creating threads by hand, we give tasks to an `ExecutorService` and get the results back through `Future`s. The lab has two parts:

1. A CPU-bound job (counting primes), where more threads should help up to the number of cores.
2. An I/O-bound job (fake downloads), where we compare a fixed pool of platform threads with virtual threads.

We also handle failures and timeouts without crashing the program.

## Everything is in `Main.java`

- `countPrimes(from, to)` - naive trial division, counts primes in a range
- `PrimeCounter` - a `Callable<Long>` that wraps `countPrimes`
- `FakeDownload` - a `Callable<String>` that sleeps, and sometimes fails
- `runDownloads(name, executor)` - submits 500 downloads, collects results, prints the summary
- `main` - runs single-threaded, then the pool, then both download tests

## Part 1: CPU-bound (primes below 4,000,000)

I count primes below 4,000,000 two ways:

- single-threaded: one call `countPrimes(0, 4_000_000)`
- pool: split the range into 16 chunks, give them to a fixed pool with `availableProcessors()` threads, then add up the `Future` results

Both must give the same answer, **283146** primes. That is how I checked the pool version did not lose any chunk.

My timings (fill in from your run):

- single-threaded: ___ ms
- pool (___ threads): ___ ms

**Why not exactly N times faster?** Two reasons I found:

- The chunks are not equal work. Checking a big number needs more divisions than a small one, so the last chunks are slower than the first ones. That is why I use 16 chunks and not just one chunk per core. When a thread finishes early, it picks up another chunk.
- Threads share the CPU with everything else on my laptop, and starting the pool also costs a little time.

## Part 2: I/O-bound (500 fake downloads)

Each download just sleeps 200 ms. So the threads are not doing any work, they are only waiting. This is where the two kinds of threads act very differently.

### Fixed pool of 20 platform threads

Only 20 downloads can wait at the same time. The other ones stand in the queue. Rough math: 500 tasks / 20 threads = 25 rounds x 200 ms = about 5 seconds.

### Virtual threads

`newVirtualThreadPerTaskExecutor()` makes a new virtual thread for every task, so all 500 downloads wait at the same time. When a virtual thread sleeps, it lets go of the real (carrier) thread under it, so waiting is almost free. The total time should be close to a single 200 ms download.

My timings (fill in from your run):

- fixed(20) platform threads: ___ ms
- virtual threads: ___ ms

### One thing that confused me

My virtual thread result will not look like 200 ms. It will be around 1 second. This is because download #1 sleeps for 5 seconds and my main thread waits for it with `future.get(1, TimeUnit.SECONDS)`. So the program always spends 1 second waiting for the timeout, even if everything else finished in 200 ms. The 20-thread pool has the same 1 second wait, but there it is hidden inside the 5 seconds of queueing.

### Why virtual threads win here (and why they would not for primes)

Virtual threads are good when tasks spend most of their time waiting (network, sleep, database). They are cheap, so I can have thousands of them. They do not help for CPU-bound work like the primes, because there the cores are the limit, not the waiting. More threads than cores would just fight for the same CPUs.

## Part 3: Failure handling

Every 50th download (50, 100, ..., 500) throws `IOException("boom")`. That is 10 failures.

When a task throws, the exception does not crash the program. It is stored inside the `Future`. Then `future.get()` throws an `ExecutionException` that wraps the real error. I catch that in the loop and count it as `failed`. The other downloads keep going.

## Part 4: Timeout

Download #1 sleeps 5 seconds. I call `future.get(1, TimeUnit.SECONDS)` on it. After 1 second it throws `TimeoutException`. I count it as `timedOut` and call `future.cancel(true)`.

The `true` matters. It interrupts the thread, so the sleep stops early and the thread is free again. If I used `cancel(false)`, the task would keep sleeping for the full 5 seconds, and on the fixed pool that thread would stay blocked.

## Expected summary

Since one download times out, the result is:

```
ok=489 failed=10 timedOut=1
```

That is 500 - 10 - 1 = 489. (The lab text says `ok=490`, but that is only without the timeout one.)

## Part 5: Shutdown

I used try-with-resources for the pool in the CPU part, and `executor.close()` at the end of `runDownloads`. In JDK 19+ `ExecutorService` is `AutoCloseable`, and `close()` waits until the submitted tasks finish and then shuts the pool down. I do not use `System.exit`. The JVM exits by itself, which shows no thread is left hanging. (A fixed pool that is never closed would keep the JVM alive forever, because its threads are not daemon threads.)

## Bonus

I did not do the `CompletableFuture` bonus.

## What I learned

- Tasks go to an executor and results come back through `Future`, so I do not manage threads myself anymore.
- Exceptions are hidden inside the `Future` until I call `get()`, and then they come as `ExecutionException`.
- `cancel(true)` is needed to really stop a sleeping task.
- Pick the thread type by the kind of work: a pool sized to the cores for CPU work, virtual threads for waiting work.
- A timeout on one task can make the whole measured time look strange, so I have to read the numbers carefully.