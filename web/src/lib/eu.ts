import { useQuery } from '@tanstack/react-query'
import { api } from './api'

export interface Eu {
  id_tenant: number
  conta: { nomeConta: string; slug: string; status: string }
  usuario: { idUsuario: number; nome: string; email: string; papel: string }
  /** Empresa ativa da sessão (escolhida no login, 2026-07-28) — todo cadastro feito nesta
   *  sessão com coluna `id_empresa` usa esta empresa. */
  /** `nomeEtiqueta` = o texto IMPRESSO no campo "Nome da Empresa" da etiqueta de produto
   *  (2026-08-21). Hoje é o mesmo nome fantasia/razão social de `nome`, e existe como campo
   *  próprio para o dia em que a loja quiser um nome comercial curto só para a etiqueta — o
   *  front já lê daqui, então virar um campo editável não mexe em nenhuma tela.
   *  ⚠️ NÃO é `empresa.cfg_nome_etiqueta`: aquela coluna guarda um modelo de texto do ERP legado
   *  ("{sku}\n{descricao}\n{preco_venda}"), que impresso sairia com os marcadores literais. */
  empresa: { idEmpresa: number; nome: string; nomeEtiqueta: string }
  /** Plano e cota do mês (ADR-015). Substituiu `trial_expira_em`: não há mais trial. */
  plano: {
    nome: string
    gratuito: boolean
    limite_vendas_mes: number | null
    vendas_no_mes: number
  } | null
  /** Contagem regressiva do aviso de horário de acesso (`HorarioAcessoGuard`, 2026-08-11) —
   *  `null` na imensa maioria das chamadas; só vem preenchido nos minutos finais de tolerância
   *  antes do bloqueio de verdade. */
  segundosRestantesTolerancia: number | null
}

/** "Quem sou eu" do tenant logado — inclui o papel (ADMIN/OPERADOR), usado para telas restritas. */
export function useEu() {
  return useQuery({ queryKey: ['eu'], queryFn: () => api<Eu>('/api/v1/eu'), retry: false })
}
