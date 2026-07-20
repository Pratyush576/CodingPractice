package org.pk.practices.dsa;

public class Trie {

    public void addTrieNodes(String str) {

    }

    public boolean search(String str) {
        return false;
    }
}

class TrieNode {
    boolean isEndOfAWord;
    TrieNode[] children;

    public TrieNode() {
        isEndOfAWord = false;
        children = new TrieNode[26];
    }
}

