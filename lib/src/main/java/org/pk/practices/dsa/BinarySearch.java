package org.pk.practices.dsa;

public class BinarySearch {

    /**
     * Assumption: Array will be sorted.
     *
     * @param array
     * @param number
     *
     * @return -1 if not found, else index of number.
     */
    public int search(int[] array, int number) {
        // If array size is zero, element can't be present in the array.
        if(array.length == 0) {
            return -1;
        }

        // If array size is not zero, code will check if element is present in the given array using binary search
        int start = 0;
        int end = array.length - 1;

        while(end > start) {
            int mid = (start + end) / 2;

            if(array[mid] == number) {
                return mid;
            } else {
                if(array[mid] > number) {
                    end = mid - 1;
                } else {
                    start = mid + 1;
                }
            }
        }

        if(start == end) {
            if(array[start] == number) {
                return start;
            }
        }

        return -1;
    }
}
