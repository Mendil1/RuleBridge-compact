package rulebridge;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebServlet("/revise")
public class ReviseServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json"); resp.setCharacterEncoding("UTF-8");
        if (EngineLoader.ENGINE == null) { resp.setStatus(503); return; }
        String empId = req.getParameter("empId");
        String prompt = req.getParameter("prompt"); String previousCode = req.getParameter("previousCode");
        String feedback = req.getParameter("feedback"); String apiKey = req.getParameter("apiKey");
        if (prompt == null || prompt.trim().isEmpty() || previousCode == null || previousCode.trim().isEmpty() || feedback == null || feedback.trim().isEmpty()) { resp.setStatus(400); return; }
        try {
            Engine.GenerationResult result = EngineLoader.ENGINE.revise(prompt, previousCode, feedback, apiKey);
            Map<String, Object> json = new HashMap<>(); json.put("generatedCode", result.generatedCode); json.put("latencySec", result.latencySec);
            AuditLogger.log(empId, "REVISE", prompt, result.generatedCode, "Feedback: " + feedback);
            new ObjectMapper().writeValue(resp.getOutputStream(), json);
        } catch (Exception e) {
            resp.setStatus(500); Map<String, String> err = new HashMap<>(); err.put("error", e.getMessage() != null ? e.getMessage() : "Error");
            new ObjectMapper().writeValue(resp.getOutputStream(), err);
        }
    }
}