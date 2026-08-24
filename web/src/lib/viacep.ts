/** Autopreenchimento de endereço a partir do CEP (docs/telas/cliente.md). */

export interface EnderecoViaCep {
  logradouro: string
  bairro: string
  localidade: string
  uf: string
  /** Código IBGE do município (7 dígitos). ⚠️ O ViaCEP SEMPRE devolveu este campo — ele só não
   *  estava declarado aqui, e por isso era descartado. É exatamente o que a NF-e pede no
   *  destinatário, e o que fazia o lojista ter de consultar o site do IBGE à mão (2026-08-24). */
  ibge?: string
  erro?: boolean
}

export async function buscarEnderecoPorCep(cepDigitos: string): Promise<EnderecoViaCep | null> {
  if (cepDigitos.length !== 8) return null
  try {
    const res = await fetch(`https://viacep.com.br/ws/${cepDigitos}/json/`)
    if (!res.ok) return null
    const dados = (await res.json()) as EnderecoViaCep
    return dados.erro ? null : dados
  } catch {
    return null
  }
}
