package rulebridge;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@WebServlet("/explain")
public class ExplainServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json"); resp.setCharacterEncoding("UTF-8");
        if (EngineLoader.ENGINE == null) { resp.setStatus(503); return; }
        String prompt = req.getParameter("prompt"); String code = req.getParameter("generatedCode");
        String question = req.getParameter("question"); String apiKey = req.getParameter("apiKey");
        String ctxJson = req.getParameter("contextJson"); String rejJson = req.getParameter("rejectedJson");
        String qaJson = req.getParameter("qaHistoryJson");
        if (prompt == null || prompt.trim().isEmpty() || code == null || code.trim().isEmpty() || question == null || question.trim().isEmpty()) { resp.setStatus(400); return; }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> ctx = ctxJson != null && !ctxJson.isEmpty() ? mapper.readValue(ctxJson, new TypeReference<List<Map<String, Object>>>() {}) : new ArrayList<>();
            List<Map<String, Object>> rej = rejJson != null && !rejJson.isEmpty() ? mapper.readValue(rejJson, new TypeReference<List<Map<String, Object>>>() {}) : new ArrayList<>();
            List<String[]> qa = qaJson != null && !qaJson.isEmpty() ? mapper.readValue(qaJson, new TypeReference<List<String[]>>() {}) : new ArrayList<>();
            Engine.GenerationResult dummy = new Engine.GenerationResult(prompt, code, ctx, rej, "", "", 0);
            String answer = EngineLoader.ENGINE.askAboutGeneration(dummy, qa, question, apiKey);
            Map<String, String> res = new HashMap<>(); res.put("answer", answer);
            new ObjectMapper().writeValue(resp.getOutputStream(), res);
        } catch (Exception e) {
            resp.setStatus(500); Map<String, String> err = new HashMap<>(); err.put("error", e.getMessage() != null ? e.getMessage() : "Error");
            new ObjectMapper().writeValue(resp.getOutputStream(), err);
        }
    }
}