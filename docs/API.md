# Contrato operacional

Base `/api`; autenticação HTTP Basic (credenciais somente em memória no frontend). `professor` é o usuário padrão configurável; `worker` tem acesso apenas a `/api/worker/**`. Basic deve trafegar em TLS se o serviço deixar o ambiente local.

## Variáveis

| Variável | Uso |
|---|---|
| DB_URL | JDBC PostgreSQL, padrão `jdbc:postgresql://localhost:5432/scrawler` |
| DB_USER / DB_PASSWORD | Conta da aplicação; senha obrigatória |
| APP_USER / APP_PASSWORD | Operador; senha com pelo menos 12 caracteres |
| WORKER_PASSWORD | Conta técnica; senha com pelo menos 12 caracteres |
| DOCUMENT_DIR | Diretório dos arquivos; padrão `./data/documents` |
| SERVER_ADDRESS | Bind da API; local por padrão, `0.0.0.0` dentro do container |
| API_URL | URL do backend para o worker |
| SPACY_MODEL | Nome do modelo instalado, ou vazio para baseline por dicionário |
| ENABLE_OCR | `true` permite OCR; falso por padrão |

## Endpoints do operador

| Método e caminho | Função |
|---|---|
| GET `/health` | Verificar aplicação e conexão |
| GET/POST `/vocab/{kind}` | Listar/criar musculo, exercicio, equipamento, objetivo, restricao |
| PUT/DELETE `/vocab/{kind}/{id}` | Atualizar/remover termo; FK impede remover referências usadas |
| GET `/dictionary` | Exportar vocabulário completo para reconhecimento |
| GET/POST `/variations` | Listar/cadastrar variações; identidade da execução é preservada |
| GET/POST `/documents` | Listar/enviar documento multipart: file, titulo, origem, licenca |
| GET `/documents/{id}/file` | Recuperar original autenticado |
| GET `/documents/{id}/pending` | Pendências da extração |
| POST `/documents/{id}/retry` | Repetir documento que falhou |
| POST `/documents/{id}/claims` | Registrar afirmação manual a partir de pendência |
| GET `/claims` | Listar versões de afirmações |
| POST `/claims/{id}/review` | Aprovar, rejeitar, reabrir ou corrigir versão |
| GET `/claims/{id}/reviews` | Histórico da versão |
| GET/POST `/rules` | Listar/criar versões de regras em rascunho |
| POST `/rules/{id}/activate` | Aprovação explícita e ativação transacional |
| POST `/rules/{id}/archive` | Arquivar com justificativa |
| GET `/rules/{id}/evidence` | Evidências vinculadas |
| GET `/rules/{id}/events` | Histórico de ativação/suspensão |
| POST `/recommendations` | Descobrir opções por músculo/objetivo, com equipamentos opcionais |
| POST `/queries` | Resolver consulta e guardar snapshot |
| GET `/queries` | Consultas históricas |
| GET `/search?q=...` | Busca textual em trechos de afirmações, não ranking científico |

Listagens aceitam `offset`, 200 registros por página (busca: 100). `/dictionary` exporta tudo para o protótipo; sua escala precisa ser medida.

## Corpos principais

Termo:
```json
{"nome":"nome canônico","sinonimos":["nome alternativo"]}
```

Afirmação (IDs devem existir; exatamente um dos dois alvos):
```json
{"exercicioId":"UUID","variacaoId":null,"musculoId":"UUID","objetivoId":null,"restricaoId":null,"tipoRelacao":"ASSOCIACAO","negada":false,"condicao":"","incerteza":"","trechoOriginal":"Trecho conferido no documento","localizador":"pagina:12:coluna:1","versaoExtrator":"manual-1"}
```
Tipos: ASSOCIACAO, COMPARACAO, CONTRAINDICACAO. Nesta primeira versão, somente associações positivas aprovadas sustentam diretamente a seleção de exercício; contraindicações/comparações ficam registradas para revisão. Restrições de regra são selecionadas e justificadas pelo revisor.

Revisão:
```json
{"decisao":"REABRIR","justificativa":"Motivo registrado"}
```
Para `CORRIGIR`, adicionar `correcao` com corpo completo de Afirmação. Trecho original e localizador são preservados. Para corrigir a própria transcrição/fonte, registrar nova afirmação e arquivar/reavaliar as regras afetadas, preservando o histórico.

Regra:
```json
{"regraId":null,"exercicioId":"UUID","variacaoId":null,"musculoId":"UUID","objetivoId":"UUID","condicoes":{"nivel":"iniciante"},"permiteFallback":true,"justificativa":"Por que a evidência sustenta os critérios","evidencias":[{"afirmacaoVersaoId":"UUID","justificativa":"Compatibilidade de escopo conferida"}],"restricoes":[]}
```
`regraId=null` cria identidade; informar identidade existente cria versão seguinte. Conteúdo de versões é imutável. Ativação da versão nova arquiva versões ativas/suspensas da mesma identidade, na mesma transação; uma falha desfaz tudo.

Ativar/arquivar:
```json
{"justificativa":"Decisão explícita após conferir as evidências"}
```

Consulta:
```json
{"exercicioId":null,"variacaoId":"UUID","musculoId":"UUID","objetivoId":"UUID","contexto":{"nivel":"iniciante"},"restricoes":[]}
```
Resposta: `id`, `status`, `explicacao`, `regras` com versões de evidências e fontes. Resultados: RECOMENDACAO_ESPECIFICA, RECOMENDACAO_GENERICA, BLOQUEADO_POR_RESTRICAO, SEM_COBERTURA, REVISAO_PENDENTE.

Não informar uma restrição não prova sua ausência; a consulta representa apenas os dados fornecidos pelo operador. Campos de condição não informados não satisfazem condições obrigatórias.

## Worker

POST `/worker/claim` retorna próximo documento e token de tarefa (ou `{}`). Lease de cinco minutos renovado a cada 45 segundos. GET `/worker/dictionary`; GET `/worker/documents/{id}/file?token=UUID`; POST `/heartbeat?token=UUID`; POST `/finish` com token, versaoExtrator, afirmacoes e pendencias; POST `/fail` com token e erro. Os três últimos caminhos são relativos a `/worker/documents/{id}`.

Conclusão grava todos os candidatos/pendências e o estado CONCLUIDO na mesma transação. Token vencido, repetido ou substituído é rejeitado. Uma falha de conexão depois de commit não duplica resultados.

## Erros

400: entrada/transição inválida; 401: autenticação; 403: papel não autorizado; 404: referência inexistente; 409: integridade (duplicidade, FK, evidência incompatível ou estado). Respostas não expõem SQL, credenciais nem stack trace.

Descoberta: corpo com `musculoId`, `objetivoId`, `contexto`, `restricoes` e `equipamentos` (lista opcional de UUIDs). Retorna `resultados` por execução, cada um com seu status. Limites: 100 famílias/200 variações por requisição; ausência de itens significa sem cobertura.
