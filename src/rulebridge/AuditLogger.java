package rulebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

public class AuditLogger {
    private static final Path AUDIT_FILE = Paths.get(System.getProperty("user.home"), ".rulebridge", "audit_trail.jsonl");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ReentrantLock lock = new ReentrantLock();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void log(String userId, String action, String prompt, String code, String details) {
        lock.lock();
        try {
            Files.createDirectories(AUDIT_FILE.getParent());
            ObjectNode node = mapper.createObjectNode();
            node.put("timestamp", LocalDateTime.now().format(formatter));
            node.put("userId", userId != null ? userId : "unknown");
            node.put("action", action);
            node.put("prompt", prompt != null ? prompt : "");
            node.put("code", code != null ? code : "");
            node.put("details", details != null ? details : "");
            
            String jsonLine = mapper.writeValueAsString(node) + "\n";
            Files.write(AUDIT_FILE, jsonLine.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[AuditLogger] Failed to write audit log: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }
    
    public static Path getAuditFile() {
        return AUDIT_FILE;
    }
}