/**
 * Classes CSS usadas no JSX que NÃO existem no CSS — a família de defeito que não dá erro em lugar
 * nenhum: passa no `tsc -b`, no build e na suíte, e o elemento só sai sem estilo.
 *
 * Uso:  node scripts/auditoria/classes-css-orfas.js [web|admin]     (padrão: os dois)
 *
 * ⚠️ DOIS FALSOS POSITIVOS CORRIGIDOS EM 2026-09-02, e os dois enganavam à primeira vista — a
 * versão anterior acusava 15 classes, das quais 8 não eram uso nenhum:
 *
 *   1. **Comentário que CITA uma classe inexistente** para explicar que ela não existe. O
 *      `ComprovantePapeletaModal` tem um comentário dizendo *"escrevi className='campo' e essa
 *      classe não existe"* — e o script reportava `campo` como uso real.
 *   2. **O literal do LADO ESQUERDO de um ternário.** `aba === 'MERCADORIA' ? 'aba ativa' : 'aba'`
 *      tem três strings e só duas são classe: a primeira é um valor de domínio. Era daí que saíam
 *      `f2`/`f3`/`f4`/`f5` (teclas do PDV), `caixa`, `geral`, `produtos`, `parcelas`.
 *
 * ⭐ Guarda que acusa falso treina quem lê a falha a ignorá-lo — e este ia por esse caminho.
 *
 * ⛔ LIMITES DECLARADOS (o que ele continua sem ver):
 *   - `className={`x-${v}`}` — classe montada por interpolação; ele CONTA e lista, não confere;
 *   - `className={variavel}` — nome vindo de outro lugar;
 *   - CSS **injetado em runtime** (`document.createElement('style')`), que é como
 *     `orcamento-imprimir-bobina` e `os-imprimir-*` são declaradas: aparecem como órfãs e não são.
 *   - classes aplicadas por seletor de elemento/atributo no CSS.
 */
const fs = require('fs');
const path = require('path');

const ALVOS = { web: 'web', admin: 'admin' };
const pedido = process.argv[2];
const projetos = pedido ? [ALVOS[pedido]].filter(Boolean) : Object.values(ALVOS);
if (!projetos.length) {
  console.error('uso: node scripts/auditoria/classes-css-orfas.js [web|admin]');
  process.exit(2);
}

/** Remove comentários JSX (`{/* … *\/}`) e de bloco/linha antes de procurar `className`. */
function semComentario(texto) {
  return texto
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/^\s*(\/\/|\*).*$/, ''))
    .join('\n');
}

function varrer(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) varrer(p, acc);
    else acc.push(p);
  }
  return acc;
}

let houveOrfa = false;

for (const projeto of projetos) {
  const raiz = path.join(projeto, 'src');
  if (!fs.existsSync(raiz)) continue;
  const arquivos = varrer(raiz);

  const declaradas = new Set();
  for (const f of arquivos.filter((f) => f.endsWith('.css'))) {
    for (const m of fs.readFileSync(f, 'utf8').matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)) declaradas.add(m[1]);
  }

  const usadas = new Map();
  const interpoladas = [];
  const anotar = (bruto, arquivo) => {
    for (const c of bruto.replace(/\$\{[^}]*\}/g, ' ').split(/\s+/).filter(Boolean)) {
      if (/[${}`'"]/.test(c)) continue;
      if (!usadas.has(c)) usadas.set(c, new Set());
      usadas.get(c).add(arquivo);
    }
  };

  for (const f of arquivos.filter((f) => /\.(tsx|ts)$/.test(f))) {
    const t = semComentario(fs.readFileSync(f, 'utf8'));
    for (const m of t.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\}|\{'([^']*)'\})/g)) {
      const bruto = m[1] || m[2] || m[3] || '';
      if (/\$\{/.test(bruto)) {
        // ⚠️ Fragmento de classe interpolada NÃO é uso: `toast-${tipo}` deixaria "toast-" como
        // órfã, e "toast-" não é classe nenhuma. Conta como não-conferido e segue.
        interpoladas.push([f, bruto]);
        continue;
      }
      anotar(bruto, f);
    }
    // Expressão: só o que vem DEPOIS do `?` é valor do className; antes dele está a condição.
    for (const m of t.matchAll(/className=\{([^}]*)\}/g)) {
      // ⚠️ Fora as COMPARAÇÕES antes de cortar: num ternário aninhado
      // (`s === 'CONVERTIDO' ? 'tag-ok' : s === 'PERDIDO' ? …`) o valor de domínio do segundo
      // teste sobrevive ao corte do primeiro `?` e vira falso positivo.
      const expr = m[1].replace(/[=!]==?\s*['"][^'"]*['"]/g, '');
      const corte = expr.indexOf('?');
      const valores = corte >= 0 ? expr.slice(corte + 1) : expr;
      for (const lit of valores.matchAll(/['"]([^'"]*)['"]/g)) anotar(lit[1], f);
    }
  }

  const orfas = [...usadas.entries()].filter(([c]) => !declaradas.has(c)).sort();
  console.log(`\n== ${projeto} ==`);
  console.log(`   ${usadas.size} classes usadas · ${declaradas.size} declaradas · `
    + `${interpoladas.length} className com interpolação (NÃO conferidos)`);
  if (!orfas.length) {
    console.log('   nenhuma classe órfã');
  } else {
    houveOrfa = true;
    for (const [c, onde] of orfas) console.log(`   ✗ ${c}  ←  ${[...onde].join(', ')}`);
  }
}

console.log('\n⚠️ Órfã listada NÃO é necessariamente defeito: confira se a classe é declarada em CSS'
  + ' injetado em runtime (o `@page` do orçamento e da OS) ou se é uma base semântica cujos'
  + ' modificadores existem (`dre-linha` + `dre-linha-grupo`). O que o script garante é a LISTA'
  + ' curta para conferir — não o veredito.');
process.exitCode = 0;   // relatório, não guarda: não reprova build
