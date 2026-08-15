"""In-isolation tests for the gateway. Most need no DB; the /index round-trip does and is
skipped automatically when no postgres/pgvector is reachable. Run: cd gateway && pytest."""
import os
import uuid

import pytest

os.environ.setdefault("INTERNAL_API_KEY", "test-key")

import main  # noqa: E402  (import after env is set)
from fastapi.testclient import TestClient  # noqa: E402

client = TestClient(main.app)
KEY = {"X-Internal-Api-Key": "test-key"}


def _db_available():
    try:
        main._ensure_schema()  # idempotent; also proves the DB is reachable
        return True
    except Exception:
        return False


def test_health_reports_384_dim():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["dim"] == 384


def test_embed_returns_384_vector():
    r = client.post("/embed", json={"text": "hello world"}, headers=KEY)
    assert r.status_code == 200
    body = r.json()
    assert body["dim"] == 384 and len(body["embedding"]) == 384


def test_embed_batch_one_vector_per_input():
    r = client.post("/embed/batch", json={"texts": ["a", "b", "c"]}, headers=KEY)
    assert r.status_code == 200
    assert len(r.json()["embeddings"]) == 3


def test_missing_key_is_rejected():
    assert client.post("/embed", json={"text": "x"}).status_code == 401


def test_retrieve_empty_allowed_short_circuits():
    # No allowed docs -> empty results without touching the DB (pool stays closed).
    r = client.post("/retrieve",
                    json={"query": "q", "allowed_document_ids": [], "top_k": 5},
                    headers=KEY)
    assert r.status_code == 200
    assert r.json()["results"] == []


def test_generate_calls_groq(monkeypatch):
    class Usage:
        def model_dump(self):
            return {"total_tokens": 3}

    class Msg:
        content = "You get 20 days [1]."

    class Choice:
        message = Msg()

    class Resp:
        choices = [Choice()]
        model = "llama-3.3-70b-versatile"
        usage = Usage()

    monkeypatch.setattr(main._groq.chat.completions, "create", lambda **kw: Resp())
    r = client.post("/generate",
                    json={"system": "s", "messages": [{"role": "user", "content": "q"}]},
                    headers=KEY)
    assert r.status_code == 200
    assert r.json()["content"] == "You get 20 days [1]."


def test_rag_query_empty_allowed_short_circuits():
    # No allowed docs -> no_context_found without touching the DB or the LLM.
    r = client.post("/rag/query",
                    json={"question": "q", "allowed_document_ids": [], "top_k": 5},
                    headers=KEY)
    assert r.status_code == 200
    body = r.json()
    assert body["no_context_found"] is True
    assert body["answer"] == "" and body["used_chunk_ids"] == []


def test_cited_chunk_ids_maps_only_real_markers():
    ids = ["c1", "c2", "c3"]
    # cites [1] and [3] (deduped, ascending); [9] is out of range and ignored.
    assert main.cited_chunk_ids("Answer from [1] and again [1], plus [3]. Ignore [9].", ids) == ["c1", "c3"]
    # no markers -> nothing cited.
    assert main.cited_chunk_ids("A plain answer with no sources.", ids) == []


def test_index_empty_short_circuits():
    # No chunks -> nothing written, no DB needed.
    r = client.post("/index", json={"chunks": []}, headers=KEY)
    assert r.status_code == 200
    assert r.json()["indexed"] == 0


@pytest.mark.skipif(not _db_available(), reason="needs a running postgres/pgvector")
def test_index_inserts_one_vector_row_per_chunk():
    doc_id = str(uuid.uuid4())
    chunks = [
        {"chunk_id": str(uuid.uuid4()), "document_id": doc_id, "content": "alpha content"},
        {"chunk_id": str(uuid.uuid4()), "document_id": doc_id, "content": "beta content"},
    ]
    try:
        r = client.post("/index", json={"chunks": chunks}, headers=KEY)
        assert r.status_code == 200
        assert r.json()["indexed"] == 2
        with main._pool.connection() as conn:
            n = conn.execute(
                "select count(*) from vector_store where metadata->>'documentId' = %s",
                (doc_id,)).fetchone()[0]
        assert n == 2, "expected exactly one vector row per indexed chunk"
    finally:
        with main._pool.connection() as conn:
            conn.execute("delete from vector_store where metadata->>'documentId' = %s", (doc_id,))


# --- Phase 6 ---

def test_split_page_respects_structure_and_keeps_page_number():
    long_text = "First paragraph sentence one. Sentence two.\n\n" + ("word " * 400)
    chunks = main.split_page(long_text, page_number=7)
    assert len(chunks) > 1, "text well over chunk_size should split into multiple chunks"
    assert all(c["page_number"] == 7 for c in chunks), "chunks must keep the source page number"
    assert all(c["content"].strip() for c in chunks), "no blank chunks"


def test_split_page_blank_text_produces_no_chunks():
    assert main.split_page("   ", page_number=1) == []
    assert main.split_page("", page_number=1) == []


def test_chunk_endpoint_returns_chunks_per_page_no_db():
    r = client.post("/chunk", json={"pages": [
        {"page_number": 1, "text": "Some page-one content here."},
        {"page_number": 2, "text": ""},  # blank page contributes nothing
        {"page_number": 3, "text": "Page three has different content."},
    ]}, headers=KEY)
    assert r.status_code == 200
    chunks = r.json()["chunks"]
    assert {c["page_number"] for c in chunks} == {1, 3}
    assert len(chunks) == 2  # one chunk per non-blank page, since both are well under chunk_size


def test_rrf_fuse_ranks_items_in_both_lists_above_single_list_items():
    vector_hits = [
        {"chunk_id": "shared", "document_id": "d", "content": "a", "score": 0.9},
        {"chunk_id": "vector-only", "document_id": "d", "content": "b", "score": 0.8},
    ]
    lexical_hits = [
        {"chunk_id": "shared", "document_id": "d", "content": "a", "score": 5.0},
        {"chunk_id": "lexical-only", "document_id": "d", "content": "c", "score": 4.0},
    ]
    fused = main.rrf_fuse(vector_hits, lexical_hits)
    ids = [h["chunk_id"] for h in fused]
    assert ids[0] == "shared", "a chunk ranked in both lists should out-rank one ranked in only one"
    assert set(ids) == {"shared", "vector-only", "lexical-only"}


def test_rrf_fuse_drops_hits_with_no_chunk_id():
    vector_hits = [{"chunk_id": None, "document_id": "d", "content": "orphan", "score": 0.9}]
    assert main.rrf_fuse(vector_hits, []) == []


def test_rerank_reorders_by_crossencoder_score_and_overwrites_score(monkeypatch):
    candidates = [
        {"chunk_id": "low", "document_id": "d", "content": "irrelevant", "score": 99.0},
        {"chunk_id": "high", "document_id": "d", "content": "relevant", "score": 1.0},
    ]
    # Stub the model so this test doesn't depend on the real CrossEncoder's judgment.
    monkeypatch.setattr(main._reranker, "predict", lambda pairs: [0.1, 9.9])
    ranked = main.rerank("q", candidates, top_k=5)
    assert [c["chunk_id"] for c in ranked] == ["high", "low"]
    assert ranked[0]["score"] == 9.9, "score must be replaced with the CrossEncoder's score"


def test_hybrid_retrieve_empty_allowed_short_circuits():
    # No allowed docs -> [] without touching the DB.
    assert main.hybrid_retrieve("q", [], top_k=5) == []
