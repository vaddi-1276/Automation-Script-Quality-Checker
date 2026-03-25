/*
=============================================================
LOCATOR HEALTH MONITOR
-------------------------------------------------------------
A segregated module that tracks UI locators and flags:
• Brittle selectors (long xpaths, index-based, text-based)
• Duplicate selectors (same locator used in multiple places)
• Outdated selectors (deprecated patterns, fragile expressions)

Runs independently or can be invoked from AutomationQualityChecker.
=============================================================
*/

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Segregated Locator Health Monitor.
 * Tracks UI locators and flags brittle, duplicate, or outdated selectors.
 */
public class LocatorHealthMonitor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".js", ".ts", ".jsx", ".tsx", ".py", ".java");

    // ------------------------------------------------------------------
    // LOCATOR ENTRY - single occurrence of a locator in source
    // ------------------------------------------------------------------
    public static class LocatorEntry {
        public final String locator;
        public final String file;
        public final int line;
        public final int column;
        public final String snippet;
        public final String type;  // "brittle" | "duplicate" | "outdated" | "ok"

        public LocatorEntry(String locator, String file, int line, int column, String snippet, String type) {
            this.locator = locator;
            this.file = file;
            this.line = line;
            this.column = column;
            this.snippet = snippet;
            this.type = type;
        }
    }

    // ------------------------------------------------------------------
    // MONITOR RESULT - aggregated output
    // ------------------------------------------------------------------
    public static class MonitorResult {
        public final List<LocatorEntry> brittle;
        public final List<LocatorEntry> duplicates;
        public final List<LocatorEntry> outdated;
        public final List<LocatorEntry> allLocators;
        public final int totalFilesScanned;

        public MonitorResult(List<LocatorEntry> brittle, List<LocatorEntry> duplicates,
                            List<LocatorEntry> outdated, List<LocatorEntry> allLocators,
                            int totalFilesScanned) {
            this.brittle = brittle;
            this.duplicates = duplicates;
            this.outdated = outdated;
            this.allLocators = allLocators;
            this.totalFilesScanned = totalFilesScanned;
        }
    }

    // ------------------------------------------------------------------
    // Locator extraction patterns (Java, Playwright, Cypress, Python)
    // ------------------------------------------------------------------
    private static Pattern[] getLocatorPatterns() {
        return new Pattern[] {
            Pattern.compile("\\bBy\\.id\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
            Pattern.compile("\\bBy\\.xpath\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
            Pattern.compile("\\bBy\\.cssSelector\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
            Pattern.compile("=\\s*\"(//[^\"]*)\"\\s*;"),
            Pattern.compile("=\\s*\"(#[^\"]*)\"\\s*;"),
            Pattern.compile("=\\s*\"(\\.[^\"]*)\"\\s*;"),
            Pattern.compile("\\blocator\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
            Pattern.compile("\\bcy\\.get\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
            Pattern.compile("\\bfind_element\\s*\\([^,]*,\\s*([\"'][^\"']+[\"'])\\s*\\)"),
            Pattern.compile("([\"']#[^\"']+[\"'])"),
            Pattern.compile("([\"']\\.[^\"']+[\"'])"),
            Pattern.compile("([\"']//[^\"']+[\"'])")
        };
    }

    // ------------------------------------------------------------------
    // Brittle patterns: long xpath, index-based, text-based, contains
    // ------------------------------------------------------------------
    private static boolean isBrittle(String locator) {
        String s = locator.replace("\"", "").replace("'", "");
        // Long xpath chain (e.g. //div/div/div/span/...)
        if (s.startsWith("//") && (s.split("/").length > 6 || s.length() > 80)) return true;
        // Index-based: [1], [2], (1), nth-child(1)
        if (Pattern.compile("\\[\\d+\\]|\\(\\d+\\)|nth-child\\s*\\(\\s*\\d+").matcher(s).find()) return true;
        // Text-based (fragile when UI text changes)
        if (Pattern.compile("text\\s*=\\s*[\"']|contains\\s*\\(|text\\(\\)").matcher(s).find()) return true;
        // contains( - often brittle
        if (s.contains("contains(")) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Outdated patterns: deprecated or fragile expressions
    // ------------------------------------------------------------------
    private static boolean isOutdated(String locator) {
        String s = locator.replace("\"", "").replace("'", "").toLowerCase();
        // position(), following-sibling with index
        if (s.contains("position()") || s.contains("following-sibling::*[")) return true;
        // preceding-sibling with index
        if (s.contains("preceding-sibling::*[")) return true;
        // ancestor:: with deep nesting
        if (s.contains("ancestor::") && s.split("ancestor::").length > 2) return true;
        // @class with multiple classes (brittle when CSS changes)
        if (s.matches(".*@class\\s*=\\s*[^\\]]*\\s+[^\\]]*.*")) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Extract all locators from a file
    // ------------------------------------------------------------------
    private static List<LocatorEntry> extractLocators(Path path, List<String> lines) {
        List<LocatorEntry> entries = new ArrayList<>();
        Pattern[] patterns = getLocatorPatterns();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern p : patterns) {
                Matcher m = p.matcher(line);
                while (m.find()) {
                    String value = m.group(1).replace("\"", "").replace("'", "");
                    String raw = m.group(1);
                    String snippet = line.trim();
                    entries.add(new LocatorEntry(raw, path.toString(), i + 1, m.start(1) + 1, snippet, "ok"));
                }
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // Collect files to scan
    // ------------------------------------------------------------------
    private static List<Path> collectFiles(List<String> targets) {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (String target : targets) {
            Path p = Paths.get(target);
            if (!Files.exists(p)) continue;
            if (Files.isRegularFile(p) && SUPPORTED_EXTENSIONS.contains(getSuffix(p))) {
                unique.add(p);
                continue;
            }
            if (Files.isDirectory(p)) {
                try (Stream<Path> stream = Files.walk(p)) {
                    stream
                        .filter(Files::isRegularFile)
                        .filter(f -> SUPPORTED_EXTENSIONS.contains(getSuffix(f)))
                        .forEach(unique::add);
                } catch (IOException ignored) {}
            }
        }
        TreeSet<Path> sorted = new TreeSet<>(Comparator.comparing(Path::toString));
        sorted.addAll(unique);
        return new ArrayList<>(sorted);
    }

    private static String getSuffix(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    // Run the Locator Health Monitor
    // ------------------------------------------------------------------
    public static MonitorResult run(List<String> targets) {
        List<Path> files = collectFiles(targets);
        List<LocatorEntry> allLocators = new ArrayList<>();
        List<LocatorEntry> brittle = new ArrayList<>();
        List<LocatorEntry> outdated = new ArrayList<>();

        for (Path path : files) {
            List<String> lines = readLines(path);
            if (lines.isEmpty()) continue;

            List<LocatorEntry> entries = extractLocators(path, lines);
            for (LocatorEntry e : entries) {
                allLocators.add(e);
                String loc = e.locator.replace("\"", "").replace("'", "");
                if (isBrittle(loc)) {
                    brittle.add(new LocatorEntry(e.locator, e.file, e.line, e.column, e.snippet, "brittle"));
                } else if (isOutdated(loc)) {
                    outdated.add(new LocatorEntry(e.locator, e.file, e.line, e.column, e.snippet, "outdated"));
                }
            }
        }

        // Duplicates: same locator value in multiple places
        List<LocatorEntry> duplicates = new ArrayList<>();
        for (LocatorEntry e : allLocators) {
            String norm = e.locator.replace("\"", "").replace("'", "").trim();
            long count = allLocators.stream()
                .filter(x -> x.locator.replace("\"", "").replace("'", "").trim().equals(norm))
                .count();
            if (count > 1) {
                duplicates.add(new LocatorEntry(e.locator, e.file, e.line, e.column, e.snippet, "duplicate"));
            }
        }

        return new MonitorResult(brittle, duplicates, outdated, allLocators, files.size());
    }

    // ------------------------------------------------------------------
    // Console report
    // ------------------------------------------------------------------
    public static void printReport(MonitorResult result) {
        System.out.println("========================================");
        System.out.println("LOCATOR HEALTH MONITOR");
        System.out.println("========================================");
        System.out.println("Files Scanned: " + result.totalFilesScanned);
        System.out.println("Total Locators: " + result.allLocators.size());
        System.out.println("Brittle: " + result.brittle.size());
        System.out.println("Duplicate: " + result.duplicates.size());
        System.out.println("Outdated: " + result.outdated.size());
        System.out.println("----------------------------------------");

        if (!result.brittle.isEmpty()) {
            System.out.println("\nBRITTLE SELECTORS:");
            for (LocatorEntry e : result.brittle) {
                System.out.println("  " + e.file + ":" + e.line + ":" + e.column + " | " + e.locator);
                System.out.println("    snippet: " + e.snippet);
            }
        }
        if (!result.duplicates.isEmpty()) {
            System.out.println("\nDUPLICATE SELECTORS:");
            for (LocatorEntry e : result.duplicates) {
                System.out.println("  " + e.file + ":" + e.line + ":" + e.column + " | " + e.locator);
            }
        }
        if (!result.outdated.isEmpty()) {
            System.out.println("\nOUTDATED SELECTORS:");
            for (LocatorEntry e : result.outdated) {
                System.out.println("  " + e.file + ":" + e.line + ":" + e.column + " | " + e.locator);
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON report (for dashboard integration)
    // ------------------------------------------------------------------
    public static String toJson(MonitorResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"locator_health\":{");
        sb.append("\"total_files_scanned\":").append(result.totalFilesScanned).append(",");
        sb.append("\"total_locators\":").append(result.allLocators.size()).append(",");
        sb.append("\"brittle_count\":").append(result.brittle.size()).append(",");
        sb.append("\"duplicate_count\":").append(result.duplicates.size()).append(",");
        sb.append("\"outdated_count\":").append(result.outdated.size()).append(",");
        sb.append("\"brittle\":[");
        for (int i = 0; i < result.brittle.size(); i++) {
            LocatorEntry e = result.brittle.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"file\":\"").append(escape(e.file)).append("\",\"line\":").append(e.line)
              .append(",\"column\":").append(e.column).append(",\"locator\":\"").append(escape(e.locator))
              .append("\",\"snippet\":\"").append(escape(e.snippet)).append("\"}");
        }
        sb.append("],\"duplicates\":[");
        for (int i = 0; i < result.duplicates.size(); i++) {
            LocatorEntry e = result.duplicates.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"file\":\"").append(escape(e.file)).append("\",\"line\":").append(e.line)
              .append(",\"column\":").append(e.column).append(",\"locator\":\"").append(escape(e.locator))
              .append("\",\"snippet\":\"").append(escape(e.snippet)).append("\"}");
        }
        sb.append("],\"outdated\":[");
        for (int i = 0; i < result.outdated.size(); i++) {
            LocatorEntry e = result.outdated.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"file\":\"").append(escape(e.file)).append("\",\"line\":").append(e.line)
              .append(",\"column\":").append(e.column).append(",\"locator\":\"").append(escape(e.locator))
              .append("\",\"snippet\":\"").append(escape(e.snippet)).append("\"}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ------------------------------------------------------------------
    // Standalone entry point
    // ------------------------------------------------------------------
    public static void main(String[] args) {
        List<String> targets = new ArrayList<>();
        String jsonOut = null;
        for (int i = 0; i < args.length; i++) {
            if ("--json".equals(args[i]) && i + 1 < args.length) {
                jsonOut = args[++i];
            } else if (!args[i].startsWith("--")) {
                targets.add(args[i]);
            }
        }
        if (targets.isEmpty()) {
            System.err.println("Usage: java LocatorHealthMonitor [--json output.json] <dir-or-file>...");
            System.exit(2);
        }
        MonitorResult result = run(targets);
        printReport(result);
        if (jsonOut != null) {
            try {
                Files.writeString(Paths.get(jsonOut), toJson(result), StandardCharsets.UTF_8);
                System.out.println("\nJSON report written to: " + jsonOut);
            } catch (IOException e) {
                System.err.println("Failed to write JSON: " + e.getMessage());
                System.exit(1);
            }
        }
    }
}
