"""Disposable Day 45 acceptance. Approval evidence is a SYNTHETIC CONTRACT FIXTURE,
not measured production promotion evidence or authorization to deploy this model.
"""
from datetime import datetime, timedelta, timezone
import json
import os
import sys
import urllib.request

import operations
import workflow as w
from workflow_smoke import sql, run_dag
from smoke import request


def token():
    return request("/auth/token", {"username": "operator", "password": os.environ["PYMK_AIRFLOW_UI_PASSWORD"]})["access_token"]


def prepare():
    auth = token()
    plan = json.loads((w.ROOT / "day43-backfill.json").read_text())
    plan.update(workflow="offline_report", outputVersion="day45-report")
    del plan["blendPolicy"], plan["context"]
    for kind in ("light", "heavy"):
        folder = w.ROOT / f"workflows/day42-candidate/{kind}/model"
        plan[kind + "Model"] = w.reference(folder / f"day42-candidate-{kind}.onnx")
        plan[kind + "Metadata"] = w.reference(folder / f"day42-candidate-{kind}.metadata.json")
    run_dag(auth, "pymk_offline_report", "day45-report", plan)
    before = sql("SELECT count(*) FROM batch_job_execution")
    run_dag(auth, "pymk_offline_report", "day45-report-replay", plan)
    assert sql("SELECT count(*) FROM batch_job_execution") == before
    # L1-only approval must not approve the intentionally failing heavy quality fixture.
    release = json.loads((w.ROOT / "day44-release/release.json").read_text())
    release["releaseId"] = "day45-approved"
    del release["registry"]["models"]["heavy"]
    w.save(w.ROOT / "day45-release.json", release)
    sync = json.loads((w.ROOT / "day44-sync.json").read_text())
    sync.update(outputVersion="day45-sync", release=w.reference(w.ROOT / "day45-release.json"))
    run_dag(auth, "pymk_release_sync", "day45-sync", sync)
    stage = w.ROOT / "workflows/day45-sync/sync/bundle"
    for name in ("deployment.properties", "rollback.properties"):
        (stage / name).write_text("pymk.serving.ranker=L1\npymk.heavy-ranker.enabled=false\npymk.light-ranker.max-candidates=500\npymk.heavy-ranker.max-candidates=500\n")
    now = datetime.now(timezone.utc)
    # Explicit synthetic contract gates, as in the existing Java approval tests.
    # These values are NEVER described as measured load/quality evidence.
    evidence = dict(schemaVersion=1, manifestSha256=w.digest(stage / "release.json"),
        measuredAt=now.isoformat().replace("+00:00", "Z"), environment="SYNTHETIC DAY45 CONTRACT FIXTURE - NOT DEPLOYMENT EVIDENCE",
        cohortSha256=w.digest(w.ROOT / "day42-inputs/profiles.csv"), mode="L1", temporalValid=True, rollbackVerified=True,
        coverage=dict(observed=1, minimum=1), quality=dict(observed=json.loads((stage / "light/metadata.json").read_text())["metrics"]["validation"]["rocAuc"], minimum=.7),
        requesters=dict(observed=30, minimum=30), coldP95Ms=dict(observed=10, maximum=200), warmP95Ms=dict(observed=10, maximum=200))
    for name in ("deployment.properties", "rollback.properties"):
        evidence[name + "Sha256"] = w.digest(stage / name)
    w.save(stage / "evidence.json", evidence)
    epoch = sql("SELECT epoch FROM pymk_active_release")[0][0]
    w.command(["java", "-Dloader.main=com.ahdyahmed.pymk.batch.PromotionApproval", "-cp", w.JAR,
        "org.springframework.boot.loader.launch.PropertiesLauncher", stage, stage / "evidence.json", "synthetic-test-only", str(epoch),
        (now+timedelta(hours=2)).isoformat().replace("+00:00", "Z")], w.ROOT / "day45-approval.log", artifact_root=stage)
    # Legacy baseline retained for a real HTTP rollback check, using compatible model/feature bytes.
    sql("""INSERT INTO member_embeddings SELECT member_id,embedding,updated_at,embedding_version,fit_cutoff
        FROM pymk_release_embeddings WHERE release_id='day45-approved';
        INSERT INTO pymk_features SELECT member_id,candidate_id,mutual_connection_count,same_company,same_school,same_geo,
        embedding_cosine_sim,profile_view_count_last_30d,feature_as_of,computed_at
        FROM pymk_release_features WHERE release_id='day45-approved'""")
    activation = {key: sync[key] for key in ("schemaVersion", "intervalStart", "intervalEnd", "sourceRevision")}
    activation.update(workflow="approved_activation", outputVersion="day45-activation", release=w.reference(stage / "release.json"),
        approval=w.reference(stage / "approval.json"), expectedEpoch=epoch, operatorDrainConfirmed=True,
        legacyModel=w.reference(stage / "light/model.onnx"), legacyMetadata=w.reference(stage / "light/metadata.json"))
    w.save(w.ROOT / "day45-activation-plan.json", activation)
    print("PASS: frozen offline report/replay, L1-only sync and synthetic approval contract fixture", flush=True)


def activate():
    run_dag(token(), "pymk_approved_activation", "day45-activate", json.loads((w.ROOT / "day45-activation-plan.json").read_text()))
    assert sql("SELECT release_id FROM pymk_active_release") == [("day45-approved",)]
    print("PASS: real approved activation DAG; synthetic fixture only", flush=True)


def http():
    with urllib.request.urlopen("http://day45-api:8080/api/v1/pymk/1?limit=2", timeout=20) as response:
        assert response.headers["X-PYMK-Scorer"] == "L1"
        body = json.load(response)
    assert len(body["recommendations"]) == 2
    assert body["recommendations"][0]["candidateId"] == 2
    assert all(0 <= row["score"] <= 1 for row in body["recommendations"])
    print("PASS: packaged HTTP recommendations with database-held approved/rollback model", flush=True)


def outage():
    # A new destination cannot claim success when registry access is unavailable.
    plan = json.loads((w.ROOT / "day45-sync.json").read_text())
    plan["outputVersion"] = "day45-outage"
    w.save(w.ROOT / "day45-outage.json", plan)
    ref = w.reference(w.ROOT / "day45-outage.json")
    previous = w.execute(ref, "validate")
    try:
        w.execute(ref, "sync", previous)
    except Exception:
        assert not (w.ROOT / "workflows/day45-outage/sync/result.json").exists()
    else:
        raise AssertionError("Offline registry synchronized new work")
    assert sql("SELECT release_id FROM pymk_active_release") == [("day45-approved",)]
    print("PASS: unavailable registry blocks new synchronization without changing serving", flush=True)


def finish():
    # Lost artifact is rejected; restoring the exact bytes permits receipt reuse.
    plan = json.loads((w.ROOT / "day45-report.json").read_text())
    ref = w.reference(w.ROOT / "day45-report.json")
    artifact = w.resolve(plan["heavyModel"])
    hidden = artifact.with_suffix(".unavailable")
    artifact.rename(hidden)
    try:
        try:
            w.execute(ref, "validate")
        except FileNotFoundError:
            pass
        else:
            raise AssertionError("Missing artifact accepted")
    finally:
        hidden.rename(artifact)
    w.execute(ref, "validate")
    report = operations.snapshot()
    assert any(row["failedAttempts"] > 0 for row in report["groups"])
    w.save(w.ROOT / "day45-operations.json", report)
    (w.ROOT / "day45-metrics.prom").write_text(operations.prometheus(report))
    request_file = w.ROOT / "requests" / ("0"*32 + ".json")
    w.save(request_file, {"syntheticExpiredRequest": True})
    os.utime(request_file, (0, 0))
    result = operations.archive_requests(w.ROOT, apply=True, quiesced=True)
    assert request_file.name in result["archivedRequests"]
    assert (w.ROOT / "workflows/day45-sync/sync/bundle/approval.json").is_file()
    print("PASS: artifact-loss fail-closed/recovery, failure/freshness/duration telemetry, conservative retention", flush=True)


def restored():
    auth = token()
    from recovery_smoke import resume
    resume(auth, "metadata-restored")
    # Restore service, then prove a new reviewed version succeeds (uncertain partial output is retained).
    plan = json.loads((w.ROOT / "day45-sync.json").read_text())
    plan["outputVersion"] = "day45-registry-restored"
    # Only validate/synchronize: staging the same release with changed absolute paths is intentionally forbidden.
    w.save(w.ROOT / "day45-registry-restored.json", plan)
    ref = w.reference(w.ROOT / "day45-registry-restored.json")
    previous = w.execute(ref, "validate")
    w.execute(ref, "sync", previous)
    print("PASS: restored metadata reuses original effects; restored registry synchronizes new reviewed work", flush=True)


if __name__ == "__main__":
    {"prepare": prepare, "activate": activate, "http": http, "outage": outage, "finish": finish, "restored": restored}[sys.argv[1]]()
