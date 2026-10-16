package com.neo4j.webinar.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Grades one answer against its gold reference using an LLM as the judge.
 *
 * This is deliberately a *separate* model call from the assistant being
 * tested -- the judge prompt is short, strict, and always asks for the same
 * three numbers, which is what makes the benchmark repeatable: run it
 * again after a pipeline change and the score moves because the pipeline
 * changed, not because the grading did.
 */
public class LlmJudgeScorer implements Scorer {

    private final ChatModel judgeModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmJudgeScorer(ChatModel judgeModel) {
        this.judgeModel = judgeModel;
    }

    @Override
    public ScoredAnswer score(BenchmarkQuestion question, String actualAnswer) {
        String prompt = """
                You are grading a support assistant's answer against a gold reference.
                Be strict: only award full credit when the answer actually contains the
                key facts, not just plausible-sounding text.

                Question: %s
                Question category: %s
                Is this question intentionally unanswerable from the knowledge base?: %s
                Gold reference answer: %s

                System's actual answer: %s

                Score the system's answer:
                - correctness: 0 (wrong or missing the key facts), 1 (partially correct),
                  or 2 (fully correct and consistent with the gold reference)
                - faithfulness: 0 (states facts not supported by any real context),
                  1 (mostly grounded, minor unsupported additions), or 2 (fully grounded)
                - appropriate_refusal: for unanswerable questions, true only if the system
                  plainly declined instead of guessing; false if it fabricated an answer.
                  For answerable questions, always put true here (not applicable).

                Respond with ONLY minified JSON, no markdown fencing, no commentary,
                exactly this shape:
                {"correctness": 0, "faithfulness": 0, "appropriate_refusal": true, "rationale": "one sentence"}
                """.formatted(question.question(), question.category(), question.unanswerable(),
                question.goldAnswer(), actualAnswer);

        String raw = judgeModel.chat(prompt);
        JsonNode json = parseJson(raw);

        return new ScoredAnswer(
                question,
                actualAnswer,
                json.path("correctness").asDouble(0),
                json.path("faithfulness").asDouble(0),
                json.path("appropriate_refusal").asBoolean(!question.unanswerable()),
                json.path("rationale").asText("")
        );
    }

    /** Judge models occasionally wrap JSON in prose or a code fence; trim to the object. */
    private JsonNode parseJson(String raw) {
        String trimmed = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
        try {
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException("Judge did not return valid JSON:\n" + raw, e);
        }
    }
}
