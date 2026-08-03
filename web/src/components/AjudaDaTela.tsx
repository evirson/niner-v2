import { useState } from 'react'
import { IconeAjuda } from './Icones'

/**
 * Ajuda contextual obrigatória em toda tela (R22 / spec §3.7.1). O catálogo `ajuda_tela`
 * servido pela API ainda não existe (🔴 spec §3.3.10) — por ora o conteúdo fica embutido
 * aqui como fallback estático, mas o gatilho de ajuda já nasce presente em toda tela nova.
 */
interface ConteudoAjuda {
  titulo: string
  objetivo: string
  passos: string[]
  errosComuns?: string[]
  urlVideo?: string | null
}

const CONTEUDOS: Record<string, ConteudoAjuda> = {
  'cadastros.cliente.lista': {
    titulo: 'Clientes',
    objetivo: 'Encontrar e gerenciar os clientes já cadastrados.',
    passos: [
      'Use a busca por nome para encontrar um cliente.',
      'Filtre por categoria ou por status (Ativos, Inativos ou Todos).',
      'Clique em "Editar" para abrir o cadastro, ou em "Excluir" para remover.',
    ],
    errosComuns: [
      'Não encontro um cliente: confira o filtro de status — ele pode estar inativo.',
      'Não consigo excluir: o cliente tem vendas associadas e foi inativado em vez de excluído.',
    ],
    urlVideo: null,
  },
  'cadastros.cliente.form': {
    titulo: 'Cadastro de cliente',
    objetivo: 'Cadastrar um cliente novo ou editar um existente.',
    passos: [
      'Escolha Pessoa Física ou Jurídica.',
      'Preencha o nome (ou razão social) e escolha uma categoria — crie uma nova se precisar.',
      'CPF/CNPJ e os demais dados são opcionais, mas recomendados.',
      'Digite o CEP para o endereço ser preenchido automaticamente.',
      'Salve.',
    ],
    errosComuns: [
      'Categoria não aparece na lista: crie uma pela opção "＋ Nova categoria".',
      'CPF/CNPJ inválido: confira os dígitos — o sistema valida o dígito verificador.',
      'Data de nascimento/gênero obrigatórios: só para Pessoa Física.',
    ],
    urlVideo: null,
  },
  'cadastros.cliente.categoria': {
    titulo: 'Categorias de cliente',
    objetivo: 'Criar ou renomear categorias usadas no cadastro de cliente.',
    passos: [
      'Digite o nome de uma categoria nova e clique em "Adicionar".',
      'Para renomear, edite o nome de uma categoria já existente na lista e clique em "Salvar".',
    ],
    urlVideo: null,
  },
  'cadastros.cliente.historico': {
    titulo: 'Histórico do cliente',
    objetivo: 'Ver as compras, as parcelas e o resumo do crediário deste cliente.',
    passos: [
      '"Histórico de Compras" lista as vendas físicas do cliente, com a loja e o valor de cada uma.',
      '"Histórico de Parcelas" lista cada parcela (crediário, cartão etc.), com vencimento, pagamento e dias de atraso.',
      '"Resumo das Parcelas de Crediário" soma só as parcelas de crediário em aberto: vencidas, a vencer e o total.',
    ],
    errosComuns: [
      'Tela vazia: o cliente ainda não tem nenhuma venda registrada.',
      '"Empresa de pagamento" em branco: a parcela ainda não foi baixada (paga).',
    ],
    urlVideo: null,
  },
  'cadastros.funcionario.lista': {
    titulo: 'Funcionários',
    objetivo: 'Encontrar e gerenciar os funcionários já cadastrados.',
    passos: [
      'Use a busca por nome para encontrar um funcionário.',
      'Filtre por status (Ativos, Inativos ou Todos).',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não encontro um funcionário: confira o filtro de status — ele pode estar inativo.',
      'Não consigo excluir: o funcionário tem movimentações de estoque associadas e foi inativado em vez de excluído.',
    ],
    urlVideo: null,
  },
  'cadastros.funcionario.form': {
    titulo: 'Cadastro de funcionário',
    objetivo: 'Cadastrar um funcionário novo ou editar um existente.',
    passos: [
      'Preencha o nome — é o único campo obrigatório por padrão.',
      'CPF, celular, cargo e percentual de comissão são opcionais, mas recomendados.',
      'Salve.',
    ],
    errosComuns: [
      'CPF inválido: confira os dígitos — o sistema valida o dígito verificador (o CPF não precisa ser único entre funcionários).',
      'Celular inválido: precisa ter 11 dígitos (DDD + 9XXXX-XXXX).',
    ],
    urlVideo: null,
  },
  'cadastros.fornecedor.lista': {
    titulo: 'Fornecedores',
    objetivo: 'Encontrar e gerenciar os fornecedores já cadastrados.',
    passos: [
      'Use a busca por razão social ou nome fantasia.',
      'Filtre por plano de contas ou por status (Ativos, Inativos ou Todos).',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não encontro um fornecedor: confira o filtro de status — ele pode estar inativo.',
      'Não consigo excluir: o fornecedor tem movimentações ou contas a pagar associadas e foi inativado em vez de excluído.',
    ],
    urlVideo: null,
  },
  'cadastros.fornecedor.form': {
    titulo: 'Cadastro de fornecedor',
    objetivo: 'Cadastrar um fornecedor novo ou editar um existente.',
    passos: [
      'Preencha a razão social e escolha um plano de contas — crie um novo pelo botão "＋ Novo" se precisar.',
      'CNPJ, contato e endereço são opcionais, mas recomendados.',
      'Digite o CEP para o endereço ser preenchido automaticamente.',
      'Salve.',
    ],
    errosComuns: [
      'Plano de contas não aparece na lista: crie um pela opção "＋ Novo" ou pela tela Plano de Contas.',
      'CNPJ inválido: confira os dígitos — o sistema valida o dígito verificador (CNPJs novos podem ter letras).',
      'Telefone inválido: precisa ter 10 ou 11 dígitos, com DDD (fixo ou celular).',
    ],
    urlVideo: null,
  },
  'estoque.transferencia.lista': {
    titulo: 'Transferências entre Empresas',
    objetivo: 'Ver o histórico de transferências de produtos entre empresas e iniciar uma nova.',
    passos: [
      'Clique em "＋ Nova Transferência" para mover produtos da empresa atual para outra.',
      'Clique no ícone verde para ver os detalhes (produtos e quantidades) de uma transferência já feita.',
    ],
    errosComuns: [
      'Transferências já feitas não podem ser editadas nem excluídas — é um registro permanente do estoque, mesma regra de uma venda.',
    ],
    urlVideo: null,
  },
  'estoque.transferencia.form': {
    titulo: 'Nova transferência entre empresas',
    objetivo: 'Mover produtos da empresa em que você está logado para outra empresa do mesmo tenant.',
    passos: [
      'A empresa de origem é sempre a empresa em que você está logado no momento — não dá para mudar aqui.',
      'Escolha a empresa de destino.',
      'Use "＋ Adicionar Produto" para buscar e adicionar cada produto; ajuste a quantidade de cada um.',
      'Confirme a transferência — o estoque sai da origem e entra no destino na mesma hora.',
    ],
    errosComuns: [
      'Quantidade maior que o estoque disponível: reduza a quantidade — não é possível transferir mais do que existe na empresa de origem.',
      'Para trocar de empresa de origem, é preciso sair e entrar de novo escolhendo outra empresa no login.',
    ],
    urlVideo: null,
  },
  'identidade.usuario.lista': {
    titulo: 'Usuários',
    objetivo: 'Encontrar e gerenciar os usuários que acessam o sistema.',
    passos: [
      'Use a busca por nome ou e-mail para encontrar um usuário.',
      'Filtre por status (Ativos, Inativos ou Todos).',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Só administradores acessam esta tela — usuários OPERADOR não têm este item no menu.',
      'Não consigo excluir: o usuário tem caixa(s) associado(s) e foi inativado em vez de excluído; também não é possível excluir a própria conta.',
    ],
    urlVideo: null,
  },
  'identidade.usuario.form': {
    titulo: 'Cadastro de usuário',
    objetivo: 'Cadastrar um usuário novo ou editar um existente, e escolher em quais empresas ele pode operar.',
    passos: [
      'Preencha nome, e-mail e senha (obrigatórios para um usuário novo).',
      'Marque "Administrador" para dar acesso total, incluindo Parâmetros do Sistema e esta própria tela de Usuários.',
      'Marque ao menos uma empresa em "Empresas com acesso" — o usuário só opera nas empresas selecionadas.',
      'Na edição, deixe a senha em branco para manter a senha atual.',
      'Salve.',
    ],
    errosComuns: [
      'E-mail já cadastrado: cada e-mail só pode pertencer a um usuário.',
      'Nenhuma empresa selecionada: é obrigatório marcar ao menos uma.',
      'A permissão fina por rotina/tela (além de Administrador/Operador) ainda não existe nesta versão.',
    ],
    urlVideo: null,
  },
  'configuracao.geral.form': {
    titulo: 'Parâmetros do sistema',
    objetivo: 'Ajustar as regras gerais do tenant: desconto máximo em venda, uso de variantes de produto e taxas de crediário.',
    passos: [
      'Informe o desconto máximo permitido em uma venda.',
      'Marque se o catálogo usa variante em linha (ex.: cor) e/ou em coluna (ex.: tamanho/voltagem).',
      'Preencha os prazos e percentuais de juros/multa do crediário — ficam prontos para quando o módulo de crediário existir.',
      'Salve.',
    ],
    errosComuns: [
      'Só administradores acessam esta tela — usuários OPERADOR não têm este item no menu.',
      'Percentuais devem ficar entre 0 e 100.',
    ],
    urlVideo: null,
  },
  'cadastros.planocontas.lista': {
    titulo: 'Plano de Contas',
    objetivo: 'Encontrar e gerenciar as contas do plano de contas (usadas por fornecedores, contas a pagar e relatórios).',
    passos: [
      'Use a busca por código (ex.: "3.1") ou por descrição.',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não consigo excluir: a conta está em uso por fornecedor ou contas a pagar — a exclusão é bloqueada.',
    ],
    urlVideo: null,
  },
  'catalogo.produto.lista': {
    titulo: 'Produtos',
    objetivo: 'Encontrar e gerenciar os produtos já cadastrados.',
    passos: [
      'Use a busca por descrição para encontrar um produto.',
      'Filtre por categoria ou por status (Ativos, Inativos ou Todos).',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não encontro um produto: confira o filtro de status — ele pode estar inativo.',
      'Não consigo excluir: o produto tem variações ou imagens associadas e foi inativado em vez de excluído.',
    ],
    urlVideo: null,
  },
  'catalogo.produto.form': {
    titulo: 'Cadastro de produto',
    objetivo: 'Cadastrar um produto novo ou editar um existente.',
    passos: [
      'Preencha a descrição e os preços de custo/venda — são os únicos campos obrigatórios por padrão.',
      'Escolha uma ou mais categorias — a ordem da lista é a ordem de exibição do produto nas categorias.',
      'Crie uma categoria nova pela opção "＋ Gerenciar categorias" sem sair da tela.',
      'Se os Parâmetros do Sistema tiverem "variante em linha/coluna" habilitados, informe o nome usado nas variações deste produto (ex.: "Cor", "Tamanho").',
      'Ao digitar o código NCM, a descrição aparece automaticamente ao lado, se o código estiver cadastrado.',
      'Salve.',
      'Depois de salvo, a seção "Fotos" permite adicionar até 6 imagens (JPEG/PNG/WebP) — use as setas pra reordenar e a lixeira pra excluir.',
    ],
    errosComuns: [
      'Não vejo os campos de variante: confira os Parâmetros do Sistema (só ADMIN acessa) — eles controlam se o produto usa variante em linha e/ou coluna.',
      'Categoria não aparece na lista: crie uma pela opção "＋ Gerenciar categorias".',
      'Data final da oferta antes da inicial: corrija o intervalo de datas.',
      'Não consigo adicionar foto: o produto precisa estar salvo primeiro, e o limite é de 6 fotos por produto.',
    ],
    urlVideo: null,
  },
  'cadastros.planocontas.form': {
    titulo: 'Cadastro de plano de contas',
    objetivo: 'Cadastrar uma conta nova do plano de contas ou editar uma existente.',
    passos: [
      'Informe o código contábil (ex.: "3.1.001") — ele identifica a conta e não pode ser alterado depois.',
      'Preencha a descrição e escolha o tipo de movimento (Crédito, Débito ou Neutro).',
      'Marque se a conta compõe a DRE e/ou o fluxo de caixa.',
      'Salve.',
    ],
    errosComuns: [
      'Código já existe: cada conta precisa de um código único.',
      'Não consigo mudar o código: ele é o identificador da conta — exclua e crie outra, se ainda não estiver em uso.',
    ],
    urlVideo: null,
  },
  'financeiro.tipocarteira.lista': {
    titulo: 'Tipo de Carteira',
    objetivo: 'Encontrar e gerenciar as formas de pagamento (categoria/prazo/parcelas/taxa/desconto/acréscimo) já cadastradas.',
    passos: [
      'Use a busca por nome para encontrar um tipo de carteira.',
      'As colunas "% Desconto"/"% Acréscimo" mostram o ajuste de preço aplicado por essa forma de pagamento.',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não consigo excluir: o tipo de carteira está em uso em contas a receber ou lançamento de caixa — a exclusão é bloqueada.',
    ],
    urlVideo: null,
  },
  'financeiro.tipocarteira.form': {
    titulo: 'Cadastro de tipo de carteira',
    objetivo: 'Cadastrar uma forma de pagamento nova (categoria/prazo/parcelas/taxa/desconto/acréscimo) ou editar uma existente.',
    passos: [
      'Preencha o nome, a categoria, o prazo de pagamento (dias entre parcelas), o número mínimo/máximo de parcelas e a taxa administradora.',
      'A mesma bandeira pode ter um cadastro por categoria (ex.: "HIPER" em Cartão Débito e "HIPER" em Cartão Crédito), cada um com seu próprio prazo/taxa.',
      'Informe o % de desconto OU o % de acréscimo aplicado na venda por essa forma de pagamento — nunca os dois juntos.',
      'Salve.',
    ],
    errosComuns: [
      'Nome já existe: cada tipo de carteira precisa de um nome único dentro da mesma categoria (pode repetir em categorias diferentes).',
      'Parcela máxima menor que a mínima: corrija o intervalo.',
      'Informei desconto e acréscimo juntos: só um dos dois pode ter valor ao mesmo tempo.',
    ],
    urlVideo: null,
  },
  'financeiro.contacorrente.lista': {
    titulo: 'Conta Corrente',
    objetivo: 'Encontrar e gerenciar as contas bancárias já cadastradas.',
    passos: [
      'Use a busca por número da conta ou por descrição para encontrar uma conta.',
      'Filtre por status (Ativas, Inativas ou Todas).',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não encontro uma conta: confira o filtro de status — ela pode estar inativa.',
      'Não consigo excluir: a conta tem lançamentos associados e foi inativada em vez de excluída.',
    ],
    urlVideo: null,
  },
  'financeiro.contacorrente.form': {
    titulo: 'Cadastro de conta corrente',
    objetivo: 'Cadastrar uma conta corrente bancária nova ou editar uma existente.',
    passos: [
      'Informe o número da conta (ex.: "001-12345-6") — ele identifica a conta e não pode ser alterado depois.',
      'Escolha a empresa dona da conta, o banco e a agência.',
      'A descrição ajuda a reconhecer a conta nas telas de lançamento (ex.: "Conta Movimento Itaú").',
      'Salve.',
    ],
    errosComuns: [
      'Número já existe: cada conta corrente precisa de um número único.',
      'Não consigo mudar o número: ele é o identificador da conta — exclua e crie outra, se ainda não estiver em uso.',
    ],
    urlVideo: null,
  },
  'financeiro.contacorrentemovimento.lista': {
    titulo: 'Movimentação de Conta Corrente',
    objetivo: 'Encontrar e gerenciar os lançamentos (extrato manual) de conta corrente.',
    passos: [
      'Use a busca por número do documento, ou filtre por conta corrente e por compensado.',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não encontro um lançamento: confira os filtros de conta corrente e de compensado.',
    ],
    urlVideo: null,
  },
  'financeiro.contacorrentemovimento.form': {
    titulo: 'Lançamento de conta corrente',
    objetivo: 'Lançar um movimento novo no extrato manual, ou editar um já existente.',
    passos: [
      'Escolha a conta corrente e o plano de contas do lançamento.',
      'Informe a data, o número do documento, se é Crédito ou Débito, e o valor.',
      'Marque "Compensado" quando o lançamento já tiver sido confirmado no extrato do banco.',
      'Salve.',
    ],
    errosComuns: [
      'Valor zerado: o valor do lançamento precisa ser maior que zero.',
    ],
    urlVideo: null,
  },
  'financeiro.aberturacaixa.tela': {
    titulo: 'Abertura de Caixa',
    objetivo: 'Abrir o caixa do dia antes de vender no PDV ou receber crediário.',
    passos: [
      'Se já houver caixa aberto hoje para você, a tela só mostra o horário, a moeda e o saldo inicial.',
      'Se não houver, escolha a moeda (tipo de carteira, geralmente "Dinheiro") e informe o saldo inicial.',
      'Confirme em "Abrir Caixa" — a partir daí o PDV e o Recebimento de Crediário liberam a operação.',
    ],
    errosComuns: [
      'PDV ou Recebimento de Crediário pedem a abertura sozinhos: é o mesmo formulário desta tela, num popup — nenhuma venda/recebimento acontece sem caixa aberto.',
      'Cada usuário abre o próprio caixa por empresa/dia — não existe caixa compartilhado entre usuários.',
    ],
    urlVideo: null,
  },
  'financeiro.fechamentocaixa.tela': {
    titulo: 'Fechamento de Caixa',
    objetivo: 'Encerrar o caixa do dia com uma contagem "às cegas" de cada tipo de carteira que teve movimento.',
    passos: [
      'Administradores escolhem o usuário e a data do caixa a fechar; operadores só veem/fecham o próprio caixa, sem o campo de usuário.',
      'A tela mostra o nome de cada carteira com movimento no dia, sem revelar o valor esperado — informe quanto foi contado fisicamente em cada uma.',
      'Confirme em "Fechar Caixa". Se todas baterem, o caixa fecha e a impressão fica disponível.',
      'Se alguma não bater, a tela mostra a diferença por carteira — clique numa carteira pra conferir lançamento a lançamento o que compõe o valor esperado, corrija e tente de novo.',
      'Use "Visualizar Impressão" depois de fechado pra conferir o relatório em folha A4 antes de imprimir ou salvar em PDF.',
    ],
    errosComuns: [
      'Um caixa já fechado não pode ser fechado de novo — a tela responde com um aviso de conflito.',
      'Operadores que tentam informar outro usuário ou fechar o caixa de outra pessoa recebem acesso negado — só administradores podem.',
      'Faltar o valor contado de alguma carteira com movimento impede o fechamento.',
    ],
    urlVideo: null,
  },
  'vendas.pesquisavendas.tela': {
    titulo: 'Pesquisa de Vendas',
    objetivo: 'Localizar uma venda já registrada e ver, numa única tela, itens, movimentação de caixa e parcelas de crediário.',
    passos: [
      'Busque pelo número da venda (ignora os demais filtros) ou por um intervalo de datas — empresa (admin), situação, cliente e vendedor são filtros opcionais.',
      'Clique numa linha do resultado para carregar o detalhamento logo abaixo: dados da venda, produtos vendidos, movimentação de caixa e parcelas de crediário.',
      'Vendas canceladas aparecem sinalizadas na grid, mas não entram na soma do rodapé.',
      'Esta tela é só de consulta — para cancelar uma venda, use Cancelamento de Venda.',
    ],
    errosComuns: [
      'Operadores sempre pesquisam só a própria empresa da sessão, mesmo que tentem informar outra.',
      'Sem número da venda, é preciso informar data inicial e final, com no máximo 365 dias de intervalo.',
    ],
    urlVideo: null,
  },
  'vendas.cancelamentovenda.lista': {
    titulo: 'Cancelamento de Venda',
    objetivo: 'Localizar uma venda finalizada e reverter completamente estoque, caixa e contas a receber.',
    passos: [
      'Busque pelo número da venda (ignora os demais filtros) ou por um intervalo de datas — empresa, cliente e vendedor são filtros opcionais.',
      'Clique no ícone de visualizar na linha da venda para ver o resumo, os itens e as formas de pagamento antes de decidir.',
      'Informe o motivo do cancelamento e confirme em "Sim, Cancelar Venda" — o estoque volta, os lançamentos de caixa e as parcelas em aberto são removidos, tudo numa única operação.',
    ],
    errosComuns: [
      'Venda de crediário com alguma parcela já recebida nunca pode ser cancelada, nem por administrador — a tela mostra quais parcelas foram recebidas e orienta a estornar o recebimento primeiro.',
      'É preciso ter o caixa de hoje aberto para cancelar — o sistema não sabe reabrir o caixa do dia original da venda.',
      'Uma venda já cancelada não pode ser cancelada de novo.',
    ],
    urlVideo: null,
  },
  'financeiro.recebimentocrediario.tela': {
    titulo: 'Recebimento de Crediário',
    objetivo: 'Receber uma ou mais parcelas de crediário em aberto de um cliente.',
    passos: [
      'Busque o cliente por nome, CPF ou celular (pelo menos um dos três).',
      'Selecione uma ou mais parcelas em aberto — os totais no rodapé somam automaticamente.',
      'Escolha a forma de pagamento (só aparecem carteiras liberadas para receber crediário) e informe o valor — pode combinar mais de uma forma.',
      'Confirme em "Receber" quando o Saldo a Pagar chegar a zero.',
    ],
    errosComuns: [
      'Nenhuma forma de pagamento aparece: cadastre ou libere uma em Tipo de Carteira, marcando "Permite receber parcelas de crediário".',
      'Não acho o cliente: confira se o nome/CPF/celular digitado está correto — a busca não exige os três juntos.',
    ],
    urlVideo: null,
  },
  'financeiro.estornorecebimentocrediario.tela': {
    titulo: 'Estorno de Recebimento de Crediário',
    objetivo: 'Desfazer um recebimento de crediário já efetivado, reabrindo as parcelas e apagando os lançamentos de caixa.',
    passos: [
      'Informe o nome do cliente (obrigatório) — data inicial/final de recebimento são opcionais, pra estreitar a busca.',
      'Cada linha é um recebimento inteiro (um "lote"), não uma parcela isolada.',
      'Clique no ícone de estorno e confirme — todas as parcelas daquele lote voltam a ficar em aberto, mesmo que sejam de vendas/contratos diferentes recebidos juntos na mesma operação.',
    ],
    errosComuns: [
      'Não acho o recebimento: confira o nome do cliente e o intervalo de datas — o filtro de cliente é sempre obrigatório.',
      'Não é possível estornar parte de um lote — se ele cobriu várias parcelas juntas, o estorno desfaz todas de uma vez.',
    ],
    urlVideo: null,
  },
  'relatorios.vendas.tela': {
    titulo: 'Relatório de Vendas',
    objetivo: 'Acompanhar o desempenho de vendas do período: KPIs, composição do faturamento, gráficos e um totalizador com drill-down.',
    passos: [
      'Escolha o período (presets ou "Personalizado"), a(s) empresa(s) — admin — e, opcionalmente, um vendedor.',
      'Os cards no topo mostram ticket médio, % médio de desconto, devoluções e itens vendidos do período filtrado.',
      '"Composição do Faturamento" mostra o caminho do valor bruto até a venda líquida.',
      'Os 7 gráficos cobrem vendas por dia, top 10 de marcas/vendedores/clientes, formas de pagamento, por hora e por dia da semana.',
      'Escolha "Totalizar Por" para agrupar a grid final (por data, cliente, vendedor, operador de caixa ou empresa) — clique numa linha agrupada para ver as vendas daquele grupo.',
      '"Não Totalizar" já mostra a lista de vendas do período diretamente, sem agrupar, em ordem cronológica (data/hora da venda), já com a linha de total no rodapé.',
      '"Gerar PDF" baixa a tela como está (com os gráficos desenhados) em PDF: a 1ª página traz os filtros aplicados, o título e a numeração/data de geração repetem no topo de todas as páginas, e o rodapé de cada página mostra a empresa logada e "Niner ERP" — a grid de vendas sempre começa numa página nova, com total no fim.',
      'O PDF sai sempre em tema claro (fundo branco), mesmo com o sistema no tema escuro — economiza tinta na impressão.',
    ],
    errosComuns: [
      'Devoluções sempre aparecem zeradas: o módulo de devolução ainda não existe no sistema.',
      'Operadores sempre veem só a própria empresa da sessão, mesmo com mais de uma selecionada.',
      '"Gerar PDF" demora 1-2 segundos (botão mostra "Gerando PDF…") — é a tela inteira sendo capturada, não um erro.',
    ],
    urlVideo: null,
  },
}

export default function AjudaDaTela({ chaveTela }: { chaveTela: string }) {
  const [aberto, setAberto] = useState(false)
  const conteudo = CONTEUDOS[chaveTela]
  if (!conteudo) return null

  return (
    <>
      <button
        type="button"
        className="btn ghost ajuda-gatilho"
        aria-label={`Ajuda: ${conteudo.titulo}`}
        onClick={() => setAberto(true)}
      >
        <IconeAjuda />
      </button>
      {aberto && (
        <div className="modal-overlay" onClick={() => setAberto(false)}>
          <div
            className="modal"
            role="dialog"
            aria-label={`Ajuda — ${conteudo.titulo}`}
            tabIndex={-1}
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key === 'Escape') setAberto(false)
            }}
          >
            <h2 style={{ marginTop: 0 }}>{conteudo.titulo}</h2>
            <p className="muted">{conteudo.objetivo}</p>

            <p className="card-title" style={{ marginTop: 16 }}>Passo a passo</p>
            <ol className="passos">
              {conteudo.passos.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ol>

            {conteudo.errosComuns && (
              <>
                <p className="card-title" style={{ marginTop: 16 }}>Erros comuns</p>
                <ul className="passos">
                  {conteudo.errosComuns.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </>
            )}

            <div className="ajuda-rodape">
              {conteudo.urlVideo ? (
                <a href={conteudo.urlVideo} target="_blank" rel="noopener" className="btn">
                  Assistir vídeo
                </a>
              ) : (
                <button type="button" className="btn ghost" disabled>
                  Vídeo em breve
                </button>
              )}
              <button type="button" className="btn ghost" onClick={() => setAberto(false)}>
                Fechar
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
