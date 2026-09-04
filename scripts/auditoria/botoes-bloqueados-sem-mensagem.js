/**
 * Botão desabilitado que não diz POR QUÊ.
 *
 * ## O que ele procura
 *
 * `disabled={!algumaCondicao}` cuja condição é composta (várias razões) sem que a tela mostre,
 * **junto do botão**, qual delas está bloqueando. É uma família de defeito que este repositório já
 * pagou várias vezes: o "Confirmar Entrada" ficava cinza por 13 motivos com mensagem para 2, e o
 * pior deles é a **permissão** — o operador olha o que preencheu e nunca desconfia da grade.
 *
 * ## ⚠️ O que mudou em 2026-09-04
 *
 * A primeira versão acusava **14**; ao conferir um a um, **8 eram falso positivo** — a mensagem
 * existia, só que numa forma que o script não reconhecia (`title=` no próprio botão, ou uma
 * instrução no topo do modal). Guarda que acusa falso treina quem lê a falha a ignorá-lo, então
 * agora ele:
 *
 * 1. aceita `title=` no próprio `<button>` (é mensagem, ainda que só no hover);
 * 2. carrega uma lista de **exceções conferidas**, cada uma com o motivo — no mesmo espírito das
 *    "exceções nomeadas" do `contagem-de-telas.js`.
 *
 * Sai com código 1 quando encontra algo fora da lista, para poder entrar em CI.
 */
const fs = require('fs')
const path = require('path')

/**
 * Botões conferidos um a um em 2026-09-04: a mensagem EXISTE, o script é que não a alcança.
 * ⚠️ Só entre aqui depois de abrir o arquivo e confirmar que o usuário vê a explicação.
 */
const CONFERIDOS = {
  'web/src/pages/fiscal/ExportacaoXmlLote.tsx|podeGerar':
    '"Escolha a empresa e informe a data inicial e final de emissão" fica acima do botão; as outras duas razões são estados transitórios que o próprio rótulo mostra ("Baixando…")',
  'web/src/pages/fiscal/ExportacaoXmlLote.tsx|podeBaixar':
    '"Nenhuma das notas do período tem XML arquivado ainda — não há o que exportar" fica logo acima do botão',
  'web/src/pages/relatorios/FiltrosComissoesModal.tsx|podeGerar':
    'o modal abre com "Informe o período (início e fim)." sob o título',
  'web/src/pages/relatorios/FiltrosContasPagarModal.tsx|podeGerar':
    'o modal abre com "Informe ao menos um período completo (início e fim) — Lançamento, Vencimento ou Pagamento"',
  'web/src/pages/relatorios/FiltrosContasReceberModal.tsx|podeGerar':
    'o modal abre com "Informe ao menos um período completo (início e fim) — Venda, Vencimento ou Recebimento"',
  'web/src/pages/relatorios/FiltrosVendasModal.tsx|podeGerar':
    'o modal mostra "Informe um período válido."',
}

function walk(d, acc = []) {
  for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, f.name)
    if (f.isDirectory()) walk(p, acc)
    else if (/\.tsx$/.test(f.name)) acc.push(p)
  }
  return acc
}

const achados = new Map()
for (const f of walk('.')) {
  const s = fs.readFileSync(f, 'utf8')
  const rel = path.relative('.', f).split(path.sep).join('/')
  for (const m of s.matchAll(/disabled=\{!(\w{4,})\b/g)) {
    const v = m[1]
    if (!/^(pode|tem|ha|permite|valido|esta)/i.test(v)) continue

    // (a) a tela renderiza algo sob a negação da mesma condição
    const sobCondicao = new RegExp('!' + v + '[\\s\\S]{0,40}&&[\\s\\S]{0,40}<').test(s)
    // (b) o arquivo calcula um "motivo" (padrão adotado em 2026-09-04)
    const temMotivo = /motivo|Motivo/.test(s)
    // (c) o próprio botão tem title — mensagem no hover conta
    const trecho = s.slice(m.index, m.index + 400)
    const temTitle = /title=/.test(trecho)

    if (sobCondicao || temMotivo || temTitle) continue
    achados.set(rel + '|' + v, { rel, v })
  }
}

const naLista = []
const novos = []
for (const [chave, dado] of achados) {
  if (CONFERIDOS[chave]) naLista.push({ chave, motivo: CONFERIDOS[chave] })
  else novos.push(dado)
}

console.log(`Botões bloqueados sem mensagem — NOVOS: ${novos.length} · conferidos e aceitos: ${naLista.length}`)
for (const { rel, v } of novos) console.log(`  ✗ ${rel}  ->  disabled={!${v}}`)

if (naLista.length) {
  console.log('\nExceções conferidas (a mensagem existe; o script é que não a alcança):')
  for (const { chave, motivo } of naLista) {
    console.log(`  · ${chave.split('|')[0].split('/').pop()} [${chave.split('|')[1]}] — ${motivo}`)
  }
}

// ⚠️ Entrada da lista que já não aparece = botão mudou. Avisar, para a lista não apodrecer.
const orfas = Object.keys(CONFERIDOS).filter((c) => !achados.has(c))
if (orfas.length) {
  console.log('\n⚠️ Exceções que o script já não encontra (o botão mudou? remova-as):')
  orfas.forEach((c) => console.log('  ? ' + c))
}

process.exit(novos.length ? 1 : 0)
