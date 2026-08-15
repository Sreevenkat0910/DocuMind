"""Phase 7 RAGAS-style eval: context precision/recall + faithfulness on the Phase 6 eval set.

Calls the real POST /rag/query over HTTP (exercising Phase 7's tracing too — each question
gets its own X-Correlation-Id, so `docker logs documind-gateway | grep <id>` shows that
question's full per-stage trace). No `ragas` package: these are hand-rolled versions of the
same three metrics, reusing the ground-truth doc mapping the Phase 6 eval set already has, so
no new heavy dependency (ragas pulls in langchain + datasets + nltk transitively).

Run inside the gateway container:
    docker exec -e INTERNAL_API_KEY=<key> documind-gateway python eval_phase7.py
"""
import json
import os
import uuid

import httpx

import main

_HERE = os.path.dirname(os.path.abspath(__file__))
API_KEY = os.environ["INTERNAL_API_KEY"]
BASE_URL = "http://localhost:8000"
TOP_K = 3


def context_precision_recall(used_chunk_ids: list[str], doc_by_chunk: dict[str, str],
                              expected_doc: str) -> tuple[float, float]:
    """Precision: fraction of cited chunks that actually came from the expected document.
    Recall: whether the expected document's content made it into the citations at all
    (single-relevant-document ground truth, matching the Phase 6 eval set's design)."""
    if not used_chunk_ids:
        return 0.0, 0.0
    docs = [doc_by_chunk.get(cid) for cid in used_chunk_ids]
    precision = sum(1 for d in docs if d == expected_doc) / len(docs)
    recall = 1.0 if expected_doc in docs else 0.0
    return precision, recall


FAITHFULNESS_PROMPT = (
    "You are grading whether an AI answer is faithful to its provided context. "
    "Output ONLY a number from 0 to 1 (e.g. 0.8): the fraction of factual claims in the ANSWER "
    "that are directly supported by the CONTEXT. If the answer says it doesn't know, output 1.\n\n"
    "CONTEXT:\n{context}\n\nANSWER:\n{answer}\n\nScore (0-1 only):"
)


def faithfulness(answer: str, context: str) -> float:
    """Groq-as-judge: does the answer's content hold up against the retrieved context?"""
    resp = main._groq.chat.completions.create(model=main.GROQ_MODEL, messages=[
        {"role": "user", "content": FAITHFULNESS_PROMPT.format(context=context, answer=answer)},
    ])
    text = (resp.choices[0].message.content or "0").strip()
    try:
        return max(0.0, min(1.0, float(text.split()[0])))
    except ValueError:
        return 0.0


def run():
    with open(os.path.join(_HERE, "eval", "questions.json")) as f:
        questions = json.load(f)
    with open(os.path.join(_HERE, "eval", "doc_ids.json")) as f:
        doc_ids = json.load(f)
    allowed = list(doc_ids.values())
    id_to_name = {v: k for k, v in doc_ids.items()}

    rows = []
    for q in questions:
        question, expected = q["question"], q["expected_doc"]
        correlation_id = str(uuid.uuid4())

        r = httpx.post(f"{BASE_URL}/rag/query",
                        headers={"X-Internal-Api-Key": API_KEY, "X-Correlation-Id": correlation_id},
                        json={"question": question, "allowed_document_ids": allowed, "top_k": TOP_K},
                        timeout=30)
        body = r.json()
        used_chunk_ids, answer = body.get("used_chunk_ids", []), body.get("answer", "")

        # /rag/query's response doesn't carry per-chunk document ids, so resolve them the same
        # way it retrieved them, to score precision/recall and build the faithfulness context.
        hits = main.hybrid_retrieve(question, allowed, TOP_K)
        doc_by_chunk = {h["chunk_id"]: id_to_name.get(h["document_id"], "?") for h in hits}
        context = "\n\n".join(h["content"] for h in hits)

        precision, recall = context_precision_recall(used_chunk_ids, doc_by_chunk, expected)
        faith = faithfulness(answer, context) if answer else 0.0
        rows.append((question, precision, recall, faith, correlation_id))

    n = len(rows)
    print("| # | Question | Context precision | Context recall | Faithfulness |")
    print("|---|---|---|---|---|")
    for i, (q, p, rec, f, _cid) in enumerate(rows, 1):
        print(f"| {i} | {q} | {p:.2f} | {rec:.0f} | {f:.2f} |")

    avg_p = sum(row[1] for row in rows) / n
    avg_r = sum(row[2] for row in rows) / n
    avg_f = sum(row[3] for row in rows) / n
    print(f"\nAverages — context precision: {avg_p:.2f}  |  context recall: {avg_r:.2f}"
          f"  |  faithfulness: {avg_f:.2f}")
    print(f"Sample correlation id (grep in `docker logs documind-gateway`): {rows[0][4]}")


if __name__ == "__main__":
    run()
