import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeEngrenagem, IconeFuncionario } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import LinhaGrid from '../../components/LinhaGrid'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarConfiguracaoTela, paraMapa, type ConfiguracaoCampo } from '../../lib/configuracaoTela'
import { useEu } from '../../lib/eu'
import {
  FUNCIONARIO_VAZIO,
  atualizarFuncionario,
  buscarFuncionario,
  criarFuncionario,
  paraFormulario,
  paraRequisicao,
  type FuncionarioFormState,
} from '../../lib/funcionarios'
import {
  celularValido,
  completarPercentual,
  documentoValido,
  mascararCpfCnpj,
  mascararPercentual,
  mascararTelefone,
} from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

const CHAVE_TELA = 'cadastros.funcionario.form'

/** Campos de texto livre do formulário sujeitos à convenção de maiúsculas do projeto. */
type CampoMaiusculo = 'nome' | 'cargo'

/**
 * Campos configuráveis pela tela de configuração (docs/telas/configuracao-tela.md) — mesma
 * lista do registro `CAMPOS_POR_TELA` no backend (`ConfiguracaoTelaService`). Nome não
 * entra: é estruturalmente obrigatório (NOT NULL no banco).
 */
type CampoConfiguravel = 'cpf' | 'telefone' | 'cargo' | 'percComissao'

type CampoValidavel = 'nome' | CampoConfiguravel
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function campoVisivel(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.visivel ?? true
}
function campoObrigatorio(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.obrigatorio ?? false
}

/** Regra de validação de cada campo — mesmo padrão de `ClienteForm`. */
function validarCampo(
  chave: CampoValidavel,
  f: FuncionarioFormState,
  mapaConfig: Map<string, ConfiguracaoCampo>,
): string | undefined {
  if (chave === 'nome') {
    return f.nome.trim() ? undefined : 'Nome é obrigatório.'
  }
  const campo = chave as CampoConfiguravel
  if (!campoVisivel(campo, mapaConfig)) return undefined
  const valor = f[campo] as string
  if (!valor.trim()) {
    return campoObrigatorio(campo, mapaConfig) ? 'Campo obrigatório.' : undefined
  }
  if (campo === 'cpf') {
    return documentoValido(valor) ? undefined : 'CPF inválido — confira os dígitos.'
  }
  if (campo === 'telefone') {
    return celularValido(valor) ? undefined : 'Celular deve ter 11 dígitos (DDD + 9XXXX-XXXX).'
  }
  return undefined
}

export default function FuncionarioForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<FuncionarioFormState>(FUNCIONARIO_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')

  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const { data: configuracao } = useQuery({
    queryKey: ['config-tela', CHAVE_TELA],
    queryFn: () => buscarConfiguracaoTela(CHAVE_TELA),
  })
  const mapaConfig = paraMapa(configuracao)

  const { data: funcionarioExistente } = useQuery({
    queryKey: ['funcionario', id],
    queryFn: () => buscarFuncionario(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (funcionarioExistente) setForm(paraFormulario(funcionarioExistente))
  }, [funcionarioExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarFuncionario(Number(id), paraRequisicao(form)) : criarFuncionario(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['funcionarios'] })
      navigate('/funcionarios')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o funcionário.'),
  })

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado. */
  const campo = (chave: CampoMaiusculo) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  /** Valida um campo ao sair dele. */
  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form, mapaConfig) }))

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      nome: validarCampo('nome', form, mapaConfig),
      cpf: validarCampo('cpf', form, mapaConfig),
      telefone: validarCampo('telefone', form, mapaConfig),
      cargo: validarCampo('cargo', form, mapaConfig),
      percComissao: validarCampo('percComissao', form, mapaConfig),
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
            <IconeFuncionario size={34} />
            <h1>Funcionário</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/funcionarios/configuracao"
                aria-label="Configurar tela de funcionário"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <button type="button" className="btn ghost" onClick={() => navigate('/funcionarios')}>
              {somenteLeitura ? 'Voltar' : 'Cancelar'}
            </button>
            {!somenteLeitura && (
              <button type="submit" form="form-funcionario" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form id="form-funcionario" className="card form-secoes form-secoes-larga" onSubmit={submeter} noValidate>
      <fieldset disabled={somenteLeitura} className="form-fieldset">
        <section className="section">
          <p className="section-label">Identificação</p>

          <div className="identificacao-linha">
            <label className="checkbox-linha" style={{ marginTop: 0 }}>
              <input
                type="checkbox"
                checked={form.ativo}
                onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))}
              />
              Funcionário ativo
            </label>
          </div>

          <div className="form-grid">
            <div className="col-12">
              <label htmlFor="nome">Nome *</label>
              <input id="nome" autoFocus value={form.nome} onChange={campo('nome')} onBlur={aoSairDoCampo('nome')} />
              {erros.nome && <p className="erro-campo">{erros.nome}</p>}
            </div>

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('cpf', mapaConfig),
                  peso: 6,
                  children: (
                    <>
                      <label htmlFor="cpf">CPF{campoObrigatorio('cpf', mapaConfig) && ' *'}</label>
                      <input
                        id="cpf"
                        value={form.cpf}
                        onChange={(e) => setForm((f) => ({ ...f, cpf: mascararCpfCnpj(e.target.value, true) }))}
                        onBlur={aoSairDoCampo('cpf')}
                      />
                      {erros.cpf && <p className="erro-campo">{erros.cpf}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('telefone', mapaConfig),
                  peso: 6,
                  children: (
                    <>
                      <label htmlFor="telefone">Celular{campoObrigatorio('telefone', mapaConfig) && ' *'}</label>
                      <input
                        id="telefone"
                        value={form.telefone}
                        onChange={(e) => setForm((f) => ({ ...f, telefone: mascararTelefone(e.target.value) }))}
                        onBlur={aoSairDoCampo('telefone')}
                      />
                      {erros.telefone && <p className="erro-campo">{erros.telefone}</p>}
                    </>
                  ),
                },
              ]}
            />
          </div>
        </section>

        {(campoVisivel('cargo', mapaConfig) || campoVisivel('percComissao', mapaConfig)) && (
          <section className="section">
            <p className="section-label">Cargo e comissão</p>

            <div className="form-grid">
              <LinhaGrid
                itens={[
                  {
                    visivel: campoVisivel('cargo', mapaConfig),
                    peso: 8,
                    children: (
                      <>
                        <label htmlFor="cargo">Cargo{campoObrigatorio('cargo', mapaConfig) && ' *'}</label>
                        <input
                          id="cargo"
                          value={form.cargo}
                          onChange={campo('cargo')}
                          onBlur={aoSairDoCampo('cargo')}
                        />
                        {erros.cargo && <p className="erro-campo">{erros.cargo}</p>}
                      </>
                    ),
                  },
                  {
                    visivel: campoVisivel('percComissao', mapaConfig),
                    peso: 4,
                    children: (
                      <>
                        <label htmlFor="percComissao">
                          % Comissão{campoObrigatorio('percComissao', mapaConfig) && ' *'}
                        </label>
                        <input
                          id="percComissao"
                          inputMode="decimal"
                          placeholder="0,00"
                          value={form.percComissao}
                          onChange={(e) =>
                            setForm((f) => ({ ...f, percComissao: mascararPercentual(e.target.value) }))
                          }
                          onBlur={(e) => {
                            setForm((f) => ({ ...f, percComissao: completarPercentual(f.percComissao) }))
                            aoSairDoCampo('percComissao')(e)
                          }}
                        />
                        {erros.percComissao && <p className="erro-campo">{erros.percComissao}</p>}
                      </>
                    ),
                  },
                ]}
              />
            </div>
          </section>
        )}

        <InfoRegistro
          codigo={funcionarioExistente?.idFuncionario}
          criadoEm={funcionarioExistente?.criadoEm}
          atualizadoEm={funcionarioExistente?.atualizadoEm}
        />
      </fieldset>
      </form>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
