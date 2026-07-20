package org.pk.practices.dsa;

import java.util.ArrayDeque;
import java.util.Deque;

public class DeQueueExample {
    public static void main(String[] args) {
        Deque<Integer> deque = new ArrayDeque<>();
        deque.offer(10);
        System.out.println(deque);
    }
}
