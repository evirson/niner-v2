import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeFiscal } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import InfoRegistro from '../../components/InfoRegistro'
import { ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import {
  CRT_OPCOES,
  buscarFiscalConfig,
  listarEmpresasFiscal,
  salvarFiscalConfig,
  type AmbienteFiscal,
  type FiscalConfig,
  type FiscalConfigRequest,
} from '../../lib/fiscalConfiguracao'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'

const CHAVE_TELA = 'fiscal.configuracao.form'

interface FormState {
  crt: number
  emiteNfce: boolean
  emiteNfe: boolean
  ambiente: AmbienteFiscal
  serieNfce: string
  serieNfe: string
  serieContingencia: string
  inscricaoEstadualSt: string
  suframa: string
  cscId: string
  cscToken: string
  removerCsc: boolean
}

const VAZIO: FormState = {
  crt: 1,
  emiteNfce: false,
  emiteNfe: false,
  ambiente: 'HOMOLOGACAO',
  serieNfce: '1',
  serieNfe: '1',
  serieContingencia: '9',
  inscricaoEstadualSt: '',
  suframa: '',
  cscId: '',
  cscToken: '',
  removerCsc: false,
}

function paraFormulario(c: FiscalConfig): FormState {
  return {
    crt: c.crt,
    emiteNfce: c.emiteNfce,
    emiteNfe: c.emiteNfe,
    ambiente: c.ambiente,
    serieNfce: String(c.serieNfce),
    serieNfe: String(c.serieNfe),
    serieContingencia: String(c.serieContingencia),
    inscricaoEstadualSt: c.inscricaoEstadualSt ?? '',
    suframa: c.suframa ?? '',
    cscId: c.cscId ?? '',
    cscToken: '',
    removerCsc: false,
  }
}

function paraRequisicao(f: FormState): FiscalConfigRequest {
  return {
    crt: f.crt,
    emiteNfce: f.emiteNfce,
    emiteNfe: f.emiteNfe,
    ambiente: f.ambiente,
    serieNfce: Number(f.serieNfce) || 1,
    serieNfe: Number(f.serieNfe) || 1,
    serieContingencia: Number(f.serieContingencia) || 9,
    inscricaoEstadualSt: f.inscricaoEstadualSt.trim() || null,
    suframa: f.suframa.trim() || null,
    cscId: f.cscId.trim() || null,
    // Sem token digitado e sem pedido de remoção: omite o campo — o backend preserva o que
    // já está gravado. Mandar string vazia apagaria o CSC em silêncio (F7).
    ...(f.removerCsc ? { removerCsc: true } : f.cscToken ? { cscToken: f.cscToken } : {}),
  }
}

/**
 * Configuração Fiscal da Empresa (docs/telas/fiscal-configuracao.md, bloco B2). Singleton por
 * EMPRESA, não por tenant — diferente de `configuracao.geral`. A linha pode não existir
 * (`configurado: false`); o primeiro `PUT` cria (upsert). Seletor de empresa no topo, como
 * `fiscal.certificado` e (futura) `fiscal.conformidade`.
 */
export default function FiscalConfiguracaoForm() {
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [form, setForm] = useState<FormState>(VAZIO)
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: config, isFetching: carregandoConfig } = useQuery({
    queryKey: ['fiscal-config', idEmpresa],
    queryFn: () => buscarFiscalConfig(idEmpresa as number),
    enabled: idEmpresa !== null,
  })

  /**
   * ⚠️ Trocar de empresa LIMPA o formulário na hora (achado de auditoria, 2026-08-21).
   *
   * <p>O efeito abaixo só preenche `if (config)`. Ao trocar a empresa, a `queryKey` vira uma chave
   * nunca vista e `config` volta a ser `undefined` enquanto a busca corre — então o formulário
   * continuava exibindo **os dados da empresa anterior**, com a nova já selecionada no combo e o
   * botão Salvar habilitado.
   *
   * <p>O estrago é grave e silencioso: o ADMIN abre a Matriz, troca para a Filial (que ele veio
   * configurar justamente por estar "não configurada"), clica em Salvar — e grava na Filial o CRT,
   * o **ambiente** e as **três séries fiscais** da Matriz. Série duplicada entre empresas e
   * produção onde deveria ser homologação, sem nada indicar o erro até a primeira nota.
   *
   * <p>Pior sem limite de tempo: se a busca falhar, `config` fica `undefined` para sempre e o
   * formulário segue mostrando a empresa anterior indefinidamente.
   */
  useEffect(() => {
    setForm(VAZIO)
  }, [idEmpresa])

  useEffect(() => {
    if (config) setForm(paraFormulario(config))
  }, [config])

  const salvar = useMutation({
    mutationFn: () => salvarFiscalConfig(idEmpresa as number, paraRequisicao(form)),
    onSuccess: (resposta) => {
      queryClient.setQueryData(['fiscal-config', idEmpresa], resposta)
      queryClient.invalidateQueries({ queryKey: ['fiscal-empresas'] })
      setToastTipo('sucesso')
      setToast('Configuração fiscal salva.')
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a configuração fiscal.')
    },
  })

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    salvar.mutate()
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFiscal size={34} />
            <div>
              <h1>Configuração Fiscal</h1>
              {config?.configurado && (
                <p className="muted" style={{ margin: 0 }}>
                  {config.emiteNfce || config.emiteNfe ? 'Emissão ligada' : 'Emissão desligada'}
                </p>
              )}
            </div>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            {/* `carregandoConfig` no disabled: salvar enquanto a configuração da empresa escolhida
                ainda está vindo gravaria o formulário em branco (ou o da empresa anterior, antes da
                limpeza acima). */}
            <button
              type="submit"
              form="form-fiscal-config"
              className="btn"
              disabled={salvar.isPending || idEmpresa === null || carregandoConfig}
            >
              {salvar.isPending ? 'Salvando…' : 'Salvar'}
            </button>
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card" style={{ marginBottom: 16 }}>
          <label htmlFor="seletor-empresa-fiscal">Empresa</label>
          <select
            id="seletor-empresa-fiscal"
            autoFocus
            value={idEmpresa ?? ''}
            onChange={(e) => setIdEmpresa(Number(e.target.value))}
          >
            {(empresas ?? []).map((emp) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.razaoSocial} {emp.configurado ? '' : '— não configurada'}
              </option>
            ))}
          </select>
        </div>

        {config && (
          <form
            id="form-fiscal-config"
            className="card form-secoes form-secoes-larga"
            onSubmit={submeter}
            onKeyDown={aoTeclarEnterNoFormulario}
            noValidate
          >
            {form.ambiente === 'HOMOLOGACAO' && (
              <div className="tarja-aviso">⚠ AMBIENTE DE HOMOLOGAÇÃO — NOTAS SEM VALOR FISCAL</div>
            )}

            <section className="section">
              <p className="section-label">Regime</p>
              <div className="form-grid">
                <div className="col-6">
                  <label htmlFor="crt">Regime Tributário (CRT) *</label>
                  <select id="crt" value={form.crt} onChange={(e) => setForm((f) => ({ ...f, crt: Number(e.target.value) }))}>
                    {CRT_OPCOES.map((o) => (
                      <option key={o.valor} value={o.valor}>
                        {o.rotulo}
                      </option>
                    ))}
                  </select>
                  <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                    O Nainer atende Simples Nacional e MEI. Lucro Real e Lucro Presumido não são
                    atendidos por este produto.
                  </p>
                </div>
                <div className="col-6">
                  <label htmlFor="inscricaoEstadualSt">IE de Substituto Tributário (outra UF)</label>
                  <input
                    id="inscricaoEstadualSt"
                    value={form.inscricaoEstadualSt}
                    onChange={(e) => setForm((f) => ({ ...f, inscricaoEstadualSt: e.target.value.toUpperCase() }))}
                  />
                </div>
                <div className="col-6">
                  <label htmlFor="suframa">Inscrição SUFRAMA</label>
                  <input
                    id="suframa"
                    value={form.suframa}
                    onChange={(e) => setForm((f) => ({ ...f, suframa: e.target.value.toUpperCase() }))}
                  />
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Emissão</p>
              <div className="form-grid">
                <div className="col-6">
                  <label className="checkbox-linha" style={{ marginTop: 0 }}>
                    <input
                      type="checkbox"
                      checked={form.emiteNfce}
                      onChange={(e) => setForm((f) => ({ ...f, emiteNfce: e.target.checked }))}
                    />
                    Emitir NFC-e (modelo 65) no PDV
                  </label>
                </div>
                <div className="col-6">
                  <label className="checkbox-linha" style={{ marginTop: 0 }}>
                    <input
                      type="checkbox"
                      checked={form.emiteNfe}
                      onChange={(e) => setForm((f) => ({ ...f, emiteNfe: e.target.checked }))}
                    />
                    Emitir NF-e (modelo 55) de devolução
                  </label>
                </div>
                {/* ⭐ Instalação em produção não oferece escolha: o campo some, não fica
                    desabilitado. Campo cinza convida a perguntar "como eu habilito isso?" — e a
                    resposta é que não se habilita. Decisão do dono do produto, 2026-08-27. */}
                {!config.ambienteTravado && (
                  <div className="col-6">
                    <label htmlFor="ambiente">Ambiente *</label>
                    <select
                      id="ambiente"
                      value={form.ambiente}
                      onChange={(e) => setForm((f) => ({ ...f, ambiente: e.target.value as AmbienteFiscal }))}
                    >
                      <option value="HOMOLOGACAO">Homologação</option>
                      <option value="PRODUCAO">Produção</option>
                    </select>
                  </div>
                )}
              </div>
            </section>

            <section className="section">
              <p className="section-label">Numeração</p>
              <div className="form-grid">
                <div className="col-4">
                  <label htmlFor="serieNfce">Série da NFC-e *</label>
                  <input
                    id="serieNfce"
                    inputMode="numeric"
                    disabled={config.serieNfceBloqueada}
                    value={form.serieNfce}
                    onChange={(e) => setForm((f) => ({ ...f, serieNfce: e.target.value.replace(/\D/g, '') }))}
                  />
                  {config.serieNfceBloqueada && (
                    <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                      Já existe nota autorizada nesta série — não pode ser trocada.
                    </p>
                  )}
                </div>
                <div className="col-4">
                  <label htmlFor="serieNfe">Série da NF-e *</label>
                  <input
                    id="serieNfe"
                    inputMode="numeric"
                    disabled={config.serieNfeBloqueada}
                    value={form.serieNfe}
                    onChange={(e) => setForm((f) => ({ ...f, serieNfe: e.target.value.replace(/\D/g, '') }))}
                  />
                  {config.serieNfeBloqueada && (
                    <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                      Já existe nota autorizada nesta série — não pode ser trocada.
                    </p>
                  )}
                </div>
                <div className="col-4">
                  <label htmlFor="serieContingencia">Série de contingência *</label>
                  <input
                    id="serieContingencia"
                    inputMode="numeric"
                    value={form.serieContingencia}
                    onChange={(e) => setForm((f) => ({ ...f, serieContingencia: e.target.value.replace(/\D/g, '') }))}
                  />
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Credenciamento</p>
              <div className="form-grid">
                <div className="col-6">
                  <label htmlFor="cscId">Identificador do CSC (CSC ID)</label>
                  <input id="cscId" value={form.cscId} onChange={(e) => setForm((f) => ({ ...f, cscId: e.target.value }))} />
                </div>
                <div className="col-6">
                  <label htmlFor="cscToken">Token do CSC</label>
                  <input
                    id="cscToken"
                    type="password"
                    autoComplete="new-password"
                    placeholder={config.cscConfigurado ? '•••••••• (gravado — deixe em branco para manter)' : ''}
                    disabled={form.removerCsc}
                    value={form.cscToken}
                    onChange={(e) => setForm((f) => ({ ...f, cscToken: e.target.value }))}
                  />
                  {config.cscConfigurado && (
                    <label className="checkbox-linha" style={{ fontSize: 12 }}>
                      <input
                        type="checkbox"
                        checked={form.removerCsc}
                        onChange={(e) => setForm((f) => ({ ...f, removerCsc: e.target.checked, cscToken: '' }))}
                      />
                      Remover o CSC atual
                    </label>
                  )}
                </div>
                <div className="col-6">
                  <label htmlFor="versaoTabelaIbpt">Versão da tabela IBPT</label>
                  <input id="versaoTabelaIbpt" value={config.versaoTabelaIbpt ?? '—'} disabled />
                </div>
              </div>
            </section>

            {config.configurado && (
              <InfoRegistro
                codigo={config.idEmpresa}
                criadoEm={config.criadoEm ?? undefined}
                atualizadoEm={config.atualizadoEm ?? undefined}
              />
            )}
          </form>
        )}
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
