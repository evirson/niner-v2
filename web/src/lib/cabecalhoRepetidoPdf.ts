/**
 * Repete o cabeçalho das colunas em toda página do PDF dos relatórios.
 *
 * ## Por que precisa de código, e não de CSS (2026-09-04)
 *
 * `<thead>` + `@media print` repetiria sozinho — mas o PDF daqui **não é impressão de HTML**: cada
 * relatório é fotografado pelo html2canvas numa **imagem única** (a do Estoque tem 2638 × 20590 px)
 * e o jsPDF a fatia em páginas, deslocando a mesma imagem para cima a cada página. O cabeçalho é
 * pixel dentro dessa imagem, então aparece só onde calhou de cair — a página 1. Nas outras 12, o
 * lojista lê "R$ 71,42" sem saber se é custo unitário ou total.
 *
 * ⭐ A saída é recortar a faixa do cabeçalho da própria imagem e recolá-la no topo das páginas
 * seguintes. Recortar da imagem grande, e não capturar o `<thead>` à parte: a largura das colunas
 * é resolvida pelo layout da tabela inteira, e um `<thead>` fotografado sozinho sairia com colunas
 * de outra largura — desalinhado do conteúdo que ele deveria rotular, que é pior que não repetir.
 *
 * ⚠️ A faixa é medida **dentro do clone**, no `onclone`, nunca na tela. Dois motivos, os dois
 * mordem: o `<th>` do produto é `position: sticky`, então na tela o retângulo dele acompanha a
 * rolagem em vez de ficar na posição real; e o clone tem altura/overflow liberados
 * (`liberarAlturaDosAncestrais`), o que muda o layout em relação ao que está na tela.
 */

/**
 * Faixa do cabeçalho dentro do canvas capturado, em pixels do canvas (já com a escala aplicada).
 *
 * `fimCorpoPx` é onde a tabela acaba, e existe por um caso concreto: a Lucratividade tem **uma**
 * tabela (então a guarda de "uma tabela só" não a desliga) e ainda assim páginas que mostram
 * gráficos e KPIs. Sem saber onde a tabela termina, o cabeçalho de colunas apareceria flutuando
 * sobre um gráfico — rotulando o que não é dele.
 */
export type FaixaCabecalho = { topoPx: number; alturaPx: number; fimCorpoPx: number }

/**
 * Mede a faixa do `<thead>` no documento clonado. Chame dentro do `onclone`, **depois** de liberar
 * a altura dos ancestrais — antes disso o layout ainda é o da tela.
 *
 * Devolve `null` quando não há tabela (relatório só de gráficos, por exemplo); quem chama trata
 * isso como "não repete cabeçalho" e segue, em vez de falhar a exportação inteira.
 *
 * ⛔ Devolve `null` também quando há **mais de uma tabela**, e isso é a parte importante: DRE,
 * Lucratividade e Fluxo de Caixa são feitos de várias seções, cada uma com o próprio cabeçalho.
 * Repetir o da primeira sobre a página que mostra a terceira rotularia as colunas com nomes que
 * não são delas — o leitor confiaria num rótulo errado, que é pior do que não ter rótulo nenhum.
 * Relatório de tabela única (Estoque, Comissões, Contas a Pagar/Receber) repete; os de seções
 * seguem como antes, e o dia em que alguém quiser repetir ali vai precisar decidir *qual*
 * cabeçalho vale em cada faixa da imagem — o que é outro problema, maior que este.
 */
export function medirFaixaDoCabecalho(doc: Document, seletorAlvo: string, escala: number): FaixaCabecalho | null {
  const alvo = doc.querySelector<HTMLElement>(seletorAlvo)
  if (!alvo) return null
  const cabecalhos = alvo.querySelectorAll<HTMLElement>('thead')
  if (cabecalhos.length !== 1) return null
  const cabecalho = cabecalhos[0]

  // `position: sticky` no clone deslocaria a medida para o topo do scroll. Desliga antes de medir.
  for (const celula of Array.from(cabecalho.querySelectorAll<HTMLElement>('th'))) celula.style.position = 'static'

  const retanguloAlvo = alvo.getBoundingClientRect()
  const retanguloCabecalho = cabecalho.getBoundingClientRect()
  if (retanguloCabecalho.height <= 0) return null

  // Fim da tabela: o `<table>` inteiro, não o `<tbody>` — assim o rodapé de totais (`<tfoot>`,
  // usado no Estoque) ainda conta como parte da tabela e continua recebendo cabeçalho.
  const tabela = cabecalho.closest('table') ?? cabecalho.parentElement
  const retanguloTabela = (tabela ?? cabecalho).getBoundingClientRect()

  return {
    topoPx: (retanguloCabecalho.top - retanguloAlvo.top) * escala,
    alturaPx: retanguloCabecalho.height * escala,
    fimCorpoPx: (retanguloTabela.bottom - retanguloAlvo.top) * escala,
  }
}

/** Recorta a faixa do cabeçalho do canvas capturado e devolve como data URL. */
export function recortarFaixa(canvas: HTMLCanvasElement, faixa: FaixaCabecalho): string | null {
  const topo = Math.max(0, Math.round(faixa.topoPx))
  const altura = Math.round(faixa.alturaPx)
  if (altura <= 0 || topo + altura > canvas.height) return null

  const recorte = document.createElement('canvas')
  recorte.width = canvas.width
  recorte.height = altura
  const contexto = recorte.getContext('2d')
  if (!contexto) return null
  contexto.drawImage(canvas, 0, topo, canvas.width, altura, 0, 0, canvas.width, altura)
  return recorte.toDataURL('image/jpeg', 0.92)
}

/** Uma página: de onde começa o recorte da imagem e se leva o cabeçalho repetido no topo. */
export type PaginaDoRelatorio = { deslocamentoMm: number; repeteCabecalho: boolean }

/** Onde a tabela começa e termina, em mm da imagem já escalada para a largura da página. */
export type LimitesDaTabela = { topoMm: number; fimMm: number }

/**
 * Divide a imagem em páginas reservando espaço para o cabeçalho repetido.
 *
 * ⚠️ A altura disponível **não é a mesma em toda página**: quem leva o cabeçalho repetido perde a
 * altura dele. Por isso o número de páginas não sai de uma divisão — sai deste laço.
 *
 * ⚠️ E o cabeçalho não vai em toda página a partir da segunda: vai só nas que **continuam dentro
 * da tabela**. Uma página que mostra gráfico ou KPI não leva rótulo de coluna nenhum. Sem esta
 * conta, a Lucratividade (uma tabela curta + gráficos) ganharia um cabeçalho de colunas boiando
 * sobre um gráfico.
 */
export function dividirEmPaginas(
  alturaImagemMm: number,
  alturaUtilMm: number,
  alturaCabecalhoMm: number,
  limites: LimitesDaTabela | null,
): PaginaDoRelatorio[] {
  const paginas: PaginaDoRelatorio[] = []
  let consumido = 0
  // Tolerância de 0,01 mm: sem ela, um resíduo de arredondamento vira uma página inteira em branco.
  while (consumido < alturaImagemMm - 0.01) {
    const primeira = paginas.length === 0
    // A página começa dentro do corpo da tabela? Na primeira nunca repete — o cabeçalho já vem
    // desenhado na imagem, na posição natural dele.
    const dentroDaTabela =
      !primeira && limites !== null && consumido > limites.topoMm && consumido < limites.fimMm
    const disponivel = alturaUtilMm - (dentroDaTabela ? alturaCabecalhoMm : 0)
    // Guarda contra laço infinito: cabeçalho maior que a área útil não pode travar a exportação.
    if (disponivel <= 0) break
    paginas.push({ deslocamentoMm: consumido, repeteCabecalho: dentroDaTabela })
    consumido += disponivel
  }
  return paginas.length > 0 ? paginas : [{ deslocamentoMm: 0, repeteCabecalho: false }]
}
