package org.pk.practices.design.videoStreaming.storage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory stand-in for an S3-class object store — just enough to
 * demonstrate key-based writes/reads and idempotent overwrites. No real
 * network, durability, or replication; see the design doc's §4.3 and §8.5
 * for what a real object store actually provides.
 */
public class ObjectStore {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    public void put(String key, byte[] data) {
        objects.put(key, data);
    }

    public byte[] get(String key) {
        byte[] data = objects.get(key);
        if (data == null) {
            throw new IllegalArgumentException("No object at key: " + key);
        }
        return data;
    }

    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    /** Sorted keys currently stored — for inspection/debugging only, no real object store offers a free list-all. */
    public List<String> keys() {
        return objects.keySet().stream().sorted().toList();
    }
}
