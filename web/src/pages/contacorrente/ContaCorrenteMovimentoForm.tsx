import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeMovimentoContaCorrente } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarOpcoesContaCorrente } from '../../lib/contaCorrente'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import {
  CONTA_CORRENTE_MOVIMENTO_VAZIO,
  atualizarMovimento,
  buscarMovimento,
  criarMovimento,
  paraFormulario,
  paraRequisicao,
  type ContaCorrenteMovimentoFormState,
  type CreditoDebito,
} from '../../lib/contaCorrenteMovimento'
import { completarMoeda, dataValida, desmascararMoeda, formatarMoeda, mascararData, mascararMoeda } from '../../lib/masks'
import { listarPlanosContas } from '../../lib/planoContas'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'idContaCorrente' | 'idPlanoContas' | 'dataMovimento' | 'numeroDocumento' | 'creditoDebito' | 'valorTexto'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function validarCampo(chave: CampoValidavel, f: ContaCorrenteMovimentoFormState): string | undefined {
  if (chave === 'idContaCorrente') return f.idContaCorrente ? undefined : 'Escolha a conta corrente.'
  if (chave === 'idPlanoContas') return f.idPlanoContas ? undefined : 'Escolha o plano de contas.'
  if (chave === 'dataMovimento') {
    if (!f.dataMovimento.trim()) return 'Data do movimento é obrigatória.'
    return dataValida(f.dataMovimento) ? undefined : 'Data inválida.'
  }
  if (chave === 'numeroDocumento') return f.numeroDocumento.trim() ? undefined : 'Número do documento é obrigatório.'
  if (chave === 'creditoDebito') return f.creditoDebito ? undefined : 'Escolha Crédito ou Débito.'
  if (chave === 'valorTexto') return desmascararMoeda(f.valorTexto) > 0 ? undefined : 'Valor deve ser maior que zero.'
  return undefined
}

/**
 * Formulário de lançamento (extrato manual) de conta corrente
 * (docs/telas/conta-corrente-movimento.md). PK surrogate (`localizador`) — diferente de
 * `financeiro.contacorrente`, este formulário permite editar/excluir um lançamento já gravado.
 */
export default function ContaCorrenteMovimentoForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<ContaCorrenteMovimentoFormState>(CONTA_CORRENTE_MOVIMENTO_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: movimentoExistente } = useQuery({
    queryKey: ['conta-corrente-movimento', id],
    queryFn: () => buscarMovimento(Number(id)),
    enabled: editando,
  })

  const { data: contas } = useQuery({ queryKey: ['contas-corrente-opcoes'], queryFn: listarOpcoesContaCorrente })
  const { data: planos } = useQuery({
    queryKey: ['planos-contas', 'select-movimento'],
    queryFn: () => listarPlanosContas({ tamanho: 100 }),
  })

  useEffect(() => {
    if (movimentoExistente) setForm(paraFormulario(movimentoExistente))
  }, [movimentoExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarMovimento(Number(id), paraRequisicao(form)) : criarMovimento(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contas-corrente-movimento'] })
      navigate('/contas-corrente-movimento', {
        state: {
          toast: {
            texto: editando ? 'Lançamento atualizado com sucesso.' : 'Lançamento cadastrado com sucesso.',
            tipo: 'sucesso',
          },
        },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o lançamento.'),
  })

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      idContaCorrente: validarCampo('idContaCorrente', form),
      idPlanoContas: validarCampo('idPlanoContas', form),
      dataMovimento: validarCampo('dataMovimento', form),
      numeroDocumento: validarCampo('numeroDocumento', form),
      creditoDebito: validarCampo('creditoDebito', form),
      valorTexto: validarCampo('valorTexto', form),
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
            <IconeMovimentoContaCorrente size={34} />
            <h1>Movimentação de Conta Corrente</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.contacorrentemovimento.form" />
            <BotaoFecharTela />
            {!somenteLeitura && (
              <button type="submit" form="form-conta-corrente-movimento" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-conta-corrente-movimento"
        className="card form-secoes form-secoes-larga"
        onSubmit={submeter}
        onKeyDown={(e: KeyboardEvent<HTMLFormElement>) => {
          if (!somenteLeitura) aoTeclarEnterNoFormulario(e, () => setConfirmarSalvarAberto(true))
        }}
        noValidate
      >
      <fieldset disabled={somenteLeitura} className="form-fieldset">
        <section className="section">
          <p className="section-label">Lançamento</p>

          <div className="form-grid">
            <div className="col-6">
              <label htmlFor="idContaCorrente">Conta Corrente *</label>
              <select
                id="idContaCorrente"
                autoFocus={!editando}
                value={form.idContaCorrente}
                onChange={(e) => setForm((f) => ({ ...f, idContaCorrente: e.target.value }))}
                onBlur={aoSairDoCampo('idContaCorrente')}
              >
                <option value="">Selecione…</option>
                {contas?.map((c) => (
                  <option key={c.idContaCorrente} value={c.idContaCorrente}>
                    {c.idContaCorrente} — {c.descricao}
                  </option>
                ))}
              </select>
              {erros.idContaCorrente && <p className="erro-campo">{erros.idContaCorrente}</p>}
            </div>
            <div className="col-6">
              <label htmlFor="idPlanoContas">Plano de Contas *</label>
              <select
                id="idPlanoContas"
                value={form.idPlanoContas}
                onChange={(e) => setForm((f) => ({ ...f, idPlanoContas: e.target.value }))}
                onBlur={aoSairDoCampo('idPlanoContas')}
              >
                <option value="">Selecione…</option>
                {planos?.itens.map((p) => (
                  <option key={p.idPlanoContas} value={p.idPlanoContas}>
                    {p.idPlanoContas} — {p.descricao}
                  </option>
                ))}
              </select>
              {erros.idPlanoContas && <p className="erro-campo">{erros.idPlanoContas}</p>}
            </div>
          </div>

          <div className="form-grid">
            <div className="col-3">
              <label htmlFor="dataMovimento">Data do Movimento *</label>
              <input
                id="dataMovimento"
                inputMode="numeric"
                placeholder="dd/mm/aaaa"
                value={form.dataMovimento}
                onChange={(e) => setForm((f) => ({ ...f, dataMovimento: mascararData(e.target.value) }))}
                onFocus={(e) => e.target.select()}
                onBlur={aoSairDoCampo('dataMovimento')}
              />
              {erros.dataMovimento && <p className="erro-campo">{erros.dataMovimento}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="numeroDocumento">Número do Documento *</label>
              <input
                id="numeroDocumento"
                value={form.numeroDocumento}
                onChange={(e: ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, numeroDocumento: maiusculas(e.target.value) }))}
                onBlur={aoSairDoCampo('numeroDocumento')}
              />
              {erros.numeroDocumento && <p className="erro-campo">{erros.numeroDocumento}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="creditoDebito">Crédito/Débito *</label>
              <select
                id="creditoDebito"
                value={form.creditoDebito}
                onChange={(e) => setForm((f) => ({ ...f, creditoDebito: e.target.value as CreditoDebito }))}
                onBlur={aoSairDoCampo('creditoDebito')}
              >
                <option value="">Selecione…</option>
                <option value="C">Crédito</option>
                <option value="D">Débito</option>
              </select>
              {erros.creditoDebito && <p className="erro-campo">{erros.creditoDebito}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="valorTexto">Valor *</label>
              <input
                id="valorTexto"
                className="mono"
                inputMode="decimal"
                value={form.valorTexto}
                onChange={(e) => setForm((f) => ({ ...f, valorTexto: mascararMoeda(e.target.value) }))}
                onFocus={(e) => e.target.select()}
                onBlur={(e) => {
                  setForm((f) => ({ ...f, valorTexto: formatarMoeda(desmascararMoeda(completarMoeda(e.target.value))) }))
                  aoSairDoCampo('valorTexto')(e)
                }}
              />
              {erros.valorTexto && <p className="erro-campo">{erros.valorTexto}</p>}
            </div>
          </div>

          <div className="form-grid">
            <div className="col-3">
              <label>Compensado</label>
              <div className="identificacao-linha" style={{ minHeight: 45, alignItems: 'center' }}>
                <label className="checkbox-linha" style={{ marginTop: 0 }}>
                  <input
                    type="checkbox"
                    checked={form.compensado}
                    onChange={(e) => setForm((f) => ({ ...f, compensado: e.target.checked }))}
                  />
                  Compensado
                </label>
              </div>
            </div>
            <div className="col-9">
              <label htmlFor="observacao">Observação</label>
              <input
                id="observacao"
                value={form.observacao}
                onChange={(e) => setForm((f) => ({ ...f, observacao: e.target.value }))}
              />
            </div>
          </div>
        </section>

        <InfoRegistro
          codigo={movimentoExistente?.localizador}
          criadoEm={movimentoExistente?.criadoEm}
          atualizadoEm={movimentoExistente?.atualizadoEm}
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
