import { useQuery } from '@tanstack/react-query'
import { buscarUsaServicos } from './configuracaoGeral'
import { filtrarPorModulo, filtrarPorPapel, filtrarPorPermissao, MENU, type NavNode } from './menu'
import { minhasPermissoes } from './permissoes'
import { useEu } from './eu'

/**
 * O menu que ESTE usuário pode ver — os três filtros aplicados juntos, num lugar só.
 *
 * <h2>⛔ Por que virou hook</h2>
 *
 * <p>O menu tem **três** consumidores — a lateral (`Layout`), a página-hub (`MenuGrupo`) e a busca
 * do topo (`BuscaDeTelas`) — e cada um montava a própria combinação de filtros. Em 2026-08-29 uma
 * auditoria mostrou que só a lateral aplicava `filtrarPorPermissao`: uma tela **negada pelo RBAC**
 * sumia do menu e continuava **clicável** no card do hub e achável pelo Ctrl+K, abrindo uma tela
 * que respondia 403 em toda chamada.
 *
 * <p>⚠️ A ironia é que o `CLAUDE.md` já registrava exatamente essa lição — escrita quando
 * `filtrarPorModulo` nasceu, com a frase "aplicado nos três consumidores; esquecer um deixa a tela
 * achável pelo Ctrl+K depois de sumir do menu". A lição estava certa e **a irmã mais antiga
 * (`filtrarPorPermissao`) já estava faltando em dois deles**. Um hook único é o que impede a
 * terceira repetição: não há mais como um consumidor divergir dos outros.
 *
 * <h2>Os três filtros, e por que são diferentes</h2>
 *
 * <ol>
 *   <li>`filtrarPorPapel` — o que é **exclusivo do administrador** por natureza;</li>
 *   <li>`filtrarPorModulo` — o que **não faz sentido** para este tenant (serviços desligado);</li>
 *   <li>`filtrarPorPermissao` — o que o administrador **concedeu** a este usuário.</li>
 * </ol>
 *
 * <p>⚠️ Enquanto os dados não chegaram, os dois últimos **mantêm** o item (`undefined`/`null`):
 * um menu que aparece cheio e encolhe meio segundo depois é ruim, mas um que aparece vazio parece
 * sistema quebrado — e essa foi a decisão registrada nos dois filtros.
 *
 * <p>⛔ **Esconder no menu nunca foi proteção** (P4): a trava real é o `PermissaoInterceptor` no
 * servidor. Isto existe para não oferecer ao usuário um caminho que vai terminar em 403.
 */
export function useMenuFiltrado(): NavNode[] {
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'

  const { data: usaServicos } = useQuery({
    queryKey: ['config-geral', 'usa-servicos'],
    queryFn: buscarUsaServicos,
    staleTime: 60_000,
  })
  const { data: permissoes } = useQuery({
    queryKey: ['minhas-permissoes'],
    queryFn: minhasPermissoes,
    staleTime: 60_000,
  })

  const catalogadas = permissoes ? new Set(permissoes.map((p) => p.chave)) : undefined
  const permitidas = permissoes
    ? new Set(permissoes.filter((p) => p.acessar).map((p) => p.chave))
    : null

  return filtrarPorPermissao(
    filtrarPorModulo(filtrarPorPapel(MENU, isAdmin), usaServicos?.cfgUsaServicos),
    permitidas,
    catalogadas,
  )
}
