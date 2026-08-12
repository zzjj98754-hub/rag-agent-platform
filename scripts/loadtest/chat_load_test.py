#!/usr/bin/env python3
"""100-concurrency chat load test with circuit-breaker/fallback validation."""

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import json
import math
import os
import pathlib
import statistics
import sys
import threading
import time
import uuid
from typing import Any, Optional
from urllib import error, parse, request


COUNTER_METRICS = (
    "rag.embedding.circuit.opens",
    "rag.embedding.primary.failures",
    "rag.embedding.fallback.calls",
)
GAUGE_METRIC = "rag.embedding.circuit.state"
LLM_FALLBACK_METRIC = "rag.llm.calls"


@dataclasses.dataclass
class RequestResult:
    index: int
    status: int
    latency_ms: float
    trace_id: str
    error: str = ""

    @property
    def succeeded(self) -> bool:
        return 200 <= self.status < 300


def json_request(
        method: str,
        url: str,
        payload: Optional[dict[str, Any]],
        headers: Optional[dict[str, str]],
        timeout: float) -> tuple[int, Any, dict[str, str], float]:
    encoded = None
    request_headers = {"Accept": "application/json"}
    if payload is not None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json; charset=utf-8"
    if headers:
        request_headers.update(headers)

    http_request = request.Request(
        url,
        data=encoded,
        headers=request_headers,
        method=method,
    )
    started = time.perf_counter()
    try:
        with request.urlopen(http_request, timeout=timeout) as response:
            raw = response.read()
            status = response.status
            response_headers = dict(response.headers.items())
    except error.HTTPError as http_error:
        raw = http_error.read()
        status = http_error.code
        response_headers = dict(http_error.headers.items())
    elapsed_ms = (time.perf_counter() - started) * 1000.0

    if not raw:
        body: Any = None
    else:
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            body = raw.decode("utf-8", errors="replace")
    return status, body, response_headers, elapsed_ms


def login(base_url: str, username: str, password: str, timeout: float) -> str:
    status, body, _, _ = json_request(
        "POST",
        f"{base_url}/auth/login",
        {"username": username, "password": password},
        None,
        timeout,
    )
    if status != 200 or not isinstance(body, dict):
        raise RuntimeError(f"login failed: HTTP {status}, body={body}")
    token = body.get("accessToken")
    if not token:
        raise RuntimeError("login response does not contain accessToken")
    return str(token)


def configure_upstream(control_url: str, scenario: str, delay_ms: int) -> None:
    payload = {
        "embedding_mode": "fail" if scenario == "embedding-failure" else "success",
        "llm_delay_ms": delay_ms if scenario == "llm-timeout" else 50,
    }
    status, body, _, _ = json_request(
        "POST",
        control_url,
        payload,
        None,
        5,
    )
    if status != 200:
        raise RuntimeError(
            f"upstream control failed: HTTP {status}, body={body}"
        )
    print(f"Upstream state: {body}")


def read_metric(
        base_url: str,
        token: str,
        metric_name: str,
        statistic: str,
        tag: Optional[str] = None) -> Optional[float]:
    metric_path = parse.quote(metric_name, safe=".")
    query = f"?{parse.urlencode({'tag': tag})}" if tag else ""
    status, body, _, _ = json_request(
        "GET",
        f"{base_url}/actuator/metrics/{metric_path}{query}",
        None,
        {"Authorization": f"Bearer {token}"},
        5,
    )
    if status == 404:
        return None
    if status != 200 or not isinstance(body, dict):
        raise RuntimeError(
            f"cannot read metric {metric_name}: HTTP {status}, body={body}"
        )
    for measurement in body.get("measurements", []):
        if measurement.get("statistic") == statistic:
            return float(measurement.get("value", 0))
    return None


def metric_snapshot(base_url: str, token: str) -> dict[str, Optional[float]]:
    snapshot = {
        name: read_metric(base_url, token, name, "COUNT")
        for name in COUNTER_METRICS
    }
    snapshot[GAUGE_METRIC] = read_metric(
        base_url,
        token,
        GAUGE_METRIC,
        "VALUE",
    )
    snapshot["rag.llm.calls{outcome=fallback}"] = read_metric(
        base_url,
        token,
        LLM_FALLBACK_METRIC,
        "COUNT",
        "outcome:fallback",
    )
    return snapshot


def run_chat_request(
        base_url: str,
        token: str,
        run_id: str,
        index: int,
        timeout: float,
        start_gate: Optional[threading.Event] = None) -> RequestResult:
    if start_gate is not None:
        start_gate.wait()
    payload = {
        "query": (
            f"[loadtest:{run_id}:{index}] "
            "请解释 Spring Boot RAG 中 BM25、向量检索和 RRF 的协作方式"
        ),
        "sessionId": f"lt-{run_id}-{index}",
    }
    try:
        status, body, headers, latency_ms = json_request(
            "POST",
            f"{base_url}/chat",
            payload,
            {"Authorization": f"Bearer {token}"},
            timeout,
        )
        message = ""
        if not 200 <= status < 300:
            message = str(body)[:300]
        trace_id = headers.get("X-Trace-Id", headers.get("x-trace-id", ""))
        return RequestResult(index, status, latency_ms, trace_id, message)
    except Exception as exception:  # noqa: BLE001 - load test must aggregate failures.
        return RequestResult(index, 0, timeout * 1000.0, "", str(exception))


def warm_up(
        base_url: str,
        token: str,
        run_id: str,
        count: int,
        timeout: float) -> None:
    for index in range(count):
        result = run_chat_request(
            base_url,
            token,
            f"warm-{run_id}",
            index,
            timeout,
        )
        if not result.succeeded:
            raise RuntimeError(
                f"warm-up request failed: status={result.status}, "
                f"error={result.error}"
            )


def execute_load(
        base_url: str,
        token: str,
        run_id: str,
        total_requests: int,
        concurrency: int,
        timeout: float) -> tuple[list[RequestResult], float]:
    workers = min(total_requests, concurrency)
    start_gate = threading.Event()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [
            executor.submit(
                run_chat_request,
                base_url,
                token,
                run_id,
                index,
                timeout,
                start_gate,
            )
            for index in range(total_requests)
        ]
        started = time.perf_counter()
        start_gate.set()
        results = [
            future.result()
            for future in concurrent.futures.as_completed(futures)
        ]
        elapsed_seconds = time.perf_counter() - started
    return results, elapsed_seconds


def percentile(values: list[float], percent: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(percent / 100.0 * len(ordered)) - 1)
    return ordered[index]


def metric_delta(
        before: dict[str, Optional[float]],
        after: dict[str, Optional[float]]) -> dict[str, float]:
    result = {}
    for name in set(before).union(after):
        before_value = before.get(name) or 0.0
        after_value = after.get(name) or 0.0
        result[name] = after_value - before_value
    return result


def validate_fault(
        scenario: str,
        after: dict[str, Optional[float]],
        deltas: dict[str, float]) -> dict[str, Any]:
    if scenario == "embedding-failure":
        state = after.get(GAUGE_METRIC)
        circuit_open = state == 2.0 or deltas["rag.embedding.circuit.opens"] > 0
        fallback_used = deltas["rag.embedding.fallback.calls"] > 0
        return {
            "passed": circuit_open and fallback_used,
            "circuit_open": circuit_open,
            "fallback_used": fallback_used,
            "circuit_state": state,
            "state_legend": "0=CLOSED, 1=HALF_OPEN, 2=OPEN",
        }
    if scenario == "llm-timeout":
        fallback_calls = deltas["rag.llm.calls{outcome=fallback}"]
        return {
            "passed": fallback_calls > 0,
            "llm_fallback_calls": fallback_calls,
        }
    return {"passed": True, "note": "baseline has no injected fault"}


def build_summary(
        args: argparse.Namespace,
        results: list[RequestResult],
        elapsed_seconds: float,
        before: dict[str, Optional[float]],
        after: dict[str, Optional[float]]) -> dict[str, Any]:
    latencies = [result.latency_ms for result in results]
    successes = sum(result.succeeded for result in results)
    errors = len(results) - successes
    deltas = metric_delta(before, after)
    fault_validation = validate_fault(args.scenario, after, deltas)
    return {
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scenario": args.scenario,
        "target": args.base_url,
        "requests": len(results),
        "concurrency": args.concurrency,
        "elapsed_seconds": round(elapsed_seconds, 6),
        "qps": round(len(results) / elapsed_seconds, 3),
        "latency_ms": {
            "average": round(statistics.fmean(latencies), 3),
            "p50": round(percentile(latencies, 50), 3),
            "p95": round(percentile(latencies, 95), 3),
            "p99": round(percentile(latencies, 99), 3),
            "minimum": round(min(latencies), 3),
            "maximum": round(max(latencies), 3),
        },
        "http": {
            "successes": successes,
            "errors": errors,
            "error_rate": round(errors / len(results), 6),
            "status_counts": {
                str(status): sum(result.status == status for result in results)
                for status in sorted({result.status for result in results})
            },
        },
        "metrics_before": before,
        "metrics_after": after,
        "metrics_delta": deltas,
        "fault_validation": fault_validation,
        "error_samples": [
            dataclasses.asdict(result)
            for result in results
            if not result.succeeded
        ][:5],
    }


def print_summary(summary: dict[str, Any], output_path: pathlib.Path) -> None:
    latency = summary["latency_ms"]
    http = summary["http"]
    print("\n=== demo00 RAG Load Test ===")
    print(
        f"Scenario={summary['scenario']} "
        f"requests={summary['requests']} "
        f"concurrency={summary['concurrency']}"
    )
    print(
        f"QPS={summary['qps']:.3f} "
        f"avg={latency['average']:.3f}ms "
        f"P99={latency['p99']:.3f}ms "
        f"P95={latency['p95']:.3f}ms"
    )
    print(
        f"HTTP success={http['successes']} errors={http['errors']} "
        f"error_rate={http['error_rate']:.2%}"
    )
    print(f"Fault validation={summary['fault_validation']}")
    print(f"Metric delta={summary['metrics_delta']}")
    print(f"Result file={output_path.resolve()}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run 100 concurrent /chat requests and validate resilience metrics."
        )
    )
    parser.add_argument(
        "--scenario",
        choices=("baseline", "embedding-failure", "llm-timeout"),
        default="baseline",
    )
    parser.add_argument("--base-url", default="http://localhost:9090")
    parser.add_argument(
        "--upstream-control-url",
        default="http://localhost:18080/control",
    )
    parser.add_argument(
        "--username",
        default=os.getenv("LOAD_TEST_USERNAME", "loadtest"),
    )
    parser.add_argument(
        "--password",
        default=os.getenv("LOAD_TEST_PASSWORD"),
    )
    parser.add_argument("--token", default=os.getenv("LOAD_TEST_TOKEN"))
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--llm-delay-ms", type=int, default=1500)
    parser.add_argument("--max-error-rate", type=float, default=0.0)
    parser.add_argument("--output")
    parser.add_argument(
        "--skip-upstream-control",
        action="store_true",
        help="Do not change the mock upstream mode before the test.",
    )
    args = parser.parse_args()
    if args.requests <= 0:
        parser.error("--requests must be > 0")
    if args.concurrency <= 0:
        parser.error("--concurrency must be > 0")
    if not 0 <= args.max_error_rate <= 1:
        parser.error("--max-error-rate must be between 0 and 1")
    if not args.token and not args.password:
        parser.error(
            "set LOAD_TEST_PASSWORD, pass --password, or provide --token"
        )
    return args


def main() -> int:
    args = parse_args()
    base_url = args.base_url.rstrip("/")
    args.base_url = base_url

    try:
        if not args.skip_upstream_control:
            configure_upstream(
                args.upstream_control_url,
                args.scenario,
                args.llm_delay_ms,
            )
        token = args.token or login(
            base_url,
            args.username,
            args.password,
            args.timeout,
        )
        run_id = uuid.uuid4().hex[:8]
        warm_up(base_url, token, run_id, args.warmup, args.timeout)
        before = metric_snapshot(base_url, token)
        results, elapsed_seconds = execute_load(
            base_url,
            token,
            run_id,
            args.requests,
            args.concurrency,
            args.timeout,
        )
        after = metric_snapshot(base_url, token)
    except Exception as exception:  # noqa: BLE001 - CLI reports a concise failure.
        print(f"Load test setup failed: {exception}", file=sys.stderr)
        return 2

    summary = build_summary(args, results, elapsed_seconds, before, after)
    if args.output:
        output_path = pathlib.Path(args.output)
    else:
        timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        output_path = pathlib.Path(
            "load-test-results",
            f"{args.scenario}-{timestamp}.json",
        )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print_summary(summary, output_path)

    error_rate = summary["http"]["error_rate"]
    fault_passed = summary["fault_validation"]["passed"]
    if error_rate > args.max_error_rate or not fault_passed:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
