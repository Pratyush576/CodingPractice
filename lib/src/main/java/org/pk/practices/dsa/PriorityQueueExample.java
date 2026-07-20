package org.pk.practices.dsa;


import org.apache.commons.math3.util.Pair;

import java.util.*;

public class PriorityQueueExample {
    class Element {
        int value;
        char key;
        int nextIndex;
        Element(int value, char key) {
            this.value = value;
            this.key = key;
        }

        public String toString() {
            return "key: [" + key + "] value ["+value+"]";
        }
    }


    public String reorganizeString(String s) {
        int[] counter = new int[26];
        char[] ch = s.toCharArray();

        for(int i= 0; i<ch.length; i++) {
            counter[ch[i]-'a'] +=1;
        }
        PriorityQueue<Element> pq = new PriorityQueue<>((a, b) -> Integer.compare(b.value, a.value));

        for(int i= 0; i<26; i++) {
            if(counter[i] > 0) {
                char c = (char) (i + 'a');
                Element pair = new Element(counter[i], c);
                pq.offer(pair);
                System.out.println(pq);
            }
        }

        Queue<Element> q = new ArrayDeque<>();

        int i= 0;
        while(i < ch.length) {
            int temp = i;
            if(!pq.isEmpty()) {
                Element el = pq.poll();
                ch[i] = el.key;
                el.value -=1;
                el.nextIndex = i + 1;
                if(el.value > 0) {
                    q.offer(el);
                }
                i++;
            }

            if(!q.isEmpty() && q.peek().nextIndex == i) {
                pq.offer(q.poll());
            }

            if (temp == i) {
                return "";
            }

        }

        return new String(ch);
    }

    public static void main(String[] args) {
        PriorityQueueExample ex = new PriorityQueueExample();
        System.out.println("Final String: " + ex.reorganizeString( "aabcbabba"));;
    }
}
