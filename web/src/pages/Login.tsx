import { SITE_BASE } from '../lib/config'
import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import Toast from '../components/Toast'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'

interface EmpresaOpcaoLogin {
  idEmpresa: number
  nomeEmpresa: string
}

interface ContaOpcaoLogin {
  idTenant: number
  nomeConta: string
}

interface LoginResp {
  token: string | null
  idTenant: number
  slug: string | null
  escolherConta: boolean
  contas: ContaOpcaoLogin[]
  escolherEmpresa: boolean
  empresas: EmpresaOpcaoLogin[]
  exigeCodigo: boolean
  desafio: string | null
  emailMascarado: string | null
}

/**
 * Login do lojista: **e-mail + senha**. O campo "Loja (identificador)" saiu em 2026-08-27 —
 * ele era gerado pelo sistema no signup (o usuário nunca o escolheu, só o via num canto do
 * Painel) e ainda assim era exigido para entrar. Quem descobre a conta agora é o back
 * (`plataforma.diretorio_login`).
 *
 * Podem aparecer até duas perguntas depois da senha, nesta ordem, e as duas são raras:
 * 1. **Qual conta?** — o mesmo e-mail existe em mais de uma conta E a senha casou em mais de uma
 *    (senhas diferentes resolvem sozinhas: só uma casa e o usuário entra direto).
 * 2. **Qual empresa?** — o usuário alcança mais de uma empresa da conta (`usuario_empresa`).
 */
export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // Mensagem vinda de outra tela (hoje: "Senha alterada", enviada por RedefinirSenha ao
  // devolver o usuário para cá). Sem isto o aviso seria montado e nunca exibido.
  const avisoDeOutraTela = (location.state as { toast?: { texto: string } } | null)?.toast?.texto ?? ''
  const [sucesso, setSucesso] = useState(avisoDeOutraTela)
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState('')
  const [carregando, setCarregando] = useState(false)
  const [contas, setContas] = useState<ContaOpcaoLogin[] | null>(null)
  const [empresas, setEmpresas] = useState<EmpresaOpcaoLogin[] | null>(null)
  // Guardado entre as duas voltas: a escolha da conta precisa acompanhar a escolha da empresa.
  const [idTenantEscolhido, setIdTenantEscolhido] = useState<number | null>(null)
  // Terceira pergunta possível: o código de 4 dígitos (V079), quando o usuário tem login em duas
  // etapas ligado no cadastro dele.
  const [desafio, setDesafio] = useState<{ id: string; email: string } | null>(null)

  const entrar = async (idTenant?: number, idEmpresa?: number) => {
    setErro('')
    setCarregando(true)
    try {
      const r = await api<LoginResp>('/api/publico/login', {
        method: 'POST',
        body: JSON.stringify({ email: email.trim(), senha, idTenant, idEmpresa }),
      })
      if (r.escolherConta) {
        setContas(r.contas)
        return
      }
      if (r.escolherEmpresa) {
        setIdTenantEscolhido(idTenant ?? r.idTenant)
        setContas(null)
        setEmpresas(r.empresas)
        return
      }
      if (r.exigeCodigo) {
        setContas(null)
        setEmpresas(null)
        setDesafio({ id: r.desafio as string, email: r.emailMascarado ?? '' })
        return
      }
      login(r.token as string)
      navigate('/', { replace: true })
    } catch (err) {
      setErro(err instanceof ApiError ? err.message : 'Não foi possível entrar.')
      setContas(null)
      setEmpresas(null)
      setIdTenantEscolhido(null)
      setDesafio(null)
    } finally {
      setCarregando(false)
    }
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    entrar()
  }

  const voltarParaCredenciais = () => {
    setContas(null)
    setEmpresas(null)
    setIdTenantEscolhido(null)
    setDesafio(null)
    setSenha('')
  }

  if (desafio) {
    return (
      <CodigoCard
        emailMascarado={desafio.email}
        aoConferir={async (codigo) => {
          const r = await api<LoginResp>('/api/publico/login/codigo', {
            method: 'POST',
            body: JSON.stringify({ desafio: desafio.id, codigo }),
          })
          login(r.token as string)
          navigate('/', { replace: true })
        }}
        aoReenviar={() =>
          api<void>('/api/publico/login/codigo/reenviar', {
            method: 'POST',
            body: JSON.stringify({ desafio: desafio.id }),
          })
        }
        aoVoltar={voltarParaCredenciais}
      />
    )
  }

  if (contas) {
    return (
      <EscolhaCard
        titulo="Qual conta você quer acessar?"
        ajuda="Este e-mail está em mais de uma conta."
        opcoes={contas.map((c) => ({ chave: c.idTenant, rotulo: c.nomeConta }))}
        carregando={carregando}
        aoEscolher={(idTenant) => entrar(idTenant)}
        erro={erro}
        aoFecharErro={() => setErro('')}
        aoVoltar={voltarParaCredenciais}
      />
    )
  }

  if (empresas) {
    return (
      <EscolhaCard
        titulo="Qual empresa você quer acessar?"
        ajuda="Tudo que você cadastrar nesta sessão vai ficar registrado na empresa escolhida."
        opcoes={empresas.map((e) => ({ chave: e.idEmpresa, rotulo: e.nomeEmpresa }))}
        carregando={carregando}
        aoEscolher={(idEmpresa) => entrar(idTenantEscolhido ?? undefined, idEmpresa)}
        erro={erro}
        aoFecharErro={() => setErro('')}
        aoVoltar={voltarParaCredenciais}
      />
    )
  }

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submeter}>
        <a className="brand" href="/" style={{ fontSize: 22 }}>
          NAI<span>NER</span>
        </a>
        <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Entrar</h1>
        <p className="muted" style={{ marginTop: 0 }}>Acesse o painel do seu ERP.</p>

        <label htmlFor="email">E-mail</label>
        <input
          id="email"
          type="email"
          autoFocus
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />

        <label htmlFor="senha">Senha</label>
        <input id="senha" type="password" value={senha} onChange={(e) => setSenha(e.target.value)} required />

        {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
        {/* ⚠️ `!erro &&`: os dois Toast são `position: fixed` na MESMA coordenada, com o mesmo
          z-index — quem pinta por cima é o último do DOM, que era sempre o de sucesso. Um sucesso
          ainda na tela ESCONDIA o erro que acabava de acontecer, nunca o contrário. Na Sangria isso
          fazia o operador ler "sangria registrada" depois de uma recusa; no 2FA, ler "enviamos um
          código novo" enquanto o contador de tentativas andava para o teto. O erro vence. */}
        {!erro && sucesso && <Toast mensagem={sucesso} tipo="sucesso" aoFechar={() => setSucesso('')} />}
        <button className="btn" type="submit" disabled={carregando} style={{ width: '100%', marginTop: 12 }}>
          {carregando ? 'Entrando…' : 'Entrar'}
        </button>
        <p className="muted" style={{ fontSize: 13, marginTop: 14, textAlign: 'center' }}>
          <Link to="/esqueci-senha">Esqueci minha senha</Link>
        </p>
        <p className="muted" style={{ fontSize: 13, marginTop: 14 }}>
          Ainda não tem conta?{' '}
          {/* Texto do trial de 14 dias sobreviveu a DUAS mudanças de modelo comercial (14 → 60
              dias → plano Gratuito sem prazo, ADR-015). O endereço vem do config de runtime: o
              `localhost:5175` que estava aqui virava link morto em produção. */}
          <a href={`${SITE_BASE}/assinar`}>Crie grátis — até 100 vendas por mês</a>.
        </p>
      </form>
    </div>
  )
}

/**
 * Segunda etapa do login — a mesma moldura serve para escolher conta e para escolher empresa.
 * As duas perguntas têm o mesmo formato (uma lista de botões) e aparecem no mesmo lugar; duplicar
 * o markup faria as duas divergirem no primeiro ajuste visual.
 */
function EscolhaCard({
  titulo,
  ajuda,
  opcoes,
  carregando,
  aoEscolher,
  erro,
  aoFecharErro,
  aoVoltar,
}: {
  titulo: string
  ajuda: string
  opcoes: { chave: number; rotulo: string }[]
  carregando: boolean
  aoEscolher: (chave: number) => void
  erro: string
  aoFecharErro: () => void
  aoVoltar: () => void
}) {
  return (
    <div className="login-wrap">
      <div className="card login-card">
        <a className="brand" href="/" style={{ fontSize: 22 }}>
          NAI<span>NER</span>
        </a>
        <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>{titulo}</h1>
        <p className="muted" style={{ marginTop: 0 }}>{ajuda}</p>

        <div className="lista-categorias" style={{ marginTop: 12 }}>
          {opcoes.map((o) => (
            <button
              key={o.chave}
              type="button"
              className="btn ghost"
              style={{ width: '100%', justifyContent: 'flex-start' }}
              disabled={carregando}
              onClick={() => aoEscolher(o.chave)}
            >
              {o.rotulo}
            </button>
          ))}
        </div>

        {erro && <Toast mensagem={erro} aoFechar={aoFecharErro} />}
        <button type="button" className="btn ghost" style={{ width: '100%', marginTop: 12 }} onClick={aoVoltar}>
          Voltar
        </button>
      </div>
    </div>
  )
}

/**
 * Terceira etapa: o código de 4 dígitos que chegou por e-mail (V079).
 *
 * ⚠️ **Voltar limpa a senha.** Sair daqui é recomeçar o login inteiro — deixar a senha preenchida
 * na tela de trás faria a segunda etapa parecer opcional para quem só quer fechar o card.
 */
function CodigoCard({
  emailMascarado,
  aoConferir,
  aoReenviar,
  aoVoltar,
}: {
  emailMascarado: string
  aoConferir: (codigo: string) => Promise<void>
  aoReenviar: () => Promise<void>
  aoVoltar: () => void
}) {
  const [codigo, setCodigo] = useState('')
  const [erro, setErro] = useState('')
  const [aviso, setAviso] = useState('')
  // ⚠️ DOIS estados, não um (2026-09-01): com um `carregando` só, clicar em "Reenviar código"
  // fazia o botão de baixo dizer "Conferindo…" — a tela anunciando uma ação que não estava
  // acontecendo, bem no momento em que o usuário já não sabe se o código chegou. Cada ação diz o
  // que ela faz; as duas continuam travando a outra, que é o que evita conferir um código enquanto
  // um novo está sendo gerado.
  const [conferindo, setConferindo] = useState(false)
  const [reenviando, setReenviando] = useState(false)
  const ocupado = conferindo || reenviando

  const conferir = async (valor: string) => {
    setErro('')
    setConferindo(true)
    try {
      await aoConferir(valor)
    } catch (err) {
      setErro(err instanceof ApiError ? err.message : 'Não foi possível conferir o código.')
      setCodigo('')
    } finally {
      setConferindo(false)
    }
  }

  const reenviar = async () => {
    setErro('')
    setReenviando(true)
    try {
      await aoReenviar()
      setCodigo('')
      setAviso('Enviamos um código novo. O anterior deixou de valer.')
    } catch (err) {
      setErro(err instanceof ApiError ? err.message : 'Não foi possível reenviar o código.')
    } finally {
      setReenviando(false)
    }
  }

  return (
    <div className="login-wrap">
      <form
        className="card login-card"
        onSubmit={(e) => {
          e.preventDefault()
          if (codigo.length === 4) conferir(codigo)
        }}
      >
        <a className="brand" href="/" style={{ fontSize: 22 }}>
          NAI<span>NER</span>
        </a>
        <h1 style={{ fontSize: 22, margin: '8px 0 4px' }}>Confirme que é você</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          Enviamos um código de 4 dígitos para {emailMascarado}. Ele vale por 10 minutos.
        </p>

        <label htmlFor="codigo">Código</label>
        <input
          id="codigo"
          autoFocus
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={4}
          value={codigo}
          onChange={(e) => {
            const so = e.target.value.replace(/[^0-9]/g, '').slice(0, 4)
            setCodigo(so)
            // Digitou o quarto dígito: confere sozinho. Pedir um clique depois disso é um passo
            // que não decide nada.
            if (so.length === 4) conferir(so)
          }}
          style={{ fontSize: 28, letterSpacing: 12, textAlign: 'center', fontWeight: 700 }}
          required
        />

        {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
        {/* ⚠️ `!erro &&`: os dois Toast são `position: fixed` na MESMA coordenada, com o mesmo
          z-index — quem pinta por cima é o último do DOM, que era sempre o de sucesso. Um sucesso
          ainda na tela ESCONDIA o erro que acabava de acontecer, nunca o contrário. Na Sangria isso
          fazia o operador ler "sangria registrada" depois de uma recusa; no 2FA, ler "enviamos um
          código novo" enquanto o contador de tentativas andava para o teto. O erro vence. */}
        {!erro && aviso && <Toast mensagem={aviso} tipo="sucesso" aoFechar={() => setAviso('')} />}

        <button className="btn" type="submit" disabled={ocupado || codigo.length < 4} style={{ width: '100%', marginTop: 12 }}>
          {conferindo ? 'Conferindo…' : 'Entrar'}
        </button>
        <button type="button" className="btn ghost" style={{ width: '100%', marginTop: 8 }} disabled={ocupado} onClick={reenviar}>
          {reenviando ? 'Enviando…' : 'Reenviar código'}
        </button>
        <button type="button" className="btn ghost" style={{ width: '100%', marginTop: 8 }} onClick={aoVoltar}>
          Voltar
        </button>
      </form>
    </div>
  )
}
