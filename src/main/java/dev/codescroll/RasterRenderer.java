package dev.codescroll;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;

/**
 * A bounded, software-only raster of sampled document lines.
 *
 * <p>Each sample is one raster row of one document line. When a line spans several rows, the row's
 * position inside the line ({@code phases}) shapes the stroke: CLEAN keeps a light first row as
 * spacing between lines; ACCURATE also uses each glyph's ink in its upper and lower half, so capitals,
 * ascenders and descenders read differently from lower-case text.
 */
public final class RasterRenderer {
    /** Raster limits in device pixels. */
    public static final int MAX_WIDTH = 600;
    public static final int MAX_HEIGHT = 4096;
    public static final int MAX_COLUMNS = 512;
    /** Default left indent of the text, in device pixels at 1x. */
    public static final int INDENT = 4;

    public record Token(int start, int end, int rgb) {}

    /** Per-glyph ink in the upper and lower half, 0..256, measured once from a monospaced font. */
    private static final int[] TOP = new int[128];
    private static final int[] BOTTOM = new int[128];
    static final boolean MEASURED;

    static {
        boolean measured;
        try {
            measured = measureGlyphs();
        } catch (RuntimeException | Error exception) {
            // Headless systems without fonts still render; every glyph then has the same shape.
            measured = false;
        }
        if (!measured) {
            Arrays.fill(TOP, 180);
            Arrays.fill(BOTTOM, 180);
        }
        MEASURED = measured;
    }

    private RasterRenderer() {}

    public static BufferedImage render(CharSequence text, int width, int height,
                                       int[] starts, int[] ends, int[] rows,
                                       int foreground, int background, List<Token> tokens,
                                       Runnable checkCanceled) {
        return render(text, width, height, starts, ends, rows, foreground, background, tokens, false, checkCanceled);
    }

    public static BufferedImage render(CharSequence text, int width, int height,
                                       int[] starts, int[] ends, int[] rows,
                                       int foreground, int background, List<Token> tokens,
                                       boolean accurate, Runnable checkCanceled) {
        return render(text, width, height, starts, ends, rows, foreground, background, tokens, accurate, 100,
                checkCanceled);
    }

    public static BufferedImage render(CharSequence text, int width, int height,
                                       int[] starts, int[] ends, int[] rows,
                                       int foreground, int background, List<Token> tokens,
                                       boolean accurate, int intensity, Runnable checkCanceled) {
        return render(text, width, height, starts, ends, rows, null, 1, 1, INDENT,
                foreground, background, tokens, accurate, intensity, checkCanceled);
    }

    /**
     * @param phases      for each sample, the row's center as a fraction of its line's height (0..1);
     *                    null when every line is a single row
     * @param rowsPerLine raster rows per document line (below 1 when lines are compressed)
     * @param charWidth   raster pixels per character cell, the device scale on HiDPI screens
     * @param indent      left indent in raster pixels
     * @param accurate    shape strokes by glyph ink instead of drawing CLEAN strokes
     * @param intensity   text strength against the background in percent
     */
    public static BufferedImage render(CharSequence text, int width, int height,
                                       int[] starts, int[] ends, int[] rows, float[] phases,
                                       double rowsPerLine, int charWidth, int indent,
                                       int foreground, int background, List<Token> tokens,
                                       boolean accurate, int intensity, Runnable checkCanceled) {
        return render(text, width, height, starts, ends, rows, phases, null, rowsPerLine, charWidth, indent,
                foreground, background, tokens, accurate, intensity, checkCanceled);
    }

    /**
     * @param coverages for each sample, the fraction of its raster row the document line covers. When
     *                  given, samples are antialiased: every line sharing a row contributes in proportion
     *                  to its coverage, instead of the first three lines overwriting each other. Thin or
     *                  fractional lines then fade smoothly rather than flickering in and out of pixels.
     */
    public static BufferedImage render(CharSequence text, int width, int height,
                                       int[] starts, int[] ends, int[] rows, float[] phases, float[] coverages,
                                       double rowsPerLine, int charWidth, int indent,
                                       int foreground, int background, List<Token> tokens,
                                       boolean accurate, int intensity, Runnable checkCanceled) {
        if (text == null || starts == null || ends == null || rows == null || tokens == null
                || starts.length != ends.length || starts.length != rows.length
                || (phases != null && phases.length != starts.length)
                || (coverages != null && coverages.length != starts.length)) {
            throw new IllegalArgumentException("Text, tokens and matching sample arrays are required");
        }
        charWidth = Math.clamp(charWidth, 1, 4);
        width = Math.max(40, Math.min(MAX_WIDTH, width));
        height = Math.max(1, Math.min(MAX_HEIGHT, height));
        indent = Math.clamp(indent, 0, width / 2);
        int strength = 256 * Math.clamp(intensity, 0, 100) / 100;
        int length = text.length();
        for (int i = 0; i < starts.length; i++) {
            if ((i & 63) == 0 && checkCanceled != null) checkCanceled.run();
            if (starts[i] < 0 || ends[i] < starts[i] || ends[i] > length
                    || rows[i] < 0 || rows[i] >= height) {
                throw new IllegalArgumentException("Invalid sample at index " + i);
            }
        }
        // Copy the sorted token list once: binary search must not depend on list implementation.
        Token[] colors = tokens.toArray(Token[]::new);
        int previous = -1;
        for (int i = 0; i < colors.length; i++) {
            if ((i & 63) == 0 && checkCanceled != null) checkCanceled.run();
            Token token = colors[i];
            if (token == null || token.start() < 0 || token.start() < previous
                    || token.end() < token.start() || token.end() > length) {
                throw new IllegalArgumentException("Invalid or unsorted token at index " + i);
            }
            previous = token.start();
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, background & 0xffffff);
        int columns = (width - indent - 6) / charWidth;
        if (coverages != null) {
            accumulate(text, width, starts, ends, rows, phases, coverages, rowsPerLine, charWidth, indent, columns,
                    foreground, background, colors, accurate, strength, pixels, checkCanceled);
            return image;
        }
        byte[] samplesPerRow = new byte[height];
        for (int i = 0; i < starts.length; i++) {
            if ((i & 63) == 0 && checkCanceled != null) checkCanceled.run();
            int row = rows[i];
            if (samplesPerRow[row] == 3) continue;
            samplesPerRow[row]++;
            float phase = phases == null ? 0.5f : phases[i];
            int limit = starts[i] + Math.min(MAX_COLUMNS, ends[i] - starts[i]);
            int column = 0;
            for (int offset = starts[i]; offset < limit && column < columns; offset++) {
                char c = text.charAt(offset);
                if (c == '\t') {
                    column += 4 - column % 4;
                    continue;
                }
                if (c == '\r' || c == '\n') break;
                if (!Character.isWhitespace(c)) {
                    int weight = weight(c, phase, rowsPerLine, accurate) * strength >> 8;
                    if (weight > 0) {
                        int low = 0;
                        int high = colors.length - 1;
                        while (low <= high) {
                            int mid = (low + high) >>> 1;
                            if (colors[mid].start() <= offset) low = mid + 1;
                            else high = mid - 1;
                        }
                        int rgb = high >= 0 && offset < colors[high].end() ? colors[high].rgb() : foreground;
                        int value = weight >= 256 ? rgb & 0xffffff : blend(rgb, background, weight);
                        int x = indent + column * charWidth;
                        for (int dx = 0; dx < charWidth && x + dx < width; dx++) pixels[row * width + x + dx] = value;
                    }
                }
                // A supplementary code point occupies one cell, not two surrogate cells.
                if (Character.isHighSurrogate(c) && offset + 1 < limit
                        && Character.isLowSurrogate(text.charAt(offset + 1))) offset++;
                column++;
            }
        }
        return image;
    }

    /**
     * Antialiased rendering: per raster row, each column sums color weighted by glyph strength times line
     * coverage, then blends the average color over the background by the total (capped) weight.
     * Samples must be grouped by row, which is how the minimap produces them.
     */
    private static void accumulate(CharSequence text, int width, int[] starts, int[] ends, int[] rows,
                                   float[] phases, float[] coverages, double rowsPerLine, int charWidth, int indent,
                                   int columns, int foreground, int background, Token[] colors, boolean accurate,
                                   int strength, int[] pixels, Runnable checkCanceled) {
        float[] red = new float[columns];
        float[] green = new float[columns];
        float[] blue = new float[columns];
        float[] ink = new float[columns];
        int row = -1;
        for (int i = 0; i <= starts.length; i++) {
            if ((i & 63) == 0 && checkCanceled != null) checkCanceled.run();
            if (i == starts.length || rows[i] != row) {
                if (row >= 0) flush(row, width, charWidth, indent, columns, background, red, green, blue, ink, pixels);
                if (i == starts.length) break;
                row = rows[i];
            }
            float phase = phases == null ? 0.5f : phases[i];
            float cover = Math.clamp(coverages[i], 0f, 1f);
            if (cover <= 0) continue;
            int limit = starts[i] + Math.min(MAX_COLUMNS, ends[i] - starts[i]);
            int column = 0;
            for (int offset = starts[i]; offset < limit && column < columns; offset++) {
                char c = text.charAt(offset);
                if (c == '\t') {
                    column += 4 - column % 4;
                    continue;
                }
                if (c == '\r' || c == '\n') break;
                if (!Character.isWhitespace(c)) {
                    float weight = (weight(c, phase, rowsPerLine, accurate) * strength >> 8) / 256f * cover;
                    if (weight > 0) {
                        int rgb = color(colors, offset, foreground);
                        red[column] += ((rgb >> 16) & 0xff) * weight;
                        green[column] += ((rgb >> 8) & 0xff) * weight;
                        blue[column] += (rgb & 0xff) * weight;
                        ink[column] += weight;
                    }
                }
                if (Character.isHighSurrogate(c) && offset + 1 < limit
                        && Character.isLowSurrogate(text.charAt(offset + 1))) offset++;
                column++;
            }
        }
    }

    private static void flush(int row, int width, int charWidth, int indent, int columns, int background,
                              float[] red, float[] green, float[] blue, float[] ink, int[] pixels) {
        for (int column = 0; column < columns; column++) {
            float total = ink[column];
            if (total <= 0) continue;
            int rgb = ((int) (red[column] / total) << 16) | ((int) (green[column] / total) << 8) | (int) (blue[column] / total);
            int value = blend(rgb, background, Math.min(256, Math.round(total * 256)));
            int x = indent + column * charWidth;
            for (int dx = 0; dx < charWidth && x + dx < width; dx++) pixels[row * width + x + dx] = value;
            red[column] = green[column] = blue[column] = ink[column] = 0;
        }
    }

    private static int color(Token[] colors, int offset, int foreground) {
        int low = 0;
        int high = colors.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (colors[mid].start() <= offset) low = mid + 1;
            else high = mid - 1;
        }
        return high >= 0 && offset < colors[high].end() ? colors[high].rgb() : foreground;
    }

    /**
     * Stroke strength (0..256) of glyph {@code c} on a raster row at {@code phase} of a line that spans
     * {@code rowsPerLine} rows.
     */
    static int weight(char c, float phase, double rowsPerLine, boolean accurate) {
        // With three or more rows per line the first row stays light: the gap between code lines.
        boolean firstRow = rowsPerLine >= 3 && phase < 1 / rowsPerLine;
        if (!accurate) {
            return firstRow ? 32 : 256;
        }
        boolean ascii = c < 128;
        int top = ascii ? TOP[c] : 180;
        int bottom = ascii ? BOTTOM[c] : 180;
        if (rowsPerLine < 2) return (top + bottom) / 2;
        if (firstRow) return top / 6;
        return phase < 0.55f ? top : bottom;
    }

    /** Average ink of a glyph on a single-row line, 0..256. */
    static int coverage(char c) {
        return weight(c, 0.5f, 1, true);
    }

    static int blend(int rgb, int background, int coverage) {
        int r = (((rgb >> 16) & 0xff) * coverage + ((background >> 16) & 0xff) * (256 - coverage)) >> 8;
        int g = (((rgb >> 8) & 0xff) * coverage + ((background >> 8) & 0xff) * (256 - coverage)) >> 8;
        int b = ((rgb & 0xff) * coverage + (background & 0xff) * (256 - coverage)) >> 8;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Rasterizes printable ASCII with the platform's monospaced font and measures the ink above and
     * below the top of the x-height, normalized so the inkiest glyph of each half is 256.
     */
    private static boolean measureGlyphs() {
        int size = 48;
        BufferedImage image = new BufferedImage(size, size * 2, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, size));
            FontMetrics metrics = g.getFontMetrics();
            int baseline = metrics.getAscent();
            int bottomEdge = Math.min(image.getHeight(), baseline + metrics.getDescent());
            int xHeight = (int) Math.round(g.getFont().createGlyphVector(g.getFontRenderContext(), "x")
                    .getVisualBounds().getHeight());
            // Upper half: capitals and ascenders above the x-height; lower half: the x-height body and descenders.
            int split = Math.clamp(baseline - xHeight, 1, bottomEdge - 1);
            byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            double[] top = new double[128];
            double[] bottom = new double[128];
            double maxTop = 0;
            double maxBottom = 0;
            for (char c = 33; c < 127; c++) {
                Arrays.fill(data, (byte) 0);
                g.setColor(java.awt.Color.WHITE);
                g.drawString(String.valueOf(c), 0, baseline);
                for (int y = 0; y < bottomEdge; y++) {
                    for (int x = 0; x < size; x++) {
                        int ink = data[y * size + x] & 0xff;
                        if (y < split) top[c] += ink;
                        else bottom[c] += ink;
                    }
                }
                maxTop = Math.max(maxTop, top[c]);
                maxBottom = Math.max(maxBottom, bottom[c]);
            }
            if (maxTop <= 0 || maxBottom <= 0) return false;
            for (char c = 33; c < 127; c++) {
                // A floor keeps tiny glyphs (dots, commas) visible at minimap scale.
                TOP[c] = Math.max(40, (int) Math.round(top[c] / maxTop * 256));
                BOTTOM[c] = Math.max(40, (int) Math.round(bottom[c] / maxBottom * 256));
            }
            return true;
        } finally {
            g.dispose();
        }
    }
}
