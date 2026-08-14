package rulebridge;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.time.LocalDateTime;

@WebServlet("/upload")
@MultipartConfig(fileSizeThreshold = 1024 * 1024 * 2, maxFileSize = 1024 * 1024 * 20, maxRequestSize = 1024 * 1024 * 50)
public class UploadServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        String empId = req.getParameter("empId");
        String customName = req.getParameter("fileName");
        
        if (empId == null || empId.trim().isEmpty()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\": \"Missing Employee ID.\"}");
            return;
        }
        empId = empId.replaceAll("[^a-zA-Z0-9_-]", "");
        
        Part exprPart = req.getPart("exprFile");
        Part ctrlPart = req.getPart("ctrlFile");
        
        if (exprPart == null || exprPart.getSize() == 0) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\": \"Le fichier Expressions est requis.\"}");
            return;
        }

        String fileId = UUID.randomUUID().toString();
        String originalName = (customName != null && !customName.trim().isEmpty()) ? customName.trim() : 
                              (exprPart.getSubmittedFileName() != null ? exprPart.getSubmittedFileName() : "Upload_" + fileId);
                              
        Path userDir = UserFileManager.getUserDir(empId);
        Files.createDirectories(userDir);
        
        Path tempExpr = userDir.resolve("temp_expr_" + fileId + ".xlsx");
        Path tempCtrl = userDir.resolve("temp_ctrl_" + fileId + ".xlsx");
        Path tempMerged = userDir.resolve("temp_merged_" + fileId + ".xlsx");
        Path finalExcelPath = userDir.resolve(fileId + ".xlsx");
        
        // FIX #2: Optimistic Transaction - Add to manifest BEFORE heavy processing
        UserFileManager.FileRecord optimisticRecord = new UserFileManager.FileRecord(
            fileId, originalName, LocalDateTime.now().toString(), 0
        );

        try {
            // 1. Save to manifest FIRST
            UserFileManager.addRecord(empId, optimisticRecord);

            // 2. Process files on disk
            if (ctrlPart != null && ctrlPart.getSize() > 0) {
                try (InputStream inExpr = exprPart.getInputStream(); InputStream inCtrl = ctrlPart.getInputStream()) {
                    Files.copy(inExpr, tempExpr, StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(inCtrl, tempCtrl, StandardCopyOption.REPLACE_EXISTING);
                }
                ExcelMerger.merge(tempExpr.toString(), tempCtrl.toString(), tempMerged.toString());
                Files.move(tempMerged, finalExcelPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                try (InputStream inExpr = exprPart.getInputStream()) {
                    Files.copy(inExpr, tempMerged, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tempMerged, finalExcelPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            
            String mainCollection = "rules_" + empId;
            String rejectedCollection = "rejected_" + empId;
            
            // 3. Ingest into ChromaDB (Can fail if network/DB is down)
            int ruleCount = EngineLoader.ENGINE.ingest(finalExcelPath.toString(), mainCollection, fileId, originalName);
            
            // 4. Update the count in the manifest to reflect reality
            UserFileManager.updateRecordCount(empId, fileId, ruleCount);
            
            resp.getWriter().write("{\"success\": true, \"mainCollection\": \"" + mainCollection + "\", \"rejectedCollection\": \"" + rejectedCollection + "\", \"fileId\": \"" + fileId + "\"}");
        } catch (Exception e) {
            // 5. FIX #2 ROLLBACK: If Chroma fails, delete file and remove from manifest
            try {
                UserFileManager.removeRecord(empId, fileId);
            } catch (Exception rollbackEx) {
                System.err.println("Rollback failed: " + rollbackEx.getMessage());
            }
            e.printStackTrace();
            resp.setStatus(500);
            String safeMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            resp.getWriter().write("{\"error\": \"Erreur de traitement: " + safeMsg.replace("\"", "'").replace("\n", " ").replace("\r", "") + "\"}");
        } finally {
            Files.deleteIfExists(tempExpr);
            Files.deleteIfExists(tempCtrl);
            Files.deleteIfExists(tempMerged);
        }
    }
}