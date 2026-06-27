package org.pk.practices.dsa.caching;

public interface Cache<K, V> {
    public V get(K k);
    public void put(K k, V v);
}
