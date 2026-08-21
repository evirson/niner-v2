import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeOlho, IconePdv } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  CLASSE_SITUACAO,
  ROTULO_SITUACAO,
  buscarOrcamento,
  cancelarOrcamento,
  listarOrcamentos,
  type Orcamento,
  type SituacaoOrcamento,
} from '../../lib/orcamento'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import OrcamentoImpressaoModal from './OrcamentoImpressaoModal'

const TAMANHO_PAGINA = 50

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function formatarDataSimples(iso: string): string {
  const [ano, mes, dia] = iso.split('-')
  return `${dia}/${mes}/${ano}`
}

/** Pesquisa de Orçamentos + detalhe em popup (docs/telas/orcamento.md). */
export default function OrcamentoLista() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [situacao, setSituacao] = useState<SituacaoOrcamento | ''>('')
  /** Busca por nome (2026-08-21) — quem procura um orçamento lembra do nome, não do id. */
  const [nomeCliente, setNomeCliente] = useState('')
  const [nomeVendedor, setNomeVendedor] = useState('')
  const [pagina, setPagina] = useState(1)

  const [detalhe, setDetalhe] = useState<Orcamento | null>(null)
  const [imprimindo, setImprimindo] = useState<Orcamento | null>(null)
  const [cancelando, setCancelando] = useState<Orcamento | null>(null)
  const [motivo, setMotivo] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const dataInicialIso = dataValida(dataInicialTexto) ? (dataParaIso(dataInicialTexto) ?? undefined) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? (dataParaIso(dataFinalTexto) ?? undefined) : undefined

  const { data, isLoading } = useQuery({
    queryKey: ['orcamentos', { dataInicialIso, dataFinalIso, situacao, pagina, nomeCliente, nomeVendedor }],
    queryFn: () =>
      listarOrcamentos({
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        situacao,
        pagina,
        limite: TAMANHO_PAGINA,
        nomeCliente,
        nomeFuncionario: nomeVendedor,
      }),
    placeholderData: (anterior) => anterior,
  })

  /** ⚠️ Abrir o detalhe pode MUDAR a situação: um orçamento vencido é marcado na hora (R6). Por
   *  isso a listagem é invalidada depois de consultar. */
  const abrirDetalhe = async (idOrcamento: number) => {
    try {
      const o = await buscarOrcamento(idOrcamento)
      setDetalhe(o)
      queryClient.invalidateQueries({ queryKey: ['orcamentos'] })
    } catch (e) {
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível abrir o orçamento.',
        tipo: 'erro',
      })
    }
  }

  const cancelar = useMutation({
    mutationFn: () => cancelarOrcamento(cancelando!.idOrcamento, maiusculas(motivo.trim())),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['orcamentos'] })
      setCancelando(null)
      setDetalhe(null)
      setMotivo('')
      setToast({ texto: 'Orçamento cancelado.', tipo: 'sucesso' })
    },
    onError: (e: unknown) =>
      setToast({
        texto: e instanceof ApiError ? e.message : 'Não foi possível cancelar.',
        tipo: 'erro',
      }),
  })

  const orcamentos = data?.itens ?? []
  const totalPaginas = Math.max(1, Math.ceil((data?.total ?? 0) / TAMANHO_PAGINA))

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePdv size={34} />
            <h1>Orçamentos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.orcamento.lista" />
            <button type="button" className="btn" onClick={() => navigate('/orcamentos/novo')}>
              ＋ Novo orçamento
            </button>
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div>
              <label htmlFor="filtro-inicio">Emitido de</label>
              <input
                id="filtro-inicio"
                className="mono"
                placeholder="dd/mm/aaaa"
                value={dataInicialTexto}
                onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
                onFocus={(e) => e.target.select()}
              />
            </div>
            <div>
              <label htmlFor="filtro-fim">até</label>
              <input
                id="filtro-fim"
                className="mono"
                placeholder="dd/mm/aaaa"
                value={dataFinalTexto}
                onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
                onFocus={(e) => e.target.select()}
              />
            </div>
            {/* Busca por nome (2026-08-21) — dispara a cada tecla, como as outras listas do
                produto; o filtro vai para o servidor, então a paginação continua correta (buscar
                só na página carregada esconderia resultados das páginas seguintes). */}
            <div>
              <label htmlFor="filtro-cliente">Cliente</label>
              <input
                id="filtro-cliente"
                placeholder="Nome do cliente"
                value={nomeCliente}
                onChange={(e) => {
                  setNomeCliente(maiusculas(e.target.value))
                  setPagina(1)
                }}
              />
            </div>
            <div>
              <label htmlFor="filtro-vendedor">Vendedor</label>
              <input
                id="filtro-vendedor"
                placeholder="Nome do vendedor"
                value={nomeVendedor}
                onChange={(e) => {
                  setNomeVendedor(maiusculas(e.target.value))
                  setPagina(1)
                }}
              />
            </div>
            <div>
              <label htmlFor="filtro-situacao">Situação</label>
              <select
                id="filtro-situacao"
                value={situacao}
                onChange={(e) => {
                  setSituacao(e.target.value as SituacaoOrcamento | '')
                  setPagina(1)
                }}
              >
                <option value="">Todas</option>
                {(Object.keys(ROTULO_SITUACAO) as SituacaoOrcamento[]).map((s) => (
                  <option key={s} value={s}>
                    {ROTULO_SITUACAO[s]}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : orcamentos.length === 0 ? (
            <p className="muted">Nenhum orçamento encontrado.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Nº</th>
                  <th>Emissão</th>
                  <th>Válido até</th>
                  <th>Cliente</th>
                  <th>Vendedor</th>
                  <th style={{ textAlign: 'right' }}>Itens</th>
                  <th style={{ textAlign: 'right' }}>Total</th>
                  <th>Situação</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {orcamentos.map((o) => (
                  <tr key={o.idOrcamento}>
                    <td className="mono">{o.idOrcamento}</td>
                    <td>{formatarData(o.dataOrcamento)}</td>
                    <td>{formatarDataSimples(o.dataValidade)}</td>
                    <td>{o.nomeCliente}</td>
                    <td>{o.nomeFuncionario}</td>
                    <td style={{ textAlign: 'right' }}>{o.qtdItens}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(o.valorTotal)}</td>
                    <td>
                      <span className={CLASSE_SITUACAO[o.situacao]}>{ROTULO_SITUACAO[o.situacao]}</span>
                      {o.idVenda && <span className="muted"> venda {o.idVenda}</span>}
                    </td>
                    <td className="acoes-cell">
                      <button
                        type="button"
                        className="acao-icone acao-visualizar"
                        title="Ver orçamento"
                        aria-label={`Ver orçamento nº ${o.idOrcamento}`}
                        onClick={() => abrirDetalhe(o.idOrcamento)}
                      >
                        <IconeOlho />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {orcamentos.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.total} orçamento{data?.total === 1 ? '' : 's'}
            </span>
            <div className="paginacao-paginas">
              <button
                type="button"
                className="btn ghost"
                disabled={pagina <= 1}
                onClick={() => setPagina((p) => Math.max(1, p - 1))}
              >
                Anterior
              </button>
              <span className="mono" style={{ padding: '0 8px' }}>
                {pagina} / {totalPaginas}
              </span>
              <button
                type="button"
                className="btn ghost"
                disabled={pagina >= totalPaginas}
                onClick={() => setPagina((p) => p + 1)}
              >
                Próxima
              </button>
            </div>
          </div>
        </div>
      )}

      {detalhe && (
        <div className="modal-overlay" onClick={() => setDetalhe(null)}>
          <div
            className="modal modal-largo"
            role="dialog"
            aria-label={`Orçamento nº ${detalhe.idOrcamento}`}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="lightbox-topo" style={{ marginBottom: 12 }}>
              <div className="titulo-tela">
                <h2 style={{ margin: 0 }}>Orçamento nº {detalhe.idOrcamento}</h2>
                <span className={CLASSE_SITUACAO[detalhe.situacao]}>{ROTULO_SITUACAO[detalhe.situacao]}</span>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                {detalhe.situacao === 'ABERTO' && (
                  <button type="button" className="btn ghost" onClick={() => setCancelando(detalhe)}>
                    Cancelar orçamento
                  </button>
                )}
                <button type="button" className="btn ghost" onClick={() => setImprimindo(detalhe)}>
                  Imprimir / WhatsApp
                </button>
              </div>
            </div>

            <p className="muted" style={{ marginTop: 0 }}>
              {detalhe.nomeCliente} · Vendedor {detalhe.nomeFuncionario} · Emitido em{' '}
              {formatarData(detalhe.dataOrcamento)} · Válido até{' '}
              <strong>{formatarDataSimples(detalhe.dataValidade)}</strong>
              {detalhe.idVenda && <> · Virou a venda nº <strong>{detalhe.idVenda}</strong></>}
            </p>

            {detalhe.situacao === 'CANCELADO' && (
              <p className="erro-campo">
                Cancelado por {detalhe.nomeUsuarioCancelamento} em{' '}
                {detalhe.dataCancelamento && formatarData(detalhe.dataCancelamento)} — {detalhe.motivoCancelamento}
              </p>
            )}
            {detalhe.situacao === 'VENCIDO' && (
              <p className="erro-campo">
                Passou da validade e não pode mais virar venda. Emita um orçamento novo.
              </p>
            )}
            {detalhe.situacao === 'VENDIDO_PARCIAL' && (
              <p className="muted">
                O cliente levou parte do orçado. O que sobrou não pode ser vendido por este
                orçamento — faça uma venda nova.
              </p>
            )}

            <div className="table-wrap" style={{ maxHeight: '46vh' }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>SKU</th>
                    <th>Produto</th>
                    <th style={{ textAlign: 'right' }}>Qtde</th>
                    <th style={{ textAlign: 'right' }}>Preço</th>
                    <th style={{ textAlign: 'right' }}>Total</th>
                    <th style={{ textAlign: 'right' }}>Estoque</th>
                  </tr>
                </thead>
                <tbody>
                  {detalhe.itens.map((i) => (
                    <tr key={i.idOrcamentoItem}>
                      <td className="mono">{i.sku}</td>
                      <td>
                        {i.descricao}
                        {(i.variacaoCor || i.variacaoTamanho) && (
                          <span className="muted">
                            {' '}
                            {[i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ')}
                          </span>
                        )}
                        {/* ⚠️ Os dois avisos que só a consulta dá — antes de o operador tentar
                            vender com o cliente na frente. */}
                        {i.produtoInativo && (
                          <span className="badge badge-inativo" style={{ marginLeft: 8 }}>
                            produto inativado — não pode ser vendido
                          </span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>{i.qtd}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        R$ {formatarMoeda(i.precoVenda)}
                        {i.precoAtual !== i.precoVenda && (
                          <div className="muted" style={{ fontSize: 11 }}>
                            hoje R$ {formatarMoeda(i.precoAtual)}
                          </div>
                        )}
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.valorTotal)}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>{i.qtdEstoque}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <p style={{ textAlign: 'right', marginTop: 12 }}>
              Subtotal <strong className="mono">R$ {formatarMoeda(detalhe.subtotal)}</strong>
              {detalhe.valorDesconto > 0 && (
                <> · Desconto <strong className="mono">R$ {formatarMoeda(detalhe.valorDesconto)}</strong></>
              )}
              {' '}· Total <strong className="mono" style={{ fontSize: 18 }}>R$ {formatarMoeda(detalhe.valorTotal)}</strong>
            </p>

            {detalhe.observacao && <p className="muted">Observações: {detalhe.observacao}</p>}

            <div className="ajuda-rodape">
              <button type="button" className="btn" onClick={() => setDetalhe(null)}>
                Fechar
              </button>
            </div>
          </div>
        </div>
      )}

      {imprimindo && (
        <OrcamentoImpressaoModal orcamento={imprimindo} aoFechar={() => setImprimindo(null)} />
      )}

      {cancelando && (
        <div className="modal-overlay" onClick={() => (cancelar.isPending ? null : setCancelando(null))}>
          <div className="modal" role="dialog" aria-label="Cancelar orçamento" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Cancelar orçamento nº {cancelando.idOrcamento}</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              O orçamento fica registrado como cancelado e não pode mais virar venda. Não é possível
              desfazer — se o cliente voltar, emita um novo.
            </p>
            <label htmlFor="motivo-cancelamento">Motivo *</label>
            <input
              id="motivo-cancelamento"
              autoFocus
              value={motivo}
              onChange={(e) => setMotivo(maiusculas(e.target.value))}
            />
            <div className="ajuda-rodape">
              <button
                type="button"
                className="btn ghost"
                disabled={cancelar.isPending}
                onClick={() => setCancelando(null)}
              >
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={!motivo.trim() || cancelar.isPending}
                onClick={() => cancelar.mutate()}
              >
                {cancelar.isPending ? 'Cancelando…' : 'Cancelar orçamento'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
