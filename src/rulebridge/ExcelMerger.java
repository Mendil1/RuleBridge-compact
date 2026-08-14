package rulebridge;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.*;

public class ExcelMerger {

    private static class ParentStat {
        int total = 0;
        Set<String> uniqueCodes = new HashSet<>();
        int lookups = 0, utils = 0, dates = 0, nulls = 0;
    }

    public static void merge(String exprPath, String ctrlPath, String outputPath) throws Exception {
        List<Map<String, String>> exprRows = readExcel(exprPath);
        List<Map<String, String>> ctrlRows = readExcel(ctrlPath);

        // 1. Clean and Parse Expressions
        Map<String, Integer> parentDensity = new HashMap<>();
        for (Map<String, String> row : exprRows) {
            String parentPk = row.getOrDefault("PARENTOBJECTPK_", "");
            if (!parentPk.isEmpty()) {
                parentDensity.put(parentPk, parentDensity.getOrDefault(parentPk, 0) + 1);
            }

            String errorKey = row.getOrDefault("ERRORKEY_", "");
            String[] parts = errorKey.split(",", 3);
            String ruleCode = parts[0].replaceAll("^['\"]|['\"]$", "").trim();
            String fieldName = parts.length > 1 ? parts[1].trim() : "";
            String fieldLabel = parts.length > 2 ? parts[2].replaceAll("^['\"]|['\"]$", "").trim() : "";

            row.put("RULE_CODE_CLEAN", cleanFrenchText(ruleCode));
            row.put("FIELD_NAME", cleanFrenchText(fieldName));
            row.put("FIELD_LABEL", cleanFrenchText(fieldLabel));
            row.put("EXPRESSION_CATEGORY", categorize(cleanFrenchText(row.get("EXPRESSION_"))));
            row.put("EXPRESSION_", cleanFrenchText(row.get("EXPRESSION_")));
        }

        // 2. Clean Control Errors
        Map<String, Map<String, String>> ctrlMap = new HashMap<>();
        for (Map<String, String> row : ctrlRows) {
            String code = row.getOrDefault("CODE_", "");
            row.put("CODE_CLEAN", cleanFrenchText(code.replaceAll("^['\"]|['\"]$", "").trim()));
            row.put("DESCRIPTION_", cleanFrenchText(row.get("DESCRIPTION_")));
            row.put("ERRORTYPE_", cleanFrenchText(row.get("ERRORTYPE_")));
            row.put("STATUT_", cleanFrenchText(row.get("STATUT_")));
            ctrlMap.put(row.get("CODE_CLEAN"), row);
        }

        Set<String> activeCodes = new HashSet<>();
        for (Map<String, String> row : exprRows) activeCodes.add(row.get("RULE_CODE_CLEAN"));

        // 3. Build Parent Stats
        Map<String, ParentStat> parentStats = new HashMap<>();
        for (Map<String, String> expr : exprRows) {
            String pPk = expr.getOrDefault("PARENTOBJECTPK_", "UNKNOWN");
            String cat = expr.getOrDefault("EXPRESSION_CATEGORY", "");
            String code = expr.getOrDefault("RULE_CODE_CLEAN", "");

            ParentStat ps = parentStats.computeIfAbsent(pPk, k -> new ParentStat());
            ps.total++;
            if (!code.isEmpty()) ps.uniqueCodes.add(code);
            if ("Oracle DB Lookup (PM:find)".equals(cat)) ps.lookups++;
            if ("Utility / Script Execution".equals(cat)) ps.utils++;
            if ("Date Validation".equals(cat)) ps.dates++;
            if ("Null / Presence Check".equals(cat)) ps.nulls++;
        }

        List<Map.Entry<String, ParentStat>> sortedParents = new ArrayList<>(parentStats.entrySet());
        sortedParents.sort((a, b) -> Integer.compare(b.getValue().total, a.getValue().total));

        // 4. Category Stats
        Map<String, Integer> catCounts = new LinkedHashMap<>();
        for (Map<String, String> expr : exprRows) {
            String cat = expr.getOrDefault("EXPRESSION_CATEGORY", "Unknown");
            catCounts.put(cat, catCounts.getOrDefault(cat, 0) + 1);
        }

        // 5. Write Output Excel
        try (Workbook outWb = new XSSFWorkbook()) {
            // --- Sheet 1: Master ---
            Sheet masterSheet = outWb.createSheet("Master_4679_Rules");
            String[] masterHeaders = {"EXPRESSION_PK", "PARENT_OBJECT_PK", "PARENT_RULE_COUNT", "CONDITION_INDEX",
                    "CODE_REGLE", "CATEGORIE_REGLE", "NOM_CHAMP", "LIBELLE_CHAMP",
                    "DESCRIPTION_ERREUR", "TYPE_ERREUR", "STATUT", "EXPRESSION_JAVA", "CLE_ERREUR_BRUTE"};
            Row hRow = masterSheet.createRow(0);
            for (int i = 0; i < masterHeaders.length; i++) hRow.createCell(i).setCellValue(masterHeaders[i]);

            int rIdx = 1;
            for (Map<String, String> expr : exprRows) {
                Row r = masterSheet.createRow(rIdx++);
                r.createCell(0).setCellValue(expr.getOrDefault("PK_", ""));
                r.createCell(1).setCellValue(expr.getOrDefault("PARENTOBJECTPK_", ""));
                r.createCell(2).setCellValue(parentDensity.getOrDefault(expr.get("PARENTOBJECTPK_"), 0));
                r.createCell(3).setCellValue(expr.getOrDefault("PARENTOBJECTCONDITIONSINDEX_", ""));
                r.createCell(4).setCellValue(expr.getOrDefault("RULE_CODE_CLEAN", ""));
                r.createCell(5).setCellValue(expr.getOrDefault("EXPRESSION_CATEGORY", ""));
                r.createCell(6).setCellValue(expr.getOrDefault("FIELD_NAME", ""));
                r.createCell(7).setCellValue(expr.getOrDefault("FIELD_LABEL", ""));

                Map<String, String> ctrl = ctrlMap.get(expr.get("RULE_CODE_CLEAN"));
                String desc = "[SANS DESCRIPTION - RÈGLE ORPHELINE]";
                String errType = "N/A";
                String statut = "N/A";
                if (ctrl != null) {
                    desc = ctrl.getOrDefault("DESCRIPTION_", desc);
                    errType = ctrl.getOrDefault("ERRORTYPE_", errType);
                    statut = ctrl.getOrDefault("STATUT_", statut);
                }
                if (desc == null || desc.isEmpty()) desc = "[SANS DESCRIPTION - RÈGLE ORPHELINE]";
                if (errType == null || errType.isEmpty()) errType = "N/A";
                if (statut == null || statut.isEmpty()) statut = "N/A";

                r.createCell(8).setCellValue(desc);
                r.createCell(9).setCellValue(errType);
                r.createCell(10).setCellValue(statut);
                r.createCell(11).setCellValue(expr.getOrDefault("EXPRESSION_", ""));
                r.createCell(12).setCellValue(expr.getOrDefault("ERRORKEY_", ""));
            }

            // --- Sheet 2: Parent Summary ---
            Sheet parentSheet = outWb.createSheet("Parent_Objects_Summary");
            String[] parentHeaders = {"PARENT_OBJECT_PK", "TOTAL_REGLES", "REGLES_UNIQUES", "NB_LOOKUPS_ORACLE", "NB_UTILITAIRES", "NB_VALIDATIONS_DATES", "NB_CONTROLES_NULL"};
            Row phRow = parentSheet.createRow(0);
            for (int i = 0; i < parentHeaders.length; i++) phRow.createCell(i).setCellValue(parentHeaders[i]);

            int pIdx = 1;
            for (Map.Entry<String, ParentStat> entry : sortedParents) {
                Row r = parentSheet.createRow(pIdx++);
                r.createCell(0).setCellValue(entry.getKey());
                r.createCell(1).setCellValue(entry.getValue().total);
                r.createCell(2).setCellValue(entry.getValue().uniqueCodes.size());
                r.createCell(3).setCellValue(entry.getValue().lookups);
                r.createCell(4).setCellValue(entry.getValue().utils);
                r.createCell(5).setCellValue(entry.getValue().dates);
                r.createCell(6).setCellValue(entry.getValue().nulls);
            }

            // --- Sheet 3: Categories Breakdown ---
            Sheet catSheet = outWb.createSheet("Categories_Breakdown");
            String[] catHeaders = {"CATEGORIE_REGLE", "TOTAL_EXPRESSIONS", "POURCENTAGE"};
            Row chRow = catSheet.createRow(0);
            for (int i = 0; i < catHeaders.length; i++) chRow.createCell(i).setCellValue(catHeaders[i]);

            int cIdx = 1;
            int totalExprs = exprRows.size();
            for (Map.Entry<String, Integer> entry : catCounts.entrySet()) {
                Row r = catSheet.createRow(cIdx++);
                r.createCell(0).setCellValue(entry.getKey());
                r.createCell(1).setCellValue(entry.getValue());
                double pct = totalExprs > 0 ? (entry.getValue() * 100.0) / totalExprs : 0;
                r.createCell(2).setCellValue(String.format("%.2f%%", pct));
            }

            // --- Sheet 4: Unused ControlErrors Orphans ---
            Sheet orphanSheet = outWb.createSheet("Unused_ControlErrors_Orphans");
            String[] orphanHeaders = {"CODE_INACTIF", "DESCRIPTION_ERREUR", "TYPE_ERREUR", "STATUT"};
            Row ohRow = orphanSheet.createRow(0);
            for (int i = 0; i < orphanHeaders.length; i++) ohRow.createCell(i).setCellValue(orphanHeaders[i]);

            int oIdx = 1;
            for (Map<String, String> ctrl : ctrlRows) {
                if (!activeCodes.contains(ctrl.get("CODE_CLEAN"))) {
                    Row r = orphanSheet.createRow(oIdx++);
                    r.createCell(0).setCellValue(ctrl.getOrDefault("CODE_CLEAN", ""));
                    r.createCell(1).setCellValue(ctrl.getOrDefault("DESCRIPTION_", ""));
                    r.createCell(2).setCellValue(ctrl.getOrDefault("ERRORTYPE_", ""));
                    r.createCell(3).setCellValue(ctrl.getOrDefault("STATUT_", ""));
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                outWb.write(fos);
            }
        }
    }

    private static List<Map<String, String>> readExcel(String path) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (InputStream is = new FileInputStream(path); Workbook wb = new XSSFWorkbook(is)) {
            Sheet sheet = wb.getSheet("Exporter la feuille de calcul");
            if (sheet == null && wb.getNumberOfSheets() > 0) {
                sheet = wb.getSheetAt(0); // Fallback to first sheet if exact name missing
            }

            // FRIEND'S FIX #3: THROW EXCEPTION ON MISSING/EMPTY SHEET
            if (sheet == null) {
                throw new IllegalArgumentException("Feuille 'Exporter la feuille de calcul' introuvable dans le fichier.");
            }
            if (sheet.getLastRowNum() < 1) {
                throw new IllegalStateException("Le fichier Excel est vide (aucune ligne de données).");
            }

            Row header = sheet.getRow(0);
            if (header == null) {
                throw new IllegalStateException("Ligne d'en-tête (headers) manquante dans le fichier Excel.");
            }

            Map<Integer, String> colMap = new HashMap<>();
            for (Cell c : header) colMap.put(c.getColumnIndex(), getCellValueAsString(c));

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String, String> rowMap = new HashMap<>();
                for (Map.Entry<Integer, String> entry : colMap.entrySet()) {
                    rowMap.put(entry.getValue(), getCellValueAsString(r.getCell(entry.getKey())));
                }
                rows.add(rowMap);
            }
        }
        return rows;
    }

    private static String cleanFrenchText(String text) {
        if (text == null) return "";
        return text.replace("déjâ", "déjà").replace("déjÂ", "déjà").replace("déclaré", "déclarée")
                .replace("Ã©", "é").replace("Ã¨", "è").replace("Ã ", "à")
                .replace("Ãª", "ê").replace("Ã§", "ç");
    }

    private static String categorize(String expr) {
        if (expr == null || expr.isEmpty()) return "Simple Logic / Flag Check";
        if (expr.contains("PM:find") || expr.contains("PM:findByCode")) return "Oracle DB Lookup (PM:find)";
        if (expr.contains("ControlUtility:treatError")) return "Custom Code / Direct Error Handler";
        if (expr.contains("CheckControleUtility") || expr.contains("DeclarationUtility")) return "Utility / Script Execution";
        if (expr.contains("DateUtil:") || expr.contains("_BUSINESSDAY")) return "Date Validation";
        if (expr.contains("!= null") || expr.contains("!=null") || expr.contains("== null") || expr.contains("==null")) return "Null / Presence Check";
        return "Simple Logic / Flag Check";
    }

    private static String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }
}