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
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeMoeda } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarPercentual, mascararPercentual } from '../../lib/masks'
import {
  MOEDA_VAZIA,
  atualizarMoeda,
  buscarMoeda,
  criarMoeda,
  paraFormulario,
  paraRequisicao,
  type MoedaFormState,
} from '../../lib/moedas'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'nomeMoeda' | 'percDesconto' | 'percAcrescimo'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

/** Só o nome é obrigatório — % Desconto/% Acréscimo são opcionais (2026-07-23). */
function validarCampo(chave: CampoValidavel, f: MoedaFormState): string | undefined {
  if (chave === 'nomeMoeda') return f.nomeMoeda.trim() ? undefined : 'Nome é obrigatório.'
  return undefined
}

/** Valor > 0 digitado (ainda incompleto, sem passar por `completarPercentual`) — usado só
 * para decidir se limpa o campo oposto, não para validação de verdade (essa é no submit). */
function ehPositivo(valor: string): boolean {
  const n = Number(valor.replace(',', '.'))
  return Number.isFinite(n) && n > 0
}

/**
 * Formulário de moeda (forma de recebimento). Sem tela de configuração de campos: todos os
 * campos são NOT NULL, nada a configurar (mesmo padrão de Plano de Contas). O vínculo com
 * tipo de carteira (`moeda_detalhe`) é gerido na tela de Tipo de Carteira, não aqui.
 * % Desconto e % Acréscimo nunca coexistem com valor positivo (pedido do dono do produto,
 * 2026-07-23): digitar um valor > 0 num deles limpa o outro automaticamente.
 */
export default function MoedaForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<MoedaFormState>(MOEDA_VAZIA)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: moedaExistente } = useQuery({
    queryKey: ['moeda', id],
    queryFn: () => buscarMoeda(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (moedaExistente) setForm(paraFormulario(moedaExistente))
  }, [moedaExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarMoeda(Number(id), paraRequisicao(form)) : criarMoeda(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['moedas'] })
      navigate('/moedas')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a moeda.'),
  })

  const campo = (e: ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, nomeMoeda: maiusculas(e.target.value) }))

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      nomeMoeda: validarCampo('nomeMoeda', form),
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
            <IconeMoeda size={34} />
            <h1>Moeda</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.moeda.form" />
            <button type="button" className="btn ghost" onClick={() => navigate('/moedas')}>
              {somenteLeitura ? 'Voltar' : 'Cancelar'}
            </button>
            {!somenteLeitura && (
              <button type="submit" form="form-moeda" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-moeda"
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
            <div className="col-12">
              <label htmlFor="nomeMoeda">Nome *</label>
              <input id="nomeMoeda" autoFocus value={form.nomeMoeda} onChange={campo} onBlur={aoSairDoCampo('nomeMoeda')} />
              {erros.nomeMoeda && <p className="erro-campo">{erros.nomeMoeda}</p>}
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Percentuais</p>
          <p className="muted" style={{ marginTop: -4 }}>
            Preencha desconto OU acréscimo — nunca os dois juntos. Deixe em branco se não se aplicar.
          </p>

          <div className="form-grid">
            <div className="col-3">
              <label htmlFor="percDesconto">% Desconto</label>
              <input
                id="percDesconto"
                inputMode="decimal"
                placeholder="0,00"
                value={form.percDesconto}
                onChange={(e) => {
                  const valor = mascararPercentual(e.target.value)
                  setForm((f) => ({
                    ...f,
                    percDesconto: valor,
                    percAcrescimo: ehPositivo(valor) ? '' : f.percAcrescimo,
                  }))
                }}
                onBlur={() => setForm((f) => ({ ...f, percDesconto: completarPercentual(f.percDesconto) }))}
              />
            </div>
            <div className="col-3">
              <label htmlFor="percAcrescimo">% Acréscimo</label>
              <input
                id="percAcrescimo"
                inputMode="decimal"
                placeholder="0,00"
                value={form.percAcrescimo}
                onChange={(e) => {
                  const valor = mascararPercentual(e.target.value)
                  setForm((f) => ({
                    ...f,
                    percAcrescimo: valor,
                    percDesconto: ehPositivo(valor) ? '' : f.percDesconto,
                  }))
                }}
                onBlur={() => setForm((f) => ({ ...f, percAcrescimo: completarPercentual(f.percAcrescimo) }))}
              />
            </div>
          </div>
        </section>

        <InfoRegistro
          codigo={moedaExistente?.idMoeda}
          criadoEm={moedaExistente?.criadoEm}
          atualizadoEm={moedaExistente?.atualizadoEm}
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
