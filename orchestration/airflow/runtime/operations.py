"""Read-only telemetry and conservative, opt-in request-envelope retention.

Never removes workflow outputs, ledger rows, releases, models, rollback artifacts or logs.
Run from the packaged Airflow environment with the same PYMK DB/artifact volume.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time

import recovery
from workflow import ROOT, STEPS


def snapshot():
    connection = recovery.connect()
    try:
        with connection.cursor() as cursor:
            cursor.execute("""SELECT workflow,step,state,count(*),sum(failure_count),
                max(last_duration_seconds),extract(epoch FROM max(interval_end) FILTER (WHERE state='COMPLETED'))
                FROM pymk_workflow_work GROUP BY workflow,step,state ORDER BY workflow,step,state""")
            groups = []
            now = time.time()
            for workflow, step, state, count, failures, duration, interval in cursor.fetchall():
                # Legacy and probe identities are intentionally excluded from bounded metric labels.
                if workflow not in STEPS or step not in STEPS[workflow]:
                    continue
                groups.append(dict(workflow=workflow, step=step, state=state, items=count,
                    failedAttempts=int(failures), maxLastAttemptSeconds=duration,
                    freshnessSeconds=None if interval is None else max(0, now-float(interval))))
            return {"schemaVersion": 1, "observedAt": datetime.now(timezone.utc).isoformat(), "groups": groups}
    finally:
        connection.close()


def prometheus(report):
    names = {"items": "pymk_workflow_items", "failedAttempts": "pymk_workflow_failed_attempts",
             "maxLastAttemptSeconds": "pymk_workflow_max_last_attempt_seconds",
             "freshnessSeconds": "pymk_workflow_freshness_seconds"}
    lines = []
    for field, metric in names.items():
        lines.append(f"# TYPE {metric} gauge")
        for row in report["groups"]:
            labels = ','.join(f'{key}="{row[key]}"' for key in ("workflow", "step", "state"))
            value = row[field]
            lines.append(f'{metric}{{{labels}}} {"NaN" if value is None else value}')
    return '\n'.join(lines) + '\n'


def archive_requests(root, days=30, apply=False, quiesced=False, now=None):
    if days < 7:
        raise ValueError("Retention must be at least seven days")
    if apply and not quiesced:
        raise ValueError("Pause DAGs and stop workers before confirming quiescence")
    root = Path(root).resolve()
    source = root / "requests"
    archive = root / "request-archive"
    if source.is_symlink() or archive.is_symlink():
        raise ValueError("Symlink retention directories are forbidden")
    if apply:
        connection = recovery.connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT count(*) FROM pymk_workflow_work WHERE state='RUNNING'")
                if cursor.fetchone()[0]:
                    raise ValueError("Unreconciled RUNNING work prevents retention")
        finally:
            connection.close()
    cutoff = (time.time() if now is None else now) - days*86400
    selected = []
    for path in sorted(source.glob("*.json")):
        if path.is_symlink() or not re.fullmatch(r"[a-f0-9]{32}\.json", path.name) or path.stat().st_mtime >= cutoff:
            continue
        # No user-controlled path is followed, and no model/data directory is traversed.
        selected.append(path.name)
        if apply:
            archive.mkdir(exist_ok=True)
            destination = archive / path.name
            if destination.exists():
                raise ValueError("Archive destination already exists; reconcile before retry")
            # Hard-link first: never overwrite, and interrupted cleanup retains at least one copy.
            destination.hardlink_to(path)
            path.unlink()
    return {"dryRun": not apply, "retainedDays": days, "archivedRequests": selected,
            "workflowArtifactsDeleted": 0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("status", "metrics", "retention"))
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--quiesced", action="store_true")
    args = parser.parse_args()
    if args.action == "retention":
        print(json.dumps(archive_requests(ROOT, args.days, args.apply, args.quiesced), sort_keys=True))
    else:
        report = snapshot()
        print(prometheus(report) if args.action == "metrics" else json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
