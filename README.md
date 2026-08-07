
# 🌉 RuleBridge: Enterprise Multi-Tenant RAG Platform

**RuleBridge** is a production-grade, multi-tenant Retrieval-Augmented Generation (RAG) platform engineered for the financial sector. It translates natural language business requirements into highly specific, domain-specific Java DSL (Domain Specific Language) validation rules used in risk management and credit engines.

Built under strict enterprise legacy constraints, it empowers domain experts to manage, query, and refine AI-generated code through isolated workspaces while contributing to a centralized, deduplicated "Company Brain."

![Status](https://img.shields.io/badge/Status-Production%20Ready-success)
![Java](https://img.shields.io/badge/Java-8%20(LTS)-orange)
![Server](https://img.shields.io/badge/Server-WildFly%2026-red)
![AI](https://img.shields.io/badge/AI-BGE--M3%20%7C%20Gemini-blue)
![Build](https://img.shields.io/badge/Build-Zero%20Maven-purple)

---

## 🚀 Key Features

- **🏢 Multi-Tenant Workspaces:** Every employee gets a cryptographically isolated workspace. Users upload raw database exports, which are automatically parsed, cleaned, and merged via a custom Java EDA (Exploratory Data Analysis) engine.
- **🧠 The "Global Brain" (SHA-256 Deduplication):** A centralized vector database. Using SHA-256 content hashing, the system mathematically guarantees that no duplicate rule is ever embedded twice, regardless of how many employees upload the same file, saving massive compute and API costs.
- **🔄 Human-in-the-Loop (RLHF):** Domain experts can Approve, Reject, or Revise AI-generated code. Corrections are instantly vectorized and saved to private knowledge bases, ensuring the AI learns from domain-specific feedback and never repeats mistakes.
- **🛡️ Immutable Audit Trail:** A thread-safe, append-only JSONL compliance logger tracks every generation, approval, and rejection with strict XSS protection, meeting rigorous financial auditing standards.
- **🔍 Context Transparency:** Users can inspect the exact "Few-Shot" examples the AI used to generate a rule, including whether the source was a private file, the Global Brain, or historical feedback.
- **⚡ Chaos-Engineered Reliability:** Hardened with atomic file I/O, transactional rollbacks for orphaned data, and thread-safe concurrent manifest management. Validated by a 12-point automated chaos test suite.

---

## 🏗️ System Architecture

```mermaid
graph TD
    subgraph Client ["Employee Browser (SPA)"]
        UI[Enterprise Dashboard]
    end

    subgraph WildFly ["WildFly 26 Application Server (Java 8)"]
        Servlets[REST Servlets / Generate / Upload / Audit]
        Engine[Core RAG & RLHF Engine]
        Merger[Custom EDA Excel Merger]
        Logger[Immutable JSONL Audit Trail]
    end

    subgraph DataLayer ["Multi-Tenant Data Layer"]
        Manifest[(Atomic User Manifests)]
        Disk[(Isolated Workspaces)]
        Chroma[(ChromaDB Vector Store)]
    end

    subgraph External ["External Services"]
        Gemini[LLM API (Few-Shot Prompting)]
    end

    UI -->|HTTP/JSON| Servlets
    Servlets --> Engine
    Servlets --> Merger
    Servlets --> Logger
    
    Engine -->|1. Embed (BGE-M3 ONNX)| Chroma
    Engine -->|2. Metadata Filter & RAG| Chroma
    Engine -->|3. Context + Prompt| Gemini
    
    Merger --> Disk
    Servlets --> Manifest
    
    style WildFly fill:#f9f9f9,stroke:#333,stroke-width:2px
    style Chroma fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    style Gemini fill:#fff3e0,stroke:#f57c00,stroke-width:2px
```

---

## 💡 The "Enterprise Constraint" Flex

*Why no Spring Boot? Why no Maven?*

This project was architected under strict enterprise deployment mandates: **Java 8 only, zero external build tools (Maven/Gradle), and deployment strictly as a standard `.war` on WildFly.**

Instead of relying on modern framework magic, RuleBridge demonstrates deep JVM and Java EE internals:
- **Custom Build Pipeline:** Engineered PowerShell scripts to manually resolve classpaths, compile sources, and package AI/ONNX workloads into standard WAR files while intelligently excluding conflicting Servlet APIs.
- **Native ONNX Inference:** Integrated Deep Java Library (DJL) to run the BGE-M3 embedding model directly on the server's CPU via ONNX Runtime, bypassing external embedding APIs for zero-latency local inference.
- **Concurrency Control:** Implemented `ReentrantLock` and `ConcurrentHashMap` to prevent race conditions in Servlet environments, and atomic `Files.move()` to prevent disk corruption during concurrent multi-part uploads.

---

## 🧪 Automated QA & Chaos Engineering

The platform includes a comprehensive PowerShell test suite (`Run-FullAudit.ps1`) that acts as an automated SDET, spinning up virtual employees, attacking the server with concurrent requests, and intentionally uploading corrupted files to verify system resilience.

| Phase | Test Case | Engineering Concept Validated |
| :--- | :--- | :--- |
| **Infrastructure** | WildFly & ChromaDB Health | Network & Service Discovery |
| **File I/O** | Dual File Upload & Merger | Apache POI EDA Pipeline |
| **Chaos** | Orphan Rollback (Corrupt File) | Transactional Disk Cleanup |
| **Concurrency** | 5-Thread Manifest Stress Test | `synchronized` & Race Condition Prevention |
| **AI / RAG** | Private Generation | ChromaDB Metadata Filtering (`$in`) |
| **AI / RAG** | Global Brain Deduplication | SHA-256 Hashing & Idempotent Upserts |
| **Security** | API Key Enforcement | 401 Unauthorized Fallback Logic |
| **RLHF** | Approve / Reject Feedback | Vector DB State Mutation |
| **RLHF** | Revise & Explain (Chat) | Contextual Few-Shot Prompting |

---

## 📂 Project Structure

```text
RuleBridge/
├── src/rulebridge/          # Core Java Source Code (Engine, Servlets, AI Pipeline)
├── lib/                     # Dependencies (Managed via download-dependencies.ps1)
├── out/                     # Compiled .class files (Ignored)
├── target/                  # Packaged .war file (Ignored)
├── compile.ps1              # Java 8 Compilation Script
├── build-war.ps1            # Custom WAR Packaging Pipeline
├── Run-FullAudit.ps1        # 12-Point Chaos Engineering Test Suite
├── index.html               # Enterprise Single-Page Application (SPA) UI
└── rulebridge.properties    # Environment & Resilience Configuration
```

---

## ⚙️ Setup & Deployment

### Prerequisites
1. **Java 8 JDK**
2. **WildFly 26.1.3.Final** (Java EE 8)
3. **ChromaDB** (Running locally via `chroma run --host 0.0.0.0 --port 8000`)
4. **BGE-M3 ONNX Model** (Downloaded locally to `D:/models/bge-m3`)

### Build & Deploy Pipeline
Since Maven is restricted, the project uses custom PowerShell scripts to compile, package, and deploy.

```powershell
# 1. Compile Java Sources
.\compile.ps1

# 2. Package into WAR (Excludes conflicting Servlet APIs)
.\build-war.ps1

# 3. Hot-Deploy to WildFly
Copy-Item -Path "target\RuleBridge.war" -Destination "D:\wildfly\standalone\deployments" -Force
```

### Environment Variables
- `GEMINI_API_KEY`: Your LLM API key (Used as a secure server-side fallback if users do not provide their own via the UI).
- **Crucial:** WildFly `JAVA_OPTS` must include `-Xms2g -Xmx4g -Dfile.encoding=UTF-8` to accommodate the local BGE-M3 ONNX model and ensure proper French character rendering.

---

## 🔮 Future Roadmap (Phase 2)
- **SSO / LDAP Integration:** Integrate with WildFly Elytron Security Realms for corporate Active Directory authentication.
- **Batch Generation:** Allow users to upload a CSV of 50+ requirements and process them asynchronously in the background.
- **Dockerization:** Package WildFly, ChromaDB, and the ONNX model into a `docker-compose.yml` file for one-click Linux server deployment.
```

### 🎯 Final Instructions for GitHub:
1. Create a new repository on GitHub named `RuleBridge`.
2. Open your terminal in the `RuleBridge` folder.
3. Run these commands to push your clean code:
   ```powershell
   git init
   git add .
   git commit -m "Initial commit: Enterprise Multi-Tenant RAG Platform"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/RuleBridge.git
   git push -u origin main
   ```
4. Go to your GitHub repository settings and set the **"About"** description to: *"Production-grade, multi-tenant RAG platform for Financial DSL generation. Built on Java 8 & WildFly with zero Maven."*
