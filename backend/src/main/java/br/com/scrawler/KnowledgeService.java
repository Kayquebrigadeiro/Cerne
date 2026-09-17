package br.com.scrawler;

import static br.com.scrawler.Contracts.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeService {
  private final Store s;

  public KnowledgeService(Store s) {
    this.s = s;
  }

  static final Set<String> VOCAB =
      Set.of("musculo", "exercicio", "equipamento", "objetivo", "restricao");

  private String table(String kind) {
    if (!VOCAB.contains(kind)) throw new IllegalArgumentException("Vocabulario desconhecido");
    return kind;
  }

  public List<Map<String, Object>> terms(String kind, int offset) {
    return s.rows(
        "SELECT * FROM " + table(kind) + " ORDER BY nome,id LIMIT 200 OFFSET ?",
        Math.max(0, offset));
  }

  @Transactional
  public Map<String, Object> term(String kind, UUID id, Term t) {
    s.lock();
    String tab = table(kind);
    String aliases = s.json(t.sinonimos() == null ? List.of() : t.sinonimos());
    if (id == null)
      return s.one(
          "INSERT INTO "
              + tab
              + "(nome,sinonimos) VALUES (?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb)))"
              + " RETURNING *",
          t.nome(),
          aliases);
    return s.one(
        "UPDATE "
            + tab
            + " SET nome=?,sinonimos=ARRAY(SELECT jsonb_array_elements_text(?::jsonb)) WHERE id=?"
            + " RETURNING *",
        t.nome(),
        aliases,
        id);
  }

  @Transactional
  public void deleteTerm(String kind, UUID id) {
    s.lock();
    if (s.jdbc.update("DELETE FROM " + table(kind) + " WHERE id=?", id) == 0)
      throw new Store.Missing();
  }

  public Map<String, Object> dictionary() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var kind : VOCAB) result.put(kind, s.rows("SELECT * FROM " + kind + " ORDER BY id"));
    result.put("variacao", s.rows("SELECT * FROM variacao_execucao ORDER BY id"));
    return result;
  }

  @Transactional
  public Map<String, Object> variation(Variation v) {
    s.lock();
    return s.one(
        """
INSERT INTO variacao_execucao(nome,exercicio_id,equipamento_id,pega,angulo,tecnica,observacoes,sinonimos)
VALUES (?,?,?,?,?,?,?,ARRAY(SELECT jsonb_array_elements_text(?::jsonb))) RETURNING *
""",
        v.nome(),
        v.exercicioId(),
        v.equipamentoId(),
        Store.text(v.pega()),
        Store.text(v.angulo()),
        Store.text(v.tecnica()),
        Store.text(v.observacoes()),
        s.json(v.sinonimos() == null ? List.of() : v.sinonimos()));
  }

  @Transactional
  public Map<String, Object> claim(UUID document, Claim c) {
    s.lock();
    UUID identity = UUID.randomUUID();
    s.jdbc.update("INSERT INTO afirmacao(id) VALUES (?)", identity);
    return insertClaim(identity, 1, document, c);
  }

  private Map<String, Object> insertClaim(UUID identity, int version, UUID document, Claim c) {
    Store.xor(c.exercicioId(), c.variacaoId());
    return s.one(
        """
INSERT INTO afirmacao_versao(afirmacao_id,versao,documento_id,exercicio_id,variacao_id,musculo_id,objetivo_id,restricao_id,tipo_relacao,negada,condicao,incerteza,trecho_original,localizador,versao_extrator)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
""",
        identity,
        version,
        document,
        c.exercicioId(),
        c.variacaoId(),
        c.musculoId(),
        c.objetivoId(),
        c.restricaoId(),
        c.tipoRelacao(),
        c.negada(),
        Store.text(c.condicao()),
        Store.text(c.incerteza()),
        c.trechoOriginal(),
        c.localizador(),
        c.versaoExtrator());
  }

  @Transactional
  public Map<String, Object> review(UUID id, Review r, String actor) {
    s.lock();
    var old = s.one("SELECT * FROM afirmacao_versao WHERE id=? FOR UPDATE", id);
    String status = old.get("status").toString();
    int latest =
        s.jdbc.queryForObject(
            "SELECT max(versao) FROM afirmacao_versao WHERE afirmacao_id=?",
            Integer.class,
            old.get("afirmacao_id"));
    if (latest != ((Number) old.get("versao")).intValue())
      throw new IllegalArgumentException("Revise a versao mais recente");
    String next;
    Map<String, Object> corrected = null;
    switch (r.decisao()) {
      case "APROVAR", "REJEITAR" -> {
        if (!Set.of("PENDENTE", "EM_REVISAO").contains(status))
          throw new IllegalArgumentException("Reabra antes de revisar");
        next = r.decisao().equals("APROVAR") ? "APROVADA" : "REJEITADA";
      }
      case "REABRIR" -> {
        if (!Set.of("APROVADA", "REJEITADA").contains(status))
          throw new IllegalArgumentException("Estado nao permite reabertura");
        next = "EM_REVISAO";
      }
      case "CORRIGIR" -> {
        if (!Set.of("PENDENTE", "EM_REVISAO").contains(status) || r.correcao() == null)
          throw new IllegalArgumentException("Reabra e forneca correcao completa");
        next = "SUBSTITUIDA";
        if (!r.correcao().trechoOriginal().equals(old.get("trecho_original"))
            || !r.correcao().localizador().equals(old.get("localizador")))
          throw new IllegalArgumentException("Correcao preserva trecho e localizador originais");
        corrected =
            insertClaim(
                (UUID) old.get("afirmacao_id"),
                latest + 1,
                (UUID) old.get("documento_id"),
                r.correcao());
      }
      default -> throw new IllegalArgumentException("Decisao desconhecida");
    }
    s.jdbc.update("UPDATE afirmacao_versao SET status=?::estado_afirmacao WHERE id=?", next, id);
    s.jdbc.update(
        "INSERT INTO revisao(afirmacao_versao_id,revisor,decisao,justificativa) VALUES (?,?,?,?)",
        id,
        actor,
        r.decisao(),
        r.justificativa());
    return corrected == null ? s.one("SELECT * FROM afirmacao_versao WHERE id=?", id) : corrected;
  }

  @Transactional
  public Map<String, Object> rule(Rule r, String actor) {
    s.lock();
    Store.xor(r.exercicioId(), r.variacaoId());
    UUID identity = r.regraId();
    int version = 1;
    if (identity == null) {
      identity = UUID.randomUUID();
      s.jdbc.update("INSERT INTO regra(id) VALUES (?)", identity);
    } else {
      s.one("SELECT id FROM regra WHERE id=?", identity);
      version =
          s.jdbc.queryForObject(
              "SELECT coalesce(max(versao),0)+1 FROM regra_versao WHERE regra_id=?",
              Integer.class,
              identity);
    }
    var row =
        s.one(
            """
 INSERT INTO regra_versao(regra_id,versao,exercicio_id,variacao_id,musculo_id,objetivo_id,condicoes,permite_fallback,justificativa)
 VALUES (?,?,?,?,?,?,?::jsonb,?,?) RETURNING *
""",
            identity,
            version,
            r.exercicioId(),
            r.variacaoId(),
            r.musculoId(),
            r.objetivoId(),
            s.json(r.condicoes() == null ? Map.of() : r.condicoes()),
            r.permiteFallback(),
            r.justificativa());
    for (var e : r.evidencias())
      s.jdbc.update(
          "INSERT INTO evidencia_regra VALUES (?,?,?)",
          row.get("id"),
          e.afirmacaoVersaoId(),
          e.justificativa());
    for (var restriction : r.restricoes() == null ? List.<UUID>of() : r.restricoes())
      s.jdbc.update("INSERT INTO regra_restricao VALUES (?,?)", row.get("id"), restriction);
    event((UUID) row.get("id"), actor, "CRIAR", r.justificativa());
    return row;
  }

  @Transactional
  public Map<String, Object> activate(UUID id, String reason, String actor) {
    s.lock();
    var r = s.one("SELECT * FROM regra_versao WHERE id=?", id);
    if (!Set.of("RASCUNHO", "SUSPENSA").contains(r.get("status").toString()))
      throw new IllegalArgumentException("Estado nao permite ativacao");
    int latest =
        s.jdbc.queryForObject(
            "SELECT max(versao) FROM regra_versao WHERE regra_id=?",
            Integer.class,
            r.get("regra_id"));
    if (latest != ((Number) r.get("versao")).intValue())
      throw new IllegalArgumentException("Ative a versao mais recente");
    for (var old :
        s.rows(
            "SELECT id FROM regra_versao WHERE regra_id=? AND id<>? AND status IN"
                + " ('ATIVA','SUSPENSA')",
            r.get("regra_id"),
            id)) {
      s.jdbc.update("UPDATE regra_versao SET status='ARQUIVADA' WHERE id=?", old.get("id"));
      event((UUID) old.get("id"), actor, "SUBSTITUIR", reason);
    }
    s.jdbc.update("UPDATE regra_versao SET status='ATIVA',motivo_suspensao=NULL WHERE id=?", id);
    event(id, actor, "ATIVAR", reason);
    return s.one("SELECT * FROM regra_versao WHERE id=?", id);
  }

  @Transactional
  public Map<String, Object> archive(UUID id, String reason, String actor) {
    s.lock();
    s.one("SELECT id FROM regra_versao WHERE id=?", id);
    s.jdbc.update("UPDATE regra_versao SET status='ARQUIVADA' WHERE id=?", id);
    event(id, actor, "ARQUIVAR", reason);
    return s.one("SELECT * FROM regra_versao WHERE id=?", id);
  }

  void event(UUID id, String actor, String action, String reason) {
    s.jdbc.update(
        "INSERT INTO evento_regra(regra_versao_id,ator,acao,motivo) VALUES (?,?,?,?)",
        id,
        actor,
        action,
        reason);
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public Map<String, Object> query(Query q) {
    s.lock();
    Store.xor(q.exercicioId(), q.variacaoId());
    UUID exercise = q.exercicioId();
    if (q.variacaoId() != null)
      exercise =
          (UUID)
              s.one("SELECT exercicio_id FROM variacao_execucao WHERE id=?", q.variacaoId())
                  .get("exercicio_id");
    else s.one("SELECT id FROM exercicio WHERE id=?", exercise);
    s.one("SELECT id FROM musculo WHERE id=?", q.musculoId());
    s.one("SELECT id FROM objetivo WHERE id=?", q.objetivoId());
    for (var rid : q.restricoes() == null ? Set.<UUID>of() : q.restricoes())
      s.one("SELECT id FROM restricao WHERE id=?", rid);
    var rows =
        s.rows(
            "SELECT * FROM regra_versao WHERE musculo_id=? AND objetivo_id=? AND status IN"
                + " ('ATIVA','SUSPENSA')",
            q.musculoId(),
            q.objetivoId());
    List<Resolver.Candidate> candidates = new ArrayList<>();
    for (var r : rows) {
      Set<UUID> excluded = new HashSet<>();
      for (var x :
          s.rows("SELECT restricao_id FROM regra_restricao WHERE regra_versao_id=?", r.get("id")))
        excluded.add((UUID) x.get("restricao_id"));
      candidates.add(
          new Resolver.Candidate(
              (UUID) r.get("id"),
              (UUID) r.get("exercicio_id"),
              (UUID) r.get("variacao_id"),
              r.get("status").toString(),
              (Boolean) r.get("permite_fallback"),
              (Map<String, String>) r.get("condicoes"),
              excluded));
    }
    var result =
        Resolver.resolve(
            exercise,
            q.variacaoId(),
            q.contexto() == null ? Map.of() : q.contexto(),
            q.restricoes() == null ? Set.of() : q.restricoes(),
            candidates);
    List<Map<String, Object>> detail = new ArrayList<>();
    for (UUID rid : result.regras()) {
      var rule = new LinkedHashMap<>(s.one("SELECT * FROM regra_versao WHERE id=?", rid));
      rule.put(
          "evidencias",
          s.rows(
              "SELECT a.*,e.justificativa_vinculo FROM evidencia_regra e JOIN afirmacao_versao a ON"
                  + " a.id=e.afirmacao_versao_id WHERE e.regra_versao_id=?",
              rid));
      detail.add(rule);
    }
    UUID id = UUID.randomUUID();
    var response =
        Map.of(
            "id",
            id,
            "status",
            result.status(),
            "explicacao",
            result.explicacao(),
            "regras",
            detail);
    s.jdbc.update(
        "INSERT INTO consulta(id,entrada,resultado,resposta) VALUES"
            + " (?,?::jsonb,?::resultado_consulta,?::jsonb)",
        id,
        s.json(q),
        result.status().name(),
        s.json(response));
    return response;
  }

  @Transactional
  public Map<String, Object> discover(Discovery d) {
    s.lock();
    s.one("SELECT id FROM musculo WHERE id=?", d.musculoId());
    s.one("SELECT id FROM objetivo WHERE id=?", d.objetivoId());
    for (UUID id : d.equipamentos() == null ? Set.<UUID>of() : d.equipamentos())
      s.one("SELECT id FROM equipamento WHERE id=?", id);
    for (UUID id : d.restricoes() == null ? Set.<UUID>of() : d.restricoes())
      s.one("SELECT id FROM restricao WHERE id=?", id);
    // Limit candidate families before expansion to keep prototype requests bounded.
    var families =
        s.rows(
            """
             SELECT DISTINCT coalesce(r.exercicio_id,v.exercicio_id) AS exercicio_id
             FROM regra_versao r LEFT JOIN variacao_execucao v ON v.id=r.variacao_id
             WHERE r.musculo_id=? AND r.objetivo_id=? AND r.status IN ('ATIVA','SUSPENSA')
             ORDER BY exercicio_id LIMIT 101
            """,
            d.musculoId(),
            d.objetivoId());
    if (families.size() > 100)
      throw new IllegalArgumentException(
          "Mais de 100 familias candidatas; consulte um exercicio especifico");
    List<Map<String, Object>> results = new ArrayList<>();
    for (var family : families) {
      UUID exercise = (UUID) family.get("exercicio_id");
      var variants =
          s.rows(
              "SELECT id,nome,equipamento_id FROM variacao_execucao WHERE exercicio_id=? ORDER BY"
                  + " nome,id",
              exercise);
      if (variants.isEmpty()) {
        // Unknown equipment cannot satisfy a requested equipment filter.
        if (d.equipamentos() == null || d.equipamentos().isEmpty()) {
          var response =
              new LinkedHashMap<>(
                  query(
                      new Query(
                          exercise,
                          null,
                          d.musculoId(),
                          d.objetivoId(),
                          d.contexto(),
                          d.restricoes())));
          response.put(
              "nome", s.one("SELECT nome FROM exercicio WHERE id=?", exercise).get("nome"));
          results.add(response);
        }
      } else
        for (var variant : variants) {
          if (d.equipamentos() != null
              && !d.equipamentos().isEmpty()
              && !d.equipamentos().contains((UUID) variant.get("equipamento_id"))) continue;
          if (results.size() >= 200)
            throw new IllegalArgumentException(
                "Mais de 200 variacoes candidatas; consulte um exercicio especifico");
          var response =
              new LinkedHashMap<>(
                  query(
                      new Query(
                          null,
                          (UUID) variant.get("id"),
                          d.musculoId(),
                          d.objetivoId(),
                          d.contexto(),
                          d.restricoes())));
          response.put("nome", variant.get("nome"));
          results.add(response);
        }
    }
    return Map.of(
        "resultados",
        results,
        "explicacao",
        "Opcoes avaliadas por musculo e objetivo. Sem ranking de eficacia; confira o status de cada"
            + " execucao.");
  }
}
