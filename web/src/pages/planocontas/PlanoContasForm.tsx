import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconePlanoContas } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  PLANO_CONTAS_VAZIO,
  TIPOS_MOVIMENTO,
  atualizarPlanoContas,
  buscarPlanoContas,
  criarPlanoContas,
  paraFormulario,
  paraRequisicao,
  type PlanoContasFormState,
  type TipoMovimentoConta,
} from '../../lib/planoContas'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'codigo' | 'descricao' | 'tipoMovimento'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

/** Todos os campos da tabela são NOT NULL — validação simples de obrigatoriedade. */
function validarCampo(chave: CampoValidavel, f: PlanoContasFormState): string | undefined {
  if (chave === 'codigo') return f.codigo.trim() ? undefined : 'Código é obrigatório.'
  if (chave === 'descricao') return f.descricao.trim() ? undefined : 'Descrição é obrigatória.'
  return f.tipoMovimento ? undefined : 'Escolha o tipo de movimento.'
}

/**
 * Formulário de plano de contas. O código contábil é a própria PK de negócio — digitado
 * livremente ao criar (ex.: "3.1.001") e **imutável depois** (campo somente leitura na
 * edição; é referenciado por fornecedor/contas a pagar). Sem tela de configuração de campos:
 * todos são NOT NULL, nada a configurar.
 */
export default function PlanoContasForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { codigo } = useParams()
  const editando = Boolean(codigo)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<PlanoContasFormState>(PLANO_CONTAS_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')

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
      navigate('/planos-contas')
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o plano de contas.'),
  })

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado. */
  const campo = (chave: 'codigo' | 'descricao') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  /** Valida um campo ao sair dele. */
  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      codigo: validarCampo('codigo', form),
      descricao: validarCampo('descricao', form),
      tipoMovimento: validarCampo('tipoMovimento', form),
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToast('Corrija os campos destacados antes de salvar.')
      return
    }
    salvar.mutate()
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
            <button type="button" className="btn ghost" onClick={() => navigate('/planos-contas')}>
              {somenteLeitura ? 'Voltar' : 'Cancelar'}
            </button>
            {!somenteLeitura && (
              <button type="submit" form="form-plano-contas" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form id="form-plano-contas" className="card form-secoes form-secoes-larga" onSubmit={submeter} noValidate>
      <fieldset disabled={somenteLeitura} className="form-fieldset">
        <section className="section">
          <p className="section-label">Identificação</p>

          <div className="form-grid">
            <div className="col-4">
              <label htmlFor="codigo">Código *</label>
              <input
                id="codigo"
                autoFocus={!editando}
                placeholder="ex.: 3.1.001"
                className={editando ? 'campo-leitura' : undefined}
                readOnly={editando}
                tabIndex={editando ? -1 : undefined}
                value={form.codigo}
                onChange={campo('codigo')}
                onBlur={aoSairDoCampo('codigo')}
              />
              {erros.codigo && <p className="erro-campo">{erros.codigo}</p>}
            </div>
            <div className="col-8">
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
          </div>
        </section>

        <section className="section">
          <p className="section-label">Classificação</p>

          <div className="form-grid">
            <div className="col-4">
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
                    {t}
                  </option>
                ))}
              </select>
              {erros.tipoMovimento && <p className="erro-campo">{erros.tipoMovimento}</p>}
            </div>
            <div className="col-8">
              <label>Relatórios</label>
              <div className="identificacao-linha" style={{ minHeight: 45, alignItems: 'center' }}>
                <label className="checkbox-linha" style={{ marginTop: 0 }}>
                  <input
                    type="checkbox"
                    checked={form.incluiDre}
                    onChange={(e) => setForm((f) => ({ ...f, incluiDre: e.target.checked }))}
                  />
                  Compõe a DRE
                </label>
                <label className="checkbox-linha" style={{ marginTop: 0 }}>
                  <input
                    type="checkbox"
                    checked={form.incluiFluxoCaixa}
                    onChange={(e) => setForm((f) => ({ ...f, incluiFluxoCaixa: e.target.checked }))}
                  />
                  Compõe o fluxo de caixa
                </label>
              </div>
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

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
