from collections import defaultdict
from pathlib import Path
import re
import spacy
from spacy.matcher import PhraseMatcher
from .reader import read_blocks, Block
from . import __version__

TRIGGERS = {
    "ativa",
    "ativam",
    "ativar",
    "trabalha",
    "trabalham",
    "envolve",
    "envolvem",
    "recruta",
    "recrutam",
    "fortalece",
    "fortalecem",
}
NEGATION = re.compile(r"\b(não|nao|nunca|jamais|sem)\b", re.I)
UNCERTAINTY = re.compile(
    r"\b(pode|podem|talvez|possivelmente|sugere|sugerem|insuficiente|incerto)\b", re.I
)
CONDITIONS = re.compile(
    r"\b(se|quando|desde que|em participantes|em adultos|em idosos|durante|sob)\b", re.I
)
COMPARISON = re.compile(
    r"\b(superior|inferior|comparado|comparação|versus|maior|menor)\b", re.I
)


class Extractor:
    def __init__(self, dictionary: dict, model: str | None = None):
        # Explicit baseline mode; never pretend a trained parser is available.
        self.nlp = spacy.load(model) if model else spacy.blank("pt")
        if (
            "parser" not in self.nlp.pipe_names
            and "sentencizer" not in self.nlp.pipe_names
        ):
            self.nlp.add_pipe("sentencizer")
        self.version = f"{__version__}:{model or 'dicionario-pt'}:{self.nlp.meta.get('version','0')}"
        self.matcher = PhraseMatcher(self.nlp.vocab, attr="LOWER")
        self.mapping = {}
        self.variations = {v["id"]: v for v in dictionary.get("variacao", [])}
        grouped = defaultdict(set)
        for kind, entries in dictionary.items():
            for entry in entries:
                for phrase in [entry["nome"], *entry.get("sinonimos", [])]:
                    if phrase.strip():
                        grouped[phrase.casefold()].add((kind, entry["id"]))
        for i, (phrase, targets) in enumerate(grouped.items()):
            label = f"term_{i}"
            self.matcher.add(label, [self.nlp.make_doc(phrase)])
            self.mapping[self.nlp.vocab.strings[label]] = targets

    def block(self, block: Block):
        claims, pending = [], []

        def defer(text, reason, locator=block.locator):
            pending.append(
                dict(localizador=locator, trecho=text[:20000], motivo=reason)
            )

        if block.warning:
            defer(block.text, block.warning)
            return claims, pending
        # Bounded NLP chunks; splitting at paragraph boundary preferred upstream.
        if len(block.text) > 100000:
            defer(block.text, "BLOCO_EXCEDE_LIMITE")
            return claims, pending
        doc = self.nlp(block.text)
        matches = self.matcher(doc)
        for sentence in doc.sents:
            text = sentence.text.strip()
            if not text:
                continue
            locator = f"{block.locator}:chars:{sentence.start_char}-{sentence.end_char}"
            spans = [
                (a, b, self.mapping[m])
                for m, a, b in matches
                if a >= sentence.start and b <= sentence.end
            ]
            # Longest phrase wins over a contained generic term.
            spans = [
                (a, b, t)
                for a, b, t in spans
                if not any(x <= a and y >= b and (x < a or y > b) for x, y, _ in spans)
            ]
            entities = defaultdict(set)
            for _, _, targets in spans:
                if len(targets) > 1:
                    defer(text, "SINONIMO_AMBIGUO", locator)
                    break
                for kind, identity in targets:
                    entities[kind].add(identity)
            else:
                exercises = entities["exercicio"]
                variations = entities["variacao"]
                if (
                    len(exercises) + len(variations) != 1
                    or len(entities["musculo"]) != 1
                ):
                    if spans:
                        defer(text, "ALVOS_AUSENTES_OU_MULTIPLOS", locator)
                    else:
                        defer(text, "SEM_ENTIDADES_RECONHECIDAS", locator)
                    continue
                if any(
                    len(entities[k]) > 1
                    for k in ["objetivo", "restricao", "equipamento"]
                ):
                    defer(text, "CONTEXTO_AMBIGUO", locator)
                    continue
                if COMPARISON.search(text):
                    defer(text, "COMPARACAO_REQUER_INTERPRETACAO", locator)
                    continue
                triggers = [
                    t
                    for t in sentence
                    if t.lower_ in TRIGGERS
                    or t.lemma_
                    in {"ativar", "trabalhar", "envolver", "recrutar", "fortalecer"}
                ]
                if not triggers:
                    defer(text, "RELACAO_NAO_SUPORTADA", locator)
                    continue
                if "parser" in self.nlp.pipe_names:
                    target_spans = [
                        (a, b)
                        for a, b, targets in spans
                        if any(
                            k in {"exercicio", "variacao", "musculo"}
                            for k, _ in targets
                        )
                    ]

                    # Both argument heads must attach to the same trigger without
                    # traversing another verb. Ambiguous syntax is left for review.
                    def attaches(a, b, verb):
                        head = doc[a:b].root
                        for parent in [head, *head.ancestors]:
                            if parent.i == verb.i:
                                return True
                            if parent.pos_ in {"VERB", "AUX"}:
                                return False
                        return False

                    if not any(
                        all(attaches(a, b, t) for a, b in target_spans)
                        for t in triggers
                    ):
                        defer(text, "DEPENDENCIA_NAO_CONFIRMADA", locator)
                        continue
                # Equipment alone never manufactures an execution variation.
                if entities["equipamento"] and not variations:
                    defer(text, "EQUIPAMENTO_SEM_VARIACAO_IDENTIFICADA", locator)
                    continue
                if variations and entities["equipamento"]:
                    v = self.variations[next(iter(variations))]
                    if v["equipamento_id"] not in entities["equipamento"]:
                        defer(text, "EQUIPAMENTO_DIVERGENTE", locator)
                        continue
                if len(text) > 20000:
                    defer(text, "FRASE_EXCEDE_LIMITE", locator)
                    continue
                condition = CONDITIONS.search(text)
                uncertain = UNCERTAINTY.search(text)
                first = lambda key: next(iter(entities[key]), None)
                claims.append(
                    dict(
                        exercicioId=first("exercicio"),
                        variacaoId=first("variacao"),
                        musculoId=first("musculo"),
                        objetivoId=first("objetivo"),
                        restricaoId=first("restricao"),
                        tipoRelacao="ASSOCIACAO",
                        negada=bool(NEGATION.search(text)),
                        condicao=text[condition.start() :] if condition else "",
                        incerteza=text if uncertain else "",
                        trechoOriginal=text,
                        localizador=locator,
                        versaoExtrator=self.version,
                    )
                )
        return claims, pending

    def document(self, path: Path, enable_ocr=False, heartbeat=None):
        claims, pending = [], []
        for block in read_blocks(path, enable_ocr):
            if heartbeat:
                heartbeat()
            c, p = self.block(block)
            claims.extend(c)
            pending.extend(p)
            if len(claims) > 50000 or len(pending) > 50000:
                raise ValueError(
                    "Documento excede limite de candidatos; divida o acervo"
                )
        return dict(versaoExtrator=self.version, afirmacoes=claims, pendencias=pending)
