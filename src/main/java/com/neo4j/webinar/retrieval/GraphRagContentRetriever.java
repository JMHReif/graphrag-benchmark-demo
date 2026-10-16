package com.neo4j.webinar.retrieval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The GraphRAG retriever: this is the piece that turns plain vector RAG into
 * *graph* RAG.
 *
 * <pre>
 *   1. Embed the question.
 *   2. Vector search over :Chunk.embedding to find the most relevant
 *      free-text passages ("seed" nodes) -- this is exactly what a
 *      vector-only pipeline would stop at.
 *   3. For each seed chunk, walk up to two hops out through the graph
 *      (Chunk -[:MENTIONS]-> entity -[any]- ...) to pull in connected
 *      facts a text chunk alone can't express: which release fixed a bug,
 *      which customers reported it (Bug -> Ticket -> Customer is two hops),
 *      whether it's still open, etc.
 *   4. Return chunk text + graph facts together as retrieved Content.
 * </pre>
 */
public class GraphRagContentRetriever implements ContentRetriever {

    private static final String VECTOR_INDEX = "chunkEmbeddings";

    private final Driver driver;
    private final EmbeddingModel embeddingModel;
    private final int maxSeedChunks;

    public GraphRagContentRetriever(Driver driver, EmbeddingModel embeddingModel, int maxSeedChunks) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
        this.maxSeedChunks = maxSeedChunks;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();
        List<Content> contents = new ArrayList<>();

        try (Session session = driver.session()) {
            List<Record> seeds = session.run("""
                    CALL db.index.vector.queryNodes($indexName, $k, $queryVector)
                    YIELD node, score
                    RETURN node.id AS id, node.text AS text, score
                    """, Map.of(
                    "indexName", VECTOR_INDEX,
                    "k", maxSeedChunks,
                    "queryVector", queryEmbedding.vectorAsList()
            )).list();

            for (Record seed : seeds) {
                String chunkId = seed.get("id").asString();
                String chunkText = seed.get("text").asString();
                String graphFacts = expandGraphFacts(session, chunkId);

                String combined = "Source passage [" + chunkId + "]: " + chunkText +
                        (graphFacts.isBlank() ? "" : "\nRelated graph facts:\n" + graphFacts);

                contents.add(Content.from(combined));
            }
        }
        return contents;
    }

    /**
     * Up to two-hop graph expansion from every entity a chunk MENTIONS.
     * Two hops matters for facts like Bug -[REPORTS]- Ticket -[FILED_BY]- Customer --
     * one hop only reaches the Ticket, never the Customer who actually filed it.
     */
    private String expandGraphFacts(Session session, String chunkId) {
        List<Record> rows = session.run("""
                MATCH (c:Chunk {id: $chunkId})-[:MENTIONS]->(entity)
                OPTIONAL MATCH path = (entity)-[rels*1..2]-(other)
                WHERE path IS NULL OR ALL(rel IN rels WHERE type(rel) <> 'MENTIONS')
                RETURN entity, path
                LIMIT 60
                """, Map.of("chunkId", chunkId)).list();

        // Group facts by the entity so the LLM sees "Bug X: fixed by Y, reported by Z" rather than a flat list.
        Map<String, Set<String>> factsByEntity = new LinkedHashMap<>();

        for (Record row : rows) {
            Node entity = row.get("entity").asNode();
            String entityLabel = describe(entity);
            factsByEntity.computeIfAbsent(entityLabel, k -> new LinkedHashSet<>());

            Value pathValue = row.get("path");
            if (pathValue.isNull()) {
                continue;
            }
            Path path = pathValue.asPath();
            StringBuilder chain = new StringBuilder();
            Node current = entity;
            for (Path.Segment segment : path) {
                Relationship rel = segment.relationship();
                Node next = segment.start().elementId().equals(current.elementId()) ? segment.end() : segment.start();
                boolean outgoing = rel.startNodeElementId().equals(current.elementId());
                String arrow = outgoing ? "-[" + rel.type() + "]->" : "<-[" + rel.type() + "]-";
                chain.append(chain.isEmpty() ? "" : " ").append(arrow).append(" ").append(describe(next));
                current = next;
            }

            factsByEntity.get(entityLabel).add(chain.toString());
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : factsByEntity.entrySet()) {
            sb.append("- ").append(entry.getKey()).append("\n");
            for (String fact : entry.getValue()) {
                sb.append("    ").append(fact).append("\n");
            }
        }
        return sb.toString();
    }

    /** Turns a graph node into a short, readable, LLM-friendly description. */
    private String describe(Node node) {
        String label = node.labels().iterator().hasNext() ? node.labels().iterator().next() : "Entity";
        Map<String, Object> props = node.asMap();

        return switch (label) {
            case "Bug" -> "Bug %s (\"%s\") - severity: %s, status: %s".formatted(
                    props.get("id"), props.get("title"), props.get("severity"), props.get("status"));
            case "Release" -> "Release %s (released %s)".formatted(props.get("version"), props.get("releaseDate"));
            case "Feature" -> "Feature: %s".formatted(props.get("name"));
            case "Product" -> "Product: %s".formatted(props.get("name"));
            case "Customer" -> "Customer %s (tier: %s)".formatted(props.get("name"), props.get("tier"));
            case "Ticket" -> "Ticket %s (status: %s, filed %s)".formatted(
                    props.get("id"), props.get("status"), props.get("createdDate"));
            case "Policy" -> "Policy \"%s\": %s".formatted(props.get("name"), props.get("text"));
            default -> label + " " + props;
        };
    }
}
