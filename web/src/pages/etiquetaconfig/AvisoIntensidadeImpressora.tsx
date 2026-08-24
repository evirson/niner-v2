/**
 * Aviso de calibragem da INTENSIDADE (temperatura) da impressora térmica (2026-08-24).
 *
 * <p><b>Por que existe.</b> O código de barras saía impresso com a geometria correta e mesmo
 * assim o leitor <b>recusava</b> a etiqueta. A medição do símbolo impresso (perfil de intensidade
 * da foto, média de 60 linhas) mostrou o motivo: das <b>59</b> transições de um EAN-13 sobravam
 * <b>31</b> — barras vizinhas fundidas. Nenhuma barra de 1 módulo sobreviveu: a mais fina media
 * <b>2,25 módulos</b>. Cada barra havia engordado ~0,33 mm e cada espaço encolhido na mesma
 * medida, que é a assinatura do <i>bar width growth</i> da térmica: intensidade alta demais, o
 * ponto queima maior que o dot e invade o branco vizinho.
 *
 * <p><b>A geometria não era o problema</b> — o símbolo media 26 mm contra os 25,2 mm previstos.
 * Foram três tentativas de correção no desenho (2026-08-21) antes de a causa aparecer, e nenhuma
 * podia funcionar: o defeito nunca esteve no SVG.
 *
 * <p><b>Por que isto é um aviso na tela e não uma correção no código.</b> A intensidade é
 * inalcançável a partir do navegador — mora no DEVMODE privado do driver, e nenhuma API web chega
 * lá. Compensar no desenho também não resolve nesta resolução: a 203 dpi o módulo tem 2,12 dots,
 * e a impressora só liga ou desliga dots inteiros, então não há como desenhar "meio ponto mais
 * fino". Sobra instruir — e a instrução precisa estar onde o lojista imprime, porque sozinho ele
 * não descobre nunca.
 *
 * <p>⚠️ <b>A pegadinha que trava todo mundo</b> é a caixa "Usar configuração atual de intensidade
 * da impressora": enquanto marcada, o driver manda usar a intensidade gravada no firmware e deixa
 * o slider <b>cinza</b>. Quem vai direto no slider conclui que não há o que ajustar. Por isso ela
 * é o passo 2 do texto, antes do valor.
 *
 * <p><b>O número 6 é medido, não teórico</b> — Argox OS-2140 PPLA, escala 0–20: em 10 (o padrão de
 * fábrica) as barras saíam grudadas e o leitor recusava; em 6 o leitor lê. Outra impressora tem
 * outra escala, e por isso o texto dá também o critério que vale em qualquer uma: o menor valor
 * que ainda produz preto sólido.
 */
export default function AvisoIntensidadeImpressora() {
  return (
    <div
      className="tarja-aviso"
      style={{ display: 'block', fontWeight: 500, lineHeight: 1.55, marginTop: 14, marginBottom: 0 }}
    >
      <strong style={{ display: 'block', marginBottom: 6 }}>
        ⚠️ O leitor não lê o código de barras? Antes de mexer no layout, confira a intensidade da
        impressora.
      </strong>
      Em impressora térmica, intensidade alta <strong>engrossa as barras e fecha os espaços</strong>:
      o código sai com o desenho perfeito e mesmo assim o leitor recusa. Medido na{' '}
      <strong>Argox OS-2140 PPLA</strong> (escala 0–20): em <strong>10</strong> (padrão de fábrica) as
      barras saíam grudadas; em <strong>6</strong> o leitor lê.
      <div style={{ marginTop: 8 }}>
        Onde ajustar, no Windows:
        <ol style={{ margin: '4px 0 0', paddingLeft: 20 }}>
          <li>
            Painel de Controle → Dispositivos e Impressoras → botão direito na impressora →{' '}
            <strong>Preferências de impressão</strong> (não "Propriedades da impressora", que é outra
            janela e não tem esse ajuste).
          </li>
          <li>
            Aba <strong>Opções</strong> → desmarque{' '}
            <strong>"Usar configuração atual de intensidade da impressora"</strong>. Sem isso o slider
            fica cinza e não deixa arrastar.
          </li>
          <li>
            <strong>Nível de intensidade</strong> → baixe para <strong>6</strong> → Aplicar.
          </li>
        </ol>
      </div>
      <div style={{ marginTop: 8 }}>
        Em outra impressora a escala pode ser diferente; o critério é o{' '}
        <strong>menor valor que ainda dá preto sólido</strong>. Barras grudadas = intensidade alta
        demais; barra falhada ou acinzentada = baixa demais.
      </div>
    </div>
  )
}
