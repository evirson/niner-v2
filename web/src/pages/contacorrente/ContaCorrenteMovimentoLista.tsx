import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import {
  IconeEditar,
  IconeExcluir,
  IconeMovimentoContaCorrente,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import SeletorPlanoContas from '../../components/SeletorPlanoContas'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarOpcoesContaCorrente } from '../../lib/contaCorrente'
import { maiusculas } from '../../lib/texto'
import {
  excluirMovimento,
  listarMovimentos,
  type ColunaOrdenacaoMovimento,
  type ContaCorrenteMovimento,
  type FiltroCompensado,
} from '../../lib/contaCorrenteMovimento'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'
import { fecharAoClicarNoFundo } from '../../lib/modais'
import { useNavegacaoDeGrid } from '../../lib/useNavegacaoDeGrid'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoMovimento; rotulo: string }> = [
  { chave: 'dataMovimento', rotulo: 'Data' },
  { chave: 'idContaCorrente', rotulo: 'Conta' },
  { chave: 'numeroDocumento', rotulo: 'Documento' },
  { chave: 'creditoDebito', rotulo: 'C/D' },
  { chave: 'valor', rotulo: 'Valor' },
  { chave: 'compensado', rotulo: 'Compensado' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

/**
 * Listagem de lançamentos (extrato manual) de conta corrente
 * (docs/telas/conta-corrente-movimento.md). PK surrogate (`localizador`) — diferente de
 * `financeiro.contacorrente` (PK de negócio), mesmo padrão de `cadastros.funcionario`.
 */
export default function ContaCorrenteMovimentoLista() {
  // ⛔ RBAC: o admin pode conceder só "acessar" — sem isto o operador preenchia a ficha
  // inteira e perdia tudo num 403 no Salvar (auditoria 2026-08-29). Conveniência de
  // interface, nunca segurança: quem recusa é o @Acao do controller (P4).
  const acoes = usePermissaoDaTela('contas-corrente-movimento')
  const location = useLocation()
  const [busca, setBusca] = useState('')
  const [idContaCorrente, setIdContaCorrente] = useState('')
  const [idEmpresa, setIdEmpresa] = useState<number | ''>('')
  const [idPlanoContas, setIdPlanoContas] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [compensado, setCompensado] = useState<FiltroCompensado>('TODOS')
  const [movimentoParaExcluir, setMovimentoParaExcluir] = useState<ContaCorrenteMovimento | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const queryClient = useQueryClient()

  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoMovimento>('dataMovimento')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')

  useEffect(() => {
    setPagina(1)
  }, [busca, idContaCorrente, idEmpresa, idPlanoContas, dataInicialTexto, dataFinalTexto, compensado, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoMovimento) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data: contas } = useQuery({ queryKey: ['contas-corrente-opcoes'], queryFn: listarOpcoesContaCorrente })
  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })

  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : undefined

  const { data, isLoading, isFetching } = useQuery({
    queryKey: [
      'contas-corrente-movimento',
      { busca, idContaCorrente, idEmpresa, idPlanoContas, dataInicialIso, dataFinalIso, compensado, pagina, ordenarPor, direcao },
    ],
    queryFn: () =>
      listarMovimentos({
        busca: busca || undefined,
        idContaCorrente: idContaCorrente || undefined,
        idEmpresa: idEmpresa || undefined,
        idPlanoContas: idPlanoContas || undefined,
        dataInicial: dataInicialIso ?? undefined,
        dataFinal: dataFinalIso ?? undefined,
        compensado,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirMovimento,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contas-corrente-movimento'] })
      setMovimentoParaExcluir(null)
      setAviso({ texto: 'Lançamento excluído.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setMovimentoParaExcluir(null)
      setAviso({ texto: e instanceof ApiError ? e.message : 'Não foi possível excluir o lançamento.', tipo: 'erro' })
    },
  })

  const movimentos: ContaCorrenteMovimento[] = data?.itens ?? []

  // Navegação por ↑/↓ na grid, com a linha corrente realçada (2026-09-04).

  const { propsDaLinha } = useNavegacaoDeGrid(movimentos.map((linha) => linha.localizador))

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeMovimentoContaCorrente size={34} />
            <h1>Movimentação de Conta Corrente</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.contacorrentemovimento.lista" />
            {acoes.incluir && (
              <Link className="btn" to="/contas-corrente-movimento/novo">
                ＋ Novo lançamento
              </Link>
            )}
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        <div className="card filtros-bar">
          <input
            autoFocus
            placeholder="Data inicial"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial"
            style={{ maxWidth: 140 }}
          />
          <input
            placeholder="Data final"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final"
            style={{ maxWidth: 140 }}
          />
          <select
            value={idEmpresa}
            onChange={(e) => setIdEmpresa(e.target.value ? Number(e.target.value) : '')}
            aria-label="Filtrar por empresa"
          >
            <option value="">Todas as empresas</option>
            {empresas?.map((emp) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.nomeFantasia ?? emp.razaoSocial}
              </option>
            ))}
          </select>
          <SeletorPlanoContas
            value={idPlanoContas}
            onChange={setIdPlanoContas}
            placeholder="Todos os planos de contas"
            ariaLabel="Filtrar por plano de contas"
          />
          <select value={idContaCorrente} onChange={(e) => setIdContaCorrente(e.target.value)} aria-label="Filtrar por conta corrente">
            <option value="">Todas as contas</option>
            {contas?.map((c) => (
              <option key={c.idContaCorrente} value={c.idContaCorrente}>
                {c.idContaCorrente} — {c.descricao}
              </option>
            ))}
          </select>
          <input
            placeholder="Buscar por número do documento…"
            value={busca}
            onChange={(e) => setBusca(maiusculas(e.target.value))}
            aria-label="Buscar por número do documento"
          />
          <select value={compensado} onChange={(e) => setCompensado(e.target.value as FiltroCompensado)} aria-label="Filtrar por compensado">
            <option value="TODOS">Todos</option>
            <option value="COMPENSADOS">Compensados</option>
            <option value="PENDENTES">Pendentes</option>
          </select>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : movimentos.length === 0 ? (
          <p className="muted">Nenhum lançamento encontrado.</p>
        ) : (
          <table className="table table-compacta">
            <thead>
              <tr>
                {COLUNAS.map((c) => {
                  const ativa = ordenarPor === c.chave
                  return (
                    <th
                      key={c.chave}
                      className="th-ordenavel"
                      onClick={() => ordenarPorColuna(c.chave)}
                      title="Clique para ordenar"
                      aria-sort={ativa ? (direcao === 'ASC' ? 'ascending' : 'descending') : 'none'}
                    >
                      {c.rotulo}
                      <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>
                        {ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}
                      </span>
                    </th>
                  )
                })}
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {movimentos.map((m, indice) => (
                <tr key={m.localizador} {...propsDaLinha(m.localizador, indice)}>
                  <td>{formatarData(m.dataMovimento)}</td>
                  <td className="mono">{m.idContaCorrente}</td>
                  <td className="mono">{m.numeroDocumento}</td>
                  <td>{m.creditoDebito === 'C' ? 'Crédito' : 'Débito'}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>R$ {formatarMoeda(m.valor)}</td>
                  <td>{m.compensado ? 'Sim' : 'Não'}</td>
                  <td className="acoes-cell">
                    <Link
                      className="acao-icone acao-visualizar"
                      to={`/contas-corrente-movimento/${m.localizador}/visualizar`}
                      aria-label={`Visualizar lançamento ${m.localizador}`}
                      title="Visualizar"
                    >
                      <IconeOlho />
                    </Link>
                    {/* Movimento gerado pela baixa de uma conta a pagar (2026-08-15) não se edita
                        nem se exclui por aqui — a alteração é na conta a pagar, e o movimento
                        acompanha ("apaga e regrava"). O backend também recusa (409); esconder as
                        ações é só pra não oferecer um caminho que vai falhar. */}
                    {m.idContaPagar !== null ? (
                      <span
                        className="badge badge-inativo"
                        title={`Gerado pela baixa da conta a pagar nº ${m.idContaPagar}. Para alterar ou desfazer, use Financeiro › Contas a Pagar / Pagas.`}
                      >
                        Baixa automática
                      </span>
                    ) : m.idSangria !== null ? (
                      /* Depósito de sangria (2026-08-29): o backend já recusava com 409, mas a grid
                         oferecia o lápis e a lixeira — o operador preenchia o formulário inteiro e
                         só descobria no Salvar. Mesmo tratamento da baixa automática. */
                      <span
                        className="badge badge-inativo"
                        title={`Depósito da sangria de caixa nº ${m.idSangria}. Ele acompanha a sangria e não se altera por aqui.`}
                      >
                        Sangria de caixa
                      </span>
                    ) : (
                      <>
                    {acoes.alterar && (
                      <Link
                        className="acao-icone acao-editar"
                        to={`/contas-corrente-movimento/${m.localizador}`}
                        aria-label={`Editar lançamento ${m.localizador}`}
                        title="Editar"
                      >
                        <IconeEditar />
                      </Link>
                    )}
                    {acoes.excluir && (
                      <button
                        type="button"
                        className="acao-icone acao-excluir"
                        disabled={excluir.isPending}
                        onClick={() => setMovimentoParaExcluir(m)}
                        aria-label={`Excluir lançamento ${m.localizador}`}
                        title="Excluir"
                      >
                        <IconeExcluir />
                      </button>
                    )}
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        </div>
      </div>

      {movimentos.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} lançamento{data?.totalItens === 1 ? '' : 's'}
              {isFetching && ' · atualizando…'}
            </span>
            <div className="paginacao-paginas">
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina(1)}
                disabled={pagina <= 1 || isFetching}
                aria-label="Primeira página"
                title="Primeira página"
              >
                <IconePrimeiraPagina />
              </button>
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina((p) => Math.max(1, p - 1))}
                disabled={pagina <= 1 || isFetching}
                aria-label="Página anterior"
                title="Página anterior"
              >
                <IconePaginaAnterior />
              </button>
              {paginasVisiveis(pagina, totalPaginas).map((p) => (
                <button
                  key={p}
                  type="button"
                  className={`btn ghost paginacao-numero ${p === pagina ? 'ativa' : ''}`}
                  onClick={() => setPagina(p)}
                  disabled={isFetching}
                  aria-current={p === pagina ? 'page' : undefined}
                >
                  {p}
                </button>
              ))}
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina((p) => Math.min(totalPaginas, p + 1))}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Próxima página"
                title="Próxima página"
              >
                <IconeProximaPagina />
              </button>
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina(totalPaginas)}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Última página"
                title="Última página"
              >
                <IconeUltimaPagina />
              </button>
            </div>
          </div>
        </div>
      )}

      {movimentoParaExcluir && (
        <div className="modal-overlay" onClick={fecharAoClicarNoFundo(() => setMovimentoParaExcluir(null))}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir lançamento?</h2>
            <p className="muted">
              Tem certeza que deseja excluir o lançamento <strong>{movimentoParaExcluir.numeroDocumento}</strong> (
              {formatarData(movimentoParaExcluir.dataMovimento)}, R$ {formatarMoeda(movimentoParaExcluir.valor)})?
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setMovimentoParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(movimentoParaExcluir.localizador)}
              >
                {excluir.isPending ? 'Excluindo…' : 'Excluir'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
