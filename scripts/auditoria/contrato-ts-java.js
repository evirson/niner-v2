#!/usr/bin/env node
/*
 * Confere o contrato entre os DTOs do TypeScript e os records do Java, casando por ENDPOINT.
 *
 * ⛔ O PROBLEMA QUE ISTO EXISTE PARA PEGAR
 * As interfaces de `web/src/lib/*.ts` são escritas À MÃO. Um campo renomeado no record do Java
 * vira `undefined` na tela — sem erro de compilação, sem teste vermelho, sem nada. A tela só
 * mostra um espaço em branco. É a mesma família de "classe CSS que não existe".
 *
 * ⚠️ POR QUE A VERSÃO ANTERIOR NÃO SERVIA (medido em 2026-08-30 e de novo em 2026-09-01)
 * Ela casava por NOME DE TIPO e errou 4 de 4 achados. O caso limpo: existe um `ResultadoEmissao`
 * em `web/src/lib/nfse.ts` (a NFS-e) e um `ResultadoEmissao` em `EmissaoNfceService` (a NFC-e) —
 * tipos diferentes, mesmo nome. Ela comparava um com o outro e listava como "campo faltando" toda
 * a diferença entre dois documentos fiscais que não têm nada a ver. Pior: com 558 records, nomes
 * repetidos são a regra, e ela fundia os campos de todos os homônimos num só conjunto.
 *
 * ⭐ Casar por endpoint remove a ambiguidade na origem: a URL + o método HTTP identificam UM
 * handler, e o handler tem UM tipo de retorno.
 *
 * Uso:  node scripts/auditoria/contrato-ts-java.js
 *       node scripts/auditoria/contrato-ts-java.js --verboso   (mostra também o que casou bem)
 *
 * ⛔ LIMITES DECLARADOS — o que este script NÃO pega, para ninguém confiar demais nele:
 *  - tipo do campo (um `int` que virou `String` passa despercebido; só o NOME é comparado);
 *  - DTO montado dinamicamente (`Map<String,Object>`), que não tem record para comparar;
 *  - campo que existe nos dois lados e mudou de SIGNIFICADO — nenhum script pega isso;
 *  - endpoint cuja URL o TS monta por concatenação de variável em vez de template literal.
 * Ele não sai com código 1: é ferramenta de leitura, e a taxa de casos que não consegue resolver
 * é alta demais para reprovar build. O guarda que reprova build é o `AcoesPorTelaConferemTest`.
 */

const fs = require('fs');
const path = require('path');

const raiz = path.resolve(__dirname, '..', '..');
const verboso = process.argv.includes('--verboso');

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else acc.push(p);
  }
  return acc;
}

const ler = (f) => fs.readFileSync(f, 'utf8').replace(/\r\n/g, '\n');

/** `/api/v1/nfse/vendas/{idVenda}/emitir` e `/api/v1/nfse/vendas/${id}/emitir` viram a mesma coisa. */
function normalizarRota(rota) {
  return rota
    .replace(/\$\{[^}]*\}/g, '{}')   // template literal do TS
    .replace(/\{[^}]*\}/g, '{}')     // @PathVariable do Java
    .replace(/\?.*$/, '')            // query string não faz parte da identidade do handler
    .replace(/\/+$/, '');
}

// ---------------------------------------------------------------- Java: records
function corpoBalanceado(t, i) {
  let n = 0;
  for (let j = i; j < t.length; j++) {
    const c = t[j];
    if (c === '(') n++;
    else if (c === ')') { n--; if (n === 0) return t.slice(i + 1, j); }
  }
  return null;
}

function tiraAnotacoes(s) {
  let out = '', i = 0;
  while (i < s.length) {
    if (s[i] === '@') {
      let j = i + 1;
      while (j < s.length && /[\w.]/.test(s[j])) j++;
      while (j < s.length && /\s/.test(s[j])) j++;
      if (s[j] === '(') { const c = corpoBalanceado(s, j); j = j + (c === null ? 1 : c.length + 2); }
      i = j;
      continue;
    }
    out += s[i++];
  }
  return out;
}

/** Remove comentários — um `//` com vírgula dentro quebra a divisão dos campos. */
function tiraComentarios(s) {
  return s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

function dividirNivel0(s) {
  const partes = [];
  let n = 0, cur = '';
  for (const c of s) {
    if (c === '<' || c === '(' || c === '[') n++;
    else if (c === '>' || c === ')' || c === ']') n--;
    if (c === ',' && n === 0) { partes.push(cur); cur = ''; } else cur += c;
  }
  if (cur.trim()) partes.push(cur);
  return partes;
}

/**
 * Todos os records, indexados por nome — mas guardando uma LISTA de definições por nome, não uma
 * fusão. Com 558 records o homônimo é a regra, e fundir os campos foi o defeito da versão antiga.
 */
function lerRecordsJava() {
  const porNome = new Map();
  for (const f of walk(path.join(raiz, 'api/src/main/java')).filter((x) => x.endsWith('.java'))) {
    const t = tiraComentarios(ler(f));
    const re = /\brecord\s+([A-Z]\w*)\s*(?:<[^>]*>)?\s*\(/g;
    let m;
    while ((m = re.exec(t))) {
      const nome = m[1];
      const abre = t.indexOf('(', m.index + m[0].length - 1);
      const corpo = corpoBalanceado(t, abre);
      if (corpo === null) continue;
      const campos = [];
      for (const parte of dividirNivel0(tiraAnotacoes(corpo))) {
        const p = parte.trim();
        if (!p) continue;
        const toks = p.split(/\s+/);
        const nc = toks[toks.length - 1].replace(/[^\w]/g, '');
        if (nc && /^[a-z_]/.test(nc)) campos.push(nc);
      }
      if (!porNome.has(nome)) porNome.set(nome, []);
      porNome.get(nome).push({ arquivo: path.relative(raiz, f), campos: new Set(campos) });
    }
  }
  return porNome;
}

// ---------------------------------------------------------------- Java: endpoints
const VERBOS = ['GetMapping', 'PostMapping', 'PutMapping', 'PatchMapping', 'DeleteMapping'];
const METODO_DE = {
  GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT',
  PatchMapping: 'PATCH', DeleteMapping: 'DELETE',
};

/** Desembrulha `ResponseEntity<List<Foo>>` → `Foo`. */
function tipoDeRetorno(assinatura) {
  let t = assinatura.trim();
  for (const casca of ['ResponseEntity', 'Optional', 'List', 'Set', 'Collection']) {
    const re = new RegExp('^' + casca + '\\s*<\\s*([\\s\\S]+)\\s*>$');
    const m = t.match(re);
    if (m) { t = m[1].trim(); return tipoDeRetorno(t); }
  }
  // Foo.Bar → Bar (record aninhado); Foo<X> → Foo
  return t.replace(/<[\s\S]*$/, '').split('.').pop().trim();
}

/**
 * ⚠️ Corta o arquivo NAS anotações de mapeamento e pega, em cada pedaço, o PRIMEIRO
 * {@code public <tipo> <nome>(}.
 *
 * <p>A primeira versão usava uma janela de 600 caracteres entre a anotação e o {@code public} —
 * e ela pulava para o método SEGUINTE quando havia javadoc longo no meio, casando a rota de um
 * handler com o tipo de retorno de outro. Foram 5 falsos positivos, todos plausíveis à primeira
 * vista (por exemplo `PUT /api/v1/config-geral` apontando para `UsaCorGradeResponse`). Cortar em
 * pedaços elimina a possibilidade em vez de reduzi-la.
 */
function lerEndpointsJava() {
  const endpoints = new Map(); // "GET /api/v1/x" -> {tipo, arquivo}
  const marcador = new RegExp('@(' + VERBOS.join('|') + ')', 'g');

  for (const f of walk(path.join(raiz, 'api/src/main/java')).filter((x) => x.endsWith('.java'))) {
    const t = tiraComentarios(ler(f));
    const base = (t.match(/@RequestMapping\(\s*"([^"]*)"/) || [, ''])[1];

    const cortes = [];
    let m;
    marcador.lastIndex = 0;
    while ((m = marcador.exec(t))) cortes.push({ verbo: m[1], inicio: m.index });

    for (let i = 0; i < cortes.length; i++) {
      const fim = i + 1 < cortes.length ? cortes[i + 1].inicio : t.length;
      const pedaco = t.slice(cortes[i].inicio, fim);

      const sufixo = (pedaco.match(/^@\w+\(\s*(?:value\s*=\s*)?"([^"]*)"/) || [, ''])[1];
      const assinatura = pedaco.match(/\bpublic\s+([\w.<>\[\], ]+?)\s+(\w+)\s*\(/);
      if (!assinatura) continue;

      const rota = normalizarRota(base + sufixo);
      endpoints.set(METODO_DE[cortes[i].verbo] + ' ' + rota, {
        tipo: tipoDeRetorno(assinatura[1]),
        arquivo: path.relative(raiz, f),
        metodo: assinatura[2],
      });
    }
  }
  return endpoints;
}

// ---------------------------------------------------------------- TS: chamadas api<T>(url, {method})
function lerChamadasTs() {
  const chamadas = [];
  for (const f of walk(path.join(raiz, 'web/src')).filter((x) => x.endsWith('.ts') || x.endsWith('.tsx'))) {
    const t = ler(f);
    // api<Tipo>(`/rota`, { method: 'POST' ... })   — a crase e as aspas são aceitas
    const re = /\bapi<([^>]+)>\(\s*[`'"]([^`'"]+)[`'"]([\s\S]{0,120}?)\)/g;
    let m;
    while ((m = re.exec(t))) {
      const tipoBruto = m[1].trim();
      const rota = normalizarRota(m[2]);
      const metodo = (m[3].match(/method:\s*'(\w+)'/) || [, 'GET'])[1].toUpperCase();
      chamadas.push({ tipoBruto, rota, metodo, arquivo: path.relative(raiz, f) });
    }
  }
  return chamadas;
}

/** `Pagina<Cliente>` → Cliente; `Foo[]` → Foo; `Foo | undefined` → Foo. */
function tipoTsPrincipal(bruto) {
  let t = bruto.replace(/\s*\|\s*(undefined|null)/g, '').trim();
  const generico = t.match(/^\w+<([\s\S]+)>$/);
  if (generico) t = generico[1].trim();
  t = t.replace(/\[\]$/, '').trim();
  return t;
}

/** Corpo de `{` a `}` com chaves BALANCEADAS, a partir do índice da `{` de abertura. */
function corpoChaves(t, i) {
  let n = 0;
  for (let j = i; j < t.length; j++) {
    if (t[j] === '{') n++;
    else if (t[j] === '}') { n--; if (n === 0) return t.slice(i + 1, j); }
  }
  return null;
}

/**
 * ⚠️ Só os campos de NÍVEL 0.
 *
 * <p>A primeira versão usava `\{([^}]*)\}`, que para na primeira `}` — a de um objeto ANINHADO.
 * Em `DevolucaoCompraEfetivada`, que tem `itens: Array<{ idVariacao, sku, … }>`, ela lia os campos
 * do ITEM e os comparava com o record da RESPOSTA, acusando cinco campos "faltando" que existem,
 * só que um nível abaixo. Foi o último falso positivo a cair.
 */
function camposDeNivel0(corpo) {
  const campos = [];
  let profundidade = 0;
  for (const linha of corpo.split('\n')) {
    const m = linha.match(/^\s*(\w+)\??\s*:/);
    if (m && profundidade === 0) campos.push(m[1]);
    for (const c of linha) {
      if (c === '{' || c === '[' || c === '(') profundidade++;
      else if (c === '}' || c === ']' || c === ')') profundidade--;
    }
  }
  return campos;
}

function lerInterfacesTs() {
  const porNome = new Map();
  for (const f of walk(path.join(raiz, 'web/src')).filter((x) => x.endsWith('.ts') || x.endsWith('.tsx'))) {
    const t = ler(f);
    const re = /export\s+(?:interface|type)\s+([A-Z]\w*)\s*=?\s*\{/g;
    let m;
    while ((m = re.exec(t))) {
      const abre = t.indexOf('{', m.index + m[0].length - 1);
      const corpo = corpoChaves(t, abre);
      if (corpo === null) continue;
      porNome.set(m[1], { campos: camposDeNivel0(corpo), arquivo: path.relative(raiz, f) });
    }
  }
  return porNome;
}

// ---------------------------------------------------------------- confronto
const recordsJava = lerRecordsJava();
const endpointsJava = lerEndpointsJava();
const chamadas = lerChamadasTs();
const interfacesTs = lerInterfacesTs();

const problemas = [];
const naoResolvidos = [];
let casados = 0;

for (const c of chamadas) {
  const chave = c.metodo + ' ' + c.rota;
  const endpoint = endpointsJava.get(chave);
  if (!endpoint) { naoResolvidos.push({ ...c, motivo: 'nenhum handler Java com esta rota+método' }); continue; }

  const nomeTs = tipoTsPrincipal(c.tipoBruto);
  const ts = interfacesTs.get(nomeTs);
  if (!ts) { naoResolvidos.push({ ...c, motivo: 'tipo TS "' + nomeTs + '" não é uma interface declarada' }); continue; }

  const definicoes = recordsJava.get(endpoint.tipo);
  if (!definicoes) { naoResolvidos.push({ ...c, motivo: 'retorno Java "' + endpoint.tipo + '" não é um record' }); continue; }
  if (definicoes.length > 1) {
    naoResolvidos.push({ ...c, motivo: 'record Java "' + endpoint.tipo + '" é ambíguo (' + definicoes.length + ' homônimos)' });
    continue;
  }

  casados++;
  const camposJava = definicoes[0].campos;
  const faltando = ts.campos.filter((x) => !camposJava.has(x));
  if (faltando.length) {
    problemas.push({
      rota: chave, tsArquivo: ts.arquivo, tsTipo: nomeTs,
      javaArquivo: definicoes[0].arquivo, javaTipo: endpoint.tipo,
      faltando, java: [...camposJava],
    });
  }
}

console.log('Chamadas api<T>() no TS: ' + chamadas.length
  + ' | endpoints Java: ' + endpointsJava.size
  + ' | casados por ENDPOINT: ' + casados
  + ' | não resolvidos: ' + naoResolvidos.length);

console.log('\n=== TS declara campo que o record Java do MESMO ENDPOINT não tem (' + problemas.length + ') ===');
for (const p of problemas) {
  console.log('\n' + p.rota);
  console.log('  TS  : ' + p.tsTipo + '  (' + p.tsArquivo + ')');
  console.log('  Java: ' + p.javaTipo + '  (' + p.javaArquivo + ')');
  console.log('  só no TS: ' + p.faltando.join(', '));
  console.log('  no Java : ' + p.java.join(', '));
}
if (!problemas.length) console.log('  nenhum.');

if (verboso) {
  console.log('\n=== não resolvidos (o script não consegue afirmar nada sobre estes) ===');
  for (const n of naoResolvidos) console.log('  ' + n.metodo + ' ' + n.rota + '  — ' + n.motivo);
}
