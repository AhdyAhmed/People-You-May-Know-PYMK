"""Database identity fence plus inherited Linux file lock for surviving child processes."""
from contextlib import contextmanager
import hashlib
import json
import os
import time
from pathlib import Path
from urllib.parse import urlsplit

INHERITED_FDS = ()


def key(document):
    return hashlib.sha256(json.dumps(document, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def connect():
    import psycopg2  # Airflow runtime, not the isolated trainer environment
    url = urlsplit(os.environ["SPRING_DATASOURCE_URL"].removeprefix("jdbc:"))
    if url.scheme != "postgresql" or not url.hostname or url.username or url.query or url.fragment:
        raise ValueError("Use a PostgreSQL JDBC URL with separately injected credentials")
    conn = psycopg2.connect(host=url.hostname, port=url.port or 5432, dbname=url.path.lstrip("/"),
                            user=os.environ["SPRING_DATASOURCE_USERNAME"],
                            password=os.environ["SPRING_DATASOURCE_PASSWORD"], connect_timeout=10)
    conn.autocommit = True
    return conn


def observations(directory):
    result = {"batch": [], "tracking": []}
    for path in sorted(directory.rglob("*-receipt.json")):
        result["batch"].append({"path": str(path), "receipt": json.loads(path.read_text())})
    for path in sorted(directory.rglob("tracking/*/*/state.json")):
        result["tracking"].append({"path": str(path), "state": json.loads(path.read_text())})
    return result


@contextmanager
def claim(root, version, step, identity):
    import fcntl  # Packaged workflows are explicitly Linux-container-only.
    global INHERITED_FDS
    directory = Path(root) / "workflows" / version / step
    locks = Path(root) / ".locks"
    locks.mkdir(parents=True, exist_ok=True)
    logical = key(identity)
    conn = connect()
    try:
        with conn.cursor() as cursor, (locks / (key([version, step]) + ".lock")).open("a+") as file:
            cursor.execute("SELECT pg_try_advisory_lock(hashtextextended(%s,440043))", (version + ":" + step,))
            if not cursor.fetchone()[0]: raise ValueError("Work is owned by another worker")
            # Children inherit this descriptor: SIGKILL of the supervisor does not release their fence.
            fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            cursor.execute("SELECT logical_key,artifact_root FROM pymk_workflow_work WHERE output_version=%s AND step=%s", (version, step))
            row = cursor.fetchone()
            if row and row != (logical, str(Path(root).resolve())):
                raise ValueError("Destination already bound to different inputs, runtime or artifact root")
            plan = identity.get("plan", {})
            cursor.execute("""INSERT INTO pymk_workflow_work
                (output_version,step,logical_key,artifact_root,state,workflow,interval_end,started_at)
                VALUES (%s,%s,%s,%s,'RUNNING',%s,%s,clock_timestamp())
                ON CONFLICT (output_version,step) DO UPDATE SET state='RUNNING',
                attempts=pymk_workflow_work.attempts+1,updated_at=clock_timestamp(),started_at=clock_timestamp(),
                workflow=EXCLUDED.workflow,interval_end=EXCLUDED.interval_end""",
                (version, step, logical, str(Path(root).resolve()), plan.get("workflow"), plan.get("intervalEnd")))
            started = time.monotonic()
            INHERITED_FDS = (file.fileno(),)
            try:
                yield logical
                evidence = observations(directory)
                receipt = directory / "result.json"
                if not receipt.is_file(): raise ValueError("Missing completed work receipt")
                evidence["result"] = {"path": str(receipt), "sha256": hashlib.sha256(receipt.read_bytes()).hexdigest()}
                cursor.execute("""UPDATE pymk_workflow_work SET state='COMPLETED',evidence=%s::jsonb,
                    updated_at=clock_timestamp(),completed_at=clock_timestamp(),last_duration_seconds=%s WHERE logical_key=%s""",
                               (json.dumps(evidence), time.monotonic()-started, logical))
            except BaseException:
                cursor.execute("""UPDATE pymk_workflow_work SET state='BLOCKED',evidence=%s::jsonb,
                    updated_at=clock_timestamp(),failure_count=failure_count+1,last_duration_seconds=%s WHERE logical_key=%s""",
                               (json.dumps(observations(directory)), time.monotonic()-started, logical))
                raise
            finally:
                INHERITED_FDS = ()
    finally:
        conn.close()  # Releases session advisory lock, including on failures.
