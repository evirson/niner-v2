import { api } from './api'

/**
 * Ramos de atividade do varejo (V072, 2026-08-27) — a lista curta que o lojista escolhe, com o
 * CNAE por trás. Vem do servidor (tabela global `cfg_ramo_atividade`) em vez de ser uma constante
 * do front: acrescentar um ramo é `INSERT`, não deploy dos três aplicativos.
 */
export interface Ramo {
  idRamo: number
  codigo: string
  nome: string
}

export function listarRamos(): Promise<Ramo[]> {
  return api<Ramo[]>('/api/v1/ramos')
}

/** O que a consulta pública de CNPJ devolve, já com o ramo resolvido pelo mapa CNAE→ramo. */
export interface DadosCnpj {
  cnpj: string
  razaoSocial: string | null
  nomeFantasia: string | null
  cnae: string | null
  cnaeDescricao: string | null
  /** `null` quando o CNAE é genérico demais para identificar o ramo — aí não se sugere nada. */
  ramoSugerido: Ramo | null
}

/**
 * Consulta o CNPJ e devolve os dados públicos, ou `null` quando não deu (CNPJ inexistente,
 * serviço fora do ar, tempo esgotado). A API responde 204 nesses casos, de propósito: para a tela
 * os quatro motivos são o mesmo — seguir preenchendo à mão, sem erro na cara do usuário.
 */
export async function consultarCnpj(cnpj: string): Promise<DadosCnpj | null> {
  const limpo = cnpj.replace(/\D/g, '')
  if (limpo.length !== 14) return null
  try {
    // O 204 chega aqui como corpo vazio (o helper `api` lê texto e só faz JSON.parse se houver
    // conteúdo), o que devolve `undefined` — normalizado para `null` num caminho só.
    return (await api<DadosCnpj>(`/api/v1/cnpj/${limpo}`)) ?? null
  } catch {
    return null
  }
}
