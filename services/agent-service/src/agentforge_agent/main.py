from contextlib import asynccontextmanager

from fastapi import FastAPI

from .action_runtime import open_postgres_action_runtime
from .api import get_observability, router
from .config import get_settings


@asynccontextmanager
async def lifespan(application: FastAPI):
    settings = get_settings()
    with application.state.action_runtime_context_factory(
        settings.effective_checkpoint_db_dsn()
    ) as action_runtime:
        application.state.action_runtime = action_runtime
        try:
            yield
        finally:
            get_observability().shutdown()


def create_app(action_runtime_context_factory=open_postgres_action_runtime) -> FastAPI:
    application = FastAPI(
        title="AgentForge Agent Service",
        version="0.1.0",
        lifespan=lifespan,
    )
    application.state.action_runtime_context_factory = action_runtime_context_factory
    application.include_router(router)
    return application


app = create_app()
