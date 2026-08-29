import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeExcluir, IconeOrdemServico } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarDescontoVenda, buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import {
  completarMoeda,
  completarQuantidade,
  desmascararMoeda,
  desmascararQuantidade,
  formatarMoeda,
  formatarQuantidade,
  mascararMoeda,
  mascararQuantidade,
} from '../../lib/masks'
import {
  ESTADOS_EDITAVEIS,
  PROXIMO_ESTADO,
  SITUACAO_OS,
  duracaoTotalMinutos,
  formatarDuracao,
  atualizarOrdemServico,
  buscarOrdemServico,
  criarOrdemServico,
  mudarSituacaoOs,
  type SituacaoOs,
} from '../../lib/ordensServico'
import { maiusculas } from '../../lib/texto'
import PesquisaClienteModal from '../pdv/PesquisaClienteModal'
import PesquisaProdutoModal from '../pdv/PesquisaProdutoModal'
import PesquisaVendedorModal from '../pdv/PesquisaVendedorModal'
import OrdemServicoImpressaoModal from './OrdemServicoImpressaoModal'

/** Uma linha em montagem. `preco` é o que o operador vê; quem grava o valor é o servidor. */
interface ItemEmMontagem {
  idVariacao: number
  sku: string
  descricao: string
  variacao: string
  tipoItem: 'MERCADORIA' | 'SERVICO'
  /** TEXTO mascarado — é a fonte da verdade do campo; o número sai por `qtdDe`. */
  qtdTexto: string
  preco: number
  /**
   * Quem EXECUTA este serviço (DS5) — nulo = o responsável pelo atendimento.
   *
   * ⚠️ Só faz sentido em serviço: é ele que carrega comissão própria, e é numa oficina com dois
   * mecânicos que a diferença aparece. Em peça, quem vende leva.
   */
  idFuncionario: number | null
  nomeFuncionario: string | null
}

/**
 * O número por trás do campo mascarado — a grade guarda TEXTO, os cálculos usam isto.
 *
 * ⚠️ O `true` fixo é seguro nos DOIS modos, e não um descuido: quem já normalizou o texto foi a
 * máscara na digitação. No modo inteiro o texto vem como "1.234" (com separador de milhar), e
 * `desmascararQuantidade` remove os pontos antes de converter — devolve 1234, não 1,234.
 */
function qtdDe(item: ItemEmMontagem): number {
  return desmascararQuantidade(item.qtdTexto, true)
}

/**
 * Abertura e alteração de Ordem de Serviço (S4, `docs/telas/ordem-servico.md`).
 *
 * <p>⚠️ Ao contrário do orçamento, a OS <b>é editável</b> — e essa é a diferença que justifica ela
 * existir. O carro entra por um barulho, e o que ele realmente precisa só se sabe depois de aberto:
 * acrescentar peça e serviço no meio do caminho é o trabalho normal da oficina, não uma exceção.
 *
 * <p>⚠️ Cada alteração das peças <b>mexe na reserva de estoque</b> pelo delta (S4/DS16) — a peça
 * separada para este carro deixa de aparecer como disponível para vender no balcão. Por isso a
 * edição para na hora em que a OS vira venda (FATURADA) ou morre (CANCELADA).
 */
export default function OrdemServicoForm() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { id, modo } = useParams()
  const idOs = id ? Number(id) : null
  const somenteLeitura = modo === 'visualizar'

  const [cliente, setCliente] = useState<{ id: number; nome: string } | null>(null)
  const [responsavel, setResponsavel] = useState<{ id: number; nome: string } | null>(null)
  const [objetoServico, setObjetoServico] = useState('')
  const [observacao, setObservacao] = useState('')
  const [descontoTexto, setDescontoTexto] = useState('')
  const [itens, setItens] = useState<ItemEmMontagem[]>([])
  const [situacao, setSituacao] = useState<SituacaoOs>('ABERTA')

  const [pesquisaProduto, setPesquisaProduto] = useState(false)
  const [pesquisaCliente, setPesquisaCliente] = useState(false)
  const [pesquisaResponsavel, setPesquisaResponsavel] = useState(false)
  /** Qual linha de serviço está escolhendo executor (null = nenhuma). */
  const [escolhendoExecutorPara, setEscolhendoExecutorPara] = useState<number | null>(null)
  const [imprimindo, setImprimindo] = useState(false)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const { data: existente } = useQuery({
    queryKey: ['ordem-servico', idOs],
    queryFn: () => buscarOrdemServico(idOs!),
    enabled: idOs != null,
  })

  const { data: descontoMaximo } = useQuery({
    queryKey: ['pdv-desconto-venda'],
    queryFn: buscarDescontoVenda,
  })

  // ⚠️ Mesma chave que o PDV e a Transferência usam — a tela de Parâmetros invalida esta.
  const { data: cfgQtdDecimal } = useQuery({
    queryKey: ['permite-qtd-decimal'],
    queryFn: buscarPermiteQtdDecimal,
  })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  useEffect(() => {
    if (!existente) return
    setCliente({ id: existente.idCliente, nome: existente.nomeCliente })
    setResponsavel({ id: existente.idFuncionario, nome: existente.nomeFuncionario })
    setObjetoServico(existente.objetoServico)
    setObservacao(existente.observacao ?? '')
    setDescontoTexto(existente.valorDesconto > 0 ? formatarMoeda(existente.valorDesconto) : '')
    setSituacao(existente.situacao)
    setItens(
      existente.itens.map((i) => ({
        idVariacao: i.idVariacao,
        sku: i.sku,
        descricao: i.descricaoProduto,
        variacao: [i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · '),
        tipoItem: i.tipoItem,
        qtdTexto: formatarQuantidade(i.qtdProduto, permiteQtdDecimal),
        preco: i.precoVenda,
        idFuncionario: i.idFuncionario,
        nomeFuncionario: i.nomeFuncionario,
      })),
    )
  }, [existente])

  const servicos = itens.filter((i) => i.tipoItem === 'SERVICO')
  const pecas = itens.filter((i) => i.tipoItem === 'MERCADORIA')
  const totalServicos = servicos.reduce((s, i) => s + qtdDe(i) * i.preco, 0)
  const totalPecas = pecas.reduce((s, i) => s + qtdDe(i) * i.preco, 0)
  const subtotal = totalServicos + totalPecas
  const desconto = desmascararMoeda(descontoTexto || '0')
  const total = subtotal - desconto

  const tetoDesconto = descontoMaximo ? (subtotal * descontoMaximo.percentualDescontoVenda) / 100 : 0
  const descontoAcimaDoTeto = desconto > tetoDesconto + 0.001

  /** Editável enquanto a OS é execução. FATURADA virou venda; CANCELADA morreu. */
  const editavel = !somenteLeitura && ESTADOS_EDITAVEIS.includes(situacao)
  const podeSalvar =
    editavel && cliente != null && responsavel != null && objetoServico.trim() !== '' &&
    itens.length > 0 && !descontoAcimaDoTeto

  const acrescentarItem = (p: {
    idVariacao: number
    sku: string
    descricaoProduto: string
    variacaoCor: string | null
    variacaoTamanho: string | null
    precoVenda: number
    tipoItem: 'MERCADORIA' | 'SERVICO'
  }) => {
    setItens((atual) => {
      const existenteNaGrade = atual.find((i) => i.idVariacao === p.idVariacao)
      if (existenteNaGrade) {
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
          descricao: p.descricaoProduto,
          variacao: [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · '),
          tipoItem: p.tipoItem,
          qtdTexto: formatarQuantidade(1, permiteQtdDecimal),
          preco: p.precoVenda,
          idFuncionario: null,
          nomeFuncionario: null,
        },
      ]
    })
    setPesquisaProduto(false)
  }

  const corpo = () => ({
    idCliente: cliente!.id,
    idFuncionario: responsavel!.id,
    objetoServico: maiusculas(objetoServico.trim()),
    observacao: observacao.trim() ? maiusculas(observacao.trim()) : null,
    valorDesconto: desconto,
    itens: itens.map((i) => ({
      idVariacao: i.idVariacao,
      qtdProduto: qtdDe(i),
      // Só serviço carrega executor; mandar em peça sujaria a comissão sem ninguém pedir.
      idFuncionario: i.tipoItem === 'SERVICO' ? i.idFuncionario : null,
    })),
  })

  const salvar = useMutation({
    mutationFn: () => (idOs ? atualizarOrdemServico(idOs, corpo()) : criarOrdemServico(corpo())),
    onSuccess: (os) => {
      queryClient.invalidateQueries({ queryKey: ['ordens-servico'] })
      queryClient.invalidateQueries({ queryKey: ['ordem-servico', os.idOrdemServico] })
      // A reserva mudou — quem mostra saldo de estoque tem de reler.
      queryClient.invalidateQueries({ queryKey: ['pdv-produtos'] })
      setToast({ texto: `Ordem de serviço nº ${os.idOrdemServico} salva.`, tipo: 'sucesso' })
      if (!idOs) navigate(`/ordens-servico/${os.idOrdemServico}`, { replace: true })
    },
    onError: (e: unknown) =>
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível salvar a ordem de serviço.',
        tipo: 'erro',
      }),
  })

  const avancar = useMutation({
    mutationFn: (para: SituacaoOs) => mudarSituacaoOs(idOs!, para),
    onSuccess: (os) => {
      setSituacao(os.situacao)
      queryClient.invalidateQueries({ queryKey: ['ordens-servico'] })
      queryClient.invalidateQueries({ queryKey: ['ordem-servico', os.idOrdemServico] })
      setToast({
        texto:
          os.situacao === 'CONCLUIDA'
            ? 'Ordem de serviço concluída — já pode ser cobrada no PDV (F5).'
            : `Ordem de serviço agora está ${SITUACAO_OS[os.situacao].rotulo.toLowerCase()}.`,
        tipo: 'sucesso',
      })
    },
    onError: (e: unknown) =>
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível mudar a situação.',
        tipo: 'erro',
      }),
  })

  const proximo = PROXIMO_ESTADO[situacao]

  const gradeItens = (titulo: string, lista: ItemEmMontagem[], vazio: string, comExecutor = false) => (
    <div className="table-wrap">
      <p className="section-label" style={{ marginBottom: 4 }}>
        {titulo} {lista.length > 0 && `(${lista.length})`}
      </p>
      <table className="table table-compacta">
        <thead>
          <tr>
            <th>SKU</th>
            <th>Descrição</th>
            {comExecutor && <th>Executado por</th>}
            <th style={{ textAlign: 'right' }}>Qtde</th>
            <th style={{ textAlign: 'right' }}>Preço</th>
            <th style={{ textAlign: 'right' }}>Total</th>
            <th aria-label="Ações" />
          </tr>
        </thead>
        <tbody>
          {lista.length === 0 ? (
            <tr>
              <td colSpan={comExecutor ? 7 : 6}>
                <p className="muted" style={{ margin: '12px 0' }}>{vazio}</p>
              </td>
            </tr>
          ) : (
            lista.map((i) => (
              <tr key={i.idVariacao}>
                <td className="mono">{i.sku}</td>
                <td>
                  {i.descricao}
                  {i.variacao && <span className="muted"> {i.variacao}</span>}
                </td>
                {comExecutor && (
                  <td>
                    {/* Botão em vez de select: a lista de funcionários é paginada, e um select
                        só acharia quem está na primeira página — armadilha já catalogada. */}
                    <button
                      type="button"
                      className="btn ghost"
                      disabled={!editavel}
                      style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}
                      onClick={() => setEscolhendoExecutorPara(i.idVariacao)}
                    >
                      {i.nomeFuncionario ?? 'Definir…'}
                    </button>
                  </td>
                )}
                <td style={{ textAlign: 'right' }}>
                  {/* ⛔ Máscara de QUANTIDADE, não `replace(/D/g,'')` (achado de auditoria,
                      2026-08-29): o filtro de dígitos transformava "2,5" em 25 — a oficina que
                      lançasse 2,5 litros de óleo reservava 25 e cobrava 10× o valor. O parâmetro
                      `cfg_permite_qtd_decimal` nasce LIGADO e o backend da OS aceita decimal;
                      só esta tela ignorava, enquanto PDV, Transferência e Histórico respeitam. */}
                  <input
                    inputMode="decimal"
                    disabled={!editavel}
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
                          // Zero não é quantidade: cai para 1, como as outras grades do produto.
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
                  {editavel && (
                    <button
                      type="button"
                      className="acao-icone acao-excluir"
                      title="Remover"
                      aria-label={`Remover ${i.descricao}`}
                      onClick={() => setItens((atual) => atual.filter((x) => x.idVariacao !== i.idVariacao))}
                    >
                      <IconeExcluir />
                    </button>
                  )}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeOrdemServico size={26} />
            <h1>
              {idOs ? `Ordem de Serviço nº ${idOs}` : 'Nova Ordem de Serviço'}
            </h1>
            {idOs && (
              <span style={{ color: SITUACAO_OS[situacao].cor, fontWeight: 600, marginLeft: 12 }}>
                {SITUACAO_OS[situacao].rotulo}
              </span>
            )}
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.ordemservico.form" />
            {/* ⚠️ Imprime o que está GRAVADO, não o que está na tela — por isso só aparece depois
                de a OS existir, e o que o cliente leva é sempre igual ao que o sistema tem.
                Alterações não salvas não saem no papel, e isso é a garantia, não a limitação. */}
            {idOs != null && existente && (
              <button type="button" className="btn ghost" onClick={() => setImprimindo(true)}>
                Imprimir / WhatsApp
              </button>
            )}
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <fieldset disabled={somenteLeitura} style={{ border: 0, padding: 0, margin: 0 }}>
          <div className="card form-secoes">
            <section className="section">
              <p className="section-label">Dados da Ordem de Serviço</p>
              <div className="form-grid">
                <div className="col-4">
                  <label>Cliente *</label>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <input readOnly value={cliente?.nome ?? ''} placeholder="Nenhum cliente" style={{ flex: 1 }} />
                    <button type="button" className="btn ghost" disabled={!editavel} onClick={() => setPesquisaCliente(true)}>
                      Buscar
                    </button>
                  </div>
                </div>

                <div className="col-4">
                  <label>Responsável *</label>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <input readOnly value={responsavel?.nome ?? ''} placeholder="Nenhum responsável" style={{ flex: 1 }} />
                    <button type="button" className="btn ghost" disabled={!editavel} onClick={() => setPesquisaResponsavel(true)}>
                      Buscar
                    </button>
                  </div>
                </div>

                <div className="col-4">
                  <label htmlFor="os-objeto">Objeto do serviço *</label>
                  <input
                    id="os-objeto"
                    disabled={!editavel}
                    placeholder="Ex.: GOL PRATA ABC1D23 / REX, POODLE"
                    value={objetoServico}
                    onChange={(e) => setObjetoServico(maiusculas(e.target.value))}
                  />
                  {/* ⚠️ Campo de TEXTO LIVRE de propósito (DS9): o que fica em serviço muda com o
                      ramo — carro, moto, cachorro, notebook. Uma tabela de veículos serviria à
                      oficina e atrapalharia o petshop. É também por ele que se busca a OS. */}
                  <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                    O que está em serviço — é por aqui que você localiza a OS depois.
                  </p>
                </div>

                <div className="col-12">
                  <label htmlFor="os-observacao">Observações</label>
                  <input
                    id="os-observacao"
                    disabled={!editavel}
                    value={observacao}
                    onChange={(e) => setObservacao(maiusculas(e.target.value))}
                  />
                </div>
              </div>
            </section>

            <section className="section">
              <div className="ajuda-rodape" style={{ justifyContent: 'space-between', marginTop: 0 }}>
                <p className="section-label" style={{ margin: 0 }}>
                  Serviços e peças
                  {/* ⛔ "Tempo estimado", não "pronto às 15h30": a soma ignora paralelismo e fila.
                      Prometer um horário exato com esse cálculo seria mentir com precisão. */}
                  {existente && duracaoTotalMinutos(existente.itens) !== null && (
                    <span className="muted" style={{ fontWeight: 400, marginLeft: 12 }}>
                      Tempo estimado dos serviços:{' '}
                      <strong>{formatarDuracao(duracaoTotalMinutos(existente.itens)!)}</strong>
                    </span>
                  )}
                </p>
                <button type="button" className="btn ghost" disabled={!editavel} onClick={() => setPesquisaProduto(true)}>
                  ＋ Adicionar serviço ou peça
                </button>
              </div>

              {/* Serviço e peça em grades separadas (DS12): o lojista quer ver quanto foi mão de
                  obra e quanto foi material — é essa divisão que a NFS-e vai precisar quando o
                  módulo fiscal de serviço entrar, e é ela que o dono da oficina usa para saber
                  onde ganhou dinheiro. */}
              {gradeItens('Serviços', servicos, 'Nenhum serviço nesta ordem.', true)}
              {gradeItens('Peças', pecas, 'Nenhuma peça nesta ordem.')}

              {pecas.length > 0 && editavel && (
                <p className="muted" style={{ fontSize: 12 }}>
                  ⚠️ As peças ficam <strong>reservadas</strong> enquanto a OS estiver aberta — elas
                  saem do disponível do balcão, mas só baixam do estoque de verdade quando a OS
                  virar venda no PDV.
                </p>
              )}
            </section>

            <section className="section">
              <div className="orcamento-rodape">
                <div className="orcamento-rodape-acao">
                  {editavel && (
                    <button type="button" className="btn" disabled={!podeSalvar || salvar.isPending} onClick={() => salvar.mutate()}>
                      {salvar.isPending ? 'Salvando…' : idOs ? 'Salvar alterações' : 'Abrir Ordem de Serviço'}
                    </button>
                  )}
                  {/* Avançar o estado só depois que a OS existe — e um passo por vez, na ordem do
                      trabalho. Não há botão de voltar: estado que anda para trás faz a reserva e o
                      histórico contarem histórias diferentes. */}
                  {idOs && proximo && !somenteLeitura && (
                    <button
                      type="button"
                      className="btn ghost"
                      disabled={avancar.isPending}
                      onClick={() => avancar.mutate(proximo)}
                    >
                      {avancar.isPending ? 'Mudando…' : `Marcar como ${SITUACAO_OS[proximo].rotulo}`}
                    </button>
                  )}
                  {situacao === 'CONCLUIDA' && (
                    <p className="muted" style={{ margin: 0, fontSize: 12 }}>
                      Pronta para cobrar: abra o <strong>PDV</strong> e puxe esta OS com o <strong>F5</strong>.
                    </p>
                  )}
                  {situacao === 'FATURADA' && existente?.idVenda && (
                    <p className="muted" style={{ margin: 0, fontSize: 12 }}>
                      Já virou a venda nº <strong>{existente.idVenda}</strong>.
                    </p>
                  )}
                  {situacao === 'CANCELADA' && (
                    <p className="erro-campo" style={{ margin: 0 }}>
                      Cancelada — {existente?.motivoCancelamento}
                    </p>
                  )}
                </div>

                <div className="orcamento-rodape-desconto">
                  <label htmlFor="os-desconto">Desconto (R$)</label>
                  <input
                    id="os-desconto"
                    className="mono"
                    disabled={!editavel}
                    style={{ textAlign: 'right' }}
                    value={descontoTexto}
                    onChange={(e) => setDescontoTexto(mascararMoeda(e.target.value))}
                    onBlur={() => setDescontoTexto(completarMoeda(descontoTexto))}
                    onFocus={(e) => e.target.select()}
                  />
                  {descontoAcimaDoTeto && (
                    <p className="erro-campo" style={{ marginTop: 4 }}>
                      Acima do máximo da loja (R$ {formatarMoeda(tetoDesconto)}).
                    </p>
                  )}
                </div>

                <div className="orcamento-rodape-totais">
                  <p style={{ margin: 0 }}>
                    Serviços <strong className="mono">R$ {formatarMoeda(totalServicos)}</strong>
                  </p>
                  <p style={{ margin: 0 }}>
                    Peças <strong className="mono">R$ {formatarMoeda(totalPecas)}</strong>
                  </p>
                  <p style={{ margin: 0 }}>
                    Total <strong className="mono" style={{ fontSize: 18 }}>R$ {formatarMoeda(total)}</strong>
                  </p>
                </div>
              </div>
            </section>
          </div>
        </fieldset>
      </div>

      {pesquisaCliente && (
        <PesquisaClienteModal
          aoFechar={() => setPesquisaCliente(false)}
          aoSelecionar={(c) => {
            setCliente({ id: c.idCliente, nome: c.nome })
            setPesquisaCliente(false)
          }}
        />
      )}
      {pesquisaResponsavel && (
        <PesquisaVendedorModal
          aoFechar={() => setPesquisaResponsavel(false)}
          aoSelecionar={(f) => {
            setResponsavel({ id: f.idFuncionario, nome: f.nome })
            setPesquisaResponsavel(false)
          }}
        />
      )}
      {escolhendoExecutorPara !== null && (
        <PesquisaVendedorModal
          aoFechar={() => setEscolhendoExecutorPara(null)}
          aoSelecionar={(f) => {
            setItens((atual) =>
              atual.map((x) =>
                x.idVariacao === escolhendoExecutorPara
                  ? { ...x, idFuncionario: f.idFuncionario, nomeFuncionario: f.nome }
                  : x,
              ),
            )
            setEscolhendoExecutorPara(null)
          }}
        />
      )}
      {pesquisaProduto && (
        <PesquisaProdutoModal aoFechar={() => setPesquisaProduto(false)} aoSelecionar={acrescentarItem} />
      )}

      {imprimindo && existente && (
        <OrdemServicoImpressaoModal os={existente} aoFechar={() => setImprimindo(false)} />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
