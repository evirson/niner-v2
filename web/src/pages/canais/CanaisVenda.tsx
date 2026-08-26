import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeCanais } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  atualizarCanal,
  buscarSaudeCanais,
  criarCanal,
  desconectarCanal,
  excluirCanal,
  iniciarConexaoMercadoLivre,
  listarCanais,
  reprocessarEvento,
  type Canal,
} from '../../lib/canais'
import { completarPercentual, desmascararPercentual, mascararPercentual } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

function formatarDataHora(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const ROTULO_STATUS: Record<Canal['status'], string> = {
  CONECTADO: 'Conectado',
  DESCONECTADO: 'Desconectado',
  ERRO: 'Com erro',
}

function classeStatus(status: Canal['status']): string {
  if (status === 'CONECTADO') return 'badge badge-ok'
  if (status === 'ERRO') return 'badge badge-erro'
  return 'badge'
}

/**
 * Canais de Venda (R7) — conexão com marketplaces e saúde da sincronização.
 *
 * <p>Não é tela de cadastro: não tem paginação nem popup de filtros. Segue o mesmo desvio do
 * padrão de `FiscalContingenciaPainel` — cards de estado e uma lista de problemas.
 *
 * <p>✅ **Conectar existe desde o bloco M1** (2026-08-26). O botão leva o lojista ao consentimento
 * do Mercado Livre; quem termina é o servidor, que redireciona de volta para cá com o resultado
 * na query string — ver o `useEffect` da volta.
 *
 * <p>⚠️ **A tela não vê token nenhum.** A credencial é cifrada e nunca volta pela API: o que
 * aparece aqui é a situação e `contaExterna` (o id público do vendedor no ML).
 */
export default function CanaisVenda() {
  const queryClient = useQueryClient()
  const [aviso, setAviso] = useState<{ mensagem: string; tipo: 'erro' | 'sucesso' } | null>(null)
  const [emEdicao, setEmEdicao] = useState<number | null>(null)
  const [nome, setNome] = useState('')
  const [percTexto, setPercTexto] = useState('0,00')
  const [criando, setCriando] = useState(false)
  const [params, setParams] = useSearchParams()

  const { data: canais, isLoading } = useQuery({ queryKey: ['canais'], queryFn: listarCanais })
  const { data: saude } = useQuery({
    queryKey: ['canais-saude'],
    queryFn: buscarSaudeCanais,
    // A fila anda sozinha (worker do outbox a cada 30 s). Sem refetch, o ADMIN só veria a
    // mudança recarregando a página na mão.
    refetchInterval: 30_000,
  })

  function invalidarTudo() {
    queryClient.invalidateQueries({ queryKey: ['canais'] })
    queryClient.invalidateQueries({ queryKey: ['canais-saude'] })
  }

  function tratarErro(e: unknown) {
    // ⚠️ Distinguir erro de aplicação de falha de transporte: o back devolve 409 com a mensagem
    // que diz o que fazer (ex.: desligar "permite estoque negativo"), e trocá-la por um genérico
    // apagaria a única instrução útil.
    setAviso({
      mensagem: e instanceof ApiError && e.message ? e.message : 'Não foi possível falar com o servidor.',
      tipo: 'erro',
    })
  }

  const salvar = useMutation({
    mutationFn: async () => {
      const payload = { nome: maiusculas(nome), percPreco: desmascararPercentual(percTexto) }
      if (emEdicao === null) return criarCanal('MERCADO_LIVRE', payload)
      return atualizarCanal(emEdicao, payload)
    },
    onSuccess: () => {
      setAviso({ mensagem: 'Canal salvo.', tipo: 'sucesso' })
      setCriando(false)
      setEmEdicao(null)
      invalidarTudo()
    },
    onError: tratarErro,
  })

  /**
   * Conectar (bloco M1).
   *
   * ⚠️ Sai desta aba com `location.assign`, e não numa janela nova: o Mercado Livre devolve o
   * navegador para o servidor, que redireciona de volta para cá — abrir popup faria o retorno
   * cair numa janela órfã, sem a tela que precisa mostrar o resultado.
   */
  const conectar = useMutation({
    mutationFn: (idCanal: number) => iniciarConexaoMercadoLivre(idCanal),
    onSuccess: ({ url }) => window.location.assign(url),
    onError: tratarErro,
  })

  const desconectar = useMutation({
    mutationFn: (idCanal: number) => desconectarCanal(idCanal),
    onSuccess: () => {
      setAviso({ mensagem: 'Canal desconectado. Os vínculos de anúncio foram mantidos.', tipo: 'sucesso' })
      invalidarTudo()
    },
    onError: tratarErro,
  })

  const excluir = useMutation({
    mutationFn: (idCanal: number) => excluirCanal(idCanal),
    onSuccess: () => {
      setAviso({ mensagem: 'Canal excluído.', tipo: 'sucesso' })
      invalidarTudo()
    },
    onError: tratarErro,
  })

  const reprocessar = useMutation({
    mutationFn: (idEvento: number) => reprocessarEvento(idEvento),
    onSuccess: () => {
      setAviso({ mensagem: 'Evento devolvido à fila.', tipo: 'sucesso' })
      invalidarTudo()
    },
    onError: tratarErro,
  })

  /**
   * A volta do Mercado Livre.
   *
   * O servidor redireciona para cá com `?canal=N&conectado=…` ou `?canal=N&erro=…`. ⚠️ A query
   * string é **limpa** logo depois: sem isso, um F5 na tela repetiria o aviso de "conectado" para
   * sempre, e o lojista acabaria acreditando numa conexão que talvez já tenha sido desfeita.
   *
   * ⚠️ E invalida as consultas: quem conectou foi o servidor, então o cache do React Query desta
   * aba ainda tem o canal como desconectado.
   */
  useEffect(() => {
    const conectado = params.get('conectado')
    const erro = params.get('erro')
    if (!conectado && !erro) return

    setAviso({ mensagem: conectado ?? erro ?? '', tipo: conectado ? 'sucesso' : 'erro' })
    invalidarTudo()

    const limpos = new URLSearchParams(params)
    limpos.delete('conectado')
    limpos.delete('erro')
    limpos.delete('canal')
    setParams(limpos, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params])

  function abrirCriacao() {
    setCriando(true)
    setEmEdicao(null)
    setNome('')
    setPercTexto('0,00')
  }

  function abrirEdicao(canal: Canal) {
    setCriando(true)
    setEmEdicao(canal.idCanal)
    setNome(canal.nome)
    setPercTexto(mascararPercentual(String(canal.percPreco).replace('.', ',')))
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCanais size={34} />
            <h1>Canais de Venda</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="canais.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {/* ⚠️ O aviso da comissão é obrigatório, não decorativo: o percentual nasce 0 (mesmo preço
            da loja) porque não é papel do sistema arbitrar a margem do lojista — e este texto é o
            que substitui o palpite que decidimos não dar. Ver MODULOMARKETPLACE.md §8.5. */}
        <div className="card" style={{ marginBottom: 16 }}>
          <strong>Antes de conectar, confira o ajuste de preço.</strong>
          <p className="muted" style={{ marginBottom: 0 }}>
            O Mercado Livre cobra comissão por venda (em geral entre 11% e 19%), além do frete.
            Com o ajuste em <strong>0%</strong>, o anúncio sai pelo mesmo preço do balcão e essa
            comissão sai inteira da sua margem. O ajuste aceita valor <em>negativo</em>, se você
            quiser vender mais barato no marketplace do que na loja.
          </p>
        </div>

        {/* Painel de saúde (R7) */}
        <section style={{ marginBottom: 20 }}>
          <h2 style={{ marginTop: 0 }}>Saúde da sincronização</h2>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
            {/* ⚠️ Três números separados, nunca somados: significam coisas diferentes. */}
            <div className="card" style={{ minWidth: 150 }}>
              <div className="muted">Na fila</div>
              <strong style={{ fontSize: 24 }}>{saude?.pendentes ?? 0}</strong>
              <div className="muted">normal — sai em segundos</div>
            </div>
            <div className="card" style={{ minWidth: 150 }}>
              <div className="muted">Tentando de novo</div>
              <strong style={{ fontSize: 24 }}>{saude?.comErro ?? 0}</strong>
              <div className="muted">o sistema reenvia sozinho</div>
            </div>
            <div className="card" style={{ minWidth: 150 }}>
              <div className="muted">Parados</div>
              <strong style={{ fontSize: 24 }}>{saude?.deadLetter ?? 0}</strong>
              <div className="muted">precisam de você</div>
            </div>
          </div>

          {saude && saude.falhas.length > 0 && (
            <table className="grade">
              <thead>
                <tr>
                  <th>Situação</th>
                  <th>Operação</th>
                  <th>Item</th>
                  <th>Tentativas</th>
                  <th>Motivo</th>
                  <th>Próxima tentativa</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {saude.falhas.map((f) => (
                  <tr key={f.id}>
                    <td>
                      <span className={f.status === 'DEAD_LETTER' ? 'badge badge-erro' : 'badge badge-aviso'}>
                        {f.status === 'DEAD_LETTER' ? 'Parado' : 'Tentando'}
                      </span>
                    </td>
                    <td className="mono">{f.tipo}</td>
                    <td className="mono">{f.agregadoId ?? '—'}</td>
                    <td>{f.tentativas}</td>
                    {/* ⚠️ Mensagem crua do canal, sem tradução: é a pista que o suporte pesquisa. */}
                    <td style={{ maxWidth: 320 }}>{f.erro ?? '—'}</td>
                    <td>{f.status === 'DEAD_LETTER' ? '—' : formatarDataHora(f.proximoRetry)}</td>
                    <td>
                      <button
                        type="button"
                        className="btn ghost"
                        disabled={reprocessar.isPending}
                        onClick={() => reprocessar.mutate(f.id)}
                      >
                        Reprocessar
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {saude && saude.falhas.length === 0 && (
            <p className="muted">Nenhuma sincronização com problema.</p>
          )}
        </section>

        {/* Canais */}
        <section>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h2 style={{ marginTop: 0 }}>Canais</h2>
            <button type="button" className="btn" onClick={abrirCriacao}>
              + Novo Canal
            </button>
          </div>

          {criando && (
            <div className="card" style={{ marginBottom: 12 }}>
              <h3 style={{ marginTop: 0 }}>{emEdicao === null ? 'Novo canal' : 'Editar canal'}</h3>
              <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
                <div>
                  <label htmlFor="canal-nome">Nome</label>
                  <input
                    id="canal-nome"
                    autoFocus
                    value={nome}
                    maxLength={60}
                    onChange={(e) => setNome(maiusculas(e.target.value))}
                  />
                </div>
                <div>
                  <label htmlFor="canal-perc">Ajuste de preço (%)</label>
                  <input
                    id="canal-perc"
                    inputMode="decimal"
                    value={percTexto}
                    onFocus={(e) => e.target.select()}
                    onChange={(e) => setPercTexto(mascararPercentual(e.target.value))}
                    onBlur={(e) => setPercTexto(completarPercentual(e.target.value))}
                  />
                </div>
                <button type="button" className="btn" disabled={salvar.isPending} onClick={() => salvar.mutate()}>
                  {salvar.isPending ? 'Salvando…' : 'Salvar'}
                </button>
                <button type="button" className="btn ghost" onClick={() => setCriando(false)}>
                  Cancelar
                </button>
              </div>
              {/* ⚠️ O aviso que substitui o palpite que decidimos NÃO dar: o padrão é 0 (mesmo
                  preço da loja), e sem este texto o lojista venderia a preço de balcão perdendo
                  a comissão do canal em cada venda. */}
              <p className="muted" style={{ marginBottom: 0 }}>
                O preço do anúncio nasce do preço de venda da loja mais este percentual. Use um valor
                positivo para cobrir a comissão do marketplace (no Mercado Livre, de 11% a 19%
                conforme a categoria), ou negativo para vender mais barato que na loja física.
                Com <strong>0</strong>, o anúncio sai pelo mesmo preço do balcão.
              </p>
            </div>
          )}

          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : !canais || canais.length === 0 ? (
            <p className="muted">Nenhum canal cadastrado.</p>
          ) : (
            <table className="grade">
              <thead>
                <tr>
                  <th>Canal</th>
                  <th>Nome</th>
                  <th>Situação</th>
                  <th>Conta</th>
                  <th>Ajuste de preço</th>
                  <th>Anúncios</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {canais.map((c) => (
                  <tr key={c.idCanal}>
                    <td>{c.tipoRotulo}</td>
                    <td>{c.nome}</td>
                    <td>
                      <span className={classeStatus(c.status)}>{ROTULO_STATUS[c.status]}</span>
                    </td>
                    <td className="mono">{c.contaExterna ?? '—'}</td>
                    <td className="mono">
                      {c.percPreco > 0 ? '+' : ''}
                      {String(c.percPreco).replace('.', ',')}%
                    </td>
                    <td>{c.anunciosVinculados}</td>
                    <td>
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        <button type="button" className="btn ghost" onClick={() => abrirEdicao(c)}>
                          Editar
                        </button>
                        {/* Conectar aparece também em ERRO: é justamente o caso de autorização
                            revogada ou expirada, cujo conserto é reconectar. */}
                        {c.status !== 'CONECTADO' && c.tipo === 'MERCADO_LIVRE' && (
                          <button
                            type="button"
                            className="btn"
                            disabled={conectar.isPending}
                            onClick={() => conectar.mutate(c.idCanal)}
                          >
                            {c.status === 'ERRO' ? 'Reconectar' : 'Conectar'}
                          </button>
                        )}
                        {c.status === 'CONECTADO' && (
                          <button
                            type="button"
                            className="btn ghost"
                            disabled={desconectar.isPending}
                            onClick={() => desconectar.mutate(c.idCanal)}
                          >
                            Desconectar
                          </button>
                        )}
                        {/* Excluir só aparece sem vínculo: o back recusa, e oferecer uma ação que
                            vai falhar é pior que não oferecer. */}
                        {c.anunciosVinculados === 0 && (
                          <button
                            type="button"
                            className="btn ghost"
                            disabled={excluir.isPending}
                            onClick={() => excluir.mutate(c.idCanal)}
                          >
                            Excluir
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>

      {aviso && <Toast mensagem={aviso.mensagem} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}
    </div>
  )
}
