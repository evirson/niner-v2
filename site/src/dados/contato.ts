/**
 * Dados de contato e identidade da landing — 🔴 PREENCHER.
 *
 * Ficam num arquivo só de propósito: o HTML entregue pelo design tinha os mesmos placeholders
 * repetidos em 6 lugares (3 links de WhatsApp, Instagram, e-mail, CNPJ), e placeholder espalhado
 * é placeholder que vai para produção esquecido em um deles.
 *
 * `PENDENTE` marca o que ainda não existe: a landing esconde o link em vez de publicar um
 * `wa.me/[PLACEHOLDER]` quebrado.
 */
export const PENDENTE = null;

export const contato = {
  /** Só dígitos, com DDI: '5541999998888'. */
  whatsapp: PENDENTE as string | null,
  /** Perfil sem @ nem URL: 'ninererp'. */
  instagram: PENDENTE as string | null,
  email: PENDENTE as string | null,
  cnpjVetor: PENDENTE as string | null,
  /** Domínio final do site — usado em canonical/OG. */
  dominio: 'https://niner.com.br',
};

export const MENSAGEM_WHATSAPP = 'Olá! Vi o Niner no site e queria saber mais.';

export function linkWhatsapp(): string | null {
  return contato.whatsapp
    ? `https://wa.me/${contato.whatsapp}?text=${encodeURIComponent(MENSAGEM_WHATSAPP)}`
    : null;
}

export function linkInstagram(): string | null {
  return contato.instagram ? `https://instagram.com/${contato.instagram}` : null;
}

/**
 * Depoimentos: lista vazia esconde a seção inteira. **Nunca** inventar cliente, loja ou número —
 * regra do briefing (docs/site/briefing-landing.md §9).
 */
export const depoimentos: { texto: string; nome: string; loja: string; cidade: string }[] = [];
