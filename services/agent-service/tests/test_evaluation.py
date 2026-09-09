import json
from pathlib import Path

import pytest

from agentforge_agent.evaluation.dataset import load_dataset
from agentforge_agent.evaluation.dataset import CorpusSource
from agentforge_agent.evaluation.subject import CurrentCodeSubject
from agentforge_agent.evaluation.runner import EvaluationRunner, run_file
from agentforge_agent.evaluation.metrics import (
    score_faithfulness,
    score_ranking,
    score_tool_result,
)
from agentforge_agent.schemas import ToolProposal


def test_score_ranking_uses_independent_gold_source_ids() -> None:
    score = score_ranking(
        retrieved_source_ids=["wiki-architecture", "task-release", "wiki-security"],
        relevant_source_ids={"task-release", "wiki-security"},
        k=2,
    )

    assert score.recall_at_k == 0.5
    assert score.reciprocal_rank == 0.5
    assert score.hit_rate == 1.0


def test_score_ranking_limits_mrr_to_the_configured_cutoff() -> None:
    score = score_ranking(
        retrieved_source_ids=["unrelated", "relevant"],
        relevant_source_ids={"relevant"},
        k=1,
    )

    assert score.reciprocal_rank == 0.0


def test_score_faithfulness_counts_only_context_supported_claims() -> None:
    score = score_faithfulness(
        answer="Java owns business writes. Python writes business data.",
        context="Java owns business writes. Python plans tool intent.",
        support_threshold=0.8,
    )

    assert score.score == 0.5
    assert [claim.supported for claim in score.claims] == [True, False]


def test_tool_selection_and_task_success_are_scored_separately() -> None:
    score = score_tool_result(
        actual=ToolProposal(
            action_type="CREATE_TASK",
            title="Ship release",
            status="TODO",
            priority="LOW",
        ),
        expected={
            "actionType": "CREATE_TASK",
            "title": "Ship release",
            "status": "TODO",
            "priority": "HIGH",
        },
    )

    assert score.selection_accuracy == 1.0
    assert score.task_success == 0.0


def test_no_tool_case_requires_no_proposal() -> None:
    score = score_tool_result(actual=None, expected=None)

    assert score.selection_accuracy == 1.0
    assert score.task_success == 1.0


def test_dataset_rejects_rag_gold_outside_corpus(tmp_path) -> None:
    path = tmp_path / "invalid.json"
    path.write_text(json.dumps({
        "schemaVersion": 1,
        "name": "invalid",
        "corpus": [],
        "ragCases": [{
            "id": "rag-1",
            "query": "architecture",
            "relevantSourceIds": ["missing"],
            "k": 2,
        }],
        "answerCases": [],
        "toolCases": [],
    }), encoding="utf-8")

    with pytest.raises(ValueError, match="unknown corpus source"):
        load_dataset(path)


def test_dataset_rejects_create_tool_gold_without_required_title(tmp_path) -> None:
    path = tmp_path / "invalid-tool.json"
    path.write_text(json.dumps({
        "schemaVersion": 1,
        "name": "invalid-tool",
        "corpus": [],
        "ragCases": [],
        "answerCases": [],
        "toolCases": [{
            "id": "tool-invalid",
            "input": "create task",
            "expectedProposal": {"actionType": "CREATE_TASK", "status": "TODO"},
        }],
    }), encoding="utf-8")

    with pytest.raises(ValueError, match="CREATE_TASK gold requires title"):
        load_dataset(path)


def test_dataset_rejects_unknown_tool_gold_parameters(tmp_path) -> None:
    path = tmp_path / "unknown-tool-field.json"
    path.write_text(json.dumps({
        "schemaVersion": 1,
        "name": "unknown-tool-field",
        "corpus": [],
        "ragCases": [],
        "answerCases": [],
        "toolCases": [{
            "id": "tool-invalid",
            "input": "create task: release",
            "expectedProposal": {
                "actionType": "CREATE_TASK",
                "title": "release",
                "inventedParameter": "must-not-be-ignored",
            },
        }],
    }), encoding="utf-8")

    with pytest.raises(ValueError, match="unknown parameter"):
        load_dataset(path)


def test_current_subject_runs_production_ranking_and_tool_planner() -> None:
    subject = CurrentCodeSubject(embedding_dimensions=64)
    corpus = (
        CorpusSource("wiki-architecture", "WIKI", "Architecture", "Java permissions and writes"),
        CorpusSource("task-ui", "TASK", "UI polish", "React markdown preview"),
    )

    assert subject.retrieve("Java permissions", corpus) == [
        "wiki-architecture",
        "task-ui",
    ]
    proposal = subject.plan_tool("create task: Ship release; priority=high")
    assert proposal is not None
    assert proposal.model_dump(by_alias=True, exclude_none=True) == {
        "actionType": "CREATE_TASK",
        "title": "Ship release",
        "status": "TODO",
        "priority": "HIGH",
    }


def test_runner_emits_all_metrics_and_per_case_evidence(tmp_path) -> None:
    path = tmp_path / "dataset.json"
    path.write_text(json.dumps({
        "schemaVersion": 1,
        "name": "runner-test",
        "corpus": [
            {"id": "wiki-java", "sourceType": "WIKI", "title": "Architecture", "content": "Java owns business writes"},
            {"id": "task-ui", "sourceType": "TASK", "title": "UI", "content": "React preview polish"},
        ],
        "ragCases": [{"id": "rag-java", "query": "Java business writes", "relevantSourceIds": ["wiki-java"], "k": 1}],
        "answerCases": [{"id": "answer-java", "answer": "Java owns business writes.", "context": "Java owns business writes."}],
        "toolCases": [{
            "id": "tool-create",
            "input": "create task: Ship release; priority=high",
            "expectedProposal": {"actionType": "CREATE_TASK", "title": "Ship release", "status": "TODO", "priority": "HIGH"},
        }],
    }), encoding="utf-8")
    dataset = load_dataset(path)

    report = EvaluationRunner(CurrentCodeSubject(64)).run(
        dataset,
        dataset_sha256="known-dataset-hash",
        generated_at="2026-09-09T00:00:00Z",
    )

    assert report["metrics"] == {
        "recallAtK": 1.0,
        "mrr": 1.0,
        "hitRate": 1.0,
        "faithfulness": 1.0,
        "toolSelectionAccuracy": 1.0,
        "taskSuccessRate": 1.0,
    }
    assert report["dataset"]["sha256"] == "known-dataset-hash"
    assert report["cases"]["rag"][0]["retrievedSourceIds"][0] == "wiki-java"
    assert report["methods"]["faithfulness"]["kind"] == "lexical-claim-support-proxy"


def test_real_dataset_runner_is_repeatable_and_serializes_tool_parameters(tmp_path) -> None:
    dataset_path = Path(__file__).parents[1] / "evaluation" / "datasets" / "v2-small.json"
    first = run_file(dataset_path, tmp_path / "first.json")
    second = run_file(dataset_path, tmp_path / "second.json")

    assert first["dataset"]["sha256"] == second["dataset"]["sha256"]
    first_without_time = {key: value for key, value in first.items() if key != "generatedAt"}
    second_without_time = {key: value for key, value in second.items() if key != "generatedAt"}
    assert first_without_time == second_without_time
    update_case = next(case for case in first["cases"]["tool"] if case["id"] == "tool-update-with-parameters")
    assert update_case["actualProposal"]["taskId"] == "11111111-1111-4111-8111-111111111111"
