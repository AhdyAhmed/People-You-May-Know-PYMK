"""Health includes HTTP and migrated DB connectivity; no schema mutation."""
import urllib.request
from sqlalchemy import create_engine, text
from launch import backend_uri

with urllib.request.urlopen("http://127.0.0.1:5000/health", timeout=3) as response:
    assert response.status == 200
with create_engine(backend_uri(), connect_args={"connect_timeout": 3}).connect() as connection:
    assert connection.execute(text("SELECT version_num FROM alembic_version")).scalar()
