# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
mvn clean package -DskipTests

# Run (Spring Boot, port 8080)
mvn spring-boot:run

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName
```

## Architecture

A Spring Boot 2.7 / Java 8 web app — a knowledge base Q&A robot using Lucene for full-text search with an optional RAG mode (Lucene retrieves context, LLM generates the answer).

**Layers:**

- **Controller** — REST JSON APIs (`/api/qa/*`, `/api/knowledge/*`) + Thymeleaf HTML pages (`/`, `/knowledge`)
- **Service** — business logic; `QAService` is the main orchestrator for search/answer, `IngestionService` handles CRUD + file import
- **Repository** — JPA (`KnowledgeRepository`) for persistence + `LuceneIndexManager` for the full-text index
- **Model** — `KnowledgeItem` (JPA entity), `QARequest`/`QAResponse`/`SearchResult` (DTOs)

**Two Q&A modes** (both in `QAService`):

1. **Keyword search** (`POST /api/qa/ask`) — Lucene query → returns ranked snippets
2. **RAG** (`POST /api/qa/ask-llm`) — Lucene query → feeds results as context to an OpenAI-compatible LLM → returns natural language answer; falls back to keyword mode if LLM is disabled or fails

**File import pipeline** (`IngestionService.importFile`):
`FileTextExtractor` (txt/md/docx/xlsx/xls, auto-detect UTF-8 vs GBK encoding) → `ContentChunker` (paragraph/sentence-based, max 800 chars/chunk) → `KeywordExtractor` (Lucene SmartChineseAnalyzer + TF-IDF) → persist to H2 + Lucene index. First chunk becomes the parent record; subsequent chunks are children linked by `parentId`.

**Data storage** (all under `./data/`):
- `faqrobot` — H2 file database (auto-recreated on schema change via `ddl-auto: update`)
- `lucene-index/` — persistent Lucene index (auto-rebuilds from DB at startup if empty)
- `logs/` — log files with 30-day retention, 10MB rollover

**LLM integration** (`LLMConfig` + `LLMService`): Configuration via `application.yml` under `llm.*` prefix. Uses OpenAI-compatible `/chat/completions` endpoint. Defaults to Zhipu GLM; supports DeepSeek, Qwen, Ollama, or any compatible API by changing `api-url`, `chat-path`, and `model`. API key can be set via `LLM_API_KEY` environment variable.
