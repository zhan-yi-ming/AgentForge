from collections.abc import Sequence
import hashlib
import math
import re
from typing import Protocol

import httpx

from .errors import RagDependencyError


TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")


def tokenize(text: str) -> list[str]:
    return [token.lower() for token in TOKEN_PATTERN.findall(text)]


class EmbeddingProvider(Protocol):
    dimensions: int

    def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class HashEmbeddingProvider:
    def __init__(self, dimensions: int = 384) -> None:
        self.dimensions = dimensions

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        vector = [0.0] * self.dimensions
        for token in tokenize(text):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimensions
            sign = 1.0 if digest[4] & 1 else -1.0
            vector[index] += sign
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            return vector
        return [value / norm for value in vector]


class OpenAIEmbeddingProvider:
    def __init__(
        self,
        api_key: str,
        base_url: str,
        model: str,
        dimensions: int,
        timeout_seconds: float,
    ) -> None:
        if not api_key:
            raise ValueError("OpenAI embedding provider requires an API key")
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.dimensions = dimensions
        self.timeout_seconds = timeout_seconds

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            response = httpx.post(
                f"{self.base_url}/embeddings",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json={
                    "model": self.model,
                    "input": list(texts),
                    "dimensions": self.dimensions,
                },
                timeout=self.timeout_seconds,
            )
            response.raise_for_status()
            data = response.json()["data"]
            ordered = sorted(data, key=lambda item: item["index"])
            vectors = [item["embedding"] for item in ordered]
            if len(vectors) != len(texts) or any(len(vector) != self.dimensions for vector in vectors):
                raise ValueError("Embedding response shape does not match the request")
            return vectors
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as exception:
            raise RagDependencyError("Embedding provider is unavailable.") from exception
