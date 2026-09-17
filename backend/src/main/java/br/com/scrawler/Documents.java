package br.com.scrawler;

import static br.com.scrawler.Contracts.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class Documents {
  final Store s;
  final KnowledgeService knowledge;
  final Path directory;

  Documents(Store s, KnowledgeService knowledge, @Value("${scrawler.documents}") String path)
      throws java.io.IOException {
    this.s = s;
    this.knowledge = knowledge;
    directory = Path.of(path).toAbsolutePath();
    Files.createDirectories(directory);
  }

  @Transactional
  public Map<String, Object> upload(MultipartFile file, String title, String origin, String license)
      throws Exception {
    if (title.isBlank() || origin.isBlank() || license.isBlank())
      throw new IllegalArgumentException("Informe titulo, origem e licenca/permissao de uso");
    if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024)
      throw new IllegalArgumentException("Documento vazio ou maior que 50 MiB");
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
    String type = name.endsWith(".pdf") ? "pdf" : name.endsWith(".txt") ? "txt" : "";
    if (type.isEmpty()) throw new IllegalArgumentException("Use PDF ou TXT UTF-8");
    byte[] bytes = file.getBytes();
    if (type.equals("pdf")
        && !(new String(
                bytes, 0, Math.min(5, bytes.length), java.nio.charset.StandardCharsets.US_ASCII))
            .equals("%PDF-")) throw new IllegalArgumentException("Assinatura PDF invalida");
    if (type.equals("txt"))
      java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    s.lock();
    var existing = s.rows("SELECT * FROM documento WHERE hash_conteudo=?", hash);
    if (!existing.isEmpty()) return existing.get(0);
    UUID id = UUID.randomUUID();
    String stored = id + "." + type;
    Files.write(directory.resolve(stored), bytes, StandardOpenOption.CREATE_NEW);
    return s.one(
        "INSERT INTO documento(id,titulo,origem,licenca,hash_conteudo,arquivo,tipo) VALUES"
            + " (?,?,?,?,?,?,?) RETURNING *",
        id,
        title,
        origin,
        license,
        hash,
        stored,
        type);
  }

  public Path file(UUID id) {
    var row = s.one("SELECT arquivo FROM documento WHERE id=?", id);
    Path p = directory.resolve(row.get("arquivo").toString()).normalize();
    if (!p.startsWith(directory)) throw new IllegalArgumentException("Caminho invalido");
    return p;
  }

  @Transactional
  public Map<String, Object> claimJob() {
    s.lock();
    var rows =
        s.rows(
            "SELECT * FROM documento WHERE status='AGUARDANDO' OR (status='PROCESSANDO' AND"
                + " lease_ate<now()) ORDER BY criado_em LIMIT 1 FOR UPDATE");
    if (rows.isEmpty()) return Map.of();
    UUID id = (UUID) rows.get(0).get("id");
    UUID token = UUID.randomUUID();
    return s.one(
        "UPDATE documento SET status='PROCESSANDO',tarefa_token=?,lease_ate=now()+interval '5"
            + " minutes',tentativas=tentativas+1,erro=NULL WHERE id=? RETURNING *",
        token,
        id);
  }

  void validateToken(UUID id, UUID token) {
    var r =
        s.one("SELECT tarefa_token,status,lease_ate>now() AS valido FROM documento WHERE id=?", id);
    if (!token.equals(r.get("tarefa_token"))
        || !"PROCESSANDO".equals(r.get("status").toString())
        || !Boolean.TRUE.equals(r.get("valido")))
      throw new IllegalArgumentException("Tarefa expirada ou ja concluida");
  }

  @Transactional
  public void heartbeat(UUID id, UUID token) {
    s.lock();
    validateToken(id, token);
    s.jdbc.update("UPDATE documento SET lease_ate=now()+interval '5 minutes' WHERE id=?", id);
  }

  @Transactional
  public Map<String, Object> finish(UUID id, Batch b) {
    s.lock();
    validateToken(id, b.token());
    for (var c : b.afirmacoes()) {
      if (!c.versaoExtrator().equals(b.versaoExtrator()))
        throw new IllegalArgumentException("Versao do extrator inconsistente");
      knowledge.claim(id, c);
    }
    for (var p : b.pendencias())
      s.jdbc.update(
          "INSERT INTO pendencia_extracao(documento_id,localizador,trecho,motivo,versao_extrator)"
              + " VALUES (?,?,?,?,?)",
          id,
          p.localizador(),
          p.trecho(),
          p.motivo(),
          b.versaoExtrator());
    return s.one(
        "UPDATE documento SET status='CONCLUIDO',versao_extrator=?,lease_ate=NULL WHERE id=?"
            + " RETURNING *",
        b.versaoExtrator(),
        id);
  }

  @Transactional
  public void fail(UUID id, WorkerError error) {
    s.lock();
    validateToken(id, error.token());
    s.jdbc.update(
        "UPDATE documento SET status='FALHOU',erro=?,lease_ate=NULL WHERE id=?",
        error.erro().substring(0, Math.min(2000, error.erro().length())),
        id);
  }

  @Transactional
  public void retry(UUID id) {
    s.lock();
    var r = s.one("SELECT status FROM documento WHERE id=?", id);
    if (!"FALHOU".equals(r.get("status").toString()))
      throw new IllegalArgumentException("Somente tarefas falhadas podem ser repetidas");
    s.jdbc.update(
        "UPDATE documento SET status='AGUARDANDO',erro=NULL,tarefa_token=NULL WHERE id=?", id);
  }
}
