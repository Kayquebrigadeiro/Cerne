package br.com.scrawler;

import static br.com.scrawler.Contracts.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class Api {
  final KnowledgeService k;
  final Store s;
  final Documents docs;

  Api(KnowledgeService k, Store s, Documents docs) {
    this.k = k;
    this.s = s;
    this.docs = docs;
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    return Map.of("status", "ok", "database", s.jdbc.queryForObject("SELECT 1", Integer.class));
  }

  @GetMapping("/vocab/{kind}")
  Object terms(@PathVariable String kind, @RequestParam(defaultValue = "0") int offset) {
    return k.terms(kind, offset);
  }

  @PostMapping("/vocab/{kind}")
  Object term(@PathVariable String kind, @Valid @RequestBody Term t) {
    return k.term(kind, null, t);
  }

  @PutMapping("/vocab/{kind}/{id}")
  Object update(@PathVariable String kind, @PathVariable UUID id, @Valid @RequestBody Term t) {
    return k.term(kind, id, t);
  }

  @DeleteMapping("/vocab/{kind}/{id}")
  void delete(@PathVariable String kind, @PathVariable UUID id) {
    k.deleteTerm(kind, id);
  }

  @GetMapping("/dictionary")
  Object dictionary() {
    return k.dictionary();
  }

  @PostMapping("/variations")
  Object variation(@Valid @RequestBody Variation v) {
    return k.variation(v);
  }

  @GetMapping("/variations")
  Object variations(@RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT * FROM variacao_execucao ORDER BY nome,id LIMIT 200 OFFSET ?", Math.max(0, offset));
  }

  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Object upload(
      @RequestParam MultipartFile file,
      @RequestParam String titulo,
      @RequestParam String origem,
      @RequestParam String licenca)
      throws Exception {
    return docs.upload(file, titulo, origem, licenca);
  }

  @GetMapping("/documents")
  Object documents(@RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT * FROM documento ORDER BY criado_em DESC,id LIMIT 200 OFFSET ?",
        Math.max(0, offset));
  }

  @GetMapping("/documents/{id}/file")
  ResponseEntity<FileSystemResource> file(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .header(
            "Content-Disposition", "attachment; filename=\"" + docs.file(id).getFileName() + "\"")
        .body(new FileSystemResource(docs.file(id)));
  }

  @PostMapping("/documents/{id}/retry")
  void retry(@PathVariable UUID id) {
    docs.retry(id);
  }

  @GetMapping("/documents/{id}/pending")
  Object pending(@PathVariable UUID id, @RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT * FROM pendencia_extracao WHERE documento_id=? ORDER BY id LIMIT 200 OFFSET ?",
        id,
        Math.max(0, offset));
  }

  @GetMapping("/claims")
  Object claims(@RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT * FROM afirmacao_versao ORDER BY criado_em DESC,id LIMIT 200 OFFSET ?",
        Math.max(0, offset));
  }

  @PostMapping("/documents/{id}/claims")
  Object claim(@PathVariable UUID id, @Valid @RequestBody Claim c) {
    return k.claim(id, c);
  }

  @PostMapping("/claims/{id}/review")
  Object review(@PathVariable UUID id, @Valid @RequestBody Review r, Principal p) {
    return k.review(id, r, p.getName());
  }

  @GetMapping("/claims/{id}/reviews")
  Object reviews(@PathVariable UUID id) {
    return s.rows("SELECT * FROM revisao WHERE afirmacao_versao_id=? ORDER BY criada_em,id", id);
  }

  @GetMapping("/rules")
  Object rules(@RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT * FROM regra_versao ORDER BY criado_em DESC,id LIMIT 200 OFFSET ?",
        Math.max(0, offset));
  }

  @GetMapping("/rules/{id}/evidence")
  Object evidence(@PathVariable UUID id) {
    return s.rows(
        "SELECT a.*,e.justificativa_vinculo FROM evidencia_regra e JOIN afirmacao_versao a ON"
            + " a.id=e.afirmacao_versao_id WHERE e.regra_versao_id=?",
        id);
  }

  @GetMapping("/rules/{id}/events")
  Object events(@PathVariable UUID id) {
    return s.rows("SELECT * FROM evento_regra WHERE regra_versao_id=? ORDER BY criado_em,id", id);
  }

  @PostMapping("/rules")
  Object rule(@Valid @RequestBody Rule r, Principal p) {
    return k.rule(r, p.getName());
  }

  @PostMapping("/rules/{id}/activate")
  Object activate(@PathVariable UUID id, @Valid @RequestBody Action a, Principal p) {
    return k.activate(id, a.justificativa(), p.getName());
  }

  @PostMapping("/rules/{id}/archive")
  Object archive(@PathVariable UUID id, @Valid @RequestBody Action a, Principal p) {
    return k.archive(id, a.justificativa(), p.getName());
  }

  @PostMapping("/queries")
  Object query(@Valid @RequestBody Query q) {
    return k.query(q);
  }

  @PostMapping("/recommendations")
  Object discover(@Valid @RequestBody Discovery d) {
    return k.discover(d);
  }

  @GetMapping("/queries")
  Object history(@RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT * FROM consulta ORDER BY criado_em DESC,id LIMIT 200 OFFSET ?",
        Math.max(0, offset));
  }

  @GetMapping("/search")
  Object search(@RequestParam String q, @RequestParam(defaultValue = "0") int offset) {
    return s.rows(
        "SELECT"
            + " id,documento_id,localizador,trecho_original,status,ts_rank(busca,plainto_tsquery('portuguese',?))"
            + " AS relevancia FROM afirmacao_versao WHERE busca @@ plainto_tsquery('portuguese',?)"
            + " ORDER BY relevancia DESC,id LIMIT 100 OFFSET ?",
        q,
        q,
        Math.max(0, offset));
  }
}
