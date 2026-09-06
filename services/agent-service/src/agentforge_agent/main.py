from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api import get_observability, router


@asynccontextmanager
async def lifespan(application: FastAPI):
    yield
    get_observability().shutdown()


def create_app() -> FastAPI:
    application = FastAPI(
        title="AgentForge Agent Service",
        version="0.1.0",
        lifespan=lifespan,
    )
    application.include_router(router)
    return application


app = create_app()
