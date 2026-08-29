import { formatarMoeda } from './masks'
import type { OrdemServico } from './ordensServico'

/**
 * Texto da Ordem de Serviço para a **bobina térmica de 80mm** — a via que o cliente leva ao
 * deixar o carro (ou o animal) na loja.
 *
 * <p>⚠️ **42 colunas**, item em 2 linhas. A calibragem está em `docs/telas/papeleta-venda.md` e
 * vale para todo comprovante do produto: largura da área imprimível 75mm (não os 80 do papel),
 * `left: 0`, Consolas em **negrito** (térmica é 1 bit: haste fina sai falhada) e
 * `print-color-adjust: exact`. Documento que não couber em 42 colunas é **reorganizado**, nunca
 * espremido na largura.
 *
 * <p>⚠️ Nada disso aparece na tela, no PDF ou em teste automatizado — só imprimindo.
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

function doisLados(esquerda: string, direita: string): string {
  const espaco = Math.max(1, LARGURA - esquerda.length - direita.length)
  return esquerda + ' '.repeat(espaco) + direita
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * Quantidade como o resto do produto imprime: vírgula decimal, sem casa sobrando no inteiro.
 *
 * ⚠️ Interpolar o `number` cru saía "2.5" com PONTO na via do cliente (achado de auditoria,
 * 2026-08-29) — enquanto a papeleta de venda da mesma loja imprime "2,5" e a grade da própria OS
 * mostra "2,500". Não erra dinheiro; erra o documento entregue ao cliente.
 *
 * ⚠️ Exportado desde 2026-08-29: a via A4 da OS usava `formatarQuantidade(x, true)` (sempre 3
 * casas) e imprimia "2,000" enquanto esta bobina imprimia "2", no mesmo atendimento. Duas vias do
 * mesmo documento têm de dizer o mesmo número — a regra mora num lugar só.
 */
export function qtdImpressa(qtd: number): string {
  return Number.isInteger(qtd) ? String(qtd) : String(qtd).replace('.', ',')
}

/** Quebra em 42 colunas sem cortar palavra no meio. */
function quebrar(texto: string): string[] {
  const saida: string[] = []
  let atual = ''
  for (const palavra of texto.split(/\s+/)) {
    if ((atual + ' ' + palavra).trim().length > LARGURA) {
      if (atual) saida.push(atual.trim())
      atual = palavra
    } else {
      atual = (atual + ' ' + palavra).trim()
    }
  }
  if (atual) saida.push(atual)
  return saida
}

export function montarLinhasOrdemServicoBobina(os: OrdemServico): string[] {
  const linhas: string[] = []
  linhas.push(linha())
  linhas.push(centralizar('ORDEM DE SERVICO'))
  linhas.push(centralizar(`No ${os.idOrdemServico}`))
  linhas.push(linha())
  linhas.push(centralizar(os.nomeEmpresa))
  linhas.push(linha())
  linhas.push(doisLados('Abertura:', formatarData(os.dataAbertura)))
  linhas.push(doisLados('Situacao:', os.situacao.replace('_', ' ')))
  linhas.push(`Cliente: ${os.nomeCliente}`.slice(0, LARGURA))
  if (os.documentoCliente) linhas.push(`Doc: ${os.documentoCliente}`)
  if (os.telefoneCliente) linhas.push(`Fone: ${os.telefoneCliente}`)
  linhas.push(`Responsavel: ${os.nomeFuncionario}`.slice(0, LARGURA))
  linhas.push(linha())
  // O objeto é o que o cliente confere primeiro — a placa do carro, o nome do animal. Fica
  // sozinho e quebrado em linhas, nunca cortado: um "GOL PRATA ABC1D2" sem o "3" final é pior
  // que duas linhas.
  linhas.push('OBJETO DO SERVICO:')
  for (const l of quebrar(os.objetoServico)) linhas.push(l)
  linhas.push(linha())

  // ⭐ Serviços e peças em BLOCOS SEPARADOS, como na tela. É a pergunta que o dono da oficina faz
  // ("quanto foi mão de obra, quanto foi material") e a divisão que a NFS-e vai precisar.
  const servicos = os.itens.filter((i) => i.tipoItem === 'SERVICO')
  const pecas = os.itens.filter((i) => i.tipoItem === 'MERCADORIA')

  if (servicos.length > 0) {
    linhas.push('SERVICOS:')
    for (const item of servicos) {
      linhas.push(`${item.descricaoProduto}`.slice(0, LARGURA))
      // O executor sai na via do cliente de propósito: numa oficina ele é a referência de quem
      // procurar quando o serviço volta.
      if (item.nomeFuncionario) linhas.push(`  por ${item.nomeFuncionario}`.slice(0, LARGURA))
      linhas.push(doisLados(`  ${qtdImpressa(item.qtdProduto)} x ${formatarMoeda(item.precoVenda)}`,
        formatarMoeda(item.total)))
    }
    linhas.push(doisLados('  Subtotal servicos', formatarMoeda(os.totalServicos)))
    linhas.push(linha())
  }

  if (pecas.length > 0) {
    linhas.push('PECAS:')
    for (const item of pecas) {
      const variacao = [item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' ')
      linhas.push(`${item.descricaoProduto}${variacao ? ' ' + variacao : ''}`.slice(0, LARGURA))
      linhas.push(doisLados(`  ${qtdImpressa(item.qtdProduto)} x ${formatarMoeda(item.precoVenda)}`,
        formatarMoeda(item.total)))
    }
    linhas.push(doisLados('  Subtotal pecas', formatarMoeda(os.totalPecas)))
    linhas.push(linha())
  }

  if (os.valorDesconto > 0) linhas.push(doisLados('DESCONTO', formatarMoeda(os.valorDesconto)))
  linhas.push(doisLados('TOTAL', formatarMoeda(os.total)))
  linhas.push(linha())

  if (os.observacao) {
    linhas.push('OBSERVACOES:')
    for (const l of quebrar(os.observacao)) linhas.push(l)
    linhas.push(linha())
  }

  linhas.push(centralizar('ESTA ORDEM DE SERVICO NAO E'))
  linhas.push(centralizar('DOCUMENTO FISCAL'))
  linhas.push(linha())
  return linhas
}
