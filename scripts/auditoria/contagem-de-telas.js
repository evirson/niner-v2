#!/usr/bin/env node
/*
 * Reconcilia a contagem de telas entre as TRÊS bases que o projeto usa (pendência #79).
 *
 * O problema que este script existe para impedir: `docs/TELAS.md` foi GERADO uma vez
 * (2026-08-25) e vem recebendo acréscimos à mão desde então. Documento gerado que passa a ser
 * editado manualmente perde a única garantia que tinha — e em 2026-08-31 ele já discordava de si
 * mesmo (58 numa linha, 57 noutra) e do banco (60).
 *
 * ⚠️ As três bases medem COISAS DIFERENTES, e nenhuma está errada:
 *
 *   1. `docs/TELAS.md`   — telas com ROTA, agrupadas por menu. É o inventário de navegação.
 *   2. `web/src/App.tsx` — todas as rotas registradas, inclusive as telas-filhas (`/x/novo`).
 *   3. `cfg_tela`        — chaves que o RBAC governa. Inclui sub-ações sem rota própria
 *                          (`estoque.contagem`, `fiscal.nfse` — que é uma ABA de Documentos
 *                          Fiscais) e exclui as públicas, que não passam por permissão.
 *
 * Por isso o script não escolhe um número: ele imprime os três, DERIVADOS, e — o que interessa —
 * lista as diferenças item a item, para que a próxima divergência apareça como uma linha com nome
 * e não como dois totais que ninguém consegue reconciliar.
 *
 * Uso:  node scripts/auditoria/contagem-de-telas.js
 *       node scripts/auditoria/contagem-de-telas.js --json
 *
 * A contagem de `cfg_tela` vem do arquivo /tmp/cfg_tela.txt quando ele existe (uma chave por
 * linha), senão é pulada — o script não abre conexão com banco de propósito, para poder rodar em
 * qualquer máquina. Para produzi-lo:
 *   docker exec -i niner-db psql -U niner_owner -d niner_db -tAc \
 *     "SELECT chave FROM cfg_tela ORDER BY chave;" > /tmp/cfg_tela.txt
 */

const fs = require('fs');
const path = require('path');

const raiz = path.resolve(__dirname, '..', '..');
// ⚠️ O `\r` do CRLF entra no nome da seção e faz TODA comparação por igualdade falhar em
// silêncio — a primeira versão deste script contou 0 telas públicas e 0 futuras por causa disso,
// e o total pareceu apenas "um pouco menor" em vez de errado.
const lerArquivo = (rel) => fs.readFileSync(path.join(raiz, rel), 'utf8').replace(/\r\n/g, '\n');

// ---------------------------------------------------------------- docs/TELAS.md
const telasMd = lerArquivo('docs/TELAS.md');

/** Linhas de tabela que carregam uma rota, agrupadas pela seção `## ` em que estão. */
function rotasDoDocumentoPorSecao() {
  const porSecao = new Map();
  let secao = '(sem seção)';
  for (const linha of telasMd.split('\n')) {
    const cabecalho = linha.match(/^## (.+)$/);
    if (cabecalho) {
      secao = cabecalho[1].trim();
      continue;
    }
    // | Nome | `/rota` | spec |  — ⚠️ a rota NÃO está sempre na 2ª célula: a tabela de
    // Relatórios tem uma coluna "Subgrupo" a mais, e casar por posição perdia 11 telas.
    const celula = linha.startsWith('|') ? linha.match(/`(\/[^`]*)`/) : null;
    if (celula) {
      if (!porSecao.has(secao)) porSecao.set(secao, []);
      porSecao.get(secao).push(celula[1]);
    }
  }
  return porSecao;
}

/** Bullets da seção "Telas-filhas". */
function filhasDoDocumento() {
  const inicio = telasMd.indexOf('## Telas-filhas');
  if (inicio < 0) return [];
  const trecho = telasMd.slice(inicio).split(/\n---/)[0];
  return [...trecho.matchAll(/^- `([^`]+)`/gm)].map((m) => m[1]);
}

// ---------------------------------------------------------------- web/src/App.tsx
const appTsx = lerArquivo('web/src/App.tsx');

/** Toda rota registrada, com a marca de quem aponta para o placeholder `EmBreve`. */
function rotasDoCodigo() {
  const rotas = [];
  for (const m of appTsx.matchAll(/<Route\s+path="([^"]+)"\s+element=\{<(\w+)/g)) {
    rotas.push({ rota: m[1], componente: m[2], emBreve: m[2] === 'EmBreve' });
  }
  return rotas;
}

// ---------------------------------------------------------------- cfg_tela (opcional)
function chavesDeCfgTela() {
  // ⚠️ `--cfg-tela=<caminho>` é o jeito confiável no Windows: o `/tmp` do Git Bash é
  // `%TEMP%`, e o Node resolve `/tmp` como `C:\tmp` — o arquivo que o psql acabou de gravar
  // simplesmente "não existe" para o script, e a contagem some sem erro nenhum.
  const arg = process.argv.find((a) => a.startsWith('--cfg-tela='));
  const candidatos = [
    arg && arg.slice('--cfg-tela='.length),
    process.env.TEMP && path.join(process.env.TEMP, 'cfg_tela.txt'),
    '/tmp/cfg_tela.txt',
  ].filter(Boolean);
  for (const candidato of candidatos) {
    try {
      return fs.readFileSync(candidato, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean);
    } catch { /* tenta o próximo */ }
  }
  return null;
}

// ---------------------------------------------------------------- relatório
const porSecao = rotasDoDocumentoPorSecao();
const filhas = filhasDoDocumento();
const rotasCodigo = rotasDoCodigo();
const cfgTela = chavesDeCfgTela();

const SECAO_PUBLICA = 'Entrada (públicas, sem login)';
const SECAO_FUTURAS = 'Implementações Futuras';

const publicas = porSecao.get(SECAO_PUBLICA) ?? [];
const futuras = porSecao.get(SECAO_FUTURAS) ?? [];
const emUso = [...porSecao.entries()]
  .filter(([s]) => s !== SECAO_PUBLICA && s !== SECAO_FUTURAS)
  .flatMap(([, r]) => r);

const rotasNoDoc = new Set([...publicas, ...futuras, ...emUso, ...filhas]);
// Rotas que o doc não precisa listar: a raiz, o hub de menu e o catch-all.
const IGNORAR = new Set(['/', '/menu/:grupo', '*']);

/**
 * As três ações de linha do padrão de cadastro (ver/editar/excluir) produzem rotas derivadas
 * — `/clientes/:id`, `/clientes/:id/visualizar`, `/ordens-servico/:id/:modo` — que o documento
 * NÃO lista de propósito: são o mesmo formulário em outro modo, não telas novas.
 *
 * ⚠️ Contadas à parte em vez de simplesmente ignoradas: some da lista de divergências (que
 * precisa ficar curta para ser lida), mas continua aparecendo como número. Filtro que esconde
 * sem contar é como a lista volta a divergir sem ninguém ver.
 */
function variantePadraoDeCadastro(rota) {
  const m = rota.match(/^(\/[^/]+(?:\/[^/:]+)*)\/:[^/]+(?:\/(?::[^/]+|visualizar))?$/);
  return m && rotasNoDoc.has(m[1]) ? m[1] : null;
}

const variantes = [];
const soNoCodigo = [];
for (const r of rotasCodigo) {
  if (IGNORAR.has(r.rota) || rotasNoDoc.has(r.rota)) continue;
  const base = variantePadraoDeCadastro(r.rota);
  if (base) variantes.push(`${r.rota} → ${base}`);
  else soNoCodigo.push(`${r.rota}  (${r.componente})`);
}
const rotasDoCodigoSet = new Set(rotasCodigo.map((r) => r.rota));
const soNoDoc = [...rotasNoDoc].filter((r) => !rotasDoCodigoSet.has(r));

/**
 * "Implementações Futuras" no doc tem de ser exatamente o conjunto de rotas que apontam para o
 * placeholder `<EmBreve>`. É a checagem que pega o defeito de
 * `feedback_placeholder_embreve_vira_rota_duplicada` pelo outro lado: ligar a tela e esquecer de
 * tirar o item da lista de futuras deixa o menu prometendo "em construção" para função pronta.
 */
const emBreveNoCodigo = new Set(rotasCodigo.filter((r) => r.emBreve).map((r) => r.rota));

/**
 * Exceções NOMEADAS — divergências que já foram vistas, decididas e registradas.
 *
 * ⚠️ A chave é ter um número de pendência do lado: exceção sem dono vira filtro que esconde. Ao
 * fechar a pendência, apague a linha daqui — se ela sobreviver, o script volta a apontar.
 */
const FUTURAS_COM_DIVERGENCIA_CONHECIDA = new Map([
  ['/', 'pendência #13 — o Painel é tela real e está listado como futura; promover ou manter é decisão do dono'],
]);

const futurasIncoerentes = [
  ...futuras.filter((r) => !emBreveNoCodigo.has(r) && !FUTURAS_COM_DIVERGENCIA_CONHECIDA.has(r))
    .map((r) => `${r}: doc diz "futura", código NÃO usa <EmBreve>`),
  ...[...emBreveNoCodigo].filter((r) => !futuras.includes(r)).map((r) => `${r}: código usa <EmBreve>, doc NÃO lista como futura`),
];

const resultado = {
  documento: {
    telasDoErpEmUso: emUso.length,
    publicas: publicas.length,
    implementacoesFuturas: futuras.length,
    telasFilhas: filhas.length,
    totalDeLinhasComRota: publicas.length + futuras.length + emUso.length,
  },
  codigo: {
    rotasEmAppTsx: rotasCodigo.length,
    apontandoParaEmBreve: rotasCodigo.filter((r) => r.emBreve).length,
    variantesDoPadraoDeCadastro: variantes.length,
  },
  cfgTela: cfgTela ? { chaves: cfgTela.length, comPonto: cfgTela.filter((c) => c.includes('.')).length } : null,
  divergencias: { soNoCodigo, soNoDoc, futurasIncoerentes },
};

const houveDivergencia = soNoCodigo.length || soNoDoc.length || futurasIncoerentes.length;

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(resultado, null, 2));
  process.exit(houveDivergencia ? 1 : 0);
}

console.log('== docs/TELAS.md (derivado das tabelas, não do texto) ==');
console.log(`  telas do ERP em uso .......... ${resultado.documento.telasDoErpEmUso}`);
console.log(`  públicas (sem login) ......... ${resultado.documento.publicas}`);
console.log(`  Implementações Futuras ....... ${resultado.documento.implementacoesFuturas}`);
console.log(`  linhas com rota (soma) ....... ${resultado.documento.totalDeLinhasComRota}`);
console.log(`  telas-filhas (bullets) ....... ${resultado.documento.telasFilhas}`);
console.log('\n== web/src/App.tsx ==');
console.log(`  rotas registradas ............ ${resultado.codigo.rotasEmAppTsx}`);
console.log(`  apontando para <EmBreve> ..... ${resultado.codigo.apontandoParaEmBreve}`);
console.log(`  variantes ver/editar ......... ${resultado.codigo.variantesDoPadraoDeCadastro}`);
if (cfgTela) {
  console.log('\n== cfg_tela (RBAC) ==');
  console.log(`  chaves ....................... ${resultado.cfgTela.chaves}`);
  console.log(`  sub-chaves (com ponto) ....... ${resultado.cfgTela.comPonto}`);
} else {
  console.log('\n== cfg_tela ==\n  (pulado: /tmp/cfg_tela.txt não existe — ver o cabeçalho deste arquivo)');
}

console.log('\n== divergências ==');
if (!houveDivergencia) {
  console.log('  nenhuma: toda rota do App.tsx está no doc, e vice-versa.');
} else {
  for (const r of soNoCodigo) console.log(`  ⚠️  no CÓDIGO e não no doc: ${r}`);
  for (const r of soNoDoc) console.log(`  ⚠️  no DOC e não no código:  ${r}`);
  for (const r of futurasIncoerentes) console.log(`  ⚠️  Implementações Futuras — ${r}`);
}
if (FUTURAS_COM_DIVERGENCIA_CONHECIDA.size) {
  console.log('\n== exceções nomeadas (vistas, decididas, registradas) ==');
  for (const [rota, motivo] of FUTURAS_COM_DIVERGENCIA_CONHECIDA) console.log(`  ${rota} — ${motivo}`);
}
process.exit(houveDivergencia ? 1 : 0);
