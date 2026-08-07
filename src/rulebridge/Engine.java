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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Everything that isn't "config" or "CLI menu" lives here:
 *  - BGE-M3 embeddings (DJL / ONNX)
 *  - Excel parsing (Apache POI)
 *  - Chroma REST client (OkHttp + Jackson, v2 tenant/database API) with retry/backoff
 *  - Gemini calls + prompt building (RAG) with retry/backoff
 *  - Human-feedback learning (approved / rejected examples), viewable and deletable by id
 *  - Incremental ingestion (skips unchanged Excel rows via a content hash stored in Chroma)
 *  - Explaining a generated rule / answering follow-up questions about it
 *  - Startup validation + persistent logging (~/.rulebridge/rulebridge.log)
 */
public class Engine implements AutoCloseable {

    private static final int EMBEDDING_DIMENSION = 1024;
    private static final int MAX_SEQUENCE_LENGTH = 512;
    private static final String SHEET_NAME = "Master_4679_Rules";
    private static final Path LOG_FILE = Paths.get(System.getProperty("user.home"), ".rulebridge", "rulebridge.log");

    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();

    // Separate clients so a slow Gemini call can't starve Chroma's fail-fast behaviour and vice versa.
    private final OkHttpClient httpChroma;
    private final OkHttpClient httpGemini;

    private final Map<String, String> collectionIds = new HashMap<>();

    private final HuggingFaceTokenizer tokenizer;
    private final Predictor<String, float[]> predictor;

    public Engine(Config config) throws IOException, MalformedModelException {
        validateStartupFiles(config);

        this.config = config;
        log("Engine starting for user '" + config.getUserId() + "'.");
        log("Loading embedding model from " + config.getModelPath() + " ...");
        this.tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(config.getModelPath()));
        Model model = Model.newInstance("bge-m3");
        model.load(Paths.get(config.getModelPath()), "model.onnx");
        this.predictor = model.newPredictor(new BGEM3Translator(tokenizer, MAX_SEQUENCE_LENGTH));
        log("Embedding model ready.");

        this.httpChroma = new OkHttpClient.Builder()
                .connectTimeout(config.getChromaConnectTimeoutSec(), TimeUnit.SECONDS)
                .writeTimeout(config.getChromaReadTimeoutSec(), TimeUnit.SECONDS)
                .readTimeout(config.getChromaReadTimeoutSec(), TimeUnit.SECONDS)
                .build();

        this.httpGemini = new OkHttpClient.Builder()
                .connectTimeout(config.getGeminiConnectTimeoutSec(), TimeUnit.SECONDS)
                .writeTimeout(config.getGeminiReadTimeoutSec(), TimeUnit.SECONDS)
                .readTimeout(config.getGeminiReadTimeoutSec(), TimeUnit.SECONDS)
                .build();
    }

    /** Fails fast with a clear message if the model files or Excel file are missing/incomplete. */
    private static void validateStartupFiles(Config config) {
        Path modelDir = Paths.get(config.getModelPath());
        if (!Files.isDirectory(modelDir)) {
            throw new IllegalStateException("Model directory not found: " + modelDir +
                    " - check model.path in rulebridge.properties.");
        }
        Path onnx = modelDir.resolve("model.onnx");
        if (!Files.isRegularFile(onnx)) {
            throw new IllegalStateException("model.onnx not found in " + modelDir +
                    " - the embedding model directory looks incomplete.");
        }
        Path excel = Paths.get(config.getExcelFilePath());
        if (!Files.isRegularFile(excel)) {
            throw new IllegalStateException("Excel file not found: " + excel +
                    " - check excel.file-path in rulebridge.properties.");
        }
    }

    // ======================================================================
    // Logging: prints to console AND appends a timestamped line to
    // ~/.rulebridge/rulebridge.log. Also used to record every user request
    // (prompt / feedback / question) and the corresponding AI response, so
    // the log file doubles as a full interaction transcript. Never throws -
    // a logging failure must never crash the app.
    // ======================================================================
    private static void log(String msg) {
        String line = "[RuleBridge] " + msg;
        System.out.println(line);
        appendToLogFile(line);
    }

    private static void appendToLogFile(String line) {
        try {
            Files.createDirectories(LOG_FILE.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(LOG_FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(LocalDateTime.now() + " " + line);
                w.newLine();
            }
        } catch (IOException e) {
            // Swallow on purpose: logging must never crash the app.
        }
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

    /** Content fingerprint used for incremental ingestion (see fetchExistingHashes / ingest). */
    private String computeContentHash(Rule r) {
        String basis = n(r.categorieRegle) + "|" + n(r.nomChamp) + "|" + n(r.libelleChamp) + "|" +
                n(r.descriptionErreur) + "|" + n(r.expressionJava);
        return sha256Hex(basis);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM; this can't actually happen.
            throw new IllegalStateException(e);
        }
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
    // Ingestion: Excel -> embeddings -> Chroma (incremental, fault-tolerant)
    // ======================================================================
    public void ingest() throws Exception {
        ingest(config.getExcelFilePath(), config.getChromaCollection());
    }

    public int ingest(String excelFilePath, String collectionName) throws Exception {
        return ingest(excelFilePath, collectionName, "legacy_cli", "Legacy CLI Upload");
    }

    public int ingest(String excelFilePath, String collectionName, String fileId, String fileName) throws Exception {
        log("Reading Excel: " + excelFilePath + " (File ID: " + fileId + ")");
        List<Rule> rules = parseExcel(excelFilePath);
        if (rules.isEmpty()) {
            log("No rules found - aborting ingestion.");
            return 0;
        }
        log("Parsed " + rules.size() + " rules from Excel. Checking against ChromaDB for changes...");

        Map<String, String> existingHashes = fetchExistingHashes(collectionName);
        log("ChromaDB currently holds " + existingHashes.size() + " previously-indexed Excel rule(s).");

        List<Rule> toProcess = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        for (Rule r : rules) {
            String id = "rule_" + r.expressionPk;
            currentIds.add(id);
            String hash = computeContentHash(r);
            if (!hash.equals(existingHashes.get(id))) {
                toProcess.add(r);
            }
        }

        int unchanged = rules.size() - toProcess.size();
        log(unchanged + " rule(s) unchanged - skipped (no re-embedding). " +
                toProcess.size() + " rule(s) are new or modified and will be embedded.");

        recordStaleRuleIds(existingHashes.keySet(), currentIds);

        if (toProcess.isEmpty()) {
            log("Nothing to embed - ChromaDB is already up to date with the Excel file.");
            return rules.size();
        }

        int batchSize = config.getEmbeddingBatchSize();
        int totalBatches = (toProcess.size() + batchSize - 1) / batchSize;
        int failedBatches = 0;

        for (int i = 0; i < toProcess.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toProcess.size());
            List<Rule> batch = toProcess.subList(i, end);
            int batchNum = i / batchSize + 1;

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
                meta.put("file_id", fileId);
                meta.put("file_name", fileName);
                meta.put("content_hash", computeContentHash(r));
                metas.add(meta);
            }

            try {
                chromaUpsert(collectionName, ids, embeddings, docs, metas);
                log("Batch " + batchNum + "/" + totalBatches + " indexed (rows " + i + "-" + (end - 1) + ").");
            } catch (IOException e) {
                failedBatches++;
                log("Batch " + batchNum + "/" + totalBatches + " FAILED after retries: " + e.getMessage() +
                        " - continuing with the next batch. Simply re-run ingestion later: unchanged rows " +
                        "are skipped automatically, so only the rows that never made it in will be retried.");
            }
        }

        if (failedBatches == 0) {
            log("Ingestion complete: " + toProcess.size() + " rule(s) embedded/updated, " + unchanged + " unchanged.");
        } else {
            log("Ingestion finished with " + failedBatches + "/" + totalBatches + " batch(es) failed - re-run to retry them.");
        }
        ingestToGlobalMaster(rules);
        return rules.size();
    }

    /** Reads existing content_hash metadata for every Excel-sourced row currently in Chroma. */
    private Map<String, String> fetchExistingHashes(String collectionName) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        int limit = 300;
        int offset = 0;
        while (true) {
            List<Map<String, Object>> items = chromaGet(collectionName,
                    Collections.singletonMap("source", "excel"), limit, offset);
            if (items.isEmpty()) break;
            for (Map<String, Object> item : items) {
                Object id = item.get("id");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) item.get("metadata");
                if (id != null && meta != null && meta.get("content_hash") != null) {
                    hashes.put(String.valueOf(id), String.valueOf(meta.get("content_hash")));
                }
            }
            if (items.size() < limit) break;
            offset += limit;
        }
        return hashes;
    }

    /** Rows that exist in Chroma but no longer appear in the Excel file: flagged, never auto-deleted. */
    private void recordStaleRuleIds(Set<String> existingIds, Set<String> currentIds) throws IOException {
        List<String> stale = new ArrayList<>();
        for (String id : existingIds) {
            if (!currentIds.contains(id)) stale.add(id);
        }
        Path staleFile = Paths.get(System.getProperty("user.home"), ".rulebridge", "stale_rule_ids.txt");
        if (stale.isEmpty()) {
            Files.deleteIfExists(staleFile);
            return;
        }
        log(stale.size() + " previously-indexed rule(s) no longer appear in the Excel file. " +
                "They were NOT deleted automatically - use the cleanup menu option if you want to remove them.");
        Files.createDirectories(staleFile.getParent());
        Files.write(staleFile, stale, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** For the CLI's cleanup menu: rule ids flagged by the last ingestion as no longer in Excel. */
    public List<String> getStaleRuleIds() throws IOException {
        Path staleFile = Paths.get(System.getProperty("user.home"), ".rulebridge", "stale_rule_ids.txt");
        if (!Files.exists(staleFile)) return Collections.emptyList();
        List<String> lines = Files.readAllLines(staleFile);
        List<String> ids = new ArrayList<>();
        for (String l : lines) if (!l.trim().isEmpty()) ids.add(l.trim());
        return ids;
    }

    /** Deletes the given rule ids from Chroma after the user has explicitly confirmed. */
    public void deleteRules(List<String> ids) throws IOException {
        chromaDelete(config.getChromaCollection(), ids);
        Files.deleteIfExists(Paths.get(System.getProperty("user.home"), ".rulebridge", "stale_rule_ids.txt"));
    }

    public void deleteByFileId(String collectionName, String fileId) throws IOException {
        Map<String, Object> whereFilter = Collections.singletonMap("file_id", fileId);
        chromaDeleteWhere(collectionName, whereFilter);
        log("Deleted all rules for file_id=" + fileId + " from " + collectionName);
    }

    private void chromaDeleteWhere(String collectionName, Map<String, Object> whereFilter) throws IOException {
        String id = collectionId(collectionName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("where", whereFilter);
        String jsonBody = mapper.writeValueAsString(body);
        String url = collectionsBaseUrl() + "/" + id + "/delete";

        withRetry("Chroma deleteWhere(" + collectionName + ")", () -> {
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpChroma.newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new HttpStatusException(resp.code(), "Chroma deleteWhere failed");
                return null;
            }
        });
    }

    // ======================================================================
    // Global Master Brain (Company-wide deduplication)
    // ======================================================================
    private void ingestToGlobalMaster(List<Rule> rules) throws Exception {
        String globalCollection = "global_master_rules";
        log("Checking " + rules.size() + " rules against Global Master Brain...");
        
        Map<String, Rule> hashToRule = new LinkedHashMap<>();
        for (Rule r : rules) {
            String hash = computeContentHash(r);
            hashToRule.put(hash, r);
        }
        
        List<String> allHashes = new ArrayList<>(hashToRule.keySet());
        Set<String> existingHashes = new HashSet<>();
        int batchSize = 500; 
        
        for (int i = 0; i < allHashes.size(); i += batchSize) {
            List<String> batchIds = allHashes.subList(i, Math.min(i + batchSize, allHashes.size()));
            try {
                List<Map<String, Object>> existingItems = chromaGetByIds(globalCollection, batchIds);
                for (Map<String, Object> item : existingItems) {
                    existingHashes.add(String.valueOf(item.get("id")));
                }
            } catch (Exception e) {
                log("Warning: Could not check global DB for batch " + i + ": " + e.getMessage());
            }
        }
        
        List<Rule> newRules = new ArrayList<>();
        for (String hash : allHashes) {
            if (!existingHashes.contains(hash)) {
                newRules.add(hashToRule.get(hash));
            }
        }
        
        if (newRules.isEmpty()) {
            log("All rules already exist in Global Master Brain. Skipped.");
            return;
        }
        
        log("Found " + newRules.size() + " NEW unique rules to add to Global Master Brain.");
        
        int embedBatchSize = config.getEmbeddingBatchSize();
        for (int i = 0; i < newRules.size(); i += embedBatchSize) {
            List<Rule> batch = newRules.subList(i, Math.min(i + embedBatchSize, newRules.size()));
            List<String> ids = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            List<float[]> embeddings = new ArrayList<>();
            List<Map<String, Object>> metas = new ArrayList<>();
            
            for (Rule r : batch) {
                String hash = computeContentHash(r);
                ids.add(hash); 
                String text = prepareEmbeddingText(r);
                docs.add(text);
                embeddings.add(embed(text));
                
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("code_regle", n(r.codeRegle));
                meta.put("category", n(r.categorieRegle));
                meta.put("nom_champ", n(r.nomChamp));
                meta.put("libelle_champ", n(r.libelleChamp));
                meta.put("expression_java", n(r.expressionJava));
                meta.put("source", "global_master");
                meta.put("content_hash", hash);
                metas.add(meta);
            }
            
            chromaUpsert(globalCollection, ids, embeddings, docs, metas);
        }
        log("Global Master Brain updated with " + newRules.size() + " new rules.");
    }

    private List<Map<String, Object>> chromaGetByIds(String collectionName, List<String> ids) throws IOException {
        if (ids.isEmpty()) return Collections.emptyList();
        String colId = collectionId(collectionName);
        String url = collectionsBaseUrl() + "/" + colId + "/get";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("include", Arrays.asList("metadatas"));
        String jsonBody = mapper.writeValueAsString(body);

        return withRetry("Chroma getByIds(" + collectionName + ")", () -> {
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpChroma.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (resp.code() == 404 || resp.code() == 500) return Collections.emptyList();
                    throw new HttpStatusException(resp.code(), "Chroma getByIds failed: " + resp.code());
                }
                Map<String, Object> m = mapper.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});
                List<String> returnedIds = m.get("ids") instanceof List ? (List<String>) m.get("ids") : Collections.emptyList();
                
                List<Map<String, Object>> items = new ArrayList<>();
                for (String id : returnedIds) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    items.add(item);
                }
                return items;
            }
        });
    }

    private QueryResult mergeAndSortResults(QueryResult r1, QueryResult r2, int topK, boolean dedup) {
        if (r1 == null) r1 = QueryResult.empty();
        if (r2 == null) r2 = QueryResult.empty();

        List<String> allIds = new ArrayList<>(r1.ids != null ? r1.ids : Collections.emptyList());
        if (r2.ids != null) allIds.addAll(r2.ids);

        List<String> allDocs = new ArrayList<>(r1.documents != null ? r1.documents : Collections.emptyList());
        if (r2.documents != null) allDocs.addAll(r2.documents);

        List<Map<String, Object>> allMetas = new ArrayList<>(r1.metadatas != null ? r1.metadatas : Collections.emptyList());
        if (r2.metadatas != null) allMetas.addAll(r2.metadatas);

        List<Double> allDists = new ArrayList<>(r1.distances != null ? r1.distances : Collections.emptyList());
        if (r2.distances != null) allDists.addAll(r2.distances);
        // Safeguard: Ensure we only iterate up to the size of the smallest list to prevent IndexOutOfBounds
        int validCount = Math.min(Math.min(allIds.size(), allDocs.size()), Math.min(allMetas.size(), allDists.size()));
        
        Integer[] indices = new Integer[validCount];
        for (int i = 0; i < validCount; i++) indices[i] = i;
        
        Arrays.sort(indices, (a, b) -> {
            Double dA = allDists.get(a) != null ? allDists.get(a) : Double.MAX_VALUE;
            Double dB = allDists.get(b) != null ? allDists.get(b) : Double.MAX_VALUE;
            return Double.compare(dA, dB);
        });
        
        List<String> sIds = new ArrayList<>();
        List<String> sDocs = new ArrayList<>();
        List<Map<String, Object>> sMetas = new ArrayList<>();
        List<Double> sDists = new ArrayList<>();
        
        for (int idx : indices) {
            sIds.add(allIds.get(idx));
            sDocs.add(allDocs.get(idx));
            sMetas.add(allMetas.get(idx));
            sDists.add(allDists.get(idx));
        }
        
        QueryResult merged = new QueryResult(sIds, sDocs, sMetas, sDists);
        return dedup && !merged.metadatas.isEmpty() ? dedupe(merged, topK) : limit(merged, topK);
    }

    // ======================================================================
    // RAG generation
    // ======================================================================
    // 1. Simple CLI overload (Fixes the Main.java error)
    public GenerationResult generate(String userPrompt, int topK) throws Exception {
        return generate(userPrompt, topK, null, config.getChromaCollection(), config.getRejectedCollection(), null, false);
    }

    // 2. Web overload without filter
    public GenerationResult generate(String userPrompt, int topK, String userApiKey, String collectionName, String rejectedCollectionName) throws Exception {
        return generate(userPrompt, topK, userApiKey, collectionName, rejectedCollectionName, null, false);
    }

    // 3. Main Web implementation WITH filter
    public GenerationResult generate(String userPrompt, int topK, String userApiKey, String collectionName, String rejectedCollectionName, Map<String, Object> whereFilter) throws Exception {
        return generate(userPrompt, topK, userApiKey, collectionName, rejectedCollectionName, whereFilter, false);
    }

    public GenerationResult generate(String userPrompt, int topK, String userApiKey, String collectionName, String rejectedCollectionName, Map<String, Object> whereFilter, boolean includeGlobal) throws Exception {
        log("REQUEST (generate) user='" + config.getUserId() + "' prompt=\"" + userPrompt + "\"");
        long t0 = System.currentTimeMillis();
        QueryResult similar = retrieveSimilar(collectionName, userPrompt, topK, config.isDeduplicate(), whereFilter);
        
        if (includeGlobal) {
            QueryResult globalSimilar = retrieveSimilar("global_master_rules", userPrompt, topK, config.isDeduplicate(), null);
            similar = mergeAndSortResults(similar, globalSimilar, topK, config.isDeduplicate());
        }
        
        QueryResult rejected = retrieveSimilar(rejectedCollectionName, userPrompt, 1, false, null);

        String system = buildSystemInstruction();
        String fewShot = buildFewShotPrompt(userPrompt, similar, rejected);
        String code = callGemini(system, fewShot, userApiKey);

        double latency = (System.currentTimeMillis() - t0) / 1000.0;
        log("RESPONSE (generate): " + code);
        return new GenerationResult(userPrompt, code, similar.metadatas, rejected.metadatas, system, fewShot, latency);
    }

    public GenerationResult revise(String userPrompt, String previousCode, String userFeedback, String userApiKey) throws Exception {
        log("REQUEST (revise) user='" + config.getUserId() + "' feedback=\"" + userFeedback + "\"");
        long t0 = System.currentTimeMillis();
        String system = buildSystemInstruction();
        StringBuilder sb = new StringBuilder();
        sb.append("### EXIGENCE MÉTIER ORIGINALE :\n").append(userPrompt).append("\n\n");
        sb.append("### EXPRESSION PRÉCÉDEMMENT GÉNÉRÉE :\n").append(previousCode).append("\n\n");
        sb.append("### DEMANDE DE CORRECTION DE L'UTILISATEUR :\n").append(userFeedback).append("\n\n");
        sb.append("Régénère l'expression Java/DSL corrigée en respectant strictement cette demande. ")
                .append("Réponds uniquement avec l'expression, sans aucun commentaire ni bloc Markdown.");
        String fewShot = sb.toString();
        String code = callGemini(system, fewShot, userApiKey);
        double latency = (System.currentTimeMillis() - t0) / 1000.0;
        log("RESPONSE (revise): " + code);
        return new GenerationResult(userPrompt, code, java.util.Collections.emptyList(), java.util.Collections.emptyList(), system, fewShot, latency);
    }

    /** Conversational revision: "add a null check", "change date format", etc. Produces new DSL code. */
    public GenerationResult revise(GenerationResult previous, String userFeedback) throws Exception {
        log("REQUEST (revise) user='" + config.getUserId() + "' feedback=\"" + userFeedback + "\"");
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
        log("RESPONSE (revise): " + code);
        return new GenerationResult(previous.userPrompt, code, previous.retrievedContext,
                previous.retrievedRejected, system, fewShot, latency);
    }

    /**
     * Conversational Q&A about an *already generated* rule: "why did you write it this way",
     * "why did you use ColUtil:eval here", etc. Does NOT regenerate DSL code - it explains.
     * qaHistory accumulates (question, answer) pairs for this generation session so follow-up
     * questions keep context; pass an empty list for the first question.
     */
    public String askAboutGeneration(GenerationResult result, List<String[]> qaHistory, String question, String userApiKey) throws IOException {
        log("REQUEST (question) user='" + config.getUserId() + "' question=\"" + question + "\"");
        String system = "Vous êtes un expert développeur Java Senior chez BFI Group qui EXPLIQUE, en français, les choix faits lors de la génération d'une expression Java/DSL. Répondez de façon claire et pédagogique.";
        StringBuilder sb = new StringBuilder();
        sb.append("### EXIGENCE MÉTIER :\n").append(result.userPrompt).append("\n\n### EXPRESSION GÉNÉRÉE :\n").append(result.generatedCode).append("\n\n");
        if (result.retrievedContext != null && !result.retrievedContext.isEmpty()) {
            sb.append("### EXEMPLES DE RÉFÉRENCE :\n");
            for (Map<String, Object> meta : result.retrievedContext) sb.append("- ").append(meta.getOrDefault("code_regle", "N/A")).append(" | ").append(meta.getOrDefault("expression_java", "N/A")).append("\n");
        }
        if (qaHistory != null && !qaHistory.isEmpty()) {
            sb.append("\n### ÉCHANGE PRÉCÉDENT :\n");
            for (String[] qa : qaHistory) sb.append("Q: ").append(qa[0]).append("\nR: ").append(qa[1]).append("\n\n");
        }
        sb.append("\n### QUESTION :\n").append(question).append("\n\nRépondez uniquement à cette question, sans régénérer l'expression.");
        String answer = callGemini(system, sb.toString(), userApiKey);
        log("RESPONSE (answer): " + answer);
        return answer;
    }

    public String askAboutGeneration(GenerationResult result, List<String[]> qaHistory, String question) throws IOException {
        log("REQUEST (question) user='" + config.getUserId() + "' question=\"" + question + "\"");
        String system = "Vous êtes un expert développeur Java Senior chez BFI Group qui EXPLIQUE, en français, " +
                "les choix faits lors de la génération d'une expression Java/DSL de validation. " +
                "Vous NE générez PAS de nouvelle expression sauf si l'utilisateur le demande explicitement. " +
                "Appuyez-vous sur l'exigence métier, les exemples de référence utilisés et l'expression générée " +
                "ci-dessous pour justifier vos choix (syntaxe BFI, gestion des nulls, BigDecimal, dates, etc.). " +
                "Répondez de façon claire, concise et pédagogique.";

        StringBuilder sb = new StringBuilder();
        sb.append("### EXIGENCE MÉTIER ORIGINALE :\n").append(result.userPrompt).append("\n\n");
        sb.append("### EXPRESSION GÉNÉRÉE :\n").append(result.generatedCode).append("\n\n");

        if (result.retrievedContext != null && !result.retrievedContext.isEmpty()) {
            sb.append("### EXEMPLES DE RÉFÉRENCE UTILISÉS POUR CETTE GÉNÉRATION :\n");
            for (Map<String, Object> meta : result.retrievedContext) {
                sb.append("- Code Règle: ").append(meta.getOrDefault("code_regle", "N/A"))
                        .append(" | Expression: ").append(meta.getOrDefault("expression_java", "N/A")).append("\n");
            }
            sb.append("\n");
        }
        if (result.retrievedRejected != null && !result.retrievedRejected.isEmpty()) {
            sb.append("### CONTRE-EXEMPLE PRIS EN COMPTE (rejeté précédemment) :\n");
            Map<String, Object> meta = result.retrievedRejected.get(0);
            sb.append("- Expression rejetée: ").append(meta.getOrDefault("expression_java", "N/A"))
                    .append(" | Raison: ").append(meta.getOrDefault("reason", "non précisée")).append("\n\n");
        }

        if (qaHistory != null && !qaHistory.isEmpty()) {
            sb.append("### ÉCHANGE PRÉCÉDENT DANS CETTE SESSION :\n");
            for (String[] qa : qaHistory) {
                sb.append("Q: ").append(qa[0]).append("\nR: ").append(qa[1]).append("\n\n");
            }
        }

        sb.append("### QUESTION DE L'UTILISATEUR :\n").append(question).append("\n\n")
                .append("Répondez uniquement à cette question, en français, sans régénérer l'expression.");

        String answer = callGemini(system, sb.toString());
        log("RESPONSE (answer): " + answer);
        return answer;
    }

    // ======================================================================
    // Human feedback -> learning
    // ======================================================================

    public void learnFromApproval(String prompt, String approvedCode, String collectionName) throws Exception {
        String id = "human_" + java.util.UUID.randomUUID();
        float[] emb = embed(prompt);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("code_regle", id); meta.put("category", "human_feedback");
        meta.put("nom_champ", ""); meta.put("libelle_champ", "");
        meta.put("expression_java", approvedCode); meta.put("source", "human_approved");
        chromaUpsert(collectionName, java.util.Collections.singletonList(id), java.util.Collections.singletonList(emb), java.util.Collections.singletonList(prompt), java.util.Collections.singletonList(meta));
        log("Saved approved example to " + collectionName);
    }

    public void learnFromRejection(String prompt, String rejectedCode, String reason, String rejectedCollectionName) throws Exception {
        String id = "rejected_" + java.util.UUID.randomUUID();
        float[] emb = embed(prompt);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("expression_java", rejectedCode); meta.put("reason", reason == null ? "" : reason); meta.put("source", "human_rejected");
        chromaUpsert(rejectedCollectionName, java.util.Collections.singletonList(id), java.util.Collections.singletonList(emb), java.util.Collections.singletonList(prompt), java.util.Collections.singletonList(meta));
        log("Saved rejected example to " + rejectedCollectionName);
    }

    /** Approved (or manually corrected) prompt->code pair: index it so future similar prompts retrieve it. */
    public void learnFromApproval(String prompt, String approvedCode) throws Exception {
        String id = "human_" + UUID.randomUUID();
        float[] emb = embed(prompt);
        Map<String, Object> meta = new LinkedHashMap<>();
        // IMPORTANT: code_regle must be unique per approved example. dedupe() keeps only the
        // FIRST result per code_regle value, so a shared constant here would silently discard
        // every approved example after the first one retrieved for a given query.
        meta.put("code_regle", id);
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
        log("Saved approved example (id=" + id + ") for future few-shot retrieval.");
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
        entry.put("id", id); // lets us find and remove this exact line later (see removeRejectedLogLine)
        entry.put("prompt", prompt);
        entry.put("rejected_code", rejectedCode);
        entry.put("reason", reason);
        entry.put("timestamp", System.currentTimeMillis());
        try (BufferedWriter w = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(entry));
            w.newLine();
        }
        log("Saved rejected example (id=" + id + ", will be shown to the AI as a counter-example next time).");
    }

    // ======================================================================
    // Viewing / deleting approved / rejected pairs
    // ======================================================================

    public static class Pair {
        public final String id;
        public final String prompt;
        public final String code;
        public final String reason;

        public Pair(String id, String prompt, String code, String reason) {
            this.id = id;
            this.prompt = prompt != null ? prompt : "";
            this.code = code != null ? code : "";
            this.reason = reason != null ? reason : "";
        }
    }

    public java.util.List<Pair> getApprovedPairs(String collectionName) throws IOException {
        java.util.Map<String, Object> filter = java.util.Collections.singletonMap("source", "human_approved");
        java.util.List<java.util.Map<String, Object>> items = chromaGet(collectionName, filter, 200, 0);
        java.util.List<Pair> pairs = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> item : items) {
            String id = item.get("id") != null ? String.valueOf(item.get("id")) : null;
            String prompt = (String) item.get("document");
            @SuppressWarnings("unchecked") java.util.Map<String, Object> meta = (java.util.Map<String, Object>) item.get("metadata");
            String code = meta != null && meta.get("expression_java") != null ? String.valueOf(meta.get("expression_java")) : "";
            pairs.add(new Pair(id, prompt, code, null));
        }
        return pairs;
    }

    public java.util.List<Pair> getRejectedPairs(String rejectedCollectionName) {
        java.util.List<Pair> pairs = new java.util.ArrayList<>();
        try {
            java.util.List<java.util.Map<String, Object>> items = chromaGet(rejectedCollectionName, java.util.Collections.singletonMap("source", "human_rejected"), 500, 0);
            for (java.util.Map<String, Object> item : items) {
                String id = item.get("id") != null ? String.valueOf(item.get("id")) : null;
                String prompt = (String) item.get("document");
                @SuppressWarnings("unchecked") java.util.Map<String, Object> meta = (java.util.Map<String, Object>) item.get("metadata");
                String code = meta != null && meta.get("expression_java") != null ? String.valueOf(meta.get("expression_java")) : "";
                String reason = meta != null && meta.get("reason") != null ? String.valueOf(meta.get("reason")) : "";
                pairs.add(new Pair(id, prompt, code, reason));
            }
        } catch (IOException e) { log("Could not retrieve rejected pairs: " + e.getMessage()); }
        return pairs;
    }

    public void deleteApprovedPair(String id, String collectionName) throws IOException { chromaDelete(collectionName, java.util.Collections.singletonList(id)); }
    public void deleteRejectedPair(String id, String rejectedCollectionName) throws IOException { chromaDelete(rejectedCollectionName, java.util.Collections.singletonList(id)); }

    /** Returns all human-approved prompt->code pairs stored in the main Chroma collection. */
    public List<Pair> getApprovedPairs() throws IOException {
        Map<String, Object> filter = Collections.singletonMap("source", "human_approved");
        List<Map<String, Object>> items = chromaGet(config.getChromaCollection(), filter, 200, 0);
        List<Pair> pairs = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String id = item.get("id") != null ? String.valueOf(item.get("id")) : null;
            String prompt = (String) item.get("document");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) item.get("metadata");

            String code = "";
            if (meta != null && meta.get("expression_java") != null) {
                code = String.valueOf(meta.get("expression_java"));
            }
            pairs.add(new Pair(id, prompt, code, null));
        }
        return pairs;
    }

    /**
     * Returns all rejected prompt->code pairs from the local JSONL audit log,
     * falling back to Chroma's rejected collection if needed.
     */
    public List<Pair> getRejectedPairs() {
        Path logFile = Paths.get(System.getProperty("user.home"), ".rulebridge", "rejected_examples.jsonl");
        List<Pair> pairs = new ArrayList<>();

        if (Files.exists(logFile)) {
            try {
                List<String> lines = Files.readAllLines(logFile);
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> entry = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    String id = entry.get("id") != null ? String.valueOf(entry.get("id")) : null;
                    String prompt = (String) entry.get("prompt");
                    String code = (String) entry.get("rejected_code");
                    String reason = (String) entry.get("reason");
                    pairs.add(new Pair(id, prompt, code, reason));
                }
                return pairs;
            } catch (IOException e) {
                log("Failed to read local rejected log: " + e.getMessage());
            }
        }

        try {
            List<Map<String, Object>> items = chromaGet(config.getRejectedCollection(), Collections.emptyMap(), 500, 0);
            for (Map<String, Object> item : items) {
                String id = item.get("id") != null ? String.valueOf(item.get("id")) : null;
                String prompt = (String) item.get("document");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) item.get("metadata");

                String code = "";
                if (meta != null && meta.get("expression_java") != null) {
                    code = String.valueOf(meta.get("expression_java"));
                }
                pairs.add(new Pair(id, prompt, code, ""));
            }
        } catch (IOException e) {
            log("Could not retrieve rejected pairs from Chroma: " + e.getMessage());
        }
        return pairs;
    }

    /** Deletes one approved pair from Chroma by its id. */
    public void deleteApprovedPair(String id) throws IOException {
        if (id == null) {
            throw new IOException("This pair has no id (older entry) and cannot be deleted this way.");
        }
        chromaDelete(config.getChromaCollection(), Collections.singletonList(id));
        log("Deleted approved pair (id=" + id + ").");
    }

    /** Deletes one rejected pair from Chroma AND removes its matching line from the local JSONL log. */
    public void deleteRejectedPair(String id) throws IOException {
        if (id == null) {
            throw new IOException("This pair has no id (older entry) and cannot be deleted this way.");
        }
        chromaDelete(config.getRejectedCollection(), Collections.singletonList(id));
        removeRejectedLogLine(id);
        log("Deleted rejected pair (id=" + id + ").");
    }

    private void removeRejectedLogLine(String id) throws IOException {
        Path logFile = Paths.get(System.getProperty("user.home"), ".rulebridge", "rejected_examples.jsonl");
        if (!Files.exists(logFile)) return;

        List<String> lines = Files.readAllLines(logFile);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            boolean matches = false;
            try {
                Map<String, Object> entry = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                matches = id.equals(String.valueOf(entry.get("id")));
            } catch (IOException ignored) {
                // Malformed line: keep it rather than risk losing data.
            }
            if (!matches) kept.add(line);
        }
        Files.write(logFile, kept, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
        return callGemini(systemInstruction, userPrompt, null);
    }

    @SuppressWarnings("unchecked")
    private String callGemini(String systemInstruction, String userPrompt, String userApiKey) throws IOException {
        // Prioritize the user's provided key over the server config key
        String apiKey = (userApiKey != null && !userApiKey.trim().isEmpty()) ? userApiKey.trim() : config.getGeminiApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "// Erreur: Aucune clé API Gemini fournie. Veuillez entrer votre clé.";
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

        return withRetry("Gemini generateContent", () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("X-goog-api-key", apiKey.trim())
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8")))
                    .build();

            try (Response response = httpGemini.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new HttpStatusException(response.code(), "Gemini API error: " + response.code() + " - " + body);
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
        });
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

        String found = withRetry("Chroma list collections", () -> {
            Request list = new Request.Builder().url(url).get().build();
            try (Response resp = httpChroma.newCall(list).execute()) {
                if (!resp.isSuccessful()) {
                    throw new HttpStatusException(resp.code(), "Could not list Chroma collections: " + resp.code() +
                            " " + (resp.body() != null ? resp.body().string() : "") +
                            " (check chroma.tenant / chroma.database in rulebridge.properties)");
                }
                if (resp.body() == null) return null;
                List<Map<String, Object>> collections =
                        mapper.readValue(resp.body().string(), new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> c : collections) {
                    if (name.equals(c.get("name"))) {
                        return String.valueOf(c.get("id"));
                    }
                }
                return null;
            }
        });

        if (found != null) {
            collectionIds.put(name, found);
            return found;
        }

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("name", name);
        String jsonBody = mapper.writeValueAsString(createBody);

        String id = withRetry("Chroma create collection '" + name + "'", () -> {
            Request post = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpChroma.newCall(post).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new HttpStatusException(resp.code(), "Could not create Chroma collection '" + name + "': " +
                            resp.code() + " " + (resp.body() != null ? resp.body().string() : ""));
                }
                Map<String, Object> m = mapper.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});
                return String.valueOf(m.get("id"));
            }
        });
        collectionIds.put(name, id);
        return id;
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
        String jsonBody = mapper.writeValueAsString(body);
        String url = collectionsBaseUrl() + "/" + id + "/upsert";

        withRetry("Chroma upsert(" + collectionName + ")", () -> {
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpChroma.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new HttpStatusException(resp.code(), "Chroma upsert failed: " + resp.code() + " " +
                            (resp.body() != null ? resp.body().string() : ""));
                }
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private QueryResult chromaQuery(String collectionName, float[] queryEmbedding, int nResults, Map<String, Object> whereFilter) {
        try {
            String id = collectionId(collectionName);
            List<Float> qe = new ArrayList<>(queryEmbedding.length);
            for (float v : queryEmbedding) qe.add(v);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query_embeddings", Collections.singletonList(qe));
            body.put("n_results", nResults);
            body.put("include", Arrays.asList("documents", "metadatas", "distances"));
            if (whereFilter != null && !whereFilter.isEmpty()) {
                body.put("where", whereFilter);
            }
            String jsonBody = mapper.writeValueAsString(body);
            String url = collectionsBaseUrl() + "/" + id + "/query";

            return withRetry("Chroma query(" + collectionName + ")", () -> {
                Request req = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                        .build();
                try (Response resp = httpChroma.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new HttpStatusException(resp.code(), "Chroma query failed: " + resp.code());
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
            });
        } catch (IOException e) {
            log("Chroma query failed for collection '" + collectionName + "' after retries: " + e.getMessage());
            return QueryResult.empty();
        }
    }

    /** Low-level call to ChromaDB's /get endpoint, with pagination (limit/offset). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chromaGet(String collectionName, Map<String, Object> whereFilter, int limit, int offset) throws IOException {
        String id = collectionId(collectionName);
        String url = collectionsBaseUrl() + "/" + id + "/get";

        Map<String, Object> body = new LinkedHashMap<>();
        if (whereFilter != null && !whereFilter.isEmpty()) body.put("where", whereFilter);
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("include", Arrays.asList("documents", "metadatas"));
        String jsonBody = mapper.writeValueAsString(body);

        return withRetry("Chroma get(" + collectionName + ")", () -> {
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpChroma.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new HttpStatusException(resp.code(), "Chroma get failed: " + resp.code());
                }
                Map<String, Object> m = mapper.readValue(resp.body().string(), new TypeReference<Map<String, Object>>() {});

                List<String> ids2 = m.get("ids") instanceof List ? (List<String>) m.get("ids") : Collections.emptyList();
                List<String> docs = m.get("documents") instanceof List ? (List<String>) m.get("documents") : Collections.emptyList();
                List<Map<String, Object>> metas = m.get("metadatas") instanceof List ? (List<Map<String, Object>>) m.get("metadatas") : Collections.emptyList();

                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = 0; i < docs.size(); i++) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", i < ids2.size() ? ids2.get(i) : null);
                    item.put("document", docs.get(i));
                    item.put("metadata", i < metas.size() ? metas.get(i) : Collections.emptyMap());
                    items.add(item);
                }
                return items;
            }
        });
    }

    /** Used by the "clean up stale rules" menu action and the delete-by-number pair actions. */
    private void chromaDelete(String collectionName, List<String> ids) throws IOException {
        if (ids.isEmpty()) return;
        String id = collectionId(collectionName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        String jsonBody = mapper.writeValueAsString(body);
        String url = collectionsBaseUrl() + "/" + id + "/delete";

        withRetry("Chroma delete(" + collectionName + ")", () -> {
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();
            try (Response resp = httpChroma.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new HttpStatusException(resp.code(), "Chroma delete failed: " + resp.code());
                }
                return null;
            }
        });
    }

    private QueryResult retrieveSimilar(String collectionName, String prompt, int topK, boolean dedup, Map<String, Object> whereFilter) throws Exception {
        float[] qe = embed(prompt);
        int fetchLimit = dedup ? topK * 5 : topK;
        QueryResult r = chromaQuery(collectionName, qe, fetchLimit, whereFilter);
        return dedup && !r.metadatas.isEmpty() ? dedupe(r, topK) : limit(r, topK);
    }

    private QueryResult limit(QueryResult r, int topK) {
        if (r.documents.size() <= topK) return r;
        return new QueryResult(sub(r.ids, topK), sub(r.documents, topK), sub(r.metadatas, topK), sub(r.distances, topK));
    }

    private <T> List<T> sub(List<T> l, int topK) {
        return l.size() <= topK ? l : new ArrayList<>(l.subList(0, topK));
    }

    /**
     * Deduplicates by code_regle, keeping the first (nearest) occurrence of each code.
     * Excel rows have real, distinct codes, so this correctly collapses duplicate hits
     * on the same rule. Human-approved examples each get a unique generated code_regle
     * (see learnFromApproval), so this no longer discards every approved example but one.
     */
    private QueryResult dedupe(QueryResult r, int topK) {
        Set<String> seen = new HashSet<>();
        List<String> ids = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        List<Map<String, Object>> metas = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (int i = 0; i < r.documents.size(); i++) {
            Map<String, Object> meta = r.metadatas.get(i);
            String code = meta != null ? String.valueOf(meta.getOrDefault("code_regle", "UNKNOWN")) : "UNKNOWN";
            if (seen.add(code)) {
                ids.add(i < r.ids.size() ? r.ids.get(i) : "deduped_" + i);
                docs.add(r.documents.get(i));
                metas.add(meta);
                dists.add(i < r.distances.size() ? r.distances.get(i) : null);
                if (docs.size() >= topK) break;
            }
        }
        return new QueryResult(ids, docs, metas, dists);
    }

    // ======================================================================
    // Retry / backoff utility
    // ======================================================================

    /** Thrown for non-2xx HTTP responses so withRetry can decide, from the status code, whether to retry. */
    private static class HttpStatusException extends IOException {
        final int code;
        HttpStatusException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    @FunctionalInterface
    private interface HttpCall<T> {
        T call() throws IOException;
    }

    /**
     * Runs an HTTP call with exponential backoff + jitter. Retries transient network errors and
     * HTTP 429/5xx responses; does not retry other 4xx responses (bad request, auth, not found, etc.)
     * since retrying those just wastes time and hides real bugs.
     */
    private <T> T withRetry(String opName, HttpCall<T> call) throws IOException {
        int maxAttempts = Math.max(1, config.getRetryMaxAttempts());
        long baseDelay = config.getRetryBaseDelayMs();
        IOException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (IOException e) {
                lastError = e;
                boolean retryable = isRetryable(e);
                if (attempt == maxAttempts || !retryable) {
                    throw e;
                }
                long delay = backoffDelay(baseDelay, attempt);
                log(opName + ": attempt " + attempt + "/" + maxAttempts + " failed (" + e.getMessage() +
                        ") - retrying in " + delay + " ms.");
                sleepQuietly(delay);
            }
        }
        // Unreachable in practice (loop always returns or throws), but keeps the compiler happy.
        throw lastError != null ? lastError : new IOException(opName + " failed for an unknown reason.");
    }

    private boolean isRetryable(IOException e) {
        if (e instanceof HttpStatusException) {
            int code = ((HttpStatusException) e).code;
            return code == 429 || code >= 500;
        }
        // Connection resets, timeouts, DNS hiccups, "connection refused" while Chroma restarts, etc.
        return true;
    }

    private long backoffDelay(long baseDelay, int attempt) {
        long exp = baseDelay * (1L << Math.min(attempt - 1, 10)); // avoid overflow on high attempt counts
        long capped = Math.min(exp, 10_000L);
        long jitter = ThreadLocalRandom.current().nextLong(0, capped / 4 + 1);
        return capped + jitter;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws Exception {
        if (predictor != null) predictor.close();
        httpChroma.dispatcher().executorService().shutdown();
        httpChroma.connectionPool().evictAll();
        httpGemini.dispatcher().executorService().shutdown();
        httpGemini.connectionPool().evictAll();
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
        /** Metadata of the similar (positive) reference rules retrieved for this generation. */
        public final List<Map<String, Object>> retrievedContext;
        /** Metadata of the closest rejected counter-example considered (may be empty). */
        public final List<Map<String, Object>> retrievedRejected;
        public final String systemInstruction;
        public final String fullPromptSent;
        public final double latencySec;

        public GenerationResult(String userPrompt, String generatedCode,
                                List<Map<String, Object>> retrievedContext,
                                List<Map<String, Object>> retrievedRejected,
                                String systemInstruction, String fullPromptSent, double latencySec) {
            this.userPrompt = userPrompt;
            this.generatedCode = generatedCode;
            this.retrievedContext = retrievedContext;
            this.retrievedRejected = retrievedRejected;
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