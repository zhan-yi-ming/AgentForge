"""Deployment must enforce the Java/Python PostgreSQL privilege boundary."""
from pathlib import Path

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[3]


@pytest.mark.parametrize("name", ["compose.yaml", "compose.prod.yaml"])
def test_compose_separates_admin_core_and_agent_database_identities(name):
    services = yaml.safe_load((ROOT / "infra" / name).read_text(encoding="utf-8"))["services"]
    bootstrap = services["database-roles"]
    core = services["core-api"]
    agent = services["agent-service"]

    assert bootstrap["depends_on"]["postgres"]["condition"] == "service_healthy"
    assert core["depends_on"]["database-roles"]["condition"] == "service_completed_successfully"
    assert core["environment"]["AGENTFORGE_DB_USERNAME"] == "agentforge_core"
    assert "AGENTFORGE_CORE_DB_PASSWORD" in core["environment"]["AGENTFORGE_DB_PASSWORD"]
    assert "POSTGRES_USER" in core["environment"]["AGENTFORGE_DB_MIGRATION_USERNAME"]
    assert "POSTGRES_PASSWORD" in core["environment"]["AGENTFORGE_DB_MIGRATION_PASSWORD"]

    rag_dsn = agent["environment"]["AGENTFORGE_AGENT_RAG_DB_DSN"]
    checkpoint_dsn = agent["environment"]["AGENTFORGE_AGENT_CHECKPOINT_DB_DSN"]
    assert "agentforge_agent:" in rag_dsn
    assert "AGENTFORGE_AGENT_DB_PASSWORD" in rag_dsn
    assert checkpoint_dsn == rag_dsn
    assert "POSTGRES_PASSWORD" not in rag_dsn
