package dev.codescroll

import java.util.Locale
import java.util.regex.Pattern

/** Conservative text heuristics, not PSI: declarations in unusual syntax may be missed. */
object MarkerScanner {
    private const val MAX_INPUT = 1024 * 1024
    private const val MAX_LINE = 512
    private const val MAX_MARKERS = 2000
    private const val NAME = """[\p{L}_$][\p{L}\p{N}_$]*"""
    private const val MODIFIER = "(?:public|protected|private|internal|static|final|abstract|" +
        "override|open|suspend|inline|tailrec|operator|infix|external|async|export|default|" +
        """pub(?:\([^)]*\))?|unsafe|const|virtual|sealed|partial|new|extern|synchronized|native|strictfp)"""
    private const val JAVA_TYPE = """(?:<[^;{}()]*>\s*)?""" + NAME +
        """(?:\.""" + NAME + """)*(?:\s*<[^;{}()]*>)?(?:\[\])*"""
    private val DECLARATION = Pattern.compile(
        """^\s*(?:""" + MODIFIER + """\s+)*(?:""" +
            """(?:fun|def|function|fn)\s+(?:<[^>]*>\s*)?""" +
            """(?:[\p{L}\p{N}_$.]+(?:<[^(){};]*>)?\??\.)?(""" + NAME + """)\s*(?:<[^>]*>\s*)?\(""" +
            """|func\s+(?:\([^)]*\)\s*)?(""" + NAME + """)\s*\(""" +
            """|(?:class|struct|enum|interface|record)\s+(""" + NAME + """)\b)""" +
            """|^\s*(?:""" + MODIFIER + """\s+)+""" +
            JAVA_TYPE + """\s+(""" + NAME + ")" +
            """\s*\([^;{}]*\)\s*(?:throws\s+[\w.,\s]+)?\s*\{""",
    )
    private val HEADING = Pattern.compile("""^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$""")
    private val FENCE = Pattern.compile("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val ANNOTATION = Pattern.compile("""^\s*@(""" + NAME + """(?:\.""" + NAME + """)*)(?:\s*\([^)]*\))?\s*""")
    private val JAVA_START = Pattern.compile("""^\s*(?:""" + MODIFIER + """\s+)+""" + JAVA_TYPE + """(?:\s+.*)?$""")

    /** Section markers that only count at the start of a comment, never inside prose or Javadoc. */
    private val STRUCTURAL = setOf("region", "endregion", "mark")
    private val HASH_DIRECTIVE = Pattern.compile("""^\s*#(?:region|endregion|pragma)\b""")

    data class Marker(val offset: Int, val label: String, val declaration: Boolean)

    /** Literals, classes, anchors and ordinary escapes; at most one quantifier, no groups or alternation. */
    fun compileMarkerRegex(expression: String?): Pattern? {
        if (expression.isNullOrEmpty()) return null
        require(expression.length <= 256) { "Marker regex is limited to 256 characters." }
        var inClass = false
        var quantifiers = 0
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '\\' -> {
                    require(++i < expression.length) { "Incomplete regex escape." }
                    val escaped = expression[i]
                    require(!escaped.isLetterOrDigit() || escaped in "dDsSwWbBnrtf") {
                        "Unsupported regex escape; backreferences are not allowed."
                    }
                }
                inClass -> {
                    require(c != '[') { "Nested character classes are not supported." }
                    if (c == ']') inClass = false
                }
                c == '[' -> inClass = true
                c == '(' || c == ')' || c == '|' ->
                    throw IllegalArgumentException("Groups, lookaround and alternation are not supported.")
                c == '*' || c == '+' || c == '?' || c == '{' -> {
                    require(++quantifiers <= 1) { "Only one regex quantifier is supported." }
                    if (c == '{') {
                        val close = expression.indexOf('}', i + 1)
                        require(close >= 0 && expression.substring(i + 1, close).matches(Regex("[0-9]{1,3}(,[0-9]{0,3})?"))) {
                            "Use a simple numeric repetition bound."
                        }
                        for (bound in expression.substring(i + 1, close).split(",")) {
                            require(bound.isEmpty() || bound.toInt() <= MAX_LINE) { "Repetition bounds must not exceed 512." }
                        }
                        i = close
                    }
                    if (i + 1 < expression.length && (expression[i + 1] == '?' || expression[i + 1] == '+')) i++
                }
            }
            i++
        }
        return Pattern.compile(expression)
    }

    /**
     * @param methodNames glob filters for method labels; types are always labelled
     * @param methodAnnotations glob filters; when set, only methods with a matching annotation are labelled
     * @param markdown whether headings and fenced blocks are Markdown syntax. Outside Markdown a line such
     * as `# comment` is a shell/Python/YAML comment, not a heading.
     * @param hashComments whether `#` starts a comment (Python, shell, YAML...). When false (C-family,
     * JavaScript, CSS), only `#region`, `#endregion` and `#pragma` directives at the start of a line count,
     * so `#id` selectors and `this.#field` are not comments.
     */
    fun scan(
        text: CharSequence,
        markerWords: String?,
        methods: Boolean,
        maxMarkers: Int,
        methodNames: String? = "*",
        methodAnnotations: String? = "",
        markerRegex: String? = "",
        markdown: Boolean = true,
        hashComments: Boolean = true,
        checkCanceled: (() -> Unit)? = null,
    ): List<Marker> {
        require(maxMarkers >= 0) { "maxMarkers must be nonnegative" }
        val cap = minOf(MAX_MARKERS, maxMarkers)
        if (cap == 0) return emptyList()
        val customMarkers = compileMarkerRegex(markerRegex)
        val names = filters(methodNames ?: "*")
        val annotationFilters = filters(methodAnnotations)
        val annotations = HashSet<String>()
        var annotationLines = 0
        val signature = StringBuilder()
        val signatureOffsets = IntArray(2048)
        var signatureLines = 0
        val words = ArrayList<String>()
        val structural = HashSet<String>()
        markerWords?.take(4096)?.split(",")?.forEach { raw ->
            val word = raw.trim()
            if (word.isEmpty() || words.size >= 128) return@forEach
            // A word configured in capitals (TODO, MARK) is matched case-sensitively: "mark as dirty" is prose.
            val capitals = word == word.uppercase(Locale.ROOT) && word.any { it.isLetter() }
            words += (if (capitals) "(?-i:" else "(?i:") + Pattern.quote(word) + ")"
            val lower = word.lowercase(Locale.ROOT)
            if (lower in STRUCTURAL) structural += lower
        }
        val markers = if (words.isEmpty()) null else Pattern.compile(
            """(?<![\p{L}\p{N}_])(?:""" + words.joinToString("|") + """)(?![\p{L}\p{N}_])""", Pattern.UNICODE_CASE,
        )
        val result = ArrayList<Marker>()
        val limit = minOf(text.length, MAX_INPUT)
        var offset = 0
        var lineNumber = 0
        var blockComment = false
        // Closing marker of the open block comment: */ for C-style, --> for HTML/XML.
        var blockEnd = "*/"
        var fence = 0.toChar()
        var fenceLength = 0
        while (offset < limit && result.size < cap) {
            if ((lineNumber++ and 63) == 0) checkCanceled?.invoke()
            var end = offset
            while (end < limit && text[end] != '\n' && text[end] != '\r') {
                if ((end and 4095) == 0) checkCanceled?.invoke()
                end++
            }
            var boundedEnd = offset + minOf(MAX_LINE, end - offset)
            // Do not split a surrogate pair at the inspection boundary.
            if (boundedEnd > offset && Character.isHighSurrogate(text[boundedEnd - 1])) boundedEnd--
            val line = text.subSequence(offset, boundedEnd).toString()
            val fenceMatch = FENCE.matcher(line)
            if (markdown && fenceMatch.matches() && !blockComment) {
                annotations.clear()
                signature.setLength(0)
                val run = fenceMatch.group(1)
                if (fence.code == 0) {
                    fence = run[0]
                    fenceLength = run.length
                } else if (run[0] == fence && run.length >= fenceLength && fenceMatch.group(2).isBlank()) {
                    fence = 0.toChar()
                }
            } else if (fence.code == 0) {
                val heading = HEADING.matcher(line)
                if (markdown && !blockComment && heading.matches()) {
                    annotations.clear()
                    signature.setLength(0)
                    result += Marker(offset + heading.start(1), heading.group(1), false)
                } else {
                    val code = StringBuilder(line)
                    val comments = StringBuilder(" ".repeat(line.length))
                    val commentChars = BooleanArray(line.length)
                    var quote = 0.toChar()
                    var i = 0
                    while (i < line.length) {
                        val c = line[i]
                        val next = if (i + 1 < line.length) line[i + 1] else 0.toChar()
                        if (blockComment) {
                            code.setCharAt(i, ' ')
                            comments.setCharAt(i, c)
                            commentChars[i] = true
                            if (line.startsWith(blockEnd, i)) {
                                for (k in 1 until blockEnd.length) {
                                    code.setCharAt(++i, ' ')
                                    comments.setCharAt(i, line[i])
                                    commentChars[i] = true
                                }
                                blockComment = false
                            }
                        } else if (quote.code != 0) {
                            code.setCharAt(i, ' ')
                            if (c == '\\' && i + 1 < line.length) code.setCharAt(++i, ' ')
                            else if (c == quote) quote = 0.toChar()
                        } else if (c == '\'' || c == '"' || c == '`') {
                            quote = c
                            code.setCharAt(i, ' ')
                        } else if ((c == '/' && next == '*') || line.startsWith("<!--", i)) {
                            blockComment = true
                            val opener = if (c == '<') "<!--" else "/*"
                            blockEnd = if (c == '<') "-->" else "*/"
                            for (k in opener.indices) {
                                code.setCharAt(i + k, ' ')
                                comments.setCharAt(i + k, line[i + k])
                                commentChars[i + k] = true
                            }
                            i += opener.length - 1
                        } else if ((c == '/' && next == '/') || (c == '#' && (if (hashComments) {
                                i == 0 || line[i - 1].isWhitespace()
                            } else {
                                line.substring(0, i).isBlank() && HASH_DIRECTIVE.matcher(line).find()
                            }))
                        ) {
                            for (j in i until line.length) {
                                code.setCharAt(j, ' ')
                                comments.setCharAt(j, line[j])
                                commentChars[j] = true
                            }
                            break
                        }
                        i++
                    }
                    var source = code.toString()
                    var codeStart = 0
                    val annotation = ANNOTATION.matcher(source)
                    if (annotations.isEmpty()) annotationLines = 0
                    while (annotation.find() && annotation.start() == codeStart) {
                        val name = annotation.group(1)
                        annotations += name
                        annotations += name.substring(name.lastIndexOf('.') + 1)
                        codeStart = annotation.end()
                        annotation.region(codeStart, source.length)
                    }
                    if (++annotationLines > 8) annotations.clear()
                    source = source.substring(codeStart)
                    var continuing = signature.isNotEmpty()
                    if (continuing && (signature.length + source.length + 1 > signatureOffsets.size || ++signatureLines > 8)) {
                        signature.setLength(0)
                        annotations.clear()
                        continuing = false
                    }
                    if (continuing) {
                        signatureOffsets[signature.length] = offset + codeStart
                        signature.append('\n')
                        for (k in source.indices) {
                            signatureOffsets[signature.length] = offset + codeStart + k
                            signature.append(source[k])
                        }
                    }
                    val declaration = DECLARATION.matcher(if (continuing) signature else source)
                    val ends = source.indexOf(';') >= 0 || source.indexOf('{') >= 0 || source.indexOf('}') >= 0
                    if (methods && declaration.find()) {
                        for (group in 1..declaration.groupCount()) {
                            val name = declaration.group(group) ?: continue
                            if (group == 3 || (matches(names, name) &&
                                    (annotationFilters.isEmpty() || annotations.any { matches(annotationFilters, it) }))
                            ) {
                                val start = declaration.start(group)
                                result += Marker(if (continuing) signatureOffsets[start] else offset + codeStart + start, name, true)
                            }
                            break
                        }
                        signature.setLength(0)
                        annotations.clear()
                    } else if (methods && !continuing && JAVA_START.matcher(source).matches() && !ends) {
                        signature.append(source)
                        for (k in source.indices) signatureOffsets[k] = offset + codeStart + k
                        signatureLines = 1
                    } else if (ends) {
                        signature.setLength(0)
                        annotations.clear()
                    } else if (!continuing && source.isNotBlank()) {
                        annotations.clear()
                    }
                    if (markers != null && result.size < cap) {
                        val marker = markers.matcher(comments)
                        while (result.size < cap && marker.find()) {
                            if (marker.group().lowercase(Locale.ROOT) in structural &&
                                !atCommentStart(comments, commentChars, marker.start())
                            ) continue
                            result += Marker(offset + marker.start(), label(line.substring(marker.start())), false)
                        }
                    }
                    if (customMarkers != null && result.size < cap) {
                        val marker = customMarkers.matcher(comments)
                        var k = 0
                        while (k < line.length && result.size < cap) {
                            if (!commentChars[k]) {
                                k++
                                continue
                            }
                            val start = k
                            while (k < line.length && commentChars[k]) k++
                            marker.region(start, k)
                            while (result.size < cap && marker.find()) {
                                if (marker.start() == marker.end() || comments.substring(marker.start(), marker.end()).isBlank()) continue
                                val value = Marker(offset + marker.start(), label(line.substring(marker.start())), false)
                                if (value !in result) result += value
                            }
                        }
                    }
                }
            }
            offset = end
            if (offset < limit && text[offset] == '\r') offset++
            if (offset < limit && text[offset] == '\n') offset++
        }
        return result.toList()
    }

    /** Label text: the rest of the line, without a trailing block-comment closer. */
    private fun label(text: String): String = text.trimEnd().removeSuffix("*/").removeSuffix("-->").trimEnd()

    /** Whether only comment-opener characters (slash, star, hash, bang, dash, angle) and spaces precede [index]. */
    private fun atCommentStart(comments: CharSequence, commentChars: BooleanArray, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && commentChars[i]) {
            val c = comments[i]
            if (!c.isWhitespace() && c !in "/*#!-<") return false
            i--
        }
        return true
    }

    private fun filters(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // Bound configuration work too; persisted values are not necessarily from the form.
        return value.take(4096).split(Regex("[,\\r\\n]"))
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotEmpty() }
            .take(128)
    }

    /** Greedy glob matching avoids regex backtracking for user-provided wildcard filters. */
    private fun matches(filters: List<String>, value: String): Boolean = filters.any { filter ->
        var pattern = 0
        var cursor = 0
        var star = -1
        var retry = 0
        while (cursor < value.length) {
            if (pattern < filter.length && filter[pattern] == '*') {
                star = pattern++
                retry = cursor
            } else if (pattern < filter.length && (filter[pattern] == '?' || filter[pattern] == value[cursor])) {
                pattern++
                cursor++
            } else if (star >= 0) {
                pattern = star + 1
                cursor = ++retry
            } else {
                break
            }
        }
        while (pattern < filter.length && filter[pattern] == '*') pattern++
        cursor == value.length && pattern == filter.length
    }
}
