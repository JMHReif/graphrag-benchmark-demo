package com.neo4j.webinar;

import com.neo4j.webinar.bench.BenchmarkQuestion;
import com.neo4j.webinar.bench.BenchmarkRunner;
import com.neo4j.webinar.bench.LlmJudgeScorer;
import com.neo4j.webinar.bench.Scorer;
import com.neo4j.webinar.bench.ScoredAnswer;
import com.neo4j.webinar.bench.TypeSafeJudgeScorer;
import com.neo4j.webinar.ingest.GraphSeeder;
import com.neo4j.webinar.retrieval.GraphRagContentRetriever;
import com.neo4j.webinar.retrieval.VectorOnlyContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point.
 *
 *   mvn compile exec:java -Dexec.args=seed     -- (re)seed the demo graph
 *   mvn compile exec:java                      -- GraphRAG: sanity question + full benchmark
 *   mvn compile exec:java -Dexec.args=vector    -- Vector-only baseline: sanity question + full benchmark
 *   mvn compile exec:java -Dexec.args=jev       -- GraphRAG, graded by the Jev judge instead of the default judge
 *
 * Requires a running Neo4j (5.x, with vector index support) and an
 * OPENAI_API_KEY. See README.md for both. The `jev` run additionally
 * requires JEV_API_KEY (see Config).
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Config.requireOpenAiKey();

        Driver driver = GraphDatabase.driver(Config.NEO4J_URI, AuthTokens.basic(Config.NEO4J_USER, Config.NEO4J_PASSWORD));
        driver.verifyConnectivity();

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(Config.OPENAI_API_KEY)
                .modelName(Config.EMBEDDING_MODEL)
                .build();

        // NOTE: if this doesn't compile against the langchain4j version you
        // pinned in pom.xml, the builder method may still be named
        // .chatLanguageModel(...) / the type ChatLanguageModel instead of
        // ChatModel on older releases -- see README.md's version note.
        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(Config.OPENAI_API_KEY)
                .modelName(Config.CHAT_MODEL)
                .temperature(0.0)
                .build();

        if (args.length > 0 && args[0].equalsIgnoreCase("seed")) {
            new GraphSeeder(driver, embeddingModel).seed();
            driver.close();
            return;
        }

        boolean vectorOnly = args.length > 0 && args[0].equalsIgnoreCase("vector");
        boolean jevJudge = args.length > 0 && args[0].equalsIgnoreCase("jev");
        int maxSeedChunks = 4;
        ContentRetriever retriever = vectorOnly
                ? new VectorOnlyContentRetriever(driver, embeddingModel, maxSeedChunks)
                : new GraphRagContentRetriever(driver, embeddingModel, maxSeedChunks);
        String runLabel = (vectorOnly ? "vectoronly" : "graphrag") + (jevJudge ? "-judge-jev" : "");

        Scorer scorer;
        if (jevJudge) {
            Config.requireJevKey();
            scorer = new TypeSafeJudgeScorer(Config.JEV_API_KEY, Config.JEV_BASE_URL, Config.JEV_MODEL);
        } else {
            scorer = new LlmJudgeScorer(chatModel);
        }

        SupportAssistant assistant = AiServices.builder(SupportAssistant.class)
                .chatModel(chatModel)
                .contentRetriever(retriever)
                .build();

        System.out.println("--- Live sanity check (" + runLabel + ") ---");
        String sample = "Which release fixed the SAML SSO login timeout bug?";
        System.out.println("Q: " + sample);
        System.out.println("A: " + assistant.answer(sample));

        System.out.println("\n--- Running full benchmark ---");
        BenchmarkRunner runner = new BenchmarkRunner(assistant::answer, scorer);
        List<BenchmarkQuestion> questions = runner.loadQuestions();
        List<ScoredAnswer> results = runner.run(questions);
        runner.printReport(results, System.out);

        String timestamp = BenchmarkRunner.timestamp();
        Path csv = runner.writeCsv(results, runLabel, timestamp);
        Path report = runner.writeReport(results, runLabel, timestamp);
        System.out.println("Scorecard written to " + csv.toAbsolutePath());
        System.out.println("Report written to " + report.toAbsolutePath());

        driver.close();
    }
}
