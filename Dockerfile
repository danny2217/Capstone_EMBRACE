FROM python:3.11-slim

WORKDIR /app

# 의존성 설치
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 소스코드 복사
COPY . .

# FastAPI 실행 (8080 포트)
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]