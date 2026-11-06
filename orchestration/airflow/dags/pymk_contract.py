"""Pure workflow validation. No I/O or heavyweight imports during DAG parsing."""
from datetime import datetime, timezone
import hashlib
import json
import re


def validate(parameters):
    if set(parameters) != {"interval_start", "interval_end", "source_sha256", "output_version"}:
        raise ValueError("Exactly the four declared workflow parameters are required")
    if not all(isinstance(value, str) for value in parameters.values()):
        raise ValueError("Workflow parameters must be strings")
    times = []
    for key in ("interval_start", "interval_end"):
        value = parameters[key]
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z", value):
            raise ValueError("Intervals must use explicit UTC Z timestamps")
        times.append(datetime.fromisoformat(value.replace("Z", "+00:00")))
    if not times[0] < times[1] or (times[1] - times[0]).total_seconds() > 86400:
        raise ValueError("Require a positive interval of at most one day")
    if times[1] > datetime.now(timezone.utc):
        raise ValueError("Future intervals are not ready")
    if not re.fullmatch(r"[a-f0-9]{64}", parameters["source_sha256"]):
        raise ValueError("Source SHA-256 required")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", parameters["output_version"]):
        raise ValueError("Safe immutable output version required")
    return dict(parameters)


def logical_key(parameters):
    return hashlib.sha256(json.dumps(validate(parameters), sort_keys=True, separators=(",", ":")).encode()).hexdigest()
