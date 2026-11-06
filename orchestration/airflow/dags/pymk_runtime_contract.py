"""Day 41 launch-contract smoke; production feature/ML scheduling starts Day 42."""
from datetime import timedelta
import pendulum
from airflow.sdk import DAG, Param
from airflow.providers.standard.operators.python import PythonOperator
from pymk_contract import validate, logical_key


def validate_interval(params, **context):
    # Manual run parameters define the work interval; never substitute today's date.
    return validate(dict(params))


def run_probe(ti, **context):
    import hashlib
    import json
    from pathlib import Path
    import subprocess
    contract = ti.xcom_pull(task_ids="validate_interval")
    key = logical_key(contract)
    result = subprocess.run(["/opt/pymk/trainer/bin/python", "/opt/pymk/runtime_probe.py",
        "--key", key, "--source-sha256", contract["source_sha256"]], check=True,
        capture_output=True, text=True, timeout=180)
    reference = json.loads(result.stdout.strip().splitlines()[-1])
    path = Path(reference["path"])
    if hashlib.sha256(path.read_bytes()).hexdigest() != reference["sha256"]:
        raise ValueError("Worker result checksum mismatch")
    return reference  # Small reference only, never model/data bytes.


with DAG(
    dag_id="pymk_runtime_contract", description="Day 41 isolated Java/Python launch and UTC contract check",
    start_date=pendulum.datetime(2026, 1, 1, tz="UTC"), schedule=None, catchup=False,
    max_active_runs=1, max_active_tasks=1, dagrun_timeout=timedelta(minutes=10),
    default_args={"retries": 1, "retry_delay": timedelta(seconds=30),
                  "execution_timeout": timedelta(minutes=4), "pool": "pymk_jobs"},
    params={
        "interval_start": Param(type="string", format="date-time"),
        "interval_end": Param(type="string", format="date-time"),
        "source_sha256": Param(type="string", pattern="^[a-f0-9]{64}$"),
        "output_version": Param(type="string", pattern="^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$"),
    }, tags=["pymk", "foundation", "no-promotion"],
) as dag:
    validate_task = PythonOperator(task_id="validate_interval", python_callable=validate_interval)
    probe_task = PythonOperator(task_id="runtime_probe", python_callable=run_probe)
    validate_task >> probe_task
