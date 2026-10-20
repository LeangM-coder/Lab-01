# Task 2 — Shared Counter Race Condition

## Objective

This task demonstrates a lost-update race condition using multiple threads and compares several thread-safe solutions.

The benchmark uses:

- 8 threads
- 1,000,000 increments per thread
- Expected final value: 8,000,000

## Implementations

### UnsafeCounter

`UnsafeCounter` uses a normal `long` variable:

```java
count++;
```

This operation is not atomic. Multiple threads can read the same value, increment it, and write the same result back. Therefore, some updates are lost.

### SynchronizedCounter

Uses `synchronized` methods to allow only one thread at a time to access the counter.

### LockCounter

Uses `ReentrantLock`. The lock is released inside a `finally` block so that it is always unlocked.

### AtomicCounter

Uses `AtomicLong`, which provides atomic increment operations without explicit synchronization.

### LongAdderCounter

`LongAdder` maintains multiple internal values so that threads can update different cells under contention. This reduces contention and can make it faster than a single `AtomicLong` when many threads are repeatedly incrementing the counter.

## Why must `get()` also be synchronized?

Making only `increment()` synchronized is not enough.

If `increment()` is synchronized but `get()` is not, another thread can call `get()` while another thread is modifying `count`.

The read is therefore not protected by the same synchronization mechanism. This can cause a data race and means the program does not provide consistent synchronization for access to the shared variable.

Therefore, both reading and modifying the shared counter should be synchronized, or the counter should use a thread-safe mechanism such as `AtomicLong` or `LongAdder`.

## Expected Result

The unsafe counter may produce a value smaller than 8,000,000 because of lost updates.

The synchronized, lock-based, atomic, and LongAdder implementations should produce:

```text
8,000,000
```

with:

```text
correct=true
```

The execution time will vary between runs and between machines.