# Task 1: Java Multithreading

## 1. Objective

The objective of this task is to understand Java threads and how to execute multiple tasks concurrently.

This task demonstrates:

- Creating threads in four different ways.
- Understanding the difference between `start()` and `run()`.
- Using `Runnable` to separate a task from a thread.
- Creating a virtual thread using `Thread.ofVirtual()`.
- Using `join()` to wait for threads to finish.
- Comparing concurrent execution with sequential execution.
- Checking thread information using `isVirtual()` and `getState()`.

## 2. Project Structure

```text
lab01-multithreading/
├── pom.xml
└── src/
    └── main/
        └── java/
            └── edu/
                └── itc/
                    └── se/
                        └── lab01/
                            └── task1/
                                ├── Main.java
                                ├── README.md
                                ├── Countdown.java
                                └── CountdownTask.java
```

### Main.java

The `Main` class is the entry point of the application.

It creates four thread examples, starts or executes them depending on the `--sequential` argument, waits for completion, and prints their information.

### Countdown.java

The `Countdown` class extends `Thread`.

It overrides the `run()` method to print a countdown from 3 to 1, sleeping for 200 milliseconds between numbers, and then prints `liftoff!`.

The constructor uses `super(name)` to assign the thread's name.

### CountdownTask.java

The `CountdownTask` class implements `Runnable`.

It contains the countdown logic independently of the thread object. It uses `Thread.currentThread().getName()` to identify the thread currently executing the task.

## 3. Four Thread Examples

### Example 1: Extending Thread

```java
Thread apollo = new Countdown("apollo");
```

This creates a thread using a custom class that extends `Thread`.

### Example 2: Implementing Runnable

```java
Thread gemini = new Thread(
        new CountdownTask(),
        "gemini"
);
```

This creates a thread that executes a `Runnable` task.

### Example 3: Using a Lambda Expression

```java
Thread mercury = new Thread(
        () -> new CountdownTask().run(),
        "mercury"
);
```

The lambda expression provides the code to execute. It reuses the countdown logic in `CountdownTask`.

### Example 4: Using a Virtual Thread

```java
Thread artemis = Thread.ofVirtual()
        .name("artemis")
        .start(new CountdownTask());
```

This creates and starts a virtual thread named `artemis`.

Virtual threads are lightweight threads managed by the Java runtime. They are particularly useful for applications with many concurrent tasks that spend time waiting.

## 4. Difference Between start() and run()

### start()

```java
apollo.start();
```

The `start()` method starts a new thread of execution. Java invokes the thread's `run()` method on that thread.

This allows multiple countdowns to execute concurrently.

### run()

```java
apollo.run();
```

The `run()` method is an ordinary method call when invoked directly. It executes on the calling thread and does not create a new thread.

When the `--sequential` argument is supplied, each countdown finishes before the next one begins.

### Comparison

| Feature | start() | run() |
|---|---|---|
| Creates a new thread of execution | Yes | No |
| Executes the task concurrently | Can do so | No, when called directly |
| Returns immediately after initiating execution | Normally yes | No, waits for the method body to finish |
| Used for this assignment | Normal mode | Sequential mode |

## 5. Using join()

The `join()` method makes the main thread wait for another thread to finish.

```java
apollo.join();
gemini.join();
mercury.join();
artemis.join();
```

The program prints:

```text
Mission control: all landed
```

only after all four threads have terminated.

Without `join()`, the main thread could print the landing message before all countdowns have completed.

## 6. Sequential Execution

The program checks the command-line argument:

```java
boolean sequential =
        args.length > 0 && args[0].equals("--sequential");
```

If `--sequential` is present, the program invokes `run()` on each thread object in order.

For Artemis, the program must create the virtual thread using `unstarted()` first, rather than `start()`, so that it does not begin executing immediately.

```java
Thread artemis = Thread.ofVirtual()
        .name("artemis")
        .unstarted(new CountdownTask());
```

The countdown is then executed with:

```java
artemis.run();
```

This is useful for demonstrating sequential execution, although Artemis is still a virtual-thread object.

## 7. Thread Information

After all countdowns finish, the program prints the thread name, whether it is virtual, and its state.

```java
thread.getName();
thread.isVirtual();
thread.getState();
```

Expected results:

| Thread | isVirtual() | getState() after completion |
|---|---|---|
| apollo | false | TERMINATED |
| gemini | false | TERMINATED |
| mercury | false | TERMINATED |
| artemis | true | TERMINATED |

`TERMINATED` means the thread has finished executing.

## 8. How to Run in IntelliJ IDEA

1. Open the `lab01-multithreading` Maven project.
2. Navigate to `Main.java`.
3. Right-click the file and select **Run 'Main.main()'**.
4. Observe the countdown messages and thread information.

### Run Normally

Do not provide any program arguments.

The four countdowns execute concurrently, so their output may be interleaved. The exact order can vary between executions.

### Run Sequentially

1. Open **Run → Edit Configurations**.
2. Select the run configuration for `Main`.
3. Enter the following in **Program arguments**:

```text
--sequential
```

4. Click **Apply**, then **Run**.

The countdowns execute in order: Apollo, Gemini, Mercury, and Artemis. The `Mission control: all launched` message appears after the four countdowns finish.

Remove `--sequential` to return to normal execution.

## 9. Example Output

Normal execution produces output similar to:

```text
apollo: 3
gemini: 3
mercury: 3
artemis: 3
Mission control: all launched
apollo: 2
mercury: 2
gemini: 2
artemis: 2
...
Mission control: all landed
apollo | isVirtual=false | state=TERMINATED
gemini | isVirtual=false | state=TERMINATED
mercury | isVirtual=false | state=TERMINATED
artemis | isVirtual=true | state=TERMINATED
```

The countdown order is only an example. Concurrent execution does not guarantee a particular print order.

## 10. Conclusion

This task demonstrates four ways to create or execute thread-based work in Java.

Extending `Thread` provides a direct way to define a thread. Implementing `Runnable` separates the task from the thread. Lambda expressions provide a concise way to supply task logic, and virtual threads provide a lightweight alternative to platform threads.

The difference between `start()` and `run()` explains why tasks can execute concurrently or sequentially. Finally, `join()` coordinates completion, while `isVirtual()` and `getState()` provide information about each thread.

**Requirements:** JDK 25 or another compatible Java release supporting virtual threads, and Maven if running through the Maven project.
