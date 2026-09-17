"""Conservative page extraction; complex layouts become visible review items."""

from dataclasses import dataclass
from pathlib import Path
import subprocess
import tempfile
import shutil
import pdfplumber


@dataclass(frozen=True)
class Block:
    text: str
    locator: str
    warning: str = ""


def ocr_page(path: Path, page: int) -> str:
    if not shutil.which("pdftoppm") or not shutil.which("tesseract"):
        raise RuntimeError("OCR exige pdftoppm e tesseract com idioma por instalados")
    with tempfile.TemporaryDirectory() as directory:
        prefix = str(Path(directory) / "page")
        subprocess.run(
            [
                "pdftoppm",
                "-f",
                str(page),
                "-l",
                str(page),
                "-singlefile",
                "-r",
                "150",
                "-png",
                str(path),
                prefix,
            ],
            check=True,
            timeout=120,
            capture_output=True,
        )
        result = subprocess.run(
            ["tesseract", prefix + ".png", "stdout", "-l", "por"],
            check=True,
            timeout=120,
            capture_output=True,
            text=True,
        )
        return result.stdout


def read_blocks(path: Path, enable_ocr: bool = False):
    if path.suffix.lower() == ".txt":
        text = path.read_text(encoding="utf-8")
        for number, paragraph in enumerate(text.split("\n\n"), 1):
            if paragraph.strip():
                yield Block(paragraph.strip(), f"paragrafo:{number}")
        return
    if path.suffix.lower() != ".pdf":
        raise ValueError("Formato suportado: PDF ou TXT UTF-8")
    with pdfplumber.open(path) as pdf:
        if len(pdf.pages) > 2000:
            raise ValueError("Limite do prototipo: 2000 paginas por documento")
        for number, page in enumerate(pdf.pages, 1):
            words = page.extract_words()
            if len(words) < 4:
                if enable_ocr:
                    yield Block(
                        ocr_page(path, number),
                        f"pagina:{number}:ocr",
                        "OCR_REQUER_CONFERENCIA",
                    )
                else:
                    yield Block(
                        "[Pagina sem texto suficiente]",
                        f"pagina:{number}",
                        "OCR_NECESSARIO",
                    )
                continue
            tables = page.find_tables()
            for index, table in enumerate(tables):
                yield Block(
                    str(table.extract()),
                    f"pagina:{number}:tabela:{index+1}",
                    "TABELA_REQUER_REVISAO",
                )

            def in_table(word):
                return any(
                    t.bbox[0] <= word["x0"] <= t.bbox[2]
                    and t.bbox[1] <= word["top"] <= t.bbox[3]
                    for t in tables
                )

            body = [w for w in words if not in_table(w)]
            notes = [w for w in body if w["top"] > page.height * 0.91]
            if notes:
                yield Block(
                    " ".join(w["text"] for w in notes),
                    f"pagina:{number}:rodape",
                    "RODAPE_REQUER_REVISAO",
                )
            body = [w for w in body if w not in notes]
            if not body:
                continue
            # A clear empty central gutter supports two columns. Otherwise keep
            # ordinary reading order; no claim of solving arbitrary page layouts.
            middle = page.width / 2
            crossing = [w for w in body if w["x0"] < middle < w["x1"]]
            left = [w for w in body if w["x1"] < middle - 5]
            right = [w for w in body if w["x0"] > middle + 5]
            columns = (
                [left, right]
                if len(left) > 10
                and len(right) > 10
                and not crossing
                and len(left) + len(right) == len(body)
                else [body]
            )
            for index, column in enumerate(columns, 1):
                ordered = sorted(column, key=lambda w: (round(w["top"] / 4), w["x0"]))
                text = " ".join(w["text"] for w in ordered)
                if text.strip():
                    yield Block(text, f"pagina:{number}:coluna:{index}")
            page.close()
