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
import { buscarDescontoVenda } from '../../lib/configuracaoGeral'
import {
  completarMoeda,
  dataParaIso,
  dataValida,
  desmascararMoeda,
  formatarMoeda,
  isoParaData,
  mascararData,
  mascararMoeda,
} from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
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
  qtd: number
  preco: number
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
  const [observacao, setObservacao] = useState('')
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

  /** A validade nasce sugerida por Parâmetros do Sistema (R11) e continua editável. */
  useEffect(() => {
    // ⚠️ `!buscandoValidade` (2026-08-21, achado em auditoria): o QueryClient não define
    // `staleTime`, então o cache entrega o valor ANTIGO no primeiro render e revalida em seguida.
    // Decidir ali preenchia a validade com o prazo velho — e quando o valor novo chegava, a guarda
    // `!validadeTexto` já bloqueava a correção. Como o orçamento é IMUTÁVEL, o documento saía com o
    // prazo errado sem conserto. Mesmo remédio de `DevolucaoProduto` e `ComprovantePapeletaModal`.
    if (config && !buscandoValidade && !validadeTexto) {
      const d = new Date()
      d.setDate(d.getDate() + config.cfgDiasValidadeOrcamento)
      setValidadeTexto(isoParaData(d.toISOString().slice(0, 10)) ?? '')
    }
  }, [config, buscandoValidade, validadeTexto])

  const subtotal = itens.reduce((s, i) => s + i.qtd * i.preco, 0)
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
        return atual.map((i) => (i.idVariacao === p.idVariacao ? { ...i, qtd: i.qtd + 1 } : i))
      }
      return [
        ...atual,
        {
          idVariacao: p.idVariacao,
          sku: p.sku,
          descricao: p.descricao,
          variacao: [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · '),
          qtd: 1,
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
        observacao: observacao.trim() || null,
        itens: itens.map((i) => ({ idVariacao: i.idVariacao, qtd: i.qtd })),
      }),
    onSuccess: (o) => setEmitido(o),
    onError: (e: unknown) =>
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível emitir o orçamento.',
        tipo: 'erro',
      }),
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePdv size={34} />
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

              <div className="col-12">
                <label htmlFor="observacao">Observações</label>
                <input
                  id="observacao"
                  value={observacao}
                  placeholder="Condições de pagamento, prazo de entrega, frete…"
                  onChange={(e) => setObservacao(maiusculas(e.target.value))}
                />
              </div>
            </div>
          </section>

          <section className="section">
            <div className="ajuda-rodape" style={{ justifyContent: 'space-between', marginTop: 0 }}>
              <p className="section-label" style={{ margin: 0 }}>
                Produtos {itens.length > 0 && `(${itens.length})`}
              </p>
              <button type="button" className="btn ghost" onClick={() => setPesquisaProduto(true)}>
                ＋ Adicionar produto
              </button>
            </div>

            <div className="table-wrap" style={{ marginTop: 12, maxHeight: 380 }}>
              <table className="table">
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
                            inputMode="numeric"
                            style={{ width: 80, textAlign: 'right' }}
                            value={i.qtd === 0 ? '' : String(i.qtd)}
                            onFocus={(e) => e.target.select()}
                            onChange={(e) => {
                              const digitos = e.target.value.replace(/\D/g, '')
                              setItens((atual) =>
                                atual.map((x) =>
                                  x.idVariacao === i.idVariacao
                                    ? { ...x, qtd: digitos === '' ? 0 : Number(digitos) }
                                    : x,
                                ),
                              )
                            }}
                            onBlur={() =>
                              setItens((atual) =>
                                atual.map((x) => (x.idVariacao === i.idVariacao && x.qtd < 1 ? { ...x, qtd: 1 } : x)),
                              )
                            }
                          />
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.preco)}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.qtd * i.preco)}</td>
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

          <section className="section">
            <div className="form-grid">
              <div className="col-4">
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
                {descontoAcimaDoTeto && (
                  <p className="erro-campo">
                    Máximo permitido: R$ {formatarMoeda(tetoDesconto)} (
                    {descontoMaximo?.percentualDescontoVenda}% do subtotal, definido em Parâmetros do
                    Sistema).
                  </p>
                )}
              </div>
              <div className="col-8">
                <p className="muted" style={{ textAlign: 'right', margin: 0 }}>
                  Subtotal <strong className="mono">R$ {formatarMoeda(subtotal)}</strong>
                  {desconto > 0 && (
                    <>
                      {' '}· Desconto <strong className="mono">R$ {formatarMoeda(desconto)}</strong>
                    </>
                  )}
                </p>
                <p style={{ textAlign: 'right', fontSize: 22, margin: '4px 0 0' }}>
                  Total <strong className="mono">R$ {formatarMoeda(total)}</strong>
                </p>
              </div>
            </div>
          </section>

          <section className="section">
            <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
              <button type="button" className="btn" disabled={!podeEmitir || emitir.isPending} onClick={() => emitir.mutate()}>
                {emitir.isPending ? 'Emitindo…' : 'Emitir Orçamento'}
              </button>
              <p className="muted" style={{ margin: 0 }}>
                ⚠️ Depois de emitido o orçamento <strong>não pode ser alterado</strong> — para mudar,
                cancele e emita outro.
              </p>
            </div>
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
          aoFechar={() => navigate('/orcamentos', { replace: true })}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
