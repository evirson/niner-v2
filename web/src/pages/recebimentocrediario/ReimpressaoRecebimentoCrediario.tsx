import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRecebimentoCrediario } from '../../components/Icones'
import { formatarMoeda, mascararData, dataParaIso, dataValida } from '../../lib/masks'
import { listarLotesRecebimento } from '../../lib/recebimentoCrediario'
import { maiusculas } from '../../lib/texto'
import ComprovanteRecebimentoModal from './ComprovanteRecebimentoModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Reimpressão de Papeleta de Recebimento de Crediário (2026-08-06) — localiza um lote de
 * recebimento já efetivado (nome do cliente obrigatório + período opcional, mesmo filtro/
 * endpoint já usado pelo Estorno de Crediário, `GET /recebimento-crediario/estornos`) e reabre o
 * comprovante pra imprimir/salvar de novo. Reaproveita `ComprovanteRecebimentoModal` (mesmo
 * componente do popup pós-efetivar, com `reimpressao` ligado).
 */
export default function ReimpressaoRecebimentoCrediario() {
  const [nomeCliente, setNomeCliente] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [idLoteReimpressao, setIdLoteReimpressao] = useState<number | null>(null)

  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : undefined
  const nomeClienteFiltro = nomeCliente.trim()

  const { data: lotes, isFetching } = useQuery({
    queryKey: ['reimpressao-recebimento-lotes', nomeClienteFiltro, dataInicialIso, dataFinalIso],
    queryFn: () =>
      listarLotesRecebimento({
        nomeCliente: nomeClienteFiltro,
        dataInicial: dataInicialIso ?? undefined,
        dataFinal: dataFinalIso ?? undefined,
      }),
    enabled: nomeClienteFiltro.length > 0,
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRecebimentoCrediario size={34} />
            <h1>Reimpressão de Papeleta de Recebimento de Crediário</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.reimpressaorecebimentocrediario.tela" />
            <BotaoFecharTela />
          </div>
        </div>

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
            placeholder="Data inicial do recebimento"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial do recebimento"
            style={{ maxWidth: 170 }}
          />
          <input
            placeholder="Data final do recebimento"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final do recebimento"
            style={{ maxWidth: 170 }}
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
                </tr>
              </thead>
              <tbody>
                {lotes.map((lote) => (
                  <tr
                    key={lote.idLoteRecebimento}
                    tabIndex={0}
                    onClick={() => setIdLoteReimpressao(lote.idLoteRecebimento)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        setIdLoteReimpressao(lote.idLoteRecebimento)
                      }
                    }}
                    title="Clique para reimprimir a papeleta deste recebimento"
                  >
                    <td className="mono">{formatarData(lote.dataRecebimento)}</td>
                    <td>{lote.nomeCliente}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {lote.qtdParcelas}
                    </td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {moeda(lote.valorTotal)}
                    </td>
                    <td>{lote.formasPagamento ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {idLoteReimpressao !== null && (
        <ComprovanteRecebimentoModal
          idLoteRecebimento={idLoteReimpressao}
          reimpressao
          aoFechar={() => setIdLoteReimpressao(null)}
        />
      )}
    </div>
  )
}
