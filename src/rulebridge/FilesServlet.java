package rulebridge;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebServlet("/files")
public class FilesServlet extends HttpServlet {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        String empId = req.getParameter("empId");
        if (empId == null) { resp.setStatus(400); return; }
        try {
            List<UserFileManager.FileRecord> records = UserFileManager.getManifest(empId);
            mapper.writeValue(resp.getOutputStream(), records);
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        String empId = req.getParameter("empId");
        String action = req.getParameter("action");
        String fileId = req.getParameter("fileId");
        
        if (empId == null || !"delete".equals(action) || fileId == null) {
            resp.setStatus(400); return;
        }
        
        try {
            UserFileManager.removeRecord(empId, fileId);
            String collectionName = "rules_" + empId;
            EngineLoader.ENGINE.deleteByFileId(collectionName, fileId);
            resp.getWriter().write("{\"success\": true}");
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}