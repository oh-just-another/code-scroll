package dev.codescroll;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final String JAVA_TYPE = "(?:<[^;{}()]*>\\s*)?" + NAME
            + "(?:\\." + NAME + ")*(?:\\s*<[^;{}()]*>)?(?:\\[\\])*";
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:" + MODIFIER + "\\s+)*(?:"
                    + "(?:fun|def|function|fn)\\s+(?:<[^>]*>\\s*)?"
                    + "(?:[\\p{L}\\p{N}_$.]+(?:<[^(){};]*>)?\\??\\.)?(" + NAME + ")\\s*(?:<[^>]*>\\s*)?\\("
                    + "|func\\s+(?:\\([^)]*\\)\\s*)?(" + NAME + ")\\s*\\("
                    + "|(?:class|struct|enum|interface|record)\\s+(" + NAME + ")\\b)"
                    + "|^\\s*(?:" + MODIFIER + "\\s+)+"
                    + JAVA_TYPE + "\\s+(" + NAME + ")"
                    + "\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern ANNOTATION = Pattern.compile("^\\s*@(" + NAME + "(?:\\." + NAME
            + ")*)(?:\\s*\\([^)]*\\))?\\s*");
    private static final Pattern JAVA_START = Pattern.compile("^\\s*(?:" + MODIFIER
            + "\\s+)+" + JAVA_TYPE + "(?:\\s+.*)?$");

    /** Literals, classes, anchors and ordinary escapes; at most one quantifier, no groups or alternation. */
    public static Pattern compileMarkerRegex(String expression) {
        if (expression == null || expression.isEmpty()) return null;
        if (expression.length() > 256) throw new IllegalArgumentException("Marker regex is limited to 256 characters.");
        boolean inClass = false;
        int quantifiers = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\\') {
                if (++i >= expression.length()) throw new IllegalArgumentException("Incomplete regex escape.");
                char escaped = expression.charAt(i);
                if (Character.isLetterOrDigit(escaped) && "dDsSwWbBnrtf".indexOf(escaped) < 0) {
                    throw new IllegalArgumentException("Unsupported regex escape; backreferences are not allowed.");
                }
            } else if (inClass) {
                if (c == '[') throw new IllegalArgumentException("Nested character classes are not supported.");
                if (c == ']') inClass = false;
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(' || c == ')' || c == '|') {
                throw new IllegalArgumentException("Groups, lookaround and alternation are not supported.");
            } else if (c == '*' || c == '+' || c == '?' || c == '{') {
                if (++quantifiers > 1) throw new IllegalArgumentException("Only one regex quantifier is supported.");
                if (c == '{') {
                    int close = expression.indexOf('}', i + 1);
                    if (close < 0 || !expression.substring(i + 1, close).matches("[0-9]{1,3}(,[0-9]{0,3})?")) {
                        throw new IllegalArgumentException("Use a simple numeric repetition bound.");
                    }
                    String[] bounds = expression.substring(i + 1, close).split(",", -1);
                    for (String bound : bounds) {
                        if (!bound.isEmpty() && Integer.parseInt(bound) > MAX_LINE) {
                            throw new IllegalArgumentException("Repetition bounds must not exceed 512.");
                        }
                    }
                    i = close;
                }
                if (i + 1 < expression.length() && (expression.charAt(i + 1) == '?'
                        || expression.charAt(i + 1) == '+')) i++;
            }
        }
        return Pattern.compile(expression);
    }

    public record Marker(int offset, String label, boolean declaration) {}

    /** Section markers that only count at the start of a comment, never inside prose or Javadoc. */
    private static final Set<String> STRUCTURAL = Set.of("region", "endregion", "mark");
    private static final Pattern HASH_DIRECTIVE = Pattern.compile("^\\s*#(?:region|endregion|pragma)\\b");

    private MarkerScanner() {}

    public static List<Marker> scan(CharSequence text, String markerWords, boolean methods,
                                    int maxMarkers, Runnable checkCanceled) {
        return scan(text, markerWords, methods, "*", "", "", maxMarkers, checkCanceled);
    }

    public static List<Marker> scan(CharSequence text, String markerWords, boolean methods,
                                    String methodNames, String methodAnnotations, String markerRegex,
                                    int maxMarkers, Runnable checkCanceled) {
        return scan(text, markerWords, methods, methodNames, methodAnnotations, markerRegex, true,
                maxMarkers, checkCanceled);
    }

    /**
     * @param markdown whether headings and fenced blocks are Markdown syntax. Outside Markdown a line
     *                 such as {@code # comment} is a shell/Python/YAML comment, not a heading.
     */
    public static List<Marker> scan(CharSequence text, String markerWords, boolean methods,
                                    String methodNames, String methodAnnotations, String markerRegex,
                                    boolean markdown, int maxMarkers, Runnable checkCanceled) {
        return scan(text, markerWords, methods, methodNames, methodAnnotations, markerRegex, markdown, true,
                maxMarkers, checkCanceled);
    }

    /**
     * @param hashComments whether {@code #} starts a comment (Python, shell, YAML...). When false (C-family,
     *                     JavaScript, CSS), only {@code #region}, {@code #endregion} and {@code #pragma}
     *                     directives at the start of a line count, so {@code #id} selectors and
     *                     {@code this.#field} are not comments.
     */
    public static List<Marker> scan(CharSequence text, String markerWords, boolean methods,
                                    String methodNames, String methodAnnotations, String markerRegex,
                                    boolean markdown, boolean hashComments, int maxMarkers, Runnable checkCanceled) {
        if (maxMarkers < 0) throw new IllegalArgumentException("maxMarkers must be nonnegative");
        if (text == null) throw new IllegalArgumentException("Text is required");
        int cap = Math.min(MAX_MARKERS, maxMarkers);
        if (cap == 0) return List.of();
        Pattern customMarkers = compileMarkerRegex(markerRegex);
        List<String> names = filters(methodNames == null ? "*" : methodNames);
        List<String> annotationFilters = filters(methodAnnotations);
        Set<String> annotations = new HashSet<>();
        int annotationLines = 0;
        StringBuilder signature = new StringBuilder();
        int[] signatureOffsets = new int[2048];
        int signatureLines = 0;
        List<String> words = new ArrayList<>();
        Set<String> structural = new HashSet<>();
        if (markerWords != null) {
            for (String word : markerWords.substring(0, Math.min(markerWords.length(), 4096)).split(",")) {
                word = word.trim();
                if (word.isEmpty()) continue;
                // A word configured in capitals (TODO, MARK) is matched case-sensitively: "mark as dirty" is prose.
                boolean capitals = word.equals(word.toUpperCase(java.util.Locale.ROOT)) && word.chars().anyMatch(Character::isLetter);
                words.add((capitals ? "(?-i:" : "(?i:") + Pattern.quote(word) + ")");
                if (STRUCTURAL.contains(word.toLowerCase(java.util.Locale.ROOT))) structural.add(word.toLowerCase(java.util.Locale.ROOT));
                if (words.size() == 128) break;
            }
        }
        Pattern markers = words.isEmpty() ? null : Pattern.compile(
                "(?<![\\p{L}\\p{N}_])(?:" + String.join("|", words) + ")(?![\\p{L}\\p{N}_])",
                Pattern.UNICODE_CASE);
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
            if (markdown && fenceMatch.matches() && !blockComment) {
                annotations.clear();
                signature.setLength(0);
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
                if (markdown && !blockComment && heading.matches()) {
                    annotations.clear();
                    signature.setLength(0);
                    result.add(new Marker(offset + heading.start(1), heading.group(1), false));
                } else {
                    StringBuilder code = new StringBuilder(line);
                    StringBuilder comments = new StringBuilder(" ".repeat(line.length()));
                    boolean[] commentChars = new boolean[line.length()];
                    char quote = 0;
                    for (int i = 0; i < line.length(); i++) {
                        char c = line.charAt(i);
                        char next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
                        if (blockComment) {
                            code.setCharAt(i, ' ');
                            comments.setCharAt(i, c);
                            commentChars[i] = true;
                            if (c == '*' && next == '/') {
                                code.setCharAt(++i, ' ');
                                comments.setCharAt(i, '/');
                                commentChars[i] = true;
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
                            comments.setCharAt(i, '/');
                            commentChars[i] = true;
                            code.setCharAt(++i, ' ');
                            comments.setCharAt(i, '*');
                            commentChars[i] = true;
                        } else if ((c == '/' && next == '/') || (c == '#' && (hashComments
                                ? (i == 0 || Character.isWhitespace(line.charAt(i - 1)))
                                : line.substring(0, i).isBlank() && HASH_DIRECTIVE.matcher(line).find()))) {
                            for (int j = i; j < line.length(); j++) {
                                code.setCharAt(j, ' ');
                                comments.setCharAt(j, line.charAt(j));
                                commentChars[j] = true;
                            }
                            break;
                        }
                    }
                    String source = code.toString();
                    int codeStart = 0;
                    Matcher annotation = ANNOTATION.matcher(source);
                    if (annotations.isEmpty()) annotationLines = 0;
                    while (annotation.find() && annotation.start() == codeStart) {
                        String name = annotation.group(1);
                        annotations.add(name);
                        annotations.add(name.substring(name.lastIndexOf('.') + 1));
                        codeStart = annotation.end();
                        annotation.region(codeStart, source.length());
                    }
                    if (++annotationLines > 8) annotations.clear();
                    source = source.substring(codeStart);
                    boolean continuing = !signature.isEmpty();
                    if (continuing && (signature.length() + source.length() + 1 > signatureOffsets.length
                            || ++signatureLines > 8)) {
                        signature.setLength(0);
                        annotations.clear();
                        continuing = false;
                    }
                    if (continuing) {
                        signatureOffsets[signature.length()] = offset + codeStart;
                        signature.append('\n');
                        for (int i = 0; i < source.length(); i++) {
                            signatureOffsets[signature.length()] = offset + codeStart + i;
                            signature.append(source.charAt(i));
                        }
                    }
                    Matcher declaration = DECLARATION.matcher(continuing ? signature : source);
                    if (methods && declaration.find()) {
                        for (int group = 1; group <= declaration.groupCount(); group++) {
                            if (declaration.group(group) != null) {
                                String name = declaration.group(group);
                                if (group == 3 || (matches(names, name) && (annotationFilters.isEmpty()
                                        || annotations.stream().anyMatch(value -> matches(annotationFilters, value))))) {
                                    int start = declaration.start(group);
                                    result.add(new Marker(continuing ? signatureOffsets[start]
                                            : offset + codeStart + start, name, true));
                                }
                                break;
                            }
                        }
                        signature.setLength(0);
                        annotations.clear();
                    } else if (methods && !continuing && JAVA_START.matcher(source).matches()
                            && source.indexOf(';') < 0 && source.indexOf('{') < 0 && source.indexOf('}') < 0) {
                        signature.append(source);
                        for (int i = 0; i < source.length(); i++) signatureOffsets[i] = offset + codeStart + i;
                        signatureLines = 1;
                    } else if (source.indexOf(';') >= 0 || source.indexOf('{') >= 0 || source.indexOf('}') >= 0) {
                        signature.setLength(0);
                        annotations.clear();
                    } else if (!continuing && !source.isBlank()) {
                        annotations.clear();
                    }
                    if (markers != null && result.size() < cap) {
                        Matcher marker = markers.matcher(comments);
                        while (result.size() < cap && marker.find()) {
                            if (structural.contains(marker.group().toLowerCase(java.util.Locale.ROOT))
                                    && !atCommentStart(comments, commentChars, marker.start())) continue;
                            result.add(new Marker(offset + marker.start(), line.substring(marker.start()).stripTrailing(), false));
                        }
                    }
                    if (customMarkers != null && result.size() < cap) {
                        Matcher marker = customMarkers.matcher(comments);
                        for (int i = 0; i < line.length() && result.size() < cap;) {
                            if (!commentChars[i]) {
                                i++;
                                continue;
                            }
                            int start = i;
                            while (i < line.length() && commentChars[i]) i++;
                            marker.region(start, i);
                            while (result.size() < cap && marker.find()) {
                                if (marker.start() == marker.end()
                                        || comments.substring(marker.start(), marker.end()).isBlank()) continue;
                                Marker value = new Marker(offset + marker.start(),
                                        line.substring(marker.start()).stripTrailing(), false);
                                if (!result.contains(value)) result.add(value);
                            }
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

    /** Whether only comment openers ({@code // /* * # ! -}) and spaces precede {@code index} in its comment. */
    private static boolean atCommentStart(CharSequence comments, boolean[] commentChars, int index) {
        for (int i = index - 1; i >= 0 && commentChars[i]; i--) {
            char c = comments.charAt(i);
            if (!Character.isWhitespace(c) && "/*#!-".indexOf(c) < 0) return false;
        }
        return true;
    }

    private static List<String> filters(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        // Bound configuration work too; persisted values are not necessarily from the form.
        for (String item : value.substring(0, Math.min(value.length(), 4096)).split("[,\\r\\n]")) {
            item = item.trim();
            if (item.startsWith("@")) item = item.substring(1);
            if (item.isEmpty()) continue;
            result.add(item);
            if (result.size() == 128) break;
        }
        return result;
    }

    private static boolean matches(List<String> filters, String value) {
        // Greedy glob matching avoids regex backtracking for user-provided wildcard filters.
        for (String filter : filters) {
            int pattern = 0;
            int cursor = 0;
            int star = -1;
            int retry = 0;
            while (cursor < value.length()) {
                if (pattern < filter.length() && filter.charAt(pattern) == '*') {
                    star = pattern++;
                    retry = cursor;
                } else if (pattern < filter.length() && (filter.charAt(pattern) == '?'
                        || filter.charAt(pattern) == value.charAt(cursor))) {
                    pattern++;
                    cursor++;
                } else if (star >= 0) {
                    pattern = star + 1;
                    cursor = ++retry;
                } else {
                    break;
                }
            }
            while (pattern < filter.length() && filter.charAt(pattern) == '*') pattern++;
            if (cursor == value.length() && pattern == filter.length()) return true;
        }
        return false;
    }
}
