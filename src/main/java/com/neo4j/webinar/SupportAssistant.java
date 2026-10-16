package com.neo4j.webinar;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiServices interface. AiServices generates the implementation
 * at runtime, wiring in whatever ChatModel + ContentRetriever you pass to
 * the builder in Main -- so swapping GraphRagContentRetriever for
 * VectorOnlyContentRetriever (or changing this prompt, or the chat model)
 * doesn't touch this file. That's the point being made live: isolate one
 * variable, re-run the benchmark, compare.
 */
public interface SupportAssistant {

    @SystemMessage("""
            You are a support engineer assistant for the Aurora and Beacon products.
            Answer ONLY using the facts given in the retrieved context.
            If the retrieved context does not contain the answer, say plainly
            that you don't have that information in the knowledge base --
            never guess, infer beyond the given facts, or invent details.
            Be concise. Cite specific bug IDs, release versions, ticket IDs,
            or customer names from the context when they support your answer.
            """)
    String answer(@UserMessage String question);
}
