package com.neo4j.webinar;

/**
 * Tiny config helper so the demo runs with zero setup beyond a local Neo4j
 * and an OpenAI API key. Everything is overridable via environment
 * variables so you're not editing Java on stage.
 */
public final class Config {

    public static final String NEO4J_URI = env("NEO4J_URI", "bolt://localhost:7687");
    public static final String NEO4J_USER = env("NEO4J_USER", "neo4j");
    public static final String NEO4J_PASSWORD = env("NEO4J_PASSWORD", "letmein123");

    public static final String OPENAI_API_KEY = env("OPENAI_API_KEY", "");
    public static final String CHAT_MODEL = env("CHAT_MODEL", "gpt-4o-mini");
    public static final String EMBEDDING_MODEL = env("EMBEDDING_MODEL", "text-embedding-3-small");
    public static final int EMBEDDING_DIMENSION = Integer.parseInt(env("EMBEDDING_DIMENSION", "1536"));

    // Only required for the opt-in `-Dexec.args=jev` benchmark run -- see Main.
    public static final String JEV_API_KEY = env("JEV_API_KEY", "");
    public static final String JEV_BASE_URL = env("JEV_BASE_URL", "https://api.typesafe.ai");
    public static final String JEV_MODEL = env("JEV_MODEL", "jev-latest");

    private Config() {
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public static void requireOpenAiKey() {
        if (OPENAI_API_KEY.isBlank()) {
            throw new IllegalStateException(
                    "Set OPENAI_API_KEY before running the demo, e.g.:\n" +
                    "  export OPENAI_API_KEY=sk-...\n");
        }
    }

    public static void requireJevKey() {
        if (JEV_API_KEY.isBlank()) {
            throw new IllegalStateException(
                    "Set JEV_API_KEY before running with the Jev judge, e.g.:\n" +
                    "  export JEV_API_KEY=...\n");
        }
    }
}
