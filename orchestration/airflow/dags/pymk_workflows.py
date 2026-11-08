"""Thin scheduled adapters. All IO and computation happen inside tasks, never parsing."""
from datetime import timedelta
import pendulum
from airflow.sdk import DAG, Param
from airflow.providers.standard.operators.python import PythonOperator
from airflow.timetables.interval import CronDataIntervalTimetable


def launch_step(workflow, step, predecessor, params, ti, data_interval_start=None, data_interval_end=None, **context):
    import json
    import subprocess
    import uuid
    from pathlib import Path
    import sys
    if "/opt/pymk" not in sys.path: sys.path.insert(0, "/opt/pymk")
    from workflow import ROOT, read, resolve, select_plan
    if predecessor:
        previous = ti.xcom_pull(task_ids=predecessor)
        plan = read(previous)["plan"]
    else:
        previous = None
        plan = select_plan(workflow, params.get("manifest"), data_interval_start, data_interval_end)
    requests = ROOT / "requests"
    requests.mkdir(parents=True, exist_ok=True)
    request = requests / (uuid.uuid4().hex + ".json")
    with request.open("x") as stream:
        json.dump({"plan": plan, "step": step, "previous": previous}, stream)
    result = subprocess.run([sys.executable, "/opt/pymk/workflow.py", "--request", str(request)],
                            check=True, capture_output=True, text=True, timeout=3300)
    ref = json.loads(result.stdout.strip().splitlines()[-1])
    resolve(ref)
    return ref  # Only a path and digest cross XCom.


def make_dag(name, workflow, schedule, steps):
    with DAG(name, start_date=pendulum.datetime(2026, 1, 1, tz="UTC"), schedule=CronDataIntervalTimetable(schedule, timezone="UTC") if schedule else None,
             catchup=False, max_active_runs=1, max_active_tasks=1, dagrun_timeout=timedelta(hours=5),
             default_args={"retries": 0 if workflow == "approved_activation" else 1, "retry_delay": timedelta(seconds=30), "execution_timeout": timedelta(minutes=56), "pool": "pymk_jobs"},
             params={"manifest": Param(None, type=["null", "object"],
                 properties={"path": {"type": "string"}, "sha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"}},
                 required=["path", "sha256"], additionalProperties=False)},
             tags=["pymk", "managed-generations", "no-promotion"]) as dag:
        previous = None
        for step in steps:
            task = PythonOperator(task_id=step, python_callable=launch_step,
                op_kwargs={"workflow": workflow, "step": step, "predecessor": previous.task_id if previous else None})
            if previous: previous >> task
            previous = task
    return dag


feature_dag = make_dag("pymk_feature_refresh", "feature", "0 2 * * *", ("validate", "embeddings", "stage"))
candidate_dag = make_dag("pymk_model_candidate", "candidate", "0 4 * * 0", ("validate", "export", "light", "heavy", "evaluate", "blend", "prepare"))
reconciliation_dag = make_dag("pymk_live_reconciliation", "reconcile", "0 3 * * *", ("validate", "reconcile"))
backfill_dag = make_dag("pymk_historical_backfill", "backfill", None, ("validate", "restore", "light", "heavy", "evaluate", "blend", "prepare"))
sync_dag = make_dag("pymk_release_sync", "release_sync", None, ("validate", "sync", "stage"))
activation_dag = make_dag("pymk_approved_activation", "approved_activation", None, ("validate", "activate"))
