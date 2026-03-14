/*
=============================================================
AUTOMATION QUALITY CHECKER
-------------------------------------------------------------
A lightweight static analysis tool designed to scan automation
test scripts and detect common quality issues.

Supported Languages:
• JavaScript
• TypeScript
• Python

Detects Issues Such As:
• Hard waits (sleep / waitForTimeout)
• Hardcoded test data
• Duplicate locators
• Weak assertions
• Unused helper functions
• Missing validations

Outputs:
• Console summary
• JSON report
• TXT report
• Markdown report

Purpose:
Improve automation test maintainability and reliability.
=============================================================
*/

/* =========================================================
   IMPORTS
   ---------------------------------------------------------
   Standard Java libraries used for file handling,
   regex detection, collections, and stream processing.
   =========================================================
*/

// Exception type used when file read/write operations fail.
import java.io.IOException;

// Explicit UTF-8 encoding for predictable text I/O across environments.
import java.nio.charset.StandardCharsets;

// Core file API for walking directories, reading lines, and writing reports.
import java.nio.file.Files;

// Represents filesystem paths in a platform-safe way.
import java.nio.file.Path;

// Utility to convert strings into Path objects.
import java.nio.file.Paths;

// Used to define custom sorting rules.
import java.util.Comparator;

// Preserves insertion order while deduplicating collected file paths.
import java.util.LinkedHashSet;

// Generic set interface for extension lists and unique values.
import java.util.Set;

// Maintains sorted unique paths for deterministic scan order.
import java.util.TreeSet;

// Regex matcher used to extract and detect pattern hits from lines.
import java.util.regex.Matcher;

// Regex pattern compiler used by all detection rules.
import java.util.regex.Pattern;

// Stream support for efficient file traversal and line processing.
import java.util.stream.Stream;

/* =========================================================
   MAIN TOOL CLASS
   ---------------------------------------------------------
   This class contains the entire analysis engine responsible
   for scanning files, detecting issues, and generating reports.
   =========================================================
*/
public class AutomationQualityChecker {

    /*
     * -----------------------------------------------------
     * SUPPORTED FILE EXTENSIONS
     * -----------------------------------------------------
     * Default file types scanned when no custom extensions
     * are provided through CLI arguments.
     * -----------------------------------------------------
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".js", ".ts", ".jsx", ".tsx", ".py", ".java");

    /*
     * -----------------------------------------------------
     * ISSUE ORDERING
     * -----------------------------------------------------
     * Fixed ordering ensures consistent report formatting
     * across Console, TXT, Markdown, and JSON outputs.
     * -----------------------------------------------------
     */
    private static final String[] ISSUE_ORDER = {
            "hard_wait",
            "hardcoded_test_data",
            "duplicate_locator",
            "poor_assertion",
            "unused_function",
            "missing_validation"
    };

    /*
     * =====================================================
     * LIGHTWEIGHT CUSTOM DATA STRUCTURE
     * -----------------------------------------------------
     * DynamicArray is a simplified custom list implementation
     * used to avoid dependency on external libraries or
     * Java collections like ArrayList.
     * 
     * It provides:
     * • dynamic resizing
     * • indexed access
     * • insertion support
     * =====================================================
     */
    private static class DynamicArray<T> {

        // Internal array storing elements
        private Object[] values = new Object[16];

        // Current number of stored elements
        private int size = 0;

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        /* Add element to the end of the array */
        void add(T value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        /* Insert element at a specific index */
        void addAt(int index, T value) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }

            ensureCapacity(size + 1);

            for (int i = size; i > index; i--) {
                values[i] = values[i - 1];
            }

            values[index] = value;
            size++;
        }

        /* Append all elements from another DynamicArray */
        void addAll(DynamicArray<T> other) {
            for (int i = 0; i < other.size(); i++) {
                add(other.get(i));
            }
        }

        /* Retrieve element by index */
        @SuppressWarnings("unchecked")
        T get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }
            return (T) values[index];
        }

        /* Replace element at index */
        void set(int index, T value) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }
            values[index] = value;
        }

        /* Ensure array has enough capacity for new elements */
        private void ensureCapacity(int needed) {

            if (needed <= values.length) {
                return;
            }

            int next = values.length * 2;

            while (next < needed) {
                next *= 2;
            }

            Object[] resized = new Object[next];

            for (int i = 0; i < size; i++) {
                resized[i] = values[i];
            }

            values = resized;
        }
    }

    /*
     * =====================================================
     * FINDING MODEL
     * -----------------------------------------------------
     * Represents a single detected issue inside a source
     * file including its exact position and description.
     * =====================================================
     */
    private static class Finding {

        final String file;
        final int line;
        final int column;
        final String issue;
        final String detail;

        Finding(String file, int line, int column, String issue, String detail) {
            this.file = file;
            this.line = line;
            this.column = column;
            this.issue = issue;
            this.detail = detail;
        }
    }

    /*
     * =====================================================
     * LOCATOR MATCH MODEL
     * -----------------------------------------------------
     * Represents a locator found in automation scripts.
     * 
     * Example:
     * page.locator("#loginBtn")
     * 
     * Used for detecting duplicate locator usage.
     * =====================================================
     */
    private static class LocatorMatch {

        final String value;
        final int line;
        final int column;
        final String source;

        LocatorMatch(String value, int line, int column, String source) {
            this.value = value;
            this.line = line;
            this.column = column;
            this.source = source;
        }
    }

    /*
     * =====================================================
     * FUNCTION DECLARATION MODEL
     * -----------------------------------------------------
     * Stores metadata about discovered functions inside
     * source files so unused helpers can be detected.
     * =====================================================
     */
    private static class FunctionDecl {

        final String name;
        final int line;
        final int column;

        FunctionDecl(String name, int line, int column) {
            this.name = name;
            this.line = line;
            this.column = column;
        }
    }

    /*
     * =====================================================
     * BUILD RESULT CONTAINER
     * -----------------------------------------------------
     * Aggregates all results produced by the scan pipeline.
     * 
     * Includes:
     * • summary metrics
     * • categorized findings
     * • duplicate locator clusters
     * • refactor insights
     * =====================================================
     */
    private static class BuildResult {

        final Summary summary;
        final FindingsByIssue findings;
        final DynamicArray<DuplicateGroup> duplicateGroups;
        final DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights;

        BuildResult(
                Summary summary,
                FindingsByIssue findings,
                DynamicArray<DuplicateGroup> duplicateGroups,
                DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights) {
            this.summary = summary;
            this.findings = findings;
            this.duplicateGroups = duplicateGroups;
            this.duplicateRefactorInsights = duplicateRefactorInsights;
        }
    }

    /*
     * =====================================================
     * SUMMARY METRICS
     * -----------------------------------------------------
     * High level statistics used in reports and dashboards.
     * =====================================================
     */
    private static class Summary {

        final int hardWaitFound;
        final int hardcodedTestData;
        final int duplicateLocators;
        final int poorAssertions;
        final int unusedFunctions;
        final int missingValidations;
        final int totalFilesScanned;

        Summary(
                int hardWaitFound,
                int hardcodedTestData,
                int duplicateLocators,
                int poorAssertions,
                int unusedFunctions,
                int missingValidations,
                int totalFilesScanned) {
            this.hardWaitFound = hardWaitFound;
            this.hardcodedTestData = hardcodedTestData;
            this.duplicateLocators = duplicateLocators;
            this.poorAssertions = poorAssertions;
            this.unusedFunctions = unusedFunctions;
            this.missingValidations = missingValidations;
            this.totalFilesScanned = totalFilesScanned;
        }
    }

    /*
     * =====================================================
     * FINDINGS GROUPED BY ISSUE TYPE
     * -----------------------------------------------------
     * Organizes detected issues by category so reports can
     * easily render grouped sections.
     * =====================================================
     */
    private static class FindingsByIssue {

        final DynamicArray<Finding> hardWait = new DynamicArray<>();
        final DynamicArray<Finding> hardcodedTestData = new DynamicArray<>();
        final DynamicArray<Finding> duplicateLocator = new DynamicArray<>();
        final DynamicArray<Finding> poorAssertion = new DynamicArray<>();
        final DynamicArray<Finding> unusedFunction = new DynamicArray<>();
        final DynamicArray<Finding> missingValidation = new DynamicArray<>();

        DynamicArray<Finding> get(String issue) {

            switch (issue) {
                case "hard_wait":
                    return hardWait;

                case "hardcoded_test_data":
                    return hardcodedTestData;

                case "duplicate_locator":
                    return duplicateLocator;

                case "poor_assertion":
                    return poorAssertion;

                case "unused_function":
                    return unusedFunction;

                case "missing_validation":
                    return missingValidation;

                default:
                    return new DynamicArray<>();
            }
        }
    }

    /*
     * =====================================================
     * LOCATOR BUCKET
     * -----------------------------------------------------
     * Used to group occurrences of the same locator across
     * multiple files for duplicate detection.
     * =====================================================
     */
    private static class LocatorBucket {

        final String locator;
        final DynamicArray<Finding> occurrences = new DynamicArray<>();

        LocatorBucket(String locator) {
            this.locator = locator;
        }
    }

    /*
     * =====================================================
     * TOKEN COUNT MODEL
     * -----------------------------------------------------
     * Simple utility used to count occurrences of tokens
     * such as module names during analysis.
     * =====================================================
     */
    private static class TokenCount {

        final String token;
        int count;

        TokenCount(String token) {
            this.token = token;
            this.count = 1;
        }
    }

    /*
     * =====================================================
     * DUPLICATE LOCATOR ENTRY
     * -----------------------------------------------------
     * Represents a single instance of a duplicated locator
     * in a specific file and line.
     * =====================================================
     */
    private static class DuplicateEntry {

        final String file;
        final String line;
        final String code;

        DuplicateEntry(String file, String line, String code) {
            this.file = file;
            this.line = line;
            this.code = code;
        }
    }

    /*
     * =====================================================
     * DUPLICATE GROUP
     * -----------------------------------------------------
     * Groups all occurrences of the same locator value.
     * =====================================================
     */
    private static class DuplicateGroup {

        final String locator;
        final DynamicArray<DuplicateEntry> entries = new DynamicArray<>();

        DuplicateGroup(String locator) {
            this.locator = locator;
        }
    }

    /*
     * =====================================================
     * REFACTOR INSIGHT MODEL
     * -----------------------------------------------------
     * Represents a ranked opportunity for refactoring
     * duplicate locator patterns into reusable constants
     * or Page Object models.
     * =====================================================
     */
    private static class DuplicateRefactorInsight {

        final String locator;
        final int occurrences;
        final int distinctFiles;
        final String topModule;
        final String priority;
        final String recommendation;

        DuplicateRefactorInsight(
                String locator,
                int occurrences,
                int distinctFiles,
                String topModule,
                String priority,
                String recommendation) {
            this.locator = locator;
            this.occurrences = occurrences;
            this.distinctFiles = distinctFiles;
            this.topModule = topModule;
            this.priority = priority;
            this.recommendation = recommendation;
        }
    }

    /*
     * =====================================================================
     * AUTOMATION QUALITY CHECKER
     * ---------------------------------------------------------------------
     * This tool performs static analysis on automation test scripts and
     * detects common quality problems such as:
     * 
     * • Hard waits
     * • Hardcoded test data
     * • Duplicate locators
     * • Weak assertions
     * • Missing validations
     * • Unused functions
     * 
     * The tool scans multiple file types like:
     * .js, .ts, .jsx, .tsx, .py
     * 
     * It generates reports in:
     * • Console
     * • JSON
     * • TXT
     * • Markdown
     * =====================================================================
     */

    // ------------------------------------------------------------------
    // Tracks test references impacted when a specific function changes.
    // Example: If login() changes, this tracks all tests referencing it.
    // ------------------------------------------------------------------
    private static class FunctionImpact {

        // Name of the changed function
        final String functionName;

        // Stores all locations where this function is referenced
        final DynamicArray<Finding> references = new DynamicArray<>();

        FunctionImpact(String functionName) {
            this.functionName = functionName;
        }
    }

    // ------------------------------------------------------------------
    // Stores a compiled regex pattern used to find references
    // to changed functions inside source code.
    // ------------------------------------------------------------------
    private static class TokenPattern {

        // Name of the function to search for
        final String functionName;

        // Precompiled regex pattern for faster scanning
        final Pattern pattern;

        TokenPattern(String functionName, Pattern pattern) {
            this.functionName = functionName;
            this.pattern = pattern;
        }
    }

    // ------------------------------------------------------------------
    // Command Line Arguments Configuration
    // Stores all parameters passed when running the CLI tool.
    // Example:
    // java AutomationQualityChecker --json report.json ./tests
    // ------------------------------------------------------------------
    private static class CliArgs {

        // Target files or directories to scan
        DynamicArray<String> targets = new DynamicArray<>();

        // Supported extensions (default list)
        String extensions = ".js,.ts,.jsx,.tsx,.py";

        // Output file locations
        String jsonOutput = null;
        String txtOutput = null;
        String mdOutput = null;

        // Display issue lines in console output
        boolean showLines = false;

        // Limit number of lines shown per issue
        int maxLinesPerIssue = 0;

        // List of changed functions for impact analysis
        DynamicArray<String> changedFunctions = new DynamicArray<>();

        // File where changed functions exist
        String changedFile = "";

        // Maximum number of impacted references per function
        int maxImpactedPerFunction = 0;

        // Optional hard wait behavior preset (for example: selenium)
        String hardWaitPreset = "";

        // In selenium preset mode, optionally treat assertions as hard wait markers
        boolean seleniumAssertionAsHardWait = false;

        // Comma-separated list of issue types to detect (empty = all)
        String enableIssues = "";

        // Automation language for framework-specific patterns (all, java, javascript_playwright, javascript_cypress, python)
        String automationLanguage = "";
    }

    // ------------------------------------------------------------------
    // Collects all files to scan.
    //
    // Responsibilities:
    // • Accept files or directories
    // • Recursively scan directories
    // • Filter supported file extensions
    // • Remove duplicates
    // • Sort files alphabetically
    // ------------------------------------------------------------------
    private static DynamicArray<Path> collectFiles(DynamicArray<String> targets, Set<String> extensions) {

        // LinkedHashSet ensures uniqueness while preserving insertion order
        LinkedHashSet<Path> unique = new LinkedHashSet<>();

        for (int i = 0; i < targets.size(); i++) {

            String target = targets.get(i);
            Path p = Paths.get(target);

            // Skip paths that do not exist
            if (!Files.exists(p)) {
                continue;
            }

            // If target is a valid file with supported extension
            if (Files.isRegularFile(p) && extensions.contains(getLowerSuffix(p))) {
                unique.add(p);
                continue;
            }

            // If target is a directory, scan recursively
            if (Files.isDirectory(p)) {
                try (Stream<Path> stream = Files.walk(p)) {

                    stream
                            .filter(Files::isRegularFile)
                            .filter(f -> extensions.contains(getLowerSuffix(f)))
                            .forEach(unique::add);

                } catch (IOException ignored) {

                    // Ignore unreadable directories
                    // and continue scanning other files
                }
            }
        }

        // Sort results for deterministic output
        TreeSet<Path> sortedSet = new TreeSet<>(Comparator.comparing(Path::toString));

        sortedSet.addAll(unique);

        DynamicArray<Path> sorted = new DynamicArray<>();

        for (Path path : sortedSet) {
            sorted.add(path);
        }

        return sorted;
    }

    // ------------------------------------------------------------------
    // Returns the lowercase file extension.
    //
    // Example:
    // test.js → .js
    // login.ts → .ts
    // ------------------------------------------------------------------
    private static String getLowerSuffix(Path p) {

        String name = p.getFileName() == null
                ? ""
                : p.getFileName().toString();

        int dot = name.lastIndexOf('.');

        return dot >= 0
                ? name.substring(dot).toLowerCase()
                : "";
    }

    // ------------------------------------------------------------------
    // Reads file content into memory as a list of lines.
    //
    // Uses UTF-8 encoding.
    // Returns an empty list if reading fails.
    // ------------------------------------------------------------------
    private static DynamicArray<String> readLines(Path path) {

        DynamicArray<String> lines = new DynamicArray<>();

        try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {

            stream.forEach(lines::add);

        } catch (IOException ignored) {

            // Return empty list if file reading fails
            return new DynamicArray<>();
        }

        return lines;
    }

    // ------------------------------------------------------------------
    // Detects hard waits in test scripts.
    //
    // Hard waits slow tests and make them flaky.
    // Examples detected:
    //
    // Thread.sleep(5000)
    // waitForTimeout(3000)
    // cy.wait(2000)
    // sleep(5)
    // ------------------------------------------------------------------
    private static DynamicArray<Finding> detectHardWaits(
            Path path,
            DynamicArray<String> lines,
            String hardWaitPreset,
            boolean seleniumAssertionAsHardWait) {

        Pattern threadSleepPattern = Pattern.compile("\\bThread\\.sleep\\s*\\(\\s*\\d+\\s*\\)");
        Pattern waitForTimeoutPattern = Pattern.compile("\\bwaitForTimeout\\s*\\(\\s*\\d+\\s*\\)");
        Pattern pythonSleepPattern = Pattern.compile("\\btime\\.sleep\\s*\\(\\s*\\d+(\\.\\d+)?\\s*\\)");
        Pattern genericSleepPattern = Pattern.compile("\\bsleep\\s*\\(\\s*\\d+(\\.\\d+)?\\s*\\)");
        Pattern cypressWaitPattern = Pattern.compile("\\bcy\\.wait\\s*\\(\\s*\\d+\\s*\\)");
        Pattern seleniumAssertionPattern = Pattern.compile(
                "\\b(assert|assertTrue|assertEquals|assertThat|Assertions\\.[A-Za-z_][A-Za-z0-9_]*|Assert\\.[A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

        boolean seleniumPreset = "selenium".equalsIgnoreCase(hardWaitPreset == null ? "" : hardWaitPreset.trim());

        DynamicArray<Finding> findings = new DynamicArray<>();

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);
            Matcher threadSleep = threadSleepPattern.matcher(line);
            if (threadSleep.find()) {
                findings.add(new Finding(
                        path.toString(),
                        i + 1,
                        threadSleep.start() + 1,
                        "hard_wait",
                        line.trim()));
            }

            // In selenium preset mode, hard waits are predefined from UI.
            if (!seleniumPreset) {
                Pattern[] nonSeleniumPatterns = new Pattern[] {
                        waitForTimeoutPattern,
                        pythonSleepPattern,
                        genericSleepPattern,
                        cypressWaitPattern
                };

                for (Pattern pat : nonSeleniumPatterns) {
                    Matcher m = pat.matcher(line);
                    if (m.find()) {
                        findings.add(new Finding(
                                path.toString(),
                                i + 1,
                                m.start() + 1,
                                "hard_wait",
                                line.trim()));
                    }
                }
            }

            if (seleniumPreset && seleniumAssertionAsHardWait) {
                Matcher assertion = seleniumAssertionPattern.matcher(line);
                if (assertion.find()) {
                    findings.add(new Finding(
                            path.toString(),
                            i + 1,
                            assertion.start() + 1,
                            "hard_wait",
                            line.trim()));
                }
            }
        }
        return findings;
    }

    // ------------------------------------------------------------------
    // Detects hardcoded sensitive test data such as credentials,
    // tokens, API keys, emails, URLs etc.
    // Hardcoding such data is a bad practice because:
    // 1. It exposes secrets
    // 2. Makes tests environment dependent
    // 3. Reduces maintainability
    // ------------------------------------------------------------------
    private static DynamicArray<Finding> detectHardcodedTestData(
            Path path,
            DynamicArray<String> lines) {

        // Regex patterns used to detect common hardcoded data
        Pattern[] patterns = new Pattern[] {

                // Detects passwords, tokens, API keys etc.
                Pattern.compile("\\b(password|passwd|pwd|token|apikey|apiKey|secret)\\b\\s*[:=]\\s*[\"'][^\"']+[\"']"),

                // Detects hardcoded emails, usernames, phone numbers
                Pattern.compile("\\b(email|username|user(name)?|phone|mobile)\\b\\s*[:=]\\s*[\"'][^\"']+[\"']"),

                // Detects hardcoded URLs or endpoints
                Pattern.compile("\\b(url|endpoint|baseUrl|baseURL)\\b\\s*[:=]\\s*[\"']https?://[^\"']+[\"']")
        };

        DynamicArray<Finding> findings = new DynamicArray<>();

        // Scan every line in the file
        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            for (Pattern pattern : patterns) {

                Matcher matcher = pattern.matcher(line);

                // If pattern matches, create a finding
                if (matcher.find()) {

                    findings.add(new Finding(
                            path.toString(),
                            i + 1, // line number
                            matcher.start() + 1, // column number
                            "hardcoded_test_data",
                            line.trim() // original code snippet
                    ));

                    break;
                }
            }
        }

        return findings;
    }

    // ------------------------------------------------------------------
    // Returns weak assertion patterns for the given automation language.
    // ------------------------------------------------------------------
    private static Pattern[] getWeakAssertionPatterns(String automationLanguage) {
        String lang = automationLanguage == null ? "" : automationLanguage.trim().toLowerCase();
        boolean useAll = lang.isEmpty() || "all".equals(lang);

        // Java (Selenium): assert, assertTrue, assertEquals
        Pattern[] javaPatterns = new Pattern[] {
                Pattern.compile("\\bassert\\s*\\([^)]*\\)\\s*;?\\s*$"),
                Pattern.compile("\\bassertTrue\\s*\\(\\s*true\\s*\\)"),
                Pattern.compile("\\bassertEquals\\s*\\([^)]*,\\s*true\\s*\\)"),
                Pattern.compile("\\bassertThat\\s*\\([^)]*\\)\\.isTrue\\s*\\(")
        };
        // Playwright JS/TS: expect().toBeTruthy(), expect().toBeDefined()
        Pattern[] playwrightPatterns = new Pattern[] {
                Pattern.compile("\\bexpect\\s*\\([^)]*\\)\\s*\\.toBeTruthy\\s*\\("),
                Pattern.compile("\\bexpect\\s*\\([^)]*\\)\\s*\\.toBeDefined\\s*\\("),
                Pattern.compile("\\bexpect\\s*\\(\\s*true\\s*\\)\\s*\\.to(Be|Equal)\\s*\\(\\s*true\\s*\\)")
        };
        // Cypress: .should('exist'), weak should
        Pattern[] cypressPatterns = new Pattern[] {
                Pattern.compile("\\.should\\s*\\(\\s*[\"']exist[\"']\\s*\\)"),
                Pattern.compile("\\.should\\s*\\(\\s*[\"']be\\.visible[\"']\\s*\\)")
        };
        // Python: assert, weak expect
        Pattern[] pythonPatterns = new Pattern[] {
                Pattern.compile("\\bassert\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$"),
                Pattern.compile("\\bassert\\s*\\([^)]*\\)\\s*;?\\s*$"),
                Pattern.compile("\\bexpect\\s*\\([^)]*\\)\\.to_be_truthy\\s*\\(")
        };

        if (useAll) {
            Pattern[] all = new Pattern[javaPatterns.length + playwrightPatterns.length + cypressPatterns.length + pythonPatterns.length];
            System.arraycopy(javaPatterns, 0, all, 0, javaPatterns.length);
            System.arraycopy(playwrightPatterns, 0, all, javaPatterns.length, playwrightPatterns.length);
            System.arraycopy(cypressPatterns, 0, all, javaPatterns.length + playwrightPatterns.length, cypressPatterns.length);
            System.arraycopy(pythonPatterns, 0, all, javaPatterns.length + playwrightPatterns.length + cypressPatterns.length, pythonPatterns.length);
            return all;
        }
        switch (lang) {
            case "java": return javaPatterns;
            case "javascript_playwright": return playwrightPatterns;
            case "javascript_cypress": return cypressPatterns;
            case "python": return pythonPatterns;
            default: return javaPatterns;
        }
    }

    // ------------------------------------------------------------------
    // Detects weak or poor assertions in tests.
    // Uses framework-specific patterns based on automation language.
    // ------------------------------------------------------------------
    private static DynamicArray<Finding> detectPoorAssertions(
            Path path,
            DynamicArray<String> lines,
            String automationLanguage) {

        Pattern[] weakAssertions = getWeakAssertionPatterns(automationLanguage);

        DynamicArray<Finding> findings = new DynamicArray<>();

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            for (Pattern pattern : weakAssertions) {

                Matcher matcher = pattern.matcher(line);

                if (matcher.find()) {

                    findings.add(new Finding(
                            path.toString(),
                            i + 1,
                            matcher.start() + 1,
                            "poor_assertion",
                            line.trim()));

                    break;
                }
            }
        }

        return findings;
    }

    // ------------------------------------------------------------------
    // Returns assertion keyword pattern for missing validation detection.
    // ------------------------------------------------------------------
    private static Pattern getAssertionKeywordsPattern(String automationLanguage) {
        String lang = automationLanguage == null ? "" : automationLanguage.trim().toLowerCase();
        boolean useAll = lang.isEmpty() || "all".equals(lang);

        if (useAll) {
            return Pattern.compile(
                    "\\b(expect|assert|assertTrue|assertEquals|assertThat|should|verify|toBe|toEqual|toContain|to_be_attached|to_be_visible|to_contain_text)\\b");
        }
        switch (lang) {
            case "java":
                return Pattern.compile("\\b(assert|assertTrue|assertEquals|assertThat|Assertions?\\.[A-Za-z_][A-Za-z0-9_]*)\\b");
            case "javascript_playwright":
                return Pattern.compile("\\b(expect|toBe|toEqual|toContain|toBeAttached|toBeVisible|toContainText)\\b");
            case "javascript_cypress":
                return Pattern.compile("\\b(should|expect)\\b");
            case "python":
                return Pattern.compile("\\b(assert|expect|to_be_attached|to_be_visible|to_contain_text)\\b");
            default:
                return Pattern.compile("\\b(expect|assert|should)\\b");
        }
    }

    // ------------------------------------------------------------------
    // Detects test actions that do not have validation.
    // Uses framework-specific assertion keywords based on automation language.
    // ------------------------------------------------------------------
    private static DynamicArray<Finding> detectMissingValidations(
            Path path,
            DynamicArray<String> lines,
            String automationLanguage) {

        // Test actions (common across frameworks)
        Pattern actionPattern = Pattern.compile(
                "\\b(click|fill|type|tap|submit|navigate|goto|goTo|sendKeys|selectOption|check|uncheck)\\b");

        Pattern assertionPattern = getAssertionKeywordsPattern(automationLanguage);

        DynamicArray<Finding> findings = new DynamicArray<>();

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            Matcher action = actionPattern.matcher(line);

            // Skip lines that do not contain actions
            if (!action.find()) {
                continue;
            }

            // Skip lines that already contain validation
            if (assertionPattern.matcher(line).find()) {
                continue;
            }

            findings.add(new Finding(
                    path.toString(),
                    i + 1,
                    action.start() + 1,
                    "missing_validation",
                    line.trim()));
        }

        return findings;
    }

    // ------------------------------------------------------------------
    // Returns locator patterns for the given automation language.
    // all = use all framework patterns; otherwise use language-specific.
    // ------------------------------------------------------------------
    private static Pattern[] getLocatorPatterns(String automationLanguage) {
        String lang = automationLanguage == null ? "" : automationLanguage.trim().toLowerCase();
        boolean useAll = lang.isEmpty() || "all".equals(lang);

        // Java (Selenium): By.id(, By.xpath(, By.cssSelector(
        Pattern[] javaPatterns = new Pattern[] {
                Pattern.compile("\\bBy\\.id\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
                Pattern.compile("\\bBy\\.xpath\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)"),
                Pattern.compile("\\bBy\\.cssSelector\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)")
        };
        // Playwright: locator(
        Pattern[] playwrightPatterns = new Pattern[] {
                Pattern.compile("\\blocator\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)")
        };
        // Cypress: cy.get(
        Pattern[] cypressPatterns = new Pattern[] {
                Pattern.compile("\\bcy\\.get\\s*\\(\\s*([\"'][^\"']+[\"'])\\s*\\)")
        };
        // Python: find_element(
        Pattern[] pythonPatterns = new Pattern[] {
                Pattern.compile("\\bfind_element\\s*\\([^,]*,\\s*([\"'][^\"']+[\"'])\\s*\\)")
        };
        // Generic CSS/XPath (used when all)
        Pattern[] genericPatterns = new Pattern[] {
                Pattern.compile("([\"']#[^\"']+[\"'])"),
                Pattern.compile("([\"']\\.[^\"']+[\"'])"),
                Pattern.compile("([\"']//[^\"']+[\"'])")
        };

        if (useAll) {
            int total = javaPatterns.length + playwrightPatterns.length + cypressPatterns.length + pythonPatterns.length + genericPatterns.length;
            Pattern[] all = new Pattern[total];
            int idx = 0;
            System.arraycopy(javaPatterns, 0, all, idx, javaPatterns.length); idx += javaPatterns.length;
            System.arraycopy(playwrightPatterns, 0, all, idx, playwrightPatterns.length); idx += playwrightPatterns.length;
            System.arraycopy(cypressPatterns, 0, all, idx, cypressPatterns.length); idx += cypressPatterns.length;
            System.arraycopy(pythonPatterns, 0, all, idx, pythonPatterns.length); idx += pythonPatterns.length;
            System.arraycopy(genericPatterns, 0, all, idx, genericPatterns.length);
            return all;
        }
        switch (lang) {
            case "java": return javaPatterns;
            case "javascript_playwright": return playwrightPatterns;
            case "javascript_cypress": return cypressPatterns;
            case "python": return pythonPatterns;
            default: return javaPatterns;
        }
    }

    // ------------------------------------------------------------------
    // Extracts locator usage from automation frameworks.
    // Uses framework-specific patterns based on automation language.
    // ------------------------------------------------------------------
    private static DynamicArray<LocatorMatch> extractLocators(
            Path path,
            DynamicArray<String> lines,
            String automationLanguage) {

        Pattern[] locatorPatterns = getLocatorPatterns(automationLanguage);

        DynamicArray<LocatorMatch> matches = new DynamicArray<>();

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            for (Pattern pattern : locatorPatterns) {

                Matcher matcher = pattern.matcher(line);

                // A single line may contain multiple locators
                while (matcher.find()) {

                    String value = matcher.group(1);

                    matches.add(new LocatorMatch(
                            value,
                            i + 1,
                            matcher.start(1) + 1,
                            line.trim()));
                }
            }
        }

        return matches;
    }

    // ------------------------------------------------------------------
    // Extracts function declarations from multiple languages.
    //
    // Supported patterns:
    // • JavaScript functions
    // • Arrow functions
    // • Python functions
    // • Async functions
    //
    // Used later for:
    // • unused function detection
    // • impacted test analysis
    // ------------------------------------------------------------------
    private static DynamicArray<FunctionDecl> extractFunctionNames(
            Path path,
            DynamicArray<String> lines) {

        Pattern[] functionPatterns = new Pattern[] {

                // JS function
                Pattern.compile("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),

                // Arrow functions
                Pattern.compile("\\bconst\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("\\blet\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("\\bvar\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),

                // Python functions
                Pattern.compile("^\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),

                // Async JS functions
                Pattern.compile("^\\s*async\\s+function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        };

        DynamicArray<FunctionDecl> declarations = new DynamicArray<>();

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            for (Pattern pattern : functionPatterns) {

                Matcher matcher = pattern.matcher(line);

                if (matcher.find()) {

                    declarations.add(new FunctionDecl(
                            matcher.group(1), // function name
                            i + 1, // line number
                            matcher.start(1) + 1 // column
                    ));

                    break;
                }
            }
        }

        return declarations;
    }

    // ------------------------------------------------------------------
    // Core Analysis Pipeline
    //
    // This method is the heart of the AutomationQualityChecker.
    //
    // Responsibilities:
    // 1. Read all test files
    // 2. Run all quality detectors
    // 3. Aggregate findings
    // 4. Detect duplicate locators
    // 5. Detect unused functions
    // 6. Build a final summary report
    // ------------------------------------------------------------------
    private static BuildResult buildReport(DynamicArray<Path> files, CliArgs args) {

        java.util.Set<String> enabledIssues = parseEnableIssues(args.enableIssues);

        // Stores all findings categorized by issue type
        FindingsByIssue allFindings = new FindingsByIssue();

        // Stores locator occurrences for duplicate detection
        DynamicArray<LocatorBucket> locatorOccurrences = new DynamicArray<>();

        // Stores all declared functions for unused-function analysis
        DynamicArray<Finding> declaredFunctions = new DynamicArray<>();

        // Used to store full combined text of all scanned files
        // This helps detect function usage later
        StringBuilder combinedTextBuilder = new StringBuilder();

        // ---------------------------------------------------------------
        // Iterate through every scanned file
        // ---------------------------------------------------------------
        for (int i = 0; i < files.size(); i++) {

            Path path = files.get(i);

            // Read file lines
            DynamicArray<String> lines = readLines(path);

            // Skip empty or unreadable files
            if (lines.isEmpty()) {
                continue;
            }

            // Combine file content into a single string for later analysis
            combinedTextBuilder.append(joinStrings(lines, "\n")).append('\n');

            // -----------------------------------------------------------
            // Run detectors (only for enabled issue types)
            // -----------------------------------------------------------

            if (isIssueEnabled(enabledIssues, "hard_wait")) {
                allFindings.hardWait.addAll(
                        detectHardWaits(
                                path,
                                lines,
                                args.hardWaitPreset,
                                args.seleniumAssertionAsHardWait));
            }

            if (isIssueEnabled(enabledIssues, "hardcoded_test_data")) {
                allFindings.hardcodedTestData.addAll(detectHardcodedTestData(path, lines));
            }

            if (isIssueEnabled(enabledIssues, "poor_assertion")) {
                allFindings.poorAssertion.addAll(detectPoorAssertions(path, lines, args.automationLanguage));
            }

            if (isIssueEnabled(enabledIssues, "missing_validation")) {
                allFindings.missingValidation.addAll(detectMissingValidations(path, lines, args.automationLanguage));
            }

            // -----------------------------------------------------------
            // Extract locators and track duplicates
            // -----------------------------------------------------------
            if (isIssueEnabled(enabledIssues, "duplicate_locator")) {
                DynamicArray<LocatorMatch> locators = extractLocators(path, lines, args.automationLanguage);

                for (int j = 0; j < locators.size(); j++) {

                    LocatorMatch lm = locators.get(j);

                    // Store locator occurrence
                    addLocatorOccurrence(
                            locatorOccurrences,
                            lm.value,
                            new Finding(
                                    path.toString(),
                                    lm.line,
                                    lm.column,
                                    "duplicate_locator",
                                    lm.source));
                }
            }

            // -----------------------------------------------------------
            // Extract declared functions for unused-function detection
            // -----------------------------------------------------------
            if (isIssueEnabled(enabledIssues, "unused_function")) {
                DynamicArray<FunctionDecl> fnDecls = extractFunctionNames(path, lines);

                for (int j = 0; j < fnDecls.size(); j++) {

                    FunctionDecl fn = fnDecls.get(j);

                    // Ignore private/internal helper functions starting with "_"
                    if (!fn.name.startsWith("_")) {

                        declaredFunctions.add(
                                new Finding(
                                        path.toString(),
                                        fn.line,
                                        fn.column,
                                        "unused_function",
                                        fn.name));
                    }
                }
            }
        }

        // ---------------------------------------------------------------
        // Identify duplicate locators
        // A locator is considered duplicate if it appears > 1 time
        // ---------------------------------------------------------------
        for (int i = 0; i < locatorOccurrences.size(); i++) {

            LocatorBucket bucket = locatorOccurrences.get(i);

            if (bucket.occurrences.size() > 1) {
                allFindings.duplicateLocator.addAll(bucket.occurrences);
            }
        }

        // ---------------------------------------------------------------
        // Detect unused functions
        // Approach:
        // 1. Count token occurrences across all files
        // 2. If function name appears only once (declaration),
        // it means the function is unused
        // ---------------------------------------------------------------
        if (!declaredFunctions.isEmpty()) {

            String combinedText = combinedTextBuilder.toString();

            DynamicArray<TokenCount> functionMentions = new DynamicArray<>();

            Matcher m = Pattern.compile("\\b([A-Za-z_]\\w*)\\b").matcher(combinedText);

            // Count occurrences of each token
            while (m.find()) {
                incrementTokenCount(functionMentions, m.group(1));
            }

            // Identify functions with only one occurrence (unused)
            for (int i = 0; i < declaredFunctions.size(); i++) {

                Finding finding = declaredFunctions.get(i);

                if (getTokenCount(functionMentions, finding.detail) <= 1) {
                    allFindings.unusedFunction.add(finding);
                }
            }
        }

        // ---------------------------------------------------------------
        // Build summary statistics
        // ---------------------------------------------------------------
        Summary summary = new Summary(

                allFindings.hardWait.size(),
                allFindings.hardcodedTestData.size(),
                allFindings.duplicateLocator.size(),
                allFindings.poorAssertion.size(),
                allFindings.unusedFunction.size(),
                allFindings.missingValidation.size(),
                files.size());

        // ---------------------------------------------------------------
        // Group duplicate locators for structured reporting
        // ---------------------------------------------------------------
        DynamicArray<DuplicateGroup> duplicatesGrouped = new DynamicArray<>();

        // Sort locators alphabetically
        DynamicArray<LocatorBucket> sortedLocators = sortLocatorBuckets(locatorOccurrences);

        for (int i = 0; i < sortedLocators.size(); i++) {

            LocatorBucket bucket = sortedLocators.get(i);

            if (bucket.occurrences.size() > 1) {

                DuplicateGroup group = new DuplicateGroup(bucket.locator);

                for (int j = 0; j < bucket.occurrences.size(); j++) {

                    Finding o = bucket.occurrences.get(j);

                    group.entries.add(
                            new DuplicateEntry(
                                    o.file,
                                    o.line + ":" + o.column,
                                    o.detail));
                }

                duplicatesGrouped.add(group);
            }
        }

        // ---------------------------------------------------------------
        // Generate refactoring insights for duplicate locators
        // ---------------------------------------------------------------
        DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights = createDuplicateRefactorInsights(
                duplicatesGrouped);

        // Return final result
        return new BuildResult(
                summary,
                allFindings,
                duplicatesGrouped,
                duplicateRefactorInsights);
    }

    // ------------------------------------------------------------------
    // Creates prioritized refactoring insights for duplicate locators.
    //
    // This helps developers decide which duplicates should be refactored
    // first based on impact and spread across modules.
    // ------------------------------------------------------------------
    private static DynamicArray<DuplicateRefactorInsight> createDuplicateRefactorInsights(
            DynamicArray<DuplicateGroup> groups) {

        DynamicArray<DuplicateRefactorInsight> insights = new DynamicArray<>();

        for (int i = 0; i < groups.size(); i++) {

            DuplicateGroup group = groups.get(i);

            int occurrences = group.entries.size();

            // Number of different files containing this locator
            int distinctFiles = countDistinctFiles(group.entries);

            // Identify module where this locator appears most
            String topModule = findTopModule(group.entries);

            // Compute impact score
            int score = occurrences * 2 + distinctFiles;

            // Assign priority level
            String priority = score >= 10 ? "high"
                    : (score >= 6 ? "medium" : "low");

            String recommendation;

            // Generate refactor suggestion
            if (occurrences >= 4 && distinctFiles >= 3) {

                recommendation = "Extract to shared page object constant; duplicate usage spans multiple modules.";

            } else if (distinctFiles >= 2) {

                recommendation = "Move locator into reusable helper/page object to reduce repetition.";

            } else {

                recommendation = "Keep one canonical locator declaration and reference it from helpers.";
            }

            insights.add(
                    new DuplicateRefactorInsight(
                            group.locator,
                            occurrences,
                            distinctFiles,
                            topModule,
                            priority,
                            recommendation));
        }

        // Sort insights by highest priority
        return sortDuplicateRefactorInsights(insights);
    }

    // ------------------------------------------------------------------
    // Counts the number of unique files containing duplicate locators.
    // ------------------------------------------------------------------
    private static int countDistinctFiles(DynamicArray<DuplicateEntry> entries) {

        DynamicArray<String> uniqueFiles = new DynamicArray<>();

        for (int i = 0; i < entries.size(); i++) {

            String file = entries.get(i).file;

            boolean seen = false;

            for (int j = 0; j < uniqueFiles.size(); j++) {

                if (uniqueFiles.get(j).equals(file)) {
                    seen = true;
                    break;
                }
            }

            if (!seen) {
                uniqueFiles.add(file);
            }
        }

        return uniqueFiles.size();
    }

    // ------------------------------------------------------------------
    // Determines which module (folder) appears most frequently
    // among duplicate locator entries.
    //
    // This helps identify where locator duplication mainly occurs.
    // Example result:
    // pages/login
    // tests/cart
    // components/navbar
    // ------------------------------------------------------------------
    private static String findTopModule(DynamicArray<DuplicateEntry> entries) {

        // Stores module names along with occurrence counts
        DynamicArray<TokenCount> modules = new DynamicArray<>();

        // Extract module name from each file path
        for (int i = 0; i < entries.size(); i++) {

            String module = inferModule(entries.get(i).file);

            // Increase count for this module
            incrementTokenCount(modules, module);
        }

        // Determine module with the highest occurrence
        String top = "unknown";
        int max = 0;

        for (int i = 0; i < modules.size(); i++) {

            TokenCount tokenCount = modules.get(i);

            if (tokenCount.count > max) {
                max = tokenCount.count;
                top = tokenCount.token;
            }
        }

        return top;
    }

    // ------------------------------------------------------------------
    // Infers a module name from a file path.
    //
    // Example:
    // /project/src/pages/login.ts
    // → pages/login
    //
    // /tests/e2e/cart/add_item.spec.ts
    // → e2e/cart
    //
    // If no known anchor exists, it falls back to the parent folder.
    // ------------------------------------------------------------------
    private static String inferModule(String filePath) {

        // Normalize Windows paths to Unix style
        String normalized = filePath.replace('\\', '/');

        // Common project structure anchors
        String[] anchors = new String[] {
                "/src/",
                "/tests/",
                "/e2e/",
                "/pages/",
                "/components/"
        };

        // Check if file path contains any known anchor
        for (String anchor : anchors) {

            int idx = normalized.indexOf(anchor);

            if (idx >= 0) {

                // Extract portion after anchor
                String tail = normalized.substring(idx + anchor.length());

                int slash = tail.indexOf('/');

                // Return module path
                return slash >= 0
                        ? anchor.substring(1, anchor.length() - 1) + "/" + tail.substring(0, slash)
                        : anchor.substring(1, anchor.length() - 1);
            }
        }

        // Fallback logic when anchor not found
        int lastSlash = normalized.lastIndexOf('/');

        if (lastSlash > 0) {

            int prevSlash = normalized.lastIndexOf('/', lastSlash - 1);

            if (prevSlash >= 0) {

                // Return folder name
                return normalized.substring(prevSlash + 1, lastSlash);
            }

            return normalized.substring(0, lastSlash);
        }

        // Root-level file
        return "root";
    }

    // ------------------------------------------------------------------
    // Sorts duplicate refactor insights based on priority and impact.
    //
    // Sorting rules:
    // 1. High priority first
    // 2. More occurrences first
    // 3. Alphabetical locator order as tie-breaker
    // ------------------------------------------------------------------
    private static DynamicArray<DuplicateRefactorInsight> sortDuplicateRefactorInsights(
            DynamicArray<DuplicateRefactorInsight> insights) {

        DynamicArray<DuplicateRefactorInsight> sorted = new DynamicArray<>();

        // Copy elements to a new list
        for (int i = 0; i < insights.size(); i++) {
            sorted.add(insights.get(i));
        }

        // Insertion sort algorithm
        for (int i = 1; i < sorted.size(); i++) {

            DuplicateRefactorInsight current = sorted.get(i);

            int j = i - 1;

            while (j >= 0 && compareDuplicateInsight(sorted.get(j), current) > 0) {

                sorted.set(j + 1, sorted.get(j));
                j--;
            }

            sorted.set(j + 1, current);
        }

        return sorted;
    }

    // ------------------------------------------------------------------
    // Comparator for sorting duplicate locator insights.
    //
    // Comparison order:
    // 1. Priority level
    // 2. Occurrence count
    // 3. Locator string (alphabetical)
    // ------------------------------------------------------------------
    private static int compareDuplicateInsight(
            DuplicateRefactorInsight left,
            DuplicateRefactorInsight right) {

        // Compare priority rank first
        int byPriority = Integer.compare(priorityRank(left.priority), priorityRank(right.priority));

        if (byPriority != 0) {
            return byPriority;
        }

        // If same priority → compare occurrence count
        int byOccurrences = Integer.compare(right.occurrences, left.occurrences);

        if (byOccurrences != 0) {
            return byOccurrences;
        }

        // Final fallback → alphabetical locator comparison
        return left.locator.compareTo(right.locator);
    }

    // ------------------------------------------------------------------
    // Converts priority string into numeric ranking.
    //
    // Ranking system:
    // high → 0 (highest priority)
    // medium → 1
    // low → 2 (lowest priority)
    //
    // Lower number means higher priority in sorting.
    // ------------------------------------------------------------------
    private static int priorityRank(String priority) {

        if ("high".equals(priority)) {
            return 0;
        }

        if ("medium".equals(priority)) {
            return 1;
        }

        return 2;
    }

    // ------------------------------------------------------------------
    // Adds a locator occurrence to the list of locator buckets.
    //
    // If a bucket for the locator already exists → append occurrence
    // If not → create a new bucket and add it.
    //
    // This helps later in identifying duplicate locators across files.
    // ------------------------------------------------------------------
    private static void addLocatorOccurrence(
            DynamicArray<LocatorBucket> locatorOccurrences,
            String locator,
            Finding occurrence) {

        // Search existing buckets for the same locator
        for (int i = 0; i < locatorOccurrences.size(); i++) {

            LocatorBucket bucket = locatorOccurrences.get(i);

            // If locator already tracked → append occurrence
            if (bucket.locator.equals(locator)) {
                bucket.occurrences.add(occurrence);
                return;
            }
        }

        // Locator not yet tracked → create a new bucket
        LocatorBucket bucket = new LocatorBucket(locator);

        bucket.occurrences.add(occurrence);

        locatorOccurrences.add(bucket);
    }

    // ------------------------------------------------------------------
    // Increments frequency count for a token.
    //
    // Used for tracking occurrences of:
    // • function names
    // • module names
    // • identifiers
    //
    // Implemented using a simple linear list instead of a map.
    // ------------------------------------------------------------------
    private static void incrementTokenCount(DynamicArray<TokenCount> counts, String token) {

        // Look for existing token entry
        for (int i = 0; i < counts.size(); i++) {

            TokenCount tokenCount = counts.get(i);

            if (tokenCount.token.equals(token)) {

                // Token already exists → increment count
                tokenCount.count++;
                return;
            }
        }

        // Token not found → create new entry
        counts.add(new TokenCount(token));
    }

    // ------------------------------------------------------------------
    // Retrieves the frequency count of a token.
    //
    // Returns:
    // • token count if found
    // • 0 if token never appeared
    // ------------------------------------------------------------------
    private static int getTokenCount(DynamicArray<TokenCount> counts, String token) {

        for (int i = 0; i < counts.size(); i++) {

            TokenCount tokenCount = counts.get(i);

            if (tokenCount.token.equals(token)) {
                return tokenCount.count;
            }
        }

        // Token was never seen
        return 0;
    }

    // ------------------------------------------------------------------
    // Converts internal issue identifiers to user-friendly labels.
    //
    // Internal IDs are used for processing, while these labels are
    // displayed in reports or dashboards.
    // ------------------------------------------------------------------
    private static String issueLabel(String issue) {

        switch (issue) {

            case "hard_wait":
                return "Hard Wait Found";

            case "hardcoded_test_data":
                return "Test Data Hardcoding";

            case "duplicate_locator":
                return "Duplicate Locators";

            case "poor_assertion":
                return "Poor Assertions";

            case "unused_function":
                return "Unused Functions";

            case "missing_validation":
                return "Missing Validations";

            default:
                return issue;
        }
    }

    // ------------------------------------------------------------------
    // Prints a compact quality report summary to the console.
    //
    // This summary includes the total number of issues detected
    // for each category and the number of files scanned.
    // ------------------------------------------------------------------
    private static void printConsoleReport(Summary summary) {

        System.out.println("Automation Script Quality Report");
        System.out.println("--------------------------------");

        System.out.println("Hard Wait Found: " + summary.hardWaitFound);

        System.out.println("Test Data Hardcoding: " + summary.hardcodedTestData);

        System.out.println("Duplicate Locators: " + summary.duplicateLocators);

        System.out.println("Poor Assertions: " + summary.poorAssertions);

        System.out.println("Unused Functions: " + summary.unusedFunctions);

        System.out.println("Missing Validations: " + summary.missingValidations);

        System.out.println("Files Scanned: " + summary.totalFilesScanned);
    }

    // ------------------------------------------------------------------
    // Sorts findings by file path and then by line/column number.
    //
    // Purpose:
    // Ensures that the output report is stable, predictable,
    // and easy to read when displayed in console or exported files.
    //
    // Sorting Order:
    // 1. File path
    // 2. Line number
    // 3. Column number
    //
    // Implementation:
    // Uses an insertion sort to keep the implementation lightweight
    // and independent from Java Collections.
    // ------------------------------------------------------------------
    private static DynamicArray<Finding> sortFindings(DynamicArray<Finding> findings) {

        // Create a copy of the original findings list
        DynamicArray<Finding> sorted = new DynamicArray<>();

        for (int i = 0; i < findings.size(); i++) {
            sorted.add(findings.get(i));
        }

        // Insertion sort algorithm
        for (int i = 1; i < sorted.size(); i++) {

            Finding current = sorted.get(i);

            int j = i - 1;

            // Move elements that are greater than current
            // to one position ahead
            while (j >= 0 && compareFinding(sorted.get(j), current) > 0) {

                sorted.set(j + 1, sorted.get(j));
                j--;
            }

            sorted.set(j + 1, current);
        }

        return sorted;
    }

    // ------------------------------------------------------------------
    // Comparator used to determine ordering between two findings.
    //
    // Comparison priority:
    // 1. File path (alphabetical)
    // 2. Line number
    // 3. Column number
    //
    // This guarantees deterministic ordering of issues in reports.
    // ------------------------------------------------------------------
    private static int compareFinding(Finding left, Finding right) {

        // Compare file paths first
        int byFile = left.file.compareTo(right.file);

        if (byFile != 0) {
            return byFile;
        }

        // If same file → compare line numbers
        int byLine = Integer.compare(left.line, right.line);

        if (byLine != 0) {
            return byLine;
        }

        // If same line → compare column numbers
        return Integer.compare(left.column, right.column);
    }

    // ------------------------------------------------------------------
    // Compacts long file paths for cleaner report output.
    //
    // Example:
    //
    // Original Path:
    // /Users/dev/project/src/tests/login/login.spec.ts
    //
    // Output Path:
    // /src/tests/login/login.spec.ts
    //
    // This keeps reports readable while preserving useful context.
    // ------------------------------------------------------------------
    private static String compactPath(String filePath) {

        // Normalize Windows paths to Unix-style
        String normalized = filePath.replace('\\', '/');

        // Prefer showing path starting from /src/
        int srcIndex = normalized.indexOf("/src/");

        if (srcIndex >= 0) {
            return normalized.substring(srcIndex);
        }

        // Fallback → return full path
        return normalized;
    }

    // ------------------------------------------------------------------
    // Formats the location of an issue in a standard format.
    //
    // Output format:
    // filePath:line:column
    //
    // Example:
    // /src/tests/login.spec.ts:45:12
    //
    // This format is used consistently across:
    // • Console reports
    // • TXT reports
    // • Markdown reports
    // ------------------------------------------------------------------
    private static String formatLocation(Finding finding) {

        return compactPath(finding.file)
                + ":" + finding.line
                + ":" + finding.column;
    }

    // ------------------------------------------------------------------
    // Sorts locator buckets alphabetically by locator string.
    //
    // Purpose:
    // Makes duplicate locator reporting deterministic and easier
    // to review in reports.
    //
    // Example:
    //
    // "#login-button"
    // "#signup-button"
    // ".cart-item"
    // ------------------------------------------------------------------
    private static DynamicArray<LocatorBucket> sortLocatorBuckets(
            DynamicArray<LocatorBucket> buckets) {

        // Copy original list
        DynamicArray<LocatorBucket> sorted = new DynamicArray<>();

        for (int i = 0; i < buckets.size(); i++) {
            sorted.add(buckets.get(i));
        }

        // Insertion sort
        for (int i = 1; i < sorted.size(); i++) {

            LocatorBucket current = sorted.get(i);

            int j = i - 1;

            while (j >= 0 &&
                    sorted.get(j).locator.compareTo(current.locator) > 0) {

                sorted.set(j + 1, sorted.get(j));
                j--;
            }

            sorted.set(j + 1, current);
        }

        return sorted;
    }

    // ------------------------------------------------------------------
    // Prints a detailed console report for each issue type.
    //
    // Features:
    // • Shows findings grouped by issue category
    // • Displays file:line:column location
    // • Limits output per issue type if maxPerIssue is set
    //
    // This prevents console flooding when large projects
    // contain thousands of findings.
    // ------------------------------------------------------------------
    private static void printDetailedConsoleFindings(FindingsByIssue findings, int maxPerIssue) {

        System.out.println("\nDetailed Findings (file:line:column)");
        System.out.println("-----------------------------");

        // Iterate through issues in a predefined order
        for (String issue : ISSUE_ORDER) {

            // Sort findings for stable output
            DynamicArray<Finding> issueFindings = sortFindings(findings.get(issue));

            System.out.println("\n" + issueLabel(issue) + ":");

            // If no findings exist for the issue
            if (issueFindings.isEmpty()) {
                System.out.println("  No findings.");
                continue;
            }

            // Determine how many results to print
            int limit = issueFindings.size();

            if (maxPerIssue > 0 && limit > maxPerIssue) {
                limit = maxPerIssue;
            }

            // Print each finding
            for (int i = 0; i < limit; i++) {

                Finding f = issueFindings.get(i);

                System.out.println(
                        "  " + formatLocation(f) + " | " + f.detail);
            }
        }
    }

    // ------------------------------------------------------------------
    // Detects whether a file is likely a test file.
    //
    // Uses common test naming conventions such as:
    // • *.spec.*
    // • *.test.*
    // • test_*
    // • /tests/
    // • /__tests__/
    // • /e2e/
    //
    // This heuristic helps the tool identify which files should
    // be analyzed for automation testing patterns.
    // ------------------------------------------------------------------
    private static boolean isTestFile(Path path) {

        String lowered = path.toString().toLowerCase();

        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase();

        // Check filename patterns
        if (name.contains(".spec.")
                || name.contains(".test.")
                || name.startsWith("test_")
                || lowered.contains("/tests/")
                || lowered.contains("/__tests__/")
                || lowered.contains("/e2e/")) {
            return true;
        }

        // Normalize Windows paths
        String normalized = lowered.replace('\\', '/');

        return normalized.contains("/tests/")
                || normalized.contains("/__tests__/")
                || normalized.contains("/e2e/");
    }

    // ------------------------------------------------------------------
    // Detects which test files may be impacted when specific
    // functions are modified.
    //
    // This feature helps engineers quickly understand:
    // "Which tests might break if this function changes?"
    //
    // Inputs:
    // • files → all project files
    // • changedFunctions → list of modified functions
    // • changedFile → file where functions were modified
    //
    // Output:
    // • list of FunctionImpact objects containing references
    // to affected tests
    // ------------------------------------------------------------------
    private static DynamicArray<FunctionImpact> findImpactedTests(
            DynamicArray<Path> files,
            DynamicArray<String> changedFunctions,
            String changedFile) {

        // Initialize impact list
        DynamicArray<FunctionImpact> impacted = new DynamicArray<>();

        // Create impact object for each changed function
        for (int i = 0; i < changedFunctions.size(); i++) {

            String fn = changedFunctions.get(i);

            if (findFunctionImpact(impacted, fn) == null) {
                impacted.add(new FunctionImpact(fn));
            }
        }

        // ------------------------------------------------------------------
        // Load text of changed file (if provided)
        // ------------------------------------------------------------------
        String changedFileText = "";

        Path changedFilePath = null;

        if (changedFile != null && !changedFile.isEmpty()) {

            changedFilePath = Paths.get(changedFile);

            if (Files.exists(changedFilePath)) {

                try {
                    changedFileText = Files.readString(
                            changedFilePath,
                            StandardCharsets.UTF_8);
                } catch (IOException ignored) {

                    // If reading fails, continue safely
                    changedFileText = "";
                }
            }
        }

        // ------------------------------------------------------------------
        // Build regex patterns for each changed function
        // Using whole-word matching to avoid partial matches
        // ------------------------------------------------------------------
        DynamicArray<TokenPattern> tokenPatterns = new DynamicArray<>();

        for (int i = 0; i < changedFunctions.size(); i++) {

            String fn = changedFunctions.get(i);

            if (!containsTokenPattern(tokenPatterns, fn)) {

                tokenPatterns.add(
                        new TokenPattern(
                                fn,
                                Pattern.compile("\\b" + Pattern.quote(fn) + "\\b")));
            }
        }

        // ------------------------------------------------------------------
        // Scan project files to detect impacted test references
        // ------------------------------------------------------------------
        for (int i = 0; i < files.size(); i++) {

            Path path = files.get(i);

            DynamicArray<String> lines = readLines(path);

            // Skip empty or non-test files
            if (lines.isEmpty() || !isTestFile(path)) {
                continue;
            }

            // Scan file line-by-line
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {

                String line = lines.get(lineIndex);

                for (int t = 0; t < tokenPatterns.size(); t++) {

                    TokenPattern tokenPattern = tokenPatterns.get(t);

                    Matcher m = tokenPattern.pattern.matcher(line);

                    if (m.find()) {

                        FunctionImpact impact = findFunctionImpact(
                                impacted,
                                tokenPattern.functionName);

                        if (impact != null) {

                            // Record impacted test location
                            impact.references.add(
                                    new Finding(
                                            path.toString(),
                                            lineIndex + 1,
                                            m.start() + 1,
                                            "impacted_test",
                                            line.trim()));
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Verification step:
        // If a changed function isn't found inside the changed file,
        // add a warning note for manual verification.
        // ------------------------------------------------------------------
        if (!changedFileText.isEmpty() && changedFilePath != null) {

            for (int i = 0; i < tokenPatterns.size(); i++) {

                TokenPattern tokenPattern = tokenPatterns.get(i);

                String fn = tokenPattern.functionName;

                if (!tokenPattern.pattern.matcher(changedFileText).find()) {

                    FunctionImpact impact = findFunctionImpact(impacted, fn);

                    if (impact != null) {

                        impact.references.addAt(
                                0,
                                new Finding(
                                        changedFilePath.toString(),
                                        1,
                                        1,
                                        "impacted_test_note",
                                        "Note: '" + fn +
                                                "' not found in changed file text; verify function name."));
                    }
                }
            }
        }

        return impacted;
    }

    // ------------------------------------------------------------------
    // Searches for a FunctionImpact object that matches a given function.
    //
    // Purpose:
    // Each changed function has an associated FunctionImpact object
    // that tracks which tests reference that function.
    //
    // If the function already exists in the impacted list → return it.
    // If not → return null.
    // ------------------------------------------------------------------
    private static FunctionImpact findFunctionImpact(
            DynamicArray<FunctionImpact> impacted,
            String functionName) {

        for (int i = 0; i < impacted.size(); i++) {

            FunctionImpact impact = impacted.get(i);

            if (impact.functionName.equals(functionName)) {
                return impact;
            }
        }

        // Function not found in the impacted list
        return null;
    }

    // ------------------------------------------------------------------
    // Checks whether a compiled regex pattern already exists for a function.
    //
    // Purpose:
    // When scanning files for changed function references, a regex pattern
    // is compiled for each function name.
    //
    // This method prevents duplicate regex patterns from being created.
    // ------------------------------------------------------------------
    private static boolean containsTokenPattern(
            DynamicArray<TokenPattern> tokenPatterns,
            String functionName) {

        for (int i = 0; i < tokenPatterns.size(); i++) {

            TokenPattern tokenPattern = tokenPatterns.get(i);

            if (tokenPattern.functionName.equals(functionName)) {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Prints a console report showing which tests are impacted
    // by changes to specific functions.
    //
    // Output example:
    //
    // Changed Function: loginUser
    // tests/login.spec.js:45:10 | loginUser()
    // tests/cart.spec.js:88:5 | loginUser()
    //
    // Parameters:
    // impactedTests → list of function impact groups
    // maxPerFunction → optional limit to prevent console overflow
    // ------------------------------------------------------------------
    private static void printImpactedTests(
            DynamicArray<FunctionImpact> impactedTests,
            int maxPerFunction) {

        System.out.println("\nImpacted Tests by Changed Function");
        System.out.println("---------------------------------");

        // Iterate over each changed function
        for (int i = 0; i < impactedTests.size(); i++) {

            FunctionImpact impact = impactedTests.get(i);

            String fnName = impact.functionName;

            DynamicArray<Finding> refs = impact.references;

            System.out.println("\nChanged Function: " + fnName);

            // If no references were detected
            if (refs.isEmpty()) {
                System.out.println("  No impacted test references found.");
                continue;
            }

            // Sort references for stable output
            DynamicArray<Finding> refsSorted = sortFindings(refs);

            int limit = refsSorted.size();

            // Apply optional limit
            if (maxPerFunction > 0 && limit > maxPerFunction) {
                limit = maxPerFunction;
            }

            // Print impacted test locations
            for (int j = 0; j < limit; j++) {

                Finding r = refsSorted.get(j);

                System.out.println(
                        "  " + formatLocation(r) + " | " + r.detail);
            }
        }
    }

    // ------------------------------------------------------------------
    // Generates a complete plain-text report for exporting results.
    //
    // The report contains:
    //
    // 1. Tool header
    // 2. Issue summary statistics
    // 3. Detailed findings grouped by issue type
    //
    // This output can be saved to:
    // • TXT reports
    // • CI logs
    // • GitHub artifacts
    // ------------------------------------------------------------------
    private static String createDetailedTextReport(
            Summary summary,
            FindingsByIssue findings) {

        // Use a dynamic list to build report line-by-line
        DynamicArray<String> out = new DynamicArray<>();

        // ------------------------------------------------------------------
        // Report Header
        // ------------------------------------------------------------------
        out.add("Automation Script Quality Report");
        out.add("================================");
        out.add("");

        // ------------------------------------------------------------------
        // Summary Section
        // ------------------------------------------------------------------
        out.add("Summary");
        out.add("-------");

        out.add("Hard Wait Found: " + summary.hardWaitFound);
        out.add("Test Data Hardcoding: " + summary.hardcodedTestData);
        out.add("Duplicate Locators: " + summary.duplicateLocators);
        out.add("Poor Assertions: " + summary.poorAssertions);
        out.add("Unused Functions: " + summary.unusedFunctions);
        out.add("Missing Validations: " + summary.missingValidations);
        out.add("Files Scanned: " + summary.totalFilesScanned);

        out.add("");

        // ------------------------------------------------------------------
        // Detailed Findings Section
        // ------------------------------------------------------------------
        for (String issue : ISSUE_ORDER) {

            DynamicArray<Finding> issueFindings = sortFindings(findings.get(issue));

            String label = issueLabel(issue);

            out.add(label);

            // Create underline using same length as label
            out.add(repeat("-", label.length()));

            // If no issues found
            if (issueFindings.isEmpty()) {

                out.add("No findings.");
                out.add("");

                continue;
            }

            // Add each finding entry
            for (int i = 0; i < issueFindings.size(); i++) {

                Finding f = issueFindings.get(i);

                out.add(
                        formatLocation(f) + " | " + f.detail);
            }

            out.add("");
        }

        // Convert list into single string separated by newlines
        return joinStrings(out, "\n");
    }

    // ------------------------------------------------------------------
    // Generates a Markdown-formatted report containing the results
    // of the automation script quality analysis.
    //
    // The report includes:
    // 1. Title header
    // 2. Summary statistics
    // 3. Detailed findings grouped by issue type
    //
    // This output is useful for:
    // • GitHub README reports
    // • CI pipeline artifacts
    // • Documentation dashboards
    // ------------------------------------------------------------------
    private static String createMarkdownReport(Summary summary, FindingsByIssue findings) {

        // Dynamic list used to build the report line-by-line
        DynamicArray<String> out = new DynamicArray<>();

        // ------------------------------------------------------------------
        // Report Header
        // ------------------------------------------------------------------
        out.add("# Automation Script Quality Report");
        out.add("");

        // ------------------------------------------------------------------
        // Summary Section
        // ------------------------------------------------------------------
        out.add("## Summary");
        out.add("");

        out.add("- Hard Wait Found: **" + summary.hardWaitFound + "**");
        out.add("- Test Data Hardcoding: **" + summary.hardcodedTestData + "**");
        out.add("- Duplicate Locators: **" + summary.duplicateLocators + "**");
        out.add("- Poor Assertions: **" + summary.poorAssertions + "**");
        out.add("- Unused Functions: **" + summary.unusedFunctions + "**");
        out.add("- Missing Validations: **" + summary.missingValidations + "**");
        out.add("- Files Scanned: **" + summary.totalFilesScanned + "**");

        out.add("");

        // ------------------------------------------------------------------
        // Detailed Findings Section
        // ------------------------------------------------------------------
        for (String issue : ISSUE_ORDER) {

            // Retrieve and sort findings for consistent output
            DynamicArray<Finding> issueFindings = sortFindings(findings.get(issue));

            out.add("## " + issueLabel(issue));
            out.add("");

            // If no findings exist for this issue
            if (issueFindings.isEmpty()) {

                out.add("No findings.");
                out.add("");

                continue;
            }

            // Add each finding as a markdown list item
            for (int i = 0; i < issueFindings.size(); i++) {

                Finding f = issueFindings.get(i);

                out.add(
                        "- `" + formatLocation(f) + "` - `" + f.detail + "`");
            }

            out.add("");
        }

        // Convert list of lines into a single string
        return joinStrings(out, "\n");
    }

    // ------------------------------------------------------------------
    // Utility method that repeats a string multiple times.
    //
    // Example:
    // repeat("-", 5) → "-----"
    //
    // Commonly used for formatting text reports or separators.
    // ------------------------------------------------------------------
    private static String repeat(String value, int times) {

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < times; i++) {
            sb.append(value);
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Joins all strings in a DynamicArray using a specified delimiter.
    //
    // Example:
    // values = ["A","B","C"]
    // delimiter = ","
    // Result → "A,B,C"
    //
    // Used to construct large report outputs from line collections.
    // ------------------------------------------------------------------
    private static String joinStrings(
            DynamicArray<String> values,
            String delimiter) {

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < values.size(); i++) {

            // Add delimiter between elements
            if (i > 0) {
                sb.append(delimiter);
            }

            sb.append(values.get(i));
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Escapes special characters to make strings safe for JSON output.
    //
    // JSON requires certain characters to be escaped such as:
    // \ → \\
    // " → \"
    // newline → \n
    //
    // This method manually serializes strings so that the tool
    // does not rely on external JSON libraries.
    // ------------------------------------------------------------------
    private static String jsonEscape(String value) {

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            switch (c) {

                case '\\':
                    sb.append("\\\\");
                    break;

                case '"':
                    sb.append("\\\"");
                    break;

                case '\b':
                    sb.append("\\b");
                    break;

                case '\f':
                    sb.append("\\f");
                    break;

                case '\n':
                    sb.append("\\n");
                    break;

                case '\r':
                    sb.append("\\r");
                    break;

                case '\t':
                    sb.append("\\t");
                    break;

                default:

                    // Escape control characters
                    if (c < 0x20) {

                        sb.append(
                                String.format("\\u%04x", (int) c));

                    } else {

                        sb.append(c);
                    }
            }
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Wraps a string value in double quotes after escaping JSON characters.
    //
    // Purpose:
    // JSON requires string values to be enclosed in double quotes.
    // Before quoting, the value is passed through jsonEscape()
    // to prevent invalid JSON caused by special characters.
    //
    // Example:
    // value = Hello "User"
    // Output → "Hello \"User\""
    // ------------------------------------------------------------------
    private static String quote(String value) {
        return "\"" + jsonEscape(value) + "\"";
    }

    // ------------------------------------------------------------------
    // Converts a Finding object into a JSON object string.
    //
    // Each Finding represents an issue detected in a file.
    //
    // Example Output:
    // {
    // "file": "src/tests/login.spec.js",
    // "line": 45,
    // "column": 10,
    // "issue": "duplicate_locator",
    // "detail": "Locator '#login-btn' duplicated"
    // }
    // ------------------------------------------------------------------
    private static String findingToJson(Finding f) {

        return "{"
                + "\"file\":" + quote(f.file) + ","
                + "\"line\":" + f.line + ","
                + "\"column\":" + f.column + ","
                + "\"issue\":" + quote(f.issue) + ","
                + "\"detail\":" + quote(f.detail)
                + "}";
    }

    // ------------------------------------------------------------------
    // Serializes the Summary object into a JSON representation.
    //
    // The summary contains aggregated counters for all detected issues.
    //
    // Example Output:
    // {
    // "hard_wait_found": 3,
    // "hardcoded_test_data": 2,
    // "duplicate_locators": 5,
    // "poor_assertions": 1,
    // "unused_functions": 4,
    // "missing_validations": 2,
    // "total_files_scanned": 25
    // }
    // ------------------------------------------------------------------
    private static String summaryToJson(Summary summary) {

        return "{"
                + "\"hard_wait_found\":" + summary.hardWaitFound + ","
                + "\"hardcoded_test_data\":" + summary.hardcodedTestData + ","
                + "\"duplicate_locators\":" + summary.duplicateLocators + ","
                + "\"poor_assertions\":" + summary.poorAssertions + ","
                + "\"unused_functions\":" + summary.unusedFunctions + ","
                + "\"missing_validations\":" + summary.missingValidations + ","
                + "\"total_files_scanned\":" + summary.totalFilesScanned
                + "}";
    }

    // ------------------------------------------------------------------
    // Serializes findings grouped by issue type into JSON.
    //
    // Structure Example:
    // {
    // "hard_wait":[ {...}, {...} ],
    // "duplicate_locator":[ {...} ],
    // "unused_function":[ {...} ]
    // }
    //
    // Each issue category contains an array of Finding objects.
    // ------------------------------------------------------------------
    private static String findingsToJson(FindingsByIssue findings) {

        DynamicArray<String> entries = new DynamicArray<>();

        for (String issue : ISSUE_ORDER) {

            DynamicArray<String> findingRows = new DynamicArray<>();

            DynamicArray<Finding> issueFindings = findings.get(issue);

            // Convert each finding to JSON
            for (int i = 0; i < issueFindings.size(); i++) {

                findingRows.add(
                        findingToJson(issueFindings.get(i)));
            }

            entries.add(
                    quote(issue) + ":[" + joinStrings(findingRows, ",") + "]");
        }

        return "{" + joinStrings(entries, ",") + "}";
    }

    // ------------------------------------------------------------------
    // Serializes duplicate locator groups into JSON format.
    //
    // DuplicateGroup represents multiple occurrences of the same locator
    // across different files.
    //
    // Example Output:
    // {
    // "#login-button":[
    // {"file":"login.spec.js","line":"45","code":"page.click('#login-button')"},
    // {"file":"signup.spec.js","line":"22","code":"page.click('#login-button')"}
    // ]
    // }
    // ------------------------------------------------------------------
    private static String duplicateGroupsToJson(
            DynamicArray<DuplicateGroup> groups) {

        DynamicArray<String> groupRows = new DynamicArray<>();

        for (int i = 0; i < groups.size(); i++) {

            DuplicateGroup group = groups.get(i);

            DynamicArray<String> entryRows = new DynamicArray<>();

            for (int j = 0; j < group.entries.size(); j++) {

                DuplicateEntry row = group.entries.get(j);

                entryRows.add("{"
                        + "\"file\":" + quote(row.file) + ","
                        + "\"line\":" + quote(row.line) + ","
                        + "\"code\":" + quote(row.code)
                        + "}");
            }

            groupRows.add(
                    quote(group.locator)
                            + ":["
                            + joinStrings(entryRows, ",")
                            + "]");
        }

        return "{" + joinStrings(groupRows, ",") + "}";
    }

    // ------------------------------------------------------------------
    // Serializes ranked duplicate locator refactor insights into JSON.
    //
    // These insights help developers identify high-priority refactoring
    // opportunities for improving locator reuse and maintainability.
    //
    // Example Output:
    // [
    // {
    // "locator": "#login-btn",
    // "occurrences": 8,
    // "distinct_files": 4,
    // "top_module": "login",
    // "priority": "high",
    // "recommendation": "Move locator to shared Page Object"
    // }
    // ]
    // ------------------------------------------------------------------
    private static String duplicateRefactorInsightsToJson(
            DynamicArray<DuplicateRefactorInsight> insights) {

        DynamicArray<String> rows = new DynamicArray<>();

        for (int i = 0; i < insights.size(); i++) {

            DuplicateRefactorInsight insight = insights.get(i);

            rows.add("{"
                    + "\"locator\":" + quote(insight.locator) + ","
                    + "\"occurrences\":" + insight.occurrences + ","
                    + "\"distinct_files\":" + insight.distinctFiles + ","
                    + "\"top_module\":" + quote(insight.topModule) + ","
                    + "\"priority\":" + quote(insight.priority) + ","
                    + "\"recommendation\":" + quote(insight.recommendation)
                    + "}");
        }

        return "[" + joinStrings(rows, ",") + "]";
    }

    // ------------------------------------------------------------------
    // Serializes impacted test references grouped by changed function
    // into a JSON object.
    //
    // Structure Example:
    //
    // {
    // "loginUser":[ {...}, {...} ],
    // "createOrder":[ {...} ]
    // }
    //
    // Each key is a changed function name and the value is an array
    // of Finding objects representing test references.
    // ------------------------------------------------------------------
    private static String impactedTestsToJson(DynamicArray<FunctionImpact> impactedTests) {

        DynamicArray<String> rows = new DynamicArray<>();

        for (int i = 0; i < impactedTests.size(); i++) {

            FunctionImpact impact = impactedTests.get(i);

            DynamicArray<String> refs = new DynamicArray<>();

            // Convert each reference finding to JSON
            for (int j = 0; j < impact.references.size(); j++) {
                refs.add(
                        findingToJson(impact.references.get(j)));
            }

            rows.add(
                    quote(impact.functionName) + ":[" + joinStrings(refs, ",") + "]");
        }

        return "{" + joinStrings(rows, ",") + "}";
    }

    // ------------------------------------------------------------------
    // Combines all report sections into a single JSON payload.
    //
    // Sections included:
    //
    // 1. Summary metrics
    // 2. All findings grouped by issue type
    // 3. Duplicate locator groups
    // 4. Refactor intelligence suggestions
    // 5. Impacted tests based on changed functions
    //
    // This payload can be consumed by dashboards, CI tools,
    // or reporting systems.
    // ------------------------------------------------------------------
    private static String createJsonPayload(
            Summary summary,
            FindingsByIssue findings,
            DynamicArray<DuplicateGroup> duplicateGroups,
            DynamicArray<FunctionImpact> impactedTests,
            DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights) {

        return "{"
                + "\"summary\":" + summaryToJson(summary) + ","
                + "\"findings\":" + findingsToJson(findings) + ","
                + "\"duplicate_locator_groups\":" + duplicateGroupsToJson(duplicateGroups) + ","
                + "\"duplicate_refactor_intelligence\":" + duplicateRefactorInsightsToJson(duplicateRefactorInsights)
                + ","
                + "\"impacted_tests\":" + impactedTestsToJson(impactedTests)
                + "}";
    }

    // ------------------------------------------------------------------
    // Parses command-line arguments passed to the program.
    //
    // Handles both:
    // • flags with values (--json report.json)
    // • flags without values (--show-lines)
    // • positional arguments (target files or directories)
    //
    // Example CLI:
    //
    // java AutomationQualityChecker --json report.json src/
    // ------------------------------------------------------------------
    private static CliArgs parseArgs(String[] argv) {

        CliArgs args = new CliArgs();

        for (int i = 0; i < argv.length; i++) {

            String arg = argv[i];

            switch (arg) {

                case "--extensions":
                    args.extensions = requireValue(argv, ++i, arg);
                    break;

                case "--json":
                    args.jsonOutput = requireValue(argv, ++i, arg);
                    break;

                case "--txt":
                    args.txtOutput = requireValue(argv, ++i, arg);
                    break;

                case "--md":
                    args.mdOutput = requireValue(argv, ++i, arg);
                    break;

                case "--show-lines":
                    args.showLines = true;
                    break;

                case "--max-lines-per-issue":
                    args.maxLinesPerIssue = Integer.parseInt(requireValue(argv, ++i, arg));
                    break;

                case "--changed-function":
                    args.changedFunctions.add(
                            requireValue(argv, ++i, arg));
                    break;

                case "--changed-file":
                    args.changedFile = requireValue(argv, ++i, arg);
                    break;

                case "--max-impacted-per-function":
                    args.maxImpactedPerFunction = Integer.parseInt(requireValue(argv, ++i, arg));
                    break;

                case "--hard-wait-preset":
                    args.hardWaitPreset = requireValue(argv, ++i, arg);
                    break;

                case "--selenium-assertion-as-hard-wait":
                    args.seleniumAssertionAsHardWait = true;
                    break;

                case "--enable-issues":
                    args.enableIssues = requireValue(argv, ++i, arg);
                    break;

                case "--automation-language":
                    args.automationLanguage = requireValue(argv, ++i, arg);
                    break;

                default:

                    // Unknown flag handling
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException(
                                "Unknown argument: " + arg);
                    }

                    // Otherwise treat as scan target
                    args.targets.add(arg);
            }
        }

        // Ensure at least one scan target exists
        if (args.targets.isEmpty()) {

            throw new IllegalArgumentException(
                    "At least one target file/directory is required.");
        }

        return args;
    }

    // ------------------------------------------------------------------
    // Ensures CLI options that require a value actually have one.
    //
    // Example:
    // --json report.json
    //
    // If the value is missing → throw an error.
    // ------------------------------------------------------------------
    private static String requireValue(
            String[] argv,
            int index,
            String optionName) {

        if (index >= argv.length) {

            throw new IllegalArgumentException(
                    "Missing value for " + optionName);
        }

        return argv[index];
    }

    // ------------------------------------------------------------------
    // Converts extension CSV input into a normalized set.
    //
    // Example input:
    // ".js,.ts,py"
    //
    // Output:
    // {".js",".ts",".py"}
    //
    // Ensures extensions:
    // • start with "."
    // • are lowercase
    // • are unique
    // ------------------------------------------------------------------
    private static Set<String> parseExtensions(String value) {

        LinkedHashSet<String> extensions = new LinkedHashSet<>();

        String[] split = value.split(",");

        for (String raw : split) {

            String ext = raw.trim();

            if (ext.isEmpty()) {
                continue;
            }

            if (!ext.startsWith(".")) {
                ext = "." + ext;
            }

            extensions.add(ext.toLowerCase());
        }

        // If user provided nothing → fallback to defaults
        if (extensions.isEmpty()) {
            extensions.addAll(SUPPORTED_EXTENSIONS);
        }

        return extensions;
    }

    // ------------------------------------------------------------------
    // Parses --enable-issues CSV into a set of issue IDs.
    // Empty or null input means all issues are enabled.
    // ------------------------------------------------------------------
    private static java.util.Set<String> parseEnableIssues(String value) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) {
            return set; // empty = all enabled
        }
        for (String s : value.split(",")) {
            String id = s.trim();
            if (!id.isEmpty()) {
                set.add(id);
            }
        }
        return set;
    }

    private static boolean isIssueEnabled(java.util.Set<String> enabled, String issueId) {
        return enabled.isEmpty() || enabled.contains(issueId);
    }

    // ------------------------------------------------------------------
    // Prints CLI usage instructions to the terminal.
    //
    // Displayed when:
    // • invalid arguments are supplied
    // • user requests help
    // ------------------------------------------------------------------
    private static void printUsage() {

        System.err.println("Usage: java AutomationQualityChecker [options] <targets...>");
        System.err.println("Options:");

        System.err.println("  --extensions <.js,.ts,...>          Comma-separated file extensions to scan.");
        System.err.println("  --json <output.json>                Optional output JSON file path.");
        System.err.println("  --txt <output.txt>                  Optional output TXT file path.");
        System.err.println("  --md <output.md>                    Optional output Markdown file path.");

        System.err.println("  --show-lines                        Print file:line:column findings in terminal.");
        System.err.println("  --max-lines-per-issue <n>           Limit terminal findings per issue.");

        System.err
                .println("  --changed-function <name>           Changed function to find impacted tests. Repeatable.");
        System.err.println("  --changed-file <path>               Optional changed file path for function validation.");
        System.err.println("  --max-impacted-per-function <n>     Limit impacted test lines per changed function.");
        System.err.println("  --hard-wait-preset <name>           Hard wait preset (for example: selenium).");
        System.err.println("  --selenium-assertion-as-hard-wait   In selenium preset, treat assertions as hard wait.");
        System.err.println("  --enable-issues <csv>               Comma-separated issue types (empty = all).");
        System.err.println("  --automation-language <name>        Language for patterns (all, java, javascript_playwright, javascript_cypress, python).");
    }

    // ------------------------------------------------------------------
    // Application entry point.
    //
    // Workflow:
    //
    // 1. Parse command-line arguments
    // 2. Collect files to scan
    // 3. Run static analysis
    // 4. Print console report
    // 5. Optionally output JSON/TXT/Markdown reports
    // ------------------------------------------------------------------
    public static void main(String[] argv) {

        CliArgs args;

        // Parse CLI arguments
        try {

            args = parseArgs(argv);

        } catch (Exception e) {

            System.err.println(e.getMessage());

            printUsage();

            System.exit(2);
            return;
        }

        // Determine file extensions to scan
        Set<String> extensions = parseExtensions(args.extensions);

        // Collect files from target directories
        DynamicArray<Path> files = collectFiles(args.targets, extensions);

        // Run static analysis
        BuildResult result = buildReport(files, args);

        // Print summary to console
        printConsoleReport(result.summary);

        // Optionally print detailed findings
        if (args.showLines) {

            printDetailedConsoleFindings(
                    result.findings,
                    args.maxLinesPerIssue);
        }

        // ------------------------------------------------------------------
        // Impact analysis for changed functions
        // ------------------------------------------------------------------
        DynamicArray<FunctionImpact> impactedTests = new DynamicArray<>();

        if (!args.changedFunctions.isEmpty()) {

            impactedTests = findImpactedTests(
                    files,
                    args.changedFunctions,
                    args.changedFile);

            printImpactedTests(
                    impactedTests,
                    args.maxImpactedPerFunction);
        }

        // ------------------------------------------------------------------
        // Write report files
        // ------------------------------------------------------------------
        try {

            if (args.jsonOutput != null) {

                String payload = createJsonPayload(
                        result.summary,
                        result.findings,
                        result.duplicateGroups,
                        impactedTests,
                        result.duplicateRefactorInsights);

                Files.writeString(
                        Paths.get(args.jsonOutput),
                        payload,
                        StandardCharsets.UTF_8);

                System.out.println("\nDetailed JSON report written to: " + args.jsonOutput);
            }

            if (args.txtOutput != null) {

                String txt = createDetailedTextReport(
                        result.summary,
                        result.findings);

                Files.writeString(
                        Paths.get(args.txtOutput),
                        txt,
                        StandardCharsets.UTF_8);

                System.out.println("Detailed TXT report written to: " + args.txtOutput);
            }

            if (args.mdOutput != null) {

                String md = createMarkdownReport(
                        result.summary,
                        result.findings);

                Files.writeString(
                        Paths.get(args.mdOutput),
                        md,
                        StandardCharsets.UTF_8);

                System.out.println("Detailed Markdown report written to: " + args.mdOutput);
            }

        } catch (IOException e) {

            System.err.println(
                    "Failed to write output file: " + e.getMessage());

            System.exit(1);
        }
    }
}
