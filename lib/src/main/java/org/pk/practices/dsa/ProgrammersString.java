package org.pk.practices.dsa;

import javax.swing.*;
import java.util.LinkedHashMap;

/**
 * Given a string which will have 2 "programmer" string in it(maybe jumbled). Return the number of characters between
 * the boundary of those two "programmer" strings. It is alos mentioned that there is no overlap between the two
 * occurences. example progxrammerrxproxgrammer, xprogxrmaxemrppprmmograeiruu and programmerprogrammer
 */
public class ProgrammersString {

    public static final String PROGRAMMER = "programmer";
    LinkedHashMap<Character, Integer> cache;

    public int getDistanceWhenJumbled(String str) {
        if(str == null || str.length() < PROGRAMMER.length()) {
            return -1;
        }

        setCache();

        int left = 0;
        int right = str.length()-1;

        while(left < str.length()) {
            if(cache.get(str.charAt(left)) != null) {
                // cachehit
                Integer temp = cache.get(str.charAt(left));
                temp--;

                if (temp == 0) {
                    cache.remove(str.charAt(left));
                } else {
                    cache.put(str.charAt(left), temp);
                }
                System.out.println("left cache: " + cache);

                if (cache.isEmpty()) {
                    break;
                }
            }

            left++;
        }

        setCache();

        while(right >= 0) {
            if(cache.get(str.charAt(right)) != null) {
                // cache hit
                Integer temp = cache.get(str.charAt(right));
                temp--;

                if (temp == 0) {
                    cache.remove(str.charAt(right));
                } else {
                    cache.put(str.charAt(right), temp);
                }

                System.out.println("right cache: " + cache);
                if (cache.isEmpty()) {
                    break;
                }
            }

            right--;
        }

        System.out.println(str +" Jumbled String Distance: " + (right - left -1));
        return right - left -1;

    }

    private void setCache() {
        cache = new LinkedHashMap<>();
        for (int i = 0; i < PROGRAMMER.length(); i++) {
            if (cache.get(PROGRAMMER.charAt(i)) != null) {
                // cache hit
                Integer temp = cache.get(PROGRAMMER.charAt(i));
                temp += 1;
                cache.put(PROGRAMMER.charAt(i), temp);
            } else {
                // cache miss
                cache.put(PROGRAMMER.charAt(i), 1);
            }
        }
    }

    public int getDistance(String str) {
        if(str == null || str.length() < PROGRAMMER.length()) {
            return -1;
        }

        int left = 0, right = PROGRAMMER.length() - 1;
        int lptr = 0, rptr = str.length() -1;

        while(left < str.length()) {
            if (str.charAt(lptr) == PROGRAMMER.charAt(left)) {
                left++;
            }

            if(left == PROGRAMMER.length()) {
                //System.out.println("left " + lptr);
                break;
            }
            lptr++;
        }


        while(rptr >= 0) {
            if (str.charAt(rptr) == PROGRAMMER.charAt(right)) {
                right--;
            }

            if(right == -1) {
                //System.out.println("right " + rptr);
                break;
            }

            rptr--;
        }

        System.out.println(str +" Distance: " + (rptr - lptr -1));
        return rptr - lptr -1;
    }

    public static void main(String[] args) {
        ProgrammersString programmersString = new ProgrammersString();
        programmersString.getDistance("progxrammerrxproxgrammer");
        programmersString.getDistance("xprogxrmaxemrppprmmograeiruu");
        programmersString.getDistance("programmerprogrammer");

        System.out.println("======================\n\n");
        programmersString.getDistanceWhenJumbled("progxrammerrxproxgrammer");
        programmersString.getDistanceWhenJumbled("xprogxrmaxemrppprmmograeiruu");
        programmersString.getDistanceWhenJumbled("programmerprogrammer");
    }
}
