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
import EmpresaLista from './pages/identidade/EmpresaLista'
import MinhaConta from './pages/plataforma/MinhaConta'
import EmpresaForm from './pages/identidade/EmpresaForm'
import EtiquetaConfigLista from './pages/etiquetaconfig/EtiquetaConfigLista'
import EtiquetaConfigForm from './pages/etiquetaconfig/EtiquetaConfigForm'
import TransferenciaLista from './pages/estoque/TransferenciaLista'
import TransferenciaForm from './pages/estoque/TransferenciaForm'
import TransferenciaDetalhe from './pages/estoque/TransferenciaDetalhe'
import ContagemEstoque from './pages/estoque/ContagemEstoque'
import ZerarContagemEstoque from './pages/estoque/ZerarContagemEstoque'
import DiferencasEstoque from './pages/estoque/DiferencasEstoque'
import EfetivarBalanco from './pages/estoque/EfetivarBalanco'
import EntradaMercadoriaLista from './pages/estoque/entrada/EntradaMercadoriaLista'
import EntradaMercadoriaForm from './pages/estoque/entrada/EntradaMercadoriaForm'
import EntradaMercadoriaDetalhe from './pages/estoque/entrada/EntradaMercadoriaDetalhe'
import DevolucaoCompra from './pages/estoque/devolucaocompra/DevolucaoCompra'
import OrcamentoForm from './pages/orcamento/OrcamentoForm'
import OrcamentoLista from './pages/orcamento/OrcamentoLista'
import EstornoRecebimentoCrediario from './pages/recebimentocrediario/EstornoRecebimentoCrediario'
import RecebimentoCrediario from './pages/recebimentocrediario/RecebimentoCrediario'
import ReimpressaoRecebimentoCrediario from './pages/recebimentocrediario/ReimpressaoRecebimentoCrediario'
import FechamentoCaixa from './pages/caixa/FechamentoCaixa'
import PesquisaVendas from './pages/vendas/PesquisaVendas'
import DevolucaoProduto from './pages/vendas/DevolucaoProduto'
import CancelamentoDevolucao from './pages/vendas/CancelamentoDevolucao'
import ContaCorrenteLista from './pages/contacorrente/ContaCorrenteLista'
import ContaCorrenteForm from './pages/contacorrente/ContaCorrenteForm'
import ContaCorrenteMovimentoLista from './pages/contacorrente/ContaCorrenteMovimentoLista'
import ContaCorrenteMovimentoForm from './pages/contacorrente/ContaCorrenteMovimentoForm'
import ContasPagarLista from './pages/financeiro/contaspagar/ContasPagarLista'
import ContasPagarForm from './pages/financeiro/contaspagar/ContasPagarForm'
import RelatorioVendas from './pages/relatorios/RelatorioVendas'
import RelatorioComissoes from './pages/relatorios/RelatorioComissoes'
import RelatorioContasReceber from './pages/relatorios/RelatorioContasReceber'
import RelatorioContasPagar from './pages/relatorios/RelatorioContasPagar'
import RelatorioEstoque from './pages/relatorios/RelatorioEstoque'
import FluxoCaixa from './pages/relatorios/FluxoCaixa'
import RelatorioDre from './pages/relatorios/RelatorioDre'
import RelatorioLucratividade from './pages/relatorios/RelatorioLucratividade'
import RelatorioMovimentacaoProdutos from './pages/relatorios/RelatorioMovimentacaoProdutos'
import CrmForm from './pages/crm/CrmForm'
import EtiquetaEmissaoForm from './pages/etiquetaemissao/EtiquetaEmissaoForm'
import ImportacaoTabelaPage from './pages/importacao/ImportacaoTabelaPage'
import ExportacaoDadosPage from './pages/exportacao/ExportacaoDadosPage'
import FiscalConfiguracaoForm from './pages/fiscal/FiscalConfiguracaoForm'
import PerfilFiscalLista from './pages/fiscal/PerfilFiscalLista'
import PerfilFiscalForm from './pages/fiscal/PerfilFiscalForm'
import FiscalCertificadoLista from './pages/fiscal/FiscalCertificadoLista'
import ConformidadeFiscalPainel from './pages/fiscal/ConformidadeFiscalPainel'
import FiscalContingenciaPainel from './pages/fiscal/FiscalContingenciaPainel'
import CanaisVenda from './pages/canais/CanaisVenda'
import VincularAnuncios from './pages/canais/VincularAnuncios'
import FilaExpedicao from './pages/canais/FilaExpedicao'
import DocumentoFiscalLista from './pages/fiscal/DocumentoFiscalLista'
import ExportacaoXmlLote from './pages/fiscal/ExportacaoXmlLote'
import InutilizacaoNumeracao from './pages/fiscal/InutilizacaoNumeracao'

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
          <Route path="/orcamentos" element={<OrcamentoLista />} />
          <Route path="/orcamentos/novo" element={<OrcamentoForm />} />
          <Route path="/pesquisa-vendas" element={<PesquisaVendas />} />
          <Route path="/devolucao-produto" element={<DevolucaoProduto />} />
          <Route path="/relatorio-vendas" element={<RelatorioVendas />} />
          <Route path="/relatorio-comissoes" element={<RelatorioComissoes />} />
          <Route path="/relatorio-contas-receber" element={<RelatorioContasReceber />} />
          <Route path="/relatorio-contas-pagar" element={<RelatorioContasPagar />} />
          <Route path="/relatorio-estoque" element={<RelatorioEstoque />} />
          <Route path="/relatorio-movimentacao-produtos" element={<RelatorioMovimentacaoProdutos />} />
          {/* Fluxo de Caixa é aberto (só entrada/saída de dinheiro); a DRE, não. */}
          <Route path="/fluxo-caixa" element={<FluxoCaixa />} />
          {/* DRE é ADMIN-only (expõe lucro, despesa e pró-labore) — a API também devolve 403. */}
          <Route element={<RequireAdmin />}>
            <Route path="/relatorio-dre" element={<RelatorioDre />} />
            <Route path="/lucratividade" element={<RelatorioLucratividade />} />
          </Route>
          <Route path="/crm" element={<CrmForm />} />
          <Route path="/etiqueta-emissao" element={<EtiquetaEmissaoForm />} />
          <Route path="/fechamento-caixa" element={<FechamentoCaixa />} />
          <Route path="/recebimento-crediario" element={<RecebimentoCrediario />} />
          <Route path="/estorno-recebimento-crediario" element={<EstornoRecebimentoCrediario />} />
          <Route path="/reimpressao-recebimento-crediario" element={<ReimpressaoRecebimentoCrediario />} />
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
          <Route path="/canais" element={<CanaisVenda />} />
          <Route path="/canais/:idCanal/anuncios" element={<VincularAnuncios />} />
          <Route path="/expedicao" element={<FilaExpedicao />} />
          <Route path="/bi-dashboard" element={<EmBreve titulo="BI Dashboard" />} />
          <Route path="/entrada-produtos-compra" element={<EntradaMercadoriaLista />} />
          <Route path="/estoque/devolucao-compra" element={<DevolucaoCompra />} />
          <Route path="/entrada-produtos-compra/nova" element={<EntradaMercadoriaForm />} />
          <Route path="/entrada-produtos-compra/:id" element={<EntradaMercadoriaDetalhe />} />
          <Route
            path="/relatorio-movimentacao-bancaria"
            element={<EmBreve titulo="Relatório de Movimentação Bancária" />}
          />
          {/* DRE e Fluxo de Caixa saíram daqui em 2026-08-14: as telas existem
              (/relatorio-dre e /fluxo-caixa, acima). Os placeholders duplicavam a rota
              /fluxo-caixa e concorriam com a tela pronta. */}
          <Route path="/integracao-marketplace" element={<EmBreve titulo="Integração com Marketplace" />} />
          <Route path="/cobranca-crediario-atraso" element={<EmBreve titulo="Cobrança de Crediário em Atraso" />} />
          {/* /exportacao-xml-fiscal saiu daqui em 2026-08-26: a tela existe
              (/fiscal/exportacao-xml, abaixo). ⚠️ Placeholder que sobrevive à tela pronta vira
              rota duplicada e item de menu repetido — foi o que aconteceu com /fluxo-caixa. */}
          <Route path="/cancelamento-devolucao-produtos" element={<CancelamentoDevolucao />} />
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
          <Route path="/contas-pagar" element={<ContasPagarLista />} />
          <Route path="/contas-pagar/nova" element={<ContasPagarForm />} />
          <Route path="/contas-pagar/:id/visualizar" element={<ContasPagarForm somenteLeitura />} />
          <Route path="/contas-pagar/:id" element={<ContasPagarForm />} />
          <Route path="/fornecedores" element={<FornecedorLista />} />
          <Route path="/fornecedores/novo" element={<FornecedorForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/fornecedores/configuracao" element={<ConfiguracaoTelaFornecedor />} />
          </Route>
          <Route path="/fornecedores/:id/visualizar" element={<FornecedorForm somenteLeitura />} />
          <Route path="/fornecedores/:id" element={<FornecedorForm />} />
          <Route element={<RequireAdmin />}>
            <Route path="/importacao-dados/clientes" element={<ImportacaoTabelaPage tabela="cliente" />} />
            <Route path="/importacao-dados/contas-receber" element={<ImportacaoTabelaPage tabela="contas_receber" />} />
            <Route path="/importacao-dados/fornecedores" element={<ImportacaoTabelaPage tabela="fornecedor" />} />
            <Route path="/importacao-dados/produtos" element={<ImportacaoTabelaPage tabela="produto" />} />
            <Route path="/importacao-dados/estoque" element={<ImportacaoTabelaPage tabela="estoque" />} />
            <Route path="/exportacao-dados" element={<ExportacaoDadosPage />} />
            <Route path="/configuracoes-gerais" element={<ConfiguracaoGeralForm />} />
            <Route path="/minha-conta" element={<MinhaConta />} />
            <Route path="/empresas" element={<EmpresaLista />} />
            <Route path="/empresas/:id" element={<EmpresaForm />} />
            <Route path="/usuarios" element={<UsuarioLista />} />
            <Route path="/usuarios/novo" element={<UsuarioForm />} />
            <Route path="/usuarios/:id/visualizar" element={<UsuarioForm somenteLeitura />} />
            <Route path="/usuarios/:id" element={<UsuarioForm />} />
            <Route path="/etiqueta-configuracao" element={<EtiquetaConfigLista />} />
            <Route path="/etiqueta-configuracao/novo" element={<EtiquetaConfigForm />} />
            <Route path="/etiqueta-configuracao/:id/visualizar" element={<EtiquetaConfigForm somenteLeitura />} />
            <Route path="/etiqueta-configuracao/:id" element={<EtiquetaConfigForm />} />
            <Route path="/fiscal/configuracao" element={<FiscalConfiguracaoForm />} />
            <Route path="/fiscal/perfis" element={<PerfilFiscalLista />} />
            <Route path="/fiscal/perfis/novo" element={<PerfilFiscalForm />} />
            <Route path="/fiscal/perfis/:id/visualizar" element={<PerfilFiscalForm somenteLeitura />} />
            <Route path="/fiscal/perfis/:id" element={<PerfilFiscalForm />} />
            <Route path="/fiscal/certificados" element={<FiscalCertificadoLista />} />
            <Route path="/fiscal/conformidade" element={<ConformidadeFiscalPainel />} />
            <Route path="/fiscal/contingencia" element={<FiscalContingenciaPainel />} />
            <Route path="/fiscal/documentos" element={<DocumentoFiscalLista />} />
            <Route path="/fiscal/exportacao-xml" element={<ExportacaoXmlLote />} />
            <Route path="/fiscal/inutilizacao" element={<InutilizacaoNumeracao />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
