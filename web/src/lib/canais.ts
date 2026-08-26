import { api } from './api'

/**
 * Canais de Venda (R7). Ver `docs/MODULOMARKETPLACE.md`.
 *
 * ⛔ Nenhum campo aqui carrega credencial: `canal.credenciais` é segredo cifrado (ADR-005) e
 * nunca volta pela API. O que a tela mostra é `contaExterna` (o id público do vendedor no canal)
 * e o status.
 */
export interface Canal {
  idCanal: number
  tipo: 'MERCADO_LIVRE' | 'SHOPEE' | 'AMAZON' | 'ECOMMERCE'
  tipoRotulo: string
  nome: string
  status: 'CONECTADO' | 'DESCONECTADO' | 'ERRO'
  /**
   * De qual empresa (filial) sai o estoque publicado neste canal.
   *
   * ⚠️ Obrigatório e **imutável**: `produto_estoque` é por empresa, então sem isto "5 peças" não
   * responde "de qual loja". Trocar depois faria anúncios já publicados passarem a mostrar o
   * saldo de outra filial — por isso o servidor recusa a troca (V067).
   */
  idEmpresa: number
  /** Id do vendedor no canal. `null` enquanto não conectado. */
  contaExterna: string | null
  /**
   * Regra de preço do canal: `preço do anúncio = preço de venda × (1 + percPreco/100)`.
   * ⚠️ Aceita NEGATIVO — o preço do marketplace pode ser menor que o da loja física.
   */
  percPreco: number
  anunciosVinculados: number
  criadoEm: string
  atualizadoEm: string
}

export interface CanalRequest {
  nome: string
  percPreco: number
  /** ⚠️ Só muda na criação — o servidor recusa a troca num canal já criado. */
  idEmpresa: number
}

/** Uma sincronização que não passou. `erro` vem cru do canal, de propósito — é a pista do suporte. */
export interface EventoComFalha {
  id: number
  tipo: string
  agregadoId: string | null
  status: 'ERRO' | 'DEAD_LETTER'
  tentativas: number
  erro: string | null
  proximoRetry: string | null
  criadoEm: string
}

/**
 * ⚠️ Os três contadores são separados de propósito: `pendentes` é normal (sai em segundos),
 * `comErro` está tentando sozinho, `deadLetter` PAROU e espera gente. Somá-los num "total de
 * problemas" produziria um alarme num dia perfeitamente saudável.
 */
export interface SaudeCanais {
  pendentes: number
  comErro: number
  deadLetter: number
  falhas: EventoComFalha[]
}

export function listarCanais(): Promise<Canal[]> {
  return api<Canal[]>('/api/v1/canais')
}

export function buscarSaudeCanais(): Promise<SaudeCanais> {
  return api<SaudeCanais>('/api/v1/canais/saude')
}

export function criarCanal(tipo: Canal['tipo'], payload: CanalRequest): Promise<Canal> {
  return api<Canal>(`/api/v1/canais/${tipo}`, { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarCanal(idCanal: number, payload: CanalRequest): Promise<Canal> {
  return api<Canal>(`/api/v1/canais/${idCanal}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function desconectarCanal(idCanal: number): Promise<Canal> {
  return api<Canal>(`/api/v1/canais/${idCanal}/desconectar`, { method: 'POST' })
}

export function excluirCanal(idCanal: number): Promise<void> {
  return api<void>(`/api/v1/canais/${idCanal}`, { method: 'DELETE' })
}

export function reprocessarEvento(idEvento: number): Promise<void> {
  return api<void>(`/api/v1/canais/eventos/${idEvento}/reprocessar`, { method: 'POST' })
}

/**
 * Começa a conexão OAuth: pede ao servidor a URL do consentimento do Mercado Livre.
 *
 * ⚠️ O servidor devolve a URL em JSON, e **não** um redirecionamento — um 302 aqui quebraria
 * este `fetch`, que precisa do endereço para levar o lojista até lá. Quem termina a conexão é o
 * próprio Mercado Livre, devolvendo o navegador para `/api/publico/canais/mercadolivre/retorno`,
 * que por sua vez redireciona de volta para esta tela com o resultado na query string.
 */
export function iniciarConexaoMercadoLivre(idCanal: number): Promise<{ url: string }> {
  return api<{ url: string }>(`/api/v1/canais/${idCanal}/mercadolivre/autorizar`)
}

/**
 * Uma LINHA da tela de vínculo (R6) — não um anúncio.
 *
 * Um anúncio com variações vira várias linhas, uma por variação do canal: é a variação que se
 * vincula, e é o saldo dela que será publicado. Anúncio simples tem `idExternoVariacao` nulo.
 */
export interface LinhaParaVincular {
  idExterno: string
  idExternoVariacao: string | null
  titulo: string
  descricaoVariacao: string | null
  sku: string | null
  precoNoCanal: number | null
  quantidadeNoCanal: number
  statusNoCanal: string
  /** Preenchido = já vinculado. */
  idAnuncio: number | null
  idVariacao: number | null
  descricaoVariacaoErp: string | null
  /** ⭐ Sugestão por casamento de SKU — sugestão, NÃO vínculo: quem confirma é o lojista. */
  idVariacaoSugerida: number | null
  descricaoSugerida: string | null
}

export interface AnunciosDoCanal {
  idCanal: number
  nomeCanal: string
  pagina: number
  linhas: LinhaParaVincular[]
}

export interface VinculoGravado {
  idAnuncio: number
  idExterno: string
  idExternoVariacao: string | null
  idVariacao: number
  sku: string
  descricaoVariacaoErp: string
  preco: number
  precoManual: boolean
  statusSync: string
  ultimoErro: string | null
}

/** ⚠️ Fala com o Mercado Livre: pode demorar, e pode responder 502 se o ML estiver fora. */
export function listarAnunciosDoCanal(idCanal: number, pagina: number): Promise<AnunciosDoCanal> {
  return api<AnunciosDoCanal>(`/api/v1/canais/${idCanal}/anuncios?pagina=${pagina}`)
}

/** Não fala com o marketplace — continua funcionando com o ML fora do ar. */
export function listarVinculos(idCanal: number): Promise<VinculoGravado[]> {
  return api<VinculoGravado[]>(`/api/v1/canais/${idCanal}/anuncios/vinculos`)
}

export function vincularAnuncio(
  idCanal: number,
  payload: { idExterno: string; idExternoVariacao: string | null; idVariacao: number },
): Promise<void> {
  return api<void>(`/api/v1/canais/${idCanal}/anuncios`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function desvincularAnuncio(idCanal: number, idAnuncio: number): Promise<void> {
  return api<void>(`/api/v1/canais/${idCanal}/anuncios/${idAnuncio}`, { method: 'DELETE' })
}
