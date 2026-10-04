import httpx

from .errors import RagDependencyError
from .schemas import GraphMatch, GraphRetrievalResponse, RagSource, RagSourcesResponse


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
        known_snapshot_version: int | None = None,
    ) -> RagSourcesResponse:
        try:
            payload = {
                "projectId": project_id,
                "userId": user_id,
                "actorAdmin": actor_admin,
                "requestId": request_id,
            }
            if known_snapshot_version is not None:
                payload["knownSnapshotVersion"] = known_snapshot_version
            response = httpx.post(
                f"{self.base_url}/internal/v1/rag/sources",
                headers={
                    "X-AgentForge-Core-Internal-Token": self.internal_token,
                    "X-Request-Id": request_id,
                },
                json=payload,
                timeout=self.timeout_seconds,
            )
            response.raise_for_status()
            parsed = RagSourcesResponse.model_validate(response.json())
            if str(parsed.project_id) != project_id or parsed.request_id != request_id:
                raise ValueError("Core API source response correlation mismatch")
            if not parsed.sources_changed and (
                known_snapshot_version is None
                or parsed.snapshot_version != known_snapshot_version
                or parsed.sources
            ):
                raise ValueError("Core API unchanged source response is inconsistent")
            return parsed
        except (httpx.HTTPError, ValueError) as exception:
            raise RagDependencyError("Core API RAG source service is unavailable.") from exception

    def fetch_graph(
        self, project_id: str, user_id: str, actor_admin: bool, request_id: str, query: str,
    ) -> list[GraphMatch]:
        try:
            response = httpx.post(
                f"{self.base_url}/internal/v1/graph/retrieval",
                headers={
                    "X-AgentForge-Core-Internal-Token": self.internal_token,
                    "X-Request-Id": request_id,
                },
                json={
                    "projectId": project_id, "userId": user_id,
                    "actorAdmin": actor_admin, "requestId": request_id, "query": query[:1000],
                },
                timeout=self.timeout_seconds,
            )
            if response.status_code == 503:
                return []
            response.raise_for_status()
            parsed = GraphRetrievalResponse.model_validate(response.json())
            if str(parsed.project_id) != project_id or parsed.request_id != request_id:
                raise ValueError("Core API graph response correlation mismatch")
            return parsed.matches
        except httpx.TransportError:
            return []
        except (httpx.HTTPStatusError, ValueError) as exception:
            raise RagDependencyError("Core API graph retrieval failed.") from exception
