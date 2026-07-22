/**
 * Formatação de data/hora vinda da API (ISO 8601 / `timestamptz` do Postgres) para o formato
 * brasileiro. Usada nos campos informativos de auditoria (`criado_em`/`atualizado_em`), que
 * toda tabela do domínio carrega — ver `web/src/components/InfoRegistro.tsx`.
 */
/** Data de hoje em "aaaa-mm-dd" (fuso local — evita o desvio de `toISOString`, que usa UTC). */
export function hojeISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
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
