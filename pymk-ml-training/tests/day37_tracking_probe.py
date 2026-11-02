"""Real MLflow CLI publication/outage/retry probe, invoked by the PowerShell smoke."""
import argparse
import json
from pathlib import Path
import tempfile

from mlflow import MlflowClient
from pymk_training import tracking
from pymk_training.train_light_ranker import main as light_main
from test_light_ranker_training import _write_snapshots


def verify(bundle, uri):
    state = json.loads((bundle / "state.json").read_bytes())
    payload = json.loads((bundle / "manifest.json").read_bytes())
    assert state["status"] == "published"
    client = MlflowClient(tracking_uri=uri)
    run_id = state["attempts"][-1]["runId"]
    run = client.get_run(run_id)
    assert run.data.tags["pymk.publication"] == "complete"
    assert run.data.tags["pymk.logicalWorkId"] == payload["logicalWorkId"]
    assert run.data.metrics == payload["evidence"]["metrics"]
    expected = dict(payload["specification"]["parameters"], **payload["evidence"].get("parameters", {}))
    assert run.data.params == {key: str(value) for key, value in expected.items()}
    assert run.info.status == ("FINISHED" if payload["outcome"] == "succeeded" else "FAILED")
    with tempfile.TemporaryDirectory() as folder:
        artifact = client.download_artifacts(run_id, "tracking/manifest.json", folder)
        assert tracking.digest(artifact) == tracking.digest(bundle / "manifest.json")
        assert str(bundle.parent.parent) not in Path(artifact).read_text()
    assert not list(client.search_registered_models()), "Day 37 must not register/promote serving models"
    return run_id


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["live", "outage", "resume"])
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--uri", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.phase == "live":
        paths = _write_snapshots(root / "data", snapshot_count=8)
    else:
        paths = sorted((root / "data").glob("*.csv"))
    common = sum((["--input", str(path)] for path in paths), []) + ["--model-version", "day37-light"]
    if args.phase in ("live", "outage"):
        spool = root / (args.phase + "-spool")
        code = light_main(common + ["--output-dir", str(root / args.phase), "--tracking-dir", str(spool), "--tracking-uri", args.uri])
        assert code == (0 if args.phase == "live" else 3), code
        bundle = next(spool.glob("*/*/manifest.json")).parent
        if args.phase == "live":
            verify(bundle, args.uri)
            # Invalid computation must appear as FAILED, not disappear or leak error messages.
            bad_spool = root / "failed-spool"
            assert light_main(["--input", str(root / "missing-private-data.csv"), "--output-dir", str(root / "failed"),
                               "--tracking-dir", str(bad_spool), "--tracking-uri", args.uri]) == 2
            failed = next(bad_spool.glob("*/*/manifest.json")).parent
            verify(failed, args.uri)
            assert "missing-private-data" not in (failed / "manifest.json").read_text()
        else:
            assert json.loads((bundle / "state.json").read_bytes())["status"] == "unpublished"
            assert (root / "outage/day37-light.onnx").is_file()
    else:
        bundle = next((root / "outage-spool").glob("*/*/manifest.json")).parent
        before = tracking.digest(root / "outage/day37-light.onnx")
        first = tracking.publish(bundle, args.uri)
        assert tracking.publish(bundle, args.uri) == first
        assert verify(bundle, args.uri) == first
        assert tracking.digest(root / "outage/day37-light.onnx") == before
        state = json.loads((bundle / "state.json").read_bytes())
        assert len(state["attempts"]) == 2
    print("PASS: Day 37 real tracking " + args.phase)


if __name__ == "__main__": main()
