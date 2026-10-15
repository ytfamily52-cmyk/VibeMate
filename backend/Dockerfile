# VibeMate backend container — works on Cloud Run, Render (Docker), Fly.io, etc.
# Deploy:
#   docker build -t vibemate-api backend
#   docker run -p 8000:8000 --env-file backend/.env vibemate-api
#
# On Cloud Run / Render the platform injects $PORT; the default is 8000 locally.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencies first so source edits do not bust the pip layer cache.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Application code + the 18+ persona prompts live in app/.
COPY app ./app

EXPOSE 8000

# $PORT is set by Cloud Run / Render; falls back to 8000 for local docker run.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
