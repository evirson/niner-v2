/**
 * Formatação de data/hora vinda da API (ISO 8601 / `timestamptz` do Postgres) para o formato
 * brasileiro. Usada nos campos informativos de auditoria (`criado_em`/`atualizado_em`), que
 * toda tabela do domínio carrega — ver `web/src/components/InfoRegistro.tsx`.
 */
/** Data de hoje em "aaaa-mm-dd" (fuso local — evita o desvio de `toISOString`, que usa UTC). */
export function hojeISO(): string {
  return emIsoLocal(new Date())
}

/**
 * "aaaa-mm-dd" de uma data, pelos componentes LOCAIS.
 *
 * <p>⚠️ Nunca use `toISOString().slice(0, 10)` para isto: ele converte para UTC, e a partir das
 * 21h de Brasília o resultado já é o dia SEGUINTE. Foi assim que a validade sugerida do orçamento
 * saía com um dia a mais para quem emitia à noite (achado de auditoria, 2026-08-21) — e o
 * orçamento é imutável, então o preço ficava congelado um dia além da política da loja.
 */
export function emIsoLocal(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Hoje + `dias`, em "aaaa-mm-dd" local. Usado por prazos sugeridos (validade de orçamento etc.). */
export function hojeMaisDiasISO(dias: number): string {
  const d = new Date()
  d.setDate(d.getDate() + dias)
  return emIsoLocal(d)
}

export function formatarDataHora(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Só a data, sem hora — grids que não precisam do horário (ex.: Contas a Receber/Recebidas). */
export function formatarSoData(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' })
}
