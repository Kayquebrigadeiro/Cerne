# Dependências e fontes técnicas

O projeto não inclui DptOIE ou Stanford CoreNLP. Nenhum código dessas ferramentas foi incorporado.

- Spring Boot: https://spring.io/projects/spring-boot/ — dependências resolvidas pelo Maven; preserve avisos das bibliotecas distribuídas.
- PostgreSQL: https://www.postgresql.org/docs/current/ — banco do produto; PGlite é somente ferramenta de validação local.
- spaCy: https://spacy.io/usage/rule-based-matching — correspondência por termos e regras. Modelo português: https://spacy.io/models/pt . O modelo é jornalístico, não especializado em exercícios.
- pdfplumber: https://github.com/jsvine/pdfplumber — extração de páginas e tabelas.
- Tesseract: https://github.com/tesseract-ocr/tesseract — OCR opcional.
- React: https://react.dev/ — interface.
- PGlite: https://github.com/electric-sql/pglite — PostgreSQL em WebAssembly para testar SQL. Não substitui validação de concorrência com PostgreSQL nativo.

Mantenha a licença do modelo `pt_core_news_sm` e seus metadados ao redistribuir. A licença do framework não substitui a do modelo nem a dos documentos. Os PDFs do usuário não estão incluídos nesta entrega. A seleção final de licença do código autoral cabe ao responsável pelo projeto.

Versões diretas estão fixadas em pom.xml, pyproject.toml, package.json e locks npm. As dependências transitivas Python ainda precisam de lock por plataforma para reprodutibilidade binária estrita. Imagens Docker usam tags e ainda não estão fixadas por digest.
