package rulebridge;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebServlet("/generate")
public class GenerateServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        if (EngineLoader.ENGINE == null) { resp.setStatus(503); return; }

        String empId = req.getParameter("empId");
        String prompt = req.getParameter("prompt");
        String userApiKey = req.getParameter("apiKey");
        String mainCollection = req.getParameter("mainCollection");
        String rejectedCollection = req.getParameter("rejectedCollection");
        String selectedFilesParam = req.getParameter("selectedFiles");
        boolean includeGlobal = "true".equalsIgnoreCase(req.getParameter("includeGlobal"));

        if (prompt == null || prompt.trim().isEmpty()) { resp.setStatus(400); return; }
        if (mainCollection == null || mainCollection.trim().isEmpty()) mainCollection = EngineLoader.CONFIG.getChromaCollection();
        if (rejectedCollection == null || rejectedCollection.trim().isEmpty()) rejectedCollection = EngineLoader.CONFIG.getRejectedCollection();

        Map<String, Object> whereFilter = null;
        if (selectedFilesParam != null && !selectedFilesParam.trim().isEmpty() && !"all".equals(selectedFilesParam)) {
            String[] ids = selectedFilesParam.split(",");
            if (ids.length > 0) {
                Map<String, Object> inClause = new HashMap<>(); inClause.put("$in", Arrays.asList(ids));
                whereFilter = Collections.singletonMap("file_id", inClause);
            }
        }

        try {
            Engine.GenerationResult result = EngineLoader.ENGINE.generate(prompt, EngineLoader.CONFIG.getDefaultTopK(), userApiKey, mainCollection, rejectedCollection, whereFilter, includeGlobal);
            if (result.generatedCode.startsWith("// Erreur:") || result.generatedCode.startsWith("// GEMINI_API_KEY")) {
                resp.setStatus(401); Map<String, String> err = new HashMap<>(); err.put("error", "Veuillez entrer votre clé API Gemini.");
                new ObjectMapper().writeValue(resp.getOutputStream(), err); return;
            }
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("generatedCode", result.generatedCode);
            jsonResponse.put("latencySec", result.latencySec);
            jsonResponse.put("retrievedContext", result.retrievedContext);
            jsonResponse.put("retrievedRejected", result.retrievedRejected);
            AuditLogger.log(empId, "GENERATE", prompt, result.generatedCode, "Latency: " + result.latencySec + "s");
            new ObjectMapper().writeValue(resp.getOutputStream(), jsonResponse);
        } catch (Exception e) {
            resp.setStatus(500); Map<String, String> err = new HashMap<>(); err.put("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
            new ObjectMapper().writeValue(resp.getOutputStream(), err);
        }
    }
}