package rulebridge;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebServlet("/audit")
public class AuditServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        Path auditFile = AuditLogger.getAuditFile();
        List<Object> logs = new ArrayList<>();
        
        if (Files.exists(auditFile)) {
            List<String> lines = Files.readAllLines(auditFile);
            ObjectMapper mapper = new ObjectMapper();
            // Read in reverse order so newest logs appear first
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    try {
                        logs.add(mapper.readValue(line, Object.class));
                    } catch (Exception e) { /* Skip malformed lines safely */ }
                }
            }
        }
        new ObjectMapper().writeValue(resp.getOutputStream(), logs);
    }
}