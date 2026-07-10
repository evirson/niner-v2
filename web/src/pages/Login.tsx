import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

interface LoginResp {
  token: string
  idTenant: number
  slug: string
}

/** Login do lojista: slug da loja + email + senha → token (JWT). */
export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [slug, setSlug] = useState('')
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState('')
  const [carregando, setCarregando] = useState(false)

  const submeter = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    setCarregando(true)
    try {
      const r = await api<LoginResp>('/api/publico/login', {
        method: 'POST',
        body: JSON.stringify({ slug: slug.trim(), email: email.trim(), senha }),
      })
      login(r.token)
      navigate('/', { replace: true })
    } catch (err) {
      setErro(err instanceof ApiError ? err.message : 'Não foi possível entrar.')
    } finally {
      setCarregando(false)
    }
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

        {erro && (
          <p role="alert" className="erro">
            {erro}
          </p>
        )}
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
