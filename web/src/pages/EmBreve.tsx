/** Placeholder para as áreas de domínio ainda não implementadas (Produtos, Estoque…). */
export default function EmBreve({ titulo }: { titulo: string }) {
  return (
    <div>
      <p className="eyebrow">{titulo}</p>
      <h1 style={{ marginTop: 4 }}>{titulo}</h1>
      <div className="card">
        <p style={{ margin: 0 }}>Esta área está em construção. 🚧</p>
        <p className="muted" style={{ marginBottom: 0 }}>
          Em breve você vai gerenciar {titulo.toLowerCase()} por aqui, com sincronização automática nos canais.
        </p>
      </div>
    </div>
  )
}
