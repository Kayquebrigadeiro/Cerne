"""Evaluate exact annotated relations on real documents. No fabricated benchmark.
Manifest JSON: [{"file":"relative.pdf","expected":[{"exercicioId":"...",
"variacaoId":null,"musculoId":"...","negada":false,"trechoOriginal":"..."}]}]
Run on a held-out corpus after freezing vocabulary and patterns.
"""

import argparse
import json
from pathlib import Path
from scrawler_extractor.extract import Extractor


def key(row):
    return (
        row.get("exercicioId"),
        row.get("variacaoId"),
        row.get("musculoId"),
        bool(row.get("negada")),
        row.get("trechoOriginal", "").strip(),
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("dictionary", type=Path)
    parser.add_argument("--model", default="pt_core_news_sm")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    extractor = Extractor(json.loads(args.dictionary.read_text()), args.model or None)
    report = []
    tp = fp = fn = 0
    for doc in manifest:
        result = extractor.document(args.manifest.parent / doc["file"])
        actual = {key(r) for r in result["afirmacoes"]}
        expected = {key(r) for r in doc["expected"]}
        correct = len(actual & expected)
        extra = len(actual - expected)
        missed = len(expected - actual)
        tp += correct
        fp += extra
        fn += missed
        report.append(
            {
                "file": doc["file"],
                "true_positive": correct,
                "false_positive": extra,
                "false_negative": missed,
                "pending": len(result["pendencias"]),
            }
        )
    print(
        json.dumps(
            {
                "extractor": extractor.version,
                "documents": report,
                "precision": tp / (tp + fp) if tp + fp else None,
                "recall": tp / (tp + fn) if tp + fn else None,
                "note": "Metrica de extracao exata; nao mede eficacia de exercicios nem qualidade de prescricao.",
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
