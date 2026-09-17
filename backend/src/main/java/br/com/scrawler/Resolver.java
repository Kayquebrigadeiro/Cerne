package br.com.scrawler;

import java.util.*;

/** Pure deterministic resolver. All matched rules are returned; no invented efficacy score. */
public final class Resolver {
  public enum Status {
    RECOMENDACAO_ESPECIFICA,
    RECOMENDACAO_GENERICA,
    BLOQUEADO_POR_RESTRICAO,
    SEM_COBERTURA,
    REVISAO_PENDENTE
  }

  public record Candidate(
      UUID id,
      UUID exercicioId,
      UUID variacaoId,
      String estado,
      boolean fallback,
      Map<String, String> condicoes,
      Set<UUID> restricoes) {}

  public record Result(Status status, List<UUID> regras, String explicacao) {}

  public static Result resolve(
      UUID exercise,
      UUID variation,
      Map<String, String> context,
      Set<UUID> restrictions,
      List<Candidate> candidates) {
    var relevant =
        candidates.stream()
            .filter(
                c ->
                    (c.variacaoId() != null
                        ? c.variacaoId().equals(variation)
                        : c.exercicioId().equals(exercise)))
            .filter(
                c ->
                    c.condicoes().entrySet().stream()
                        .allMatch(e -> Objects.equals(context.get(e.getKey()), e.getValue())))
            .sorted(Comparator.comparing(c -> c.id().toString()))
            .toList();
    var blocked =
        relevant.stream()
            .filter(
                c ->
                    c.estado().equals("ATIVA")
                        && !Collections.disjoint(c.restricoes(), restrictions))
            .toList();
    if (!blocked.isEmpty())
      return result(
          Status.BLOQUEADO_POR_RESTRICAO,
          blocked,
          "Restricao aprovada aplicavel ao contexto informado.");
    var suspended = relevant.stream().filter(c -> c.estado().equals("SUSPENSA")).toList();
    if (!suspended.isEmpty())
      return result(
          Status.REVISAO_PENDENTE,
          suspended,
          "Evidencia em reavaliacao; fallback impedido neste escopo.");
    var specific =
        relevant.stream()
            .filter(
                c ->
                    c.estado().equals("ATIVA")
                        && variation != null
                        && variation.equals(c.variacaoId()))
            .toList();
    if (!specific.isEmpty())
      return result(
          Status.RECOMENDACAO_ESPECIFICA,
          specific,
          "Regras especificas aplicaveis; sem ordenacao de eficacia.");
    var generic =
        relevant.stream()
            .filter(
                c ->
                    c.estado().equals("ATIVA")
                        && c.exercicioId() != null
                        && (variation == null || c.fallback()))
            .toList();
    if (!generic.isEmpty())
      return result(
          Status.RECOMENDACAO_GENERICA,
          generic,
          "Evidencia generica do exercicio, mantendo objetivo e contexto.");
    return result(
        Status.SEM_COBERTURA,
        List.of(),
        "Nao ha regra aplicavel aos criterios informados; isso nao indica inadequacao do"
            + " exercicio.");
  }

  private static Result result(Status status, List<Candidate> rows, String message) {
    return new Result(status, rows.stream().map(Candidate::id).toList(), message);
  }
}
