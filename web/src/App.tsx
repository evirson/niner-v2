import { Navigate, Route, Routes } from 'react-router-dom'
import RequireAdmin from './components/RequireAdmin'
import RequireAuth from './components/RequireAuth'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import EmBreve from './pages/EmBreve'
import MenuGrupo from './pages/MenuGrupo'
import Pdv from './pages/pdv/Pdv'
import ClienteLista from './pages/clientes/ClienteLista'
import ClienteForm from './pages/clientes/ClienteForm'
import ClienteHistorico from './pages/clientes/ClienteHistorico'
import ConfiguracaoTelaCliente from './pages/clientes/ConfiguracaoTelaCliente'
import FuncionarioLista from './pages/funcionarios/FuncionarioLista'
import FuncionarioForm from './pages/funcionarios/FuncionarioForm'
import ConfiguracaoTelaFuncionario from './pages/funcionarios/ConfiguracaoTelaFuncionario'
import PlanoContasLista from './pages/planocontas/PlanoContasLista'
import PlanoContasForm from './pages/planocontas/PlanoContasForm'
import TipoCarteiraLista from './pages/tipocarteira/TipoCarteiraLista'
import TipoCarteiraForm from './pages/tipocarteira/TipoCarteiraForm'
import FornecedorLista from './pages/fornecedores/FornecedorLista'
import FornecedorForm from './pages/fornecedores/FornecedorForm'
import ConfiguracaoTelaFornecedor from './pages/fornecedores/ConfiguracaoTelaFornecedor'
import ConfiguracaoGeralForm from './pages/configuracaogeral/ConfiguracaoGeralForm'
import ProdutoLista from './pages/produtos/ProdutoLista'
import ProdutoForm from './pages/produtos/ProdutoForm'
import ConfiguracaoTelaProduto from './pages/produtos/ConfiguracaoTelaProduto'
import UsuarioLista from './pages/usuarios/UsuarioLista'
import UsuarioForm from './pages/usuarios/UsuarioForm'
import TransferenciaLista from './pages/estoque/TransferenciaLista'
import TransferenciaForm from './pages/estoque/TransferenciaForm'
import TransferenciaDetalhe from './pages/estoque/TransferenciaDetalhe'
import ContagemEstoque from './pages/estoque/ContagemEstoque'
import ZerarContagemEstoque from './pages/estoque/ZerarContagemEstoque'
import DiferencasEstoque from './pages/estoque/DiferencasEstoque'
import EfetivarBalanco from './pages/estoque/EfetivarBalanco'
import EstornoRecebimentoCrediario from './pages/recebimentocrediario/EstornoRecebimentoCrediario'
import RecebimentoCrediario from './pages/recebimentocrediario/RecebimentoCrediario'
import AberturaCaixa from './pages/caixa/AberturaCaixa'
import FechamentoCaixa from './pages/caixa/FechamentoCaixa'
import CancelamentoVenda from './pages/vendas/CancelamentoVenda'
import PesquisaVendas from './pages/vendas/PesquisaVendas'
import DevolucaoProduto from './pages/vendas/DevolucaoProduto'
import ContaCorrenteLista from './pages/contacorrente/ContaCorrenteLista'
import ContaCorrenteForm from './pages/contacorrente/ContaCorrenteForm'
import ContaCorrenteMovimentoLista from './pages/contacorrente/ContaCorrenteMovimentoLista'
import ContaCorrenteMovimentoForm from './pages/contacorrente/ContaCorrenteMovimentoForm'
import RelatorioVendas from './pages/relatorios/RelatorioVendas'
import RelatorioComissoes from './pages/relatorios/RelatorioComissoes'
import RelatorioContasReceber from './pages/relatorios/RelatorioContasReceber'
import RelatorioEstoque from './pages/relatorios/RelatorioEstoque'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/" element={<Dashboard />} />
          {/* Página-hub de um grupo do menu (cards dos filhos, 2026-08-03). Prefixo /menu/ para
              não colidir com as rotas das telas (`/estoque` já é a Transferência). */}
          <Route path="/menu/:grupo" element={<MenuGrupo />} />
          <Route path="/pdv" element={<Pdv />} />
          <Route path="/pesquisa-vendas" element={<PesquisaVendas />} />
          <Route path="/devolucao-produto" element={<DevolucaoProduto />} />
          <Route path="/relatorio-vendas" element={<RelatorioVendas />} />
          <Route path="/relatorio-comissoes" element={<RelatorioComissoes />} />
          <Route path="/relatorio-contas-receber" element={<RelatorioContasReceber />} />
          <Route path="/relatorio-estoque" element={<RelatorioEstoque />} />
          <Route path="/abertura-caixa" element={<AberturaCaixa />} />
          <Route path="/fechamento-caixa" element={<FechamentoCaixa />} />
          <Route path="/recebimento-crediario" element={<RecebimentoCrediario />} />
          <Route path="/estorno-recebimento-crediario" element={<EstornoRecebimentoCrediario />} />
          <Route path="/produtos" element={<ProdutoLista />} />
          <Route path="/produtos/novo" element={<ProdutoForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/produtos/configuracao" element={<ConfiguracaoTelaProduto />} />
          </Route>
          <Route path="/produtos/:id/visualizar" element={<ProdutoForm somenteLeitura />} />
          <Route path="/produtos/:id" element={<ProdutoForm />} />
          <Route path="/estoque" element={<TransferenciaLista />} />
          <Route path="/estoque/nova" element={<TransferenciaForm />} />
          <Route path="/estoque/contagem" element={<ContagemEstoque />} />
          <Route path="/estoque/zerar-contagem" element={<ZerarContagemEstoque />} />
          <Route path="/estoque/diferencas" element={<DiferencasEstoque />} />
          <Route path="/estoque/efetivar-balanco" element={<EfetivarBalanco />} />
          <Route path="/estoque/:id" element={<TransferenciaDetalhe />} />
          <Route path="/pedidos" element={<EmBreve titulo="Pedidos" />} />
          <Route path="/canais" element={<EmBreve titulo="Canais" />} />
          <Route path="/clientes" element={<ClienteLista />} />
          <Route path="/clientes/novo" element={<ClienteForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/clientes/configuracao" element={<ConfiguracaoTelaCliente />} />
          </Route>
          <Route path="/clientes/:id/visualizar" element={<ClienteForm somenteLeitura />} />
          <Route path="/clientes/:id/historico" element={<ClienteHistorico />} />
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
          <Route path="/tipos-carteira" element={<TipoCarteiraLista />} />
          <Route path="/tipos-carteira/novo" element={<TipoCarteiraForm />} />
          <Route path="/tipos-carteira/:id/visualizar" element={<TipoCarteiraForm somenteLeitura />} />
          <Route path="/tipos-carteira/:id" element={<TipoCarteiraForm />} />
          <Route path="/contas-corrente" element={<ContaCorrenteLista />} />
          <Route path="/contas-corrente/nova" element={<ContaCorrenteForm />} />
          <Route path="/contas-corrente/:id/visualizar" element={<ContaCorrenteForm somenteLeitura />} />
          <Route path="/contas-corrente/:id" element={<ContaCorrenteForm />} />
          <Route path="/contas-corrente-movimento" element={<ContaCorrenteMovimentoLista />} />
          <Route path="/contas-corrente-movimento/novo" element={<ContaCorrenteMovimentoForm />} />
          <Route path="/contas-corrente-movimento/:id/visualizar" element={<ContaCorrenteMovimentoForm somenteLeitura />} />
          <Route path="/contas-corrente-movimento/:id" element={<ContaCorrenteMovimentoForm />} />
          <Route path="/fornecedores" element={<FornecedorLista />} />
          <Route path="/fornecedores/novo" element={<FornecedorForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/fornecedores/configuracao" element={<ConfiguracaoTelaFornecedor />} />
          </Route>
          <Route path="/fornecedores/:id/visualizar" element={<FornecedorForm somenteLeitura />} />
          <Route path="/fornecedores/:id" element={<FornecedorForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/configuracoes-gerais" element={<ConfiguracaoGeralForm />} />
            <Route path="/usuarios" element={<UsuarioLista />} />
            <Route path="/usuarios/novo" element={<UsuarioForm />} />
            <Route path="/usuarios/:id/visualizar" element={<UsuarioForm somenteLeitura />} />
            <Route path="/usuarios/:id" element={<UsuarioForm />} />
            <Route path="/cancelamento-venda" element={<CancelamentoVenda />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
