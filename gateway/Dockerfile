FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

ENV EMBED_MODEL=sentence-transformers/all-MiniLM-L6-v2
ENV RERANK_MODEL=cross-encoder/ms-marco-MiniLM-L-6-v2
# Bake both models into the image so the first request isn't a slow (or flaky) network
# download at runtime — this is exactly the ONNX-download bug Phase 5.5 root-caused on the
# Java side; don't repeat it here.
RUN python -c "from sentence_transformers import SentenceTransformer; import os; SentenceTransformer(os.environ['EMBED_MODEL'])"
RUN python -c "from sentence_transformers import CrossEncoder; import os; CrossEncoder(os.environ['RERANK_MODEL'])"

COPY main.py .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
