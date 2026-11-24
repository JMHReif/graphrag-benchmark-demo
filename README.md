# GraphRAG Benchmark Demo

Companion code for the "Benchmarking RAG for Accuracy" webinar. A small
GraphRAG pipeline (LangChain4j + Neo4j) over a made-up software-support
knowledge graph, plus a benchmark harness that scores it across question
types with an LLM-as-judge.

## What's here

```
src/main/java/com/neo4j/webinar/
  Config.java                        env-var driven config (Neo4j + OpenAI)
  SupportAssistant.java               the LangChain4j AiServices interface
  Main.java                           entry point: seed, sanity-check, benchmark
  ingest/GraphSeeder.java             loads the demo graph + embeds chunks
  retrieval/GraphRagContentRetriever.java   vector seed + 1-hop graph expansion
  retrieval/VectorOnlyContentRetriever.java bonus: vector-only baseline for comparison
  bench/BenchmarkQuestion.java        one row of the benchmark
  bench/ScoredAnswer.java             one graded result
  bench/LlmJudgeScorer.java           LLM-as-judge grading
  bench/BenchmarkRunner.java          runs + reports + writes CSV
src/main/resources/benchmark_questions.json   the 12-question benchmark set
```

The demo domain: two fictional products (Aurora, Beacon), their releases,
bugs, features, customers, and support tickets, plus two policy documents.
Small enough to hold in your head during the talk, connected enough to need
graph traversal for the interesting questions.

## Prerequisites

- Java 17+
- Maven 3.9+
- A running Neo4j 5.18+ (vector indexes need to be available) -- easiest via Docker:

  ```bash
  docker run -d --name neo4j-graphrag \
    -p 7474:7474 -p 7687:7687 \
    -e NEO4J_AUTH=neo4j/letmein123 \
    neo4j:5.26
  ```

- An OpenAI API key (or swap `OpenAiChatModel` / `OpenAiEmbeddingModel` in
  `Main.java` for another `langchain4j-<provider>` module -- Ollama is a
  good no-API-key option if you'd rather not depend on network access
  during the live demo).

## Setup

```bash
export OPENAI_API_KEY=sk-...
# optional overrides (these are the defaults):
export NEO4J_URI=bolt://localhost:7687
export NEO4J_USER=neo4j
export NEO4J_PASSWORD=letmein123

mvn compile exec:java -Dexec.args=seed   # loads the demo graph, one time
mvn compile exec:java                    # runs the sanity check + full benchmark
mvn compile exec:java -Dexec.args=vector # full benchmark with vector-only RAG
mvn compile exec:java -Dexec.args=jev    # full benchmark with Jev (system one model) as the judge
```

Re-run the seed step any time; everything is `MERGE`-based so it's safe to
run twice.

## Before you go on stage

- **Pre-seed the graph** the night before, not live -- seeding calls the
  embedding API 6 times and isn't interesting to watch. What's interesting
  is the retriever and the scorer, which is what's worth live-coding.
- **Record a backup run.** Capture the terminal output of a full,
  successful `mvn compile exec:java` run (screen recording or just the
  scrollback) in case live network/API flakiness eats your demo window.
- **Warm the JVM / Maven cache** beforehand: run the full flow once before
  the session so dependency downloads and JIT warm-up don't eat your
  30 minutes.
- Double-check the version pins in `pom.xml` -- see the comment block
  there. `langchain4j-community-neo4j*` are beta artifacts that ship
  frequently; a mismatch between the core `langchain4j` version and the
  community modules is the most common source of a `NoSuchMethodError` at
  runtime, and won't necessarily show up at compile time.

## Suggested live-coding path (see the talk outline for timing)

1. Show the graph model in Neo4j Browser (`:play` or just a `MATCH (n) RETURN n LIMIT 50`)
   -- 30 seconds, sets up why graph context matters.
2. Walk through `GraphRagContentRetriever.retrieve()` -- this is the piece
   that makes it *GraphRAG* instead of vector RAG: vector search finds the
   seed chunk, then one Cypher query pulls the connected facts a text
   chunk can't express on its own.
3. Run the sanity-check question, show the answer.
4. Walk through `LlmJudgeScorer.score()` and `BenchmarkRunner` -- this is
   the reusable eval harness, independent of what's under test.
5. Run the full benchmark, look at the per-category breakdown together,
   and point at the "HALLUCINATION RISK" flags if any come up.
6. (Bonus, if time allows): swap `GraphRagContentRetriever` for
   `VectorOnlyContentRetriever` in `Main.java`, re-run, and diff the two
   CSVs in `results/` -- this is the moment that makes the abstract's
   promise concrete: the benchmark tells you *where* a pipeline change
   helped, not just whether the demo still "looks right."

## Extending the benchmark

Add rows to `src/main/resources/benchmark_questions.json`. Keep `id`
values stable once you've used them in a report, so score-over-time
comparisons in `results/*.csv` stay meaningful as the question set grows.

## Resources
* [Neo4j webinar (Nov 2026)](https://speakerdeck.com/jmhreif/ragbenchmark-oct2026)
