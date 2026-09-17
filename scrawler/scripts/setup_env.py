"""Generate unique local credentials without overwriting existing settings."""

from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[1] / ".env"
if path.exists():
    raise SystemExit(".env existente preservado.")
path.write_text(
    "APP_USER=professor\n"
    + "".join(
        f"{key}={secrets.token_urlsafe(24)}\n"
        for key in ["DB_PASSWORD", "APP_PASSWORD", "WORKER_PASSWORD"]
    )
    + "ENABLE_OCR=false\n"
)
path.chmod(0o600)
print("Configuracao criada. Consulte APP_USER e APP_PASSWORD em .env para entrar.")
