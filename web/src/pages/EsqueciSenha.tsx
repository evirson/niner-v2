import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import Toast from '../components/Toast'
import { api, ApiError } from '../lib/api'

/**
 * "Esqueci minha senha" — pede só o **e-mail** (2026-08-27).
 *
 * ⚠️ **A tela responde a mesma coisa em todos os casos**, exista ou não a conta. Não é descuido de
 * mensagem: a API devolve 204 sempre, de propósito, para o endpoint não virar uma lista de quem é
 * cliente do Nainer. Por isso o texto de sucesso fala no condicional ("se houver uma conta") em vez
 * de afirmar que o e-mail foi enviado — afirmar seria mentir para metade dos casos.
 *
 * ⚠️ Quando o mesmo e-mail está em mais de uma conta, chega **um** e-mail com **um link por
 * conta**, cada um nomeando a conta. Quem monta isso é o back (`RecuperacaoSenhaService`).
 */
export default function EsqueciSenha() {
  const [email, setEmail] = useState('')
  const [enviado, setEnviado] = useState(false)
  const [erro, setErro] = useState('')
  const [carregando, setCarregando] = useState(false)

  const submeter = async (e: FormEvent) => {
    e.preventDefault()
    setErro('')
    setCarregando(true)
    try {
      await api<void>('/api/publico/recuperar-senha', {
        method: 'POST',
        body: JSON.stringify({ email: email.trim() }),
      })
      setEnviado(true)
    } catch (err) {
      // Só chega aqui se a própria API falhou (rede, 500, limite de requisição) — conta
      // inexistente responde 204 como qualquer outra.
      setErro(err instanceof ApiError ? err.message : 'Não foi possível enviar o e-mail agora.')
    } finally {
      setCarregando(false)
    }
  }

  if (enviado) {
    return (
      <div className="login-wrap">
        <div className="card login-card">
          <span className="brand" style={{ fontSize: 22 }}>
            NAI<span>NER</span>
          </span>
          <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Confira seu e-mail</h1>
          <p className="muted" style={{ marginTop: 0 }}>
            Se houver uma conta com <b>{email.trim()}</b>, o link de redefinição chega em alguns
            instantes. Ele vale por 2 horas e só pode ser usado uma vez.
          </p>
          <p className="muted" style={{ fontSize: 13 }}>
            Não chegou? Confira a caixa de spam antes de pedir de novo — pedir um link novo cancela
            o anterior.
          </p>
          <Link className="btn" to="/login" style={{ width: '100%', marginTop: 12 }}>
            Voltar para a entrada
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
        <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Esqueci minha senha</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          Digite o e-mail que você usa para entrar. Enviaremos um link para você criar uma senha
          nova.
        </p>

        <label htmlFor="email">E-mail</label>
        <input
          id="email"
          type="email"
          autoFocus
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />

        {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
        <button className="btn" type="submit" disabled={carregando} style={{ width: '100%', marginTop: 12 }}>
          {carregando ? 'Enviando…' : 'Enviar link'}
        </button>
        <p className="muted" style={{ fontSize: 13, marginTop: 14 }}>
          <Link to="/login">Voltar para a entrada</Link>
        </p>
      </form>
    </div>
  )
}
