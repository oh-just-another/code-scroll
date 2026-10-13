package dev.codescroll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LabelLayoutTest {
    @Test
    void overlappingLabelsAreSkippedGreedilyFromTheTop() {
        assertArrayEquals(new boolean[]{true, false, true, false, true},
                LabelLayout.visible(new double[]{0, 5, 13, 20, 26}, 13));
        assertArrayEquals(new boolean[0], LabelLayout.visible(new double[0], 13));
    }

    @Test
    void choiceDoesNotDependOnTheScrollPosition() {
        double[] tops = {100, 108, 121, 130};
        boolean[] expected = LabelLayout.visible(tops, 13);
        for (double scroll : new double[]{0, 95, 104, 110, 125}) {
            double[] shifted = new double[tops.length];
            for (int i = 0; i < tops.length; i++) shifted[i] = tops[i] - scroll;
            assertArrayEquals(expected, LabelLayout.visible(shifted, 13), "scroll " + scroll);
        }
    }
}
