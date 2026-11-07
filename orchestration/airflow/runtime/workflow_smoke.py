"""Disposable Day 42 acceptance. Synthetic history is explicitly not real historical replay."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import psycopg2
from smoke import request, wait_for
import workflow as w


def sql(statement):
    with psycopg2.connect(host="workflow-postgres", dbname="pymk", user="pymk", password=os.environ["SPRING_DATASOURCE_PASSWORD"]) as conn:
        with conn.cursor() as cursor:
            cursor.execute(statement)
            return cursor.fetchall() if cursor.description else None


def feature(version, date, revision, transform, profiles):
    from datetime import datetime, timedelta
    end = datetime.fromisoformat(date.replace("Z", "+00:00"))
    return {"schemaVersion": 1, "workflow": "feature", "intervalStart": (end-timedelta(days=1)).isoformat().replace("+00:00", "Z"),
            "intervalEnd": date, "outputVersion": version, "sourceRevision": revision,
            "transform": w.reference(transform), "profiles": w.reference(profiles)}


def run_dag(token, name, ident, plan, expected="success"):
    path = w.ROOT / (ident + ".json")
    w.save(path, plan)
    endpoint = "/api/v2/dags/" + name
    wait_for(lambda: request("/api/v2/dags", token=token), lambda result: any(d["dag_id"] == name for d in result["dags"]))
    request(endpoint, {"is_paused": False}, "PATCH", token)
    request(endpoint + "/dagRuns", {"dag_run_id": ident, "logical_date": None, "conf": {"manifest": w.reference(path)}}, "POST", token)
    result = wait_for(lambda: request(endpoint + "/dagRuns/" + ident, token=token), lambda r: r["state"] in ("success", "failed"), seconds=1800)
    request(endpoint, {"is_paused": True}, "PATCH", token)
    assert result["state"] == expected, (name, ident, result["state"])
    print("PASS:", name, ident, expected, flush=True)


def main():
    base = w.ROOT / "day42-inputs"
    base.mkdir()
    for name in ("profiles.csv", "evaluation-policy.json", "blend-policy.json"):
        shutil.copyfile(Path("/opt/pymk/fixtures") / name, base / name)
    w.command(["java", "-jar", w.JAR, "--spring.batch.job.enabled=false"], base / "migrate.log")
    sql("""INSERT INTO members(id,full_name,headline,company,school,geo_region,created_at) VALUES
        (1,'Engineer 1','Java backend engineer distributed systems','Acme','Engineering University','Cairo','2026-01-01'),
        (2,'Engineer 2','Java backend engineer distributed systems','Acme','Engineering University','Cairo','2026-01-01'),
        (3,'Chef','Chef culinary pastry restaurant','Bakery','Cooking Academy','Paris','2026-01-01'),
        (4,'Blank',NULL,NULL,NULL,NULL,'2026-01-01');
        INSERT INTO member_events(actor_member_id,target_member_id,type,occurred_at)
        SELECT a,b,'INVITE_ACCEPTED',d::timestamptz FROM (VALUES (1,2),(2,1)) p(a,b)
        CROSS JOIN (VALUES ('2026-01-05'),('2026-01-12'),('2026-01-19')) dates(d);
        INSERT INTO member_events(actor_member_id,target_member_id,type,occurred_at)
        SELECT 2,1,'INVITE_SENT',d::timestamptz FROM (VALUES ('2026-01-04'),('2026-01-11'),('2026-01-18')) dates(d);""")
    revision = str(sql("SELECT revision FROM pymk_source_revision")[0][0])
    transform, profiles = base / "transform.json", base / "profiles.csv"
    w.cli("profile_embeddings", ["--input", profiles, "--artifact", transform, "--output", base / "fit.csv",
        "--as-of", "2026-01-03T00:00:00Z", "--fit", "--embedding-version", "day42-transform"], base)
    history, inputs = [], []
    # Existing historical generations are prerequisites, not a claim that today's profiles are historical.
    for i, day in enumerate((3, 10, 17)):
        plan = feature(f"history-{i}", f"2026-01-{day:02d}T00:00:00Z", revision, transform, profiles)
        path = base / f"history-{i}.json"
        w.save(path, plan)
        previous = None
        for step in w.STEPS["feature"]:
            previous = w.execute(w.reference(path), step, previous)
        item = {"releaseId": f"history-{i}", "featureAsOf": plan["intervalEnd"], "labelWindowEnd": f"2026-01-{day+7:02d}T00:00:00Z"}
        history.append(item)
        output = base / f"context-{i}.csv"
        w.batch(plan, "servingReleaseJob", {"action": "export-v2", "releaseId": item["releaseId"],
            "labelWindowEnd": item["labelWindowEnd"], "outputPath": output, "datasetId": f"context-{i}"}, base, f"context-{i}")
        inputs += ["--input", output]
    w.cli("blend_context_fixture", [*inputs, "--profiles", profiles, "--output", base / "context.json"], base)
    token = request("/auth/token", {"username": "operator", "password": os.environ["PYMK_AIRFLOW_UI_PASSWORD"]})["access_token"]
    active = sql("SELECT release_id,epoch FROM pymk_active_release")
    retained = sql("SELECT release_id,manifest_sha256,state FROM pymk_serving_releases ORDER BY release_id")
    run_dag(token, "pymk_feature_refresh", "day42-feature", feature("fresh-generation", "2026-02-01T00:00:00Z", revision, transform, profiles))
    plan = {"schemaVersion": 1, "workflow": "candidate", "intervalStart": "2026-01-24T00:00:00Z", "intervalEnd": "2026-01-25T00:00:00Z",
            "outputVersion": "day42-candidate", "sourceRevision": revision, "transform": w.reference(transform), "history": history,
            "evaluationPolicy": w.reference(base / "evaluation-policy.json"), "blendPolicy": w.reference(base / "blend-policy.json"),
            "context": w.reference(base / "context.json")}
    run_dag(token, "pymk_model_candidate", "day42-candidate", plan)
    result = json.loads((w.ROOT / "workflows/day42-candidate/prepare/result.json").read_text())
    assert result["results"]["qualityGate"] == "not_met" and not result["automaticPromotion"]
    for kind in ("light", "heavy"):
        candidate = json.loads((w.ROOT / f"workflows/day42-candidate/prepare/{kind}/candidate.json").read_text())
        assert not candidate["automaticPromotion"] and candidate["javaVerifierSha256"] == w.digest(w.JAR)
    invalid = copy.deepcopy(plan)
    invalid["outputVersion"] = "immature-rejected"
    invalid["history"][-1]["labelWindowEnd"] = "2026-02-01T00:00:00Z"
    run_dag(token, "pymk_model_candidate", "day42-immature", invalid, "failed")
    assert not (w.ROOT / "workflows/immature-rejected").exists()
    # Valid file/hash but incomplete member coverage: fail in the actual managed staging job.
    incomplete = base / "incomplete.csv"
    incomplete.write_text("\n".join(profiles.read_text().splitlines()[:-1]) + "\n")
    failed = feature("failed-refresh", "2026-02-02T00:00:00Z", revision, transform, incomplete)
    run_dag(token, "pymk_feature_refresh", "day42-failed-refresh", failed, "failed")
    assert sql("SELECT release_id,epoch FROM pymk_active_release") == active
    assert sql("SELECT release_id,manifest_sha256,state FROM pymk_serving_releases WHERE release_id LIKE 'history-%' ORDER BY release_id") == retained
    assert sql("SELECT state FROM pymk_serving_releases WHERE release_id='fresh-generation'")[0][0] == "STAGED"
    print("PASS: managed generations retained; failed refresh isolated; mature tracked L1/L2, evaluation, blend and Java-validated candidates; no activation", flush=True)


if __name__ == "__main__": main()
