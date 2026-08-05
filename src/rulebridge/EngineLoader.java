package rulebridge;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

@WebListener
public class EngineLoader implements ServletContextListener {
    public static Engine ENGINE;
    public static Config CONFIG;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("[RuleBridge] Initializing Engine for WildFly...");
        try {
            CONFIG = Config.loadServerConfig();
            ENGINE = new Engine(CONFIG);
            System.out.println("[RuleBridge] Engine ready and listening.");
        } catch (Exception e) {
            System.err.println("[RuleBridge] FATAL: Failed to start Engine: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (ENGINE != null) {
            try { ENGINE.close(); } catch (Exception ignored) {}
        }
    }
}