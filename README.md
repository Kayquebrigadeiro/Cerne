# Scrawler — núcleo de conhecimento

Protótipo local de ingestão documental, revisão humana e consulta de regras de exercícios. Java + Spring Boot, PostgreSQL, Python + spaCy e React + TypeScript. Nenhuma API de LLM; nenhuma recomendação é criada automaticamente pelo extrator.

## Iniciar com Docker

Requisitos: Docker Engine/Desktop com Compose e Python 3 para gerar as credenciais. A primeira construção baixa dependências e o modelo de português; a execução posterior não consulta serviços de IA.

```bash
python3 scripts/setup_env.py
docker compose up --build
```

Abra http://localhost:5173. Consulte `APP_USER` e `APP_PASSWORD` no arquivo `.env` gerado. O banco não expõe porta; frontend e API são publicados somente em localhost. Os documentos e o banco persistem em volumes Docker. `docker compose down` preserva esses volumes.

O protótipo usa um operador revisor e uma conta técnica separada para o worker. Não é uma implantação pública: multiusuário, TLS e política de acesso institucional precisam de implementação antes disso.

## Primeiro fluxo

1. Cadastre músculos, exercícios, equipamentos, objetivos, restrições e sinônimos em **Vocabulário**.
2. Cadastre uma **Variação** quando houver equipamento/execução identificável. O par exercício/equipamento não é único.
3. Envie PDF ou TXT em **Documentos**, registrando a origem e a permissão de uso. Arquivos idênticos são deduplicados por SHA-256.
4. O worker processa a fila, gera afirmações pendentes e registra trechos não interpretados como pendências. Nenhum documento baixa conteúdo externo por iniciativa do sistema.
5. Em **Afirmações**, confira a fonte e aprove, rejeite ou corrija. Correção gera nova versão; para alterar uma versão aprovada, reabra primeiro.
6. Em **Regras**, crie um rascunho com IDs das versões de afirmações aprovadas, músculo, objetivo, condições e justificativas. Ative explicitamente.
7. Em **Consulta**, informe músculo e objetivo para descobrir opções. Para conferir uma execução, selecione exatamente um exercício ou variação. Acrescente condições e restrições conhecidas.
8. Reabra uma evidência aprovada e verifique a suspensão das regras dependentes. Reaprovar a evidência não reativa essas regras.

Não há dados científicos pré-aprovados. As fixtures dos testes são sintéticas e não devem fundamentar recomendações reais.

## O que está implementado

- Migração PostgreSQL com versões, exclusividade dos alvos, busca textual, histórico e proteção contra alteração de conteúdo histórico.
- API autenticada, vocabulário com sinônimos, variações, upload, fila com lease, worker e reprocessamento de falhas.
- Extração em português por vocabulário e padrões; análise de dependências quando `SPACY_MODEL=pt_core_news_sm`.
- Revisão, nova versão corrigida, regras em rascunho, validação de ativação e suspensão transacional.
- Cinco resultados explícitos; restrição e suspensão têm precedência sobre seleção/fallback.
- Console React para operar e conferir o fluxo; fontes e respostas ficam disponíveis para inspeção.

## Limites que permanecem

- O acervo real do professor ainda não foi recebido. Precisão/recall científicos, grande volume e qualidade em PDFs heterogêneos **não estão validados**.
- A extração é conservadora: uma relação simples com um alvo e um músculo por frase. Comparações, múltiplos alvos, sinônimos ambíguos, tabelas e notas viram pendências. Não existe interpretação universal de texto científico.
- A separação de duas colunas é uma heurística para páginas com corredor central claro. Diagramações complexas precisam de conferência.
- OCR é opcional (`ENABLE_OCR=true`) e seu resultado vira pendência de conferência. Não é promovido automaticamente a afirmação.
- O motor lista regras aplicáveis em ordem estável, **sem ranking de eficácia**. Definir "melhor" depende de critérios científicos ainda a validar com o professor.
- Condições do motor são mapas de igualdade textual (ex.: `{"nivel":"iniciante"}`). Não há interpretação automática de condição em prosa nem comparação numérica/ranges.
- Uma variação usa um equipamento; conjuntos exigirão modelagem futura.
- Mapa anatômico/SPA visual avançada (fase 7) permanece adiado conforme o acordo: somente depois da validação do núcleo com documentos reais.
- A transação usa um bloqueio global do domínio para garantir ordem consistente no protótipo. Favorece correção, limita paralelismo; medir antes de otimizar.
- O usuário do banco é o dono da aplicação. A auditoria de ator pressupõe acesso pelas APIs; um administrador SQL continua tendo poderes administrativos.

## Desenvolvimento sem Docker

Java JDK 17+, Maven 3.9+, PostgreSQL 17+, Python 3.11/3.12 e Node 22. Configure as variáveis descritas em `docs/API.md`.

```bash
cd backend
mvn verify
mvn spring-boot:run
```

Em outro terminal, a partir da raiz:

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -e './extractor[test]'
pip install https://github.com/explosion/spacy-models/releases/download/pt_core_news_sm-3.8.0/pt_core_news_sm-3.8.0-py3-none-any.whl
SPACY_MODEL=pt_core_news_sm scrawler-worker
```

E para a interface:

```bash
cd frontend
npm ci
npm run dev
```

O modo sem `SPACY_MODEL` usa somente dicionário/tokenização, identificado explicitamente na versão do extrator. Não é substituição silenciosa por um modelo treinado.

## Testes

```bash
# Java: resolvedor puro
cd backend && mvn verify

# Python: extração, negação, contexto, abstinência e PDF sintético multipágina
cd extractor && python -m pytest -q

# SQL: engine PostgreSQL via PGlite (não substitui teste de concorrência nativa)
npm ci
npm run test:db

# Integração HTTP: use uma instância descartável; cria registros de teste
# Exportar APP_USER, APP_PASSWORD e WORKER_PASSWORD previamente.
PYTHONPATH=extractor python tests/test_api.py
```

Para repetir a integração, use um banco descartável novo. Consulte `docs/VALIDACAO.md` para saber quais verificações foram realmente executadas nesta entrega.

## Organização

- `backend/`: API, domínio, Flyway e testes Java.
- `extractor/`: leitor de documentos, extração, worker e testes Python.
- `frontend/`: console React.
- `tests/`: verificações SQL e de integração HTTP.
- `docs/`: contrato da API, decisões, limitações e validação.
- `scripts/`: configuração local e avaliação de corpus.

A licença do código autoral do projeto permanece a decidir pelo responsável. Licenças de bibliotecas, modelos e documentos são independentes; consulte `docs/DEPENDENCIAS.md`.
