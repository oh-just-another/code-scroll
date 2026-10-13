package dev.codescroll;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative text heuristics, not PSI: declarations in unusual syntax may be missed. */
public final class MarkerScanner {
    private static final int MAX_INPUT = 1024 * 1024;
    private static final int MAX_LINE = 512;
    private static final int MAX_MARKERS = 2000;
    private static final String NAME = "[\\p{L}_$][\\p{L}\\p{N}_$]*";
    private static final String MODIFIER = "(?:public|protected|private|internal|static|final|abstract|"
            + "override|open|suspend|inline|tailrec|operator|infix|external|async|export|default|"
            + "pub(?:\\([^)]*\\))?|unsafe|const|virtual|sealed|partial|new|extern|synchronized|native|strictfp)";
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:" + MODIFIER + "\\s+)*(?:"
                    + "(?:fun|def|function|fn)\\s+(" + NAME + ")\\s*(?:<[^>]*>\\s*)?\\("
                    + "|func\\s+(?:\\([^)]*\\)\\s*)?(" + NAME + ")\\s*\\("
                    + "|(?:class|struct|enum|interface|record)\\s+(" + NAME + ")\\b)"
                    + "|^\\s*(?:" + MODIFIER + "\\s+)+"
                    + "[\\p{L}_$][\\p{L}\\p{N}_$.<>?,\\[\\]]*\\s+(" + NAME + ")"
                    + "\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    public record Marker(int offset, String label, boolean declaration) {}

    private MarkerScanner() {}

    public static List<Marker> scan(CharSequence text, String markerWords, boolean methods,
                                    int maxMarkers, Runnable checkCanceled) {
        if (maxMarkers < 0) throw new IllegalArgumentException("maxMarkers must be nonnegative");
        if (text == null) throw new IllegalArgumentException("Text is required");
        int cap = Math.min(MAX_MARKERS, maxMarkers);
        if (cap == 0) return List.of();
        List<String> words = new ArrayList<>();
        if (markerWords != null) {
            for (String word : markerWords.split(",")) {
                word = word.trim();
                if (!word.isEmpty()) words.add(Pattern.quote(word));
            }
        }
        Pattern markers = words.isEmpty() ? null : Pattern.compile(
                "(?<![\\p{L}\\p{N}_])(?:" + String.join("|", words) + ")(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Marker> result = new ArrayList<>();
        int limit = Math.min(text.length(), MAX_INPUT);
        int offset = 0;
        int lineNumber = 0;
        boolean blockComment = false;
        char fence = 0;
        int fenceLength = 0;
        while (offset < limit && result.size() < cap) {
            if ((lineNumber++ & 63) == 0 && checkCanceled != null) checkCanceled.run();
            int end = offset;
            while (end < limit && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                if ((end & 4095) == 0 && checkCanceled != null) checkCanceled.run();
                end++;
            }
            int boundedEnd = offset + Math.min(MAX_LINE, end - offset);
            // Do not split a surrogate pair at the inspection boundary.
            if (boundedEnd > offset && Character.isHighSurrogate(text.charAt(boundedEnd - 1))) boundedEnd--;
            String line = text.subSequence(offset, boundedEnd).toString();
            Matcher fenceMatch = FENCE.matcher(line);
            if (fenceMatch.matches() && !blockComment) {
                String run = fenceMatch.group(1);
                if (fence == 0) {
                    fence = run.charAt(0);
                    fenceLength = run.length();
                } else if (run.charAt(0) == fence && run.length() >= fenceLength
                        && fenceMatch.group(2).isBlank()) {
                    fence = 0;
                }
            } else if (fence == 0) {
                Matcher heading = HEADING.matcher(line);
                if (!blockComment && heading.matches()) {
                    result.add(new Marker(offset + heading.start(1), heading.group(1), false));
                } else {
                    StringBuilder code = new StringBuilder(line);
                    StringBuilder comments = new StringBuilder(" ".repeat(line.length()));
                    char quote = 0;
                    for (int i = 0; i < line.length(); i++) {
                        char c = line.charAt(i);
                        char next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
                        if (blockComment) {
                            code.setCharAt(i, ' ');
                            comments.setCharAt(i, c);
                            if (c == '*' && next == '/') {
                                code.setCharAt(++i, ' ');
                                blockComment = false;
                            }
                        } else if (quote != 0) {
                            code.setCharAt(i, ' ');
                            if (c == '\\' && i + 1 < line.length()) code.setCharAt(++i, ' ');
                            else if (c == quote) quote = 0;
                        } else if (c == '\'' || c == '"' || c == '`') {
                            quote = c;
                            code.setCharAt(i, ' ');
                        } else if (c == '/' && next == '*') {
                            blockComment = true;
                            code.setCharAt(i, ' ');
                            code.setCharAt(++i, ' ');
                        } else if ((c == '/' && next == '/') || c == '#') {
                            for (int j = i; j < line.length(); j++) {
                                code.setCharAt(j, ' ');
                                comments.setCharAt(j, line.charAt(j));
                            }
                            break;
                        }
                    }
                    Matcher declaration = DECLARATION.matcher(code);
                    if (methods && declaration.find()) {
                        for (int group = 1; group <= declaration.groupCount(); group++) {
                            if (declaration.group(group) != null) {
                                result.add(new Marker(offset + declaration.start(group), declaration.group(group), true));
                                break;
                            }
                        }
                    }
                    if (markers != null && result.size() < cap) {
                        Matcher marker = markers.matcher(comments);
                        while (result.size() < cap && marker.find()) {
                            result.add(new Marker(offset + marker.start(), line.substring(marker.start()).stripTrailing(), false));
                        }
                    }
                }
            }
            offset = end;
            if (offset < limit && text.charAt(offset) == '\r') offset++;
            if (offset < limit && text.charAt(offset) == '\n') offset++;
        }
        return List.copyOf(result);
    }
}
