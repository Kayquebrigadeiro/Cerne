FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils tesseract-ocr tesseract-ocr-por && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml .
COPY scrawler_extractor ./scrawler_extractor
RUN pip install --no-cache-dir . && pip install --no-cache-dir https://github.com/explosion/spacy-models/releases/download/pt_core_news_sm-3.8.0/pt_core_news_sm-3.8.0-py3-none-any.whl
USER 10001:10001
ENV SPACY_MODEL=pt_core_news_sm
ENTRYPOINT ["scrawler-worker"]
