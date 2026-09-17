from scrawler_extractor.extract import Extractor
from scrawler_extractor.reader import Block, read_blocks
from reportlab.pdfgen.canvas import Canvas
import pytest


@pytest.fixture
def dictionary():
    return {
        "exercicio": [{"id": "e", "nome": "Movimento Alfa", "sinonimos": ["Alfa"]}],
        "musculo": [{"id": "m", "nome": "Músculo Beta", "sinonimos": []}],
        "equipamento": [{"id": "q", "nome": "Aparelho Gama", "sinonimos": []}],
        "objetivo": [],
        "variacao": [],
        "restricao": [],
    }


def test_association_and_exact_quote(dictionary):
    text = "Movimento Alfa ativa o Músculo Beta."
    claims, pending = Extractor(dictionary).block(Block(text, "pagina:1"))
    assert len(claims) == 1 and not pending and claims[0]["trechoOriginal"] == text
    assert claims[0]["exercicioId"] == "e" and claims[0]["variacaoId"] is None


@pytest.mark.parametrize("word", ["não", "nunca", "jamais"])
def test_negation(dictionary, word):
    c, _ = Extractor(dictionary).block(
        Block(f"Movimento Alfa {word} ativa o Músculo Beta.", "p:1")
    )
    assert c[0]["negada"]


def test_uncertainty_condition(dictionary):
    c, _ = Extractor(dictionary).block(
        Block(
            "Movimento Alfa pode ativar o Músculo Beta quando executado sob condição experimental.",
            "p:1",
        )
    )
    assert c[0]["incerteza"] and c[0]["condicao"]


def test_equipment_not_invented(dictionary):
    c, p = Extractor(dictionary).block(
        Block("Movimento Alfa com Aparelho Gama ativa o Músculo Beta.", "p:1")
    )
    assert not c and p[0]["motivo"] == "EQUIPAMENTO_SEM_VARIACAO_IDENTIFICADA"


def test_unknown_is_pending(dictionary):
    c, p = Extractor(dictionary).block(
        Block("Uma frase sem termos do catálogo.", "p:1")
    )
    assert not c and p[0]["motivo"] == "SEM_ENTIDADES_RECONHECIDAS"


def test_ambiguous_alias(dictionary):
    dictionary["exercicio"].append(
        {"id": "other", "nome": "Outro", "sinonimos": ["Alfa"]}
    )
    c, p = Extractor(dictionary).block(Block("Alfa ativa o Músculo Beta.", "p:1"))
    assert not c and p[0]["motivo"] == "SINONIMO_AMBIGUO"


def test_multi_targets_not_cross_joined(dictionary):
    dictionary["musculo"].append({"id": "m2", "nome": "Músculo Delta", "sinonimos": []})
    c, p = Extractor(dictionary).block(
        Block("Alfa ativa o Músculo Beta e o Músculo Delta.", "p:1")
    )
    assert not c and p[0]["motivo"] == "ALVOS_AUSENTES_OU_MULTIPLOS"


def test_variation_wins_over_contained_exercise(dictionary):
    dictionary["variacao"] = [
        {
            "id": "v",
            "nome": "Movimento Alfa sentado",
            "sinonimos": [],
            "equipamento_id": "q",
            "exercicio_id": "e",
        }
    ]
    c, p = Extractor(dictionary).block(
        Block("Movimento Alfa sentado ativa o Músculo Beta.", "p:1")
    )
    assert c[0]["variacaoId"] == "v" and c[0]["exercicioId"] is None


def test_comparison_abstains(dictionary):
    c, p = Extractor(dictionary).block(
        Block("Movimento Alfa ativa o Músculo Beta com resultado superior.", "p:1")
    )
    assert not c and p[0]["motivo"] == "COMPARACAO_REQUER_INTERPRETACAO"


def test_multi_page_pdf(dictionary, tmp_path):
    # Synthetic integration fixture, explicitly NOT scientific validation.
    path = tmp_path / "synthetic.pdf"
    canvas = Canvas(str(path))
    for page in range(60):
        canvas.drawString(50, 760, "Movimento Alfa ativa o Músculo Beta.")
        canvas.showPage()
    canvas.save()
    result = Extractor(dictionary).document(path)
    assert len(result["afirmacoes"]) == 60
    assert result["afirmacoes"][-1]["localizador"].startswith("pagina:60:")


def test_blank_scanned_page_flagged(tmp_path):
    path = tmp_path / "blank.pdf"
    c = Canvas(str(path))
    c.showPage()
    c.save()
    blocks = list(read_blocks(path))
    assert blocks[0].warning == "OCR_NECESSARIO"


def test_warned_block_cannot_become_claim(dictionary):
    c, p = Extractor(dictionary).block(
        Block("Alfa ativa o Músculo Beta.", "p:1", "TABELA_REQUER_REVISAO")
    )
    assert not c and p


def test_trained_portuguese_parser(dictionary):
    import spacy

    if not spacy.util.is_package("pt_core_news_sm"):
        pytest.skip("Modelo opcional não instalado neste ambiente")
    extractor = Extractor(dictionary, "pt_core_news_sm")
    c, p = extractor.block(Block("Movimento Alfa ativa o Músculo Beta.", "p:1"))
    assert len(c) == 1 and not p and "pt_core_news_sm" in c[0]["versaoExtrator"]
    c, p = extractor.block(Block("Movimento Alfa não ativa o Músculo Beta.", "p:1"))
    assert c[0]["negada"]


def test_two_column_pdf_not_interleaved(dictionary, tmp_path):
    path = tmp_path / "columns.pdf"
    c = Canvas(str(path))
    for x in [35, 330]:
        c.drawString(x, 760, "Movimento Alfa ativa o")
        c.drawString(x, 745, "Músculo Beta. Movimento Alfa")
        c.drawString(x, 730, "ativa o Músculo Beta.")
    c.save()
    result = Extractor(dictionary).document(path)
    assert len(result["afirmacoes"]) == 4
    assert any("coluna:2" in a["localizador"] for a in result["afirmacoes"])
