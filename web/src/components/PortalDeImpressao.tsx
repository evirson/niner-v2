import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * Leva o bloco de impressão para **fora do `#root`**, direto no `<body>`.
 *
 * <h2>⛔ Sem isto, TODO documento A4 imprime em branco</h2>
 *
 * <p>O isolamento de impressão do projeto é
 * `body.imprimindo-documento > *:not(.documento-a4-imprimir) { display: none }` — o seletor é
 * **`body > *`**, filho direto. O `<body>` tem um filho só, o `<div id="root">`, e os modais
 * renderizam o bloco de impressão **dentro** dele. Resultado: a regra casa com o `#root`, esconde
 * a subárvore inteira, e o documento vai junto — `visibility: visible` no descendente não traz de
 * volta o que o ancestral apagou com `display: none`.
 *
 * <p>⚠️ **Medido em 2026-08-29**, não inferido: aplicando a classe e lendo o estilo computado, o
 * `#root` fica `display: none` e a largura visível do documento é **0**. Achado por auditoria; a
 * correção de 2026-08-22 que introduziu o seletor está registrada em `docs/PROGRESSO.md` com
 * "⚠️ não testado no papel" — e é exatamente o que não foi testado.
 *
 * <p>⭐ O portal é o caminho **já provado** aqui: é o que a Emissão de Etiqueta usa desde
 * 2026-08-21, pelo mesmo motivo (paginar exige fluxo normal, e o bloco precisa ser irmão do
 * `#root`, não filho dele).
 *
 * <p>⚠️ Quem imprime documento novo usa este componente. Corrigir o seletor CSS em vez do DOM
 * seria possível (`:not(:has(...))`), mas frágil: qualquer wrapper novo entre o `body` e o
 * documento quebraria de novo, e em silêncio — a impressão em branco não gera erro nenhum.
 */
export default function PortalDeImpressao({ children }: { children: ReactNode }) {
  return createPortal(children, document.body)
}
