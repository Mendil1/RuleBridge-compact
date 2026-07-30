package rulebridge;

import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

import java.util.stream.Collectors;
import java.util.Map;

/**
 * Everything that isn't "config" or "CLI menu" lives here:
 *  - BGE-M3 embeddings (DJL / ONNX)
 *  - Excel parsing (Apache POI)
 *  - Chroma REST client (OkHttp + Jackson)   <-- see NOTE below
 *  - Gemini calls + prompt building (RAG)
 *  - Human-feedback learning (approved / rejected examples)
 *
 * NOTE ON CHROMA: this uses the plain v1 REST API
 * (POST /api/v1/collections, /api/v1/collections/{id}/upsert, /query).
 * If your existing VectorStoreService talked to a different Chroma version
 * (v2 API with tenant/database), swap the three chromaXxx() methods below
 * for your already-working implementation and keep the rest unchanged.
 */
public class Engine implements AutoCloseable {

    private static final int EMBEDDING_DIMENSION = 1024;
    private static final int MAX_SEQUENCE_LENGTH = 512;
    private static final String SHEET_NAME = "Master_4679_Rules";

    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final Map<String, String> collectionIds = new HashMap<>();

    private final HuggingFaceTokenizer tokenizer;
    private final Predictor<String, float[]> predictor;

    public Engine(Config config) throws IOException, MalformedModelException {
        this.config = config;
        log("Loading embedding model from " + config.getModelPath() + " ...");
        this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(config.getModelPath()));
        Model model = Model.newInstance("bge-m3");
        model.load(Paths.get(config.getModelPath()), "model.onnx");
        this.predictor = model.newPredictor(new BGEM3Translator(tokenizer, MAX_SEQUENCE_LENGTH));
        log("Embedding model ready.");

        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    private static void log(String msg) {
        System.out.println("[RuleBridge] " + msg);
    }

    private static String n(String s) { return s == null ? "" : s; }

    // ======================================================================
    // Embedding
    // ======================================================================
    public float[] embed(String text) throws TranslateException {
        if (text == null || text.trim().isEmpty()) {
            return new float[EMBEDDING_DIMENSION];
        }
        return predictor.predict(text);
    }

    public String prepareEmbeddingText(Rule r) {
        return "Code Règle: " + n(r.codeRegle) + "\n" +
                "Catégorie: " + n(r.categorieRegle) + "\n" +
                "Champ UI: " + n(r.nomChamp) + " (" + n(r.libelleChamp) + ")\n" +
                "Description Métier: " + n(r.descriptionErreur);
    }

    // ======================================================================
    // Excel parsing (same logic as the old ExcelParser.java)
    // ======================================================================
    public List<Rule> parseExcel(String filePath) {
        List<Rule> rules = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                System.err.println("Sheet '" + SHEET_NAME + "' not found.");
                return rules;
            }
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rules;

            Map<String, Integer> cols = new HashMap<>();
            for (Cell cell : headerRow) {
                String h = cellToString(cell);
                if (h != null && !h.isEmpty()) cols.put(h, cell.getColumnIndex());
            }

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                try {
                    Rule r = parseRow(row, cols);
                    if (r != null) rules.add(r);
                } catch (Exception e) {
                    System.err.println("Skipping row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read Excel file: " + filePath + " -> " + e.getMessage());
        }
        return rules;
    }

    private Rule parseRow(Row row, Map<String, Integer> cols) {
        Integer pk = getInt(row, cols, "EXPRESSION_PK", null);
        String code = getStr(row, cols, "CODE_REGLE", null);
        if (pk == null || code == null || code.isEmpty()) return null;

        Rule r = new Rule();
        r.expressionPk = pk;
        r.codeRegle = code;
        r.categorieRegle = getStr(row, cols, "CATEGORIE_REGLE", "");
        r.nomChamp = getStr(row, cols, "NOM_CHAMP", "");
        r.libelleChamp = getStr(row, cols, "LIBELLE_CHAMP", "");
        r.descriptionErreur = getStr(row, cols, "DESCRIPTION_ERREUR", "");
        r.expressionJava = getStr(row, cols, "EXPRESSION_JAVA", "");
        return r;
    }

    private String getStr(Row row, Map<String, Integer> cols, String header, String def) {
        Integer idx = cols.get(header);
        if (idx == null) return def;
        Cell cell = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return def;
        String v = cellToString(cell);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private Integer getInt(Row row, Map<String, Integer> cols, String header, Integer def) {
        String s = getStr(row, cols, header, null);
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private String cellToString(Cell cell) {
        if (cell == null) return null;
        DataFormatter f = new DataFormatter();
        String v = f.formatCellValue(cell).trim();
        return v.isEmpty() ? null : v;
    }

    // ======================================================================
    // Ingestion: Excel -> embeddings -> Chroma
    // ======================================================================
    public void ingest() throws Exception {
        log("Reading Excel: " + config.getExcelFilePath());
        List<Rule> rules = parseExcel(config.getExcelFilePath());
        if (rules.isEmpty()) {
            log("No rules found - aborting ingestion.");
            return;
        }
        log("Loaded " + rules.size() + " rules. Embedding & indexing...");

        int batchSize = config.getEmbeddingBatchSize();
        for (int i = 0; i < rules.size(); i += batchSize) {
            int end = Math.min(i + batchSize, rules.size());
            List<Rule> batch = rules.subList(i, end);

            List<String> ids = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            List<Map<String, Object>> metas = new ArrayList<>();
            List<float[]> embeddings = new ArrayList<>();

            for (Rule r : batch) {
                ids.add("rule_" + r.expressionPk);
                String text = prepareEmbeddingText(r);
                docs.add(text);
                embeddings.add(embed(text));
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("code_regle", n(r.codeRegle));
                meta.put("category", n(r.categorieRegle));
                meta.put("nom_champ", n(r.nomChamp));
                meta.put("libelle_champ", n(r.libelleChamp));
                meta.put("expression_java", n(r.expressionJava));
                meta.put("source", "excel");
                metas.add(meta);
            }
            chromaUpsert(config.getChromaCollection(), ids, embeddings, docs, metas);
            log("Indexed rows " + i + "-" + (end - 1));
        }
        log("Ingestion complete: " + rules.size() + " rules indexed.");
    }

    // ======================================================================
    // RAG generation
    // ======================================================================
    public GenerationResult generate(String userPrompt, int topK) throws Exception {
        long t0 = System.currentTimeMillis();
        QueryResult similar = retrieveSimilar(config.getChromaCollection(), userPrompt, topK, config.isDeduplicate());
        QueryResult rejected = retrieveSimilar(config.getRejectedCollection(), userPrompt, 1, false);

        String system = buildSystemInstruction();
        String fewShot = buildFewShotPrompt(userPrompt, similar, rejected);
        String code = callGemini(system, fewShot);

        double latency = (System.currentTimeMillis() - t0) / 1000.0;
        return new GenerationResult(userPrompt, code, similar.metadatas, system, fewShot, latency);
    }

    /** Conversational revision: "add a null check", "change date format", etc. */
    public GenerationResult revise(GenerationResult previous, String userFeedback) throws Exception {
        long t0 = System.currentTimeMillis();
        String system = buildSystemInstruction();
        StringBuilder sb = new StringBuilder();
        sb.append(previous.fullPromptSent);
        sb.append("\n\n### EXPRESSION PRÉCÉDEMMENT GÉNÉRÉE :\n").append(previous.generatedCode);
        sb.append("\n\n### DEMANDE DE CORRECTION DE L'UTILISATEUR :\n").append(userFeedback);
        sb.append("\n\nRégénère l'expression Java/DSL corrigée en respectant strictement cette demande. ")
                .append("Réponds uniquement avec l'expression, sans aucun commentaire ni bloc Markdown.");
        String fewShot = sb.toString();
        String code = callGemini(system, fewShot);
        double latency = (System.currentTimeMillis() - t0) / 1000.0;
        return new GenerationResult(previous.userPrompt, code, previous.retrievedContext, system, fewShot, latency);
    }

    // ======================================================================
    // Human feedback -> learning
    // ======================================================================

    /** Approved (or manually corrected) prompt->code pair: index it so future similar prompts retrieve it. */
    public void learnFromApproval(String prompt, String approvedCode) throws Exception {
        String id = "human_" + UUID.randomUUID();
        float[] emb = embed(prompt);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("code_regle", "HUMAN_APPROVED");
        meta.put("category", "human_feedback");
        meta.put("nom_champ", "");
        meta.put("libelle_champ", "");
        meta.put("expression_java", approvedCode);
        meta.put("source", "human_approved");
        chromaUpsert(config.getChromaCollection(),
                Collections.singletonList(id),
                Collections.singletonList(emb),
                Collections.singletonList(prompt),
                Collections.singletonList(meta));
        log("Saved approved example for future few-shot retrieval.");
    }



    /** Rejected code: kept as a counter-example (in Chroma) + local audit log, never used as a positive few-shot. */
    public void learnFromRejection(String prompt, String rejectedCode, String reason) throws Exception {
        String id = "rejected_" + UUID.randomUUID();
        float[] emb = embed(prompt);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("expression_java", rejectedCode);
        meta.put("reason", reason == null ? "" : reason);
        meta.put("source", "human_rejected");
        chromaUpsert(config.getRejectedCollection(),
                Collections.singletonList(id),
                Collections.singletonList(emb),
                Collections.singletonList(prompt),
                Collections.singletonList(meta));

        Path logFile = Paths.get(System.getProperty("user.home"), ".rulebridge", "rejected_examples.jsonl");
        Files.createDirectories(logFile.getParent());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("prompt", prompt);
        entry.put("rejected_code", rejectedCode);
        entry.put("reason", reason);
        entry.put("timestamp", System.currentTimeMillis());
        try (BufferedWriter w = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(entry));
            w.newLine();
        }
        log("Saved rejected example (will be shown to the AI as a counter-example next time).");
    }

    // ======================================================================
// Viewing approved / rejected pairs
// ======================================================================

    public static class Pair {
        public final String prompt;
        public final String code;
        public final String reason;

        public Pair(String prompt, String code, String reason) {
            this.prompt = prompt != null ? prompt : "";
            this.code = code != null ? code : "";
            this.reason = reason != null ? reason : "";
        }
    }

    /**
     * Returns all human‑approved prompt→code pairs stored in the main Chroma collection.
     */
    public List<Pair> getApprovedPairs() throws IOException {
        Map<String, Object> filter = Collections.singletonMap("source", "human_approved");
        List<Map<String, Object>> items = chromaGet(config.getChromaCollection(), filter, 200);
        List<Pair> pairs = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String prompt = (String) item.get("document");
            Map<String, Object> meta = (Map<String, Object>) item.get("metadata");

            String code = "";
            if (meta != null && meta.get("expression_java") != null) {
                code = String.valueOf(meta.get("expression_java"));
            }

            pairs.add(new Pair(prompt, code, null));
        }
        return pairs;
    }

    /**
     * Returns all rejected prompt→code pairs from the local JSONL audit log,
     * falling back to Chroma's rejected collection if needed.
     */
    public List<Pair> getRejectedPairs() {
        Path logFile = Paths.get(System.getProperty("user.home"), ".rulebridge", "rejected_examples.jsonl");
        List<Pair> pairs = new ArrayList<>();

        if (Files.exists(logFile)) {
            try {
                List<String> lines = Files.readAllLines(logFile);
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue; // Fix: ignore trailing/empty lines

                    Map<String, Object> entry = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    String prompt = (String) entry.get("prompt");
                    String code = (String) entry.get("rejected_code");
                    String reason = (String) entry.get("reason");

                    pairs.add(new Pair(prompt, code, reason));
                }
                return pairs;
            } catch (IOException e) {
                log("Failed to read local rejected log: " + e.getMessage());
            }
        }

        // Fallback: query Chroma rejected collection
        try {
            List<Map<String, Object>> items = chromaGet(config.getRejectedCollection(), Collections.emptyMap(), 500);
            for (Map<String, Object> item : items) {
                String prompt = (String) item.get("document");
                Map<String, Object> meta = (Map<String, Object>) item.get("metadata");

                String code = "";
                if (meta != null && meta.get("expression_java") != null) {
                    code = String.valueOf(meta.get("expression_java"));
                }

                pairs.add(new Pair(prompt, code, ""));
            }
        } catch (IOException e) {
            log("Could not retrieve rejected pairs from Chroma: " + e.getMessage());
        }
        return pairs;
    }

    /**
     * Low‑level call to ChromaDB’s /get endpoint.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chromaGet(String collectionName, Map<String, Object> whereFilter, int limit) throws IOException {
        String id = collectionId(collectionName);
        String url = collectionsBaseUrl() + "/" + id + "/get";

        Map<String, Object> body = new LinkedHashMap<>();
        if (!whereFilter.isEmpty()) body.put("where", whereFilter);
        body.put("limit", limit);
        body.put("include", Arrays.asList("documents", "metadatas"));

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Chroma get failed: " + resp.code());
            }

            Map<String, Object> m = mapper.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});

            // Fix: Null-safe extraction for documents and metadatas lists
            List<String> docs = m.get("documents") instanceof List ? (List<String>) m.get("documents") : Collections.emptyList();
            List<Map<String, Object>> metas = m.get("metadatas") instanceof List ? (List<Map<String, Object>>) m.get("metadatas") : Collections.emptyList();

            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("document", docs.get(i));
                item.put("metadata", i < metas.size() ? metas.get(i) : Collections.emptyMap());
                items.add(item);
            }
            return items;
        }
    }

    // ======================================================================
    // Prompt building (unchanged wording from the original RAGEngine)
    // ======================================================================
    private String buildSystemInstruction() {
        return "Vous êtes un expert développeur Java Senior et Architecte logiciel spécialisé dans les systèmes d'information bancaires " +
                "et de finance/assurance chez BFI Group (Tunisie).\n\n" +
                "CONTEXTE ET MISSION :\n" +
                "- Votre rôle est de traduire des expressions de besoins métier formulées en Français en règles de validation techniques " +
                "hautement précises (expressions booléennes Java / DSL internes BFI / SpEL).\n" +
                "- Ces règles sont intégrées dans les moteurs de contrôle de gestion des risques (CIR, crédits, engagements, garanties).\n\n" +
                "RÈGLES D'OR SYNTAXIQUE ET DIALECTE BFI :\n" +
                "1. INVOCATION DE FONCTIONS ET CLASSES :\n" +
                "   - Utilisez STRICTEMENT la syntaxe à deux-points `:` pour les appels de méthodes statiques BFI " +
                "(ex: `DateUtil:dateToString`, `CheckControleUtility:excuteScript`, `NumberUtils:createBigDecimal`, `ColUtil:getList`). NE JAMAIS utiliser de point `.` pour ces utilitaires.\n" +
                "   - Utilisez STRICTEMENT des guillemets simples `'` pour les chaînes littérales et formats de date (ex: `'dd/MM/yyyy'`, `'ND'`, `'01/01/1900'`).\n" +
                "   - Gardez l'orthographe exacte des méthodes BFI (ex: `excuteScript` sans 'e' au milieu).\n\n" +
                "2. INTERDICTION DES VALEURS DU TAUX DE CHANGE HARDCODÉES (DEVISE / FX) :\n" +
                "   - NE HARDCODEZ JAMAIS de taux de conversion ou de multiplicateur numérique fixe.\n" +
                "   - Pour toute conversion de devise, appelez TOUJOURS le script utilitaire BFI dédié : " +
                "`CheckControleUtility:excuteScript('CONV_DEVISE_TND', ColUtil:getList(montant, devise))`.\n\n" +
                "3. TYPE BIGDECIMAL ET SÉCURITÉ DES OPÉRATIONS MATHÉMATIQUES :\n" +
                "   - Avec `NumberUtils:createBigDecimal(...)`, n'utilisez pas d'opérateurs arithmétiques primitifs directement sur deux BigDecimals.\n" +
                "   - Convertissez en types primitifs/`doubleValue()` avant comparaison, ou utilisez l'évaluation ternaire sécurisée BFI.\n\n" +
                "4. MANIPULATION ET CALCUL SUR LES DATES (`DateUtil`) :\n" +
                "   - Utilisez `DateUtil:addYears(date, int)` ou `DateUtil:addMonths(date, int)`.\n\n" +
                "5. LOGIQUE DE VALIDATION ET GESTION DES VALEURS NULLES / 'ND' :\n" +
                "   - Une règle retourne `true` si la donnée est valide ou non applicable, `false` si elle viole la règle métier.\n" +
                "   - Vérifiez toujours la présence d'une variable avant de l'évaluer (`!= null && var != 'ND'`).\n\n" +
                "6. STRUCTURATION DES DATES ET COMPARAISONS EN INTERVALLE :\n" +
                "   - Regroupez les vérifications de nullité en amont, puis évaluez la plage de dates en une seule expression propre.\n\n" +
                "7. LISTES ET COLLECTIONS (`ColUtil`) :\n" +
                "   - N'UTILISEZ PAS les streams Java 8. Utilisez `ColUtil:eval`, `ColUtil:getList`.\n\n" +
                "8. OUTPUT STRICT :\n" +
                "   - Ne générez QUE l'expression Java/DSL exécutable brute. Aucun texte d'introduction, aucun bloc Markdown.";
    }

    private String buildFewShotPrompt(String userPrompt, QueryResult similar, QueryResult rejected) {
        StringBuilder sb = new StringBuilder();
        sb.append("### EXEMPLES DE RÉFÉRENCE ISSUS DE LA BASE DE RÈGLES BFI GROUPE :\n\n");
        if (!similar.documents.isEmpty()) {
            for (int i = 0; i < similar.documents.size(); i++) {
                Map<String, Object> meta = similar.metadatas.get(i);
                sb.append("--- EXEMPLE #").append(i + 1).append(" ---\n")
                        .append("Code Règle BFI: ").append(meta.getOrDefault("code_regle", "N/A")).append("\n")
                        .append("Description / Requirement: ").append(similar.documents.get(i)).append("\n")
                        .append("Champ/Variable UI: ").append(meta.getOrDefault("nom_champ", "N/A"))
                        .append(" (").append(meta.getOrDefault("libelle_champ", "N/A")).append(")\n")
                        .append("Catégorie Métier: ").append(meta.getOrDefault("category", "N/A")).append("\n")
                        .append("Expression Java/DSL Réelle: \n").append(meta.getOrDefault("expression_java", "N/A")).append("\n\n");
            }
        } else {
            sb.append("(Aucun exemple pertinent trouvé)\n\n");
        }

        if (rejected != null && !rejected.documents.isEmpty()) {
            Map<String, Object> meta = rejected.metadatas.get(0);
            sb.append("### CONTRE-EXEMPLE (rejeté par un utilisateur, à NE PAS reproduire) :\n")
                    .append("Requirement similaire: ").append(rejected.documents.get(0)).append("\n")
                    .append("Expression rejetée: ").append(meta.getOrDefault("expression_java", "N/A")).append("\n")
                    .append("Raison du rejet: ").append(meta.getOrDefault("reason", "non précisée")).append("\n\n");
        }

        sb.append("### TÂCHE À EXECUTER :\n")
                .append("Exigence Métier (Français): \"").append(userPrompt).append("\"\n\n")
                .append("Générez l'expression Java/DSL BFI correspondante en suivant la même rigueur que les exemples ci-dessus, ")
                .append("et en évitant l'erreur du contre-exemple le cas échéant :");
        return sb.toString();
    }

    // ======================================================================
    // Gemini call
    // ======================================================================
    @SuppressWarnings("unchecked")
    private String callGemini(String systemInstruction, String userPrompt) throws IOException {
        String apiKey = config.getGeminiApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "// GEMINI_API_KEY not configured.";
        }
        String model = config.getGeminiModel();
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";

        Map<String, Object> systemPart = new LinkedHashMap<>();
        systemPart.put("text", systemInstruction);
        Map<String, Object> systemInstructionMap = new LinkedHashMap<>();
        systemInstructionMap.put("parts", Collections.singletonList(systemPart));

        Map<String, Object> userPart = new LinkedHashMap<>();
        userPart.put("text", userPrompt);
        Map<String, Object> contentMap = new LinkedHashMap<>();
        contentMap.put("role", "user");
        contentMap.put("parts", Collections.singletonList(userPart));

        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("temperature", config.getGeminiTemperature());
        genConfig.put("maxOutputTokens", config.getGeminiMaxTokens());

        Map<String, Object> requestMap = new LinkedHashMap<>();
        requestMap.put("system_instruction", systemInstructionMap);
        requestMap.put("contents", Collections.singletonList(contentMap));
        requestMap.put("generationConfig", genConfig);

        String jsonBody = mapper.writeValueAsString(requestMap);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-goog-api-key", apiKey.trim())
                .post(RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8")))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Gemini API error: " + response.code() + " - " + body);
            }
            Map<String, Object> resp = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                if (content != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return cleanFences((String) parts.get(0).get("text"));
                    }
                }
            }
            return "// No output generated";
        }
    }

    private String cleanFences(String text) {
        if (text == null) return "// null generated text";
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return text.trim();
    }

    // ======================================================================
    // Chroma REST client (v2 API - tenant/database scoped, matches current
    // `chroma run` servers). If your Chroma is old enough to only speak v1,
    // change collectionsBaseUrl() to return ".../api/v1/collections" instead.
    // ======================================================================
    private String collectionsBaseUrl() {
        return "http://" + config.getChromaHost() + ":" + config.getChromaPort() +
                "/api/v2/tenants/" + config.getChromaTenant() +
                "/databases/" + config.getChromaDatabase() + "/collections";
    }

    @SuppressWarnings("unchecked")
    private String collectionId(String name) throws IOException {
        if (collectionIds.containsKey(name)) return collectionIds.get(name);
        String url = collectionsBaseUrl();

        // 1. List existing collections and look for a name match.
        Request list = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(list).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                List<Map<String, Object>> collections =
                        mapper.readValue(resp.body().string(), new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> c : collections) {
                    if (name.equals(c.get("name"))) {
                        String id = String.valueOf(c.get("id"));
                        collectionIds.put(name, id);
                        return id;
                    }
                }
            } else if (!resp.isSuccessful()) {
                throw new IOException("Could not list Chroma collections: " + resp.code() +
                        " " + (resp.body() != null ? resp.body().string() : "") +
                        " (check chroma.tenant / chroma.database in rulebridge.properties)");
            }
        }

        // 2. Not found -> create it.
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("name", name);
        Request post = new Request.Builder()
                .url(url)
                .post(RequestBody.create(mapper.writeValueAsString(createBody), MediaType.get("application/json")))
                .build();
        try (Response resp = http.newCall(post).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Could not create Chroma collection '" + name + "': " + resp.code() +
                        " " + (resp.body() != null ? resp.body().string() : ""));
            }
            Map<String, Object> m = mapper.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});
            String id = String.valueOf(m.get("id"));
            collectionIds.put(name, id);
            return id;
        }
    }

    private void chromaUpsert(String collectionName, List<String> ids, List<float[]> embeddings,
                              List<String> documents, List<Map<String, Object>> metadatas) throws IOException {
        String id = collectionId(collectionName);

        List<List<Float>> embList = new ArrayList<>();
        for (float[] e : embeddings) {
            List<Float> l = new ArrayList<>(e.length);
            for (float v : e) l.add(v);
            embList.add(l);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embList);
        body.put("documents", documents);
        body.put("metadatas", metadatas);

        Request req = new Request.Builder()
                .url(collectionsBaseUrl() + "/" + id + "/upsert")
                .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Chroma upsert failed: " + resp.code() + " " +
                        (resp.body() != null ? resp.body().string() : ""));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private QueryResult chromaQuery(String collectionName, float[] queryEmbedding, int nResults) {
        try {
            String id = collectionId(collectionName);
            List<Float> qe = new ArrayList<>(queryEmbedding.length);
            for (float v : queryEmbedding) qe.add(v);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query_embeddings", Collections.singletonList(qe));
            body.put("n_results", nResults);
            body.put("include", Arrays.asList("documents", "metadatas", "distances"));

            Request req = new Request.Builder()
                    .url(collectionsBaseUrl() + "/" + id + "/query")
                    .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.get("application/json")))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    return QueryResult.empty();
                }
                Map<String, Object> m = mapper.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});
                List<List<String>> idsN = (List<List<String>>) m.getOrDefault("ids", Collections.emptyList());
                List<List<String>> docsN = (List<List<String>>) m.getOrDefault("documents", Collections.emptyList());
                List<List<Map<String, Object>>> metasN = (List<List<Map<String, Object>>>) m.getOrDefault("metadatas", Collections.emptyList());
                List<List<Double>> distsN = (List<List<Double>>) m.getOrDefault("distances", Collections.emptyList());

                return new QueryResult(
                        idsN.isEmpty() ? Collections.emptyList() : idsN.get(0),
                        docsN.isEmpty() ? Collections.emptyList() : docsN.get(0),
                        metasN.isEmpty() ? Collections.emptyList() : metasN.get(0),
                        distsN.isEmpty() ? Collections.emptyList() : distsN.get(0));
            }
        } catch (IOException e) {
            log("Chroma query failed for collection '" + collectionName + "': " + e.getMessage());
            return QueryResult.empty();
        }
    }

    private QueryResult retrieveSimilar(String collectionName, String prompt, int topK, boolean dedup) throws Exception {
        float[] qe = embed(prompt);
        int fetchLimit = dedup ? topK * 5 : topK;
        QueryResult r = chromaQuery(collectionName, qe, fetchLimit);
        return dedup && !r.metadatas.isEmpty() ? dedupe(r, topK) : limit(r, topK);
    }

    private QueryResult limit(QueryResult r, int topK) {
        if (r.documents.size() <= topK) return r;
        return new QueryResult(sub(r.ids, topK), sub(r.documents, topK), sub(r.metadatas, topK), sub(r.distances, topK));
    }

    private <T> List<T> sub(List<T> l, int topK) {
        return l.size() <= topK ? l : new ArrayList<>(l.subList(0, topK));
    }

    private QueryResult dedupe(QueryResult r, int topK) {
        Set<String> seen = new HashSet<>();
        List<String> docs = new ArrayList<>();
        List<Map<String, Object>> metas = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (int i = 0; i < r.documents.size(); i++) {
            Map<String, Object> meta = r.metadatas.get(i);
            String code = meta != null ? String.valueOf(meta.getOrDefault("code_regle", "UNKNOWN")) : "UNKNOWN";
            if (seen.add(code)) {
                docs.add(r.documents.get(i));
                metas.add(meta);
                dists.add(i < r.distances.size() ? r.distances.get(i) : null);
                if (docs.size() >= topK) break;
            }
        }
        return new QueryResult(Collections.emptyList(), docs, metas, dists);
    }

    @Override
    public void close() throws Exception {
        if (predictor != null) predictor.close();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        log("Engine closed.");
    }

    // ======================================================================
    // Nested data types
    // ======================================================================
    public static class Rule {
        public Integer expressionPk;
        public String codeRegle;
        public String categorieRegle;
        public String nomChamp;
        public String libelleChamp;
        public String descriptionErreur;
        public String expressionJava;
    }

    public static class GenerationResult {
        public final String userPrompt;
        public final String generatedCode;
        public final List<Map<String, Object>> retrievedContext;
        public final String systemInstruction;
        public final String fullPromptSent;
        public final double latencySec;

        public GenerationResult(String userPrompt, String generatedCode, List<Map<String, Object>> retrievedContext,
                                String systemInstruction, String fullPromptSent, double latencySec) {
            this.userPrompt = userPrompt;
            this.generatedCode = generatedCode;
            this.retrievedContext = retrievedContext;
            this.systemInstruction = systemInstruction;
            this.fullPromptSent = fullPromptSent;
            this.latencySec = latencySec;
        }
    }

    private static class QueryResult {
        final List<String> ids;
        final List<String> documents;
        final List<Map<String, Object>> metadatas;
        final List<Double> distances;

        QueryResult(List<String> ids, List<String> documents, List<Map<String, Object>> metadatas, List<Double> distances) {
            this.ids = ids;
            this.documents = documents;
            this.metadatas = metadatas;
            this.distances = distances;
        }

        static QueryResult empty() {
            return new QueryResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    private static class BGEM3Translator implements Translator<String, float[]> {
        private final HuggingFaceTokenizer tokenizer;
        private final int maxLength;

        BGEM3Translator(HuggingFaceTokenizer tokenizer, int maxLength) {
            this.tokenizer = tokenizer;
            this.maxLength = maxLength;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            NDManager manager = ctx.getNDManager();
            Encoding encoding = tokenizer.encode(input);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            if (inputIds.length > maxLength) {
                long[] truncatedIds = new long[maxLength];
                long[] truncatedMask = new long[maxLength];
                System.arraycopy(inputIds, 0, truncatedIds, 0, maxLength);
                System.arraycopy(attentionMask, 0, truncatedMask, 0, maxLength);
                inputIds = truncatedIds;
                attentionMask = truncatedMask;
            }
            NDArray inputIdsArray = manager.create(new long[][]{inputIds});
            NDArray attentionMaskArray = manager.create(new long[][]{attentionMask});
            return new NDList(inputIdsArray, attentionMaskArray);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            NDArray lastHiddenState = list.get(0);
            long[] shape = lastHiddenState.getShape().getShape();
            int hiddenSize = (int) shape[2];
            float[] rawOutput = lastHiddenState.toFloatArray();

            float[] clsEmbedding = new float[hiddenSize];
            System.arraycopy(rawOutput, 0, clsEmbedding, 0, hiddenSize);

            double sumSquares = 0.0;
            for (float v : clsEmbedding) sumSquares += v * v;
            float norm = (float) Math.sqrt(sumSquares);
            if (norm < 1e-12f) norm = 1e-12f;
            for (int j = 0; j < hiddenSize; j++) clsEmbedding[j] /= norm;
            return clsEmbedding;
        }

        @Override
        public Batchifier getBatchifier() {
            return null;
        }
    }
}