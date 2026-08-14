package rulebridge;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebServlet("/pairs")
public class PairsServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json"); resp.setCharacterEncoding("UTF-8");
        if (EngineLoader.ENGINE == null) { resp.setStatus(503); return; }
        String type = req.getParameter("type");
        String mainCol = req.getParameter("mainCollection"); String rejCol = req.getParameter("rejectedCollection");
        if (mainCol == null || mainCol.isEmpty()) mainCol = EngineLoader.CONFIG.getChromaCollection();
        if (rejCol == null || rejCol.isEmpty()) rejCol = EngineLoader.CONFIG.getRejectedCollection();
        try {
            List<Engine.Pair> pairs = "rejected".equals(type) ? EngineLoader.ENGINE.getRejectedPairs(rejCol) : EngineLoader.ENGINE.getApprovedPairs(mainCol);
            new ObjectMapper().writeValue(resp.getOutputStream(), pairs);
        } catch (Exception e) {
            resp.setStatus(500); Map<String, String> err = new HashMap<>(); err.put("error", e.getMessage() != null ? e.getMessage() : "Error");
            new ObjectMapper().writeValue(resp.getOutputStream(), err);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json"); resp.setCharacterEncoding("UTF-8");
        if (EngineLoader.ENGINE == null) { resp.setStatus(503); return; }
        String action = req.getParameter("action"); String type = req.getParameter("type"); String id = req.getParameter("id");
        String mainCol = req.getParameter("mainCollection"); String rejCol = req.getParameter("rejectedCollection");
        if (mainCol == null || mainCol.isEmpty()) mainCol = EngineLoader.CONFIG.getChromaCollection();
        if (rejCol == null || rejCol.isEmpty()) rejCol = EngineLoader.CONFIG.getRejectedCollection();
        if (!"delete".equals(action) || id == null) { resp.setStatus(400); return; }
        try {
            if ("rejected".equals(type)) EngineLoader.ENGINE.deleteRejectedPair(id, rejCol);
            else EngineLoader.ENGINE.deleteApprovedPair(id, mainCol);
            Map<String, String> res = new HashMap<>(); res.put("success", "true");
            new ObjectMapper().writeValue(resp.getOutputStream(), res);
        } catch (Exception e) {
            resp.setStatus(500); Map<String, String> err = new HashMap<>(); err.put("error", e.getMessage() != null ? e.getMessage() : "Error");
            new ObjectMapper().writeValue(resp.getOutputStream(), err);
        }
    }
}