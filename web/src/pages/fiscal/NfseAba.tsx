import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, api } from '../../lib/api'
import {
  MOTIVOS_CANCELAMENTO,
  cancelarNfse,
  nomeDaNfse,
  urlXmlNfse,
  type NfseDocumento,
} from '../../lib/nfse'

interface Props {
  idEmpresa: number
  dataInicialIso: string
  dataFinalIso: string
  aoAvisar: (mensagem: string, tipo: 'erro' | 'sucesso') => void
}

interface Pagina {
  itens: NfseDocumento[]
  total: number
  pagina: number
  tamanhoPagina: number
}

const ROTULO: Record<string, string> = {
  RASCUNHO: 'Rascunho',
  ASSINADA: 'Aguardando envio',
  TRANSMITINDO: 'Transmitindo',
  AUTORIZADA: 'Autorizada',
  REJEITADA: 'Rejeitada',
  CANCELADA: 'Cancelada',
  NAO_EMITIDA: 'Não emitida',
}

/** ⚠️ Amarelo é "ainda vai sair, reenvie"; vermelho é "corrija o dado apontado". A diferença
 *  decide se o operador espera ou age, e confundi-la nos dois sentidos custa caro. */
function classeBadge(situacao: string): string {
  if (situacao === 'AUTORIZADA') return 'badge badge-sucesso'
  if (situacao === 'REJEITADA') return 'badge badge-perigo'
  if (situacao === 'CANCELADA' || situacao === 'NAO_EMITIDA') return 'badge badge-inativo'
  return 'badge badge-aviso'
}

function listar(idEmpresa: number, de: string, ate: string, situacao: string, pagina: number) {
  const busca = new URLSearchParams({ idEmpresa: String(idEmpresa), de, ate, pagina: String(pagina) })
  if (situacao) busca.set('situacao', situacao)
  return api<Pagina>(`/api/v1/nfse?${busca}`)
}

/**
 * Aba de NFS-e em Documentos Fiscais.
 *
 * ⭐ Existe para a nota PENDENTE ter onde aparecer. É a consequência de tela da DS13: nota que
 * ninguém emitiu por esquecimento é pior que nota que falhou, porque não aparece em lugar nenhum.
 * Por isso a listagem vem ordenada com pendente e rejeitada em cima — ordenar por data enterraria
 * a pendente de ontem sob as autorizadas de hoje.
 */
export default function NfseAba({ idEmpresa, dataInicialIso, dataFinalIso, aoAvisar }: Props) {
  const queryClient = useQueryClient()
  const [situacao, setSituacao] = useState('')
  const [pagina, setPagina] = useState(1)
  const [cancelando, setCancelando] = useState<NfseDocumento | null>(null)
  const [motivo, setMotivo] = useState('')
  const [codigoMotivo, setCodigoMotivo] = useState(1)

  const { data, isLoading, error } = useQuery({
    queryKey: ['nfse-lista', idEmpresa, dataInicialIso, dataFinalIso, situacao, pagina],
    queryFn: () => listar(idEmpresa, dataInicialIso, dataFinalIso, situacao, pagina),
  })

  const cancelar = useMutation({
    mutationFn: () => cancelarNfse(cancelando!.idNfse, codigoMotivo, motivo),
    onSuccess: (r) => {
      queryClient.invalidateQueries({ queryKey: ['nfse-lista'] })
      setCancelando(null)
      setMotivo('')
      aoAvisar(r.mensagem, 'sucesso')
    },
    onError: (e: unknown) => {
      // ⚠️ A mensagem do back traz o código do Sefin no início (E0840, E1235…) e, no erro de
      // schema, o campo exato que faltou. Trocá-la por um genérico apagaria isso.
      aoAvisar(e instanceof ApiError ? e.message : 'Não foi possível cancelar a NFS-e.', 'erro')
    },
  })

  const itens = data?.itens ?? []
  /** ⚠️ 15 caracteres é a faixa do XSD, não capricho: menos que isso o Sefin recusa por schema. */
  const motivoCurto = motivo.trim().length < 15

  return (
    <>
      <div className="card filtros-bar" style={{ marginBottom: 12 }}>
        <select
          value={situacao}
          onChange={(e) => {
            setSituacao(e.target.value)
            setPagina(1)
          }}
          aria-label="Situação da NFS-e"
        >
          <option value="">Todas as situações</option>
          {Object.entries(ROTULO).map(([valor, rotulo]) => (
            <option key={valor} value={valor}>
              {rotulo}
            </option>
          ))}
        </select>
      </div>

      <div className="card table-wrap">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error ? (
          <p className="erro">
            {error instanceof ApiError ? error.message : 'Não foi possível buscar as NFS-e.'}
          </p>
        ) : itens.length === 0 ? (
          <p className="muted">Nenhuma NFS-e no período.</p>
        ) : (
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Nota</th>
                <th>Serviço</th>
                <th>Situação</th>
                <th>Valor</th>
                <th>Chave</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {itens.map((d) => (
                <tr key={d.idNfse}>
                  <td>
                    {/* Enquanto não autorizada NÃO há número oficial — o nome é o nosso
                        sequencial. Mostrar um número que imite numeração fiscal antes da hora foi
                        o defeito que o workshop registrou (a mesma nota com dois nomes). */}
                    {nomeDaNfse(d)}
                    <br />
                    <span className="muted">venda {d.idVenda}</span>
                  </td>
                  <td>
                    {d.codigoTributacaoNacional}
                    <br />
                    <span className="muted">{d.descricaoServico}</span>
                  </td>
                  <td>
                    <span className={classeBadge(d.situacao)}>{ROTULO[d.situacao] ?? d.situacao}</span>
                    {d.motivoStatus && (
                      <>
                        <br />
                        <span className="muted">{d.motivoStatus}</span>
                      </>
                    )}
                  </td>
                  <td>{d.valorServicos.toLocaleString('pt-BR', { minimumFractionDigits: 2 })}</td>
                  <td className="muted" style={{ fontFamily: 'monospace', fontSize: '0.8em' }}>
                    {d.chaveAcesso ?? '—'}
                  </td>
                  <td>
                    {d.xmlChave && (
                      <a className="btn ghost" href={urlXmlNfse(d.idNfse)}>
                        XML
                      </a>
                    )}
                    {d.situacao === 'AUTORIZADA' && (
                      <button
                        type="button"
                        className="btn ghost"
                        onClick={() => {
                          setCancelando(d)
                          setMotivo('')
                          setCodigoMotivo(1)
                        }}
                      >
                        Cancelar
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {cancelando && (
        <div className="modal-overlay" onClick={() => setCancelando(null)}>
          <div
            className="modal modal-medio"
            role="dialog"
            aria-label="Cancelamento de NFS-e"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="lightbox-topo" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>Cancelar {nomeDaNfse(cancelando)}</h2>
              <button
                type="button"
                className="btn ghost btn-fechar-tela"
                onClick={() => setCancelando(null)}
                aria-label="Fechar"
              >
                ×
              </button>
            </div>

            <p className="muted">
              O cancelamento é registrado na prefeitura e não pode ser desfeito. ⚠ O prazo é
              definido pelo município — fora dele, quem decide se aceita é a prefeitura.
            </p>

            <div className="form-grid">
              <div className="col-6">
                <label htmlFor="codigo-motivo">Motivo</label>
                <select
                  id="codigo-motivo"
                  value={codigoMotivo}
                  onChange={(e) => setCodigoMotivo(Number(e.target.value))}
                >
                  {MOTIVOS_CANCELAMENTO.map((m) => (
                    <option key={m.valor} value={m.valor}>
                      {m.rotulo}
                    </option>
                  ))}
                </select>
              </div>
              <div className="col-12">
                <label htmlFor="motivo">Descrição do motivo</label>
                <textarea
                  id="motivo"
                  rows={3}
                  value={motivo}
                  maxLength={255}
                  onChange={(e) => setMotivo(e.target.value)}
                />
                <p className={motivoCurto ? 'erro' : 'muted'}>
                  {motivo.trim().length}/255 — o layout nacional exige entre 15 e 255 caracteres.
                </p>
              </div>
            </div>

            <div className="topbar-acoes" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
              <button type="button" className="btn ghost" onClick={() => setCancelando(null)}>
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={motivoCurto || cancelar.isPending}
                onClick={() => cancelar.mutate()}
              >
                {cancelar.isPending ? 'Cancelando…' : 'Cancelar a NFS-e'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
