import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEstornar, IconeEstornoRecebimentoCrediario, IconeOlho } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import {
  estornarLoteRecebimento,
  listarLotesRecebimento,
  listarParcelasDoLote,
  type LoteRecebimento,
} from '../../lib/recebimentoCrediario'
import { maiusculas } from '../../lib/texto'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Estorno de Recebimento de Crediário (2026-07-29): busca por nome do cliente (obrigatório) +
 * intervalo de data de recebimento (opcional), lista cada lote já efetivado e permite estornar
 * um de cada vez. O estorno sempre desfaz o lote inteiro — nunca uma parcela isolada, já que um
 * lote pode cobrir parcelas de vendas/contratos diferentes recebidas juntas na mesma operação.
 */
export default function EstornoRecebimentoCrediario() {
  const queryClient = useQueryClient()
  const [nomeCliente, setNomeCliente] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [loteParaEstornar, setLoteParaEstornar] = useState<LoteRecebimento | null>(null)
  const [loteParaVisualizar, setLoteParaVisualizar] = useState<LoteRecebimento | null>(null)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : undefined
  const nomeClienteFiltro = nomeCliente.trim()

  const { data: lotes, isFetching } = useQuery({
    queryKey: ['estorno-recebimento-lotes', nomeClienteFiltro, dataInicialIso, dataFinalIso],
    queryFn: () =>
      listarLotesRecebimento({
        nomeCliente: nomeClienteFiltro,
        dataInicial: dataInicialIso ?? undefined,
        dataFinal: dataFinalIso ?? undefined,
      }),
    enabled: nomeClienteFiltro.length > 0,
  })

  const { data: parcelasDoLote, isLoading: carregandoParcelas } = useQuery({
    queryKey: ['estorno-recebimento-parcelas', loteParaVisualizar?.idLoteRecebimento],
    queryFn: () => listarParcelasDoLote(loteParaVisualizar!.idLoteRecebimento),
    enabled: loteParaVisualizar !== null,
  })

  const estornar = useMutation({
    mutationFn: estornarLoteRecebimento,
    onSuccess: (resultado) => {
      queryClient.invalidateQueries({ queryKey: ['estorno-recebimento-lotes'] })
      setLoteParaEstornar(null)
      setToast({
        texto: `Recebimento #${resultado.idLoteRecebimento} estornado — ${resultado.qtdParcelas} parcela(s), ${moeda(resultado.valorTotal)}.`,
        tipo: 'sucesso',
      })
    },
    onError: (e: unknown) => {
      setLoteParaEstornar(null)
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível estornar o recebimento.', tipo: 'erro' })
    },
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstornoRecebimentoCrediario size={34} />
            <h1>Estorno de Recebimento de Crediário</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.estornorecebimentocrediario.tela" />
            <BotaoFecharTela />
          </div>
        </div>

        {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}

        <div className="card filtros-bar">
          <input
            autoFocus
            placeholder="Nome do cliente *"
            value={nomeCliente}
            onChange={(e) => setNomeCliente(maiusculas(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Nome do cliente"
            style={{ minWidth: 240 }}
          />
          <input
            placeholder="Data inicial"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial"
            style={{ maxWidth: 140 }}
          />
          <input
            placeholder="Data final"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final"
            style={{ maxWidth: 140 }}
          />
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {nomeClienteFiltro.length === 0 ? (
            <p className="muted">Informe o nome do cliente para localizar os recebimentos já feitos.</p>
          ) : isFetching && !lotes ? (
            <p className="muted">Carregando…</p>
          ) : !lotes || lotes.length === 0 ? (
            <p className="muted">Nenhum recebimento encontrado para esse filtro.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Data do Recebimento</th>
                  <th>Cliente</th>
                  <th style={{ textAlign: 'right' }}>Qtd. Parcelas</th>
                  <th style={{ textAlign: 'right' }}>Valor Total</th>
                  <th>Forma(s) de Pagamento</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {lotes.map((lote) => (
                  <tr key={lote.idLoteRecebimento}>
                    <td className="mono">{formatarData(lote.dataRecebimento)}</td>
                    <td>{lote.nomeCliente}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {lote.qtdParcelas}
                    </td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {moeda(lote.valorTotal)}
                    </td>
                    <td>{lote.formasPagamento ?? '—'}</td>
                    <td className="acoes-cell">
                      <button
                        type="button"
                        className="acao-icone acao-visualizar"
                        onClick={() => setLoteParaVisualizar(lote)}
                        aria-label={`Visualizar parcelas do recebimento #${lote.idLoteRecebimento}`}
                        title="Visualizar parcelas"
                      >
                        <IconeOlho />
                      </button>
                      <button
                        type="button"
                        className="acao-icone acao-excluir"
                        onClick={() => setLoteParaEstornar(lote)}
                        aria-label={`Estornar recebimento #${lote.idLoteRecebimento}`}
                        title="Estornar"
                      >
                        <IconeEstornar />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {loteParaVisualizar && (
        <div className="modal-overlay" onClick={() => setLoteParaVisualizar(null)}>
          <div className="modal" role="dialog" aria-label="Parcelas do recebimento" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Parcelas do recebimento</h2>
            <p className="muted" style={{ marginTop: -4 }}>
              {loteParaVisualizar.nomeCliente} — {formatarData(loteParaVisualizar.dataRecebimento)}
            </p>

            <div className="table-wrap" style={{ marginTop: 12, maxHeight: 320 }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Nº Venda</th>
                    <th>Nº PC</th>
                    <th>Vencimento</th>
                    <th style={{ textAlign: 'right' }}>Valor Recebido</th>
                  </tr>
                </thead>
                <tbody>
                  {carregandoParcelas ? (
                    <tr>
                      <td colSpan={4}>
                        <p className="muted" style={{ margin: '8px 0' }}>
                          Carregando…
                        </p>
                      </td>
                    </tr>
                  ) : (
                    (parcelasDoLote ?? []).map((p) => (
                      <tr key={p.idContaReceber}>
                        <td className="mono">{p.idVenda}</td>
                        <td className="mono">
                          {String(p.numeroParcela).padStart(2, '0')}/{String(p.totalParcelas).padStart(2, '0')}
                        </td>
                        <td>{new Date(p.dataVencimento).toLocaleDateString('pt-BR')}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {moeda(p.valorRecebido)}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setLoteParaVisualizar(null)}>
                Fechar
              </button>
            </div>
          </div>
        </div>
      )}

      {loteParaEstornar && (
        <div className="modal-overlay" onClick={() => setLoteParaEstornar(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar estorno" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Estornar recebimento?</h2>
            <p className="muted">
              Tem certeza que deseja estornar o recebimento de <strong>{loteParaEstornar.nomeCliente}</strong> feito em{' '}
              {formatarData(loteParaEstornar.dataRecebimento)} ({moeda(loteParaEstornar.valorTotal)})? As{' '}
              <strong>{loteParaEstornar.qtdParcelas}</strong> parcela(s) deste lote voltarão a ficar em aberto e os
              lançamentos de caixa serão apagados. Esta ação não pode ser desfeita.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setLoteParaEstornar(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={estornar.isPending}
                onClick={() => estornar.mutate(loteParaEstornar.idLoteRecebimento)}
              >
                {estornar.isPending ? 'Estornando…' : 'Estornar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
