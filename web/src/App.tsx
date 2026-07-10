import { Navigate, Route, Routes } from 'react-router-dom'
import RequireAuth from './components/RequireAuth'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import EmBreve from './pages/EmBreve'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/produtos" element={<EmBreve titulo="Produtos" />} />
          <Route path="/estoque" element={<EmBreve titulo="Estoque" />} />
          <Route path="/pedidos" element={<EmBreve titulo="Pedidos" />} />
          <Route path="/canais" element={<EmBreve titulo="Canais" />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
