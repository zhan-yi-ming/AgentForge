import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from typing import Any

from agentforge_agent.evaluation.dataset import EvaluationDataset, load_dataset
from agentforge_agent.evaluation.metrics import (
    score_faithfulness,
    score_ranking,
    score_tool_result,
)
from agentforge_agent.evaluation.subject import CurrentCodeSubject


class EvaluationRunner:
    def __init__(self, subject: CurrentCodeSubject) -> None:
        self.subject = subject

    def run(
        self,
        dataset: EvaluationDataset,
        dataset_sha256: str,
        generated_at: str | None = None,
    ) -> dict[str, Any]:
        rag_results: list[dict[str, Any]] = []
        for case in dataset.rag_cases:
            retrieved = self.subject.retrieve(case.query, dataset.corpus)
            score = score_ranking(retrieved, set(case.relevant_source_ids), case.k)
            rag_results.append({
                "id": case.id,
                "k": case.k,
                "relevantSourceIds": sorted(case.relevant_source_ids),
                "retrievedSourceIds": retrieved,
                "recallAtK": score.recall_at_k,
                "reciprocalRank": score.reciprocal_rank,
                "hitRate": score.hit_rate,
            })

        answer_results: list[dict[str, Any]] = []
        for case in dataset.answer_cases:
            score = score_faithfulness(case.answer, case.context)
            answer_results.append({
                "id": case.id,
                "faithfulness": score.score,
                "claims": [
                    {
                        "text": claim.text,
                        "tokenSupport": claim.token_support,
                        "supported": claim.supported,
                    }
                    for claim in score.claims
                ],
            })

        tool_results: list[dict[str, Any]] = []
        for case in dataset.tool_cases:
            actual = self.subject.plan_tool(case.input)
            score = score_tool_result(actual, case.expected_proposal)
            tool_results.append({
                "id": case.id,
                "expectedProposal": case.expected_proposal,
                "actualProposal": (
                    None
                    if actual is None
                    else actual.model_dump(mode="json", by_alias=True, exclude_none=True)
                ),
                "selectionAccuracy": score.selection_accuracy,
                "taskSuccess": score.task_success,
            })

        return {
            "schemaVersion": 1,
            "generatedAt": generated_at or _utc_now(),
            "dataset": {
                "name": dataset.name,
                "schemaVersion": dataset.schema_version,
                "sha256": dataset_sha256,
            },
            "subject": "agentforge-current-code-offline-v1",
            "metrics": {
                "recallAtK": _mean(rag_results, "recallAtK"),
                "mrr": _mean(rag_results, "reciprocalRank"),
                "hitRate": _mean(rag_results, "hitRate"),
                "faithfulness": _mean(answer_results, "faithfulness"),
                "toolSelectionAccuracy": _mean(tool_results, "selectionAccuracy"),
                "taskSuccessRate": _mean(tool_results, "taskSuccess"),
            },
            "methods": {
                "rag": {
                    "unit": "source-id",
                    "ranking": "hash-vector-plus-bm25-rrf",
                },
                "faithfulness": {
                    "kind": "lexical-claim-support-proxy",
                    "supportThreshold": 0.8,
                    "limitation": (
                        "Deterministic regression proxy; it does not establish semantic "
                        "entailment or factual correctness."
                    ),
                },
                "tool": {
                    "selection": "exact action type including no-tool",
                    "taskSuccess": "exact normalized proposal including all parameters",
                },
            },
            "cases": {
                "rag": rag_results,
                "answer": answer_results,
                "tool": tool_results,
            },
        }


def write_report(report: dict[str, Any], output_path: str | Path) -> None:
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(f"{output.suffix}.tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(output)


def run_file(dataset_path: str | Path, output_path: str | Path) -> dict[str, Any]:
    dataset_bytes = Path(dataset_path).read_bytes()
    dataset = load_dataset(dataset_path)
    report = EvaluationRunner(CurrentCodeSubject()).run(
        dataset,
        dataset_sha256=hashlib.sha256(dataset_bytes).hexdigest(),
    )
    write_report(report, output_path)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Run AgentForge offline evaluation")
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = run_file(args.dataset, args.output)
    print(json.dumps(report["metrics"], sort_keys=True))
    return 0


def _mean(rows: list[dict[str, Any]], key: str) -> float:
    if not rows:
        return 0.0
    return sum(float(row[key]) for row in rows) / len(rows)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


if __name__ == "__main__":
    raise SystemExit(main())
