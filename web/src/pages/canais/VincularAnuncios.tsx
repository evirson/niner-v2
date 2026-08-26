import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import EscolherVariacaoModal from '../../components/EscolherVariacaoModal'
import { IconeCanais } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  desvincularAnuncio,
  listarAnunciosDoCanal,
  listarVinculos,
  vincularAnuncio,
  type LinhaParaVincular,
} from '../../lib/canais'
import { formatarMoeda } from '../../lib/masks'

/**
 * Vincular anúncio ↔ produto (R6, bloco M2).
 *
 * <p>⚠️ **Cada linha é uma variação, não um anúncio.** Um anúncio do Mercado Livre com 12 tamanhos
 * vira 12 linhas — porque é a variação que se vincula, e é o saldo dela que será publicado.
 *
 * <p>⭐ **A sugestão por SKU é o que torna a tela usável.** Vincular 600 anúncios a dedo é uma
 * tarde perdida; quando o SKU do canal bate com o do ERP, a linha já vem com o produto sugerido e
 * o vínculo é um clique. Mas **sugerir não é vincular**: quem confirma é o lojista, porque um
 * vínculo criado por coincidência de texto publicaria saldo errado sem nada dizendo de onde veio.
 *
 * <p>⚠️ Esta tela **fala com o Mercado Livre** para listar os anúncios — é a única do produto que
 * depende de um terceiro estar no ar. Quando ele não está, a lista de anúncios falha (502) mas os
 * **vínculos já gravados continuam aparecendo**, e podem ser desfeitos.
 */
export default function VincularAnuncios() {
  const { idCanal: idCanalTexto } = useParams()
  const idCanal = Number(idCanalTexto)
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [aviso, setAviso] = useState<{ mensagem: string; tipo: 'erro' | 'sucesso' } | null>(null)
  const [escolhendoPara, setEscolhendoPara] = useState<LinhaParaVincular | null>(null)

  const anuncios = useQuery({
    queryKey: ['canal-anuncios', idCanal, pagina],
    queryFn: () => listarAnunciosDoCanal(idCanal, pagina),
    retry: false,
  })

  // ⭐ Consulta separada, de propósito: não depende do ML estar no ar.
  const vinculos = useQuery({
    queryKey: ['canal-vinculos', idCanal],
    queryFn: () => listarVinculos(idCanal),
  })

  function invalidarTudo() {
    queryClient.invalidateQueries({ queryKey: ['canal-anuncios', idCanal] })
    queryClient.invalidateQueries({ queryKey: ['canal-vinculos', idCanal] })
    // A tela de Canais mostra a contagem de vínculos — sem isto ela fica com o número velho.
    queryClient.invalidateQueries({ queryKey: ['canais'] })
  }

  function tratarErro(e: unknown) {
    // ⚠️ O back devolve 409/502 com a mensagem que diz o que fazer; trocá-la por um genérico
    // apagaria a única instrução útil.
    setAviso({
      mensagem: e instanceof ApiError && e.message ? e.message : 'Não foi possível falar com o servidor.',
      tipo: 'erro',
    })
  }

  const vincular = useMutation({
    mutationFn: (p: { linha: LinhaParaVincular; idVariacao: number }) =>
      vincularAnuncio(idCanal, {
        idExterno: p.linha.idExterno,
        idExternoVariacao: p.linha.idExternoVariacao,
        idVariacao: p.idVariacao,
      }),
    onSuccess: () => {
      setAviso({ mensagem: 'Anúncio vinculado.', tipo: 'sucesso' })
      setEscolhendoPara(null)
      invalidarTudo()
    },
    onError: tratarErro,
  })

  const desvincular = useMutation({
    mutationFn: (idAnuncio: number) => desvincularAnuncio(idCanal, idAnuncio),
    onSuccess: () => {
      setAviso({ mensagem: 'Vínculo desfeito.', tipo: 'sucesso' })
      invalidarTudo()
    },
    onError: tratarErro,
  })

  const linhas = anuncios.data?.linhas ?? []
  const erroDoCanal = anuncios.isError
    ? anuncios.error instanceof ApiError && anuncios.error.message
      ? anuncios.error.message
      : 'Não foi possível listar os anúncios.'
    : null

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCanais size={34} />
            <h1>Vincular Anúncios{anuncios.data ? ` — ${anuncios.data.nomeCanal}` : ''}</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="canais.anuncios.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {erroDoCanal && (
          <div className="card" style={{ marginBottom: 16 }}>
            <strong>Não foi possível listar os anúncios do Mercado Livre.</strong>
            <p className="muted" style={{ marginBottom: 0 }}>
              {erroDoCanal} Os vínculos que você já fez continuam abaixo e podem ser desfeitos.
            </p>
          </div>
        )}

        {anuncios.isFetching && <p className="muted">Consultando o Mercado Livre…</p>}

        {!anuncios.isFetching && !erroDoCanal && linhas.length === 0 && (
          <p className="muted">Nenhum anúncio encontrado nesta página da conta conectada.</p>
        )}

        {linhas.length > 0 && (
          <table className="tabela">
            <thead>
              <tr>
                <th>Anúncio no canal</th>
                <th>Variação do canal</th>
                <th>SKU no canal</th>
                <th style={{ textAlign: 'right' }}>Qtd. lá</th>
                <th style={{ textAlign: 'right' }}>Preço lá</th>
                <th>Produto do ERP</th>
                <th>Ações</th>
              </tr>
            </thead>
            <tbody>
              {linhas.map((l) => (
                <tr key={`${l.idExterno} ${l.idExternoVariacao ?? ''}`}>
                  <td>
                    {l.titulo}
                    <br />
                    <span className="muted mono">{l.idExterno}</span>
                  </td>
                  <td>{l.descricaoVariacao ?? <span className="muted">—</span>}</td>
                  <td className="mono">{l.sku ?? '—'}</td>
                  <td style={{ textAlign: 'right' }}>{l.quantidadeNoCanal}</td>
                  <td style={{ textAlign: 'right' }}>
                    {l.precoNoCanal === null ? '—' : formatarMoeda(l.precoNoCanal)}
                  </td>
                  <td>
                    {l.idVariacao ? (
                      <span className="badge badge-ok">{l.descricaoVariacaoErp}</span>
                    ) : l.idVariacaoSugerida ? (
                      <>
                        <span className="muted">Sugerido pelo SKU:</span>
                        <br />
                        {l.descricaoSugerida}
                      </>
                    ) : (
                      <span className="muted">Não vinculado</span>
                    )}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {l.idAnuncio ? (
                        <button
                          type="button"
                          className="btn ghost"
                          disabled={desvincular.isPending}
                          onClick={() => desvincular.mutate(l.idAnuncio as number)}
                        >
                          Desvincular
                        </button>
                      ) : (
                        <>
                          {l.idVariacaoSugerida && (
                            <button
                              type="button"
                              className="btn"
                              disabled={vincular.isPending}
                              onClick={() =>
                                vincular.mutate({ linha: l, idVariacao: l.idVariacaoSugerida as number })
                              }
                            >
                              Vincular ao sugerido
                            </button>
                          )}
                          <button
                            type="button"
                            className="btn ghost"
                            onClick={() => setEscolhendoPara(l)}
                          >
                            Escolher produto…
                          </button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* ⚠️ Sem número de páginas: a origem é o marketplace, e a busca de itens do ML não dá um
            total confiável. Inventar um faria a tela mentir sobre quantas páginas existem. */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 12 }}>
          <button
            type="button"
            className="btn ghost"
            disabled={pagina <= 1 || anuncios.isFetching}
            onClick={() => setPagina((p) => Math.max(1, p - 1))}
          >
            ← Anterior
          </button>
          <span className="muted">Página {pagina}</span>
          <button
            type="button"
            className="btn ghost"
            disabled={linhas.length === 0 || anuncios.isFetching}
            onClick={() => setPagina((p) => p + 1)}
          >
            Próxima →
          </button>
        </div>

        {(vinculos.data ?? []).length > 0 && (
          <section style={{ marginTop: 28 }}>
            <h2>Vínculos deste canal ({vinculos.data?.length})</h2>
            <p className="muted">
              Esta lista vem do ERP e não depende do Mercado Livre estar no ar. O preço é o que este
              canal publica — nasce do preço de venda da loja mais o ajuste do canal.
            </p>
            <table className="tabela">
              <thead>
                <tr>
                  <th>Produto</th>
                  <th>Anúncio</th>
                  <th style={{ textAlign: 'right' }}>Preço no canal</th>
                  <th>Sincronização</th>
                </tr>
              </thead>
              <tbody>
                {(vinculos.data ?? []).map((v) => (
                  <tr key={v.idAnuncio}>
                    <td>
                      {v.descricaoVariacaoErp}
                      <br />
                      <span className="muted mono">{v.sku}</span>
                    </td>
                    <td className="mono">
                      {v.idExterno}
                      {v.idExternoVariacao ? ` · ${v.idExternoVariacao}` : ''}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {formatarMoeda(v.preco)}
                      {v.precoManual && <span className="muted"> (digitado)</span>}
                    </td>
                    <td>
                      <span className={v.statusSync === 'ERRO' ? 'badge badge-erro' : 'badge'}>
                        {v.statusSync}
                      </span>
                      {v.ultimoErro && <div className="muted">{v.ultimoErro}</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </div>

      {escolhendoPara && (
        <EscolherVariacaoModal
          titulo={`Vincular “${escolhendoPara.titulo}”${
            escolhendoPara.descricaoVariacao ? ` — ${escolhendoPara.descricaoVariacao}` : ''
          }`}
          aoFechar={() => setEscolhendoPara(null)}
          aoEscolher={(v) => vincular.mutate({ linha: escolhendoPara, idVariacao: v.idVariacao })}
        />
      )}

      {aviso && <Toast mensagem={aviso.mensagem} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}
    </div>
  )
}
