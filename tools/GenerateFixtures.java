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
        Files.writeString(directory.resolve("medium.kt"), kotlin(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("medium.ts"), typescript(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("medium.py"), python(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("medium.html"), html(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("guide.md"), markdown("Code Scroll Guide", 21), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("notes.md"), markdown("Release Notes", 20), StandardCharsets.UTF_8);
        System.out.println("Fixtures generated in " + directory.toAbsolutePath());
    }

    private static final String[] NAMES = {"Order", "Invoice", "Customer", "Shipment", "Payment", "Report",
            "Session", "Catalog", "Inventory", "Ticket", "Account", "Schedule", "Archive", "Message"};
    private static final String[] WORDS = ("the minimap shows each line of the document as a thin stroke so that "
            + "structure indentation and density stay visible while the editor scrolls through long files and "
            + "markers highlight todo items declarations and headings").split(" ");

    /** Deterministic prose of varying length, so paragraphs and comments differ in width. */
    private static String words(int seed, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(i == 0 ? "" : " ").append(WORDS[(seed * 7 + i * 3) % WORDS.length]);
        return text.toString();
    }

    /** About 400 lines: classes with properties, nested control flow, comments and markers. */
    private static String kotlin() {
        StringBuilder out = new StringBuilder("package dev.codescroll.fixtures\n\nimport kotlin.math.max\nimport kotlin.math.min\n\n");
        for (int c = 0; c < NAMES.length; c++) {
            String name = NAMES[c];
            out.append("// MARK: ").append(name).append("\n");
            out.append("/** ").append(words(c, 9 + c % 5)).append(" */\n");
            out.append("data class ").append(name).append("(val id: Long, val title: String, val amount: Int)\n\n");
            out.append("class ").append(name).append("Service(private val limit: Int = ").append(10 + c).append(") {\n");
            out.append("    private val cache = mutableMapOf<Long, ").append(name).append(">()\n\n");
            if (c % 3 == 0) out.append("    // TODO: ").append(words(c + 1, 6)).append("\n");
            out.append("    fun load(id: Long): ").append(name).append("? = cache[id]\n\n");
            out.append("    suspend fun refresh(items: List<").append(name).append(">) {\n");
            out.append("        for (item in items) {\n");
            out.append("            if (item.amount > limit) {\n");
            out.append("                cache[item.id] = item.copy(amount = min(item.amount, limit * 2))\n");
            out.append("            } else {\n");
            out.append("                cache.remove(item.id)\n");
            out.append("            }\n        }\n    }\n\n");
            out.append("    fun summary(): String {\n");
            out.append("        val total = cache.values.sumOf { it.amount }\n");
            out.append("        val largest = cache.values.maxOfOrNull { it.amount } ?: 0\n");
            out.append("        return \"").append(name).append(": ${cache.size} items, total $total, max ${max(largest, 0)}\"\n");
            out.append("    }\n");
            if (c % 2 == 1) {
                out.append("\n    private fun validate(item: ").append(name).append("): Boolean =\n");
                out.append("        item.title.isNotBlank() && item.amount in 0..limit * ").append(c + 2).append("\n");
            }
            out.append("}\n\n");
        }
        return out.toString();
    }

    private static String typescript() {
        StringBuilder out = new StringBuilder("// region imports\nimport { EventEmitter } from \"events\";\n// endregion\n\n");
        for (int c = 0; c < NAMES.length; c++) {
            String name = NAMES[c];
            out.append("export interface ").append(name).append("Dto {\n  id: string;\n  label: string;\n  tags: string[];\n}\n\n");
            out.append("/** ").append(words(c + 3, 7 + c % 6)).append(" */\n");
            out.append("export class ").append(name).append("Store extends EventEmitter {\n");
            out.append("  private readonly items = new Map<string, ").append(name).append("Dto>();\n\n");
            out.append("  async fetchAll(endpoint: string): Promise<").append(name).append("Dto[]> {\n");
            out.append("    const response = await fetch(`${endpoint}/").append(name.toLowerCase()).append("s`);\n");
            out.append("    if (!response.ok) {\n      throw new Error(`Failed: ${response.status}`);\n    }\n");
            out.append("    const data = (await response.json()) as ").append(name).append("Dto[];\n");
            out.append("    data.forEach((item) => this.items.set(item.id, item));\n");
            out.append("    this.emit(\"loaded\", data.length);\n    return data;\n  }\n\n");
            if (c % 4 == 1) out.append("  // FIXME: ").append(words(c, 5)).append("\n");
            out.append("  byTag(tag: string): ").append(name).append("Dto[] {\n");
            out.append("    return [...this.items.values()].filter((item) => item.tags.includes(tag));\n  }\n}\n\n");
        }
        return out.toString();
    }

    private static String python() {
        StringBuilder out = new StringBuilder("# Hash comments are not Markdown headings outside .md files.\nimport dataclasses\n\n\n");
        for (int c = 0; c < NAMES.length * 2; c++) {
            String name = NAMES[c % NAMES.length] + (c < NAMES.length ? "" : "Draft");
            out.append("@dataclasses.dataclass\nclass ").append(name).append(":\n");
            out.append("    \"\"\"").append(words(c + 5, 8)).append(".\"\"\"\n\n");
            out.append("    id: int\n    title: str\n    amount: int = 0\n\n");
            out.append("    def scaled(self, factor: float) -> \"").append(name).append("\":\n");
            out.append("        # TODO: ").append(words(c, 4)).append("\n");
            out.append("        if factor <= 0:\n            raise ValueError(\"factor must be positive\")\n");
            out.append("        return dataclasses.replace(self, amount=int(self.amount * factor))\n\n\n");
        }
        return out.toString();
    }

    /** About 400 lines of nested HTML with inline CSS and JavaScript; WebStorm shows sticky lines for it. */
    private static String html() {
        StringBuilder out = new StringBuilder("<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n"
                + "  <title>Code Scroll fixture</title>\n  <style>\n");
        for (int c = 0; c < 6; c++) {
            out.append("    .").append(NAMES[c].toLowerCase()).append("-card {\n      display: grid;\n")
                    .append("      gap: ").append(4 + c).append("px;\n      padding: 8px 12px;\n    }\n");
        }
        out.append("  </style>\n</head>\n<body>\n  <!-- TODO: ").append(words(2, 6)).append(" -->\n");
        out.append("  <header class=\"site-header\">\n    <nav>\n      <ul>\n");
        for (String name : NAMES) out.append("        <li><a href=\"#").append(name.toLowerCase()).append("\">").append(name).append("</a></li>\n");
        out.append("      </ul>\n    </nav>\n  </header>\n  <main>\n");
        for (int c = 0; c < 12; c++) {
            String name = NAMES[c];
            out.append("    <section id=\"").append(name.toLowerCase()).append("\" class=\"").append(name.toLowerCase()).append("-card\">\n");
            out.append("      <h2>").append(name).append(" overview</h2>\n");
            out.append("      <p>").append(words(c, 12 + c % 4 * 5)).append(".</p>\n");
            out.append("      <div class=\"details\">\n        <table>\n          <thead>\n            <tr><th>Field</th><th>Value</th></tr>\n          </thead>\n          <tbody>\n");
            for (int r = 0; r < 3 + c % 3; r++) {
                out.append("            <tr><td>").append(words(c + r, 2)).append("</td><td>").append(r * 17 + c).append("</td></tr>\n");
            }
            out.append("          </tbody>\n        </table>\n");
            if (c % 3 == 0) {
                out.append("        <form class=\"filter\">\n          <label>Search <input type=\"search\" name=\"q\"></label>\n")
                        .append("          <button type=\"submit\">Apply</button>\n        </form>\n");
            }
            out.append("      </div>\n    </section>\n");
        }
        out.append("  </main>\n  <script>\n");
        for (int c = 0; c < 8; c++) {
            String name = NAMES[c];
            out.append("    function render").append(name).append("(items) {\n")
                    .append("      const list = document.querySelector('#").append(name.toLowerCase()).append(" tbody');\n")
                    .append("      for (const item of items) {\n        if (!item.visible) {\n          continue;\n        }\n")
                    .append("        const row = document.createElement('tr');\n        row.textContent = item.label;\n")
                    .append("        list.appendChild(row);\n      }\n    }\n\n");
        }
        out.append("  </script>\n</body>\n</html>\n");
        return out.toString();
    }

    /** About 400 lines of Markdown with headings, paragraphs, lists, tables, quotes and code fences. */
    private static String markdown(String title, int sections) {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n").append(words(1, 30)).append(".\n\n");
        for (int s = 0; s < sections; s++) {
            out.append("## ").append(s + 1).append(". ").append(NAMES[s % NAMES.length]).append(" handling\n\n");
            out.append(words(s, 18 + s % 4 * 9)).append(".\n").append(words(s + 2, 12 + s % 3 * 7)).append(".\n\n");
            out.append("### Steps\n\n");
            for (int i = 0; i < 3 + s % 3; i++) out.append("- ").append(words(s + i, 4 + i * 2)).append("\n");
            out.append("\n");
            switch (s % 4) {
                case 0 -> out.append("```kotlin\nfun ").append(NAMES[s % NAMES.length].toLowerCase())
                        .append("(items: List<String>) = items\n    .filter { it.isNotBlank() }\n    .map { it.trim() }\n```\n\n");
                case 1 -> out.append("| Option | Default | Notes |\n| --- | --- | --- |\n| width | 110 | ")
                        .append(words(s, 5)).append(" |\n| pixels | 3 | ").append(words(s + 1, 4)).append(" |\n| fit | off | ")
                        .append(words(s + 2, 6)).append(" |\n\n");
                case 2 -> out.append("> ").append(words(s + 4, 16)).append(".\n> ").append(words(s + 5, 9)).append(".\n\n");
                default -> out.append("1. ").append(words(s, 7)).append("\n2. ").append(words(s + 1, 5))
                        .append("\n3. ").append(words(s + 2, 9)).append("\n\n");
            }
            out.append(words(s + 6, 25)).append(".\n\n");
        }
        return out.toString();
    }
}
