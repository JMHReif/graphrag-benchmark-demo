package com.neo4j.webinar.bench;

/**
 * The result of running one {@link BenchmarkQuestion} through the pipeline
 * and having the LLM judge grade it.
 *
 * @param correctness         0 (wrong) to 2 (fully correct). Whole numbers from
 *                            {@link LlmJudgeScorer}; a continuous, probability-weighted
 *                            value (e.g. 1.35) from {@link TypeSafeJudgeScorer}.
 * @param faithfulness        0 (fabricated) to 2 (fully grounded); same scale note as correctness.
 * @param appropriateRefusal  for unanswerable questions: true if the system
 *                            correctly declined instead of guessing. Always
 *                            true (not applicable) for answerable questions.
 */
public record ScoredAnswer(
        BenchmarkQuestion question,
        String actualAnswer,
        double correctness,
        double faithfulness,
        boolean appropriateRefusal,
        String rationale
) {
    /** A hallucinated answer to a question that should have been declined. */
    public boolean isHallucinationRisk() {
        return question.unanswerable() && !appropriateRefusal;
    }
}
