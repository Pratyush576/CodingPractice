package org.pk.practices.design.caching;

public interface Cache<K, V> {
    public V get(K k);
    public void put(K k, V v);
}
