FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PINCH_DATA_DIR=/data PINCH_TRUSTED_PROXY_IPS=""
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --require-hashes -r requirements.txt \
    && useradd --uid 10001 --create-home pinch \
    && mkdir /data && chown pinch:pinch /data
COPY server.py cookbooks.py managed.py admin.py plugin_auth.py pinch_plugin.py plugin_ui.html invitation.html ./
USER pinch
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"
CMD ["sh", "-c", "exec uvicorn server:app --host 0.0.0.0 --port 8000 --workers 1 --limit-concurrency 64 --proxy-headers --forwarded-allow-ips \"$PINCH_TRUSTED_PROXY_IPS\" --no-access-log"]
