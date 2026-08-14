package rulebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Terminal entry point. No Spring, no HTTP server, no Maven at runtime.
 * Run with: java -cp "out;lib/*" rulebridge.Main   (Windows; use ':' on Linux/Mac)
 */
public class Main {

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        Engine engine = null;
        try {
            System.out.println("=== RuleBridge (CLI) ===");
            Config config = Config.loadInteractive(sc);
            engine = new Engine(config);

            boolean running = true;
            while (running) {
                System.out.println("\n1) Generate a rule from a prompt");
                System.out.println("2) View / delete approved / rejected pairs");
                System.out.println("3) Advanced");
                System.out.println("4) Exit");
                System.out.print("> ");
                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "1":
                        generateFlow(engine, config, sc);
                        break;

                    case "2":
                        viewPairs(engine, sc);
                        break;

                    case "3":
                        advancedMenu(engine, sc);
                        break;

                    case "4":
                        running = false;
                        break;

                    default:
                        System.out.println("Please choose 1, 2, 3 or 4.");
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception ignored) {
                }
            }
            sc.close();
        }
    }

    // ======================================================================
    // Advanced submenu: ingestion + stale-rule cleanup
    // ======================================================================
    private static void advancedMenu(Engine engine, Scanner sc) {
        while (true) {
            System.out.println("\n--- Advanced ---");
            System.out.println("1) Run ingestion (Excel -> ChromaDB, incremental)");
            System.out.println("2) Clean up rules removed from Excel (stale rules)");
            System.out.println("3) Back");
            System.out.print("> ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    System.out.println("\n--------------------------------------------------");
                    System.out.println("Ingestion is incremental: only new or modified Excel rows");
                    System.out.println("will be embedded. Unchanged rows are skipped automatically.");
                    System.out.println("--------------------------------------------------");
                    System.out.print("Are you sure? Type 'CONFIRM' to proceed (or press Enter to cancel): ");

                    String confirmation = sc.nextLine().trim();
                    if ("CONFIRM".equalsIgnoreCase(confirmation)) {
                        try {
                            engine.ingest();
                        } catch (Throwable t) {
                            System.err.println("\n[ERROR] Ingestion failed: " + t.getMessage());
                            t.printStackTrace();
                        }
                    } else {
                        System.out.println("Ingestion canceled.");
                    }
                    break;

                case "2":
                    cleanupStaleRules(engine, sc);
                    break;

                case "3":
                    return;

                default:
                    System.out.println("Choose 1, 2 or 3.");
            }
        }
    }

    private static void generateFlow(Engine engine, Config config, Scanner sc) {
        System.out.print("\nEnter the business requirement (French): ");
        String prompt = sc.nextLine();
        if (prompt == null || prompt.trim().isEmpty()) {
            System.out.println("Empty prompt, cancelled.");
            return;
        }

        Engine.GenerationResult result;
        try {
            result = engine.generate(prompt, config.getDefaultTopK());
        } catch (Exception e) {
            System.err.println("Generation failed: " + e.getMessage());
            return;
        }

        // Q&A history is per-generation: it resets whenever a new rule is generated or revised,
        // and follows the "current" result across [Q]uestion turns so follow-ups keep context.
        List<String[]> qaHistory = new ArrayList<>();

        boolean reviewing = true;
        while (reviewing) {
            System.out.println("\n========== GENERATED JAVA/DSL CODE ==========");
            System.out.println(result.generatedCode);
            System.out.println("==============================================");
            System.out.printf("Latency: %.2f s%n", result.latencySec);

            System.out.println("\n[A]pprove   [E]dit   [R]eject   [F]eedback (ask AI to revise)");
            System.out.println("[C]ontext (show reference rules used)   [Q]uestion (ask AI why)   [S]kip");
            System.out.print("> ");
            String action = sc.nextLine().trim().toUpperCase();

            switch (action) {
                case "A":
                    try {
                        engine.learnFromApproval(result.userPrompt, result.generatedCode);
                        System.out.println("Approved and saved for future use.");
                    } catch (Exception e) {
                        System.err.println("Could not save approval: " + e.getMessage());
                    }
                    reviewing = false;
                    break;

                case "E": {
                    System.out.println("Type/paste the corrected expression. Finish with a line containing only: END");
                    StringBuilder edited = new StringBuilder();
                    String line;
                    while (!(line = sc.nextLine()).equals("END")) {
                        if (edited.length() > 0) edited.append("\n");
                        edited.append(line);
                    }
                    String correctedCode = edited.toString().trim();
                    try {
                        engine.learnFromApproval(result.userPrompt, correctedCode);
                        System.out.println("Correction saved as verified ground truth.");
                    } catch (Exception e) {
                        System.err.println("Could not save correction: " + e.getMessage());
                    }
                    reviewing = false;
                    break;
                }

                case "R": {
                    System.out.print("Reason for rejection (optional): ");
                    String reason = sc.nextLine();
                    try {
                        engine.learnFromRejection(result.userPrompt, result.generatedCode, reason);
                        System.out.println("Rejection recorded.");
                    } catch (Exception e) {
                        System.err.println("Could not save rejection: " + e.getMessage());
                    }
                    reviewing = false;
                    break;
                }

                case "F": {
                    System.out.print("Describe what to change: ");
                    String feedback = sc.nextLine();
                    try {
                        result = engine.revise(result, feedback);
                        qaHistory.clear(); // new version of the code -> start Q&A fresh
                    } catch (Exception e) {
                        System.err.println("Revision failed: " + e.getMessage());
                    }
                    // stays in the loop so the revised code is shown again
                    break;
                }

                case "C":
                    printContext(result);
                    // stays in the loop, no state change
                    break;

                case "Q": {
                    System.out.print("What do you want to ask the AI about this rule? ");
                    String question = sc.nextLine();
                    if (question == null || question.trim().isEmpty()) {
                        break;
                    }
                    try {
                        String answer = engine.askAboutGeneration(result, qaHistory, question);
                        System.out.println("\n----- AI explanation -----");
                        System.out.println(answer);
                        System.out.println("---------------------------");
                        qaHistory.add(new String[]{question, answer});
                    } catch (Exception e) {
                        System.err.println("Could not get an explanation: " + e.getMessage());
                    }
                    // stays in the loop so the user can ask follow-ups or then approve/reject
                    break;
                }

                case "S":
                default:
                    reviewing = false;
                    break;
            }
        }
    }

    /** Shows the reference rules (and counter-example, if any) that fed this generation. */
    private static void printContext(Engine.GenerationResult result) {
        System.out.println("\n--- Reference rules used for this generation ---");
        if (result.retrievedContext == null || result.retrievedContext.isEmpty()) {
            System.out.println("(No similar reference rule was found in the vector store.)");
        } else {
            int i = 1;
            for (Map<String, Object> meta : result.retrievedContext) {
                System.out.printf("%d. Code Règle : %s%n", i, meta.getOrDefault("code_regle", "N/A"));
                System.out.printf("   Catégorie  : %s%n", meta.getOrDefault("category", "N/A"));
                System.out.printf("   Champ      : %s (%s)%n",
                        meta.getOrDefault("nom_champ", "N/A"), meta.getOrDefault("libelle_champ", "N/A"));
                System.out.printf("   Expression : %s%n", meta.getOrDefault("expression_java", "N/A"));
                i++;
            }
        }

        if (result.retrievedRejected != null && !result.retrievedRejected.isEmpty()) {
            System.out.println("\n--- Counter-example considered (previously rejected) ---");
            Map<String, Object> meta = result.retrievedRejected.get(0);
            System.out.printf("Expression rejetée : %s%n", meta.getOrDefault("expression_java", "N/A"));
            System.out.printf("Raison du rejet    : %s%n", meta.getOrDefault("reason", "non précisée"));
        }
        System.out.println("--------------------------------------------------");
    }

    // ======================================================================
    // View / delete approved / rejected pairs
    // ======================================================================
    private static void viewPairs(Engine engine, Scanner sc) {
        while (true) {
            System.out.println("\n--- View / delete approved/rejected pairs ---");
            System.out.println("1) View/delete approved pairs");
            System.out.println("2) View/delete rejected pairs");
            System.out.println("3) Back");
            System.out.print("> ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1": {
                    List<Engine.Pair> approved;
                    try {
                        approved = engine.getApprovedPairs();
                    } catch (Exception e) {
                        System.err.println("Failed to load approved pairs: " + e.getMessage());
                        break;
                    }
                    printPairs(approved);
                    deleteByNumber(engine, sc, approved, true);
                    break;
                }

                case "2": {
                    List<Engine.Pair> rejected = engine.getRejectedPairs();
                    printPairs(rejected);
                    deleteByNumber(engine, sc, rejected, false);
                    break;
                }

                case "3":
                    return;

                default:
                    System.out.println("Choose 1, 2 or 3.");
            }
        }
    }

    private static void printPairs(List<Engine.Pair> pairs) {
        if (pairs.isEmpty()) {
            System.out.println("No pairs found.");
            return;
        }
        System.out.println("=== " + pairs.size() + " pair(s) ===");
        for (int i = 0; i < pairs.size(); i++) {
            Engine.Pair p = pairs.get(i);
            System.out.printf("%d. Prompt: %s%n   Code  : %s%n", i + 1, p.prompt, p.code);
            if (p.reason != null && !p.reason.isEmpty()) {
                System.out.println("   Reason: " + p.reason);
            }
            if (i < pairs.size() - 1) System.out.println();
        }
    }

    /** Prompts for a pair number to delete, confirms, then deletes from Chroma (and the local log for rejected). */
    private static void deleteByNumber(Engine engine, Scanner sc, List<Engine.Pair> pairs, boolean approved) {
        if (pairs.isEmpty()) return;

        System.out.print("\nEnter the number of the pair to delete (or press Enter to cancel): ");
        String input = sc.nextLine().trim();
        if (input.isEmpty()) return;

        int idx;
        try {
            idx = Integer.parseInt(input) - 1;
        } catch (NumberFormatException e) {
            System.out.println("Not a valid number, cancelled.");
            return;
        }
        if (idx < 0 || idx >= pairs.size()) {
            System.out.println("Out of range, cancelled.");
            return;
        }

        Engine.Pair target = pairs.get(idx);
        System.out.print("Delete pair #" + (idx + 1) + "? (y/N): ");
        String confirm = sc.nextLine().trim();
        if (!confirm.equalsIgnoreCase("y")) {
            System.out.println("Cancelled.");
            return;
        }

        try {
            if (approved) {
                engine.deleteApprovedPair(target.id);
            } else {
                engine.deleteRejectedPair(target.id);
            }
            System.out.println("Deleted.");
        } catch (Exception e) {
            System.err.println("Delete failed: " + e.getMessage());
        }
    }

    /** Lists rules the last ingestion flagged as no longer present in Excel, and deletes them on confirmation. */
    private static void cleanupStaleRules(Engine engine, Scanner sc) {
        List<String> stale;
        try {
            stale = engine.getStaleRuleIds();
        } catch (Exception e) {
            System.err.println("Could not read stale rule list: " + e.getMessage());
            return;
        }

        if (stale.isEmpty()) {
            System.out.println("\nNo stale rules flagged. (This list is populated by running ingestion.)");
            return;
        }

        System.out.println("\nThe following " + stale.size() + " rule id(s) exist in ChromaDB but no longer " +
                "appear in the Excel file:");
        for (String id : stale) {
            System.out.println("  - " + id);
        }
        System.out.print("Type 'CONFIRM' to permanently delete these from ChromaDB (or press Enter to cancel): ");
        String confirmation = sc.nextLine().trim();
        if (!"CONFIRM".equalsIgnoreCase(confirmation)) {
            System.out.println("Cleanup canceled.");
            return;
        }

        try {
            engine.deleteRules(stale);
            System.out.println("Deleted " + stale.size() + " stale rule(s) from ChromaDB.");
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
        }
    }
}