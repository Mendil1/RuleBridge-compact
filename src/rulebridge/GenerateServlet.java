package rulebridge;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@WebServlet("/generate")
public class GenerateServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        if (EngineLoader.ENGINE == null) {
            resp.setStatus(503);
            resp.getWriter().write("{\"error\": \"Engine is still loading or failed to start. Check server logs.\"}");
            return;
        }

        String prompt = req.getParameter("prompt");
        if (prompt == null || prompt.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\": \"Missing 'prompt' parameter.\"}");
            return;
        }

        try {
            Engine.GenerationResult result = EngineLoader.ENGINE.generate(prompt, EngineLoader.CONFIG.getDefaultTopK());
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("generatedCode", result.generatedCode);
            jsonResponse.put("latencySec", result.latencySec);

            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(resp.getOutputStream(), jsonResponse);
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}