"""DocuMind Embedding & LLM Gateway (Plan.md Phase 3).

Smallest possible first cut of the Python service: text in -> embedding vector out,
and prompt in -> LLM completion out. No vector store, no retrieval, no permissions —
Java still owns all of that. Every route requires the shared internal API key.
"""
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from groq import Groq
from psycopg_pool import ConnectionPool
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

API_KEY = os.environ["INTERNAL_API_KEY"]  # required: fail fast if unset
EMBED_MODEL = os.getenv("EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/documind")

app = FastAPI(title="DocuMind Embedding & LLM Gateway")
_embedder = SentenceTransformer(EMBED_MODEL)  # loaded once at startup (384-dim)
_groq = Groq(api_key=os.getenv("GROQ_API_KEY") or "gsk-not-set")
# Opened lazily on the first /retrieve so importing this module (tests, /embed-only use)
# needs no database.
_pool = ConnectionPool(DATABASE_URL, min_size=1, max_size=4, open=False)


def require_key(x_internal_api_key: str = Header(default="")):
    if x_internal_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="bad internal api key")


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    embedding: list[float]
    model: str
    dim: int


class EmbedBatchRequest(BaseModel):
    texts: list[str]


class EmbedBatchResponse(BaseModel):
    embeddings: list[list[float]]
    model: str
    dim: int


class Message(BaseModel):
    role: str
    content: str


class GenerateRequest(BaseModel):
    system: str | None = None
    messages: list[Message]


class GenerateResponse(BaseModel):
    content: str
    model: str
    usage: dict


class RetrieveRequest(BaseModel):
    query: str
    allowed_document_ids: list[str]
    top_k: int = 5


class RetrieveResult(BaseModel):
    chunk_id: str | None
    document_id: str | None
    score: float


class RetrieveResponse(BaseModel):
    results: list[RetrieveResult]


@app.get("/health")
def health():
    return {"status": "ok", "embed_model": EMBED_MODEL,
            "dim": _embedder.get_sentence_embedding_dimension()}


@app.post("/embed", response_model=EmbedResponse, dependencies=[Depends(require_key)])
def embed(req: EmbedRequest):
    vec = _embedder.encode(req.text).tolist()
    return EmbedResponse(embedding=vec, model=EMBED_MODEL, dim=len(vec))


@app.post("/embed/batch", response_model=EmbedBatchResponse, dependencies=[Depends(require_key)])
def embed_batch(req: EmbedBatchRequest):
    vecs = [v.tolist() for v in _embedder.encode(req.texts)]
    dim = len(vecs[0]) if vecs else _embedder.get_sentence_embedding_dimension()
    return EmbedBatchResponse(embeddings=vecs, model=EMBED_MODEL, dim=dim)


@app.post("/generate", response_model=GenerateResponse, dependencies=[Depends(require_key)])
def generate(req: GenerateRequest):
    msgs = ([{"role": "system", "content": req.system}] if req.system else [])
    msgs += [{"role": m.role, "content": m.content} for m in req.messages]
    resp = _groq.chat.completions.create(model=GROQ_MODEL, messages=msgs)
    usage = resp.usage.model_dump() if resp.usage else {}
    return GenerateResponse(content=resp.choices[0].message.content, model=resp.model, usage=usage)


# Raw cosine-distance ANN over Spring AI's vector_store table (Plan.md Phase 4). Java passes
# the already-computed allowed_document_ids and RE-VERIFIES every returned chunk_id — this
# endpoint never reads document_permissions and must never be treated as an authorization check.
_RETRIEVE_SQL = (
    "SELECT metadata->>'chunkId' AS chunk_id, "
    "       metadata->>'documentId' AS document_id, "
    "       1 - (embedding <=> %(vec)s::vector) AS score "
    "FROM vector_store "
    "WHERE metadata->>'documentId' = ANY(%(docs)s) "
    "ORDER BY embedding <=> %(vec)s::vector "
    "LIMIT %(k)s"
)


@app.post("/retrieve", response_model=RetrieveResponse, dependencies=[Depends(require_key)])
def retrieve(req: RetrieveRequest):
    if not req.allowed_document_ids:
        return RetrieveResponse(results=[])
    vec = "[" + ",".join(map(str, _embedder.encode(req.query).tolist())) + "]"
    _pool.open()  # idempotent; establishes the pool on first use
    with _pool.connection() as conn:
        rows = conn.execute(
            _RETRIEVE_SQL,
            {"vec": vec, "docs": req.allowed_document_ids, "k": req.top_k},
        ).fetchall()
    return RetrieveResponse(results=[
        RetrieveResult(chunk_id=r[0], document_id=r[1], score=float(r[2])) for r in rows
    ])
