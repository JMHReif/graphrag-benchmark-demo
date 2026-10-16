package com.neo4j.webinar.retrieval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BONUS / stretch comparison, not needed for the main demo flow.
 *
 * Same vector-seed step as {@link GraphRagContentRetriever}, but stops
 * there -- no graph expansion. Swapping this in for the GraphRAG retriever
 * and re-running the benchmark is the fastest way to *show* (not just
 * claim) what the graph half is buying you: watch the multi-hop questions
 * (Q3, Q4, Q5, Q9, Q12 in benchmark_questions.json) drop in score while the
 * simple single-fact questions stay roughly the same.
 */
public class VectorOnlyContentRetriever implements ContentRetriever {

    private static final String VECTOR_INDEX = "chunkEmbeddings";

    private final Driver driver;
    private final EmbeddingModel embeddingModel;
    private final int maxSeedChunks;

    public VectorOnlyContentRetriever(Driver driver, EmbeddingModel embeddingModel, int maxSeedChunks) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
        this.maxSeedChunks = maxSeedChunks;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();

        try (Session session = driver.session()) {
            List<Record> seeds = session.run("""
                    CALL db.index.vector.queryNodes($indexName, $k, $queryVector)
                    YIELD node, score
                    RETURN node.text AS text, score
                    """, Map.of(
                    "indexName", VECTOR_INDEX,
                    "k", maxSeedChunks,
                    "queryVector", queryEmbedding.vectorAsList()
            )).list();

            return seeds.stream()
                    .map(r -> Content.from(r.get("text").asString()))
                    .collect(Collectors.toList());
        }
    }
}
