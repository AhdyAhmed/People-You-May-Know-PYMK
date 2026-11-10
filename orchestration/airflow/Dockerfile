FROM eclipse-temurin:21.0.10_7-jre-jammy AS java
FROM apache/airflow:3.2.2-python3.12
USER root
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN mkdir -p /opt/pymk /opt/airflow/artifacts && chown -R airflow:0 /opt/pymk /opt/airflow/artifacts \
    && python -m venv /opt/pymk/trainer && chown -R airflow:0 /opt/pymk/trainer
USER airflow
RUN pip install --no-cache-dir 'apache-airflow==3.2.2' 'apache-airflow-providers-standard==1.13.1' \
    --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.2.2/constraints-3.12.txt && pip check
COPY --chown=airflow:0 orchestration/airflow/trainer-requirements.txt /opt/pymk/
RUN /opt/pymk/trainer/bin/pip install --no-cache-dir torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu \
    && /opt/pymk/trainer/bin/pip install --no-cache-dir -r /opt/pymk/trainer-requirements.txt
COPY --chown=airflow:0 pymk-ml-training/pyproject.toml /opt/pymk/training/pyproject.toml
COPY --chown=airflow:0 pymk-ml-training/src /opt/pymk/training/src
RUN /opt/pymk/trainer/bin/pip install --no-cache-dir --no-deps /opt/pymk/training \
    && /opt/pymk/trainer/bin/pip check
COPY --chown=airflow:0 pymk-batch/target/pymk-batch.jar /opt/pymk/pymk-batch.jar
COPY --chown=airflow:0 pymk-api/target/pymk-api.jar /opt/pymk/pymk-api.jar
COPY --chown=airflow:0 pymk-batch/src/test/resources/day23/model.base64 /opt/pymk/model.base64
COPY --chown=airflow:0 orchestration/airflow/runtime /opt/pymk/
COPY --chown=airflow:0 orchestration/airflow/dags /opt/airflow/dags
COPY --chown=airflow:0 orchestration/airflow/tests /opt/pymk/tests
COPY --chown=airflow:0 pymk-ml-training/evaluation-policy.json pymk-ml-training/blend-policy.json pymk-ml-training/tests/fixtures/profiles.csv /opt/pymk/fixtures/
ENV PYTHONPATH=/opt/airflow/dags
ENTRYPOINT ["/usr/bin/dumb-init", "--", "python", "/opt/pymk/launch.py"]
CMD ["api-server"]
