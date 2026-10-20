# Lab 01 - Task 5: Deadlock, Fixes and Graceful Shutdown

## What this lab is about

I make a real deadlock on purpose, find it with the JDK tools, and then fix it in two different ways (lock ordering and `tryLock`). At the end I add a clean way to stop the worker threads using interruption.

## How to run

Everything is in `Main.java`. The mode is chosen with an argument:

```
java Main deadlock    # broken version, will hang
java Main ordered     # Fix A
java Main trylock     # Fix B
java Main shutdown    # graceful shutdown demo
```

If I give no argument, it runs `ordered`.

## The setup

8 accounts, each starts with 500, so the total must always be **4000**. 8 threads do random transfers of 1 between two different accounts.

## Step 1: The deadlock

`brokenTransfer(from, to)` locks `from` first and then `to`. I also added `Thread.yield()` between the two locks to make it more likely that another thread gets in between.

The problem: thread 1 does `A -> B` and thread 2 does `B -> A` at the same time.
- Thread 1 holds A and waits for B.
- Thread 2 holds B and waits for A.
- Nobody can move, so the program hangs forever.

With 8 threads and only 8 accounts it happened very fast for me. _(write here how long it took to hang on your computer)_

## Step 2: Finding it with jstack

While the program was hanging, I did this:

```
jps            # (or jcmd) to find the process id
jstack <pid>
```

At the bottom of the output, `jstack` prints a section starting with `Found one Java-level deadlock`. It shows which thread holds which lock and which lock it is waiting for. I saved that part in `deadlock.txt`.

_(Remember to run it yourself and paste that section in `deadlock.txt`. I did not include one here because I have not run it.)_

What to look for in it: two `transfer-X` threads, each "waiting to lock" an `Account` that the other thread is "locked" on. That is the circle.

## Step 3: The watchdog

`startWatchdog()` starts a daemon thread. It calls `ThreadMXBean.findDeadlockedThreads()` in a loop. When it finds something, it prints the names of the stuck threads, like:

```
[watchdog] DEADLOCK detected between: transfer-2, transfer-5
```

Notes:
- It is a **daemon** thread, so it does not stop the JVM from exiting.
- The task says to check every second. My version checks every 500 ms, which is a bit faster, but the idea is the same.
- After it prints once, it stops (the `return`). It only detects, it does not fix anything. I still have to press Ctrl+C.

## Step 4: Fix A - lock ordering (`OrderedBank`)

`orderedTransfer` always locks the account with the **smaller id first**, no matter which one is the sender. So if one thread does `A -> B` and another does `B -> A`, both lock A first and then B. One of them waits at A, but nobody holds B while waiting for A, so there is no circle.

This is the classic fix: break the circular wait.

Result of my run (fill in):

```
# OrderedBank
transfers=80000 total=____ (expected 4000) in ___ ms
```

The total must be 4000, and the program must finish. That shows no money was lost and nothing hangs.

One thing I had to be careful about: the balance check uses the sender (`first`), but the lock order uses the ids. These are two different things, so I used the `lower` / `higher` variables just for locking.

## Step 5: Fix B - `tryLock` (`TryLockBank`)

Here each account has a `ReentrantLock`. Instead of waiting forever, I use `tryLock(10, MILLISECONDS)` on both accounts.

- If I cannot get the first lock, I give up and count a retry.
- If I get the first lock but not the second, I release the first one (in the `finally`), count a retry, and try again later.
- After a failure, the thread sleeps a random 1-10 ms (back-off), so two threads do not keep colliding at the exact same time.

This fixes the deadlock because a thread never waits forever while holding a lock. It breaks the "hold and wait" part.

Result of my run (fill in):

```
# TryLockBank
transfers=80000 retries=____ total=____ (expected 4000) in ___ ms
```

Only successful transfers count towards the 10,000 per thread, so I still get 80,000 in the end.

### Ordered vs tryLock

- `tryLock` is slower, because of the timeouts and the sleeping. The retry counter shows how much extra work it does.
- Lock ordering is simpler and faster, but it only works when I know all the locks in advance and can sort them.
- `tryLock` is more flexible (and the timeout lets me do something else), but I have to write the retry code myself, and in theory threads could keep failing together (livelock). The random back-off helps with that.

I would use lock ordering here. It is simpler and I cannot get it wrong easily.

## Step 6: Graceful shutdown

The workers loop with `while (!Thread.currentThread().isInterrupted())`. After 2 seconds `main` calls `interrupt()` on every worker, then waits for them with `join()`. Each worker prints how many transfers it finished, and `main` prints the final total (should be 4000) and whether all workers stopped.

Result of my run (fill in):

```
transfer-0 stopped after ____ transfers
...
all workers stopped=true total=____ (expected 4000)
```

### Things I want to be honest about

- The task says to join with a **1-second timeout**. My code uses plain `worker.join()` without a timeout. It works here because the workers check the flag very often, but with a timeout I could detect a worker that does not stop. This would be a good thing to improve: `worker.join(1000)` and then check `isAlive()`.
- The task says workers must restore the interrupt flag when `InterruptedException` is thrown. In the shutdown demo the workers use `synchronized`, which cannot be interrupted and does not throw `InterruptedException`, so there is nothing to catch there. The flag just stays set and the loop ends. The place where this matters is `runTryLock`, where `tryLock(...)` and `sleep(...)` can throw it. There I catch it and call `Thread.currentThread().interrupt()` again.

### Why restore the flag?

When a blocking method throws `InterruptedException`, it **clears** the interrupt flag. If I just swallow the exception, the loop condition never sees the interruption and the thread never stops. Calling `interrupt()` again puts the flag back so the rest of the code (and the loop) can see it.

## What I learned

- A deadlock needs a circle of threads each waiting for a lock the next one holds. `jstack` shows this circle clearly.
- A program that "just hangs" is hard to debug without `jstack` and `findDeadlockedThreads()`.
- Fix A (ordering) removes the circle. Fix B (`tryLock`) removes the endless waiting.
- Interrupting a thread is only a request. The worker must check the flag, and must put it back if it catches `InterruptedException`.
- Hardest part for me: understanding that the deadlock can look fine in some runs, because it depends on timing. I need to run the broken version more than once.