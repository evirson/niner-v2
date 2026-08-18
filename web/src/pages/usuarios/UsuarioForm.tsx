import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeUsuario } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { horaValida, mascararHora } from '../../lib/masks'
import {
  USUARIO_VAZIO,
  atualizarUsuario,
  buscarUsuario,
  criarUsuario,
  paraFormulario,
  paraRequisicao,
  type UsuarioFormState,
} from '../../lib/usuarios'
import { maiusculas } from '../../lib/texto'

const CHAVE_TELA = 'identidade.usuario.form'
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const NOMES_DIA_SEMANA: Record<number, string> = {
  1: 'Segunda-feira',
  2: 'Terça-feira',
  3: 'Quarta-feira',
  4: 'Quinta-feira',
  5: 'Sexta-feira',
  6: 'Sábado',
  7: 'Domingo',
}

/** {@code true} só quando os horários estão consistentes pra gravar: cada dia tem os dois
 *  campos preenchidos (com fim depois do início) ou os dois em branco (sem acesso naquele
 *  dia) — mesma regra validada de novo no backend (`UsuarioService.validar`). */
function horariosValidos(f: UsuarioFormState): boolean {
  if (!f.controlaHorarioAcesso) return true
  return f.horarios.every((h) => {
    const inicioPreenchido = h.horaInicio.trim() !== ''
    const fimPreenchido = h.horaFim.trim() !== ''
    if (!inicioPreenchido && !fimPreenchido) return true
    if (inicioPreenchido !== fimPreenchido) return false
    if (!horaValida(h.horaInicio) || !horaValida(h.horaFim)) return false
    return h.horaFim > h.horaInicio
  })
}

type CampoValidavel = 'nome' | 'email' | 'senha' | 'idsEmpresa'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function validarCampo(chave: CampoValidavel, f: UsuarioFormState, editando: boolean): string | undefined {
  if (chave === 'nome') {
    return f.nome.trim() ? undefined : 'Nome é obrigatório.'
  }
  if (chave === 'email') {
    if (!f.email.trim()) return 'E-mail é obrigatório.'
    return EMAIL_REGEX.test(f.email.trim()) ? undefined : 'E-mail inválido.'
  }
  if (chave === 'senha') {
    if (!f.senha.trim()) return editando ? undefined : 'Senha é obrigatória para um usuário novo.'
    return f.senha.trim().length >= 8 ? undefined : 'Senha deve ter ao menos 8 caracteres.'
  }
  if (chave === 'idsEmpresa') {
    return f.idsEmpresa.length > 0 ? undefined : 'Selecione ao menos uma empresa.'
  }
  return undefined
}

export default function UsuarioForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<UsuarioFormState>(USUARIO_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })

  const { data: usuarioExistente } = useQuery({
    queryKey: ['usuario', id],
    queryFn: () => buscarUsuario(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (usuarioExistente) setForm(paraFormulario(usuarioExistente))
  }, [usuarioExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarUsuario(Number(id), paraRequisicao(form)) : criarUsuario(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['usuarios'] })
      navigate('/usuarios', {
        state: {
          toast: {
            texto: editando ? 'Usuário atualizado com sucesso.' : 'Usuário cadastrado com sucesso.',
            tipo: 'sucesso',
          },
        },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o usuário.'),
  })

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form, editando) }))

  const alternarEmpresa = (idEmpresa: number) => (e: ChangeEvent<HTMLInputElement>) => {
    setForm((f) => ({
      ...f,
      idsEmpresa: e.target.checked
        ? [...f.idsEmpresa, idEmpresa]
        : f.idsEmpresa.filter((id) => id !== idEmpresa),
    }))
  }

  const alterarHorario = (indice: number, campo: 'horaInicio' | 'horaFim') => (e: ChangeEvent<HTMLInputElement>) => {
    const valor = mascararHora(e.target.value)
    setForm((f) => {
      const horarios = [...f.horarios]
      horarios[indice] = { ...horarios[indice], [campo]: valor }
      return { ...f, horarios }
    })
  }

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      nome: validarCampo('nome', form, editando),
      email: validarCampo('email', form, editando),
      senha: validarCampo('senha', form, editando),
      idsEmpresa: validarCampo('idsEmpresa', form, editando),
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToast('Corrija os campos destacados antes de salvar.')
      return
    }
    if (!horariosValidos(form)) {
      setToast(
        'Informe início e fim do horário de acesso juntos (com o fim depois do início), ou deixe os dois em branco para o dia sem acesso.',
      )
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
            <IconeUsuario size={34} />
            <h1>Usuário</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
            {!somenteLeitura && (
              <button type="submit" form="form-usuario" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-usuario"
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

          <div className="identificacao-linha">
            <label className="checkbox-linha" style={{ marginTop: 0 }}>
              <input
                type="checkbox"
                checked={form.ativo}
                onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))}
              />
              Usuário ativo
            </label>
            {usuarioExistente?.administrador && (
              <span className="badge" title="Definido no cadastro da loja — não pode ser alterado aqui">
                Administrador
              </span>
            )}
          </div>
          {usuarioExistente?.administrador && (
            <p className="muted" style={{ marginTop: 4 }}>
              Este é o administrador da conta. Só pode existir um - não pode ser alterado nem
              removido por aqui.
            </p>
          )}

          <div className="form-grid">
            <div className="col-12">
              <label htmlFor="nome">Nome *</label>
              <input
                id="nome"
                autoFocus
                value={form.nome}
                onChange={(e) => setForm((f) => ({ ...f, nome: maiusculas(e.target.value) }))}
                onBlur={aoSairDoCampo('nome')}
              />
              {erros.nome && <p className="erro-campo">{erros.nome}</p>}
            </div>

            <div className="col-6">
              <label htmlFor="email">E-mail *</label>
              <input
                id="email"
                type="email"
                value={form.email}
                onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                onBlur={aoSairDoCampo('email')}
              />
              {erros.email && <p className="erro-campo">{erros.email}</p>}
            </div>

            <div className="col-6">
              <label htmlFor="senha">Senha{editando ? ' (deixe em branco para manter a atual)' : ' *'}</label>
              <input
                id="senha"
                type="password"
                autoComplete="new-password"
                value={form.senha}
                onChange={(e) => setForm((f) => ({ ...f, senha: e.target.value }))}
                onBlur={aoSairDoCampo('senha')}
              />
              {erros.senha && <p className="erro-campo">{erros.senha}</p>}
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Empresas com acesso</p>
          <p className="muted" style={{ marginTop: 0 }}>
            O usuário só poderá operar nas empresas marcadas abaixo.
          </p>

          <div className="checklist-empresas-grid">
            {(empresas ?? []).map((emp) => (
              <label key={emp.idEmpresa} className="checklist-empresa-card">
                <input
                  type="checkbox"
                  checked={form.idsEmpresa.includes(emp.idEmpresa)}
                  onChange={alternarEmpresa(emp.idEmpresa)}
                />
                {emp.nomeFantasia ?? emp.razaoSocial}
              </label>
            ))}
          </div>
          {erros.idsEmpresa && <p className="erro-campo">{erros.idsEmpresa}</p>}
        </section>

        {!usuarioExistente?.administrador && (
          <section className="section">
            <p className="section-label">Horário de acesso</p>

            <label className="checkbox-linha" style={{ marginTop: 0 }}>
              <input
                type="checkbox"
                checked={form.controlaHorarioAcesso}
                onChange={(e) => setForm((f) => ({ ...f, controlaHorarioAcesso: e.target.checked }))}
              />
              Controla horário de acesso
            </label>
            <p className="muted" style={{ marginTop: 0 }}>
              Fora dos dias e horários abaixo, o usuário não consegue fazer login nem continuar
              trabalhando. Deixe início e fim em branco para não liberar acesso naquele dia (ex.:
              folga). O horário considerado é sempre o do servidor.
            </p>

            {form.controlaHorarioAcesso && (
              <table className="table-compacta">
                <thead>
                  <tr>
                    <th>Dia da semana</th>
                    <th>Início</th>
                    <th>Fim</th>
                  </tr>
                </thead>
                <tbody>
                  {form.horarios.map((h, i) => (
                    <tr key={h.diaSemana}>
                      <td>{NOMES_DIA_SEMANA[h.diaSemana]}</td>
                      <td>
                        <input
                          value={h.horaInicio}
                          placeholder="--:--"
                          style={{ width: 70 }}
                          onFocus={(e) => e.target.select()}
                          onChange={alterarHorario(i, 'horaInicio')}
                        />
                      </td>
                      <td>
                        <input
                          value={h.horaFim}
                          placeholder="--:--"
                          style={{ width: 70 }}
                          onFocus={(e) => e.target.select()}
                          onChange={alterarHorario(i, 'horaFim')}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        )}

        <InfoRegistro
          codigo={usuarioExistente?.idUsuario}
          criadoEm={usuarioExistente?.criadoEm}
          atualizadoEm={usuarioExistente?.atualizadoEm}
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
