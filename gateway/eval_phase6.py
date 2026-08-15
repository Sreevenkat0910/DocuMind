"""Phase 6 retrieval A/B eval (Plan.md Phase 6 exit criteria).

Compares two retrieval methods on the hand-built eval set in eval/questions.json, over the
documents listed in eval/doc_ids.json (re-upload the docs in eval/docs/*.txt and update that
file if the corpus changes — document ids are assigned fresh on each upload):

  - "baseline"  the exact Phase 4/5 plain-vector-ANN query (top_k results, no fusion/rerank)
  - "phase6"    hybrid_retrieve(): vector + Postgres full-text -> RRF fusion -> CrossEncoder rerank

Metric: Precision@1 (did the #1-ranked chunk come from the expected document?) is the headline
number — the most discriminating metric on a 5-document corpus, and what actually drives which
source grounds the LLM's answer. Hit@3 is reported alongside for context.

Run inside the gateway container (has DB access + all deps):
    docker cp eval documind-gateway:/app/eval
    docker cp eval_phase6.py documind-gateway:/app/eval_phase6.py
    docker exec -e INTERNAL_API_KEY=<key> documind-gateway python eval_phase6.py
"""
import json
import os

import main

_HERE = os.path.dirname(os.path.abspath(__file__))
TOP_K = 3


def _baseline_top_k(query: str, allowed_document_ids: list[str], top_k: int) -> list[dict]:
    """The plain vector-ANN query Phase 4/5 used, before Phase 6's hybrid search + rerank."""
    if not allowed_document_ids:
        return []
    vec = "[" + ",".join(map(str, main._embedder.encode(query).tolist())) + "]"
    main._pool.open()
    with main._pool.connection() as conn:
        rows = conn.execute(
            main._VECTOR_CANDIDATES_SQL,
            {"vec": vec, "docs": allowed_document_ids, "n": top_k},
        ).fetchall()
    return [main._row_to_hit(r) for r in rows]


def run():
    with open(os.path.join(_HERE, "eval", "questions.json")) as f:
        questions = json.load(f)
    with open(os.path.join(_HERE, "eval", "doc_ids.json")) as f:
        doc_ids = json.load(f)
    allowed = list(doc_ids.values())
    id_to_name = {v: k for k, v in doc_ids.items()}

    rows = []
    baseline_p1 = baseline_hit3 = phase6_p1 = phase6_hit3 = 0

    for q in questions:
        question, expected = q["question"], q["expected_doc"]

        base_hits = _baseline_top_k(question, allowed, TOP_K)
        p6_hits = main.hybrid_retrieve(question, allowed, TOP_K)

        base_docs = [id_to_name.get(h["document_id"], "?") for h in base_hits]
        p6_docs = [id_to_name.get(h["document_id"], "?") for h in p6_hits]

        base_p1 = bool(base_docs) and base_docs[0] == expected
        p6_p1 = bool(p6_docs) and p6_docs[0] == expected
        base_h3 = expected in base_docs
        p6_h3 = expected in p6_docs

        baseline_p1 += base_p1
        phase6_p1 += p6_p1
        baseline_hit3 += base_h3
        phase6_hit3 += p6_h3

        rows.append((question, expected, base_docs[:1], base_h3, p6_docs[:1], p6_h3))

    n = len(questions)
    print(f"| # | Question | Expected | Baseline top-1 | Phase 6 top-1 |")
    print(f"|---|---|---|---|---|")
    for i, (q, exp, base_top1, base_h3, p6_top1, p6_h3) in enumerate(rows, 1):
        b = (base_top1[0] if base_top1 else "-") + ("" if base_h3 else " ✗")
        p = (p6_top1[0] if p6_top1 else "-") + ("" if p6_h3 else " ✗")
        print(f"| {i} | {q} | {exp} | {b} | {p} |")

    print()
    print(f"Precision@1 — baseline: {baseline_p1}/{n} ({100*baseline_p1/n:.0f}%)"
          f"  |  phase6: {phase6_p1}/{n} ({100*phase6_p1/n:.0f}%)")
    print(f"Hit@{TOP_K}      — baseline: {baseline_hit3}/{n} ({100*baseline_hit3/n:.0f}%)"
          f"  |  phase6: {phase6_hit3}/{n} ({100*phase6_hit3/n:.0f}%)")


if __name__ == "__main__":
    run()
