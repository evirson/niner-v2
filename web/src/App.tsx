import { Navigate, Route, Routes } from 'react-router-dom'
import RequireAdmin from './components/RequireAdmin'
import RequireAuth from './components/RequireAuth'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import EmBreve from './pages/EmBreve'
import ClienteLista from './pages/clientes/ClienteLista'
import ClienteForm from './pages/clientes/ClienteForm'
import ConfiguracaoTelaCliente from './pages/clientes/ConfiguracaoTelaCliente'
import FuncionarioLista from './pages/funcionarios/FuncionarioLista'
import FuncionarioForm from './pages/funcionarios/FuncionarioForm'
import ConfiguracaoTelaFuncionario from './pages/funcionarios/ConfiguracaoTelaFuncionario'
import PlanoContasLista from './pages/planocontas/PlanoContasLista'
import PlanoContasForm from './pages/planocontas/PlanoContasForm'
import MoedaLista from './pages/moeda/MoedaLista'
import MoedaForm from './pages/moeda/MoedaForm'
import TipoCarteiraLista from './pages/tipocarteira/TipoCarteiraLista'
import TipoCarteiraForm from './pages/tipocarteira/TipoCarteiraForm'
import FornecedorLista from './pages/fornecedores/FornecedorLista'
import FornecedorForm from './pages/fornecedores/FornecedorForm'
import ConfiguracaoTelaFornecedor from './pages/fornecedores/ConfiguracaoTelaFornecedor'
import ConfiguracaoGeralForm from './pages/configuracaogeral/ConfiguracaoGeralForm'
import ProdutoLista from './pages/produtos/ProdutoLista'
import ProdutoForm from './pages/produtos/ProdutoForm'
import ConfiguracaoTelaProduto from './pages/produtos/ConfiguracaoTelaProduto'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/produtos" element={<ProdutoLista />} />
          <Route path="/produtos/novo" element={<ProdutoForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/produtos/configuracao" element={<ConfiguracaoTelaProduto />} />
          </Route>
          <Route path="/produtos/:id/visualizar" element={<ProdutoForm somenteLeitura />} />
          <Route path="/produtos/:id" element={<ProdutoForm />} />
          <Route path="/estoque" element={<EmBreve titulo="Estoque" />} />
          <Route path="/pedidos" element={<EmBreve titulo="Pedidos" />} />
          <Route path="/canais" element={<EmBreve titulo="Canais" />} />
          <Route path="/clientes" element={<ClienteLista />} />
          <Route path="/clientes/novo" element={<ClienteForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/clientes/configuracao" element={<ConfiguracaoTelaCliente />} />
          </Route>
          <Route path="/clientes/:id/visualizar" element={<ClienteForm somenteLeitura />} />
          <Route path="/clientes/:id" element={<ClienteForm />} />
          <Route path="/funcionarios" element={<FuncionarioLista />} />
          <Route path="/funcionarios/novo" element={<FuncionarioForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/funcionarios/configuracao" element={<ConfiguracaoTelaFuncionario />} />
          </Route>
          <Route path="/funcionarios/:id/visualizar" element={<FuncionarioForm somenteLeitura />} />
          <Route path="/funcionarios/:id" element={<FuncionarioForm />} />
          <Route path="/planos-contas" element={<PlanoContasLista />} />
          <Route path="/planos-contas/novo" element={<PlanoContasForm />} />
          <Route path="/planos-contas/:codigo/visualizar" element={<PlanoContasForm somenteLeitura />} />
          <Route path="/planos-contas/:codigo" element={<PlanoContasForm />} />
          <Route path="/moedas" element={<MoedaLista />} />
          <Route path="/moedas/novo" element={<MoedaForm />} />
          <Route path="/moedas/:id/visualizar" element={<MoedaForm somenteLeitura />} />
          <Route path="/moedas/:id" element={<MoedaForm />} />
          <Route path="/tipos-carteira" element={<TipoCarteiraLista />} />
          <Route path="/tipos-carteira/novo" element={<TipoCarteiraForm />} />
          <Route path="/tipos-carteira/:id/visualizar" element={<TipoCarteiraForm somenteLeitura />} />
          <Route path="/tipos-carteira/:id" element={<TipoCarteiraForm />} />
          <Route path="/fornecedores" element={<FornecedorLista />} />
          <Route path="/fornecedores/novo" element={<FornecedorForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/fornecedores/configuracao" element={<ConfiguracaoTelaFornecedor />} />
          </Route>
          <Route path="/fornecedores/:id/visualizar" element={<FornecedorForm somenteLeitura />} />
          <Route path="/fornecedores/:id" element={<FornecedorForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/configuracoes-gerais" element={<ConfiguracaoGeralForm />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
