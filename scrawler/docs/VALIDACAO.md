# Relatório de validação — protótipo 0.1.0

## Executado nesta entrega

| Camada | Resultado | Cobertura principal |
|---|---|---|
| PostgreSQL via PGlite 0.5.8 / engine 18.3 | 13 testes passaram | Migração, XOR exercício/variação, objetivo obrigatório, ativação, suspensão, rollback, imutabilidade, versionamento, busca textual |
| Java / Maven / JDK 17 | 12 testes passaram; pacote compilado | Cinco resultados, precedência, fallback autorizado, restrições, suspensão relevante, contexto obrigatório |
| Python / pytest | 16 testes passaram | Entidades, ambiguidade, negação, condições, incerteza, comparação recusada, PDF sintético de 60 páginas, página sem texto, duas colunas e modelo português |
| API Spring Boot + JDBC + PostgreSQL via PGlite + worker Python | 9 testes passaram | Autenticação/papéis, CRUD, documento, extração, revisão, correção, regra, cinco resultados, descoberta por músculo/equipamento, deduplicação e replay recusado |
| Frontend TypeScript / Vite | Build passou | Verificação de tipos e empacotamento |

**Total: 50 testes automatizados aprovados**, além do build frontend. Esse número mede os casos implementados, não uma taxa de acerto científico.

A integração HTTP usou o protocolo PostgreSQL de PGlite com uma conexão, `preferQueryMode=simple` e `prepareThreshold=0`, pois a emulação de prepared statements não equivale ao servidor nativo. A migração foi aplicada antes de iniciar a API e Flyway foi desabilitado somente nessa execução de integração. No produto, Flyway permanece habilitado.

## Falhas encontradas e corrigidas

- Configuração BCrypt sem prefixo de algoritmo no Spring Security: corrigida e validada com requisições autenticadas e testes de papéis.
- Dependência `click` ausente no ambiente Python: adicionada explicitamente ao projeto.
- Aviso de import CSS vazio: removido; build limpo.
- Limitações do ambiente de teste: ausência de PostgreSQL/Docker locais e diferenças do protocolo PGlite foram tratadas no harness de teste, sem alterar a configuração do produto para fingir equivalência.

## Ainda não validado

1. Corpus científico real, qualidade dos rótulos e precisão/recall por tipo de relação.
2. PDFs escaneados reais, OCR em português e layouts complexos além das fixtures.
3. Throughput, memória e tempo em acervo volumoso ou no computador do usuário.
4. Concorrência entre revisão, ativação e consulta em PostgreSQL nativo, com sessões independentes. A implementação serializa o domínio, mas PGlite não comprova esse comportamento multissessão.
5. Construção e execução efetivas das imagens Docker neste ambiente; Docker não estava disponível.
6. Ranking de eficácia, decisões científicas e validade das recomendações para pessoas reais.
7. Verificação interativa/visual em navegador: o Chromium não estava disponível e sua instalação falhou neste ambiente. O frontend passou pelo build, mas esse teste de navegador não foi concluído.
8. Mapa anatômico: adiado até o núcleo ser validado com o professor, conforme combinado.

## Próxima validação com os documentos

- Selecionar documentos com formatos variados e registrar origem/permissão.
- Separar documentos de desenvolvimento e avaliação antes de ajustar padrões.
- Anotar afirmações esperadas com exercício/variação, músculo, negação, contexto e localizador.
- Rodar `scripts/evaluate_corpus.py` com o catálogo exportado por `/api/dictionary`.
- Inspecionar falsos positivos, omissões e pendências, priorizando erros que mudariam a decisão.
- Só depois definir critérios de aceitação com o professor e ampliar o acervo.

Todos os PDFs produzidos nos testes são sintéticos. Nenhum resultado foi apresentado como validação científica em documentação real.
