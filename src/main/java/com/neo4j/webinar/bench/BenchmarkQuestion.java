package com.neo4j.webinar.bench;

/**
 * One row of the benchmark. This is the artifact meant to keep growing:
 * every time production surfaces a wrong or ugly answer, add a row here
 * instead of just fixing the one case and moving on.
 *
 * @param id            stable id, e.g. "Q1" -- keep ids stable across edits
 *                      so score-over-time comparisons stay meaningful
 * @param category      one of: simple_factual, multi_hop, comparison_aggregation,
 *                      temporal, unanswerable
 * @param question      the question text sent to the assistant
 * @param goldAnswer    a human-written reference answer (or, for
 *                      unanswerable questions, a note on what correct
 *                      refusal looks like)
 * @param unanswerable  true if the correct behavior is to decline rather
 *                      than answer -- these are the questions that catch
 *                      hallucination, not just wrong facts
 */
public record BenchmarkQuestion(
        String id,
        String category,
        String question,
        String goldAnswer,
        boolean unanswerable
) {
}
