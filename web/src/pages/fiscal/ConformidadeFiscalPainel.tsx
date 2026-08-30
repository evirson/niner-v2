import CabecalhoModal from '../../components/CabecalhoModal'
import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeConformidade, IconePaginaAnterior, IconeProximaPagina } from '../../components/Icones'
import { useAuth } from '../../lib/auth'
import {
  buscarDrillDown,
  buscarPainelConformidade,
  rotaDeCorrecao,
  type CategoriaConformidade,
  type CategoriaConformidadeResponse,
} from '../../lib/conformidadeFiscal'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import { fecharAoClicarNoFundo } from '../../lib/modais'

/**
 * Conformidade Fiscal (docs/telas/fiscal-conformidade.md, bloco B3) — painel de diagnóstico
 * somente-leitura. Foge do padrão de lista de propósito: sem popup de filtros (um parâmetro só,
 * a empresa, com default), sem paginação no nível superior (é um painel de contagens; a
 * paginação existe só dentro do drill-down de cada categoria).
 */
export default function ConformidadeFiscalPainel() {
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [categoriaAberta, setCategoriaAberta] = useState<CategoriaConformidadeResponse | null>(null)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: painel, isLoading, isError } = useQuery({
    queryKey: ['fiscal-conformidade', idEmpresa],
    queryFn: () => buscarPainelConformidade(idEmpresa as number),
    enabled: idEmpresa !== null,
  })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeConformidade size={34} />
            <h1>Conformidade Fiscal</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.conformidade.tela" />
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div>
            <label htmlFor="seletor-empresa-conformidade">Empresa</label>
            <select
              id="seletor-empresa-conformidade"
              autoFocus
              value={idEmpresa ?? ''}
              onChange={(e) => setIdEmpresa(Number(e.target.value))}
            >
              {(empresas ?? []).map((emp) => (
                <option key={emp.idEmpresa} value={emp.idEmpresa}>
                  {emp.razaoSocial}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {/* ⚠️ O ramo de ERRO vem ANTES do de carregamento. Com a consulta em falha, `isLoading`
            é false e `painel` é undefined — a condição antiga caía no primeiro ramo PARA SEMPRE, e
            a tela ficava em "Carregando…" indefinidamente, sem erro visível para o lojista. Ele
            recarrega, espera, liga para o suporte. */}
        {isError ? (
          <p className="erro">
            Não foi possível carregar a conformidade fiscal desta empresa. Tente de novo em instantes.
          </p>
        ) : isLoading || !painel ? (
          <p className="muted">Carregando…</p>
        ) : (
          <>
            <div className={painel.pronto ? 'tarja-sucesso' : 'tarja-perigo'}>
              {painel.pronto ? '✓' : '⚠'} {painel.veredito}
            </div>

            <div className="menu-cards">
              {painel.categorias.map((cat) => (
                <button
                  key={cat.categoria}
                  type="button"
                  className="card-categoria"
                  onClick={() => setCategoriaAberta(cat)}
                >
                  <div className="topbar-tela" style={{ marginBottom: 4 }}>
                    <strong>{cat.rotulo}</strong>
                    <span
                      className={`badge ${
                        cat.quantidadePendencias === 0
                          ? 'badge-sucesso'
                          : cat.bloqueia
                            ? 'badge-perigo'
                            : 'badge-aviso'
                      }`}
                    >
                      {cat.quantidadePendencias === 0 ? 'OK' : cat.bloqueia ? 'Bloqueia' : 'Avisa'}
                    </span>
                  </div>
                  <p className="muted" style={{ margin: 0 }}>
                    {cat.quantidadePendencias} pendência{cat.quantidadePendencias === 1 ? '' : 's'}
                  </p>
                </button>
              ))}
            </div>
          </>
        )}
      </div>

      {categoriaAberta && idEmpresa !== null && (
        <DrillDownModal
          idEmpresa={idEmpresa}
          categoria={categoriaAberta}
          aoFechar={() => setCategoriaAberta(null)}
        />
      )}
    </div>
  )
}

function DrillDownModal({
  idEmpresa,
  categoria,
  aoFechar,
}: {
  idEmpresa: number
  categoria: CategoriaConformidadeResponse
  aoFechar: () => void
}) {
  const [pagina, setPagina] = useState(1)

  const { data, isLoading, isFetching, isError: erroDrilldown } = useQuery({
    queryKey: ['fiscal-conformidade-drilldown', idEmpresa, categoria.categoria, pagina],
    queryFn: () => buscarDrillDown(idEmpresa, categoria.categoria as CategoriaConformidade, pagina),
    placeholderData: (anterior) => anterior,
  })

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div className="modal modal-largo" role="dialog" aria-label={`Pendências — ${categoria.rotulo}`} onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo=<>{categoria.rotulo}</> aoFechar={aoFechar} />

        {/* ⛔ "Nenhuma pendência." é uma AFIRMAÇÃO, e com a consulta em falha ela era falsa: o
            card atrás diz "7 pendências — bloqueia", o ADMIN clica para ver quais são, o drill-down
            responde 500, e o popup responde que está tudo certo. A tela existe para dizer "posso
            emitir nota?" — respondendo "sim" com base numa requisição que falhou. */}
        {erroDrilldown ? (
          <p className="erro">Não foi possível carregar as pendências desta categoria. Tente de novo.</p>
        ) : isLoading ? (
          <p className="muted">Carregando…</p>
        ) : !data || data.itens.length === 0 ? (
          <p className="muted">Nenhuma pendência.</p>
        ) : (
          <>
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Registro</th>
                  <th>Problema</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {data.itens.map((item) => {
                  const rota = rotaDeCorrecao(item)
                  return (
                    <tr key={item.idRegistro}>
                      <td>{item.identificador}</td>
                      <td>{item.problema}</td>
                      <td>
                        {rota && (
                          <Link className="btn ghost" to={rota} target="_blank" rel="noreferrer">
                            Corrigir
                          </Link>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>

            {data.totalPaginas > 1 && (
              <div className="paginacao-bar" style={{ marginTop: 12 }}>
                <span className="muted">
                  {data.totalItens} pendência{data.totalItens === 1 ? '' : 's'}
                  {isFetching && ' · atualizando…'}
                </span>
                <div className="paginacao-paginas">
                  <button
                    type="button"
                    className="btn ghost paginacao-seta"
                    onClick={() => setPagina((p) => Math.max(1, p - 1))}
                    disabled={pagina <= 1 || isFetching}
                    aria-label="Página anterior"
                  >
                    <IconePaginaAnterior />
                  </button>
                  <span>
                    {pagina} / {data.totalPaginas}
                  </span>
                  <button
                    type="button"
                    className="btn ghost paginacao-seta"
                    onClick={() => setPagina((p) => Math.min(data.totalPaginas, p + 1))}
                    disabled={pagina >= data.totalPaginas || isFetching}
                    aria-label="Próxima página"
                  >
                    <IconeProximaPagina />
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        <div className="ajuda-rodape">

        </div>
      </div>
    </div>
  )
}
