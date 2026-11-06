import json
import urllib.request

with urllib.request.urlopen("http://localhost:8080/api/v2/monitor/health", timeout=5) as response:
    health = json.load(response)
if health["metadatabase"]["status"] != "healthy":
    raise SystemExit("Airflow metadata database is unhealthy")
