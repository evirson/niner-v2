import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

interface Eu {
  id_tenant: number
  conta: { nomeConta: string; slug: string; status: string }
  usuario: { nome: string; email: string; papel: string }
  trial_expira_em: string | null
}

/** Painel inicial: dá as boas-vindas e mostra o estado da conta/assinatura (via /api/v1/eu). */
export default function Dashboard() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data, isLoading, error } = useQuery({
    queryKey: ['eu'],
    queryFn: () => api<Eu>('/api/v1/eu'),
    retry: false,
  })

  useEffect(() => {
    if (error instanceof ApiError && error.status === 401) {
      logout()
      navigate('/login', { replace: true })
    }
  }, [error, logout, navigate])

  if (isLoading) return <p className="muted">Carregando…</p>
  if (error) return <p className="erro">Não foi possível carregar seus dados.</p>
  if (!data) return null

  const trial = data.trial_expira_em ? new Date(data.trial_expira_em).toLocaleDateString('pt-BR') : null

  return (
    <div>
      <p className="eyebrow">Painel</p>
      <h1 style={{ marginTop: 4 }}>Olá, {data.usuario.nome.split(' ')[0]} 👋</h1>

      <div className="grid">
        <section className="card">
          <h2 className="card-title">Sua loja</h2>
          <p className="big">{data.conta.nomeConta}</p>
          <p className="muted">
            Identificador: <code>{data.conta.slug}</code>
          </p>
          <span className={`badge ${data.conta.status === 'TRIAL' ? 'badge-trial' : ''}`}>{data.conta.status}</span>
          {trial && <p className="muted" style={{ marginTop: 10 }}>Teste até <strong>{trial}</strong>.</p>}
        </section>

        <section className="card">
          <h2 className="card-title">Você</h2>
          <p className="big">{data.usuario.nome}</p>
          <p className="muted">{data.usuario.email}</p>
          <span className="badge">{data.usuario.papel}</span>
        </section>
      </div>

      <section className="card" style={{ marginTop: 20 }}>
        <h2 className="card-title">Próximos passos</h2>
        <ol className="passos">
          <li>Cadastrar seus produtos e variações (SKU/EAN).</li>
          <li>Ajustar o estoque inicial de cada item.</li>
          <li>Conectar um canal de venda (Mercado Livre, Shopee…).</li>
          <li>Importar e acompanhar seus pedidos numa fila única.</li>
        </ol>
        <p className="muted" style={{ fontSize: 13 }}>
          Estas áreas estão em construção — em breve disponíveis no menu ao lado.
        </p>
      </section>
    </div>
  )
}
