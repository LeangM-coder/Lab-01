# Lab 01 - Task 3: Bounded Buffer (Producer / Consumer)

## What this lab is about

We build a bounded buffer by hand with `synchronized`, `wait()` and `notifyAll()`.
Then we swap it for Java's `ArrayBlockingQueue` and see how much code we can delete.

## Files

- `Main.java` - runs 2 producers x 50 orders and 3 consumers using my own `BoundedBuffer` (capacity 5)
- `Main2.java` - same `Producer` / `Consumer`, but plugged into `ArrayBlockingQueue<Order>(5)`
- `Order` - a record: `Order(int id, String item, int qty)`
- `OrderQueue` - the small interface (`put` / `take`) so both buffers can plug in
- `BoundedBuffer` - the hand-made one
- `BlockingOrderQueue` - a tiny adapter that wraps `ArrayBlockingQueue`

## How it works

Producers make orders and `put` them in the buffer. Consumers `take` them, "process" them (sleep 5-30 ms) and count them in an `AtomicInteger`.

When all producers are done, `Main` puts one poison pill (`Order(-1, "POISON", 0)`) per consumer. A consumer that takes a pill stops. After joining everything, `Main` checks that the total processed is 100.

## Hand-made buffer vs BlockingQueue

The hand-made `BoundedBuffer` needs:
- the `ArrayDeque` and the capacity field
- `synchronized` on both methods
- a `while` loop with `wait()` in both methods
- `notifyAll()` in both methods

With `ArrayBlockingQueue`, all of that is gone. `put()` and `take()` already do the waiting and waking inside. My adapter is just two one-line methods. The result is the same (100 processed) with way less code and way less chance of me making a mistake.

## The experiment: notify() and if

I changed two things in `BoundedBuffer`:
1. `notifyAll()` -> `notify()`
2. `while (...) wait();` -> `if (...) wait();`

### Why `if` is wrong

`wait()` does not promise that the condition is true when the thread wakes up. Another thread can sneak in first.

Example with the consumers:
1. The buffer is empty, so consumer A waits.
2. A producer puts one order and wakes A.
3. Before A gets the lock back, consumer B arrives (it never waited), grabs the lock and takes the order.
4. A finally gets the lock. With `if`, A does not check again. It goes straight to `removeFirst()` on an empty deque and crashes with `NoSuchElementException`.

The same thing can happen on the producer side. A producer wakes up on a full buffer, does not re-check, and adds an item anyway. This one is sneakier because `ArrayDeque` does not complain. The buffer just grows past 5 and nothing crashes, so the capacity limit silently breaks.

Also, Java is allowed to wake threads up for no reason (spurious wakeups), which is another reason the check must be in a loop.

So `while` is not optional. The thread has to re-check the condition every time it wakes up.

### Why `notify()` is risky

Producers and consumers wait on the same lock, but they are waiting for different things (producers wait for space, consumers wait for items). `notify()` wakes only one random waiting thread. It can pick the wrong kind.

For example, a consumer takes an item and wants to wake a producer, but `notify()` picks another consumer. That consumer checks, sees the buffer is still empty, and goes back to sleep. The wake-up got used up on the wrong thread, and the producer that could have worked stays asleep. In the worst case every thread ends up waiting and the program just hangs forever.

`notifyAll()` fixes this because everyone wakes up and re-checks their own condition (this is why it works together with `while`). It is a bit wasteful, but it is correct.

### What I saw when running it in a loop

I ran it 20 times in a loop (e.g. `for i in $(seq 1 20); do java Main; done`).

- With `if` + `notify()`: _(write your own results here: how many runs crashed with `NoSuchElementException`, how many hung, how many were fine)_
- With the original `while` + `notifyAll()`: _(should be 20/20 fine)_

One thing I noticed: the bug does not show up every time. Some runs look totally fine, and that is what makes this kind of bug annoying. It depends on thread timing, so one good run proves nothing. That is why we need to run it many times.

## What I learned

- Always use `while` around `wait()`, never `if`.
- When one lock is shared by two kinds of waiters, use `notifyAll()`, not `notify()`.
- Writing this by hand is easy to get wrong, so `BlockingQueue` is the better choice in real code.
- Putting a small interface (`OrderQueue`) in front of the buffer made it easy to swap implementations without touching `Producer` and `Consumer`.