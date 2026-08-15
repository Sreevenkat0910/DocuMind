# DocuMind — Copy/Paste Prompts (Phase 4 checkpoint → Phase 10)

Paste one line at a time. Each prompt is self-contained — I read `Plan.md` for the phase scope and act on it. Wait for one prompt to finish before pasting the next.

---

## Checkpoint — verify Phases 2, 3, 4 before starting Phase 5

```
Verify Phases 2, 3, and 4 in Plan.md are fully implemented and functioning. For each: confirm the code exists, run the tests, and check the exit criteria in Plan.md are actually met. Report a per-phase PASS/FAIL table with evidence (test output, files). Do not start Phase 5.
```

```
Bring up all containers with docker compose up and prove the full end-to-end path works: upload a document, index it, ask a question, get a cited answer — with embeddings, the LLM call, and vector search all happening in the Python service. Show me the actual request/response.
```

```
Run the Phase 4 security check from Plan.md: revoke a permission mid-conversation and confirm a subsequent question cannot surface that document's chunks, proving Java's re-verification still holds with retrieval in Python. Show the test proving it.
```

```
Fix any FAIL from the checkpoint above. Root-cause each one, apply the fix, re-run the relevant tests, and re-report the PASS/FAIL table. Only tell me it's green when the tests actually pass.
```

---

## Phase 5 — Prompt construction + read-path orchestration into Python

```
Implement Phase 5 from Plan.md (Python owns RAG assembly via POST /rag/query; ChatService.ask shrinks to orchestrate + re-verify). Follow the scope exactly. Show me the plan first, then implement.
```

```
Add the Phase 5 exit-criteria test: a forged/malformed chunk_id from a stubbed Python /rag/query response must still be rejected by Java's re-verification. Write it, run it, show it passing.
```

```     
Verify Phase 5 exit criteria in Plan.md: full write-path and read-path work with Python as the RAG brain. Run the tests, demo the flow, report PASS/FAIL.
```

```
Update the repo README to document the "Python never queries document_permissions; Java re-verifies every chunk_id" rule and the current Java⇄Python API contract from Section 5 of Plan.md.
```

---

## Phase 5.5 — Finish the migration: Python owns vector storage, drop Spring AI from Java

> **Why:** after Phase 5 the *read* path (embed, search, RAG) is in Python, but the *write*
> path still stores vectors through Spring AI's `VectorStore`, and 4 `spring-ai-*` deps remain
> on the Java classpath. This sub-phase finishes the job so the boundary is clean: **Java =
> system-of-record + auth + orchestration + the API the frontend calls; Python = the single AI
> engine (now including vector storage). The frontend talks only to Java; only Java calls Python.**
>
> **Migrate → Python:** ingestion vector writes, the embedding bridge, the in-process vector-search
> fallback, and every Spring AI dependency + config line.
> **Do NOT migrate (stays in Java):** Postgres relational data + JPA, JWT/RBAC auth, the chunk-id
> re-verification gate, file storage + PDF parsing, orchestration, and the public REST API. No new
> infrastructure — no queues, gRPC, GraphQL, or second database.

```
Analyze and plan Phase 5.5: Python should own writing embeddings into vector_store via a new POST /index, and Java should drop Spring AI entirely. Confirm exactly which Java files get deleted or changed, which spring-ai-* dependencies leave pom.xml (including the BOM if nothing else needs it), which application.properties lines are removed, and that vector_store schema creation (CREATE EXTENSION vector + table + HNSW index) moves from Spring AI's initialize-schema to Python on startup. Show me the plan and the migrate / don't-migrate list before writing any code.
```

```
Implement Phase 5.5. Python: add POST /index that embeds chunk texts and inserts them into vector_store using the SAME columns /retrieve and /rag/query already read (content + metadata {chunkId, documentId} + embedding), and create the vector_store table + HNSW index (and the vector extension) on startup. Java: have ChunkIngestionService call /index via GatewayClient instead of vectorStore.add(...); delete RestEmbeddingModel, the Spring AI VectorStore bean, and the in-process candidatesViaVectorStore fallback in ChunkRetrievalService; remove the spring-ai-* dependencies from pom.xml and the Spring AI lines from application.properties. Do NOT touch the re-verification gate, auth, JPA, or PDF parsing. Plan first, then implement.
```

```
Add/adjust the Phase 5.5 tests: a Python test that POST /index inserts one vector row per chunk, and update the Java integration tests so ingestion writes via /index (no Spring AI EmbeddingModel/VectorStore). Run the full Java + Python suites and report PASS/FAIL. Only tell me it's green when the tests actually pass.
```

```
Prove Phase 5.5 is complete: grep the Java source and pom.xml to show zero org.springframework.ai imports and zero spring-ai-* dependencies remain. Then bring everything up and demo upload → index → ask → cited answer end-to-end, showing the vector row was written by Python's /index. Report PASS/FAIL with evidence.
```

```
Update the README and the Java⇄Python contract table: add POST /index (Phase 5.5), state that Python now owns the vector_store table and all embedding/search/LLM, and that Java no longer depends on Spring AI. Keep the "Python never queries document_permissions; Java re-verifies every chunk_id" rule prominent.
```

---

## Phase 6 — RAG quality (structure-aware chunking, hybrid search, reranking)

> **Post-5.5 note:** Python now owns ingestion writes (`/index`) and retrieval, so structure-aware
> chunking is naturally a Python job — do the splitting in the gateway (inside `/index`, or a new
> endpoint that takes raw document text) instead of Java's `ChunkingService`, keeping Java as pure
> orchestration. Hybrid search + reranking already belong in the gateway's `/retrieve` + `/rag/query`.

```
Implement Phase 6 from Plan.md: structure-aware chunking, hybrid search (vector + BM25 with reciprocal rank fusion), and CrossEncoder reranking — all in the Python gateway (chunking moves out of Java's ChunkingService now that Python owns /index). Show me the plan first, then implement.
```

```
Build a small eval set of 15 question/expected-answer pairs for our documents, then A/B Phase 6 retrieval against Phase 5 plain vector search on it. Give me a before/after precision comparison table.
```

```
Verify Phase 6 exit criteria and write the short before-vs-after retrieval quality comparison from Plan.md into the README.
```

---

## Phase 7 — Observability across the service boundary

```
Implement Phase 7 from Plan.md: propagate a correlationId from Java to Python via header and log it on both sides, add per-stage tracing (retrieve / rerank / LLM) with Langfuse or structured logging, and RAGAS-style faithfulness + context precision/recall on the Phase 6 eval set. Plan first, then implement.
```

```
Verify Phase 7 exit criteria: show me a single trace where I can see exactly where a slow request's latency went, broken down by stage.
```

---

## Phase 8 — Streaming responses end-to-end

```
Implement Phase 8 from Plan.md: add POST /rag/query/stream in Python (StreamingResponse + Groq streaming), have Java consume it and re-expose via SSE, and end the stream with a final structured metadata event carrying citations/used_chunk_ids. Plan first, then implement.
```

```
Verify Phase 8 exit criteria: the tester UI shows tokens appearing incrementally and citations arriving once the stream closes. Show me it working.
```

---

## Phase 9 — OCR & multi-format ingestion

```
Implement Phase 9 from Plan.md: add POST /parse in Python (OCR via pytesseract/unstructured, plus DOCX/XLSX/PPTX), and have Java's DocumentService call it for formats PDFBox can't handle. Plan first, then implement.
```

```
Verify Phase 9 exit criteria: a scanned PDF that previously indexed zero chunks now indexes successfully, and a DOCX upload works end-to-end. Show both.
```

---

## Phase 10 — Production hardening

> **Post-5.5 must-dos:**
> 1. **CI ordering** — the Java integration tests now call the gateway's `/index` during ingestion,
>    so CI must start the **gateway + Postgres containers before** running the Java test suite.
> 2. **No fallback** — Phase 5.5 removed the in-process vector path, so if the gateway is down Java
>    cannot embed / search / index at all. The Resilience4j circuit-breaker's "graceful degradation"
>    therefore means **failing cleanly**: ingestion → durable `FAILED` status; chat → a clear
>    "AI service unavailable", never a silent empty answer.
> 3. **Restricted DB role** (Plan §256, optional) — Python now *writes* `vector_store`, so its role
>    needs `SELECT`+`INSERT` on `vector_store` (and rights to create that table on startup), still
>    with **no** access to `users` / `document_permissions`.

```
Implement Phase 10 from Plan.md: full multi-container docker-compose (Java, Python, Postgres, optional Redis), Resilience4j retry/circuit-breaker on Java→Python calls with clean failure (ingestion marks FAILED, chat returns a clear unavailable message — there is no in-process fallback after Phase 5.5), and GitHub Actions CI that starts the gateway + Postgres before running the Java tests and also runs the Python tests. Plan first, then implement. Treat the RabbitMQ ingestion queue as an optional stretch — ask me before doing it.
```

```
Verify Phase 10 exit criteria: the system survives the Python service restarting mid-conversation, and ingestion fails loudly and recoverably (durable FAILED status) instead of silently losing a document. Show me the resilience test.
```
Remaining work:
- docker-compose.yml: add the app (Java) and redis services, a gateway healthcheck, and wire app's env vars (SPRING_DATASOURCE_URL, DOCUMIND_GATEWAY_URL, DOCUMIND_STORAGE_LOCATION, JWT_SECRET, INTERNAL_API_KEY) plus a storage volume — Dockerfile and .dockerignore are already written.
- GitHub Actions CI: create .github/workflows/ci.yml — checkout, JDK 21 + Python setup, docker compose up -d --wait postgres gateway with job-level dummy env vars, ./mvnw test, then pytest against the same Postgres container.
- Verify end-to-end: bring up the full compose stack, confirm the app container serves traffic through Postgres/gateway, then demonstrate the two Phase 10 exit criteria (kill/restart the gateway mid-conversation and confirm chat degrades gracefully instead of crashing; force a gateway-down ingestion and confirm the document lands in FAILED, not silently lost).
---

## Anytime prompts (use as needed between phases)

```
Fetch and pull the latest changes safely; if there are conflicts, preserve both sides and show me before committing.
```

```
Commit and push the current phase's changes safely with a clear message. Show me the diff first.
```

```
Run all tests (Java + Python) and give me a PASS/FAIL summary. Root-cause and fix anything that fails.
```

```
We're deviating from Plan.md — add a new phase to Plan.md for <describe it>: goal, scope, and exit criteria, placed in the right order. Then add matching prompts to prompts.md.
```
