"""Deployment waiting budgets must include retrieval, planning and answer fallback."""
from pathlib import Path
import re
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[3]

@pytest.mark.parametrize("name", ["compose.yaml", "compose.prod.yaml"])
def test_outer_chat_wait_covers_supported_model_attempt_budget(name):
    services = yaml.safe_load((ROOT / "infra" / name).read_text(encoding="utf-8"))["services"]
    agent = services["agent-service"]["environment"]
    core = services["core-api"]["environment"]
    per_call = float(re.search(r":-(\d+)", str(agent["AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS"])).group(1))
    read = int(re.search(r":-PT(\d+)S", core["AGENTFORGE_AGENT_READ_TIMEOUT"]).group(1))
    assert read >= 5 * per_call + 30, "Core must cover retrieval + planning/answer primary/fallback and margin"

@pytest.mark.parametrize("path", ["chat", "chat/stream"])
def test_nginx_chat_locations_cover_core_model_wait_budget(path):
    template = (ROOT / "infra/nginx/production.conf.template").read_text(encoding="utf-8")
    marker = "location ~ ^/api/v1/projects/[^/]+/agent/" + path + "$ {"
    location = template.split(marker, 1)[1].split("}", 1)[0]
    read = re.search(r"proxy_read_timeout\s+(\d+)s;", location)
    assert read is not None, "Agent Chat must declare its own outer waiting budget"
    assert int(read.group(1)) >= 330, "Nginx must cover the Core model waiting budget"
