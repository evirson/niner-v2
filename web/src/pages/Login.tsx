import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import Toast from '../components/Toast'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

interface EmpresaOpcaoLogin {
  idEmpresa: number
  nomeEmpresa: string
}

interface LoginResp {
  token: string | null
  idTenant: number
  slug: string
  escolherEmpresa: boolean
  empresas: EmpresaOpcaoLogin[]
}

/**
 * Login do lojista: slug da loja + email + senha → token (JWT). Quando o usuário tem acesso a
 * mais de uma empresa (`usuario_empresa`, 2026-07-28), a API responde `escolherEmpresa=true`
 * em vez do token — a tela troca pra uma segunda etapa pedindo qual empresa ele quer acessar
 * nesta sessão; tudo que ele cadastrar depois (registros com `id_empresa`) usa essa escolha.
 */
export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [slug, setSlug] = useState('')
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState('')
  const [carregando, setCarregando] = useState(false)
  const [empresas, setEmpresas] = useState<EmpresaOpcaoLogin[] | null>(null)

  const entrarComEmpresa = async (idEmpresa?: number) => {
    setErro('')
    setCarregando(true)
    try {
      const r = await api<LoginResp>('/api/publico/login', {
        method: 'POST',
        body: JSON.stringify({ slug: slug.trim(), email: email.trim(), senha, idEmpresa }),
      })
      if (r.escolherEmpresa) {
        setEmpresas(r.empresas)
        return
      }
      login(r.token as string)
      navigate('/', { replace: true })
    } catch (err) {
      setErro(err instanceof ApiError ? err.message : 'Não foi possível entrar.')
      setEmpresas(null)
    } finally {
      setCarregando(false)
    }
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    entrarComEmpresa()
  }

  if (empresas) {
    return (
      <div className="login-wrap">
        <div className="card login-card">
          <a className="brand" href="/" style={{ fontSize: 22 }}>
            NI<span>NER</span>
          </a>
          <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Qual empresa você quer acessar?</h1>
          <p className="muted" style={{ marginTop: 0 }}>
            Tudo que você cadastrar nesta sessão vai ficar registrado na empresa escolhida.
          </p>

          <div className="lista-categorias" style={{ marginTop: 12 }}>
            {empresas.map((emp) => (
              <button
                key={emp.idEmpresa}
                type="button"
                className="btn ghost"
                style={{ width: '100%', justifyContent: 'flex-start' }}
                disabled={carregando}
                onClick={() => entrarComEmpresa(emp.idEmpresa)}
              >
                {emp.nomeEmpresa}
              </button>
            ))}
          </div>

          {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
          <button
            type="button"
            className="btn ghost"
            style={{ width: '100%', marginTop: 12 }}
            onClick={() => setEmpresas(null)}
          >
            Voltar
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submeter}>
        <a className="brand" href="/" style={{ fontSize: 22 }}>
          NI<span>NER</span>
        </a>
        <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Entrar na sua loja</h1>
        <p className="muted" style={{ marginTop: 0 }}>Acesse o painel do seu ERP.</p>

        <label htmlFor="slug">Loja (identificador)</label>
        <input id="slug" value={slug} onChange={(e) => setSlug(e.target.value)} placeholder="ex.: minha-loja" required />

        <label htmlFor="email">E-mail</label>
        <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />

        <label htmlFor="senha">Senha</label>
        <input id="senha" type="password" value={senha} onChange={(e) => setSenha(e.target.value)} required />

        {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
        <button className="btn" type="submit" disabled={carregando} style={{ width: '100%', marginTop: 12 }}>
          {carregando ? 'Entrando…' : 'Entrar'}
        </button>
        <p className="muted" style={{ fontSize: 13, marginTop: 14 }}>
          Ainda não tem conta?{' '}
          <a href="http://localhost:5175/assinar">Teste grátis por 14 dias</a>.
        </p>
      </form>
    </div>
  )
}
