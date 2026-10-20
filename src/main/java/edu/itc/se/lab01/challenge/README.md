# Challenge: Parallel Word-Frequency Analyser

A command-line tool that scans a folder of text files, counts the words in parallel, shows live progress, and stops cleanly on Ctrl+C or a deadline. Only the JDK is used (JUnit is only for the tests).

## Files

```
src/main/java/edu/itc/se/lab01/challenge/
  WordCount.java              the tool (scanner, workers, reporter, shutdown)
  SingleThreadedReference.java   simple version used to check the parallel one
  DataGenerator.java          makes the test files
src/test/java/.../WordCountTest.java   the two tests
pom.xml    bench.sh    README.md
```

## How to run

```
mvn compile
java -cp target/classes edu.itc.se.lab01.challenge.DataGenerator ./data      # 200 files x 50 000 words
java -cp target/classes edu.itc.se.lab01.challenge.WordCount ./data --top 5 --timeout 30
java -cp target/classes edu.itc.se.lab01.challenge.WordCount ./data --top 3 --virtual
```

Options: `--top N` (default 10), `--timeout SECONDS` (default none), `--virtual`, and `--workers N` (default = number of cores). I added `--workers` myself so I could make the table below.

The result is also written to `result.csv` (rank, word, count, share).

## How it works

```
scanner  -->  BlockingQueue<Path>(16)  -->  worker-1..N  -->  ConcurrentHashMap<String, LongAdder>
                                                                      ^
                                                              reporter reads it every 500 ms
```

- **Scanner**: one thread walks the folder and calls `queue.put(path)`. The queue only holds 16 paths, so when the workers are slow the scanner blocks. That is the back-pressure.
- **Workers**: `take()` a path, read the file line by line, split on non-letters, lower-case, and do `increment()` on the word's `LongAdder`. I wrote the splitting by hand (no regex) because it is faster.
- **Done signal**: after the last file the scanner puts one poison pill per worker. A worker that takes the pill stops.
- **Reporter**: a daemon thread. Every 500 ms it prints files done / total, words, words per second and the top 3. It only reads the counters (`LongAdder.sum()`), it never locks the map, so it does not slow the workers down.
- **Shutdown**: both the shutdown hook (Ctrl+C) and the `--timeout` deadline call the same `shutdown()` method. It interrupts the scanner and all workers and waits at most 2 seconds for them. Then `main` prints the result, marked `[PARTIAL]` if not all files were done. Workers check the interrupt flag between files (and between lines), and if `take()` throws `InterruptedException` they put the flag back with `interrupt()` and leave the loop.
- The shutdown hook does not print by itself. It calls `shutdown()` and then waits for `main` to finish printing, so the result is printed only once.

## Testing

`WordCountTest` has two tests:

1. **Same result as the reference.** 20 generated files. The single-threaded reference (it uses a regex split, so it does not share the tokenising code) and the parallel tool must give **identical maps**, for 1, 4 and 8 workers, both platform and virtual.
2. **Stops within 3 s.** 5 000 files and `--timeout 1`. The test checks that it ends in under 3 s, that the result is `partial`, and that fewer than 5 000 files were done.

What I actually checked, and what I did not:
- I ran the logic of both tests in my sandbox, but I **could not download JUnit 6 there**, so I used tiny stand-in annotations instead of the real JUnit. Both passed (about 0.9 s and 1.7 s). I did not run them with real `mvn test`, and the `pom.xml` is also not run, so please run `mvn test` once on your side. If `junit-jupiter` 6.0.0 is not found, change the version number in the pom.
- In test 2 I give each file an artificial 5 ms delay (a test-only constructor parameter, `perFileDelayMs`). Without it, 5 000 tiny files can finish in under a second on a fast computer, and then the test would not prove anything.

I also tried the shutdown by hand: I sent a termination signal to the running program after 0.8 s. The hook ran, and the program printed `[PARTIAL] files=26 ...` and exited. (I used `kill -TERM` because background processes in my script ignore Ctrl+C, but both call the same shutdown hook.) And `--timeout 1` on the 200-file folder printed `[PARTIAL] files=94 ... elapsed=1.03s`.

## Elapsed time (200 files, 10 million words)

Median of 3 runs each:

| Workers            | Elapsed  |
|--------------------|----------|
| 1 platform         | 1.56 s   |
| 2 platform         | 1.70 s   |
| 4 platform         | 2.34 s   |
| 8 platform         | 3.32 s   |
| 8 virtual threads  | 2.18 s   |

**Important:** I made these numbers in a sandbox that has **only 1 CPU core** (`nproc` = 1). So this table does **not** show the speedup you should see on a real computer. More workers just fight for the same core, so it gets slower. That is also why virtual(8) is a bit faster than platform(8) here: it has less thread overhead. Please run `./bench.sh` on your own laptop and replace the table with your numbers. I would expect it to get faster up to your number of cores and then flatten, but I did not measure that.

## Why LongAdder (or merge) is better than synchronized on the map

If every increment is inside `synchronized (map)`, only one worker can count at a time. Eight workers would stand in a line for the lock, and the lock is taken once **per word**, which is millions of times. The reporter would also need the same lock.

`ConcurrentHashMap` already avoids a global lock. But a hot word like "the" is hit by every thread all the time. A normal counter (`AtomicLong`, or `Long` with `merge`) would make all the threads do compare-and-swap on one single memory spot, and they keep failing and retrying. `LongAdder` keeps several internal cells and each thread mostly updates a different one, and `sum()` adds them up only when somebody asks. So increments rarely block, and the reporter can read at any time without a lock. (A `Long` with `merge(word, 1L, Long::sum)` is correct too, but it locks the bin of that word for a moment, so it is slower for hot words.)

The best version for pure speed would be one private `HashMap` per worker and a merge at the end, with no sharing at all. I did not use it, because then the live top-3 in the progress line would not work. I also did **not** measure `synchronized` against `LongAdder`, so this explanation is from the theory, not from my own numbers.

## Coffman conditions

A deadlock needs all four: mutual exclusion, hold and wait, no preemption, and circular wait. My design mainly rules out **hold and wait** and **circular wait**. No thread ever holds one lock while asking for another: the workers just call `take()` on the queue and `increment()` on a counter, and my code has no nested `synchronized` or `lock()` at all, so there can be no circle of threads waiting for each other. The scanner and the workers only wait for the queue, and they never hold anything while they wait. **Mutual exclusion** is still there (the queue and the map use internal locks), but those are held only for a very short moment and never while waiting for something else. For **no preemption**, I give every blocked thread a way out: `put()` and `take()` react to `interrupt()`, so the shutdown can always wake them up and end them, even if something goes wrong.

## Things to know / honest notes

- The `files` number only counts files that were completely processed. If a worker is interrupted in the middle of a file, the words it already counted stay in the result (so `[PARTIAL]` words can include part of a file).
- The hand-written splitter lower-cases each character, while the reference uses `String.toLowerCase`. For some rare letters those could differ. The test data is only a-z, so the test does not see this.
- Files with bad UTF-8 do not crash the tool (bad bytes are replaced). An unreadable file is reported on stderr and skipped.
- The progress line prints "files done / total" using a file count made at the start, so the total is known from the first line.

## What I learned

- Back-pressure is easy with a bounded queue: `put()` just blocks.
- Poison pills are simple, but I need exactly one per worker.
- Shutdown from two places (hook and deadline) is much easier when both call one method.
- The hardest part was the hook: the JVM can exit as soon as the hook ends, so the hook has to wait for `main` to print the result.
- My benchmark computer had one core, so I could not show the speedup. I would rather say that than make up a nice-looking table.