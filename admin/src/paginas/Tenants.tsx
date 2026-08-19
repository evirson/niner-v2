import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { NUMERO, REAL, data, dataHora } from '../lib/api'
import { buscarTenant, listarTenants } from '../lib/plataforma'

const STATUS = ['', 'ATIVA', 'INADIMPLENTE', 'SUSPENSA', 'CANCELADA']

/** Contas assinantes (R17): quem é o cliente e quanto ele está usando, na mesma linha. */
export default function Tenants() {
  const [params, setParams] = useSearchParams()
  const [busca, setBusca] = useState(params.get('busca') ?? '')
  const [status, setStatus] = useState('')
  const [pagina, setPagina] = useState(1)
  const [aberta, setAberta] = useState<number | null>(null)

  const filtroBusca = params.get('busca') ?? ''
  const { data: lista, isLoading } = useQuery({
    queryKey: ['tenants', filtroBusca, status, pagina],
    queryFn: () => listarTenants({ busca: filtroBusca || undefined, status: status || undefined, pagina }),
  })

  return (
    <>
      <div className="topo-pagina">
        <h1>Contas</h1>
        <div className="acoes">
          <input placeholder="Nome, slug ou e-mail" value={busca} style={{ width: 240 }}
            onChange={(e) => setBusca(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setPagina(1)
                setParams(busca ? { busca } : {})
              }
            }} />
          <select value={status} onChange={(e) => { setStatus(e.target.value); setPagina(1) }} style={{ width: 170 }}>
            {STATUS.map((s) => (
              <option key={s} value={s}>{s === '' ? 'Todos os status' : s}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="card tabela-rolagem" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Conta</th>
              <th>Plano</th>
              <th className="num">Uso do mês</th>
              <th style={{ width: 130 }}>Consumo</th>
              <th className="num">CNPJs</th>
              <th className="num">MRR</th>
              <th>Criada</th>
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={7} className="muted">Carregando…</td></tr>
            )}
            {lista?.itens.length === 0 && (
              <tr><td colSpan={7} className="muted">Nenhuma conta encontrada.</td></tr>
            )}
            {lista?.itens.map((t) => (
              <tr key={t.idTenant} onClick={() => setAberta(aberta === t.idTenant ? null : t.idTenant)}
                style={{ cursor: 'pointer' }}>
                <td>
                  <div>{t.nomeConta}</div>
                  <div className="muted" style={{ fontSize: 12 }}>{t.emailContato}</div>
                </td>
                <td>
                  {t.plano ?? '—'} <Situacao status={t.status} />
                </td>
                <td className="num">
                  {NUMERO.format(t.vendasNoMes)}
                  {t.limiteVendasMes ? `/${NUMERO.format(t.limiteVendasMes)}` : ''}
                </td>
                <td><Consumo percentual={t.percentualCota} /></td>
                <td className="num">{t.qtdEmpresas}</td>
                <td className="num">{REAL.format(t.mrr ?? 0)}</td>
                <td className="muted">{dataHora(t.criadoEm)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {lista && lista.total > lista.limite && (
        <div className="acoes" style={{ marginTop: 12 }}>
          <button className="btn btn-secundario" disabled={pagina <= 1} onClick={() => setPagina((p) => p - 1)}>
            Anterior
          </button>
          <span className="muted">
            Página {lista.pagina} · {NUMERO.format(lista.total)} conta(s)
          </span>
          <button className="btn btn-secundario" disabled={pagina * lista.limite >= lista.total}
            onClick={() => setPagina((p) => p + 1)}>
            Próxima
          </button>
        </div>
      )}

      {aberta !== null && <Ficha idTenant={aberta} aoFechar={() => setAberta(null)} />}
    </>
  )
}

function Situacao({ status }: { status: string }) {
  const classe = status === 'ATIVA' ? 'tag-ok' : status === 'CANCELADA' || status === 'SUSPENSA' ? 'tag-perigo' : 'tag-neutro'
  return <span className={`tag ${classe}`} style={{ marginLeft: 6 }}>{status}</span>
}

function Consumo({ percentual }: { percentual: number | null }) {
  if (percentual === null) return <span className="muted">ilimitado</span>
  const classe = percentual >= 100 ? 'perigo' : percentual >= 80 ? 'atencao' : ''
  return (
    <div className={`barra ${classe}`} title={`${percentual}%`}>
      <span style={{ width: `${Math.min(100, percentual)}%` }} />
    </div>
  )
}

/** Ficha da conta: o que o suporte precisa numa ligação — uso, faturas e os CNPJs. */
function Ficha({ idTenant, aoFechar }: { idTenant: number; aoFechar: () => void }) {
  const { data: detalhe, isLoading } = useQuery({
    queryKey: ['tenant', idTenant],
    queryFn: () => buscarTenant(idTenant),
  })

  return (
    <section className="card secao">
      <div className="topo-pagina" style={{ marginBottom: 12 }}>
        <h2>{isLoading ? 'Carregando…' : detalhe?.resumo.nomeConta}</h2>
        <button className="btn btn-secundario" onClick={aoFechar}>Fechar</button>
      </div>

      {detalhe && (
        <>
          <p className="muted">
            {detalhe.resumo.slug} · {detalhe.resumo.emailContato} · {detalhe.resumo.qtdUsuarios} usuário(s)
          </p>

          <div className="linha" style={{ marginTop: 16 }}>
            <div>
              <h3 style={{ fontSize: 15, marginBottom: 8 }}>Uso mensal</h3>
              {detalhe.historico.length === 0 ? (
                <p className="muted">Sem mês fechado ainda.</p>
              ) : (
                <table>
                  <tbody>
                    {detalhe.historico.map((h) => (
                      <tr key={h.competencia}>
                        <td>{data(h.competencia)}</td>
                        <td className="num">{NUMERO.format(h.qtdVendas)} vendas</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div>
              <h3 style={{ fontSize: 15, marginBottom: 8 }}>Faturas</h3>
              {detalhe.faturas.length === 0 ? (
                <p className="muted">Nenhuma fatura — conta gratuita.</p>
              ) : (
                <table>
                  <tbody>
                    {detalhe.faturas.map((f) => (
                      <tr key={f.idFatura}>
                        <td>{data(f.competencia)}</td>
                        <td>{f.plano}</td>
                        <td className="num">{REAL.format(f.valor)}</td>
                        <td>
                          <span className={`tag ${f.situacao === 'PAGA' ? 'tag-ok' : 'tag-neutro'}`}>{f.situacao}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>

          <h3 style={{ fontSize: 15, margin: '18px 0 8px' }}>Empresas ({detalhe.empresas.length})</h3>
          <table>
            <tbody>
              {detalhe.empresas.map((e) => (
                <tr key={e.codigoEmpresa}>
                  <td className="num" style={{ width: 40 }}>{String(e.codigoEmpresa).padStart(2, '0')}</td>
                  <td>{e.razaoSocial}</td>
                  <td className="mono">{e.cnpj ?? '—'}</td>
                  <td className="muted">{e.cidade ? `${e.cidade}/${e.estado ?? ''}` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}
