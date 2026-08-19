import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'

export default function Login() {
  const { sessao, entrar } = useAuth()
  const navegar = useNavigate()
  const local = useLocation()
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  if (sessao) {
    return <Navigate to="/" replace />
  }

  async function enviar(e: FormEvent) {
    e.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await entrar(email.trim(), senha)
      navegar((local.state as { de?: string } | null)?.de ?? '/', { replace: true })
    } catch (err) {
      setErro(err instanceof Error ? err.message : 'Não foi possível entrar.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="tela-login">
      <form className="card" onSubmit={enviar}>
        <h1>
          <span style={{ color: 'var(--accent)' }}>Niner</span> Plataforma
        </h1>
        <p className="muted" style={{ marginTop: 0, marginBottom: 18 }}>
          Acesso restrito ao time da Vetor.
        </p>

        <div className="campo">
          <label htmlFor="email">E-mail</label>
          <input id="email" type="email" autoFocus autoComplete="username" value={email}
            onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="campo">
          <label htmlFor="senha">Senha</label>
          <input id="senha" type="password" autoComplete="current-password" value={senha}
            onChange={(e) => setSenha(e.target.value)} required />
        </div>

        {erro && <p className="erro">{erro}</p>}

        <button type="submit" className="btn btn-primario" style={{ width: '100%' }} disabled={enviando}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
    </div>
  )
}
