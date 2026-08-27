import { api } from './api'

/**
 * Permissão por tela e por ação (RBAC, V073).
 *
 * ⚠️ **Isto é conveniência de interface, não segurança.** Esconder um item de menu evita o erro
 * honesto — o operador não entra onde não deve; não evita quem chama a API direto. A trava que
 * vale é a do servidor, em cada rotina.
 */
export interface Permissao {
  chave: string
  nome: string
  grupo: string
  acessar: boolean
  incluir: boolean
  alterar: boolean
  excluir: boolean
}

/** A grade do usuário logado — o ADMIN recebe tudo `true` sem ter grade gravada. */
export function minhasPermissoes(): Promise<Permissao[]> {
  return api<Permissao[]>('/api/v1/eu/permissoes')
}

export function permissoesDoUsuario(idUsuario: number): Promise<Permissao[]> {
  return api<Permissao[]>(`/api/v1/usuarios/${idUsuario}/permissoes`)
}

/**
 * Substitui a grade inteira. Mandar tudo (e não só o que mudou) é o que impede o modo de falha
 * clássico: uma permissão **desmarcada** que não chega ao servidor continuaria valendo.
 */
export function salvarPermissoes(idUsuario: number, grade: Permissao[]): Promise<Permissao[]> {
  return api<Permissao[]>(`/api/v1/usuarios/${idUsuario}/permissoes`, {
    method: 'PUT',
    body: JSON.stringify(
      grade.map((p) => ({
        chaveTela: p.chave,
        acessar: p.acessar,
        incluir: p.incluir,
        alterar: p.alterar,
        excluir: p.excluir,
      })),
    ),
  })
}

/**
 * A chave de tela de uma rota — a mesma convenção do catálogo (`cfg_tela.chave`), que nasceu da
 * rota: `/relatorio-vendas` → `relatorio-vendas`, `/estoque/contagem` → `estoque.contagem`.
 * Mantê-la derivável evita uma segunda lista para alguém esquecer de atualizar.
 */
export function chaveDaRota(rota: string): string {
  return rota.replace(/^\//, '').replace(/\//g, '.') || 'painel'
}
