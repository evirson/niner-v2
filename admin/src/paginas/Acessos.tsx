import { useQuery } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
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
 *
 * ⭐ **A busca só acontece no botão "Localizar dados"** (decisão do dono do produto em 2026-09-02).
 * A tabela é a maior do plano de controle — cresce a cada login de cada lojista — e a versão
 * anterior consultava sozinha ao abrir e **a cada tecla digitada no e-mail**, varrendo o log
 * inteiro para jogar fora o resultado no caractere seguinte. Auditoria se faz com o filtro pronto,
 * não com o dedo no teclado.
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

/**
 * ⚠️ `tag`/`tag-ok`/`tag-perigo` são as classes do backoffice. A versão anterior usava
 * `badge badge-sucesso`, que é o vocabulário do ERP do lojista e **não existe** em
 * `admin/src/styles.css` — o selo saía como texto cru, sem erro em lugar nenhum.
 */
function selo(resultado: string): string {
  return resultado === 'SUCESSO' ? 'tag tag-ok' : 'tag tag-perigo'
}

/** Uma linha só: "Windows · Chrome · Computador", sem os travessões de campo vazio. */
function aparelho(a: AcessoLogin): string {
  const partes = [a.so, a.navegador, a.dispositivo === 'DESCONHECIDO' ? null : a.dispositivo]
  const texto = partes.filter(Boolean).join(' · ')
  return texto || '—'
}

/** O que foi efetivamente pedido ao servidor — não o que está digitado na tela. */
type Consulta = {
  de: string
  ate: string
  email: string
  resultado: string
  somenteFalhas: boolean
  pagina: number
}

export default function Acessos() {
  const [de, setDe] = useState('')
  const [ate, setAte] = useState('')
  const [email, setEmail] = useState('')
  const [resultado, setResultado] = useState('')
  const [somenteFalhas, setSomenteFalhas] = useState(false)
  const [aberto, setAberto] = useState<number | null>(null)

  /** `null` = ainda não localizou nada. É o que separa "sem dados" de "nada foi pedido". */
  const [consulta, setConsulta] = useState<Consulta | null>(null)

  const { data: lista, isFetching } = useQuery({
    queryKey: ['acessos', consulta],
    queryFn: () =>
      listarAcessos({
        de: consulta!.de || undefined,
        ate: consulta!.ate || undefined,
        email: consulta!.email || undefined,
        resultado: consulta!.resultado || undefined,
        somenteFalhas: consulta!.somenteFalhas,
        pagina: consulta!.pagina,
      }),
    // ⭐ Sem isto a tela voltaria a buscar sozinha ao abrir, que é justamente o que o botão evita.
    enabled: consulta !== null,
  })

  /** Localizar sempre volta para a página 1: manter a página velha traria uma lista vazia. */
  function localizar() {
    setAberto(null)
    setConsulta({ de, ate, email, resultado, somenteFalhas, pagina: 1 })
  }

  function limpar() {
    setDe('')
    setAte('')
    setEmail('')
    setResultado('')
    setSomenteFalhas(false)
    setAberto(null)
    setConsulta(null)
  }

  /** Trocar de página é navegação no resultado que já existe — não precisa do botão de novo. */
  function irPara(pagina: number) {
    setAberto(null)
    setConsulta((c) => (c ? { ...c, pagina } : c))
  }

  const totalPaginas = lista ? Math.max(1, Math.ceil(lista.total / lista.limite)) : 1
  const pagina = consulta?.pagina ?? 1

  return (
    <>
      <div className="topo-pagina">
        <h1>Acessos ao ERP</h1>
        <span className="muted">
          {lista ? `${NUMERO.format(lista.total)} registro(s)` : ''}
        </span>
      </div>

      {/* Enter em qualquer campo faz o mesmo que o botão — convenção do produto. */}
      <form
        className="card"
        style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}
        onSubmit={(e) => {
          e.preventDefault()
          localizar()
        }}
      >
        {/* ⚠️ `campo` é a classe que põe o rótulo ACIMA do controle. Sem ela, "De/Até/E-mail" só
            pareciam certos por acaso — `input` é `width: 100%` e empurrava o label para cima
            sozinho; o `select`, de largura fixa, deixava "Resultado" pendurado ao lado. */}
        <div className="campo" style={{ marginBottom: 0 }}>
          <label htmlFor="de">De</label>
          <input id="de" type="date" value={de} onChange={(e) => setDe(e.target.value)} />
        </div>
        <div className="campo" style={{ marginBottom: 0 }}>
          <label htmlFor="ate">Até</label>
          <input id="ate" type="date" value={ate} onChange={(e) => setAte(e.target.value)} />
        </div>
        <div className="campo" style={{ marginBottom: 0, flex: 1, minWidth: 200 }}>
          <label htmlFor="email">E-mail</label>
          <input
            id="email"
            placeholder="parte do e-mail informado"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>
        <div className="campo" style={{ marginBottom: 0 }}>
          <label htmlFor="resultado">Resultado</label>
          <select
            id="resultado"
            value={resultado}
            onChange={(e) => setResultado(e.target.value)}
            style={{ width: 200 }}
          >
            <option value="">Todos</option>
            {RESULTADOS.map((r) => (
              <option key={r} value={r}>{ROTULO[r]}</option>
            ))}
          </select>
        </div>
        {/* ⭐ O atalho que a auditoria usa de verdade: "mostre o que NÃO deu certo". */}
        <label className="check" style={{ marginBottom: 8, gap: 6 }}>
          <input
            type="checkbox"
            checked={somenteFalhas}
            onChange={(e) => setSomenteFalhas(e.target.checked)}
          />
          Só falhas
        </label>
        <div style={{ display: 'flex', gap: 8 }}>
          <button type="submit" className="btn btn-primario" disabled={isFetching}>
            {isFetching ? 'Localizando…' : 'Localizar dados'}
          </button>
          <button type="button" className="btn btn-secundario" onClick={limpar} disabled={isFetching}>
            Limpar
          </button>
        </div>
      </form>

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
            {/* ⚠️ Três estados diferentes, três mensagens: "não pedi nada" não é "não achei nada". */}
            {consulta === null && !isFetching && (
              <tr>
                <td colSpan={6} className="muted">
                  Escolha os filtros e clique em <strong>Localizar dados</strong>.
                </td>
              </tr>
            )}
            {isFetching && <tr><td colSpan={6} className="muted">Carregando…</td></tr>}
            {consulta !== null && !isFetching && lista?.itens.length === 0 && (
              <tr><td colSpan={6} className="muted">Nenhum acesso com esses filtros.</td></tr>
            )}
            {!isFetching && lista?.itens.map((a) => (
              // ⚠️ A `key` vai no elemento que o map devolve — o fragmento. Antes ela estava no
              // <tr> de dentro, e o React reclamava de lista sem chave no console.
              <Fragment key={a.idAcesso}>
                <tr
                  style={{ cursor: 'pointer' }}
                  onClick={() => setAberto(aberto === a.idAcesso ? null : a.idAcesso)}
                >
                  <td>{dataHora(a.ocorridoEm)}</td>
                  <td>{a.nomeConta ?? '—'}</td>
                  <td>{a.emailInformado}</td>
                  <td><span className={selo(a.resultado)}>{ROTULO[a.resultado] ?? a.resultado}</span></td>
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
                  <tr>
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
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>

      {/* A paginação some enquanto nada foi localizado — botão que não navega nada é ruído. */}
      {consulta !== null && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'flex-end', marginTop: 12 }}>
          <button
            className="btn btn-secundario"
            disabled={pagina <= 1 || isFetching}
            onClick={() => irPara(pagina - 1)}
          >
            Anterior
          </button>
          <span className="muted">{pagina} / {totalPaginas}</span>
          <button
            className="btn btn-secundario"
            disabled={!lista || pagina >= totalPaginas || isFetching}
            onClick={() => irPara(pagina + 1)}
          >
            Próxima
          </button>
        </div>
      )}
    </>
  )
}
