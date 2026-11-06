from datetime import timedelta
from pathlib import Path
import unittest
from unittest.mock import patch

from pymk_contract import validate, logical_key


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.params = dict(interval_start="2026-01-01T00:00:00Z", interval_end="2026-01-02T00:00:00Z",
                           source_sha256="a"*64, output_version="runtime-v1")

    def test_identity_is_deterministic_and_binds_every_input(self):
        self.assertEqual(logical_key(self.params), logical_key(dict(reversed(list(self.params.items())))))
        for key, value in (("interval_end", "2026-01-01T12:00:00Z"), ("source_sha256", "b"*64), ("output_version", "v2")):
            self.assertNotEqual(logical_key(self.params), logical_key({**self.params, key: value}))

    def test_invalid_intervals_and_paths_are_rejected(self):
        for key, value in (("interval_start", "2026-01-01Z"), ("interval_start", "2026-01-01T00:00:00"), ("interval_start", "2026-01-02T00:00:00Z"),
                           ("interval_end", "2026-01-03T00:00:00Z"), ("interval_end", "2099-01-01T00:00:00Z"),
                           ("source_sha256", "unknown"), ("source_sha256", None), ("output_version", "../escape"),
                           ("output_version", "a; echo injection")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): validate({**self.params, key: value})

    def test_missing_or_additional_parameters_are_rejected(self):
        with self.assertRaises(ValueError): validate({**self.params, "command": "arbitrary"})
        del self.params["source_sha256"]
        with self.assertRaises(ValueError): validate(self.params)


class DagStructureTest(unittest.TestCase):
    def test_import_without_live_services_or_subprocesses(self):
        from airflow.dag_processing.dagbag import DagBag
        with patch("subprocess.run", side_effect=AssertionError("DAG parsing launched work")), \
             patch("socket.socket.connect", side_effect=AssertionError("DAG parsing contacted a service")):
            bag = DagBag(dag_folder="/opt/airflow/dags", include_examples=False)
        self.assertEqual(bag.import_errors, {})
        self.assertEqual(set(bag.dags), {"pymk_runtime_contract"})
        dag = bag.dags["pymk_runtime_contract"]
        self.assertFalse(dag.catchup)
        self.assertEqual(dag.max_active_runs, 1)
        self.assertEqual(dag.max_active_tasks, 1)
        self.assertEqual(dag.dagrun_timeout, timedelta(minutes=10))
        self.assertEqual(str(dag.timezone), "UTC")
        self.assertEqual(type(dag.timetable).__name__, "NullTimetable")
        self.assertEqual(set(dag.task_ids), {"validate_interval", "runtime_probe"})
        self.assertEqual(dag.get_task("validate_interval").downstream_task_ids, {"runtime_probe"})
        for task in dag.tasks:
            self.assertEqual(task.pool, "pymk_jobs")
            self.assertEqual(task.retries, 1)
            self.assertEqual(task.execution_timeout, timedelta(minutes=4))
        self.assertNotIn("shell=True", Path("/opt/airflow/dags/pymk_runtime_contract.py").read_text())


if __name__ == "__main__": unittest.main()
