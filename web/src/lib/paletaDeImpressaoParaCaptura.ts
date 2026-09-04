/**
 * Força o **tema claro** no documento clonado que o html2canvas renderiza — usado por todo
 * `lib/*Captura.ts` (relatórios em PDF).
 *
 * ## Por que existe (2026-08-26)
 *
 * Relatório impresso não pode sair no tema escuro: gasta tinta, fica ilegível em papel comum e
 * não é o que o lojista manda para o contador. Cada módulo de captura já fazia
 * `doc.documentElement.setAttribute('data-theme', 'light')` no `onclone`, e isso **quase sempre**
 * funcionava — o dono do produto reportou o PDF saindo escuro, e o defeito foi reproduzido uma vez
 * e não reproduziu em quatro tentativas seguintes.
 *
 * ⚠️ **O problema é a natureza do mecanismo, não um bug pontual.** Trocar o atributo é uma aposta
 * na cascata: o clone roda dentro de um iframe que **herda `prefers-color-scheme: dark` do sistema
 * operacional** (medido: `matchMedia` responde `true` lá dentro), então a paleta escura está viva
 * no clone e só perde por especificidade. Basta o atributo não pegar — por ordem, por timing, por
 * uma folha injetada depois — para o relatório inteiro sair escuro **sem nenhum erro**. Medido: um
 * clone com `data-theme='dark'` produz `--surface: #1a2225` e cards pretos.
 *
 * ⭐ A saída é não depender da cascata: além do atributo, **declarar a paleta clara direto no
 * clone com `!important`**. Medido nos dois piores casos — clone forçado em `data-theme='dark'` e
 * clone sem atributo nenhum com o SO em escuro — os dois passaram a render `--surface: #ffffff`.
 *
 * ## Por que os valores vêm do CSS, e não de uma cópia
 *
 * A paleta é lida de `:root[data-theme='light']` em `styles.css`, que continua sendo a **única**
 * fonte da verdade — uma cópia literal aqui divergiria no dia em que alguém ajustasse uma cor lá e
 * não aqui, e o PDF passaria a ter uma paleta própria que ninguém escolheu. A cópia literal existe
 * só como **fallback** para o caso de as regras não serem legíveis (`cssRules` lança em folha
 * cross-origin).
 *
 * ⚠️ Isto **não vence a extensão Dark Reader**, que reescreve os tokens no documento do usuário —
 * é comportamento da extensão, não do produto (ver `styles.css`, comentário de `color-scheme`).
 */

/** Espelho de `:root[data-theme='light']` (`styles.css`). Só entra em uso se o CSS não for legível. */
const PALETA_CLARA_FALLBACK = [
  'color-scheme:light',
  '--ground:#f5f4f0',
  '--surface:#ffffff',
  '--surface-2:#edece6',
  '--field-bg:#e4e6e2',
  '--field-text:#20262a',
  '--label-color:#5c6660',
  '--ink:#20262a',
  '--ink-muted:#5c6660',
  '--accent:#1f6f6b',
  '--accent-ink:#ffffff',
  '--line:#dee2dc',
  '--line-strong:#b9beb5',
  '--danger:#a63d29',
  '--info:#3060c0',
  '--sucesso:#217a33',
  '--aviso:#b45309',
  '--accent-rgb:31, 111, 107',
  '--accent-ink-rgb:255, 255, 255',
  '--danger-rgb:166, 61, 41',
  '--sucesso-rgb:33, 122, 51',
  '--aviso-rgb:180, 83, 9',
]
  .map((d) => `${d} !important;`)
  .join('')

/**
 * Some do PDF com o que é estado transitório da TELA e não faz sentido no papel.
 *
 * ⚠️ Achado gerando o PDF de verdade (2026-08-26): o aviso "Atualizando…", que aparece enquanto a
 * consulta refaz, foi **impresso no relatório** — em 6 telas ele mora dentro do elemento que a
 * captura fotografa. Marcar com `data-sem-impressao` é melhor que condicionar a renderização em
 * cada tela: quem escrever a próxima só precisa marcar o elemento, sem saber que existe captura.
 */
const REGRA_SEM_IMPRESSAO = '[data-sem-impressao]{display:none !important;}'

/**
 * Devolve o valor de uma custom property dentro de um bloco de declarações já montado.
 * Usado para reemitir a cor como **literal** — ver `regrasDoGrafico`.
 */
function valorDoToken(declaracoes: string, token: string): string | null {
  const achado = new RegExp(`${token}\\s*:\\s*([^;!]+)`).exec(declaracoes)
  return achado ? achado[1].trim() : null
}

/** Monta `{'--accent': '#1f6f6b', …}` a partir do bloco de declarações já lido. */
function mapaDeTokens(declaracoes: string): Map<string, string> {
  const mapa = new Map<string, string>()
  for (const par of declaracoes.split(';')) {
    const corte = par.indexOf(':')
    if (corte < 0) continue
    const nome = par.slice(0, corte).trim()
    if (!nome.startsWith('--')) continue
    mapa.set(nome, par.slice(corte + 1).replace('!important', '').trim())
  }
  return mapa
}

/**
 * Resolve `var(--x)` e `var(--x, alternativa)` (inclusive aninhado) contra a paleta clara.
 * Devolve `null` quando não conhece o token — aí o valor original fica como está.
 */
function resolverVar(expressao: string, tokens: Map<string, string>): string | null {
  const achado = /^var\(\s*(--[\w-]+)\s*(?:,\s*(.+)\s*)?\)$/.exec(expressao.trim())
  if (!achado) return null
  const [, token, alternativa] = achado
  const valor = tokens.get(token)
  if (valor) return valor
  if (!alternativa) return null
  return alternativa.trim().startsWith('var(') ? resolverVar(alternativa, tokens) : alternativa.trim()
}

/**
 * Gera uma regra CSS por cor de SVG usada na página, repintando-a com o valor da paleta **clara**.
 *
 * ⚠️ Duas medições de 2026-08-26 explicam por que é assim, e não do jeito óbvio:
 *
 * 1. Dentro do clone, `fill="var(--accent)"` de SVG **continua resolvendo para a cor do tema
 *    escuro** mesmo com `--accent` já valendo a cor clara no `:root` — o Chrome não reinvalida o
 *    estilo do SVG adotado. Sem isto, as barras e os rótulos dos eixos saem na paleta escura (os
 *    rótulos, em cinza-claro sobre papel branco, ficam quase ilegíveis).
 * 2. ⛔ **Reescrever o atributo não adianta**: medido, `setAttribute('fill', '#1f6f6b')` no clone
 *    grava o atributo mas o **estilo computado continua o antigo**, e é o computado que o
 *    html2canvas pinta — a captura saía idêntica com e sem a reescrita. Foi uma tentativa que
 *    parecia funcionar e não fazia nada. CSS, por outro lado, **muda o computado** (provado pela
 *    regra do texto dos eixos), e ainda vence atributo de apresentação, que tem especificidade zero.
 *
 * ⭐ Uma regra por **valor de atributo encontrado**, casado literalmente (`[fill="var(--accent)"]`),
 * em vez de uma cor só para tudo: o Fluxo de Caixa distingue entrada de saída por `--sucesso` ×
 * `--danger`, e pintar todas as barras de `--accent` apagaria a informação do gráfico.
 */
function regrasDeCorDeSvg(doc: Document, declaracoes: string): string {
  const tokens = mapaDeTokens(declaracoes)
  if (tokens.size === 0) return ''
  const regras: string[] = []
  const jaFeitos = new Set<string>()
  for (const elemento of Array.from(doc.querySelectorAll('[fill],[stroke]'))) {
    for (const atributo of ['fill', 'stroke'] as const) {
      const valor = elemento.getAttribute(atributo)
      if (!valor || !valor.trim().startsWith('var(')) continue
      const chave = `${atributo}=${valor}`
      if (jaFeitos.has(chave)) continue
      jaFeitos.add(chave)
      const resolvido = resolverVar(valor, tokens)
      // Aspas duplas quebrariam o seletor; nenhum valor nosso tem, mas não custa recusar.
      if (!resolvido || valor.includes('"')) continue
      regras.push(`[${atributo}="${valor}"]{${atributo}:${resolvido} !important;}`)
    }
  }
  return regras.join('')
}

/**
 * Rede de segurança para o texto dos gráficos: repinta com a cor clara **literal**.
 *
 * Complementa `resolverCoresDeSvgNoClone` — aquela reescreve o `var()` que está **no atributo**,
 * esta cobre o texto cuja cor chega por CSS herdado, sem atributo nenhum para reescrever. CSS
 * vence atributo de apresentação (especificidade zero), então as duas convivem sem conflito.
 */
function regrasDoGrafico(declaracoes: string): string {
  const inkMuted = valorDoToken(declaracoes, '--ink-muted')
  return inkMuted ? `.recharts-surface text,.recharts-surface tspan{fill:${inkMuted} !important;}` : ''
}

/**
 * Lê as declarações de `:root[data-theme='light']` das folhas da página, já com `!important`.
 * Devolve `null` se não achar — aí vale o fallback.
 */
function lerPaletaClaraDoCss(): string | null {
  try {
    for (const folha of Array.from(document.styleSheets)) {
      let regras: CSSRuleList
      // Folha de outra origem lança ao ler `cssRules`; é esperado, só pula.
      try {
        regras = folha.cssRules
      } catch {
        continue
      }
      for (const regra of Array.from(regras)) {
        const estilo = (regra as CSSStyleRule).style
        const seletor = (regra as CSSStyleRule).selectorText
        // O navegador serializa com aspas duplas (`[data-theme="light"]`); normaliza antes de comparar.
        if (!estilo || !seletor || !seletor.replace(/"/g, "'").includes("[data-theme='light']")) continue
        let saida = ''
        for (let i = 0; i < estilo.length; i++) {
          const propriedade = estilo.item(i)
          saida += `${propriedade}:${estilo.getPropertyValue(propriedade)} !important;`
        }
        if (saida) return saida
      }
    }
  } catch {
    // Qualquer surpresa aqui não pode impedir a geração do PDF — o fallback cobre.
  }
  return null
}

/**
 * Chame no `onclone` do html2canvas, antes de qualquer outro ajuste no clone.
 *
 * ⚠️ Faz as **duas** coisas de propósito: o atributo mantém coerente o que `getComputedStyle`
 * responde dentro do clone (código que inspecione o clone continua vendo o tema claro), e o
 * `<style>` é o que garante o resultado quando o atributo não basta.
 */
export function forcarTemaClaroNoClone(doc: Document): void {
  doc.documentElement.setAttribute('data-theme', 'light')
  const declaracoes = lerPaletaClaraDoCss() ?? PALETA_CLARA_FALLBACK
  const estilo = doc.createElement('style')
  estilo.setAttribute('data-origem', 'captura-pdf-tema-claro')
  estilo.textContent =
    `:root{${declaracoes}}` +
    REGRA_SEM_IMPRESSAO +
    regrasDoGrafico(declaracoes) +
    // Lê o clone para descobrir quais cores de SVG existem nesta tela — por isso vem antes do
    // `appendChild`, e não numa constante.
    regrasDeCorDeSvg(doc, declaracoes)
  // No fim do <head>: empata em especificidade com o resto e vence por ordem, e o `!important`
  // resolve o que a ordem não resolveria.
  doc.head.appendChild(estilo)
}

/**
 * Espera os gráficos pararem de se mexer antes de fotografar a tela.
 *
 * ⚠️ Achado em 2026-08-26 gerando o PDF de verdade: exportando **logo depois** de gerar o
 * relatório (~2 s), o recharts ainda está animando a barra de 0 até o valor, e o PDF sai com os
 * **gráficos vazios** — só os eixos. Não aparece na tela (lá a animação termina e fica certo) nem
 * em teste; só no papel, e de forma intermitente, que é o pior jeito de um defeito existir.
 *
 * ⭐ Espera o desenho **estabilizar**, em vez de desligar a animação ou dormir um tempo fixo:
 * a animação continua existindo na tela (é decisão visual do dono do produto, não minha), e um
 * `setTimeout` fixo seria chute — curto demais captura no meio, longo demais atrasa todo mundo.
 *
 * Compara a geometria das barras entre quadros; dois quadros iguais bastam.
 *
 * ⛔ **O teto de QUADROS não bastava, e isso travava o PDF de verdade (medido em 2026-08-31).**
 * `requestAnimationFrame` **não dispara em aba oculta** — e trocar de aba enquanto um relatório
 * grande exporta é exatamente o que a pessoa faz, porque demora. Com o laço esperando só por
 * quadros, o primeiro `await` ficava pendurado **indefinidamente**: medido com
 * `document.hidden = true`, nenhum quadro em 1,5 s e o botão parado em *"Gerando PDF…"*,
 * desabilitado, **seis segundos depois** — e assim ficaria até a pessoa voltar para a aba.
 *
 * Por isso cada espera corre contra um `setTimeout`, e existe um **teto de tempo real** além do
 * de quadros. ⚠️ Os dois são necessários: em aba oculta o Chrome também estrangula `setTimeout`
 * (mínimo de ~1 s), então 90 iterações levariam ~90 s — quem corta é o teto de tempo.
 */
/**
 * Espera o navegador **pintar** o que o `setState` acabou de mudar, antes de fotografar a tela.
 *
 * Dois quadros depois do `setState`: o primeiro aplica o DOM novo, o segundo garante que ele foi
 * pintado. Sem isso o html2canvas roda contra o DOM antigo (sem a seção "Filtros Aplicados", que
 * só existe durante a captura).
 *
 * ⛔ **Por que não é `requestAnimationFrame` puro, que é como as 10 telas faziam até 2026-08-31:**
 * `rAF` **não dispara em aba oculta**. Quem clicava em "Gerar PDF" e trocava de aba — o que se faz
 * justamente porque o relatório demora — ficava com o botão em *"Gerando PDF…"*, desabilitado,
 * **para sempre**, e o PDF nunca saía. Medido: com `document.hidden = true`, nenhum quadro em
 * 1,5 s e o botão travado 8 s depois; a captura **nem começava** (nenhum log da primeira linha
 * dela aparecia no console).
 *
 * ⚠️ O `setTimeout` de reserva é o que resolve, e ele também é estrangulado em aba oculta (mínimo
 * de ~1 s no Chrome) — por isso o valor é generoso: o objetivo é **destravar**, não competir em
 * velocidade com o caminho normal, que continua sendo o `rAF` quando a aba está visível.
 *
 * ⭐ Vive aqui, e não copiada em cada tela, porque era a duplicação que fazia o mesmo defeito
 * existir em dez lugares ao mesmo tempo.
 */
export function aguardarPintura(): Promise<void> {
  const quadro = () =>
    new Promise<void>((r) => {
      let resolvido = false
      const concluir = () => {
        if (resolvido) return
        resolvido = true
        r()
      }
      requestAnimationFrame(concluir)
      setTimeout(concluir, 50)
    })
  return quadro().then(quadro)
}

export async function aguardarGraficosEstaveis(elemento: HTMLElement): Promise<void> {
  const LIMITE_QUADROS = 90 // ~1,5 s a 60 fps, acima da animação padrão do recharts
  const TETO_MS = 2500 // teto de tempo REAL — é ele que salva a aba oculta
  const inicio = performance.now()
  // Resolve no primeiro que chegar: o quadro (aba visível) ou o timer (aba oculta).
  const quadro = () =>
    new Promise<void>((r) => {
      let resolvido = false
      const concluir = () => {
        if (resolvido) return
        resolvido = true
        r()
      }
      requestAnimationFrame(concluir)
      setTimeout(concluir, 32) // ~2 quadros a 60 fps
    })

  const geometria = () =>
    Array.from(elemento.querySelectorAll('.recharts-bar-rectangle path, .recharts-rectangle, .recharts-line-curve'))
      .map((e) => e.getAttribute('d') ?? `${e.getAttribute('width')}x${e.getAttribute('height')}`)
      .join('|')

  if (!geometria()) return // tela sem gráfico: não há o que esperar

  let anterior = geometria()
  let iguais = 0
  for (let i = 0; i < LIMITE_QUADROS && iguais < 2 && performance.now() - inicio < TETO_MS; i++) {
    await quadro()
    const atual = geometria()
    iguais = atual === anterior ? iguais + 1 : 0
    anterior = atual
  }
}
