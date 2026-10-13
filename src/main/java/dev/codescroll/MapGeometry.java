package dev.codescroll;

/**
 * Maps document lines to minimap pixels.
 *
 * <p>{@code scale} is map pixels per document line; {@code origin} is the map-document y shown at the
 * top of the panel. The viewport frame is the editor's visible area at map scale, clamped to the
 * document. In proportional mode the map scrolls so that the frame travels from the top of the panel
 * to its bottom in step with the editor, instead of staying centered.
 */
public record MapGeometry(int lines, int height, double scale, double origin) {
    /** Upper bound for Fill, so a three-line file does not turn into three solid blocks. */
    public static final double MAX_FILL_SCALE = 16;
    /** The frame never gets thinner than this, unless the document itself is shorter. */
    public static final double MIN_VIEWPORT = 8;

    public static MapGeometry create(int lines, int height, boolean fit, int pixelsPerLine,
                                     int visibleStart, int visibleEnd) {
        return forScroll(lines, height, fit, false, pixelsPerLine, visibleStart, visibleEnd - visibleStart);
    }

    public static MapGeometry create(int lines, int height, boolean fit, boolean fill, int pixelsPerLine,
                                     int visibleStart, int visibleEnd) {
        return forScroll(lines, height, fit, fill, pixelsPerLine, visibleStart, visibleEnd - visibleStart);
    }

    /**
     * @param topLine   first visible line, fractional when the editor is scrolled mid-line
     * @param spanLines how many lines the editor's visible area can show
     * @param fill      with {@code fit}, stretch short documents beyond pixels-per-line
     */
    public static MapGeometry forScroll(int lines, int height, boolean fit, boolean fill, int pixelsPerLine,
                                        double topLine, double spanLines) {
        lines = Math.max(1, lines);
        height = Math.max(1, Math.min(4096, height));
        pixelsPerLine = Math.clamp(pixelsPerLine, 1, 8);
        double available = (double) height / lines;
        double scale = !fit ? pixelsPerLine
                : fill ? Math.min(MAX_FILL_SCALE, available) : Math.min(pixelsPerLine, available);
        double documentHeight = lines * scale;
        double maxOrigin = Math.max(0, documentHeight - height);
        double origin = 0;
        if (maxOrigin > 0) {
            double frame = frameHeight(documentHeight, spanLines * scale);
            double frameTop = Math.clamp(topLine * scale, 0, Math.max(0, documentHeight - frame));
            double maxFrameTop = Math.max(0, documentHeight - frame);
            // Move the map at the rate that keeps the frame's position proportional to the scroll.
            origin = maxFrameTop > 0 ? frameTop / maxFrameTop * maxOrigin : 0;
            // ...but keep the whole frame inside the panel; a frame taller than the panel shows its top.
            double lowest = Math.max(0, frameTop + frame - height);
            double highest = Math.min(frameTop, maxOrigin);
            origin = lowest > highest ? highest : Math.clamp(origin, lowest, highest);
        }
        return new MapGeometry(lines, height, scale, origin);
    }

    private static double frameHeight(double documentHeight, double frame) {
        return Math.min(documentHeight, Math.max(MIN_VIEWPORT, frame));
    }

    public double documentHeight() { return lines * scale; }

    public double y(double line) { return line * scale - origin; }

    public int line(double y) {
        return Math.max(0, Math.min(lines - 1, (int) Math.floor((y + origin) / scale)));
    }

    /** Panel y of the frame's top for an editor showing {@code spanLines} lines from {@code topLine}. */
    public double frameTop(double topLine, double spanLines) {
        double frame = frameHeight(spanLines);
        return Math.clamp(topLine * scale, 0, Math.max(0, documentHeight() - frame)) - origin;
    }

    public double frameHeight(double spanLines) {
        return frameHeight(documentHeight(), spanLines * scale);
    }

    /** The same mapping with a different first row, for a raster that covers part of the document. */
    public MapGeometry window(int windowHeight, double windowOrigin) {
        return new MapGeometry(lines, Math.max(1, windowHeight), scale, windowOrigin);
    }
}
