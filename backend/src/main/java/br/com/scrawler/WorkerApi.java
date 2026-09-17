package br.com.scrawler;

import static br.com.scrawler.Contracts.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/worker")
public class WorkerApi {
  final Documents docs;
  final KnowledgeService k;

  WorkerApi(Documents docs, KnowledgeService k) {
    this.docs = docs;
    this.k = k;
  }

  @PostMapping("/claim")
  Object claim() {
    return docs.claimJob();
  }

  @GetMapping("/dictionary")
  Object dictionary() {
    return k.dictionary();
  }

  @GetMapping("/documents/{id}/file")
  Object file(@PathVariable UUID id, @RequestParam UUID token) {
    docs.validateToken(id, token);
    return new FileSystemResource(docs.file(id));
  }

  @PostMapping("/documents/{id}/heartbeat")
  void heartbeat(@PathVariable UUID id, @RequestParam UUID token) {
    docs.heartbeat(id, token);
  }

  @PostMapping("/documents/{id}/finish")
  Object finish(@PathVariable UUID id, @Valid @RequestBody Batch b) {
    return docs.finish(id, b);
  }

  @PostMapping("/documents/{id}/fail")
  void fail(@PathVariable UUID id, @Valid @RequestBody WorkerError e) {
    docs.fail(id, e);
  }
}
