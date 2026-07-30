# RuleBridge — plain-Java CLI edition

No Spring, no Maven build, no REST API. Three classes, terminal only.

## New structure

```
ruleBridge/
├── lib/                        <- jars go here (see "Getting the jars" below)
├── src/
│   └── rulebridge/
│       ├── Main.java           entry point, menu, approve/edit/reject/feedback UI
│       ├── Config.java         shared settings + per-user Gemini API key
│       └── Engine.java         embedding + Excel + Chroma + Gemini + learning
├── rulebridge.properties       shared, non-secret settings (safe to commit)
├── compile.ps1
└── run.ps1
```

`~/.rulebridge/users.properties` (in your home folder, NOT in the repo) stores each
user's Gemini API key after the first run, keyed by user id. Never commit this file.

`~/.rulebridge/rejected_examples.jsonl` is a plain audit log of everything a user rejected.

## What changed vs. the old project

| Old | New |
|---|---|
| `RuleController` (REST) | removed — no API needed |
| `SimpleCli` | merged into `Main` |
| `RuleBridgeConfig` (`@ConfigurationProperties`) | `Config` (plain properties file) |
| `EmbeddingService`, `Ingestor`, `ExcelParser`, `VectorStoreService`, `RAGEngine` (`@Service`) | merged into `Engine`, no annotations, no DI |
| `Rule` (top-level) | `Engine.Rule` (nested, same fields) |
| Maven + Spring Boot jar | plain `javac`/`java` against a `lib/` folder |
| slf4j + logback | plain `System.out`/`System.err` (one less dependency to fetch) |

Nothing about the RAG logic (system instruction, few-shot prompt, dedup, Gemini call)
changed — it was just moved and de-Springified.

## New features included

- **Per-user API key**: on first run, asks for a user id (defaults to your OS login),
  then asks for the Gemini key once (hidden input if running in a real terminal) and
  saves it. Next run it's loaded automatically. `GEMINI_API_KEY` env var still works
  as an override/fallback.
- **Approve / Edit / Reject / Feedback loop** after every generation:
  - **A**pprove → the prompt+code pair is embedded and upserted into
    `rules_collection`, so it's immediately available as a few-shot example for
    future similar prompts.
  - **E**dit → paste a corrected expression (end with a line `END`); it's saved the
    same way as an approval (manual corrections become verified ground truth).
  - **R**eject → optional reason, saved into a separate `rules_rejected` Chroma
    collection *and* a local JSONL log. Future generations retrieve the closest
    rejected example and Gemini is explicitly told not to repeat that mistake.
  - **F**eedback → natural-language revision ("add a null check for amount",
    "change date format to dd/MM/yyyy"); Gemini regenerates, and you're shown the
    new version to review again — you can loop through feedback several times
    before approving/rejecting.

## Getting the jars (no Maven at runtime)

You need: `poi-ooxml` (+ transitive), `okhttp` (+ okio), `jackson-databind/core/annotations`,
`ai.djl:api`, `ai.djl.huggingface:tokenizers`, `ai.djl.onnxruntime:onnxruntime-engine`,
`ai.djl:model-zoo`, `com.microsoft.onnxruntime:onnxruntime`.

**Easiest**: reuse a throwaway pom (your old one, minus the Spring dependencies) purely
as a one-time downloader:

```powershell
mvn dependency:copy-dependencies -DoutputDirectory=lib
```

Run that once. From then on, forget Maven exists — use `compile.ps1` / `run.ps1`.

**Alternative**: if you still have your old Maven project's local repo cache
(`~/.m2/repository`), the same jars are already sitting on disk — you can copy them
into `lib/` by hand using the group/artifact/version from your old `pom.xml`.

## Running

```powershell
.\compile.ps1
.\run.ps1
```

Everything happens in the terminal: menu → generate → review → approve/edit/reject/feedback.
