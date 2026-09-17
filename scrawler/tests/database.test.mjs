import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { PGlite } from "@electric-sql/pglite";
let db;
before(async () => {
  db = await PGlite.create();
  await db.exec(
    readFileSync(
      new URL(
        "../backend/src/main/resources/db/migration/V1__core.sql",
        import.meta.url,
      ),
      "utf8",
    ),
  );
});
after(async () => {
  await db.close();
});
const q = async (sql, args = []) => (await db.query(sql, args)).rows;
async function add(table, name) {
  return (
    await q(`INSERT INTO ${table}(nome) VALUES ($1) RETURNING id`, [name])
  )[0].id;
}
let seq = 0;
async function fixture() {
  const prefix = "test" + seq++;
  const m = await add("musculo", prefix),
    e = await add("exercicio", prefix),
    o = await add("objetivo", prefix),
    equipment = await add("equipamento", prefix);
  const v = (
    await q(
      "INSERT INTO variacao_execucao(nome,exercicio_id,equipamento_id) VALUES ($1,$2,$3) RETURNING id",
      [prefix, e, equipment],
    )
  )[0].id;
  const d = (
    await q(
      "INSERT INTO documento(titulo,origem,licenca,hash_conteudo,arquivo,tipo) VALUES ('Fixture','teste','autoria propria',$1,'fixture.txt','txt') RETURNING id",
      [String(seq).padStart(64, "0")],
    )
  )[0].id;
  const a = (await q("INSERT INTO afirmacao DEFAULT VALUES RETURNING id"))[0]
    .id;
  const av = (
    await q(
      "INSERT INTO afirmacao_versao(afirmacao_id,versao,documento_id,exercicio_id,musculo_id,objetivo_id,tipo_relacao,trecho_original,localizador,versao_extrator) VALUES ($1,1,$2,$3,$4,$5,'ASSOCIACAO','Texto de teste','p1','test') RETURNING id",
      [a, d, e, m, o],
    )
  )[0].id;
  const r = (await q("INSERT INTO regra DEFAULT VALUES RETURNING id"))[0].id;
  const rv = (
    await q(
      "INSERT INTO regra_versao(regra_id,versao,exercicio_id,musculo_id,objetivo_id,justificativa,permite_fallback) VALUES ($1,1,$2,$3,$4,'Teste',true) RETURNING id",
      [r, e, m, o],
    )
  )[0].id;
  return { m, e, o, v, d, a, av, r, rv, equipment };
}
async function approveLink(f) {
  await q("UPDATE afirmacao_versao SET status='APROVADA' WHERE id=$1", [f.av]);
  await q(
    "INSERT INTO evidencia_regra VALUES ($1,$2,'Fundamentacao de teste')",
    [f.rv, f.av],
  );
}
async function activate(f) {
  await approveLink(f);
  await q("UPDATE regra_versao SET status='ATIVA' WHERE id=$1", [f.rv]);
}
test("exclusividade exercício/variação", async () => {
  const f = await fixture();
  await assert.rejects(
    () =>
      q(
        "INSERT INTO regra_versao(regra_id,versao,exercicio_id,variacao_id,musculo_id,objetivo_id,justificativa) VALUES ($1,2,$2,$3,$4,$5,'x')",
        [f.r, f.e, f.v, f.m, f.o],
      ),
    /check constraint/,
  );
});
test("objetivo obrigatório", async () => {
  const f = await fixture();
  await assert.rejects(
    () =>
      q(
        "INSERT INTO regra_versao(regra_id,versao,exercicio_id,musculo_id,justificativa) VALUES ($1,2,$2,$3,'x')",
        [f.r, f.e, f.m],
      ),
    /not-null constraint/,
  );
});
test("regra sem evidência não ativa", async () => {
  const f = await fixture();
  await assert.rejects(
    () => q("UPDATE regra_versao SET status='ATIVA' WHERE id=$1", [f.rv]),
    /sem evidencias/,
  );
});
test("evidência pendente não ativa regra", async () => {
  const f = await fixture();
  await q("INSERT INTO evidencia_regra VALUES ($1,$2,'Teste')", [f.rv, f.av]);
  await assert.rejects(
    () => q("UPDATE regra_versao SET status='ATIVA' WHERE id=$1", [f.rv]),
    /nao aprovada/,
  );
});
test("reabertura suspende e aprovação não reativa", async () => {
  const f = await fixture();
  await activate(f);
  await q("UPDATE afirmacao_versao SET status='EM_REVISAO' WHERE id=$1", [
    f.av,
  ]);
  assert.equal(
    (await q("SELECT status FROM regra_versao WHERE id=$1", [f.rv]))[0].status,
    "SUSPENSA",
  );
  await q("UPDATE afirmacao_versao SET status='APROVADA' WHERE id=$1", [f.av]);
  assert.equal(
    (await q("SELECT status FROM regra_versao WHERE id=$1", [f.rv]))[0].status,
    "SUSPENSA",
  );
  assert.equal(
    (
      await q(
        "SELECT count(*)::int AS n FROM evento_regra WHERE regra_versao_id=$1",
        [f.rv],
      )
    )[0].n,
    1,
  );
});
test("suspensão e reabertura sofrem rollback juntas", async () => {
  const f = await fixture();
  await activate(f);
  await db.exec("BEGIN");
  await q("UPDATE afirmacao_versao SET status='EM_REVISAO' WHERE id=$1", [
    f.av,
  ]);
  await db.exec("ROLLBACK");
  assert.equal(
    (await q("SELECT status FROM regra_versao WHERE id=$1", [f.rv]))[0].status,
    "ATIVA",
  );
  assert.equal(
    (await q("SELECT status FROM afirmacao_versao WHERE id=$1", [f.av]))[0]
      .status,
    "APROVADA",
  );
});
test("conteúdo da afirmação é imutável", async () => {
  const f = await fixture();
  await assert.rejects(
    () =>
      q("UPDATE afirmacao_versao SET trecho_original='alterado' WHERE id=$1", [
        f.av,
      ]),
    /nova versao/,
  );
});
test("conteúdo da regra é imutável", async () => {
  const f = await fixture();
  await assert.rejects(
    () =>
      q("UPDATE regra_versao SET justificativa='alterado' WHERE id=$1", [f.rv]),
    /nova versao/,
  );
});
test("vínculos de regra ativa não são alterados", async () => {
  const f = await fixture();
  await activate(f);
  await assert.rejects(
    () => q("DELETE FROM evidencia_regra WHERE regra_versao_id=$1", [f.rv]),
    /rascunho/,
  );
});
test("versões preservam evidência anterior", async () => {
  const f = await fixture();
  await activate(f);
  await q(
    "INSERT INTO afirmacao_versao(afirmacao_id,versao,documento_id,exercicio_id,musculo_id,tipo_relacao,trecho_original,localizador,versao_extrator) VALUES ($1,2,$2,$3,$4,'ASSOCIACAO','nova interpretacao','p1','test')",
    [f.a, f.d, f.e, f.m],
  );
  assert.equal(
    (
      await q(
        "SELECT afirmacao_versao_id FROM evidencia_regra WHERE regra_versao_id=$1",
        [f.rv],
      )
    )[0].afirmacao_versao_id,
    f.av,
  );
});
test("mesmo exercício e equipamento permite variações distintas", async () => {
  const f = await fixture();
  await q(
    "INSERT INTO variacao_execucao(nome,exercicio_id,equipamento_id,pega) VALUES ($1,$2,$3,'outra')",
    ["other" + seq, f.e, f.equipment],
  );
});
test("evidência de outro objetivo é incompatível", async () => {
  const f = await fixture();
  const o = await add("objetivo", "other" + seq);
  const r = (await q("INSERT INTO regra DEFAULT VALUES RETURNING id"))[0].id;
  const rv = (
    await q(
      "INSERT INTO regra_versao(regra_id,versao,exercicio_id,musculo_id,objetivo_id,justificativa) VALUES ($1,1,$2,$3,$4,'x') RETURNING id",
      [r, f.e, f.m, o],
    )
  )[0].id;
  await q("UPDATE afirmacao_versao SET status='APROVADA' WHERE id=$1", [f.av]);
  await q("INSERT INTO evidencia_regra VALUES ($1,$2,'x')", [rv, f.av]);
  await assert.rejects(
    () => q("UPDATE regra_versao SET status='ATIVA' WHERE id=$1", [rv]),
    /incompativel/,
  );
});
test("busca textual recupera afirmação com origem", async () => {
  const f = await fixture();
  const rows = await q(
    "SELECT documento_id FROM afirmacao_versao WHERE busca @@ plainto_tsquery('portuguese','texto') AND id=$1",
    [f.av],
  );
  assert.equal(rows[0].documento_id, f.d);
});
