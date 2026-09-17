"""HTTP integration against a running, disposable stack; synthetic data only."""

import os
import uuid
import io
import unittest
import requests
from reportlab.pdfgen.canvas import Canvas


class LifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = os.environ.get("API_URL", "http://127.0.0.1:8080") + "/api"
        cls.s = requests.Session()
        cls.s.auth = (
            os.environ.get("APP_USER", "professor"),
            os.environ["APP_PASSWORD"],
        )
        cls.worker = requests.Session()
        cls.worker.auth = ("worker", os.environ["WORKER_PASSWORD"])

    def call(self, path, method="GET", data=None, expected=200):
        r = self.s.request(method, self.base + path, json=data, timeout=30)
        self.assertEqual(r.status_code, expected, r.text[:1500])
        return r.json() if r.content else None

    def term(self, kind, name):
        return self.call("/vocab/" + kind, "POST", {"nome": name, "sinonimos": []})[
            "id"
        ]

    def fixture(self):
        suffix = uuid.uuid4().hex[:8]
        m = self.term("musculo", "Músculo " + suffix)
        e = self.term("exercicio", "Exercício " + suffix)
        o = self.term("objetivo", "Objetivo " + suffix)
        eq = self.term("equipamento", "Equipamento " + suffix)
        v = self.call(
            "/variations",
            "POST",
            {"nome": "Variação " + suffix, "exercicioId": e, "equipamentoId": eq},
        )["id"]
        r = self.s.post(
            self.base + "/documents",
            data={
                "titulo": "Fixture " + suffix,
                "origem": "Teste automatizado",
                "licenca": "Autoria própria",
            },
            files={
                "file": (
                    "fixture.txt",
                    ("Documento sintético " + suffix).encode(),
                    "text/plain",
                )
            },
            timeout=30,
        )
        self.assertEqual(r.status_code, 200, r.text)
        d = r.json()["id"]
        body = {
            "exercicioId": e,
            "musculoId": m,
            "objetivoId": o,
            "tipoRelacao": "ASSOCIACAO",
            "negada": False,
            "trechoOriginal": "Afirmação sintética " + suffix,
            "localizador": "paragrafo:1",
            "versaoExtrator": "manual-test",
        }
        av = self.call("/documents/" + d + "/claims", "POST", body)["id"]
        return {"m": m, "e": e, "o": o, "v": v, "d": d, "av": av, "claim": body}

    def review(self, av, action):
        return self.call(
            "/claims/" + av + "/review",
            "POST",
            {"decisao": action, "justificativa": "Revisão sintética de teste"},
        )

    def rule(
        self,
        f,
        specific=False,
        fallback=True,
        restrictions=None,
        identity=None,
        claim=None,
    ):
        return self.call(
            "/rules",
            "POST",
            {
                "regraId": identity,
                "exercicioId": None if specific else f["e"],
                "variacaoId": f["v"] if specific else None,
                "musculoId": f["m"],
                "objetivoId": f["o"],
                "condicoes": {},
                "permiteFallback": fallback and not specific,
                "justificativa": "Critérios sintéticos revisados",
                "evidencias": [
                    {
                        "afirmacaoVersaoId": claim or f["av"],
                        "justificativa": "Escopo conferido no teste",
                    }
                ],
                "restricoes": restrictions or [],
            },
        )

    def activate(self, r, expected=200):
        return self.call(
            "/rules/" + r["id"] + "/activate",
            "POST",
            {"justificativa": "Ativação explícita"},
            expected,
        )

    def query(self, f, restrictions=None):
        return self.call(
            "/queries",
            "POST",
            {
                "variacaoId": f["v"],
                "musculoId": f["m"],
                "objetivoId": f["o"],
                "contexto": {},
                "restricoes": restrictions or [],
            },
        )

    def test_lifecycle_all_statuses_and_no_auto_reactivation(self):
        f = self.fixture()
        self.assertEqual(self.query(f)["status"], "SEM_COBERTURA")
        self.review(f["av"], "APROVAR")
        generic = self.rule(f)
        self.activate(generic)
        self.assertEqual(self.query(f)["status"], "RECOMENDACAO_GENERICA")
        exact = self.rule(f, specific=True)
        self.activate(exact)
        old = self.query(f)
        self.assertEqual(old["status"], "RECOMENDACAO_ESPECIFICA")
        self.assertEqual(old["regras"][0]["id"], exact["id"])
        self.review(f["av"], "REABRIR")
        self.assertEqual(self.query(f)["status"], "REVISAO_PENDENTE")
        self.activate(exact, 409)
        self.review(f["av"], "APROVAR")
        self.assertEqual(self.query(f)["status"], "REVISAO_PENDENTE")
        self.activate(generic)
        self.activate(exact)
        self.assertEqual(self.query(f)["status"], "RECOMENDACAO_ESPECIFICA")
        # Query snapshots retain the original approved evidence state.
        history = self.call("/queries")
        snapshot = next(q for q in history if q["id"] == old["id"])
        self.assertEqual(
            snapshot["resposta"]["regras"][0]["evidencias"][0]["status"], "APROVADA"
        )
        restriction = self.term("restricao", "Restrição " + uuid.uuid4().hex)
        new = self.rule(
            f, specific=True, restrictions=[restriction], identity=exact["regra_id"]
        )
        self.activate(new)
        self.assertEqual(
            self.query(f, [restriction])["status"], "BLOQUEADO_POR_RESTRICAO"
        )

    def test_correction_is_new_version_and_old_rule_stays_suspended(self):
        f = self.fixture()
        self.review(f["av"], "APROVAR")
        rule = self.rule(f)
        self.activate(rule)
        self.review(f["av"], "REABRIR")
        correction = {**f["claim"], "incerteza": "Interpretação corrigida"}
        new = self.call(
            "/claims/" + f["av"] + "/review",
            "POST",
            {
                "decisao": "CORRIGIR",
                "justificativa": "Corrigir contexto",
                "correcao": correction,
            },
        )
        self.assertEqual(new["versao"], 2)
        self.assertEqual(new["status"], "PENDENTE")
        self.review(new["id"], "APROVAR")
        self.activate(rule, 409)
        self.assertEqual(self.query(f)["status"], "REVISAO_PENDENTE")
        updated = self.rule(f, identity=rule["regra_id"], claim=new["id"])
        self.activate(updated)
        self.assertEqual(self.query(f)["status"], "RECOMENDACAO_GENERICA")

    def test_reject_ends_review_requires_reopen(self):
        f = self.fixture()
        self.review(f["av"], "REJEITAR")
        self.call(
            "/claims/" + f["av"] + "/review",
            "POST",
            {"decisao": "APROVAR", "justificativa": "x"},
            400,
        )
        self.review(f["av"], "REABRIR")
        self.review(f["av"], "APROVAR")

    def test_pending_evidence_cannot_activate(self):
        f = self.fixture()
        r = self.rule(f)
        self.activate(r, 409)
        self.assertEqual(self.query(f)["status"], "SEM_COBERTURA")

    def test_unknown_reference_and_exclusive_target(self):
        f = self.fixture()
        self.call(
            "/queries",
            "POST",
            {
                "exercicioId": f["e"],
                "variacaoId": f["v"],
                "musculoId": f["m"],
                "objetivoId": f["o"],
            },
            400,
        )
        self.call(
            "/queries",
            "POST",
            {
                "exercicioId": str(uuid.uuid4()),
                "musculoId": f["m"],
                "objetivoId": f["o"],
            },
            404,
        )

    def test_discovery_by_muscle_and_equipment(self):
        f = self.fixture()
        self.review(f["av"], "APROVAR")
        rule = self.rule(f)
        self.activate(rule)
        result = self.call(
            "/recommendations",
            "POST",
            {
                "musculoId": f["m"],
                "objetivoId": f["o"],
                "contexto": {},
                "restricoes": [],
            },
        )
        self.assertEqual(len(result["resultados"]), 1)
        self.assertEqual(result["resultados"][0]["status"], "RECOMENDACAO_GENERICA")
        other = self.term("equipamento", "Outro equipamento " + uuid.uuid4().hex)
        result = self.call(
            "/recommendations",
            "POST",
            {"musculoId": f["m"], "objetivoId": f["o"], "equipamentos": [other]},
        )
        self.assertEqual(result["resultados"], [])

    def test_auth_and_worker_cannot_review(self):
        self.assertEqual(
            requests.get(self.base + "/dictionary", timeout=10).status_code, 401
        )
        self.assertEqual(
            self.worker.get(self.base + "/rules", timeout=10).status_code, 403
        )
        self.assertEqual(
            self.s.post(self.base + "/worker/claim", timeout=10).status_code, 403
        )

    def test_upload_deduplicates_and_rejects_fake_pdf(self):
        data = {
            "titulo": "Documento duplicado",
            "origem": "Teste",
            "licenca": "Própria",
        }
        content = uuid.uuid4().hex.encode()
        ids = []
        for _ in range(2):
            r = self.s.post(
                self.base + "/documents",
                data=data,
                files={"file": ("test.txt", content)},
                timeout=10,
            )
            self.assertEqual(r.status_code, 200)
            ids.append(r.json()["id"])
        self.assertEqual(ids[0], ids[1])
        r = self.s.post(
            self.base + "/documents",
            data=data,
            files={"file": ("fake.pdf", b"not a pdf")},
            timeout=10,
        )
        self.assertEqual(r.status_code, 400)

    def test_worker_pipeline_real_pdf_bytes_synthetic_content(self):
        # Drain existing pending fixtures through the actual Python worker.
        from scrawler_extractor.worker import Worker

        self.term("exercicio", "Movimento Alfa")
        self.term("musculo", "Músculo Beta")
        buffer = io.BytesIO()
        c = Canvas(buffer)
        for _ in range(3):
            c.drawString(50, 760, "Movimento Alfa ativa o Músculo Beta.")
            c.showPage()
        c.save()
        content = buffer.getvalue()
        r = self.s.post(
            self.base + "/documents",
            data={
                "titulo": "PDF sintético de integração",
                "origem": "Fixture de teste",
                "licenca": "Autoria própria",
            },
            files={"file": ("fixture.pdf", content)},
            timeout=10,
        )
        self.assertEqual(r.status_code, 200)
        did = r.json()["id"]
        worker = Worker()
        for _ in range(30):
            if not worker.once():
                break
        rows = self.call("/documents")
        doc = next(d for d in rows if d["id"] == did)
        self.assertEqual(doc["status"], "CONCLUIDO")
        claims = [a for a in self.call("/claims") if a["documento_id"] == did]
        self.assertEqual(len(claims), 3)
        self.assertTrue(all(a["status"] == "PENDENTE" for a in claims))
        # Completed task cannot be replayed to duplicate claims.
        response = self.worker.post(
            self.base + f"/worker/documents/{did}/finish",
            json={
                "token": doc["tarefa_token"],
                "versaoExtrator": "x",
                "afirmacoes": [],
                "pendencias": [],
            },
            timeout=10,
        )
        self.assertEqual(response.status_code, 400)


if __name__ == "__main__":
    unittest.main(verbosity=2)
