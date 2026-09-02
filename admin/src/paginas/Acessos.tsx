import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { NUMERO, dataHora } from '../lib/api'
import { listarAcessos, type AcessoLogin } from '../lib/plataforma'

/**
 * Log de acesso ao ERP (docs/MODULOLOGACESSO.md) — quem entrou, quando, de onde e com o quê.
 *
 * ⛔ Tela **da Vetor**: o administrador do tenant não vê estes dados, e não existe endpoint sob
 * `/api/v1` que os leia (há teste prendendo isso). Decisão do dono do produto: se o funcionário
 * pedir os registros ao patrão, o patrão pede à Vetor.
 *
 * ⚠️ **Não há hora de saída, e isso é desenho**: o navegador não avisa quando a pessoa vai embora,
 * então qualquer "logoff" aqui seria inferência — e inferência numa trilha de auditoria vira "fato"
 * na cabeça de quem lê seis meses depois.
 */

const RESULTADOS = [
  'SUCESSO',
  'CREDENCIAL_INVALIDA',
  'FORA_DO_HORARIO',
  'SEM_EMPRESA',
  'EMPRESA_INVALIDA',
  'CODIGO_2FA_INVALIDO',
]

/** Rótulos: o enum é do banco, a tela fala português. */
const ROTULO: Record<string, string> = {
  SUCESSO: 'Entrou',
  CREDENCIAL_INVALIDA: 'Credencial inválida',
  FORA_DO_HORARIO: 'Fora do horário',
  SEM_EMPRESA: 'Sem empresa vinculada',
  EMPRESA_INVALIDA: 'Empresa inválida',
  CODIGO_2FA_INVALIDO: 'Código 2FA inválido',
}

function badge(resultado: string): string {
  return resultado === 'SUCESSO' ? 'badge badge-sucesso' : 'badge badge-perigo'
}

/** Uma linha só: "Windows · Chrome · Computador", sem os travessões de campo vazio. */
function aparelho(a: AcessoLogin): string {
  const partes = [a.so, a.navegador, a.dispositivo === 'DESCONHECIDO' ? null : a.dispositivo]
  const texto = partes.filter(Boolean).join(' · ')
  return texto || '—'
}

export default function Acessos() {
  const [de, setDe] = useState('')
  const [ate, setAte] = useState('')
  const [email, setEmail] = useState('')
  const [resultado, setResultado] = useState('')
  const [somenteFalhas, setSomenteFalhas] = useState(false)
  const [pagina, setPagina] = useState(1)
  const [aberto, setAberto] = useState<number | null>(null)

  const { data: lista, isLoading } = useQuery({
    queryKey: ['acessos', de, ate, email, resultado, somenteFalhas, pagina],
    queryFn: () =>
      listarAcessos({
        de: de || undefined,
        ate: ate || undefined,
        email: email || undefined,
        resultado: resultado || undefined,
        somenteFalhas,
        pagina,
      }),
  })

  /** Qualquer filtro novo volta para a primeira página — senão a lista aparece vazia. */
  function aoFiltrar(acao: () => void) {
    acao()
    setPagina(1)
  }

  const totalPaginas = lista ? Math.max(1, Math.ceil(lista.total / lista.limite)) : 1

  return (
    <>
      <div className="topo-pagina">
        <h1>Acessos ao ERP</h1>
        <span className="muted">
          {lista ? `${NUMERO.format(lista.total)} registro(s)` : ''}
        </span>
      </div>

      <div className="card" style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <div>
          <label htmlFor="de">De</label>
          <input id="de" type="date" value={de} onChange={(e) => aoFiltrar(() => setDe(e.target.value))} />
        </div>
        <div>
          <label htmlFor="ate">Até</label>
          <input id="ate" type="date" value={ate} onChange={(e) => aoFiltrar(() => setAte(e.target.value))} />
        </div>
        <div style={{ flex: 1, minWidth: 200 }}>
          <label htmlFor="email">E-mail</label>
          <input
            id="email"
            placeholder="parte do e-mail informado"
            value={email}
            onChange={(e) => aoFiltrar(() => setEmail(e.target.value))}
          />
        </div>
        <div>
          <label htmlFor="resultado">Resultado</label>
          <select
            id="resultado"
            value={resultado}
            onChange={(e) => aoFiltrar(() => setResultado(e.target.value))}
            style={{ width: 200 }}
          >
            <option value="">Todos</option>
            {RESULTADOS.map((r) => (
              <option key={r} value={r}>{ROTULO[r]}</option>
            ))}
          </select>
        </div>
        {/* ⭐ O atalho que a auditoria usa de verdade: "mostre o que NÃO deu certo". */}
        <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <input
            type="checkbox"
            checked={somenteFalhas}
            onChange={(e) => aoFiltrar(() => setSomenteFalhas(e.target.checked))}
          />
          Só falhas
        </label>
      </div>

      <div className="card tabela-rolagem" style={{ padding: 0 }}>
        <table>
          <thead>
            <tr>
              <th>Quando</th>
              <th>Conta</th>
              <th>E-mail</th>
              <th>Resultado</th>
              <th>IP</th>
              <th>Aparelho</th>
            </tr>
          </thead>
          <tbody>
            {isLoading && <tr><td colSpan={6} className="muted">Carregando…</td></tr>}
            {lista?.itens.length === 0 && (
              <tr><td colSpan={6} className="muted">Nenhum acesso com esses filtros.</td></tr>
            )}
            {lista?.itens.map((a) => (
              <>
                <tr
                  key={a.idAcesso}
                  style={{ cursor: 'pointer' }}
                  onClick={() => setAberto(aberto === a.idAcesso ? null : a.idAcesso)}
                >
                  <td>{dataHora(a.ocorridoEm)}</td>
                  <td>{a.nomeConta ?? '—'}</td>
                  <td>{a.emailInformado}</td>
                  <td><span className={badge(a.resultado)}>{ROTULO[a.resultado] ?? a.resultado}</span></td>
                  <td>
                    {a.ip ?? '—'}
                    {/* ⚠️ Sem proxy confiável o endereço pode ser o do próprio proxy. Dizer isso
                        na tela evita que alguém trate o dado como certeza seis meses depois. */}
                    {a.ip && !a.ipConfiavel && (
                      <span className="muted" style={{ fontSize: 11 }}> (sem proxy)</span>
                    )}
                  </td>
                  <td>{aparelho(a)}</td>
                </tr>
                {aberto === a.idAcesso && (
                  <tr key={`${a.idAcesso}-detalhe`}>
                    <td colSpan={6} style={{ background: 'rgba(0,0,0,.15)' }}>
                      <div className="muted" style={{ fontSize: 12, marginBottom: 4 }}>
                        User-Agent completo (texto original enviado pelo navegador)
                      </div>
                      <code style={{ fontSize: 12, wordBreak: 'break-all' }}>
                        {a.userAgent ?? '— não informado —'}
                      </code>
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'flex-end', marginTop: 12 }}>
        <button className="btn ghost" disabled={pagina <= 1} onClick={() => setPagina((p) => p - 1)}>
          Anterior
        </button>
        <span className="muted">{pagina} / {totalPaginas}</span>
        <button
          className="btn ghost"
          disabled={!lista || pagina >= totalPaginas}
          onClick={() => setPagina((p) => p + 1)}
        >
          Próxima
        </button>
      </div>
    </>
  )
}
