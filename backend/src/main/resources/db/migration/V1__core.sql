CREATE TYPE resultado_consulta AS ENUM ('RECOMENDACAO_ESPECIFICA','RECOMENDACAO_GENERICA','BLOQUEADO_POR_RESTRICAO','SEM_COBERTURA','REVISAO_PENDENTE');
CREATE TYPE estado_afirmacao AS ENUM ('PENDENTE','APROVADA','REJEITADA','EM_REVISAO','SUBSTITUIDA');
CREATE TYPE estado_regra AS ENUM ('RASCUNHO','ATIVA','SUSPENSA','ARQUIVADA');
CREATE TYPE estado_documento AS ENUM ('AGUARDANDO','PROCESSANDO','CONCLUIDO','FALHOU');
CREATE TABLE musculo (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), nome text NOT NULL UNIQUE CHECK(length(trim(nome))>0), sinonimos text[] NOT NULL DEFAULT '{}');
CREATE TABLE exercicio (LIKE musculo INCLUDING ALL);
CREATE TABLE equipamento (LIKE musculo INCLUDING ALL);
CREATE TABLE objetivo (LIKE musculo INCLUDING ALL);
CREATE TABLE restricao (LIKE musculo INCLUDING ALL);
CREATE TABLE variacao_execucao (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), nome text NOT NULL UNIQUE CHECK(length(trim(nome))>0),
 exercicio_id uuid NOT NULL REFERENCES exercicio, equipamento_id uuid NOT NULL REFERENCES equipamento,
 pega text NOT NULL DEFAULT '', angulo text NOT NULL DEFAULT '', tecnica text NOT NULL DEFAULT '', observacoes text NOT NULL DEFAULT '', sinonimos text[] NOT NULL DEFAULT '{}'
);
CREATE TABLE documento (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), titulo text NOT NULL, origem text NOT NULL, licenca text NOT NULL,
 hash_conteudo char(64) NOT NULL UNIQUE, arquivo text NOT NULL, tipo text NOT NULL CHECK(tipo IN ('pdf','txt')),
 criado_em timestamptz NOT NULL DEFAULT now(), status estado_documento NOT NULL DEFAULT 'AGUARDANDO',
 tarefa_token uuid, lease_ate timestamptz, tentativas int NOT NULL DEFAULT 0, erro text, versao_extrator text,
 busca tsvector GENERATED ALWAYS AS (to_tsvector('portuguese',coalesce(titulo,'')||' '||coalesce(origem,''))) STORED
);
CREATE INDEX documento_busca_idx ON documento USING gin(busca);
CREATE TABLE pendencia_extracao (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), documento_id uuid NOT NULL REFERENCES documento,
 localizador text NOT NULL, trecho text NOT NULL, motivo text NOT NULL, versao_extrator text NOT NULL
);
CREATE TABLE afirmacao (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), criado_em timestamptz NOT NULL DEFAULT now());
CREATE TABLE afirmacao_versao (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), afirmacao_id uuid NOT NULL REFERENCES afirmacao,
 versao int NOT NULL CHECK(versao>0), documento_id uuid NOT NULL REFERENCES documento,
 exercicio_id uuid REFERENCES exercicio, variacao_id uuid REFERENCES variacao_execucao,
 musculo_id uuid REFERENCES musculo, objetivo_id uuid REFERENCES objetivo, restricao_id uuid REFERENCES restricao,
 tipo_relacao text NOT NULL CHECK(tipo_relacao IN ('ASSOCIACAO','COMPARACAO','CONTRAINDICACAO')),
 negada boolean NOT NULL DEFAULT false, condicao text NOT NULL DEFAULT '', incerteza text NOT NULL DEFAULT '',
 trecho_original text NOT NULL CHECK(length(trim(trecho_original))>0), localizador text NOT NULL,
 versao_extrator text NOT NULL, status estado_afirmacao NOT NULL DEFAULT 'PENDENTE',
 criado_em timestamptz NOT NULL DEFAULT now(), UNIQUE(afirmacao_id,versao),
 CHECK(num_nonnulls(exercicio_id,variacao_id)=1),
 busca tsvector GENERATED ALWAYS AS (to_tsvector('portuguese',trecho_original)) STORED
);
CREATE INDEX afirmacao_busca_idx ON afirmacao_versao USING gin(busca);
CREATE TABLE revisao (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), afirmacao_versao_id uuid NOT NULL REFERENCES afirmacao_versao,
 revisor text NOT NULL CHECK(length(trim(revisor))>0), decisao text NOT NULL CHECK(decisao IN ('APROVAR','REJEITAR','REABRIR','CORRIGIR')),
 justificativa text NOT NULL CHECK(length(trim(justificativa))>0), criada_em timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE regra (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), criado_em timestamptz NOT NULL DEFAULT now());
CREATE TABLE regra_versao (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), regra_id uuid NOT NULL REFERENCES regra, versao int NOT NULL CHECK(versao>0),
 exercicio_id uuid REFERENCES exercicio, variacao_id uuid REFERENCES variacao_execucao,
 musculo_id uuid NOT NULL REFERENCES musculo, objetivo_id uuid NOT NULL REFERENCES objetivo,
 condicoes jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(condicoes)='object'),
 permite_fallback boolean NOT NULL DEFAULT false, justificativa text NOT NULL CHECK(length(trim(justificativa))>0),
 status estado_regra NOT NULL DEFAULT 'RASCUNHO', motivo_suspensao text, criado_em timestamptz NOT NULL DEFAULT now(),
 UNIQUE(regra_id,versao), CHECK(num_nonnulls(exercicio_id,variacao_id)=1), CHECK(NOT permite_fallback OR exercicio_id IS NOT NULL)
);
CREATE UNIQUE INDEX uma_regra_ativa ON regra_versao(regra_id) WHERE status='ATIVA';
CREATE INDEX regra_escopo_idx ON regra_versao(musculo_id,objetivo_id,status);
CREATE TABLE evidencia_regra (
 regra_versao_id uuid NOT NULL REFERENCES regra_versao, afirmacao_versao_id uuid NOT NULL REFERENCES afirmacao_versao,
 justificativa_vinculo text NOT NULL CHECK(length(trim(justificativa_vinculo))>0), PRIMARY KEY(regra_versao_id,afirmacao_versao_id)
);
CREATE INDEX evidencia_afirmacao_idx ON evidencia_regra(afirmacao_versao_id);
CREATE TABLE regra_restricao (
 regra_versao_id uuid NOT NULL REFERENCES regra_versao, restricao_id uuid NOT NULL REFERENCES restricao, PRIMARY KEY(regra_versao_id,restricao_id)
);
CREATE TABLE evento_regra (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), regra_versao_id uuid NOT NULL REFERENCES regra_versao,
 ator text NOT NULL, acao text NOT NULL, motivo text NOT NULL, criado_em timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE consulta (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), entrada jsonb NOT NULL, resultado resultado_consulta NOT NULL,
 resposta jsonb NOT NULL, criado_em timestamptz NOT NULL DEFAULT now()
);
-- Single write gate for prototype: predictable lock ordering across evidence and rules.
-- All domain mutations and consultations use this transaction-level gate first.
CREATE FUNCTION lock_dominio() RETURNS void LANGUAGE sql AS $$ SELECT pg_advisory_xact_lock(83471392) $$;
CREATE FUNCTION imutavel() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Registro historico imutavel' USING ERRCODE='23514'; END $$;
CREATE TRIGGER revisao_imutavel BEFORE UPDATE OR DELETE ON revisao FOR EACH ROW EXECUTE FUNCTION imutavel();
CREATE TRIGGER evento_imutavel BEFORE UPDATE OR DELETE ON evento_regra FOR EACH ROW EXECUTE FUNCTION imutavel();
CREATE TRIGGER consulta_imutavel BEFORE UPDATE OR DELETE ON consulta FOR EACH ROW EXECUTE FUNCTION imutavel();
CREATE FUNCTION proteger_versao() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM lock_dominio();
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Versoes nao podem ser removidas' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='afirmacao_versao' AND (to_jsonb(NEW)-'status'-'busca') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'busca') THEN
  RAISE EXCEPTION 'Crie nova versao da afirmacao' USING ERRCODE='23514';
 ELSIF TG_TABLE_NAME='regra_versao' AND (to_jsonb(NEW)-'status'-'motivo_suspensao') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'motivo_suspensao') THEN
  RAISE EXCEPTION 'Crie nova versao da regra' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER proteger_afirmacao BEFORE UPDATE OR DELETE ON afirmacao_versao FOR EACH ROW EXECUTE FUNCTION proteger_versao();
CREATE TRIGGER proteger_regra BEFORE UPDATE OR DELETE ON regra_versao FOR EACH ROW EXECUTE FUNCTION proteger_versao();
CREATE FUNCTION proteger_vinculo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE rid uuid;
BEGIN
 PERFORM lock_dominio();
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Remova e recrie vinculos no rascunho' USING ERRCODE='23514'; END IF;
 rid:=CASE WHEN TG_OP='DELETE' THEN OLD.regra_versao_id ELSE NEW.regra_versao_id END;
 IF (SELECT status FROM regra_versao WHERE id=rid)<>'RASCUNHO' THEN RAISE EXCEPTION 'Vinculo exige rascunho' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER proteger_evidencia BEFORE INSERT OR UPDATE OR DELETE ON evidencia_regra FOR EACH ROW EXECUTE FUNCTION proteger_vinculo();
CREATE TRIGGER proteger_restricao BEFORE INSERT OR UPDATE OR DELETE ON regra_restricao FOR EACH ROW EXECUTE FUNCTION proteger_vinculo();
CREATE FUNCTION validar_ativacao() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
 PERFORM lock_dominio();
 IF NEW.status='ATIVA' THEN
  SELECT count(*) INTO n FROM evidencia_regra WHERE regra_versao_id=NEW.id;
  IF n=0 THEN RAISE EXCEPTION 'Regra sem evidencias' USING ERRCODE='23514'; END IF;
  IF EXISTS (
   SELECT 1 FROM evidencia_regra e JOIN afirmacao_versao a ON a.id=e.afirmacao_versao_id
   LEFT JOIN variacao_execucao va ON va.id=a.variacao_id
   LEFT JOIN variacao_execucao vr ON vr.id=NEW.variacao_id
   WHERE e.regra_versao_id=NEW.id AND (
    a.status<>'APROVADA' OR a.negada OR a.tipo_relacao<>'ASSOCIACAO'
    OR a.musculo_id IS DISTINCT FROM NEW.musculo_id
    OR (a.objetivo_id IS NOT NULL AND a.objetivo_id<>NEW.objetivo_id)
    OR coalesce(a.exercicio_id,va.exercicio_id) IS DISTINCT FROM coalesce(NEW.exercicio_id,vr.exercicio_id)
    OR (a.variacao_id IS NOT NULL AND a.variacao_id IS DISTINCT FROM NEW.variacao_id)
   )
  ) THEN RAISE EXCEPTION 'Evidencia nao aprovada ou incompativel com o escopo' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ativacao_valida BEFORE INSERT OR UPDATE OF status ON regra_versao FOR EACH ROW EXECUTE FUNCTION validar_ativacao();
CREATE FUNCTION suspender_dependentes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='APROVADA' AND NEW.status<>'APROVADA' THEN
  WITH afetadas AS (
   UPDATE regra_versao SET status='SUSPENSA', motivo_suspensao='EVIDENCIA_EM_REVISAO'
   WHERE status='ATIVA' AND id IN (SELECT regra_versao_id FROM evidencia_regra WHERE afirmacao_versao_id=NEW.id)
   RETURNING id
  ) INSERT INTO evento_regra(regra_versao_id,ator,acao,motivo)
    SELECT id,'sistema','SUSPENDER','Evidencia '||NEW.id||' deixou de estar aprovada' FROM afetadas;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER suspensao_automatica AFTER UPDATE OF status ON afirmacao_versao FOR EACH ROW EXECUTE FUNCTION suspender_dependentes();
