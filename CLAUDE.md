# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Environment

- **Java 17** (Corretto) at `D:\dev\jdks`. Set `JAVA_HOME=/d/dev/jdks` before running Maven.
- **Redis 8.4** at `D:\Redis\Redis-8.4.0-Windows-x64-cygwin-with-Service`. Start with `./redis-server.exe redis.conf`. If Redis is down, the app still runs — all Redis-dependent features have in-memory fallbacks.
- **MySQL 8.0** — required for startup. The app verifies the connection on boot (`MysqlConnectionVerifier`). Connection config is in `application.properties`. If MySQL is down, startup logs an ERROR but the app still starts (RAG features don't depend on MySQL).
- **Documents** for RAG knowledge base live in `D:\docs` (15 files: `java.txt`, `java-collections.txt`, `java-concurrency.txt`, `java-jvm.txt`, `spring.txt`, `spring-ioc-aop.txt`, `spring-boot-autoconfig.txt`, `spring-mvc-rest.txt`, `redis.txt`, `redis-in-depth.txt`, `mysql-index-transaction.txt`, `design-patterns.txt`, `rag-principles.txt`, `vector-search.txt`, `http-https.txt`). Ingested → 181 chunks.

## Dependencies (pom.xml)

- **spring-boot-starter-web** — Spring MVC + embedded Tomcat
- **spring-boot-starter-data-redis** — Redis via Lettuce client
- **commons-pool2** — connection pooling for Lettuce
- **spring-boot-starter-jdbc** — HikariCP connection pool
- **mysql-connector-j** (runtime) — MySQL JDBC driver
- **lombok** (optional) — boilerplate reduction
- **spring-boot-starter-test** (test) — JUnit 5 + Mockito

## Build & Run

```bash
export JAVA_HOME=/d/dev/jdks
export PATH=$JAVA_HOME/bin:$PATH
./mvnw spring-boot:run       # starts on port 9090
./mvnw test                   # run all tests
./mvnw test -Dtest=ClassName  # run single test class
./mvnw test -Dtest=ClassName#methodName  # run single test method
./mvnw test -Dtest=MysqlConnectionTest  # verify MySQL connection only
./mvnw test -Dtest=EvalRunnerTest#runRetrievalEvaluation   # retrieval metrics
./mvnw test -Dtest=EvalRunnerTest#compareBm25VsHybrid      # BM25 vs hybrid comparison
./mvnw test -Dtest=EvalRunnerTest#testCircuitBreakerFallback  # circuit breaker test
```

## Architecture Overview

Spring Boot 3.3.0 AI Agent Platform — 46 main source files across 6 capability areas:

| Capability | Key Components |
|---|---|
| **AI 对话工程化** | `ChatService`, `ChatSessionService`, `StreamController`, `ChatController` |
| **RAG 检索增强** | `HybridRetriever`, `Bm25Index`, `RrfFusion`, `BgeReranker`, `QueryRewriter`, `LlmQueryRewriter`, `RelevanceGate`, `HierarchicalChunker`, `CitationFormatter`, `CitationValidator` |
| **Embedding 弹性化** | `ResilientEmbeddingService` (@Primary, 熔断+缓存+降级), `SiliconFlowEmbeddingService` (主), `SimpleEmbeddingService` (备, TF-IDF) |
| **VectorStore 可插拔** | `VectorStore` (接口), `InMemoryVectorStore` (@Primary 实现), `VectorStoreConfig` (后端选择) |
| **文档入库管线** | `DocumentIngestionService` (Load→Chunk→Embed→Index), `DocumentRegistry` (SHA-256 变更检测), `AdminController` |
| **Agent / Tool Calling** | `ToolRegistry`, `ToolScheduler`, `ToolPermissionEvaluator`, `SearchTool`, `CalculatorTool` |
| **高并发高可用** | `ExternalLlmClient` (timeout+fallback), `ResilientEmbeddingService` (circuit breaker) |
| **可观测性** | `RequestLoggingFilter`, `logback-spring.xml` |
| **数据库连接** | `MysqlConnectionVerifier` (startup health check), HikariCP (connection pool). No business tables yet — MySQL is connected but not used by RAG features. |

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/chat` | Synchronous RAG chat. Body: `{"query":"...", "sessionId":"..."}` |
| `GET` | `/chat/stream` | SSE streaming chat. Params: `query`, `sessionId` |
| `POST` | `/mock-llm` | Mock LLM API for testing. Body: `{"prompt":"...", "model":"...", "delay":<ms>}` |
| `POST` | `/agent/tool-call` | Execute a tool. Body: `{"toolName":"...", "params":{...}, "role":"...", "sessionId":"..."}` |
| `GET` | `/agent/tools` | List available tools & JSON schemas |
| `POST` | `/admin/documents/ingest` | Trigger sync incremental ingestion |
| `POST` | `/admin/documents/ingest/async` | Trigger async ingestion, returns taskId |
| `GET` | `/admin/documents/status/{taskId}` | Query ingestion task progress |
| `GET` | `/admin/documents` | List indexed documents |
| `POST` | `/admin/documents/upload` | Upload single document. Params: `fileName`, `content` |

## Key Design Patterns

### RestTemplate: Shared vs Per-Service

`RestClientConfig` creates a shared `llmRestTemplate` bean (connectTimeout=3s, readTimeout=30s) — only `ExternalLlmClient` uses it. `SiliconFlowEmbeddingService` and `BgeReranker` each construct their own `RestTemplate` internally with **different** timeout settings (embedding read timeout = 60s, reranker = 30s). This is deliberate: embedding calls are slower than LLM calls, so they need longer timeouts. Sharing one RestTemplate with the shortest timeout would break embedding.

### Embedding: Primary + Fallback + Circuit Breaker

`ResilientEmbeddingService` (@Primary) wraps SiliconFlow API (primary) and TF-IDF (fallback). Three-layer defense:

1. **Query cache**: SHA-256(text) → float[], FIFO eviction (max 2000 entries), avoids re-embedding identical queries
2. **Circuit breaker**: CLOSED → (3 consecutive failures) → OPEN (60s cooldown) → HALF_OPEN (probe) → CLOSED
3. **Auto-fallback**: Primary unavailable → transparently switches to `SimpleEmbeddingService` (TF-IDF)

`SimpleEmbeddingService` requires `buildVocabulary()` before first use — `DocumentIngestionService` calls it during ingestion. `SiliconFlowEmbeddingService` has retry with exponential backoff (configurable: `app.embedding.retry-max`, `app.embedding.retry-backoff-ms`).

**⚠️ Thread-safety note**: `SimpleEmbeddingService.buildVocabulary()` replaces `vocabulary`, `termIndex`, and `idf` fields. If `embed()` is called concurrently with `buildVocabulary()`, it may read a partially-updated state. In practice, `buildVocabulary()` is called once during startup ingestion (single-threaded), so the risk is low. If ingestion is re-triggered via API while queries are running, this becomes a real race condition.

### VectorStore: Interface + Metadata

`VectorStore` is an interface. `InMemoryVectorStore` is the default (`@Primary` via `VectorStoreConfig`). `Entry` carries `dimension` + `modelName` metadata for multi-model coexistence. Search is O(N) brute-force cosine — sufficient for <10K vectors. For larger scale, implement the interface with Redis Stack (HNSW index) or pgvector.

### Document Ingestion: Incremental + Async

`DocumentIngestionService` runs on `@PostConstruct` via `CompletableFuture.runAsync()` — service starts in ~3s, ingestion runs in background. Pipeline:

```
Load (SHA-256 hash) → Chunk (HierarchicalChunker) → Embed (ResilientEmbeddingService) → Index (VectorStore + BM25 rebuild)
```

- **Change detection**: SHA-256 file hash in `DocumentRegistry`. Unchanged files are skipped.
- **Update safety**: Old chunks deleted from VectorStore before new ones are written.
- **BM25 rebuild**: Full rebuild from `DocumentRegistry.getAllChunkTexts()` after each ingestion batch (milliseconds for hundreds of documents). Parent ID/text mappings are set via `bm25Index.setParentIds()` and `bm25Index.setParentTexts()` during `rebuildBm25()` in `DocumentIngestionService`.
- **Async API**: `POST /admin/documents/ingest/async` returns taskId; poll `GET /admin/documents/status/{taskId}` for progress.
- **⚠️ Concurrency risk**: `rebuildBm25()` replaces BM25 internals while `HybridRetriever` may be reading them for a query. No read-write lock is in place — acceptable at low concurrency, but a known gap for production.

### RAG Pipeline

```
Request → RequestLoggingFilter (traceId + timing)
  → ChatService.ask() / askWithContext()
    → ChatSessionService.formatHistory()                 // Redis List: session history
    → QueryRewriter.rewrite(query, history)              // LLM-based pronoun resolution + expansion
    → HybridRetriever.retrieve(query, topK)              // 2-stage retrieval:
        Stage 1: BM25 + Embedding parallel → RRF fusion → parent dedup → ~18 candidates
        Stage 2: BgeReranker Cross-Encoder rerank → topK results
    → RelevanceGate.evaluate(docs)                       // maxScore < 0.35 → tell LLM "not found"
    → Cache lookup (Redis, SHA-256 key, fallback to ConcurrentHashMap)
    → CitationFormatter.formatReferenceSection(docs)     // [N] numbered references
    → ExternalLlmClient.callLlm(prompt)                  // HTTP → /mock-llm with timeouts
      → on timeout → fallbackResponse()
    → CitationValidator.validate(response)               // detect hallucinated ref numbers
    → Redis SET (TTL + jitter)
    → ChatSessionService.appendMessage()
```

### Tool Calling: Strategy Pattern + Security Boundary

`ToolDefinition` is the strategy interface. `SearchTool` and `CalculatorTool` are concrete strategies. `ToolRegistry` auto-collects all `ToolDefinition` Beans via `List<ToolDefinition>` constructor injection — adding a tool requires only `implements ToolDefinition` + `@Component`. `ToolScheduler` applies 5 sequential safety checks before execution: step limit → dead-loop detection (consecutive identical calls) → tool existence → RBAC permission → timed execution via `Future.get(stepTimeoutMs, MILLISECONDS)`.

### Mock LLM (`MockExternalLlmController`)

The project uses a mock LLM endpoint (`POST /mock-llm`) that returns keyword-matched responses rather than calling a real AI model. This is the single biggest gap between demo and production.

- **Request format**: `{"prompt":"...", "model":"...", "delay": <optional ms>}`
- **Response format**: `{"content":"【模拟 LLM 回答】...", "model":"...", "tokens": N}`
- **Keyword matching**: The mock scans the prompt for keywords like "缓存穿透", "IoC", "多线程", etc. and returns pre-written answers. If no keyword matches, returns a generic response.
- **`delay` parameter**: Optional manual delay override for testing timeout handling. When omitted, uses `ThreadLocalRandom` 100-200ms to simulate realistic API latency.
- **`ExternalLlmClient.fallbackResponse()`** has its own keyword-matching fallback logic (`ExternalLlmClient.java:103-120`) — separate from the mock LLM. This fallback triggers on network errors/timeouts, while the mock LLM is the "normal" path.

When switching to a real LLM (DeepSeek, Qwen, GLM, etc.), only `ExternalLlmClient.callLlm()` needs to change — the entire RAG pipeline stays the same.

### Logging (`logback-spring.xml`)

Two appenders:
- **CONSOLE**: Color-coded output with `%X{traceId}` from MDC. When traceId is unset, prints `-`.
- **FILE**: Structured `key=value` format (`time=... level=... traceId=... thread=... logger=... msg=...`), rolling by size (50MB) and time (daily), max 7 days / 1GB total. Designed for log platform ingestion (Filebeat/Fluentd).

### Small-to-Big Chunking (HierarchicalChunker)

- **Parent** (~2000 chars): full context for LLM. Stored as metadata, NOT embedded.
- **Child** (~500 chars, 128-char overlap): embedded for retrieval. Only Child vectors are in VectorStore.
- **Parent-level dedup**: Same parent appears at most once in results (prevents Top-K waste).

## Configuration Reference (application.properties)

| Key | Default | Purpose |
|---|---|---|
| `app.llm.connect-timeout` | 3 | TCP handshake timeout (seconds) |
| `app.llm.read-timeout` | 30 | Response wait timeout (seconds) |
| `app.llm.url` | http://localhost:9090/mock-llm | LLM API endpoint |
| `app.cache.ttl` | 3600 | Result cache base TTL (seconds) |
| `app.cache.ttl-jitter` | 0.1 | TTL random jitter fraction (±10%) to prevent cache avalanche |
| `app.session.max-history` | 20 | Chat history sliding window size |
| `app.session.ttl` | 604800 | Session expiry in seconds (default 7 days) |
| `app.agent.max-steps` | 10 | Max tool calls per agent loop |
| `app.agent.dead-loop-threshold` | 3 | Consecutive identical calls to trigger dead-loop |
| `app.agent.step-timeout-ms` | 30000 | Per-tool execution timeout (ms) |
| `app.rag.top-k` | 3 | Final chunks returned to LLM |
| `app.rag.relevance-threshold` | 0.35 | Reranker score below this → reject, don't RAG |
| `app.embedding.api-key` | — | SiliconFlow API key (⚠️ plaintext, don't commit) |
| `app.embedding.url` | https://api.siliconflow.cn/v1/embeddings | Embedding API endpoint |
| `app.embedding.model` | BAAI/bge-large-zh-v1.5 | Embedding model (1024 dim) |
| `app.embedding.batch-size` | 32 | Max texts per Embedding API call |
| `app.embedding.retry-max` | 2 | API call retries (exponential backoff) |
| `app.embedding.retry-backoff-ms` | 500 | Backoff base interval per retry attempt |
| `app.embedding.cache-max-size` | 2000 | Query embedding cache entries (FIFO) |
| `app.embedding.circuit-threshold` | 3 | Consecutive failures to open circuit breaker |
| `app.embedding.circuit-cooldown-seconds` | 60 | Circuit breaker cooldown before half-open probe |
| `app.vector-store.backend` | in-memory | VectorStore backend: `in-memory` or `redis-stack` (future) |
| `app.reranker.api-key` | ${app.embedding.api-key} | SiliconFlow API key (references embedding key) |
| `app.reranker.url` | https://api.siliconflow.cn/v1/rerank | Reranker API endpoint |
| `app.reranker.model` | BAAI/bge-reranker-v2-m3 | Cross-Encoder model name |
| `spring.datasource.url` | jdbc:mysql://localhost:3306/demo00?... | MySQL JDBC URL (auto-creates DB) |
| `spring.datasource.username` | root | MySQL user |
| `spring.datasource.password` | — | MySQL password |
| `spring.datasource.hikari.maximum-pool-size` | 10 | HikariCP max connections |
| `spring.data.redis.host` | localhost | Redis host |
| `spring.data.redis.port` | 6379 | Redis port |
| `spring.data.redis.timeout` | 3s | Redis connection timeout |
| `spring.data.redis.lettuce.pool.max-active` | 8 | Lettuce pool max connections |

> ⚠️ **Security note**: `app.embedding.api-key` contains a real SiliconFlow API key in plaintext. Do NOT commit this file to a public repository. Use environment variables or `@Value` with defaults for production.

## Evaluation

Evaluation code lives in `src/test/java/.../evaluation/`. `eval-dataset.json` (30 annotated queries, 4 types) is in `src/test/resources/`.

Dataset entry format:
```json
{
  "id": "eval-001",
  "query": "面向对象有哪三大特性",
  "type": "factoid",
  "groundTruthDocPrefixes": ["java.txt"],
  "referenceAnswer": "面向对象三大特性是封装、继承和多态。"
}
```
Query types: `factoid` (11), `conceptual` (15), `multi-hop` (3), `pronoun-resolution` (1). `groundTruthDocPrefixes` are matched against chunk IDs by prefix (e.g., chunk `java.txt:0:2` matches `["java.txt"]`).

**Test methods in `EvalRunnerTest`:**

- **`runRetrievalEvaluation()`**: Recall@K, Precision@K, MRR, NDCG@K — pure math, free, runs every `mvnw test`. Also breaks down results by query type (`printTypeBreakdown`).
- **`runGenerationEvaluation()`**: RAGAS-style Faithfulness / Answer Relevance / Context Relevance — keyword-overlap approximation (mock LLM). **Note**: scores are relative rankings, not absolute quality measures. Switch to LLM-as-Judge (GPT-4/Claude) for production.
- **`compareBm25VsHybrid()`**: BM25-only vs full hybrid retrieval head-to-head comparison. Runs the same 30 queries through both paths and prints a comparison table with Recall/Precision/MRR/NDCG and elapsed time. **MUST call `waitForIngestion()` first** — see testing caveat below.
- **`testCircuitBreakerFallback()`**: Verifies the embedding circuit breaker state machine. Uses reflection to force consecutive failures → OPEN state, then verifies fallback to TF-IDF produces valid (non-empty) vectors, and restores CLOSED state.

**⚠️ Testing caveat — async ingestion race**: `DocumentIngestionService` runs on `@PostConstruct` via `CompletableFuture.runAsync()`. `VectorStore.size() > 0` means the FIRST document was ingested, but `rebuildBm25()` only runs at the VERY END of `ingestAll()` (after all 15 docs). Tests that use `Bm25Index` directly before `rebuildBm25()` completes will get empty results. Use `waitForIngestion()` helper which polls both `vectorStore.size()` and `bm25Index.search("Java", 1)`.

**Empirical baseline (2026-06-08, 15 docs / 181 chunks / 30 queries, topK=3):**

| Metric | BM25-only | Hybrid (BM25+Vector+RRF+BGE Reranker) |
|---|---|---|
| Recall@3 | 0.96 | 0.99 |
| MRR | 0.91 | 0.95 |
| NDCG@3 | 1.40* | 1.24* |
| Latency (30 queries) | 12ms | ~14s |

\* NDCG can exceed 1.0 because `groundTruthDocPrefixes.size()` may be smaller than the actual number of relevant chunks retrieved (e.g., 2 prefixes but 3 relevant chunks in top-K). Compare directionally (BM25 ranks higher on this keyword-rich corpus) rather than citing absolute NDCG values.

To add queries: edit `eval-dataset.json`, add entries with `id`, `query`, `type` (factoid/conceptual/multi-hop/pronoun-resolution), `groundTruthDocPrefixes` (file name prefixes for matching chunk IDs), and optional `referenceAnswer`.

## Known Limitations (Demo vs Production)

These are known gaps — the code acknowledges them rather than hiding them:

| Limitation | Detail | Production Fix |
|---|---|---|
| **Mock LLM** | `MockExternalLlmController` returns keyword-matched pre-written responses | Replace `ExternalLlmClient.callLlm()` target with real API (DeepSeek/Qwen) |
| **O(N) vector search** | `InMemoryVectorStore.search()` brute-force cosine over all entries | Implement `VectorStore` with Redis Stack HNSN or pgvector IVFFlat |
| **No Agent loop** | `AgentController` passes empty `stepHistory` each call; dead-loop detection can't work at loop level | Build `AgentLoop` component that maintains in-memory call history across a reasoning chain |
| **BM25 rebuild race** | `rebuildBm25()` replaces index internals while queries may be reading | Add ReadWriteLock or double-buffer swap on rebuild |
| **No authentication** | All endpoints are open; role is client-claimed in request body | Add Spring Security + JWT; bind role to authenticated principal |
| **No rate limiting** | No protection against abuse or runaway LLM calls | Add bucket4j or Spring Cloud Gateway rate limiter |
| **Embedding API key in plaintext** | `app.embedding.api-key` in `application.properties` | Move to env var: `${EMBEDDING_API_KEY}` |
| **SimpleEmbeddingService not thread-safe** | `buildVocabulary()` and `embed()` race if ingestion runs while querying | Add `synchronized` or use `volatile` field swap after build completes |
| **HALF_OPEN thundering herd** | Multiple requests may probe primary simultaneously after cooldown | Use `AtomicBoolean.compareAndSet` to allow only one probe |
| **Fallback cache unbounded growth** | `ChatSessionService.fallbackStore` has no TTL; stale sessions never cleaned | Add scheduled cleanup or use Guava Cache with expiry |
| **No SSE from real LLM** | `StreamController.streamTokens()` simulates streaming by splitting pre-generated text | Connect to LLM streaming API via WebClient reactive flow |

## Critical Design Decisions (面试重点)

When explaining this project, focus on **trade-offs**, not just features:

- **Why self-implemented BM25 instead of Elasticsearch?** → Learning project; understanding the algorithm is more valuable than using a black box. k1=1.2, b=0.75 are TREC-verified optimal defaults.
- **Why RRF (k=60) instead of weighted sum for fusion?** → BM25 scores are unbounded, cosine similarity is [-1,1] — different scales make weighted sum unreliable without per-query normalization. RRF uses rank only, naturally cross-source comparable.
- **Why LPUSH+LTRIM for chat history instead of MySQL?** → Sliding window is O(1) append + O(N) trim (N=20, negligible). MySQL INSERT+DELETE+SELECT would be orders of magnitude slower and requires index maintenance.
- **Why CompletableFuture for parallel retrieval?** → BM25 and Embedding retrieval are independent I/O tasks. Parallel execution makes latency = max(BM25, Embedding) instead of sum.
- **Why `volatile` not `synchronized` for circuit breaker state?** → `volatile` guarantees visibility across threads (sufficient for state flags). `synchronized` would add unnecessary lock contention. Note: `volatile` does NOT guarantee atomicity — the HALF_OPEN→probe path has a known thundering-herd race condition, acceptable at low concurrency.
- **Why is `SimpleEmbeddingService` (TF-IDF) a working fallback, not a TODO?** → "降级方案是可工作的实现而非占位符" — this demonstrates high-availability thinking. The fallback may be semantically weaker, but it keeps the RAG pipeline operational when the primary API is down.

## Learning Notes

- `D:\AI-Notes\RAG\` — 11 demand packages covering the full RAG pipeline (01-basics through 11-evaluation).
- `D:\AI-Notes\Agent\` — 12 documents on rate limiting, timeout, circuit breaker, async, observability, multi-tenant, canary release, Prompt/Tool versioning.
- `D:\AI-Notes\Agent\three-works-agent\` — 15 project internalization documents: 01 project map, 02-09 module deep-dives (ChatService, HybridRetriever, ToolScheduler, RelevanceGate, ResilientEmbeddingService, SSE, Redis sessions, Evaluation), 10-15 Java fundamentals × project integration (Concurrency, Collections, Spring Boot, Redis, MySQL, JVM).
- `D:\AI-Notes\Java Basics\AI实习面试-项目映射Java基础\Java Basics-Expand\` — 8 source-code reading guides (01-CompletableFuture through 08-ToolScheduler), each analyzing a specific class or pattern in the project.
