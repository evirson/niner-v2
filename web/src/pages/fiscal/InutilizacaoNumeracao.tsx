import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeInutilizacao } from '../../components/Icones'
import { useAuth } from '../../lib/auth'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import {
  detectarBuracos,
  inutilizarFaixa,
  listarInutilizacoes,
  type FaixaBuraco,
} from '../../lib/fiscalInutilizacao'
import { ApiError } from '../../lib/api'
import Toast from '../../components/Toast'

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function rotuloFaixa(f: FaixaBuraco): string {
  return f.numeroInicial === f.numeroFinal ? `${f.numeroInicial}` : `${f.numeroInicial}–${f.numeroFinal}`
}

/**
 * Inutilização de Numeração (§10.4, bloco B8) — a tela detecta os buracos sozinha (número
 * alocado que nunca virou nota nem já foi inutilizado) em vez de exigir conferência manual de
 * sequência. Mesmo desvio de padrão do `FiscalContingenciaPainel.tsx`: seletor de empresa + card
 * de ação, sem paginação nem popup de filtros — o histórico abaixo é só leitura.
 */
export default function InutilizacaoNumeracao() {
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [modelo, setModelo] = useState(65)
  const [serie, setSerie] = useState(1)
  const [faixaEscolhida, setFaixaEscolhida] = useState<FaixaBuraco | null>(null)
  const [justificativa, setJustificativa] = useState('')
  const [processando, setProcessando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [sucesso, setSucesso] = useState<string | null>(null)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: buracos, isLoading: carregandoBuracos } = useQuery({
    queryKey: ['fiscal-inutilizacao-buracos', idEmpresa, modelo, serie],
    queryFn: () => detectarBuracos(idEmpresa as number, modelo, serie),
    enabled: idEmpresa !== null,
  })

  const { data: historico } = useQuery({
    queryKey: ['fiscal-inutilizacoes', idEmpresa],
    queryFn: () => listarInutilizacoes(idEmpresa as number),
    enabled: idEmpresa !== null,
  })

  function escolherFaixa(f: FaixaBuraco) {
    setFaixaEscolhida(f)
    setJustificativa('')
    setErro(null)
  }

  async function confirmarInutilizacao() {
    if (idEmpresa === null || !faixaEscolhida || justificativa.trim().length < 15) return
    setProcessando(true)
    setErro(null)
    try {
      const resultado = await inutilizarFaixa(
        idEmpresa,
        modelo,
        serie,
        faixaEscolhida.numeroInicial,
        faixaEscolhida.numeroFinal,
        justificativa.trim(),
      )
      setSucesso(`Inutilização homologada pela SEFAZ — protocolo ${resultado.protocolo}.`)
      setFaixaEscolhida(null)
      setJustificativa('')
      queryClient.invalidateQueries({ queryKey: ['fiscal-inutilizacao-buracos', idEmpresa, modelo, serie] })
      queryClient.invalidateQueries({ queryKey: ['fiscal-inutilizacoes', idEmpresa] })
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível inutilizar esta faixa.')
    } finally {
      setProcessando(false)
    }
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeInutilizacao size={34} />
            <h1>Inutilização de Numeração</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.inutilizacao.tela" />
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div>
            <label htmlFor="seletor-empresa-inutilizacao">Empresa</label>
            <select
              id="seletor-empresa-inutilizacao"
              autoFocus
              value={idEmpresa ?? ''}
              onChange={(e) => {
                setIdEmpresa(Number(e.target.value))
                setFaixaEscolhida(null)
              }}
            >
              {(empresas ?? []).map((emp) => (
                <option key={emp.idEmpresa} value={emp.idEmpresa}>
                  {emp.razaoSocial}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="seletor-modelo-inutilizacao">Modelo</label>
            <select
              id="seletor-modelo-inutilizacao"
              value={modelo}
              onChange={(e) => {
                setModelo(Number(e.target.value))
                setFaixaEscolhida(null)
              }}
            >
              <option value={65}>65 — NFC-e</option>
              <option value={55}>55 — NF-e</option>
            </select>
          </div>
          <div>
            <label htmlFor="serie-inutilizacao">Série</label>
            <input
              id="serie-inutilizacao"
              type="number"
              min={1}
              value={serie}
              onChange={(e) => {
                setSerie(Number(e.target.value) || 1)
                setFaixaEscolhida(null)
              }}
              style={{ width: 90 }}
            />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card">
          <h2 style={{ marginTop: 0 }}>Buracos detectados</h2>
          {carregandoBuracos ? (
            <p className="muted">Carregando…</p>
          ) : !buracos || buracos.length === 0 ? (
            <p className="muted">Nenhum número queimado sem nota nesta série — nada a inutilizar.</p>
          ) : (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {buracos.map((f) => (
                <button
                  key={`${f.numeroInicial}-${f.numeroFinal}`}
                  type="button"
                  className={faixaEscolhida === f ? 'btn' : 'btn ghost'}
                  onClick={() => escolherFaixa(f)}
                >
                  {rotuloFaixa(f)}
                </button>
              ))}
            </div>
          )}

          {faixaEscolhida && (
            <div style={{ marginTop: 16, display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <div style={{ flex: 1, minWidth: 280 }}>
                <label htmlFor="justificativa-inutilizacao">
                  Justificativa da inutilização — faixa {rotuloFaixa(faixaEscolhida)}
                </label>
                <input
                  id="justificativa-inutilizacao"
                  type="text"
                  value={justificativa}
                  onChange={(e) => setJustificativa(e.target.value)}
                  placeholder="Ex.: número perdido por falha de comunicação no caixa"
                  minLength={15}
                  maxLength={255}
                />
              </div>
              <button
                type="button"
                className="btn"
                disabled={processando || justificativa.trim().length < 15}
                onClick={confirmarInutilizacao}
              >
                Inutilizar na SEFAZ
              </button>
            </div>
          )}
        </div>

        <div className="card" style={{ marginTop: 16 }}>
          <h2 style={{ marginTop: 0 }}>Histórico de tentativas</h2>
          {!historico || historico.length === 0 ? (
            <p className="muted">Nenhuma inutilização registrada para esta empresa ainda.</p>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Data</th>
                    <th>Modelo/Série</th>
                    <th>Faixa</th>
                    <th>Situação</th>
                    <th>Protocolo / motivo</th>
                    <th>Justificativa</th>
                  </tr>
                </thead>
                <tbody>
                  {historico.map((item, i) => (
                    <tr key={i}>
                      <td>{formatarDataHora(item.criadoEm)}</td>
                      <td>
                        {item.modelo}/{item.serie}
                      </td>
                      <td>{rotuloFaixa(item)}</td>
                      <td>
                        <span className={item.autorizado ? 'badge badge-sucesso' : 'badge badge-perigo'}>
                          {item.autorizado ? 'Homologada' : 'Recusada'}
                        </span>
                      </td>
                      <td>{item.autorizado ? item.protocolo : item.motivoSefaz}</td>
                      <td>{item.justificativa}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}
      {sucesso && <Toast mensagem={sucesso} tipo="sucesso" aoFechar={() => setSucesso(null)} />}
    </div>
  )
}
