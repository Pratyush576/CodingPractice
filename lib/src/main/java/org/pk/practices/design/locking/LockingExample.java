package org.pk.practices.design.locking;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

public class LockingExample {
    static List<Integer> list = new ArrayList<>();
    Random random = new Random();
    ReentrantLock lock = new ReentrantLock();

    public void add() {

        lock.lock();
        try {
            int boundedInt = random.nextInt(100);
            System.out.println(Thread.currentThread().getName() + " is adding " + boundedInt);
            list.add(boundedInt);
            Thread.sleep(5000);
            print();
            System.out.println(Thread.currentThread().getName() + " is stopping the done with addition");
        } catch (Exception ex) {
          ex.printStackTrace();
        } finally {
            lock.unlock();
        }

    }

    public void remove() {
        lock.lock();
        try {
            System.out.println(Thread.currentThread().getName() + " is removing");
            if (!list.isEmpty()) {
                list.removeFirst();

            } else {
                System.out.println("List is empty");
            }
            print();
            System.out.println(Thread.currentThread().getName() + " is done with removal");
        } finally {
            lock.unlock();
        }
    }

    public void print() {
        System.out.println(list);
    }

    public void task1() {
        add();
        add();
        remove();
        print();
        add();
        add();
        print();
        remove();
    }

    public void task2() {
        remove();
        add();
        add();
        add();
        remove();
        add();
        print();
        remove();
        add();
        print();
        remove();
        print();
        remove();
        remove();
        remove();
        add();
        add();
        print();
        remove();
    }

    public static void main(String[] args) {
        LockingExample le = new LockingExample();
        //LockingExample le1 = new LockingExample();
        Thread task1 = new Thread(le::task1, "TASK1");
        //Thread task2 = new Thread(le1::task2, "TASK2");
        Thread task3 = new Thread(le::task2, "TASK3");
        //Thread task4 = new Thread(le1::task2, "TASK4");
        Thread task6 = new Thread(le::task2, "TASK6");

//        for (int i = 0; i < 5 ; i++) {
//            new Thread(le1::task2, "AUTO TASK2 - " + i).start();
//        }

        for (int i = 0; i < 5 ; i++) {
            new Thread(le::task1, "AUTO TASK1 - " + i).start();
        }

        task1.start();
        //task2.start();
        task3.start();
        //task4.start();
        task6.start();

    }
}
