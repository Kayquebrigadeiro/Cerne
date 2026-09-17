package br.com.scrawler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ResolverTest {
  final UUID exercise = UUID.randomUUID(),
      variation = UUID.randomUUID(),
      restriction = UUID.randomUUID();

  Resolver.Candidate candidate(
      UUID e,
      UUID v,
      String state,
      boolean fallback,
      Map<String, String> conditions,
      Set<UUID> restrictions) {
    return new Resolver.Candidate(
        UUID.randomUUID(), e, v, state, fallback, conditions, restrictions);
  }

  Resolver.Result run(List<Resolver.Candidate> rows) {
    return Resolver.resolve(
        exercise, variation, Map.of("nivel", "iniciante"), Set.of(restriction), rows);
  }

  @Test
  void noCoverage() {
    assertEquals(Resolver.Status.SEM_COBERTURA, run(List.of()).status());
  }

  @Test
  void exactBeforeGeneric() {
    var exact = candidate(null, variation, "ATIVA", false, Map.of(), Set.of());
    var generic = candidate(exercise, null, "ATIVA", true, Map.of(), Set.of());
    assertEquals(List.of(exact.id()), run(List.of(generic, exact)).regras());
  }

  @Test
  void genericRequiresPermission() {
    assertEquals(
        Resolver.Status.SEM_COBERTURA,
        run(List.of(candidate(exercise, null, "ATIVA", false, Map.of(), Set.of()))).status());
    assertEquals(
        Resolver.Status.RECOMENDACAO_GENERICA,
        run(List.of(candidate(exercise, null, "ATIVA", true, Map.of(), Set.of()))).status());
  }

  @Test
  void restrictionStopsFallback() {
    assertEquals(
        Resolver.Status.BLOQUEADO_POR_RESTRICAO,
        run(List.of(
                candidate(null, variation, "ATIVA", false, Map.of(), Set.of(restriction)),
                candidate(exercise, null, "ATIVA", true, Map.of(), Set.of())))
            .status());
  }

  @Test
  void genericRestrictionStopsExact() {
    assertEquals(
        Resolver.Status.BLOQUEADO_POR_RESTRICAO,
        run(List.of(
                candidate(exercise, null, "ATIVA", false, Map.of(), Set.of(restriction)),
                candidate(null, variation, "ATIVA", false, Map.of(), Set.of())))
            .status());
  }

  @Test
  void suspendedStopsFallback() {
    assertEquals(
        Resolver.Status.REVISAO_PENDENTE,
        run(List.of(
                candidate(null, variation, "SUSPENSA", false, Map.of(), Set.of()),
                candidate(exercise, null, "ATIVA", true, Map.of(), Set.of())))
            .status());
  }

  @Test
  void unrelatedSuspensionDoesNotBlock() {
    assertEquals(
        Resolver.Status.RECOMENDACAO_GENERICA,
        run(List.of(
                candidate(null, UUID.randomUUID(), "SUSPENSA", false, Map.of(), Set.of()),
                candidate(exercise, null, "ATIVA", true, Map.of(), Set.of())))
            .status());
  }

  @Test
  void conditionsAreMandatory() {
    assertEquals(
        Resolver.Status.SEM_COBERTURA,
        run(List.of(
                candidate(exercise, null, "ATIVA", true, Map.of("nivel", "avancado"), Set.of())))
            .status());
  }

  @Test
  void irrelevantSuspendedContext() {
    assertEquals(
        Resolver.Status.SEM_COBERTURA,
        run(List.of(
                candidate(exercise, null, "SUSPENSA", true, Map.of("nivel", "avancado"), Set.of())))
            .status());
  }

  @Test
  void missingContextIsNotWildcard() {
    assertEquals(
        Resolver.Status.SEM_COBERTURA,
        run(List.of(
                candidate(exercise, null, "ATIVA", true, Map.of("populacao", "adultos"), Set.of())))
            .status());
  }

  @Test
  void genericQueryDoesNotInventVariation() {
    assertEquals(
        Resolver.Status.RECOMENDACAO_GENERICA,
        Resolver.resolve(
                exercise,
                null,
                Map.of(),
                Set.of(),
                List.of(candidate(exercise, null, "ATIVA", false, Map.of(), Set.of())))
            .status());
  }

  @Test
  void draftsNeverRecommend() {
    assertEquals(
        Resolver.Status.SEM_COBERTURA,
        run(List.of(candidate(null, variation, "RASCUNHO", false, Map.of(), Set.of()))).status());
  }
}
