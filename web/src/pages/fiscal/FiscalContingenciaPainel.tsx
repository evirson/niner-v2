import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeContingencia } from '../../components/Icones'
import { useAuth } from '../../lib/auth'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import { buscarContingencia, entrarEmContingencia, sairDaContingencia } from '../../lib/fiscalContingencia'
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

function formatarDuracao(minutos: number): string {
  if (minutos < 60) return `${minutos} min`
  const horas = Math.floor(minutos / 60)
  const resto = minutos % 60
  return resto === 0 ? `${horas} h` : `${horas} h ${resto} min`
}

/**
 * Painel de Contingência (§9.7, bloco B7) — estado atual, fila pendente de transmissão, e a
 * saída manual (entrada automática já acontece sozinha na emissão via DF19; este painel também
 * permite entrar manualmente, ex.: o lojista sabe que a internet vai cair pra manutenção antes
 * mesmo da primeira falha).
 *
 * <p>Não é uma tela de lista — segue o mesmo desvio do padrão que `ConformidadeFiscalPainel.tsx`:
 * um seletor de empresa e um card de estado, sem paginação nem popup de filtros.
 */
export default function FiscalContingenciaPainel() {
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [justificativa, setJustificativa] = useState('')
  const [processando, setProcessando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: estado, isLoading } = useQuery({
    queryKey: ['fiscal-contingencia', idEmpresa],
    queryFn: () => buscarContingencia(idEmpresa as number),
    enabled: idEmpresa !== null,
    // A situação muda sozinha (entrada automática por DF19, saída pelo job de drenagem) — sem
    // um refetch periódico o ADMIN só veria a mudança recarregando a página na mão.
    refetchInterval: 30_000,
  })

  async function confirmarEntrar() {
    if (idEmpresa === null || !justificativa.trim()) return
    setProcessando(true)
    setErro(null)
    try {
      await entrarEmContingencia(idEmpresa, justificativa.trim())
      setJustificativa('')
      queryClient.invalidateQueries({ queryKey: ['fiscal-contingencia', idEmpresa] })
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível entrar em contingência.')
    } finally {
      setProcessando(false)
    }
  }

  async function confirmarSair() {
    if (idEmpresa === null || !justificativa.trim()) return
    setProcessando(true)
    setErro(null)
    try {
      await sairDaContingencia(idEmpresa, justificativa.trim())
      setJustificativa('')
      queryClient.invalidateQueries({ queryKey: ['fiscal-contingencia', idEmpresa] })
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível sair da contingência.')
    } finally {
      setProcessando(false)
    }
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeContingencia size={34} />
            <h1>Contingência Fiscal</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.contingencia.tela" />
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div>
            <label htmlFor="seletor-empresa-contingencia">Empresa</label>
            <select
              id="seletor-empresa-contingencia"
              autoFocus
              value={idEmpresa ?? ''}
              onChange={(e) => setIdEmpresa(Number(e.target.value))}
            >
              {(empresas ?? []).map((emp) => (
                <option key={emp.idEmpresa} value={emp.idEmpresa}>
                  {emp.razaoSocial}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {isLoading || !estado ? (
          <p className="muted">Carregando…</p>
        ) : (
          <>
            <div className={estado.ativa ? 'tarja-perigo' : 'tarja-sucesso'}>
              {estado.ativa ? '⚠' : '✓'}{' '}
              {estado.ativa
                ? `Em contingência há ${formatarDuracao(estado.duracaoMinutos)} — série ${estado.serieContingencia}`
                : 'Fora de contingência — emissão direta pela SEFAZ'}
            </div>

            <div className="card" style={{ marginTop: 16 }}>
              {estado.ativa && (
                <>
                  <p>
                    <strong>Desde:</strong> {estado.desde ? formatarDataHora(estado.desde) : '—'}
                  </p>
                  <p>
                    <strong>Motivo:</strong> {estado.justificativa ?? '—'}
                  </p>
                </>
              )}
              <p>
                <strong>Notas aguardando transmissão:</strong> {estado.pendentes}
                {estado.pendentes > 0 && (
                  <span className="muted"> — o job de drenagem transmite sozinho quando a SEFAZ voltar.</span>
                )}
              </p>

              <div style={{ marginTop: 16, display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
                <div style={{ flex: 1, minWidth: 240 }}>
                  <label htmlFor="justificativa-contingencia">
                    Justificativa {estado.ativa ? 'da saída' : 'da entrada'}
                  </label>
                  <input
                    id="justificativa-contingencia"
                    type="text"
                    value={justificativa}
                    onChange={(e) => setJustificativa(e.target.value)}
                    placeholder={estado.ativa ? 'Ex.: internet voltou' : 'Ex.: manutenção programada no link'}
                  />
                </div>
                {estado.ativa ? (
                  <button type="button" className="btn" disabled={processando || !justificativa.trim()} onClick={confirmarSair}>
                    Sair da contingência
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn"
                    disabled={processando || !justificativa.trim()}
                    onClick={confirmarEntrar}
                  >
                    Entrar em contingência
                  </button>
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}
    </div>
  )
}
