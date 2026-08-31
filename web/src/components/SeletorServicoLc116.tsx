import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { aliquotaSugerida } from '../lib/nfse'

export interface ServicoLc116 {
  codigo: string
  descricao: string
  /** PRESTADOR | PRESTACAO | TOMADOR | ESPECIAL | SEM_INCIDENCIA — vem da fonte oficial. */
  localIncidencia: string
  /** Preenchido = o serviço exige um bloco extra no layout que o v1 não monta. */
  grupoDps: string | null
}

const ONDE_O_ISS_E_DEVIDO: Record<string, string> = {
  PRESTADOR: 'no município da sua loja',
  PRESTACAO: 'no município onde o serviço é executado',
  TOMADOR: 'no município do cliente',
  ESPECIAL: 'em regra especial (apuração no MAN ou trecho de rodovia)',
  SEM_INCIDENCIA: 'não há incidência de ISS',
}

function buscarServicos(busca: string): Promise<ServicoLc116[]> {
  return api<ServicoLc116[]>(`/api/v1/servicos/lc116?busca=${encodeURIComponent(busca)}`)
}

function sugestoesDoRamo(): Promise<ServicoLc116[]> {
  return api<ServicoLc116[]>('/api/v1/servicos/lc116/sugestoes')
}

interface Props {
  idEmpresa: number | null
  codigo: string
  descricaoAtual: string | null
  localIncidenciaAtual: string | null
  somenteLeitura: boolean
  aoEscolher: (servico: ServicoLc116 | null) => void
  /** Chamado quando o ADN devolve a alíquota do município — a tela preenche o campo. */
  aoSugerirAliquota: (percentual: number, vigenteDesde: string | undefined) => void
}

/**
 * Escolha do código de serviço da LC 116 (cTribNac).
 *
 * ⭐ Resolve a maior fonte de chamado do módulo: ninguém sabe que banho e tosa é `050801`. Dois
 * caminhos, e o primeiro cobre a maioria — a sugestão pelo ramo da loja mostra 5 códigos, não 334.
 *
 * ⚠️ A sugestão é CURADORIA, não mapa oficial: não existe um. Por isso a tela diz para confirmar
 * com o contador — enquadramento é decisão fiscal, não do software.
 */
export default function SeletorServicoLc116({
  idEmpresa,
  codigo,
  descricaoAtual,
  localIncidenciaAtual,
  somenteLeitura,
  aoEscolher,
  aoSugerirAliquota,
}: Props) {
  const [busca, setBusca] = useState('')
  const [buscaAdiada, setBuscaAdiada] = useState('')
  const [avisoAliquota, setAvisoAliquota] = useState('')

  /**
   * ⚠️ Debounce em estado próprio, não no `busca`. Ligar a query direto ao campo dispararia uma
   * requisição por tecla — e este projeto já registrou o inverso (useEffect congelando no 1º
   * dígito). 300 ms é o suficiente para digitar "tosa" sem quatro idas ao servidor.
   */
  useEffect(() => {
    const t = setTimeout(() => setBuscaAdiada(busca), 300)
    return () => clearTimeout(t)
  }, [busca])

  const { data: resultados } = useQuery({
    queryKey: ['lc116-busca', buscaAdiada],
    queryFn: () => buscarServicos(buscaAdiada),
    enabled: buscaAdiada.trim().length >= 2,
  })

  const { data: sugestoes } = useQuery({
    queryKey: ['lc116-sugestoes'],
    queryFn: sugestoesDoRamo,
  })

  const lista = buscaAdiada.trim().length >= 2 ? (resultados ?? []) : (sugestoes ?? [])
  const mostrandoSugestao = buscaAdiada.trim().length < 2

  async function escolher(s: ServicoLc116) {
    // ⛔ Serviço que exige bloco extra do layout não é atendido pelo v1 — e recusar aqui, com o
    // motivo, é melhor que deixar montar um XML incompleto e o Sefin recusar depois.
    if (s.grupoDps) {
      setAvisoAliquota(
        `O serviço ${s.codigo} exige informações extras no layout nacional (${s.grupoDps}) que o ` +
          'Nainer ainda não emite. Escolha outro código ou fale com o suporte.',
      )
      return
    }
    setAvisoAliquota('')
    aoEscolher(s)

    // ⭐ Pergunta a alíquota à FONTE em vez de pedir ao lojista. ⚠️ Não encontrar é resposta
    // legítima — nem todo município publicou a tabela no ADN.
    if (idEmpresa === null) return
    try {
      const r = await aliquotaSugerida(idEmpresa, s.codigo)
      if (r.encontrada && typeof r.percentual === 'number') {
        aoSugerirAliquota(r.percentual, r.vigenteDesde)
        setAvisoAliquota(
          `Alíquota de ${r.percentual}% obtida do Sistema Nacional para o seu município` +
            (r.vigenteDesde ? ` (vigente desde ${r.vigenteDesde.substring(0, 10)})` : '') +
            '. Confirme com seu contador.',
        )
      } else {
        setAvisoAliquota(
          r.aviso ??
            'Este município não publicou a alíquota deste serviço. Informe manualmente e confirme ' +
              'com seu contador.',
        )
      }
    } catch {
      // Consulta de apoio nunca derruba o cadastro: ela melhora, não é pré-requisito.
      setAvisoAliquota('Não foi possível consultar a alíquota agora — informe manualmente.')
    }
  }

  return (
    <div className="col-12">
      <label htmlFor="busca-lc116">Código do serviço (Lista Nacional / LC 116) *</label>

      {codigo && (
        <p className="muted" style={{ marginTop: 4 }}>
          <strong>{codigo}</strong> — {descricaoAtual ?? '—'}
          {localIncidenciaAtual && (
            <>
              {' · '}ISS devido {ONDE_O_ISS_E_DEVIDO[localIncidenciaAtual] ?? localIncidenciaAtual}
            </>
          )}
          {!somenteLeitura && (
            <>
              {' '}
              <button type="button" className="btn ghost" onClick={() => aoEscolher(null)}>
                trocar
              </button>
            </>
          )}
        </p>
      )}

      {!codigo && !somenteLeitura && (
        <>
          <input
            id="busca-lc116"
            placeholder="Digite o que a loja faz: tosa, conserto, cabeleireiro…"
            value={busca}
            onChange={(e) => setBusca(e.target.value)}
          />
          <p className="muted" style={{ marginTop: 4 }}>
            {mostrandoSugestao
              ? 'Sugestões para o ramo da sua loja — confirme o enquadramento com seu contador.'
              : `${lista.length} resultado(s).`}
          </p>
          <ul style={{ listStyle: 'none', padding: 0, margin: 0, maxHeight: 240, overflowY: 'auto' }}>
            {lista.map((s) => (
              <li key={s.codigo}>
                <button type="button" className="btn ghost" onClick={() => escolher(s)}>
                  <strong>{s.codigo}</strong> — {s.descricao}
                </button>
              </li>
            ))}
          </ul>
          {lista.length === 0 && !mostrandoSugestao && (
            <p className="muted">Nenhum serviço encontrado com esse texto.</p>
          )}
          {lista.length === 0 && mostrandoSugestao && (
            <p className="muted">
              O ramo da sua loja ainda não tem sugestões cadastradas — use a busca por texto.
            </p>
          )}
        </>
      )}

      {avisoAliquota && <p className="muted">{avisoAliquota}</p>}
    </div>
  )
}
