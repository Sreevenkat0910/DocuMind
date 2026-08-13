"""DocuMind Embedding & LLM Gateway (Plan.md Phase 3).

Smallest possible first cut of the Python service: text in -> embedding vector out,
and prompt in -> LLM completion out. No vector store, no retrieval, no permissions —
Java still owns all of that. Every route requires the shared internal API key.
"""
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from groq import Groq
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

API_KEY = os.environ["INTERNAL_API_KEY"]  # required: fail fast if unset
EMBED_MODEL = os.getenv("EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
GROQ_MODEL = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")

app = FastAPI(title="DocuMind Embedding & LLM Gateway")
_embedder = SentenceTransformer(EMBED_MODEL)  # loaded once at startup (384-dim)
_groq = Groq(api_key=os.getenv("GROQ_API_KEY") or "gsk-not-set")


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
