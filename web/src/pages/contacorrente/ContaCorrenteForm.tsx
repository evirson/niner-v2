import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeContaCorrente } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarBanco } from '../../lib/banco'
import {
  CONTA_CORRENTE_VAZIA,
  atualizarContaCorrente,
  buscarContaCorrente,
  criarContaCorrente,
  paraFormulario,
  paraRequisicao,
  type ContaCorrenteFormState,
} from '../../lib/contaCorrente'
import { listarEmpresas } from '../../lib/empresas'
import { hojeISO } from '../../lib/datas'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { dataParaIso, dataValida, mascararData } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

type CampoValidavel = 'idContaCorrente' | 'idEmpresa' | 'idBanco' | 'idAgencia' | 'descricao' | 'dataAbertura'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function validarCampo(chave: CampoValidavel, f: ContaCorrenteFormState): string | undefined {
  if (chave === 'idContaCorrente') return f.idContaCorrente.trim() ? undefined : 'Número da conta é obrigatório.'
  if (chave === 'idEmpresa') return f.idEmpresa !== '' ? undefined : 'Escolha a empresa.'
  if (chave === 'idBanco') return f.idBanco.trim() ? undefined : 'Banco é obrigatório.'
  if (chave === 'idAgencia') return f.idAgencia.trim() ? undefined : 'Agência é obrigatória.'
  if (chave === 'descricao') return f.descricao.trim() ? undefined : 'Descrição é obrigatória.'
  if (chave === 'dataAbertura') {
    if (!f.dataAbertura) return undefined
    if (!dataValida(f.dataAbertura)) return 'Data inválida.'
    return dataParaIso(f.dataAbertura)! > hojeISO() ? 'Data de abertura não pode ser no futuro.' : undefined
  }
  return undefined
}

/**
 * Formulário de conta corrente (docs/telas/conta-corrente.md). O número/código da conta é a
 * própria PK de negócio — digitado livremente ao criar e **imutável depois** (mesmo padrão de
 * `cadastros.planocontas`), já que é referenciado por `conta_corrente_movimento`.
 */
export default function ContaCorrenteForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<ContaCorrenteFormState>(CONTA_CORRENTE_VAZIA)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)
  const [nomeBanco, setNomeBanco] = useState('')

  const { data: contaExistente } = useQuery({
    queryKey: ['conta-corrente', id],
    queryFn: () => buscarContaCorrente(String(id)),
    enabled: editando,
  })

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })

  useEffect(() => {
    if (contaExistente) {
      setForm(paraFormulario(contaExistente))
      buscarNomeDoBanco(contaExistente.idBanco)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contaExistente])

  /** Busca o nome do banco pelo código digitado (mesmo estilo do autopreenchimento de NCM). */
  const buscarNomeDoBanco = async (codigo: string) => {
    const cod = codigo.trim()
    if (!cod) {
      setNomeBanco('')
      return
    }
    const banco = await buscarBanco(cod)
    setNomeBanco(banco?.nomeBanco ?? '')
  }

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarContaCorrente(String(id), paraRequisicao(form)) : criarContaCorrente(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contas-corrente'] })
      navigate('/contas-corrente', {
        state: {
          toast: {
            texto: editando ? 'Conta corrente atualizada com sucesso.' : 'Conta corrente cadastrada com sucesso.',
            tipo: 'sucesso',
          },
        },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a conta corrente.'),
  })

  const campoTexto = (chave: 'idContaCorrente' | 'idBanco' | 'idAgencia' | 'descricao') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      idContaCorrente: validarCampo('idContaCorrente', form),
      idEmpresa: validarCampo('idEmpresa', form),
      idBanco: validarCampo('idBanco', form),
      idAgencia: validarCampo('idAgencia', form),
      descricao: validarCampo('descricao', form),
      dataAbertura: validarCampo('dataAbertura', form),
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
            <IconeContaCorrente size={34} />
            <h1>Conta Corrente</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.contacorrente.form" />
            <button type="button" className="btn ghost" onClick={() => navigate('/contas-corrente')}>
              {somenteLeitura ? 'Voltar' : 'Cancelar'}
            </button>
            {!somenteLeitura && (
              <button type="submit" form="form-conta-corrente" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-conta-corrente"
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
            <div className="col-4">
              <label htmlFor="idContaCorrente">Número da Conta *</label>
              <input
                id="idContaCorrente"
                autoFocus={!editando}
                placeholder="ex.: 001-12345-6"
                className={editando ? 'campo-leitura' : undefined}
                readOnly={editando}
                tabIndex={editando ? -1 : undefined}
                value={form.idContaCorrente}
                onChange={campoTexto('idContaCorrente')}
                onBlur={aoSairDoCampo('idContaCorrente')}
              />
              {erros.idContaCorrente && <p className="erro-campo">{erros.idContaCorrente}</p>}
            </div>
            <div className="col-8">
              <label htmlFor="descricao">Descrição *</label>
              <input
                id="descricao"
                autoFocus={editando && !somenteLeitura}
                value={form.descricao}
                onChange={campoTexto('descricao')}
                onBlur={aoSairDoCampo('descricao')}
              />
              {erros.descricao && <p className="erro-campo">{erros.descricao}</p>}
            </div>
          </div>

          <div className="form-grid">
            <div className="col-4">
              <label htmlFor="idEmpresa">Empresa *</label>
              <select
                id="idEmpresa"
                value={form.idEmpresa}
                onChange={(e) => setForm((f) => ({ ...f, idEmpresa: e.target.value ? Number(e.target.value) : '' }))}
                onBlur={aoSairDoCampo('idEmpresa')}
              >
                <option value="">Selecione…</option>
                {empresas?.map((emp) => (
                  <option key={emp.idEmpresa} value={emp.idEmpresa}>
                    {emp.nomeFantasia ?? emp.razaoSocial}
                  </option>
                ))}
              </select>
              {erros.idEmpresa && <p className="erro-campo">{erros.idEmpresa}</p>}
            </div>
            <div className="col-2">
              <label htmlFor="idBanco">Banco *</label>
              <input
                id="idBanco"
                placeholder="ex.: 341"
                value={form.idBanco}
                onChange={campoTexto('idBanco')}
                onBlur={(e) => {
                  aoSairDoCampo('idBanco')(e)
                  buscarNomeDoBanco(e.target.value)
                }}
              />
              {erros.idBanco && <p className="erro-campo">{erros.idBanco}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="nomeBanco">Nome do Banco</label>
              <input
                id="nomeBanco"
                className="campo-leitura"
                readOnly
                tabIndex={-1}
                placeholder="Digite um código de banco válido…"
                value={nomeBanco}
              />
            </div>
            <div className="col-3">
              <label htmlFor="idAgencia">Agência *</label>
              <input id="idAgencia" placeholder="ex.: 1234" value={form.idAgencia} onChange={campoTexto('idAgencia')} onBlur={aoSairDoCampo('idAgencia')} />
              {erros.idAgencia && <p className="erro-campo">{erros.idAgencia}</p>}
            </div>
          </div>

          <div className="form-grid">
            <div className="col-4">
              <label htmlFor="dataAbertura">Data de Abertura</label>
              <input
                id="dataAbertura"
                inputMode="numeric"
                placeholder="dd/mm/aaaa"
                value={form.dataAbertura}
                onChange={(e) => setForm((f) => ({ ...f, dataAbertura: mascararData(e.target.value) }))}
                onFocus={(e) => e.target.select()}
                onBlur={aoSairDoCampo('dataAbertura')}
              />
              {erros.dataAbertura && <p className="erro-campo">{erros.dataAbertura}</p>}
            </div>
            <div className="col-8">
              <label>Status</label>
              <div className="identificacao-linha" style={{ minHeight: 45, alignItems: 'center' }}>
                <label className="checkbox-linha" style={{ marginTop: 0 }}>
                  <input
                    type="checkbox"
                    checked={form.ativo}
                    onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))}
                  />
                  Ativa
                </label>
              </div>
            </div>
          </div>
        </section>

        <InfoRegistro
          codigo={contaExistente?.idContaCorrente}
          criadoEm={contaExistente?.criadoEm}
          atualizadoEm={contaExistente?.atualizadoEm}
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
