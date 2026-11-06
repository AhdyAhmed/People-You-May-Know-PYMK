import base64
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, "/opt/pymk")
from launch import configure


class StartupTest(unittest.TestCase):
    def setUp(self):
        self.values = {key: "x"*40 for key in ("PYMK_AIRFLOW_DB_PASSWORD", "PYMK_AIRFLOW_UI_PASSWORD", "PYMK_AIRFLOW_JWT_SECRET")}
        self.values["PYMK_AIRFLOW_FERNET_KEY"] = base64.urlsafe_b64encode(b"x"*32).decode()

    def test_missing_or_weak_secrets_reject_before_writing(self):
        for key in self.values:
            with self.subTest(key=key), patch.dict(os.environ, {**self.values, key: ""}, clear=True), \
                 patch.object(Path, "write_text") as write:
                with self.assertRaises(ValueError): configure()
                write.assert_not_called()

    def test_invalid_fernet_rejected(self):
        with patch.dict(os.environ, {**self.values, "PYMK_AIRFLOW_FERNET_KEY": "x"*40}, clear=True):
            with self.assertRaisesRegex(ValueError, "Fernet"): configure()

    def test_database_password_is_encoded_and_password_file_private(self):
        with patch.dict(os.environ, {**self.values, "PYMK_AIRFLOW_DB_PASSWORD": "x"*40 + "@:/"}, clear=True), \
             patch.object(Path, "write_text") as write, patch.object(Path, "chmod") as chmod:
            configure()
            self.assertIn("%40%3A%2F@airflow-db", os.environ["AIRFLOW__DATABASE__SQL_ALCHEMY_CONN"])
            self.assertEqual(os.environ["AIRFLOW__API_AUTH__JWT_SECRET"], self.values["PYMK_AIRFLOW_JWT_SECRET"])
            write.assert_called_once()
            chmod.assert_called_once_with(0o600)
