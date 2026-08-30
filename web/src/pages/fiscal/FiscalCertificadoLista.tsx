import CabecalhoModal from '../../components/CabecalhoModal'
import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeCertificado, IconeHistorico } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import {
  enviarCertificado,
  listarCertificados,
  listarUsosCertificado,
  type FiscalCertificado,
  type SituacaoCertificado,
} from '../../lib/fiscalCertificado'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import { formatarSoData } from '../../lib/datas'
import { mascararCpfCnpj } from '../../lib/masks'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

const BADGE_POR_SITUACAO: Record<SituacaoCertificado, { rotulo: string; classe: string }> = {
  ATIVO: { rotulo: 'Ativo', classe: 'badge-sucesso' },
  VENCE_EM_BREVE: { rotulo: 'Vence em breve', classe: 'badge-aviso' },
  VENCIDO: { rotulo: 'Vencido', classe: 'badge-perigo' },
  SUBSTITUIDO: { rotulo: 'Substituído', classe: '' },
}

function rotuloSituacao(c: FiscalCertificado): string {
  const base = BADGE_POR_SITUACAO[c.situacao].rotulo
  if (c.situacao === 'VENCE_EM_BREVE' && c.diasParaVencer !== null) {
    return `Vence em ${c.diasParaVencer} dia${c.diasParaVencer === 1 ? '' : 's'}`
  }
  return base
}

const FINALIDADE_ROTULO: Record<string, string> = {
  ASSINATURA: 'Assinatura de XML',
  MTLS: 'Transporte (mTLS)',
  VALIDACAO: 'Validação',
}

/**
 * Certificado Digital A1 (docs/telas/fiscal-certificado.md, bloco B2). Lista + upload — não é o
 * padrão de cadastro completo: sem edição (certificado não se edita) e sem exclusão (histórico
 * imutável, F7). Write-only: o formulário de envio nunca mostra o arquivo nem a senha de volta.
 */
export default function FiscalCertificadoLista() {
  // ⛔ Tela governada (`fiscal.certificados`) e o item de menu não é adminOnly, mas ela ficou fora
  // do RBAC das rodadas 1 e 2 (auditoria 2026-08-29, rodada 3).
  const acoes = usePermissaoDaTela('fiscal.certificados')
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [uploadAberto, setUploadAberto] = useState(false)
  const [senha, setSenha] = useState('')
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [idCertificadoDrillDown, setIdCertificadoDrillDown] = useState<number | null>(null)
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')
  const inputRef = useRef<HTMLInputElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: certificados, isLoading } = useQuery({
    queryKey: ['fiscal-certificados', idEmpresa],
    queryFn: () => listarCertificados(idEmpresa as number),
    enabled: idEmpresa !== null,
  })

  const enviar = useMutation({
    mutationFn: () => enviarCertificado(idEmpresa as number, arquivo as File, senha),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['fiscal-certificados', idEmpresa] })
      setUploadAberto(false)
      setArquivo(null)
      setSenha('')
      setToastTipo('sucesso')
      setToast('Certificado enviado com sucesso.')
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : 'Não foi possível enviar o certificado.')
    },
  })

  const lista = certificados ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCertificado size={34} />
            <h1>Certificado Digital</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.certificado.tela" />
            {acoes.incluir && (
              <button type="button" className="btn" disabled={idEmpresa === null} onClick={() => setUploadAberto(true)}>
                ＋ Enviar certificado
              </button>
            )}
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div>
            <label htmlFor="seletor-empresa-certificado">Empresa</label>
            <select
              id="seletor-empresa-certificado"
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
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : lista.length === 0 ? (
            <p className="muted">Nenhum certificado enviado para esta empresa.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>CNPJ do titular</th>
                  <th>Razão social do titular</th>
                  <th>Válido de</th>
                  <th>Válido até</th>
                  <th>Situação</th>
                  <th>Enviado em</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {lista.map((c) => (
                  <tr key={c.idCertificado}>
                    <td>{c.cnpjTitular ? mascararCpfCnpj(c.cnpjTitular, false) : '—'}</td>
                    <td>{c.razaoSocialTitular ?? '—'}</td>
                    <td>{formatarSoData(c.validoDe)}</td>
                    <td>{formatarSoData(c.validoAte)}</td>
                    <td>
                      <span className={`badge ${BADGE_POR_SITUACAO[c.situacao].classe}`}>{rotuloSituacao(c)}</span>
                    </td>
                    <td>{formatarSoData(c.criadoEm)}</td>
                    <td className="acoes-cell">
                      <button
                        type="button"
                        className="acao-icone"
                        onClick={() => setIdCertificadoDrillDown(c.idCertificado)}
                        aria-label="Ver histórico de uso"
                        title="Histórico de uso"
                      >
                        <IconeHistorico />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {uploadAberto && (
        <div className="modal-overlay" onClick={() => setUploadAberto(false)}>
          <div className="modal" role="dialog" aria-label="Enviar certificado" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Enviar certificado</h2>
            <p className="muted">
              CNPJ, razão social e validade são extraídos do próprio arquivo — não digite nada
              além da senha.
            </p>

            <label htmlFor="certificado-arquivo">Arquivo (.pfx)</label>
            <input
              ref={inputRef}
              id="certificado-arquivo"
              type="file"
              accept=".pfx,.p12"
              style={{ display: 'none' }}
              onChange={(e) => setArquivo(e.target.files?.[0] ?? null)}
            />
            <button type="button" className="btn ghost" onClick={() => inputRef.current?.click()}>
              {arquivo ? arquivo.name : 'Escolher arquivo…'}
            </button>

            <label htmlFor="certificado-senha">Senha do certificado</label>
            <input
              id="certificado-senha"
              type="password"
              autoComplete="new-password"
              value={senha}
              onChange={(e) => setSenha(e.target.value)}
            />

            <div className="ajuda-rodape">
              <button
                type="button"
                className="btn ghost"
                onClick={() => {
                  setUploadAberto(false)
                  setArquivo(null)
                  setSenha('')
                }}
              >
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={!arquivo || !senha || enviar.isPending}
                onClick={() => enviar.mutate()}
              >
                {enviar.isPending ? 'Enviando…' : 'Enviar'}
              </button>
            </div>
          </div>
        </div>
      )}

      {idCertificadoDrillDown !== null && (
        <UsosDrillDownModal idCertificado={idCertificadoDrillDown} aoFechar={() => setIdCertificadoDrillDown(null)} />
      )}

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}

function UsosDrillDownModal({ idCertificado, aoFechar }: { idCertificado: number; aoFechar: () => void }) {
  const { data: usos, isLoading } = useQuery({
    queryKey: ['fiscal-certificado-usos', idCertificado],
    queryFn: () => listarUsosCertificado(idCertificado),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Histórico de uso do certificado" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Histórico de uso" aoFechar={aoFechar} />
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : !usos || usos.length === 0 ? (
          <p className="muted">Nenhum uso registrado ainda.</p>
        ) : (
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Finalidade</th>
                <th>Ocorrido em</th>
              </tr>
            </thead>
            <tbody>
              {usos.map((u) => (
                <tr key={u.idUso}>
                  <td>{FINALIDADE_ROTULO[u.finalidade] ?? u.finalidade}</td>
                  <td>{new Date(u.ocorridoEm).toLocaleString('pt-BR')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="ajuda-rodape">

        </div>
      </div>
    </div>
  )
}
