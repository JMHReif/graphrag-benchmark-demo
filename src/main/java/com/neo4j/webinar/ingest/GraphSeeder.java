package com.neo4j.webinar.ingest;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.util.List;
import java.util.Map;

/**
 * Loads a small "software support" knowledge graph into Neo4j:
 *
 *   (:Product)-[:HAS_FEATURE]->(:Feature)
 *   (:Release)-[:OF_PRODUCT]->(:Product)
 *   (:Release)-[:FIXES]->(:Bug)-[:AFFECTS]->(:Feature)
 *   (:Customer)-[:SUBSCRIBES_TO]->(:Product)
 *   (:Ticket)-[:FILED_BY]->(:Customer)
 *   (:Ticket)-[:REPORTS]->(:Bug)
 *   (:Chunk)-[:MENTIONS]->(any of the above)
 *
 * Chunk nodes hold the free-text (release notes, policy text) that gets
 * embedded for vector search. Everything else is the graph context that a
 * pure vector-RAG chunk can't see on its own -- which customers were
 * affected, which release fixed a bug, whether it's still open, etc.
 * That's the "graph" half of GraphRAG.
 *
 * Deliberately over-explicit and re-runnable (MERGE everywhere) so you can
 * re-seed mid-demo without worrying about duplicates.
 */
public class GraphSeeder {

    private final Driver driver;
    private final EmbeddingModel embeddingModel;

    public GraphSeeder(Driver driver, EmbeddingModel embeddingModel) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
    }

    public void seed() {
        try (Session session = driver.session()) {
            createConstraintsAndIndex(session);
            seedProductsAndFeatures(session);
            seedReleasesAndBugs(session);
            seedCustomersAndTickets(session);
            seedPolicies(session);
            seedChunks(session);
        }
        System.out.println("Graph seeded: products, features, releases, bugs, customers, tickets, policies, chunks.");
    }

    private void createConstraintsAndIndex(Session session) {
        session.run("CREATE CONSTRAINT product_name IF NOT EXISTS FOR (p:Product) REQUIRE p.name IS UNIQUE");
        session.run("CREATE CONSTRAINT bug_id IF NOT EXISTS FOR (b:Bug) REQUIRE b.id IS UNIQUE");
        session.run("CREATE CONSTRAINT release_version IF NOT EXISTS FOR (r:Release) REQUIRE r.version IS UNIQUE");
        session.run("CREATE CONSTRAINT customer_name IF NOT EXISTS FOR (c:Customer) REQUIRE c.name IS UNIQUE");
        session.run("CREATE CONSTRAINT ticket_id IF NOT EXISTS FOR (t:Ticket) REQUIRE t.id IS UNIQUE");
        session.run("CREATE CONSTRAINT chunk_id IF NOT EXISTS FOR (c:Chunk) REQUIRE c.id IS UNIQUE");
        session.run("""
                CREATE VECTOR INDEX chunkEmbeddings IF NOT EXISTS
                FOR (c:Chunk) ON (c.embedding)
                OPTIONS {indexConfig: {
                  `vector.dimensions`: $dim,
                  `vector.similarity_function`: 'cosine'
                }}
                """, Map.of("dim", embeddingModel.dimension() > 0 ? embeddingModel.dimension() : 1536));
    }

    private void seedProductsAndFeatures(Session session) {
        session.run("""
                UNWIND $rows AS row
                MERGE (p:Product {name: row.product})
                MERGE (f:Feature {name: row.feature})
                MERGE (f)-[:PART_OF]->(p)
                """, Map.of("rows", List.of(
                Map.of("product", "Aurora", "feature", "SSO Login"),
                Map.of("product", "Aurora", "feature", "Scheduled Workflows"),
                Map.of("product", "Aurora", "feature", "Webhook Triggers"),
                Map.of("product", "Beacon", "feature", "Usage Dashboards")
        )));
    }

    private void seedReleasesAndBugs(Session session) {
        session.run("""
                UNWIND $rows AS row
                MERGE (r:Release {version: row.version})
                  ON CREATE SET r.releaseDate = row.releaseDate
                MERGE (p:Product {name: row.product})
                MERGE (r)-[:OF_PRODUCT]->(p)
                """, Map.of("rows", List.of(
                Map.of("version", "Aurora 3.0", "product", "Aurora", "releaseDate", "2026-03-01"),
                Map.of("version", "Aurora 3.1", "product", "Aurora", "releaseDate", "2026-05-15"),
                Map.of("version", "Aurora 3.2", "product", "Aurora", "releaseDate", "2026-07-01"),
                Map.of("version", "Beacon 1.4", "product", "Beacon", "releaseDate", "2026-06-10")
        )));

        session.run("""
                UNWIND $rows AS row
                MERGE (b:Bug {id: row.id})
                  ON CREATE SET b.title = row.title, b.severity = row.severity, b.status = row.status
                MERGE (f:Feature {name: row.feature})
                MERGE (b)-[:AFFECTS]->(f)
                WITH b, row
                OPTIONAL MATCH (r:Release {version: row.fixedIn})
                FOREACH (_ IN CASE WHEN r IS NOT NULL THEN [1] ELSE [] END |
                  MERGE (r)-[:FIXES]->(b)
                )
                """, Map.of("rows", List.of(
                Map.of("id", "BUG-101", "title", "SAML SSO logins time out after 30 seconds",
                        "severity", "high", "status", "fixed", "feature", "SSO Login", "fixedIn", "Aurora 3.2"),
                Map.of("id", "BUG-102", "title", "Scheduled workflows skip a run across DST transitions",
                        "severity", "medium", "status", "fixed", "feature", "Scheduled Workflows", "fixedIn", "Aurora 3.1"),
                Map.of("id", "BUG-103", "title", "Webhook retries duplicate events on slow endpoints",
                        "severity", "high", "status", "open", "feature", "Webhook Triggers"),
                Map.of("id", "BUG-104", "title", "Usage dashboard totals undercount deleted users",
                        "severity", "low", "status", "fixed", "feature", "Usage Dashboards", "fixedIn", "Beacon 1.4")
        )));
    }

    private void seedCustomersAndTickets(Session session) {
        session.run("""
                UNWIND $rows AS row
                MERGE (c:Customer {name: row.name})
                  ON CREATE SET c.tier = row.tier
                WITH c, row
                UNWIND row.products AS productName
                MERGE (p:Product {name: productName})
                MERGE (c)-[:SUBSCRIBES_TO]->(p)
                """, Map.of("rows", List.of(
                Map.of("name", "Acme Corp", "tier", "Enterprise", "products", List.of("Aurora")),
                Map.of("name", "Globex", "tier", "Growth", "products", List.of("Aurora", "Beacon")),
                Map.of("name", "Initech", "tier", "Enterprise", "products", List.of("Beacon")),
                Map.of("name", "Umbrella Labs", "tier", "Starter", "products", List.of("Aurora"))
        )));

        session.run("""
                UNWIND $rows AS row
                MERGE (t:Ticket {id: row.id})
                  ON CREATE SET t.status = row.status, t.createdDate = row.createdDate
                MERGE (cust:Customer {name: row.customer})
                MERGE (b:Bug {id: row.bug})
                MERGE (t)-[:FILED_BY]->(cust)
                MERGE (t)-[:REPORTS]->(b)
                """, Map.of("rows", List.of(
                Map.of("id", "TCK-501", "customer", "Acme Corp", "bug", "BUG-101", "status", "closed", "createdDate", "2026-06-20"),
                Map.of("id", "TCK-502", "customer", "Globex", "bug", "BUG-101", "status", "closed", "createdDate", "2026-06-22"),
                Map.of("id", "TCK-503", "customer", "Umbrella Labs", "bug", "BUG-102", "status", "closed", "createdDate", "2026-04-10"),
                Map.of("id", "TCK-504", "customer", "Globex", "bug", "BUG-103", "status", "open", "createdDate", "2026-08-01"),
                Map.of("id", "TCK-505", "customer", "Initech", "bug", "BUG-104", "status", "closed", "createdDate", "2026-06-01")
        )));
    }

    private void seedPolicies(Session session) {
        session.run("""
                UNWIND $rows AS row
                MERGE (p:Policy {name: row.name})
                  ON CREATE SET p.text = row.text
                """, Map.of("rows", List.of(
                Map.of("name", "Software Support SLA",
                        "text", "Enterprise customers receive a 4-hour first response time. " +
                                "Growth customers receive a 1 business day first response time. " +
                                "Starter customers receive a 2 business day first response time. " +
                                "This SLA applies to all software products."),
                Map.of("name", "Subscription Refund Policy",
                        "text", "Annual software subscription plans (Aurora and Beacon) are refundable " +
                                "within 30 days of purchase if usage is under 10% of the plan's included quota. " +
                                "This policy covers software subscriptions only.")
        )));
    }

    /**
     * The "vector" half of GraphRAG: free-text chunks, each MENTIONS-linked
     * to the graph entities it's about, embedded for similarity search.
     */
    private void seedChunks(Session session) {
        record ChunkSeed(String id, String text, List<String> mentionsBugs, List<String> mentionsReleases,
                          List<String> mentionsFeatures, List<String> mentionsPolicies) {
        }

        List<ChunkSeed> chunks = List.of(
                new ChunkSeed("C1",
                        "Release notes -- Aurora 3.2 (July 1, 2026): This release fixes BUG-101, a critical issue " +
                                "where SAML-based SSO logins would time out after 30 seconds. Affected customers " +
                                "should no longer see login failures during SAML redirects.",
                        List.of("BUG-101"), List.of("Aurora 3.2"), List.of("SSO Login"), List.of()),
                new ChunkSeed("C2",
                        "Release notes -- Aurora 3.1 (May 15, 2026): Fixed BUG-102, where scheduled workflows " +
                                "would silently skip a run during daylight-saving transitions in certain timezones.",
                        List.of("BUG-102"), List.of("Aurora 3.1"), List.of("Scheduled Workflows"), List.of()),
                new ChunkSeed("C3",
                        "Known issue: webhook retries can duplicate events (BUG-103) when a downstream endpoint " +
                                "responds slowly. Status: open, no fix scheduled yet.",
                        List.of("BUG-103"), List.of(), List.of("Webhook Triggers"), List.of()),
                new ChunkSeed("C4",
                        "Release notes -- Beacon 1.4 (June 10, 2026): Corrected BUG-104, where usage dashboards " +
                                "undercounted totals for deleted user accounts.",
                        List.of("BUG-104"), List.of("Beacon 1.4"), List.of("Usage Dashboards"), List.of()),
                new ChunkSeed("C5",
                        "Support SLA policy: response times vary by tier and apply to all software products.",
                        List.of(), List.of(), List.of(), List.of("Software Support SLA")),
                new ChunkSeed("C6",
                        "Subscription refund policy: annual software subscription plans may be refunded within " +
                                "30 days under certain usage conditions. Covers software subscriptions only.",
                        List.of(), List.of(), List.of(), List.of("Subscription Refund Policy"))
        );

        for (ChunkSeed chunk : chunks) {
            Embedding embedding = embeddingModel.embed(chunk.text()).content();

            session.run("""
                    MERGE (c:Chunk {id: $id})
                      ON CREATE SET c.text = $text
                    WITH c
                    CALL db.create.setNodeVectorProperty(c, 'embedding', $embedding)
                    RETURN c
                    """, Map.of(
                    "id", chunk.id(),
                    "text", chunk.text(),
                    "embedding", embedding.vectorAsList()
            ));

            linkMentions(session, chunk.id(), "Bug", "id", chunk.mentionsBugs());
            linkMentions(session, chunk.id(), "Release", "version", chunk.mentionsReleases());
            linkMentions(session, chunk.id(), "Feature", "name", chunk.mentionsFeatures());
            linkMentions(session, chunk.id(), "Policy", "name", chunk.mentionsPolicies());
        }
    }

    private void linkMentions(Session session, String chunkId, String label, String keyProperty, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        session.run(String.format("""
                UNWIND $keys AS key
                MATCH (c:Chunk {id: $chunkId})
                MATCH (e:%s {%s: key})
                MERGE (c)-[:MENTIONS]->(e)
                """, label, keyProperty),
                Values.parameters("keys", keys, "chunkId", chunkId));
    }
}
