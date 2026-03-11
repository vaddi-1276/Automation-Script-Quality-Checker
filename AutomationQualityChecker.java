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

public class AutomationQualityChecker {
    // Default file types scanned when no custom extensions are provided.
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(".js", ".ts", ".jsx", ".tsx", ".py");
    // Fixed issue ordering to keep console/TXT/MD/JSON outputs consistent.
    private static final String[] ISSUE_ORDER = {
            "hard_wait",
            "hardcoded_test_data",
            "duplicate_locator",
            "poor_assertion",
            "unused_function",
            "missing_validation"
    };

    // Lightweight custom list used throughout the file to avoid external dependencies.
    private static class DynamicArray<T> {
        private Object[] values = new Object[16];
        private int size = 0;

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void add(T value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

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

        void addAll(DynamicArray<T> other) {
            for (int i = 0; i < other.size(); i++) {
                add(other.get(i));
            }
        }

        @SuppressWarnings("unchecked")
        T get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }
            return (T) values[index];
        }

        void set(int index, T value) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
            }
            values[index] = value;
        }

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

    // Canonical record for any detector hit.
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

    // Parsed locator occurrence from one source line.
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

    // Function declaration candidate extracted from source text.
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

    // Aggregated result bundle returned by the report builder.
    private static class BuildResult {
        final Summary summary;
        final FindingsByIssue findings;
        final DynamicArray<DuplicateGroup> duplicateGroups;
        final DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights;

        BuildResult(
                Summary summary,
                FindingsByIssue findings,
                DynamicArray<DuplicateGroup> duplicateGroups,
                DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights
        ) {
            this.summary = summary;
            this.findings = findings;
            this.duplicateGroups = duplicateGroups;
            this.duplicateRefactorInsights = duplicateRefactorInsights;
        }
    }

    // Summary counters shown in reports.
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
                int totalFilesScanned
        ) {
            this.hardWaitFound = hardWaitFound;
            this.hardcodedTestData = hardcodedTestData;
            this.duplicateLocators = duplicateLocators;
            this.poorAssertions = poorAssertions;
            this.unusedFunctions = unusedFunctions;
            this.missingValidations = missingValidations;
            this.totalFilesScanned = totalFilesScanned;
        }
    }

    // Buckets findings by issue type for reporting and serialization.
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

    // Tracks all occurrences for one locator string.
    private static class LocatorBucket {
        final String locator;
        final DynamicArray<Finding> occurrences = new DynamicArray<>();

        LocatorBucket(String locator) {
            this.locator = locator;
        }
    }

    // Mutable token frequency holder for simple usage counting.
    private static class TokenCount {
        final String token;
        int count;

        TokenCount(String token) {
            this.token = token;
            this.count = 1;
        }
    }
    // Entry used in grouped duplicate-locator JSON output.
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

    // Group of duplicate locator occurrences under one locator key.
    private static class DuplicateGroup {
        final String locator;
        final DynamicArray<DuplicateEntry> entries = new DynamicArray<>();

        DuplicateGroup(String locator) {
            this.locator = locator;
        }
    }

    // Ranked refactor opportunity derived from duplicate locator clusters.
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
                String recommendation
        ) {
            this.locator = locator;
            this.occurrences = occurrences;
            this.distinctFiles = distinctFiles;
            this.topModule = topModule;
            this.priority = priority;
            this.recommendation = recommendation;
        }
    }

    // Tracks test references impacted by one changed function.
    private static class FunctionImpact {
        final String functionName;
        final DynamicArray<Finding> references = new DynamicArray<>();

        FunctionImpact(String functionName) {
            this.functionName = functionName;
        }
    }

    // Precompiled whole-word regex for changed-function lookups.
    private static class TokenPattern {
        final String functionName;
        final Pattern pattern;

        TokenPattern(String functionName, Pattern pattern) {
            this.functionName = functionName;
            this.pattern = pattern;
        }
    }

    // Parsed CLI arguments with defaults.
    private static class CliArgs {
        DynamicArray<String> targets = new DynamicArray<>();
        String extensions = ".js,.ts,.jsx,.tsx,.py";
        String jsonOutput = null;
        String txtOutput = null;
        String mdOutput = null;
        boolean showLines = false;
        int maxLinesPerIssue = 0;
        DynamicArray<String> changedFunctions = new DynamicArray<>();
        String changedFile = "";
        int maxImpactedPerFunction = 0;
    }

    // Collects scan targets, expands directories recursively, deduplicates, and sorts.
    private static DynamicArray<Path> collectFiles(DynamicArray<String> targets, Set<String> extensions) {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            String target = targets.get(i);
            Path p = Paths.get(target);
            if (!Files.exists(p)) {
                continue;
            }
            if (Files.isRegularFile(p) && extensions.contains(getLowerSuffix(p))) {
                unique.add(p);
                continue;
            }
            if (Files.isDirectory(p)) {
                try (Stream<Path> stream = Files.walk(p)) {
                    stream.filter(Files::isRegularFile)
                            .filter(f -> extensions.contains(getLowerSuffix(f)))
                            .forEach(unique::add);
                } catch (IOException ignored) {
                    // Ignore unreadable directories and continue scanning.
                }
            }
        }

        TreeSet<Path> sortedSet = new TreeSet<>(Comparator.comparing(Path::toString));
        sortedSet.addAll(unique);
        DynamicArray<Path> sorted = new DynamicArray<>();
        for (Path path : sortedSet) {
            sorted.add(path);
        }
        return sorted;
    }

    // Returns lowercase extension (e.g., ".ts"), or empty string when none exists.
    private static String getLowerSuffix(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }

    // Reads UTF-8 file into DynamicArray lines; returns empty on I/O failure.
    private static DynamicArray<String> readLines(Path path) {
        DynamicArray<String> lines = new DynamicArray<>();
        try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
            stream.forEach(lines::add);
        } catch (IOException ignored) {
            return new DynamicArray<>();
        }
        return lines;
    }

    // Detects hard-coded sleeps/waits that can make test suites flaky and slow.
    private static DynamicArray<Finding> detectHardWaits(Path path, DynamicArray<String> lines) {
        Pattern[] patterns = new Pattern[] {
                Pattern.compile("\\bwaitForTimeout\\s*\\(\\s*\\d+\\s*\\)"),
                Pattern.compile("\\bThread\\.sleep\\s*\\(\\s*\\d+\\s*\\)"),
                Pattern.compile("\\btime\\.sleep\\s*\\(\\s*\\d+(\\.\\d+)?\\s*\\)"),
                Pattern.compile("\\bsleep\\s*\\(\\s*\\d+(\\.\\d+)?\\s*\\)"),
                Pattern.compile("\\bcy\\.wait\\s*\\(\\s*\\d+\\s*\\)")
        };
        DynamicArray<Finding> findings = new DynamicArray<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern pat : patterns) {
                Matcher m = pat.matcher(line);
                if (m.find()) {
                    findings.add(new Finding(path.toString(), i + 1, m.start() + 1, "hard_wait", line.trim()));
                    break;
                }
            }
        }
        return findings;
    }

    // Flags likely hardcoded test data literals (credentials/PII/form inputs) in tests.
    private static DynamicArray<Finding> detectHardcodedTestData(Path path, DynamicArray<String> lines) {
        DynamicArray<Finding> findings = new DynamicArray<>();
        Pattern fillSecondArg = Pattern.compile("\\b(?:page|cy)\\.[A-Za-z_]\\w*\\s*\\([^,]+,\\s*([\"'])(.+?)\\1");
        Pattern fillOrTypeValue = Pattern.compile("\\.(?:fill|type|sendKeys|setValue)\\s*\\(\\s*([\"'])(.+?)\\1\\s*\\)");
        Pattern jsSensitiveAssign = Pattern.compile(
                "^\\s*(?:const|let|var)\\s+([A-Za-z_]\\w*(?:email|user(?:name)?|password|phone|mobile|otp|token|ssn|dob|address|pin|zipcode)[A-Za-z_0-9]*)\\s*=\\s*([\"'])(.+?)\\2"
        );
        Pattern pySensitiveAssign = Pattern.compile(
                "^\\s*([A-Za-z_]\\w*(?:email|user(?:name)?|password|phone|mobile|otp|token|ssn|dob|address|pin|zipcode)[A-Za-z_0-9]*)\\s*=\\s*([\"'])(.+?)\\2"
        );

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher opMatcher = fillSecondArg.matcher(line);
            if (opMatcher.find()) {
                String value = opMatcher.group(2);
                if (isLikelyHardcodedData(value)) {
                    findings.add(new Finding(path.toString(), i + 1, opMatcher.start(2) + 1, "hardcoded_test_data", line.trim()));
                    continue;
                }
            }

            Matcher typedValueMatcher = fillOrTypeValue.matcher(line);
            if (typedValueMatcher.find()) {
                String value = typedValueMatcher.group(2);
                if (isLikelyHardcodedData(value)) {
                    findings.add(new Finding(path.toString(), i + 1, typedValueMatcher.start(2) + 1, "hardcoded_test_data", line.trim()));
                    continue;
                }
            }

            Matcher jsAssignMatcher = jsSensitiveAssign.matcher(line);
            if (jsAssignMatcher.find()) {
                String value = jsAssignMatcher.group(3);
                if (isLikelyHardcodedData(value)) {
                    findings.add(new Finding(path.toString(), i + 1, jsAssignMatcher.start(3) + 1, "hardcoded_test_data", line.trim()));
                    continue;
                }
            }

            Matcher pyAssignMatcher = pySensitiveAssign.matcher(line);
            if (pyAssignMatcher.find()) {
                String value = pyAssignMatcher.group(3);
                if (isLikelyHardcodedData(value)) {
                    findings.add(new Finding(path.toString(), i + 1, pyAssignMatcher.start(3) + 1, "hardcoded_test_data", line.trim()));
                }
            }
        }
        return findings;
    }

    // Filters out obvious non-hardcoded/dynamic values to reduce false positives.
    private static boolean isLikelyHardcodedData(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("${")
                || lower.contains("process.env")
                || lower.contains("system.getenv")
                || lower.contains("faker.")
                || lower.contains("random")
                || lower.contains("uuid")
                || lower.contains("date.now")
                || lower.contains("new date")
                || lower.contains("testdata")
                || lower.contains("fixture")
                || lower.contains("config.")
                || lower.contains("env.")) {
            return false;
        }
        return true;
    }

    // Extracts common locator usages (Playwright/Cypress/Selenium/etc.) from source lines.
    private static DynamicArray<LocatorMatch> extractLocators(Path path, DynamicArray<String> lines) {
        Pattern[] patterns = new Pattern[] {
                Pattern.compile("\\bpage\\.locator\\s*\\(\\s*([\"'])(.+?)\\1\\s*\\)"),
                Pattern.compile("\\bcy\\.get\\s*\\(\\s*([\"'])(.+?)\\1\\s*\\)"),
                Pattern.compile("\\bBy\\.(?:id|xpath|cssSelector|name|className|linkText)\\s*\\(\\s*([\"'])(.+?)\\1\\s*\\)"),
                Pattern.compile("\\$\\s*\\(\\s*([\"'])(.+?)\\1\\s*\\)"),
                Pattern.compile("\\bfind_element\\s*\\(\\s*[^,]+,\\s*([\"'])(.+?)\\1\\s*\\)")
        };
        DynamicArray<LocatorMatch> locators = new DynamicArray<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern pat : patterns) {
                Matcher m = pat.matcher(line);
                if (m.find()) {
                    locators.add(new LocatorMatch(m.group(2), i + 1, m.start(2) + 1, line.trim()));
                    break;
                }
            }
        }
        return locators;
    }

    // Flags weak assertions that provide low validation value.
    private static DynamicArray<Finding> detectPoorAssertions(Path path, DynamicArray<String> lines) {
        Pattern[] patterns = new Pattern[] {
                Pattern.compile("\\bexpect\\s*\\(.+?\\)\\.toBeTruthy\\s*\\("),
                Pattern.compile("\\bexpect\\s*\\(.+?\\)\\.toBeDefined\\s*\\("),
                Pattern.compile("\\bexpect\\s*\\(\\s*true\\s*\\)\\.toBe\\s*\\(\\s*true\\s*\\)"),
                Pattern.compile("\\bassert\\s+True\\b"),
                Pattern.compile("\\bassertTrue\\s*\\(\\s*true\\s*\\)")
        };
        DynamicArray<Finding> findings = new DynamicArray<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern pat : patterns) {
                Matcher m = pat.matcher(line);
                if (m.find()) {
                    findings.add(new Finding(path.toString(), i + 1, m.start() + 1, "poor_assertion", line.trim()));
                    break;
                }
            }
        }
        return findings;
    }

    // Extracts function declaration names across JS/TS/Python patterns.
    private static DynamicArray<FunctionDecl> extractFunctionNames(Path path, DynamicArray<String> lines) {
        DynamicArray<FunctionDecl> names = new DynamicArray<>();
        Pattern jsFunc = Pattern.compile("^\\s*(?:async\\s+)?function\\s+([A-Za-z_]\\w*)\\s*\\(");
        Pattern jsArrow = Pattern.compile("^\\s*(?:const|let|var)\\s+([A-Za-z_]\\w*)\\s*=\\s*(?:async\\s*)?\\(");
        Pattern pyFunc = Pattern.compile("^\\s*def\\s+([A-Za-z_]\\w*)\\s*\\(");
        Pattern[] patterns = new Pattern[] {jsFunc, jsArrow, pyFunc};

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern p : patterns) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    names.add(new FunctionDecl(m.group(1), i + 1, m.start(1) + 1));
                    break;
                }
            }
        }
        return names;
    }

    // Marks tests that appear to have no assertion within a local scan window.
    private static DynamicArray<Finding> detectMissingValidations(Path path, DynamicArray<String> lines) {
        DynamicArray<Finding> findings = new DynamicArray<>();
        Pattern[] testStartPatterns = new Pattern[] {
                Pattern.compile("\\bit\\s*\\("),
                Pattern.compile("\\btest\\s*\\("),
                Pattern.compile("^\\s*def\\s+test_[A-Za-z_]\\w*\\s*\\(")
        };
        Pattern[] assertionPatterns = new Pattern[] {
                Pattern.compile("\\bexpect\\s*\\("),
                Pattern.compile("\\bassert\\b"),
                Pattern.compile("\\bshould\\s*\\(")
        };

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            boolean isTestStart = false;
            int testStartColumn = 1;
            for (Pattern p : testStartPatterns) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    isTestStart = true;
                    testStartColumn = m.start() + 1;
                    break;
                }
            }
            if (!isTestStart) {
                i++;
                continue;
            }

            int start = i;
            int end = Math.min(lines.size(), i + 120);
            boolean hasAssertion = false;
            for (int j = start; j < end && !hasAssertion; j++) {
                String blockLine = lines.get(j);
                for (Pattern a : assertionPatterns) {
                    if (a.matcher(blockLine).find()) {
                        hasAssertion = true;
                        break;
                    }
                }
            }

            if (!hasAssertion) {
                findings.add(new Finding(path.toString(), start + 1, testStartColumn, "missing_validation", lines.get(start).trim()));
            }
            i = end;
        }
        return findings;
    }

    // Core pipeline: runs detectors, aggregates findings, computes summary, builds duplicate groups.
    private static BuildResult buildReport(DynamicArray<Path> files) {
        FindingsByIssue allFindings = new FindingsByIssue();
        DynamicArray<LocatorBucket> locatorOccurrences = new DynamicArray<>();
        DynamicArray<Finding> declaredFunctions = new DynamicArray<>();
        StringBuilder combinedTextBuilder = new StringBuilder();

        for (int i = 0; i < files.size(); i++) {
            Path path = files.get(i);
            DynamicArray<String> lines = readLines(path);
            if (lines.isEmpty()) {
                continue;
            }

            combinedTextBuilder.append(joinStrings(lines, "\n")).append('\n');
            allFindings.hardWait.addAll(detectHardWaits(path, lines));
            allFindings.hardcodedTestData.addAll(detectHardcodedTestData(path, lines));
            allFindings.poorAssertion.addAll(detectPoorAssertions(path, lines));
            allFindings.missingValidation.addAll(detectMissingValidations(path, lines));

            DynamicArray<LocatorMatch> locators = extractLocators(path, lines);
            for (int j = 0; j < locators.size(); j++) {
                LocatorMatch lm = locators.get(j);
                addLocatorOccurrence(
                        locatorOccurrences,
                        lm.value,
                        new Finding(path.toString(), lm.line, lm.column, "duplicate_locator", lm.source)
                );
            }

            DynamicArray<FunctionDecl> fnDecls = extractFunctionNames(path, lines);
            for (int j = 0; j < fnDecls.size(); j++) {
                FunctionDecl fn = fnDecls.get(j);
                if (!fn.name.startsWith("_")) {
                    declaredFunctions.add(new Finding(path.toString(), fn.line, fn.column, "unused_function", fn.name));
                }
            }
        }

        for (int i = 0; i < locatorOccurrences.size(); i++) {
            LocatorBucket bucket = locatorOccurrences.get(i);
            if (bucket.occurrences.size() > 1) {
                allFindings.duplicateLocator.addAll(bucket.occurrences);
            }
        }

        if (!declaredFunctions.isEmpty()) {
            String combinedText = combinedTextBuilder.toString();
            DynamicArray<TokenCount> functionMentions = new DynamicArray<>();
            Matcher m = Pattern.compile("\\b([A-Za-z_]\\w*)\\b").matcher(combinedText);
            while (m.find()) {
                incrementTokenCount(functionMentions, m.group(1));
            }
            for (int i = 0; i < declaredFunctions.size(); i++) {
                Finding finding = declaredFunctions.get(i);
                if (getTokenCount(functionMentions, finding.detail) <= 1) {
                    allFindings.unusedFunction.add(finding);
                }
            }
        }

        Summary summary = new Summary(
                allFindings.hardWait.size(),
                allFindings.hardcodedTestData.size(),
                allFindings.duplicateLocator.size(),
                allFindings.poorAssertion.size(),
                allFindings.unusedFunction.size(),
                allFindings.missingValidation.size(),
                files.size()
        );

        DynamicArray<DuplicateGroup> duplicatesGrouped = new DynamicArray<>();
        DynamicArray<LocatorBucket> sortedLocators = sortLocatorBuckets(locatorOccurrences);
        for (int i = 0; i < sortedLocators.size(); i++) {
            LocatorBucket bucket = sortedLocators.get(i);
            if (bucket.occurrences.size() > 1) {
                DuplicateGroup group = new DuplicateGroup(bucket.locator);
                for (int j = 0; j < bucket.occurrences.size(); j++) {
                    Finding o = bucket.occurrences.get(j);
                    group.entries.add(new DuplicateEntry(o.file, o.line + ":" + o.column, o.detail));
                }
                duplicatesGrouped.add(group);
            }
        }

        DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights =
                createDuplicateRefactorInsights(duplicatesGrouped);
        return new BuildResult(summary, allFindings, duplicatesGrouped, duplicateRefactorInsights);
    }

    // Builds ranked duplicate-locator refactor opportunities for dashboard triage.
    private static DynamicArray<DuplicateRefactorInsight> createDuplicateRefactorInsights(DynamicArray<DuplicateGroup> groups) {
        DynamicArray<DuplicateRefactorInsight> insights = new DynamicArray<>();
        for (int i = 0; i < groups.size(); i++) {
            DuplicateGroup group = groups.get(i);
            int occurrences = group.entries.size();
            int distinctFiles = countDistinctFiles(group.entries);
            String topModule = findTopModule(group.entries);
            int score = occurrences * 2 + distinctFiles;
            String priority = score >= 10 ? "high" : (score >= 6 ? "medium" : "low");
            String recommendation;
            if (occurrences >= 4 && distinctFiles >= 3) {
                recommendation = "Extract to shared page object constant; duplicate usage spans multiple modules.";
            } else if (distinctFiles >= 2) {
                recommendation = "Move locator into reusable helper/page object to reduce repetition.";
            } else {
                recommendation = "Keep one canonical locator declaration and reference it from helpers.";
            }
            insights.add(new DuplicateRefactorInsight(
                    group.locator,
                    occurrences,
                    distinctFiles,
                    topModule,
                    priority,
                    recommendation
            ));
        }
        return sortDuplicateRefactorInsights(insights);
    }

    // Counts unique files represented in duplicate entries.
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

    // Derives the most repeated module-like path segment from duplicate entries.
    private static String findTopModule(DynamicArray<DuplicateEntry> entries) {
        DynamicArray<TokenCount> modules = new DynamicArray<>();
        for (int i = 0; i < entries.size(); i++) {
            incrementTokenCount(modules, inferModule(entries.get(i).file));
        }
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

    // Approximates a module key from a full file path.
    private static String inferModule(String filePath) {
        String normalized = filePath.replace('\\', '/');
        String[] anchors = new String[] {"/src/", "/tests/", "/e2e/", "/pages/", "/components/"};
        for (String anchor : anchors) {
            int idx = normalized.indexOf(anchor);
            if (idx >= 0) {
                String tail = normalized.substring(idx + anchor.length());
                int slash = tail.indexOf('/');
                return slash >= 0 ? anchor.substring(1, anchor.length() - 1) + "/" + tail.substring(0, slash) : anchor.substring(1, anchor.length() - 1);
            }
        }
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash > 0) {
            int prevSlash = normalized.lastIndexOf('/', lastSlash - 1);
            if (prevSlash >= 0) {
                return normalized.substring(prevSlash + 1, lastSlash);
            }
            return normalized.substring(0, lastSlash);
        }
        return "root";
    }

    // Sorts insights by priority score, then by occurrence count.
    private static DynamicArray<DuplicateRefactorInsight> sortDuplicateRefactorInsights(DynamicArray<DuplicateRefactorInsight> insights) {
        DynamicArray<DuplicateRefactorInsight> sorted = new DynamicArray<>();
        for (int i = 0; i < insights.size(); i++) {
            sorted.add(insights.get(i));
        }
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

    // Comparator prioritizes high-impact refactors first.
    private static int compareDuplicateInsight(DuplicateRefactorInsight left, DuplicateRefactorInsight right) {
        int byPriority = Integer.compare(priorityRank(left.priority), priorityRank(right.priority));
        if (byPriority != 0) {
            return byPriority;
        }
        int byOccurrences = Integer.compare(right.occurrences, left.occurrences);
        if (byOccurrences != 0) {
            return byOccurrences;
        }
        return left.locator.compareTo(right.locator);
    }

    // Priority ranking helper where lower number means higher priority.
    private static int priorityRank(String priority) {
        if ("high".equals(priority)) {
            return 0;
        }
        if ("medium".equals(priority)) {
            return 1;
        }
        return 2;
    }

    // Adds one locator occurrence into an existing bucket or creates a new bucket.
    private static void addLocatorOccurrence(
            DynamicArray<LocatorBucket> locatorOccurrences,
            String locator,
            Finding occurrence
    ) {
        for (int i = 0; i < locatorOccurrences.size(); i++) {
            LocatorBucket bucket = locatorOccurrences.get(i);
            if (bucket.locator.equals(locator)) {
                bucket.occurrences.add(occurrence);
                return;
            }
        }
        LocatorBucket bucket = new LocatorBucket(locator);
        bucket.occurrences.add(occurrence);
        locatorOccurrences.add(bucket);
    }

    // Increments token frequency in a linear key/value list.
    private static void incrementTokenCount(DynamicArray<TokenCount> counts, String token) {
        for (int i = 0; i < counts.size(); i++) {
            TokenCount tokenCount = counts.get(i);
            if (tokenCount.token.equals(token)) {
                tokenCount.count++;
                return;
            }
        }
        counts.add(new TokenCount(token));
    }

    // Reads token frequency; returns 0 when token was not seen.
    private static int getTokenCount(DynamicArray<TokenCount> counts, String token) {
        for (int i = 0; i < counts.size(); i++) {
            TokenCount tokenCount = counts.get(i);
            if (tokenCount.token.equals(token)) {
                return tokenCount.count;
            }
        }
        return 0;
    }

    // Converts internal issue IDs to user-friendly section titles.
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

    // Prints compact top-level summary to stdout.
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

    // Returns findings sorted by file and line for stable, readable output.
    private static DynamicArray<Finding> sortFindings(DynamicArray<Finding> findings) {
        DynamicArray<Finding> sorted = new DynamicArray<>();
        for (int i = 0; i < findings.size(); i++) {
            sorted.add(findings.get(i));
        }
        for (int i = 1; i < sorted.size(); i++) {
            Finding current = sorted.get(i);
            int j = i - 1;
            while (j >= 0 && compareFinding(sorted.get(j), current) > 0) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, current);
        }
        return sorted;
    }

    // Comparator for finding ordering: file path, then line/column.
    private static int compareFinding(Finding left, Finding right) {
        int byFile = left.file.compareTo(right.file);
        if (byFile != 0) {
            return byFile;
        }
        int byLine = Integer.compare(left.line, right.line);
        if (byLine != 0) {
            return byLine;
        }
        return Integer.compare(left.column, right.column);
    }

    // Prefer /src/... in output when present to keep paths compact.
    private static String compactPath(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int srcIndex = normalized.indexOf("/src/");
        if (srcIndex >= 0) {
            return normalized.substring(srcIndex);
        }
        return normalized;
    }

    // Standard location format used in console/TXT/MD outputs.
    private static String formatLocation(Finding finding) {
        return compactPath(finding.file) + ":" + finding.line + ":" + finding.column;
    }

    // Sorts locator buckets alphabetically by locator string.
    private static DynamicArray<LocatorBucket> sortLocatorBuckets(DynamicArray<LocatorBucket> buckets) {
        DynamicArray<LocatorBucket> sorted = new DynamicArray<>();
        for (int i = 0; i < buckets.size(); i++) {
            sorted.add(buckets.get(i));
        }
        for (int i = 1; i < sorted.size(); i++) {
            LocatorBucket current = sorted.get(i);
            int j = i - 1;
            while (j >= 0 && sorted.get(j).locator.compareTo(current.locator) > 0) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, current);
        }
        return sorted;
    }

    // Optional detailed console section, with configurable per-issue truncation.
    private static void printDetailedConsoleFindings(FindingsByIssue findings, int maxPerIssue) {
        System.out.println("\nDetailed Findings (file:line:column)");
        System.out.println("-----------------------------");
        for (String issue : ISSUE_ORDER) {
            DynamicArray<Finding> issueFindings = sortFindings(findings.get(issue));
            System.out.println("\n" + issueLabel(issue) + ":");
            if (issueFindings.isEmpty()) {
                System.out.println("  No findings.");
                continue;
            }
            int limit = issueFindings.size();
            if (maxPerIssue > 0 && limit > maxPerIssue) {
                limit = maxPerIssue;
            }
            for (int i = 0; i < limit; i++) {
                Finding f = issueFindings.get(i);
                System.out.println("  " + formatLocation(f) + " | " + f.detail);
            }
        }
    }

    // Heuristic test-file detection by filename/path conventions.
    private static boolean isTestFile(Path path) {
        String lowered = path.toString().toLowerCase();
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (name.contains(".spec.")
                || name.contains(".test.")
                || name.startsWith("test_")
                || lowered.contains("/tests/")
                || lowered.contains("/__tests__/")
                || lowered.contains("/e2e/")) {
            return true;
        }
        String normalized = lowered.replace('\\', '/');
        return normalized.contains("/tests/")
                || normalized.contains("/__tests__/")
                || normalized.contains("/e2e/");
    }

    // Finds tests likely impacted by changed functions using whole-word regex matching.
    private static DynamicArray<FunctionImpact> findImpactedTests(
            DynamicArray<Path> files,
            DynamicArray<String> changedFunctions,
            String changedFile
    ) {
        DynamicArray<FunctionImpact> impacted = new DynamicArray<>();
        for (int i = 0; i < changedFunctions.size(); i++) {
            String fn = changedFunctions.get(i);
            if (findFunctionImpact(impacted, fn) == null) {
                impacted.add(new FunctionImpact(fn));
            }
        }

        String changedFileText = "";
        Path changedFilePath = null;
        if (changedFile != null && !changedFile.isEmpty()) {
            changedFilePath = Paths.get(changedFile);
            if (Files.exists(changedFilePath)) {
                try {
                    changedFileText = Files.readString(changedFilePath, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    changedFileText = "";
                }
            }
        }

        DynamicArray<TokenPattern> tokenPatterns = new DynamicArray<>();
        for (int i = 0; i < changedFunctions.size(); i++) {
            String fn = changedFunctions.get(i);
            if (!containsTokenPattern(tokenPatterns, fn)) {
                tokenPatterns.add(new TokenPattern(fn, Pattern.compile("\\b" + Pattern.quote(fn) + "\\b")));
            }
        }

        for (int i = 0; i < files.size(); i++) {
            Path path = files.get(i);
            DynamicArray<String> lines = readLines(path);
            if (lines.isEmpty() || !isTestFile(path)) {
                continue;
            }
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String line = lines.get(lineIndex);
                for (int t = 0; t < tokenPatterns.size(); t++) {
                    TokenPattern tokenPattern = tokenPatterns.get(t);
                    Matcher m = tokenPattern.pattern.matcher(line);
                    if (m.find()) {
                        FunctionImpact impact = findFunctionImpact(impacted, tokenPattern.functionName);
                        if (impact != null) {
                            impact.references.add(new Finding(
                                    path.toString(),
                                    lineIndex + 1,
                                    m.start() + 1,
                                    "impacted_test",
                                    line.trim()
                            ));
                        }
                    }
                }
            }
        }

        if (!changedFileText.isEmpty() && changedFilePath != null) {
            for (int i = 0; i < tokenPatterns.size(); i++) {
                TokenPattern tokenPattern = tokenPatterns.get(i);
                String fn = tokenPattern.functionName;
                if (!tokenPattern.pattern.matcher(changedFileText).find()) {
                    FunctionImpact impact = findFunctionImpact(impacted, fn);
                    if (impact != null) {
                        impact.references.addAt(0, new Finding(
                                changedFilePath.toString(),
                                1,
                                1,
                                "impacted_test_note",
                                "Note: '" + fn + "' not found in changed file text; verify function name."
                        ));
                    }
                }
            }
        }

        return impacted;
    }

    // Finds a FunctionImpact entry by function name.
    private static FunctionImpact findFunctionImpact(DynamicArray<FunctionImpact> impacted, String functionName) {
        for (int i = 0; i < impacted.size(); i++) {
            FunctionImpact impact = impacted.get(i);
            if (impact.functionName.equals(functionName)) {
                return impact;
            }
        }
        return null;
    }

    // Checks whether a function already has a compiled token pattern.
    private static boolean containsTokenPattern(DynamicArray<TokenPattern> tokenPatterns, String functionName) {
        for (int i = 0; i < tokenPatterns.size(); i++) {
            TokenPattern tokenPattern = tokenPatterns.get(i);
            if (tokenPattern.functionName.equals(functionName)) {
                return true;
            }
        }
        return false;
    }

    // Prints impacted test references grouped by changed function.
    private static void printImpactedTests(DynamicArray<FunctionImpact> impactedTests, int maxPerFunction) {
        System.out.println("\nImpacted Tests by Changed Function");
        System.out.println("---------------------------------");
        for (int i = 0; i < impactedTests.size(); i++) {
            FunctionImpact impact = impactedTests.get(i);
            String fnName = impact.functionName;
            DynamicArray<Finding> refs = impact.references;
            System.out.println("\nChanged Function: " + fnName);
            if (refs.isEmpty()) {
                System.out.println("  No impacted test references found.");
                continue;
            }
            DynamicArray<Finding> refsSorted = sortFindings(refs);
            int limit = refsSorted.size();
            if (maxPerFunction > 0 && limit > maxPerFunction) {
                limit = maxPerFunction;
            }
            for (int j = 0; j < limit; j++) {
                Finding r = refsSorted.get(j);
                System.out.println("  " + formatLocation(r) + " | " + r.detail);
            }
        }
    }

    // Builds full plain-text report body.
    private static String createDetailedTextReport(Summary summary, FindingsByIssue findings) {
        DynamicArray<String> out = new DynamicArray<>();
        out.add("Automation Script Quality Report");
        out.add("================================");
        out.add("");
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

        for (String issue : ISSUE_ORDER) {
            DynamicArray<Finding> issueFindings = sortFindings(findings.get(issue));
            String label = issueLabel(issue);
            out.add(label);
            out.add(repeat("-", label.length()));
            if (issueFindings.isEmpty()) {
                out.add("No findings.");
                out.add("");
                continue;
            }
            for (int i = 0; i < issueFindings.size(); i++) {
                Finding f = issueFindings.get(i);
                out.add(formatLocation(f) + " | " + f.detail);
            }
            out.add("");
        }
        return joinStrings(out, "\n");
    }

    // Builds markdown report body.
    private static String createMarkdownReport(Summary summary, FindingsByIssue findings) {
        DynamicArray<String> out = new DynamicArray<>();
        out.add("# Automation Script Quality Report");
        out.add("");
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

        for (String issue : ISSUE_ORDER) {
            DynamicArray<Finding> issueFindings = sortFindings(findings.get(issue));
            out.add("## " + issueLabel(issue));
            out.add("");
            if (issueFindings.isEmpty()) {
                out.add("No findings.");
                out.add("");
                continue;
            }
            for (int i = 0; i < issueFindings.size(); i++) {
                Finding f = issueFindings.get(i);
                out.add("- `" + formatLocation(f) + "` - `" + f.detail + "`");
            }
            out.add("");
        }
        return joinStrings(out, "\n");
    }

    // Utility to repeat a string N times.
    private static String repeat(String value, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(value);
        }
        return sb.toString();
    }

    // Joins DynamicArray<String> using a delimiter.
    private static String joinStrings(DynamicArray<String> values, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    // Escapes JSON special/control characters for safe manual serialization.
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // Wraps an escaped JSON string in double quotes.
    private static String quote(String value) {
        return "\"" + jsonEscape(value) + "\"";
    }

    // Serializes a single finding as JSON object text.
    private static String findingToJson(Finding f) {
        return "{"
                + "\"file\":" + quote(f.file) + ","
                + "\"line\":" + f.line + ","
                + "\"column\":" + f.column + ","
                + "\"issue\":" + quote(f.issue) + ","
                + "\"detail\":" + quote(f.detail)
                + "}";
    }

    // Serializes summary counters as JSON object text.
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

    // Serializes issue-grouped findings as a JSON object.
    private static String findingsToJson(FindingsByIssue findings) {
        DynamicArray<String> entries = new DynamicArray<>();
        for (String issue : ISSUE_ORDER) {
            DynamicArray<String> findingRows = new DynamicArray<>();
            DynamicArray<Finding> issueFindings = findings.get(issue);
            for (int i = 0; i < issueFindings.size(); i++) {
                findingRows.add(findingToJson(issueFindings.get(i)));
            }
            entries.add(quote(issue) + ":[" + joinStrings(findingRows, ",") + "]");
        }
        return "{" + joinStrings(entries, ",") + "}";
    }

    // Serializes duplicate locator groups for report payload.
    private static String duplicateGroupsToJson(DynamicArray<DuplicateGroup> groups) {
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
            groupRows.add(quote(group.locator) + ":[" + joinStrings(entryRows, ",") + "]");
        }
        return "{" + joinStrings(groupRows, ",") + "}";
    }

    // Serializes ranked duplicate-locator refactor opportunities.
    private static String duplicateRefactorInsightsToJson(DynamicArray<DuplicateRefactorInsight> insights) {
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

    // Serializes impacted test references keyed by changed function.
    private static String impactedTestsToJson(DynamicArray<FunctionImpact> impactedTests) {
        DynamicArray<String> rows = new DynamicArray<>();
        for (int i = 0; i < impactedTests.size(); i++) {
            FunctionImpact impact = impactedTests.get(i);
            DynamicArray<String> refs = new DynamicArray<>();
            for (int j = 0; j < impact.references.size(); j++) {
                refs.add(findingToJson(impact.references.get(j)));
            }
            rows.add(quote(impact.functionName) + ":[" + joinStrings(refs, ",") + "]");
        }
        return "{" + joinStrings(rows, ",") + "}";
    }

    // Assembles final JSON payload sections into one object.
    private static String createJsonPayload(
            Summary summary,
            FindingsByIssue findings,
            DynamicArray<DuplicateGroup> duplicateGroups,
            DynamicArray<FunctionImpact> impactedTests,
            DynamicArray<DuplicateRefactorInsight> duplicateRefactorInsights
    ) {
        return "{"
                + "\"summary\":" + summaryToJson(summary) + ","
                + "\"findings\":" + findingsToJson(findings) + ","
                + "\"duplicate_locator_groups\":" + duplicateGroupsToJson(duplicateGroups) + ","
                + "\"duplicate_refactor_intelligence\":" + duplicateRefactorInsightsToJson(duplicateRefactorInsights) + ","
                + "\"impacted_tests\":" + impactedTestsToJson(impactedTests)
                + "}";
    }

    // Parses CLI flags/values and positional targets.
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
                    args.changedFunctions.add(requireValue(argv, ++i, arg));
                    break;
                case "--changed-file":
                    args.changedFile = requireValue(argv, ++i, arg);
                    break;
                case "--max-impacted-per-function":
                    args.maxImpactedPerFunction = Integer.parseInt(requireValue(argv, ++i, arg));
                    break;
                default:
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                    }
                    args.targets.add(arg);
            }
        }

        if (args.targets.isEmpty()) {
            throw new IllegalArgumentException("At least one target file/directory is required.");
        }
        return args;
    }

    // Enforces option value presence and returns it.
    private static String requireValue(String[] argv, int index, String optionName) {
        if (index >= argv.length) {
            throw new IllegalArgumentException("Missing value for " + optionName);
        }
        return argv[index];
    }

    // Normalizes extension CSV into lowercase dot-prefixed set.
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
        if (extensions.isEmpty()) {
            extensions.addAll(SUPPORTED_EXTENSIONS);
        }
        return extensions;
    }

    // Prints command usage and option help text.
    private static void printUsage() {
        System.err.println("Usage: java AutomationQualityChecker [options] <targets...>");
        System.err.println("Options:");
        System.err.println("  --extensions <.js,.ts,...>          Comma-separated file extensions to scan.");
        System.err.println("  --json <output.json>                Optional output JSON file path.");
        System.err.println("  --txt <output.txt>                  Optional output TXT file path.");
        System.err.println("  --md <output.md>                    Optional output Markdown file path.");
        System.err.println("  --show-lines                        Print file:line:column findings in terminal.");
        System.err.println("  --max-lines-per-issue <n>           Limit terminal findings per issue.");
        System.err.println("  --changed-function <name>           Changed function to find impacted tests. Repeatable.");
        System.err.println("  --changed-file <path>               Optional changed file path for function validation.");
        System.err.println("  --max-impacted-per-function <n>     Limit impacted test lines per changed function.");
    }

    // Program entry point: parse args, scan files, print results, optionally write reports.
    public static void main(String[] argv) {
        CliArgs args;
        try {
            args = parseArgs(argv);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        Set<String> extensions = parseExtensions(args.extensions);
        DynamicArray<Path> files = collectFiles(args.targets, extensions);
        BuildResult result = buildReport(files);

        printConsoleReport(result.summary);

        if (args.showLines) {
            printDetailedConsoleFindings(result.findings, args.maxLinesPerIssue);
        }

        DynamicArray<FunctionImpact> impactedTests = new DynamicArray<>();
        if (!args.changedFunctions.isEmpty()) {
            impactedTests = findImpactedTests(files, args.changedFunctions, args.changedFile);
            printImpactedTests(impactedTests, args.maxImpactedPerFunction);
        }

        try {
            if (args.jsonOutput != null) {
                String payload = createJsonPayload(
                        result.summary,
                        result.findings,
                        result.duplicateGroups,
                        impactedTests,
                        result.duplicateRefactorInsights
                );
                Files.writeString(Paths.get(args.jsonOutput), payload, StandardCharsets.UTF_8);
                System.out.println("\nDetailed JSON report written to: " + args.jsonOutput);
            }

            if (args.txtOutput != null) {
                String txt = createDetailedTextReport(result.summary, result.findings);
                Files.writeString(Paths.get(args.txtOutput), txt, StandardCharsets.UTF_8);
                System.out.println("Detailed TXT report written to: " + args.txtOutput);
            }

            if (args.mdOutput != null) {
                String md = createMarkdownReport(result.summary, result.findings);
                Files.writeString(Paths.get(args.mdOutput), md, StandardCharsets.UTF_8);
                System.out.println("Detailed Markdown report written to: " + args.mdOutput);
            }
        } catch (IOException e) {
            System.err.println("Failed to write output file: " + e.getMessage());
            System.exit(1);
        }
    }
}
