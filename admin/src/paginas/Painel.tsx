import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { NUMERO, REAL, data } from '../lib/api'
import { buscarFunil, contasPertoDoLimite, type Funil } from '../lib/plataforma'

const PERIODOS = [
  { dias: 7, rotulo: '7 dias' },
  { dias: 30, rotulo: '30 dias' },
  { dias: 90, rotulo: '90 dias' },
]

function isoDiasAtras(dias: number): string {
  const d = new Date()
  d.setDate(d.getDate() - dias)
  return d.toISOString().slice(0, 10)
}

/**
 * Painel do funil (ADR-017). A ordem das telas não é acidental: a primeira coisa que o time
 * precisa ver é <b>quem está prestes a precisar pagar</b> — é o único item aqui que pede ação
 * hoje; o resto é leitura de tendência.
 */
export default function Painel() {
  const [dias, setDias] = useState(30)
  const { data: funil, isLoading } = useQuery({
    queryKey: ['funil', dias],
    queryFn: () => buscarFunil(isoDiasAtras(dias), new Date().toISOString().slice(0, 10)),
  })
  const { data: perto } = useQuery({ queryKey: ['perto-do-limite'], queryFn: contasPertoDoLimite })

  return (
    <>
      <div className="topo-pagina">
        <h1>Painel</h1>
        <div className="acoes">
          {PERIODOS.map((p) => (
            <button key={p.dias} type="button" onClick={() => setDias(p.dias)}
              className={`btn ${dias === p.dias ? 'btn-primario' : 'btn-secundario'}`}>
              {p.rotulo}
            </button>
          ))}
        </div>
      </div>

      {perto && perto.length > 0 && (
        <section className="card" style={{ borderColor: 'var(--warning)' }}>
          <h2 style={{ fontSize: 18 }}>Contas perto do limite ({perto.length})</h2>
          <p className="muted" style={{ marginTop: 4 }}>
            Gratuitas com 80% ou mais da cota consumida neste mês — é a fila do contato comercial.
          </p>
          <div className="tabela-rolagem" style={{ marginTop: 12 }}>
            <table>
              <thead>
                <tr>
                  <th>Conta</th>
                  <th>Contato</th>
                  <th className="num">Uso</th>
                  <th style={{ width: 160 }}>Consumo</th>
                </tr>
              </thead>
              <tbody>
                {perto.map((c) => (
                  <tr key={c.idTenant}>
                    <td>
                      <Link to={`/contas?busca=${encodeURIComponent(c.nomeConta)}`}>{c.nomeConta}</Link>
                    </td>
                    <td className="muted">{c.emailContato}</td>
                    <td className="num">
                      {c.qtdVendas}/{c.limite}
                    </td>
                    <td>
                      <div className={`barra ${c.percentual >= 100 ? 'perigo' : 'atencao'}`}>
                        <span style={{ width: `${Math.min(100, c.percentual)}%` }} />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <section className="secao">
        <h2>Funil de aquisição</h2>
        {isLoading || !funil ? <p className="muted">Carregando…</p> : <Metricas funil={funil} />}
      </section>

      {funil && funil.porOrigem.length > 0 && (
        <section className="secao">
          <h2>Por origem e campanha</h2>
          <p className="muted" style={{ marginBottom: 10 }}>
            Atribuição de <b>primeiro toque</b>: a origem é a da primeira visita do visitante, não a
            da visita em que ele se cadastrou.
          </p>
          <div className="card tabela-rolagem" style={{ padding: 0 }}>
            <table>
              <thead>
                <tr>
                  <th>Origem</th>
                  <th>Campanha</th>
                  <th className="num">Visitantes</th>
                  <th className="num">Leads</th>
                  <th className="num">Contas</th>
                  <th className="num">Pagantes</th>
                  <th className="num">MRR</th>
                </tr>
              </thead>
              <tbody>
                {funil.porOrigem.map((o) => (
                  <tr key={`${o.origem}-${o.campanha}`}>
                    <td>{o.origem}</td>
                    <td className="muted">{o.campanha}</td>
                    <td className="num">{NUMERO.format(o.visitantes)}</td>
                    <td className="num">{NUMERO.format(o.leads)}</td>
                    <td className="num">{NUMERO.format(o.contas)}</td>
                    <td className="num">{NUMERO.format(o.pagantes)}</td>
                    <td className="num">{REAL.format(o.mrr)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {funil && (
        <p className="muted" style={{ marginTop: 18, fontSize: 12 }}>
          Período: {data(funil.de)} a {data(funil.ate)}.
        </p>
      )}
    </>
  )
}

function Metricas({ funil }: { funil: Funil }) {
  const conversao = (de: number, para: number) => (de === 0 ? '—' : `${Math.round((para / de) * 100)}%`)
  const itens = [
    { rotulo: 'Visitas', valor: NUMERO.format(funil.visitas), apoio: `${NUMERO.format(funil.visitantes)} visitantes` },
    { rotulo: 'Leads', valor: NUMERO.format(funil.leads), apoio: `${conversao(funil.visitantes, funil.leads)} dos visitantes` },
    { rotulo: 'Contas criadas', valor: NUMERO.format(funil.contas), apoio: `${conversao(funil.leads, funil.contas)} dos leads` },
    { rotulo: 'Contas vendendo', valor: NUMERO.format(funil.contasComVenda), apoio: 'com ao menos 1 venda' },
    { rotulo: 'Pagantes', valor: NUMERO.format(funil.pagantes), apoio: 'assinatura ativa' },
    { rotulo: 'MRR', valor: REAL.format(funil.mrr), apoio: 'receita recorrente' },
  ]
  return (
    <div className="grade-metricas">
      {itens.map((i) => (
        <div key={i.rotulo} className="card metrica">
          <div className="rotulo">{i.rotulo}</div>
          <div className="valor">{i.valor}</div>
          <div className="apoio">{i.apoio}</div>
        </div>
      ))}
    </div>
  )
}
