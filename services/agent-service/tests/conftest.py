from langgraph.checkpoint.memory import InMemorySaver

from agentforge_agent.action_runtime import ActionWorkflowRuntime
from agentforge_agent.api import get_action_runtime
from agentforge_agent.main import app


_action_runtime = ActionWorkflowRuntime(InMemorySaver())
app.dependency_overrides[get_action_runtime] = lambda: _action_runtime
