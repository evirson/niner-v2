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
import { IconeFormaCartao, IconeFormaPix, IconeMoeda, IconeTipoCarteira } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import MoedaModal from '../../components/MoedaModal'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarPercentual, mascararPercentual } from '../../lib/masks'
import { listarMoedas } from '../../lib/moedas'
import {
  TIPO_CARTEIRA_VAZIO,
  atualizarTipoCarteira,
  buscarTipoCarteira,
  criarTipoCarteira,
  paraFormulario,
  paraRequisicao,
  type TipoCarteiraFormState,
} from '../../lib/tiposCarteira'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'nomeCarteira' | 'prazoPagamento' | 'pcMinima' | 'pcMaxima'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

/** Nome, prazo e parcelas são obrigatórios (prazo/parcelas aceitam 0/1 normalmente — só não
 * podem ficar em branco); Taxa Administradora é opcional (2026-07-23, nem todo tipo de
 * carteira cobra taxa), mais a regra de parcelas (mesmo CHECK do banco: mínima ≥ 1, máxima ≥
 * mínima). */
function validarCampo(chave: CampoValidavel, f: TipoCarteiraFormState): string | undefined {
  if (chave === 'nomeCarteira') return f.nomeCarteira.trim() ? undefined : 'Nome é obrigatório.'
  if (!f[chave].trim()) return 'Campo obrigatório.'
  if (chave === 'pcMinima' && Number(f.pcMinima) < 1) return 'Deve ser pelo menos 1.'
  if (chave === 'pcMaxima' && f.pcMinima.trim() && Number(f.pcMaxima) < Number(f.pcMinima)) {
    return 'Deve ser maior ou igual à mínima.'
  }
  return undefined
}

/** Ícone representativo por nome da moeda (heurística por palavra-chave — moeda é texto
 * livre, então nomes fora do padrão caem no ícone genérico). */
function IconeDaMoeda({ nomeMoeda }: { nomeMoeda: string }) {
  const nome = nomeMoeda.toUpperCase()
  if (nome.includes('PIX')) return <IconeFormaPix size={18} />
  if (nome.includes('CART')) return <IconeFormaCartao size={18} />
  return <IconeMoeda size={18} />
}

/**
 * Formulário de tipo de carteira (prazo/parcelas/taxa do crediário, cartão etc.). Gerencia
 * embutido o vínculo N:N com moeda (`moeda_detalhe`, 2026-07-23) — checklist sem ordenação
 * (diferente da categoria de Produto): o fluxo é "criar um tipo de carteira e escolher em
 * quais moedas ele vale", com criação rápida de moeda embutida se a que falta ainda não
 * existir (`MoedaModal`, mesmo papel do `PlanoContasModal` em Fornecedor). Sem tela de
 * configuração de campos: todos são NOT NULL, nada a configurar.
 */
export default function TipoCarteiraForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<TipoCarteiraFormState>(TIPO_CARTEIRA_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)
  const [modalMoedaAberto, setModalMoedaAberto] = useState(false)

  const { data: carteiraExistente } = useQuery({
    queryKey: ['tipo-carteira', id],
    queryFn: () => buscarTipoCarteira(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (carteiraExistente) setForm(paraFormulario(carteiraExistente))
  }, [carteiraExistente])

  const { data: moedas } = useQuery({
    queryKey: ['moedas', 'select-tipo-carteira'],
    queryFn: () => listarMoedas({ tamanho: 100 }),
  })

  const salvar = useMutation({
    mutationFn: () =>
      editando
        ? atualizarTipoCarteira(Number(id), paraRequisicao(form))
        : criarTipoCarteira(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tipos-carteira'] })
      navigate('/tipos-carteira')
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

  const alternarMoeda = (idMoeda: number) =>
    setForm((f) => ({
      ...f,
      moedas: f.moedas.includes(idMoeda) ? f.moedas.filter((m) => m !== idMoeda) : [...f.moedas, idMoeda],
    }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      nomeCarteira: validarCampo('nomeCarteira', form),
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
            <button type="button" className="btn ghost" onClick={() => navigate('/tipos-carteira')}>
              {somenteLeitura ? 'Voltar' : 'Cancelar'}
            </button>
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
            <div className="col-12">
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
          <p className="section-label">Moedas em que este tipo de carteira vale</p>

          <div className="identificacao-linha" style={{ flexWrap: 'wrap', gap: '8px 24px' }}>
            {(moedas?.itens ?? []).map((m) => (
              <label
                key={m.idMoeda}
                className="checkbox-linha"
                style={{ marginTop: 0, display: 'inline-flex', alignItems: 'center', gap: 6 }}
              >
                <input
                  type="checkbox"
                  checked={form.moedas.includes(m.idMoeda)}
                  onChange={() => alternarMoeda(m.idMoeda)}
                />
                <IconeDaMoeda nomeMoeda={m.nomeMoeda} />
                {m.nomeMoeda}
              </label>
            ))}
          </div>

          {!somenteLeitura && (
            <button
              type="button"
              className="btn ghost"
              style={{ marginTop: 12 }}
              onClick={() => setModalMoedaAberto(true)}
            >
              ＋ Nova moeda
            </button>
          )}
        </section>

        <InfoRegistro
          codigo={carteiraExistente?.idCarteira}
          criadoEm={carteiraExistente?.criadoEm}
          atualizadoEm={carteiraExistente?.atualizadoEm}
        />
      </fieldset>
      </form>
      </div>

      {modalMoedaAberto && (
        <MoedaModal
          aoFechar={() => setModalMoedaAberto(false)}
          aoCriar={(idMoeda) => {
            setForm((f) => ({ ...f, moedas: [...f.moedas, idMoeda] }))
            setModalMoedaAberto(false)
          }}
        />
      )}

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
