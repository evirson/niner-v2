import {
  useEffect,
  useState,
  type ChangeEvent,
  type FocusEvent,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeTipoCarteira } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarPercentual, mascararPercentual } from '../../lib/masks'
import {
  ROTULO_CATEGORIA_CARTEIRA,
  TIPO_CARTEIRA_VAZIO,
  atualizarTipoCarteira,
  buscarTipoCarteira,
  criarTipoCarteira,
  paraFormulario,
  paraRequisicao,
  type CategoriaCarteira,
  type TipoCarteiraFormState,
} from '../../lib/tiposCarteira'
import type { EstadoListaTipoCarteira } from './TipoCarteiraLista'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'nomeCarteira' | 'categoriaCarteira' | 'prazoPagamento' | 'pcMinima' | 'pcMaxima'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

const CATEGORIAS_CARTEIRA: CategoriaCarteira[] = [
  'AVISTA',
  'CARTAO_DEBITO',
  'CARTAO_CREDITO',
  'CREDIARIO',
  'VALE_MERCADORIA',
]

/** Nome, categoria, prazo e parcelas são obrigatórios (prazo/parcelas aceitam 0/1 normalmente
 * — só não podem ficar em branco); Taxa Administradora e % Desconto/Acréscimo são opcionais
 * (2026-07-23/28), mais a regra de parcelas (mesmo CHECK do banco: mínima ≥ 1, máxima ≥
 * mínima). */
function validarCampo(chave: CampoValidavel, f: TipoCarteiraFormState): string | undefined {
  if (chave === 'nomeCarteira') return f.nomeCarteira.trim() ? undefined : 'Nome é obrigatório.'
  if (chave === 'categoriaCarteira') return f.categoriaCarteira ? undefined : 'Categoria é obrigatória.'
  if (!f[chave].trim()) return 'Campo obrigatório.'
  if (chave === 'pcMinima' && Number(f.pcMinima) < 1) return 'Deve ser pelo menos 1.'
  if (chave === 'pcMaxima' && f.pcMinima.trim() && Number(f.pcMaxima) < Number(f.pcMinima)) {
    return 'Deve ser maior ou igual à mínima.'
  }
  return undefined
}

/** Valor > 0 digitado (ainda incompleto, sem passar por `completarPercentual`) — usado só
 * para decidir se limpa o campo oposto, não para validação de verdade (essa é no submit). */
function ehPositivo(valor: string): boolean {
  const n = Number(valor.replace(',', '.'))
  return Number.isFinite(n) && n > 0
}

/**
 * Formulário de tipo de carteira — forma de pagamento completa (categoria, prazo/parcelas/
 * taxa administradora, desconto/acréscimo). Absorveu o cadastro de Moeda em 2026-07-28: a
 * mesma bandeira (nome) pode existir uma vez por categoria (ex.: "HIPER" em débito e "HIPER"
 * em crédito são dois cadastros distintos, com prazo/taxa próprios) — motivo completo em
 * `docs/PROGRESSO.md`. % Desconto e % Acréscimo nunca coexistem com valor positivo: digitar
 * um valor > 0 num deles limpa o outro automaticamente. Sem tela de configuração de campos:
 * todos são NOT NULL ou opcionais fixos, nada a configurar por tenant.
 */
export default function TipoCarteiraForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  // Estado da grade (busca/página/ordenação) recebido da lista — devolvido ao voltar (Salvar/
  // Cancelar), pra grade não resetar (2026-07-30, pedido explícito).
  const listaEstado = (location.state as { listaEstado?: EstadoListaTipoCarteira } | null)?.listaEstado

  const [form, setForm] = useState<TipoCarteiraFormState>(TIPO_CARTEIRA_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: carteiraExistente } = useQuery({
    queryKey: ['tipo-carteira', id],
    queryFn: () => buscarTipoCarteira(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (carteiraExistente) setForm(paraFormulario(carteiraExistente))
  }, [carteiraExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando
        ? atualizarTipoCarteira(Number(id), paraRequisicao(form))
        : criarTipoCarteira(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tipos-carteira'] })
      navigate('/tipos-carteira', {
        state: {
          toast: {
            texto: editando ? 'Tipo de carteira atualizado com sucesso.' : 'Tipo de carteira cadastrado com sucesso.',
            tipo: 'sucesso',
          },
          listaEstado,
        },
      })
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o tipo de carteira.'),
  })

  const campo = (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, nomeCarteira: maiusculas(e.target.value) }))

  const campoNumerico = (chave: 'prazoPagamento' | 'pcMinima' | 'pcMaxima') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: e.target.value.replace(/\D/g, '') }))

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      nomeCarteira: validarCampo('nomeCarteira', form),
      categoriaCarteira: validarCampo('categoriaCarteira', form),
      prazoPagamento: validarCampo('prazoPagamento', form),
      pcMinima: validarCampo('pcMinima', form),
      pcMaxima: validarCampo('pcMaxima', form),
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
            <IconeTipoCarteira size={34} />
            <h1>Tipo de Carteira</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.tipocarteira.form" />
            <BotaoFecharTela onClick={() => navigate('/tipos-carteira', { state: { listaEstado } })} />
            {!somenteLeitura && (
              <button type="submit" form="form-tipo-carteira" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-tipo-carteira"
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
            <div className="col-8">
              <label htmlFor="nomeCarteira">Nome *</label>
              <input
                id="nomeCarteira"
                autoFocus
                value={form.nomeCarteira}
                onChange={campo}
                onBlur={aoSairDoCampo('nomeCarteira')}
              />
              {erros.nomeCarteira && <p className="erro-campo">{erros.nomeCarteira}</p>}
            </div>
            <div className="col-4">
              <label htmlFor="categoriaCarteira">Categoria *</label>
              <select
                id="categoriaCarteira"
                value={form.categoriaCarteira}
                onChange={(e) =>
                  setForm((f) => ({ ...f, categoriaCarteira: e.target.value as CategoriaCarteira }))
                }
                onBlur={aoSairDoCampo('categoriaCarteira')}
              >
                <option value="">Selecione…</option>
                {CATEGORIAS_CARTEIRA.map((c) => (
                  <option key={c} value={c}>
                    {ROTULO_CATEGORIA_CARTEIRA[c]}
                  </option>
                ))}
              </select>
              {erros.categoriaCarteira && <p className="erro-campo">{erros.categoriaCarteira}</p>}
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                A mesma bandeira pode ter um cadastro por categoria (ex.: "HIPER" em Débito e
                em Crédito, com prazo/taxa próprios de cada um).
              </p>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Prazo, parcelas e taxa</p>

          <div className="form-grid">
            <div className="col-3">
              <label htmlFor="prazoPagamento">Prazo de Pagamento (dias) *</label>
              <input
                id="prazoPagamento"
                inputMode="numeric"
                value={form.prazoPagamento}
                onChange={campoNumerico('prazoPagamento')}
                onBlur={aoSairDoCampo('prazoPagamento')}
              />
              {erros.prazoPagamento && <p className="erro-campo">{erros.prazoPagamento}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="pcMinima">Parcelas — Mínima *</label>
              <input
                id="pcMinima"
                inputMode="numeric"
                value={form.pcMinima}
                onChange={campoNumerico('pcMinima')}
                onBlur={aoSairDoCampo('pcMinima')}
              />
              {erros.pcMinima && <p className="erro-campo">{erros.pcMinima}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="pcMaxima">Parcelas — Máxima *</label>
              <input
                id="pcMaxima"
                inputMode="numeric"
                value={form.pcMaxima}
                onChange={campoNumerico('pcMaxima')}
                onBlur={aoSairDoCampo('pcMaxima')}
              />
              {erros.pcMaxima && <p className="erro-campo">{erros.pcMaxima}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="taxaAdministradora">Taxa Administradora (%)</label>
              <input
                id="taxaAdministradora"
                inputMode="decimal"
                placeholder="0,00"
                value={form.taxaAdministradora}
                onChange={(e) => setForm((f) => ({ ...f, taxaAdministradora: mascararPercentual(e.target.value) }))}
                onBlur={() => setForm((f) => ({ ...f, taxaAdministradora: completarPercentual(f.taxaAdministradora) }))}
              />
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Desconto / Acréscimo</p>
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

        <section className="section">
          <p className="section-label">Recebimento de Crediário</p>
          <label className="checkbox-linha" style={{ marginTop: 0 }}>
            <input
              type="checkbox"
              checked={form.permiteReceberCrediario}
              onChange={(e) => setForm((f) => ({ ...f, permiteReceberCrediario: e.target.checked }))}
            />
            Permite receber parcelas de crediário
          </label>
          <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
            Só carteiras marcadas aqui aparecem como opção de pagamento na tela de Recebimento de
            Crediário.
          </p>
        </section>

        <InfoRegistro
          codigo={carteiraExistente?.idCarteira}
          criadoEm={carteiraExistente?.criadoEm}
          atualizadoEm={carteiraExistente?.atualizadoEm}
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
