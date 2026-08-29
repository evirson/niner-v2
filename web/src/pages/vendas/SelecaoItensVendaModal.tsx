import CabecalhoModal from '../../components/CabecalhoModal'
import { useMemo, useState } from 'react'
import { ApiError } from '../../lib/api'
import { buscarVendedorDaVenda, type ItemVendaOrigem, type VendedorDaVenda } from '../../lib/devolucaoProduto'
import { formatarMoeda, formatarQuantidade } from '../../lib/masks'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function variacaoTexto(item: ItemVendaOrigem): string | null {
  if (!item.variacaoCor && !item.variacaoTamanho) return null
  return [item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' · ')
}

/**
 * Popup obrigatório de Devolução de Produtos quando `cfg_exige_numero_venda_devolucao` está
 * LIGADO (revisão 2026-08-19 de `docs/telas/devolucao-produtos.md`): pede o número da venda,
 * lista o que ela vendeu e deixa o operador escolher o que volta — **substituindo a leitura por
 * código de barras nesse modo** (decisão do dono do produto; com o número obrigatório, escolher
 * numa lista curta é mais rápido que bipar item a item e o sistema recusar o que não pertence à
 * venda). Desligado, a tela continua exatamente como sempre foi: campo opcional inline + leitura
 * de código de barras livre.
 *
 * ⚠️ **Uma linha por PREÇO, não por variação** (2026-08-22, auditoria item 2). Desde o Orçamento,
 * o mesmo produto pode aparecer duas vezes na mesma venda — a peça orçada (preço congelado que a
 * loja honra) e a levada na hora (preço do dia). As duas linhas têm descrição IDÊNTICA e só o preço
 * as separa, então cada uma ganha o aviso dizendo qual é qual; a seleção é por `chaveLinha`
 * (`idVariacao|preço`), senão marcar uma marcava as duas e devolver uma peça consumia o saldo das
 * duas.
 *
 * O que sai daqui já vem validado contra a venda (só itens dela, até `qtdDisponivelDevolucao`), o
 * que **não** dispensa a validação do servidor — `DevolucaoProdutoService.efetivar` recalcula
 * tudo (P4, RN-06). "Selecionar" leva a quantidade disponível inteira; devolução parcial de um
 * item se ajusta depois, no campo de quantidade da grid principal (decisão via `AskUserQuestion`
 * — evita dois lugares editando quantidade).
 *
 * Botão "Fechar" sai da tela ([[project_botao_fechar_tela]]: todo popup obrigatório-de-entrada
 * precisa de uma saída), "Confirmar" devolve os itens escolhidos pro `DevolucaoProduto.tsx`.
 */
export default function SelecaoItensVendaModal({
  aoConfirmar,
  aoFechar,
  permiteQtdDecimal,
}: {
  aoConfirmar: (venda: VendedorDaVenda, itensSelecionados: ItemVendaOrigem[]) => void
  aoFechar: () => void
  permiteQtdDecimal: boolean
}) {
  const [numeroVendaTexto, setNumeroVendaTexto] = useState('')
  const [venda, setVenda] = useState<VendedorDaVenda | null>(null)
  const [buscando, setBuscando] = useState(false)
  const [erro, setErro] = useState('')
  /**
   * Chave `idVariacao|preco` dos itens marcados — só itens com quantidade disponível entram aqui.
   *
   * ⚠️ Era `id_variacao` puro até 2026-08-22 (auditoria, item 2). Desde o orçamento a mesma
   * variação pode vir DUAS VEZES na mesma venda, com preços diferentes: marcar uma marcava as
   * duas, e devolver uma peça consumia as duas linhas.
   */
  const [selecionados, setSelecionados] = useState<Set<string>>(new Set())

  /** Mesma chave usada pela tela de Devolução — variação + preço, com 2 casas fixas. */
  const chaveLinha = (idVariacao: number, precoUnitario: number) => `${idVariacao}|${precoUnitario.toFixed(2)}`

  const itensDisponiveis = useMemo(
    () => (venda?.itens ?? []).filter((i) => i.qtdDisponivelDevolucao > 0),
    [venda],
  )

  const localizar = async () => {
    const numero = Number(numeroVendaTexto.trim())
    if (!numeroVendaTexto.trim() || !Number.isFinite(numero) || numero <= 0) {
      setErro('Informe o número da venda.')
      return
    }
    setBuscando(true)
    setErro('')
    setVenda(null)
    setSelecionados(new Set())
    try {
      const resultado = await buscarVendedorDaVenda(numero)
      setVenda(resultado)
      if (resultado.itens.length === 0) {
        setErro('Esta venda não tem itens que possam ser devolvidos.')
      }
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível localizar a venda.')
    } finally {
      setBuscando(false)
    }
  }

  const alternarSelecao = (chave: string) => {
    setSelecionados((atual) => {
      const novo = new Set(atual)
      if (novo.has(chave)) {
        novo.delete(chave)
      } else {
        novo.add(chave)
      }
      return novo
    })
  }

  const todosSelecionados = itensDisponiveis.length > 0 && selecionados.size === itensDisponiveis.length

  const alternarTodos = () => {
    setSelecionados(
      todosSelecionados ? new Set() : new Set(itensDisponiveis.map((i) => chaveLinha(i.idVariacao, i.precoUnitario))),
    )
  }

  const confirmar = () => {
    if (!venda || selecionados.size === 0) return
    aoConfirmar(venda, itensDisponiveis.filter((i) => selecionados.has(chaveLinha(i.idVariacao, i.precoUnitario))))
  }

  return (
    <div className="modal-overlay">
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="Selecionar itens da venda para devolução"
        onClick={(e) => e.stopPropagation()}
        // Mais largo que o `.modal-largo` padrão (1040px): são 6 colunas + botão de ação, e a
        // descrição do produto (nome + cor + tamanho) é o campo mais longo do sistema — a 1040px
        // a coluna do botão "Selecionar" saía fora da área visível, atrás de um scroll horizontal.
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', maxHeight: '85vh', maxWidth: 1240 }}
      >
        <CabecalhoModal titulo="Devolução de Produtos" aoFechar={aoFechar} />
        <p className="muted" style={{ marginTop: 4, flexShrink: 0 }}>
          Informe o número da venda e escolha os produtos que o cliente está devolvendo.
        </p>

        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', marginTop: 16, flexShrink: 0 }}>
          <div style={{ maxWidth: 220 }}>
            <label htmlFor="selecao-numero-venda">Número da Venda *</label>
            <input
              id="selecao-numero-venda"
              autoFocus
              type="text"
              inputMode="numeric"
              autoComplete="off"
              value={numeroVendaTexto}
              onChange={(e) => setNumeroVendaTexto(e.target.value.replace(/\D/g, ''))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  localizar()
                }
              }}
            />
          </div>
          <button type="button" className="btn" style={{ height: 44 }} disabled={buscando} onClick={localizar}>
            {buscando ? 'Localizando…' : 'Localizar Venda'}
          </button>
          {venda && (
            <p className="muted" style={{ margin: 0, paddingBottom: 12 }}>
              {venda.idFuncionario ? (
                <>
                  Vendedor: <strong>{venda.nomeFuncionario}</strong>
                </>
              ) : (
                'Sem vendedor associado a esta venda.'
              )}
            </p>
          )}
        </div>

        {erro && (
          <p className="erro" style={{ marginTop: 12, flexShrink: 0 }}>
            {erro}
          </p>
        )}

        <div style={{ overflowY: 'auto', flex: 1, minHeight: 0, marginTop: 16 }}>
          {venda && venda.itens.length > 0 && (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <p className="section-label" style={{ margin: 0 }}>
                  Produtos desta venda
                </p>
                {itensDisponiveis.length > 0 && (
                  <button type="button" className="btn ghost" onClick={alternarTodos}>
                    {todosSelecionados ? 'Limpar Seleção' : 'Selecionar Todos'}
                  </button>
                )}
              </div>
              <div className="table-wrap">
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Código de Barras</th>
                      <th>Descrição do Produto</th>
                      <th style={{ textAlign: 'right' }}>Qtd. Vendida</th>
                      <th style={{ textAlign: 'right' }}>Preço Unitário</th>
                      <th style={{ textAlign: 'right' }}>Preço Total</th>
                      <th aria-label="Selecionar" />
                    </tr>
                  </thead>
                  <tbody>
                    {venda.itens.map((item) => {
                      const variacao = variacaoTexto(item)
                      const indisponivel = item.qtdDisponivelDevolucao <= 0
                      const marcado = selecionados.has(chaveLinha(item.idVariacao, item.precoUnitario))
                      // ⚠️ A mesma variação pode ter mais de uma linha nesta venda, com preços
                      // diferentes (o congelado do orçamento e o do dia). Quando isso acontece, as
                      // linhas têm descrição IDÊNTICA e só o preço as separa — sem este aviso o
                      // operador escolheria no escuro qual peça está devolvendo (auditoria, item 2).
                      const temOutroPreco = venda.itens.some(
                        (o) => o.idVariacao === item.idVariacao && o.precoUnitario !== item.precoUnitario,
                      )
                      return (
                        <tr key={chaveLinha(item.idVariacao, item.precoUnitario)} className={marcado ? 'linha-selecionada' : undefined}>
                          <td className="mono">{item.sku}</td>
                          <td style={{ minWidth: 260 }}>
                            {item.descricaoProduto}
                            {variacao && <span className="muted"> · {variacao}</span>}
                            {temOutroPreco && (
                              <p className="muted" style={{ margin: '2px 0 0', fontSize: 12 }}>
                                ⚠️ Este produto foi vendido por mais de um preço nesta venda — esta linha é a
                                de {moeda(item.precoUnitario)}.
                              </p>
                            )}
                            {indisponivel && (
                              <p className="muted" style={{ margin: '2px 0 0', fontSize: 12 }}>
                                Já devolvido por completo.
                              </p>
                            )}
                            {!indisponivel && item.qtdDisponivelDevolucao < item.qtdVendida && (
                              <p className="muted" style={{ margin: '2px 0 0', fontSize: 12 }}>
                                Disponível para devolução:{' '}
                                {formatarQuantidade(item.qtdDisponivelDevolucao, permiteQtdDecimal)}
                              </p>
                            )}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {formatarQuantidade(item.qtdVendida, permiteQtdDecimal)}
                          </td>
                          {/* ⚠️ Unitário LÍQUIDO — o que o cliente pagou por unidade (2026-08-29).
                              Mostrar o bruto ao lado de um total líquido fazia a linha não fechar:
                              1 × R$ 100,00 aparecia com total R$ 90,00. O bruto continua no
                              `title`, porque é ele que identifica a linha da venda. */}
                          <td
                            className="mono"
                            style={{ textAlign: 'right' }}
                            title={
                              item.descontoUnitario > 0
                                ? `Preço da venda ${moeda(item.precoUnitario)} − desconto ${moeda(item.descontoUnitario)}`
                                : undefined
                            }
                          >
                            {moeda(item.precoUnitario - item.descontoUnitario)}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {moeda(item.valorTotal)}
                          </td>
                          <td style={{ textAlign: 'right' }}>
                            <button
                              type="button"
                              className={marcado ? 'btn' : 'btn ghost'}
                              disabled={indisponivel}
                              onClick={() => alternarSelecao(chaveLinha(item.idVariacao, item.precoUnitario))}
                            >
                              {marcado ? 'Selecionado' : 'Selecionar'}
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>

        <div className="ajuda-rodape" style={{ flexShrink: 0 }}>

          <button type="button" className="btn" disabled={selecionados.size === 0} onClick={confirmar}>
            {selecionados.size === 0
              ? 'Confirmar Seleção'
              : `Confirmar Seleção (${selecionados.size} produto${selecionados.size === 1 ? '' : 's'})`}
          </button>
        </div>
      </div>
    </div>
  )
}
