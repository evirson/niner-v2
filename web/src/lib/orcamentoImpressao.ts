import { formatarMoeda } from './masks'
import type { Orcamento } from './orcamento'

/**
 * Texto do orçamento para a **bobina térmica de 80mm**, no mesmo formato da papeleta de venda.
 *
 * <p>⚠️ **42 colunas**, item em 2 linhas — a calibragem está em `docs/telas/papeleta-venda.md` e
 * vale para todo comprovante do produto: largura da área imprimível 75mm (não os 80 do papel),
 * `left: 0`, Consolas em **negrito** (térmica é 1 bit: haste fina sai falhada) e
 * `print-color-adjust: exact`. Documento que não couber em 42 colunas é **reorganizado**, nunca
 * espremido na largura.
 *
 * <p>Nada disso aparece na tela, no PDF ou em teste automatizado — só imprimindo.
 */
const LARGURA = 42

function linha(caractere = '-'): string {
  return caractere.repeat(LARGURA)
}

function centralizar(texto: string): string {
  const t = texto.slice(0, LARGURA)
  const espacos = Math.max(0, Math.floor((LARGURA - t.length) / 2))
  return ' '.repeat(espacos) + t
}

/** Rótulo à esquerda, valor à direita, preenchendo a largura. */
function doisLados(esquerda: string, direita: string): string {
  const espaco = Math.max(1, LARGURA - esquerda.length - direita.length)
  return esquerda + ' '.repeat(espaco) + direita
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

/** `data_validade` vem como `aaaa-mm-dd` puro — `new Date()` nele interpretaria como UTC e podia
 *  voltar um dia. Formatação manual evita isso. */
function formatarDataSimples(iso: string): string {
  const [ano, mes, dia] = iso.split('-')
  return `${dia}/${mes}/${ano}`
}

export function montarLinhasOrcamentoBobina(o: Orcamento): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar('ORCAMENTO'))
  linhas.push(centralizar(`No ${o.idOrcamento}`))
  linhas.push(linha())
  linhas.push(centralizar(o.nomeEmpresa))
  linhas.push(linha())
  linhas.push(doisLados('Emissao:', formatarData(o.dataOrcamento)))
  linhas.push(doisLados('Valido ate:', formatarDataSimples(o.dataValidade)))
  linhas.push(`Cliente: ${o.nomeCliente}`.slice(0, LARGURA))
  if (o.documentoCliente) linhas.push(`Doc: ${o.documentoCliente}`)
  linhas.push(`Vendedor: ${o.nomeFuncionario}`.slice(0, LARGURA))
  linhas.push(linha())

  // Item em 2 linhas: descrição na primeira, números na segunda — em 42 colunas não cabe tudo
  // numa linha só, e espremer a largura faria o driver encolher a fonte inteira.
  for (const item of o.itens) {
    const variacao = [item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' ')
    linhas.push(`${item.descricao}${variacao ? ' ' + variacao : ''}`.slice(0, LARGURA))
    linhas.push(
      doisLados(
        `  ${item.qtd} x ${formatarMoeda(item.precoVenda)}`,
        formatarMoeda(item.valorTotal),
      ),
    )
  }

  linhas.push(linha())
  linhas.push(doisLados('SUBTOTAL', formatarMoeda(o.subtotal)))
  if (o.valorDesconto > 0) linhas.push(doisLados('DESCONTO', formatarMoeda(o.valorDesconto)))
  linhas.push(doisLados('TOTAL', formatarMoeda(o.valorTotal)))
  linhas.push(linha())

  if (o.observacao) {
    linhas.push('OBSERVACOES:')
    // Quebra manual em 42 colunas, sem cortar palavra no meio.
    let atual = ''
    for (const palavra of o.observacao.split(/\s+/)) {
      if ((atual + ' ' + palavra).trim().length > LARGURA) {
        linhas.push(atual.trim())
        atual = palavra
      } else {
        atual = (atual + ' ' + palavra).trim()
      }
    }
    if (atual) linhas.push(atual)
    linhas.push(linha())
  }

  linhas.push(centralizar('ESTE ORCAMENTO NAO E'))
  linhas.push(centralizar('DOCUMENTO FISCAL'))
  linhas.push(linha())
  return linhas
}
