package org.pk.practices.dsa;

import java.util.Collections;
import java.util.PriorityQueue;


/**
 * Cheat sheet https://leetcode.com/discuss/post/6149018/heap-and-priority-queue-in-java-cheat-sh-276e/
 *
 * Output for below
 *
 * ================ MIN HEAP ================
 * Top element: 10
 * Removed: 10
 * ================ MAX HEAP ================
 * Top element: 30
 * Removed: 30
 * Top element: 15
 * Removed, decremented and readded the value ar peak
 * Top element: 14
 */
public class HeapExample {
    public static void main(String[] args) {
        minHeapExample();
        maxHeapExample();
    }

    public static void minHeapExample() {
        System.out.println("================ MIN HEAP ================");
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        // Add elements
        minHeap.add(15);
        minHeap.add(10);
        minHeap.add(30);

        // View top element (returns 10)
        System.out.println("Top element: " + minHeap.peek());

        // Remove top element
        System.out.println("Removed: " + minHeap.poll());
    }

    public static void maxHeapExample() {
        System.out.println("================ MAX HEAP ================");
        // Creates a Max-Heap
        PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());

        maxHeap.add(15);
        maxHeap.add(10);
        maxHeap.add(30);

        // View top element (returns 30)
        System.out.println("Top element: " + maxHeap.peek());

        // Remove top element
        System.out.println("Removed: " + maxHeap.poll());
        System.out.println("Top element: " + maxHeap.peek());
        Integer removed = maxHeap.remove();
        removed--;
        maxHeap.add(removed);
        System.out.println("Removed, decremented and readded the value ar peak");
        System.out.println("Top element: " + maxHeap.peek());
    }
}
