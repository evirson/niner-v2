import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeFiscal } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import {
  ANEXOS_SIMPLES,
  buscarNfseConfig,
  salvarNfseConfig,
  testarConexaoNfse,
  verificarNfse,
  type AmbienteFiscal,
  type NfseConfig,
  type VerificacaoNfse,
} from '../../lib/nfse'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarPercentual, desmascararPercentual, mascararPercentual } from '../../lib/masks'

const CHAVE_TELA = 'fiscal.nfse-configuracao.form'

interface FormState {
  emiteNfse: boolean
  ambiente: AmbienteFiscal
  serie: string
  rbt12: string
  simplesAnexo: string
  aliquotaSimplesEfetiva: string
}

const VAZIO: FormState = {
  emiteNfse: false,
  ambiente: 'HOMOLOGACAO',
  serie: '1',
  rbt12: '',
  simplesAnexo: 'III',
  aliquotaSimplesEfetiva: '',
}

function paraFormulario(c: NfseConfig): FormState {
  return {
    emiteNfse: c.emiteNfse,
    ambiente: c.ambiente,
    serie: String(c.serie),
    rbt12: c.rbt12 === null ? '' : mascararPercentual(String(c.rbt12)),
    simplesAnexo: c.simplesAnexo ?? 'III',
    aliquotaSimplesEfetiva:
      c.aliquotaSimplesEfetiva === null ? '' : mascararPercentual(String(c.aliquotaSimplesEfetiva)),
  }
}

/**
 * Configuração da NFS-e (docs/telas/nfse-configuracao.md). Singleton por EMPRESA, no molde da
 * Configuração Fiscal — a linha pode não existir e o primeiro PUT cria.
 *
 * ⭐ A razão de existir desta tela é o lojista ligar a NFS-e SEM abrir chamado. Por isso ela tem
 * o assistente: em vez de um formulário que aceita tudo e falha na primeira nota, ela roda as
 * verificações e diz o que falta com o link de onde resolver.
 */
export default function NfseConfiguracaoForm() {
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [form, setForm] = useState<FormState>(VAZIO)
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')
  const [verificacoes, setVerificacoes] = useState<VerificacaoNfse[] | null>(null)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: config, isFetching: carregando, isError: erroConfig } = useQuery({
    queryKey: ['nfse-config', idEmpresa],
    queryFn: () => buscarNfseConfig(idEmpresa as number),
    enabled: idEmpresa !== null,
  })

  /**
   * ⚠️ Trocar de empresa LIMPA o formulário na hora — a mesma armadilha que a auditoria pegou na
   * Configuração Fiscal. Sem isto, o ADMIN abre a Matriz, troca para a Filial e clica em Salvar:
   * grava na Filial a série e o ambiente da Matriz. Série duplicada entre empresas e produção
   * onde deveria ser homologação, sem nada indicar o erro até a primeira nota.
   */
  useEffect(() => {
    setForm(VAZIO)
    setVerificacoes(null)
  }, [idEmpresa])

  useEffect(() => {
    if (config) setForm(paraFormulario(config))
  }, [config])

  /**
   * ⛔ Série em branco NÃO vira 1 em silêncio. A loja está na série 2, o operador seleciona o
   * campo para digitar 3, é interrompido e salva → gravaria série 1 com toast verde. Série é o
   * que separa uma numeração fiscal de outra.
   */
  const serieVazia = !form.serie.trim()

  const salvar = useMutation({
    mutationFn: () =>
      salvarNfseConfig(idEmpresa as number, {
        emiteNfse: form.emiteNfse,
        ambiente: form.ambiente,
        serie: Number(form.serie),
        rbt12: form.rbt12.trim() ? desmascararPercentual(form.rbt12) : null,
        simplesAnexo: form.simplesAnexo,
        aliquotaSimplesEfetiva: form.aliquotaSimplesEfetiva.trim()
          ? desmascararPercentual(form.aliquotaSimplesEfetiva)
          : null,
      }),
    onSuccess: (resposta) => {
      queryClient.setQueryData(['nfse-config', idEmpresa], resposta)
      setToastTipo('sucesso')
      setToast('Configuração da NFS-e salva.')
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      // ⚠️ A mensagem do back diz O QUE falta para ligar a emissão (F11). Trocá-la por um
      // genérico apagaria o trabalho de escrevê-la — o defeito de 2026-08-24.
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a configuração.')
    },
  })

  const testar = useMutation({
    mutationFn: () => testarConexaoNfse(idEmpresa as number),
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['nfse-config', idEmpresa] })
      setToastTipo(r.status === 'OK' ? 'sucesso' : 'erro')
      setToast(r.mensagem)
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : 'Não foi possível testar a conexão.')
    },
  })

  const verificar = useMutation({
    mutationFn: () => verificarNfse(idEmpresa as number),
    onSuccess: (itens) => {
      setVerificacoes(itens)
      queryClient.invalidateQueries({ queryKey: ['nfse-config', idEmpresa] })
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : 'Não foi possível verificar a configuração.')
    },
  })

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    salvar.mutate()
  }

  const optanteDoSimples = config ? config.crt === 1 || config.crt === 2 || config.crt === 4 : true

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFiscal size={34} />
            <div>
              <h1>Configuração da NFS-e</h1>
              {config && (
                <p className="muted" style={{ margin: 0 }}>
                  {config.emiteNfse ? 'Emissão ligada' : 'Emissão desligada'}
                </p>
              )}
            </div>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <button
              type="button"
              className="btn ghost"
              disabled={idEmpresa === null || verificar.isPending}
              onClick={() => verificar.mutate()}
            >
              {verificar.isPending ? 'Verificando…' : 'Verificar configuração'}
            </button>
            <button
              type="button"
              className="btn ghost"
              disabled={idEmpresa === null || testar.isPending}
              onClick={() => testar.mutate()}
            >
              {testar.isPending ? 'Testando…' : 'Testar conexão'}
            </button>
            <button
              type="submit"
              form="form-nfse-config"
              className="btn"
              disabled={
                idEmpresa === null || carregando || erroConfig || salvar.isPending || serieVazia
              }
            >
              {salvar.isPending ? 'Salvando…' : 'Salvar'}
            </button>
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card" style={{ marginBottom: 16 }}>
          <label htmlFor="seletor-empresa-nfse">Empresa</label>
          <select
            id="seletor-empresa-nfse"
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
          {config && (
            <p className="muted" style={{ marginTop: 8 }}>
              CNPJ {config.cnpj ?? '—'} · Município IBGE {config.codigoMunicipioIbge ?? '—'} ·
              {' '}Inscrição Municipal {config.inscricaoMunicipal ?? '—'}
            </p>
          )}
        </div>

        {erroConfig && (
          <p className="erro">
            Não foi possível carregar a configuração da NFS-e desta empresa. Tente de novo em
            instantes.
          </p>
        )}

        {config && (
          <form
            id="form-nfse-config"
            className="card form-secoes form-secoes-larga"
            onSubmit={submeter}
            onKeyDown={aoTeclarEnterNoFormulario}
            noValidate
          >
            {form.ambiente === 'HOMOLOGACAO' && (
              <div className="tarja-aviso">
                ⚠ AMBIENTE DE HOMOLOGAÇÃO — NOTAS SEM VALOR FISCAL. Alguns municípios não têm a
                Inscrição Municipal cadastrada no CNC de homologação e recusam toda emissão ali
                (E0116); nesses casos a primeira nota válida sai direto em produção.
              </div>
            )}

            <section className="section">
              <p className="section-label">Emissão</p>
              <div className="form-grid">
                <div className="col-6">
                  <label className="checkbox-linha">
                    <input
                      type="checkbox"
                      checked={form.emiteNfse}
                      onChange={(e) => setForm((f) => ({ ...f, emiteNfse: e.target.checked }))}
                    />
                    Emitir NFS-e nesta empresa
                  </label>
                </div>
                <div className="col-6">
                  <label htmlFor="ambiente">Ambiente</label>
                  <select
                    id="ambiente"
                    value={form.ambiente}
                    onChange={(e) =>
                      setForm((f) => ({ ...f, ambiente: e.target.value as AmbienteFiscal }))
                    }
                  >
                    <option value="HOMOLOGACAO">Homologação (produção restrita)</option>
                    <option value="PRODUCAO">Produção</option>
                  </select>
                </div>
                <div className="col-4">
                  <label htmlFor="serie">Série da DPS *</label>
                  <input
                    id="serie"
                    inputMode="numeric"
                    value={form.serie}
                    onChange={(e) =>
                      setForm((f) => ({ ...f, serie: e.target.value.replace(/\D/g, '') }))
                    }
                  />
                  {serieVazia && <p className="erro">Informe a série (1 a 99999).</p>}
                </div>
              </div>

              {/* ⚠️ Aviso obrigatório e decisão de produto: não emitir é caminho legítimo e
                  permanente (DS10), MAS desde 01/11/2026 ME/EPP do Simples é obrigada ao Emissor
                  Nacional. A escolha tem de ser INFORMADA, não silenciosa. */}
              {!form.emiteNfse && (
                <div className="tarja-aviso">
                  Sem emissão, o serviço sai apenas na papeleta e no Recibo de Serviço — caminho
                  legítimo para MEI que atende pessoa física. ⚠ Para ME/EPP do Simples Nacional a
                  NFS-e é obrigatória pelo Emissor Nacional desde 01/11/2026.
                </div>
              )}
            </section>

            <section className="section">
              <p className="section-label">Simples Nacional</p>

              {/* ⛔ Não é opcional para optante: sem a alíquota efetiva o Sefin recusa com E0712,
                  e omitir o bloco totTrib recusa com E1235. Medido em produção. */}
              {/* ⚠️ `display: block` abaixo: `.tarja-aviso` é **flex**, então o `<strong>` no meio
                  da frase virava um ITEM da flexbox e o aviso saía repartido em colunas. Achado ao
                  varrer os usos da classe em 2026-09-02 — o mesmo tropeço já tinha acontecido em
                  `AvisoIntensidadeImpressora`. */}
              {optanteDoSimples && !form.aliquotaSimplesEfetiva.trim() && (
                <div className="tarja-aviso" style={{ display: 'block', fontWeight: 500, lineHeight: 1.5 }}>
                  A alíquota efetiva é <strong>obrigatória</strong> para optante do Simples: sem
                  ela o Sefin Nacional recusa a nota (E0712). O valor está no extrato do PGDAS-D do
                  mês anterior — confirme com seu contador.
                </div>
              )}

              <div className="form-grid">
                <div className="col-4">
                  <label htmlFor="anexo">Anexo</label>
                  <select
                    id="anexo"
                    value={form.simplesAnexo}
                    onChange={(e) => setForm((f) => ({ ...f, simplesAnexo: e.target.value }))}
                  >
                    {ANEXOS_SIMPLES.map((a) => (
                      <option key={a} value={a}>
                        Anexo {a}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="col-4">
                  <label htmlFor="aliquota">Alíquota efetiva do Simples (%)</label>
                  <input
                    id="aliquota"
                    value={form.aliquotaSimplesEfetiva}
                    onChange={(e) =>
                      setForm((f) => ({
                        ...f,
                        aliquotaSimplesEfetiva: mascararPercentual(e.target.value),
                      }))
                    }
                    onBlur={(e) =>
                      setForm((f) => ({
                        ...f,
                        aliquotaSimplesEfetiva: completarPercentual(e.target.value),
                      }))
                    }
                    onFocus={(e) => e.target.select()}
                  />
                </div>
                <div className="col-4">
                  <label htmlFor="rbt12">Receita bruta de 12 meses (opcional)</label>
                  <input
                    id="rbt12"
                    value={form.rbt12}
                    onChange={(e) =>
                      setForm((f) => ({ ...f, rbt12: mascararPercentual(e.target.value) }))
                    }
                    onBlur={(e) =>
                      setForm((f) => ({ ...f, rbt12: completarPercentual(e.target.value) }))
                    }
                    onFocus={(e) => e.target.select()}
                  />
                  <p className="muted">
                    Só para conferência com o contador — quem vai na nota é a alíquota efetiva.
                  </p>
                </div>
              </div>
            </section>

            {config.ultimoTesteEm && (
              <section className="section">
                <p className="section-label">Último teste de conexão</p>
                <p className={config.ultimoTesteStatus === 'OK' ? 'muted' : 'erro'}>
                  <strong>{config.ultimoTesteStatus}</strong> — {config.ultimoTesteMensagem}
                </p>
              </section>
            )}

            {verificacoes && (
              <section className="section">
                <p className="section-label">Verificação da configuração</p>
                {/* ⭐ Cada pendência traz ONDE resolver. A correção de 2026-08 registrou por quê:
                    "'sem IBGE' / 'sem CNAE' no painel de Conformidade não levavam a lugar nenhum". */}
                <ul>
                  {verificacoes.map((v) => (
                    <li key={v.item} className={v.ok ? 'muted' : 'erro'}>
                      {v.ok ? '✅' : '⚠'} <strong>{v.item}</strong> — {v.detalhe}
                      {!v.ok && v.telaParaResolver && (
                        <>
                          {' '}
                          <a href={v.telaParaResolver}>resolver</a>
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </form>
        )}
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
