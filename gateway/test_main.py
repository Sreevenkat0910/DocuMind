"""In-isolation tests for the gateway (no Java, no DB). Run: cd gateway && pytest."""
import os

os.environ.setdefault("INTERNAL_API_KEY", "test-key")

import main  # noqa: E402  (import after env is set)
from fastapi.testclient import TestClient  # noqa: E402

client = TestClient(main.app)
KEY = {"X-Internal-Api-Key": "test-key"}


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
