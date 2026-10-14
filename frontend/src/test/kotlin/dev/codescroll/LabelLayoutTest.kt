package dev.codescroll

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class LabelLayoutTest {
    @Test
    fun overlappingLabelsAreSkippedGreedilyFromTheTop() {
        assertArrayEquals(booleanArrayOf(true, false, true, false, true),
            LabelLayout.visible(doubleArrayOf(0.0, 5.0, 13.0, 20.0, 26.0), 13.0))
        assertArrayEquals(BooleanArray(0), LabelLayout.visible(DoubleArray(0), 13.0))
    }

    @Test
    fun choiceDoesNotDependOnTheScrollPosition() {
        val tops = doubleArrayOf(100.0, 108.0, 121.0, 130.0)
        val expected = LabelLayout.visible(tops, 13.0)
        for (scroll in listOf(0.0, 95.0, 104.0, 110.0, 125.0)) {
            assertArrayEquals(expected, LabelLayout.visible(DoubleArray(tops.size) { tops[it] - scroll }, 13.0), "scroll $scroll")
        }
    }
}
