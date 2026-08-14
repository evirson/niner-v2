import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import CamposAberturaCaixa from '../../components/CamposAberturaCaixa'
import { IconeCaixa } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { abrirCaixa, buscarStatusCaixa, listarCarteirasParaAbertura } from '../../lib/caixa'
import { completarMoeda, desmascararMoeda, formatarMoeda, mascararMoeda } from '../../lib/masks'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Abertura de Caixa (2026-07-30) — tela dedicada: mostra se já há caixa aberto hoje para este
 * usuário/empresa; se não houver, pede o saldo inicial e a moeda (tipo de carteira,
 * preferencialmente "Dinheiro"). PDV e Recebimento de Crediário usam o mesmo endpoint via um
 * popup obrigatório ({@link AberturaCaixaModal}) quando detectam caixa fechado.
 */
export default function AberturaCaixa() {
  const queryClient = useQueryClient()
  const [idCarteira, setIdCarteira] = useState<number | ''>('')
  const [valorTexto, setValorTexto] = useState('0,00')
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const { data: status, isLoading } = useQuery({ queryKey: ['caixa-status'], queryFn: buscarStatusCaixa })
  const { data: carteiras } = useQuery({
    queryKey: ['caixa-carteiras'],
    queryFn: listarCarteirasParaAbertura,
    enabled: status !== undefined && !status.aberto,
  })

  useEffect(() => {
    if (idCarteira !== '' || !carteiras || carteiras.length === 0) return
    setIdCarteira(carteiras[0].idCarteira)
  }, [carteiras, idCarteira])

  const abrir = useMutation({
    mutationFn: () => {
      if (idCarteira === '') throw new ApiError(400, 'Selecione a moeda do saldo inicial.')
      return abrirCaixa({ idCarteira, saldoInicial: desmascararMoeda(valorTexto) })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['caixa-status'] })
      setToast({ texto: 'Caixa aberto com sucesso.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível abrir o caixa.', tipo: 'erro' }),
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCaixa size={34} />
            <h1>Abertura de Caixa</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.aberturacaixa.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : status?.aberto ? (
            <section className="section">
              <p className="section-label">Caixa Aberto Hoje</p>
              <div className="form-grid">
                <div className="col-6">
                  <label>Aberto em</label>
                  <div className="pdv-selecao-valor">
                    <span>{formatarDataHora(status.dataAbertura!)}</span>
                  </div>
                </div>
                <div className="col-6">
                  <label>Moeda do Saldo Inicial</label>
                  <div className="pdv-selecao-valor">
                    <span>{status.nomeCarteira}</span>
                  </div>
                </div>
                <div className="col-6">
                  <label>Saldo Inicial</label>
                  <div className="pdv-selecao-valor">
                    <span className="mono">{moeda(status.saldoInicial ?? 0)}</span>
                  </div>
                </div>
              </div>
            </section>
          ) : (
            <section className="section">
              <p className="section-label">Abrir Caixa</p>
              <p className="muted" style={{ marginTop: -4 }}>
                Não há caixa aberto hoje para este usuário — informe o saldo inicial e a moeda.
              </p>
              <CamposAberturaCaixa
                carteiras={carteiras ?? []}
                valorTexto={valorTexto}
                aoMudarValor={(t) => setValorTexto(mascararMoeda(t))}
                aoFinalizarValor={(t) => setValorTexto(formatarMoeda(desmascararMoeda(completarMoeda(t))))}
              />
              <div className="ajuda-rodape">
                <button
                  type="button"
                  className="btn"
                  disabled={idCarteira === '' || abrir.isPending}
                  onClick={() => abrir.mutate()}
                >
                  {abrir.isPending ? 'Abrindo…' : 'Abrir Caixa'}
                </button>
              </div>
            </section>
          )}
        </div>
      </div>

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
