import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone JDK-only generator; all generated files stay in the ignored build directory. */
public class GenerateFixtures {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "build/fixtures" : args[0]);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("empty.txt"), "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("short.kt"), """
                // MARK: navigation
                class Sample {
                    // TODO: inspect the minimap
                    private suspend fun myMethod(param: String) {
                        println(param)
                    }
                    fun anotherMethod() = println("<html>&preview</html>")
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("preview.md"), """
                # Document
                ## First section
                Some text and a <tag> with & symbols.
                ```kotlin
                fun insideFence() {}
                ```
                ## Second section
                Last paragraph.
                """, StandardCharsets.UTF_8);
        try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve("large.yaml"))) {
            for (int i = 0; i < 10_000; i++) writer.write("key" + i + ": value" + i + "\n");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve("large.php"))) {
            writer.write("<?php\n");
            for (int i = 1; i < 26_000; i++) writer.write("$value" + i + " = " + i + ";\n");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(directory.resolve("million.txt"))) {
            for (int i = 0; i < 999_999; i++) writer.write("line " + i + "\n");
        }
        Files.writeString(directory.resolve("long-line.json"), "{\"value\":\"" + "x".repeat(2_000_000) + "\"}");
        System.out.println("Fixtures generated in " + directory.toAbsolutePath());
    }
}
