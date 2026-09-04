from fastapi import FastAPI

from .api import router


def create_app() -> FastAPI:
    application = FastAPI(title="AgentForge Agent Service", version="0.1.0")
    application.include_router(router)
    return application


app = create_app()
