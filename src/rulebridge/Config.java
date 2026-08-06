package rulebridge;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.Scanner;

/**
 * All configuration in one place.
 *
 * - Shared, non-secret settings come from ./rulebridge.properties (checked into git).
 * - The Gemini API key follows a strict precedence for production safety:
 *     1. GEMINI_API_KEY environment variable - always wins if set.
 *     2. If unset and auth.require-env-key=true (the default) -> fail fast with a clear error.
 *     3. If unset and auth.require-env-key=false (local dev only) -> saved per-user key from
 *        ~/.rulebridge/users.properties, or an interactive prompt if nothing is saved yet.
 *        The key is only ever written to disk if auth.persist-interactive-key=true.
 */
public class Config {

    // ---- shared settings (override via rulebridge.properties) ----
    private String chromaHost = "localhost";
    private int chromaPort = 8000;
    private String chromaTenant = "default_tenant";
    private String chromaDatabase = "default_database";
    private String chromaCollection = "rules_collection";
    private String rejectedCollection = "rules_rejected";
    private String modelPath = "D:/models/bge-m3";
    private int embeddingBatchSize = 15;
    private String excelFilePath = "D:/projects/rag-java-migration/Master_Rules_Audit_Report.xlsx";
    private String geminiModel = "gemini-3.5-flash-lite";
    private double geminiTemperature = 0.1;
    private int geminiMaxTokens = 500;
    private int defaultTopK = 3;
    private boolean deduplicate = true;

    // ---- resilience settings ----
    private int retryMaxAttempts = 4;
    private long retryBaseDelayMs = 400;
    private int chromaConnectTimeoutSec = 10;
    private int chromaReadTimeoutSec = 30;
    private int geminiConnectTimeoutSec = 15;
    private int geminiReadTimeoutSec = 60;

    // ---- API key policy ----
    private boolean requireEnvKey = true;
    private boolean persistInteractiveKey = false;

    // ---- per-user ----
    private String userId;
    private String geminiApiKey;

    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".rulebridge");
    private static final Path USERS_FILE = CONFIG_DIR.resolve("users.properties");
    private static final Path SHARED_FILE = Paths.get("rulebridge.properties");

    /**
     * Loads shared settings, resolves the user id, and resolves the Gemini API key
     * according to the precedence described above.
     */
    public static Config loadInteractive(Scanner sc) throws IOException {
        Config c = new Config();
        c.applySharedOverrides();
        Files.createDirectories(CONFIG_DIR);

        String osUser = System.getProperty("user.name", "default");
        System.out.print("User id [" + osUser + "]: ");
        String typed = sc.nextLine();
        c.userId = (typed == null || typed.trim().isEmpty()) ? osUser : typed.trim();

        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            c.geminiApiKey = validateKey(envKey);
            System.out.println("Using GEMINI_API_KEY from environment (recommended for production).");
            return c;
        }

        if (c.requireEnvKey) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY environment variable is not set.\n" +
                            "RuleBridge requires each user to provide their own key via this variable " +
                            "(export GEMINI_API_KEY=... on Linux/Mac, $env:GEMINI_API_KEY=\"...\" or " +
                            "setx GEMINI_API_KEY \"...\" on Windows), then restart.\n" +
                            "If you are running a local/dev instance and want to allow a saved or interactively " +
                            "typed key instead, set auth.require-env-key=false in rulebridge.properties.");
        }

        // Dev-only fallback path: saved key or interactive prompt.
        Properties users = new Properties();
        if (Files.exists(USERS_FILE)) {
            try (InputStream in = Files.newInputStream(USERS_FILE)) {
                users.load(in);
            }
        }

        String key = users.getProperty(c.userId + ".gemini_api_key");
        if (key == null || key.trim().isEmpty()) {
            key = promptForApiKey(c.userId, sc);
            if (c.persistInteractiveKey) {
                users.setProperty(c.userId + ".gemini_api_key", key);
                try (OutputStream out = Files.newOutputStream(USERS_FILE)) {
                    users.store(out, "RuleBridge per-user settings (dev mode) - do not commit this file");
                }
                System.out.println("API key saved to " + USERS_FILE + " for future runs (dev mode).");
            } else {
                System.out.println("Key will NOT be persisted (auth.persist-interactive-key=false). " +
                        "You will be asked again next run.");
            }
        } else {
            System.out.println("Loaded saved API key for user '" + c.userId + "' (dev mode).");
        }
        c.geminiApiKey = validateKey(key);
        return c;
    }

    private static String promptForApiKey(String userId, Scanner sc) {
        Console console = System.console();
        System.out.println("No Gemini API key found for user '" + userId + "'.");
        String key;
        if (console != null) {
            char[] chars = console.readPassword("Enter your Gemini API key (hidden): ");
            key = (chars == null) ? "" : new String(chars);
        } else {
            // Fallback when running inside an IDE console that has no java.io.Console
            System.out.print("Enter your Gemini API key: ");
            key = sc.nextLine();
        }
        return key;
    }

    /** Fails fast on an obviously invalid key instead of letting every Gemini call fail later. */
    private static String validateKey(String rawKey) {
        if (rawKey == null || rawKey.trim().isEmpty()) {
            throw new IllegalStateException("Gemini API key is empty.");
        }
        String key = rawKey.trim();
        if (key.length() < 10) {
            System.out.println("[WARN] The provided Gemini API key looks unusually short - double check it.");
        }
        return key;
    }

    private void applySharedOverrides() throws IOException {
        Path sharedFile = Paths.get(System.getProperty("rulebridge.config", "rulebridge.properties"));

        if (!Files.exists(sharedFile)) {
            // Fallback: look inside the WAR file (WEB-INF/classes)
            try (InputStream is = Config.class.getClassLoader().getResourceAsStream("rulebridge.properties")) {
                if (is != null) {
                    Properties p = new Properties();
                    p.load(is);
                    applyProperties(p);
                    return;
                }
            }
        }

        if (!Files.exists(sharedFile)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(sharedFile)) {
            p.load(in);
        }
        applyProperties(p);
    }

    private void applyProperties(Properties p) {
        chromaHost = p.getProperty("chroma.host", chromaHost);
        chromaPort = Integer.parseInt(p.getProperty("chroma.port", String.valueOf(chromaPort)));
        chromaTenant = p.getProperty("chroma.tenant", chromaTenant);
        chromaDatabase = p.getProperty("chroma.database", chromaDatabase);
        chromaCollection = p.getProperty("chroma.collection", chromaCollection);
        rejectedCollection = p.getProperty("chroma.rejected-collection", rejectedCollection);
        modelPath = p.getProperty("model.path", modelPath);
        embeddingBatchSize = Integer.parseInt(p.getProperty("embedding.batch-size", String.valueOf(embeddingBatchSize)));
        excelFilePath = p.getProperty("excel.file-path", excelFilePath);
        geminiModel = p.getProperty("gemini.model", geminiModel);
        geminiTemperature = Double.parseDouble(p.getProperty("gemini.temperature", String.valueOf(geminiTemperature)));
        geminiMaxTokens = Integer.parseInt(p.getProperty("gemini.max-tokens", String.valueOf(geminiMaxTokens)));
        defaultTopK = Integer.parseInt(p.getProperty("rag.default-top-k", String.valueOf(defaultTopK)));
        deduplicate = Boolean.parseBoolean(p.getProperty("rag.deduplicate", String.valueOf(deduplicate)));

        retryMaxAttempts = Integer.parseInt(p.getProperty("retry.max-attempts", String.valueOf(retryMaxAttempts)));
        retryBaseDelayMs = Long.parseLong(p.getProperty("retry.base-delay-ms", String.valueOf(retryBaseDelayMs)));
        chromaConnectTimeoutSec = Integer.parseInt(p.getProperty("chroma.connect-timeout-sec", String.valueOf(chromaConnectTimeoutSec)));
        chromaReadTimeoutSec = Integer.parseInt(p.getProperty("chroma.read-timeout-sec", String.valueOf(chromaReadTimeoutSec)));
        geminiConnectTimeoutSec = Integer.parseInt(p.getProperty("gemini.connect-timeout-sec", String.valueOf(geminiConnectTimeoutSec)));
        geminiReadTimeoutSec = Integer.parseInt(p.getProperty("gemini.read-timeout-sec", String.valueOf(geminiReadTimeoutSec)));

        requireEnvKey = Boolean.parseBoolean(p.getProperty("auth.require-env-key", String.valueOf(requireEnvKey)));
        persistInteractiveKey = Boolean.parseBoolean(p.getProperty("auth.persist-interactive-key", String.valueOf(persistInteractiveKey)));
    }

    public static Config loadServerConfig() throws IOException {
        Config c = new Config();
        c.applySharedOverrides();
        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey == null || envKey.trim().isEmpty()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable MUST be set in WildFly.");
        }
        c.geminiApiKey = (envKey != null && !envKey.trim().isEmpty()) ? validateKey(envKey) : null;        c.userId = "wildfly-server";
        return c;
    }

    // ---- getters ----
    public String getChromaHost() { return chromaHost; }
    public int getChromaPort() { return chromaPort; }
    public String getChromaTenant() { return chromaTenant; }
    public String getChromaDatabase() { return chromaDatabase; }
    public String getChromaCollection() { return chromaCollection; }
    public String getRejectedCollection() { return rejectedCollection; }
    public String getModelPath() { return modelPath; }
    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public String getExcelFilePath() { return excelFilePath; }
    public String getGeminiModel() { return geminiModel; }
    public double getGeminiTemperature() { return geminiTemperature; }
    public int getGeminiMaxTokens() { return geminiMaxTokens; }
    public int getDefaultTopK() { return defaultTopK; }
    public boolean isDeduplicate() { return deduplicate; }
    public String getGeminiApiKey() { return geminiApiKey; }
    public String getUserId() { return userId; }

    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public int getChromaConnectTimeoutSec() { return chromaConnectTimeoutSec; }
    public int getChromaReadTimeoutSec() { return chromaReadTimeoutSec; }
    public int getGeminiConnectTimeoutSec() { return geminiConnectTimeoutSec; }
    public int getGeminiReadTimeoutSec() { return geminiReadTimeoutSec; }
}