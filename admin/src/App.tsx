import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import RequireStaff from './components/RequireStaff'
import Configuracao from './paginas/Configuracao'
import CsrtPorUf from './paginas/CsrtPorUf'
import Leads from './paginas/Leads'
import Login from './paginas/Login'
import Painel from './paginas/Painel'
import Tenants from './paginas/Tenants'

/**
 * Backoffice da Vetor. Cinco telas, nesta ordem por frequência de uso: Painel (funil e contas
 * perto do limite), Contas, Leads, Configuração e CSRT por UF — o código do responsável
 * técnico que a SEFAZ de cada estado emite.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/entrar" element={<Login />} />
      <Route element={<RequireStaff />}>
        <Route element={<Layout />}>
          <Route path="/" element={<Painel />} />
          <Route path="/contas" element={<Tenants />} />
          <Route path="/leads" element={<Leads />} />
          <Route path="/configuracao" element={<Configuracao />} />
          <Route path="/csrt" element={<CsrtPorUf />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
