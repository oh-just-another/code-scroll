package dev.codescroll;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarkerScannerTest {
    @Test
    void recognizesConservativeDeclarationsAcrossLanguages() {
        String text = "private suspend fun load() {}\n"
                + "def parse(x):\nclass Parser:\nasync def fetch():\n"
                + "export function run() {}\nclass Widget {}\n"
                + "pub fn build() {}\nstruct State {}\nenum Mode {}\n"
                + "func (s *Server) Serve() {}\nfunc main() {}\n"
                + "public static String name() {\nprivate async Task Work() {\n";
        List<MarkerScanner.Marker> markers = MarkerScanner.scan(text, "TODO", true, 100, null);
        assertEquals(List.of("load", "parse", "Parser", "fetch", "run", "Widget", "build", "State", "Mode", "Serve", "main", "name", "Work"),
                markers.stream().map(MarkerScanner.Marker::label).toList());
        for (MarkerScanner.Marker marker : markers) {
            assertTrue(marker.declaration());
            assertEquals(marker.label(), text.substring(marker.offset(), marker.offset() + marker.label().length()));
        }
    }

    @Test
    void callsControlsStringsAndCommentsAreNotDeclarations() {
        String text = "load();\nservice.run();\nif (ready) {\nreturn make();\n"
                + "Thing create() {\npublic invoke() {\n"
                + "// private suspend fun fake() {}\n/*\nclass Fake {}\n*/\n"
                + "String s = \"function fake() {} // TODO\";\n";
        assertTrue(MarkerScanner.scan(text, "TODO", true, 100, null).isEmpty());
    }

    @Test
    void commentsUseLiteralConfigurableWordsAndBoundaries() {
        String text = "// TODO: fix\r\n# FIXME later\r\n/* MARK section */\n#region Useful\n"
                + "// TODOLATER MYTODO TODO_suffix\nString x = \"TODO\";\n// A+B literal\n";
        List<MarkerScanner.Marker> markers = MarkerScanner.scan(text, " TODO, FIXME,MARK,region,A+B ", false, 100, null);
        assertEquals(5, markers.size());
        assertEquals(List.of("TODO: fix", "FIXME later", "MARK section */", "region Useful", "A+B literal"),
                markers.stream().map(MarkerScanner.Marker::label).toList());
        assertTrue(markers.stream().noneMatch(MarkerScanner.Marker::declaration));
        assertEquals(text.indexOf("FIXME"), markers.get(1).offset());
        assertTrue(MarkerScanner.scan("// anything", ".*", false, 100, null).isEmpty());
    }

    @Test
    void markdownHeadingsAreOutsideMatchingFencesOnly() {
        String text = "# Title\n```java\n# Hidden\nclass Hidden {}\n// TODO hidden\n~~~\n```\n"
                + "## Section ##\n~~~~python\n# Hidden again\n~~~\n# Still hidden\n~~~~\n"
                + "class Visible {}\n";
        List<MarkerScanner.Marker> markers = MarkerScanner.scan(text, "TODO", true, 100, null);
        assertEquals(List.of("Title", "Section", "Visible"), markers.stream().map(MarkerScanner.Marker::label).toList());
        assertFalse(markers.get(0).declaration());
        assertTrue(markers.get(2).declaration());
    }

    @Test
    void methodSwitchEmptyInputZeroCapAndImmutableResults() {
        assertTrue(MarkerScanner.scan("", "TODO", true, 100, null).isEmpty());
        assertTrue(MarkerScanner.scan("class X {}", "TODO", false, 100, null).isEmpty());
        assertTrue(MarkerScanner.scan("// TODO", "TODO", true, 0, () -> fail("zero cap should do no work")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MarkerScanner.scan("", "TODO", true, -1, null));
        List<MarkerScanner.Marker> markers = MarkerScanner.scan("// TODO", "TODO", false, 10, null);
        assertThrows(UnsupportedOperationException.class, () -> markers.clear());
    }

    @Test
    void inputLineAndMarkerCountsAreBounded() {
        assertTrue(MarkerScanner.scan(" ".repeat(512) + "// TODO\n", "TODO", false, 10, null).isEmpty());
        String text = "x".repeat(1024 * 1024) + "\n// TODO";
        assertTrue(MarkerScanner.scan(text, "TODO", false, 10, null).isEmpty());
        String many = "// TODO\n".repeat(3000);
        assertEquals(2000, MarkerScanner.scan(many, "TODO", false, Integer.MAX_VALUE, null).size());
        assertEquals(3, MarkerScanner.scan(many, "TODO", false, 3, null).size());
        CharSequence huge = new CharSequence() {
            public int length() { return Integer.MAX_VALUE; }
            public char charAt(int index) {
                assertTrue(index < 1024 * 1024);
                return 'x';
            }
            public CharSequence subSequence(int start, int end) {
                assertTrue(end - start <= 512);
                return "x".repeat(end - start);
            }
        };
        assertTrue(MarkerScanner.scan(huge, "TODO", true, 100, null).isEmpty());
    }

    @Test
    void crlfUnicodeAndBareCarriageReturnsKeepExactOffsets() {
        String name = "\u0437\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u044c";
        String heading = "R\u00e9sum\u00e9";
        String text = "// \ud83d\ude00\r\nprivate suspend fun " + name + "() {}\r// TODO: \ud83d\ude00\r\n# " + heading + "\n";
        List<MarkerScanner.Marker> markers = MarkerScanner.scan(text, "TODO", true, 100, null);
        assertEquals(List.of(name, "TODO: \ud83d\ude00", heading), markers.stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(text.indexOf(name), markers.get(0).offset());
        assertEquals(text.indexOf("TODO"), markers.get(1).offset());
        assertEquals(text.indexOf(heading), markers.get(2).offset());
    }

    @Test
    void cancellationIsCheckedEverySixtyFourLinesAndPropagated() {
        AtomicInteger checks = new AtomicInteger();
        RuntimeException canceled = new RuntimeException("canceled");
        assertSame(canceled, assertThrows(RuntimeException.class, () -> MarkerScanner.scan("\n".repeat(130), "TODO", true, 10, () -> {
            if (checks.incrementAndGet() == 3) throw canceled;
        })));
        assertEquals(3, checks.get());
    }

    @Test
    void methodNameFiltersAreGlobsSeparatedByCommasOrNewlinesAndDoNotFilterClasses() {
        String text = "class Service {}\nfun loadOne() {}\nfun loadTwo() {}\n"
                + "fun parse() {}\nfun saveA() {}\nfun saveAB() {}\n";
        assertEquals(List.of("Service", "loadOne", "loadTwo", "parse", "saveA"),
                MarkerScanner.scan(text, "", true, "load*,parse\nsave?", "", "", 100, null)
                        .stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(List.of("Service"), MarkerScanner.scan(text, "", true, "", "", "", 100, null)
                .stream().map(MarkerScanner.Marker::label).toList());
        assertTrue(MarkerScanner.scan(text, "", false, "*", "", "", 100, null).isEmpty());
        assertEquals(MarkerScanner.scan(text, "", true, 100, null),
                MarkerScanner.scan(text, "", true, null, null, null, 100, null));
    }

    @Test
    void annotationFiltersTrackPrecedingAndInlineJavaAndKotlinAnnotations() {
        String text = "@org.example.Test\n// a comment\npublic void loadTest() {}\n"
                + "public void loadPlain() {}\n@JvmStatic @Tag(\"fast\") fun loadKotlin() {}\n"
                + "@Ignored\nprivate int field = 1;\nfun loadAfterField() {}\nclass Service {}\n";
        assertEquals(List.of("loadTest", "loadKotlin", "Service"),
                MarkerScanner.scan(text, "", true, "load*", "@Test\nJvm*,Other", "", 100, null)
                        .stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(List.of("loadTest", "Service"),
                MarkerScanner.scan(text, "", true, "*", "org.example.Test", "", 100, null)
                        .stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(List.of("Service"),
                MarkerScanner.scan(text, "", true, "missing", "Test", "", 100, null)
                        .stream().map(MarkerScanner.Marker::label).toList());
        assertTrue(MarkerScanner.scan("@Test\n" + "\n".repeat(9) + "fun stale() {}", "", true,
                "*", "Test", "", 100, null).isEmpty());
    }

    @Test
    void kotlinExtensionsAndGenericsAndMultilineJavaSignaturesKeepExactOffsets() {
        String text = "fun String.formatValue() = this\r\n"
                + "inline fun <T, R> List<T>.mapValues(action: (T) -> R) {}\n"
                + "fun <K, V> Map<K, V>.copyEntries() {}\nfun String?.safeValue() {}\n"
                + "@Test\r\npublic static <T> java.util.List<T>\r\n"
                + "  collect(\r\n    T first,\r\n    T second\r\n  ) throws Exception\r\n  {\n}\n";
        List<MarkerScanner.Marker> markers = MarkerScanner.scan(text, "", true, "*", "", "", 100, null);
        assertEquals(List.of("formatValue", "mapValues", "copyEntries", "safeValue", "collect"),
                markers.stream().map(MarkerScanner.Marker::label).toList());
        for (MarkerScanner.Marker marker : markers) {
            assertEquals(text.indexOf(marker.label()), marker.offset());
            assertTrue(marker.declaration());
        }
        assertEquals(List.of("collect"), MarkerScanner.scan(text, "", true,
                "collect", "Test", "", 100, null).stream().map(MarkerScanner.Marker::label).toList());
        assertTrue(MarkerScanner.scan("public void tooLong(\n" + "    int x,\n".repeat(10) + ") {", "", true,
                "*", "", "", 100, null).isEmpty());
    }

    @Test
    void customRegexMatchesOnlyCommentsAndDeduplicatesLiteralMarkers() {
        String text = "String note = \"NOTE-12\"; NOTE_34(); // NOTE-56: fix\n"
                + "/* NOTE-78 section */\n#NOTE-90 later\n"
                + "```\n// NOTE-99 hidden\n```\n";
        List<MarkerScanner.Marker> markers = MarkerScanner.scan(text, "", false,
                "*", "", "NOTE-[0-9]+", 100, null);
        assertEquals(List.of("NOTE-56: fix", "NOTE-78 section */", "NOTE-90 later"),
                markers.stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(text.indexOf("NOTE-56"), markers.get(0).offset());
        assertEquals(1, MarkerScanner.scan("// TODO fix", "TODO", false,
                "*", "", "TODO", 100, null).size());
        assertTrue(MarkerScanner.scan("String a = \"NOTE\";", "", false,
                "*", "", ".+", 100, null).isEmpty());
        assertEquals("// NOTE", MarkerScanner.scan("call(); // NOTE", "", false,
                "*", "", "^// NOTE$", 100, null).get(0).label());
        assertTrue(MarkerScanner.scan("// anything", "", false, "*", "", "^", 100, null).isEmpty());
        assertEquals(2, MarkerScanner.scan("// NOTE\n".repeat(4), "", false,
                "*", "", "NOTE", 2, null).size());
    }

    @Test
    void customRegexRejectsUnsafeAndUnsupportedConstructsBeforeScanning() {
        for (String expression : List.of("(a+)+$", "a+a+$", "(?=TODO)", "(TODO)", "TODO|FIXME",
                "\\1", "\\k<name>", "a{513}", "[", "a".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> MarkerScanner.compileMarkerRegex(expression), expression);
            assertThrows(IllegalArgumentException.class, () -> MarkerScanner.scan("// anything", "", false,
                    "*", "", expression, 100, null), expression);
        }
        for (String expression : List.of("NOTE-[0-9]+", "TODO:.*", "\\bTODO\\b", "a{1,512}",
                "a*?", "a++", "[()|]+", "\\(TODO\\)")) {
            assertNotNull(MarkerScanner.compileMarkerRegex(expression), expression);
        }
        assertNull(MarkerScanner.compileMarkerRegex(""));
        assertNull(MarkerScanner.compileMarkerRegex(null));
    }

    @Test
    void cancellationDuringVeryLongLinesPropagatesAndRegexInspectionIsBounded() {
        AtomicInteger checks = new AtomicInteger();
        RuntimeException canceled = new RuntimeException("canceled");
        assertSame(canceled, assertThrows(RuntimeException.class, () -> MarkerScanner.scan(
                "// " + "x".repeat(100_000), "", false, "*", "", "x+$", 10, () -> {
                    if (checks.incrementAndGet() == 4) throw canceled;
                })));
        assertEquals(4, checks.get());
        assertTrue(MarkerScanner.scan("// " + "x".repeat(512) + " NOTE", "", false,
                "*", "", "NOTE", 100, null).isEmpty());
        assertEquals(1, MarkerScanner.scan("// " + "x".repeat(511), "", false,
                "*", "", "x+$", 100, null).size());
    }

    @Test
    void scansMarkersNearTheEndOfSmallFilesAndKeepsWildcardWorkBounded() {
        String text = "int value = 1;\n".repeat(50_000) + "\n// NOTE near end\nfun finalMethod() {}\n";
        assertEquals(List.of("NOTE near end", "finalMethod"), MarkerScanner.scan(text, "", true,
                "final*", "", "NOTE", 100, null).stream().map(MarkerScanner.Marker::label).toList());
        assertTrue(MarkerScanner.scan("fun " + "a".repeat(100) + "() {}", "", true,
                "*a".repeat(100) + "b", "", "", 100, null).isEmpty());
    }

    @Test
    void hashCommentsAndFencesAreOnlyMarkdownSyntaxInMarkdownFiles() {
        String text = "# compute totals\n```\ndef run():\n    pass  # TODO tidy\n";
        assertEquals(List.of("run", "TODO tidy"), MarkerScanner.scan(text, "TODO", true, "*", "", "",
                false, 100, null).stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(List.of("compute totals"), MarkerScanner.scan(text, "TODO", true, "*", "", "",
                true, 100, null).stream().map(MarkerScanner.Marker::label).toList());
    }

    @Test
    void sectionMarkersOnlyCountAtTheStartOfAComment() {
        String text = "/** Sets the region for this client */\n// mark as dirty\n// MARK: - Section\n"
                + "// region Helpers\n  * TODO inside javadoc\n";
        assertEquals(List.of("MARK: - Section", "region Helpers"), MarkerScanner.scan(text, "MARK,region", false,
                "", "", "", false, 100, null).stream().map(MarkerScanner.Marker::label).toList());
        // Ordinary markers still match anywhere in a comment, but capitals are case-sensitive.
        assertEquals(List.of("TODO later"), MarkerScanner.scan("// fix it, TODO later\n// todo lowercase\n", "TODO",
                false, "", "", "", false, 100, null).stream().map(MarkerScanner.Marker::label).toList());
    }

    @Test
    void hashIsACommentOnlyWhereTheLanguageUsesIt() {
        String css = "#main-region { color: red; }\n.a { } # TODO not css\n";
        String js = "class A { #mark = 1; run() { this.#mark(); } }\n#region Helpers\n";
        assertEquals(List.of("region Helpers"), MarkerScanner.scan(css + js, "TODO,MARK,region", false, "", "", "",
                false, false, 100, null).stream().map(MarkerScanner.Marker::label).toList());
        assertEquals(List.of("TODO tidy"), MarkerScanner.scan("x = this.#a  # TODO tidy\n", "TODO", false, "", "", "",
                false, true, 100, null).stream().map(MarkerScanner.Marker::label).toList());
    }
}
