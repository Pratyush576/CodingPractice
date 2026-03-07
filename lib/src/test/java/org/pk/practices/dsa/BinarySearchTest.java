package org.pk.practices.dsa;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class BinarySearchTest {
    private BinarySearch search = new BinarySearch();

    // This is your "DataProvider" method
    static Stream<Arguments> searchTestProvider() {
        return Stream.of(
                arguments(new int[]{1, 2, 3}, 2, 1),
                arguments(new int[]{10, -2, 5}, 7, -1),
                arguments(new int[]{}, 0, -1),
                arguments(new int[]{1}, 0, -1),
                arguments(new int[]{0}, 0, 0)
        );
    }

    @ParameterizedTest
    @MethodSource("searchTestProvider")
    public void searchTest(int[] array, int number, int expected) {
        Assertions.assertEquals(expected, search.search(array, number));
    }
}
