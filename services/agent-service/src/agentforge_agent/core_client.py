import httpx

from .errors import RagDependencyError
from .schemas import RagSource, RagSourcesResponse


class CoreApiClient:
    def __init__(self, base_url: str, internal_token: str, timeout_seconds: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.internal_token = internal_token
        self.timeout_seconds = timeout_seconds

    def fetch_sources(
        self,
        project_id: str,
        user_id: str,
        actor_admin: bool,
        request_id: str,
    ) -> list[RagSource]:
        try:
            response = httpx.post(
                f"{self.base_url}/internal/v1/rag/sources",
                headers={
                    "X-AgentForge-Core-Internal-Token": self.internal_token,
                    "X-Request-Id": request_id,
                },
                json={
                    "projectId": project_id,
                    "userId": user_id,
                    "actorAdmin": actor_admin,
                    "requestId": request_id,
                },
                timeout=self.timeout_seconds,
            )
            response.raise_for_status()
            parsed = RagSourcesResponse.model_validate(response.json())
            if str(parsed.project_id) != project_id or parsed.request_id != request_id:
                raise ValueError("Core API source response correlation mismatch")
            return parsed.sources
        except (httpx.HTTPError, ValueError) as exception:
            raise RagDependencyError("Core API RAG source service is unavailable.") from exception
