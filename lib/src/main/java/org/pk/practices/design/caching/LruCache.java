package org.pk.practices.design.caching;

import java.util.HashMap;
import java.util.Map;

public class LruCache implements Cache<Integer, Integer> {
    final int maxSize;

    LruCache(int size) {
        this.maxSize = size;
    }


    Map<Integer, Node<Integer, Integer>> cache = new HashMap<>();
    Node<Integer, Integer> first = null;
    Node<Integer, Integer>  last = null;

    @Override
    public Integer get(Integer key) {
        System.out.println("----- fetching the data from cache for Key: " + key + " -----");
        Node<Integer, Integer> value = cache.get(key);

        if(value != null) {
            if (value.previous != null) {
                value.previous.next = value.next;
            }

            if (value.next != null) {
                value.next.previous = value.previous;
            }

            last.next = value;
            value.previous = last;
            value.next = null;
            last = value;
        }

        Integer cacheVal = value != null ? value.v : null;
        displayCache();
        System.out.println("Cache value will be returned: " + cacheVal);
        return cacheVal;
    }

    @Override
    public void put(Integer key, Integer value) {
        System.out.println("----- putting the data into the cache for Key: " + key + " with value " + value + " -----");

        // If k-v pair already exists
        if (get(key) != null) {
            System.out.println("data exists into the cache. cache value will be updated");
            Node<Integer, Integer> existingNode = cache.get(key);
            existingNode.v = value;
            return;
        }

        System.out.println("data don't exist into the cache. a new cache entry will be created");
        Node<Integer, Integer> node = new Node<>();
        node.v = value;
        node.k = key;
        node.next = null;
        node.previous = last;

        if(cache.size() >= maxSize) {
            // remove the least recently used element
            Integer firstKey = first.k;
            if (first.next != null) {
                first.next.previous = null;
            }

            first = first.next;
            cache.remove(firstKey);
        }

        if(first == null) {
            first = node;
            last = node;
        } else {
            last.next = node;
        }

        cache.put(key, node);
        displayCache();
    }


    public void displayCache() {
        if (cache.size() == 0) {
            System.out.println("Cache is empty");
        }
        cache.forEach((key, value) -> System.out.println(key + " : " + value.v));
    }

    public static void main(String[] args) {
        Cache<Integer, Integer> cache = new LruCache(4);
        System.out.println("\n\n");

        cache.put(1, 7);
        System.out.println("\n\n");

        cache.put(1, 7);
        System.out.println("\n\n");

        cache.get(0);
        System.out.println("\n\n");

        cache.get(1);
        System.out.println("\n\n");

        cache.get(7);
        System.out.println("\n\n");

        cache.put(7, 21);
        System.out.println("\n\n");

        cache.put(9, 7);
        System.out.println("\n\n");

        cache.put(5, 7);
        System.out.println("\n\n");

        cache.put(3, 7);
        System.out.println("\n\n");

        cache.put(1, 7);
        System.out.println("\n\n");

        cache.get(7);
        System.out.println("\n\n");

        cache.get(3);
        System.out.println("\n\n");

        cache.get(5);
        System.out.println("\n\n");

        cache.get(5);
        System.out.println("\n\n");

        cache.get(9);
        System.out.println("\n\n");
    }
}
