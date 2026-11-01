"""Explicit metadata upgrade or non-root server; never print credentials."""
import os
import sys
from sqlalchemy import URL


def backend_uri():
    password = os.environ.get("MLFLOW_DB_PASSWORD", "")
    if len(password) < 16:
        raise ValueError("MLFLOW_DB_PASSWORD must contain at least 16 characters")
    return URL.create("postgresql+psycopg2", username="mlflow", password=password,
                      host="mlflow-db", port=5432, database="mlflow").render_as_string(hide_password=False)


def main():
    os.environ["MLFLOW_BACKEND_STORE_URI"] = backend_uri()
    mode = sys.argv[1] if len(sys.argv) == 2 else "invalid"
    if mode == "upgrade":
        # MLflow's CLI requires a URI argument for db upgrade (unlike server).
        # Visible to container administrators; do not expose docker/exec access.
        os.execvp("mlflow", ["mlflow", "db", "upgrade", os.environ["MLFLOW_BACKEND_STORE_URI"]])
    if mode != "serve":
        raise ValueError("Expected serve or upgrade")
    os.execvp("mlflow", ["mlflow", "server", "--host", "0.0.0.0", "--port", "5000",
                        "--workers", "1", "--serve-artifacts", "--artifacts-destination",
                        os.environ.get("PYMK_MLFLOW_ARTIFACT_DESTINATION", "/mlartifacts"),
                        "--default-artifact-root", "mlflow-artifacts:/",
                        "--allowed-hosts", "localhost:*,127.0.0.1:*,mlflow:5000",
                        "--cors-allowed-origins", os.environ.get("PYMK_MLFLOW_ORIGIN", "http://127.0.0.1:5000"),
                        "--x-frame-options", "DENY"])


if __name__ == "__main__":
    main()
