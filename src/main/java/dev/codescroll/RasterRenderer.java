package dev.codescroll;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;

/** A bounded, software-only raster of sampled document lines. */
public final class RasterRenderer {
    public static final int MAX_WIDTH = 300;
    public static final int MAX_HEIGHT = 4096;
    public static final int MAX_COLUMNS = 512;

    public record Token(int start, int end, int rgb) {}

    private RasterRenderer() {}

    public static BufferedImage render(CharSequence text, int width, int height,
                                       int[] starts, int[] ends, int[] rows,
                                       int foreground, int background, List<Token> tokens,
                                       Runnable checkCanceled) {
        if (text == null || starts == null || ends == null || rows == null || tokens == null
                || starts.length != ends.length || starts.length != rows.length) {
            throw new IllegalArgumentException("Text, tokens and matching sample arrays are required");
        }
        width = Math.max(40, Math.min(MAX_WIDTH, width));
        height = Math.max(1, Math.min(MAX_HEIGHT, height));
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
        byte[] samplesPerRow = new byte[height];
        for (int i = 0; i < starts.length; i++) {
            if ((i & 63) == 0 && checkCanceled != null) checkCanceled.run();
            int row = rows[i];
            if (samplesPerRow[row] == 3) continue;
            samplesPerRow[row]++;
            int limit = starts[i] + Math.min(MAX_COLUMNS, ends[i] - starts[i]);
            int column = 0;
            for (int offset = starts[i]; offset < limit && column < width - 10; offset++) {
                char c = text.charAt(offset);
                if (c == '\t') {
                    column += 4 - column % 4;
                    continue;
                }
                if (c == '\r' || c == '\n') break;
                if (!Character.isWhitespace(c)) {
                    int low = 0;
                    int high = colors.length - 1;
                    while (low <= high) {
                        int mid = (low + high) >>> 1;
                        if (colors[mid].start() <= offset) low = mid + 1;
                        else high = mid - 1;
                    }
                    int rgb = high >= 0 && offset < colors[high].end()
                            ? colors[high].rgb() : foreground;
                    pixels[row * width + 4 + column] = rgb & 0xffffff;
                }
                // A supplementary code point occupies one cell, not two surrogate cells.
                if (Character.isHighSurrogate(c) && offset + 1 < limit
                        && Character.isLowSurrogate(text.charAt(offset + 1))) offset++;
                column++;
            }
        }
        return image;
    }
}
