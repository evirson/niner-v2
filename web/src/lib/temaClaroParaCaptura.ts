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

/**
 * Repinta o texto dos gráficos (recharts) com a cor clara, em valor **literal**.
 *
 * ⚠️ Medido em 2026-08-26: dentro do clone, um `fill="var(--accent)"` de SVG **continua
 * resolvendo para a cor do tema escuro** mesmo com `--accent` já valendo a cor clara no `:root`
 * — o Chrome não reinvalida o estilo do SVG adotado. Por isso a cor aqui é literal: reemitir
 * `var(...)` reproduziria o mesmo problema.
 *
 * Efeito prático: os rótulos dos eixos saíam em cinza-claro sobre papel branco, quase ilegíveis.
 * CSS vence atributo de apresentação (que tem especificidade zero), então basta a regra.
 *
 * ⏭️ **A cor das barras continua a do tema escuro** e não é mexida aqui de propósito: ela é
 * legível no claro e trocá-la é decisão visual do dono do produto, não conserto de legibilidade.
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
  estilo.textContent = `:root{${declaracoes}}${REGRA_SEM_IMPRESSAO}${regrasDoGrafico(declaracoes)}`
  // No fim do <head>: empata em especificidade com o resto e vence por ordem, e o `!important`
  // resolve o que a ordem não resolveria.
  doc.head.appendChild(estilo)
}
