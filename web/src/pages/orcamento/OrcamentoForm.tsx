import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeExcluir, IconePdv } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  buscarDiasValidadeOrcamento,
  emitirOrcamento,
  type Orcamento,
} from '../../lib/orcamento'
import { buscarDescontoVenda, buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { hojeMaisDiasISO } from '../../lib/datas'
import {
  completarMoeda,
  completarQuantidade,
  dataParaIso,
  dataValida,
  desmascararMoeda,
  desmascararQuantidade,
  formatarMoeda,
  formatarQuantidade,
  isoParaData,
  mascararData,
  mascararMoeda,
  mascararQuantidade,
} from '../../lib/masks'
import PesquisaClienteModal from '../pdv/PesquisaClienteModal'
import PesquisaProdutoModal from '../pdv/PesquisaProdutoModal'
import PesquisaVendedorModal from '../pdv/PesquisaVendedorModal'
import OrcamentoImpressaoModal from './OrcamentoImpressaoModal'

/** Um item na grade em montagem. `preco` é só para o operador conferir o total — o que vale é o
 *  que o servidor congela na emissão (a tela nunca manda preço). */
interface ItemEmMontagem {
  idVariacao: number
  sku: string
  descricao: string
  variacao: string
  /**
   * ⛔ **Texto mascarado, não `number`** (auditoria 2026-08-29). O campo filtrava com
   * `replace(/\D/g,'')`, e a vírgula era engolida em silêncio: 2,5 m de tecido viravam **25**, o
   * total ia de R$ 50 para R$ 500 — num documento **imutável** (R1), que só se conserta cancelando
   * e refazendo, e que já saiu impresso para o cliente. Mesmo defeito corrigido na Ordem de
   * Serviço no mesmo dia; o Orçamento era a ponta que ficou sem varrer.
   */
  qtdTexto: string
  preco: number
}

/** Quantidade do item como número — sempre pelo desmascarador, que remove os pontos de milhar. */
function qtdDe(item: ItemEmMontagem): number {
  return desmascararQuantidade(item.qtdTexto, true)
}

/**
 * Emissão de Orçamento (docs/telas/orcamento.md).
 *
 * <p>⚠️ Não existe tela de edição: o orçamento é imutável depois de emitido (R1). Quem quiser
 * mudar cancela e emite outro — a mesma filosofia da venda.
 */
export default function OrcamentoForm() {
  const navigate = useNavigate()

  const [cliente, setCliente] = useState<{ id: number; nome: string } | null>(null)
  const [vendedor, setVendedor] = useState<{ id: number; nome: string } | null>(null)
  const [validadeTexto, setValidadeTexto] = useState('')
  const [descontoTexto, setDescontoTexto] = useState('')
  const [itens, setItens] = useState<ItemEmMontagem[]>([])

  const [pesquisaProduto, setPesquisaProduto] = useState(false)
  const [pesquisaCliente, setPesquisaCliente] = useState(false)
  const [pesquisaVendedor, setPesquisaVendedor] = useState(false)
  const [emitido, setEmitido] = useState<Orcamento | null>(null)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const { data: config, isFetching: buscandoValidade } = useQuery({
    queryKey: ['orcamento-dias-validade'],
    queryFn: buscarDiasValidadeOrcamento,
  })
  const { data: descontoMaximo } = useQuery({
    queryKey: ['pdv-desconto-venda'],
    queryFn: buscarDescontoVenda,
  })
  const { data: cfgQtdDecimal } = useQuery({
    queryKey: ['permite-qtd-decimal'],
    queryFn: buscarPermiteQtdDecimal,
  })
  /** `cfg_permite_qtd_decimal` nasce LIGADO, então o default enquanto a query não resolve é `true`. */
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  /** A validade nasce sugerida por Parâmetros do Sistema (R11) e continua editável. */
  useEffect(() => {
    // ⚠️ `!buscandoValidade` (2026-08-21, achado em auditoria): o QueryClient não define
    // `staleTime`, então o cache entrega o valor ANTIGO no primeiro render e revalida em seguida.
    // Decidir ali preenchia a validade com o prazo velho — e quando o valor novo chegava, a guarda
    // `!validadeTexto` já bloqueava a correção. Como o orçamento é IMUTÁVEL, o documento saía com o
    // prazo errado sem conserto. Mesmo remédio de `DevolucaoProduto` e `ComprovantePapeletaModal`.
    if (config && !buscandoValidade && !validadeTexto) {
      // ⚠️ `hojeMaisDiasISO` monta a data pelos componentes LOCAIS. Era `toISOString().slice(0,10)`,
      // que converte para UTC: a partir das 21h de Brasília o resultado já é o dia seguinte, e o
      // orçamento saía com um dia a mais de validade — congelando o preço além da política da loja,
      // num documento que é imutável.
      setValidadeTexto(isoParaData(hojeMaisDiasISO(config.cfgDiasValidadeOrcamento)) ?? '')
    }
  }, [config, buscandoValidade, validadeTexto])

  const subtotal = itens.reduce((s, i) => s + qtdDe(i) * i.preco, 0)
  const desconto = desmascararMoeda(descontoTexto || '0')
  const total = subtotal - desconto
  const tetoDesconto = descontoMaximo
    ? (subtotal * descontoMaximo.percentualDescontoVenda) / 100
    : 0
  const descontoAcimaDoTeto = desconto > tetoDesconto + 0.001

  const podeEmitir =
    cliente != null && vendedor != null && itens.length > 0 &&
    dataValida(validadeTexto) && !descontoAcimaDoTeto

  const acrescentarItem = (p: {
    idVariacao: number
    sku: string
    descricao: string
    variacaoCor: string | null
    variacaoTamanho: string | null
    precoVenda: number
  }) => {
    setItens((atual) => {
      const existente = atual.find((i) => i.idVariacao === p.idVariacao)
      if (existente) {
        return atual.map((i) =>
          i.idVariacao === p.idVariacao
            ? { ...i, qtdTexto: formatarQuantidade(qtdDe(i) + 1, permiteQtdDecimal) }
            : i,
        )
      }
      return [
        ...atual,
        {
          idVariacao: p.idVariacao,
          sku: p.sku,
          descricao: p.descricao,
          variacao: [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · '),
          qtdTexto: formatarQuantidade(1, permiteQtdDecimal),
          preco: p.precoVenda,
        },
      ]
    })
  }

  const emitir = useMutation({
    mutationFn: () =>
      emitirOrcamento({
        idCliente: cliente!.id,
        idFuncionario: vendedor!.id,
        dataValidade: dataParaIso(validadeTexto),
        valorDesconto: desconto,
        itens: itens.map((i) => ({ idVariacao: i.idVariacao, qtd: qtdDe(i) })),
      }),
    onSuccess: (o) => setEmitido(o),
    onError: (e: unknown) =>
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível emitir o orçamento.',
        tipo: 'erro',
      }),
  })

  return (
    <div className="lista-tela orcamento-form-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePdv size={26} />
            <h1>Novo Orçamento</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.orcamento.form" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
          <section className="section">
            <p className="section-label">Dados do Orçamento</p>
            <div className="form-grid">
              <div className="col-4">
                <label>Cliente *</label>
                <div style={{ display: 'flex', gap: 8 }}>
                  <input readOnly value={cliente?.nome ?? ''} placeholder="Nenhum cliente" style={{ flex: 1 }} />
                  <button type="button" className="btn ghost" onClick={() => setPesquisaCliente(true)}>
                    Buscar
                  </button>
                </div>
              </div>

              <div className="col-4">
                <label>Vendedor *</label>
                <div style={{ display: 'flex', gap: 8 }}>
                  <input readOnly value={vendedor?.nome ?? ''} placeholder="Nenhum vendedor" style={{ flex: 1 }} />
                  <button type="button" className="btn ghost" onClick={() => setPesquisaVendedor(true)}>
                    Buscar
                  </button>
                </div>
              </div>

              <div className="col-4">
                <label htmlFor="validade">Válido até *</label>
                <input
                  id="validade"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={validadeTexto}
                  onChange={(e) => setValidadeTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
                <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                  Sugerido por Parâmetros do Sistema. Passando desta data o orçamento vence sozinho e
                  <strong> não vira mais venda</strong>.
                </p>
              </div>

            </div>
          </section>

          <section className="section section-produtos">
            <div className="ajuda-rodape" style={{ justifyContent: 'space-between', marginTop: 0 }}>
              <p className="section-label" style={{ margin: 0 }}>
                Produtos {itens.length > 0 && `(${itens.length})`}
              </p>
              <button type="button" className="btn ghost" onClick={() => setPesquisaProduto(true)}>
                ＋ Adicionar produto
              </button>
            </div>

            <div className="table-wrap">
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>SKU</th>
                    <th>Produto</th>
                    <th style={{ textAlign: 'right' }}>Qtde</th>
                    <th style={{ textAlign: 'right' }}>Preço</th>
                    <th style={{ textAlign: 'right' }}>Total</th>
                    <th aria-label="Ações" />
                  </tr>
                </thead>
                <tbody>
                  {itens.length === 0 ? (
                    <tr>
                      <td colSpan={6}>
                        <p className="muted" style={{ margin: '12px 0' }}>Nenhum produto no orçamento.</p>
                      </td>
                    </tr>
                  ) : (
                    itens.map((i) => (
                      <tr key={i.idVariacao}>
                        <td className="mono">{i.sku}</td>
                        <td>
                          {i.descricao}
                          {i.variacao && <span className="muted"> {i.variacao}</span>}
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <input
                            inputMode="decimal"
                            style={{ width: 90, textAlign: 'right' }}
                            value={i.qtdTexto}
                            onFocus={(e) => e.target.select()}
                            onChange={(e) =>
                              setItens((atual) =>
                                atual.map((x) =>
                                  x.idVariacao === i.idVariacao
                                    ? { ...x, qtdTexto: mascararQuantidade(e.target.value, permiteQtdDecimal) }
                                    : x,
                                ),
                              )
                            }
                            onBlur={() =>
                              setItens((atual) =>
                                atual.map((x) => {
                                  if (x.idVariacao !== i.idVariacao) return x
                                  const completo = completarQuantidade(x.qtdTexto, permiteQtdDecimal)
                                  // Zero não é quantidade: cai para 1, como nas outras grades.
                                  return desmascararQuantidade(completo, permiteQtdDecimal) > 0
                                    ? { ...x, qtdTexto: completo }
                                    : { ...x, qtdTexto: formatarQuantidade(1, permiteQtdDecimal) }
                                }),
                              )
                            }
                          />
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.preco)}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(qtdDe(i) * i.preco)}</td>
                        <td>
                          <button
                            type="button"
                            className="acao-icone acao-excluir"
                            title="Remover"
                            aria-label={`Remover ${i.descricao}`}
                            onClick={() => setItens((atual) => atual.filter((x) => x.idVariacao !== i.idVariacao))}
                          >
                            <IconeExcluir />
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </section>

          {/* Rodapé numa LINHA só (2026-08-25): emitir à esquerda, desconto ao centro, totais à
              direita. Antes eram duas seções empilhadas, e a altura que elas somavam era parte do
              que empurrava a página para o scroll. */}
          <section className="section">
            <div className="orcamento-rodape">
              <div className="orcamento-rodape-acao">
                <button type="button" className="btn" disabled={!podeEmitir || emitir.isPending} onClick={() => emitir.mutate()}>
                  {emitir.isPending ? 'Emitindo…' : 'Emitir Orçamento'}
                </button>
                {/* O aviso da imutabilidade fica sob o botão: ocupa altura que a coluna dos
                    totais já usa de qualquer forma, então não custa linha nova. */}
                <p className="muted" style={{ margin: 0, fontSize: 12 }}>
                  ⚠️ Depois de emitido <strong>não pode ser alterado</strong> — cancele e emita outro.
                </p>
              </div>

              <div className="orcamento-rodape-desconto">
                <label htmlFor="desconto">Desconto (R$)</label>
                <input
                  id="desconto"
                  inputMode="decimal"
                  placeholder="0,00"
                  value={descontoTexto}
                  onChange={(e) => setDescontoTexto(mascararMoeda(e.target.value))}
                  onBlur={(e) => setDescontoTexto(completarMoeda(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>

              <div className="orcamento-rodape-totais">
                <p className="muted" style={{ margin: 0 }}>
                  Subtotal <strong className="mono">R$ {formatarMoeda(subtotal)}</strong>
                  {desconto > 0 && (
                    <>
                      {' '}· Desconto <strong className="mono">R$ {formatarMoeda(desconto)}</strong>
                    </>
                  )}
                </p>
                <p style={{ fontSize: 22, margin: '4px 0 0' }}>
                  Total <strong className="mono">R$ {formatarMoeda(total)}</strong>
                </p>
              </div>
            </div>

            {/* ⚠️ O erro do teto fica FORA da linha, largura inteira: dentro da coluna de 150px
                ele quebraria em cinco linhas e esticaria o rodapé — desfazendo o ganho de altura
                justamente quando o operador erra, que é quando ele mais precisa ver o total. */}
            {descontoAcimaDoTeto && (
              <p className="erro-campo" style={{ marginTop: 8 }}>
                Desconto acima do máximo permitido: R$ {formatarMoeda(tetoDesconto)} (
                {descontoMaximo?.percentualDescontoVenda}% do subtotal, definido em Parâmetros do
                Sistema).
              </p>
            )}
          </section>
        </div>
      </div>

      {pesquisaProduto && (
        <PesquisaProdutoModal
          aoFechar={() => setPesquisaProduto(false)}
          aoSelecionar={(p) => {
            acrescentarItem({ ...p, descricao: p.descricaoProduto })
            setPesquisaProduto(false)
          }}
        />
      )}
      {pesquisaCliente && (
        <PesquisaClienteModal
          aoFechar={() => setPesquisaCliente(false)}
          aoSelecionar={(c) => {
            setCliente({ id: c.idCliente, nome: c.nome })
            setPesquisaCliente(false)
          }}
        />
      )}
      {pesquisaVendedor && (
        <PesquisaVendedorModal
          aoFechar={() => setPesquisaVendedor(false)}
          aoSelecionar={(v) => {
            setVendedor({ id: v.idFuncionario, nome: v.nome })
            setPesquisaVendedor(false)
          }}
        />
      )}

      {emitido && (
        <OrcamentoImpressaoModal
          orcamento={emitido}
          // ⚠️ `navigate(-1)`, não `replace`. O `replace` trocava `/orcamentos/novo` por
          // `/orcamentos` — e a entrada ANTERIOR já era essa mesma lista: ficavam duas idênticas em
          // sequência, e o ✕ da lista (que é `navigate(-1)`) não saía do lugar no primeiro clique.
          // É o desfecho que `EntradaMercadoriaForm` já documenta com estas palavras: "troquei 'o ✕
          // te devolve à tela que você fechou' por 'o ✕ não faz nada': melhor, e ainda errado".
          aoFechar={() => navigate(-1)}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
