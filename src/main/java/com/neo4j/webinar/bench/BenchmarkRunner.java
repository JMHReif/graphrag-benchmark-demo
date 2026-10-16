package com.neo4j.webinar.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads the benchmark set, runs every question through the assistant,
 * scores each answer, and prints/saves a report broken down by question
 * type -- so you can see *which* kind of question a pipeline change
 * helped or hurt, not just one aggregate score.
 */
public class BenchmarkRunner {

    private final Function<String, String> assistant;
    private final Scorer scorer;

    public BenchmarkRunner(Function<String, String> assistant, Scorer scorer) {
        this.assistant = assistant;
        this.scorer = scorer;
    }

    public List<BenchmarkQuestion> loadQuestions() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = BenchmarkRunner.class.getResourceAsStream("/benchmark_questions.json")) {
            if (in == null) {
                throw new IllegalStateException("benchmark_questions.json not found on the classpath");
            }
            return mapper.readValue(in, mapper.getTypeFactory().constructCollectionType(List.class, BenchmarkQuestion.class));
        }
    }

    public List<ScoredAnswer> run(List<BenchmarkQuestion> questions) {
        return questions.stream()
                .map(q -> {
                    String actualAnswer = assistant.apply(q.question());
                    return scorer.score(q, actualAnswer);
                })
                .toList();
    }

    public void printReport(List<ScoredAnswer> results, PrintStream out) {
        printReport(results, out, 160);
    }

    /** @param maxAnswerLength truncate each answer to this many characters; 0 (or less) means print it in full. */
    public void printReport(List<ScoredAnswer> results, PrintStream out, int maxAnswerLength) {
        out.println();
        out.println("=".repeat(100));
        out.println("BENCHMARK RESULTS");
        out.println("=".repeat(100));

        for (ScoredAnswer r : results) {
            out.printf("[%s] %-22s correctness=%.2f/2  faithfulness=%.2f/2  %s%n",
                    r.question().id(), r.question().category(), r.correctness(), r.faithfulness(),
                    r.isHallucinationRisk() ? "*** HALLUCINATION RISK ***" : "");
            out.printf("      Q: %s%n", r.question().question());
            out.printf("      A: %s%n", maxAnswerLength > 0 ? truncate(r.actualAnswer(), maxAnswerLength) : r.actualAnswer().replace("\n", " "));
            out.printf("      judge: %s%n", r.rationale());
        }

        out.println("-".repeat(100));
        Map<String, List<ScoredAnswer>> byCategory = new LinkedHashMap<>();
        for (ScoredAnswer r : results) {
            byCategory.computeIfAbsent(r.question().category(), k -> new java.util.ArrayList<>()).add(r);
        }
        for (var entry : byCategory.entrySet()) {
            out.printf("%-22s avg correctness=%.2f  avg faithfulness=%.2f  (n=%d)%n",
                    entry.getKey(), average(entry.getValue(), ScoredAnswer::correctness),
                    average(entry.getValue(), ScoredAnswer::faithfulness), entry.getValue().size());
        }

        long hallucinationRisks = results.stream().filter(ScoredAnswer::isHallucinationRisk).count();
        out.println("-".repeat(100));
        out.printf("OVERALL  avg correctness=%.2f/2  avg faithfulness=%.2f/2  hallucination risks=%d/%d%n",
                average(results, ScoredAnswer::correctness), average(results, ScoredAnswer::faithfulness),
                hallucinationRisks, results.stream().filter(r -> r.question().unanswerable()).count());
        out.println("=".repeat(100));
    }

    /** Appends one row per question so you can diff runs over time (before/after a pipeline change). */
    public Path writeCsv(List<ScoredAnswer> results, String runLabel, String timestamp) throws IOException {
        Path dir = Path.of("results");
        Files.createDirectories(dir);
        Path file = dir.resolve("scorecard-%s-%s.csv".formatted(runLabel, timestamp));

        StringBuilder csv = new StringBuilder("id,category,correctness,faithfulness,hallucination_risk,question\n");
        for (ScoredAnswer r : results) {
            csv.append(String.join(",",
                    r.question().id(),
                    r.question().category(),
                    String.valueOf(r.correctness()),
                    String.valueOf(r.faithfulness()),
                    String.valueOf(r.isHallucinationRisk()),
                    "\"" + r.question().question().replace("\"", "'") + "\""
            )).append("\n");
        }
        Files.writeString(file, csv.toString());
        return file;
    }

    /** Same content as {@link #printReport}, saved alongside the CSV so you can review judge rationale later. */
    public Path writeReport(List<ScoredAnswer> results, String runLabel, String timestamp) throws IOException {
        Path dir = Path.of("results");
        Files.createDirectories(dir);
        Path file = dir.resolve("report-%s-%s.txt".formatted(runLabel, timestamp));

        try (PrintStream out = new PrintStream(Files.newOutputStream(file))) {
            printReport(results, out, 0);
        }
        return file;
    }

    public static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static double average(List<ScoredAnswer> results, java.util.function.ToDoubleFunction<ScoredAnswer> f) {
        return results.stream().mapToDouble(f).average().orElse(0);
    }

    private static String truncate(String s, int max) {
        String flattened = s.replace("\n", " ");
        return flattened.length() <= max ? flattened : flattened.substring(0, max) + "...";
    }
}
