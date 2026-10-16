package com.neo4j.webinar.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grades one answer against its gold reference using Jev (TypeSafe AI's
 * "System One" model) via its documented HTTP API -- no SDK dependency,
 * just java.net.http.HttpClient + the Jackson dependency already on the
 * classpath.
 *
 * Unlike {@link LlmJudgeScorer}, Jev never generates free text: correctness
 * and faithfulness are asked as typed "score" questions (a probability-weighted
 * value across named levels, e.g. 1.35 -- not rounded to a level index), and
 * appropriate-refusal is asked as a typed "noul" (yes/no) question. Jev itself
 * has no rationale field, so {@link ScoredAnswer#rationale()} is synthesized
 * from the response's own `legend`/`probabilities` -- the level Jev leaned
 * towards, and how confident it was -- so a disagreement with the other judge
 * is at least legible instead of just a bare number.
 */
public class TypeSafeJudgeScorer implements Scorer {

    private static final List<String> CORRECTNESS_LEVELS = List.of(
            "wrong or missing the key facts",
            "partially correct",
            "fully correct and consistent with the gold reference"
    );

    private static final List<String> FAITHFULNESS_LEVELS = List.of(
            "states facts not supported by any real context",
            "mostly grounded, minor unsupported additions",
            "fully grounded"
    );

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public TypeSafeJudgeScorer(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public ScoredAnswer score(BenchmarkQuestion question, String actualAnswer) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", question.question());
        state.put("category", question.category());
        state.put("unanswerable", question.unanswerable());
        state.put("goldAnswer", question.goldAnswer());
        state.put("actualAnswer", actualAnswer);

        Map<String, Object> body = Map.of(
                "state", state,
                "model", model,
                "questions", Map.of(
                        "correctness", scoreQuestion(
                                "Be strict: only award credit when the system's actual answer actually "
                                        + "contains the key facts from the gold reference, not just plausible-sounding text. "
                                        + "How correct is the actual answer?",
                                CORRECTNESS_LEVELS),
                        "faithfulness", scoreQuestion(
                                "How well is the actual answer grounded in real, supported context, "
                                        + "as opposed to fabricated or invented detail?",
                                FAITHFULNESS_LEVELS),
                        "refusal", noulQuestion(
                                "This question is intentionally unanswerable from the knowledge base. "
                                        + "Did the system plainly decline instead of guessing?")
                )
        );

        JsonNode answers = call(body).path("answers");
        JsonNode correctnessAnswer = answers.path("correctness");
        JsonNode faithfulnessAnswer = answers.path("faithfulness");

        boolean appropriateRefusal = !question.unanswerable()
                || answers.path("refusal").path("noul").asDouble(0) >= 0.5;

        String rationale = "correctness: %s | faithfulness: %s".formatted(
                describeScoreAnswer(correctnessAnswer), describeScoreAnswer(faithfulnessAnswer));

        return new ScoredAnswer(
                question,
                actualAnswer,
                correctnessAnswer.path("score").asDouble(0),
                faithfulnessAnswer.path("score").asDouble(0),
                appropriateRefusal,
                rationale
        );
    }

    /** e.g. "1.55 (leaning \"fully correct and consistent with the gold reference\", 60%)" */
    private static String describeScoreAnswer(JsonNode scoreAnswer) {
        JsonNode legend = scoreAnswer.path("legend");
        JsonNode probabilities = scoreAnswer.path("probabilities");

        String topLevel = null;
        double topProbability = -1;
        Iterator<Map.Entry<String, JsonNode>> fields = probabilities.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            double probability = entry.getValue().asDouble();
            if (probability > topProbability) {
                topProbability = probability;
                topLevel = entry.getKey();
            }
        }

        String leaning = topLevel == null ? "unknown" : legend.path(topLevel).asText(topLevel);
        return "%.2f (leaning \"%s\", %.0f%%)".formatted(scoreAnswer.path("score").asDouble(0), leaning, topProbability * 100);
    }

    private static Map<String, Object> scoreQuestion(String instructions, List<String> levels) {
        return Map.of("type", "score", "instructions", instructions, "criteria", levels);
    }

    private static Map<String, Object> noulQuestion(String instructions) {
        return Map.of("type", "noul", "instructions", instructions, "criteria", Map.of(
                "true", "the system plainly declined / said it doesn't have the information",
                "false", "the system fabricated or guessed an answer"
        ));
    }

    private JsonNode call(Map<String, Object> body) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/systemone"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            return mapper.readTree(sendWithRetry(request).body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Jev API call failed", e);
        }
    }

    /** The Jev docs ask callers to back off on 429 (rate limited) / 529 (overloaded). */
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        long backoffMillis = 500;
        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 429 && status != 529) {
                if (status >= 400) {
                    throw new IllegalStateException("Jev API error " + status + ": " + response.body());
                }
                return response;
            }
            if (attempt == 4) {
                throw new IllegalStateException("Jev API still returning " + status + " after 4 attempts");
            }
            Thread.sleep(backoffMillis);
            backoffMillis *= 2;
        }
        throw new IllegalStateException("unreachable");
    }
}
