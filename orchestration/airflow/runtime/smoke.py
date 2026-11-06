"""Authenticated real-scheduler acceptance; no direct task-test shortcut."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"


def request(path, body=None, method=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def wait_for(call, condition, seconds=240):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = call()
        if condition(value): return value
        time.sleep(3)
    raise TimeoutError("Airflow acceptance condition was not reached")


def main():
    try: request("/api/v2/dags")
    except urllib.error.HTTPError as error:
        assert error.code in (401, 403), error.code
    else: raise AssertionError("Airflow API allowed unauthenticated access")
    token = request("/auth/token", {"username": "operator", "password": os.environ["PYMK_AIRFLOW_UI_PASSWORD"]})["access_token"]
    endpoint = "/api/v2/dags/pymk_runtime_contract"
    wait_for(lambda: request("/api/v2/dags", token=token), lambda result: any(d["dag_id"] == "pymk_runtime_contract" for d in result["dags"]))
    params = {"interval_start": "2026-01-01T00:00:00Z", "interval_end": "2026-01-02T00:00:00Z",
              "source_sha256": hashlib.sha256(Path("/opt/pymk/model.base64").read_bytes()).hexdigest(), "output_version": "day41-smoke"}
    run_id = "day41-runtime"
    path = endpoint + "/dagRuns/" + run_id
    if sys.argv[1] == "run":
        request(endpoint, {"is_paused": False}, "PATCH", token)
        request(endpoint + "/dagRuns", {"dag_run_id": run_id, "logical_date": "2026-01-02T00:00:00Z", "conf": params}, "POST", token)
        result = wait_for(lambda: request(path, token=token), lambda r: r["state"] in ("success", "failed"))
        assert result["state"] == "success", result["state"]
        # Different Airflow run identity, identical explicit work: reuse the exact report.
        request(endpoint + "/dagRuns", {"dag_run_id": "day41-repeat", "logical_date": "2026-01-04T00:00:00Z", "conf": params}, "POST", token)
        result = wait_for(lambda: request(endpoint + "/dagRuns/day41-repeat", token=token), lambda r: r["state"] in ("success", "failed"))
        assert result["state"] == "success", result["state"]
        # A valid JSON-schema payload with an invalid time contract must fail, not execute the probe.
        invalid = {**params, "interval_end": params["interval_start"], "output_version": "rejected"}
        request(endpoint + "/dagRuns", {"dag_run_id": "day41-invalid", "logical_date": "2026-01-03T00:00:00Z", "conf": invalid}, "POST", token)
        result = wait_for(lambda: request(endpoint + "/dagRuns/day41-invalid", token=token), lambda r: r["state"] in ("success", "failed"))
        assert result["state"] == "failed"
        tasks = request(endpoint + "/dagRuns/day41-invalid/taskInstances", token=token)["task_instances"]
        assert next(t for t in tasks if t["task_id"] == "runtime_probe")["state"] == "upstream_failed"
    else:
        assert request(path, token=token)["state"] == "success", "Completed run lost after recreation"
    from pymk_contract import logical_key
    artifact = Path("/opt/airflow/artifacts/runtime-contract") / (logical_key(params) + ".json")
    result = json.loads(artifact.read_text())
    assert result["javaParity"]["rows"] == 4 and result["javaParity"]["maxAbsoluteError"] <= 1e-6
    assert result["automaticPromotion"] is False
    logs = list(Path("/opt/airflow/logs").rglob("*.log"))
    assert any("runtime_probe" in str(p) and p.stat().st_size for p in logs), "No persisted task logs"
    print("PASS: authenticated API, real LocalExecutor Java/Python inference, invalid-interval rejection, persistent run/artifact/log evidence")


if __name__ == "__main__": main()
