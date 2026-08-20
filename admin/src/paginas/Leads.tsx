import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { NUMERO, dataHora } from '../lib/api'
import { atualizarLead, buscarLead, listarLeads } from '../lib/plataforma'

const STATUS = ['NOVO', 'CONTATADO', 'QUALIFICADO', 'CONVERTIDO', 'PERDIDO']

export default function Leads() {
  const [status, setStatus] = useState('')
  const [pagina, setPagina] = useState(1)
  const [aberto, setAberto] = useState<number | null>(null)

  const { data: lista, isLoading } = useQuery({
    queryKey: ['leads', status, pagina],
    queryFn: () => listarLeads({ status: status || undefined, pagina }),
  })

  return (
    <>
      <div className="topo-pagina">
        <h1>Leads</h1>
        <select value={status} onChange={(e) => { setStatus(e.target.value); setPagina(1) }} style={{ width: 190 }}>
          <option value="">Todos os status</option>
          {STATUS.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      <div className="card tabela-rolagem" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Lead</th>
              <th>Origem</th>
              <th>Campanha</th>
              <th>Status</th>
              <th>Conta</th>
              <th>Chegou</th>
            </tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={6} className="muted">Carregando…</td></tr>}
            {lista?.itens.length === 0 && <tr><td colSpan={6} className="muted">Nenhum lead ainda.</td></tr>}
            {lista?.itens.map((l) => (
              <tr key={l.idLead} style={{ cursor: 'pointer' }}
                onClick={() => setAberto(aberto === l.idLead ? null : l.idLead)}>
                <td>
                  <div>{l.nome ?? '—'}</div>
                  <div className="muted" style={{ fontSize: 12 }}>
                    {l.email}
                    {l.telefoneWhatsapp ? ` · ${l.telefoneWhatsapp}` : ''}
                  </div>
                </td>
                <td>{l.origem}</td>
                <td className="muted">{l.campanha}</td>
                <td>
                  <span className={`tag ${l.status === 'CONVERTIDO' ? 'tag-ok' : l.status === 'PERDIDO' ? 'tag-perigo' : 'tag-neutro'}`}>
                    {l.status}
                  </span>
                </td>
                <td className="muted">{l.nomeConta ?? '—'}</td>
                <td className="muted">{dataHora(l.criadoEm)}</td>
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
          <span className="muted">Página {lista.pagina} · {NUMERO.format(lista.total)} lead(s)</span>
          <button className="btn btn-secundario" disabled={pagina * lista.limite >= lista.total}
            onClick={() => setPagina((p) => p + 1)}>
            Próxima
          </button>
        </div>
      )}

      {aberto !== null && <Ficha idLead={aberto} aoFechar={() => setAberto(null)} />}
    </>
  )
}

/**
 * Ficha do lead com a <b>linha do tempo do visitante</b> — as páginas que ele viu e onde clicou,
 * antes e depois de se identificar. É o que diferencia "entrou e saiu" de "quase comprou", e o
 * que dá assunto para o contato.
 */
function Ficha({ idLead, aoFechar }: { idLead: number; aoFechar: () => void }) {
  const qc = useQueryClient()
  const { data: detalhe } = useQuery({ queryKey: ['lead', idLead], queryFn: () => buscarLead(idLead) })
  const [anotacao, setAnotacao] = useState('')

  const salvar = useMutation({
    mutationFn: (dados: { status?: string; anotacao?: string }) => atualizarLead(idLead, dados),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['leads'] })
      qc.invalidateQueries({ queryKey: ['lead', idLead] })
      setAnotacao('')
    },
  })

  if (!detalhe) return null

  return (
    <section className="card secao">
      <div className="topo-pagina" style={{ marginBottom: 12 }}>
        <h2>{detalhe.lead.nome ?? detalhe.lead.email}</h2>
        <button className="btn btn-secundario" onClick={aoFechar}>Fechar</button>
      </div>

      <p className="muted">
        {detalhe.lead.email}
        {detalhe.lead.telefoneWhatsapp ? ` · ${detalhe.lead.telefoneWhatsapp}` : ''}
        {detalhe.lead.nomeLoja ? ` · ${detalhe.lead.nomeLoja}` : ''} · origem {detalhe.lead.origem}
      </p>

      <div className="linha" style={{ marginTop: 14, alignItems: 'end' }}>
        <div className="campo">
          <label htmlFor="status-lead">Status</label>
          <select id="status-lead" value={detalhe.lead.status}
            onChange={(e) => salvar.mutate({ status: e.target.value })}>
            {STATUS.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
        <div className="campo">
          <label htmlFor="anotacao">Anotação</label>
          <input id="anotacao" value={anotacao} placeholder="O que ficou combinado"
            onChange={(e) => setAnotacao(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && anotacao.trim()) salvar.mutate({ anotacao: anotacao.trim() })
            }} />
        </div>
      </div>

      <h3 style={{ fontSize: 15, margin: '16px 0 8px' }}>Linha do tempo</h3>
      {detalhe.linhaDoTempo.length === 0 ? (
        <p className="muted">
          Sem rastro de navegação — o lead chegou sem passar pelo site (ou com a medição bloqueada).
        </p>
      ) : (
        <ul className="tempo">
          {detalhe.linhaDoTempo.map((m, i) => (
            <li key={`${m.quando}-${i}`}>
              <span className="quando">{dataHora(m.quando)}</span>
              <span>{m.tipo}</span>
              <span className="muted">{m.detalhe ?? '—'}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
