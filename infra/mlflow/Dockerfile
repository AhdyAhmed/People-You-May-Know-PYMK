FROM python:3.12.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /opt/pymk-mlflow
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && pip check \
    && groupadd --gid 10001 mlflow && useradd --uid 10001 --gid mlflow --create-home mlflow \
    && mkdir /mlartifacts && chown mlflow:mlflow /mlartifacts
COPY launch.py health.py probe.py ./
USER 10001:10001
EXPOSE 5000
ENTRYPOINT ["python", "/opt/pymk-mlflow/launch.py"]
CMD ["serve"]
