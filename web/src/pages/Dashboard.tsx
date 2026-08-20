import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useEu } from '../lib/eu'

/** Painel inicial: dá as boas-vindas e mostra o estado da conta/assinatura (via /api/v1/eu). */
export default function Dashboard() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data, isLoading, error } = useEu()

  useEffect(() => {
    if (error instanceof ApiError && error.status === 401) {
      logout()
      navigate('/login', { replace: true })
    }
  }, [error, logout, navigate])

  if (isLoading) return <p className="muted">Carregando…</p>
  if (error) return <p className="erro">Não foi possível carregar seus dados.</p>
  if (!data) return null

  // O painel mostrava selo de TRIAL e "Teste até <data>" — cópia de dois modelos comerciais
  // atrás (14 dias → 60 dias → plano Gratuito sem prazo, ADR-015). Hoje o que importa é a cota.
  const plano = data.plano
  const usaCota = plano?.limite_vendas_mes != null
  const percentual = usaCota ? Math.min(100, Math.round((plano!.vendas_no_mes / plano!.limite_vendas_mes!) * 100)) : 0

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
          <span className="badge">{plano?.nome ?? data.conta.status}</span>
          {usaCota && (
            <>
              <p className="muted" style={{ marginTop: 10 }}>
                <strong>{plano!.vendas_no_mes}</strong> de {plano!.limite_vendas_mes} vendas neste mês
                {plano!.gratuito ? ' — o plano gratuito não expira.' : '.'}
              </p>
              <div className="barra-cota" role="img" aria-label={`${percentual}% da cota do mês`}>
                <div className={`barra-cota-preenchida${percentual >= 80 ? ' atencao' : ''}`}
                  style={{ width: `${percentual}%` }} />
              </div>
            </>
          )}
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
        {/* A lista antiga prometia canais de marketplace, que não existem no produto ainda —
            promessa que o lojista cobra. Estes quatro passos são o que ele consegue fazer hoje. */}
        <ol className="passos">
          <li>Cadastrar seus produtos, com cor e tamanho quando for o caso.</li>
          <li>Ajustar o estoque inicial de cada item.</li>
          <li>Abrir o caixa e registrar a primeira venda no PDV.</li>
          <li>Configurar o fiscal para emitir NFC-e direto da venda.</li>
        </ol>
      </section>
    </div>
  )
}
