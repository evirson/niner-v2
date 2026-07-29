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
