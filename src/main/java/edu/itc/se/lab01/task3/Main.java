package edu.itc.se.lab01.task3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

record Order(int id, String item, int qty) {
}

interface OrderQueue {
    void put(Order order) throws InterruptedException;

    Order take() throws InterruptedException;
}

// Adapter implementation using Java's BlockingQueue
final class BlockingOrderQueue implements OrderQueue {

    private final java.util.concurrent.BlockingQueue<Order> q;

    BlockingOrderQueue(int capacity) {
        this.q = new java.util.concurrent.ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void put(Order o) throws InterruptedException {
        q.put(o);
    }

    @Override
    public Order take() throws InterruptedException {
        return q.take();
    }
}

// Custom bounded buffer using wait() / notifyAll()
class BoundedBuffer implements OrderQueue {

    private final ArrayDeque<Order> items = new ArrayDeque<>();
    private final int capacity;

    public BoundedBuffer(int capacity) {
        this.capacity = capacity;
    }

    @Override
    public synchronized void put(Order order)
            throws InterruptedException {

        // Wait while the buffer is full
        while (items.size() == capacity) {
            wait();
        }

        // Add order to the buffer
        items.addLast(order);

        // Wake up waiting consumers/producers
        notifyAll();
    }

    @Override
    public synchronized Order take()
            throws InterruptedException {

        // Wait while the buffer is empty
        while (items.isEmpty()) {
            wait();
        }

        // Remove order from the buffer
        Order order = items.removeFirst();

        // Wake up waiting consumers/producers
        notifyAll();

        return order;
    }
}

class Producer implements Runnable {

    private final OrderQueue queue;
    private final int startId;
    private final int count;
    private final Random random = new Random();

    public Producer(
            OrderQueue queue,
            int startId,
            int count) {

        this.queue = queue;
        this.startId = startId;
        this.count = count;
    }

    @Override
    public void run() {

        try {
            for (int i = 0; i < count; i++) {

                int id = startId + i;

                Order order = new Order(
                        id,
                        "item-" + id,
                        random.nextInt(5) + 1
                );

                queue.put(order);

                // Random pause: 0-20 ms
                Thread.sleep(random.nextInt(21));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class Consumer implements Runnable {

    private final OrderQueue queue;
    private final AtomicInteger processed;
    private final Random random = new Random();

    public Consumer(
            OrderQueue queue,
            AtomicInteger processed) {

        this.queue = queue;
        this.processed = processed;
    }

    @Override
    public void run() {

        String name = Thread.currentThread().getName();

        try {

            while (true) {

                Order order = queue.take();

                // Poison pill
                if (order.id() == -1) {

                    System.out.println(
                            name + " received poison pill, stopping"
                    );

                    break;
                }

                // Simulate processing
                Thread.sleep(5 + random.nextInt(26));

                processed.incrementAndGet();

                System.out.println(
                        name + " processed " + order
                );
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

public class Main {

    public static void main(String[] args)
            throws InterruptedException {

        int producers = 2;
        int ordersPerProducer = 50;
        int consumers = 3;
        int capacity = 5;

        // Use our custom bounded buffer
        OrderQueue queue = new BoundedBuffer(capacity);

        AtomicInteger processed = new AtomicInteger(0);

        List<Thread> producerThreads = new ArrayList<>();
        List<Thread> consumerThreads = new ArrayList<>();

        long start = System.nanoTime();

        // ==========================================
        // Start producers
        // ==========================================

        for (int i = 0; i < producers; i++) {

            int startId = i * ordersPerProducer + 1;

            Thread producer = new Thread(
                    new Producer(
                            queue,
                            startId,
                            ordersPerProducer
                    ),
                    "producer-" + (i + 1)
            );

            producerThreads.add(producer);
            producer.start();
        }

        // ==========================================
        // Start consumers
        // ==========================================

        for (int i = 0; i < consumers; i++) {

            Thread consumer = new Thread(
                    new Consumer(queue, processed),
                    "consumer-" + (i + 1)
            );

            consumerThreads.add(consumer);
            consumer.start();
        }

        // Wait for ALL producers
        for (Thread producer : producerThreads) {
            producer.join();
        }
        // Add one poison pill for each consumer
        for (int i = 0; i < consumers; i++) {
            queue.put(new Order(-1, "POISON", 0));
        }

        // Calculate elapsed time AFTER all work is finished
        long elapsed =
                (System.nanoTime() - start) / 1_000_000;

        System.out.println(
                "Total processed: "
                        + processed.get()
                        + " (expected 100) in "
                        + elapsed
                        + " ms"
        );

        // Wait for ALL consumers to finish
        for (Thread consumer : consumerThreads) {
            consumer.join();
        }

        // Verify result
        if (processed.get() != 100) {
            throw new AssertionError(
                    "Expected 100 but got "
                            + processed.get()
            );
        }
    }
}
