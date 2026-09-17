package br.com.scrawler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class Contracts {
  private Contracts() {}

  public record Term(
      @NotBlank @Size(max = 200) String nome, List<@NotBlank @Size(max = 200) String> sinonimos) {}

  public record Variation(
      @NotBlank String nome,
      @NotNull UUID exercicioId,
      @NotNull UUID equipamentoId,
      String pega,
      String angulo,
      String tecnica,
      String observacoes,
      List<String> sinonimos) {}

  public record Claim(
      UUID exercicioId,
      UUID variacaoId,
      UUID musculoId,
      UUID objetivoId,
      UUID restricaoId,
      @NotBlank String tipoRelacao,
      boolean negada,
      String condicao,
      String incerteza,
      @NotBlank @Size(max = 20000) String trechoOriginal,
      @NotBlank String localizador,
      @NotBlank String versaoExtrator) {}

  public record Review(
      @NotBlank String decisao,
      @NotBlank @Size(max = 10000) String justificativa,
      @Valid Claim correcao) {}

  public record Evidence(@NotNull UUID afirmacaoVersaoId, @NotBlank String justificativa) {}

  public record Rule(
      UUID regraId,
      UUID exercicioId,
      UUID variacaoId,
      @NotNull UUID musculoId,
      @NotNull UUID objetivoId,
      Map<String, String> condicoes,
      boolean permiteFallback,
      @NotBlank String justificativa,
      @NotNull List<@Valid Evidence> evidencias,
      List<UUID> restricoes) {}

  public record Action(@NotBlank String justificativa) {}

  public record Query(
      UUID exercicioId,
      UUID variacaoId,
      @NotNull UUID musculoId,
      @NotNull UUID objetivoId,
      Map<String, String> contexto,
      Set<UUID> restricoes) {}

  public record Discovery(
      @NotNull UUID musculoId,
      @NotNull UUID objetivoId,
      Map<String, String> contexto,
      Set<UUID> restricoes,
      Set<UUID> equipamentos) {}

  public record Pending(
      @NotBlank String localizador,
      @NotBlank @Size(max = 20000) String trecho,
      @NotBlank String motivo) {}

  public record Batch(
      @NotNull UUID token,
      @NotBlank String versaoExtrator,
      @NotNull @Size(max = 50000) List<@Valid Claim> afirmacoes,
      @NotNull @Size(max = 50000) List<@Valid Pending> pendencias) {}

  public record WorkerError(@NotNull UUID token, @NotBlank String erro) {}
}
