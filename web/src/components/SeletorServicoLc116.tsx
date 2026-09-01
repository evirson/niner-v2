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
    // ⭐ O código é GRAVADO SEMPRE, inclusive o de grupo especial (2026-09-01, decisão dele:
    // *"apenas vamos colocar o código"*).
    //
    // ⛔ Antes daqui a função RETORNAVA sem gravar quando o serviço exigia bloco extra do layout,
    // e escrevia o motivo num parágrafo cinza no rodapé do bloco. Da cadeira do lojista isso é
    // *"cliquei no código e não aconteceu nada"* — foi exatamente o relato dele com INSTALACAO DE
    // VIDRO (070602, grupo "obra"), cujo cadastro ficou com a alíquota gravada e o código vazio.
    // Recusa que parece falha de gravação é pior que recusa nenhuma: some com o trabalho do
    // usuário sem dizer que sumiu.
    //
    // ⚠️ O aviso continua — mas como AVISO, não como bloqueio: cadastrar o código é útil por si
    // (é a classificação do serviço), e quem precisa do bloco extra é a EMISSÃO. A trava mora lá,
    // no servidor (`VendaNfseAssembler`), onde ela também vale para quem não passa por esta tela.
    aoEscolher(s)
    if (s.grupoDps) {
      setAvisoAliquota(
        `Código gravado. ⚠️ Atenção: o ${s.codigo} exige informações extras no layout nacional ` +
          `(${s.grupoDps}) que o Nainer ainda não emite — o cadastro fica pronto, mas a NFS-e ` +
          'desse serviço ainda não sai.',
      )
      return
    }
    setAvisoAliquota('')

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
      {/* ⚠️ SEM asterisco (2026-09-01, decisão dele): o código é OPCIONAL, porque nem toda loja
          emite NFS-e — o MEI que atende pessoa física vive sem ele. A tela dizia "obrigatório" e o
          servidor aceitava vazio; label que mente sobre a regra treina o usuário a desconfiar de
          todos os asteriscos. Quem cobra o código é a EMISSÃO, e ali a mensagem nomeia o serviço. */}
      <label htmlFor="busca-lc116">Código do serviço (Lista Nacional / LC 116)</label>
      <p className="muted" style={{ marginTop: 4 }}>
        Só é necessário para emitir NFS-e. Sem ele o serviço funciona normalmente na venda e na
        ordem de serviço.
      </p>

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
          {/* ⚠️ A mensagem antiga afirmava "o ramo da sua loja ainda não tem sugestões cadastradas"
              — e o caso REAL, medido em 2026-09-01, era outro: **a empresa estava sem ramo
              definido** (as 5 do tenant, todas com id_ramo NULL). Dizer que o ramo não tem
              curadoria manda o lojista concluir que o sistema não cobre o negócio dele, quando
              falta um campo que ele mesmo preenche. O endpoint devolve lista vazia nos dois casos,
              então o texto cobre os dois — e diz ONDE resolver, que é o que separa aviso útil de
              aviso que vira chamado. */}
          {lista.length === 0 && mostrandoSugestao && (
            <p className="muted">
              Sem sugestões para esta loja. Defina o <strong>Ramo de Atividade</strong> no cadastro
              da Empresa para receber os códigos mais usados no seu ramo — ou busque por texto
              acima.
            </p>
          )}
        </>
      )}

      {avisoAliquota && <p className="muted">{avisoAliquota}</p>}
    </div>
  )
}
