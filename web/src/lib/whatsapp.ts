import { somenteDigitos } from './masks'

/**
 * Monta o link `wa.me` com o número do Brasil (`55` fixo — o sistema é Brasil-only, mesma
 * convenção de CPF/CNPJ) e uma mensagem pré-preenchida. Abrir esse link só deixa o WhatsApp
 * Web/app já pronto pra enviar — quem de fato envia é sempre um humano (o operador), clicando
 * em "Enviar" na própria conversa. Não é uma integração automática com a API do WhatsApp.
 */
export function montarLinkWhatsApp(celular: string, mensagem: string): string {
  const digitos = somenteDigitos(celular)
  return `https://wa.me/55${digitos}?text=${encodeURIComponent(mensagem)}`
}
