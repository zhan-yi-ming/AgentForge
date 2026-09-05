FROM python:3.14-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml ./
COPY src src
RUN python -m pip install --no-cache-dir .
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "agentforge_agent.main:app", "--host", "0.0.0.0", "--port", "8000"]
