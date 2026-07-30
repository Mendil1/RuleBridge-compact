package rulebridge;

import java.util.List;
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
                System.out.println("2) Run ingestion (Excel -> ChromaDB)");
                System.out.println("3) View approved / rejected pairs");
                System.out.println("4) Exit");
                System.out.print("> ");
                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "1":
                        generateFlow(engine, config, sc);
                        break;

                    case "2":
                        System.out.println("\n--------------------------------------------------");
                        System.out.println("[WARNING] Ingestion will embed and re-index 4,600+ rules from Excel.");
                        System.out.println("This process is computationally heavy and takes time.");
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

                    case "3":
                        viewPairs(engine, sc);
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

        boolean reviewing = true;
        while (reviewing) {
            System.out.println("\n========== GENERATED JAVA/DSL CODE ==========");
            System.out.println(result.generatedCode);
            System.out.println("==============================================");
            System.out.printf("Latency: %.2f s%n", result.latencySec);

            System.out.println("\n[A]pprove   [E]dit   [R]eject   [F]eedback (ask AI to revise)   [S]kip");
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
                    } catch (Exception e) {
                        System.err.println("Revision failed: " + e.getMessage());
                    }
                    break;
                }

                case "S":
                default:
                    reviewing = false;
                    break;
            }
        }
    }

    private static void viewPairs(Engine engine, Scanner sc) {
        while (true) {
            System.out.println("\n--- View approved/rejected pairs ---");
            System.out.println("1) View approved pairs");
            System.out.println("2) View rejected pairs");
            System.out.println("3) Back");
            System.out.print("> ");
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    try {
                        List<Engine.Pair> approved = engine.getApprovedPairs();
                        if (approved.isEmpty()) {
                            System.out.println("No approved pairs found.");
                        } else {
                            System.out.println("=== Approved pairs (" + approved.size() + ") ===");
                            for (int i = 0; i < approved.size(); i++) {
                                Engine.Pair p = approved.get(i);
                                System.out.printf("%d. Prompt: %s%n   Code  : %s%n", i + 1, p.prompt, p.code);
                                if (i < approved.size() - 1) System.out.println();
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to load approved pairs: " + e.getMessage());
                    }
                    break;

                case "2":
                    List<Engine.Pair> rejected = engine.getRejectedPairs();
                    if (rejected.isEmpty()) {
                        System.out.println("No rejected pairs found.");
                    } else {
                        System.out.println("=== Rejected pairs (" + rejected.size() + ") ===");
                        for (int i = 0; i < rejected.size(); i++) {
                            Engine.Pair p = rejected.get(i);
                            System.out.printf("%d. Prompt: %s%n   Code  : %s%n", i + 1, p.prompt, p.code);
                            if (p.reason != null && !p.reason.isEmpty()) {
                                System.out.println("   Reason: " + p.reason);
                            }
                            if (i < rejected.size() - 1) System.out.println();
                        }
                    }
                    break;

                case "3":
                    return;

                default:
                    System.out.println("Choose 1, 2 or 3.");
            }
        }
    }
}