"""Authenticated polling worker. No database credentials or automatic approval."""

import os
import logging
import tempfile
import threading
import time
from pathlib import Path
import requests
from .extract import Extractor

log = logging.getLogger(__name__)


class Worker:
    def __init__(self):
        self.base = (
            os.environ.get("API_URL", "http://localhost:8080").rstrip("/")
            + "/api/worker"
        )
        self.session = requests.Session()
        self.session.auth = ("worker", os.environ["WORKER_PASSWORD"])

    def request(self, method, path, **kwargs):
        response = self.session.request(method, self.base + path, timeout=90, **kwargs)
        response.raise_for_status()
        return response

    def once(self):
        job = self.request("POST", "/claim").json()
        if not job:
            return False
        token = job["tarefa_token"]
        doc = job["id"]
        stop = threading.Event()
        lost = threading.Event()

        def renew():
            # Separate Session because requests.Session is not thread safe.
            with requests.Session() as heartbeat_session:
                heartbeat_session.auth = self.session.auth
                while not stop.wait(45):
                    try:
                        response = heartbeat_session.post(
                            f"{self.base}/documents/{doc}/heartbeat",
                            params={"token": token},
                            timeout=30,
                        )
                        response.raise_for_status()
                    except requests.RequestException:
                        lost.set()
                        return

        thread = threading.Thread(target=renew, daemon=True)
        thread.start()
        try:
            dictionary = self.request("GET", "/dictionary").json()
            extractor = Extractor(dictionary, os.environ.get("SPACY_MODEL") or None)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / ("document." + job["tipo"])
                with self.request(
                    "GET",
                    f"/documents/{doc}/file",
                    params={"token": token},
                    stream=True,
                ) as response:
                    with path.open("wb") as output:
                        for chunk in response.iter_content(65536):
                            output.write(chunk)

                def check():
                    if lost.is_set():
                        raise RuntimeError("Lease perdido; resultado descartado")

                batch = extractor.document(
                    path, os.environ.get("ENABLE_OCR", "false").lower() == "true", check
                )
                check()
                batch["token"] = token
                self.request("POST", f"/documents/{doc}/finish", json=batch)
                log.info(
                    "Documento %s: %s candidatos, %s pendencias",
                    doc,
                    len(batch["afirmacoes"]),
                    len(batch["pendencias"]),
                )
        except Exception as exc:
            log.exception("Falha no documento %s", doc)
            try:
                self.request(
                    "POST",
                    f"/documents/{doc}/fail",
                    json={"token": token, "erro": str(exc)[:2000]},
                )
            except requests.RequestException:
                log.warning("Tarefa expirada/concluida; servidor preservou estado")
        finally:
            stop.set()
            thread.join(timeout=35)
        return True


def main():
    logging.basicConfig(level=logging.INFO)
    worker = Worker()
    while True:
        try:
            if not worker.once():
                time.sleep(3)
        except requests.RequestException:
            log.exception("API indisponivel")
            time.sleep(5)


if __name__ == "__main__":
    main()
