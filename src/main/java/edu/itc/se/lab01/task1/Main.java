package edu.itc.se.lab01.task1;

// Countdown thread
class Countdown extends Thread {

    public Countdown(String name) {
        super(name);
    }

    @Override
    public void run() {
        for (int i = 3; i >= 1; i--) {
            System.out.println(getName() + ": " + i);

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.out.println(getName() + ": liftoff!");
    }
}
// CountdownTask
class CountdownTask implements Runnable {
    @Override
    public void run() {
        String name = Thread.currentThread().getName();
        for (int i=3 ; i>=1 ; i--){
            System.out.println(name + ":" + i);
            try {
                Thread.sleep(200);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println(name + ": liftoff!");
    }
}



public class Main {
    public static void main(String[] args) throws InterruptedException {
        boolean sequential = args.length > 0 && args[0].equals("--sequential");

        Thread apollo  = new Countdown("apollo");
        Thread gemini  = new Thread(new CountdownTask(), "gemini");
        Thread mercury = new Thread(
                () -> new CountdownTask().run(),
                "mercury"
        );
        Thread artemis = Thread.ofVirtual().name("artemis").unstarted(new CountdownTask());

        // start or run the first three threads
        if (sequential) {
            apollo.run();
            gemini.run();
            mercury.run();
            artemis.run();
        }else{
            apollo.start();
            gemini.start();
            mercury.start();
            artemis.start();
        }
        // TODO: start (or run) all four depending on 'sequential'
        System.out.println("Mission control: all launched");
        // wait for all threads to finish
        apollo.join();
        gemini.join();
        mercury.join();
        artemis.join();
        // TODO: join al;l four
        System.out.println("Mission control: all landed");
        // Print thread information after joins
        printThreadInfo(apollo);
        printThreadInfo(gemini);
        printThreadInfo(mercury);
        printThreadInfo(artemis);
        // TODO: print name, isVirtual(), getState() of each
    }
    private static void printThreadInfo(Thread thread){
        System.out.println(
                thread.getName()
                + " | isVirtual=" + thread.isVirtual()
                + " | state=" + thread.getState()
        );
    }
}
