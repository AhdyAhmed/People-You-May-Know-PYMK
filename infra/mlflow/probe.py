"""Infrastructure acceptance only: no training integration or serving promotion."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request
import urllib.error
import mlflow
from mlflow import MlflowClient
from sqlalchemy import create_engine, text
from launch import backend_uri

PAYLOAD = b"pymk-day36-persistent-artifact\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["write", "read"])
    parser.add_argument("--uri", default="http://mlflow:5000")
    parser.add_argument("--experiment", required=True)
    args = parser.parse_args()
    assert mlflow.__version__ == "3.17.1"
    client = MlflowClient(tracking_uri=args.uri)
    if args.phase == "write":
        experiment = client.create_experiment(args.experiment)
        run = client.create_run(experiment, tags={"purpose": "infrastructure-smoke"})
        client.log_param(run.info.run_id, "seed", "36")
        client.log_metric(run.info.run_id, "probe", 1.0)
        with tempfile.TemporaryDirectory() as folder:
            artifact = Path(folder) / "probe.txt"
            artifact.write_bytes(PAYLOAD)
            client.log_artifact(run.info.run_id, str(artifact), "checks")
        client.set_terminated(run.info.run_id)
    experiment = client.get_experiment_by_name(args.experiment)
    assert experiment is not None
    assert experiment.artifact_location.startswith("mlflow-artifacts:/")
    runs = client.search_runs([experiment.experiment_id])
    assert len(runs) == 1
    run = runs[0]
    assert run.info.status == "FINISHED"
    assert run.data.params["seed"] == "36"
    assert run.data.metrics["probe"] == 1.0
    with tempfile.TemporaryDirectory() as folder:
        downloaded = client.download_artifacts(run.info.run_id, "checks/probe.txt", folder)
        assert Path(downloaded).read_bytes() == PAYLOAD
    # Backend role owns its schema but cannot administer the cluster.
    with create_engine(backend_uri()).connect() as db:
        assert db.execute(text("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname=current_user")).scalar()
        assert db.execute(text("SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history'")).scalar() == 0
    bad_host = urllib.request.Request(args.uri + "/api/2.0/mlflow/experiments/search",
                                     data=b'{"max_results":1}',
                                     headers={"Host": "untrusted.example", "Content-Type": "application/json"})
    try:
        urllib.request.urlopen(bad_host, timeout=10)
        raise AssertionError("Untrusted Host header accepted")
    except urllib.error.HTTPError as error:
        assert error.code in (400, 403), error.code
    print(json.dumps({"phase": args.phase, "run_id": run.info.run_id,
                      "sha256": hashlib.sha256(PAYLOAD).hexdigest(), "version": mlflow.__version__}))


if __name__ == "__main__":
    main()
