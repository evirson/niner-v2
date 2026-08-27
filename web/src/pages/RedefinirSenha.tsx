import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import Toast from '../components/Toast'
import { api, ApiError } from '../lib/api'

const MINIMO = 8

/**
 * Redefinição de senha pelo link do e-mail (`/redefinir-senha?token=…`), 2026-08-27.
 *
 * ⚠️ **Esta rota é o destino de um link que já estava sendo enviado.** O
 * `RecuperacaoSenhaService` monta `{web-base-url}/redefinir-senha?token=…` desde que nasceu, mas a
 * rota nunca existiu no `web/` — quem clicasse caía numa tela inexistente. Só apareceu em
 * 2026-08-27, quando o SMTP foi configurado e o primeiro e-mail de verdade saiu. **Se o caminho
 * desta rota mudar, o link dos e-mails já enviados quebra** — ele vive 2 horas na caixa de
 * entrada de alguém.
 */
export default function RedefinirSenha() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const token = params.get('token') ?? ''

  const [senha, setSenha] = useState('')
  const [confirmacao, setConfirmacao] = useState('')
  const [erro, setErro] = useState('')
  const [carregando, setCarregando] = useState(false)

  const submeter = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    if (senha.length < MINIMO) {
      setErro(`A senha precisa ter pelo menos ${MINIMO} caracteres.`)
      return
    }
    if (senha !== confirmacao) {
      setErro('As duas senhas não são iguais.')
      return
    }
    setCarregando(true)
    try {
      await api<void>('/api/publico/redefinir-senha', {
        method: 'POST',
        body: JSON.stringify({ token, novaSenha: senha }),
      })
      // A API não devolve token: quem redefiniu a senha entra de novo, com ela.
      navigate('/login', {
        replace: true,
        state: { toast: { texto: 'Senha alterada. Entre com a senha nova.', tipo: 'sucesso' } },
      })
    } catch (err) {
      setErro(err instanceof ApiError ? err.message : 'Não foi possível alterar a senha agora.')
    } finally {
      setCarregando(false)
    }
  }

  if (!token) {
    return (
      <div className="login-wrap">
        <div className="card login-card">
          <span className="brand" style={{ fontSize: 22 }}>
            NAI<span>NER</span>
          </span>
          <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Link incompleto</h1>
          <p className="muted" style={{ marginTop: 0 }}>
            Este endereço não traz o código de redefinição. Abra o link direto do e-mail, sem
            copiar pela metade, ou peça um novo.
          </p>
          <Link className="btn" to="/esqueci-senha" style={{ width: '100%', marginTop: 12 }}>
            Pedir um link novo
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submeter}>
        <span className="brand" style={{ fontSize: 22 }}>
          NAI<span>NER</span>
        </span>
        <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Criar senha nova</h1>
        <p className="muted" style={{ marginTop: 0 }}>Mínimo de {MINIMO} caracteres.</p>

        <label htmlFor="senha">Nova senha</label>
        <input
          id="senha"
          type="password"
          autoFocus
          value={senha}
          onChange={(e) => setSenha(e.target.value)}
          required
        />

        <label htmlFor="confirmacao">Repita a nova senha</label>
        <input
          id="confirmacao"
          type="password"
          value={confirmacao}
          onChange={(e) => setConfirmacao(e.target.value)}
          required
        />

        {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
        <button className="btn" type="submit" disabled={carregando} style={{ width: '100%', marginTop: 12 }}>
          {carregando ? 'Salvando…' : 'Salvar senha'}
        </button>
        <p className="muted" style={{ fontSize: 13, marginTop: 14 }}>
          <Link to="/esqueci-senha">Pedir um link novo</Link> · <Link to="/login">Voltar para a entrada</Link>
        </p>
      </form>
    </div>
  )
}
