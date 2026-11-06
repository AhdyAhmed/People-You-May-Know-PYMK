"""Secrets are injected at runtime; Airflow, never Flyway, owns this metadata DB."""
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import quote


def configure():
    for key in ("PYMK_AIRFLOW_DB_PASSWORD", "PYMK_AIRFLOW_UI_PASSWORD", "PYMK_AIRFLOW_JWT_SECRET", "PYMK_AIRFLOW_FERNET_KEY"):
        if len(os.environ.get(key, "")) < 32:
            raise ValueError(f"Provide a strong {key} (at least 32 characters)")
    from cryptography.fernet import Fernet
    try:
        Fernet(os.environ["PYMK_AIRFLOW_FERNET_KEY"].encode())
    except (ValueError, TypeError) as error:
        raise ValueError("Provide a valid URL-safe base64 32-byte Fernet key") from error
    os.environ["AIRFLOW__DATABASE__SQL_ALCHEMY_CONN"] = "postgresql+psycopg2://airflow:" + quote(os.environ["PYMK_AIRFLOW_DB_PASSWORD"], safe="") + "@airflow-db/airflow"
    os.environ["AIRFLOW__API_AUTH__JWT_SECRET"] = os.environ["PYMK_AIRFLOW_JWT_SECRET"]
    os.environ["AIRFLOW__API__SECRET_KEY"] = os.environ["PYMK_AIRFLOW_JWT_SECRET"]
    os.environ["AIRFLOW__CORE__FERNET_KEY"] = os.environ["PYMK_AIRFLOW_FERNET_KEY"]
    password_file = Path("/tmp/pymk-airflow-passwords.json")
    password_file.write_text(json.dumps({"operator": os.environ["PYMK_AIRFLOW_UI_PASSWORD"]}))
    password_file.chmod(0o600)
    os.environ["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE"] = str(password_file)


if __name__ == "__main__":
    configure()
    if sys.argv[1:] == ["init"]:
        subprocess.run(["airflow", "db", "migrate"], check=True)
        subprocess.run(["airflow", "pools", "set", "pymk_jobs", "1", "Bounded PYMK subprocess work"], check=True)
    else:
        os.execvp("airflow", ["airflow", *sys.argv[1:]])
