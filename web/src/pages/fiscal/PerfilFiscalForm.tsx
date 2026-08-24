import { useEffect, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeEditar, IconeExcluir, IconeFiscal } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import RegraFiscalModal from '../../components/RegraFiscalModal'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import {
  TIPO_DESTINATARIO_OPCOES,
  TIPO_OPERACAO_OPCOES,
  atualizarPerfilFiscal,
  buscarPerfilFiscal,
  criarPerfilFiscal,
  type PerfilFiscal,
  type PerfilFiscalRegra,
  type PerfilFiscalRegraRequest,
} from '../../lib/perfilFiscal'
import type { EstadoListaPerfilFiscal } from './PerfilFiscalLista'
import { maiusculas } from '../../lib/texto'

function rotuloDestinatario(v: string): string {
  return TIPO_DESTINATARIO_OPCOES.find((o) => o.valor === v)?.rotulo ?? v
}
function rotuloOperacao(v: string): string {
  return TIPO_OPERACAO_OPCOES.find((o) => o.valor === v)?.rotulo ?? v
}

/** Regra local do formulário: `PerfilFiscalRegraRequest` + uma chave de UI estável para
 *  edição/remoção (o backend não tem conceito de índice — regras são apaga-e-reinsere). */
type RegraLocal = PerfilFiscalRegraRequest & { chave: number }

function paraRegraLocal(r: PerfilFiscalRegra, chave: number): RegraLocal {
  const { idRegra: _idRegra, ...resto } = r
  return { ...resto, chave }
}

/**
 * Formulário de Perfil Fiscal (docs/telas/fiscal-perfil.md, bloco B2) — cabeçalho + coleção de
 * regras, cada uma editada em popup (`RegraFiscalModal`). As regras são substituídas por
 * completo a cada save (apaga-e-reinsere no backend), então este formulário trabalha com uma
 * lista local só de requests, não com os `idRegra` que vieram do servidor.
 */
export default function PerfilFiscalForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const listaEstado = (location.state as { listaEstado?: EstadoListaPerfilFiscal } | null)?.listaEstado

  const [nome, setNome] = useState('')
  const [descricao, setDescricao] = useState('')
  const [ativo, setAtivo] = useState(true)
  const [regras, setRegras] = useState<RegraLocal[]>([])
  const [proximaChave, setProximaChave] = useState(1)
  const [modalAberto, setModalAberto] = useState<{ chave: number | null } | null>(null)
  const [regraParaExcluir, setRegraParaExcluir] = useState<number | null>(null)
  const [erroNome, setErroNome] = useState<string | undefined>()
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: perfilExistente } = useQuery<PerfilFiscal>({
    queryKey: ['perfil-fiscal', id],
    queryFn: () => buscarPerfilFiscal(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (!perfilExistente) return
    setNome(perfilExistente.nome)
    setDescricao(perfilExistente.descricao ?? '')
    setAtivo(perfilExistente.ativo)
    setRegras(perfilExistente.regras.map((r, i) => paraRegraLocal(r, i + 1)))
    setProximaChave(perfilExistente.regras.length + 1)
  }, [perfilExistente])

  const salvar = useMutation({
    mutationFn: () => {
      const payload = { nome: nome.trim(), descricao: descricao.trim() || null, ativo, regras: regras.map(({ chave: _c, ...r }) => r) }
      return editando ? atualizarPerfilFiscal(Number(id), payload) : criarPerfilFiscal(payload)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['perfis-fiscais'] })
      // O <select> de perfil do ProdutoForm tem chave PRÓPRIA: array não casa por prefixo.
      queryClient.invalidateQueries({ queryKey: ['perfis-fiscais-opcoes'] })
      navigate('/fiscal/perfis', {
        state: {
          toast: {
            texto: editando ? 'Perfil fiscal atualizado com sucesso.' : 'Perfil fiscal cadastrado com sucesso.',
            tipo: 'sucesso',
          },
          listaEstado,
        },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o perfil fiscal.'),
  })

  const validarEEnviar = () => {
    if (somenteLeitura) return
    if (!nome.trim()) {
      setErroNome('Nome é obrigatório.')
      setToast('Corrija os campos destacados antes de salvar.')
      return
    }
    if (regras.length === 0) {
      setToast('Adicione pelo menos uma regra antes de salvar.')
      return
    }
    setErroNome(undefined)
    salvar.mutate()
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    validarEEnviar()
  }

  const regraEditando = modalAberto ? regras.find((r) => r.chave === modalAberto.chave) ?? null : null

  const salvarRegraDoModal = (payload: PerfilFiscalRegraRequest) => {
    if (modalAberto?.chave) {
      setRegras((rs) => rs.map((r) => (r.chave === modalAberto.chave ? { ...payload, chave: r.chave } : r)))
    } else {
      setRegras((rs) => [...rs, { ...payload, chave: proximaChave }])
      setProximaChave((c) => c + 1)
    }
    setModalAberto(null)
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeFiscal size={34} />
            <h1>Perfil Fiscal</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.perfil.tela" />
            <BotaoFecharTela onClick={() => navigate('/fiscal/perfis', { state: { listaEstado } })} />
            {!somenteLeitura && (
              <button type="submit" form="form-perfil-fiscal" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <form
          id="form-perfil-fiscal"
          className="card form-secoes form-secoes-larga"
          onSubmit={submeter}
          onKeyDown={(e: KeyboardEvent<HTMLFormElement>) => {
            if (!somenteLeitura) aoTeclarEnterNoFormulario(e, () => setConfirmarSalvarAberto(true))
          }}
          noValidate
        >
          <fieldset disabled={somenteLeitura} className="form-fieldset">
            <section className="section">
              <p className="section-label">Identificação</p>
              <div className="form-grid">
                <div className="col-6">
                  <label htmlFor="perfil-nome">Nome *</label>
                  <input
                    id="perfil-nome"
                    autoFocus
                    value={nome}
                    onChange={(e) => setNome(maiusculas(e.target.value))}
                    onBlur={() => setErroNome(nome.trim() ? undefined : 'Nome é obrigatório.')}
                  />
                  {erroNome && <p className="erro-campo">{erroNome}</p>}
                </div>
                <div className="col-4">
                  <label htmlFor="perfil-descricao">Descrição</label>
                  <input id="perfil-descricao" value={descricao} onChange={(e) => setDescricao(maiusculas(e.target.value))} />
                </div>
                <div className="col-2">
                  <label className="checkbox-linha">
                    <input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} />
                    Ativo
                  </label>
                </div>
              </div>
            </section>

            <section className="section">
              <div className="topbar-tela" style={{ marginBottom: 8 }}>
                <p className="section-label" style={{ margin: 0 }}>
                  Regras
                </p>
                {!somenteLeitura && (
                  <button type="button" className="btn ghost" onClick={() => setModalAberto({ chave: null })}>
                    ＋ Adicionar regra
                  </button>
                )}
              </div>

              {regras.length === 0 ? (
                <p className="muted">Nenhuma regra cadastrada. Sem regra, o motor não emite nota para nenhum produto deste perfil.</p>
              ) : (
                <div className="table-wrap">
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>CRT</th>
                        <th>UF</th>
                        <th>Destinatário</th>
                        <th>Operação</th>
                        <th>CFOP</th>
                        <th title="CFOP usado quando o cliente é de outra UF">CFOP fora da UF</th>
                        <th>ICMS</th>
                        {!somenteLeitura && <th aria-label="Ações" />}
                      </tr>
                    </thead>
                    <tbody>
                      {regras.map((r) => (
                        <tr key={r.chave}>
                          <td>{r.crt}</td>
                          <td>{r.ufDestino}</td>
                          <td>{rotuloDestinatario(r.tipoDestinatario)}</td>
                          <td>{rotuloOperacao(r.tipoOperacao)}</td>
                          <td>{r.cfop}</td>
                          {/* Vazio = a regra só cobre operação interna; a venda interestadual é
                              recusada com mensagem, nunca com CFOP derivado (2026-08-24). */}
                          <td>{r.cfopInterestadual ?? <span className="muted">—</span>}</td>
                          <td>{r.csosn ? `CSOSN ${r.csosn}` : `CST ${r.cstIcms}`}</td>
                          {!somenteLeitura && (
                            <td className="acoes-cell">
                              <button
                                type="button"
                                className="acao-icone acao-editar"
                                onClick={() => setModalAberto({ chave: r.chave })}
                                aria-label="Editar regra"
                                title="Editar"
                              >
                                <IconeEditar />
                              </button>
                              <button
                                type="button"
                                className="acao-icone acao-excluir"
                                onClick={() => setRegraParaExcluir(r.chave)}
                                aria-label="Remover regra"
                                title="Remover"
                              >
                                <IconeExcluir />
                              </button>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            {perfilExistente && (
              <InfoRegistro
                codigo={perfilExistente.idPerfilFiscal}
                criadoEm={perfilExistente.criadoEm}
                atualizadoEm={perfilExistente.atualizadoEm}
              />
            )}
          </fieldset>
        </form>
      </div>

      {modalAberto && (
        <RegraFiscalModal
          regraInicial={regraEditando}
          aoSalvar={salvarRegraDoModal}
          aoFechar={() => setModalAberto(null)}
        />
      )}

      {regraParaExcluir !== null && (
        <div className="modal-overlay" onClick={() => setRegraParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Remover regra" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Remover regra?</h2>
            <p className="muted">A regra só é removida de verdade quando o perfil for salvo.</p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setRegraParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setRegras((rs) => rs.filter((r) => r.chave !== regraParaExcluir))
                  setRegraParaExcluir(null)
                }}
              >
                Remover
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmarSalvarAberto && (
        <ConfirmarSalvarModal
          aoConfirmar={() => {
            setConfirmarSalvarAberto(false)
            validarEEnviar()
          }}
          aoCancelar={() => setConfirmarSalvarAberto(false)}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
