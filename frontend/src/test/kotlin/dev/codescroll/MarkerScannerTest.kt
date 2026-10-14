package dev.codescroll

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarkerScannerTest {
    private fun List<MarkerScanner.Marker>.labels() = map { it.label }

    @Test
    fun recognizesConservativeDeclarationsAcrossLanguages() {
        val text = "private suspend fun load() {}\n" +
            "def parse(x):\nclass Parser:\nasync def fetch():\n" +
            "export function run() {}\nclass Widget {}\n" +
            "pub fn build() {}\nstruct State {}\nenum Mode {}\n" +
            "func (s *Server) Serve() {}\nfunc main() {}\n" +
            "public static String name() {\nprivate async Task Work() {\n"
        val markers = MarkerScanner.scan(text, "TODO", true, 100)
        assertEquals(
            listOf("load", "parse", "Parser", "fetch", "run", "Widget", "build", "State", "Mode", "Serve", "main", "name", "Work"),
            markers.labels(),
        )
        for (marker in markers) {
            assertTrue(marker.declaration)
            assertEquals(marker.label, text.substring(marker.offset, marker.offset + marker.label.length))
        }
    }

    @Test
    fun callsControlsStringsAndCommentsAreNotDeclarations() {
        val text = "load();\nservice.run();\nif (ready) {\nreturn make();\n" +
            "Thing create() {\npublic invoke() {\n" +
            "// private suspend fun fake() {}\n/*\nclass Fake {}\n*/\n" +
            "String s = \"function fake() {} // TODO\";\n"
        assertTrue(MarkerScanner.scan(text, "TODO", true, 100).isEmpty())
    }

    @Test
    fun commentsUseLiteralConfigurableWordsAndBoundaries() {
        val text = "// TODO: fix\r\n# FIXME later\r\n/* MARK section */\n#region Useful\n" +
            "// TODOLATER MYTODO TODO_suffix\nString x = \"TODO\";\n// A+B literal\n"
        val markers = MarkerScanner.scan(text, " TODO, FIXME,MARK,region,A+B ", false, 100)
        assertEquals(listOf("TODO: fix", "FIXME later", "MARK section", "region Useful", "A+B literal"), markers.labels())
        assertTrue(markers.none { it.declaration })
        assertEquals(text.indexOf("FIXME"), markers[1].offset)
        assertTrue(MarkerScanner.scan("// anything", ".*", false, 100).isEmpty())
    }

    @Test
    fun markdownHeadingsAreOutsideMatchingFencesOnly() {
        val text = "# Title\n```java\n# Hidden\nclass Hidden {}\n// TODO hidden\n~~~\n```\n" +
            "## Section ##\n~~~~python\n# Hidden again\n~~~\n# Still hidden\n~~~~\n" +
            "class Visible {}\n"
        val markers = MarkerScanner.scan(text, "TODO", true, 100)
        assertEquals(listOf("Title", "Section", "Visible"), markers.labels())
        assertFalse(markers[0].declaration)
        assertTrue(markers[2].declaration)
    }

    @Test
    fun methodSwitchEmptyInputAndZeroCap() {
        assertTrue(MarkerScanner.scan("", "TODO", true, 100).isEmpty())
        assertTrue(MarkerScanner.scan("class X {}", "TODO", false, 100).isEmpty())
        assertTrue(MarkerScanner.scan("// TODO", "TODO", true, 0, checkCanceled = { fail("zero cap should do no work") }).isEmpty())
        assertThrows<IllegalArgumentException> { MarkerScanner.scan("", "TODO", true, -1) }
    }

    @Test
    fun inputLineAndMarkerCountsAreBounded() {
        assertTrue(MarkerScanner.scan(" ".repeat(512) + "// TODO\n", "TODO", false, 10).isEmpty())
        assertTrue(MarkerScanner.scan("x".repeat(1024 * 1024) + "\n// TODO", "TODO", false, 10).isEmpty())
        val many = "// TODO\n".repeat(3000)
        assertEquals(2000, MarkerScanner.scan(many, "TODO", false, Int.MAX_VALUE).size)
        assertEquals(3, MarkerScanner.scan(many, "TODO", false, 3).size)
        val huge = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char {
                assertTrue(index < 1024 * 1024)
                return 'x'
            }

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                assertTrue(endIndex - startIndex <= 512)
                return "x".repeat(endIndex - startIndex)
            }
        }
        assertTrue(MarkerScanner.scan(huge, "TODO", true, 100).isEmpty())
    }

    @Test
    fun crlfUnicodeAndBareCarriageReturnsKeepExactOffsets() {
        val name = "загрузить"
        val heading = "Résumé"
        val text = "// 😀\r\nprivate suspend fun $name() {}\r// TODO: 😀\r\n# $heading\n"
        val markers = MarkerScanner.scan(text, "TODO", true, 100)
        assertEquals(listOf(name, "TODO: 😀", heading), markers.labels())
        assertEquals(text.indexOf(name), markers[0].offset)
        assertEquals(text.indexOf("TODO"), markers[1].offset)
        assertEquals(text.indexOf(heading), markers[2].offset)
    }

    @Test
    fun cancellationIsCheckedEverySixtyFourLinesAndPropagated() {
        var checks = 0
        val canceled = RuntimeException("canceled")
        val thrown = assertThrows<RuntimeException> {
            MarkerScanner.scan("\n".repeat(130), "TODO", true, 10, checkCanceled = { if (++checks == 3) throw canceled })
        }
        assertSame(canceled, thrown)
        assertEquals(3, checks)
    }

    @Test
    fun methodNameFiltersAreGlobsSeparatedByCommasOrNewlinesAndDoNotFilterClasses() {
        val text = "class Service {}\nfun loadOne() {}\nfun loadTwo() {}\n" +
            "fun parse() {}\nfun saveA() {}\nfun saveAB() {}\n"
        assertEquals(listOf("Service", "loadOne", "loadTwo", "parse", "saveA"),
            MarkerScanner.scan(text, "", true, 100, methodNames = "load*,parse\nsave?").labels())
        assertEquals(listOf("Service"), MarkerScanner.scan(text, "", true, 100, methodNames = "").labels())
        assertTrue(MarkerScanner.scan(text, "", false, 100).isEmpty())
        assertEquals(MarkerScanner.scan(text, "", true, 100),
            MarkerScanner.scan(text, "", true, 100, methodNames = null, methodAnnotations = null, markerRegex = null))
    }

    @Test
    fun annotationFiltersTrackPrecedingAndInlineJavaAndKotlinAnnotations() {
        val text = "@org.example.Test\n// a comment\npublic void loadTest() {}\n" +
            "public void loadPlain() {}\n@JvmStatic @Tag(\"fast\") fun loadKotlin() {}\n" +
            "@Ignored\nprivate int field = 1;\nfun loadAfterField() {}\nclass Service {}\n"
        assertEquals(listOf("loadTest", "loadKotlin", "Service"),
            MarkerScanner.scan(text, "", true, 100, methodNames = "load*", methodAnnotations = "@Test\nJvm*,Other").labels())
        assertEquals(listOf("loadTest", "Service"),
            MarkerScanner.scan(text, "", true, 100, methodAnnotations = "org.example.Test").labels())
        assertEquals(listOf("Service"),
            MarkerScanner.scan(text, "", true, 100, methodNames = "missing", methodAnnotations = "Test").labels())
        assertTrue(MarkerScanner.scan("@Test\n" + "\n".repeat(9) + "fun stale() {}", "", true, 100, methodAnnotations = "Test").isEmpty())
    }

    @Test
    fun kotlinExtensionsAndGenericsAndMultilineJavaSignaturesKeepExactOffsets() {
        val text = "fun String.formatValue() = this\r\n" +
            "inline fun <T, R> List<T>.mapValues(action: (T) -> R) {}\n" +
            "fun <K, V> Map<K, V>.copyEntries() {}\nfun String?.safeValue() {}\n" +
            "@Test\r\npublic static <T> java.util.List<T>\r\n" +
            "  collect(\r\n    T first,\r\n    T second\r\n  ) throws Exception\r\n  {\n}\n"
        val markers = MarkerScanner.scan(text, "", true, 100)
        assertEquals(listOf("formatValue", "mapValues", "copyEntries", "safeValue", "collect"), markers.labels())
        for (marker in markers) {
            assertEquals(text.indexOf(marker.label), marker.offset)
            assertTrue(marker.declaration)
        }
        assertEquals(listOf("collect"), MarkerScanner.scan(text, "", true, 100, methodNames = "collect", methodAnnotations = "Test").labels())
        assertTrue(MarkerScanner.scan("public void tooLong(\n" + "    int x,\n".repeat(10) + ") {", "", true, 100).isEmpty())
    }

    @Test
    fun customRegexMatchesOnlyCommentsAndDeduplicatesLiteralMarkers() {
        val text = "String note = \"NOTE-12\"; NOTE_34(); // NOTE-56: fix\n" +
            "/* NOTE-78 section */\n#NOTE-90 later\n" +
            "```\n// NOTE-99 hidden\n```\n"
        val markers = MarkerScanner.scan(text, "", false, 100, markerRegex = "NOTE-[0-9]+")
        assertEquals(listOf("NOTE-56: fix", "NOTE-78 section", "NOTE-90 later"), markers.labels())
        assertEquals(text.indexOf("NOTE-56"), markers[0].offset)
        assertEquals(1, MarkerScanner.scan("// TODO fix", "TODO", false, 100, markerRegex = "TODO").size)
        assertTrue(MarkerScanner.scan("String a = \"NOTE\";", "", false, 100, markerRegex = ".+").isEmpty())
        assertEquals("// NOTE", MarkerScanner.scan("call(); // NOTE", "", false, 100, markerRegex = "^// NOTE$")[0].label)
        assertTrue(MarkerScanner.scan("// anything", "", false, 100, markerRegex = "^").isEmpty())
        assertEquals(2, MarkerScanner.scan("// NOTE\n".repeat(4), "", false, 2, markerRegex = "NOTE").size)
    }

    @Test
    fun customRegexRejectsUnsafeAndUnsupportedConstructsBeforeScanning() {
        for (expression in listOf("(a+)+$", "a+a+$", "(?=TODO)", "(TODO)", "TODO|FIXME", "\\1", "\\k<name>", "a{513}", "[", "a".repeat(257))) {
            assertThrows<IllegalArgumentException>(expression) { MarkerScanner.compileMarkerRegex(expression) }
            assertThrows<IllegalArgumentException>(expression) { MarkerScanner.scan("// anything", "", false, 100, markerRegex = expression) }
        }
        for (expression in listOf("NOTE-[0-9]+", "TODO:.*", "\\bTODO\\b", "a{1,512}", "a*?", "a++", "[()|]+", "\\(TODO\\)")) {
            assertNotNull(MarkerScanner.compileMarkerRegex(expression), expression)
        }
        assertNull(MarkerScanner.compileMarkerRegex(""))
        assertNull(MarkerScanner.compileMarkerRegex(null))
    }

    @Test
    fun cancellationDuringVeryLongLinesPropagatesAndRegexInspectionIsBounded() {
        var checks = 0
        val canceled = RuntimeException("canceled")
        val thrown = assertThrows<RuntimeException> {
            MarkerScanner.scan("// " + "x".repeat(100_000), "", false, 10, markerRegex = "x+$",
                checkCanceled = { if (++checks == 4) throw canceled })
        }
        assertSame(canceled, thrown)
        assertEquals(4, checks)
        assertTrue(MarkerScanner.scan("// " + "x".repeat(512) + " NOTE", "", false, 100, markerRegex = "NOTE").isEmpty())
        assertEquals(1, MarkerScanner.scan("// " + "x".repeat(511), "", false, 100, markerRegex = "x+$").size)
    }

    @Test
    fun scansMarkersNearTheEndOfSmallFilesAndKeepsWildcardWorkBounded() {
        val text = "int value = 1;\n".repeat(50_000) + "\n// NOTE near end\nfun finalMethod() {}\n"
        assertEquals(listOf("NOTE near end", "finalMethod"),
            MarkerScanner.scan(text, "", true, 100, methodNames = "final*", markerRegex = "NOTE").labels())
        assertTrue(MarkerScanner.scan("fun " + "a".repeat(100) + "() {}", "", true, 100, methodNames = "*a".repeat(100) + "b").isEmpty())
    }

    @Test
    fun hashCommentsAndFencesAreOnlyMarkdownSyntaxInMarkdownFiles() {
        val text = "# compute totals\n```\ndef run():\n    pass  # TODO tidy\n"
        assertEquals(listOf("run", "TODO tidy"), MarkerScanner.scan(text, "TODO", true, 100, markdown = false).labels())
        assertEquals(listOf("compute totals"), MarkerScanner.scan(text, "TODO", true, 100, markdown = true).labels())
    }

    @Test
    fun sectionMarkersOnlyCountAtTheStartOfAComment() {
        val text = "/** Sets the region for this client */\n// mark as dirty\n// MARK: - Section\n" +
            "// region Helpers\n  * TODO inside javadoc\n"
        assertEquals(listOf("MARK: - Section", "region Helpers"),
            MarkerScanner.scan(text, "MARK,region", false, 100, methodNames = "", markdown = false).labels())
        // Ordinary markers still match anywhere in a comment, but capitals are case-sensitive.
        assertEquals(listOf("TODO later"),
            MarkerScanner.scan("// fix it, TODO later\n// todo lowercase\n", "TODO", false, 100, markdown = false).labels())
    }

    @Test
    fun hashIsACommentOnlyWhereTheLanguageUsesIt() {
        val css = "#main-region { color: red; }\n.a { } # TODO not css\n"
        val js = "class A { #mark = 1; run() { this.#mark(); } }\n#region Helpers\n"
        assertEquals(listOf("region Helpers"),
            MarkerScanner.scan(css + js, "TODO,MARK,region", false, 100, markdown = false, hashComments = false).labels())
        assertEquals(listOf("TODO tidy"),
            MarkerScanner.scan("x = this.#a  # TODO tidy\n", "TODO", false, 100, markdown = false, hashComments = true).labels())
    }

    @Test
    fun htmlAndXmlCommentsCarryMarkers() {
        val text = "<div>\n  <!-- TODO: fix layout -->\n  <p>TODO in text</p>\n<!--\n  MARK: footer\n-->\n<!-- region Nav --><nav/>\n"
        assertEquals(listOf("TODO: fix layout", "MARK: footer", "region Nav --><nav/>"),
            MarkerScanner.scan(text, "TODO,MARK,region", false, 100, markdown = false, hashComments = false).labels())
    }
}
