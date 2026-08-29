import { useQuery } from '@tanstack/react-query'
import { useEu } from './eu'
import { minhasPermissoes } from './permissoes'

/**
 * As ações que o usuário logado pode exercer numa tela (RBAC, V073–V081).
 *
 * ⚠️ **Isto é conveniência de interface, NUNCA segurança** (P4). A trava que vale é o
 * `@Acao` do controller, traduzido pelo `PermissaoInterceptor`. O que este hook evita é o
 * caminho oferecido que vai falhar: até a auditoria de 2026-08-29, a grade de permissões tinha
 * **três colunas que não governavam nada visível** — o admin concedia só "acessar" em Clientes,
 * o operador via o botão "＋ Novo Cliente", preenchia a ficha inteira (nome, CPF, endereço do
 * ViaCEP, limite de crédito) e perdia tudo num 403 no Salvar. Nada na tela avisava.
 *
 * ⚠️ **Enquanto a grade não chegou, tudo é `true`.** Um botão que aparece e some meio segundo
 * depois é pior que um que demora a aparecer — a mesma decisão de `filtrarPorModulo`. E o
 * servidor continua recusando de qualquer forma, então o custo do otimismo é zero.
 *
 * ⚠️ O ADMIN recebe tudo `true` do servidor, sem grade gravada — não é preciso tratá-lo aqui.
 */
export interface AcoesDaTela {
  acessar: boolean
  incluir: boolean
  alterar: boolean
  excluir: boolean
}

const TUDO_LIBERADO: AcoesDaTela = { acessar: true, incluir: true, alterar: true, excluir: true }

export function usePermissaoDaTela(chaveTela: string): AcoesDaTela {
  const { data: eu } = useEu()
  const { data: permissoes } = useQuery({
    queryKey: ['minhas-permissoes'],
    queryFn: minhasPermissoes,
    staleTime: 60_000,
  })

  if (eu?.usuario.papel === 'ADMIN') return TUDO_LIBERADO
  if (!permissoes) return TUDO_LIBERADO

  const p = permissoes.find((x) => x.chave === chaveTela)
  // Tela fora do catálogo não é governada por ninguém — tratá-la como negada esconderia ações de
  // uma tela que o servidor libera. Mesma decisão de `filtrarPorPermissao`.
  if (!p) return TUDO_LIBERADO

  return { acessar: p.acessar, incluir: p.incluir, alterar: p.alterar, excluir: p.excluir }
}
