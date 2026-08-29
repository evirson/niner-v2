import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * Rede de segurança contra a **tela preta** (2026-08-29).
 *
 * ⚠️ **O motivo de existir, medido:** o React desmonta a árvore inteira quando uma exceção escapa
 * de um render ou de um `useEffect`, e sem nenhum error boundary o que sobra na tela é o fundo do
 * `<body>` — no tema escuro, uma **tela preta**, sem mensagem, sem stack, com a URL parada no
 * mesmo endereço. Foi assim que um `qrCodeUrl` nulo (NF-e 55 não tem QR Code) apagou o ERP em dois
 * caminhos que mexem em dinheiro: reimprimir a papeleta na Pesquisa de Vendas e emitir a nota no
 * PDV. O defeito era de uma linha; o que o transformou em "não sai nada" foi a ausência DESTA
 * classe.
 *
 * ⛔ **Isto não conserta defeito nenhum — só impede que um defeito vire ausência de informação.**
 * O operador passa a ver o que quebrou e continua com o sistema navegável; o erro segue indo para
 * o console inteiro, porque esconder o stack é trocar uma tela preta por uma mentira arrumada.
 *
 * ⚠️ Precisa ser **classe**: não existe equivalente em hook — `componentDidCatch` e
 * `getDerivedStateFromError` só existem na API de classe. É a única classe do projeto de propósito.
 *
 * ⚠️ E precisa de `chaveDeReset`: sem ela o boundary fica **preso** no estado de erro e a tela
 * segue quebrada mesmo depois de o usuário navegar para outro lugar — trocando a tela preta por
 * uma mensagem de erro eterna, que não é melhor. `App.tsx` passa a rota atual, então mudar de tela
 * limpa o erro.
 */
type Props = { children: ReactNode; chaveDeReset?: string }
type Estado = { erro: Error | null }

export default class LimiteDeErro extends Component<Props, Estado> {
  state: Estado = { erro: null }

  static getDerivedStateFromError(erro: Error): Estado {
    return { erro }
  }

  componentDidCatch(erro: Error, info: ErrorInfo) {
    // O console continua recebendo tudo — é ele que permite diagnosticar depois.
    console.error('[Nainer] erro não tratado na interface:', erro, info.componentStack)
  }

  componentDidUpdate(anterior: Props) {
    if (this.state.erro && anterior.chaveDeReset !== this.props.chaveDeReset) {
      this.setState({ erro: null })
    }
  }

  render() {
    if (!this.state.erro) return this.props.children
    return (
      <div className="limite-erro">
        <h2>Algo quebrou nesta tela</h2>
        <p>
          O sistema continua funcionando — use o menu para ir a outra tela. Se o problema se
          repetir, avise o suporte com a mensagem abaixo.
        </p>
        <pre className="limite-erro-detalhe">{this.state.erro.message || String(this.state.erro)}</pre>
        <button type="button" className="btn" onClick={() => this.setState({ erro: null })}>
          Tentar novamente
        </button>
      </div>
    )
  }
}
