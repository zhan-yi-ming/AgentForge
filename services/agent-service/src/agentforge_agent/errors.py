class RagDependencyError(RuntimeError):
    """A sanitized failure raised when a RAG dependency is unavailable."""


class LlmDependencyError(RuntimeError):
    """A sanitized failure raised when the configured LLM is unavailable."""
