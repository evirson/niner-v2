import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeFechamentoCaixa } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  buscarFechamentoCaixa,
  fecharCaixa,
  listarCaixasAbertos,
  reabrirCaixa,
  rotuloCarteira,
  type ResultadoFechamento,
} from '../../lib/caixa'
import { useEu } from '../../lib/eu'
import { completarMoeda, desmascararMoeda, formatarMoeda, mascararMoeda } from '../../lib/masks'
import FechamentoCaixaPreviewModal from './FechamentoCaixaPreviewModal'
import LancamentosCarteiraModal from './LancamentosCarteiraModal'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Fechamento de Caixa (2026-08-19, substitui a contagem "às cegas" por data/usuário) — a tela
 * abre direto na grade "Caixas Abertos" ({@link listarCaixasAbertos}: operador só vê o próprio,
 * administrador vê de todo mundo em qualquer empresa). Escolhida uma linha, mostra cada carteira
 * com o **valor que o sistema espera** ao lado do campo pra digitar o valor contado — não é mais
 * contagem cega. Se todas baterem, fecha e a impressão (mesma bobina térmica 80mm da papeleta de
 * venda) abre sozinha. Se alguma não bater, mostra a divergência e oferece "Fechar Mesmo Assim"
 * ({@code forcarComDivergencia}), que fecha do mesmo jeito com a diferença registrada na
 * observação do caixa (auditoria, P3) — nada é gravado até essa confirmação explícita.
 *
 * Reabertura (ADMIN-only): como um caixa fechado some da grade de abertos, o administrador
 * localiza pelo número do caixa (a mensagem de erro de operações bloqueadas por caixa fechado já
 * traz esse número).
 */
export default function FechamentoCaixa() {
  // ⛔ RBAC (auditoria 2026-08-29, rodada 5): a varredura das rodadas 1-4 cobriu os CADASTROS
  // e deixou as ROTINAS OPERACIONAIS de fora -- que e onde o 403 tardio custa mais caro, porque
  // o operador so descobre depois de montar a operacao inteira com o cliente na frente.
  const acoes = usePermissaoDaTela('fechamento-caixa')
  const queryClient = useQueryClient()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [idCaixaSelecionado, setIdCaixaSelecionado] = useState<number | null>(null)
  const [buscaNumeroCaixa, setBuscaNumeroCaixa] = useState('')
  const [valoresContados, setValoresContados] = useState<Record<number, string>>({})
  const [erroContagem, setErroContagem] = useState('')
  const [resultadoDivergencia, setResultadoDivergencia] = useState<ResultadoFechamento | null>(null)
  const [reaberturaAberta, setReaberturaAberta] = useState(false)
  const [motivoReabertura, setMotivoReabertura] = useState('')
  const [carteiraParaConferir, setCarteiraParaConferir] = useState<{ idCarteira: number; rotulo: string } | null>(null)
  const [mostrarPreview, setMostrarPreview] = useState(false)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const { data: abertos, isLoading: carregandoAbertos } = useQuery({
    queryKey: ['caixa-abertos'],
    queryFn: listarCaixasAbertos,
    enabled: idCaixaSelecionado === null,
  })

  const {
    data: fechamento,
    isLoading,
    isFetching,
    error,
  } = useQuery({
    queryKey: ['caixa-fechamento', idCaixaSelecionado],
    queryFn: () => buscarFechamentoCaixa(idCaixaSelecionado!),
    enabled: idCaixaSelecionado !== null,
    retry: false,
  })

  // Novo caixa carregado — reseta a contagem e qualquer divergência da tentativa anterior. Campos
  // nascem com o valor esperado já preenchido (a contagem não é mais às cegas): o operador ajusta
  // pra baixo/cima conforme o que contou fisicamente, em vez de digitar tudo do zero.
  //
  // ⚠️ A dependência é o DADO (`fechamento`) mais o `isFetching`, NUNCA `fechamento?.idCaixa`.
  // Com o id na dependência, reabrir o MESMO caixa dentro do `gcTime` (5 min) preenchia os campos
  // a partir do cache no primeiro render, e quando o refetch trazia o esperado NOVO o id não tinha
  // mudado — o efeito não rodava outra vez e o prefill ficava no número velho. O caminho que
  // produz isso é o do dia a dia desde a V095: o fechamento recusa enquanto houver excedente do
  // fundo, o operador vai sangrar e VOLTA para cá. Ele veria "Valor em Caixa: 200,00" com o campo
  // contado dizendo "500,00" e, confiando no prefill, "Fechar Mesmo Assim" gravaria em
  // `caixa_fechamento_conferencia` uma sobra de R$ 300,00 que nunca existiu. Esperar
  // `isFetching === false` é o mesmo remédio de `DevolucaoProduto` e do `ComprovantePapeletaModal`.
  useEffect(() => {
    if (!fechamento || isFetching) return
    const iniciais: Record<number, string> = {}
    fechamento.linhas.forEach((l) => {
      iniciais[l.idCarteira] = formatarMoeda(l.valorEsperado)
    })
    setValoresContados(iniciais)
    setResultadoDivergencia(null)
    setErroContagem('')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fechamento, isFetching])

  const fechar = useMutation({
    mutationFn: (forcarComDivergencia: boolean) => {
      const valoresContadosPayload = fechamento!.linhas.map((l) => ({
        idCarteira: l.idCarteira,
        valorContado: desmascararMoeda(valoresContados[l.idCarteira] ?? ''),
      }))
      return fecharCaixa({ idCaixa: fechamento!.idCaixa, valoresContados: valoresContadosPayload, forcarComDivergencia })
    },
    onSuccess: (resultado) => {
      if (resultado.fechado) {
        setResultadoDivergencia(null)
        queryClient.invalidateQueries({ queryKey: ['caixa-fechamento'] })
        queryClient.invalidateQueries({ queryKey: ['caixa-status'] })
        queryClient.invalidateQueries({ queryKey: ['caixa-abertos'] })
        setToast({ texto: 'Caixa fechado com sucesso.', tipo: 'sucesso' })
        setMostrarPreview(true)
      } else {
        setResultadoDivergencia(resultado)
        setToast({ texto: 'Os valores contados não bateram com o esperado — confira a divergência abaixo.', tipo: 'erro' })
      }
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível fechar o caixa.', tipo: 'erro' }),
  })

  const reabrir = useMutation({
    mutationFn: () => reabrirCaixa(fechamento!.idCaixa, motivoReabertura),
    onSuccess: () => {
      setReaberturaAberta(false)
      setMotivoReabertura('')
      setResultadoDivergencia(null)
      queryClient.invalidateQueries({ queryKey: ['caixa-fechamento'] })
      queryClient.invalidateQueries({ queryKey: ['caixa-status'] })
      queryClient.invalidateQueries({ queryKey: ['caixa-abertos'] })
      setToast({ texto: 'Caixa reaberto. A conferência anterior foi apagada.', tipo: 'sucesso' })
    },
    onError: (e: unknown) =>
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível reabrir o caixa.', tipo: 'erro' }),
  })

  const confirmarFechamento = () => {
    const faltando = fechamento!.linhas.some((l) => !(valoresContados[l.idCarteira] ?? '').trim())
    if (faltando) {
      setErroContagem('Informe o valor contado de todas as carteiras — nenhuma pode ficar em branco.')
      return
    }
    setErroContagem('')
    fechar.mutate(false)
  }

  const voltarParaLista = () => {
    setIdCaixaSelecionado(null)
    setBuscaNumeroCaixa('')
    setResultadoDivergencia(null)
  }

  const naoEncontrado = error instanceof ApiError && error.status === 404

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFechamentoCaixa size={34} />
            <h1>Fechamento de Caixa</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.fechamentocaixa.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
          {idCaixaSelecionado === null && (
            <section className="section">
              <p className="section-label">Caixas Abertos</p>
              {carregandoAbertos && <p className="muted">Carregando…</p>}
              {abertos && abertos.length === 0 && <p className="muted">Nenhum caixa aberto no momento.</p>}
              {abertos && abertos.length > 0 && (
                <div className="table-wrap">
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>Usuário</th>
                        <th>Empresa</th>
                        <th>Carteira de Abertura</th>
                        <th>Abertura</th>
                        <th>Saldo Inicial</th>
                      </tr>
                    </thead>
                    <tbody>
                      {abertos.map((c) => (
                        <tr key={c.idCaixa} style={{ cursor: 'pointer' }} onClick={() => setIdCaixaSelecionado(c.idCaixa)}>
                          <td>{c.nomeUsuario}</td>
                          <td>{c.nomeEmpresa}</td>
                          <td>{c.nomeCarteira}</td>
                          <td>{formatarDataHora(c.dataAbertura)}</td>
                          <td className="mono">{moeda(c.saldoInicial)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {ehAdmin && (
                <div className="form-grid" style={{ marginTop: 16 }}>
                  <div className="col-3">
                    <label htmlFor="fechamento-caixa-numero">Ver Caixa Já Fechado (nº)</label>
                    <input
                      id="fechamento-caixa-numero"
                      className="mono"
                      inputMode="numeric"
                      placeholder="Nº do caixa"
                      value={buscaNumeroCaixa}
                      onChange={(e) => setBuscaNumeroCaixa(e.target.value.replace(/\D/g, ''))}
                      onKeyDown={(e) => {
                        // ⚠️ `preventDefault` (auditoria 2026-08-29, rodada 5): a tela não está
                        // dentro de um `<form>`, então o listener global de Enter também roda e
                        // leva o foco para o campo seguinte — a busca acontece e o cursor some do
                        // lugar. Era a única violação restante: os cinco leitores de código de
                        // barras e os quatro campos de busca do projeto já o chamam.
                        if (e.key === 'Enter' && buscaNumeroCaixa) {
                          e.preventDefault()
                          setIdCaixaSelecionado(Number(buscaNumeroCaixa))
                        }
                      }}
                    />
                  </div>
                  <div className="col-3" style={{ display: 'flex', alignItems: 'flex-end' }}>
                    <button
                      type="button"
                      className="btn ghost"
                      disabled={!buscaNumeroCaixa}
                      onClick={() => setIdCaixaSelecionado(Number(buscaNumeroCaixa))}
                    >
                      Localizar
                    </button>
                  </div>
                </div>
              )}
            </section>
          )}

          {idCaixaSelecionado !== null && isLoading && <p className="muted">Carregando…</p>}
          {idCaixaSelecionado !== null && naoEncontrado && (
            <>
              <p className="muted">Nenhum caixa encontrado com esse número.</p>
              <button type="button" className="btn ghost" onClick={voltarParaLista}>
                Voltar
              </button>
            </>
          )}
          {idCaixaSelecionado !== null && error && !naoEncontrado && (
            <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível buscar o fechamento.'}</p>
          )}

          {fechamento && (
            <section className="section">
              <div className="ajuda-rodape" style={{ marginBottom: 8 }}>
                <p className="section-label" style={{ margin: 0 }}>
                  {fechamento.fechado ? 'Caixa Fechado' : 'Caixa Aberto'}
                </p>
                <button type="button" className="btn ghost" onClick={voltarParaLista}>
                  Voltar à Lista
                </button>
              </div>

              {/* Reabertura (2026-08-14) — só ADMIN, e só faz sentido em caixa fechado. Existe
                  porque estorno de crediário e exclusão/reabertura de conta a pagar recusam
                  apagar lançamento de caixa fechado e mandam o operador vir reabrir aqui. */}
              {fechamento.fechado && ehAdmin && acoes.excluir && (
                <div style={{ marginBottom: 12 }}>
                  <button type="button" className="btn ghost" onClick={() => setReaberturaAberta(true)}>
                    Reabrir Caixa
                  </button>
                  <p className="muted" style={{ marginTop: 6 }}>
                    Reabrir apaga a conferência já gravada — depois de corrigir, feche o caixa de novo.
                  </p>
                </div>
              )}
              <div className="form-grid">
                <div className="col-4">
                  <label>Usuário</label>
                  <div className="pdv-selecao-valor">
                    <span>{fechamento.nomeUsuario}</span>
                  </div>
                </div>
                <div className="col-4">
                  <label>Abertura</label>
                  <div className="pdv-selecao-valor">
                    <span>{formatarDataHora(fechamento.dataAbertura)}</span>
                  </div>
                </div>
                <div className="col-4">
                  <label>Fechamento</label>
                  <div className="pdv-selecao-valor">
                    <span>{fechamento.fechado ? formatarDataHora(fechamento.dataFechamento) : '(em aberto)'}</span>
                  </div>
                </div>
              </div>

              {/* Caixa aberto, sem tentativa de fechamento em andamento: mostra o valor esperado
                  de cada carteira lado a lado com o campo do valor contado — não é mais uma
                  contagem às cegas (2026-08-19, pedido do dono do produto). */}
              {!fechamento.fechado && !resultadoDivergencia && (
                <>
                  <div className="table-wrap" style={{ marginTop: 16 }}>
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          <th>Carteira</th>
                          <th>Valor em Caixa</th>
                          <th>Valor Contado</th>
                        </tr>
                      </thead>
                      <tbody>
                        {fechamento.linhas.map((l) => (
                          <tr key={l.idCarteira}>
                            <td>{rotuloCarteira(l)}</td>
                            <td className="mono">{moeda(l.valorEsperado)}</td>
                            <td>
                              <input
                                id={`contado-${l.idCarteira}`}
                                className="mono"
                                inputMode="decimal"
                                placeholder="0,00"
                                value={valoresContados[l.idCarteira] ?? ''}
                                onChange={(e) => {
                                  setErroContagem('')
                                  setValoresContados((v) => ({ ...v, [l.idCarteira]: mascararMoeda(e.target.value) }))
                                }}
                                onBlur={(e) => {
                                  if (!e.target.value.trim()) return
                                  setValoresContados((v) => ({
                                    ...v,
                                    [l.idCarteira]: formatarMoeda(desmascararMoeda(completarMoeda(e.target.value))),
                                  }))
                                }}
                                onFocus={(e) => e.target.select()}
                              />
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  {erroContagem && <p className="erro-campo">{erroContagem}</p>}
                  <div className="ajuda-rodape">
                    <span />
                    <button type="button" className="btn" disabled={fechar.isPending || !acoes.incluir} onClick={confirmarFechamento}>
                      {fechar.isPending ? 'Conferindo…' : 'Fechar Caixa'}
                    </button>
                  </div>
                </>
              )}

              {/* Valores não bateram: mostra esperado × contado × diferença por carteira; clicar
                  numa linha abre o drill-down analítico dos lançamentos daquela carteira. Fechar
                  mesmo assim registra a divergência na observação do caixa (auditoria, P3). */}
              {resultadoDivergencia && (
                <div style={{ marginTop: 16 }}>
                  <p className="erro">
                    Os valores contados não bateram com o esperado. Clique numa carteira para conferir os
                    lançamentos, corrija e conte de novo, ou feche mesmo assim.
                  </p>
                  <div className="table-wrap">
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          <th>Carteira</th>
                          <th>Esperado</th>
                          <th>Contado</th>
                          <th>Diferença</th>
                        </tr>
                      </thead>
                      <tbody>
                        {resultadoDivergencia.linhas.map((l) => {
                          const bateu = l.diferenca === 0
                          return (
                            <tr
                              key={l.idCarteira}
                              onClick={() => setCarteiraParaConferir({ idCarteira: l.idCarteira, rotulo: rotuloCarteira(l) })}
                              style={{ cursor: 'pointer' }}
                              title="Clique para ver os lançamentos desta carteira"
                            >
                              <td>{rotuloCarteira(l)}</td>
                              <td className="mono">{moeda(l.valorEsperado)}</td>
                              <td className="mono">{moeda(l.valorContado)}</td>
                              <td className="mono" style={bateu ? undefined : { color: 'var(--danger)' }}>
                                {moeda(l.diferenca)}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                  <div className="ajuda-rodape">
                    <button type="button" className="btn ghost" onClick={() => setResultadoDivergencia(null)}>
                      Contar de Novo
                    </button>
                    <button type="button" className="btn" disabled={fechar.isPending} onClick={() => fechar.mutate(true)}>
                      {fechar.isPending ? 'Fechando…' : 'Fechar Mesmo Assim'}
                    </button>
                  </div>
                </div>
              )}

              {/* Caixa fechado com sucesso (agora ou antes) — relatório completo + impressão. */}
              {fechamento.fechado && (
                <>
                  <div className="table-wrap" style={{ marginTop: 16 }}>
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          <th>Carteira</th>
                          <th>Saldo Inicial</th>
                          <th>Crédito</th>
                          <th>Débito</th>
                          <th>Esperado</th>
                        </tr>
                      </thead>
                      <tbody>
                        {fechamento.linhas.map((l) => (
                          <tr key={l.idCarteira}>
                            <td>{rotuloCarteira(l)}</td>
                            <td className="mono">{moeda(l.saldoInicial)}</td>
                            <td className="mono">{moeda(l.totalCredito)}</td>
                            <td className="mono">{moeda(l.totalDebito)}</td>
                            <td className="mono">{moeda(l.valorEsperado)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  <p className="section-label" style={{ marginTop: 16 }}>
                    Conferência
                  </p>
                  <div className="table-wrap">
                    <table className="table table-compacta">
                      <thead>
                        <tr>
                          <th>Carteira</th>
                          <th>Esperado</th>
                          <th>Contado</th>
                          <th>Diferença</th>
                        </tr>
                      </thead>
                      <tbody>
                        {fechamento.conferencia.map((c) => (
                          <tr key={c.idCarteira}>
                            <td>{rotuloCarteira(c)}</td>
                            <td className="mono">{moeda(c.valorEsperado)}</td>
                            <td className="mono">{moeda(c.valorContado)}</td>
                            <td className="mono">{moeda(c.diferenca)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  <div className="ajuda-rodape">
                    <button type="button" className="btn ghost" onClick={() => setMostrarPreview(true)}>
                      Visualizar Impressão
                    </button>
                    <span />
                  </div>
                </>
              )}
            </section>
          )}
        </div>
      </div>

      {mostrarPreview && fechamento && (
        <FechamentoCaixaPreviewModal fechamento={fechamento} aoFechar={() => setMostrarPreview(false)} />
      )}

      {carteiraParaConferir && fechamento && (
        <LancamentosCarteiraModal
          idCaixa={fechamento.idCaixa}
          idCarteira={carteiraParaConferir.idCarteira}
          nomeCarteira={carteiraParaConferir.rotulo}
          aoFechar={() => setCarteiraParaConferir(null)}
        />
      )}

      {/* Motivo obrigatório, mesmo par ADMIN + motivo do Cancelamento de Venda — sem o porquê
          gravado em `caixa_mestre.observacoes`, ninguém audita a reabertura depois (P3). */}
      {reaberturaAberta && fechamento && (
        <div className="modal-overlay" onClick={() => setReaberturaAberta(false)}>
          <div className="modal" role="dialog" aria-label="Reabrir Caixa" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Reabrir Caixa</h2>
            <p className="muted">
              A conferência já gravada será apagada. Depois de corrigir o que precisa, feche o caixa de novo.
            </p>
            <div className="form-grid">
              <div className="col-12">
                <label htmlFor="motivo-reabertura">Motivo *</label>
                <input
                  id="motivo-reabertura"
                  autoFocus
                  maxLength={200}
                  value={motivoReabertura}
                  onChange={(e) => setMotivoReabertura(e.target.value.toUpperCase())}
                  placeholder="EX.: ESTORNAR RECEBIMENTO LANCADO POR ENGANO"
                />
              </div>
            </div>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setReaberturaAberta(false)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={motivoReabertura.trim().length === 0 || reabrir.isPending}
                onClick={() => reabrir.mutate()}
              >
                {reabrir.isPending ? 'Reabrindo…' : 'Reabrir Caixa'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
