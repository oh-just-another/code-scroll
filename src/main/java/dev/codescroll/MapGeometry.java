package dev.codescroll;

/** All map coordinates are logical document lines, independent of editor font size. */
public record MapGeometry(int lines, int height, double scale, double origin) {
    /** Upper bound for Fill, so a three-line file does not turn into three solid blocks. */
    public static final double MAX_FILL_SCALE = 16;

    public static MapGeometry create(int lines, int height, boolean fit, int pixelsPerLine,
                                     int visibleStart, int visibleEnd) {
        return create(lines, height, fit, false, pixelsPerLine, visibleStart, visibleEnd);
    }

    /** @param fill with {@code fit}, stretch short documents beyond pixels-per-line to the map height. */
    public static MapGeometry create(int lines, int height, boolean fit, boolean fill, int pixelsPerLine,
                                     int visibleStart, int visibleEnd) {
        lines = Math.max(1, lines);
        height = Math.max(1, Math.min(4096, height));
        pixelsPerLine = Math.clamp(pixelsPerLine, 1, 8);
        double available = (double) height / lines;
        double scale = !fit ? pixelsPerLine
                : fill ? Math.min(MAX_FILL_SCALE, available) : Math.min(pixelsPerLine, available);
        double mapHeight = lines * scale;
        double center = (visibleStart + (double) visibleEnd) / 2;
        double origin = Math.max(0, Math.min(mapHeight - height, center * scale - height / 2.0));
        return new MapGeometry(lines, height, scale, origin);
    }

    public double y(double line) { return line * scale - origin; }
    public int line(double y) {
        return Math.max(0, Math.min(lines - 1, (int) Math.floor((y + origin) / scale)));
    }
}
