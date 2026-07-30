package rulebridge;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.Scanner;

/**
 * All configuration in one place.
 *
 * - Shared, non-secret settings come from ./rulebridge.properties (checked into git).
 * - Each user's Gemini API key is entered once and persisted in
 *   ~/.rulebridge/users.properties (NOT in the repo), keyed by user id.
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
    private int embeddingBatchSize = 32;
    private String excelFilePath = "D:/projects/rag-java-migration/Master_Rules_Audit_Report.xlsx";
    private String geminiModel = "gemini-3.5-flash-lite";
    private double geminiTemperature = 0.1;
    private int geminiMaxTokens = 500;
    private int defaultTopK = 3;
    private boolean deduplicate = true;

    // ---- per-user ----
    private String userId;
    private String geminiApiKey;

    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".rulebridge");
    private static final Path USERS_FILE = CONFIG_DIR.resolve("users.properties");
    private static final Path SHARED_FILE = Paths.get("rulebridge.properties");

    /**
     * Loads shared settings, asks who is using the tool, and either loads that
     * user's saved Gemini API key or prompts for it once and persists it.
     */
    public static Config loadInteractive(Scanner sc) throws IOException {
        Config c = new Config();
        c.applySharedOverrides();
        Files.createDirectories(CONFIG_DIR);

        Properties users = new Properties();
        if (Files.exists(USERS_FILE)) {
            try (InputStream in = Files.newInputStream(USERS_FILE)) {
                users.load(in);
            }
        }

        String osUser = System.getProperty("user.name", "default");
        System.out.print("User id [" + osUser + "]: ");
        String typed = sc.nextLine();
        c.userId = (typed == null || typed.trim().isEmpty()) ? osUser : typed.trim();

        String key = users.getProperty(c.userId + ".gemini_api_key");
        if (key == null || key.trim().isEmpty()) {
            String envKey = System.getenv("GEMINI_API_KEY");
            if (envKey != null && !envKey.trim().isEmpty()) {
                key = envKey.trim();
                System.out.println("Using GEMINI_API_KEY from environment.");
            } else {
                key = promptForApiKey(c.userId, sc);
            }
            users.setProperty(c.userId + ".gemini_api_key", key);
            try (OutputStream out = Files.newOutputStream(USERS_FILE)) {
                users.store(out, "RuleBridge per-user settings - do not commit this file");
            }
            System.out.println("API key saved to " + USERS_FILE + " for future runs.");
        } else {
            System.out.println("Loaded saved API key for user '" + c.userId + "'.");
        }
        c.geminiApiKey = key;
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
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("A Gemini API key is required to use RuleBridge.");
        }
        return key.trim();
    }

    private void applySharedOverrides() throws IOException {
        if (!Files.exists(SHARED_FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(SHARED_FILE)) {
            p.load(in);
        }
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
}