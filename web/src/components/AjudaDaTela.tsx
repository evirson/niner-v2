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
      'Na tela de detalhes, use "Imprimir Guia" para gerar a Guia de Transferência (folha A4, com linhas de assinatura para conferência na origem e recebimento no destino).',
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
  'vendas.reimpressaopapeleta.tela': {
    titulo: 'Reimpressão de Papeleta de Venda',
    objetivo: 'Localizar uma venda já efetivada e reimprimir a papeleta (mesmo layout da 1ª via, com "REIMPRESSÃO" e a data/hora da reimpressão).',
    passos: [
      'Busque pelo número da venda (ignora os demais filtros) ou por um intervalo de datas — cliente é opcional.',
      'Clique numa linha do resultado para abrir a papeleta — imprima, salve em PDF ou envie por WhatsApp.',
      'Em "Enviar por WhatsApp", confirme (ou corrija) o celular do cliente no popup antes de continuar — o WhatsApp abre com o link de download já preenchido, e quem envia de fato é você, na própria conversa.',
    ],
    errosComuns: [
      'Sem número da venda, é preciso informar data inicial e final.',
      'O link enviado pelo WhatsApp expira em 24 horas — depois disso, é preciso reimprimir e enviar de novo.',
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
  'vendas.devolucaoproduto.form': {
    titulo: 'Devolução de Produtos',
    objetivo: 'Devolver ao estoque produtos que o cliente trouxe de volta e emitir um vale-mercadoria pelo crédito.',
    passos: [
      'Informe o número da venda (opcional) e clique em "Buscar Vendedor" só se quiser identificar quem vendeu — não fica gravado na devolução, serve apenas para no futuro descontar a comissão do vendedor.',
      'Leia o código de barras de cada produto devolvido — a grid vai empilhando os itens, somando a quantidade se o mesmo código for lido de novo.',
      'Ajuste a quantidade de qualquer linha direto na grid, se precisar.',
      'Clique em "Gravar Devolução" — cada item volta ao estoque da empresa atual e um vale-mercadoria é emitido automaticamente, com um comprovante pronto para imprimir ou salvar em PDF.',
      'O cliente usa esse vale numa compra futura, no PDV: escolhe "Vale-Mercadoria" como forma de pagamento e digita o número do vale impresso.',
    ],
    errosComuns: [
      'Não valida se a quantidade devolvida bate com o que foi vendido naquela venda — não há vínculo entre a devolução e a venda informada.',
      'O vale é sempre consumido por inteiro — se a compra futura for menor que o valor do vale, o sistema bloqueia o uso (sem troco em vale).',
      'Um vale já usado não pode ser usado de novo — o PDV mostra o erro na hora de digitar o número.',
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
      'Confirme em "Receber" quando o Saldo a Pagar chegar a zero — o comprovante abre sozinho, com opção de imprimir, salvar em PDF ou enviar por WhatsApp.',
      'Em "Enviar por WhatsApp", confirme (ou corrija) o celular do cliente no popup antes de continuar — o WhatsApp abre com o link de download já preenchido, e quem envia de fato é você, na própria conversa.',
    ],
    errosComuns: [
      'Nenhuma forma de pagamento aparece: cadastre ou libere uma em Tipo de Carteira, marcando "Permite receber parcelas de crediário".',
      'Não acho o cliente: confira se o nome/CPF/celular digitado está correto — a busca não exige os três juntos.',
      'O link enviado pelo WhatsApp expira em 24 horas — depois disso, use a Reimpressão de Recebimento de Crediário e envie de novo.',
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
  'financeiro.reimpressaorecebimentocrediario.tela': {
    titulo: 'Reimpressão de Papeleta de Recebimento de Crediário',
    objetivo: 'Localizar um recebimento de crediário já efetivado e reimprimir a papeleta (mesmo layout da 1ª via, com "REIMPRESSÃO" e a data/hora da reimpressão).',
    passos: [
      'Informe o nome do cliente (obrigatório) — data inicial/final de recebimento são opcionais, pra estreitar a busca.',
      'Cada linha é um recebimento inteiro (um "lote"), não uma parcela isolada.',
      'Clique numa linha do resultado para abrir a papeleta — imprima, salve em PDF ou envie por WhatsApp.',
      'Em "Enviar por WhatsApp", confirme (ou corrija) o celular do cliente no popup antes de continuar — o WhatsApp abre com o link de download já preenchido, e quem envia de fato é você, na própria conversa.',
    ],
    errosComuns: [
      'Não acho o recebimento: confira o nome do cliente e o intervalo de datas — o filtro de cliente é sempre obrigatório.',
      'O link enviado pelo WhatsApp expira em 24 horas — depois disso, é preciso reimprimir e enviar de novo.',
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
      'Operadores sempre veem só a própria empresa da sessão, mesmo com mais de uma selecionada.',
      '"Gerar PDF" demora 1-2 segundos (botão mostra "Gerando PDF…") — é a tela inteira sendo capturada, não um erro.',
    ],
    urlVideo: null,
  },
  'relatorios.comissoes.tela': {
    titulo: 'Relatório de Comissões',
    objetivo: 'Ver quanto cada funcionário vendeu, devolveu e tem de comissão a apurar no período.',
    passos: [
      'Escolha o período (data inicial e final) e, se for ADMIN, a(s) empresa(s).',
      'A grid traz uma linha por funcionário (por empresa, se houver mais de uma) com valor da venda, valor da devolução, valor líquido, % de comissão e valor da comissão.',
      'Com mais de uma empresa no resultado, a grid mostra um subtotal logo depois das linhas de cada empresa, além do total geral no fim.',
      '"Gerar PDF" captura a tela como está, com filtros aplicados na 1ª página e cabeçalho/rodapé repetidos em todas.',
    ],
    errosComuns: [
      '% de comissão vem do cadastro de Funcionário — se aparecer 0%, o funcionário não tem percentual configurado.',
      'Nenhuma comissão é paga ou lançada de verdade pelo sistema — é só o cálculo do relatório (venda líquida × % de comissão).',
      'Um funcionário só aparece se teve venda ou devolução no período — sem nenhuma das duas, ele simplesmente não sai na lista.',
    ],
    urlVideo: null,
  },
  'relatorios.contasreceber.tela': {
    titulo: 'Contas a Receber / Recebidas',
    objetivo: 'Ver as parcelas de cartão e crediário por período de venda, vencimento ou recebimento, com valor bruto e líquido.',
    passos: [
      'Preencha ao menos um dos três períodos (Venda, Vencimento ou Recebimento) — dá pra combinar mais de um ao mesmo tempo.',
      'Filtre por status se quiser: Todos, só Parcelas Em Aberto ou só Parcelas Recebidas.',
      'Filtre por forma de pagamento se quiser: Todos, só Crediário, só Cartão Débito ou só Cartão Crédito — combina livremente com o filtro de status.',
      'A grid traz uma linha por parcela: empresa, venda, cliente, forma de pagamento, parcela no formato "01/06" (nº da parcela sobre o total da mesma venda/forma), as três datas (sem hora), valor bruto, taxa administrativa e valor líquido.',
      'Uma coluna "Recebimento" vazia significa que a parcela ainda está em aberto.',
      'Com mais de uma empresa no resultado, aparece um subtotal logo depois das linhas de cada empresa, além do total geral no fim.',
      '"Gerar PDF" captura a tela como está, com filtros aplicados na 1ª página e cabeçalho/rodapé repetidos em todas.',
    ],
    errosComuns: [
      'Só aparecem parcelas de Cartão Débito, Cartão Crédito e Crediário — À Vista e Vale-Mercadoria nascem sempre quitados na hora, não entram aqui.',
      'Crediário nunca tem taxa administrativa (sempre 0%) — só Cartão Débito/Crédito descontam a taxa configurada em Tipo de Carteira, e o valor líquido é sempre calculado com a taxa atual do cadastro, não a que vigorava na data da venda.',
      'Sem nenhum período preenchido, a tela não busca nada — é preciso informar pelo menos um.',
    ],
    urlVideo: null,
  },
  'estoque.contagem': {
    titulo: 'Contagem de Estoque',
    objetivo: 'Registrar a contagem física dos produtos da loja lendo o código de barras.',
    passos: [
      'Leia o código de barras do produto e pressione Enter — não existe pesquisa por nome nesta tela, só o código.',
      'Dica: "5*código" soma 5 unidades de uma vez, sem precisar ler o código 5 vezes (máximo 1000 unidades por leitura).',
      'Ler o mesmo código de novo soma na quantidade já contada daquele produto — não duplica a linha.',
      'A quantidade contada de cada linha pode ser corrigida direto na grid (edite o número e saia do campo).',
      'O ícone de excluir remove o produto inteiro da contagem, sem afetar os demais.',
    ],
    errosComuns: [
      'A contagem é sempre da empresa em que você está logado — não existe seletor de empresa aqui.',
      'Terminou de contar? Use "Diferenças de Estoque" pra ver o que não bateu antes de efetivar.',
    ],
    urlVideo: null,
  },
  'estoque.diferencas': {
    titulo: 'Diferenças de Estoque',
    objetivo: 'Comparar a contagem ativa com o estoque do sistema antes de efetivar o balanço.',
    passos: [
      'A tela carrega sozinha — não tem filtro pra escolher, é sempre a contagem ativa da empresa logada.',
      'Só aparecem produtos onde a quantidade contada é diferente do estoque atual.',
      'Diferença em verde é sobra (contado maior que o estoque); em vermelho é falta.',
      'Clique no título de qualquer coluna pra ordenar.',
      '"Gerar PDF" captura a grid como está, com a empresa da contagem em destaque no topo.',
    ],
    errosComuns: [
      'Um produto em estoque que nunca foi lido também aparece aqui (contagem tratada como 0) — não é bug, é pra não passar batido.',
      'Se não há nenhuma contagem em andamento, a tela avisa isso — é diferente de "contagem bate exatamente com o estoque" (que também vem vazia, mas por outro motivo).',
    ],
    urlVideo: null,
  },
  'estoque.efetivar-balanco': {
    titulo: 'Efetivar Balanço',
    objetivo: 'Gravar as diferenças de estoque encontradas como ajuste definitivo.',
    passos: [
      'Revise a grid de diferenças (mesma informação de "Diferenças de Estoque").',
      'Clique em "Efetivar Balanço"; o popup mostra o total contado e o total em estoque antes de confirmar.',
      'Digite "efetiva contagem" no campo do popup pra habilitar o botão de confirmação — sem isso não efetiva.',
      'O estoque de cada produto passa a ser exatamente a quantidade contada; a contagem ativa desta empresa é zerada.',
    ],
    errosComuns: [
      'Sem nenhuma contagem em andamento (ou sem nenhuma diferença), o botão fica desabilitado e a tela avisa — não há nada para efetivar.',
      'Efetivou por engano? Em "Zerar Contagem de Estoque" tem a opção "Desfazer Última Efetivação".',
    ],
    urlVideo: null,
  },
  'estoque.zerar-contagem': {
    titulo: 'Zerar Contagem de Estoque',
    objetivo: 'Apagar a contagem em andamento, ou desfazer a última efetivação de balanço.',
    passos: [
      'A tela mostra o total de produtos e a quantidade contada ativa nesta empresa.',
      '"Zerar Contagem de Estoque" apaga tudo que foi contado e ainda não foi efetivado — é irreversível, por isso o popup só libera o botão de confirmar depois de digitar "zerar estoque".',
      '"Desfazer Última Efetivação" reverte o estoque para o valor de antes da última efetivação e devolve aquela contagem pro balanço ativo, pronta pra corrigir e efetivar de novo.',
    ],
    errosComuns: [
      'As duas ações valem só para a empresa em que você está logado.',
      'Só a efetivação mais recente pode ser desfeita — desfazer de novo (sem efetivar outra vez no meio) volta pra efetivação anterior a ela, uma de cada vez.',
    ],
    urlVideo: null,
  },
  'relatorios.estoque.tela': {
    titulo: 'Relatório de Estoque',
    objetivo: 'Ver a quantidade em estoque e o custo dos produtos em 3 modelos diferentes de relatório.',
    passos: [
      'Escolha o Modelo no popup de filtros: Inventário (uma linha por produto, quantidade e custo total), Sintético (uma linha por produto, quantidade aberta por empresa) ou Analítico (uma linha por variação — linha/coluna —, quantidade aberta por empresa).',
      'Filtre por Empresas (só ADMIN — OPERADOR sempre vê a própria), Marca, Categorias, Tipo de Quantidade (Diferente de Zero / Zerada) e Situação do Produto (Ativos / Inativos / Todos).',
      'Nos modelos Sintético e Analítico, cada empresa incluída no filtro vira uma coluna de quantidade própria, além da coluna de total.',
      'Clique no título de qualquer coluna pra ordenar — por padrão a lista vem por descrição do produto.',
      '"Gerar PDF" captura a grid como está, com os filtros aplicados no topo.',
    ],
    errosComuns: [
      'Um produto sem nenhuma variação cadastrada nunca aparece (não tem como ter estoque).',
      'O filtro de Tipo de Quantidade olha o total já somado da linha (todas as variações e empresas selecionadas) — não cada empresa isoladamente.',
      'Custo Unitário/Total (só no Inventário) usa o custo cadastrado no produto — não o custo da última compra.',
    ],
    urlVideo: null,
  },
  'relatorios.movimentacaoprodutos.tela': {
    titulo: 'Movimentação de Produtos',
    objetivo: 'Ver o histórico de tudo que entrou e saiu do estoque (Kardex) — diferente do Relatório de Estoque, que mostra só o saldo atual.',
    passos: [
      'Escolha o Modelo no popup de filtros: Analítico (uma linha por movimento, no período), Kardex por Produto (escolha um produto e uma empresa — mostra a ficha cronológica com saldo corrido) ou Sintético (totais por tipo de movimento).',
      'No Analítico/Sintético, filtre por Empresas (só ADMIN), Tipo de Movimento (Compra, Transferência, Devolução, Ajuste, Venda, Reserva, Liberação de Reserva, Cancelamento), Marca e Categorias.',
      'No Kardex, escolha a Empresa (só ADMIN) e busque o Produto pelo botão — o relatório mostra Saldo Inicial (tudo antes do período) e Saldo Final, com o saldo após cada movimento.',
      'A coluna Documento mostra de onde veio o movimento: nº da venda, da transferência, da devolução, fornecedor + nota fiscal (compra) ou o texto de origem (ex.: "contagem de estoque").',
      'O KPI "Saída Física"/"Entrada Física" e o gráfico "Movimentação por Tipo" ignoram Reserva/Liberação de Reserva de propósito — não são movimentação física real, é só reserva de saldo pra pedido de canal.',
      '"Top Ajustes Negativos por Produto" mostra onde o estoque físico deu menos do que o sistema esperava (contagem) — é o indicador de quebra/perda/furto.',
      '"Gerar PDF" captura os KPIs/gráficos (ou o cabeçalho do Kardex) numa página e a grid noutra.',
    ],
    errosComuns: [
      'Compra e Reserva/Liberação de Reserva ainda não têm tela própria que grave esses tipos — o filtro já lista os 8, mas só aparecem linhas quando essas telas existirem.',
      'O custo é o gravado no próprio movimento (histórico, de quando ele aconteceu). Movimentos gerados antes de 2026-08-04 não tinham esse valor gravado — pra esses, o relatório usa o custo ATUAL do cadastro do produto como aproximação.',
      'O Kardex soma TODOS os tipos de movimento no saldo corrido, inclusive Reserva/Liberação de Reserva — é o único jeito de bater com o saldo real do sistema.',
    ],
    urlVideo: null,
  },
  'configuracoes.etiquetaconfig.lista': {
    titulo: 'Configuração de Etiqueta de Produtos',
    objetivo: 'Ver e gerenciar os layouts de etiqueta de código de barras já cadastrados.',
    passos: [
      'Um tenant pode ter várias configurações nomeadas — impressoras/rolos diferentes por loja ou situação.',
      'Clique em "Nova configuração" pra montar um layout do zero, ou em "Editar" pra ajustar um já existente.',
      '"Visualizar" abre o layout em modo só leitura, sem risco de mudar nada por engano.',
    ],
    errosComuns: [
      'Esta tela só monta o LAYOUT — a impressão de verdade fica pra "Emissão de Etiqueta de Produtos" (ainda em Implementações Futuras).',
    ],
    urlVideo: null,
  },
  'configuracoes.etiquetaconfig.form': {
    titulo: 'Configuração de Etiqueta de Produtos',
    objetivo: 'Montar visualmente o layout de uma etiqueta: dimensões do rolo, colunas e quais campos aparecem, onde e como.',
    passos: [
      'No topo, preencha Ativa/Nome e, nos 3 cartões (Rolo e Etiqueta, Bordas, Posição das Colunas), a largura do rolo, o número de colunas (1 a 4), o tamanho da etiqueta e as bordas — tudo em milímetros.',
      'Ajuste a posição de cada coluna no rolo (a distância do início do rolo até cada etiqueta) — o valor é livre, não é calculado sozinho.',
      'No editor visual, clique num campo da paleta (à esquerda) pra colocá-lo na etiqueta — ele aparece com uma posição padrão.',
      'Arraste o campo pra posicioná-lo; arraste a alça no canto inferior-direito pra redimensionar; use as setas do teclado pra ajustar fino (Shift+seta move 5mm de uma vez).',
      'Clique num campo já colocado pra abrir um popup de propriedades e ajustar posição exata, tamanho, fonte, negrito, fundo preto/letra branca e alinhamento — feche pelo botão "Fechar" ou clicando fora.',
      'Pra código de barras (SKU), marque "Mostrar os dígitos embaixo" se quiser o número legível junto com as barras.',
      'Use "Escolher produto de exemplo" pra pré-visualizar a etiqueta com dados reais de um produto do seu catálogo (com ou sem variação/SKU cadastrado) — opcional, sem escolher aparece texto genérico.',
      'A régua em milímetros e o zoom (canto superior do editor) ajudam a posicionar com precisão.',
      'A "Prévia do rolo completo" embaixo do editor mostra como as etiquetas ficam lado a lado no rolo físico, uma por coluna configurada.',
      '"Testar Impressão" (topo da tela) imprime a quantidade de etiquetas que você informar, no tamanho físico real e com um quadro de corte ao redor de cada uma — pra testar na impressora antes de emitir de verdade.',
    ],
    errosComuns: [
      'Um campo com contorno vermelho está invadindo a margem de borda configurada — é só um aviso, não impede salvar, mas pode cortar na impressão real.',
      'O código de barras já sai no formato EAN-13 de verdade (o SKU sempre tem 13 dígitos com dígito verificador correto) — o que muda na impressão de verdade é só a proporção/qualidade da impressora, não a simbologia.',
      'Deletar (tecla Delete/Backspace com o campo selecionado) remove o campo da etiqueta — ele volta pra paleta e pode ser adicionado de novo.',
      '"Testar Impressão" fica desabilitado até ter rolo/etiqueta preenchidos e pelo menos 1 campo posicionado.',
    ],
    urlVideo: null,
  },
  'crm.tela': {
    titulo: 'CRM',
    objetivo: 'Filtrar clientes por perfil e histórico de compras, ver o resultado numa grid e exportar pra planilha Excel (campanhas de marketing/relacionamento).',
    passos: [
      'Ao entrar na tela, um popup abre automaticamente com os filtros e as colunas — configure antes de localizar.',
      '"Filtros de Clientes" (popup) — faixa alfabética do nome, gênero, idade, aniversário (dia/mês, ignora o ano), categoria do cliente, período de cadastro e nº mínimo de dias sem comprar. Todos opcionais e combináveis.',
      '"Filtros de Produtos Comprados" (popup) — período da compra, categoria de produto, variação de linha e variação de coluna. Quando qualquer um está preenchido, só entram clientes que compraram um produto batendo em TODOS eles ao mesmo tempo (mesmo item, não compras diferentes).',
      'Marque em "Dados do Cliente Para Geração" quais colunas quer ver: Nome, Data de Nascimento, Gênero, E-mail, Celular, Primeira Compra, Última Compra, Nº de Compras, Valor Total Comprado, Ticket Médio e Nº de Dias sem Comprar — também define as colunas da grid de resultado.',
      'Clique em "Localizar Clientes" — o popup fecha, aparece um indicador de progresso e depois a grid com o resultado. Clique num título de coluna pra ordenar (alterna crescente/decrescente); o total de clientes aparece ao lado do botão e no rodapé fixo da grid; só a área de dados rola, o cabeçalho fica fixo.',
      '"Alterar Filtros e Colunas" reabre o popup mantendo a seleção atual, pra ajustar e localizar de novo.',
      'Clique em "Gerar Planilha Excel" pra baixar um .xlsx com uma linha por cliente já localizado (não busca de novo); o toast de sucesso mostra quantos clientes entraram.',
    ],
    errosComuns: [
      'Nenhum cliente encontrado: confira se os filtros de "Produtos Comprados" não estão pedindo uma combinação (categoria + linha + coluna) que nenhum produto realmente tem junto.',
      'Idade/Aniversário não filtram quem não tem data de nascimento cadastrada (comum em Pessoa Jurídica).',
      'Primeira/Última Compra e Nº de Compras sempre mostram o HISTÓRICO COMPLETO do cliente — não ficam limitados ao período usado no filtro "Produtos Comprados" (esse filtro só decide QUEM entra na lista).',
      'Venda cancelada não conta como compra em nenhum filtro nem coluna.',
      '"Gerar Planilha Excel" fica desabilitado até localizar ao menos uma vez, e some se nenhuma coluna estiver marcada.',
    ],
    urlVideo: null,
  },
  'relatorios.etiquetaemissao.tela': {
    titulo: 'Emissão de Etiqueta de Produtos',
    objetivo: 'Selecionar produtos e quantidades e imprimir etiquetas de código de barras em lote, usando um modelo já criado em Configuração de Etiqueta.',
    passos: [
      '"＋ Selecionar Produtos" abre um popup com 3 formas de escolher: Individual (busca 1 produto — com ou sem código de barras já cadastrado —, escolhe a variação de linha/coluna quando o produto usa essas dimensões, digita a quantidade), Por Entradas (período/fornecedor/nota fiscal — a quantidade vem da entrada) e Por Estoques (empresa obrigatória + categoria opcional — a quantidade vem do saldo em estoque).',
      'No modo Individual, se o produto escolhido não tiver código de barras cadastrado ainda para aquela variação, o sistema gera um automaticamente ao adicionar à lista — não precisa cadastrar antes.',
      'Cada busca ADICIONA à lista da tela principal (o popup não fecha sozinho) — dá pra combinar as 3 formas antes de fechar.',
      'Na grade, ajuste a quantidade de qualquer item direto no campo, clique no ícone vermelho pra remover um item, ou em "Limpar Lista" pra esvaziar tudo de uma vez.',
      'Clique em "Emitir Etiquetas" — escolha o modelo (layout já criado em Configuração de Etiqueta) e clique em "Imprimir".',
    ],
    errosComuns: [
      'No modo Individual, se o produto usa variação de linha e/ou coluna (configurado no cadastro dele), o respectivo seletor aparece como obrigatório — não dá pra adicionar sem escolher.',
      '"Por Entradas" sem resultado: nenhuma tela de Entrada de Produtos por Compra existe ainda no sistema — o filtro busca no schema real, mas só vai ter dado quando essa área for construída (ou dado inserido manualmente).',
      '"Por Estoques" só traz produtos com saldo positivo (zerado ou negativo não aparece).',
      'Quantidade vinda de estoque/entrada fracionária (produto por peso/medida) é arredondada pro inteiro mais próximo — etiqueta não existe em fração.',
      '"Emitir Etiquetas" fica desabilitado até ter pelo menos 1 produto na lista; "Imprimir" só some quando um modelo é escolhido.',
    ],
    urlVideo: null,
  },
  'configuracao.importacao': {
    titulo: 'Importação de Dados',
    objetivo: 'Trazer cadastros e saldo de crediário de um sistema anterior para o Niner, via arquivo CSV.',
    passos: [
      'Escolha a tabela (Clientes, Fornecedores, Produtos ou Contas a Receber).',
      'Baixe o modelo .csv e preencha fora do sistema, seguindo o formato indicado (delimitador ";", decimal com vírgula, datas dd/mm/aaaa).',
      'Envie o arquivo preenchido.',
      'Resolva as escolhas prévias pedidas (ex.: categoria de cliente, plano de contas, carteira de crediário, ou mapeamento das colunas de estoque para as empresas).',
      'Revise a prévia — quantas linhas seriam importadas, ignoradas (já existiam) ou rejeitadas, com o motivo de cada rejeição.',
      'Clique em "Confirmar importação" para gravar de verdade.',
    ],
    errosComuns: [
      'Linha rejeitada: veja o motivo na lista de erros, corrija só aquela linha no arquivo original e reenvie.',
      'Cliente/fornecedor "ignorado": já existia um cadastro com o mesmo CPF/CNPJ — o sistema reaproveitou em vez de duplicar.',
      'Contas a Receber: o cliente precisa já estar cadastrado (com CPF/CNPJ preenchido) e a empresa precisa bater com o nome cadastrado.',
    ],
    urlVideo: null,
  },
  'configuracao.exportacao': {
    titulo: 'Exportação de Dados',
    objetivo: 'Baixar em planilha Excel (.xlsx) tudo que já está cadastrado no Niner — clientes, fornecedores, produtos, estoque, financeiro etc.',
    passos: [
      'Escolha a tabela desejada.',
      'A planilha é gerada e baixada automaticamente, com todos os registros já cadastrados.',
    ],
    errosComuns: [
      '"Nenhum registro encontrado": a tabela escolhida ainda não tem nenhum dado cadastrado neste tenant.',
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
