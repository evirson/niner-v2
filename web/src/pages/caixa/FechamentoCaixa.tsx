import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeFechamentoCaixa } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarFechamentoCaixa, fecharCaixa, type ResultadoFechamento } from '../../lib/caixa'
import { hojeISO } from '../../lib/datas'
import { useEu } from '../../lib/eu'
import { completarMoeda, dataParaIso, dataValida, desmascararMoeda, formatarMoeda, isoParaData, mascararData, mascararMoeda } from '../../lib/masks'
import { listarUsuarios } from '../../lib/usuarios'
import FechamentoCaixaPreviewModal from './FechamentoCaixaPreviewModal'
import LancamentosCarteiraModal from './LancamentosCarteiraModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Fechamento de Caixa "às cegas" (2026-07-30, revisado no mesmo dia) — ADMIN escolhe o usuário
 * (select) + a data do movimento; OPERADOR só vê/fecha o próprio caixa. Mostra os TIPOS de
 * carteira com movimento no dia, sem os valores (contagem às cegas de verdade), pede o valor
 * contado de cada um e só fecha quando todos batem exatamente — senão mostra a divergência
 * (esperado/contado/diferença por carteira) sem gravar nada, com drill-down analítico pra
 * conferir lançamento a lançamento. Impressão em A4 só fica disponível depois do fechamento
 * bem-sucedido ({@link FechamentoCaixaPreviewModal}).
 */
export default function FechamentoCaixa() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [idUsuarioSelecionado, setIdUsuarioSelecionado] = useState<number | ''>('')
  const [dataTexto, setDataTexto] = useState(isoParaData(hojeISO()))
  const [valoresContados, setValoresContados] = useState<Record<number, string>>({})
  const [resultadoDivergencia, setResultadoDivergencia] = useState<ResultadoFechamento | null>(null)
  const [carteiraParaConferir, setCarteiraParaConferir] = useState<{ idCarteira: number; nomeCarteira: string } | null>(null)
  const [mostrarPreview, setMostrarPreview] = useState(false)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  useEffect(() => {
    if (eu && !ehAdmin) return
    if (eu && idUsuarioSelecionado === '') setIdUsuarioSelecionado(eu.usuario.idUsuario)
  }, [eu, ehAdmin, idUsuarioSelecionado])

  const { data: usuarios } = useQuery({
    queryKey: ['usuarios-select-fechamento-caixa'],
    queryFn: () => listarUsuarios({ status: 'ATIVOS', tamanho: 100 }),
    enabled: ehAdmin,
  })

  const dataIso = dataValida(dataTexto) ? dataParaIso(dataTexto) : null
  const podeBuscar = dataIso !== null && (!ehAdmin || idUsuarioSelecionado !== '')

  const {
    data: fechamento,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['caixa-fechamento', ehAdmin ? idUsuarioSelecionado : eu?.usuario.idUsuario, dataIso],
    queryFn: () => buscarFechamentoCaixa(dataIso!, ehAdmin ? (idUsuarioSelecionado as number) : undefined),
    enabled: podeBuscar,
    retry: false,
  })

  // Novo caixa carregado — reseta a contagem às cegas e qualquer divergência da tentativa anterior.
  useEffect(() => {
    if (!fechamento) return
    const iniciais: Record<number, string> = {}
    fechamento.linhas.forEach((l) => {
      iniciais[l.idCarteira] = '0,00'
    })
    setValoresContados(iniciais)
    setResultadoDivergencia(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fechamento?.idCaixa])

  const fechar = useMutation({
    mutationFn: () => {
      const valoresContadosPayload = fechamento!.linhas.map((l) => ({
        idCarteira: l.idCarteira,
        valorContado: desmascararMoeda(valoresContados[l.idCarteira] ?? '0,00'),
      }))
      return fecharCaixa({ idCaixa: fechamento!.idCaixa, valoresContados: valoresContadosPayload })
    },
    onSuccess: (resultado) => {
      if (resultado.fechado) {
        setResultadoDivergencia(null)
        queryClient.invalidateQueries({ queryKey: ['caixa-fechamento'] })
        queryClient.invalidateQueries({ queryKey: ['caixa-status'] })
        setToast({ texto: 'Caixa fechado com sucesso.', tipo: 'sucesso' })
      } else {
        setResultadoDivergencia(resultado)
        setToast({ texto: 'Os valores contados não bateram com o esperado — confira a divergência abaixo.', tipo: 'erro' })
      }
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível fechar o caixa.', tipo: 'erro' }),
  })

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
            <button type="button" className="btn ghost" onClick={() => navigate('/')}>
              Voltar
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
          <section className="section">
            <p className="section-label">Buscar Caixa</p>
            <div className="form-grid">
              {ehAdmin && (
                <div className="col-6">
                  <label htmlFor="fechamento-caixa-usuario">Usuário *</label>
                  <select
                    id="fechamento-caixa-usuario"
                    value={idUsuarioSelecionado}
                    onChange={(e) => setIdUsuarioSelecionado(e.target.value ? Number(e.target.value) : '')}
                  >
                    <option value="">Selecione…</option>
                    {usuarios?.itens.map((u) => (
                      <option key={u.idUsuario} value={u.idUsuario}>
                        {u.nome}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <div className="col-3">
                <label htmlFor="fechamento-caixa-data">Data do Movimento *</label>
                <input
                  id="fechamento-caixa-data"
                  className="mono"
                  inputMode="numeric"
                  placeholder="dd/mm/aaaa"
                  value={dataTexto}
                  onChange={(e) => setDataTexto(mascararData(e.target.value))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
            </div>
          </section>

          {isLoading && podeBuscar && <p className="muted">Carregando…</p>}
          {naoEncontrado && <p className="muted">Nenhum caixa encontrado para este usuário nesta data.</p>}
          {error && !naoEncontrado && <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível buscar o fechamento.'}</p>}

          {fechamento && (
            <section className="section">
              <p className="section-label">{fechamento.fechado ? 'Caixa Fechado' : 'Caixa Aberto'}</p>
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

              {/* Caixa aberto, sem tentativa de fechamento em andamento: contagem às cegas — só
                  os NOMES das carteiras com movimento, nenhum valor do sistema aparece aqui. */}
              {!fechamento.fechado && !resultadoDivergencia && (
                <>
                  <p className="muted" style={{ marginTop: 16 }}>
                    Informe quanto você tem de cada forma de pagamento abaixo (contagem às cegas — os valores do
                    sistema só aparecem depois de fechar).
                  </p>
                  <div className="form-grid" style={{ marginTop: 8 }}>
                    {fechamento.linhas.map((l) => (
                      <div className="col-4" key={l.idCarteira}>
                        <label htmlFor={`contado-${l.idCarteira}`}>{l.nomeCarteira} *</label>
                        <input
                          id={`contado-${l.idCarteira}`}
                          className="mono"
                          inputMode="decimal"
                          value={valoresContados[l.idCarteira] ?? '0,00'}
                          onChange={(e) =>
                            setValoresContados((v) => ({ ...v, [l.idCarteira]: mascararMoeda(e.target.value) }))
                          }
                          onBlur={(e) =>
                            setValoresContados((v) => ({
                              ...v,
                              [l.idCarteira]: formatarMoeda(desmascararMoeda(completarMoeda(e.target.value))),
                            }))
                          }
                          onFocus={(e) => e.target.select()}
                        />
                      </div>
                    ))}
                  </div>
                  <div className="ajuda-rodape">
                    <span />
                    <button type="button" className="btn" disabled={fechar.isPending} onClick={() => fechar.mutate()}>
                      {fechar.isPending ? 'Conferindo…' : 'Fechar Caixa'}
                    </button>
                  </div>
                </>
              )}

              {/* Valores não bateram: mostra esperado × contado × diferença por carteira; clicar
                  numa linha abre o drill-down analítico dos lançamentos daquela carteira. */}
              {resultadoDivergencia && (
                <div style={{ marginTop: 16 }}>
                  <p className="erro">
                    Os valores contados não bateram com o esperado. Clique numa carteira para conferir os
                    lançamentos, ou volte e conte de novo.
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
                              onClick={() => setCarteiraParaConferir({ idCarteira: l.idCarteira, nomeCarteira: l.nomeCarteira })}
                              style={{ cursor: 'pointer' }}
                              title="Clique para ver os lançamentos desta carteira"
                            >
                              <td>{l.nomeCarteira}</td>
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
                    <span />
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
                            <td>{l.nomeCarteira}</td>
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
                    Conferência (contagem às cegas)
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
                            <td>{c.nomeCarteira}</td>
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
          nomeCarteira={carteiraParaConferir.nomeCarteira}
          aoFechar={() => setCarteiraParaConferir(null)}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
