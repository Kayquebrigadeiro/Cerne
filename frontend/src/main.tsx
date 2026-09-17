import React, { useState, useEffect } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";
type Row = Record<string, any>;
const kinds = ["musculo", "exercicio", "equipamento", "objetivo", "restricao"];
const labels: Record<string, string> = {
  musculo: "Músculos",
  exercicio: "Exercícios",
  equipamento: "Equipamentos",
  objetivo: "Objetivos",
  restricao: "Restrições",
};
function App() {
  const [credentials, setCredentials] = useState<{
    user: string;
    password: string;
  } | null>(null);
  const [tab, setTab] = useState("Consulta"),
    [dictionary, setDictionary] = useState<Record<string, Row[]>>({}),
    [rows, setRows] = useState<Row[]>([]),
    [kind, setKind] = useState("musculo"),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [result, setResult] = useState<Row | null>(null),
    [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<Row | null>(null),
    [reason, setReason] = useState(""),
    [correction, setCorrection] = useState(""),
    [conditions, setConditions] = useState("{}");
  async function api(
    path: string,
    method = "GET",
    body?: unknown,
  ): Promise<any> {
    if (!credentials) throw Error("Entre para continuar");
    const isForm = body instanceof FormData;
    const auth = btoa(
      String.fromCharCode(
        ...new TextEncoder().encode(
          `${credentials.user}:${credentials.password}`,
        ),
      ),
    );
    const response = await fetch("/api" + path, {
      method,
      headers: {
        Authorization: "Basic " + auth,
        ...(!isForm && body ? { "Content-Type": "application/json" } : {}),
      },
      body: body
        ? isForm
          ? (body as FormData)
          : JSON.stringify(body)
        : undefined,
    });
    const text = await response.text();
    let value: any;
    try {
      value = text ? JSON.parse(text) : null;
    } catch {
      value = null;
    }
    if (!response.ok)
      throw Error(
        value?.erro || `Falha ${response.status}: confira acesso e entrada.`,
      );
    return value;
  }
  async function task(fn: () => Promise<void>) {
    setBusy(true);
    setMessage("");
    try {
      await fn();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }
  async function refresh() {
    setDictionary(await api("/dictionary"));
    const route =
      tab === "Vocabulário"
        ? "/vocab/" + kind
        : tab === "Variações"
          ? "/variations"
          : tab === "Documentos"
            ? "/documents"
            : tab === "Afirmações"
              ? "/claims"
              : tab === "Regras"
                ? "/rules"
                : tab === "Histórico"
                  ? "/queries"
                  : null;
    if (route) setRows(await api(route + "?offset=" + offset));
    else setRows([]);
  }
  useEffect(() => {
    if (credentials) void task(refresh);
  }, [credentials, tab, kind, offset]);
  function select(name: string, group: string, required = true) {
    return (
      <label>
        {labels[group] || group}
        <select name={name} required={required} defaultValue="">
          <option value="">Selecione</option>
          {(dictionary[group] || []).map((x) => (
            <option key={x.id} value={x.id}>
              {x.nome}
            </option>
          ))}
        </select>
      </label>
    );
  }
  function data(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    return new FormData(event.currentTarget);
  }
  const get = (f: FormData, k: string) => String(f.get(k) || "");
  if (!credentials)
    return (
      <main className="login">
        <span className="eyebrow">SC RAWLER / PESQUISA APLICADA</span>
        <h1>
          Conhecimento com origem.
          <br />
          Decisões com evidência.
        </h1>
        <p>
          Console local para organizar documentos, revisar afirmações e validar
          regras.
        </p>
        <form
          onSubmit={(e) => {
            const f = data(e);
            setCredentials({
              user: get(f, "user"),
              password: get(f, "password"),
            });
          }}
        >
          <label>
            Usuário
            <input name="user" required autoComplete="username" />
          </label>
          <label>
            Senha
            <input
              name="password"
              type="password"
              required
              autoComplete="current-password"
            />
          </label>
          <button>Entrar no núcleo</button>
        </form>
        <small>As credenciais permanecem apenas na memória desta aba.</small>
      </main>
    );
  return (
    <div className="layout">
      <aside>
        <div className="brand">
          scrawler<span>núcleo de conhecimento</span>
        </div>
        <nav>
          {[
            "Consulta",
            "Vocabulário",
            "Variações",
            "Documentos",
            "Afirmações",
            "Regras",
            "Histórico",
          ].map((t) => (
            <button
              key={t}
              className={tab === t ? "active" : ""}
              onClick={() => {
                setTab(t);
                setOffset(0);
                setSelected(null);
                setResult(null);
              }}
            >
              {t}
            </button>
          ))}
        </nav>
        <p>
          Protótipo de pesquisa
          <br />
          Revisão humana obrigatória
        </p>
        <button
          onClick={() => {
            setCredentials(null);
            setRows([]);
            setResult(null);
          }}
        >
          Sair
        </button>
      </aside>
      <main>
        <header>
          <div>
            <span className="eyebrow">
              BASE REVISADA · MOTOR DETERMINÍSTICO
            </span>
            <h1>{tab}</h1>
          </div>
          <button
            className="secondary"
            disabled={busy}
            onClick={() => void task(refresh)}
          >
            Atualizar
          </button>
        </header>
        {message && (
          <div role="alert" className="error">
            {message}
          </div>
        )}
        {busy && <p role="status">Processando…</p>}
        {tab === "Consulta" && (
          <>
            <p className="intro">
              Informe o contexto. A resposta mostra regras aplicáveis e suas
              fontes, sem atribuir uma eficácia não demonstrada.
            </p>
            <form
              className="panel grid"
              onSubmit={(e) => {
                const f = data(e);
                void task(async () =>
                  setResult(
                    await api(
                      get(f, "exercicioId") || get(f, "variacaoId")
                        ? "/queries"
                        : "/recommendations",
                      "POST",
                      {
                        ...(get(f, "exercicioId") || get(f, "variacaoId")
                          ? {
                              exercicioId: get(f, "exercicioId") || null,
                              variacaoId: get(f, "variacaoId") || null,
                            }
                          : {}),
                        musculoId: get(f, "musculoId"),
                        objetivoId: get(f, "objetivoId"),
                        contexto: JSON.parse(get(f, "contexto")),
                        restricoes: f.getAll("restricoes"),
                      },
                    ),
                  ),
                );
              }}
            >
              {select("exercicioId", "exercicio", false)}
              {select("variacaoId", "variacao", false)}
              {select("musculoId", "musculo")}
              {select("objetivoId", "objetivo")}
              <p className="wide hint">
                Deixe exercício e variação vazios para descobrir opções por
                músculo. Para conferir uma execução, escolha somente um dos
                dois.
              </p>
              <label>
                Restrições informadas
                <select name="restricoes" multiple>
                  {(dictionary.restricao || []).map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.nome}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Condições do contexto (JSON)
                <textarea name="contexto" defaultValue="{}" />
              </label>
              <button disabled={busy}>Consultar regras</button>
            </form>
            {result && (
              <section className="panel">
                {result.status && (
                  <span className="badge">{result.status}</span>
                )}
                {result.resultados?.map((item: Row) => (
                  <article key={item.id}>
                    <h3>{item.nome}</h3>
                    <span className="badge">{item.status}</span>
                    <p>{item.explicacao}</p>
                    {item.regras?.map((r: Row) => (
                      <details key={r.id}>
                        <summary>Regra · versão {r.versao}</summary>
                        <p>{r.justificativa}</p>
                        {r.evidencias.map((ev: Row) => (
                          <blockquote key={ev.id}>
                            {ev.trecho_original}
                            <footer>{ev.localizador}</footer>
                          </blockquote>
                        ))}
                      </details>
                    ))}
                  </article>
                ))}
                {result.resultados?.length === 0 && (
                  <p>Sem cobertura para os critérios informados.</p>
                )}
                <p>{result.explicacao}</p>
                {result.regras?.map((r: Row) => (
                  <article key={r.id}>
                    <h3>Regra · versão {r.versao}</h3>
                    <p>{r.justificativa}</p>
                    {r.evidencias.map((ev: Row) => (
                      <blockquote key={ev.id}>
                        {ev.trecho_original}
                        <footer>
                          {ev.localizador} · documento {ev.documento_id}
                        </footer>
                      </blockquote>
                    ))}
                  </article>
                ))}
              </section>
            )}
          </>
        )}
        {tab === "Vocabulário" && (
          <>
            <div className="chips">
              {kinds.map((k) => (
                <button
                  className={kind === k ? "active" : "secondary"}
                  key={k}
                  onClick={() => {
                    setKind(k);
                    setOffset(0);
                  }}
                >
                  {labels[k]}
                </button>
              ))}
            </div>
            <form
              className="panel grid"
              onSubmit={(e) => {
                const f = data(e),
                  form = e.currentTarget;
                void task(async () => {
                  await api("/vocab/" + kind, "POST", {
                    nome: get(f, "nome"),
                    sinonimos: get(f, "sinonimos")
                      .split(";")
                      .map((s) => s.trim())
                      .filter(Boolean),
                  });
                  form.reset();
                  await refresh();
                });
              }}
            >
              <label>
                Nome oficial
                <input name="nome" required />
              </label>
              <label>
                Sinônimos separados por ;<input name="sinonimos" />
              </label>
              <button disabled={busy}>Cadastrar termo</button>
            </form>
          </>
        )}
        {tab === "Variações" && (
          <form
            className="panel grid"
            onSubmit={(e) => {
              const f = data(e);
              void task(async () => {
                await api("/variations", "POST", {
                  nome: get(f, "nome"),
                  exercicioId: get(f, "exercicioId"),
                  equipamentoId: get(f, "equipamentoId"),
                  pega: get(f, "pega"),
                  angulo: get(f, "angulo"),
                  tecnica: get(f, "tecnica"),
                  observacoes: get(f, "observacoes"),
                  sinonimos: get(f, "sinonimos")
                    .split(";")
                    .map((s) => s.trim())
                    .filter(Boolean),
                });
                await refresh();
              });
            }}
          >
            <label>
              Nome da execução
              <input name="nome" required />
            </label>
            {select("exercicioId", "exercicio")}
            {select("equipamentoId", "equipamento")}
            {["pega", "angulo", "tecnica", "observacoes", "sinonimos"].map(
              (n) => (
                <label key={n}>
                  {n}
                  <input name={n} />
                </label>
              ),
            )}
            <button disabled={busy}>Cadastrar variação</button>
          </form>
        )}
        {tab === "Documentos" && (
          <form
            className="panel grid"
            onSubmit={(e) => {
              const f = data(e);
              void task(async () => {
                await api("/documents", "POST", f);
                await refresh();
                setMessage(
                  "Documento registrado. O worker processará a tarefa.",
                );
              });
            }}
          >
            <label>
              Título
              <input name="titulo" required />
            </label>
            <label>
              Origem / referência
              <input name="origem" required />
            </label>
            <label>
              Licença ou permissão de uso
              <input name="licenca" required />
            </label>
            <label>
              PDF ou TXT · até 50 MiB
              <input name="file" type="file" accept=".pdf,.txt" required />
            </label>
            <button disabled={busy}>Enviar para extração</button>
          </form>
        )}
        {tab === "Afirmações" && (
          <p className="intro">
            Confira trecho, negação, condições e incerteza antes de aprovar. A
            aprovação não cria uma regra automaticamente.
          </p>
        )}
        {tab === "Regras" && (
          <form
            className="panel grid"
            onSubmit={(e) => {
              const f = data(e);
              void task(async () => {
                const ids = get(f, "evidencias")
                  .split(",")
                  .map((s) => s.trim())
                  .filter(Boolean);
                await api("/rules", "POST", {
                  regraId: get(f, "regraId") || null,
                  exercicioId: get(f, "exercicioId") || null,
                  variacaoId: get(f, "variacaoId") || null,
                  musculoId: get(f, "musculoId"),
                  objetivoId: get(f, "objetivoId"),
                  condicoes: JSON.parse(conditions),
                  permiteFallback: f.has("fallback"),
                  justificativa: get(f, "justificativa"),
                  evidencias: ids.map((id) => ({
                    afirmacaoVersaoId: id,
                    justificativa: get(f, "vinculo"),
                  })),
                  restricoes: f.getAll("restricoes"),
                });
                await refresh();
              });
            }}
          >
            {select("exercicioId", "exercicio", false)}
            {select("variacaoId", "variacao", false)}
            {select("musculoId", "musculo")}
            {select("objetivoId", "objetivo")}
            <label className="wide">
              IDs das versões de afirmações aprovadas, separados por vírgula
              <input name="evidencias" required />
            </label>
            <label>
              Justificativa da regra
              <textarea name="justificativa" required />
            </label>
            <label>
              Como as evidências sustentam o escopo
              <textarea name="vinculo" required />
            </label>
            <label>
              Condições (JSON com valores textuais)
              <textarea
                value={conditions}
                onChange={(e) => setConditions(e.target.value)}
              />
            </label>
            <label>
              Restrições de exclusão
              <select name="restricoes" multiple>
                {(dictionary.restricao || []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.nome}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Identidade da regra, somente para nova versão
              <input name="regraId" />
            </label>
            <label className="check">
              <input name="fallback" type="checkbox" /> Autorizar fallback para
              esta regra genérica
            </label>
            <button disabled={busy}>Criar rascunho</button>
          </form>
        )}
        {tab !== "Consulta" && (
          <section className="panel">
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Registro</th>
                    <th>Estado / detalhe</th>
                    <th>Ações</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <strong>
                          {r.nome ||
                            r.titulo ||
                            r.trecho_original?.slice(0, 140) ||
                            r.justificativa?.slice(0, 140) ||
                            r.resultado}
                        </strong>
                        <code>{r.id}</code>
                      </td>
                      <td>
                        {r.status ||
                          r.resultado ||
                          r.sinonimos?.join(", ") ||
                          "Cadastrado"}
                        {r.motivo_suspensao && (
                          <small>{r.motivo_suspensao}</small>
                        )}
                      </td>
                      <td>
                        <button
                          className="secondary"
                          onClick={() => {
                            setSelected(r);
                            setReason("");
                            if (tab === "Afirmações")
                              setCorrection(
                                JSON.stringify(
                                  {
                                    exercicioId: r.exercicio_id,
                                    variacaoId: r.variacao_id,
                                    musculoId: r.musculo_id,
                                    objetivoId: r.objetivo_id,
                                    restricaoId: r.restricao_id,
                                    tipoRelacao: r.tipo_relacao,
                                    negada: r.negada,
                                    condicao: r.condicao,
                                    incerteza: r.incerteza,
                                    trechoOriginal: r.trecho_original,
                                    localizador: r.localizador,
                                    versaoExtrator: r.versao_extrator,
                                  },
                                  null,
                                  2,
                                ),
                              );
                          }}
                        >
                          Inspecionar
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!rows.length && (
                <p className="empty">Nenhum registro nesta página.</p>
              )}
            </div>
            <div className="pagination">
              <button
                className="secondary"
                disabled={offset === 0 || busy}
                onClick={() => setOffset(Math.max(0, offset - 200))}
              >
                Anterior
              </button>
              <span>Página {offset / 200 + 1}</span>
              <button
                className="secondary"
                disabled={rows.length < 200 || busy}
                onClick={() => setOffset(offset + 200)}
              >
                Próxima
              </button>
            </div>
          </section>
        )}
        {selected && (
          <section className="panel">
            <h2>Inspeção do registro</h2>
            <pre>{JSON.stringify(selected, null, 2)}</pre>
            {["Afirmações", "Regras"].includes(tab) && (
              <label>
                Motivo da decisão
                <textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
            )}
            {tab === "Afirmações" && (
              <>
                <div className="chips">
                  {["APROVAR", "REJEITAR", "REABRIR"].map((action) => (
                    <button
                      key={action}
                      disabled={busy || !reason.trim()}
                      onClick={() =>
                        void task(async () => {
                          await api(
                            "/claims/" + selected.id + "/review",
                            "POST",
                            { decisao: action, justificativa: reason },
                          );
                          setSelected(null);
                          await refresh();
                        })
                      }
                    >
                      {action}
                    </button>
                  ))}
                </div>
                <details>
                  <summary>Corrigir criando uma nova versão</summary>
                  <textarea
                    className="json"
                    value={correction}
                    onChange={(e) => setCorrection(e.target.value)}
                  />
                  <button
                    disabled={busy || !reason.trim()}
                    onClick={() =>
                      void task(async () => {
                        await api(
                          "/claims/" + selected.id + "/review",
                          "POST",
                          {
                            decisao: "CORRIGIR",
                            justificativa: reason,
                            correcao: JSON.parse(correction),
                          },
                        );
                        setSelected(null);
                        await refresh();
                      })
                    }
                  >
                    Enviar correção
                  </button>
                </details>
              </>
            )}
            {tab === "Regras" && (
              <div className="chips">
                {["activate", "archive"].map((action) => (
                  <button
                    key={action}
                    disabled={busy || !reason.trim()}
                    onClick={() =>
                      void task(async () => {
                        await api(
                          "/rules/" + selected.id + "/" + action,
                          "POST",
                          { justificativa: reason },
                        );
                        setSelected(null);
                        await refresh();
                      })
                    }
                  >
                    {action === "activate" ? "Aprovar e ativar" : "Arquivar"}
                  </button>
                ))}
                <button
                  className="secondary"
                  onClick={() =>
                    void task(async () =>
                      setResult({
                        evidencias: await api(
                          "/rules/" + selected.id + "/evidence",
                        ),
                      }),
                    )
                  }
                >
                  Ver evidências
                </button>
              </div>
            )}
            {tab === "Documentos" && (
              <div className="chips">
                <button
                  onClick={() =>
                    void task(async () =>
                      setResult({
                        pendencias: await api(
                          "/documents/" + selected.id + "/pending",
                        ),
                      }),
                    )
                  }
                >
                  Ver pendências
                </button>
                {selected.status === "FALHOU" && (
                  <button
                    onClick={() =>
                      void task(async () => {
                        await api(
                          "/documents/" + selected.id + "/retry",
                          "POST",
                        );
                        await refresh();
                      })
                    }
                  >
                    Reprocessar falha
                  </button>
                )}
              </div>
            )}
            {result && tab !== "Consulta" && (
              <pre>{JSON.stringify(result, null, 2)}</pre>
            )}
          </section>
        )}
      </main>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
