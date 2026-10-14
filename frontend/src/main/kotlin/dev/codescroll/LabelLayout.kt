package dev.codescroll

/**
 * Chooses which minimap labels to draw so that none overlap.
 *
 * The choice is made in document-map coordinates, independent of the scroll position: deciding it in
 * panel coordinates made labels near the panel's top edge push each other in and out while scrolling,
 * so labels blinked and vanished as they reached the edge.
 */
object LabelLayout {
    /**
     * @param tops label tops in map-document pixels, ascending
     * @param height label height in pixels, including spacing
     * @return for each label, whether it is drawn
     */
    fun visible(tops: DoubleArray, height: Double): BooleanArray {
        val visible = BooleanArray(tops.size)
        var nextFree = Double.NEGATIVE_INFINITY
        for (i in tops.indices) {
            if (tops[i] >= nextFree) {
                visible[i] = true
                nextFree = tops[i] + height
            }
        }
        return visible
    }
}
