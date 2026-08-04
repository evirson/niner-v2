import {
  useEffect,
  useState,
  type ChangeEvent,
  type FocusEvent,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconePlanoContas } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { codigoPlanoContasValido, mascararCodigoPlanoContas } from '../../lib/masks'
import {
  GRUPOS_DFC,
  GRUPOS_DRE,
  NATUREZAS,
  PLANO_CONTAS_VAZIO,
  ROTULO_GRUPO_DFC,
  ROTULO_GRUPO_DRE,
  ROTULO_NATUREZA,
  ROTULO_TIPO_MOVIMENTO,
  TIPOS_MOVIMENTO,
  atualizarPlanoContas,
  buscarPlanoContas,
  criarPlanoContas,
  paraFormulario,
  paraRequisicao,
  type GrupoDfcConta,
  type GrupoDreConta,
  type NaturezaConta,
  type PlanoContasFormState,
  type TipoMovimentoConta,
} from '../../lib/planoContas'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'codigo' | 'descricao' | 'tipoMovimento' | 'natureza' | 'grupoDre' | 'grupoDfc'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function validarCampo(chave: CampoValidavel, f: PlanoContasFormState): string | undefined {
  if (chave === 'codigo') {
    if (!f.codigo.trim()) return 'Código é obrigatório.'
    return codigoPlanoContasValido(mascararCodigoPlanoContas(f.codigo))
      ? undefined
      : 'Código incompleto — formato 9.99.999.999 (conta.subconta.item.subitem).'
  }
  if (chave === 'descricao') return f.descricao.trim() ? undefined : 'Descrição é obrigatória.'
  if (chave === 'tipoMovimento') return f.tipoMovimento ? undefined : 'Escolha o tipo de movimento.'
  if (chave === 'natureza') return f.natureza ? undefined : 'Escolha a natureza.'
  if (chave === 'grupoDre') return !f.incluiDre || f.grupoDre ? undefined : 'Escolha o grupo da DRE.'
  return !f.incluiFluxoCaixa || f.grupoDfc ? undefined : 'Escolha o grupo do fluxo de caixa.'
}

/**
 * Formulário de plano de contas — revisado 2026-07-31 (docs/telas/plano-contas.md): DRE e
 * fluxo de caixa passaram de flag solta para classificação de verdade (grupo próprio, exigido
 * só quando a flag correspondente está marcada); `sinal`/`aceitaLancamento` são sempre
 * derivados no servidor a partir de tipo de movimento/natureza — não aparecem como campo. O
 * código contábil continua sendo a própria PK de negócio — digitado ao criar (agora com
 * máscara fixa 9.99.999.999) e **imutável depois** (a hierarquia inteira é derivada dele).
 */
export default function PlanoContasForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { codigo } = useParams()
  const editando = Boolean(codigo)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<PlanoContasFormState>(PLANO_CONTAS_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: planoExistente } = useQuery({
    queryKey: ['plano-contas', codigo],
    queryFn: () => buscarPlanoContas(String(codigo)),
    enabled: editando,
  })

  useEffect(() => {
    if (planoExistente) setForm(paraFormulario(planoExistente))
  }, [planoExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando
        ? atualizarPlanoContas(String(codigo), paraRequisicao(form))
        : criarPlanoContas(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['planos-contas'] })
      navigate('/planos-contas', {
        state: {
          toast: {
            texto: editando ? 'Plano de contas atualizado com sucesso.' : 'Plano de contas cadastrado com sucesso.',
            tipo: 'sucesso',
          },
        },
      })
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o plano de contas.'),
  })

  const aoMudarCodigo = (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, codigo: mascararCodigoPlanoContas(e.target.value) }))

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado. */
  const campo = (chave: 'descricao' | 'descricaoCurta' | 'idContaContabil' | 'idPlanoReferencial' | 'observacao') =>
    (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
      setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  /** Valida um campo ao sair dele. */
  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      codigo: validarCampo('codigo', form),
      descricao: validarCampo('descricao', form),
      tipoMovimento: validarCampo('tipoMovimento', form),
      natureza: validarCampo('natureza', form),
      grupoDre: validarCampo('grupoDre', form),
      grupoDfc: validarCampo('grupoDfc', form),
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToast('Corrija os campos destacados antes de salvar.')
      return
    }
    salvar.mutate()
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    validarEEnviar()
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePlanoContas size={34} />
            <h1>Plano de Contas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="cadastros.planocontas.form" />
            <BotaoFecharTela />
            {!somenteLeitura && (
              <button type="submit" form="form-plano-contas" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-plano-contas"
        className="card form-secoes form-secoes-larga"
        onSubmit={submeter}
        onKeyDown={(e: KeyboardEvent<HTMLFormElement>) => {
          if (!somenteLeitura) aoTeclarEnterNoFormulario(e, () => setConfirmarSalvarAberto(true))
        }}
        noValidate
      >
      <fieldset disabled={somenteLeitura} className="form-fieldset">
        <section className="section">
          <p className="section-label">Identificação</p>

          <div className="form-grid">
            <div className="col-3">
              <label htmlFor="codigo">Código *</label>
              <input
                id="codigo"
                className={`mono${editando ? ' campo-leitura' : ''}`}
                autoFocus={!editando}
                placeholder="9.99.999.999"
                readOnly={editando}
                tabIndex={editando ? -1 : undefined}
                value={form.codigo}
                onChange={aoMudarCodigo}
                onBlur={aoSairDoCampo('codigo')}
              />
              {erros.codigo && <p className="erro-campo">{erros.codigo}</p>}
            </div>
            <div className="col-5">
              <label htmlFor="descricao">Descrição *</label>
              <input
                id="descricao"
                autoFocus={editando && !somenteLeitura}
                value={form.descricao}
                onChange={campo('descricao')}
                onBlur={aoSairDoCampo('descricao')}
              />
              {erros.descricao && <p className="erro-campo">{erros.descricao}</p>}
            </div>
            <div className="col-4">
              <label htmlFor="descricaoCurta">Descrição curta</label>
              <input
                id="descricaoCurta"
                placeholder="p/ cupom, PDV, relatório estreito"
                value={form.descricaoCurta}
                onChange={campo('descricaoCurta')}
              />
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Classificação</p>

          <div className="form-grid">
            <div className="col-6">
              <label htmlFor="tipoMovimento">Tipo de movimento *</label>
              <select
                id="tipoMovimento"
                value={form.tipoMovimento}
                onChange={(e) =>
                  setForm((f) => ({ ...f, tipoMovimento: e.target.value as TipoMovimentoConta }))
                }
                onBlur={aoSairDoCampo('tipoMovimento')}
              >
                <option value="">Selecione…</option>
                {TIPOS_MOVIMENTO.map((t) => (
                  <option key={t} value={t}>
                    {ROTULO_TIPO_MOVIMENTO[t]}
                  </option>
                ))}
              </select>
              {erros.tipoMovimento && <p className="erro-campo">{erros.tipoMovimento}</p>}
            </div>
            <div className="col-6">
              <label htmlFor="natureza">Natureza *</label>
              <select
                id="natureza"
                value={form.natureza}
                onChange={(e) => setForm((f) => ({ ...f, natureza: e.target.value as NaturezaConta }))}
                onBlur={aoSairDoCampo('natureza')}
              >
                <option value="">Selecione…</option>
                {NATUREZAS.map((n) => (
                  <option key={n} value={n}>
                    {ROTULO_NATUREZA[n]}
                  </option>
                ))}
              </select>
              {erros.natureza && <p className="erro-campo">{erros.natureza}</p>}
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">DRE e fluxo de caixa</p>
          <p className="muted" style={{ marginTop: -4 }}>
            Independentes um do outro — é isso que evita contar a mesma coisa duas vezes (ex.:
            compra de mercadoria entra no fluxo de caixa, nunca na DRE; o CMV é o oposto).
          </p>

          <div className="form-grid">
            <div className="col-3">
              <label className="checkbox-linha">
                <input
                  type="checkbox"
                  checked={form.incluiDre}
                  onChange={(e) =>
                    setForm((f) => ({ ...f, incluiDre: e.target.checked, grupoDre: e.target.checked ? f.grupoDre : '' }))
                  }
                />
                Compõe a DRE
              </label>
            </div>
            <div className="col-3">
              {form.incluiDre && (
                <>
                  <label htmlFor="grupoDre">Grupo da DRE *</label>
                  <select
                    id="grupoDre"
                    value={form.grupoDre}
                    onChange={(e) => setForm((f) => ({ ...f, grupoDre: e.target.value as GrupoDreConta }))}
                    onBlur={aoSairDoCampo('grupoDre')}
                  >
                    <option value="">Selecione…</option>
                    {GRUPOS_DRE.map((g) => (
                      <option key={g} value={g}>
                        {ROTULO_GRUPO_DRE[g]}
                      </option>
                    ))}
                  </select>
                  {erros.grupoDre && <p className="erro-campo">{erros.grupoDre}</p>}
                </>
              )}
            </div>
            <div className="col-3">
              <label className="checkbox-linha">
                <input
                  type="checkbox"
                  checked={form.incluiFluxoCaixa}
                  onChange={(e) =>
                    setForm((f) => ({
                      ...f,
                      incluiFluxoCaixa: e.target.checked,
                      grupoDfc: e.target.checked ? f.grupoDfc : '',
                    }))
                  }
                />
                Compõe o fluxo de caixa
              </label>
            </div>
            <div className="col-3">
              {form.incluiFluxoCaixa && (
                <>
                  <label htmlFor="grupoDfc">Grupo do fluxo de caixa *</label>
                  <select
                    id="grupoDfc"
                    value={form.grupoDfc}
                    onChange={(e) => setForm((f) => ({ ...f, grupoDfc: e.target.value as GrupoDfcConta }))}
                    onBlur={aoSairDoCampo('grupoDfc')}
                  >
                    <option value="">Selecione…</option>
                    {GRUPOS_DFC.map((g) => (
                      <option key={g} value={g}>
                        {ROTULO_GRUPO_DFC[g]}
                      </option>
                    ))}
                  </select>
                  {erros.grupoDfc && <p className="erro-campo">{erros.grupoDfc}</p>}
                </>
              )}
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Exigências no lançamento</p>

          <div className="form-grid">
            <div className="col-4">
              <label className="checkbox-linha">
                <input
                  type="checkbox"
                  checked={form.exigeCentroCusto}
                  onChange={(e) => setForm((f) => ({ ...f, exigeCentroCusto: e.target.checked }))}
                />
                Exige centro de custo
              </label>
            </div>
            <div className="col-4">
              <label className="checkbox-linha">
                <input
                  type="checkbox"
                  checked={form.exigeContraparte}
                  onChange={(e) => setForm((f) => ({ ...f, exigeContraparte: e.target.checked }))}
                />
                Exige contraparte (conta de destino)
              </label>
            </div>
            <div className="col-4">
              <label className="checkbox-linha">
                <input
                  type="checkbox"
                  checked={form.exigeDocumento}
                  onChange={(e) => setForm((f) => ({ ...f, exigeDocumento: e.target.checked }))}
                />
                Exige documento (NF/contrato)
              </label>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Integrações (opcional)</p>

          <div className="form-grid">
            <div className="col-6">
              <label htmlFor="idContaContabil">Conta contábil (de-para SPED ECD)</label>
              <input id="idContaContabil" value={form.idContaContabil} onChange={campo('idContaContabil')} />
            </div>
            <div className="col-6">
              <label htmlFor="idPlanoReferencial">Plano referencial (de-para RFB)</label>
              <input
                id="idPlanoReferencial"
                value={form.idPlanoReferencial}
                onChange={campo('idPlanoReferencial')}
              />
            </div>
            <div className="col-12">
              <label htmlFor="observacao">Observação</label>
              <textarea id="observacao" rows={2} value={form.observacao} onChange={campo('observacao')} />
            </div>
          </div>
        </section>

        <InfoRegistro
          codigo={planoExistente?.idPlanoContas}
          criadoEm={planoExistente?.criadoEm}
          atualizadoEm={planoExistente?.atualizadoEm}
        />
      </fieldset>
      </form>
      </div>

      {confirmarSalvarAberto && (
        <ConfirmarSalvarModal
          aoConfirmar={() => {
            setConfirmarSalvarAberto(false)
            validarEEnviar()
          }}
          aoCancelar={() => setConfirmarSalvarAberto(false)}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
