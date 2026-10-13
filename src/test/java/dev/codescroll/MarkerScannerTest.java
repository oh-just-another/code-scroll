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
}
