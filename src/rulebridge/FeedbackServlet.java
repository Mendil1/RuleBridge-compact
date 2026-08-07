package rulebridge;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebServlet("/feedback")
public class FeedbackServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json"); resp.setCharacterEncoding("UTF-8");
        if (EngineLoader.ENGINE == null) { resp.setStatus(503); return; }
        String empId = req.getParameter("empId");
        String action = req.getParameter("action"); String prompt = req.getParameter("prompt");
        String code = req.getParameter("code"); String reason = req.getParameter("reason");
        String mainCol = req.getParameter("mainCollection"); String rejCol = req.getParameter("rejectedCollection");
        if (mainCol == null || mainCol.isEmpty()) mainCol = EngineLoader.CONFIG.getChromaCollection();
        if (rejCol == null || rejCol.isEmpty()) rejCol = EngineLoader.CONFIG.getRejectedCollection();
        try {
            if ("approve".equals(action)) { EngineLoader.ENGINE.learnFromApproval(prompt, code, mainCol); AuditLogger.log(empId, "APPROVE", prompt, code, "Saved to " + mainCol); }
            else if ("reject".equals(action)) { EngineLoader.ENGINE.learnFromRejection(prompt, code, reason, rejCol); AuditLogger.log(empId, "REJECT", prompt, code, "Reason: " + reason); }
            else { resp.setStatus(400); return; }
            Map<String, String> res = new HashMap<>(); res.put("success", "true");
            new ObjectMapper().writeValue(resp.getOutputStream(), res);
        } catch (Exception e) {
            resp.setStatus(500); Map<String, String> err = new HashMap<>(); err.put("error", e.getMessage() != null ? e.getMessage() : "Error");
            new ObjectMapper().writeValue(resp.getOutputStream(), err);
        }
    }
}