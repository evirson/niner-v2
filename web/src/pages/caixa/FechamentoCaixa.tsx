import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeFechamentoCaixa } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarFechamentoCaixa, fecharCaixa } from '../../lib/caixa'
import { hojeISO } from '../../lib/datas'
import { useEu } from '../../lib/eu'
import { completarMoeda, dataParaIso, dataValida, desmascararMoeda, formatarMoeda, isoParaData, mascararData, mascararMoeda } from '../../lib/masks'
import { listarUsuarios } from '../../lib/usuarios'
import FechamentoCaixaPreviewModal from './FechamentoCaixaPreviewModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR')
}

const NOME_CARTEIRA_DINHEIRO = 'DINHEIRO'

/**
 * Fechamento de Caixa (2026-07-30) — ADMIN escolhe o usuário (select) + a data do movimento;
 * OPERADOR só vê/fecha o próprio caixa (sem o select, mesma checagem de {@code
 * CaixaService.buscarParaFechamento}). Totais por tipo de carteira (crédito/débito separados)
 * recalculados a partir de {@code caixa_detalhe}; conferência de dinheiro contado comparada
 * contra a carteira "DINHEIRO" antes de fechar. Impressão em A4, pré-visualização antes de
 * imprimir/salvar PDF ({@link FechamentoCaixaPreviewModal}).
 */
export default function FechamentoCaixa() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [idUsuarioSelecionado, setIdUsuarioSelecionado] = useState<number | ''>('')
  const [dataTexto, setDataTexto] = useState(isoParaData(hojeISO()))
  const [valorContadoTexto, setValorContadoTexto] = useState('0,00')
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

  useEffect(() => {
    if (!fechamento) return
    setValorContadoTexto(formatarMoeda(fechamento.valorContadoDinheiro ?? 0))
  }, [fechamento])

  const linhaDinheiro = fechamento?.linhas.find((l) => l.nomeCarteira.trim().toUpperCase() === NOME_CARTEIRA_DINHEIRO) ?? null
  const valorContadoNumero = desmascararMoeda(valorContadoTexto)
  const diferenca = linhaDinheiro ? valorContadoNumero - linhaDinheiro.valorEsperado : null

  const fechar = useMutation({
    mutationFn: () => fecharCaixa({ idCaixa: fechamento!.idCaixa, valorContadoDinheiro: valorContadoNumero }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['caixa-fechamento'] })
      queryClient.invalidateQueries({ queryKey: ['caixa-status'] })
      setToast({ texto: 'Caixa fechado com sucesso.', tipo: 'sucesso' })
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

              <div className="form-grid" style={{ marginTop: 16 }}>
                <div className="col-4">
                  <label htmlFor="fechamento-caixa-contado">Valor Contado em Dinheiro *</label>
                  <input
                    id="fechamento-caixa-contado"
                    className="mono"
                    inputMode="decimal"
                    disabled={fechamento.fechado}
                    value={valorContadoTexto}
                    onChange={(e) => setValorContadoTexto(mascararMoeda(e.target.value))}
                    onBlur={(e) => setValorContadoTexto(formatarMoeda(desmascararMoeda(completarMoeda(e.target.value))))}
                    onFocus={(e) => e.target.select()}
                  />
                </div>
                <div className="col-4">
                  <label>Diferença (vs. {NOME_CARTEIRA_DINHEIRO})</label>
                  <div className="pdv-selecao-valor">
                    <span className="mono">{diferenca !== null ? moeda(diferenca) : '—'}</span>
                  </div>
                </div>
              </div>

              <div className="ajuda-rodape">
                <button type="button" className="btn ghost" onClick={() => setMostrarPreview(true)}>
                  Visualizar Impressão
                </button>
                {!fechamento.fechado && (
                  <button type="button" className="btn" disabled={fechar.isPending} onClick={() => fechar.mutate()}>
                    {fechar.isPending ? 'Fechando…' : 'Fechar Caixa'}
                  </button>
                )}
              </div>
            </section>
          )}
        </div>
      </div>

      {mostrarPreview && fechamento && (
        <FechamentoCaixaPreviewModal
          fechamento={fechamento}
          linhaDinheiro={linhaDinheiro}
          valorContadoDinheiro={fechamento.fechado ? fechamento.valorContadoDinheiro : valorContadoNumero}
          aoFechar={() => setMostrarPreview(false)}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
