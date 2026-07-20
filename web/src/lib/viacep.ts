/** Autopreenchimento de endereço a partir do CEP (docs/telas/cliente.md). */

export interface EnderecoViaCep {
  logradouro: string
  bairro: string
  localidade: string
  uf: string
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
