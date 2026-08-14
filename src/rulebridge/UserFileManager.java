package rulebridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UserFileManager {
    private static final ObjectMapper mapper = new ObjectMapper();
    // FIX #1: Per-user mutex locks to prevent race conditions on read-modify-write
    private static final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public static class FileRecord {
        public String id;
        public String name;
        public String date;
        public int rules;

        public FileRecord() {}
        public FileRecord(String id, String name, String date, int rules) {
            this.id = id; this.name = name; this.date = date; this.rules = rules;
        }
    }

    public static Path getUserDir(String empId) {
        return Paths.get(System.getProperty("user.home"), ".rulebridge_data", empId.replaceAll("[^a-zA-Z0-9_-]", ""));
    }

    public static Path getManifestPath(String empId) {
        return getUserDir(empId).resolve("manifest.json");
    }

    // FIX #3: Self-healing reader. If JSON is corrupted, backup and reset instead of crashing.
    public static List<FileRecord> getManifest(String empId) {
        Path path = getManifestPath(empId);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            return mapper.readValue(path.toFile(), new TypeReference<List<FileRecord>>() {});
        } catch (Exception e) {
            System.err.println("[RuleBridge] Corrupted manifest.json for user " + empId + ", renaming and resetting.");
            try {
                Path backup = path.resolveSibling("manifest_corrupted_" + System.currentTimeMillis() + ".json");
                Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) { /* ignore */ }
            return new ArrayList<>(); // User gets a clean slate, no lockout
        }
    }

    // FIX #3: Atomic write (write to temp, then move) prevents half-written files on crash
    public static void saveManifest(String empId, List<FileRecord> records) throws Exception {
        Path dir = getUserDir(empId);
        Files.createDirectories(dir);
        Path target = getManifestPath(empId);
        Path temp = dir.resolve("manifest.tmp.json");
        
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), records);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // FIX #1: Synchronized block prevents concurrent uploads from overwriting each other
    public static void addRecord(String empId, FileRecord record) throws Exception {
        Object lock = userLocks.computeIfAbsent(empId, k -> new Object());
        synchronized (lock) {
            List<FileRecord> records = getManifest(empId);
            records.add(0, record);
            saveManifest(empId, records);
        }
    }

    public static void updateRecordCount(String empId, String fileId, int count) throws Exception {
        Object lock = userLocks.computeIfAbsent(empId, k -> new Object());
        synchronized (lock) {
            List<FileRecord> records = getManifest(empId);
            for (FileRecord r : records) {
                if (r.id.equals(fileId)) {
                    r.rules = count;
                    break;
                }
            }
            saveManifest(empId, records);
        }
    }

    public static void removeRecord(String empId, String fileId) throws Exception {
        Object lock = userLocks.computeIfAbsent(empId, k -> new Object());
        synchronized (lock) {
            List<FileRecord> records = getManifest(empId);
            records.removeIf(r -> r.id.equals(fileId));
            saveManifest(empId, records);
            
            Path filePath = getUserDir(empId).resolve(fileId + ".xlsx");
            Files.deleteIfExists(filePath);
        }
    }
}