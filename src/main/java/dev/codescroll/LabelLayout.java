package dev.codescroll;

/**
 * Chooses which minimap labels to draw so that none overlap.
 *
 * <p>The choice is made in document-map coordinates, independent of the scroll position: deciding it
 * in panel coordinates made labels near the panel's top edge push each other in and out while
 * scrolling, so labels blinked and vanished as they reached the edge.
 */
public final class LabelLayout {
    private LabelLayout() {}

    /**
     * @param tops   label tops in map-document pixels, ascending
     * @param height label height in pixels, including spacing
     * @return for each label, whether it is drawn
     */
    public static boolean[] visible(double[] tops, double height) {
        boolean[] visible = new boolean[tops.length];
        double nextFree = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tops.length; i++) {
            if (tops[i] >= nextFree) {
                visible[i] = true;
                nextFree = tops[i] + height;
            }
        }
        return visible;
    }
}
