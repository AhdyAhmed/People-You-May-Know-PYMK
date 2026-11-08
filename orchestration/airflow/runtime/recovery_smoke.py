"""Day 44 acceptance against the disposable workflow database and real scheduler."""
import copy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import recovery
import workflow as w
from workflow_smoke import sql, run_dag
from smoke import request


def preflight():
    plan = {"schemaVersion": 1, "workflow": "reconcile", "outputVersion": "day44-preflight",
            "intervalStart": "2026-01-24T00:00:00Z", "intervalEnd": "2026-01-25T00:00:00Z",
            "windowStart": "2026-01-01T00:00:00Z", "knowledgeCutoff": "2026-01-25T00:00:00Z"}
    path = w.ROOT / "day44-preflight.json"
    w.save(path, plan)
    previous = w.execute(w.reference(path), "validate")
    original = w.execute(w.reference(path), "reconcile", previous)
    directory = w.ROOT / "workflows/day44-preflight/reconcile"
    before = sql("SELECT count(*) FROM batch_job_execution")
    for name in ("result.json", "reconcile.completed.json", "reconcile-receipt.json"):
        (directory / name).unlink()
    assert w.execute(w.reference(path), "reconcile", previous) == original
    assert sql("SELECT count(*) FROM batch_job_execution") == before
    print("PASS: preflight lost Batch acknowledgement reloads persisted step context without new execution", flush=True)


def run(token):
    # Lost acknowledgement after the actual Batch commit: reconstruct, do not relaunch.
    plan = json.loads((w.ROOT / "day43-repair.json").read_text())
    plan["outputVersion"] = "day44-lost-ack"
    path = w.ROOT / "day44-lost-ack.json"
    w.save(path, plan)
    ref = w.reference(path)
    prior = w.execute(ref, "validate")
    result = w.execute(ref, "reconcile", prior)
    directory = w.ROOT / "workflows/day44-lost-ack/reconcile"
    receipt = json.loads((directory / "reconcile-receipt.json").read_text())
    generation = sql("SELECT generation FROM live_projection_active")
    # Only this disposable fixture's acknowledgement artifacts are removed.
    for name in ("result.json", "reconcile.completed.json", "reconcile-receipt.json"):
        (directory / name).unlink()
    recovered = w.execute(ref, "reconcile", prior)
    assert recovered == result
    assert json.loads((directory / "reconcile-receipt.json").read_text()) == receipt
    assert sql("SELECT generation FROM live_projection_active") == generation
    assert sql("SELECT attempts,state FROM pymk_workflow_work WHERE output_version='day44-lost-ack' AND step='reconcile'") == [(2, "COMPLETED")]
    print("PASS: lost acknowledgement recovered original Batch instance/execution and live generation", flush=True)

    # A killed supervisor must not allow another worker to race its surviving child.
    code = """import os,subprocess,sys,recovery,workflow as w
with recovery.claim(w.ROOT,'day44-orphan','probe',{'probe':1}):
    subprocess.Popen([sys.executable,'-c','import time; time.sleep(4)'],pass_fds=recovery.INHERITED_FDS,
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    os._exit(9)
"""
    killed = subprocess.run([sys.executable, "-c", code], cwd="/opt/pymk", check=False, timeout=15)
    assert killed.returncode == 9
    try:
        with recovery.claim(w.ROOT, "day44-orphan", "probe", {"probe": 1}):
            raise AssertionError("Surviving child fence was lost")
    except BlockingIOError:
        pass
    time.sleep(5)
    with recovery.claim(w.ROOT, "day44-orphan", "probe", {"probe": 1}):
        folder = w.ROOT / "workflows/day44-orphan/probe"
        folder.mkdir(parents=True)
        w.save(folder / "result.json", {"reconciled": True})
    print("PASS: killed supervisor fenced surviving child; orphan reconciled only after ownership ended", flush=True)

    # Registration is candidate-only. Keep source candidates immutable for retry evidence.
    base = w.ROOT / "day44-release"
    base.mkdir()
    stages = []
    for kind in ("light", "heavy"):
        stage = base / kind
        shutil.copytree(w.ROOT / f"workflows/day42-candidate/prepare/{kind}", stage)
        logs = base / (kind + "-logs")
        logs.mkdir()
        w.cli("registry", ["publish", "--stage", stage, "--tracking-uri", os.environ["PYMK_WORKFLOW_TRACKING_URI"]], logs)
        stages += ["--stage", stage]
    original = json.loads((w.ROOT / "workflows/fresh-generation/embeddings/release.json").read_text())
    original["releaseId"] = "day44-synchronized"
    w.save(base / "base.json", original)
    logs = base / "extend-logs"
    logs.mkdir()
    w.cli("registry", ["extend-release", "--release", base / "base.json", *stages, "--output", base / "release.json"], logs)
    plan = {"schemaVersion": 1, "workflow": "release_sync", "intervalStart": "2026-02-01T00:00:00Z",
            "intervalEnd": "2026-02-02T00:00:00Z", "outputVersion": "day44-sync",
            "sourceRevision": str(sql("SELECT revision FROM pymk_source_revision")[0][0]),
            "release": w.reference(base / "release.json")}
    active = sql("SELECT release_id,epoch FROM pymk_active_release")
    run_dag(token, "pymk_release_sync", "day44-sync", plan)
    assert sql("SELECT state FROM pymk_serving_releases WHERE release_id='day44-synchronized'") == [("STAGED",)]
    assert sql("SELECT release_id,epoch FROM pymk_active_release") == active
    print("PASS: numeric-pinned M7 synchronization and managed staging without approval or activation", flush=True)
    stage = w.ROOT / "workflows/day44-sync/sync/bundle"
    now = datetime.now(timezone.utc)
    # Deliberately incomplete fixture approval: the disabled activation boundary must reject it.
    w.save(stage / "approval.json", {"expectedEpoch": active[0][1], "manifestSha256": w.digest(stage / "release.json"),
        "approvedAt": now.isoformat().replace("+00:00", "Z"),
        "expiresAt": (now+timedelta(hours=1)).isoformat().replace("+00:00", "Z")})
    disabled = {**plan, "workflow": "approved_activation", "outputVersion": "day44-disabled-activation",
        "release": w.reference(stage / "release.json"), "approval": w.reference(stage / "approval.json"),
        "expectedEpoch": active[0][1], "operatorDrainConfirmed": True,
        "legacyModel": w.reference(stage / "light/model.onnx"), "legacyMetadata": w.reference(stage / "light/metadata.json")}
    run_dag(token, "pymk_approved_activation", "day44-disabled-activation", disabled, "failed")
    assert sql("SELECT release_id,epoch FROM pymk_active_release") == active
    resume(token, "before-restart")


def resume(token, suffix):
    plan = json.loads((w.ROOT / "day42-candidate.json").read_text())
    before = sql("SELECT count(*) FROM batch_job_execution")
    runs = sql("SELECT evidence::text FROM pymk_workflow_work WHERE output_version='day42-candidate' ORDER BY step")
    run_dag(token, "pymk_model_candidate", "day44-reuse-" + suffix, plan)
    assert sql("SELECT count(*) FROM batch_job_execution") == before
    assert sql("SELECT evidence::text FROM pymk_workflow_work WHERE output_version='day42-candidate' ORDER BY step") == runs
    conflicting = copy.deepcopy(plan)
    conflicting["sourceRevision"] = "9999999"
    path = w.ROOT / ("day44-conflict-" + suffix + ".json")
    w.save(path, conflicting)
    try:
        w.execute(w.reference(path), "validate")
        raise AssertionError("Conflicting work reused an immutable destination")
    except ValueError as error:
        assert "Destination already bound" in str(error)
    print("PASS: scheduler/manual replay reused original Batch and MLflow identities: " + suffix, flush=True)


if __name__ == "__main__":
    token = request("/auth/token", {"username": "operator", "password": os.environ["PYMK_AIRFLOW_UI_PASSWORD"]})["access_token"]
    resume(token, "after-restart")
