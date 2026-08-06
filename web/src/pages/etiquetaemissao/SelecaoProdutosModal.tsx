import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useEu } from '../../lib/eu'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { listarCategoriasProduto } from '../../lib/categoriasProduto'
import {
  FILTROS_ENTRADAS_EMISSAO_VAZIO,
  buscarFornecedoresEmissao,
  buscarOpcoesVarianteEmissao,
  buscarPorEntradas,
  buscarPorEstoques,
  buscarProdutosEmissao,
  criarOuObterVariacaoEmissao,
  paraQuantidadeInteira,
  produtoEmissaoParaItem,
  type FiltrosEntradasEmissao,
  type FornecedorOpcaoEmissao,
  type ItemEmissao,
  type ProdutoOpcaoEmissao,
} from '../../lib/etiquetaEmissao'
import { mascararData } from '../../lib/masks'

type ModoSelecao = 'INDIVIDUAL' | 'ENTRADAS' | 'ESTOQUES'

/**
 * Modo Individual (item 1.1; revisado 2026-08-05) — busca QUALQUER produto ativo, com ou sem
 * variação/SKU já cadastrado. Se o produto usa variação de linha e/ou coluna (configurado no
 * cadastro dele — `nomeVarianteLinha`/`nomeVarianteColuna`), o respectivo seletor aparece como
 * OBRIGATÓRIO (item 2 do pedido); as opções vêm do catálogo inteiro do tenant, não só do que já
 * existe pra este produto — o "Adicionar" pode CRIAR o SKU na hora (item 1 do pedido) via
 * `criarOuObterVariacaoEmissao`, que acha a combinação se já existir ou cria (backend valida a
 * obrigatoriedade de novo, defesa em profundidade).
 */
function SelecaoIndividual({ aoAdicionar }: { aoAdicionar: (itens: ItemEmissao[]) => void }) {
  const [busca, setBusca] = useState('')
  const [produtoEscolhido, setProdutoEscolhido] = useState<ProdutoOpcaoEmissao | null>(null)
  const [idVarianteLinha, setIdVarianteLinha] = useState<number | ''>('')
  const [idVarianteColuna, setIdVarianteColuna] = useState<number | ''>('')
  const [quantidade, setQuantidade] = useState('1')
  const [erro, setErro] = useState('')

  const { data: resultados } = useQuery({
    queryKey: ['etiqueta-emissao-produtos', busca],
    queryFn: () => buscarProdutosEmissao(busca),
    enabled: busca.trim().length > 0 && !produtoEscolhido,
  })

  const { data: opcoesVariante } = useQuery({
    queryKey: ['etiqueta-emissao-variantes'],
    queryFn: buscarOpcoesVarianteEmissao,
  })

  const precisaLinha = Boolean(produtoEscolhido?.nomeVarianteLinha)
  const precisaColuna = Boolean(produtoEscolhido?.nomeVarianteColuna)

  function trocarProduto() {
    setProdutoEscolhido(null)
    setIdVarianteLinha('')
    setIdVarianteColuna('')
    setErro('')
  }

  const adicionar = useMutation({
    mutationFn: async () => {
      if (!produtoEscolhido) throw new ApiError(400, 'Escolha um produto.')
      if (precisaLinha && idVarianteLinha === '') {
        throw new ApiError(400, `Informe a variação de "${produtoEscolhido.nomeVarianteLinha}".`)
      }
      if (precisaColuna && idVarianteColuna === '') {
        throw new ApiError(400, `Informe a variação de "${produtoEscolhido.nomeVarianteColuna}".`)
      }
      const qtd = Number(quantidade)
      if (!quantidade.trim() || !Number.isInteger(qtd) || qtd < 1) {
        throw new ApiError(400, 'Informe uma quantidade de etiquetas inteira maior que zero.')
      }
      const variacao = await criarOuObterVariacaoEmissao(produtoEscolhido.idProduto, {
        idVarianteLinha: precisaLinha ? (idVarianteLinha as number) : null,
        idVarianteColuna: precisaColuna ? (idVarianteColuna as number) : null,
      })
      return produtoEmissaoParaItem(variacao, qtd)
    },
    onSuccess: (item) => {
      aoAdicionar([item])
      setBusca('')
      trocarProduto()
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível adicionar.'),
  })

  return (
    <div>
      <label htmlFor="emissao-busca-produto">Produto *</label>
      {produtoEscolhido ? (
        <p className="muted">
          <strong>{produtoEscolhido.descricao}</strong>{' '}
          <button type="button" className="btn ghost" onClick={trocarProduto}>
            Trocar
          </button>
        </p>
      ) : (
        <>
          <input
            id="emissao-busca-produto"
            autoFocus
            placeholder="Digite a descrição ou o SKU…"
            value={busca}
            onChange={(e) => setBusca(e.target.value.toUpperCase())}
          />
          {resultados && resultados.length > 0 && (
            <div className="table-wrap" style={{ maxHeight: 160, marginTop: 8 }}>
              <table className="table table-compacta">
                <tbody>
                  {resultados.map((p) => (
                    <tr key={p.idProduto} tabIndex={0} onClick={() => setProdutoEscolhido(p)}>
                      <td>{p.descricao}</td>
                      <td>{p.marca ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {produtoEscolhido && (precisaLinha || precisaColuna) && (
        <div className="form-grid" style={{ marginTop: 12 }}>
          {precisaLinha && (
            <div className="col-6">
              <label htmlFor="emissao-linha">{produtoEscolhido.nomeVarianteLinha} *</label>
              <select
                id="emissao-linha"
                value={idVarianteLinha}
                onChange={(e) => setIdVarianteLinha(e.target.value === '' ? '' : Number(e.target.value))}
              >
                <option value="">Selecione…</option>
                {(opcoesVariante?.variantesLinha ?? []).map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.descricao}
                  </option>
                ))}
              </select>
            </div>
          )}
          {precisaColuna && (
            <div className="col-6">
              <label htmlFor="emissao-coluna">{produtoEscolhido.nomeVarianteColuna} *</label>
              <select
                id="emissao-coluna"
                value={idVarianteColuna}
                onChange={(e) => setIdVarianteColuna(e.target.value === '' ? '' : Number(e.target.value))}
              >
                <option value="">Selecione…</option>
                {(opcoesVariante?.variantesColuna ?? []).map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.descricao}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      )}

      <div className="form-grid" style={{ marginTop: 12 }}>
        <div className="col-4">
          <label htmlFor="emissao-quantidade">Quantidade de Etiquetas *</label>
          <input
            id="emissao-quantidade"
            inputMode="numeric"
            value={quantidade}
            onChange={(e) => setQuantidade(e.target.value.replace(/\D/g, ''))}
            onFocus={(e) => e.target.select()}
          />
        </div>
      </div>

      {erro && <p className="erro-campo">{erro}</p>}
      <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
        <button type="button" className="btn" disabled={adicionar.isPending} onClick={() => adicionar.mutate()}>
          {adicionar.isPending ? 'Adicionando…' : 'Adicionar à Lista'}
        </button>
      </div>
    </div>
  )
}

/** Modo Por Entradas (item 1.2) — todos os filtros opcionais, mas ao menos 1 obrigatório
 * (validado também no backend). Fornecedor é uma busca simples (id+razão social). */
function SelecaoPorEntradas({ aoAdicionar }: { aoAdicionar: (itens: ItemEmissao[]) => void }) {
  const [filtros, setFiltros] = useState<FiltrosEntradasEmissao>(FILTROS_ENTRADAS_EMISSAO_VAZIO)
  const [buscaFornecedor, setBuscaFornecedor] = useState('')
  const [fornecedorEscolhido, setFornecedorEscolhido] = useState<FornecedorOpcaoEmissao | null>(null)
  const [erro, setErro] = useState('')

  const { data: fornecedores } = useQuery({
    queryKey: ['etiqueta-emissao-fornecedores', buscaFornecedor],
    queryFn: () => buscarFornecedoresEmissao(buscaFornecedor),
    enabled: buscaFornecedor.trim().length > 0 && !fornecedorEscolhido,
  })

  const buscar = useMutation({
    mutationFn: () => buscarPorEntradas({ ...filtros, idFornecedor: fornecedorEscolhido?.idFornecedor ?? null }),
    onSuccess: (resultados) => {
      if (resultados.length === 0) {
        setErro('Nenhuma entrada encontrada com esses filtros.')
        return
      }
      setErro('')
      aoAdicionar(resultados.map((r) => produtoEmissaoParaItem(r, paraQuantidadeInteira(r.quantidadeSugerida))))
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível buscar as entradas.'),
  })

  return (
    <div>
      <div className="form-grid">
        <div className="col-3">
          <label htmlFor="emissao-entrada-de">Período de Entrada De</label>
          <input
            id="emissao-entrada-de"
            placeholder="dd/mm/aaaa"
            value={filtros.dataDe}
            onChange={(e) => setFiltros((f) => ({ ...f, dataDe: mascararData(e.target.value) }))}
            onFocus={(e) => e.target.select()}
          />
        </div>
        <div className="col-3">
          <label htmlFor="emissao-entrada-ate">Período de Entrada Até</label>
          <input
            id="emissao-entrada-ate"
            placeholder="dd/mm/aaaa"
            value={filtros.dataAte}
            onChange={(e) => setFiltros((f) => ({ ...f, dataAte: mascararData(e.target.value) }))}
            onFocus={(e) => e.target.select()}
          />
        </div>
        <div className="col-3">
          <label htmlFor="emissao-fornecedor">Fornecedor</label>
          {fornecedorEscolhido ? (
            <p className="muted">
              {fornecedorEscolhido.razaoSocial}{' '}
              <button
                type="button"
                className="btn ghost"
                onClick={() => {
                  setFornecedorEscolhido(null)
                  setBuscaFornecedor('')
                }}
              >
                Trocar
              </button>
            </p>
          ) : (
            <>
              <input
                id="emissao-fornecedor"
                placeholder="Buscar fornecedor…"
                value={buscaFornecedor}
                onChange={(e) => setBuscaFornecedor(e.target.value.toUpperCase())}
              />
              {fornecedores && fornecedores.length > 0 && (
                <div className="table-wrap" style={{ maxHeight: 140 }}>
                  <table className="table table-compacta">
                    <tbody>
                      {fornecedores.map((f) => (
                        <tr key={f.idFornecedor} tabIndex={0} onClick={() => setFornecedorEscolhido(f)}>
                          <td>{f.razaoSocial}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </div>
        <div className="col-3">
          <label htmlFor="emissao-nota-fiscal">Nº Nota Fiscal</label>
          <input
            id="emissao-nota-fiscal"
            inputMode="numeric"
            value={filtros.notaFiscal}
            onChange={(e) => setFiltros((f) => ({ ...f, notaFiscal: e.target.value.replace(/\D/g, '') }))}
          />
        </div>
      </div>
      {erro && <p className="erro-campo">{erro}</p>}
      <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
        <button type="button" className="btn" disabled={buscar.isPending} onClick={() => buscar.mutate()}>
          {buscar.isPending ? 'Buscando…' : 'Buscar e Adicionar'}
        </button>
      </div>
    </div>
  )
}

/** Modo Por Estoques (item 1.3) — Empresa obrigatória (ADMIN escolhe explicitamente; OPERADOR
 * sempre a própria empresa ativa, sem seletor — mesmo padrão de todo relatório do sistema). */
function SelecaoPorEstoques({ aoAdicionar }: { aoAdicionar: (itens: ItemEmissao[]) => void }) {
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'
  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })
  const { data: categorias } = useQuery({ queryKey: ['categorias-produto'], queryFn: listarCategoriasProduto })
  const [idEmpresa, setIdEmpresa] = useState<number | ''>('')
  const [idCategoria, setIdCategoria] = useState<number | ''>('')
  const [erro, setErro] = useState('')

  const buscar = useMutation({
    mutationFn: () => {
      if (ehAdmin && idEmpresa === '') {
        throw new ApiError(400, 'Selecione a empresa.')
      }
      return buscarPorEstoques(ehAdmin ? (idEmpresa as number) : null, idCategoria === '' ? null : (idCategoria as number))
    },
    onSuccess: (resultados) => {
      if (resultados.length === 0) {
        setErro('Nenhum produto em estoque com esses filtros.')
        return
      }
      setErro('')
      aoAdicionar(resultados.map((r) => produtoEmissaoParaItem(r, paraQuantidadeInteira(r.quantidadeSugerida))))
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível buscar o estoque.'),
  })

  return (
    <div>
      <div className="form-grid">
        {ehAdmin && (
          <div className="col-6">
            <label htmlFor="emissao-empresa">Empresa *</label>
            <select
              id="emissao-empresa"
              value={idEmpresa}
              onChange={(e) => setIdEmpresa(e.target.value === '' ? '' : Number(e.target.value))}
            >
              <option value="">Selecione…</option>
              {(empresas ?? []).map((e) => (
                <option key={e.idEmpresa} value={e.idEmpresa}>
                  {e.nomeFantasia ?? e.razaoSocial}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="col-6">
          <label htmlFor="emissao-categoria">Categoria do Produto</label>
          <select
            id="emissao-categoria"
            value={idCategoria}
            onChange={(e) => setIdCategoria(e.target.value === '' ? '' : Number(e.target.value))}
          >
            <option value="">Todas</option>
            {(categorias ?? []).map((c) => (
              <option key={c.idCategoria} value={c.idCategoria}>
                {c.nomeCategoria}
              </option>
            ))}
          </select>
        </div>
      </div>
      {erro && <p className="erro-campo">{erro}</p>}
      <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
        <button type="button" className="btn" disabled={buscar.isPending} onClick={() => buscar.mutate()}>
          {buscar.isPending ? 'Buscando…' : 'Buscar e Adicionar'}
        </button>
      </div>
    </div>
  )
}

/**
 * Popup de seleção de produtos (item 1 do pedido, 2026-08-05) — 3 modos alternativos (segmentado
 * no topo), cada um só adiciona à grade do pai (`aoAdicionar`), sem fechar o popup sozinho —
 * deixa o usuário combinar várias buscas/adições antes de fechar.
 */
export default function SelecaoProdutosModal({
  aoFechar,
  aoAdicionar,
}: {
  aoFechar: () => void
  aoAdicionar: (itens: ItemEmissao[]) => void
}) {
  const [modo, setModo] = useState<ModoSelecao>('INDIVIDUAL')

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Selecionar Produtos" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Selecionar Produtos</h2>

        <div className="editor-etiqueta-segmentado" style={{ marginBottom: 16 }}>
          <button type="button" className={`btn ghost ${modo === 'INDIVIDUAL' ? 'ativa' : ''}`} onClick={() => setModo('INDIVIDUAL')}>
            Individual
          </button>
          <button type="button" className={`btn ghost ${modo === 'ENTRADAS' ? 'ativa' : ''}`} onClick={() => setModo('ENTRADAS')}>
            Por Entradas
          </button>
          <button type="button" className={`btn ghost ${modo === 'ESTOQUES' ? 'ativa' : ''}`} onClick={() => setModo('ESTOQUES')}>
            Por Estoques
          </button>
        </div>

        {modo === 'INDIVIDUAL' && <SelecaoIndividual aoAdicionar={aoAdicionar} />}
        {modo === 'ENTRADAS' && <SelecaoPorEntradas aoAdicionar={aoAdicionar} />}
        {modo === 'ESTOQUES' && <SelecaoPorEstoques aoAdicionar={aoAdicionar} />}

        <div className="ajuda-rodape">
          <button type="button" className="btn" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  )
}
