import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useSearchParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEtiqueta, IconeExcluir } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import {
  MM_PARA_PX_IMPRESSAO,
  passoVertical,
  xDaColuna,
  type EtiquetaConfig,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'
import {
  mesclarItensEmissao,
  montarSequenciaImpressao,
  totalDeEtiquetas,
  type ItemEmissao,
  type OrigemEntrada,
} from '../../lib/etiquetaEmissao'
import { useEu } from '../../lib/eu'
import CampoEtiquetaVisual from '../etiquetaconfig/CampoEtiquetaVisual'
import EscolherModeloModal from './EscolherModeloModal'
import SelecaoProdutosModal from './SelecaoProdutosModal'

/** Distribui a sequência achatada de produtos (1 por posição de etiqueta) pelas colunas/linhas
 * do rolo — mesma lógica de `linhasParaImprimir` (etiquetaConfig.ts), mas carregando o PRODUTO
 * de cada posição junto (lá é sempre o mesmo produto de exemplo; aqui cada etiqueta pode ser de
 * um produto diferente). */
function linhasComProdutos(
  sequencia: ProdutoExemplo[],
  numeroColunas: number,
): Array<Array<{ indiceColuna: number; produto: ProdutoExemplo }>> {
  if (numeroColunas <= 0) return []
  const linhas: Array<Array<{ indiceColuna: number; produto: ProdutoExemplo }>> = []
  for (let i = 0; i < sequencia.length; i += numeroColunas) {
    const fatia = sequencia.slice(i, i + numeroColunas)
    linhas.push(fatia.map((produto, indiceColuna) => ({ indiceColuna, produto })))
  }
  return linhas
}

/** Lê os params de "Emitir Etiquetas desta Nota". Devolve `null` quando a tela foi aberta
 * normalmente pelo menu — só há origem quando veio um `idFornecedor` numérico. A nota é
 * opcional: entrada sem número de nota (compra manual) ainda filtra pelo fornecedor. */
function origemDaEntrada(params: URLSearchParams): OrigemEntrada | null {
  const idFornecedor = Number(params.get('idFornecedor'))
  if (!Number.isInteger(idFornecedor) || idFornecedor <= 0) return null
  return {
    idFornecedor,
    nomeFornecedor: params.get('nomeFornecedor') ?? '',
    notaFiscal: params.get('notaFiscal') ?? '',
  }
}

/**
 * Emissão de Etiqueta de Produtos (2026-08-05, docs/telas/etiqueta-emissao.md) — 1ª implementação
 * real da área (era só placeholder desde 2026-08-04). Seleciona produtos/quantidades (popup, 3
 * modos) → grade local editável (excluir/ajustar quantidade) → escolhe o modelo já criado em
 * Configuração de Etiqueta → imprime em lote (client-side, mesmo mecanismo de "Testar Impressão"
 * generalizado pra produtos diferentes por etiqueta).
 */
export default function EtiquetaEmissaoForm() {
  // Chegada por "Emitir Etiquetas desta Nota" (EntradaMercadoriaForm). Os params existiam desde
  // 2026-08-11 mas esta tela NÃO os lia — o operador caía aqui com a lista vazia e tinha que
  // redigitar fornecedor e nota. Desde 2026-08-14 abrem o popup já no modo Por Entradas, com os
  // dois filtros preenchidos; basta clicar em Localizar.
  // Nome IMPRESSO na etiqueta: empresa.cfg_nome_etiqueta da empresa da SESSAO (2026-08-21).
  // A empresa escolhida no popup de selecao e so filtro de estoque, nao emitente.
  const { data: eu } = useEu()
  const nomeEmpresaImpressa = eu?.empresa.nomeEtiqueta || 'NOME DA EMPRESA'

  const [params] = useSearchParams()
  const origemEntrada = origemDaEntrada(params)

  const [itens, setItens] = useState<ItemEmissao[]>([])
  const [selecaoAberta, setSelecaoAberta] = useState(origemEntrada !== null)
  const [modeloAberto, setModeloAberto] = useState(false)
  const [impressao, setImpressao] = useState<{ config: EtiquetaConfig; sequencia: ProdutoExemplo[] } | null>(null)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  function adicionarItens(novos: ItemEmissao[]) {
    setItens((atual) => mesclarItensEmissao(atual, novos))
    setToast({ texto: `${novos.length} item${novos.length === 1 ? '' : 's'} adicionado${novos.length === 1 ? '' : 's'} à lista.`, tipo: 'sucesso' })
  }

  function removerItem(idVariacao: number) {
    setItens((atual) => atual.filter((i) => i.idVariacao !== idVariacao))
  }

  function limparLista() {
    setItens([])
  }

  function alterarQuantidade(idVariacao: number, quantidade: number) {
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, quantidade } : i)))
  }

  const total = totalDeEtiquetas(itens)

  function confirmarModelo(config: EtiquetaConfig) {
    setModeloAberto(false)
    setImpressao({ config, sequencia: montarSequenciaImpressao(itens) })
  }

  const fileirasImpressao = impressao ? linhasComProdutos(impressao.sequencia, impressao.config.numeroColunas) : []

  /**
   * Mesmo mecanismo de `@page` dinâmico de `EtiquetaConfigForm.tsx` (Testar Impressão) — largura
   * do rolo vem do modelo escolhido, não é fixa.
   *
   * <p>⚠️ A altura da página é o **passo do rolo**: uma fileira por página (2026-08-21, tarde).
   * Impressora de etiqueta não imprime folha — ela encaixa cada página no adesivo pelo sensor de
   * gap. Mandando a folha inteira, quem decide a origem vertical vira o driver, e um papel de
   * 152,4 mm configurado nele fatiava o trabalho fora do passo: 5 fileiras em branco e a primeira
   * etiqueta 3 mm fora do adesivo. Detalhes no comentário de `etiquetaConfig.ts`.
   */
  useEffect(() => {
    if (!impressao) return
    const estilo = document.createElement('style')
    estilo.textContent =
      `@page etiqueta-emissao-impressao { size: ${impressao.config.larguraRoloMm}mm ${passoVertical(impressao.config)}mm; margin: 0; }` +
      `.etiqueta-rolo-imprimir { page: etiqueta-emissao-impressao; }`
    document.head.appendChild(estilo)
    // No <html> TAMBÉM: é lá que mora o `overflow: hidden` do shell, e sem destravá-lo o navegador
    // corta tudo depois da primeira página em vez de paginar (ver styles.css).
    document.documentElement.classList.add('imprimindo-etiquetas')
    document.body.classList.add('imprimindo-etiquetas')
    const aoTerminarImpressao = () => setImpressao(null)
    window.addEventListener('afterprint', aoTerminarImpressao)
    const temporizador = window.setTimeout(() => window.print(), 60)
    return () => {
      window.clearTimeout(temporizador)
      window.removeEventListener('afterprint', aoTerminarImpressao)
      document.documentElement.classList.remove('imprimindo-etiquetas')
      document.body.classList.remove('imprimindo-etiquetas')
      estilo.remove()
    }
  }, [impressao])

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEtiqueta size={34} />
            <h1>Emissão de Etiqueta de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.etiquetaemissao.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
          <section className="section">
            <div className="ajuda-rodape" style={{ justifyContent: 'space-between', marginTop: 0 }}>
              <p className="section-label" style={{ margin: 0 }}>
                Produtos Selecionados
                {itens.length > 0 &&
                  ` (${itens.length} ${itens.length === 1 ? 'item' : 'itens'} · ${total} etiqueta${total === 1 ? '' : 's'})`}
              </p>
              <div style={{ display: 'flex', gap: 8 }}>
                <button type="button" className="btn ghost" disabled={itens.length === 0} onClick={limparLista}>
                  Limpar Lista
                </button>
                <button type="button" className="btn ghost" onClick={() => setSelecaoAberta(true)}>
                  ＋ Selecionar Produtos
                </button>
              </div>
            </div>

            <div className="table-wrap" style={{ marginTop: 12, maxHeight: 420 }}>
              <table className="table">
                <thead>
                  <tr>
                    <th>Produto</th>
                    <th>Marca</th>
                    <th>Variação</th>
                    <th>SKU</th>
                    <th>Quantidade</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {itens.length === 0 ? (
                    <tr>
                      <td colSpan={6}>
                        <p className="muted" style={{ margin: '12px 0' }}>
                          Nenhum produto selecionado ainda.
                        </p>
                      </td>
                    </tr>
                  ) : (
                    itens.map((item) => (
                      <tr key={item.idVariacao}>
                        <td>{item.descricao}</td>
                        <td>{item.marca ?? '—'}</td>
                        <td>{[item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' · ') || '—'}</td>
                        <td className="mono">{item.sku}</td>
                        <td>
                          <input
                            inputMode="numeric"
                            style={{ width: 90 }}
                            value={item.quantidade === 0 ? '' : String(item.quantidade)}
                            onFocus={(e) => e.target.select()}
                            onChange={(e) => {
                              const digitos = e.target.value.replace(/\D/g, '')
                              alterarQuantidade(item.idVariacao, digitos === '' ? 0 : Number(digitos))
                            }}
                            onBlur={() => {
                              if (item.quantidade < 1) alterarQuantidade(item.idVariacao, 1)
                            }}
                          />
                        </td>
                        <td>
                          <button
                            type="button"
                            className="acao-icone acao-excluir"
                            title="Remover"
                            aria-label="Remover"
                            onClick={() => removerItem(item.idVariacao)}
                          >
                            <IconeExcluir />
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </section>

          <section className="section">
            <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
              <button type="button" className="btn" disabled={itens.length === 0} onClick={() => setModeloAberto(true)}>
                Emitir Etiquetas
              </button>
            </div>
          </section>
        </div>
      </div>

      {selecaoAberta && (
        <SelecaoProdutosModal
          origemEntrada={origemEntrada}
          aoFechar={() => setSelecaoAberta(false)}
          aoAdicionar={adicionarItens}
        />
      )}

      {modeloAberto && (
        <EscolherModeloModal totalEtiquetas={total} aoFechar={() => setModeloAberto(false)} aoConfirmar={confirmarModelo} />
      )}

      {/* Fora da tela (só existe pro navegador imprimir) — mesma técnica de isolamento
          `.etiqueta-rolo-imprimir` (styles.css) do Teste de Impressão, generalizada aqui pra um
          produto DIFERENTE por posição de etiqueta (não N cópias do mesmo). Vai por PORTAL para o
          `<body>` pelo mesmo motivo de lá: paginar (uma fileira por página) exige fluxo normal. */}
      {impressao && createPortal(
        <div
          className="etiqueta-rolo-imprimir"
          style={{ width: impressao.config.larguraRoloMm * MM_PARA_PX_IMPRESSAO }}
        >
          {fileirasImpressao.map((linha, indiceLinha) => (
            <div
              key={indiceLinha}
              style={{
                // Uma fileira = UMA PÁGINA (2026-08-21, tarde), com o sensor de gap da impressora
                // encaixando cada uma no adesivo. Altura do bloco = a da ETIQUETA, não a do passo:
                // bloco tão alto quanto a página é o caso limite da paginação e um sub-pixel a
                // mais nasce uma página em branco entre cada etiqueta. O gap quem dá é o `@page`.
                position: 'relative',
                width: impressao.config.larguraRoloMm * MM_PARA_PX_IMPRESSAO,
                height: impressao.config.alturaEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                breakAfter: indiceLinha === fileirasImpressao.length - 1 ? 'auto' : 'page',
                breakInside: 'avoid',
              }}
            >
              {linha.map(({ indiceColuna, produto }) => (
                <div
                  key={indiceColuna}
                  style={{
                    position: 'absolute',
                    // x derivado (V057): margem + i x (largura + espaco entre colunas).
                    left: xDaColuna(impressao.config, indiceColuna) * MM_PARA_PX_IMPRESSAO,
                    top: 0,
                    width: impressao.config.larguraEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                    height: impressao.config.alturaEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                    background: '#fff',
                  }}
                >
                  {impressao.config.campos.map((c) => (
                    <CampoEtiquetaVisual
                      key={c.campo}
                      campo={c}
                      escalaPxPorMm={MM_PARA_PX_IMPRESSAO}
                      produtoExemplo={produto}
                      nomeEmpresaExemplo={nomeEmpresaImpressa}
                    />
                  ))}
                </div>
              ))}
            </div>
          ))}
        </div>,
        document.body,
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
