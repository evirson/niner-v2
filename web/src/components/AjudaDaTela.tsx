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

/** As 4 telas de Configuração de Tela (Cliente/Fornecedor/Funcionário/Produto) são irmãs: mesma
 *  estrutura, mesmo comportamento, só muda o cadastro configurado. Uma fábrica evita quatro
 *  cópias que envelheceriam em ritmos diferentes. */
function configuracaoDeTela(cadastro: string): ConteudoAjuda {
  return {
    titulo: `Configurar tela de ${cadastro}`,
    objetivo: `Escolher quais campos do cadastro de ${cadastro} aparecem na tela e quais são de preenchimento obrigatório, para esta loja.`,
    passos: [
      'Cada linha é um campo do cadastro. Marque "Visível" para o campo aparecer na tela e "Obrigatório" para exigir o preenchimento.',
      'Um campo escondido nunca pode ser obrigatório — ao desmarcar "Visível", a obrigatoriedade sai junto.',
      'Alguns campos são essenciais e não podem ser escondidos nem deixar de ser obrigatórios; nesses, os controles ficam travados.',
      'Clique em "Salvar". A mudança vale para todos os usuários desta loja, na hora.',
    ],
    errosComuns: [
      'Escondi um campo por engano e agora não acho o dado antigo: nada é apagado — o valor continua gravado e volta a aparecer assim que o campo for marcado como visível de novo.',
      'A obrigatoriedade também é conferida no servidor, não só na tela — então marcar "Obrigatório" aqui realmente impede salvar o cadastro sem aquele campo.',
      'Só o administrador pode alterar esta configuração.',
    ],
    urlVideo: null,
  }
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
  // 'cadastros.cliente.categoria' removida em 2026-08-14: era chave órfã — nenhum arquivo do
  // repositório a referenciava, e não existe tela nem modal de "Categorias de cliente". As
  // categorias são mantidas dentro do próprio cadastro de Cliente.
  // Configuração de tela (visibilidade/obrigatoriedade de campo por tenant) — 4 telas irmãs,
  // mesma estrutura, mesma ajuda com o nome do cadastro trocado. R22 exige entrada em TODA tela;
  // faltavam desde que essas telas nasceram (corrigido em 2026-08-14).
  'comum.telaconfig.cliente': configuracaoDeTela('Cliente'),
  'comum.telaconfig.fornecedor': configuracaoDeTela('Fornecedor'),
  'comum.telaconfig.funcionario': configuracaoDeTela('Funcionário'),
  'comum.telaconfig.produto': configuracaoDeTela('Produto'),
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
  'estoque.entrada.lista': {
    titulo: 'Entrada de Produtos por Compra',
    objetivo: 'Ver o histórico de entradas de mercadoria e iniciar uma nova.',
    passos: [
      'Ao abrir a tela, um popup pede os filtros (fornecedor, empresa, nota fiscal, período) — clique em "Localizar" para ver a lista, "＋ Nova entrada" para pular direto para uma nova entrada, ou "Fechar" para voltar à tela anterior.',
      'Clique no ícone verde para ver os detalhes (itens, quantidades e custos) de uma entrada já confirmada.',
      'Administradores podem cancelar uma entrada (ícone vermelho) informando o motivo — o estoque recebido é estornado e as contas a pagar geradas por ela são removidas.',
    ],
    errosComuns: [
      'Entradas já confirmadas não podem ser excluídas — o cabeçalho é um registro permanente (P3); a correção é editar a quantidade/custo de um item já lançado (direto na tela de detalhes) ou cancelar a entrada inteira (Administrador).',
      'Uma entrada cancelada aparece na lista com a marca "Cancelada" — não some do histórico.',
    ],
    urlVideo: null,
  },
  'estoque.entrada.form': {
    titulo: 'Nova entrada de mercadoria',
    objetivo: 'Registrar a compra de mercadoria de um fornecedor, dando entrada no estoque por XML de NF-e, item a item ou por planilha.',
    passos: [
      'No lançamento por XML, envie primeiro o arquivo da NF-e — fornecedor, empresa, nota, data e parcelas são preenchidos automaticamente a partir dele; produtos sem correspondência ficam pendentes de vínculo ou cadastro antes de confirmar.',
      'Escolha o fornecedor e, se o tenant tiver mais de uma empresa, em qual empresa a mercadoria está entrando (Administrador escolhe qualquer uma; Operador só as empresas liberadas para ele).',
      'No lançamento Individual, busque cada produto (nome, marca ou referência) e informe quantidade e custo unitário.',
      'No lançamento por Planilha, baixe o modelo, preencha e envie de volta — o sistema tenta casar cada linha com o cadastro (por código de barras do fabricante, ou por descrição/marca/referência e cor/tamanho da grade) e cria cor/tamanho novos automaticamente quando fizer sentido; linhas que não casarem ficam pendentes de resolução antes de confirmar.',
      'Se quiser gerar as parcelas em Contas a Pagar, informe número da duplicata, vencimento e valor de cada parcela (no XML, isso vem automático quando a nota traz as duplicatas).',
      'Confirme a entrada — o estoque sobe na mesma hora (trigger do banco, o serviço não mexe em saldo na mão).',
    ],
    errosComuns: [
      'Fornecedor é sempre obrigatório, mesmo em ajustes sem nota fiscal.',
      'Rateio de frete/IPI/ICMS-ST no custo e o reajuste automático de preço só têm efeito se as flags correspondentes estiverem ligadas em Parâmetros do Sistema (Configurações).',
      'Uma nota fiscal com a mesma chave de acesso já importada antes é rejeitada — não duplica estoque (a não ser que a importação anterior tenha sido cancelada).',
      'No fluxo por XML, cor e tamanho de um item nunca são cadastrados sozinhos — o operador sempre confirma, mesmo quando o sistema já identifica um palpite.',
      'Se "Usa cor/grade" estiver desligado em Parâmetros do Sistema, nenhuma tela desta entrada pede cor ou tamanho — mesmo para produtos que já tinham grade cadastrada antes.',
      'Com "Usa cor/grade" desligado, linhas do XML/planilha com o mesmo nome de produto (tamanhos diferentes de uma grade legada, por exemplo) aparecem somadas numa única linha, tanto em "Localizados" quanto em "Não Localizados" — resolver ou ignorar uma resolve/ignora o grupo inteiro.',
      'Ao cadastrar um fornecedor novo por esta tela, o plano de contas é sempre "Compra de Mercadoria para Revenda" (o definido em Parâmetros do Sistema) — não aparece campo para escolher outro.',
      'Com "Consistir valor das contas a pagar na entrada" ligado (padrão) em Parâmetros do Sistema, a soma das duplicatas precisa ser exatamente igual ao total dos produtos lançados — enquanto não bater, o botão Confirmar fica bloqueado. Desligue o parâmetro se a loja precisa lançar entradas com divergência (adiantamento, parte à vista).',
      'O valor das parcelas é dividido automaticamente pelo Nº de Parcelas e acompanha qualquer mudança de quantidade/custo — até você digitar um valor de parcela à mão, que congela a divisão (mudar o Nº de Parcelas recalcula tudo de novo).',
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
    objetivo: 'Cadastrar um usuário novo ou editar um existente, escolher em quais empresas ele pode operar e, se quiser, restringir os dias/horários em que ele pode acessar o sistema.',
    passos: [
      'Preencha nome, e-mail e senha (obrigatórios para um usuário novo).',
      'Marque ao menos uma empresa em "Empresas com acesso" — o usuário só opera nas empresas selecionadas.',
      'Para restringir quando o usuário pode trabalhar, marque "Controla horário de acesso" e preencha início/fim de cada dia da semana — deixe os dois campos em branco num dia para não liberar acesso nele (ex.: folga). Nunca aparece no cadastro do Administrador, que não é afetado por esta regra.',
      'Na edição, deixe a senha em branco para manter a senha atual.',
      'Salve.',
    ],
    errosComuns: [
      'E-mail já cadastrado: cada e-mail só pode pertencer a um usuário.',
      'Nenhuma empresa selecionada: é obrigatório marcar ao menos uma.',
      'Só existe um Administrador por loja, definido na assinatura — esta tela não cria nem promove Administrador; ao editar o Administrador, o formulário mostra um aviso somente-leitura em vez de um campo.',
      'Horário de acesso: se marcar "Controla horário de acesso", é preciso preencher início E fim juntos em cada dia usado (com o fim depois do início) — deixar só um dos dois preenchido, ou o fim antes do início, é rejeitado ao salvar.',
      'Fora do horário liberado, o usuário não consegue fazer login; já logado, o sistema avisa com um contador de 15 minutos antes de encerrar a sessão sozinho — mas nunca no meio de uma venda em andamento no PDV, que sempre termina antes do encerramento.',
      'A permissão fina por rotina/tela (além de Administrador/Operador) ainda não existe nesta versão.',
    ],
    urlVideo: null,
  },
  'relatorios.fluxocaixa': {
    titulo: 'Fluxo de Caixa',
    objetivo: 'Ver para onde foi o dinheiro e se vai faltar dinheiro à frente.',
    passos: [
      'A aba "Realizado" mostra o dinheiro que entrou e saiu de verdade no período, separado por atividade: operacional (o dia a dia da loja), investimento (compra de bem, reforma) e financiamento (empréstimo, aporte, retirada de sócio).',
      'No rodapé, confira se o saldo calculado bate com o saldo real de hoje (caixa + conta corrente) — se bater, o relatório está confiável.',
      'A aba "Projeção" parte do saldo de hoje e soma o que ainda vai entrar (contas a receber em aberto) e sair (contas a pagar em aberto), por dia, semana ou mês.',
      'Se o saldo ficar negativo em alguma data, aparece um aviso em vermelho no topo com a data e quanto falta — é o momento de antecipar recebimento ou renegociar um pagamento.',
      'Contas já vencidas e ainda não pagas/recebidas entram no primeiro período da projeção, marcadas como "inclui vencidos".',
    ],
    errosComuns: [
      'A projeção não adivinha vendas futuras — ela só considera o que já está lançado como conta a receber ou a pagar. O que você ainda vai vender no mês não aparece aqui.',
      'Pagamentos registrados antes de 14/08/2026 não aparecem no Realizado: naquela época a baixa não gerava movimento de caixa. Contas pagas a partir dessa data aparecem normalmente.',
      'Lucro não é dinheiro: a DRE pode mostrar lucro num mês em que faltou dinheiro, porque a venda no crediário só vira caixa quando o cliente paga.',
      'Se o saldo calculado não bater com o saldo real, o motivo mais comum é um pagamento lançado duas vezes — baixado em Contas a Pagar e também lançado na Movimentação de Conta Corrente.',
    ],
    urlVideo: null,
  },
  'relatorios.dre': {
    titulo: 'DRE — Demonstração do Resultado',
    objetivo: 'Ver se a loja deu lucro no período, e por quê.',
    passos: [
      'Escolha o período e o regime. "Competência" conta a venda no dia em que ela aconteceu, mesmo que o cliente vá pagar depois — é o lucro real do período. "Caixa" conta só o dinheiro que entrou e saiu de verdade.',
      'Uma venda no crediário aparece inteira na Competência no dia da venda, e vai pingando na Caixa conforme as parcelas são recebidas — não é erro, é a diferença entre vender e receber.',
      'A coluna AV % mostra quanto cada linha representa da receita líquida (ex.: "aluguel = 14% do que sobrou depois das deduções").',
      'A comparação com o período anterior mostra o que melhorou (verde) e o que piorou (vermelho) — para despesa, crescer é piorar.',
      'Margem de Contribuição é o que sobra das vendas depois do custo da mercadoria e dos custos que só existem quando se vende (comissão, taxa de cartão) — é dela que saem o aluguel, os salários e o lucro.',
      'O botão "Gerar PDF" salva a DRE em A4 retrato, com regime e período impressos no cabeçalho de todas as páginas — é o formato para mandar ao contador.',
    ],
    errosComuns: [
      'A compra de mercadoria não aparece como despesa: comprar estoque não é gasto, é troca de dinheiro por mercadoria. O custo entra como CMV quando a mercadoria é vendida.',
      'CMV é o custo do que foi vendido no período, calculado pelo custo real gravado em cada venda — não pelo que você comprou no mês.',
      'Distribuição de lucro para os sócios não é despesa e não aparece aqui; pró-labore, sim.',
      'No regime de Caixa, não lance na Movimentação de Conta Corrente um pagamento que você já baixou em Contas a Pagar — o valor sairia duas vezes.',
      'Só administradores acessam esta tela.',
    ],
    urlVideo: null,
  },
  'configuracao.geral.form': {
    titulo: 'Parâmetros do sistema',
    objetivo: 'Ajustar as regras gerais do tenant: desconto máximo em venda, exigência de venda na devolução, uso de cor/grade, taxas de crediário e regras da Entrada de Produtos por Compra.',
    passos: [
      'Informe o desconto máximo permitido em uma venda.',
      'Marque "Exigir número da venda na Devolução de Produtos" se quiser bloquear devoluções sem vínculo com uma venda — a tela passa a exigir o número da venda e só aceita produtos que ela vendeu.',
      'Marque "Usa cor/grade" se o tenant é de calçados ou confecções — liga o campo Grade no cadastro de Produto e as variações passam a ter cor e tamanho.',
      'Preencha os prazos e percentuais de juros/multa do crediário — ficam prontos para quando o módulo de crediário existir.',
      'Marque "Ratear frete/IPI/ICMS-ST no custo" se quiser que o valor de rateio informado numa Entrada de Produtos seja distribuído proporcionalmente entre os itens da nota.',
      'Marque "Reajustar preço na entrada" se quiser que o custo/preço de venda do produto sejam atualizados automaticamente a cada Entrada de Produtos.',
      'Marque "Consistir valor das contas a pagar na entrada" se a soma das duplicatas tiver de ser sempre igual ao total dos produtos lançados — uma entrada de R$ 1.500,00 só é confirmada com duplicatas somando R$ 1.500,00. Desmarque para permitir divergência (adiantamento, parte à vista, nota parcialmente financiada).',
      'Escolha o plano de contas usado nas contas a pagar geradas pela Entrada de Produtos por Compra.',
      'Salve.',
    ],
    errosComuns: [
      'Só administradores acessam esta tela — usuários OPERADOR não têm este item no menu.',
      'Percentuais devem ficar entre 0 e 100.',
    ],
    urlVideo: null,
  },
  'fiscal.configuracao.form': {
    titulo: 'Configuração Fiscal',
    objetivo: 'Definir como esta empresa emite nota fiscal: regime tributário, ambiente (homologação/produção), numeração e credenciamento junto à SEFAZ.',
    passos: [
      'Escolha a empresa no seletor do topo — cada empresa (matriz/filiais) tem sua própria configuração fiscal.',
      'Escolha o Regime Tributário (CRT). O Niner atende só Simples Nacional (1 e 2) e MEI (4) — Lucro Real e Lucro Presumido não são atendidos por este produto.',
      'Enquanto o ambiente estiver em Homologação, nenhuma nota emitida tem valor fiscal — é assim que se testa sem risco.',
      'Ligar "Emitir NFC-e"/"Emitir NF-e" passa por uma conferência automática (certificado válido, CNPJ, Inscrição Estadual, município, CNAE). Faltando algo, a tela recusa e explica o que falta.',
      'A série de numeração fica travada assim que a primeira nota é autorizada nela — trocar depois quebraria a sequência exigida pela lei.',
      'O Token do CSC nunca é mostrado de volta depois de salvo — o campo aparece vazio, e digitar de novo troca; para remover, marque "Remover o CSC atual".',
    ],
    errosComuns: [
      'Só administradores acessam esta tela.',
      'Não consigo ligar a emissão: veja a mensagem — ela lista exatamente o que falta (certificado, CNPJ, Inscrição Estadual…).',
      'A série de numeração está travada: já existe nota autorizada nela; não é possível trocar.',
    ],
    urlVideo: null,
  },
  'fiscal.perfil.tela': {
    titulo: 'Perfis Fiscais',
    objetivo: 'Cadastrar as regras de tributação (CFOP, ICMS, PIS/COFINS, IBS/CBS) que o produto usa na nota — sem digitar isso em cada produto individualmente.',
    passos: [
      'Um perfil agrupa uma ou mais regras. Cada regra vale para um contexto: CRT do emitente, UF de destino (ou "*" para qualquer uma), tipo de destinatário e tipo de operação.',
      'Em CRT 1 e 4 (a maioria) o ICMS é sempre por CSOSN. Só o CRT 2 (excesso de sublimite) pode escolher entre CSOSN e CST — se não souber qual usar, confirme com o contador.',
      'PIS/COFINS: o normal é CST 99 (tributo dentro do DAS, sem alíquota). Só use outro CST para produto com tratamento próprio (monofásico, alíquota zero) — aí a alíquota é do produto.',
      'A regra mais específica ganha: uma UF exata vale mais que "*". Sem nenhuma regra que case, a nota não sai — o sistema nunca chuta um CFOP.',
      'Aponte cada produto para um perfil na tela de Produto. Um perfil pode ser usado por quantos produtos precisar.',
    ],
    errosComuns: [
      'Só administradores acessam esta tela — uma regra errada aqui afeta todos os produtos do perfil.',
      'Não consigo excluir um perfil: ele está em uso por algum produto — o sistema inativa em vez de excluir; o perfil continua valendo para quem já apontava para ele.',
    ],
    urlVideo: null,
  },
  'fiscal.certificado.tela': {
    titulo: 'Certificado Digital',
    objetivo: 'Enviar o certificado A1 (.pfx) da empresa — obrigatório para assinar e transmitir qualquer nota fiscal.',
    passos: [
      'O certificado A1 é comprado de uma Autoridade Certificadora (AC) credenciada pela ICP-Brasil, em nome do CNPJ da empresa.',
      'Escolha a empresa, selecione o arquivo .pfx e informe a senha. CNPJ, razão social e validade são lidos direto do arquivo — nunca digitados.',
      'O sistema nunca devolve o certificado nem a senha depois do envio, para ninguém — nem para o administrador.',
      'O certificado precisa ser do MESMO CNPJ da empresa. Se for de outra empresa (matriz/filial trocadas), o envio é recusado.',
      'Enviar um certificado novo substitui o anterior automaticamente — o antigo fica guardado (histórico de qual assinou qual nota) mas para de ser usado.',
      'Acompanhe o badge de validade: fica amarelo faltando 30 dias, vermelho faltando 7 — combine a renovação com a AC antes de vencer.',
    ],
    errosComuns: [
      'Só administradores acessam esta tela.',
      'Envio recusado por senha incorreta: confira a senha do certificado, não a senha do sistema.',
      'Envio recusado por CNPJ diferente: o certificado pertence a outra empresa (comum trocar matriz e filial).',
      'Certificado vencido: emitir nota vai falhar até subir um certificado novo.',
    ],
    urlVideo: null,
  },
  'fiscal.conformidade.tela': {
    titulo: 'Conformidade Fiscal',
    objetivo: 'Ver o que impede esta empresa de ligar o fiscal e emitir nota, antes de descobrir no caixa com o cliente na frente.',
    passos: [
      'Escolha a empresa no seletor do topo.',
      'O topo mostra um veredito: "Pronto para emitir" (verde) ou quantas pendências bloqueiam a emissão (vermelho).',
      'Clique em qualquer categoria (Empresa, Produtos, Formas de pagamento, Clientes) para ver a lista de registros com problema.',
      'Categorias que bloqueiam (vermelho) impedem realmente ligar a emissão. Clientes só avisa (amarelo) — nunca bloqueia.',
      'Clique em "Corrigir" numa linha pra abrir o cadastro daquele registro numa aba nova. Esta tela não edita nada — só aponta.',
    ],
    errosComuns: [
      'Só administradores acessam esta tela.',
      'Cliente sem município IBGE não impede vender no balcão (a NFC-e sem identificação não exige isso) — só impede devolver depois de 30 minutos. Por isso é aviso, não bloqueio.',
      'Formas de pagamento aparecem sempre com pendência hoje: o código de forma de pagamento ainda não tem campo na tela de Tipo de Carteira.',
    ],
    urlVideo: null,
  },
  'cadastros.planocontas.lista': {
    titulo: 'Plano de Contas',
    objetivo: 'Encontrar e gerenciar as contas do plano de contas (usadas por fornecedores, contas a pagar e relatórios).',
    passos: [
      'Use a busca por código (ex.: "4.01") ou por descrição.',
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
      'Se os Parâmetros do Sistema tiverem "Usa cor/grade" habilitado, escolha a Grade deste produto (ou crie uma nova pela opção "＋ Gerenciar Grades") — a cor de cada variação é definida depois, na Entrada de Produtos.',
      'Ao digitar o código NCM, a descrição aparece automaticamente ao lado, se o código estiver cadastrado.',
      'Salve.',
      'Depois de salvo, a seção "Fotos" permite adicionar até 6 imagens (JPEG/PNG/WebP) — use as setas pra reordenar e a lixeira pra excluir.',
    ],
    errosComuns: [
      'Não vejo o campo Grade: confira os Parâmetros do Sistema (só ADMIN acessa) — o campo "Usa cor/grade" controla se ele aparece.',
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
      'Informe o código contábil no formato 9.99.999 (ex.: "4.01.001") — ele identifica a conta e não pode ser alterado depois.',
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
      'Use a busca por número do documento, ou filtre por período, empresa, plano de contas, conta corrente e "Compensado".',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
      'Lançamento marcado como "Baixa automática" veio do pagamento de uma conta a pagar, não da digitação aqui — por isso não tem os ícones de editar e excluir.',
    ],
    errosComuns: [
      'Não encontro um lançamento: confira os filtros — período, empresa, plano de contas, conta corrente e "Compensado" combinam entre si (todos precisam bater).',
      'Não consigo editar ou excluir um lançamento: se ele está marcado como "Baixa automática", a alteração é feita na conta a pagar que o gerou (Financeiro › Contas a Pagar / Pagas) — o movimento da conta corrente acompanha sozinho.',
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
  'financeiro.contaspagar.lista': {
    titulo: 'Contas a Pagar / Pagas',
    objetivo: 'Encontrar e gerenciar as duplicatas de fornecedor a pagar.',
    passos: [
      'Ao entrar na tela, um popup pede os filtros: fornecedor, empresa, nota fiscal, duplicata, e o período de vencimento ou de pagamento.',
      'Deixe tudo em branco e clique em "Localizar" para ver todas as contas, ou use "＋ Nova Conta a Pagar" para pular direto pro cadastro.',
      'Clique no ícone verde para visualizar, no azul para editar, ou no vermelho para excluir.',
    ],
    errosComuns: [
      'Não encontro uma conta: confira os filtros — clique em "Alterar Filtros" e tente um período mais amplo.',
      'Ao excluir uma conta já paga, a saída de dinheiro que ela gerou (no caixa ou na conta corrente) é apagada junto — não fica sobrando lançamento sem conta.',
      '"Esta operação mexe no caixa nº X, que já está fechado": o pagamento saiu de um caixa que já foi encerrado. Peça ao administrador para reabrir aquele caixa em Frente de Loja › Caixa › Fechamento de Caixa, exclua de novo, e feche o caixa.',
    ],
    urlVideo: null,
  },
  'financeiro.contaspagar.form': {
    titulo: 'Cadastro de conta a pagar',
    objetivo: 'Cadastrar uma conta a pagar nova, editar uma existente, ou registrar o pagamento de uma já lançada.',
    passos: [
      'Busque e escolha o fornecedor, a empresa e o plano de contas.',
      'Informe a data de vencimento e o valor a pagar — nota fiscal e duplicata são opcionais.',
      'Para registrar que a conta foi paga, preencha Data de Pagamento, Valor Pago e marque "Documento Pago" — não existe uma tela de "Pagar" separada, isso é feito editando a própria conta.',
      'Ao informar a data de pagamento aparece o campo "De onde saiu o dinheiro": escolha Caixa da loja ou Conta corrente. O sistema lança a saída no lugar escolhido, e é isso que faz o pagamento aparecer no Fluxo de Caixa.',
      'Salve.',
    ],
    errosComuns: [
      'Não acho "Pagar" na grid: a baixa é feita editando a conta (ícone azul) e preenchendo os campos de pagamento.',
      'Para pagar em dinheiro é preciso ter caixa aberto — a saída é lançada no seu caixa do dia. Se não houver caixa aberto, ao escolher "Caixa da loja" a própria tela abre o formulário de abertura; se preferir, clique em Voltar e pague pela conta corrente.',
      'Se você desfizer a baixa (apagar a data de pagamento), a saída de dinheiro correspondente é apagada junto — o saldo volta ao que era. O mesmo vale ao excluir a conta: a saída do caixa ou do banco vai junto, não fica sobrando.',
      '"Esta operação mexe no caixa nº X, que já está fechado": o pagamento saiu de um caixa que já foi encerrado. Peça ao administrador para reabrir aquele caixa em Frente de Loja › Caixa › Fechamento de Caixa, refaça a alteração ou a exclusão, e feche o caixa de novo.',
      'Contas pagas antes de 14/08/2026 não têm essa informação de origem e continuam editáveis normalmente; elas só não aparecem no Fluxo de Caixa realizado.',
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
      'Precisa desfazer algo de um caixa já fechado (estornar um recebimento, excluir ou reabrir uma conta a pagar)? Só o administrador vê o botão "Reabrir Caixa" num caixa fechado. Reabrir exige informar o motivo, apaga a conferência que estava gravada e libera a correção — depois é só fechar o caixa de novo, refazendo a contagem às cegas.',
    ],
    errosComuns: [
      'Um caixa já fechado não pode ser fechado de novo — a tela responde com um aviso de conflito.',
      'Operadores que tentam informar outro usuário ou fechar o caixa de outra pessoa recebem acesso negado — só administradores podem.',
      'Faltar o valor contado de alguma carteira com movimento impede o fechamento.',
      '"Esta operação mexe no caixa nº X, que já está fechado": você tentou estornar um recebimento ou mexer numa conta a pagar cujo pagamento saiu de um caixa já encerrado. Peça ao administrador para reabrir aquele caixa aqui, refaça a operação e feche de novo.',
      'Não dá pra reabrir um caixa se o mesmo operador já tem outro caixa aberto — feche o que está aberto primeiro, senão haveria dois caixas abertos e o PDV não saberia em qual lançar.',
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
      'Impressão saindo apagada ou com as letras "falhadas"? O sistema já imprime em negrito, no tamanho certo para a bobina de 80mm. Se ainda sair fraco, o ajuste é na impressora: aumente a densidade (Density/Darkness) e, no driver, coloque o meio-tom (Halftone) em modo texto ou nenhum — assim ela imprime preto sólido em vez de pontinhos alternados.',
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
  'vendas.cancelamentodevolucao.lista': {
    titulo: 'Cancelamento de Devolução de Produtos',
    objetivo: 'Localizar um vale-mercadoria ainda não usado e cancelá-lo, retirando do estoque a quantidade que a devolução tinha devolvido.',
    passos: [
      'No popup inicial, informe o número do vale (ignora as datas) ou um intervalo de datas da devolução.',
      'A grid mostra só vales ainda canceláveis — não usados e não cancelados.',
      'Clique no ícone de visualizar na linha do vale para ver o resumo e os itens que vão sair do estoque.',
      'Informe o motivo do cancelamento e confirme em "Sim, Cancelar Devolução".',
    ],
    errosComuns: [
      'Um vale já usado numa venda não pode ser cancelado — cancele antes a venda que o consumiu (Cancelamento de Venda), se for o caso.',
      'Uma devolução já cancelada não pode ser cancelada de novo.',
      'Operador só enxerga e cancela devoluções da empresa em que está logado — administrador cancela de qualquer empresa.',
    ],
    urlVideo: null,
  },
  'vendas.devolucaoproduto.form': {
    titulo: 'Devolução de Produtos',
    objetivo: 'Devolver ao estoque produtos que o cliente trouxe de volta e emitir um vale-mercadoria pelo crédito.',
    passos: [
      'Informe o número da venda e saia do campo — o vendedor é identificado automaticamente. A partir daí, só é possível devolver produtos que fizeram parte daquela venda, até a quantidade ainda não devolvida dela. O campo pode ser opcional ou obrigatório dependendo da configuração do tenant (Parâmetros do Sistema); quando opcional e deixado em branco, a devolução não tem vínculo com nenhuma venda.',
      'Leia o código de barras de cada produto devolvido — a grid vai empilhando os itens, somando a quantidade se o mesmo código for lido de novo.',
      'Ajuste a quantidade de qualquer linha direto na grid, se precisar.',
      'Clique em "Gravar Devolução" — cada item volta ao estoque da empresa atual e um vale-mercadoria é emitido automaticamente, com um comprovante pronto para imprimir ou salvar em PDF.',
      'O cliente usa esse vale numa compra futura, no PDV: escolhe "Vale-Mercadoria" como forma de pagamento e digita o número do vale impresso.',
    ],
    errosComuns: [
      'Com a venda informada: um produto que não fez parte dela, ou uma quantidade maior que a ainda disponível, é bloqueado (na leitura ou ao gravar) com uma mensagem explicando o motivo.',
      'O vale é sempre consumido por inteiro — se a compra futura for menor que o valor do vale, o sistema bloqueia o uso (sem troco em vale).',
      'Um vale já usado não pode ser usado de novo — o PDV mostra o erro na hora de digitar o número.',
    ],
    urlVideo: null,
  },
  'pdv.tela': {
    titulo: 'PDV — Frente de Caixa',
    objetivo: 'Lançar os itens de uma venda por código de barras ou busca, escolher a forma de pagamento (inclusive dividindo entre várias formas) e efetivar a venda.',
    passos: [
      'Antes de vender, é preciso abrir o caixa do dia — se ainda não estiver aberto, um popup obrigatório pede o saldo inicial em Dinheiro.',
      'Leia o código de barras no campo "Código de Barras" (aceita SKU ou EAN) — o item entra direto na lista. Digite "5*código" para lançar 5 unidades de uma vez, sem precisar ler o código 5 vezes.',
      '"F2 Pesquisa Produto" busca por nome quando não tem o código em mãos — mostra o estoque por empresa.',
      '"F3 Altera Quantidade" corrige a quantidade do item selecionado na lista de produtos vendidos (navegue com ↑/↓).',
      '"F4 Limpa Tela" remove todos os itens lançados e recomeça a venda, com confirmação antes de apagar.',
      '"F5 Efetiva Venda" abre a Forma de Pagamento: escolha Cliente e Vendedor (os dois são obrigatórios), aplique um desconto (até o máximo definido em Parâmetros do Sistema) e lance uma ou mais formas de pagamento — dinheiro, cartão, crediário ou vale-mercadoria — até fechar o valor a pagar.',
      'Depois de confirmar, a Papeleta de Venda abre automaticamente, pronta para imprimir, salvar em PDF ou enviar por WhatsApp.',
    ],
    errosComuns: [
      'Sem caixa aberto no dia, nenhuma venda é efetivada — abra o caixa antes de lançar produtos.',
      '"Confirmar Venda" só libera quando as formas de pagamento lançadas fecham exatamente o valor a pagar, com Cliente e Vendedor selecionados.',
      'Vender mais do que tem em estoque é permitido de propósito — o saldo fica negativo, sem bloqueio.',
      'Crediário tem limite de crédito: se o cliente tiver um limite de crédito cadastrado (maior que zero), o valor em crediário desta venda somado às parcelas de crediário já em aberto não pode ultrapassar o limite.',
      'Um vale-mercadoria maior que o valor a pagar não pode ser usado sozinho — sobra de vale não gera troco.',
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
      '"Esta operação mexe no caixa nº X, que já está fechado": o recebimento entrou num caixa que já foi encerrado, e apagá-lo faria a conferência daquele fechamento deixar de bater. Peça ao administrador para reabrir aquele caixa em Frente de Loja › Caixa › Fechamento de Caixa, estorne, e feche o caixa de novo. Nada é desfeito pela metade enquanto isso — o recebimento continua exatamente como estava.',
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
      'Impressão saindo apagada ou com as letras "falhadas"? O sistema já imprime em negrito, no tamanho certo para a bobina de 80mm. Se ainda sair fraco, o ajuste é na impressora: aumente a densidade (Density/Darkness) e, no driver, coloque o meio-tom (Halftone) em modo texto ou nenhum — assim ela imprime preto sólido em vez de pontinhos alternados.',
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
      'Reserva e Liberação de Reserva ainda não têm tela própria que grave esses tipos (dependem da integração com marketplaces) — o filtro já lista os 9, mas esses dois só vão trazer linhas quando essa integração existir. Compra já traz: quem grava é a Entrada de Produtos por Compra.',
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
      'Esta tela só monta o LAYOUT — a impressão de verdade é feita em "Emissão de Etiqueta de Produtos", no grupo Relatórios.',
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
      '"Filtros de Produtos Comprados" (popup) — período da compra, categoria de produto, cor e tamanho. Quando qualquer um está preenchido, só entram clientes que compraram um produto batendo em TODOS eles ao mesmo tempo (mesmo item, não compras diferentes).',
      'Marque em "Dados do Cliente Para Geração" quais colunas quer ver: Nome, Data de Nascimento, Gênero, E-mail, Celular, Primeira Compra, Última Compra, Nº de Compras, Valor Total Comprado, Ticket Médio e Nº de Dias sem Comprar — também define as colunas da grid de resultado.',
      'Clique em "Localizar Clientes" — o popup fecha, aparece um indicador de progresso e depois a grid com o resultado. Clique num título de coluna pra ordenar (alterna crescente/decrescente); o total de clientes aparece ao lado do botão e no rodapé fixo da grid; só a área de dados rola, o cabeçalho fica fixo.',
      '"Alterar Filtros e Colunas" reabre o popup mantendo a seleção atual, pra ajustar e localizar de novo.',
      'Clique em "Gerar Planilha Excel" pra baixar um .xlsx com uma linha por cliente já localizado (não busca de novo); o toast de sucesso mostra quantos clientes entraram.',
    ],
    errosComuns: [
      'Nenhum cliente encontrado: confira se os filtros de "Produtos Comprados" não estão pedindo uma combinação (categoria + cor + tamanho) que nenhum produto realmente tem junto.',
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
      '"＋ Selecionar Produtos" abre um popup com 3 formas de escolher: Individual (busca 1 produto — com ou sem código de barras já cadastrado —, escolhe cor e tamanho quando o produto usa grade, digita a quantidade), Por Entradas (período/fornecedor/nota fiscal — a quantidade vem da entrada) e Por Estoques (empresa obrigatória + categoria opcional — a quantidade vem do saldo em estoque).',
      'No modo Individual, se o produto escolhido não tiver código de barras cadastrado ainda para aquela variação, o sistema gera um automaticamente ao adicionar à lista — não precisa cadastrar antes.',
      'Cada busca ADICIONA à lista da tela principal (o popup não fecha sozinho) — dá pra combinar as 3 formas antes de fechar.',
      'Na grade, ajuste a quantidade de qualquer item direto no campo, clique no ícone vermelho pra remover um item, ou em "Limpar Lista" pra esvaziar tudo de uma vez.',
      'Clique em "Emitir Etiquetas" — escolha o modelo (layout já criado em Configuração de Etiqueta) e clique em "Imprimir".',
      'Se você chegou aqui pelo botão "Emitir Etiquetas desta Nota", logo depois de gravar uma entrada, o popup já abre no modo Por Entradas com o fornecedor e a nota preenchidos: basta clicar em "Localizar" pra trazer os produtos daquela nota.',
    ],
    errosComuns: [
      'No modo Individual, se o produto usa grade (configurado no cadastro dele), os seletores de Cor e Tamanho aparecem como obrigatórios — não dá pra adicionar sem escolher.',
      '"Por Entradas" sem resultado: confira o fornecedor e o período. Só aparecem aqui as notas já lançadas em "Entrada de Produtos por Compra" (grupo Estoque).',
      '"Por Estoques" só traz produtos com saldo positivo (zerado ou negativo não aparece).',
      'Quantidade vinda de estoque/entrada fracionária (produto por peso/medida) é arredondada pro inteiro mais próximo — etiqueta não existe em fração.',
      '"Emitir Etiquetas" fica desabilitado até ter pelo menos 1 produto na lista; "Imprimir" só some quando um modelo é escolhido.',
    ],
    urlVideo: null,
  },
  'configuracao.importacao.clientes': {
    titulo: 'Importar Clientes',
    objetivo: 'Trazer o cadastro de clientes de um sistema anterior para o Niner, via planilha Excel (.xlsx ou .xls).',
    passos: [
      'Sem a planilha ainda? Baixe o modelo no topo da tela.',
      'Clique em "Escolher planilha" e selecione o arquivo .xlsx ou .xls — a planilha é conferida na hora, e se não for da tabela certa a tela avisa e não deixa continuar.',
      'Escolha a categoria (existente ou nova) aplicada a todos os clientes deste arquivo.',
      'Clique em "Validar" — mostra quantas linhas ficariam prontas e quais têm erro, sem gravar nada ainda. Durante a leitura e a validação, a tela mostra o registro atual e o total em tempo real (útil em arquivo grande).',
      'Sem erro real, o botão "Importar" libera — ele grava o arquivo de verdade.',
      'Depois de importar, "Nova importação" limpa a tela para trazer outro arquivo de clientes.',
    ],
    errosComuns: [
      'Linha com erro: veja o motivo na tabela de erros, corrija só aquela linha no arquivo original e clique em "Validar" de novo.',
      'Cliente "já existia": já havia um cadastro com o mesmo CPF/CNPJ — o sistema reaproveitou em vez de duplicar.',
      'Nada foi importado mesmo o relatório mostrando linhas prontas: a importação é tudo-ou-nada — se sobrar QUALQUER linha com erro real, o arquivo inteiro não grava nada.',
    ],
    urlVideo: null,
  },
  'configuracao.importacao.contasReceber': {
    titulo: 'Importar Contas a Receber',
    objetivo: 'Trazer o saldo devedor de crediário dos clientes, via planilha Excel (.xlsx ou .xls), para não perder cobrança em aberto na migração.',
    passos: [
      'Importe Clientes antes desta tela — a parcela é ligada ao cliente pelo CPF/CNPJ, que precisa já estar cadastrado.',
      'Sem a planilha ainda? Baixe o modelo no topo da tela.',
      'Clique em "Escolher planilha" e selecione o arquivo .xlsx ou .xls — a planilha é conferida na hora, e se não for da tabela certa a tela avisa e não deixa continuar.',
      'Escolha a carteira de crediário (existente ou nova) aplicada a todas as parcelas deste arquivo.',
      'Clique em "Validar" — mostra quantas linhas ficariam prontas e quais têm erro, sem gravar nada ainda. Durante a leitura e a validação, a tela mostra o registro atual e o total em tempo real (útil em arquivo grande, com centenas de milhares de parcelas).',
      'Sem erro real, o botão "Importar" libera — ele grava o arquivo de verdade.',
    ],
    errosComuns: [
      'Linha com erro: veja o motivo na tabela de erros, corrija só aquela linha no arquivo original e clique em "Validar" de novo.',
      'Cliente não encontrado: o CPF/CNPJ da linha não bate com nenhum cliente já cadastrado — importe Clientes primeiro, ou corrija o documento.',
      'Empresa não encontrada: a empresa é resolvida pelo código curto (1, 2...), não pelo nome.',
      'Nada foi importado mesmo o relatório mostrando linhas prontas: a importação é tudo-ou-nada — se sobrar QUALQUER linha com erro real, o arquivo inteiro não grava nada.',
    ],
    urlVideo: null,
  },
  'configuracao.importacao.fornecedores': {
    titulo: 'Importar Fornecedores',
    objetivo: 'Trazer o cadastro de fornecedores de um sistema anterior para o Niner, via planilha Excel (.xlsx ou .xls).',
    passos: [
      'Sem a planilha ainda? Baixe o modelo no topo da tela.',
      'Clique em "Escolher planilha" e selecione o arquivo .xlsx ou .xls — a planilha é conferida na hora, e se não for da tabela certa a tela avisa e não deixa continuar.',
      'Escolha o plano de contas (existente ou novo) aplicado a todos os fornecedores deste arquivo.',
      'Clique em "Validar" — mostra quantas linhas ficariam prontas e quais têm erro, sem gravar nada ainda. Durante a leitura e a validação, a tela mostra o registro atual e o total em tempo real (útil em arquivo grande).',
      'Sem erro real, o botão "Importar" libera — ele grava o arquivo de verdade.',
      'Depois de importar, "Nova importação" limpa a tela para trazer outro arquivo de fornecedores.',
    ],
    errosComuns: [
      'Linha com erro: veja o motivo na tabela de erros, corrija só aquela linha no arquivo original e clique em "Validar" de novo.',
      'Fornecedor "já existia": já havia um cadastro com o mesmo CNPJ — o sistema reaproveitou em vez de duplicar.',
      'CNPJ ou e-mail inválido não impede a importação: a linha entra com esse campo em branco (não rejeitada) — corrija depois no cadastro do fornecedor, se quiser preencher certo.',
      'Nada foi importado mesmo o relatório mostrando linhas prontas: a importação é tudo-ou-nada — se sobrar QUALQUER linha com erro real, o arquivo inteiro não grava nada.',
    ],
    urlVideo: null,
  },
  'configuracao.importacao.produtos': {
    titulo: 'Importar Produtos',
    objetivo: 'Trazer o catálogo de produtos de um sistema anterior para o Niner, com a grade de tamanhos de cada um, via planilha Excel (.xlsx ou .xls).',
    passos: [
      'Sem a planilha ainda? Baixe o modelo no topo da tela.',
      'Clique em "Escolher planilha" e selecione o arquivo .xlsx ou .xls — a planilha é conferida na hora, e se não for da tabela certa a tela avisa e não deixa continuar. Esta tabela não pede nenhuma escolha prévia.',
      'Clique em "Validar" — mostra quantas linhas ficariam prontas e quais têm erro, sem gravar nada ainda. Durante a leitura e a validação, a tela mostra o registro atual e o total em tempo real (útil em arquivo grande).',
      'Sem erro real, o botão "Importar" libera — ele grava o arquivo de verdade.',
      'Depois de importar, use a tela de Estoque Inicial para trazer o saldo de cada variação.',
    ],
    errosComuns: [
      'Linha com erro: veja o motivo na tabela de erros, corrija só aquela linha no arquivo original e clique em "Validar" de novo.',
      'Produto "já existia": já havia um cadastro com a mesma descrição+marca+referência E o mesmo CODIGO_PRODUTO — o sistema reaproveitou em vez de duplicar. Duas linhas com descrição igual mas CODIGO_PRODUTO diferente viram produtos separados (são produtos diferentes no sistema de origem, mesmo com texto parecido).',
      'Nada foi importado mesmo o relatório mostrando linhas prontas: a importação é tudo-ou-nada — se sobrar QUALQUER linha com erro real, o arquivo inteiro não grava nada.',
    ],
    urlVideo: null,
  },
  'configuracao.importacao.estoque': {
    titulo: 'Importar Estoque Inicial',
    objetivo: 'Trazer o saldo inicial de estoque de cada variação (cor/tamanho) de produtos já importados, por empresa, via planilha Excel (.xlsx ou .xls).',
    passos: [
      'Importe Produtos antes desta tela — o produto é achado pelo CODIGO_PRODUTO, que precisa já ter sido importado.',
      'NOME_TAMANHO fora da grade do produto não é erro: é aceito normalmente, mesmo que o produto tenha uma grade de tamanhos diferente cadastrada.',
      'Sem a planilha ainda? Baixe o modelo no topo da tela.',
      'Clique em "Escolher planilha" e selecione o arquivo .xlsx ou .xls — a planilha é conferida na hora, e se não for da tabela certa a tela avisa e não deixa continuar.',
      'Diga a qual empresa corresponde cada uma das 5 colunas de quantidade do arquivo — não precisa preencher as 5 (só as que o arquivo realmente usa, no mínimo 1), e uma empresa já escolhida numa coluna some das opções das outras.',
      'Clique em "Validar" — mostra quantas linhas ficariam prontas e quais têm erro, sem gravar nada ainda. Durante a leitura e a validação, a tela mostra o registro atual e o total em tempo real (útil em arquivo grande).',
      'Sem erro real, o botão "Importar" libera — ele grava o arquivo de verdade.',
    ],
    errosComuns: [
      'Linha com erro: veja o motivo na tabela de erros, corrija só aquela linha no arquivo original e clique em "Validar" de novo.',
      '"Nenhum produto importado com CODIGO_PRODUTO...": esse código não existe em nenhuma linha do arquivo de Produtos — importe Produtos primeiro, ou corrija o código.',
      'Nada foi importado mesmo o relatório mostrando linhas prontas: a importação é tudo-ou-nada — se sobrar QUALQUER linha com erro real, o arquivo inteiro não grava nada.',
    ],
    urlVideo: null,
  },
  'configuracao.exportacao': {
    titulo: 'Exportação de Dados',
    objetivo: 'Baixar em planilha Excel (.xlsx) tudo que já está cadastrado no Niner — clientes, fornecedores, produtos, estoque, financeiro etc.',
    passos: [
      'Escolha a tabela desejada.',
      'Enquanto os dados são buscados e a planilha é montada, a tela mostra um indicador de progresso.',
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
