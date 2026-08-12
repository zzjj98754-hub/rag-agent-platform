#!/usr/bin/env python3
"""Controllable OpenAI-compatible upstream used by the local load tests."""

import argparse
import hashlib
import json
import math
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse


VECTOR_DIMENSION = 1024


class UpstreamState:
    def __init__(self, embedding_mode: str, llm_delay_ms: int) -> None:
        self._lock = threading.Lock()
        self.embedding_mode = embedding_mode
        self.llm_delay_ms = llm_delay_ms

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "embedding_mode": self.embedding_mode,
                "llm_delay_ms": self.llm_delay_ms,
            }

    def update(self, body: dict[str, Any]) -> dict[str, Any]:
        with self._lock:
            if "embedding_mode" in body:
                mode = str(body["embedding_mode"]).lower()
                if mode not in {"success", "fail"}:
                    raise ValueError("embedding_mode must be success or fail")
                self.embedding_mode = mode
            if "llm_delay_ms" in body:
                delay = int(body["llm_delay_ms"])
                if delay < 0:
                    raise ValueError("llm_delay_ms must be >= 0")
                self.llm_delay_ms = delay
            return {
                "embedding_mode": self.embedding_mode,
                "llm_delay_ms": self.llm_delay_ms,
            }


def deterministic_embedding(text: str) -> list[float]:
    """Create a stable normalized vector with no external ML dependency."""
    values = [0.0] * VECTOR_DIMENSION
    normalized = text.lower()
    terms = list(normalized[:2000])
    terms.extend(
        normalized[index:index + 2]
        for index in range(min(len(normalized) - 1, 1999))
    )
    for term in terms:
        digest = hashlib.sha256(term.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % VECTOR_DIMENSION
        values[index] += 1.0 if digest[4] % 2 == 0 else -1.0
    norm = math.sqrt(sum(value * value for value in values))
    if norm == 0:
        return values
    return [round(value / norm, 8) for value in values]


def relevance_score(query: str, document: str) -> float:
    query_terms = set(query.lower())
    if not query_terms:
        return 0.0
    overlap = len(query_terms.intersection(set(document.lower())))
    return round(overlap / len(query_terms), 6)


class MockAiHandler(BaseHTTPRequestHandler):
    server_version = "Demo00LoadTestUpstream/1.0"

    @property
    def state(self) -> UpstreamState:
        return self.server.state  # type: ignore[attr-defined]

    def do_GET(self) -> None:
        if urlparse(self.path).path == "/health":
            self._send_json(200, {"status": "UP", **self.state.snapshot()})
            return
        self._send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        try:
            body = self._read_json()
            if path == "/control":
                self._send_json(200, self.state.update(body))
            elif path == "/v1/embeddings":
                self._handle_embeddings(body)
            elif path == "/v1/rerank":
                self._handle_rerank(body)
            elif path == "/mock-llm":
                self._handle_llm(body)
            else:
                self._send_json(404, {"error": "not_found"})
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            self._send_json(400, {"error": str(error)})

    def _handle_embeddings(self, body: dict[str, Any]) -> None:
        if self.state.snapshot()["embedding_mode"] == "fail":
            self._send_json(
                503,
                {"error": {"message": "injected embedding failure"}},
            )
            return

        raw_input = body.get("input", [])
        texts = [raw_input] if isinstance(raw_input, str) else list(raw_input)
        data = [
            {
                "object": "embedding",
                "index": index,
                "embedding": deterministic_embedding(str(text)),
            }
            for index, text in enumerate(texts)
        ]
        self._send_json(
            200,
            {
                "object": "list",
                "model": body.get("model", "load-test-embedding"),
                "data": data,
            },
        )

    def _handle_rerank(self, body: dict[str, Any]) -> None:
        query = str(body.get("query", ""))
        documents = [str(item) for item in body.get("documents", [])]
        top_n = max(0, min(int(body.get("top_n", len(documents))), len(documents)))
        ranked = sorted(
            (
                {
                    "index": index,
                    "relevance_score": relevance_score(query, document),
                }
                for index, document in enumerate(documents)
            ),
            key=lambda item: item["relevance_score"],
            reverse=True,
        )
        self._send_json(200, {"results": ranked[:top_n]})

    def _handle_llm(self, body: dict[str, Any]) -> None:
        delay_ms = int(self.state.snapshot()["llm_delay_ms"])
        if delay_ms:
            time.sleep(delay_ms / 1000.0)
        prompt = str(body.get("prompt", ""))
        digest = hashlib.sha256(prompt.encode("utf-8")).hexdigest()[:8]
        content = f"【压测 Mock LLM 回答】请求已处理，prompt={digest}。"
        self._send_json(
            200,
            {
                "content": content,
                "model": body.get("model", "load-test-llm"),
                "tokens": max(1, len(content) // 2),
            },
        )

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(length) if length else b"{}"
        parsed = json.loads(payload.decode("utf-8"))
        if not isinstance(parsed, dict):
            raise ValueError("request body must be a JSON object")
        return parsed

    def _send_json(self, status: int, value: dict[str, Any]) -> None:
        payload = json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            # LLM timeout tests deliberately close the client connection early.
            return

    def log_message(self, format_string: str, *args: Any) -> None:
        if getattr(self.server, "verbose", False):
            super().log_message(format_string, *args)


class MockAiServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
            self,
            address: tuple[str, int],
            state: UpstreamState,
            verbose: bool) -> None:
        super().__init__(address, MockAiHandler)
        self.state = state
        self.verbose = verbose


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Controllable Embedding/Rerank/LLM server for demo00 load tests."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument(
        "--embedding-mode",
        choices=("success", "fail"),
        default="success",
    )
    parser.add_argument("--llm-delay-ms", type=int, default=50)
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    state = UpstreamState(args.embedding_mode, args.llm_delay_ms)
    server = MockAiServer((args.host, args.port), state, args.verbose)
    print(
        "Mock AI upstream listening on "
        f"http://{args.host}:{args.port} "
        f"state={state.snapshot()}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping mock AI upstream.", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
