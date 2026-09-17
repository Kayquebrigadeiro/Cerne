# Decisões implementadas

1. Uma afirmação representa a interpretação de um trecho; uma regra é uma decisão revisada, com escopo e evidências. Não há promoção automática.
2. Afirmações e regras têm identidades e versões. A evidência vincula IDs das versões. Conteúdo histórico é imutável; estados podem mudar com auditoria.
3. Afirmação/regra exige exatamente um alvo: exercício ou variação. Variação tem um equipamento, e o par não é único.
4. Objetivo é obrigatório na regra. Evidência sem objetivo pode ser vinculada somente com avaliação humana explícita de escopo. Evidência com objetivo divergente é rejeitada na ativação.
5. O motor resolve cada exercício/variação por vez. A descoberta por músculo/objetivo enumera famílias com regras e avalia suas execuções; não calcula ranking científico. Filtro de equipamento exige uma variação com equipamento conhecido.
6. Condições são pares chave/valor textual e todas precisam corresponder. A condição original da afirmação é preservada em prosa e o revisor deve traduzi-la para critérios verificáveis.
7. Ordem: exclusão ativa aplicável → suspensão relevante → regra específica → genérica autorizada → sem cobertura. Genéricas também podem excluir uma variação. Uma suspensão em outro alvo/contexto não bloqueia a consulta.
8. Reabrir uma afirmação aprovada altera seu estado e suspende todas as regras ativas dependentes na mesma transação. Aprovar novamente não reativa. Reativação depende de comando explícito e nova checagem das evidências.
9. Aprovação da versão corrigida não altera vínculos anteriores. Criar nova versão da regra permite selecionar a nova evidência e arquivar a versão antiga durante ativação.
10. Regra negada/comparativa/contraindicação não vira recomendação positiva automaticamente. O protótipo aceita como suporte direto somente associações positivas aprovadas e compatíveis. Outros tipos ficam documentados; o professor decide restrições e seus fundamentos.
11. Resultados armazenam snapshot das regras/evidências usadas. A revisão não reescreve o passado. Uma tela específica para avisar que consultas antigas foram afetadas ainda não está implementada.
12. O bloqueio transacional global simplifica consistência neste protótipo. Operações Java adquirem o bloqueio antes de ler/alterar o domínio; triggers reforçam invariantes. Chamadas SQL administrativas não são o contrato público.
13. Fila usa token/lease, renovação, conclusão atômica e recusa de replay. Reprocessamento de documento concluído com nova versão do extrator ainda exige uma operação futura de execução versionada; a repetição disponível cobre somente falhas.
14. Trechos não interpretados ficam em pendências, sem FK inventada. Um revisor pode cadastrar uma afirmação manual com origem pelo endpoint documentado.
15. A SPA anatômica será desenvolvida após validação no acervo, conforme prioridade combinada. O console operacional já permite testar os cinco status.

## Estados

Afirmação: PENDENTE → APROVADA ou REJEITADA. APROVADA/REJEITADA → EM_REVISAO por reabertura. PENDENTE/EM_REVISAO → SUBSTITUIDA ao corrigir, criando versão PENDENTE. Aprovação/rejeição repetida exige reabertura. Apenas a versão mais recente pode receber revisão pela API.

Regra: RASCUNHO → ATIVA por aprovação explícita. ATIVA → SUSPENSA quando perde evidência aprovada. SUSPENSA → ATIVA somente por aprovação explícita com todas as evidências compatíveis e aprovadas. ARQUIVADA não reativa; crie nova versão. Ativar versão nova arquiva versões anteriores ativas/suspensas da mesma identidade.

Documento: AGUARDANDO → PROCESSANDO → CONCLUIDO/FALHOU. Lease vencido permite nova tentativa com novo token. FALHOU → AGUARDANDO por ação explícita.
