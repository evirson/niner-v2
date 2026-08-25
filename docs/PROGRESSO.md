# Progresso do Projeto — niner-v2

Registro cronológico das decisões e entregas. Atualizar a cada marco relevante.
**Última atualização:** 2026-08-25

---

## Estado atual

> **MÓDULO FISCAL — B0 a B8 fechados no mesmo dia (2026-08-17), mais 3 melhorias pós-B8.**
> Estudo fechado (`docs/MODULOFISCAL.md` v2.1), schema no banco (V034/V035), tabelas nacionais de
> IBS/CBS carregadas e **243 XSD oficiais versionados**. Escopo do v1: **NFC-e ao consumidor final
> + NF-e de devolução de venda**, com cancelamento, inutilização, contingência, arquivamento e
> download. Roteiro em blocos B0–B9: **§17.1 do estudo**.
>
> **O que está pronto e testado (775/775 backend verdes):** B0 (PoC real contra a SEFAZ-PR, `cStat
> 100`, JDK puro sem lib de NF-e — DF7 fechada no plano B) · B1 (specs) · B2 (`fiscal.configuracao`
> + `fiscal.perfil` + certificado A1, **cifrado no banco**, não em bucket — DF21 revisada) · B3
> (Conformidade Fiscal, bloqueio preventivo F11) · B4 (motor tributário puro — ICMS/PIS/COFINS/IPI/
> IBS/CBS, DF37: só MEI e Simples Nacional) · B5 (montagem do XML validada contra o XSD oficial) ·
> B6 (assinatura XMLDSig + transporte mTLS) · B7 (numeração sem buraco F4, emissão síncrona no PDV,
> contingência offline com drenagem automática, DANFCE na papeleta) · B8 (cancelamento 110111,
> tela de Documentos Fiscais, inutilização de numeração) · **pós-B8**: reprocessar documento preso,
> link de consulta pública e "Ver DANFCE" na lista de Documentos Fiscais · **Arquivamento**
> (2026-08-18, MinIO/ADR-014 + `ArquivamentoXmlService`/`Job`: nota/evento/inutilização autorizados
> arquivam sozinhos, `nfeProc` validado contra XSD, `GET .../xml` serve do bucket).
>
> ✅ **B9 fechado em 2026-08-19** — a **DF20** foi decidida com o contador (devolução sem consumidor
> identificado sai contra o CNPJ da própria loja) e a NF-e modelo 55 de devolução emite ponta a
> ponta, com **DANFE em A4**. Blocos B0–B9 completos.
>
> ⏭️ **O que falta não é código: o CSRT.** A NF-e modelo 55 exige `idCSRT`/`hashCSRT` no
> `infRespTec` (NT 2018.005) — a **MITRYUSCASH precisa se cadastrar como responsável técnico no portal
> da SEFAZ de cada UF** e obter o código. Sem ele a SEFAZ responde `cStat 974` ("CNPJ do
> responsavel tecnico diverge do cadastrado") e nenhuma nota de devolução autoriza. **A NFC-e, que
> é a operação do dia a dia da loja, não é afetada** — o PR não cobra CSRT no modelo 65. Ver
> `docs/MODULOFISCAL.md` §9.9.
>
> 🌎 **2026-08-20 — o fiscal saiu do "produto do Paraná".** O CSRT deixou de ser uma
> variável de ambiente única e virou **cadastro por UF × ambiente** (`cfg_csrt_resptec`, V046 +
> tela no backoffice), porque ele é emitido pela SEFAZ de **cada** estado: com o desenho anterior,
> a primeira nota de um lojista de fora do PR sairia carimbada com o código do Paraná e voltaria
> `cStat 974`, com o diagnóstico apontando para o CNPJ em vez da UF. A exigência do par também
> virou dado (`cfg_uf_autorizador.exige_csrt`, por modelo). ✅ **E a outra metade fechou no mesmo
> dia:** a **V047** carregou as **27 unidades da federação** em `cfg_uf_autorizador` — 108 linhas
> (27 × 2 modelos × 2 ambientes), geradas por script a partir de fonte machine-readable, não
> digitadas. A validação: as linhas do PR que a fonte gerou saíram **idênticas** às que já
> autorizam de verdade. ⚠️ O autorizador **muda com o modelo** (BA e PE: próprio no 55, SVRS no
> 65; MA: SVAN no 55, SVRS no 65). Antes da primeira emissão de uma UF nova: bater o
> `url_status_servico` dela e obter o **CSRT** daquele estado.
>
> 🔴✅ **Primeira emissão síncrona real contra a SEFAZ-PR fora de um script de PoC (2026-08-18)
> achou e corrigiu um bug crítico:** a resposta síncrona real tem dois `cStat` (lote e nota) e
> `SefazTransporte` lia o do lote — nota autorizada saía reportada como REJEITADA. Nenhum teste
> pegava porque todo fixture mockado só tinha um `cStat`. Corrigido e comprovado ao vivo (nova
> emissão saiu `AUTORIZADO` direto). Ver a entrada de hoje na linha do tempo.
>
> 🔴✅ **O MESMO bug em outro endpoint, achado cancelando uma venda real (2026-08-19):**
> `RecepcaoEvento4` (cancelamento 110111) também devolve dois `cStat` — um do **lote de evento**
> (128 "Lote de Evento Processado", `retEnvEvento`) e outro do **evento em si** (135 "Evento
> registrado e vinculado à NF-e", dentro de `retEvento/infEvento`). Sem escopo pra `infEvento`,
> o cancelamento saía **sempre "recusado pela SEFAZ"** mesmo quando ela autorizava de verdade — a
> venda nunca revertia. Corrigido em `SefazTransporte.enviar()` (mesma técnica de escopo por
> bloco já usada pro par `infProt`), com teste de regressão contra um XML real de dois `cStat`
> (`SefazTransporteTest`). Efeito colateral descoberto ao vivo: a 1ª tentativa (com o bug) já
> tinha sido silenciosamente aceita pela SEFAZ, e a 2ª tentativa (já corrigida) colidia com a
> UNIQUE de `documento_fiscal_evento` — ver a entrada de hoje na linha do tempo pro fix completo
> (handler de exceção, coluna `tentativa`, e o prazo de cancelamento da NFC-e exposto na tela
> ANTES do usuário tentar).
>
> 🚚 **2026-08-20 — Devolução de Produtos Comprados (devolução ao fornecedor) entrou inteira.**
> Baixa de estoque + **NF-e 55 de saída**, espelhando os impostos da nota de entrada. Exigiu que
> o **XML da entrada** passasse a ser arquivado no MinIO e que a **tributação por item** da nota
> do fornecedor fosse gravada (`entrada_nfe_item`, V051) — consequência que a tela diz por
> extenso: **entrada anterior a 20/08/2026, manual ou por planilha, não é devolvível.** Fecha a
> DF14 e tira a operação da lista de "futuras" do §4.2 do estudo fiscal.
> ⚠️ **Nunca foi transmitida à SEFAZ** — reusa o montador homologado do B9, mas depende do CSRT
> acima e de homologação real. Ver `docs/telas/devolucao-compra.md`.
>
> 📦 **2026-08-20 — controle de estoque virou parâmetro, e a regra mora na trigger.**
> `cfg_permite_estoque_negativo` (Parâmetros do Sistema → Estoque) **nasce ligado**: a loja típica
> não faz gestão de estoque e o caixa não deve travar por um número que ninguém alimenta. Quem
> **desliga** ganha o controle inteiro: nenhuma rotina tira mais do que existe, contado **por
> empresa**. A checagem ficou em `fn_atualiza_estoque_movimento` (V054), não nos serviços, porque
> o pedido dizia "vendas, transferências, devolução ao fornecedor, **e etc**" — e o "e etc" inclui
> o **cancelamento de entrada**, que debita estoque e que ninguém lembra. Também neste dia: **venda
> cancelada perdeu a reimpressão de papeleta** (409 no comprovante), porque papel impresso circula.
>
> ⚠️ **A MITRYUSCASH é a desenvolvedora, não uma cliente** — o certificado é da casa de software e
> ela nunca emitirá em produção. Cada comprador do ERP terá um regime próprio (Simples, Presumido,
> Real ou MEI), então o teste de tabela do motor (§16.1) é a **superfície principal do produto**, não
> cobertura de borda.
>
> **Resumo em uma linha (2026-08-20):** ERP com **~44 telas** ponta a ponta cobrindo cadastros,
> catálogo, estoque (incl. entrada por XML de NF-e), PDV, **orçamento de venda** e vendas, financeiro
> completo (caixa, crediário, contas a pagar/receber, conta corrente, **DRE e Fluxo de Caixa**),
> relatórios, etiquetas e importação/exportação. **Falta o coração da visão original**: integração com
> marketplaces (`canais`/`pedidos`/`precos`/`integracao` seguem sem implementação de domínio) e o
> app `admin/` (backoffice da plataforma). **909/909 testes de backend verdes, `tsc -b` limpo.**
>
> **Pendências adiadas pelo dono do produto (não são esquecimento):** calibragem de impressão
> térmica da **Guia de Transferência** (ainda em folha A4) — papeleta de venda, comprovante de
> crediário, vale-mercadoria e, desde 2026-08-19, **Fechamento de Caixa** já estão calibrados (42
> colunas / 75mm / Consolas em negrito, `docs/telas/papeleta-venda.md`). O **vídeo de treinamento
> de Produtos** também segue pausado.
>
> **2026-08-19:** Fechamento de Caixa redesenhado por completo (fim da contagem "às cegas", grade
> "Caixas Abertos", "Fechar Mesmo Assim", impressão térmica) + Abertura de Caixa virou só popup +
> bug real de "duplicate key" na Importação de Estoque corrigido + tela nova **Dados da Empresa**
> (`/empresas`, primeiro CRUD do projeto sem criar/excluir) fecha a categoria "Empresa" da
> Conformidade Fiscal (CNPJ/Inscrição Estadual/Inscrição Municipal/código de município IBGE/CNAE
> nunca tinham tela pra editar) + **Perfis Fiscais** ganharam os 2 perfis padrão semeados no
> signup (fecha a última pendência aberta da spec), coluna "Regime" (Simples/MEI, derivada) na
> grade, e o motor passou a recusar CST de ICMS tributado sem alíquota informada (achado real:
> nota saía autorizada com ICMS R$ 0,00 em silêncio). Mais tarde no mesmo dia: os jobs
> `@Scheduled` de fiscal (drenagem de contingência, arquivamento) nunca encontravam nenhum tenant
> — RLS sem `TenantContext` devolve vazio em silêncio, não um erro (corrigido: loop por tenant via
> `plataforma.tenant`, global) — e `cfg_produto_ncm` ganhou alíquota IBPT real
> (`alq_federal_nacional`/`alq_federal_importado`/`alq_estadual`/`alq_municipal`, populada via
> planilha, 10.515/10.515 NCMs). Fechando o dia: **`vTotTrib` (Lei 12.741) deixou de ser
> hardcoded em zero** — calcula de verdade a partir do NCM/origem do produto e passa a ser
> **emitido no XML** (antes ficava sempre omitido); a papeleta ganhou número/série oficial da
> NFC-e e a linha "CONSUMIDOR - CPF/CNPJ" (ou "NÃO IDENTIFICADO"), fechando a especificação de
> DANFE recebida do dono do produto. Ainda no mesmo dia: DANFCE reconstruído como tabela HTML de
> verdade (era texto monoespaçado), QR Code corrigido (`cStat 464` real — formato/hash errados,
> CSC passou a ser cifrado de fato), e um bug real de rejeição SEFAZ por `cnpj_credenciadora`
> ausente em carteira de cartão (`cStat 391`, venda 586) corrigido — a Conformidade Fiscal agora
> flags essa pendência. Calibragem de impressão do DANFE fechada em **72mm de área útil, `left: 0`**
> (medida real por régua, depois de 4 rodadas por estimativa), quantidade do item passou a respeitar
> `cfg_permite_qtd_decimal` (antes saía sempre "1,000UN"), e espaçamento vertical reduzido em 2
> níveis (Leve + Moderado) a pedido do dono do produto, pra gastar menos papel. Depois disso:
> Pesquisa de Vendas ganhou mais respiro visual (modal alargado, linhas espaçadas) e o DANFCE
> passou a imprimir o número da venda em "Informações adicionais de interesse do contribuinte".
> **Maior achado do dia**, testando o Cancelamento de Venda ao vivo (venda 590): o mesmo bug de
> dois `cStat` da emissão (2026-08-18) existia também no cancelamento — corrigido, junto com a
> exceção de validação fiscal que caía num 500 genérico, a justificativa sem checar 15 caracteres,
> a UNIQUE de `documento_fiscal_evento` que travava qualquer retentativa, e a tela ganhou o prazo
> de cancelamento da NFC-e (30 min, por UF) exposto ANTES do usuário tentar, bloqueando de vez
> quando já passou. Fechando: a reimpressão de papeleta de venda cancelada avisa **"VENDA
> CANCELADA"** já na primeira linha do cupom. **775/775 testes de backend verdes.** Ver linha do
> tempo de hoje.
>
> Os parágrafos abaixo são a **narrativa acumulada** desde o começo — leia o resumo acima para o
> estado, e a linha do tempo (do mais novo para o mais antigo) para o detalhe de cada entrega.

Projeto **spec-driven** que começou pela fundação, com **seis telas de cadastro completas e
ponta a ponta**: Clientes (`cadastros.cliente`), Funcionários (`cadastros.funcionario`), Plano
de Contas (`cadastros.planocontas`), Fornecedores (`cadastros.fornecedor`), **Produtos**
(`catalogo.produto`, 2026-07-22 — primeiro corte vertical do núcleo do catálogo, com categoria
N:N ordenada, NCM como referência global e regras de precificação/oferta) e, do módulo
**`financeiro`**, **Tipo de Carteira** (`financeiro.tipocarteira` — crediário/cartão
antecipados da Fase 2, Q5/ADR-010/ADR-012; **absorveu o cadastro de Moeda em 2026-07-28**, ver
linha do tempo — `moeda`/`moeda_detalhe` não existem mais, cada bandeira agora é uma linha de
`tipo_carteira` por categoria, com `percDesconto`/`percAcrescimo` próprios), mais a primeira
tela de **configuração de sistema** — Parâmetros do Sistema (`configuracao.geral`, 2026-07-21,
singleton por tenant, ADMIN-only, fora do padrão de cadastro) e a nova tela auxiliar
**Histórico do Cliente** (`cadastros.cliente`, 2026-07-23 — compras, parcelas e resumo de
crediário; só leitura, ainda sem fluxo de venda/baixa de parcela pra alimentar dados reais;
layout em duas colunas com painel de **Produtos da Compra** desde 2026-07-27) e, novo em
2026-07-28, o primeiro corte vertical do módulo **`vendas`** — **PDV (Frente de Caixa)**,
`docs/telas/pdv.md`: busca de produto e leitura por código de barras real (com estoque por
empresa), efetivação de venda de verdade com **split-tender** (múltiplas formas de pagamento
na mesma venda, cada uma cobrindo um pedaço do saldo) e **desconto promocional**
(`cfg_geral.percentual_desconto_venda`) rateado junto com o desconto/acréscimo de cada forma de
pagamento em `produto_movimento_detalhe`, menu lateral retrátil. Ainda em 2026-07-28, na
sequência: a sétima tela de domínio, **Usuários** (`identidade.usuario`,
docs/telas/usuario.md — ADMIN-only, seleciona empresas com acesso via N:N `usuario_empresa`;
**só existe um ADMIN por tenant, para sempre**, criado no signup e imutável — o campo nem
aparece no formulário desta tela); **login com escolha de empresa**
(docs/telas/login-empresa.md — JWT ganhou o claim `eid`, empresa ativa da sessão, escolhida no
login quando o usuário acessa mais de uma; todo INSERT que grava `id_empresa` usa esse claim,
não mais "a primeira empresa do tenant"); e a primeira feature real do menu **Estoque**,
**Transferência de Produtos Entre Empresas** (`estoque.transferencia`,
docs/telas/transferencia-estoque.md — a empresa de origem é sempre a empresa ativa da sessão,
só o destino é escolhido; reaproveita o ledger `produto_movimento_mestre/detalhe` já existente,
sem lógica de saldo nova). A **API (Spring Boot 4 / Java 25)** sobe com 3 superfícies + infra
de contexto de tenant; o schema completo (control-plane + domínio do lojista, V001–V027, mais
`usuario_empresa`/`usuario_um_admin_uk`/`produto_transferencia` adicionadas dentro das
migrations existentes em 2026-07-28) está criado, revisado e com RLS validado. Falta a camada
de domínio dos demais módulos (pedido/canais) e o app `admin/`. Em 2026-07-29, a tela de
**Transferência de Produtos** ganhou filtros (Data Inicial/Final, Nº da Transferência, Empresa
de Saída/Entrada), ordenação por coluna, exclusão (reverte o estoque, mesmo sem saldo
suficiente no destino) e um popup pedindo a empresa de destino antes de abrir a tela de
produtos; e nasceu a **oitava tela de domínio**, do módulo `financeiro` — **Recebimento de
Crediário** (`financeiro.recebimentocrediario`, docs/telas/recebimento-crediario.md): busca de
cliente, seleção múltipla de parcelas de crediário em aberto com multa/juros calculados pela
carência configurada em Parâmetros do Sistema, e quitação com uma ou mais formas de pagamento
(sem desconto/acréscimo da carteira, diferente do PDV), gerando lançamento de caixa (abrindo o
caixa do usuário automaticamente se preciso) — primeiro uso real de `caixa_mestre`/
`caixa_detalhe`, dormentes desde 2026-07-16 — que ganhou, no mesmo dia, uma tela irmã de
**Estorno** (reverte um lote de recebimento inteiro, reabrindo as parcelas e apagando os
lançamentos de caixa). Mesmo dia: **qualquer movimentação de estoque
(PDV e Transferência) deixou de bloquear por saldo insuficiente** — pedido explícito do dono do
produto, saldo negativo é permitido de propósito em todo o sistema.
**Convenções de UI novas (2026-07-22), valem para todo campo do sistema daqui pra frente:**
campo decimal (moeda/percentual/peso) com digitação natural (inteiro primeiro, vírgula abre
decimais, completa só no `onBlur`) e campo de data como texto mascarado `dd/mm/aaaa` (não
`<input type="date">` — ver §3.7 da spec). **Convenção de pop-up nova (2026-07-24), vale para
toda mensagem de erro/alerta/sucesso do sistema daqui pra frente:** nunca um banner inline na
página — sempre `Toast.tsx` (canto superior direito, letra branca), vermelho
(`tipo="erro"`, padrão) para erro/alerta, verde (`tipo="sucesso"`) para confirmação de
sucesso (inclusive ao clicar Salvar num cadastro, que antes só navegava de volta pra lista
sem avisar nada). Ver linha do tempo de 2026-07-24 pros detalhes e o que motivou (um bug de
exclusão que violava FK sem avisar nada ao usuário). Em 2026-07-30, **Abertura de Caixa**
(`financeiro.caixa`, docs/telas/abertura-caixa.md) ganhou tela própria — `caixa_mestre` volta a
ter `id_carteira`/`saldo_inicial`, e PDV/Recebimento de Crediário passam a **exigir** caixa
aberto (popup obrigatório ao entrar na tela) em vez de operar sem checar ou abrir sozinho em
silêncio como antes. No mesmo dia, o Recebimento de Crediário ganhou **Comprovante de Pagamento**
(docs/telas/comprovante-recebimento-crediario.md) — popup automático após efetivar, pronto pra
impressão térmica 80mm ou PDF (`jsPDF`, dependência nova do `web/`). Ainda em 2026-07-30, o
módulo **`financeiro` fechou por completo** com **Conta Corrente** e **Movimentação de Conta
Corrente** (docs/telas/conta-corrente.md, docs/telas/conta-corrente-movimento.md — última tabela
do legado a entrar no v1, §3.3.7), incluindo autopreenchimento do nome do banco (`cfg_banco`,
mesmo padrão do NCM) e os 3 últimos dumps Firebird removidos do `db/`. Ainda em 2026-07-30, a
Abertura de Caixa ganhou tela irmã, **Fechamento de Caixa** (docs/telas/fechamento-caixa.md):
ADMIN fecha o caixa de qualquer usuário, OPERADOR só o próprio, totais recalculados de
`caixa_detalhe` por tipo de carteira e conferência de dinheiro contado, impressão A4. Verificação
manual dessa tela expôs que o **PDV nunca lançava em `caixa_detalhe`** (decisão antiga, "fora de
escopo") — corrigido: toda venda à vista (dinheiro/Pix/débito/crédito) agora lança crédito no
caixa na hora (crediário fica de fora, só conta quando a parcela for recebida depois); na mesma
correção, **débito passou a nascer em aberto em `contas_receber`** (antes nascia quitado junto
com dinheiro) — entra no caixa pro fechamento, mas fica pendente até uma futura conciliação de
cartões baixar a parcela (ver `docs/telas/pdv.md`). `cfg_banco` ganhou o banco 999 - CAIXA
CENTRAL. A grade de Tipo de Carteira também passou a manter busca/página/ordenação ao
visualizar/editar um registro e voltar, em vez de resetar. Ainda em 2026-07-30, nasceram a
**nona e décima telas de domínio**, ambas do módulo `vendas`: **Cancelamento de Venda**
(`vendas.cancelamento`, docs/telas/cancelamento-venda.md, ADMIN-only — reverte estoque/caixa/
contas a receber de uma venda numa única transação, bloqueada em definitivo se houver parcela
de crediário já recebida) e **Pesquisa de Vendas** (`vendas.pesquisa`, docs/telas/
pesquisa-vendas.md, qualquer papel, adaptada de uma especificação externa pro schema real do
Nainer — busca por venda com detalhamento completo em quatro grids empilhadas: dados, produtos,
caixa e parcelas de crediário, só leitura; **desde 2026-07-31, o detalhamento abre em popup**
— `DetalheVendaModal.tsx` — em vez de empilhar abaixo da grid, pra não exigir scroll da página).
No mesmo dia, o **Fechamento de Caixa foi revisado**
pra uma contagem **"às cegas"** por tipo de carteira (não só dinheiro) — só fecha se todas
baterem, com tela de divergência e drill-down analítico quando não bate (ver linha do tempo).
E o **menu lateral** ganhou expandir ao passar o mouse/focar quando está recolhido, recolhendo
sozinho ao sair. Ainda em 2026-07-31, o grupo de menu **Relatórios** (placeholder vazio desde a
reorganização do menu) ganhou sua **primeira tela**, **Relatório de Vendas**
(`relatorios.vendas`, docs/telas/relatorio-vendas.md) — define o padrão de tela de relatório do
projeto (filtros → KPIs → composição do faturamento → 7 gráficos Recharts, nova dependência do
`web/` → grid totalizável com drill-down em popup); qualquer papel, empresa em multi-select para
ADMIN (`EmpresaMultiSelect.tsx`, 1º componente do tipo no projeto). Ganhou também um botão
**"Gerar PDF"** que captura a própria tela como imagem (`html2canvas`, mais uma dependência nova)
em vez de reconstruir os dados como texto — por causa disso, `color-mix()` foi **eliminado do
`web/src/styles.css` inteiro** (trocado por `rgba()` com trincas RGB novas), incompatível com o
parser de CSS do `html2canvas`. O grupo **Relatórios** ganhou mais 3 telas em seguida: **Relatório
de Comissões** (`relatorios.comissoes`, 2026-08-03), **Contas a Receber / Recebidas**
(`relatorios.contasreceber`, 2026-08-03) e **Relatório de Estoque** (`relatorios.estoque`,
2026-08-04, com seletor de Modelo Inventário/Sintético/Analítico e colunas dinâmicas por empresa).
O módulo `vendas` ganhou **Devolução de Produtos + Vale-Mercadoria** (2026-08-03, resgatável no
PDV) e o módulo `estoque` ganhou a **Rotina de Contagem de Estoque** (2026-08-04, 4 telas:
Contagem/Diferenças/Efetivar Balanço/Zerar Contagem) — ver linha do tempo pros detalhes de cada
uma. Ver também `docs/telas/relatorio-comissoes.md`, `docs/telas/relatorio-contas-receber.md`,
`docs/telas/contagem-estoque.md`, `docs/telas/relatorio-estoque.md` e `docs/telas/
devolucao-produtos.md`. Em 2026-08-06, o grupo **Configurações** ganhou duas rotinas irmãs de
migração de dados: **Importação de Dados** (`docs/telas/importacao-dados.md` — CSV, 4 tabelas:
cliente/fornecedor/produto/contas_receber-crediário, dry-run via rollback de transação, venda
sintética pra crediário importado sem mexer em schema) e **Exportação de Dados**
(`docs/telas/exportacao-dados.md` — Excel gerado no navegador, 9 tabelas, só leitura). Em
2026-08-07, o Comprovante de Pagamento de Crediário e a Papeleta de Venda (com as respectivas
telas de Reimpressão) ganharam a opção **"Enviar por WhatsApp"** — popup de confirmação do celular
do cliente, PDF gerado no navegador e compartilhado por um link temporário que vive só em memória
na API (24h, sem custo de object storage, limite de 20 arquivos por tenant somando os 4 fluxos —
ver `docs/infra/compartilhamento-arquivo-temporario.md`) — e os quatro popups de comprovante
passaram a ter cabeçalho/rodapé fixos, com scroll só na pré-visualização.

**De 2026-08-08 a 2026-08-14** (resumo; detalhe na linha do tempo): o módulo `estoque` ganhou
**Entrada de Produtos por Compra** nos três fluxos — manual, planilha e **XML de NF-e** — com
cancelamento, filtros e consistência configurável entre duplicatas e total; `catalogo` trocou as
variantes linha/coluna por **cor + grade de tamanho** e recebeu os ~10.515 NCMs reais da Receita;
`financeiro` ganhou **Contas a Pagar/Pagas**; `identidade` ganhou **horário de acesso por dia da
semana**; o **plano de contas** encolheu para a máscara `9.99.999` (3 níveis, 76 contas padrão) e
ganhou o `SeletorPlanoContas`. Em **2026-08-14** saíram as duas telas que fecham o módulo de
resultado — **DRE em dois regimes** (competência e caixa) e **Fluxo de Caixa** (realizado +
projeção) —, o que exigiu que a **baixa de conta a pagar passasse a gerar movimento de dinheiro**
de verdade; no mesmo dia o ERP ganhou **seletor de tema Claro/Escuro/Automático**, o signup passou
a aplicar o **plano de contas padrão completo**, e foi descoberto que o `tsc --noEmit` **não
checava nada** (19 erros reais acumulados, todos corrigidos — usar `tsc -b`). Em **2026-08-14**, a
primeira impressão em bobina térmica real mostrou que a **papeleta de venda saía ilegível**: virou
42 colunas com item em 2 linhas, e a calibragem de impressão (largura imprimível, negrito,
Consolas) foi aplicada também ao comprovante de crediário — o vale-mercadoria herdou sozinho.
**500/500 testes de backend verdes (2026-08-14); `tsc -b` limpo.**

**Sessão de 2026-08-15 — leitura integral da documentação, e o rescaldo dela.** Dia sem feature
nova: o trabalho saiu todo de ler a documentação inteira (spec, plano de negócio, `docs/infra/`,
as **40** specs de `docs/telas/` e as 110 memórias) e corrigir o que não batia com o código.
Removido o `docs/TREINAMENTO_IA_ENTRADA.md` (2.318 linhas catalogando ~30 padrões de extração de
cor/tamanho de NF-e por fornecedor — **nunca implementado**: o código usa duas heurísticas
genéricas; preservado em `c2a0c80`). Nasceu o **`PrivilegiosNinerAppTest`**, que fecha o ponto
cego de `GRANT`/`REVOKE`/RLS registrado em 08-12 (a suíte roda como superusuário do container, não
como `niner_app`). A seção "Próximos passos" do próprio `PROGRESSO.md` — que a auditoria de 08-14
não cobriu — foi alinhada ao código e passou a ter **marketplaces como item 1**. E as **três
pendências que a baixa de conta a pagar (08-14) tinha aberto nas telas que leem dinheiro** foram
fechadas: Contas a Pagar passou a oferecer a abertura de caixa, o drill-down do Fechamento de
Caixa passou a nomear a saída ("Conta a pagar nº N") e o extrato manual de conta corrente passou a
**recusar** edição/exclusão do movimento gerado pela baixa. **509/509 testes verdes.**

| Artefato | Situação |
|---|---|
| `spec-driven-erp-varejo.md` | **v2.0 — pivô SaaS multi-tenant** (Constituição P1–P9 + PRD R1–R21 + plano técnico + control-plane + migrations) |
| `docs/PLANO-DE-NEGOCIO.md` | **Novo** — plano de negócio (planos/preços, trial, funil, métricas SaaS, roadmap, decisões D1–D10) |
| `docs/padroes/` | Mockup de referência de UI (golden file, §3.7) — `TELA.rar` descompactado e removido |
| `docs/infra/armazenamento-imagens.md` | Object storage das fotos de produto (ADR-013). Infra no GCP **provisionada e testada**; **código Java implementado em 2026-07-23** (`comum.armazenamento` + `catalogo.ProdutoImagemService`, ver linha do tempo) — só falta credencial real (Opção A, ADC pessoal do Claudio) pra upload funcionar de ponta a ponta fora dos testes |
| `docs/infra/compartilhamento-arquivo-temporario.md` | **Novo (2026-08-07)** — `comum.arquivocompartilhado`: cache de PDF em memória (não é object storage), token aleatório, expiração 24h, limite de 20 arquivos/tenant. Usado hoje pelo "Enviar por WhatsApp" dos comprovantes de crediário e venda |
| `db/*.txt` | Schema **legado (Firebird)** versionado como referência (31 tabelas + generators, procedures, triggers) |
| `CLAUDE.md` | Guia do repositório — atualizado para o SaaS multi-tenant (P8/P9, plataforma, `id_tenant`+RLS) |
| `docker-compose.yml` | Infra local de dev: `db` (postgres:18, `niner_db`) + `flyway` (profile `migrate`) + **`api`** (Spring Boot, porta 8080, conecta como `niner_app`); **V001–V026 aplicadas e validadas em banco real** (control-plane + domínio do lojista + financeiro parcial + RLS) — banco **recriado do zero em 2026-07-16** (volume `niner_pgdata` apagado e refeito) |
| `db/migration/V013–V024` | Domínio do lojista (identidade, cadastros, catálogo com `sku`+`ean`, estoque com `reservado`/`disponivel`, vendas, canais, pedidos, integração/outbox, cfg_geral) + **RLS de domínio** (`FORCE` + política por `id_tenant`). **Gate P8 verde** (teste de isolamento cross-tenant automatizado). **Revisado em 2026-07-16** (ver linha do tempo): tipos padronizados (`id_tenant SMALLINT`, demais PKs `INTEGER`), sem `ON DELETE CASCADE`, ledger de estoque imutável via `REVOKE`, e-mail case-insensitive, fix de bootstrap (`GRANT CREATE ON SCHEMA public`) |
| `db/migration/V025` | **`financeiro` parcial (revisão de Q5/ADR-010, ADR-012):** crediário (`tipo_carteira`, `contas_receber`/`contas_receber_detalhe`) + caixa (`caixa_mestre`/`caixa_detalhe`). RLS próprio no arquivo (V024 já tinha rodado). **Editada em 2026-07-23** (histórico do cliente): `categoria_carteira` (ENUM + coluna obrigatória em `tipo_carteira`) e `id_empresa_pagamento` (coluna nullable + FK em `contas_receber`) — aplicadas no banco de dev já rodando via `ALTER` manual + `flyway repair` (sem recriar o banco). **Editada de novo em 2026-07-28 (fusão `tipo_carteira`+`moeda`):** `moeda`/`moeda_detalhe` **removidas** do arquivo; `tipo_carteira` ganhou `perc_desconto`/`perc_acrescimo` (vieram de `moeda`) e a chave única virou `(id_tenant, nome_carteira, categoria_carteira)` — a mesma bandeira pode existir uma vez por categoria (ex.: "HIPER" em débito e crédito, prazo/taxa próprios de cada um); `caixa_detalhe.id_moeda` renomeado pra `id_carteira`, FK retargetada pra `tipo_carteira`. Aplicada no banco de dev via `ALTER`/`DROP TABLE` manual + `flyway repair` (sem recriar o banco, mesma convenção de sempre "banco ainda em construção"). **Editada de novo em 2026-07-29 (Recebimento de Crediário):** `tipo_carteira` ganhou `permite_receber_crediario` (boolean, RN007) e nasceu a tabela `contas_receber_lote` (cabeçalho/gerador real de `id_lote_recebimento`, mesmo padrão de `produto_transferencia`/`id_transferencia`, sem FK de `contas_receber`/`caixa_detalhe` pra ela, proposital) — RLS próprio no mesmo arquivo. Aplicada no banco de dev via `ALTER`/`CREATE TABLE` manual + `flyway repair` (mesma convenção). |
| `db/migration/V026` | **`contas_pagar`** (mais uma revisão de Q5/ADR-010/ADR-012): PK `id_conta_pagar` (renomeada de `localizador`), `nota_fiscal integer` nullable sem `DEFAULT 0` (padronização que também corrigiu `produto_movimento_mestre.nota_fiscal`, V019, de `text` para `integer`). RLS próprio no arquivo. Só `conta_corrente*` segue fora do v1. **Aplicada e validada em banco real em 2026-07-16** (schema/FKs/RLS conferidos via `psql`) |
| `db/migration/V027` | **`cfg_tela_campo`** (novo, 2026-07-21) — configuração por tenant de campos visíveis/obrigatórios por tela (`chave_tela`), reutilizável para qualquer tela futura. RLS próprio no arquivo. **Migration aditiva** — aplicada sem recriar o banco (`docker compose run --rm flyway`, só essa migration rodou). |
| `api/` | Spring Boot 4.0.7 / Java 25 (Maven). 3 superfícies com `SecurityFilterChain` separados; `TenantContext` (`ScopedValue`) + `TenantAwareTransactionManager`; **auth JWT HS256** (login/signup emitem, `/api/v1` valida `aud=tenant`); **trial self-service** (`POST /api/publico/assinar` → tenant+configs+moedas+ADMIN+assinatura TRIAL + token), `POST /api/publico/login`, `GET /api/v1/eu`. **Módulo `cadastros.cliente` (2026-07-20/21):** CRUD completo de `GET/POST/PUT/DELETE /api/v1/clientes` + `GET/POST/PUT /api/v1/categorias-cliente`, validação de CPF/CNPJ (dígito verificador + duplicidade — **CNPJ alfanumérico desde 2026-07-21**, ver linha do tempo), normalização de texto para maiúsculas, celular/WhatsApp (11 dígitos + 3º=9), nascimento opcional (só não pode ser futuro), exclusão com fallback para inativar quando há venda associada. **Listagem ordenada por `nome` (ou pela coluna pedida), paginação por número de página** (2026-07-21, era por `id_cliente`/cursor) — `GET /api/v1/clientes?pagina=&limite=&ordenarPor=&direcao=` com `LIMIT/OFFSET` + contagem total + `ORDER BY` dinâmico (allowlist de colunas). **Validação de servidor reforçada (2026-07-21):** além do dígito verificador de CPF/CNPJ, agora também formato de e-mail/celular/WhatsApp/CEP e a obrigatoriedade configurável por tenant (`cfg_tela_campo`) — antes só o frontend checava isso. **Módulo `cadastros.funcionario` (novo, 2026-07-21):** CRUD completo de `GET/POST/PUT/DELETE /api/v1/funcionarios` replicando o padrão do cliente (paginação por página, ordenação com allowlist, validação de CPF/celular/% comissão, obrigatoriedade configurável, exclusão com fallback para inativar quando há movimentação de estoque vinculada). Reaproveita `Documentos` (tornado público) para o dígito verificador do CPF; **CPF não é único** aqui (decisão de V016/§3.3.9, oposto do cliente). **Módulo `cadastros.planocontas` (novo, 2026-07-21):** CRUD de `GET/POST/PUT/DELETE /api/v1/planos-contas` — PK de negócio `text` (código contábil digitado pelo usuário, imutável após criar), exclusão **sem** fallback de inativar (tabela sem `ativo` — 409 com vínculo em fornecedor/contas_pagar), `tipoMovimento` validado contra o ENUM acentuado (CRÉDITO/DÉBITO/NEUTRO), busca única código-ou-descrição; sem registro em `cfg_tela_campo` (todos os campos NOT NULL, nada configurável). **Módulo `cadastros.fornecedor` (novo, 2026-07-21):** CRUD de `GET/POST/PUT/DELETE /api/v1/fornecedores` — mesmo padrão de Cliente/Funcionário (obrigatoriedade configurável, exclusão com fallback para inativar quando há movimentação de estoque ou conta a pagar vinculada); CNPJ sempre 14 caracteres (pessoa jurídica — CPF é rejeitado), telefone aceita fixo ou celular (10–11 dígitos, mais frouxo que a regra do cliente); `idPlanoContas` inexistente vira 400 amigável em vez de 500; listagem com filtro por plano de contas. Sem alteração de schema (tabela completa desde V016/V024). **Módulo `configuracao.geral` (novo, 2026-07-21):** `GET/PUT /api/v1/config-geral` sobre o singleton `cfg_geral` (V023, semeado no signup) — sem POST/DELETE; **somente ADMIN, inclusive na leitura** (diferente de `comum.telaconfig`, onde qualquer papel lê); validação só de faixa (percentuais 0–100, dias ≥ 0), já que a tabela inteira é NOT NULL. **Módulo `comum.telaconfig` (novo, 2026-07-21):** `GET/PUT /api/v1/config-tela/{chaveTela}` — quais campos aparecem/são obrigatórios por tenant, reutilizável entre telas (já com 3 telas registradas: `cadastros.cliente.form`, `cadastros.funcionario.form` e `cadastros.fornecedor.form`); só ADMIN grava (403 para OPERADOR, checado a partir do claim `roles` do JWT); leitura filtrada por `id_tenant` explicitamente (defesa em profundidade, além do RLS). **69 testes verdes** (Testcontainers: `ClienteCrudTest` 17, `FornecedorCrudTest` 12, `FuncionarioCrudTest` 10, `PlanoContasCrudTest` 8, `ConfiguracaoGeralTest` 6, `ConfiguracaoTelaTest` 5, + suíte anterior) + fluxo **verificado ao vivo**. Persistência: **Spring Data JDBC**. **Módulo `catalogo.produto` (novo, 2026-07-22 — docs/telas/produto.md):** CRUD de `GET/POST/PUT/DELETE /api/v1/produtos` — categorias N:N com ordenação (`produto_categoria.indice` derivado da posição da lista recebida, substituição total a cada save), `nomeVarianteLinha`/`nomeVarianteColuna` forçados a `null` quando a flag correspondente de `cfg_geral` está desligada, regra da oferta tudo-ou-nada (início/final/preço, com data no passado/ordem/preço `< venda` validados), peso líquido ≤ peso bruto, exclusão com fallback para inativar quando há variação/imagem vinculada. **Módulo `catalogo` auxiliar:** `GET/POST/PUT /api/v1/categorias-produto` (mesmo padrão de categoria de cliente); `GET /api/v1/ncm/{codigo}` (consulta de `cfg_produto_ncm`, tabela global sem RLS, 404 amigável); `GET /api/v1/config-geral/flags-variante` (aberto a qualquer papel, diferente do resto de `cfg_geral`). **Gerador de EAN-13 interno (novo, 2026-07-22):** função SQL `gerar_ean13_interno()` + tabela de controle `cfg_ean_gerador` (GLOBAL, sem `id_tenant`/RLS — sequencial único compartilhado por todos os tenants desta instância de banco; `id_banco` distingue instâncias diferentes se houver *sharding* no futuro); vai popular `produto_barra.sku` **sempre** (nunca digitado) quando o serviço de variação for construído — hoje só a rotina existe, nada chama automaticamente ainda (decisão de acionamento — Java explícito vs. trigger — adiada a pedido do dono do produto). **91 testes verdes** no total (`ProdutoCrudTest` 20 + `EanGeradorTest` 2, novos). Falta: domínio de variação/SKU (`produto_barra`), imagens (`produto_imagem`) e estoque/pedido. **Módulo `financeiro` (novo, 2026-07-23):** `MoedaController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/moedas`, sem `ativo`, exclusão bloqueia com 409 se vinculada a `moeda_detalhe`/`caixa_detalhe`; `percDesconto`/`percAcrescimo` opcionais — nunca os dois com valor positivo ao mesmo tempo, checagem por valor > 0 não por presença) e `TipoCarteiraController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/tipos-carteira`, gerencia embutido o N:N `moeda_detalhe` sem índice — apaga e reinsere a cada save; `taxaAdministradora` opcional, prazo/taxa aceitam 0). Filtro `id_tenant` explícito em toda consulta dos dois serviços (ambiente de teste conecta como superusuário, RLS não filtra sozinho ali). **113 testes verdes** no total (`MoedaCrudTest` 11 + `TipoCarteiraCrudTest` 11, novos). **Módulo `comum.armazenamento` + galeria de fotos de produto (novo, 2026-07-23 — ADR-013):** `ArmazenamentoDeArquivos` (interface) + `GcsArmazenamento` (adapter GCS, cliente `Storage` `@Lazy` — API sobe sem credencial de GCS configurada); `ProdutoImagemController/Service` (`POST/DELETE/PUT /api/v1/produtos/{id}/imagens...`) valida por **magic bytes** (nunca extensão/Content-Type do cliente), normaliza pra **WebP de verdade** (`org.sejda.imageio:webp-imageio`, redimensiona ≤1600px), aplica o **máximo de 6 fotos por produto** (regra de produto), exclusão renumera índices, reordenação; falha de storage sem credencial vira 503 com mensagem clara. **119 testes verdes** no total (`ProdutoImagemCrudTest` 6, novos, contra `fake-gcs-server` via Testcontainers — nenhum teste toca o bucket real). **Categoria de tipo de carteira + histórico do cliente (novo, 2026-07-23):** `tipo_carteira` ganhou `categoria_carteira` (ENUM `AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`/`CREDIARIO`, obrigatório) e `contas_receber` ganhou `id_empresa_pagamento` (nullable — loja do pagamento, editado em V025); novo `ClienteHistoricoController/Service/Dtos` (`GET /api/v1/clientes/{id}/historico`) devolve compras (valor somado do ledger de estoque), parcelas (com dias de atraso calculado) e resumo de crediário em aberto (vencidas/a vencer/total) — só leitura, sem fluxo de venda/baixa de parcela ainda. **126 testes verdes** no total (`ClienteHistoricoCrudTest` 6, novos). **Rede de segurança contra exclusão que viola FK sem avisar (novo, 2026-07-24):** `GlobalExceptionHandler` ganhou handler pra `DataIntegrityViolationException` → 409 genérico ("Registro em uso por outro cadastro"), cobrindo qualquer FK que uma pré-checagem manual de serviço esqueça de enumerar (achado real: `PlanoContasService.excluir()` checava só `fornecedor`/`contas_pagar`, esquecendo `caixa_detalhe` — o DELETE real caía num 500 genérico sem `detail`, indistinguível de "nada aconteceu" no front); corrigido também o `PlanoContasService` pra checar as 3 FKs. **Histórico do Cliente ganhou `qtdProdutos`** em `VendaHistorico` (soma de `qtd_produto` do ledger, mesma origem do `valor`) **e `numeroParcela`** em `ParcelaHistorico` (já existia no banco, nunca exposto) — pedido pra bater com um modelo de planilha (`HISTORICO_DE_COMPRAS.xlsx`/`HISTORICO_DE_PAGAMENTOS.xlsx`) que tinha essas colunas + linha de total. **128 testes verdes** no total (2 novos: `PlanoContasCrudTest`/`TipoCarteiraCrudTest` ganharam teste de exclusão bloqueada por vínculo que faltava cobertura). **Produtos da Compra no Histórico do Cliente (novo, 2026-07-27, pedido do dono do produto com mockup de layout):** `ClienteHistoricoService.buscarProdutos()` — junta `produto_movimento_detalhe` (débito de venda) → `produto_barra` → `produto` (+ `cfg_variante_linha`/`cfg_variante_coluna` pro nome da variação, quando existir) e calcula o preço de venda líquido por unidade com a fórmula pedida (`((qtd_produto * preco_venda) - valor_desconto + valor_acrescimo) / qtd_produto`, `RoundingMode.HALF_UP`, 2 casas — mesma composição do "valor" da compra de 2026-07-23, só dividida de volta pela quantidade); novo DTO `ItemVendaHistorico` + campo `produtos` em `ClienteHistoricoResponse`. Esclarecido com o dono do produto antes de implementar: a lista "DINHEIRO, PIX, CARTAO DEBITO, CARTAO CREDITO, CREDIARIO" do mockup era só exemplo de nomes de carteira, **não** uma mudança na `categoria_carteira` (que segue `AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`/`CREDIARIO` — nenhuma alteração de schema). **130 testes verdes** no total (2 novos: produto sem variação e com variação linha/coluna). **Módulo novo `vendas` — PDV/Frente de Caixa (2026-07-28, docs/telas/pdv.md, spec escrita antes do código a pedido do dono do produto):** `GET /api/v1/pdv/produtos?busca=` (busca por descrição, cada variação de `produto_barra` uma linha, com estoque por empresa — `CROSS JOIN empresa` + `LEFT JOIN produto_estoque` — e total; filtra e faz `LIMIT 20` **antes** de expandir por empresa via CTE, senão o limite cortaria linhas no meio de uma variação) e `GET /api/v1/pdv/produtos/codigo/{codigo}` (leitura por sku ou ean, 404 amigável) — `PdvProdutoController/Service`. `POST /api/v1/pdv/vendas` (`PdvVendaController/Service`) efetiva a venda numa única transação: resolve a única empresa do tenant automaticamente (`SELECT ... LIMIT 1`, tenant 1:1 empresa hoje), valida estoque disponível de **todos** os itens antes de gravar qualquer coisa (`produto_estoque` não tem CHECK contra saldo negativo — a garantia de nunca vender no negativo é só essa validação de serviço, P1), grava `venda` + `produto_movimento_mestre/detalhe` (débito — a trigger `fn_atualiza_estoque_movimento` já existente baixa o estoque sozinha) e a(s) parcela(s) em `contas_receber` a partir do `tipo_carteira` escolhido: categoria `AVISTA`/`CARTAO_DEBITO` só aceita 1 parcela e já nasce paga (`data_recebimento`/`valor_recebido` preenchidos); `CARTAO_CREDITO`/`CREDIARIO` nascem em aberto, vencimento `data_venda + prazoPagamento×N` dias, valor dividido igualmente com o resto do arredondamento absorvido pela última parcela (soma sempre bate exata). Preço/variação de cada item são sempre resolvidos no servidor a partir do `idVariacao` — a tela nunca envia preço. v1 sem cliente/vendedor vinculado, sem seleção de empresa, sem desconto/oferta (não existe vínculo usuário↔funcionário ainda; ver non-goals na spec). **141 testes verdes** no total (`PdvCrudTest` 11, novos: busca com estoque por empresa, produto inativo não aparece, leitura por sku, venda à vista paga na hora e baixa estoque de verdade, crediário com 3 parcelas de soma exata, estoque insuficiente bloqueia com 409 e não grava nada, validações de parcela/carteira, isolamento entre tenants). **Fusão `tipo_carteira`+`moeda` (2026-07-28):** `MoedaController/Service/Dtos` **removidos** (`rm -f`); `TipoCarteiraDtos` ganhou `percDesconto`/`percAcrescimo`; `TipoCarteiraService.validar()` herdou a checagem de mutualidade do `MoedaService` deletado (por valor > 0, não presença — 0/0 é neutro) e `excluir()` passou a checar vínculo em `contas_receber` **e** `caixa_detalhe`; `SignupService` semeia `tipo_carteira` direto (sem passar por `moeda`). **136 testes verdes** no total (`TipoCarteiraCrudTest` foi de 11 pra 17, `MoedaCrudTest` removido). **PDV — split-tender + desconto promocional (2026-07-28, mesma sessão, pedido literal do dono do produto com a fórmula do resumo/saldo a pagar):** `ConfiguracaoGeralService` ganhou `percentualDescontoVenda()` + endpoint `GET /api/v1/config-geral/desconto-venda` (aberto a qualquer papel, mesmo padrão de `/flags-variante` — o PDV precisa do percentual sem ser ADMIN). `EfetivarVendaRequest` trocou `idCarteira`+`numeroParcelas` únicos por `pagamentos: List<PagamentoRequest>` (um `idCarteira`+`valorPago`+`numeroParcelas` por linha). `PdvVendaService.efetivarVenda()` reescrito: calcula `descontoPromocional` = produtos × `cfg_geral.percentual_desconto_venda`; pra cada linha de pagamento, `cobertura = valorPago + valorPago×percDesconto/100 − valorPago×percAcrescimo/100` (desconto faz a linha cobrir *mais* saldo que o valor tendido — é um bônus por pagar naquela forma; acréscimo cobre *menos* — a diferença é custo da forma de pagamento); a soma das coberturas tem que fechar exatamente o líquido a pagar (tolerância de 1 centavo) ou 400 sem gravar nada. Todo o desconto/acréscimo apurado (promocional + de cada linha) é **rateado entre os itens vendidos** em `produto_movimento_detalhe.valor_desconto`/`valor_acrescimo` (proporcional ao valor de cada item, resto do arredondamento no último — mesmo truque da parcela) — **nunca** em `contas_receber.valor_desconto`, reservado pra um recurso futuro e sem relação (desconto ao cobrar parcela de crediário muito atrasada); `contas_receber` sempre grava o `valorPago` literal de cada linha, e por construção a soma bate exatamente com o valor dos itens já líquido de desconto/acréscimo (verificado por derivação matemática a partir do exemplo numérico dado pelo dono do produto, não só por teste). **149 testes verdes** no total (4 novos em `PdvCrudTest`: desconto promocional rateado no item, sem desconto quando o percentual é zero, split-tender com desconto por forma de pagamento fechando o saldo, pagamentos que não fecham o saldo respondem 400 e não gravam nada). **Desconto da venda vira informado pelo operador + teto de valor pago (2026-07-28, mesmo dia, sessão seguinte):** `cfg_geral.percentual_desconto_venda` deixou de ser aplicado automaticamente — passou a ser só o **máximo** permitido; `EfetivarVendaRequest` ganhou `descontoVenda` (BigDecimal explícito, `@NotNull @DecimalMin("0")`), validado em `PdvVendaService.efetivarVenda()` contra `valorTotalProdutos × percentualMaximoDesconto/100` (400 se passar, tolerância de 1 centavo, antes de qualquer INSERT). `resolverPagamentos()` passou a processar as linhas de pagamento **na ordem do request**, mantendo um `saldoRestante` corrente — quando o `tipo_carteira` da linha tem `percDesconto > 0`, o `valorPago` não pode passar de `saldoRestante × (1 − percDesconto/100)` (400 se passar), pedido com exemplo numérico direto do dono do produto ("saldo de 500, desconto de 10%, só posso pagar no máximo o valor − o % de desconto" = R$450). Campos de request/response renomeados de `descontoPromocional` para `descontoVenda` (nada estava commitado, seguro renomear pra ficar mais preciso). **151 testes verdes** no total (2 novos: desconto acima do máximo rejeitado sem gravar nada, valor pago acima do teto de uma forma de pagamento com desconto rejeitado sem gravar nada). **PDV — F5/F6 renomeados, teto de valor pago generalizado, fórmula de desconto fechada, Cliente/Vendedor obrigatórios (2026-07-28, mesma sessão):** `PdvVendaService.resolverPagamentos()` mudou o teto de valor pago de uma linha com desconto de subtração simples pra `saldoRestante ÷ (1 + percDesconto/100)`, mantendo `descontoLinha = valorPago × percDesconto/100` (a fórmula foi tentada de 3 jeitos diferentes na mesma sessão até fechar nesta — detalhe em `docs/telas/pdv.md`). Novo módulo `PdvClienteController/Service` (`GET /api/v1/pdv/clientes?busca=`, nome/CPF-CNPJ/celular, só ativo). `EfetivarVendaRequest` ganhou `idCliente`/`idFuncionario` (`@NotNull`, validados contra o tenant e `ativo=true`, 400 se inválidos); `efetivarVenda()` passou a gravar `venda.id_cliente` e `produto_movimento_detalhe.id_funcionario` (mesmo vendedor em todas as linhas de item da venda) — colunas que já existiam desde V018/V019, sem migration nova. **156 testes verdes** no total (`PdvCrudTest` foi de 22 pra 27). **Módulo `identidade.usuario` (novo, 2026-07-28 — docs/telas/usuario.md):** CRUD de `GET/POST/PUT/DELETE /api/v1/usuarios`, ADMIN-only; gerencia embutido o N:N `usuario_empresa` (apaga e reinsere a cada save, mínimo uma empresa); exclusão nunca permite a própria conta, cai pra inativar se houver `caixa_mestre` vinculado. **Só um ADMIN por tenant, para sempre** (revisão no mesmo dia): `UsuarioRequest` não tem campo `administrador` — `criar` sempre grava `false`, `atualizar` nunca toca a coluna; garantia dupla com `usuario_um_admin_uk` (índice único parcial `WHERE administrador = true`, V015). Novo `identidade.empresa` (`GET /api/v1/empresas`, leitura simples, qualquer papel). **Login com escolha de empresa (novo, docs/telas/login-empresa.md):** `POST /api/publico/login` em até duas voltas quando o usuário acessa mais de uma empresa (`usuario_empresa`) — sem `idEmpresa` devolve a lista pra escolher, com `idEmpresa` (sempre validado contra a lista de acesso) devolve o token; `TokenService` ganhou o claim `eid` (empresa ativa da sessão); `PdvVendaService`/`FuncionarioService` retrofitados pra gravar `id_empresa` a partir desse claim, não mais "a primeira empresa do tenant"; `GET /api/v1/eu` ganhou o campo `empresa`. **Módulo `estoque.transferencia` (novo, 2026-07-28 — docs/telas/transferencia-estoque.md), primeira classe real do pacote `estoque`:** `GET/POST /api/v1/estoque/transferencias` — empresa de origem sempre do claim `eid`, só a de destino vem do request; grava dois `produto_movimento_mestre` (`tipo_movimento='TRANSFERENCIA'`, um por empresa, mesmo `id_transferencia` — nova tabela `produto_transferencia`, cabeçalho) com detalhe 'D'/'C' por produto, reaproveitando a trigger `fn_atualiza_estoque_movimento` já existente sem nenhuma lógica de saldo nova; checa estoque disponível na origem antes de gravar (409 se insuficiente, mesmo padrão do PDV). **179 testes verdes** no total (`UsuarioCrudTest` 11, `LoginEmpresaTest` 3, `TransferenciaCrudTest` 6, novos). **Transferência de Produtos — filtros, ordenação, exclusão e popup de destino (novo, 2026-07-29):** `GET /api/v1/estoque/transferencias` ganhou `idTransferencia`/`idEmpresaOrigem`/`idEmpresaDestino`/`dataInicial`/`dataFinal` (filtro) e `ordenarPor`/`direcao` cobrindo todas as colunas (incluindo `numero`, novo); `DELETE /api/v1/estoque/transferencias/{id}` reverte o estoque nos dois lados apagando o detalhe do ledger (`produto_movimento_mestre` continua imutável por grant — os cabeçalhos ficam órfãos), sem checar saldo no destino. **Estoque negativo permitido em qualquer movimentação (2026-07-29, pedido direto do dono do produto):** `TransferenciaService`/`PdvVendaService` pararam de checar `produto_estoque.disponivel` antes de gravar — venda/transferência sempre é aceita, mesmo deixando saldo negativo; `SignupService` não muda (sem CHECK no banco, nunca existiu). **Módulo novo `financeiro.recebimentocrediario` (2026-07-29, docs/telas/recebimento-crediario.md, spec RN001–RN013 fornecida pelo dono do produto):** `GET /api/v1/recebimento-crediario/clientes` (busca por nome/CPF/celular, 400 se nenhum filtro), `GET /api/v1/recebimento-crediario/parcelas?idCliente=` (só categoria CREDIARIO em aberto, multa/juros recalculados a cada chamada a partir de `cfg_geral.multa_crediario`/`juros_crediario`/carências — percentual único pra multa após a carência, percentual ao dia pra juros), `GET /api/v1/recebimento-crediario/carteiras` (só `permite_receber_crediario = true` nas categorias AVISTA/CARTAO_DEBITO/CARTAO_CREDITO), `POST /api/v1/recebimento-crediario` efetiva numa única transação: trava cada parcela (`FOR UPDATE`, protege contra corrida de recebimento duplo — 409 se já recebida), recalcula multa/juros de novo no servidor (nunca confia no valor da listagem), valida a(s) carteira(s) escolhida(s), aloca os pagamentos nas parcelas em FIFO por vencimento (permite uma parcela ser paga por mais de uma forma — cada fatia vira um lançamento de `caixa_detalhe` com o `id_venda` certo), abre `caixa_mestre` automaticamente se não houver um pra esse usuário/empresa/dia, grava `contas_receber` (baixa) + `contas_receber_detalhe` (quando a carteira dominante da parcela é de cartão) + `contas_receber_lote` (tabela nova, cabeçalho/gerador real de `id_lote_recebimento` — antes só um "gerador externo" placeholder, mesmo padrão que `produto_transferencia` deu a `id_transferencia`). **Schema (V025, editada):** `tipo_carteira.permite_receber_crediario` (boolean, novo — controla quais carteiras aparecem na tela; tenants novos já nascem com DINHEIRO/PIX/CARTAO DEBITO/CARTAO CREDITO marcados `true`, CREDIARIO/vales `false`) + `contas_receber_lote` (tabela nova, RLS próprio). `TipoCarteiraDtos/Service` ganharam o campo. **193 testes verdes no total** (`RecebimentoCrediarioCrudTest` 13, novos, mais os ajustes em `PdvCrudTest`/`ClienteHistoricoCrudTest`/`TipoCarteiraCrudTest`/`TransferenciaCrudTest` que precisaram do campo novo obrigatório ou do comportamento de estoque negativo). Verificado ao vivo no navegador com dados reais de um cliente com parcelas vencidas — cálculo de multa/juros bateu exato com a fórmula, split-tender dividiu uma parcela entre duas formas de pagamento corretamente, caixa abriu sozinho. **Estorno de Recebimento de Crediário (novo, mesmo dia, mesmo módulo `financeiro.recebimentocrediario`):** `GET /api/v1/recebimento-crediario/estornos?nomeCliente=&dataInicial=&dataFinal=` (nome do cliente obrigatório, 400 se vazio; lista cada lote com qtd. de parcelas e formas de pagamento via `string_agg`), `GET /api/v1/recebimento-crediario/estornos/{idLoteRecebimento}/parcelas` (`readOnly`, só visualização) e `POST /api/v1/recebimento-crediario/estornos/{idLoteRecebimento}` (transacional: trava lote+parcelas, apaga `contas_receber_detalhe` **antes** de limpar `id_lote_recebimento`, reabre todas as parcelas do lote numa `UPDATE` só, apaga `caixa_detalhe` do lote e o cabeçalho `contas_receber_lote` — nunca mexe em `caixa_mestre`; 409 se o lote não existir ou já tiver sido estornado). Regra central: um lote pode cobrir parcelas de vendas/contratos diferentes recebidas juntas — estornar uma exige estornar todas as do mesmo lote, garantido por desenho (a unidade de ação é sempre o lote, nunca a parcela). Decisões via `AskUserQuestion`: ADMIN e OPERADOR podem estornar (não só ADMIN) e o lote é apagado fisicamente após o estorno (não mantido com marca) — mesmo padrão da exclusão de Transferência. **7 testes novos** (`RecebimentoCrediarioCrudTest`, 20 no arquivo). **200 testes de backend verdes no total.** **Parâmetro "Permite quantidade decimal para produtos" (novo, 2026-07-29 — docs/telas/configuracao-geral.md):** `cfg_geral` ganhou `cfg_permite_qtd_decimal boolean NOT NULL DEFAULT true` (coluna nova dentro de `V023__cfg_geral.sql`, banco em construção — editada em vez de nova migration); `ConfiguracaoGeralService` ganhou `permiteQtdDecimalProduto()` + `GET /api/v1/config-geral/permite-qtd-decimal` (aberto a qualquer papel, mesmo padrão de `/flags-variante`/`/desconto-venda` — PDV e Transferência precisam do valor sem ser ADMIN). `PdvVendaService`/`TransferenciaService` passaram a validar cada item antes de gravar qualquer coisa: quando o parâmetro está desligado, quantidade com parte decimal (`temParteDecimal()`, novo helper igual nos dois serviços) responde 400 sem gravar nada — antes a coluna `numeric(14,3)` aceitava fração em qualquer situação, sem checagem nenhuma. **203 testes verdes no total** (`ConfiguracaoGeralTest` foi de 6 pra 8, `PdvCrudTest`/`TransferenciaCrudTest` ganharam 1 teste cada de rejeição). **Multiplicador de código de barras "qtd*código" (novo, 2026-07-29):** feature só de frontend (`web/`) — nenhuma mudança de backend, já que a validação de quantidade decimal/inteira acima cobre qualquer valor que a tela envie. **Cancelamento de Venda (novo, 2026-07-30 — módulo `vendas.cancelamento`, docs/telas/cancelamento-venda.md, ADMIN-only):** `GET /api/v1/vendas/cancelamento` (busca por nº ou período+empresa/cliente/vendedor, paginada) e `POST /api/v1/vendas/cancelamento/{id}` reverte numa única transação — estoque (novo `produto_movimento_mestre` tipo `CANCELAMENTO`), `caixa_detalhe` e `contas_receber` da venda são apagados; bloqueio definitivo se alguma parcela de crediário já foi recebida (RN-03) e exige o caixa de **hoje** aberto (RN-02, simplificado — não reabre caixa de dias passados). **Fechamento de Caixa revisado pra "às cegas" (mesmo dia, docs/telas/fechamento-caixa.md):** substituiu a conferência de um único valor (dinheiro) por uma linha de contagem por **cada** tipo de carteira com movimento no dia — `POST /api/v1/caixa/fechamento` só grava (`caixa_mestre` + nova tabela `caixa_fechamento_conferencia`, uma linha por carteira) quando todas as diferenças fecham em zero; se alguma não bate, devolve a divergência sem gravar nada, e um novo `GET /api/v1/caixa/fechamento/{idCaixa}/carteiras/{idCarteira}/lancamentos` mostra o drill-down analítico daquela carteira (inclusive uma linha sintética de abertura de caixa). Coluna antiga `valor_contado_dinheiro` mantida sem uso (nunca apagar dado de caixa já fechado). **Pesquisa de Vendas (novo, módulo `vendas.pesquisa`, docs/telas/pesquisa-vendas.md, qualquer papel — adaptado de uma especificação de um sistema de referência, "Mitryus", pro schema real do Nainer):** `GET /api/v1/vendas/pesquisa` (mesmos filtros do Cancelamento, mais filtro de situação Ativas/Canceladas/Todas; totalizador sempre excluindo canceladas, calculado sobre todo o filtro, não só a página) e `GET /api/v1/vendas/pesquisa/{id}` devolve o detalhamento completo — itens do ledger, movimentação de `caixa_detalhe` e parcelas de `contas_receber` (com situação Aberta/Paga/Vencida sempre calculada na hora), mais `recebido`/`aReceber` derivados de `contas_receber` (pagamento à vista fica quitado na hora; cartão fica pendente até uma futura conciliação, ainda não implementada). Empresa: ADMIN filtra livremente, OPERADOR sempre só a própria empresa da sessão (servidor ignora um `idEmpresa` diferente enviado por OPERADOR). **274 testes verdes no total** (`CancelamentoVendaCrudTest` 10, `FechamentoCaixaCrudTest` foi de 11 pra 14, `PesquisaVendaCrudTest` 10, novos). **Abertura de Caixa restrita a "Dinheiro" (2026-07-31):** `CaixaService.listarCarteirasParaAbertura()` passou a filtrar `categoria_carteira = 'AVISTA' AND nome_carteira = 'DINHEIRO'` (antes listava todo `tipo_carteira` do tenant); `validarCarteira()` (usada em `POST /caixa/abrir`) reforça a mesma regra no servidor — 400 se a carteira informada não for a Dinheiro. **Fechamento de Caixa — `categoriaCarteira` na resposta (mesmo dia):** `LinhaTotalCarteiraResponse`/`LinhaConferenciaResponse` ganharam o campo, resolvendo o caso de duas carteiras com o mesmo nome em categorias diferentes (ex.: "HIPER" débito/crédito) aparecerem indistinguíveis na tela. **266 testes verdes no total** (+2 em `CaixaCrudTest`, +1 em `FechamentoCaixaCrudTest`). **Plano de Contas Gerencial (revisão completa, mesmo dia — docs/telas/plano-contas.md):** `cfg_plano_contas` (V016) ganhou máscara `9.99.999.999`, hierarquia de 4 níveis (`nivel`/`id_plano_contas_pai` gerados), `natureza`, `grupo_dre`/`grupo_dfc` (obrigatórios só quando `inclui_dre`/`inclui_fluxo_caixa` são `true`), `sinal`/`aceita_lancamento` (sempre derivados), exigências, `padrao_sistema`, **`ativo`** (tirou esta tela da única exceção sem fallback de inativar); `tipo_movimento_conta` perdeu os acentos. Dado existente (1 conta, vinculada a 1 fornecedor) migrado sem apagar nada. Novo `db/scripts/seed_plano_contas_padrao.sql` carregou 335 contas padrão pro tenant 1 (336 no total). `PlanoContasDtos/Service/Controller` reescritos — erro amigável quando falta a conta pai, exclusão com fallback. Frontend manteve lista plana (decisão confirmada), só com os campos novos e máscara. **276 testes verdes no total** (`PlanoContasCrudTest` foi de 8 pra 19; `FornecedorCrudTest`/`ContaCorrenteCrudTest`/`ContaCorrenteMovimentoCrudTest` tiveram códigos de teste convertidos pro formato novo). |
| `site/` | Site público (Astro/SSG, ADR-011). **Home institucional "matadora"** (posicionamento concorrente do Bling): hero com painel animado + demo de sincronização, faixa de stats com contadores, contraste problema→solução, 3 passos, 6 recursos, canais (ML/Shopee/Amazon/balcão), planos (preços via `/api/publico/planos`), FAQ e CTA — tudo em CSS/SVG puro com **scroll-reveal** e **prefers-reduced-motion** (sem novas deps). Sistema visual em `src/styles/site.css` portado do golden `nainer_institucional`. `/assinar` (form → `POST /api/publico/assinar` → auto-login → `/bem-vindo`) e `/bem-vindo` mantidos. **Trial 60 dias** em toda a copy. Tema claro/escuro persistido. **Build SSG ok**; hero/reveal/contadores verificados via Playwright |
| `web/` | ERP do lojista (React 19 + Vite + TS). Auth JWT (login slug+email+senha; **handoff SSO** do site via `#token=`), shell (nav Painel/Produtos/Estoque/Pedidos/Canais/**Clientes** + Sair), **Painel** real (`GET /api/v1/eu` via TanStack Query). **Tela de Clientes completa** (2026-07-20/21): ícone de identificação (pessoa) à esquerda do título, listagem com busca em maiúsculas/filtro/**paginação fixa em 50 itens, sem seletor** (janela deslizante de páginas com primeira/anterior/próxima/última, estilo inspirado no sistema legado)/**ordenação por coluna** (cabeçalho em destaque, ícone "⇅"/"▲"/"▼" em cada uma), **três ícones de ação por linha** (visualizar verde/editar azul/excluir vermelho, sem texto — visualizar abre `/clientes/:id/visualizar` em modo somente-leitura), grid mais compacta, formulário com cabeçalho enxuto ("Cliente" + Cancelar/Salvar no topo, topo fixo/só o corpo rola) e grid de 12 colunas (§3.7) largura total (`.app-main` 1600px), máscaras com validação de dígito verificador/formato/duplicidade (inclusive **CNPJ alfanumérico** e **limite de crédito em moeda**), validação por campo (blur + submit, replicada no backend — 2026-07-21), pop-up de erro vermelho (`Toast.tsx`), autopreenchimento de endereço via ViaCEP, modal embutido de categoria, exclusão com confirmação em modal próprio, `AjudaDaTela` (R22), convenções de **maiúsculas sempre** e **foco automático**. **Tela de configuração de campos** (`ConfiguracaoTelaCliente.tsx`, `/clientes/configuracao`, só `ADMIN` — `RequireAdmin.tsx`): cabeçalho enxuto com Cancelar/Salvar no topo (fixo, 2026-07-21), acessível pelo ícone ⚙ ao lado da ajuda; o formulário de Cliente lê essa config (`lib/configuracaoTela.ts`) e ajusta visibilidade/obrigatoriedade dos campos em tempo real. **Tela de Funcionários completa (nova, 2026-07-21):** `pages/funcionarios/` (lista + formulário + configuração de tela), replicando integralmente o padrão do cliente — ícone próprio (maleta), ordenação por coluna, paginação fixa em 50, três ícones de ação, modo somente-leitura, máscara de percentual para "% Comissão". **Tela de Plano de Contas completa (nova, 2026-07-21):** `pages/planocontas/` (lista + formulário; sem tela de configuração — nada configurável), código contábil como PK de negócio nas rotas (`/planos-contas/3.1.001`), Código bloqueado na edição, sem filtro de status (tabela sem `ativo`), ícone próprio (prancheta). **Tela de Fornecedores completa (nova, 2026-07-21):** `pages/fornecedores/` (lista + formulário + configuração de tela), com um mecanismo novo — `PlanoContasModal.tsx`, criação rápida de plano de contas embutida no formulário (botão "＋ Novo" ao lado do select de Plano de Contas, mesmo papel do modal de categoria do cliente); filtro por plano de contas na listagem; ícone próprio (caminhão). **Parâmetros do Sistema (nova, 2026-07-21):** `pages/configuracaogeral/ConfiguracaoGeralForm.tsx` — primeira tela **fora** do padrão de cadastro: sem listagem/paginação/busca/modo somente-leitura/`InfoRegistro` (a tabela `cfg_geral` é singleton por tenant, sem `criado_em`); item de menu ("Parâmetros do Sistema") e a própria rota só aparecem/funcionam para ADMIN; ícone próprio (`IconeParametros`), deliberadamente diferente da engrenagem (⚙) usada como atalho de "configurar campos" em cada cadastro. **Campos informativos de auditoria** (`InfoRegistro.tsx` + `lib/datas.ts`, 2026-07-21): Código/Cadastrado em/Última alteração, somente leitura, no fim de todo formulário de cadastro (em Cliente, Funcionário, Plano de Contas e Fornecedor; o `codigo` aceita PK numérica ou texto). Demais áreas (Produtos/Estoque/Pedidos/Canais) ainda placeholder. **Shell do ERP com altura travada no viewport** (2026-07-21, convenção nova — `Layout.tsx`/`styles.css`): menu lateral e cabeçalho fixos, sem scroll de página inteira; `html`/`body`/`#root` com `overflow: hidden` (2026-07-21 — sem isso, qualquer 1px de folga faz o documento inteiro rolar) e `.app-main`/`.table-wrap` com altura própria fazendo o scroll de verdade, para o cabeçalho `position: sticky` das tabelas grudar no lugar certo; a tela de Clientes usa `.lista-tela`/`.lista-topo`/`.lista-corpo`/`.lista-rodape` para travar também a barra de filtros e o rodapé de paginação, deixando só a tabela com scroll próprio. Barra de rolagem no padrão de cores do tema (claro/escuro). Design tokens §3.7. **Build ok**; fluxo **e2e verificado no navegador**. **Tela de Produtos completa (nova, 2026-07-22 — `pages/produtos/`):** lista + formulário + configuração de tela, mesmo padrão; categorias com setas ▲/▼ de reordenação + modal de gestão embutida (`CategoriaProdutoModal.tsx`); seção "Dimensões e Variantes" só mostra nome de variante quando a flag correspondente de Parâmetros do Sistema está ligada; NCM com máscara `9999.99.99` e busca automática de descrição ao lado; preço de venda/% de venda recalculados ao vivo um a partir do outro; layout final: "Produto ativo" em linha própria, Descrição+Marca juntos, Referência+NCM+descrição do NCM juntos, os 6 campos de Preços numa única linha, Peso Bruto/Líquido+variantes numa única linha. **Convenções novas que valem pro sistema inteiro (2026-07-22):** campo decimal (moeda/percentual/peso) com digitação natural (`lib/masks.ts#mascararMoeda/mascararPercentual/mascararPeso` + `completar*` no `onBlur`) substituindo a antiga leitura de dígitos da direita; campo de data como texto mascarado `dd/mm/aaaa` (`mascararData`, `onFocus` com `.select()`) substituindo `<input type="date">` em Cliente (nascimento) e Produto (início/final de oferta) — o nativo não permite "selecionar tudo e sobrescrever ao digitar" em nenhum navegador. Ícone próprio (caixa/pacote). **Confirmação antes de salvar via Enter (2026-07-23, `ConfirmarSalvarModal.tsx` + `lib/formularios.ts`):** nas telas de cadastro, Enter num campo de texto abre "Salvar dados?" em vez de submeter direto; clique no botão "Salvar" continua instantâneo. **Telas de Moeda e Tipo de Carteira completas (novas, 2026-07-23 — `pages/moeda/`, `pages/tipocarteira/`):** mesmo padrão de cadastro; Tipo de Carteira gerencia embutido o checklist de moedas (`moeda_detalhe`, sem reordenar) com criação rápida de moeda (`MoedaModal.tsx`, mesmo papel do `PlanoContasModal`) e ícones por moeda no checklist (`IconeDaMoeda`, heurística por palavra-chave — cartão/PIX/genérico); % Desconto/% Acréscimo de Moeda são mutuamente exclusivos (digitar valor > 0 num limpa o outro); Taxa Administradora de Tipo de Carteira é opcional. Ícones próprios (cifrão/carteira) e itens no menu. **Galeria de fotos em Produtos (nova, 2026-07-23 — ADR-013, `GaleriaImagensProduto.tsx`):** seção "Fotos (N/6)" no formulário — miniaturas com setas de reordenar + lixeira de excluir, botão "＋ Adicionar foto" (desabilita ao atingir 6), oculta no modo somente-leitura, aviso "salve o produto primeiro" em produto novo (upload precisa de `idProduto` de verdade). Upload é `multipart/form-data` — `lib/api.ts` ganhou `apiUpload()` (nunca define `Content-Type` manualmente, o navegador precisa gerar o boundary sozinho). **Galeria de fotos — confirmação, lightbox e upload antes de salvar (2026-07-23):** excluir foto pede confirmação em modal; clicar numa miniatura abre lightbox (`.modal-lightbox`) com navegação ◀/▶/teclado entre as fotos do produto; produto novo (ainda sem `idProduto`) já permite escolher fotos — ficam em preview local (`arquivosLocais`, `URL.createObjectURL`) e são enviadas em ordem só depois que o produto é criado. **Tipo de Carteira** ganhou o campo "Categoria \*" (select) no formulário e coluna própria na listagem. **Tela de Histórico do Cliente (nova, `pages/clientes/ClienteHistorico.tsx`, rota `/clientes/:id/historico`):** acessível por ícone (relógio) na lista de Clientes e botão no formulário — 3 seções (Histórico de Compras, Histórico de Parcelas, Resumo das Parcelas de Crediário em cards), só leitura, sem paginação. **Pop-up de erro/sucesso em todo o sistema (2026-07-24):** `Toast.tsx` ganhou a prop `tipo?: 'erro' | 'sucesso'` (vermelho/verde, sempre letra branca); as 7 listagens de cadastro (Cliente/Fornecedor/Funcionário/Produto/Moeda/Tipo de Carteira/Plano de Contas) trocaram o banner inline (`aviso-banner`, removido do CSS) por `Toast`; `Login.tsx` e `ConfiguracaoGeralForm.tsx` idem. **Pop-up de sucesso ao clicar Salvar (novo):** os 7 formulários de cadastro e as 4 telas de configuração de campos, que antes só navegavam de volta pra lista sem avisar nada, agora passam `navigate(rota, { state: { toast } })` — a lista lê `location.state` uma vez no primeiro render (`useState` com inicializador preguiçoso) e limpa do histórico (`window.history.replaceState`) pra um F5/voltar não repetir o popup. **Histórico do Cliente — grid navegável e master-detail (2026-07-24, vídeo/imagem de referência do dono do produto, `docs/PROGRESSO.md` linha do tempo):** linhas de Compras e Parcelas ficam destacadas ao clicar/Enter/Espaço (`.linha-selecionada`, cor `--accent`); Compras ganhou barra de navegação ▲/▼ (mouse e teclado ↑/↓) que troca a compra selecionada — a primeira já vem selecionada ao carregar; Histórico de Parcelas **filtra automaticamente pelas parcelas da compra selecionada** (master-detail de verdade, com botão "Ver todas as parcelas" pra sair do filtro); colunas novas: Qtd Produtos (compras) e Nº Parcela (parcelas); Tipo passou a mostrar `nomeCarteira` (nome específico da carteira) em vez da categoria genérica; linha de Total no rodapé das duas grids (recalculada sobre o conjunto filtrado, no caso de parcelas). Decisão confirmada pelo dono do produto: manter o visual do próprio Nainer (claro/escuro), só copiando o *comportamento* de navegação do sistema legado — sem caixa de detalhe de texto abaixo da grid (só destaque de linha). **Histórico do Cliente — layout em duas colunas (2026-07-27, pedido do dono do produto com mockup ASCII de referência):** reorganizado pra Histórico de Compras à esquerda (altura cheia, mantendo navegação ▲/▼ e teclado) e, à direita, **Produtos da Compra** (novo painel — Descrição do Produto/Variação de Linha/Variação de Coluna/Qtd Vendida/Preço de Venda) empilhado sobre o Histórico das Parcelas, os dois sempre filtrados pela compra selecionada/navegada (master-detail — o botão "Ver todas as parcelas" foi removido, deixou de fazer sentido no layout novo). CSS novo (`.historico-corpo`/`.historico-grid`/`.historico-coluna-esquerda`/`.historico-coluna-direita`/`.historico-painel-produtos`/`.historico-painel-parcelas`) — cada painel rola o próprio conteúdo, mesmo princípio de `.lista-corpo`/`.table-wrap` adaptado pra duas colunas. Coluna "Tipo" da grid de Parcelas renomeada pra "Tipo Parcela" (o dado exibido continua `nomeCarteira`, decisão de 2026-07-24 mantida — só o rótulo mudou) e "Id Venda" da grid de Compras renomeada pra "Nº Venda" (pedido à parte, mesma sessão). **Tela de PDV — Frente de Caixa (nova, 2026-07-28 — `pages/pdv/`):** nasceu como rascunho de layout num Artifact (mockup `TELA PDV.png`/`tela_modelo_1.png` do dono do produto) e foi incorporada ao ERP em etapas na mesma sessão — primeiro só a tela (F2/F3/F4/F5 com catálogo de demonstração local, sem API), depois renomeação de rótulos (Quantidade/Valor Unitário/Total Produto), F2 (`PesquisaProdutoModal.tsx`) e F3 (`AlteraQuantidadeModal.tsx`, com confirmação antes de remover item ao chegar em zero) funcionais, navegação ↑/↓ nos Produtos Vendidos (funciona mesmo com foco no campo de código de barras — nenhuma das teclas usadas insere caractere), ícones nos botões F2–F5 (`IconeLupa`/`IconeAjustar`/`IconeLimpar`/`IconeConfirmar`, novos em `Icones.tsx`) e um bug real corrigido: o listener global de teclado tinha um `return` por causa do foco no campo de código de barras **antes** de tratar F2–F5, então o `preventDefault` nunca rodava e o navegador vencia (F5 recarregava a página de verdade) — corrigido tirando essa checagem de cima das F-keys. Por fim (2026-07-28), a tela virou real: `lib/pdv.ts` substituiu o catálogo de demonstração (`catalogoDemo.ts`, removido) por chamadas de verdade (`buscarProdutosPdv`/`buscarProdutoPorCodigo`/`efetivarVenda`); `FormaPagamentoModal.tsx` (novo) escolhe o Tipo de Carteira e o número de parcelas, mostra a prévia calculada no front (mesma conta do backend, só pra exibir antes de confirmar) e efetiva a venda de verdade no F5. Verificado ao vivo com produtos reais cadastrados no banco de dev: venda de crediário em 3x bateu exatamente com a prévia e, conferido direto no banco, gravou a venda, baixou o estoque só na loja certa e criou as 3 parcelas em aberto. **Menu lateral retrátil (novo, 2026-07-28 — `Layout.tsx`):** botão no rodapé do menu alterna entre 200px (com rótulos) e 56px (só ícones, com `title` como tooltip), preferência em `localStorage`; ícone novo pra todo item que ainda não tinha (Painel, Estoque, Pedidos, Canais — necessário pro modo recolhido não ficar vazio). **Tela de Moeda removida / Tipo de Carteira absorveu o cadastro (2026-07-28 — fusão `tipo_carteira`+`moeda`):** `pages/moeda/`, `lib/moedas.ts` e `MoedaModal.tsx` **deletados**; rotas `/moedas*` e item de menu "Moeda" removidos de `App.tsx`/`Layout.tsx`; `TipoCarteiraForm.tsx` perdeu o checklist de moedas embutido e ganhou a seção "Desconto / Acréscimo" (% Desconto/% Acréscimo, mesma UX de mutualidade por valor que a Moeda tinha) direto no formulário, com nota explicando que a mesma bandeira pode ter um cadastro por categoria; `TipoCarteiraLista.tsx` trocou a coluna "Moedas" por "% Desconto"/"% Acréscimo" (e as três agora são ordenáveis por coluna, pedido à parte no dia seguinte). **F5 — Forma de Pagamento virou split-tender + desconto promocional (2026-07-28, mesma sessão do dia seguinte, pedido literal do dono do produto com a fórmula do resumo/saldo a pagar):** `FormaPagamentoModal.tsx` reescrito por completo. Resumo no topo: Valor Total da Venda sempre; Desconto Promocional + Sub-total só aparecem quando `cfg_geral.percentual_desconto_venda > 0` (`lib/configuracaoGeral.ts` ganhou `buscarDescontoVenda()`, endpoint aberto a qualquer papel). **Saldo a Pagar** sempre visível antes dos botões, fica verde quando fecha em zero. **4 botões de categoria** (À Vista/Cartão Débito/Cartão Crédito/Crediário, ordem fixa) — desabilitado se não houver tipo de carteira cadastrado na categoria; ao clicar, se houver mais de um tipo de carteira na categoria (ex.: HIPER débito e crédito) pede pra escolher, senão já vai direto pro valor pago (pré-preenchido com o saldo restante, `formatarMoeda`) + prévia de quanto aquela linha cobre do saldo (mesma fórmula do backend, só client-side) + prévia de parcelas. Dá pra combinar várias formas de pagamento na mesma venda — lista embaixo do resumo, cada uma removível — e só libera "Confirmar Venda" quando a soma fecha o saldo. `lib/pdv.ts` trocou `EfetivarVendaRequest.idCarteira/numeroParcelas` por `pagamentos: PagamentoRequest[]` e `VendaEfetivada` ganhou `valorTotalProdutos`/`descontoPromocional`/`valorLiquido`/`pagamentos[].parcelas`. CSS novo (`.pdv-resumo-pagamento`/`.pdv-saldo-pagar`/`.pdv-categorias-pagamento`/`.pdv-edicao-pagamento`). Verificado ao vivo no navegador: venda de R$100 com 10% de desconto promocional + dois pagamentos (dinheiro com 10% de desconto próprio + débito) fechando o saldo em R$0,00 exato, efetivada com sucesso (venda registrada, depois removida do banco de dev por ser só teste). **`FormaPagamentoModal.tsx` — desconto vira campo do operador + teto de valor pago (2026-07-28, mesmo dia, sessão seguinte):** o resumo perdeu o cálculo automático de desconto e ganhou dois campos sincronizados — **Desconto (%)** e **Desconto (R$)** (`mascararPercentual`/`mascararMoeda`, convenção de sempre) — que nascem zerados, clampam no `onBlur` contra o máximo de `cfg_geral.percentual_desconto_venda` e ficam travados assim que o primeiro pagamento é lançado; Sub-total só aparece quando o desconto informado é `> 0`. O campo "Valor Pago" de uma linha cuja forma de pagamento tem `percDesconto` próprio agora mostra o valor máximo permitido e **ajusta sozinho** pro teto se o operador digitar acima (`calcularValorPagoMaximo`, mesma fórmula do backend). `lib/pdv.ts` — `EfetivarVendaRequest` ganhou `descontoVenda`; `VendaEfetivada.descontoPromocional` renomeado pra `descontoVenda`. **F6 Efetiva Venda (era F5) + F5 Devolver Produto reservado + Cliente/Vendedor obrigatorios (2026-07-28, mesma sessao):** botao grande renomeado pra F6; novo botao F5 na linha F2-F4, sem funcionalidade ainda (so o atalho reservado). Resumo reorganizado: fonte maior em "Valor Total da Venda", foco automatico no campo % do Desconto Gerencial, prefill de "Valor Pago" ja com o valor ajustado ao escolher uma carteira com desconto, teto de valor pago valendo pra qualquer carteira (nao so as com desconto). Layout dos botoes de categoria mudou de grade horizontal pra coluna ao lado do resumo, sempre visiveis; area de pagamentos (lista ou edicao de linha) numa caixa propria abaixo; rodape Cancelar/Confirmar sempre visivel. Bug corrigido: "Valor a Pagar" nao caia conforme as formas de pagamento eram lancadas (so descontava o Desconto Promocional, esquecendo o que ja tinha sido pago). **Cliente e Vendedor da venda (novo):** dois modais novos (`PesquisaClienteModal.tsx` — nome/CPF-CNPJ/celular — e `PesquisaVendedorModal.tsx` — nome, reaproveitando `listarFuncionarios` ja existente), campos obrigatorios no topo da tela ("Cliente *"/"Vendedor *"), "Confirmar Venda" exige os dois selecionados alem do saldo fechado. `lib/pdv.ts` ganhou `PdvCliente`/`buscarClientesPdv`; `EfetivarVendaRequest` ganhou `idCliente`/`idFuncionario`. **Tela de Usuários completa (nova, 2026-07-28 — `pages/usuarios/`):** lista + formulário, ADMIN-only (`RequireAdmin` na rota + item de menu só visível pra ADMIN), sem `cfg_tela_campo`; formulário com checklist "Empresas com acesso" (`GET /api/v1/empresas`); campo "Administrador" **não é um checkbox** — quando o usuário editado é o admin, aparece um badge somente-leitura com explicação de que o privilégio é permanente e definido só no signup. **Login com escolha de empresa (`Login.tsx` reescrito):** formulário normal vira uma segunda etapa (lista de botões, um por empresa) quando a API responde pedindo escolha; "Voltar" descarta sem perder as credenciais já digitadas. `lib/eu.ts` ganhou o campo `empresa`; `Layout.tsx` mostra o nome da empresa ativa no header. **Tela de Transferência de Estoque (nova, 2026-07-28 — `pages/estoque/`), primeiro conteúdo real do menu Estoque (era `EmBreve`):** `TransferenciaLista` (`/estoque`, histórico com paginação), `TransferenciaForm` (`/estoque/nova` — Empresa de Origem sempre somente-leitura com o nome da empresa ativa, Empresa de Destino em select excluindo a origem, produtos adicionados via `PesquisaProdutoModal` do PDV reaproveitado direto, quantidade com a máscara de peso de 3 casas) e `TransferenciaDetalhe` (`/estoque/:id`, somente-leitura, sem editar/excluir — registro permanente como uma venda). Achado ao vivo: `<textarea>` nunca tinha estilo global (`styles.css` só tinha `input, select`) — corrigido na mesma regra. **Transferência de Produtos — filtros, ordenação, exclusão, popup de destino, alinhamento e totais na grid (novo, 2026-07-29, três rodadas de pedidos do dono do produto):** barra de filtros reordenada (Data Inicial/Final, Nº da Transferência, Empresa de Saída/Entrada) com coluna "Nº" nova e ordenável em todas as colunas (`th-ordenavel`, mesmo padrão do resto do projeto); ícone vermelho de excluir por linha com modal de confirmação avisando que o estoque volta pra origem mesmo sem saldo no destino. `Nova Transferência` reescrita: `EscolherDestinoModal.tsx` novo — popup que mostra a origem e pede o destino antes de liberar a tela de produtos (a antiga seção "Empresas" com select embutido saiu do corpo do formulário); título passou a mostrar "Nova Transferência · {origem} → {destino}" na mesma linha; campo Observações removido (nunca foi usado); alinhamento do campo Código de Barras com o botão "Pesquisar Produto" corrigido (rótulo invisível do mesmo tamanho do real, garantindo que os dois comecem na mesma linha, em vez de alinhar pelo fundo da coluna mais alta); totais (Quantidade/Valor Total) viraram uma linha `<tfoot>` dentro da própria tabela em vez de uma caixa solta — garante alinhamento pixel-perfeito com as colunas Quantidade/Valor Total da grid (mesmo padrão já usado no Histórico do Cliente). **Tipo de Carteira** ganhou o checkbox "Permite receber parcelas de crediário" (`permiteReceberCrediario`, RN007). **Tela nova `financeiro.recebimentocrediario` (`pages/recebimentocrediario/RecebimentoCrediario.tsx`, 2026-07-29):** busca de cliente (3 campos, resultado em tabela clicável), grid de parcelas selecionável/navegável por teclado com rodapé de 5 caixas de totais (`.recebimento-rodape-totais`, CSS novo), área de forma de pagamento reaproveitando o visual de categoria→carteira→valor do `FormaPagamentoModal` do PDV (sem a lógica de desconto/acréscimo da carteira, que não se aplica aqui) — split-tender testado ao vivo dividindo uma parcela entre duas formas de pagamento. Item novo no menu lateral (`IconeRecebimentoCrediario`, recibo com check) e entrada em `AjudaDaTela.tsx` (`financeiro.recebimentocrediario.tela`). **Redesenho de layout (mesmo dia, sessão seguinte):** grid ganhou coluna Empresa e "Nº PC" (`parcela/totalParcelas`, escopado por venda+carteira), Multa e Juros fundidas numa coluna; rodapé de totais virou barra lateral fixa com altura mínima reservada e só a grid rola por dentro (`.recebimento-parcelas-layout`/`.recebimento-parcelas-sidebar`, CSS novo); os 3 botões de categoria saíram da lateral — um único botão "Receber" abre `EscolherFormaPagamentoModal.tsx`, que já resolve categoria **e** Tipo de Carteira/Valor Pago no mesmo popup; formas de pagamento lançadas viraram cards em grade de 4 colunas; botão final renomeado "Efetivar Recebimento", "Cancelar" da busca virou "Voltar", botão "Trocar" redundante ao lado do nome do cliente removido. **Estorno de Recebimento de Crediário (tela nova, mesmo dia — `EstornoRecebimentoCrediario.tsx`):** busca por nome do cliente (obrigatório) + intervalo de data de recebimento, lista cada lote já efetivado (data, cliente, qtd. de parcelas, valor total, formas de pagamento usadas) com um ícone verde "visualizar" (abre popup só-leitura com as parcelas do lote) e um ícone vermelho "estornar" (reverte o lote inteiro — nunca uma parcela isolada — com modal de confirmação); mesmo esqueleto de `TransferenciaLista.tsx`, sem paginação. Item de menu "Estorno de Crediário", ícones próprios (`IconeEstornoRecebimentoCrediario`/`IconeEstornar`). **Menu "Estoque" renomeado para "Transferência de Produtos" (2026-07-29):** só o rótulo em `Layout.tsx` — rota/ícone inalterados. **"Empresas com acesso" (Usuários) em grade de 6 colunas (2026-07-29):** `UsuarioForm.tsx` trocou a lista vertical de checkboxes por um cartão por empresa em grade de 6 colunas, eliminando o scroll vertical do checklist. **Parâmetro "Permite quantidade decimal para produtos" (novo, 2026-07-29 — `cfg_geral.cfg_permite_qtd_decimal`):** checkbox na seção "Estoque" de Parâmetros do Sistema (`ConfiguracaoGeralForm.tsx`), lido via `buscarPermiteQtdDecimal()`/`GET /permite-qtd-decimal` (qualquer papel) em todo lugar que mostra ou digita quantidade de produto — PDV (`Pdv.tsx`, `PesquisaProdutoModal.tsx`, `AlteraQuantidadeModal.tsx`), Transferência de Produtos (`TransferenciaForm.tsx`, `TransferenciaDetalhe.tsx`) e Histórico do Cliente (`ClienteHistorico.tsx`) — quando ligado (padrão) aceita/mostra 3 casas decimais (`mascararQuantidade`/`completarQuantidade`/`desmascararQuantidade`/`formatarQuantidade`, novas em `lib/masks.ts`, delegando às funções de peso já existentes), quando desligado força inteiro; validado também no servidor (`PdvVendaService`/`TransferenciaService` rejeitam com 400 quantidade fracionária quando desligado — P4, o front nunca é a única barreira). **Multiplicador de código de barras "qtd*código" (novo, 2026-07-29):** `interpretarCodigoBarras()` (nova em `lib/pdv.ts`) reconhece a sintaxe `5*9001000000138` (quantidade 5, código `9001000000138`) na leitura de código de barras do PDV (`Pdv.tsx`) e da Transferência de Produtos (`TransferenciaForm.tsx`) — sem o `*` funciona como sempre (quantidade 1); dica discreta ("Dica: '5*código' lança direto com quantidade 5.") exibida abaixo do campo de código de barras nas duas telas. **Fechamento de Caixa redesenhado pra "às cegas" (2026-07-30, `FechamentoCaixa.tsx` reescrito):** contagem por carteira sem mostrar o valor esperado, tela de divergência (esperado/contado/diferença por carteira, clicável) com `LancamentosCarteiraModal.tsx` novo pro drill-down, botão "Contar de Novo" preservando os valores já digitados; impressão (`fechamentoCaixaImpressao.ts`) ganhou a seção de conferência. **Cancelamento de Venda (nova, `pages/vendas/CancelamentoVenda.tsx` + `CancelamentoVendaModal.tsx`, ADMIN-only):** mesmo esqueleto de filtro/grid/paginação das demais listagens, badge "Cancelada"/"🔒 Bloqueada" (crediário com parcela recebida) por linha — **desde 2026-07-31, à direita do ícone verde de visualizar** (antes vinha antes dele) —, modal de confirmação mostrando tudo que será revertido antes de pedir o motivo. **Pesquisa de Vendas (nova, `pages/vendas/PesquisaVendas.tsx`, qualquer papel):** mesmo esqueleto de filtro/grid, mas **sem abas** — ao clicar numa linha, quatro grids de detalhamento (dados da venda, produtos vendidos, movimentação de caixa, parcelas de crediário), reutilizando o padrão `.form-grid`/`.campo-leitura` de `InfoRegistro.tsx` pro bloco de dados; **desde 2026-07-31 abrem dentro de um popup** (`DetalheVendaModal.tsx`, extraído do corpo da tela — rola internamente, sem afetar o scroll da página) **em vez de empilhar abaixo do resultado**. Ícone novo (`IconePesquisaVendas`). **Menu lateral — expandir ao passar o mouse/focar (2026-07-30, `Layout.tsx`):** com o menu recolhido, hover ou foco (mouse ou Tab) expande temporariamente mostrando os rótulos, sem alterar a preferência persistida; sai do `<nav>` (mouse ou foco, ignorando troca de foco **entre** os próprios links) e recolhe de novo automaticamente. **Abertura de Caixa — saldo inicial só em "Dinheiro" (2026-07-31):** `CamposAberturaCaixa.tsx` trocou o select de moeda por um campo fixo. **Fechamento de Caixa — carteiras homônimas em categorias diferentes aparecem separadas (2026-07-31):** `rotuloCarteira()` novo em `lib/caixa.ts` monta `"NOME — Categoria"` (ex.: "HIPER — Cartão Débito"/"HIPER — Cartão Crédito"), usado em toda a tela e na impressão/PDF. |
| `admin/` | Ainda não criado (backoffice React 19 + Vite) |

**Stack alvo:** Java 25 + Spring Boot 4.x · PostgreSQL 18 (Docker, banco **`niner_db`**) · React 19 + Vite (3 apps) · Flyway · JWT. **SaaS multi-tenant** (banco único + `id_tenant` + Postgres RLS).

**Sessão de 2026-08-04 (continuação, depois do commit `4ac997b`):** toda tela do sistema ganhou um **botão de fechar (✕)** no canto superior direito (`BotaoFecharTela.tsx`, volta pra tela anterior real via histórico do navegador — `navigate(-1)` — não uma rota fixa); **Enter passou a mudar de campo igual Tab** em qualquer `<input>`/`<select>` do sistema (`lib/formularios.ts`: `aoTeclarEnterNoFormulario` nas telas de cadastro, `iniciarNavegacaoGlobalPorEnter` no resto), preservando os poucos campos com Enter próprio (leitor de código de barras, buscas); os relatórios de **Comissões** (colunas Nº Vendas/Ticket Médio) e **Contas a Receber** (KPIs + gráfico "por forma de pagamento", coluna Valor Taxa Adm.) ganharam uma 2ª rodada de melhorias; nasceu a **5ª tela de Relatórios**, **Movimentação de Produtos** (Kardex, `docs/telas/relatorio-movimentacao-produtos.md` — 3 modelos: Analítico, Kardex por Produto com saldo corrido, Sintético por tipo de movimento), cujos testes descobriram que **nenhum service gravava `produto_movimento_detalhe.preco_custo`** — corrigido na raiz nos 5 services que escrevem no ledger (Pdv/Devolução/Cancelamento/Transferência/Balanço); e o grupo **Implementações Futuras** ganhou 9 áreas ainda não construídas (Etiqueta de Produtos — configuração e emissão —, CRM, BI Dashboard, Importação de Dados, Entrada de Produtos por Compra, Contas a Pagar/Pagas, Movimentação Bancária, Integração com Marketplace), todas como páginas `EmBreve` sem lógica; o botão de fechar (✕) foi removido das páginas-hub de grupo do menu (não fazem sentido como "fechar", são ponto de entrada); e nasceu **Configuração de Etiqueta de Produtos** (V029 + tela, `docs/telas/configuracao-etiqueta.md`) — cadastro com editor visual de arraste (régua em mm, código de barras real via `jsbarcode`, produto de exemplo real), agora em Configurações (não mais Implementações Futuras). Ver linha do tempo de 2026-08-04 (topo, entradas mais recentes primeiro) pro detalhe completo. Tudo commitado e pushado ao final de cada sessão do dia.

**Sessão de 2026-08-05:** Configuração de Etiqueta passou por três rodadas de refinamento (campos 10→4 concatenados na Descrição, máximo de colunas do rolo 6→4, cabeçalho em 3 cartões, botão **Testar Impressão**, código de barras `EAN13` de verdade, quadro no Teste de Impressão, painel de propriedades virou popup — `docs/telas/configuracao-etiqueta.md`) mais dois bugs reais corrigidos em Produto (margem de venda acima de 100% rejeitada; busca de produto de exemplo quase sempre vazia). Nasceram duas telas novas no grupo **Relatórios**, saindo de Implementações Futuras: **CRM** (`cadastros.crm`, `docs/telas/crm.md`) — filtra clientes por perfil e histórico de compras, exporta 11 colunas pra planilha Excel (`write-excel-file`) — e **Emissão de Etiqueta de Produtos** (`configuracao.etiquetaemissao`, `docs/telas/etiqueta-emissao.md`) — 3 formas de selecionar produtos (Individual, com criação automática de SKU e obrigatoriedade de variação por produto; Por Entradas; Por Estoques), grade local editável, imprime em lote com produto diferente por etiqueta, reaproveitando o layout já configurado em Configuração de Etiqueta. Nasceu `ProdutoBarraService` (`com.vetor.niner.catalogo`) — acha-ou-cria uma variação de produto chamando `gerar_ean13_interno()`, primeira peça de domínio real sobre `produto_barra`. Ver linha do tempo de 2026-08-05 (topo) pro detalhe completo de cada rodada. Suíte de backend: **386 testes**.

**Sessão de 2026-08-08:** o modelo de variação de produto foi revisado a pedido do dono do
produto — o par genérico "variante em linha/coluna" (rótulo livre por produto) não resolvia bem o
caso de calçados/confecções, que variam por **cor × grade** (uma curva de tamanhos nomeada e
ORDENADA, ex. "Grade 36-44"). Nasceram `cfg_cor`/`cfg_tamanho`/`cfg_grade` (esta com 20 colunas
fixas de slot, mantendo a ordem sem precisar de tabela filha), `produto.id_grade` substituiu
`nome_variante_linha`/`nome_variante_coluna`, `produto_barra.id_cor`/`id_tamanho` substituíram
`id_variante_linha`/`id_variante_coluna`, e `cfg_geral` ganhou um único `cfg_usa_cor_grade`
(era duas flags). Cor nasce sem tela própria (válvula de escape via Emissão de Etiqueta) até a
tela de Entrada de Produtos existir; geração em lote de todas as combinações cor×grade também
ficou para essa tela futura. Consumido por `ProdutoBarraService` (regra: grade presente ⇒ cor E
tamanho obrigatórios, tamanho restrito à grade do produto), por `GradeModal.tsx` (novo, popup
"＋ Gerenciar Grades" embutido no formulário de Produto) e pela Rotina de Importação de Dados
(o antigo passo de "detectar e confirmar rótulo de variante" foi removido — cor/tamanho têm nome
fixo agora, `ProdutoImportador` resolve a grade por nome via `GradeService.obterOuCriarPorNome`).
**Durante os testes desta feature, um achado sério e não relacionado:** uma query que dependia só
da política RLS (sem `id_tenant` explícito no SQL) vazou/alterou uma linha de outro tenant, de
forma reproduzível — testado também contra código antigo intocado (`CategoriaProdutoService`),
que vazou do mesmo jeito. A pedido explícito do dono do produto ("o que é de um tenant só pode
ser visto no próprio tenant"), uma auditoria cobriu `api/src/main` inteiro (3 buscas em
paralelo) e todo `service`/`controller` sem o filtro explícito foi corrigido — inclusive
`SignupService.login()`, onde o risco era autenticação cross-tenant, não só leitura indevida.
Detalhe completo, causa suspeita e a lista de arquivos corrigidos:
`docs/infra/isolamento-tenant-rls.md`; convenção nova registrada em `CLAUDE.md`. Depois, ao subir
o ambiente pra teste manual (banco de dev recriado, API reconstruída, seeds recarregados), uma
pergunta direta do dono do produto sobre a impressão de etiqueta motivou uma varredura completa
atrás de rótulos/comentários/specs que a renomeação mecânica tinha deixado pra trás (~15 arquivos,
incluindo uma asserção de teste que checava um nome de campo que nunca existiu de verdade). Suíte
de backend depois de tudo: **405/405 verdes**. Ver linha do tempo de 2026-08-08 (topo) pro detalhe
completo.

**Sessão de 2026-08-10:** a Rotina de Importação de Dados passou por uma reformulação grande, em
várias rodadas, testando com os arquivos reais do dono do produto. Primeiro, dois bugs pontuais:
upload de arquivo grande (`CONTAS_RECEBER.csv`, 26,6 MB) batia no limite de 10 MB herdado do upload
de foto de produto — corrigido junto com um achado colateral (um `413` do Tomcat some antes do
filtro de CORS, então o front mostrava "Ocorreu um erro." genérico em vez do motivo real); depois,
a pedido explícito, o limite foi removido de vez (`max-file-size`/`max-request-size: -1`) — não há
mais teto de tamanho de arquivo nem de número de linhas em nenhuma importação. E a importação de
Fornecedores parou de validar formato do campo `TELEFONE` (`FornecedorService.criar` ganhou uma
sobrecarga `validarTelefone=false`, só usada pela importação — o cadastro manual continua
validando). Na sequência, o **formato do CSV de Produtos mudou de estrutura na origem**: em vez do
antigo "achatado" (uma linha = uma variação, com `NOME_GRADE`/`DESCRICAO_COR`/EAN/estoque),
**uma linha agora é um produto inteiro**, com `TAMANHO_1..20` formando a grade direto nas colunas —
`ProdutoImportador` foi reescrito (a grade "já existe" passou a ser definida pelo CONTEÚDO da
sequência de tamanhos, não por nome, via `GradeService.obterOuCriarPorTamanhos`, novo) e uma coluna
nova, `produto.codigo_importacao` (editada na V017, não é o `id_produto`), passou a guardar o
`CODIGO_PRODUTO` da planilha de origem. Isso abriu caminho pra uma **5ª tabela de importação,
Estoque** (`EstoqueImportador`, novo — sempre depois de Produtos, liga pelo `codigo_importacao`,
resolve cor/tamanho/EAN/quantidade por empresa igual o antigo formato de produto fazia). Por fim, a
tela virou **uma tela única**: o usuário localiza quantos arquivos quiser de uma vez, o tipo de
cada um é **identificado automaticamente** (`POST /api/v1/importacao/detectar`, por similaridade de
Jaccard contra o cabeçalho de cada tabela — 100% de acerto nos 5 arquivos reais testados), cada
arquivo ganha um cartão com "Validar" próprio, e um botão único "Importar" só libera quando tudo
estiver validado sem erro — com uma exceção deliberada pra Estoque/Contas a Receber poderem
depender de Produtos/Clientes **na mesma leva** (mostra "Pendente" em vez de erro, checagem de
verdade acontece na hora certa da importação sequencial). **Bug real encontrado testando com os
arquivos grandes de verdade:** a tela nova não conferia `relatorio.confirmado` antes de marcar um
arquivo como importado — como o sistema é "tudo ou nada" por arquivo, uma chamada com
`confirmar=true` e alguma linha com erro real volta `200 OK` com `confirmado:false` e **nada
gravado**, e a tela mostrava "Importação concluída" mesmo assim; corrigido, e nesse processo
descoberto que o par de arquivos de exemplo (`ESTOQUES.csv`/`PRODUTOS.csv`) tem 357 linhas com
`CODIGO_PRODUTO` que não existe em nenhum dos dois — dado real do lojista, não bug. Zerado o banco
de dev a pedido (recriado do zero) e semeado com 5 empresas, 5 vendedores e o plano de contas
padrão completo (336 contas, script já existente). Suíte de backend: **405/405 verdes**. Ver linha
do tempo de 2026-08-10 (topo) pro detalhe completo.

**Sessões de 2026-08-10 — Rotina de Importação de Dados, reformulação + correções de produção:**
a tela única multi-arquivo virou um **hub com 5 telas dedicadas** (uma por tabela, ordem de
dependência: Clientes → Contas a Receber → Fornecedores → Produtos → Estoque Inicial), o formato
de arquivo trocou de CSV para **planilha Excel nativa** (`.xlsx`/`.xls`, Apache POI). Ainda no
mesmo dia, testando com dados reais e grandes de verdade (planilha de Contas a Receber com 660 mil
linhas), apareceram e foram corrigidos **3 bugs de produção genuínos**: (1) uma exceção de banco
numa linha (não só erro de validação) deixava a transação "abortada" e derrubava em cascata toda
linha seguinte do arquivo (Postgres `25P02`) — corrigido com **SAVEPOINT por linha**
(`ImportacaoSavepointExecutor`, `PROPAGATION_NESTED`) nos 5 importadores; (2) a leitura de `.xlsx`
carregava o arquivo inteiro em memória (DOM) antes de processar a primeira célula — pra um arquivo
grande isso é a maior parte do tempo, "muda" pro progresso — corrigido trocando pra **leitura em
streaming (SAX)**, só pra `.xlsx` (`.xls` legado, capado em 65.536 linhas pelo próprio formato,
ficou em DOM); (3) `ContasReceberImportador`/`EstoqueImportador`/`ProdutoImportador` faziam uma
consulta ao banco **por linha** pra achar cliente/empresa/produto/cor/tamanho, sem cache — numa
planilha repetindo o mesmo valor em centenas de milhares de linhas (o caso normal), isso virava
uma consulta redundante por linha; corrigido com **cache local por chamada** nos 3 importadores.
Resultado medido: 300 mil linhas repetindo o mesmo CPF, que antes ficava mais de 1 minuto travada
sem previsão de terminar, agora completa em 3min25s. Também nesse período: **progresso "ao vivo"**
(registro atual/total real, não simulado) nas 3 etapas leitura/validação/importação, via polling
(`GET /importacao/progresso/{id}`) + `ScopedValue` no backend (mesmo padrão de `TenantContext`);
zip-bomb do Apache POI dando falso positivo em planilha grande e legítima; e-mail/CNPJ inválidos
do Fornecedor deixaram de rejeitar a linha (entram em branco, mesmo espírito do TELEFONE); e
exclusão mútua de empresa nas 5 colunas de quantidade da Importação de Estoque (com mínimo de 1
mapeada, não mais as 5). **Na continuação da mesma sessão**, testando com os arquivos reais
completos (~22 mil linhas cada): dedup de Produto passou a considerar `CODIGO_PRODUTO` além de
descrição+marca+referência (14 pares de produtos diferentes estavam sendo tratados como
duplicata); tamanho fora da grade do produto deixou de ser erro na importação de Estoque; uma 2ª
rodada de otimização (pré-busca em lote no `ProdutoBarraService`) derrubou o tempo de validação de
7min46s pra 59,7s (~7,8x) num arquivo real de 22.483 linhas; e a tela de **Exportação de Dados**
ganhou o mesmo `GaugeProgresso` (modo simulado, sem loop linha a linha no backend pra progresso
real). Tudo testado ao vivo (planilhas geradas com `write-excel-file`, dados conferidos no banco,
não só o relatório da API) — ver linha do tempo de 2026-08-10 (topo) pro detalhe completo.

**Sessão de 2026-08-07:** o comprovante de Recebimento de Crediário e a Papeleta de Venda (com suas reimpressões) ganharam **envio por WhatsApp** (`comum.arquivocompartilhado` — cache de PDF em memória, sem custo, sem object storage, token expira em 24h, limite de 20 arquivos/tenant somando os 4 fluxos) e **layout fixo** (título/rodapé sempre visíveis, só a pré-visualização rola). Na sequência, a tela de **CRM** passou por duas rodadas revisando o fluxo original de 2026-08-05: primeiro ganhou uma **grid de resultado** (cabeçalho fixo, ordenação por coluna, total, scroll só nos dados), depois os filtros/colunas viraram um **popup obrigatório que abre sozinho ao entrar na tela** (com `GaugeProgresso` — reaproveitado da Rotina de Importação de Dados — enquanto a busca roda) e o botão "Gerar Planilha Excel" subiu pra linha do título. Numa terceira sessão do mesmo dia — recuperada depois que o terminal foi fechado sem pedido explícito de commit/documentação, mas com todo o código intacto no working tree —, o PDV passou a **validar limite de crédito do cliente** no crediário (`cliente.limite_credito`, campo que existia desde a V016 mas nunca era checado) e o comprovante de vale-mercadoria da devolução foi **padronizado com o layout de 64 colunas/Lucida Console da Papeleta de Venda**, ganhando também "Enviar por WhatsApp"; erros de confirmação de venda passaram de `Toast` para um popup dedicado (`AvisoModal`). Ver linha do tempo de 2026-08-07 (topo, três entradas) pro detalhe completo.

**Sessão de 2026-08-10 (nova, depois da reformulação da Importação de Dados):** nasceu o
**Cancelamento de Devolução de Produtos** (`vendas.cancelamentodevolucao`,
`docs/telas/cancelamento-devolucao-produtos.md`) — desfaz um vale-mercadoria ainda não usado,
retirando do estoque a quantidade que a devolução original tinha colocado de volta
(`tipo_movimento = 'CANCELAMENTO_DEVOLUCAO'`, novo). Tela em Frente de Loja → Cancelamentos com
popup obrigatório de filtros (nº do vale ou período); ADMIN e OPERADOR, mas **OPERADOR restrito à
empresa em que está logado** — diferente de todas as outras telas ADMIN+OPERADOR do sistema, que
não têm esse recorte. Motivou correção de consistência em código já existente: `PdvVendaService`
e a busca de vale usada pelo PDV (`DevolucaoProdutoService.buscarVale`) passaram a bloquear
também um vale **cancelado**, não só usado. Suíte de backend segue **405/405 verdes**
(nenhum teste novo — pendência registrada na spec da feature, ver linha do tempo).

**Sessão de 2026-08-11 (continuação):** dois ajustes pequenos na tela nova. O popup obrigatório
de filtros do Cancelamento de Devolução ganhou um botão **"Fechar"** (`navigate(-1)`) — sem ele
não havia como sair da tela sem confirmar os filtros primeiro, já que o popup cobre o ✕ da
topbar. E foi corrigido um bug real no gerador de PDF dos comprovantes térmicos
(`web/src/lib/comprovante.ts`): o `jsPDF`, em orientação retrato, troca largura por altura sempre
que `largura > altura` — um comprovante curto (Vale-Mercadoria com poucos itens é o caso comum)
tinha a altura calculada abaixo de 80mm e saía com a página invertida, cortando as colunas da
direita. Corrigido com um piso de 80mm na altura, nas três variantes que geram PDF (crediário,
papeleta de venda, vale). Detalhe técnico em `docs/telas/papeleta-venda.md`.

**Sessão de 2026-08-11 (continuação 2):** a Devolução de Produtos ganhou **restrição a produtos
vendidos** — pedido explícito do dono do produto, fechando um non-goal que a spec original
deixava aberto. Com o número da venda informado, o vendedor passou a ser buscado
**automaticamente** (sem botão) e a tela só aceita devolver produtos que a venda vendeu, até a
quantidade ainda não devolvida — validado no servidor, não só na tela. Ganhou também um novo
**parâmetro em Parâmetros do Sistema** ("Exigir número da venda na Devolução de Produtos",
`cfg_geral.cfg_exige_numero_venda_devolucao`, default desligado) que torna o campo obrigatório
quando ligado, e o **foco inicial da tela** passou a depender dessa configuração (número da
venda quando obrigatório, código de barras quando opcional). No meio do caminho, um bug real de
cache do React Query foi encontrado e corrigido: salvar em Parâmetros do Sistema não invalidava
o cache das flags leves usadas por outras telas, então a Devolução de Produtos podia continuar
mostrando o comportamento antigo depois de mudar a configuração, se o usuário navegasse (SPA,
sem recarregar a página) em vez de dar F5. Suíte de backend: **410/410 verdes** (5 testes novos
nesta sessão: 4 da restrição a produtos vendidos + 1 da obrigatoriedade condicional). Detalhe
completo em `docs/telas/devolucao-produtos.md` e `docs/telas/configuracao-geral.md`.

**Sessão de 2026-08-11:** retomada da spec pausada **Entrada de Produtos por Compra**
(`docs/telas/entrada-mercadoria.md`) — décima primeira tela de domínio, módulo `estoque`.
Fluxos **Manual** e **Planilha** implementados (confirmação comum grava o ledger existente,
V019, saldo sobe por trigger); **Fluxo XML e atalho de emissão de etiqueta ficaram pendentes
nesta sessão** (fases 3 e 5 — fase 3 completada em 2026-08-12, ver abaixo). Rateio de
frete/reajuste de preço configuráveis em Parâmetros do Sistema,
`contas_pagar` opcional a partir de parcelas informadas, plano de contas de compra dedicado
(`cfg_geral.id_plano_contas_compra_mercadoria`, V032), vínculo `produto_fornecedor` (V031) pra
acelerar o match de importações futuras, cadastro rápido embutido de produto/fornecedor/NCM, e
escolha de empresa (ADMIN: todas do tenant; OPERADOR: só as liberadas,
`GET /api/v1/empresas/permitidas`). Ver linha do tempo e `docs/telas/entrada-mercadoria.md`
para o estado exato do que falta.

**Sessão de 2026-08-11 (continuação 3):** **Horário de Acesso por Dia da Semana**, adicionado ao Cadastro de
Usuários (`docs/telas/usuario.md`) — restringe quando um `OPERADOR` pode logar/trabalhar, com
janela por dia da semana e checkbox `controla_horario_acesso` (ADMIN nunca é afetado). Login
sem tolerância; chamadas autenticadas com **15 minutos** de tolerância (pra não cortar uma
venda no meio do PDV) e um aviso visual — anel circular com contagem regressiva no canto
superior esquerdo — nos minutos finais, usando um novo mecanismo genérico de "rotina crítica em
andamento" (`web/src/lib/rotinaCritica.tsx`) que adia o logoff automático até o PDV terminar a
venda em curso. Dois bugs reais corrigidos na verificação manual: `ResponseStatusException` sem
corpo Problem Details (afetava também "Credenciais inválidas." do login) e `time + interval` do
Postgres embrulhando na virada da meia-noite (quebrava a tolerância pra expedientes que fecham
nos últimos 15 minutos do dia). Suíte de backend: **444/444 verdes**. Ver linha do tempo e
`docs/telas/usuario.md`, seção "Horário de acesso por dia da semana".

**Sessão de 2026-08-12:** retomada da Entrada de Produtos por Compra para fechar o **Fluxo
XML** (Fase 3, pendente desde 08-11) — upload da NF-e primeiro, fornecedor/empresa/nota/
parcelas resolvidos automaticamente a partir dela, cadastro rápido de fornecedor pré-preenchido
quando não há match por CNPJ, matching de item por EAN → código do fornecedor aprendido →
heurística de texto → pendência manual, cor/tamanho sempre exigindo confirmação do operador.
Testado com 2 NF-es reais. Com isso a feature fica completa (só falta a Fase 5, não bloqueante).
Na sequência, mesma leva de sessões: **Cancelamento de Entrada de Produtos** (ícone na grid,
ADMIN-only, mesmo padrão de Cancelamento de Venda/Devolução — estorna estoque e apaga as
`contas_pagar` da entrada) e **Filtros da listagem de Entrada de Produtos** (popup obrigatório
+ correção de um bug real de fuso horário em filtro de data). Ver `docs/telas/
entrada-mercadoria.md` para o desenho completo dos três.

**Sessão de 2026-08-12 (continuação):** nova tela **Contas a Pagar / Pagas**
(`docs/telas/contas-pagar.md`, novo) no módulo Financeiro — CRUD completo sobre a tabela
`contas_pagar` (V026), que até aqui só era escrita internamente pela Entrada de Produtos.
Popup de filtros obrigatório (Fornecedor, Empresa, Nota Fiscal, Duplicata, período de
Vencimento/Pagamento); não existe ação de "Pagar" separada — editar a conta preenchendo Data de
Pagamento/Valor Pago/Documento Pago é a baixa. Dois bugs pegos só testando ao vivo no navegador
(coluna Duplicata esquecida na grid; mensagem de erro fantasma no campo Fornecedor depois de
escolher um pelo typeahead), ambos corrigidos. Suíte de backend: **472/472 verdes** (11 testes
novos, `ContaPagarCrudTest`). Ver `docs/telas/contas-pagar.md`.

**Sessão de 2026-08-13:** três entregas independentes. (1) **Fix real** — o cadastro rápido de
fornecedor embutido na Entrada de Produtos por Compra (`FornecedorQuickCreateModal`) só
preenchia o plano de contas padrão de compra quando quem estava logado era `ADMIN`, porque
reusava o endpoint completo de Parâmetros do Sistema (`GET /api/v1/config-geral`, ADMIN-only) só
pra ler um campo — `OPERADOR` recebia 403 silencioso e o campo ficava vazio. Corrigido com um
endpoint novo sem checagem de papel, `GET /api/v1/config-geral/plano-contas-compra-mercadoria`
(mesmo padrão de `/usa-cor-grade`/`/rateia-frete-entrada`), reproduzido e confirmado ao vivo
(403 → 200 pro mesmo usuário OPERADOR). (2) **Foco automático** adicionado a 15 telas de lista/
localização (Pesquisa de Vendas, Recebimento de Crediário, Reimpressão de Papeleta de Venda/
Recebimento, Transferência de Produtos, Conta Corrente, Movimentação de Conta Corrente, Tipo de
Carteira, Contas a Pagar, Clientes, Fornecedores, Funcionários, Produtos, Usuários, Configuração
de Etiqueta) e espaçamento vertical corrigido entre os botões da Exportação de Dados. (3) **Plano
de Contas simplificado** (pedido do dono do produto, "código muito grande pra pequeno negócio"):
máscara encurtada de `9.99.999.999` (4 níveis, ~200 contas no plano padrão) para `9.99.999`
(3 níveis — grupo.família.conta, 76 contas no plano padrão), com DRE/fluxo de caixa continuando
100% independentes da máscara (grupo+sinal, inalterados); banco de dev convertido ao vivo sem
perder nenhum dado (fornecedores e configuração geral remapeados do código antigo pro novo antes
do DROP das colunas geradas antigas). Componente novo `SeletorPlanoContas` (busca por prefixo de
código OU por nome, combobox único) substituiu os `<select>` truncados em Fornecedor, Contas a
Pagar, Movimentação de Conta Corrente, Parâmetros do Sistema e Importação de Dados. Na sequência,
removidas por completo — tela, schema e banco — as seções "Exigências no lançamento"
(`exige_centro_custo`/`exige_contraparte`/`exige_documento`) e "Integrações"
(`id_conta_contabil`/`id_plano_referencial`): nenhum lançamento do sistema as lia, não afetam
DRE/DFC. Suíte de backend: **474/474 verdes** (mesma contagem — testes de hierarquia convertidos
de 4 para 3 níveis, nenhum teste novo/removido). Ver `docs/telas/plano-contas.md` (atualizado)
e a linha do tempo abaixo para o detalhe técnico completo. Uma auditoria posterior, no mesmo
dia (pedido repetido de "documente/memorize tudo"), achou e corrigiu referências cruzadas
esquecidas em specs de outras telas, e fechou os 2 filtros de listagem (Fornecedores,
Movimentação de Conta Corrente) que ainda não tinham migrado pro `SeletorPlanoContas`.

---

## Linha do tempo

### 2026-08-25 (4) — a rodada de interface: padrão de fechar, layout do orçamento e o nome do produto

Seis mudanças pedidas pelo dono do produto, todas conferidas **no navegador** — nenhuma delas
seria provada por compilar.

#### ✕ no canto superior direito, em todo popup

*"Para as telas ficarem no mesmo padrão."* A varredura achou **39 botões "Fechar" em 37 arquivos**,
em **cinco marcações diferentes** (`btn ghost`, `btn`, `btn-secundario`, `btn-secondary` e uma com
espaçamento próprio) — o que acontece quando o padrão vive na memória de quem escreve.

Por isso a saída **não** foi repetir a marcação 39 vezes: nasceu o **`CabecalhoModal`** (título +
✕ com os rótulos de acessibilidade certos), usado por **34 popups**. Um componente torna a
divergência impossível de escrever sem querer, que é o que o pedido pede de verdade.

**A regra que fica:** o rodapé do popup é para a **ação que a tela existe para fazer**. Fechar não
é ação de negócio — é saída, e saída mora no ✕.

⚠️ **O codemod criou ✕ duplicado em 2 arquivos** que já tinham um (`DanfeModal`,
`PesquisaProdutoEntradaModal`) — ele trocou o `<h2>` que estava *dentro* de um cabeçalho pronto.
Corrigidos à mão; a conferência final varre os 166 `.tsx` procurando duplicata e sobra: 0 e 0.

⏭️ **39 popups ficaram de fora** porque nunca tiveram "Fechar" — e eles **não são todos iguais**:
os de *trabalho* (Filtros do Relatório, Nova cor, Forma de Pagamento…) merecem o ✕; os de
*confirmação* ("Excluir cliente?", "Salvar dados?") **não**, porque ali o ✕ é ambíguo — "Cancelar"
já É a saída, e em "Salvar dados?" fechar não diz se salva ou descarta. Decisão pendente.

#### Novo Orçamento: campo removido e a página parou de rolar

Campo **Observações** saiu da tela. ⚠️ A **coluna fica no banco** e continua exibida na impressão e
no detalhe — orçamento antigo não perde texto. Deu para remover sem risco porque o orçamento é
**imutável** e o formulário é só de criação (só existe `/orcamentos/novo`): não há caminho de
edição que pudesse gravar `null` por cima.

Depois, com a tela marcada em imagem: cabeçalho mais baixo, grade mais compacta, desconto menor e
ao centro, "Emitir Orçamento" na mesma linha à esquerda.

⚠️ **A altura da grade deixou de ser número fixo.** Havia `maxHeight: 380` — número mágico quebra
na primeira janela de tamanho diferente. Hoje a seção é `flex: 1` e absorve o que sobra;
**`min-height: 0`** é o que permite o item flex encolher abaixo do conteúdo (sem ele a tabela
empurra o container e a página volta a rolar). Medido com 12 itens: página não rola.

#### F5 do PDV

✕ no canto nos dois estados · "Levar para a venda" só aparece **depois de abrir** um orçamento ·
grade sem scroll horizontal · cabeçalho fixo.

⚠️ **O cabeçalho fixo quase passou batido:** `.table th` **já era** `position: sticky` — e não
funcionava, porque quem rolava era o **`.modal` inteiro**, e sticky gruda no scroller mais próximo.
O título e o ✕ saíam de vista. O modal virou coluna flex com um **miolo** como único scroller; a
grade **perdeu** o scroll próprio de propósito (dois scrollers aninhados fariam o `th` grudar no
de dentro).

#### A marca na tela dizia NINER

O produto se chama **Nainer**. O site público e o backoffice já estavam certos; só o ERP ficou para
trás, no cabeçalho e nas duas ocorrências do login.

⚠️ **Não aparecia numa busca por "NINER"**: a marca é renderizada **partida** —
`NI<span>NER</span>`, para o CSS pintar as metades de cores diferentes. `grep` devolveu 15 acertos
e **nenhum era tela**. Foi olhando o navegador que o problema apareceu.

⛔ Ficaram de fora, de propósito: `NINER_API_BASE` (configuração de runtime), chaves de
`localStorage` (renomear **desloga todo mundo**), nomes de evento, pacote Java, banco `niner_db`. E
os nomes de cookie listados na página de **Privacidade**: aquilo é texto visível, mas é a
*declaração do nome real do cookie* — trocar sem trocar o cookie seria informação falsa.

#### Menu: 3 itens prometiam "Em construção" para função pronta

O subgrupo "Módulo Fiscal" oferecia quatro telas; **três já existem em outro lugar** (NFC-e e NF-e
saem do PDV, o cancelamento fica em Documentos Fiscais). Item de menu assim é pior que item
ausente: manda o lojista procurar onde a coisa não está.

⚠️ **A quarta ficou** — e conferi antes de apagar: **"Exportação de XML" não existe**, nem tela nem
endpoint de exportação em lote. É lacuna real. Renomeada para "Exportação de XML em Lote", com a
descrição dizendo onde baixar o XML de uma nota. ⚠️ E o `MODULOFISCAL.md` §11.2 lista
`fiscal.download` como se existisse — **não existe**.

#### `docs/TELAS.md` — inventário completo

**56 telas em uso · 6 em construção · 20 telas-filhas**, gerado do código (`menu.ts` + `App.tsx`),
com rota e spec de cada uma. ⚠️ O casamento com a spec **errou 4 vezes** na primeira geração,
sempre preferindo o arquivo de nome mais longo (PDV → `cancelamento-venda.md`). Corrigidos, e todos
os caminhos conferidos no disco. ⚠️ **Duas telas sem spec nenhuma: Efetivar Balanço e Tipo de
Carteira.**

### 2026-08-25 (3) — começou a integração com marketplaces: estudo, decisões e quatro blocos

O coração da visão original do produto (P1/P2, R3–R7) saiu do papel. `canais/`, `pedidos/`,
`precos/` e `integracao/` tinham só `package-info.java` desde julho; o schema (V020–V022) existia
e nunca fora usado.

**Estudo primeiro** (`docs/MODULOMARKETPLACE.md`), como manda o repositório: o que o ML exige, três
colisões com o ERP que já existe, sete perguntas, e três opções de escopo. ⚠️ Os fatos da API do ML
vieram de **documentação lida**, não medida — o portal deles responde 403 a leitura automatizada, e
este projeto já sabe que documentação lida não é comportamento medido.

#### As decisões do dono do produto

| Decisão | Consequência |
|---|---|
| **Pedido de marketplace VIRA `venda`** | custou **um valor de enum** (V063): `venda.id_caixa`, `venda.id_cliente` e `produto_movimento_detalhe.id_funcionario` já aceitavam NULL, então "sem caixa" e "sem comissão" saíram de graça |
| **Escopo A + B**; NF-e 55 depois | a Opção C espera o `cStat 974` |
| ⛔ **"Se vende em marketplace, não pode existir estoque negativo"** | **dois** guardas, não um |
| **Preço próprio por canal, maior OU menor** | V064: `canal.perc_preco` (aceita negativo) + `anuncio.preco_manual` |
| **Preço em oferta não acompanha** | a regra lê `preco_venda`; virou proibição explícita no javadoc |
| Cota do plano: **conta** (recomendação minha) | direção cujo arrependimento é barato — reduzir depois é presente, aumentar é churn |

⚠️ **O segundo guarda de estoque é o que se esquece:** não basta barrar a conexão do canal; é
preciso barrar o **religar do parâmetro** enquanto houver canal conectado. Só o primeiro seria
trava decorativa — a loja conectaria com o controle ligado e religaria no dia seguinte, com o
anúncio seguindo em frente prometendo estoque que não existe.

#### O que foi construído (997 testes verdes)

- **M0** — `CanalDeVenda` (a anti-corrupção), `CredenciaisCanal` (com `toString` que nunca imprime
  token), `PrecoDoCanal`, os dois guardas.
- **Adapter do Mercado Livre contra WireMock.** ⛔ A armadilha central: no ML, um `PUT` em
  `variations` **apaga as que não forem enviadas**. O adapter lê o anúncio antes de escrever e
  reenvia todas com seus `id`. WireMock (dependência nova, escopo de teste) entrou porque o defeito
  é de **payload** — mock de interface passaria por ele sem ver — e porque **o ML não tem sandbox**:
  os testes reais rodam na produção deles, com usuários de teste limitados a 10 e que expiram.
- **Worker do outbox** — `FOR UPDATE SKIP LOCKED`, backoff 1min→1h, dead-letter. Agendador e
  trabalho em **beans separados** (auto-invocação não passa pelo proxy e roda sem transação, o que
  soltaria o lock). ⚠️ `outbox_evento` tem RLS: sem `TenantContext` a fila sai **vazia sem erro**.
- **Tela de Canais de Venda** com painel de saúde (R7), conferida no navegador. Saiu de
  "Implementações Futuras" para Configurações.

#### Três coisas que eu errei e valem mais que o que acertei

1. **Documentei um número que não conferi.** O javadoc do backoff dizia "10 tentativas ≈ 6 horas";
   a soma real de 10 é **4h03**. Quem pegou foi o teste que soma a janela inteira — escrito
   justamente porque número desses apodrece calado. Hoje são 12 (6h03) e o teste afirma o valor
   **exato**.
2. **Um teste meu ficou PULADO e eu quase não vi.** O que prova que não se exclui canal com anúncio
   vinculado usava `assumeTrue` sobre massa que não existe em tenant novo — o guarda nunca era
   exercitado. Teste pulado conta como verde e **não prova nada**; passou a criar a própria massa.
3. **Quase "consertei" um defeito que não existia.** O Toast do erro não aparecia nos meus
   screenshots e eu já suspeitava do front que engole mensagem do back. Medindo o DOM, o Toast
   estava lá com o texto certo: os **6 segundos de auto-fechamento** passavam entre o clique e a
   captura. A ferramenta era lenta, não o código.

#### ⏸️ Onde parou

**M1 (OAuth) espera o `client_id`.** A MITRYUSCASH precisa de uma conta ML **pessoa jurídica** com
o titular validado (upload de documento — é a parte que demora), e então criar a aplicação. ⚠️ **No
Brasil o ML permite só 1 aplicação por conta**, então dev e produção compartilham o mesmo
`client_id`; a mitigação é registrar várias URLs de redirect. Não existe "conta de integrador": é
conta comum, e quem vende é o lojista, que autoriza a aplicação na conta dele.

### 2026-08-25 (2) — o 974 fechado do nosso lado (chamado na SEFAZ), e três defeitos que um documento sem chave revelou

#### 🔴 `cStat 974` — chamado aberto, bola com a SEFAZ/PR

Todas as hipóteses foram **medidas** e descartadas, e o chamado está escrito em
`docs/fiscal/chamado-sefaz-pr-csrt-974.md`. Detalhe completo em `docs/MODULOFISCAL.md` §974.

⚠️ **A entrada de ontem (08-24, noite, 7) estava errada no método, não no fato.** Ela afirmava
*"o CSRT correto não resolveu o 974"* — mas a retransmissão citada rodou às **08:14** e o CSRT
conferido só foi gravado depois. **Concluir um fracasso a partir de um teste anterior à correção**
é o espelho de concluir um sucesso sem reproduzir o sintoma, e custa mais caro: fecha uma hipótese
verdadeira. A afirmação virou verdadeira hoje às 08:55, quando a primeira transmissão com o CSRT
conferido voltou 974 — aí sim medido.

O que foi descartado, em ordem, cada um com evidência: **`idCSRT` `01` × `1`** (o XSD exige 2
dígitos, `<xs:pattern value="[0-9]{2}"/>`); **CNPJ do responsável técnico** (o portal mostra
`37.829.453/0001-35`, idêntico ao transmitido); **CSRT de produção em homologação** (o portal só
tem token de homologação); **cálculo do hash** (hash errado dá `cStat 976`, não 974 — ⚠️ este é
leitura da NT, o 976 nunca foi observado aqui); e **propagação**, derrubada pelo teste decisivo:
foi solicitado um **segundo token (Id 2) sem revogar o primeiro**, e uma nota transmitida minutos
após a ativação falhou idêntico.

A prova que isola o problema: a **NFC-e 65 do mesmo emitente, mesmo ambiente, mesmo certificado,
autoriza** (`cStat 100`) — ela não exige CSRT no PR, então nunca exercita esse caminho. Os dois
modelos respondem inclusive por aplicações diferentes da SEFAZ-PR (`PR-v4_5_39` × `PR-v4_9_87-2`).

#### Documentos Fiscais ficava em branco — e eram três defeitos, não um

Relatado como *"a tela fica toda em branco e não faz nada"*. Era **crash de render**: exceção no
render do React **apaga a página inteira**, e o sintoma soa como falha de rota ou de API. O console
do navegador deu arquivo, linha e stack em um passo.

Causa: 3 documentos `NAO_EMITIDO` (do bloqueio preventivo F11, durante o trabalho da NF-e 55 em
24/08) têm `serie`, `numero` e `chave_acesso` **NULL**, e a tela nunca tinha visto essa linha.
Bastava um cair no período filtrado. Os três defeitos, em camadas diferentes:

1. **Front:** `item.chaveAcesso.slice(-8)` com nulo → `TypeError` → tela em branco.
2. **Back, silencioso:** o DTO declarava `int serie, long numero` (**primitivos**) sobre colunas
   nullable — o driver converte NULL em **0 sem erro nenhum**, e a lista exibiria **"0/0"** como se
   fosse número real de nota. Hoje `Integer`/`Long` via `getIntOuNulo`/`getLongOuNulo`.
3. **Back, grave:** "Consultar SEFAZ" num documento sem chave montava `<chNFe>null</chNFe>` e
   **transmitia de verdade ao fisco**, colhendo `cStat 215`. Agora recusa com **409 antes de
   qualquer envio**, e o guard está **no serviço**, não só escondendo o botão — o front não é o
   único cliente da API.

⚠️ **A armadilha ao escrever o teste:** a primeira versão do teste do 409 **passaria com o defeito
presente** — sem certificado no tenant de teste, a consulta morreria no passo *seguinte* ao guard e
o transporte também não seria chamado. Só vale com o certificado enviado antes. Os dois testes
foram conferidos **revertendo as correções**, e reprovam: o do zero silencioso com a mensagem
`Expected no value at "$.itens[0].serie" but found: 0`.

#### Popup de emissão do PDV: a escolha trocava o modelo em silêncio

Levantado pelo dono do produto. O popup dizia "CPF" mesmo com CNPJ na tela, e — mais sério — os
dois botões produzem **documentos fiscalmente diferentes** para cliente PJ (identificar → NF-e 55;
não identificar → NFC-e 65) sem nenhuma indicação. Quem clicasse em "não" só para escapar do 974
emitiria NFC-e para uma empresa que precisa da nota para crédito, que é justamente o que a regra
PJ→55 existe para evitar. Rótulo agora acompanha o cliente e os botões nomeiam o modelo. ⛔ A tela
**não segue o padrão do projeto** e será reformulada — o que foi feito aqui é corretivo.
⚠️ **Não conferido em navegador:** o popup só aparece depois de uma venda.

**935 testes verdes** (eram 933), `tsc -b` limpo.

### 2026-08-25 — Relatório de Lucratividade (o último item de "Implementações Futuras" em Relatórios)

Pedido do dono do produto com a estrutura de impressão já definida por ele: venda, custo do
vendido, lucro bruto e %, contas pagas por plano de contas com dois percentuais, e lucro líquido
com % sobre venda bruta e líquida. Spec em `docs/telas/relatorio-lucratividade.md`, escrita antes
do código.

**⚠️ O período tem TRÊS naturezas.** Crédito (venda, devolução, custo) conta pela **data da
venda**; conta paga, pela **data de pagamento** do Contas a Pagar; e as duas despesas **derivadas**
(comissão e taxa de cartão) pela **data da venda**, porque não têm data de pagamento — não existe
lançamento em Contas a Pagar para elas. É um regime **misto**
— competência de um lado, caixa do outro — e a consequência está escrita na tela e na ajuda, não só
na spec: conta de janeiro paga em fevereiro pesa em **fevereiro**. Quem quer o resultado pelo fato
gerador continua tendo a DRE em competência, e a tela **linka** para ela em vez de deixar o lojista
descobrir sozinho que o número "está errado".

**⭐ A decisão que faz o relatório estar certo: compra de mercadoria NÃO entra nas contas pagas.**
Ele escolheu excluir os pagamentos que não são despesa — hoje 9 contas marcadas com
`inclui_dre = false`: compra de mercadoria e frete sobre compra (`3.03.x`), amortização de principal
de empréstimo (`5.02.x`) e compra de imobilizado (`6.01.x`). A mercadoria **já está contada no CMV**,
quando sai vendida; somá-la de novo no desembolso contaria a mesma coisa duas vezes e transformaria
em prejuízo um mês que deu lucro. ⚠️ **É o tipo de erro que ninguém percebe olhando**, porque os
dois números são plausíveis isolados — daí o critério de aceitação 6 lançar R$ 5.000 de mercadoria
paga contra uma venda de R$ 100, valor escolhido justamente para o teste falhar ruidosamente se a
regra sumir. ⛔ **A regra é dado, não código**: quem decide é a marca do plano de contas, editável
pelo lojista; não há lista de códigos no serviço.

**"Venda bruta" × "venda líquida" (item 6).** Ele fechou a ambiguidade ao redefinir o item 1 como
*"valor da venda − devoluções"*: a líquida **é** o item 1, e a bruta é a venda antes de abater
devolução. Sem devolução no período os dois percentuais coincidem — e isso é informação, não
redundância: mostra que a devolução não comeu margem naquele mês.

**Percentual com base zero é `null`, nunca `0`** — e o front imprime `—`. Um `0%` afirmaria "margem
zero" onde não houve venda nenhuma. Base **negativa** também devolve `null`: *"esta despesa é 40% de
um prejuízo"* não é frase com significado, e acontece de verdade no mês de liquidação abaixo do
custo.

**Cuidados herdados de defeitos reais já corridos neste projeto:** devolução **cancelada** não
deduz (cancelar não apaga as linhas do ledger — achado da auditoria de 2026-08-21 na DRE); venda
cancelada fora de tudo; `id_tenant` explícito em toda query (P8); comparação de data sempre
`AT TIME ZONE 'America/Sao_Paulo'` nos dois lados.

⚠️ **O teste apura o "hoje" perguntando ao BANCO**, não com `LocalDate.now()`: o serviço compara no
fuso da loja e o relógio da JVM roda em UTC nos containers — das 21h à meia-noite de Brasília os
dois discordam por um dia, e a suíte passaria a falhar só nesse intervalo.

**Verificado:** 14 critérios da spec viram 14 testes, todos verdes (**933 no total**, eram 919);
`tsc -b` limpo; endpoint exercitado contra o banco de dev com dados reais (venda bruta 68.077,10,
devoluções 1.329,10, CMV 29.517,78, lucro bruto 37.230,22 = 55,78%). ⚠️ **`contas_pagar` está vazia
no dev**, então o item 5 aparece zerado ali — conferido no banco, não é defeito de filtro. ⏭️ **O
visual da tela não foi conferido em navegador** (o `npm run build` do host falha por falta do
binário nativo do rolldown no `node_modules`, problema de ambiente pré-existente — só o
`binding-linux-x64-musl` está instalado, o do container).

**✅ Comissão e taxa de cartão entram, derivadas do movimento** (ele confirmou no mesmo dia). O
item 5 deixou de ser "contas pagas" e virou **"Despesas do Período"**, com as linhas derivadas
marcadas **(calculado)** na tela — a marca não é enfeite: sem ela a tabela misturaria duas bases de
data em silêncio. Contas: `3.02.001` (Comissões) e `3.02.002` (Taxas de Cartão e PIX), **as mesmas
da DRE**, para os dois relatórios baterem. ⚠️ **Zero não vira linha** — a loja típica não comissiona
e vende no dinheiro, e veria duas linhas de R$ 0,00 todo mês sugerindo configuração faltando.
⚠️ **Devolução não estorna comissão nem taxa**, igual à DRE: o vendedor vendeu e a operadora já
cobrou sobre a transação original; reverter seria regra nova e divergente.

**Cruzamento com a DRE, no mesmo período e com dados reais do dev:** taxa de cartão **94,96** no
novo relatório = 94,96 conferido direto no banco = −94,96 na DRE (sinal invertido porque a DRE usa
o sinal do efeito); CMV **29.517,78** idêntico nos dois. Comissão ausente nos dois — o único
funcionário do dev tem `perc_comissao = 0`.

---

### 2026-08-24 (noite, 7) — 🔴 o `cStat 974` continua de pé, e o CSRT não era a causa

Fecho honesto do dia: **a NF-e 55 ainda não foi autorizada pela SEFAZ.**

**O que foi testado de verdade.** Com o CSRT correto cadastrado no backoffice, reprocessei a venda
608 contra a SEFAZ-PR (homologação): **974 de novo**. Antes de transmitir, confirmei o tamanho do
código gravado **sem revelar o segredo**, pela matemática do cifrado —
`octet_length(decode(csrt_cifrado,'base64'))` = **64** = 12 (nonce) + **36** + 16 (tag). O código de
36 caracteres está lá.

⚠️ **Ou seja: a correção do piso de tamanho do CSRT era real e necessária, e mesmo assim não era a
causa do 974.** Registro isso explicitamente porque a tentação era dar o caso por encerrado quando
o campo passou a recusar valor curto — o defeito estava provado, a correção estava certa, e a
conclusão *"então o 974 acabou"* seria inferência, não medição. **Só a retransmissão provou.**

**Como a SEFAZ chega ao 974.** Ela **não** compara o `<infRespTec><CNPJ>` com o emitente: busca o
CSRT **pelo `idCSRT`**, lê para qual CNPJ aquele código foi emitido, e compara. Divergiu ali → 974.
Consequência contraintuitiva: **um `idCSRT` errado produz uma mensagem sobre CNPJ**, e o
identificador não é citado em lugar nenhum da resposta.

**⚠️ O `idCSRT` da tela vem pré-preenchido com `01`** — chute do formulário, não valor conferido.
É a **mesma família** do campo do CSRT sem piso: *um default com cara de valor conferido*. É a
hipótese nº 1 e depende do portal para confirmar.

As três hipóteses, em ordem: (1) `idCSRT` diferente de `01`; (2) o CNPJ do credenciamento 79413 não
é `37829453000135` — hoje o `NINER_FISCAL_RESPTEC_CNPJ` cai no default do `application.yml`, a
variável nunca foi definida; (3) propagação do credenciamento, que saiu na manhã de 24/08 — só
considerar depois de descartar as duas primeiras.

**Estado no banco:** 9 documentos `REJEITADO` com 974, **nenhum autorizado**. Nada preso no meio,
nada a estornar; numeração queimada é irrelevante em homologação.

**⛔ Erro de processo do dia, e o conserto.** Editei V061 e V062 **depois de aplicadas** no banco de
dev — para corrigir uma data de comentário — e o Flyway recusou subir com *checksum mismatch* nas
duas. Como ainda não estavam commitadas e só existiam neste banco, `flyway repair` realinhou; as
colunas foram **conferidas no `information_schema`** depois, porque `repair` não reexecuta nada. É
a regra do CLAUDE.md que já parou um deploy de produção, repetida por um motivo tão pequeno quanto
um comentário — e as datas que eu estava "corrigindo" eram as que eu próprio tinha escrito um dia
no futuro.

**919 testes verdes**, `tsc -b` limpo, oito serviços no ar.

---

### 2026-08-24 (noite, 6) — as quatro rejeições que separavam a NF-e 55 da autorização (V061, V062)

Sequência de um caminho novo sendo exercitado pela primeira vez contra a SEFAZ. Cada erro escondia
o seguinte, e **nenhum deles aparecia na suíte** — o modelo 55 era código novo sem venda real.

**1. `cStat 253` — "Dígito Verificador da chave de acesso composta inválida".** `MontadorXmlNfce`
montava a chave com a constante `MODELO_NFCE` fixa em vez de `nota.modelo().codigo()`. O XML dizia
`mod 55` e a chave dizia `65` nas posições 21–22 — o DV batia com a chave que ele montou, mas a
SEFAZ recompõe a chave a partir dos campos do XML e chega noutro número. ⚠️ **Constante literal
onde existe um campo de domínio é bomba-relógio**: enquanto só havia um modelo, ela estava certa.

**2. `cStat 733` — "CFOP de operação interna e idDest difere de 1".** O `idDest` já saía 2 (outra
UF), mas o CFOP vinha da regra fiscal, e a regra tinha **um CFOP só**. Solução: `cfop_interestadual`
na `cfg_perfil_fiscal_regra` (**V061**), coluna nova, opcional. ⚠️ **Por que a regra carrega os dois
em vez de o sistema derivar um do outro:** trocar o 5 pelo 6 mantendo o sufixo funciona nos CFOPs
comuns (5102 → 6102) e **não é regra geral** — `5405` não vira `6405`, que **não existe** na tabela
oficial (o correspondente é `6404`). Derivar às cegas emitiria CFOP inválido ou, pior, um CFOP
válido que descreve outra operação. Sem o interestadual cadastrado, a venda para fora do estado é
**recusada dizendo o que falta** (F11), nunca com um CFOP adivinhado.

**3. "Não foi possível falar com o serviço fiscal" — e o serviço tinha respondido.** O `catch` do
`ComprovantePapeletaModal` trocava **qualquer** exceção pelo erro genérico de comunicação,
descartando a mensagem do 409. Ou seja: as validações preventivas (F11), escritas justamente para
dizer o que falta, chegavam ao operador como falha de rede. Agora o `catch` distingue `ApiError`
com mensagem de falha real de transporte. ⚠️ **Front que engole mensagem de erro do back anula a
validação preventiva inteira** — o trabalho de escrever a mensagem certa já estava feito.

**4. `cStat 974` — "CNPJ do responsável técnico diverge do cadastrado".** A mensagem fala em
**CNPJ** e o problema era o **CSRT**: o campo só tinha `@Size(max)`, sem piso, e aceitou o **número
do credenciamento** (5 dígitos) no lugar do código de 36 caracteres. O `hashCSRT` saiu calculado
sobre o valor errado. Corrigido com `@Pattern` de 20–200 caracteres, mantendo o **vazio** válido
(convenção do projeto: em branco = manter o segredo gravado). Teste `recusaCodigoCurtoDemaisComoCsrt`
prende o caso real. ⚠️ **Piso de tamanho em campo de segredo custa uma anotação e economiza uma
viagem à SEFAZ** — sem ele, o erro só aparece do outro lado, com um texto que aponta para o lado
errado.

**Autopreenchimento do que a NF-e exige do destinatário (V062).** Pedido do dono do produto depois
de consultar o site do IBGE à mão: em pessoa **física**, código do município e contribuinte de ICMS
somem da tela (a NFC-e não os pede); em pessoa **jurídica**, o CEP traz o município já preenchido e
a inscrição estadual marca o contribuinte. ⚠️ **O ViaCEP sempre devolveu o campo `ibge`** — ele só
não estava declarado na interface `EnderecoViaCep` e por isso era descartado na desserialização.
O **fornecedor não tinha os dois campos**: `codigo_municipio_ibge` e `indicador_ie` (default 9,
"não contribuinte") entraram na **V062**, com CHECK. A importação de dados deixa os dois nulos de
propósito — planilha migrada raramente os traz, e a tela preenche o IBGE sozinha pelo CEP.

**⛔ Erro de processo cometido e consertado na hora:** editei V061 e V062 **depois de aplicadas** no
banco de dev (para corrigir uma data de comentário), e o Flyway recusou subir com *checksum
mismatch* nas duas. Como ainda não estavam commitadas e só existiam neste banco, `flyway repair`
realinhou — e as colunas foram **conferidas no `information_schema`** depois, já que `repair` não
reexecuta nada. É a regra do CLAUDE.md que já parou um deploy de produção, repetida por um motivo
tão pequeno quanto um comentário.

**919 testes verdes** (eram 913), front limpo no `tsc -b`. ⚠️ **Nenhuma NF-e 55 foi autorizada pela
SEFAZ ainda** — o CSRT correto ainda não estava cadastrado quando esta entrada foi escrita.

---

### 2026-08-24 (noite, 5) — PF sai em NFC-e, PJ sai em NF-e 55, e o PDV imprime o DANFE A4

Dois pedidos do dono do produto no mesmo dia, mais o credenciamento da MITRYUSCASH na SEFAZ/PR
(código **79413**, sistema **NAINER**).

**O gatilho mudou: pessoa jurídica, não mais "contribuinte de ICMS".** Substitui a decisão da
véspera — antes só `indicador_ie = 1` saía em NF-e; agora toda PJ sai. É mais simples de explicar
ao lojista ("CNPJ = NF-e") e nunca emite documento de menos. ⚠️ A **inscrição estadual** continua
sendo exigida só de contribuinte: para `indIEDest` 2 e 9 o XSD **não aceita** a tag `IE`, e mandá-la
seria rejeição de schema — é o caso do escritório que tem CNPJ e não tem IE.

⚠️ **A armadilha que quase virou bug grave:** `cliente.fisica_juridica` vale **`true` para pessoa
FÍSICA** (é ela que exige gênero e CPF de 11 dígitos — está no CHECK da tabela e no
`ClienteService`). Ler o nome da coluna ao pé da letra faria **toda venda a consumidor comum sair em
NF-e**, exigindo endereço completo de quem só queria um cupom. `Destinatario.pessoaJuridica` é o
**inverso** da coluna, montado num ponto só e com aviso no javadoc dos dois lados.

**DANFE A4 no PDV.** `ResultadoEmissao` ganhou `modelo` (65 ou 55) — é por ele que o PDV escolhe o
documento. As sete fábricas do record não passaram a carregar o parâmetro: o modelo é aplicado num
**ponto único**, em `emitir()`, que virou envelope de `emitirInterno()`. No
`ComprovantePapeletaModal`, modelo 55 **e** nota existindo de verdade abre o `DanfeModal` — o mesmo
popup A4 que a devolução e Documentos Fiscais já usavam. A papeleta térmica continua saindo; ela
nunca substituiu a nota. ⚠️ Só abre quando há nota: rejeitada ou não emitida não têm DANFE, e o
popup daria "documento não encontrado" em cima de uma mensagem que já explicava o problema.

⚠️ **O build incremental escondeu duas quebras.** `./mvnw compile` passou verde com duas fábricas de
`ResultadoEmissao` ainda sem o parâmetro novo; os erros só apareceram com `clean`, e disfarçados de
falha em **outro** teste (*"Unresolved compilation problem"*). É a armadilha já registrada — em
mudança de assinatura, `clean` não é opcional.

**916 testes verdes.** ⏭️ Falta a **SVC**, a **homologação real** (nenhuma 55 de venda foi
transmitida à SEFAZ) e o cadastro do **CSRT do PR** no backoffice — sem o par `idCSRT` + `CSRT` a
NF-e 55 volta `cStat 975`.

### 2026-08-24 (noite, 4) — venda a contribuinte de ICMS passa a sair em NF-e 55

Pedido do dono do produto: *"no caixa, para pessoa jurídica tem que ser emitida a NF-e e não a
NFC-e"*. Até aqui o PDV **detectava** o caso (DF13) e apenas **recusava** — a venda ficava sem nota,
em `NAO_EMITIDO`. Agora ele emite a 55.

**Quase toda a infraestrutura já existia:** séries separadas (`serie_nfce`/`serie_nfe`), flags
`emite_nfce`/`emite_nfe`, numeração por (empresa, modelo, série), autorizador por **UF e modelo**
(BA/PE/MA já divergem entre 55 e 65), cadastro de cliente com `rg_ie`/endereço/IBGE, DANFE A4 e todo
o transporte. Faltava a montagem do 55 **de saída com tributação calculada** — o que havia era o da
NFC-e (saída, calculada) e o da devolução (55, espelhada).

**Decisões do dono do produto:** o gatilho é o **`indicador_ie`**, não "tem CNPJ" — PJ não
contribuinte (escritório, condomínio) continua recebendo NFC-e, que a legislação permite; DANFE em
**A4 na impressora comum**; e se a SEFAZ cair, **a venda é registrada e a nota fica pendente**
(sem SVC, que não está implementado).

**Um montador parametrizado, não um terceiro.** `MontadorXmlNfeDevolucao` é separado porque ali a
tributação é *espelhada*; aqui ela é *calculada*, igual à NFC-e — ~80% do montador é idêntico.
`NotaParaMontar` ganhou `modelo`, e só `ide`, `dest` e o QR Code divergem.

⚠️ **Três defeitos que só apareceram testando**, e valem mais que a funcionalidade:

1. **A primeira versão travava o caixa (F3).** Lançava 409 quando o cadastro do cliente estava
   incompleto, impedindo o fechamento da venda por um campo faltando no *cliente*. Isso viola o F3
   — "a venda nunca desaparece porque a nota falhou" —, que é justamente o princípio que o resto do
   módulo protege. Hoje o assembler devolve **mensagem** e o serviço grava `NAO_EMITIDO` dizendo
   qual campo falta. Quem pegou foi um teste que **já existia**.
2. **A validação de QR Code barrava a 55.** O montador exigia `urls` e `csc` — que existem para o QR
   Code, e o autorizador do modelo 55 sequer tem `urlQrCode`.
3. **`DocumentoFiscalRepositorio` gravava o modelo fixo em 65**, então a nota saía **55 no XML e 65
   no banco**. ⚠️ Divergência que **só um teste conferindo a coluna pega** — um que olhasse o status
   da resposta passaria com a nota errada gravada. Foi por isso que o teste novo confere `modelo`,
   `serie` e o **XML**: `<mod>55</mod>`, `<enderDest>`, `<IE>`, `<indFinal>0</indFinal>` e a
   **ausência** de `infNFeSupl`.

**916 testes verdes** (913 + 3). ⏭️ Falta o DANFE A4 ao fechar a venda no PDV (o back já emite), a
SVC, e a confirmação em homologação real — os testes usam transporte mockado e **nenhuma NF-e 55 de
venda foi transmitida à SEFAZ ainda**.

### 2026-08-24 (noite, 2) — a altura da página de etiqueta era o PASSO, e devia ser a altura do ADESIVO

Ao cadastrar um segundo rolo (**34 × 60**, destacável), a etiqueta saía **partida em duas tiras** —
nome/descrição/preço numa, os códigos de barras na seguinte. O modelo de 34 × 31,7 imprimia
perfeito, e o **PDF da mesma impressão saía completo**: o navegador desenhava certo, o defeito
estava do papel para lá.

⭐ **Quem resolveu foi o `.rtm` do Delphi — e eu demorei a pedir.** O dono do produto disse desde
cedo que o sistema legado imprime os dois modelos na mesma impressora sem ajuste nenhum, o que
encerra qualquer hipótese de limitação física. A regra já estava escrita no projeto
(*evidência do usuário vence inferência minha*) e eu a repeti como erro: gastei duas rodadas de
etiqueta e um ajuste de driver antes de pedir o arquivo. Lido com um parser de DFM binário escrito
para isto, o veredito estava em uma linha:

| | `PrinterSetup.mmPaperHeight` |
|---|---|
| `ETQ_30_34.rtm` | **30 mm** (adesivo de 30) |
| `ETQ_60_35.rtm` | **60 mm** (adesivo de 60) |

O Delphi declara a página com a **altura do adesivo**; nós declarávamos **adesivo + espaçamento
entre fileiras**. E somar o gap está errado por um motivo físico: a impressora imprime o bitmap e
**depois avança até o próximo gap** — quem produz o branco entre fileiras é o avanço, não o desenho.

⚠️ **Por que o modelo de 31,7 funcionava com a conta errada:** o papel do driver estava em 31,0 mm e
**cortava** a página de 33,9, sobrando por acaso ≈ a altura do adesivo. O erro estava lá desde
21/08, mascarado. Foi por isso que subir o papel para 62 mm não consertou a etiqueta alta — tirou o
corte que escondia o defeito.

Continua valendo **uma página por fileira** (a parte certa de 21/08); mudou o valor.
`alturaPaginaImpressaoMm()` devolve a altura da etiqueta, `passoVertical()` ficou marcada como **não
sendo** a altura da página, e `FOLGA_PAGINACAO_MM` (0,2 mm) evita o efeito colateral: com página e
bloco valendo o mesmo, um sub-pixel nasce uma página em branco entre cada etiqueta.

**Resultado no papel:** o modelo 34 × 60 passou a sair com o conteúdo inteiro em cada adesivo, e o
34 × 31,7 continuou correto.

### 2026-08-24 (noite, 3) — o papel do driver controla o AVANÇO, e é por isso que a web não alcança

O teste seguinte trouxe o achado que fecha o assunto:

| Papel no driver | 34 × 31,7 | 34 × 60 |
|---|---|---|
| **31 mm** | ✅ correto | ❌ partido em duas tiras |
| **60 mm** | ❌ **imprime uma e pula uma em branco** | ✅ correto |

Papel 60 com página de 31,7 faz a impressora andar 60 mm e **saltar uma etiqueta**. Ou seja: o papel
não é só a área de desenho, é o **avanço** — e não existe valor único que sirva aos dois rolos.

**O Delphi escapa porque grava o tamanho DENTRO do relatório** e o aplica por trabalho via DEVMODE
do Windows. **Aplicação web não tem essa API:** `@page size` é uma dica, quem escolhe o papel é o
driver. Também descartado o caminho dos **formulários do Windows** — o sistema tem 33 cadastrados,
mas o driver Seagull expõe apenas os **4 tamanhos próprios** e ignora os do Windows.

Restam dois caminhos, e a escolha é de produto: (a) trocar o papel do driver junto com o rolo — são
dois rolos físicos, a troca já é manual; (b) **agente local de impressão**, que definiria o papel
por trabalho como o Delphi e resolveria a intensidade junto, ao custo de instalação, atualização e
suporte em cada loja (empurra contra o P6). ⏭️ **Decisão pendente do dono do produto.**

⏭️ Fica pendente também mostrar na tela o papel que cada modelo exige (*"este modelo exige papel
110 × 60 mm no driver"*) — hoje esse número é invisível, e foi ele que custou o dia.

**Geometria horizontal.** Medindo a foto dá para separar erro de passo de erro de origem, porque
ela tem as duas referências: as **linhas impressas** (preto sólido) e os **picotes físicos** (vinco,
queda leve de brilho). Dois limiares no mesmo perfil: passo impresso 234 px, passo físico 242 px
(+3,4%) → o rolo tem **≈ 35,7 mm** e imprimíamos 34,5. ⚠️ E eu tinha mandado **zerar a margem
esquerda** copiando o `.rtm`, o que fez a coluna 1 começar antes do papel — os 3,00 mm que já
estavam lá eram os certos. **O `.rtm` do legado é evidência sobre o comportamento (a altura da
página), não sobre a geometria do rolo que está na impressora agora.**

### 2026-08-24 (noite) — o mesmo campo pode aparecer mais de uma vez na etiqueta (V060)

Pedido do dono do produto, ao montar um modelo 34 × 60: *"preciso ter a opção de poder colocar 2
vezes cada campo, pois às vezes a etiqueta é destacável"*. O adesivo é picotado ao meio — uma parte
fica no produto, a outra é destacada no caixa — e as duas metades precisam do mesmo código de
barras, preço e descrição. Até aqui a paleta esvaziava com *"Todos os campos já estão na etiqueta"*
e a segunda metade saía em branco.

**Havia duas travas, e a segunda só apareceu testando.** A `UNIQUE (id_config_etiqueta, campo)`
(caiu na **V060**) e uma validação Java — `"Campo repetido na etiqueta: X"` — em
`EtiquetaConfigService.validar`, que **não estava no radar** ao ler o schema. Quem a revelou foi um
`POST` de teste respondendo **400**: conferindo só pela tela, o editor deixaria montar o layout e a
falha apareceria no **Salvar**, com o trabalho já feito.

**O modelo relacional já suportava** — nada mais mudou no backend. A tabela tem PK própria,
`salvarCampos` apaga e reinsere a lista inteira em ordem (padrão do projeto) e `buscarCampos` lê com
`ORDER BY id_config_etiqueta_campo`, que preserva a ordem enviada.

**No front, a identidade dos campos deixou de ser o NOME e passou a ser o ÍNDICE.** Por nome, as
duas instâncias seriam a mesma coisa: arrastar uma moveria a outra, o painel não diria qual está
aberto e as `key` do React duplicariam. Mudaram seleção, arraste, alças, setas do teclado, remoção,
o `Set` de transbordo e as `key` de **quatro** renders — incluindo a **Emissão de Etiqueta**, fora
da tela mexida e que passaria despercebida. ⚠️ Remover limpa a seleção sempre: remover por índice
desloca os seguintes, e o índice antigo passaria a apontar para o campo vizinho.

A tela agora mostra a contagem na paleta (`Código de Barras (SKU) (2)`) e o ordinal no painel
(`Código de Barras (SKU) (2ª)`), que só aparece quando há repetição. **Sem limite de repetições**:
travar em 2 exigiria contagem e mensagem para impedir algo que não faz mal.

**Verificado:** no navegador, mover uma das duas instâncias moveu 1 de 5 campos; pela API, `POST`
201 com dois `SKU_BARRAS` voltando na ordem certa e com `exibirTextoLegivel` independente, `PUT`
200, `DELETE` 200 sem campo órfão. **913 testes verdes** — `campoRepetidoEhRejeitado` prendia o
comportamento antigo e virou `mesmoCampoPodeAparecerMaisDeUmaVez`, que confere as **duas
instâncias de volta**, não só o status: um teste que olhasse o 201 passaria mesmo se a leitura
devolvesse uma linha só.

### 2026-08-24 (fim da tarde) — a tela de CRIAR modelo de etiqueta era inutilizável, e a de editar estava perfeita

Encontrado pelo dono do produto ao cadastrar um segundo modelo (34 × 60): o formulário espremido
numa coluna com rolagem interna, o editor visual jogado **abaixo** dele reduzido a ~30 px, e meia
tela vazia à direita. ⚠️ **Só em `/etiqueta-configuracao/novo`** — editando ficava correto, e é por
isso que passou dias despercebido: toda a calibragem foi feita **editando** o único modelo que
existia.

**A causa: seletor posicional sobre um item condicional.** O grid posicionava a faixa de baixo com
`.etiqueta-config-corpo .form-fieldset > .section:last-of-type`, mirando *Informações do Registro*.
Mas `InfoRegistro` faz `if (!codigo) return null` — no modo criar ele não renderiza, e o "último
`.section`" passa a ser a **seção do editor**. E a especificidade decide o estrago: **0,4,0** da
regra da faixa de baixo contra **0,2,0** da que põe o editor na coluna 2. A errada vence, e o
desenho da etiqueta — a razão de a tela existir — vai esmagado para a linha 3.

O diagnóstico saiu de um comando lendo o estilo **computado** no DOM (`gridColumnStart` = `1/-1`
onde devia ser `2`), nas duas rotas, sem teoria nenhuma.

**Correção:** `InfoRegistro` ganhou a classe `secao-info-registro` e o CSS passa a mirá-la pelo
nome. Ele é usado em **15 telas**; acrescentar classe é seguro (nada a referenciava) e foi
conferido no navegador que em Clientes a seção segue com `border-bottom: 0` (regra global
`.section:last-of-type`, intocada) e `grid-column: auto`.

**Lição:** seletor posicional (`:last-of-type`, `:last-child`, `:nth-*`) sobre lista onde algum
item é condicional aponta para o elemento errado exatamente quando o item some — e, por ser mais
específico, ainda vence a regra correta. Se o CSS precisa de um alvo, dê um nome a ele. E ao mexer
em layout de tela de cadastro, **teste os dois modos**: criar e editar.

### 2026-08-24 (tarde) — o quadro de corte do Teste de Impressão mudava o layout que ele deveria provar

Fecha o último defeito da etiqueta. Com as barras já lendo, sobrou um sintoma restrito: os
**dígitos legíveis** do código de barras não saíam **pelo Teste de Impressão**; pela Emissão de
Etiqueta saíam, na mesma impressora e no mesmo modelo.

⭐ **Quem matou a hipótese errada foi o dono do produto.** Eu estava convencido de que a
intensidade recém-baixada apagava a haste fina do Courier New; ele observou que a Emissão imprimia
certo — e se fosse calor, as duas falhariam igual. **Duas rotinas imprimem a mesma coisa e só uma
falha ⇒ a diferença está no código das duas**, não no mundo físico.

Descartados com evidência: `exibir_texto_legivel` (está `t`), o `GET` (devolve `true`), o `PUT`
(faz `return buscar(id)` — mesmo DTO), o render (os dígitos **existem no DOM**), o
`visibility: hidden` da impressão (a regra do rolo vence por especificidade), o `@page`
(`110mm 33.9mm`, correto) e o próprio navegador — **o PDF da mesma impressão traz os dígitos**.

A foto do papel, calibrada pelo passo de 33,9 mm entre duas tarjas (434 px → 12,8 px/mm), mostrou
as barras começando em **18,0 mm** (previsto 17,5) e terminando em **27,1 mm**: altura
**reduzida**, de quando os dígitos existem — se `exibirTexto` estivesse desligado o SVG teria
esticado até 31,5 mm. O espaço foi reservado e ficou **vazio**.

**A causa:** o Teste desenhava o quadro de corte com `border: 1px solid #000`, e com o
`* { box-sizing: border-box }` global a borda **encolhe a área interna e empurra todo o conteúdo
1 px (0,265 mm) para baixo**. O campo de barras termina em 31,5 mm numa etiqueta de 31,70 — folga
de 0,2 mm, menor que o deslocamento. Só os dígitos atravessavam.

**Correção:** `outline` + `outlineOffset: -1px`, que desenha fora do fluxo e não ocupa espaço.
Medido no navegador: os dígitos saíram de 0,066 mm **além** do adesivo para 0,76 px **dentro** —
a mesma geometria da Emissão. Confirmado no papel pelo dono do produto.

⚠️ **O defeito de fundo vale mais que a linha corrigida:** um enfeite da tela de calibragem estava
alterando a geometria que ela deveria estar provando, divergindo da rotina real num item visível.
É o mesmo princípio que a tela já defendia ao imprimir sempre a versão gravada em vez do
formulário em edição.

### 2026-08-24 (tarde) — a calibragem da etiqueta passa a sobreviver a reset de banco

Pedido do dono do produto: *"pra não correr o risco de perder esta configuração de etiqueta, pois
às vezes vamos ter que zerar o banco"*. Os números custaram duas sessões de etiqueta impressa e o
banco de dev é recriado com frequência.

`db/scripts/seed_etiqueta_calibrada.sql` recria o modelo inteiro — geometria do rolo (110 mm,
3 colunas, 34 × 31,70, espaçamentos 3,00/2,50/2,20) e os 4 campos posicionados. Idempotente,
resolve o `id_tenant` **pelo slug** (o id muda a cada recriação) e faz
`set_config('app.id_tenant', ...)` — sem isso o `FORCE RLS` barra o INSERT e esconderia o SELECT.
Termina com um `SELECT` de conferência.

⛔ **Não virou migration, de propósito:** é dado de um tenant e vai mudar enquanto a calibragem
evoluir; migration aplicada não pode ser editada (checksum do Flyway) e rodaria em produção.

**Testado nos dois caminhos**: atualizando o modelo existente e criando do zero com um nome
temporário, removido em seguida — script de restauração que ninguém conferiu não foi verificado,
foi torcido.

### 2026-08-24 — o código de barras que não lia era INTENSIDADE, e a etiqueta provou medindo

Fecha a pendência aberta em 2026-08-21, que tinha resistido a **três** correções no mesmo dia —
todas no desenho do SVG, todas necessariamente inúteis: **o desenho sempre esteve certo**.

**Como a causa apareceu.** Em vez de reler o código pela quarta vez, a etiqueta impressa foi
**medida**: `ffmpeg` recortou a foto, extraiu a faixa em cinza cru, e um script Node tirou a média
vertical de 60 linhas (matando ruído de papel e câmera) e mediu os *runs* de preto/branco.

| Medida | Esperado | Impresso |
|---|---|---|
| Transições de um EAN-13 | 59 | **31** |
| Barra mais fina | 1,00 módulo | **2,25** |
| **Largura do símbolo** | **25,2 mm** | **≈26 mm** |

⭐ **A medida que fechou o diagnóstico foi a que deu certo.** O símbolo mede o que devia medir — se
a geometria estivesse errada, essa linha acusaria. Como não acusou, o erro só podia estar na
repartição entre preto e branco: cada barra engordou ~0,33 mm e cada espaço encolheu o mesmo.
Isso é *bar width growth*, assinatura de **intensidade alta demais** em impressão térmica.

Confirmação independente e gratuita, na mesma foto: o texto **vazado em branco** sobre a tarja
preta saiu comido pela metade — preto invadindo branco num campo sem nenhuma relação com o SVG.
Sintoma em dois elementos independentes aponta para a impressora; sintoma isolado apontaria para o
desenho daquele elemento.

**A correção, medida:** Argox OS-2140 PPLA, escala 0–20 — **10 (padrão de fábrica) não lê, 6 lê**.
⚠️ A pegadinha vem antes do slider: a caixa *"Usar configuração atual de intensidade da impressora"*
vem marcada e deixa o slider **cinza**, porque o driver passa a usar o valor gravado no firmware.
E o ajuste fica em *Preferências de impressão* → aba **Opções** — **não** em "Propriedades da
impressora", que é outra janela e não tem o campo. O estado real do driver saiu de
`Get-PrintConfiguration` (o XML do Seagull traz o slider como `DisplayHint: Disabled`).

**Por que virou aviso na tela e não código** (pergunta do dono do produto: *"e o usuário, que não
vai saber fazer isso?"*): a intensidade mora no DEVMODE privado do driver e **nenhuma API web
alcança** — nem WebUSB, porque no Windows a impressora já está capturada pelo driver de classe de
impressora. E compensar no desenho é impossível nesta resolução: a 203 dpi o módulo tem **2,12
dots** e a impressora só liga ou desliga dots inteiros. Do engrossamento medido, ~0,9 dot é fração
de dot (corrigível) e **~1,8 dot é calor (não corrigível)** — mesmo zerando a parte corrigível,
sobraria o engrossamento maior numa barra de 2 dots.

**Entregue:** `web/src/pages/etiquetaconfig/AvisoIntensidadeImpressora.tsx`, exibido no popup do
**Testar Impressão** (o momento em que o lojista imprime e confere), com o passo a passo do driver
— inclusive a caixa que trava o slider —, mais um passo e um erro comum na `AjudaDaTela` (R22).
Detalhe completo em `docs/telas/configuracao-etiqueta.md`.

⏭️ **Fica registrado:** módulo em **dots inteiros** vale a pena (elimina os 0,9 dot) mas exige
parâmetro novo de resolução da impressora (203/300 dpi). Conta que explica a falta de folga: numa
etiqueta de 34 mm a 203 dpi, **2 dots é a única opção** — 3 dots dariam 42,4 mm e não cabem. O
legado usa 0,31 mm de módulo, e é daí que vem a margem que ele tem e nós não. Caminho definitivo,
se o volume justificar: **agente local falando PPLA**, com a própria impressora desenhando o código
de barras — mas isso empurra contra o P6.

### 2026-08-24 — subida do ambiente: o `minio-init` estava morrendo por CRLF

Ao subir os servidores, `niner-minio-init` saía com **código 2** a cada tentativa
(`set: -: invalid option` na linha do `set -eu`), e a consequência aparecia longe da causa: sem os
buckets, `ArquivamentoXmlJob` respondia **503** para todo XML fiscal — 34 documentos e eventos do
tenant 1 acumulados sem arquivar.

Causa: `infra/minio/bootstrap.sh` estava no working tree com **CRLF**, e o `\r` entrava como
argumento do `set`. O `.gitattributes` já manda `eol=lf` e o índice tinha LF — o estrago vinha de
um checkout antigo com `core.autocrlf=true`, e só esse arquivo dos quatro `.sh` do repositório
estava afetado (`git ls-files --eol` mostrava `i/lf w/crlf`). Corrigido com `rm` +
`git checkout --`, sem tocar no índice.

⚠️ O `cat -A` do Git Bash **não** mostrou o `^M`; quem provou foi hexdump dentro do container. E
cuidado ao inspecionar: num `printf "[%s]"` o `\r` volta o cursor e o `]` sobrescreve o caractere,
fazendo a linha parecer limpa.

Confirmado ponta a ponta: o ciclo seguinte do job gravou os **34 XMLs** em
`tenants/1/fiscal/2026/08/65/…`, sem nenhum erro.

### 2026-08-22 — as pendências da auditoria: 24 dos 33 resolvidos, 5 adiados por decisão

Sessão inteira dedicada a `docs/PENDENCIAS-AUDITORIA-2026-08-21.md`. O dono do produto foi avisado
(ele havia pedido duas vezes para ser cobrado), decidiu item a item e mandou tratar o resto.

**Os que mexiam em dinheiro ou documento fiscal:**

- **Item 2 — o vale-mercadoria saía pela MÉDIA.** Desde o orçamento, o mesmo produto pode aparecer
  duas vezes na mesma venda: o preço congelado que a loja honrou e o preço do dia. Devolver 1 peça
  de uma venda 1×R$ 50 + 1×R$ 120 gerava vale de **R$ 85** — valor que a venda nunca praticou — e o
  mesmo R$ 85 ia para a NF-e 55. A grid passou a agrupar por **(variação, preço)**; cada preço vira
  uma linha, e quando há mais de um a tela avisa qual é qual. `ItemDevolucaoRequest.precoUnitario`
  (opcional, para não quebrar contrato) identifica a linha, e o servidor **confere que a venda teve
  aquela linha** — senão quem chamasse a API escolheria o valor do próprio vale. Teste de
  regressão novo em `DevolucaoProdutoCrudTest`.
- **Item 19 — CST do fornecedor em nota de Simples/MEI.** `DevolucaoCompraFiscalAssembler` recusa
  montar quando `crt != 2` e o item original traz `cst_icms`: emitir assim destacaria ICMS que a
  loja não apura. Mínimo seguro por decisão do dono — a conversão para CSOSN é regra tributária e
  espera o contador.
- **Item 1 — cancelar entrada com devolução debitava estoque 2×.** Terceiro guard em
  `EntradaMercadoriaService.cancelar`, ao lado dos dois que já existiam.
- **Item 22 — parcela migrada virava lucro de 100%.** `V059` deu à `venda` as colunas `origem` e
  `importado_em`; o DRE caixa exclui as parcelas recebidas **antes** da importação. Parcela legada
  em aberto que o cliente pagar depois continua sendo receita — esse dinheiro entrou no caixa.
- **Item 6 — devolução no mês da devolução.** *"Valores fechados do mês não podem ser mudados."* O
  KPI passou a recortar por `data_movimento` (como o DRE e as Comissões) e ganhou o filtro de
  devolução cancelada. O javadoc que afirmava "sempre 0 hoje" mentia desde 19/08.
- **Item 5 — nota presa em `TRANSMITINDO` sumia da fila do dreno.** Volta agora, depois de 10 min de
  carência, e **nunca é reenviada cega**: consulta a chave antes (F5). Resposta que o job não sabe
  decidir sozinho é registrada e deixada para o ADMIN.
- **Item 20 — cStat 573.** Consulta a nota e, se a SEFAZ confirmar o cancelamento (101/151), segue
  com a reversão. Só o "sim" é aproveitado — qualquer outra resposta mantém a recusa.
- **Itens 7 e 23 — cobrança.** Trava de idempotência antes de empurrar `proxima_cobranca` (uma
  notificação duplicada dava um mês, ou um ano, de graça), e a chamada ao Mercado Pago saiu de
  dentro da transação (novo bean `CobrancaFaturaTransacional`, padrão Job × Processador).
- **Item 28 — produto órfão no cadastro rápido.** Novo `POST /produtos/com-variacao`, transação
  única. Endpoint separado de propósito: aninhar `ProdutoRequest` num envelope quebraria o
  formulário completo e todos os importadores.
- **Item 24 — 7 campos configuráveis sem revalidação no servidor.** `exigirSeObrigatorioValor` cobre
  número e data. ⚠️ Ausente é `null`; **zero é valor legítimo** — quem quer o campo fora do cadastro
  usa `visivel = false`, que é a dimensão certa.
- **Item 4 — documentos A4 não paginavam.** Orçamento, DANFE de devolução e guia de transferência
  saíam da impressora com **uma página só**, perdendo total e assinatura sem aviso. `position:
  absolute` fora, classe `.documento-a4-imprimir` + helper `imprimirDocumentoA4()` no lugar — o mesmo
  remédio que fez a etiqueta paginar. Cabeçalho só na primeira página. ⚠️ **Não testado no papel.**
- **Mecânicos 8–18 e 33:** confirmação ao remover linha de contagem; recusa de caixa fechado
  antecipada no cancelamento (campo novo `caixaAbertoHoje` no DTO — o front não deduz, a venda pode
  ser de outra empresa); erro de negócio em `AvisoModal` com `pre-line` em vez de banner inline;
  quantidade deixou de ser formatada como moeda; **inutilização de numeração agora exige digitar a
  faixa** (é a ação mais irreversível do sistema); `autoFocus`; invalidação das chaves derivadas de
  React Query (`invalidarBalanco`, `invalidarTiposCarteira` — chave de array não casa por prefixo);
  maiúsculas nas justificativas (a da inutilização vai no XML da SEFAZ); `navigate(-1)`; `@page`
  órfão amarrado; e o aviso de busca truncada em 6 telas, com o limite de fornecedor subindo de 10
  para 20.

**Adiados por decisão dele:** 21+32 (homologação SEFAZ/PR pendente), 25 (ele quer repensar a forma
de avisar — a ideia é um **sino** no topo da tela), 26+30 (política de staff não definida — é escopo
de produto, não dívida técnica) e 27 (estorno não revoga assinatura: **aceito, com pedido explícito
de ser lembrado em toda revisão do ERP**).

⛔ **A segunda passada da documentação (pedida pelo dono do produto) achou uma lacuna de CÓDIGO,
não de doc:** `EtiquetaConfigService.buscarProdutos` (editor de etiqueta) e
`RelatorioMovimentacaoProdutosService.buscarVariacoes` (Kardex) tinham exatamente o defeito do
item 33 — `LIMIT 10` sem avisar —, mas a auditoria não os havia listado. Corrigidos junto (limite
20 + aviso). **Corrigir as telas listadas não é o mesmo que corrigir o defeito:** o que os achou
foi procurar quais specs de tela ainda não citavam a data de hoje, e depois o `grep` do padrão.
Junto vieram 5 specs sem a seção do dia (`cliente`, `funcionario`, `transferencia-estoque`,
`fiscal-conformidade`, `configuracao-etiqueta`), a ajuda das telas que usam a busca de
fornecedor, e dois docstrings meus escritos horas antes (um typo e uma pergunta retórica no meio
de um comentário).

⛔ **A TERCEIRA passada da documentação (pedida pelo dono do produto, insatisfeito com as duas
anteriores) achou mais 8 specs de tela sem a seção do dia**, todas de telas realmente tocadas:
`cancelamento-devolucao-produtos` (itens 10 e 13), `estorno-recebimento-crediario` (13),
`relatorio-comissoes`, `relatorio-contas-receber` e `relatorio-estoque` (17); e três que tinham
a seção mas não cobriam tudo — `devolucao-produtos` (faltava o item 4, o DANFE que passou a
paginar), `devolucao-compra` (faltava o 33) e `relatorio-movimentacao-produtos` (faltava o 17).

O método que as achou foi **listar os arquivos de código alterados no dia e mapear cada um à sua
spec**, em vez de partir da lista de itens da auditoria — um mesmo item (o 17) tocou cinco telas,
e só uma tinha sido documentada.

⚠️ **Lacuna PRÉ-EXISTENTE encontrada de passagem, NÃO corrigida:** o CRUD de **Tipo de Carteira**
não tem spec em `docs/telas/` — existe a tela, o serviço, os testes e a ajuda dentro do produto,
mas nenhum arquivo de especificação. Não foi criada agora por ser trabalho de escopo próprio, não
documentação do que mudou hoje. Fica registrado aqui para não se perder.

**913 testes verdes** (912 + o de regressão do item 2), `tsc -b` limpo. ⚠️ `npm run build` não roda
neste ambiente — o `node_modules` só tem o binding nativo `linux-x64-musl` do rolldown e a máquina é
Windows; é instalação, não código (o `CLAUDE.md` já registra que falha depois do type-check é do
bundler).


### 2026-08-21 (madrugada) — a auditoria completa: seis rodadas, 31 defeitos, e o fim decidido pelos agentes

Continuação da entrada abaixo (que cobre as duas primeiras passadas). Ao todo foram **seis rodadas**
por agente, e a auditoria terminou **por recomendação dos próprios agentes** — o backend voltou da
sexta sem nenhum defeito e os dois disseram, independentemente, para não pedir a sétima.

**Balanço: 31 defeitos corrigidos, 33 pendências registradas** em
`docs/PENDENCIAS-AUDITORIA-2026-08-21.md`. 912 testes verdes.

**Os dois piores achados do dia inteiro eram o mesmo erro:** `@Transactional` faltando em dois
métodos do `DocumentoFiscalRepositorio`, cada um com uma irmã que tem. Sem a anotação o
`SET LOCAL app.id_tenant` nunca roda e `plataforma.tenant_atual()` vira NULL. Um **impedia emitir** a
NF-e da devolução ao fornecedor — com a mercadoria já baixada, o número fiscal já queimado e a
mensagem *"Registro em uso por outro cadastro"* (sobre exclusão de cadastro, para uma emissão de
nota); o outro fazia o cancelamento **devolver o estoque sem enviar o evento 110111**, deixando uma
NF-e 55 autorizada contra uma saída que não aconteceu — em silêncio, com resposta 200. Nenhum teste
via os dois: a suíte da devolução roda com o fiscal desligado.

**Fiscal, o resto:** o **CST 60** não zerava a base de ICMS — gêmeo exato do CSOSN 500 corrigido em
2026-08-19, com o javadoc dele dizendo *"mesma lógica do 500"* e mesmo assim fora da lista; é a
rejeição cStat 531 com o cliente no caixa. O **cStat 155** (cancelamento homologado fora de prazo) era
tratado como recusa, então a SEFAZ cancelava e o ERP não revertia. E a NF-e de devolução **descartava
o segundo item da mesma variação** (`putIfAbsent`) — premissa que a mudança do mesmo dia quebrou;
hoje recusa em vez de descartar.

**Dinheiro:** o DRE calculava "Taxas de Cartão" com `perc_desconto` (o desconto que a loja dá ao
cliente) em vez de `taxa_administradora` (o que a maquininha cobra) — contava em dobro um desconto já
deduzido **e** mostrava R$ 0,00 de taxa para toda loja que não dá desconto. Devolução cancelada
continuava deduzida da comissão e do DRE. E o backup que falha era retentado **a cada 60 segundos até
a meia-noite** (~1.260 `pg_dump` num dia) — o que já aconteceu no primeiro dia de produção.

**Estoque e tela:** a Contagem **apagava a linha** quando o campo de quantidade ficava vazio, sem
toast e sem desfazer; a devolução ao fornecedor era a única rotina de estoque a ignorar
`cfg_permite_qtd_decimal` (nos dois lados — foi a ponte entre os dois agentes); trocar de empresa em
Configuração Fiscal mantinha os dados da anterior nos campos, e salvar gravava as séries fiscais da
Matriz na Filial; marcar os campos de oferta como obrigatórios **não fazia nada** (o asterisco
aparecia e o produto salvava vazio); e o cadastro rápido criava **produto sem variação** quando o EAN
repetia, dizendo que não tinha criado nada.

⚠️ **Três dos 31 eram regressões introduzidas no mesmo dia**, todas no PDV do orçamento: o F4 não
limpava o orçamento (e a guarda criada horas antes transformou o defeito silencioso em venda
travada), o F3 deixava aumentar a quantidade mantendo o preço congelado, e o auto-ajuste de zoom do
editor de etiqueta era descartado a cada tecla.

⛔ **E a descoberta que não é bug: a impersonação auditada (R21/P9) nunca foi construída.** A
constituição promete que staff da Vetor alcança dado de lojista *"only via audited impersonation"*; a
tabela `impersonacao_log` existe desde a V010, com `REVOKE DELETE` e teste guardando a imutabilidade
— e **zero escritores**. O único ponto em que a superfície de staff lê domínio abre o contexto RLS
com `set_config` à mão, sem trilha. Pendência 26, e a 30 mostra que também não há gestão de staff nem
revogação de token: são um trabalho só.

**Método que vale reusar.** Os dois agentes acharam **independentemente** os mesmos dois bugs
críticos do PDV — convergência de auditorias cegas é a confirmação mais forte que existe. Cada
achado foi conferido no código antes de virar correção, e alguns caíram nessa conferência (o
`Map.of` com null do `/eu`, que o próprio agente descartou depois de ler a migration). Os
**descartes** renderam tanto quanto os achados: o agente do backend previu um irmão do `putIfAbsent`
na devolução ao fornecedor, foi lá e **provou que não existe**; e delimitou a superfície de staff a
um único ponto em vez de deixar a suspeita no módulo inteiro.

⚠️ **Duas armadilhas de ferramenta mordidas no caminho:** `mvn compile` deu **BUILD SUCCESS com um
import faltando** (o contexto do Spring só quebrou em teste, com 768 erros — é o compile incremental
que o `CLAUDE.md` já registra; `clean` pega na hora), e `EXIT=$?` depois de um pipe mede o **último**
comando do pipe, não o Maven.

⭐ **A recomendação final da auditoria não é código:** *transmitir uma devolução ao fornecedor em
homologação*. É o único caminho fiscal do produto que **nunca rodou de verdade** — sem teste (a suíte
roda com o fiscal desligado) e quebrado até a correção do `@Transactional` acima. Sobre ele já se
acumulam três pendências (19, 21, 32).

### 2026-08-21 (fim da noite) — auditoria por dois agentes: 13 defeitos corrigidos, 18 registrados

Dois agentes varreram backend e frontend em paralelo, em duas passadas cada. **Os dois acharam,
independentemente, os mesmos dois bugs críticos do PDV** — convergência de auditorias cegas é a
confirmação mais forte que existe. Cada achado foi conferido no código antes de virar correção;
nenhum foi aceito só pelo relatório (um deles, `EuController` com `Map.of` e null, o próprio agente
descartou depois de conferir a migration).

**Corrigidos (13).** Os dois piores eram `@Transactional` faltando no `DocumentoFiscalRepositorio`,
em métodos cuja irmã tem: sem a anotação o `SET LOCAL app.id_tenant` nunca roda e
`plataforma.tenant_atual()` vira NULL. Um impedia emitir a NF-e da devolução ao fornecedor — com a
mercadoria já baixada, o número fiscal já queimado e a mensagem *"Registro em uso por outro
cadastro"* (sobre exclusão de cadastro, para uma emissão de nota); o outro fazia o cancelamento
**devolver o estoque sem enviar o evento 110111**, deixando uma NF-e 55 autorizada contra uma saída
que não aconteceu — em silêncio, com resposta 200. Nenhum teste cobria os dois: a suíte da devolução
roda com o fiscal desligado.

Três eram regressões do próprio dia: o F4 do PDV não limpava o orçamento (e a guarda criada horas
antes transformou o defeito silencioso em venda travada), o F3 deixava aumentar a quantidade
mantendo o preço congelado (tela pedindo R$ 250 e servidor exigindo R$ 280, com mensagem sobre
*pagamento*), e o auto-ajuste de zoom do editor de etiqueta era descartado a cada tecla digitada nas
medidas.

Os demais: devolução cancelada ainda deduzida da comissão e do DRE; a Contagem de Estoque **apagando
a linha** quando o campo de quantidade ficava vazio (sem toast, sem confirmação, sem desfazer, e o
produto sumia do balanço sem virar divergência); a devolução ao fornecedor sendo a única rotina de
estoque a ignorar `cfg_permite_qtd_decimal` — nos dois lados, e foi a ponte entre os dois agentes; a
validade do orçamento ganhando um dia depois das 21h (`toISOString` em UTC, o último caso do `web/`);
o orçamento nascendo com o prazo velho por cache stale e invalidação faltando; `"nullT12:00:00Z"` em
Contas a Pagar; o DANFCE sem o piso do jsPDF; e o `multiploDeColunas` estourando o próprio teto.

Mais três casos no `PrivilegiosNinerAppTest` para os GRANTs de V051–V054, que não tinham cobertura —
Testcontainers conecta como superusuário, então GRANT faltando é invisível na suíte e só aparece em
produção.

**Registrados para decisão (18).** `docs/PENDENCIAS-AUDITORIA-2026-08-21.md` lista os 7 que exigem
regra de negócio — dupla baixa ao cancelar entrada que já teve devolução, contingência presa em
`TRANSMITINDO` (o dreno nunca mais a pega, com o prazo de 24 h correndo), vale-mercadoria por preço
médio (caso que a mudança do mesmo dia tornou comum), documentos A4 que não paginam, KPI de
devoluções medindo período diferente do DRE, orçamento de outra empresa e webhook sem lock — mais 11
menores, cada um com cenário concreto, e as áreas que os agentes declararam sem cobertura.

**912 testes verdes.**

### 2026-08-21 (noite) — o código de barras: duas correções, uma reversão e uma pendência aberta

Com a etiqueta saindo alinhada, sobrou o problema que fecha o dia **sem solução**: o leitor não
reconhece o código impresso. Três rodadas, nesta ordem, e o registro importa mais pelo que
**descarta** do que pelo que resolve.

**1. ⛔ `crispEdges` foi um erro meu e foi revertido.** Eu o acrescentei de manhã para resolver o
relato de "barras borradas". A etiqueta impressa saiu com **barras gordas e ilegíveis**: ele
arredonda **cada borda** para a grade de pixels de forma independente e, com o módulo caindo em
fração de pixel, uma barra de 1 módulo vira 2 px enquanto o espaço vizinho some. A razão
barra/espaço — que é o que o leitor mede — deixa de existir. A foto do dono do produto encerrou a
discussão.

**2. A causa do "borrado" nunca foi o antialiasing: era o esticamento.** O jsbarcode desenhava com
`width: 2` fixo e o SVG era escalado por um fator arbitrário até preencher a caixa. Hoje o **módulo
é derivado da caixa**, então o desenho nasce do tamanho do viewport e nada é escalado.

**3. ⛔ E o que impedia a leitura era a ZONA DE SILÊNCIO ausente.** `margin: 0` no jsbarcode remove
o branco obrigatório antes e depois do símbolo (**11 módulos à esquerda, 7 à direita**, GS1). Não é
margem estética: **é por ela que o leitor sabe onde o símbolo começa e termina** — sem ela o código
pode estar impresso com precisão perfeita e ser recusado. O módulo passou a ser derivado de **113**
módulos (95 + 11 + 7) em vez de 95:

| | antes | agora |
|---|---|---|
| módulo | 0,337 mm (102% do nominal) | 0,283 mm (86%, dentro da norma) |
| zona de silêncio | **0 mm** ❌ | 3,1 mm esq · 2,0 mm dir ✅ |

⏭️ **Mesmo assim não lê.** Fica como pendência aberta do dia. O atalho já identificado: o sistema
Delphi imprime etiquetas que **leem** na mesma impressora, e o `.rtm` dele dá os números para
comparar — módulo **0,31 mm** e altura de barra de apenas **5 mm**. ⚠️ Esse segundo número
**derruba a hipótese da altura**, que eu tinha listado como suspeito principal: se o legado lê com
5 mm, nossos 9 mm não são o bloqueio.

### 2026-08-21 (fim do dia) — sete ajustes da tela de etiqueta, e o PDV do orçamento

Com a etiqueta saindo certa, o dono do produto usou as telas de verdade e listou o que atrapalhava.
Dois lotes: a tela de Configuração de Etiqueta e o PDV/Orçamento.

**Etiqueta — o defeito mais grave estava no papel, não na tela.** O campo "Nome da Empresa"
imprimia o literal `"NOME DA LOJA"` em **quatro** lugares, incluindo a Emissão: **toda etiqueta já
emitida saiu com esse texto**. Passou a vir de `GET /api/v1/eu`. ⚠️ E **não** de
`empresa.cfg_nome_etiqueta`, apesar do nome da coluna — aquilo é herança do ERP legado, onde a
etiqueta era um modelo com marcadores, e o signup ainda semeia assim (no banco de dev o valor é
literalmente `{sku}\n{descricao}\n{preco_venda}`); imprimir aquela coluna colocaria `{sku}` no
adesivo.

Mais: o painel de propriedades era `.modal-overlay` e **cobria a etiqueta** — o painel que existe
para ajustar o campo impedia arrastar o campo; virou janela flutuante arrastável. Alças de
redimensionamento passaram de uma (canto) para três (canto, largura, altura). Código de barras
ganhou `crispEdges` (antialiasing em térmica 1-bit vira barra irregular) e os dígitos saíram do SVG
para HTML, agrupados 1+6+6 — dentro do SVG eles eram esticados junto com o desenho.

⚠️ **A tela virou duas colunas depois de uma tentativa que saiu pior que o problema.** Travar a
rolagem com tudo empilhado espremeu o editor no que sobrava e ele passou a desenhar POR CIMA das
Informações do Registro. Medindo o DOM: item de flex tem `min-height: auto` e **se recusa a encolher
abaixo do conteúdo** — sem `min-height: 0` em cada elo, o canvas de 476 px vaza de um container de
99 px. Hoje: medidas à esquerda, editor à direita com a altura inteira, prévia do rolo recolhida
(`<details>`, custava ~190 px permanentes) e a dica da barra de ferramentas em uma linha (custava
71 px). O **zoom abre no maior que mostra a etiqueta inteira**, com teto de 250% e **piso de 150%**:
sem piso, uma janela baixa abria em 50%; e com `ResizeObserver` vivo, redimensionar a janela
**descartava o zoom escolhido pelo usuário**.

**PDV — F5 e F6 trocaram** (F5 Buscar Orçamento, F6 Efetiva Venda, lado a lado), cliente e vendedor
ficaram **fixos** em venda vinda de orçamento, e o popup virou busca por número/cliente/vendedor,
só dos ABERTOS.

⛔ **O ajuste mais profundo foi "juntar só se o preço for igual".** A regra do dono do produto —
*"se o preço mudou a empresa tem que honrar o orçamento, mas produtos a mais não precisam"* — não
cabia no modelo: o servidor amarrava o preço congelado à **variação**, não à linha, e a validação
"não pode levar mais do que foi orçado" **somava as duas linhas e recusava a venda inteira**.
Nasceram `ItemVendaRequest.doOrcamento` (marca por LINHA), `ItemLedger.idLinha` (o SKU era a chave e
alterar uma linha alterava as duas) e `dividirParaEnvio` (uma linha da tela vira dois itens no envio
quando o preço não mudou). ⚠️ Com **guarda de contrato**: mandar `idOrcamento` sem nenhuma linha
marcada é recusado — sem ela a venda sairia inteira a preço de cadastro, o orçamento seria consumido
e nada indicaria que a loja deixou de honrar o preço prometido.

Orçamentos ganharam busca por nome de cliente e de vendedor (⚠️ os JOINs entraram **também** no
`count(*)`, senão o filtro quebraria a contagem com *"missing FROM-clause entry"*).

**911 testes verdes**, dois deles novos: `unidadeAMaisDoMesmoProdutoSaiPeloPrecoDeHoje` e
`vendaDeOrcamentoSemNenhumaLinhaMarcadaEhRecusada`.

### 2026-08-21 (tarde) — as 3 colunas voltam: o papel do driver é que mandava, não o cabeçote

Continuação direta da sessão da manhã (logo abaixo). O dono do produto retomou com a impressora
ligada, e o dia terminou com **as 3 colunas alinhadas** — o oposto da conclusão da manhã, que
tinha dado o rolo por impossível. Vale ler as duas entradas juntas: a segunda é a correção da
primeira, e o motivo do erro é mais interessante que o erro.

**1. Uma página por FILEIRA, não uma folha longa.** O modelo da manhã montava todas as fileiras
numa página só (`@page` de 102 × 634 mm para 40 etiquetas) e posicionava cada fileira pelo índice.
Impressora térmica de etiqueta não imprime folha: a Argox estava com mídia **die-cut** e papel de
**101,6 × 152,4 mm** no driver, então ela fatiava o trabalho em pedaços de 152,4 mm. Como 152,4
não é múltiplo do passo, cada corte caía no meio de um adesivo — saíam ~5 fileiras em branco antes
de a impressão começar e a primeira etiqueta já nascia 3 mm fora. **Erro já na primeira fileira
não é erro de passo**: passo só se manifesta acumulado, e essa distinção foi o que apontou para o
driver em vez de para a geometria. `yDaFileira`/`alturaFolhaMm` foram removidos; a página passou a
valer `passoVertical` e cada fileira sai numa página, deixando o **sensor de gap** encaixar cada
uma no adesivo. O erro de passo deixa de existir por construção — não tem onde acumular.

**2. Duas armadilhas de CSS que escondem trabalho em silêncio.** Para paginar, o bloco de
impressão precisou (a) ir por **portal** para filho direto do `<body>`, com o resto da árvore
escondido por `display: none` — o `visibility: hidden` global do projeto esconde o pixel mas
**mantém o espaço**, e esse espaço empurrava a primeira etiqueta; e (b) **destravar
`html, body, #root { height: 100%; overflow: hidden }`** (styles.css:84, o shell de altura fixa do
ERP). Com `overflow: hidden` o navegador **corta** o que passa da primeira página em vez de
paginar: saía **uma** página com a primeira fileira e as outras 19 sumiam sem aviso nenhum. As
duas regras são presas a `body.imprimindo-etiquetas` para não vazar para as outras impressões do
produto (papeleta, DANFE, orçamento em bobina — este último ainda usa a classe antiga
`.etiqueta-imprimir`, que segue intacta).

**3. ⛔ A conclusão da manhã estava errada, e o `.rtm` do sistema legado provou.** A manhã concluiu
"o cabeçote alcança ~102 mm, logo 3 colunas são impossíveis neste rolo". O dono do produto
discordou com o argumento mais forte que existe: *"tenho um sistema em Delphi e ele imprime as 3
colunas normalmente nesta impressora"* — e mandou o template (`.rtm`, um DFM binário do
ReportBuilder) e um PDF. Lidos os dois:

| o que o legado faz | valor |
|---|---|
| `PrinterSetup.mmPaperWidth` | **110 mm** — o papel inteiro |
| `PrinterSetup.mmPaperHeight` | **30 mm** — ou seja, **uma página por fileira**, o mesmo desenho |
| `Columns` / `ColumnPositions` | 3, em **3,5 / 39 / 75 mm** |
| `mmColumnWidth` | 34 mm |
| código de barras | caixa de 34 mm, `taRightJustify`, `mmBarWidth` 0,31 mm (94% do padrão) |

E no PDF, extraindo as coordenadas dos desenhos: as barras vão de **9,78 mm a 109,43 mm**, e os
três grupos de texto distam 35,52 e 35,99 mm — batendo com os `ColumnPositions`. **O legado pinta
até ~109 mm e a impressora imprime.**

**A causa raiz real, então, é outra:** o problema nunca foi o alcance do cabeçote — era o **papel
configurado no driver ser menor que a página declarada**, o que faz o Chrome encolher a página
para caber. A régua de calibragem da manhã mediu **o encolhimento**, e o número foi lido como se
fosse o cabeçote. O legado nunca sofreu isso porque declara o papel de 110 × 30 no próprio
trabalho. ⚠️ **Regra que fica:** *o papel do driver tem de ser igual ou maior que a página
declarada* — vale para toda impressão do produto, não só etiqueta.

**Geometria final, validada na impressora:** Largura de Impressão **110**, **3 colunas**, etiqueta
34 mm, margem 3, espaço entre colunas 2,5, colunas nascendo em **3 / 39 / 75** (as duas últimas
idênticas às do legado; a primeira 0,5 mm à esquerda porque nosso modelo usa passo uniforme e o
template tem 35,5 e 36 — provável digitação, não intenção).

**4. Quantidade do Teste de Impressão travada em múltiplo do número de colunas** (pedido do dono
do produto). Numa fileira incompleta o rolo avança igual: pedir 40 com 3 colunas imprime uma
fileira final com uma etiqueta só, e **os outros 2 adesivos passam pelo cabeçote em branco e vão
para o lixo**. Arredonda para cima (quem pede 40 quer pelo menos 40), no `onBlur` para o usuário
ver o número antes de confirmar, e de novo no envio — o `onBlur` não dispara quando se digita e
tecla Enter direto.

**5. A régua saiu de junto das etiquetas.** Com página do tamanho do passo, os 132 mm dela não
cabem; e como 132 nunca foi múltiplo do passo, ela empurrava todas as etiquetas para fora do
adesivo — **escondendo justamente o defeito que se queria medir**. Virou impressão exclusiva
("no lugar das etiquetas"), desmarcada por padrão.

**6. O Teste de Impressão passou a gravar antes de imprimir** (*"ajuste para sempre seguir as
medidas que estão no banco"*). Antes ele imprimia do formulário — conveniente para calibrar sem
salvar, mas admite a pior divergência possível numa tela de calibragem: o papel com uma medida e o
cadastro com outra. Como o papel é a única evidência que temos, teste que não corresponde ao
gravado não prova nada. Agora grava e imprime o **retorno do servidor**, sem navegar de volta
(quem calibra fica na tela). ⚠️ Precisou guardar o id quando a configuração nasce do próprio teste
(`idCriadoNoTeste`): sem isso o "Salvar" seguinte criaria uma **segunda linha** em vez de atualizar.
Modo visualização não grava — imprime o que já veio do banco. Corrigido junto um bug de cache
clássico daqui: salvar invalidava só `['etiquetas-config']` e não `['etiquetas-config-emissao']`,
deixando a Emissão listando modelos pelo cache antigo.

**Dívida registrada:** durante a investigação foi necessário cadastrar "Largura da Etiqueta" ora
como o adesivo (34), ora como a área que a impressora alcança (30). São duas grandezas com um
campo só — o ponto cego que a manhã já tinha anotado. Com o papel do driver correto elas voltaram
a coincidir e a dívida deixou de doer, mas ela existe: rolo mais largo que o cabeçote vai
reabrir o assunto, e aí são dois campos, não um.

### 2026-08-21 — a etiqueta encontra uma impressora de verdade (Argox OS-2140)

Sessão inteira de diagnóstico com o rolo, a régua e a impressora do dono do produto na mão.
Spec completa em `docs/telas/configuracao-etiqueta.md` §"🔴 2026-08-21".

**A causa raiz não era espaçamento — era o nome de um campo.** "Largura do Rolo" pedia a largura
do **papel** (110 mm), mas a Argox OS-2140 só imprime **104**. O driver encolhia a página inteira
em ~7% para caber, e encolher a página encolhe o **passo entre colunas** junto — o adesivo não
encolhe. Erro de 2,8 mm por coluna, que **se acumula**: a primeira etiqueta sai perfeita e a
terceira sai fora. Isso imita exatamente um erro de medida no cadastro, e os dois não se
distinguem olhando a etiqueta impressa. ⚠️ `Escala: 100%` no Chrome **não corrige** — quem escala
é o driver. Campo renomeado para **"Largura de Impressão"**, com ajuda explicando a diferença.

**Nasceu a régua de calibragem** (`ReguaCalibragem.tsx`): duas réguas de 100 mm, deitada e em pé,
impressas antes das etiquetas por uma caixa no popup do Teste de Impressão. Existem porque, sem
elas, "medida errada" e "impressora escalando" são indistinguíveis — e o conserto de um não
conserta o outro.

**Ela morreu duas vezes na impressora antes de funcionar, e as duas lições são gerais:**

1. **`background: #000` não imprime.** O navegador suprime cor de fundo por padrão. Saiu uma
   régua só com os números — e foi o que *sobreviveu* que deu o diagnóstico: números (texto),
   código de barras (SVG do JsBarcode) e o quadro da etiqueta (`border`) saíram.
2. **`border` imprime, mas a espessura é arredondada para pixel inteiro** — 0,5 mm pedido virou
   1 px = 0,26 mm, e traço fino em térmica (1 bit) sai falhado.
3. **SVG resolve os dois**: é conteúdo (não fundo) e é vetor.

⚠️ **Bug latente destapado no caminho:** o campo com `fundo_preto` usa `background` com letra
branca — **imprimia branco no branco**, campo invisível no papel e perfeito na tela. Ninguém
tinha usado a opção ainda. Corrigido com `print-color-adjust: exact`.

**Mais duas correções de geometria**, nas duas telas (Configuração e Emissão):
- `@page` com **altura exata** em vez de `auto` — com `auto`, quem decide onde a folha acaba é o
  driver, e a fileira atravessada pela quebra sai partida no meio do adesivo, sem aviso.
- **Fileira posicionada por índice** (`yDaFileira`, `alturaFolhaMm`) em vez de empilhada: bloco
  de altura fracionária (33,5 mm = 126,614 px) soma arredondamento a cada fileira. Mesmo
  raciocínio da V057, que derivou o x das colunas.

**⛔ Um limite físico que nenhum número contorna.** Medido: etiqueta 34,0 × 29,5 mm, rolo 110 mm
(`2 + 34 + 2 + 34 + 2 + 34 + 2 = 110`, fecha exato). A 3ª etiqueta termina em **108 mm** e o
cabeçote alcança **~102** — sobram 28 mm úteis dos 34, e o código de barras teria de cair de 30
para ~27 mm, abaixo do mínimo de 80% do EAN-13. **Decisão do dono do produto: 2 colunas neste
rolo.** Alternativas registradas na spec.

**Método que vale reusar:** o quadro `border: 1px solid #000` que o Teste de Impressão já
desenhava virou **instrumento de medida** — o quadro é onde mandamos imprimir, o adesivo é onde a
etiqueta está. Fotografando os dois juntos e medindo **razões** por pixel no mesmo plano, o
diagnóstico dispensa calibrar escala e não sofre com perspectiva. A previsão tirada da foto ("a
etiqueta mede ≈34,5 mm, não os 33 do cadastro") foi confirmada pela régua: **34,0**.

⏭️ **Parou pela metade:** horizontal resolvida (quadro dentro do adesivo nas 2 colunas em 20
fileiras), **vertical ainda desalinhada**. Próximo passo já decidido: aplicar Altura da Etiqueta
**29,50** e Espaço entre fileiras **2,20** (passo 31,70 contra os 33,50 de hoje) e reimprimir 40
etiquetas conferindo a última fileira. Não aplicado ainda porque os valores vieram de medição em
foto e a configuração atual está imprimindo certo na horizontal.

### 2026-08-20 — Orçamento de Venda: a rotina inteira, do schema ao papel

Área nova na frente de loja, pedida em 12 itens pelo dono do produto e fechada pergunta a pergunta
antes de escrever a primeira linha. Spec completa: `docs/telas/orcamento.md`.

O problema: o consumidor pergunta o preço e não fecha na hora. O vendedor anota num papel; o
cliente volta três dias depois com o papel amassado e ninguém sabe quem atendeu, o que foi
combinado, nem se aquele preço ainda vale.

#### As decisões que não dão para inferir do código

**O orçamento é imutável** — e isso **reverte o item 10 do pedido original** ("possibilidade de
alterar o orçamento"), por decisão dele na mesma sessão. A necessidade que o item 10 endereçava —
o cliente levar menos do que orçou — é resolvida no PDV, que pode **diminuir** quantidade sem
tornar o documento mutável. É a mesma filosofia da venda: corrige-se cancelando.

**O preço é congelado** na emissão e relido do banco na efetivação. É o que faz a data de validade
significar alguma coisa: *"este preço vale até tal dia"*. A tela avisa quando o cadastro divergiu —
**informa, não decide**; quem escolhe honrar é o operador. A descrição, ao contrário, **não** é
congelada: resolve por JOIN, como a papeleta e o DANFE já fazem. Preço é compromisso comercial;
congelar texto de cadastro criaria uma segunda verdade.

**`VENDIDO_PARCIAL` é estado final.** O cliente que volta para buscar o resto faz uma venda nova,
**sem vínculo nenhum** — não há saldo por item nem segunda venda ligada ao orçamento. Palavras
dele: *"como o cliente voltou, faz uma venda nova e pronto"*.

**`VENCIDO` é estado próprio, separado de `CANCELADO`.** O pedido original dizia "cancela
automaticamente com o motivo CLIENTE NÃO VOLTOU"; virou estado próprio porque num relatório
*quantos o vendedor cancelou* e *quantos morreram esperando o cliente* são perguntas diferentes —
e essa é a métrica de sucesso da rotina.

**Produto inativado depois não vende.** É a **única** regra do PDV que o orçamento não afrouxa, e
o aviso aparece na consulta, antes de o operador tentar com o cliente na frente.

**Item extra é permitido** (ajuste pedido depois da primeira rodada): a venda pode levar produto
que não estava no orçamento. Esse item é venda comum — preço de cadastro, sem limite — e não conta
para decidir se o orçamento foi parcial. A regra "só diminuir" vale para o que foi **orçado**, não
para a venda inteira.

**Não consome cota do plano** (ADR-015): cobrar pela captação desincentivaria a venda que de fato é
cobrada.

#### As três armadilhas do repositório que esta feature encostou

⚠️ **Job sem `TenantContext` não enxerga nada — e não dá erro.** O job de vencimento é o ponto mais
perigoso: `niner_app` nunca tem `BYPASSRLS`, então uma consulta de domínio fora de
`TenantContext.comTenant` devolve **zero linhas em silêncio**. Ele "não acharia nada para vencer"
todo dia, para sempre, sem uma linha de log. E o par obrigatório: `@Scheduled` num bean,
`@Transactional` em outro — auto-invocação não passa pelo proxy do Spring.

⚠️ **O orçamento venceria às 21h.** A sessão do Postgres roda em UTC. O "hoje" do vencimento vem da
**UF de cada empresa** (`FusoDaLoja.da`), não de um `America/Sao_Paulo` carimbado: uma loja no Acre
(UTC−5) e uma em Recife (UTC−3) viram o dia em momentos diferentes, e o fixo mataria o orçamento do
lojista do Acre duas horas antes. Por isso a varredura é **por empresa**, não uma consulta só.

⚠️ **Marcar o orçamento como efetivado NÃO é efeito colateral.** A regra do projeto ("efeito
secundário roda depois do commit") nasceu do signup respondendo 201 com conta inexistente e vale
para **medição e log**. Aqui é vínculo de negócio: se a gravação de `id_venda` falhasse depois do
commit, sobraria uma venda feita e um orçamento aberto que o cliente usa de novo. Fica **dentro** da
transação de `efetivarVenda`.

#### Detalhes de tela que valem registro

**F6 só funciona com a venda vazia** — puxar orçamento por cima de itens já lançados misturaria
preço congelado com preço de cadastro sem o operador perceber. **O `idOrcamento` é limpo junto com
o ledger** ao efetivar, senão a venda seguinte carimbaria um orçamento sem relação com ela. **As
duas versões do documento ficam sempre no DOM**: o CSS decide qual vai para a impressora, e o
WhatsApp captura sempre o **A4** (bobina é papel de balcão, não anexo de mensagem). E o A4 declara
`@page orcamento-a4` próprio — o `@page` global do projeto é 80mm, e sem página nomeada ele sairia
espremido na bobina, tropeço que o DANFE já deu.

**Migration V058** (enum de 5 estados, `orcamento`, `orcamento_item`, RLS, GRANT, parâmetro de
validade em `cfg_geral`), com caso novo em `PrivilegiosNinerAppTest` — o `GRANT` é invisível para a
suíte, que conecta como superusuário.

**909 testes verdes** (+15), cobrindo os 12 critérios de aceitação da spec.

⚠️ **Nada de impressão foi testado** — nem o A4 nem a bobina. A calibragem térmica não aparece na
tela, no PDF nem em teste automatizado; só imprimindo.
### 2026-08-20 — Etiqueta, rodada 2: a geometria virou derivada, e a migration quase apagou o dado em silêncio

Proposta do dono do produto depois de ler a rodada 1: *"você já sabe a largura do rolo, o número de
colunas, a largura e a altura da etiqueta. O que preciso a mais é margem até a primeira coluna,
espaçamento entre colunas e espaçamento entre fileiras. Com isso você sabe a área de impressão e a
posição x,y de cada etiqueta."*

Está certo, e expõe um defeito de fundo do modelo original: `cfg_etiqueta_coluna.posicao_inicial_mm`
era **dado redundante** — sempre foi calculável — e, por ser digitado à mão, era exatamente ali que
o erro entrava (3/41/79 num rolo de passo 40). Com a posição derivada, **o erro deixa de ser
representável**. A folha inteira sai de 7 números.

**O modelo encolheu:** foram embora a tabela `cfg_etiqueta_coluna`, as 4 colunas de borda, duas
validações de coerência da lista de colunas — e, do front, todo o aparato que a rodada 1 tinha
criado para conviver com posições digitadas (modo "rolo irregular", dedução de margem/espaço na
abertura e a trava de ordem de efeitos que ela exigia). No lugar entrou **uma** validação, a que
importa fisicamente: *as colunas cabem no rolo?* — agora conferida **também no servidor**, o que
antes era impossível: com posições digitadas, o backend não tinha como saber onde cada coluna
começava sem confiar no que recebeu.

#### 🔴 A armadilha: o backfill da migration saiu zerado, e o Flyway disse "sucesso"

A V057 precisa ler `cfg_etiqueta_coluna` para preencher margem/espaçamento antes de apagar a
tabela. Rodou, anunciou sucesso — e a config existente em dev, que tinha colunas em 2,50 / 40,50 /
78,50 (margem 2,50, espaço 4,00), ficou com **margem 0,00 e espaço 0,00**.

⚠️ **Migration roda como `niner_owner`, e com `FORCE ROW LEVEL SECURITY` (V024) nem o dono da
tabela escapa da política.** Sem `app.id_tenant` no contexto, o `SELECT` devolveu 0 linhas e o
`UPDATE` casou 0 linhas — as colunas novas ficaram no `DEFAULT 0`. Não há erro, não há aviso: o
Flyway não tem como saber que zero linha era o número errado.

É o **mesmo** defeito que já tinha mordido o backup do produto em 2026-08-19 (`pg_dump` sem
`BYPASSRLS` levando estrutura completa e zero linha de cliente). Vale a generalização: **migration
que LÊ dado de tenant para transformá-lo precisa tratar RLS explicitamente**, e o único jeito de
perceber é conferir o resultado no banco depois.

Conserto: `NO FORCE ROW LEVEL SECURITY` nas duas tabelas antes do backfill e `FORCE` de volta
depois — `NO FORCE`, não `DISABLE`, porque libera só o **dono** e mantém a política valendo para
`niner_app`. Provado em isolamento antes de aceitar: como `niner_owner`, `count(*)` na mesma tabela
devolve **0 com FORCE e 1 com NO FORCE**. Como a V057 ainda não tinha sido publicada e só o dev a
tinha rodado, o caminho foi corrigir o arquivo + `flyway repair` (o procedimento já documentado
para este caso) e repor à mão a geometria da única config de dev.

**894 testes verdes**, `tsc -b` limpo.
### 2026-08-20 — Etiqueta: a tela mentia sobre o tamanho da letra, e o rolo não tinha passo vertical

Diagnóstico pedido com material real na mão — as duas telas de configuração, o PDF gerado e a
**foto de uma folha impressa**. É a segunda vez no projeto que a foto da impressão acha o que
nenhum teste acharia (a primeira foi a calibragem da bobina térmica).

**O defeito central cabe numa linha.** `CampoEtiquetaVisual` dimensiona posição, largura e altura
em `mm × escalaPxPorMm`, mas o `font-size` saía em **`pt`** — unidade **absoluta de tela**. 7pt são
sempre ~9,3px, não importa se 1mm vale 12px (editor a 200%), 3,78px (impressão) ou 3px (prévia do
rolo). Resultado: o texto aparecia **3,2× menor que o real** no editor e 26% maior na prévia. A
descrição do produto cabia numa linha na tela e quebrava em **três** no papel, e as duas linhas
extras vazavam por cima do preço.

⚠️ **A impressão sempre esteve certa; a tela é que mentia** — e a prova é a própria correção: pt →
mm → px pela mesma escala dá, no papel, exatamente o que dava antes
(`pt × 25,4/72 × 96/25,4 = pt × 96/72`, que é o que `Npt` já valia a 96dpi). Só a tela mudou.

**O segundo defeito só aparece na quarta etiqueta.** A impressão empilhava as fileiras usando
`alturaEtiquetaMm` como passo, e o rolo tem espaço em branco entre fileiras. O conteúdo subia a
cada fileira: na foto, a 1ª sai quase certa, a 2ª já sem cabeçalho, a 4ª inteiramente fora do
adesivo. **É a assinatura de um erro que se acumula** — e por isso ninguém o pega testando uma
etiqueta. Coluna nova `espacamento_vertical_mm` (V056), passo = `altura + espaçamento`, aplicada na
Emissão e no Testar Impressão. Default 0 = comportamento atual: nenhum modelo existente muda
sozinho.

**O terceiro é o que fecha o ciclo:** `overflow: hidden` fazia o texto que não cabia sumir em
silêncio na tela e reaparecer bagunçado no papel. Agora o campo **mede** (`scrollHeight` ×
`clientHeight`) e a tela diz por extenso qual campo não cabe.

#### A reforma da tela veio de uma pergunta do dono do produto

A primeira versão do conserto acrescentou "espaço entre fileiras" (vertical) e manteve "posição de
cada coluna" (horizontal). Ele leu e perguntou: *"o espaço horizontal e o vertical eu tenho que
informar, ou só informo a posição onde cada etiqueta começa?"* — e a pergunta era o defeito: duas
formas de pensar a mesma medida física, na mesma tela.

**Hoje é uma só: informa-se sempre o espaço em branco.** O card "Espaçamento entre Etiquetas"
reúne margem até a 1ª coluna, ↔ espaço entre colunas e ↕ espaço entre fileiras — as três medidas
que se tiram com a régua, do mesmo jeito. As posições das colunas passaram a ser **calculadas** e
mostradas como conferência; continuam sendo o que vai para o banco.

⚠️ **"Rolo irregular — digitar cada posição" não é recurso avançado:** é o que impede a tela de
estragar um modelo que já funcionava. Ao abrir um modelo salvo, a tela deduz margem/espaço das
posições gravadas e cai sozinha no modo manual quando elas não seguem passo constante.

⚠️ **Armadilha de ordem de efeitos que isso criou**, resolvida com trava explícita: o recálculo
automático e a dedução rodam no **mesmo commit** do React, e o recálculo é declarado depois — leria
margem/espaço ainda vazios e `colunasManuais` ainda `false`. Em rolo regular se auto-corrige no
render seguinte; em rolo **irregular** não, e as posições já teriam sido sobrescritas antes de o
modo manual ligar. O estado `medidasDeduzidas` bloqueia qualquer recálculo até a dedução terminar.

**Avisos que a tela não dava:** conteúdo que não cabe, coluna que passa da largura do rolo (no
modelo que motivou tudo, a coluna 3 terminava em 113mm num rolo de 110mm), colunas sobrepostas, e
a prévia do rolo passou a mostrar **duas fileiras** — sem a segunda não havia como conferir o
espaço vertical antes de gastar rolo.

**894 testes verdes** (+1: `espacamentoEntreFileirasEhGravadoELido`, que cobre criação, leitura e
**atualização** — o UPDATE tem lista de colunas própria e é onde campo novo costuma ficar de fora).

⚠️ **Achado colateral, não corrigido:** `npm run build` está quebrado nesta máquina —
`node_modules/@rolldown` só tem o binding **linux-x64-musl** num Windows (bug conhecido de
optional dependencies do npm). `tsc -b` passa; quem quebra é o bundler. Conserto é remover
`node_modules` + `package-lock.json` e reinstalar — não fiz por conta própria por ser demorado e
mexer em ambiente.

### 2026-08-20 — Estoque negativo vira escolha do lojista, e a regra mora na trigger

Três pedidos do dono do produto no mesmo dia, sendo o terceiro uma **inversão de política**:

**1. Venda cancelada não tem mais reimpressão de papeleta.** Até 2026-08-19 a reimpressão saía com
`**** VENDA CANCELADA ****` no topo — a ideia era avisar. O dono do produto trocou o aviso pela
recusa, e o motivo é melhor que a solução anterior: **papel impresso circula**. Um cupom de venda
cancelada na mão de alguém é um documento afirmando uma venda que não existe mais, e a única forma
de ele não circular é não imprimir. O botão some da tela e
`PdvVendaService.buscarComprovante` responde **409** — checagem no servidor porque o endpoint
atende qualquer usuário do tenant e a tela não é o único caminho até ele (P4).

**2. Parâmetro novo: "Permite quantidade de estoque negativo"** (Parâmetros do Sistema → Estoque),
`cfg_geral.cfg_permite_estoque_negativo`.

**3. Nenhuma rotina debita além do que existe** — e o saldo é **por empresa**.

#### A regra mora na trigger, e essa foi a decisão de projeto do dia

O pedido dizia "vendas, transferências, devolução ao fornecedor, **e etc**". O "e etc" é o
problema: debitam estoque hoje o PDV, a transferência, a devolução ao fornecedor, **o cancelamento
de entrada**, o cancelamento de devolução de venda e o balanço — e amanhã, alguma rotina nova.
Espalhar a checagem por serviço garante que uma fique de fora, hoje ou em seis meses.

`produto_movimento_detalhe` é o **único** caminho por onde `produto_estoque` se mexe. A V054 pôs a
regra em `fn_atualiza_estoque_movimento`, que já estava lá: quem passa por ela é toda rotina que
existe e toda que vier. É o mesmo raciocínio do RLS — a regra que não pode falhar mora no banco.

⚠️ **O cancelamento de entrada prova o ponto.** Ele não tem uma linha de checagem de estoque, e
ninguém pensa nele ao ouvir "rotinas que debitam estoque" — mas cancelar uma compra é um débito.
Entrou 3, vendeu 2, alguém cancela a entrada: sem a trava, o saldo iria a −2 e a loja teria vendido
mercadoria que o sistema passa a dizer que nunca entrou. Está preso em
`CancelamentoEntradaCrudTest.cancelarEntradaCujaMercadoriaJaSaiuEhBloqueado`.

#### Três detalhes da implementação que não são óbvios

- **A conferência no `UPDATE` vem depois dos DOIS passos, nunca no meio.** A trigger desfaz o
  efeito da linha antiga e aplica o da nova; desfazer um crédito derruba o saldo no caminho e o
  passo seguinte o recompõe. Corrigir a quantidade de um item de entrada de 10 para 12, com 5 já
  vendidos, passa por −5 e termina em 7 — conferir no meio reprovaria uma correção legítima.
- **O caminho comum não paga nada.** `saldo >= 0` sai na primeira linha, sem ler `cfg_geral` nem
  montar mensagem. Uma importação de 10 mil linhas de crédito não sente a regra.
- **A mensagem diz qual produto, qual empresa, quanto há e quanto falta** — montada só no caminho
  de falha, onde consulta extra não custa. Sem o item, o operador caçaria linha por linha.

⚠️ **Como o erro da trigger vira uma mensagem legível:** ela levanta com `ERRCODE 23514`
(check_violation) e o prefixo `ESTOQUE_NEGATIVO|`. Sem o prefixo, o `GlobalExceptionHandler`
responderia *"Registro em uso por outro cadastro — não pode ser excluído"* para quem tentou vender.
Um SQLSTATE próprio (classe `P0`) seria pior ainda: viraria `UncategorizedSQLException` e um 500 sem
mensagem nenhuma.

#### O custo nos testes — e o que ele revelou

**67 dos 891 testes quebraram.** Vale destrinchar, porque a distribuição foi informativa:

- **~38 não tinham nada a ver com estoque**: eram `PUT /config-geral` sem o campo novo obrigatório
  → 400. Um `perl` de uma linha nos payloads resolveu.
- **~26 eram fixtures vendendo sem estoque.** Esses foram **abastecidos**, não contornados: a
  variação nasce com saldo, como nasceria depois de uma entrada. Ligar o parâmetro nesses testes
  teria sido mais rápido e teria escondido a regra da suíte inteira.
- **3 afirmavam explicitamente o comportamento antigo** (`estoqueInsuficienteNaoBloqueiaVenda...`,
  `transferenciaComEstoqueInsuficienteEhAceita...`, e o comprovante de venda cancelada). Esses
  **viraram a cobertura da regra nova**, com o comentário dizendo que diziam o contrário até hoje —
  quem ler daqui a um ano precisa saber que foi decisão, não deriva.

#### ⚠️ O padrão nasceu ao contrário e foi invertido no mesmo dia (V054 `false` → V055 `true`)

A V054 leu o item 3 do pedido ("as rotinas não podem debitar e ficar negativo") como o
comportamento desejado e nasceu **bloqueando**. Perguntado sobre o padrão, o dono do produto deu o
contexto que faltava: *"na maioria das vezes o usuário não vai querer controlar o estoque, aí se
ficar negativo não tem problema"*. A loja típica do produto **não faz gestão de estoque** — travar
o caixa dela por um número que ninguém alimenta seria o pior dos dois erros. O controle é
**opt-in**: quem quer estoque confiável desmarca.

Vale registrar o custo dessa inversão, que foi pequeno **porque a regra estava num lugar só**:
mudou um `DEFAULT`, e os três testes que medem o bloqueio passaram a desligar o parâmetro
explicitamente. Se a checagem estivesse espalhada por seis serviços, seria seis vezes o trabalho e
uma chance de esquecer um.

⚠️ A V055 também roda `UPDATE cfg_geral SET cfg_permite_estoque_negativo = true` nas linhas
existentes: ninguém chegou a **escolher** `false` — foi um default que durou uma sessão, e deixar
as lojas já cadastradas bloqueando faria elas se comportarem diferente das novas por acidente de
cronologia.

**893 testes verdes** (+2 líquidos: 3 reescritos, 2 novos).

### 2026-08-20 — Devolução de Produtos Comprados: o XML da entrada virou insumo, e o estoque virou limite

Rotina nova: devolver mercadoria ao **fornecedor**, com baixa de estoque e **NF-e 55 de saída**.
É o espelho da devolução do consumidor (B9), mas as duas se parecem menos do que o nome sugere —
e as diferenças foram o trabalho de verdade. Spec completa em `docs/telas/devolucao-compra.md`.

**Duas ausências pedidas pelo dono do produto, e uma delas encolheu o schema.** A devolução ao
fornecedor **não mexe no financeiro** (o lojista negocia o crédito por fora) — e é por isso que ela
**não tem tabela de cabeçalho**, ao contrário de `venda_devolucao`. Lá o cabeçalho existe porque a
devolução gera um vale-mercadoria, que é objeto de negócio com vida própria. Aqui não há
equivalente: a operação **é** o movimento de estoque, e inventar um cabeçalho vazio só criaria uma
tabela para manter sincronizada com o ledger.

**O que faltava não era código, era dado: o XML da entrada não estava sendo guardado direito.** A
devolução espelha os impostos da nota de origem — e a nota de origem vivia numa coluna `text` do
banco, sem tributação por item nenhuma. Duas coisas mudaram (V051): o XML da entrada passou a ser
**arquivado no MinIO** como o de saída já era (ADR-014), e nasceu **`entrada_nfe_item`** (47
colunas, com RLS), gravada no ato da entrada. Ela **tem IPI**, que `documento_fiscal_item` não tem:
a NFC-e de saída não destaca IPI, a nota de compra do fornecedor destaca — copiar o schema da irmã
teria perdido a coluna em silêncio.

⚠️ **A consequência disso é um limite duro que a tela precisa dizer por extenso:** entrada anterior
a hoje **não é devolvível**, e entrada manual ou por planilha **nunca** será. Mostrar essas
entradas na lista e falhar na confirmação seria pior.

**A regra que o dono do produto enunciou, e as duas maneiras de furá-la.** O máximo devolvível é o
**menor entre o saldo da nota e o estoque atual** — "entrou 10 e o estoque tem 8, devolve 8; o
estoque tem 12, devolve 10". Escrever a validação foi fácil; o que apareceu relendo foram dois
furos: **linha repetida** (duas linhas de 5 do mesmo produto passavam uma a uma contra um estoque
de 8) e **corrida com o PDV** (a validação usava o estoque que a grid tinha lido). Hoje o pedido é
somado por variação antes de validar e o estoque é lido dentro da transação com a linha travada
(`FOR UPDATE`, ordenado por `id_variacao` contra deadlock).

**⚠️ Esse segundo limite é exceção deliberada à política de estoque negativo — e o javadoc diz
"não uniformize isto".** Não é inconsistência, são situações opostas: na **venda**, o produto está
na mão do operador e quem está errado é o cadastro — travar a venda por um número atrasado custaria
a venda de algo que existe. Na **devolução**, ninguém tem a mercadoria na mão e o sistema vai
emitir uma NF-e declarando que ela está saindo; se o estoque diz zero, a nota afirmaria à SEFAZ uma
saída física que não aconteceu. A diferença não é de rigor, é de **o que a operação afirma**: a
venda registra um fato observado, a devolução declara um fato que ninguém conferiu.

**O CFOP é a única coisa que a devolução não copia da entrada.** `6101` é "venda de produção do
estabelecimento" — do fornecedor. Repetido na nossa nota, declararia que **nós** vendemos produção
própria. De-para fechado com o dono do produto (`x.101/102/103/104 → x.202`,
`x.401/403/405 → x.411`, primeiro dígito acompanhando o sentido). CFOP fora do de-para **falha na
montagem** com o motivo por extenso (F11) — remessa e consignação têm regra própria e precisam de
decisão, não de palpite.

**Duas parametrizações no lugar de duas cópias.** `MontadorXmlNfeDevolucao` ganhou `tpNF`/`idDest`
em vez de uma segunda cópia de ~500 linhas de XML já homologado (o compilador achou os 2 chamadores;
os 13 testes do consumidor seguiram verdes). E o cancelamento do evento 110111 passou a servir os
dois modelos: o corpo — onde mora a regra "não reverta sem confirmação da SEFAZ" (F5) — existe uma
vez só, e o que difere (prazo por UF, vocabulário das mensagens) entra por parâmetro.

**A ordem das operações se inverte entre efetivar e cancelar**, e isso é proposital: efetivar grava
e depois emite (se a SEFAZ estiver fora, a devolução vale e a carga não sai); cancelar cancela a
nota **antes** de reverter o estoque (a nota autorizada já declarou que a mercadoria saiu —
devolver o estoque antes da confirmação deixaria a loja com a mercadoria em casa e um documento
válido dizendo o contrário). Em cada caso, o passo que vem primeiro é o que deixa o sistema no
estado *menos* errado se o outro falhar.

**Três peças já existiam e encolheram o schema:** `tipo_operacao_fiscal.DEVOLUCAO_FORNECEDOR`
(semeada na V035 "porque acrescentar valor a ENUM depois é ALTER TYPE" — a decisão se pagou hoje),
`documento_fiscal.id_movimento` e `documento_fiscal_referencia.id_documento_referenciado`, esta já
documentada como "preenchida quando a nota é do próprio Niner" — ou seja, já previa referenciar
nota de **terceiro** só pela chave, que é exatamente o caso da nota do fornecedor. Sobraram uma
coluna (`id_movimento_origem`) e uma view (`vw_entrada_saldo_devolucao`, que ignora `cancelado` dos
dois lados — é o que faz o cancelamento devolver o saldo sem UPDATE em linha nenhuma).

**⚠️ Um achado de diagnóstico que vale para o repositório inteiro:** `rs.getObject(coluna,
Long.class)` numa coluna `integer` **não funciona** — o driver recusa com *"conversion to class
java.lang.Long from int4 not supported"*, e o erro chega ao controller como
`DataIntegrityViolationException`, que o handler global traduz para **"Registro em uso por outro
cadastro — não pode ser excluído"**. Um erro de *leitura* vira uma mensagem sobre *exclusão*, num
GET, e o diagnóstico vai inteiro para o lado errado. Custou uma hora às cegas porque o handler
descartava a causa: ele agora **loga a exceção original** antes de responder o 409 genérico.

**Migrations:** V051 (XML da entrada no bucket + `entrada_nfe_item`), V052 (`ALTER TYPE
tipo_movimento ADD VALUE 'DEVOLUCAO_COMPRA'`, sozinha no arquivo — o valor novo não pode ser usado
na mesma transação em que é criado), V053 (`id_movimento_origem` + a view).

**Testes: 891 (+13), tudo verde.** `DevolucaoCompraCrudTest` (7) prende os dois tetos com os
números que o dono do produto usou, a linha repetida, a devolução parcial, o cancelamento e o
isolamento entre tenants; `CfopDevolucaoCompraTest` (4) prende o de-para inteiro.

### 2026-08-20 — Duas caçadas de bug em paralelo: 6 defeitos corrigidos, 1 mantido por decisão

Dois agentes de leitura varreram o repositório ao mesmo tempo (back e front), depois de um dia com
7 commits grandes. Nove achados; seis corrigidos, um virou decisão registrada, dois descartados por
mim como fora de escopo.

#### 🔴 O mais grave era meu, de hoje, e passou por 877 testes verdes

`FiscalInutilizacaoRepositorio:166` — acrescentei o campo `uf` ao record `InutilizacaoParaArquivar`
e o `perl` que devia acrescentar a coluna à query **falhou em silêncio**. `buscarParaArquivar`
passou a estourar *"The column name uf was not found in this ResultSet"* — e o erro era **engolido**
pelo `catch (RuntimeException) → log.warn` do próprio `ArquivamentoXmlService`.

Efeito: a inutilização respondia **200**, o operador via sucesso, e **nenhum XML de inutilização
chegava ao MinIO** — guarda legal de 5 anos (F6/DF21). O job de recuperação retentava a cada 10
minutos, para sempre, com um warn por ciclo que ninguém lê.

**Por que a suíte não pegou:** nenhum teste cobria o arquivamento de inutilização. As três consultas
irmãs em `DocumentoFiscalRepositorio` tinham; esta não. É a mesma lição de sempre — *código sem
teste no caminho é código que ninguém prova*. Corrigido com `JOIN empresa` (com `id_tenant`
explícito no `ON`, P8) e coberto por `ArquivamentoXmlTest.inutilizacaoAutorizadaTemOXmlArquivadoNoBucket`.

Registro do método, porque se repetiu hoje: **`perl -0pi -e` falhou em silêncio várias vezes** nesta
sessão (padrão que não casa = arquivo intacto, exit 0). Onde o resultado importa, conferir com
`grep` depois — foi assim que este bug nasceu.

#### Os outros cinco

| Onde | O que era |
|---|---|
| `CsrtPorUf.tsx` | 🔴 `existente` era calculado mas **nunca preenchia o formulário**. Trocar o CSRT de uma UF que usava `idCSRT 02` o rebaixava para `01` e **apagava a observação**, em silêncio — e o `idCSRT` vai em claro no `infRespTec`: seria toda NF-e daquela UF voltando `cStat 974` com a tela dizendo "salvo". Corrigido com um efeito que repõe o formulário **uma vez por seleção** (não a cada chegada de dado, senão um refetch de janela apagaria o que o usuário está digitando) |
| `CsrtPorUf.tsx` | dizia *"salvo"* mesmo com o campo do código vazio. O campo é `type="password"` — colagem que não pegou é invisível. Agora distingue *"trocado"* de *"o código gravado foi mantido"* |
| `CsrtAdminService` | `@Pattern` **passa quando o valor é `null`** (especificação do Bean Validation) → `idCsrt` ausente estourava o `NOT NULL` e o handler global respondia **409 "Registro em uso por outro cadastro"** numa criação. `@NotBlank` junto |
| `ConfiguracaoGeralForm` | invalidava `['desconto-venda']` contra a chave real `['pdv-desconto-venda']` — **não atingia query nenhuma**, e o teto de desconto ficava o antigo no PDV até um F5 |
| `ComprovantePapeletaModal` | decidia emitir NFC-e com `configFiscal !== undefined`, servindo cache desatualizado: desligar "emitir fiscal após a venda" e voltar ao PDV ainda abria o popup de CPF na janela do refetch. A tela irmã (`DevolucaoProduto`) já esperava `isFetching` e documentava o porquê — mesmo tratamento aplicado |
| `EmpresaForm` | ✕ com rota fixa em vez de `navigate(-1)`, criando laço no histórico (fechar a lista devolvia ao formulário recém-fechado) |

#### ⚠️ O achado mantido: `CancelamentoVendaService` apaga `caixa_detalhe` sem o guard

É o único dos quatro `DELETE FROM caixa_detalhe` do sistema que não chama
`exigirCaixaAbertoParaDesfazer`. A justificativa escrita na spec — *"não existe rota pra reabrir um
caixa já fechado"* — **morreu em 2026-08-14**, quando a reabertura e o guard passaram a existir.

**O dono do produto decidiu manter.** O custo aceito está agora escrito com todas as letras em
`docs/telas/cancelamento-venda.md`: cancelar venda de caixa já fechado apaga o lançamento sem aviso,
e a conferência gravada passa a afirmar um total que não existe mais. Vale reavaliar quando houver
cliente real com conferência de caixa valendo dinheiro. O `CLAUDE.md` passou a citar isso como **a
única exceção conhecida** da regra geral — rotina nova continua obrigada a chamar o guard.

O que interessa registrar não é a decisão em si: é que uma **justificativa vencida** ficou seis dias
parecendo regra vigente. É o que [[feedback_non_goals_apodrecem]] já dizia, agora com exemplo.

**878 testes verdes**, `tsc -b` limpo em `web/` e `admin/`.
### 2026-08-20 — Importação de estoque recusa código de barras na faixa do Nainer, e o prefixo vira dado

Pedido do dono do produto, que começou como um estudo maior — "criar um campo para o código de
barras do sistema antigo" — e **encolheu para a solução certa** depois de conversar. Vale registrar
o encolhimento, não só o resultado.

**O problema real.** O lojista que contrata o Nainer já tem produtos cadastrados e **etiquetas
impressas** pelo sistema antigo. Esses códigos entram por `EAN_CODIGO_BARRAS` na importação de
estoque inicial. Se um deles começar com `9`, ele cai na faixa do nosso gerador
(`gerar_ean13_interno()` monta `9` + `id_banco` + sequencial + DV).

**Por que isso é pior do que parece:** o sequencial **cresce**. Um código legado `9001000041032`
importado hoje não conflita com nada — e passa a conflitar no dia em que o contador alcançar
`41032`. Nenhuma conferência no momento da importação evita isso, porque a colisão **nasce depois**.
Só bloquear a faixa resolve. Foi o argumento que fez a proposta inicial (campo novo + checagem
cruzada + índice) dar lugar a **uma regra de uma linha**.

**A decisão:** código começando por prefixo reservado → **nada é importado**. Não é aviso, não é
linha rejeitada: a planilha inteira volta, com a lista das linhas, para o lojista corrigir. Nas
palavras dele, *"pra não gerar mal-entendidos"* — importar metade deixaria o cliente sem saber qual
metade entrou. Sai barato porque `EstoqueImportador.processar` é **uma transação só** e a tela já
tem o passo **Validar** antes do Importar: o erro aparece antes de qualquer gravação.

**O prefixo virou dado (V050), e esse é o ponto de desenho.** Ele avisou que o `9` pode mudar um
dia. Escrever `'9'` no importador criaria uma **segunda cópia** da regra — e este projeto já foi
mordido três vezes por lista duplicada (as UFs, as siglas do CSRT, o cUF). Então:

- `cfg_ean_gerador.prefixo` — o que o gerador **usa**; `gerar_ean13_interno()` passou a lê-lo em vez
  de carregar o literal;
- `cfg_ean_gerador.prefixos_reservados text[]` — tudo que já foi usado, que é o que a importação
  **barra**, via `prefixos_ean_reservados()` (`niner_app` continua sem grant na tabela, só na função);
- **CHECK `prefixo = ANY(prefixos_reservados)`** — impossível trocar e esquecer de reservar. Isso
  importa porque os SKUs já emitidos continuam com o prefixo antigo: liberar a faixa velha
  reabriria a colisão para trás.

Trocar o prefixo passa a ser `UPDATE cfg_ean_gerador SET prefixo = '8', prefixos_reservados =
prefixos_reservados || '8'::text` — **sem deploy**, e a importação acompanha sozinha.

**Duas armadilhas medidas escrevendo isto**, as duas viraram comentário no código:

1. `query(String[].class)` **não lê array do Postgres** — o driver devolve `PgArray` e o Spring não
   converte (*"Value [{9}] is of type PgArray"*). Tem de passar pelo `ResultSet`. O teste pegou
   antes de virar bug em produção.
2. `prefixos_reservados || '8'` sem `::text` estoura com *"malformed array literal"* — o Postgres lê
   o `'8'` como literal de array. O `::text` não é decorativo.

**4 testes novos** (877 no total): o prefixo do código gerado vindo da tabela e não do corpo da
função; a troca sem reservar sendo recusada pelo banco (e a troca correta funcionando, com o antigo
seguindo barrado); a planilha inteira derrubada por uma linha na faixa, **conferindo no banco que
nem a linha boa entrou**; e o caminho normal (EAN de fabricante `789…`) continuando a importar.

⏭️ **O que NÃO foi feito, e por decisão:** nenhum campo novo em `produto_barra`. O código do sistema
antigo continua entrando em `ean` quando não conflita com a nossa faixa — que é o caso da esmagadora
maioria (a praxe do varejo brasileiro para código interno é a faixa `2`, de circulação restrita da
GS1). Se um dia aparecer lojista cujo sistema antigo usava o `9`, aí sim a conversa volta: ou ele
re-etiqueta, ou trocamos o nosso prefixo (que agora é um `UPDATE`).

### 2026-08-20 — A UF de um documento fiscal vem congelada na chave, não da empresa de hoje

Consequência que só apareceu porque o dono do produto perguntou "não seria melhor já corrigir?"
sobre um erro de construção. Os dois erros já estavam corrigidos — mas o primeiro (supor coluna
`uf` em `documento_fiscal`, quando ela vem de `empresa` por join) escondia uma questão de desenho
que valia decidir.

**O problema.** Documento fiscal é registro **imutável** (F6/P3), mas a UF dele estava vindo de
`empresa.estado` — o valor de **hoje**. Se um lojista mudar a empresa de UF, todo o histórico passa
a ser lido com a UF nova. O caso que dói de verdade é o **cancelamento**: é por essa UF que
`CancelamentoNfceService` escolhe **para qual SEFAZ** transmitir o evento. Depois de uma mudança de
UF, o cancelamento de uma nota antiga iria para o autorizador errado e voltaria recusado.

**A resposta já existia no próprio dado:** a **chave de acesso** carrega o `cUF` nas posições 1–2,
gravado no ato da emissão. `ChaveAcesso.ufDaChave(chave)` faz o caminho inverso, com o mapa
**derivado** do direto (`CODIGO_UF.entrySet().stream()...`) — nunca uma segunda lista à mão, que é
o erro que a `CsrtAdminService` já tinha cometido e que foi desfeito ontem.

Aplicado nas 5 consultas que carregam um documento existente (cancelamento, consulta,
reprocessamento, arquivamento do documento e do evento), via um helper único
`ufDoDocumento(rs)`: chave primeiro, UF da empresa como último recurso. Sem chave — documento que
nunca recebeu número (`NAO_EMITIDO`, `RASCUNHO`) — não há evento nem arquivamento para errar o
destino, então a UF da empresa é o melhor palpite e serve.

Ficam **de propósito** com a UF da empresa: `empresasComFilaPendente` (lista por empresa, sem chave)
e o contexto de **inutilização** (que não é documento e não tem chave — é faixa de numeração).

**Probabilidade × princípio:** a janela real é estreita — a NFC-e só cancela em 30 min, então
exigiria a loja mudar de UF entre a venda e o cancelamento (24 h na NF-e 55). Foi corrigido pelo
princípio, não pela estatística: o projeto trata imutabilidade de documento fiscal como regra, e o
custo eram 30 minutos.

`ChaveAcessoUfTest` (3 casos, unitário puro): a chave real do B0 devolvendo PR, SP/AM/AC pelo
`cUF`, chave ausente ou `cUF` desconhecido devolvendo `null` em vez de inventar, e as **27 UFs
dando a volta completa** pelo mapa inverso. **873 testes verdes.**
### 2026-08-20 — P3: o `dhEmi` sai no fuso da UF, e `empresa.estado` só aceita UF que existe

Fecha o fuso horário. O `dhEmi` do XML e todo "hoje" do plano do lojista saíam de
`America/Sao_Paulo` **fixo** — correto para 21 UFs, errado em 1 h para AM/MT/MS/RO/RR e em 2 h
para o AC.

**Por que isso nunca apareceu como rejeição da SEFAZ.** A conversão usa `atZoneSameInstant`, que
**preserva o instante**; o `dhEmi` sempre descreveu o momento certo, só com o offset de outro
estado. As validações da SEFAZ comparam instante (228 "emissão muito atrasada", 703 "posterior ao
recebimento"), então nada era recusado. O defeito era silencioso — o pior tipo:

| Onde | O que sairia numa loja de Manaus |
|---|---|
| `dhEmi` | venda das 14:00 declarada como **15:00−03:00** (16:00 no Acre) |
| cupom × XML | DANFCE formata no fuso do navegador → cupom **14:00**, consulta da SEFAZ **15:00** |
| virada de mês | venda de 31/08 às 23:30 → `dhEmi` de **01/09**, chave com `AAMM = 2609`, XML arquivado na pasta **2026/09** |
| inutilização | no AC, 31/12 às 22:00 pedia faixa do **ano seguinte** |

Todos passam agora por `FusoDaUf.de(uf do emitente)`: `MontadorXmlNfce`, `MontadorXmlNfeDevolucao`,
`MontadorEventoCancelamento`, `FiscalInutilizacaoService` e `ArquivamentoXmlService` (este ganhou
`uf` em três records/queries — o mês da pasta tem de bater com o mês do `dhEmi`, senão o contador
procura no lugar errado). No plano do lojista, `FluxoCaixaService` e `RecebimentoCrediarioService`
passaram a usar `FusoDaLoja.hoje(jwt)` — o dia da **loja onde o usuário está logado**, que é a
referência certa num relatório que soma várias empresas.

#### `empresa.estado` deixou de ser texto livre (decisão do dono do produto)

A coluna nasceu anulável, sem CHECK e sem validação nenhuma (V014). Isso passou a ser perigoso
quando a UF virou a fonte do **fuso** e já era a fonte de **para qual SEFAZ a nota vai**: sigla
errada não falha no cadastro, falha na primeira venda do cliente.

- **Banco (V049):** CHECK com as 27 siglas. ⛔ Nasce **`NOT VALID`** de propósito — uma constraint
  que varre linha existente derrubaria o deploy inteiro se **uma** empresa tivesse 'Paraná' ou
  'XX' gravado, que é exatamente como a publicação parou em 2026-08-19. A migration normaliza
  caixa/espaço, tenta `VALIDATE` e, se não der, **avisa com a query de diagnóstico** em vez de
  abortar. Nada é apagado.
- **API:** `EmpresaService.validarUf` recusa sigla fora das 27, com mensagem que diz o que fazer.
- **Front:** o campo virou `<select>` das 27 (era `<input maxLength={2}>`), como já era em Cliente
  e Fornecedor.
- ⚠️ **NULL continua valendo**: o signup cria a empresa **sem** UF (o funil não pergunta) e o
  lojista preenche depois. `NOT NULL` aqui quebraria o cadastro de conta nova. Quem **exige** a UF
  é o caminho fiscal, que falha explicitamente sem ela.

#### O guarda também pegou o lado Java

`LocalDate.now()` **sem argumento** usa o fuso da JVM — definido só em produção (`TZ` no
`docker-compose.prod.yml`), UTC em dev. Ou seja: o bug não reproduz na máquina de quem escreve.
`ComparacaoDeDataNoFusoCertoTest` passou a reprovar `LocalDate/LocalDateTime/LocalTime.now()` sem
fuso; `OffsetDateTime.now()` fica de fora de propósito, porque representa um **instante** e
comparar instante não depende de fuso.

**Novo:** `EmpresaUfValidaTest` (5 casos) confere que as **três** listas de UF do projeto concordam
— o CHECK do banco, `ChaveAcesso.CODIGO_UF` (que monta a chave) e `FusoDaUf` (que decide o fuso) —
e que todo fuso cai num dos quatro offsets do Brasil, o que pega erro de digitação em identificador
IANA (`America/Manaos` compilaria e só explodiria no cliente).

**870 testes de backend verdes** (eram 865), `tsc -b` limpo.

**Erros que a construção cometeu e valem registro:** `documento_fiscal` **não tem coluna `uf`** — a
UF vem de `empresa` por join, como `DocumentoParaCancelar` já fazia; e o regex de `localtime` do
guarda não pode ser case-insensitive, senão pega o tipo `LocalTime` do Java.

### 2026-08-20 — Fuso horário: a data que o usuário lê, o mês que a plataforma cobra, e um guarda para não esquecer de novo

Terceira frente do dia, saída de uma pergunta do dono do produto ("o Nainer vai rodar no país
inteiro, e o Brasil tem 4 fusos — como resolver?"). Uma auditoria em paralelo mediu o estado real, e
o que ela achou vale mais que o plano que eu tinha.

**Decisão do dono do produto: o fuso vem da UF, sem exceção por município.** Fernando de Noronha
(UTC−2, mas é PE) e o extremo oeste do Amazonas (UTC−5, mas é AM) ficam com **1 h de desencontro
conhecido e aceito** — cupom com 1 h a menos e venda entre 00:00 e 01:00 caindo no caixa do dia
anterior. Está registrado no javadoc de `FusoDaUf` para não ser "consertado" daqui a seis meses por
quem achar que é bug. O mapa guarda **identificador IANA**, nunca offset: se o horário de verão
voltar, o `tzdata` resolve e nenhuma linha muda.

**Onde o padrão da NF-e ajuda:** `dhEmi` é do tipo `TDateTimeUTC`, que exige offset explícito
(`AAAA-MM-DDThh:mm:ss±hh:00`, conferido no `tiposBasico_v4.00.xsd`). A SEFAZ compara **instantes**;
nenhuma delas assume UTC−3. Multi-fuso é suportado pelo padrão — o que ele exige é que o offset
declarado seja o certo.

#### 🔴 O que estava errado AGORA, em Curitiba (não era multi-UF)

O driver devolve `timestamptz` como `OffsetDateTime` em **UTC**. Formatar direto o que vem do banco
imprime **3 h a mais**. A mensagem de prazo do cancelamento de NFC-e dizia *"autorizada em 17/08 às
19:44"* para uma nota autorizada às **16:44** — e o mesmo defeito estava em mais quatro lugares,
onde a **data** (não a hora) saía do dia seguinte depois das 21h:

| Onde | O que o usuário via |
|---|---|
| `CancelamentoNfceService` | hora da autorização 3 h à frente |
| `CancelamentoVendaService` | "já foi cancelada em 20/08" para cancelamento das 21:30 de 19/08 |
| `EntradaMercadoriaService` | idem, na entrada já cancelada |
| `CancelamentoDevolucaoService` | idem, na devolução já cancelada |
| `CaixaService` | observação de "fechado com divergência" com hora do **container** (só definida em produção — dev e produção gravavam horas diferentes para o mesmo fechamento) |

Todos passam agora por `comum.tempo.FusoDaLoja`, que resolve o fuso pela UF da empresa. Regra
nova: **nenhum `DateTimeFormatter` do backend recebe um `OffsetDateTime` sem passar por lá.**

#### O plano da plataforma comparava data em UTC — e um dos casos é dinheiro

O plano do lojista estava 100% varrido desde ontem; o de controle, 0% — e eram exatamente os
**únicos 15 `::date` sem conversão** de todo o `src/main`. Os dois que passam de cosmético:

- **`LimiteVendasService`** — a competência da cota virava às 21:00 do último dia do mês. Uma loja
  **bloqueada por cota estourada voltava a vender às 21:05 de 31/08**, porque o contador zerava 3 h
  antes da hora; e as vendas das 21:00 à meia-noite eram debitadas do mês seguinte.
- **`BackupJob`** — `localtime` é o relógio da **sessão** (UTC): backup configurado para 03:00
  disparava às **00:00 de Brasília**, no meio do movimento de quem fecha tarde.

Mais: "Minha Conta" mostrando *agosto/2026 com 0 vendas* às 22h de 31/08 (e **não reproduzia em
dev** — em produção a JVM está em `America/Sao_Paulo` pelo `docker-compose.prod.yml` e o banco em
UTC, então os dois lados discordavam só lá), funil de aquisição com lead da noite caindo no dia
seguinte, "contas perto do limite" sumindo na virada do mês, e vigência de assinatura ganhando um
dia a cada pagamento noturno.

⚠️ **Aqui o fuso é constante de propósito** (`comum.tempo.FusoDaPlataforma`): competência de cota,
agenda de backup e vigência são fatos do negócio da **Vetor** (P9), e um tenant com empresas em UFs
diferentes não teria "fuso do tenant" para desempatar. **V048** acerta junto os `DEFAULT` de coluna
de `uso_tenant`, sem reescrever competência já gravada — histórico reescrito com regra nova produz
número que nunca foi apurado.

#### O guarda: a convenção passou a ser verificada, não lembrada

`ComparacaoDeDataNoFusoCertoTest` é o **primeiro teste do projeto que lê código-fonte** (os outros
invariantes — privilégio, autorizador por UF — são verificados contra o banco). Varre
`src/main/java` e reprova `::date`, `CURRENT_DATE`, `localtime`, `age(` e `EXTRACT(… FROM now())`
sem `AT TIME ZONE`. Duas lições da construção dele, ambas viraram comentário no arquivo:

- `localtime` **não** pode ser case-insensitive — pegava o tipo `LocalTime` do Java em 11 linhas
  legítimas de DTO e `getObject`. Palavra-chave SQL é toda minúscula ou toda maiúscula; tipo Java é
  CamelCase, e isso basta para separar.
- comentário `--` dentro de *text block* é comentário: o primeiro a reprovar o teste foi **o próprio
  comentário que explicava a correção**.

E o guarda tem teste do guarda (`oGuardaReprovaAConstrucaoErradaEAceitaACerta`) — regex quebrado
viraria verde vazio para sempre.

**865 testes de backend verdes** (eram 862).

⏭️ **Fica para a próxima etapa:** o `dhEmi` do XML e os `LocalDate.now()` do plano do lojista ainda
saem de `America/Sao_Paulo` fixo. Como o instante é preservado (`atZoneSameInstant`), **não há
rejeição da SEFAZ** e as 21 UFs em UTC−3 estão corretas; o que muda para AM/MT/MS/RO/RR/AC é a hora
declarada, a virada de dia na chave de acesso e o `<ano>` da inutilização na virada do ano. É
pré-requisito do primeiro cliente desses seis estados, não de hoje.

### 2026-08-20 — As 27 unidades da federação em `cfg_uf_autorizador`: o fiscal deixou de ser "do Paraná"

Fechando a outra metade do trabalho de hoje. A tabela que governa **para onde a nota é
transmitida** tinha **4 linhas, todas do PR** — e `SefazAutorizadorService` responde 409 ("a UF
ainda não está cadastrada") para qualquer outra. Na prática, um lojista de fora do Paraná não
emitia nada. A arquitetura estava pronta desde a V034 (F10: "a UF é dado, não código"); faltava o
**dado**.

**V047 carrega 108 linhas** — 27 UFs × 2 modelos (55/65) × 2 ambientes (produção/homologação).

**Como isso foi feito sem digitar 108 URLs.** Transcrever endereço de webservice é exatamente o
tipo de trabalho onde o erro entra e só aparece na primeira transmissão real do cliente. O SQL foi
**gerado por script** a partir de arquivos machine-readable do projeto `nfephp-org/sped-nfe`
(`autorizadores.json`, `wsnfe_4.00_mod55.xml`, `wsnfe_4.00_mod65.xml`, `uri_consulta_nfce.json`),
com o `codigo_uf_ibge` vindo do **mapa do próprio projeto** (`ChaveAcesso.CODIGO_UF`).

**A validação que dá confiança na carga:** as linhas do **PR** que a fonte gerou saíram
**idênticas** às que a V034 já tinha — e aquelas autorizaram NFC-e e NF-e de verdade contra a
SEFAZ-PR (B0, B7, B9), incluindo o achado do `cStat 878` sobre a URL de consulta pública não ser o
host do webservice. A mesma fonte reproduz, sem ajuste, o que se sabe que funciona. As 4 linhas do
PR foram **preservadas** (`ON CONFLICT DO NOTHING`): carregam observações escritas à mão que valem
mais que a carga automática.

**⚠️ A armadilha que a carga revelou: o autorizador muda com o MODELO, não só com a UF.**

| UF | NF-e 55 | NFC-e 65 |
|---|---|---|
| BA | **próprio** | SVRS |
| PE | **próprio** | SVRS |
| MA | **SVAN** | SVRS |

Quem assumir "um autorizador por UF" manda a NFC-e da Bahia para o endpoint da NF-e da Bahia. Por
isso a chave da tabela sempre foi (UF, modelo, ambiente) — e agora há teste provando os três casos.
Distribuição final: modelo 55 = 10 autorizadores próprios + 16 SVRS + 1 SVAN; modelo 65 = 8
próprios + 19 SVRS.

**O que a migration deliberadamente NÃO afirma** (está escrito no cabeçalho dela, com o porquê):
`aliquota_interna` e `aliquota_fcp` ficam nulas — não são consumidas por nenhum caminho de código
(o FCP do cálculo vem de `regra_fiscal`, do perfil do lojista) e preencher 27 alíquotas de memória
seria inventar dado tributário; `exige_cbenef` fica `false` (há UF que exige `cBenef`, mas marcar
sem confirmar quebra a emissão); `prazo_cancelamento_min` = 1440 no modelo 55 (Ajuste SINIEF
07/2005, nacional) e 30 no 65 (o mais comum, e o mesmo default que o Java já usava). E
`url_consulta_publica` sai **como cada SEFAZ publica** — parte com esquema, parte sem: não é
defeito de carga, é o endereço que o consumidor vê impresso no cupom.

**6 testes novos** em `SefazAutorizadorCrudTest` (13 no total): as 27 UFs com as 4 combinações; o
`cUF` de cada linha batendo com o mapa que monta a chave de acesso (divergir aí produz chave
inválida, e a SEFAZ recusa sem dizer que o problema é esse); o autorizador mudando com o modelo em
BA/PE/MA; QR Code e consulta pública presentes em toda linha de NFC-e (sem os dois,
`MontadorXmlNfce` recusa montar o `infNFeSupl`); a consulta pública sendo do estado do **emitente**
mesmo quando quem autoriza é a SVRS; e os seis serviços não nulos em toda linha. O teste que usava
**SP** como exemplo de "UF não cadastrada" passou a usar uma sigla inválida — SP agora existe.

**Auditoria de "o que mais está preso ao Paraná":** nenhum `if (uf == "PR")` no código de produção;
os únicos literais de UF são o mapa de `cUF` e as listas de siglas (`ESTADOS_UF` no ERP, que já
tinha as 27). `CsrtAdminService` deixou de manter a **segunda cópia** das 27 siglas e passou a
validar pelo `ChaveAcesso` — duas listas divergindo fariam uma aceitar o que a outra recusa.

**862 testes de backend verdes.**

⏭️ **O que continua valendo antes de vender para uma UF nova:** bater o `url_status_servico` dela
(serviço mais barato, não gera documento) e obter o **CSRT** daquele estado — endereço de
webservice muda sem aviso, e o CSRT é por UF.

### 2026-08-20 — CSRT deixa de ser variável global e vira cadastro por UF (o Nainer é para os 27 entes, não para o Paraná)

Decisão do dono do produto: **o Nainer vai ter lojista em todos os 26 estados e no Distrito
Federal** — o Paraná é só onde a homologação foi feita. Isso condena um desenho que estava no
código desde o B7: o **CSRT** (Código de Segurança do Responsável Técnico, NT 2018.005) morava em
**duas variáveis de ambiente únicas para a aplicação inteira**, e ele é emitido pela SEFAZ de
**cada** UF.

**Por que isso seria pior que "não funciona".** Com o par global, a primeira nota de um lojista de
São Paulo sairia carimbada com o código do Paraná — estruturalmente válida, assinada, transmitida
e recusada com `cStat 974` ("CNPJ do responsável técnico diverge do cadastrado"), que aponta para
o CNPJ e não para a UF. O diagnóstico iria para o lugar errado, num cliente novo, na primeira
venda. E havia um caminho pior ainda: a exigência do par é **a critério da UF** — o próprio PR
mostra que varia dentro do mesmo estado, cobrando na NF-e 55 e não na NFC-e 65. Numa UF que cobre
no modelo 65, quem quebraria não seria a devolução: seria a **venda do dia a dia**.

**O que mudou** (V046, arquivo novo — migration aplicada é imutável):

- **`cfg_csrt_resptec`**, chaveada por **(UF, ambiente)** — homologação e produção são cadastros
  separados no portal. Global e sem RLS, igual a `cfg_uf_autorizador`: é dado da casa de software,
  não do lojista. O código fica **cifrado** (AES-256-GCM, chave mestra fora do banco).
- **`cfg_uf_autorizador.exige_csrt`**, por (UF, modelo, ambiente). A regra da UF virou linha, não
  `if` — mesma filosofia do F10 que já governa endpoint, prazo e alíquota. Semeada com o que foi
  **medido** ao vivo: PR 55 = sim, PR 65 = não.
- **`CsrtService`** resolve na ordem: linha da UF → fallback do `application.yml`, **e só se a UF
  pedida for a declarada em `NINER_FISCAL_RESPTEC_UF`** (default `PR`) → nada. A amarra da UF é o
  ponto: sem ela o fallback seria curinga e reproduziria exatamente o defeito que a mudança
  conserta.
- **Tela nova no backoffice** — `/csrt`, "CSRT por UF" (`docs/telas/admin-csrt-por-uf.md`).
  SUPER_ADMIN grava, o resto do staff lê. Segredo entra e não sai: a tela recebe só "definido" e o
  `idCSRT`, que é público porque vai no XML em claro. Campo em branco **mantém** o código gravado,
  mesma convenção da senha de SMTP. Precisa existir sem deploy porque CSRT novo chega quando entra
  lojista de estado novo — evento de horário comercial.
- **F11 com a UF na mensagem**: "o modelo 55 exige o CSRT da UF **SP**, que não está cadastrado" em
  vez de citar duas variáveis de ambiente que deixaram de ser a fonte.

⚠️ **`cfg_csrt_resptec` é o único `cfg_*` que `niner_app` escreve** — os outros são carga por
script do dono. Como o teste de integração conecta como superusuário do container e não enxerga
`GRANT` nenhum, o invariante foi preso em `PrivilegiosNinerAppTest`
([[feedback_testcontainers_nao_usa_niner_app]]).

**Correção de fato no mesmo dia:** o responsável técnico é a **MITRYUSCASH** (CNPJ
37.829.453/0001-35), **não a Vetor** — quem precisa se cadastrar na SEFAZ e obter o CSRT é ela. O
XML sempre esteve certo; a documentação dizia "Vetor" em 12 lugares e os três campos de contato do
grupo carregavam dado da Vetor, indo em toda nota de todo lojista. Contato agora é `MITRYUSCASH` /
`suporte@nainer.com.br` / 4133334444.

**856 testes de backend verdes** (eram 845): `CsrtPorUfTest` novo com 10 casos e mais um em
`PrivilegiosNinerAppTest`. `tsc -b` limpo no `admin/`.

⏭️ **O que este trabalho NÃO resolve, e é maior:** `cfg_uf_autorizador` tem **só as 4 linhas do
Paraná**. Endpoint de autorização, prazo de cancelamento, alíquota interna e FECOP das outras 26
UFs não estão carregados, e `SefazAutorizadorService` responde 409 ("a UF ainda não está
cadastrada") para qualquer estado fora do PR. A arquitetura está pronta para isso desde a V034 —
falta o **dado**, que precisa vir do portal de cada SEFAZ, não de memória.

### 2026-08-20 — Correção de fato: o responsável técnico do `infRespTec` é a MITRYUSCASH, não a Vetor

Esclarecimento do dono do produto: o **responsável técnico** declarado em toda NFC-e/NF-e é sempre
a **casa de software** — **MITRYUSCASH**, CNPJ **37.829.453/0001-35** — e **não a Vetor**. Quem
precisa se cadastrar no portal da SEFAZ de cada UF e obter o **CSRT** (a única pendência do módulo
fiscal, `cStat 974` hoje) é a MITRYUSCASH.

**O XML sempre esteve certo**: `niner.fiscal.resp-tec.cnpj` já era `37829453000135`. O erro estava
só na **documentação**, que dizia "a Vetor precisa se cadastrar" em 12 lugares —
`docs/MODULOFISCAL.md` (§9.9, §17.0, tabela do B9 em §17.1, anexo de origem dos campos),
`docs/PROGRESSO.md` (3), `docs/telas/devolucao-produtos.md` (2), o Javadoc de
`NinerProperties.RespTec` e os comentários do `application.yml`. Todos corrigidos.

**Os três campos de contato do grupo passaram a ser da MITRYUSCASH**, que antes carregavam dado da
Vetor e iam em **toda nota de todo lojista**: `xContato` **MITRYUSCASH**, `email`
**suporte@nainer.com.br**, `fone` 4133334444 (inalterado). A SEFAZ não confere esses três contra o
cadastro — o que ela compara é o CNPJ, e é por isso que a rejeição de hoje é 974 e não outra — mas
eles são o canal que o fisco usa para falar com quem desenvolve o emissor, então precisam ser reais.
Alinhados também os *fixtures* dos testes e o `application.yml` de teste, para o valor errado não
voltar por cópia. **59 testes verdes** (`MontadorXmlNfceTest` 36, `MontadorXmlNfeDevolucaoTest` 13,
`AssinadorXmlNfeTest` 10) — o primeiro valida o XML contra os XSD oficiais, então a troca do
`xContato` está conferida contra o schema, não só compilando.

### 2026-08-19 — 🔴 Bug real corrigido: "hoje" era o dia do banco (UTC), não o da loja — o caixa aberto de manhã sumia às 21h

Achado tentando fazer uma venda de teste às 21:30: `POST /api/v1/pdv/vendas` respondia **"Não há
caixa aberto hoje para este usuário"** com o caixa aberto de manhã e nunca fechado. A sessão do
Postgres roda em `Etc/UTC`, então `CURRENT_DATE` vira o dia **seguinte** às 21:00 de Brasília —
e `caixa_mestre.data_abertura::date = CURRENT_DATE` deixa de casar. Toda loja que atende depois
das 21h perdia o caixa no meio do expediente, e junto o Recebimento de Crediário e a baixa em
dinheiro de Contas a Pagar, que passam pelo mesmo guard.

O bug não era do caixa, era **sistêmico**: só três arquivos tinham sido corrigidos antes (Entrada
de Produtos, Contas a Pagar e Fluxo de Caixa, todos por sintomas isolados); o resto do sistema
continuava comparando `timestamptz::date` em UTC. Levantamento completo antes de mexer, e o
segundo caso grave apareceu aí: `HorarioAcessoService` montava a janela de acesso sobre a data
errada **e** consultava o dia da semana errado (`EXTRACT(ISODOW FROM now())` em UTC ⇒ sexta às
21h é "sábado"), expulsando do sistema o usuário com horário controlado. Os outros ~14 erravam
em silêncio — venda feita depois das 21h caía no dia seguinte em todo filtro e relatório
(Pesquisa de Vendas, Cancelamento de Venda/Devolução, DRE, CRM, Kardex, Transferência, Conta
Corrente, Etiquetas, Comissões, Contas a Receber, Recebimento de Crediário, Documentos Fiscais).

Convenção aplicada em todos, a mesma dos três já corrigidos: `(coluna AT TIME ZONE
'America/Sao_Paulo')::date`, e `(now() AT TIME ZONE 'America/Sao_Paulo')` onde havia
`CURRENT_DATE`/`now()` crus. No `HorarioAcessoService` a comparação inteira passou a viver em
timestamp **local**, preservando o cuidado anterior de somar a tolerância em domínio de timestamp
(nunca em `time` puro, que embrulha na meia-noite — bug de 2026-08-11). Antes do sweep foi
conferido no `information_schema` que toda coluna envolvida é `timestamptz`:
`cliente.data_nascimento` e `conta_corrente.data_abertura` são `date` e ficaram de fora, exceto
pelo `age()` do CRM, que passou a comparar contra o hoje local. 794 testes verdes.

### 2026-08-19 — `MODULOFISCAL.md` §17.0: uma seção que responde "o que ainda falta no fiscal?"

Pedido do dono do produto ao encerrar o dia: amanhã ele vai perguntar o que falta no módulo
fiscal, e a resposta não podia depender de reler 1.800 linhas de estudo. §17.0 nova, logo antes do
"ponto de partida da codificação", com as pendências em ordem de urgência: **(1)** o **CSRT**, que
é bloqueante e **não é código** — a MITRYUSCASH precisa se credenciar como responsável técnico na SEFAZ
de cada UF; **(2)** o prazo de **04/01/2027** do IBS/CBS, que atinge 100% da base (DF37) e ainda
carrega uma pergunta em aberto — se o optante do Simples fica dentro do DAS ou pode optar pelo
regime regular (LC 214/2025), o que mudaria CST, `cClassTrib` e talvez exigisse `gTribRegular`;
**(3)** as dívidas pequenas (tabelas `tPag`/`tBand` vigentes, ZIP do contador da DF22, papel
`CONTADOR`, e as notas anteriores a hoje que ficaram sem `documento_fiscal_item` e por isso não
podem gerar devolução fiscal); **(4)** as operações futuras da §4.2, nenhuma começada, com as
candidatas naturais ordenadas por custo/valor — cancelamento por substituição (110112), baixa por
perda (a mais barata: o ajuste já existe), NF-e de venda a contribuinte e Distribuição DF-e.

A tabela de fases (§17) ganhou ✅ em **F0 a F5** — estava sem nenhuma marca, o que fazia o
documento se contradizer com a própria linha do tempo.

**Correção de fato, achada ao levantar as pendências:** o Anexo B item 16 (chave de acesso × CNPJ
alfanumérico) seguia marcado 🔴 *"não confirmado"*, mas tinha sido **respondido no B0 em
2026-08-17**. Conferido de novo no XSD antes de corrigir — o `pattern` de `TChNFe` em
`tiposBasico_v4.00.xsd` é `[0-9]{6}[0-9A-Z]{12}[0-9]{26}`, ou seja, as 12 posições de raiz+ordem do
CNPJ aceitam `A-Z` e a chave acomoda CNPJ alfanumérico nativamente. É exatamente o tipo de linha
que faria alguém repetir uma pesquisa já feita.

### 2026-08-19 — ⭐ B9 fechado no código: NF-e modelo 55 de devolução emitindo de verdade, DANFE em A4 — falta só o CSRT da Vetor na SEFAZ

Fim do bloco B9 (§10.2), o último pendente do módulo fiscal. A devolução de produtos passa a
emitir uma **NF-e modelo 55 de entrada** (`tpNF=0`, `finNFe=4`) referenciando a NFC-e da venda
original, e o DANFE dela sai em **A4**, não na bobina.

**A SEFAZ recusou duas coisas que o XSD tinha aprovado.** As duas só apareceram transmitindo de
verdade contra a SEFAZ-PR de homologação, e cada uma virou teste:

- **cStat 1010** — *"NF-e com referenciamento de documento a nivel de nota e a nivel de item"*.
  A leitura literal do Ajuste SINIEF 8/2026 sugeria referenciar nos **dois** níveis
  (`ide/NFref/refNFe` + `det/DFeReferenciado`), e o XSD aceitou os dois juntos. A SEFAZ não: são
  mutuamente exclusivos. Ficou o de **item**, que é estritamente mais informativo (mesma chave,
  mais o `nItem`) e é o que torna a devolução **parcial** rastreável — o caso comum do balcão. A
  **ausência** do `NFref` virou assert, pra não voltar por refatoração.
- **cStat 975** — *"Obrigatoria a informacao do identificador do CSRT e do Hash do CSRT"*. O
  grupo `infRespTec` existia desde o B7, mas sem o par `idCSRT`/`hashCSRT` (NT 2018.005): a NFC-e
  do PR autoriza sem eles, a NF-e 55 não. Implementado `XmlFiscal.hashCsrt` — SHA-1 de
  `(CSRT + chave)`, o digest **bruto** em Base-64 (28 caracteres). Duas armadilhas registradas no
  código: não é o *hex* do digest que vai pro Base-64, e a chave são os 44 dígitos sem o prefixo
  `NFe` — errar qualquer uma dá cStat 976, que não diz qual das duas foi.

⏭️ **Pendência que não é código:** a SEFAZ-PR agora responde **cStat 974** ("CNPJ do responsavel
tecnico diverge do cadastrado"). A **MITRYUSCASH precisa se cadastrar como responsável técnico no
portal da SEFAZ de cada UF e obter o CSRT de verdade** (`NINER_FISCAL_RESPTEC_ID_CSRT` +
`NINER_FISCAL_RESPTEC_CSRT`, segredo, via `.env`). O valor de dev é de teste e não autoriza nada
— serviu pra provar que o caminho do hash está certo, já que a rejeição avançou de 975 pra 974.
Enquanto isso, montar a NF-e 55 sem CSRT **falha explicitamente** (F11), com o nome das variáveis
na mensagem, em vez de assinar e transmitir uma nota que já se sabe recusada.

**DANFE A4** (`DanfeImprimir.tsx` + `DanfeModal.tsx`, `GET /api/v1/fiscal/documentos/{id}/danfe`):
folha inteira conforme o layout de referência do MOC — canhoto, tarja de homologação, cabeçalho de
3 colunas com a chave em grupos de 4, natureza + protocolo, destinatário, cálculo do imposto,
transportador, itens e dados adicionais. Modelo 65 responde **409**: NFC-e não tem DANFE, tem
DANFCE. Acessível de dois lugares — o popup do vale-mercadoria (logo após a devolução) e a tela de
Documentos Fiscais (reimpressão). Duas armadilhas de CSS que só aparecem no pixel: (1) `@page`
**nomeado** é obrigatório, porque o `@page` global do projeto é `size: 80mm auto` (bobina) e sem
`@page danfe-a4` o A4 sairia espremido em 80mm; (2) nada de `transform: scale` na
pré-visualização — `transform` não encolhe a *caixa* de layout, então o wrapper mantinha a largura
original e o modal ganhava barra de rolagem horizontal para um conteúdo que já cabia (a folha tem
210mm ≈ 794px e cabe inteira). O modal também precisou de `width` explícito, não só `maxWidth`: a
classe `.modal` já define largura própria, menor, e `maxWidth` sozinho apenas limita.

**Comportamento na falha (F3):** a nota rejeitada **não desfaz a devolução** — a mercadoria já
voltou ao estoque e o vale já é do cliente. O popup mostra tarja amarela com o motivo real da
SEFAZ e a frase de que o vale continua válido; a nota fica em Documentos Fiscais para
reprocessar. Diferente de Cancelamento de Venda, onde cancelar a nota é **pré-condição**.

Verificado ponta a ponta no navegador com venda real (595, dois produtos, NFC-e autorizada):
devolução **parcial** de 1 das 2 unidades do segundo item gerou XML com
`<DFeReferenciado><chaveAcesso>…</chaveAcesso><nItem>2</nItem>`, CFOP 1202, `infRespTec` com
`idCSRT`/`hashCSRT`, e **sem** `NFref`. Ver `docs/telas/devolucao-produtos.md`.

### 2026-08-19 — Reimpressão de papeleta avisa "VENDA CANCELADA" já na primeira linha do cupom

Pedido do dono do produto, achado direto ao reimprimir a papeleta de uma venda já cancelada (a
591, usada de exemplo): a reimpressão saía **idêntica** à de uma venda válida, sem nenhum aviso.
`venda.cancelada` passou a viajar em `ComprovanteVendaResponse`/`ComprovanteVenda`
(`PdvVendaService.buscarComprovante`), e `montarLinhasComprovanteVenda` (`web/src/lib/
comprovante.ts`) imprime `**** VENDA CANCELADA ****` centralizado, antes de qualquer outra coisa
— até antes da tarja "DOCUMENTO SEM VALOR FISCAL". Uma venda cancelada com NFC-e nunca chega como
DANFCE aqui: `buscarDadosFiscais` só considera documento `AUTORIZADO`/`CONTINGENCIA`, e o
cancelamento marca `documento_fiscal.situacao = CANCELADO` — a papeleta comum é sempre o formato
que sai na reimpressão de uma venda cancelada, então um único aviso cobre os dois casos (fiscal e
não fiscal). `jsonPath("$.cancelada")` novo em `CancelamentoVendaCrudTest`. Ver
`docs/telas/papeleta-venda.md`.

### 2026-08-19 — 🔴 Bug real corrigido: cancelamento de NFC-e sempre saía "recusado pela SEFAZ" mesmo quando ela autorizava — mesma classe do bug de emissão de 2026-08-18; trava de retentativa e prazo de cancelamento na tela

Investigação disparada por um pedido simples ("cancele a venda 590, dá 'Ocorreu um erro'") que
puxou uma cadeia de quatro problemas reais, achados só testando ao vivo contra a SEFAZ-PR:

1. **Mensagem genérica escondendo o motivo real.** `MontagemInvalidaException` (validação de XML
   fiscal — inclusive a checagem da justificativa mínima do cancelamento) não tinha
   `@ExceptionHandler` nenhum: caía no 500 padrão do Spring, sem corpo Problem Details, e o front
   só mostrava "Ocorreu um erro". Corrigido com um handler novo em `GlobalExceptionHandler`
   (mesmo padrão já usado pra `ResponseStatusException`/`DataIntegrityViolationException`).
2. **Justificativa sem validar o mínimo de 15 caracteres** (regra da SEFAZ, MOC) no front —
   `CancelamentoVendaModal.tsx` só checava "não vazio". Agora valida 15 caracteres antes de
   chamar a API, com um texto de ajuda explicando o porquê.
3. **🔴 O bug de verdade: `SefazTransporte` lia o `cStat` errado na resposta de cancelamento.**
   Mesma classe do bug de emissão achado em 2026-08-18 (ver a entrada daquele dia e o box no topo
   deste arquivo): `RecepcaoEvento4` também devolve dois `cStat` — 128 (lote de evento,
   `retEnvEvento`) e 135 (o evento em si, `retEvento/infEvento`). Sem escopo pra `infEvento`, o
   primeiro `cStat` do texto (128, do lote) sempre vencia, e **todo cancelamento saía "recusado"
   mesmo quando a SEFAZ autorizava de verdade** — a venda nunca revertia estoque/caixa/fiscal.
   Corrigido estendendo a mesma resolução por bloco (`infProt` primeiro, `infEvento` como
   segunda opção) que já existia só pra autorização. Teste de regressão novo com um XML real de
   dois `cStat` (`SefazTransporteTest.cancelamentoComDoisCstatExtraiODoEventoNaoODoLote`) — os
   testes antigos de `CancelamentoNfceTest` nunca pegavam isso porque mockam `SefazTransporte`
   inteiro, sem exercitar o parser de verdade.
4. **Efeito colateral: a UNIQUE de `documento_fiscal_evento` travava qualquer retentativa.**
   Como o bug 3 fez a 1ª tentativa de cancelamento (antes do fix) registrar `autorizado = false`
   mesmo com a SEFAZ tendo aceitado de verdade, a 2ª tentativa (já com o fix) reenviou o mesmo
   evento (cancelamento é sempre `nSeqEvento = 1`, regra da SEFAZ, não bug) — e bateu na
   `UNIQUE (id_tenant, id_documento_fiscal, tipo_evento, sequencia)`, que só permitia **uma**
   linha por documento+evento. A tentativa nova nem conseguia ser gravada, e a transação inteira
   revertia com "Registro em uso por outro cadastro". Corrigido com uma coluna nova,
   `tentativa` (calculada por subquery, `COALESCE(MAX(tentativa),0)+1`), entrando na UNIQUE junto
   — cada tentativa LOCAL de registrar o mesmo evento vira uma linha própria, preservando o
   rastro de todas (P3) sem travar a retentativa. Editado direto em `V035__fiscal_documento.sql`
   (banco em construção) + aplicado por hand no dev (`ALTER TABLE`, sem apagar dados) +
   `flyway repair` pra reconciliar o checksum.
5. **Novo, a pedido do dono do produto**: a tela de Cancelamento de Venda passou a mostrar o
   horário de autorização da NFC-e e até quando a SEFAZ permite cancelá-la (30 minutos, padrão
   nacional pra NFC-e — configurável por UF em `cfg_uf_autorizador.prazo_cancelamento_min`, bem
   diferente da NF-e, 24h) **antes** de deixar preencher o motivo — e bloqueia por completo
   (venda e nota) quando esse prazo já passou, com uma mensagem que já aponta a saída (NF-e de
   devolução). `VendaDetalheCancelamentoResponse` ganhou `nfceDataAutorizacao`/
   `nfcePrazoCancelamentoMinutos`/`nfcePrazoCancelamentoExpiraEm`/`nfcePrazoCancelamentoExpirado`
   (calculado no servidor, não no relógio do navegador).

Confirmado ao vivo contra a SEFAZ-PR de verdade: a venda 590 teve o cancelamento genuinamente
autorizado pela SEFAZ nos bastidores (mesmo com o bug local reportando recusa), mas por essa
altura o prazo real de 30 minutos já tinha passado — o bloqueio novo pegou exatamente esse caso e
apontou a saída certa (devolução). **775/775 testes de backend verdes** (2 novos:
`SefazTransporteTest` + `CancelamentoNfceTest.buscarDetalheDeVendaComNfceDentroDoPrazo...`), `tsc
-b` limpo. Ver `docs/telas/cancelamento-venda.md`.

**Auditoria "documente/memorize tudo" pedida de novo, na mesma sessão** (sinal de que a 1ª
passada não bastou, ver `feedback_checklist_documentar_tudo` na memória) achou 2 lacunas reais de conteúdo
**dentro do produto**, não em `docs/`: `AjudaDaTela.tsx` da própria Pesquisa de Vendas
(`vendas.pesquisavendas.tela`) não mencionava nem o prazo de cancelamento da NFC-e nem o aviso
"VENDA CANCELADA" da reimpressão — os dois corrigidos (novo item em `errosComuns` + duas frases
nos `passos`). E o docstring do próprio `CancelamentoVendaModal.tsx` (componente escrito nesta
mesma sessão) ainda não citava a integração fiscal nem o bloqueio por prazo — corrigido também.

### 2026-08-19 — Pesquisa de Vendas mais espaçada e DANFCE ganha o número da venda

Dois pedidos pequenos do dono do produto, resolvidos antes da investigação de cancelamento acima:

- **Popup de filtros da Pesquisa de Vendas** (o das "linhas simétricas" de mais cedo hoje) estava
  com tudo colado — modal alargado pra `modal-medio` (640px), 20px de respiro vertical entre as
  três linhas de campos, coluna "Vendedor" mais larga (`col-6`, não quebrava mais em duas linhas),
  placeholder do Nº da Venda encurtado (cortava), e "Situação" ocupa a linha inteira quando o
  usuário não é ADMIN (sem "Empresa" do lado). Ver `docs/telas/pesquisa-vendas.md`.
- **DANFCE não tinha o número da venda** — só o número/série oficial da NFC-e. Adicionado "Venda
  Nº: <id>" em "Informações adicionais de interesse do contribuinte" (`DanfceImprimir.tsx`); como
  esse número sempre existe, a seção deixou de ser condicional (antes só aparecia com
  caixa/vendedor/IBS-CBS preenchidos).

### 2026-08-19 — Bug real corrigido: rejeição SEFAZ por CNPJ da credenciadora ausente (`cStat 391`); calibragem final do DANFE (72mm confirmado por foto); quantidade respeita `cfg_permite_qtd_decimal`; ajuste de espaçamento vertical pra economizar papel

1. **🔴✅ Bug real de produção: NFC-e paga em cartão rejeitada pela SEFAZ-PR** — "Não informados os
   dados do cartão de crédito/débito nas Formas de Pagamento da Nota Fiscal (391)" na venda nº 586.
   Causa: `tipo_carteira.cnpj_credenciadora` NULL nas 6 carteiras de cartão do tenant — o grupo
   `<card>` do XML saía com `tpIntegra`/`tBand` mas sem `CNPJ`, incompleto pra SEFAZ mesmo com
   `tpIntegra = 2` (maquininha não integrada). Isso **contraria a leitura genérica da NT 2015.002**
   (que só exige CNPJ com `tpIntegra = 1`) — achado só reproduzindo contra o ambiente real da
   SEFAZ-PR. Corrigido em duas frentes: `ConformidadeFiscalService.listarPagamentos()` passa a
   também flagar carteira de cartão sem `cnpj_credenciadora` (antes a Conformidade Fiscal dizia
   "pronto pra emitir" com essa pendência invisível); dado de dev corrigido (CNPJ real da Cielo
   como placeholder nas 6 carteiras — **trocar pelo CNPJ real da credenciadora antes de produção**).
   Teste novo (`ConformidadeFiscalCrudTest.carteiraDeCreditoComBandeiraMasSemCnpjDaCredenciadoraContaComoPendencia`)
   + fix no helper compartilhado `configurarEmpresaCompleta()`, que também deixava o campo NULL e
   mascarava o gap nos testes "caminho feliz". Confirmado ao vivo: nova venda com VISA crédito saiu
   `AUTORIZADO`, protocolo real. **730/730 testes de backend verdes.**
2. **Calibragem final da largura/posição do DANFE** — depois de 4 rodadas por estimativa (-3mm,
   +1mm, 72mm, 77mm de largura), o dono do produto trouxe uma foto do cupom cortando dos **dois**
   lados e as duas medidas reais tiradas com régua: **78mm de bobina, 72mm de área útil**. Corrigido
   para `left: 0` (não negativo — a origem do CSS já É o começo da área imprimível, mesma regra da
   papeleta comum) + `width: 72mm`. Régua venceu a estimativa visual.
3. **Quantidade do DANFE ignorava `cfg_permite_qtd_decimal`** — saía sempre "1,000UN" mesmo em
   tenant configurado pra não trabalhar com fração, porque `formatarQuantidadeDanfce` (`danfce.ts`)
   forçava 3 casas decimais sempre, por decisão anterior deliberada ("o DANFE mostra o que foi
   gravado na nota") que o uso real provou errada. Corrigido pra usar a mesma
   `formatarQuantidade(valor, permiteDecimal)` de `masks.ts` já usada em todo o resto do PDV/Estoque
   — `ComprovantePapeletaModal.tsx` passou a buscar `cfg_permite_qtd_decimal` (mesmo padrão
   `useQuery(['permite-qtd-decimal'], buscarPermiteQtdDecimal)` de `Pdv.tsx`/`AlteraQuantidadeModal.tsx`)
   e repassar como prop pro `DanfceImprimir`.
4. **Espaçamento vertical reduzido pra gastar menos papel** — pedido explícito do dono do produto,
   com 3 níveis de opção apresentados antes de mexer (Leve/Moderado/Agressivo). Aplicado **Leve**
   (`line-height` 1.4→1.2 + margem dos separadores `.danfce-sep` 1.5mm→0.9mm) e, a pedido, também
   **Moderado** em cima (margens de seção — empresa/título/tarja/tabelas — de ~2mm/1mm pra ~1.2mm/
   0.6mm, padding de cabeçalho de tabela 1mm→0.6mm, padding de linha de item/pagamento 0.3mm→0.2mm,
   espaço acima da descrição do item 1.5mm→1mm). Todos os separadores continuam presentes — o nível
   **Agressivo** (trocar o separador entre itens, o mais repetido, por margem fina) ficou pra depois
   se o Moderado ainda não bastar. Em teste pelo dono do produto no momento desta entrada.

### 2026-08-19 — DANFE: papeleta sem linhas zeradas, cabeçalho "Descrição dos Produtos" e traços contínuos entre seções

Ajustes visuais pedidos pelo dono do produto, comparando contra modelos reais trazidos por ele
(prints de outro sistema). Três rodadas até acertar o traço:

1. **Papeleta comum (sem fiscal):** quando desconto **e** acréscimo são zero (caso mais comum),
   esconde `SUB-TOTAL`/`DESCONTOS`/`ACRESCIMOS` e mostra só `TOTAL A PAGAR` — as três linhas
   zeradas só poluíam.
2. **DANFE ganhou a linha "Descrição dos Produtos"** antes do cabeçalho da tabela de itens
   (`Cod. Qtd. Un Vlr.Unit. Vlr.Total.`), igual ao modelo de referência.
3. **Separador entre seções — três tentativas:** primeiro `border-style: dashed` (CSS), não batia
   com o modelo; depois uma fileira de caracteres `-` (texto), ainda saía "pontilhada" na
   impressora térmica — a resolução da bobina não resolve traços finos intercalados com espaço.
   Versão final: uma **barra gráfica sólida** (`<div>` com `background`, não borda nem texto) — e
   em **preto fixo**, não `var(--ink)`: a cor ligada ao tema claro/escuro do app deixava a barra
   quase invisível no modo escuro (achado comparando a pré-visualização em cada tema; a área do
   DANFE sempre representa papel, não deveria seguir o tema da tela). Acrescentados separadores
   novos pedidos depois: antes/depois da frase "DANFE NFC-e - Documento Auxiliar" e **entre cada
   produto** da lista de itens.
4. **Cabeçalho da empresa trocado**: razão social e nome fantasia estavam invertidos no cadastro
   de dev (`Loja Dev Claudio` como razão social, `MITRYUSCASH LTDA` como fantasia) — corrigido via
   tela de Dados da Empresa (dado, não código; o campo razão social é somente-leitura na tela,
   corrigido direto no banco de dev com confirmação do dono do produto).

`tsc -b` limpo, testado ao vivo reimprimindo vendas reais em `Pesquisa de Vendas`. Ver
`web/src/pages/pdv/DanfceImprimir.tsx` e `web/src/styles.css` (`.danfce-sep`).

### 2026-08-19 — 🔴 Bug real: QR Code da NFC-e rejeitado pela SEFAZ (`cStat 464`, hash divergente) — formato e cálculo do hash estavam errados

Reportado pelo dono do produto ("erro ao ler o QR Code, corrija de uma vez por todas"),
reproduzido ao vivo navegando direto pro portal público da SEFAZ-PR com o QR Code de notas reais.

1. **Formato:** o QR online usava `chave|3|tpAmb` (sem CSC/hash) — passa na autorização (o XSD só
   confere a forma do campo), mas o portal de consulta da SEFAZ-PR rejeitava com "Url do QRCode
   mal formatado". Corrigido pro formato nacional NT 2015.002 v2: `chave|2|tpAmb|idCSC|hashQRCode`.
2. **`idCSC` precisa ir sem zeros à esquerda** — a SEFAZ credencia e exibe o CSC como "000001", mas
   o XSD oficial exige `0|[1-9][0-9]{1,5}` nesse campo; achado validando contra o XSD, não contra
   suposição.
3. **A fórmula do hash estava errada**: a versão anterior calculava `SHA-1(chave+CSC)`; o correto é
   `SHA-1(chave|2|tpAmb|idCSC + CSC)` — faltava concatenar versão/ambiente/idCSC antes do CSC.
   Confirmado contra a SEFAZ de verdade: uma emissão real chegou a ser **rejeitada em produção**
   com `cStat 464` ("Código de Hash no QR-Code difere do calculado") antes deste ajuste; a próxima
   emissão, já corrigida, saiu `AUTORIZADO` com protocolo real. Fórmula conferida contra a
   implementação de referência `nfephp-org/sped-nfe` (`get200()`).
4. **CSC estava gravado em texto puro** — a coluna se chama `csc_token_cifrado`, mas nada cifrava
   desde que a tela existe. Corrigido com `SegredoCifrador` (AES-256-GCM), mesmo padrão do
   certificado digital (`fiscal_certificado.senha_cifrada`).
5. **Gate novo**: não dá mais pra ligar a emissão de NFC-e sem CSC configurado (F11) — antes dava
   pra ligar e todo QR Code impresso sair inválido em silêncio, sem nenhum aviso até uma rejeição
   real ou um cliente reclamando que não conseguia ler o cupom.

O erro "mal formatado" do portal público, quando a nota é de **homologação**, **não é bug
nosso** — confirmado trocando só o `tpAmb` de 2 pra 1 no mesmo QR Code: a mensagem muda pra
"Chave de Acesso... não consta na base de dados da SEFAZ" (o portal simplesmente não indexa notas
de homologação para consulta pública). Notas de produção devem ler normalmente. Suíte: 772/772
verdes. Ver `MontadorXmlNfce.qrCodeOnline`/`sha1Hex`, `FiscalConfigService.carregarCscParaEmissao`.

### 2026-08-19 — DANFE detalha o tributo aproximado em Federal/Estadual/Municipal (Lei 12.741), não só o total

Pedido do dono do produto: em vez de "Valor aproximado dos tributos R$ X (Y%)" sozinho, detalhar
as três parcelas.

1. `MotorTributarioDtos.ItemOperacao` trocou o único `aliquotaTributoAproximado` por três campos
   (`aliquotaTribFederal`/`Estadual`/`Municipal`); `ItemTributado` e `TotaisTributarios` ganharam os
   3 valores em paralelo ao total (que continua sendo a soma dos três — teste novo prova isso com
   alíquotas diferentes, não só o mesmo valor triplicado).
2. `VendaFiscalAssembler` resolve a alíquota federal por origem (Nacional × Importado) e passa as
   3 pro motor; `documento_fiscal` ganhou `valor_trib_federal`/`estadual`/`municipal` (V035) — não
   vai no XML (`vTotTrib` é um valor só no XSD), só serve o DANFE.
3. **Bug real achado no caminho**: o `INSERT` de `gravarAssinado()` tinha 31 `?` para 32
   parâmetros — um placeholder a menos, causando `409 Conflito` ("Registro em uso") em **toda**
   emissão de NFC-e assim que os 3 campos novos foram acrescentados à query. Sem esse fix os 12
   testes de emissão afetados ficariam vermelhos.
4. No DANFE, a primeira versão mostrava os 3 valores em R$; a pedido do dono do produto, virou só
   percentual (`Federal: X% Estadual: Y% Municipal: Z%`) — os valores em reais poluíam a linha.

Testado: `MotorTributarioTest` (caso novo com 3 alíquotas diferentes), suíte inteira. Ver
`web/src/pages/pdv/DanfceImprimir.tsx` (seção `.danfce-tributo`).

### 2026-08-19 — DANFE da NFC-e: `vTotTrib` (Lei 12.741) real, emitido no XML, e layout completo na papeleta

Pedido do dono do produto, com especificação técnica em mãos (layout MOC + fórmula IBPT) —
mesclada em `docs/MODULOFISCAL.md` §8.6/§8.6.1 e o arquivo solto apagado. O DANFCE **já existia**
desde o B7 (17/08) — chave em blocos, protocolo, QR Code, tarja de homologação — o que faltava era
o cálculo real do tributo aproximado (entrada anterior de hoje deu a matéria-prima: `alq_federal_
nacional`/`alq_federal_importado`/`alq_estadual`/`alq_municipal` em `cfg_produto_ncm`).

1. **`vTotTrib` deixou de ser hardcoded em zero** (`MotorTributario.calcularItem`). A alíquota
   total (Nacional × Importado por `produto.origem_mercadoria`, mais estadual/municipal) é
   resolvida **antes** do motor, em `VendaFiscalAssembler.buscarItens` (`LEFT JOIN
   cfg_produto_ncm` pelo mesmo NCM que já buscava pro XML) — o motor continua **sem I/O**, só
   multiplica pela base do item, igual ICMS/PIS/COFINS. Sem NCM cadastrado (ou sem
   correspondência), fica zero sem aviso — informação indisponível, não erro (F11).
2. **`orig` deixou de ser hardcoded `"0"`** em `MontadorXmlNfce.montarIcms` (6 grupos de ICMS) —
   passa a vir de `produto.origem_mercadoria` via `ItemNota`. Sem tela pra editar essa coluna
   ainda (item aberto, não bloqueante: nenhum cliente piloto vende importado hoje).
3. **`vTotTrib` passou a ser EMITIDO no XML** — antes ficava sempre omitido de propósito (o valor
   era sempre zero, e um `0,00` afirmaria falsamente "sem tributo"). Aparece no `det/imposto` de
   cada item (primeiro elemento, antes de `ICMS` — ordem do XSD) e no total (`ICMSTot/vTotTrib`,
   após `vNF`), só quando positivo.
4. **Papeleta ganhou os 2 itens que faltavam da especificação**: número/série oficial da NFC-e
   (`documento_fiscal.numero`/`serie`, antes só aparecia o nº interno da venda) e a linha
   "CONSUMIDOR - CPF/CNPJ: ..." ou "CONSUMIDOR NÃO IDENTIFICADO" — extraída do `<dest>` do XML
   assinado, nunca do cadastro do cliente (podem divergir se o operador respondeu "não" à
   pergunta de incluir CPF).
5. **⚠️ Bug real achado pelo próprio teste novo**: a extração do CNPJ do consumidor
   (`PdvVendaService.buscarDadosFiscais`) usava `extrairTag(xml, "CNPJ")` no XML inteiro — mas o
   `<emit>` (a empresa) também tem `<CNPJ>`, e aparece ANTES de qualquer `<dest>` eventual. Uma
   venda sem CPF incluído devolvia o CNPJ da **própria empresa** como "consumidor". Corrigido
   restringindo a busca ao bloco `<dest>` primeiro (`extrairTag(xml, "dest")`), só então
   procurando CPF/CNPJ dentro dele.
6. **`cfg_produto_ncm.alq_federal` dividida em `alq_federal_nacional`/`alq_federal_importado`**
   (mesma sessão, antes do cálculo) — a fórmula da Lei 12.741 usa uma OU outra, nunca a soma;
   reimportada da mesma planilha (`TabelaIBPTaxSP26.1.L.csv`), 10.515/10.515 NCMs batidos de
   novo, sem perda de dado (aplicado por `ALTER TABLE` direto no banco de dev, sem reset completo
   — mesmo procedimento da entrada anterior).

Testado: `MotorTributarioTest` (cálculo puro, 2 casos: com e sem alíquota resolvida),
`MontadorXmlNfceTest` (3 casos novos: `orig` variável, `vTotTrib` no item+total, omissão quando
zero) e `VendaFiscalEmissaoTest` (ponta a ponta real: produto com NCM real + origem Importado,
venda autorizada contra SEFAZ mockada, `documento_fiscal.valor_total_tributos` e o XML assinado
batendo com a fórmula, e a papeleta devolvendo número/série/consumidor corretos via
`GET .../comprovante`). **770/770 testes de backend verdes.** `tsc -b` limpo. API reconstruída e
confirmado ao vivo (`GET /api/v1/ncm/{codigo}` com os 4 campos separados). Ver
`docs/MODULOFISCAL.md` §8.6/§8.6.1 para o detalhe técnico completo (fórmula, tabela de
correspondência item-a-item com a especificação recebida).

**O que ficou de fora, de propósito**: coluna opcional de tributo por item `(VL TR)` na tabela de
itens (a especificação marca como opcional/recomendada, só o total é obrigatório) e tela de
cadastro pra `produto.origem_mercadoria` (a coluna já existe desde sempre, só nunca teve UI —
hoje todo produto do sistema é Nacional por não ter como marcar diferente).

### 2026-08-19 — `cfg_produto_ncm` ganha alíquota IBPT federal/estadual/municipal (dado real da Receita/IBPT)

Pedido do dono do produto: trocar a coluna `aliquota_ibpt` (nunca usada — o próprio `db/migration`
já tinha comentário dizendo que ela "não serve", ver entrada de arquitetura abaixo) por três colunas
reais, `alq_federal`/`alq_estadual`/`alq_municipal` (todas `numeric(10,2)`), e popular as 10.515
linhas com a planilha `TabelaIBPTaxSP26.1.L.csv` (tabela paga IBPT, versão 26.1.L, base São Paulo).

- **Migration dona é a `V017__catalogo.sql`** (banco ainda em construção,
  [[project_db_in_construction]]) — `aliquota_ibpt` saiu, entraram as 3 colunas novas.
- Antes de remover, conferido que `aliquota_ibpt` estava NULL/zero nas 10.515 linhas — nenhum dado
  real se perdeu.
- **Existe uma tabela `cfg_ibpt` (V034) desenhada exatamente para isso**, com as mesmas 4 alíquotas
  mas por NCM × UF × vigência — mais correta tecnicamente, porque a planilha é específica de SP e
  `cfg_produto_ncm` é global (mesma linha vale pra tenant de qualquer UF). Perguntado explicitamente
  via `AskUserQuestion`: o dono do produto escolheu popular `cfg_produto_ncm` mesmo assim (mais
  simples, uma linha por NCM). `cfg_ibpt` segue vazia, sem consumidor — ver [[project_modulo_fiscal]].
- Import: planilha é `;`-delimitada, `codigo` pode repetir por causa da exceção TIPI (coluna `ex`)
  — 586 dos 11.576 NCMs únicos têm mais de uma linha; usada sempre a linha com `ex` vazio (a
  "base"), presente para 100% dos códigos duplicados. `alq_federal = nacionalfederal +
  importadosfederal` (a planilha separa produto nacional de importado; a tabela guarda só uma
  alíquota federal). Importado via `COPY` numa staging table + `UPDATE ... FROM`, direto no banco de
  dev (não passou pelo Flyway — ver nota abaixo). **10.515/10.515 NCMs atualizados, 0 sem
  correspondência.**
- `NcmService`/`NcmDtos`/`NcmController` (backend) e `web/src/lib/ncm.ts` (frontend) atualizados
  para os 3 campos novos — `aliquotaIbpt` não era consumido em nenhum componente do front, troca
  sem quebra. `tsc -b` limpo, endpoint testado ao vivo (`GET /api/v1/ncm/{codigo}`).
- **Nota de processo:** a migration V017 já estava aplicada no banco de dev — editá-la não altera
  o banco sozinho. Em vez do reset completo de banco (procedimento de outras sessões, ver
  [[project_credenciais_dev_atual]]), a alteração foi aplicada por `ALTER TABLE` manual direto no
  Postgres (mesmo shape do que a migration editada faz), preservando os 10.515 NCMs e todo o resto
  do dado de teste do dia — cabível aqui porque `cfg_produto_ncm` é uma tabela GLOBAL sem
  `id_tenant`, sem risco de RLS/isolamento.

### 2026-08-19 — Bug real corrigido: os jobs `@Scheduled` de fiscal nunca encontravam nenhum tenant (RLS silenciosamente vazio)

Pedido de rotina ("suba o servidor") revelou, ao ler os logs, que `FiscalContingenciaDrenoJob`
estava lançando `operator does not exist: smallint = ambiente_fiscal` a cada 5 minutos desde
17/08 — `cfg_uf_autorizador.ambiente` é `smallint` (1/2) e `fiscal_config_empresa.ambiente` é o
enum `ambiente_fiscal`, comparados direto num `JOIN` sem cast. Corrigido
(`CASE c.ambiente WHEN 'PRODUCAO' THEN 1 ELSE 2 END`).

**Investigando o teste de regressão apareceu um segundo bug, bem mais sério, escondido atrás do
primeiro.** O comentário no código dizia "a varredura roda como `niner_owner` numa consulta
explicitamente global" — **isso nunca foi verdade**: `application.yml`/`docker-compose.yml`
mostram que a API só conecta como `niner_app`, em qualquer ambiente. `documento_fiscal`/
`fiscal_config_empresa`/`empresa` têm `FORCE ROW LEVEL SECURITY`; sem `SET app.id_tenant` a
política `USING (id_tenant = plataforma.tenant_atual())` não bate pra NENHUMA linha, de NENHUM
tenant — não é um filtro "global", é RLS devolvendo vazio em silêncio. Provado ao vivo: mesmo
`niner_owner` (dono das tabelas, sem `BYPASSRLS`) via 0 linhas em `SELECT * FROM empresa` sem
contexto, com 5 empresas reais no banco. **Consequência prática: o job de drenagem de
contingência nunca tinha encontrado uma única empresa desde que existe (17/08), e o job de
arquivamento (`ArquivamentoXmlJob`) tinha o mesmo bug na descoberta de "quais tenants têm
pendência"** — só o caminho quente (arquivar logo após emitir, já dentro do `TenantContext` de
uma requisição) funcionava; a rede de segurança agendada nunca rodava de verdade.

Por que nenhum teste pegava: Testcontainers conecta como **superusuário** do container (não
`niner_app`), que sempre ignora RLS independente de `FORCE` — o mesmo ponto cego que motivou
`PrivilegiosNinerAppTest` (ver [[feedback_testcontainers_nao_usa_niner_app]]). Um teste ingênuo
teria passado em CI e continuado quebrado em produção.

**Correção (opção "loop por tenant" escolhida pelo dono do produto entre 3 alternativas
propostas):** `DocumentoFiscalRepositorio.listarTenantIds()` (novo) lê `plataforma.tenant`
(GLOBAL, sem RLS, P9) — o único jeito correto de um job sem JWT descobrir quais tenants existem.
Os dois jobs passam a fazer loop por tenant, entrando em `TenantContext.comTenant` **antes** de
qualquer consulta de domínio; a consulta "quem tem pendência" antiga (`tenantsComPendenciaDeArquivamento`,
que rodava sem contexto) foi removida — ficou redundante, cada tenant agora é sempre verificado.
Motivo de não usar uma função `SECURITY DEFINER` (alternativa rejeitada): manter o padrão P8 já
usado no resto do projeto, sem abrir exceção nova ao RLS.

Dois testes de regressão novos: `FiscalContingenciaTest.filaDePendentesEncontraEmpresaComNotaEmContingencia`
(prova que a query com o cast novo encontra a empresa certa, com `ambienteCodigo` correto) e
`PrivilegiosNinerAppTest.semTenantNoContextoDominioNaoAparecePorNenhumTenantMasPlataformaTenantContinuaGlobal`
(prova as duas metades do contrato: domínio vazio sem contexto, `plataforma.tenant` sempre
acessível) — este no lugar certo pra bug invisível ao Testcontainers padrão. **765/765 testes de
backend verdes.** API reconstruída (`docker compose up -d --build api`) e confirmado ao vivo:
erro sumiu dos logs. Ver [[project_modulo_fiscal]].

### 2026-08-19 — Fix de rejeição SEFAZ: CSOSN 500 mandava uma base de ICMS que nenhum item declarava (venda 560, cStat 531)

Reportado ao vivo pelo dono do produto: *"A SEFAZ rejeitou a nota: Total da BC ICMS difere do
somatorio dos itens. [vBC informado: 1279.80, vBC calculado: 0.0] (531)."* — segunda rejeição real
na mesma sessão, depois do fix do `xProd`/`dest/xNome` (ver entrada seguinte). Causa raiz:
`MotorTributario.calcularIcms` calculava uma base/valor não-zero pra itens com **CSOSN 500** (ICMS
já retido por substituição tributária, o perfil "REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)" seedado
no signup) sempre que a regra tinha alíquota cadastrada — mas `MontadorXmlNfce.montarIcms` nunca
escreve `vBC`/`pICMS`/`vICMS` pra CSOSN 500 (o XSD só permite o grupo `ST` retido, que o motor não
calcula, então o item sai só com `orig`+`CSOSN`). O total da nota (`ICMSTot/vBC`) somava a base
"fantasma" calculada mas nunca impressa em nenhum item — SEFAZ recalcula esse total a partir dos
itens de verdade e rejeita a divergência. Fix: CSOSN 500 entrou no mesmo grupo de 102/103/300/400
(zera base e valor, `MotorTributario.CSOSN_SEM_ICMS`) — semanticamente correto também: CSOSN 500
significa que esta venda não abre uma base nova de ICMS, o imposto já foi retido lá atrás.

`MotorTributarioTest` reescrito: o teste antigo que validava a base "destacada" do CSOSN 500 virou
regressão (`csosn500DeStRetidoNaoDestacaBaseNemValorMesmoComAliquotaNaRegra`, exigindo zero mesmo
com alíquota na regra); o teste de CSOSN sem destaque virou parametrizado sobre
102/103/300/400. **763/763 testes de backend verdes.** Verificado ao vivo: venda nova com produto
do perfil ST (CSOSN 500) autorizada pela SEFAZ-PR (protocolo `141260001535836`), XML confirmado com
`vBC=0.00` no total, batendo com `<ICMSSN500><orig>0</orig><CSOSN>500</CSOSN></ICMSSN500>` do item
(nenhum campo de base/valor). Ver `docs/MODULOFISCAL.md` §8.2.

### 2026-08-19 — PDV pergunta CPF antes de emitir a NFC-e, fix de rejeição SEFAZ (venda 555) e texto do admin em Usuários

Três pedidos do dono do produto na mesma sessão.

**1) Pergunta de CPF antes de emitir (`docs/telas/papeleta-venda.md`).** Até aqui, com
`cfg_emite_fiscal_apos_venda` ligado, a NFC-e disparava sozinha assim que o popup de papeleta
abria, decidindo o CPF do destinatário automaticamente a partir do cliente vinculado à venda.
Passa a existir uma etapa de confirmação: o popup mostra cliente/valor/formas de pagamento e
pergunta "Deseja incluir o CPF do cliente nesta nota fiscal?" — "Sim" emite com o documento do
cliente, "Não" emite para consumidor não identificado, e cliente sem CPF/CNPJ cadastrado só recebe
um aviso + botão único ("Confirmar e emitir"). A papeleta não fiscal ("DOCUMENTO SEM VALOR
FISCAL") nunca mais aparece antes dessa pergunta quando o parâmetro está ligado, e os botões
Imprimir/Enviar por WhatsApp só liberam depois do resultado da emissão. `ComprovanteVendaResponse`/
`ComprovanteVenda` ganharam `documentoCliente`; `VendaFiscalAssembler.montar`,
`VendaFiscalService.emitirNfce` e `POST /api/v1/pdv/vendas/{id}/nfce` ganharam o parâmetro
`incluirCpf` (corpo passou a ser obrigatório, `{"incluirCpf": boolean}`), com
`buscarDestinatarioObrigatorio` recusando com 409 se o frontend mandar `incluirCpf=true` sem
cliente/documento (P4 — defesa também no servidor). O botão manual "Emitir Nota Fiscal" (parâmetro
desligado) abre a mesma tela, com um "Cancelar" a mais.

**2) Fix da rejeição SEFAZ na venda 555 (cStat 373).** Uma correção anterior, na mesma sessão, para
a rejeição de `dest/xNome` (cStat 598) tinha reaproveitado por engano a mesma constante de frase
de homologação usada em `xProd` (descrição do item) — as duas frases são **diferentes** e a SEFAZ
passou a rejeitar o item. `MontadorXmlNfce` agora guarda `FRASE_HOMOLOGACAO` (`xProd`, "NOTA FISCAL
EMITIDA...") e `FRASE_HOMOLOGACAO_DESTINATARIO` (`dest/xNome`, "NF-E EMITIDA...") separadas, com
javadoc avisando pra nunca mais unificá-las sem validar as duas emissões reais. Ver
`docs/MODULOFISCAL.md` §9.6.

**3) Texto do bloqueio do ADMIN em Cadastro de Usuários** simplificado — tirou a menção a "definido
no cadastro da loja" (a empresa nunca decidiu isso; é sempre o primeiro ADMIN do tenant).

**Testado:** `MontadorXmlNfceTest` (32/32) e suíte completa do backend, **760/760 verdes**;
`tsc -b` limpo; verificado ao vivo em três vendas reais na SEFAZ-PR homologação — cliente com CPF
respondendo "sim" (XML com `<dest><CPF>`), mesmo cliente respondendo "não" (XML sem `<dest>`
nenhum) e cliente sem CPF cadastrado (aviso único) — e o caminho manual com "Cancelar".

### 2026-08-19 — Abertura de Caixa reabre no mesmo dia, coluna TRIBUTACAO na Importação de Produtos e Parâmetros do Sistema em 4 abas

Três frentes na mesma sessão, antes do trabalho de emissão fiscal (CPF/SEFAZ) documentado acima.

**1) Abertura de Caixa — "1 caixa por empresa+usuário+dia" (`docs/telas/abertura-caixa.md`).**
Pedido direto do dono do produto: abrir de novo depois de fechar no mesmo dia estava criando um
**segundo** `caixa_mestre` (mesma empresa+usuário+data), quebrando esse invariante. `CaixaService.
abrir` passa a checar se já existe um caixa fechado hoje e, se existir, **reabre a mesma linha**
(saldo inicial/carteira do novo request, rastro em `observacoes` — P3) em vez de criar outra; a
conferência do fechamento anterior é apagada (não vale mais). `GET /caixa/status` ganhou uma
segunda consulta interna que traz o caixa de hoje mesmo fechado (`aberto:false` com `idCaixa`/
`saldoInicial` preenchidos), usada por `AberturaCaixaModal.tsx` (prop `statusCaixa`, passada pelas
3 telas que abrem o popup — PDV, Recebimento de Crediário e baixa em dinheiro de Contas a Pagar)
pra pré-preencher o saldo inicial e trocar "Abrir Caixa" por "Reabrir Caixa". 3 testes novos em
`CaixaCrudTest`.

**2) Coluna TRIBUTACAO na Importação de Produtos + perfil "NÃO INFORMADO"
(`docs/telas/importacao-dados.md`, `docs/telas/fiscal-perfil.md`).** A coluna resolve o perfil
fiscal do produto por **nome**, comparado sem acento/maiúscula (`NORMAL`/`1` → "Revenda Tributada
Normal", `SUBSTITUICAO`/`ST`/`2` → "Revenda com Substituição Tributária (ST)", ajustado no mesmo
dia depois de testar com a planilha real do usuário, que traz texto, não os códigos numéricos
assumidos na primeira versão). Vazio não rejeita a linha — atribui um **terceiro perfil padrão**,
"NÃO INFORMADO" (semeado no signup, `SignupService`, **sem regra fiscal nenhuma** de propósito):
a Conformidade Fiscal aponta o produto como pendente e a emissão de nota recusa até o usuário
corrigir, em vez de o produto ficar silenciosamente sem perfil nenhum. Um aviso agregado no
relatório final lista quantos produtos (e em quais linhas) ficaram sem `TRIBUTACAO`. 5 testes
novos em `ProdutoImportadorCrudTest` (arquivo novo); `PerfilFiscalCrudTest` atualizado pros 3
perfis.

**3) Parâmetros do Sistema em 4 abas (`docs/telas/configuracao-geral.md`).** As 6 seções (Vendas,
Fiscal, Catálogo, Estoque, Compras, Crediário) cresceram demais pra rolar numa página só — viraram
4 abas: **Vendas** (+ Fiscal), **Estoque** (+ Catálogo), **Compras** e **Crediário**. O `PUT`
continua salvando o formulário inteiro de uma vez, não só a aba aberta.

**Testado:** suíte completa do backend, **763/763 verdes**; `tsc -b` limpo; os três fluxos
testados ao vivo no navegador (reabertura de caixa confirmada também direto no banco — mesmo
`id_caixa` reaproveitado; troca de aba em Parâmetros preservando os campos digitados nas outras).
### 2026-08-19 — Nainer no ar (deploy no VPS) + auditoria de produção: 3 defeitos 🔴 encontrados e corrigidos no mesmo dia

O produto passou a se chamar **Nainer** (`nainer.com.br`; `niner.com.br` registrado como
defensivo e redirecionando 301 para o principal) e subiu num VPS **compartilhado com ~29 sites de
cliente** — cada mexida no nginx foi precedida de `nginx -t` e seguida de `reload` (nunca
`restart`), com a lista dos outros hosts conferida antes e depois. Quatro hosts com TLS por
Let's Encrypt: `nainer.com.br` (landing), `app.` (ERP), `admin.` (backoffice), `api.`.

**Três incidentes de CORS no primeiro dia, todos com o mesmo sintoma na tela** ("não foi possível
conectar ao servidor", com a API perfeitamente no ar) **e três causas diferentes**:

1. o `limit_req` do nginx recusava com **503 e página HTML**, que não carrega cabeçalho de CORS →
   o `fetch` estourava como falha de rede. Corrigido com `limit_req_status 429` + o cabeçalho nas
   respostas de erro.
2. a correção acima passou a **duplicar** o `Access-Control-Allow-Origin` (o Spring já mandava o
   dele) — e **cabeçalho duplicado o navegador recusa igual a cabeçalho ausente**.
3. o `www` não estava na lista de origens → 403 sem CORS. Resolvido com 301 para o apex.

**Auditoria de produção item a item** (subagente, ~19 min, só leitura no VPS; as escritas foram
pela API pública, que é o que estava sendo testado). O relatório completo, com evidência e passo
de reprodução de cada achado, está em **`docs/infra/validacao-producao-2026-08-19.md`**. Três
defeitos 🔴, corrigidos no mesmo dia:

**🔴 D1 — o backup nunca rodou.** `backup_ultimo_status = ERRO` desde o primeiro dia:

> `pg_dump: error: failed to get data for sequence "assinatura_id_assinatura_seq"; user may lack
> SELECT privilege on the sequence`

A role `niner_backup` tinha BYPASSRLS e SELECT nas **tabelas** — e SELECT em **0 de 59
sequências**. O `pg_dump` lê o `last_value` de cada uma e aborta com código 1 sem isso. Era o
bloqueador nº 3 do próprio checklist de produção, falhando exatamente do jeito que o checklist
queria evitar: um "backup configurado" que não produz backup nenhum. Corrigido em
**`V044__backup_grants_sequencias.sql`** (grants no que existe + `ALTER DEFAULT PRIVILEGES` para
o que vier) e no `db/bootstrap/00_roles.sh` (banco novo já nasce certo). O bloco é condicional à
existência da role, porque ela é objeto de **cluster**, não de banco.

**🔴 D2 — o 429 da aplicação chegava ao navegador com CORS duplicado.** A correção do incidente 2
tinha condicionado o cabeçalho ao **status** (`map $status`, só em 429/502/503) — mas a aplicação
**também** responde 429 (`LimiteRequisicaoFilter`), e aí o cabeçalho saía duas vezes justamente na
resposta que mais precisa chegar íntegra: a que explica ao visitante que ele tentou rápido demais.
O critério certo não é o status, é a **origem da resposta**:

```nginx
map $upstream_http_access_control_allow_origin $nainer_cors_falta {
    default "";            # a aplicação respondeu e já mandou o dela — não duplicar
    ""      $nainer_cors;  # resposta gerada pelo próprio nginx — o cabeçalho falta
}
```

Medido em produção depois do reload: 1 cabeçalho no 204, 1 no 429 da aplicação, 1 no 429 do nginx.

**🔴 D3 — e-mail repetido criava uma segunda loja.** `plataforma.tenant` só tinha unique em
`slug`, então repetir o cadastro devolvia **201** e criava outra conta. Quem clica duas vezes (ou
tenta de novo achando que falhou) ficava com **duas lojas**, dados divididos, e — na hora da
cobrança — duas assinaturas no mesmo e-mail. Pior: `AquisicaoService.converter()` faz
`ON CONFLICT (email) DO UPDATE SET id_tenant = EXCLUDED.id_tenant`, então o **lead migrava para a
conta nova** e a primeira sumia do funil, sem aviso. Corrigido no `SignupService`, com
`pg_advisory_xact_lock` antes da checagem — o clique duplo é *exatamente* a corrida que um
`SELECT`-depois-`INSERT` sozinho não fecha. Mais de um CNPJ é **empresa dentro da mesma conta**,
que é o desenho do produto (ADR-015). Regressão:
`OnboardingContaGratuitaTest.mesmoEmailNaoCriaUmaSegundaLoja` (inclusive com o e-mail em outra
caixa e nome de loja diferente, pra provar que não é a unicidade do slug que barra).

Corrigidos junto, de menor gravidade: **corpo do 429 em ISO-8859-1** (o Tomcat assume isso quando
o content-type não declara charset; o `response.json()` do navegador decodifica sempre como UTF-8,
e o usuário lia *"Muitas requisies"*) — coberto por
`LimiteRequisicaoTest.mensagemDo429ChegaEmUtf8`; **429 do nginx em HTML** (o front faz `.json()` e
estoura no meio do tratamento do erro) — agora `error_page 429 /429.json` com Problem Details e
`Retry-After`; **HSTS ausente** nos 4 hosts; e o filtro morto de status **TRIAL** no backoffice
(ADR-015 acabou com o trial, o filtro nunca devolveria nada).

**O que a auditoria confirmou funcionando:** certificado correto nos 4 hosts e nenhum vazando para
outro cliente do VPS; os ~29 sites vizinhos intactos; nenhum link 404 na landing; JSON-LD com as 7
perguntas do FAQ batendo com as visíveis; cadastro → login → `/minha-conta` → criar 2ª empresa;
`aud` separando as duas populações de token **nos dois sentidos** (token de lojista em
`/api/admin/**` = 401, token de staff em `/api/v1/**` = 401); isolamento entre lojistas (empresa
de outro tenant = 404); beacon gravando visita com UTM e aparecendo no funil; portas internas
(5433/9080/9081/8090) fechadas de fora.

**⛔ O deploy das correções foi barrado pelo Flyway — e o motivo merece registro:** dois arquivos
de migration **já aplicados** tinham sido editados no lugar em `main` (V017, alíquota IBPT
detalhada; V035, `tentativa` na UNIQUE do evento fiscal), então a soma de verificação registrada
em produção não batia mais e o Flyway **recusou subir inteiro**, sem publicar nada. Em banco
recriado do zero isso passa despercebido — por isso não apareceu na máquina de quem editou.
Conserto para frente, em `V045__realinha_v017_v035_editadas_apos_aplicadas.sql`, idempotente
(vale no banco antigo e é no-op no novo), mais um `flyway repair` **uma vez** em produção para
realinhar as somas. Migration aplicada é imutável: registrado no CLAUDE.md.

**Backup provado com restore, não só com "status OK":** depois da V044, o backup manual pelo
backoffice gerou `plataforma/backup/niner-20260819-1958.dump` (513 KB). O arquivo foi **baixado
do MinIO e restaurado num banco descartável** dentro do mesmo Postgres — 27 tenants, 28 empresas,
27 usuários e 2.052 linhas de plano de contas vieram junto. O banco de teste foi apagado em
seguida. Era exatamente o que o `pg_dump` sem `SELECT` em sequência não conseguia produzir.

**O que ficou em aberto** (detalhe e reprodução no relatório): SMTP em branco — sem ele
`recuperar-senha` responde 204 e **não manda e-mail** —, `og.png` 404 (compartilhamento sai sem
imagem), mensagem de validação em inglês e sem dizer o campo (`"Invalid request content."`) no
formulário que converte visitante em cliente, `porOrigem` do funil não fechando com os totais
(quem entra por signup direto some da quebra em vez de cair num balde "não atribuído"), home com
772 KB sendo 94% imagem, e o **teto de preço** achatando as faixas 10 a 20 todas em R$ 990 — que é
o `preco_maximo` funcionando como pedido, mas vale reconferir antes de cobrar de verdade.
Os 5 parâmetros comerciais seguem com valor provisório em produção.

### 2026-08-18 — Pivô comercial: gratuito por volume de vendas, multi-CNPJ, Mercado Pago e rastreamento próprio (ADR-015/016/017)

Sessão de **spec** (nenhuma linha de código de produção ainda): o dono do produto redefiniu o
modelo comercial, e o encadeamento spec→código exigiu fechar três decisões que estavam abertas
desde julho (D1, D3) ou não existiam (rastreamento).

**O que muda.** Sai o **trial de 60 dias** (D2) e saem os **3 planos por funcionalidade** (D1);
entra **plano Gratuito sem prazo de validade, limitado a 100 vendas/mês**, com o produto
**inteiro** liberado — inclusive fiscal — e **CNPJs, usuários e produtos ilimitados**. Acima
disso, **faixas por volume** de 500 em 500 vendas, geradas por fórmula
(`preco_base × (1 + f + … + f^(n-1))`, com `f=1` linear e `f<1` atenuando, tudo limitado por um
**preço máximo**), anual com **15%** de desconto. Decisões do dono do produto nesta sessão:
(1) o contador **zera todo mês**; (2) ao estourar, ainda são permitidas **X vendas de tolerância**
— parâmetro que ele preenche — e só então bloqueia; (3) **cancelar venda não devolve cota**;
(4) escala pode ser linear ou atenuada, **com teto de preço**; (5) **nada de limitar ferramenta**,
nem no gratuito nem no pago.

**Por que o modelo antigo não servia mais** (registrado no ADR-015): os 3 planos vendiam
*canais de marketplace*, e `canais/`/`pedidos/`/`precos/`/`integracao/` seguem **sem uma linha de
domínio**; enquanto isso o que está pronto — PDV, caixa, crediário, contas a pagar, DRE e o
**módulo fiscal inteiro** — não aparecia no plano de preços. E trial por tempo cria uma data em
que o lojista **perde acesso ao que já digitou**, no produto em que migrar cadastro leva semanas.

**Achado que facilitou o pivô:** o enforcement de limite **nunca existiu**. `plataforma.uso_tenant`
é escrito uma única vez, no `SignupService`, e nenhum caminho de escrita compara uso × plano — R19
estava só no papel. Trocar a dimensão medida não custou refatoração; custou a implementação que
faltava de qualquer jeito.

**Artefatos criados/atualizados:**
- **ADR-015** (modelo comercial), **ADR-016** (Mercado Pago fecha D3 — escolhido porque a equipe
  já o integrou em `ecommerce-revo` e `s7classificados`, além de PIX nativo e recorrência) e
  **ADR-017** (rastreamento de aquisição first-party, sem pixel de terceiro sem consentimento) —
  em `spec-driven-erp-varejo.md`, com §3.3.11 reescrita, **§3.3.12 nova** (lead/visita/evento),
  §3.4 (endpoints novos) e §3.5.1 (V037–V040 previstas).
- **`docs/PLANO-DE-NEGOCIO.md`** — §4/§5/§6/§7/§9 reescritos e Anexo B atualizado: D1 ✅, D2 ⛔
  superada, D3 ✅, D4 ✅ revisada (multi-CNPJ ilimitado), D7 ✅ revisada, **D11/D12 novas**.
- **`docs/telas/painel-assinatura.md`** (nova tela *Minha Conta*: medidor de cota, histórico de
  12 meses, faixa recomendada e inclusão de CNPJ) e **`docs/telas/admin-marketing.md`** (funil,
  leads e "contas perto do limite", no `admin/` que ainda não existe).
- **`docs/telas/empresa.md`** — inclusão de empresa deixa de ser SQL manual (`POST /api/v1/empresas`).
- **`docs/site/briefing-landing.md`** — briefing de design da landing de venda (estrutura, copy,
  SEO, instrumentação `data-evento`, restrições), para gerar o HTML e portar para Astro.

**Regras que ficaram fixadas para a implementação:** a cota conta **venda emitida no PDV**, soma
todas as empresas do tenant, e **não** conta importação de dados legada (`ContasReceberImportador`
insere `venda` histórica — contaria 500 vendas no dia da migração) nem devolução; o bloqueio é
**409 Problem Details** e atinge **só a emissão de venda** (login, relatórios, crediário e
financeiro seguem abertos); o contador é a **única** travessia domínio→plataforma permitida (P9),
confinada a `LimiteVendasService`; e `plataforma.gerar_faixas_planos()` **nunca faz DELETE** (há FK
de `assinatura` e o preço histórico importa).

**🔴 Falta o dono do produto preencher** (são dado em `plataforma.parametro_comercial`, não código):
`preco_base`, `fator_faixa`, `preco_maximo`, `vendas_maximo` e `tolerancia_vendas`.

**Ambiente subido nesta sessão** para inspeção das telas de assinatura: compose (db 5435, MinIO
9300/9301, fake-gcs 4443) + migrations V001–V036 + API e os dois fronts. Dois desvios registrados:
a **8080 estava ocupada** pela API do `calcemoda-v2` (a API do Nainer subiu na **8081**, com os
`config.js` de `web/` e `site/` apontados para lá — reverter com `git checkout` quando a 8080
liberar), e os **fronts rodam pelo compose**, porque os `node_modules` do repositório têm binário
nativo `linux-arm64-musl` (rolldown/rollup) e `npm run dev` no host quebra com `MODULE_NOT_FOUND`.

**Implementado no mesmo dia (branch `feat/modelo-comercial-assinatura`):**

- **V037** — `plataforma.parametro_comercial` (singleton com preço-base, passo, fator, teto,
  tolerância e desconto anual), `plano` ganha `limite_vendas_mes`/`faixa_ordem`/`gratuito`, e a
  função `plataforma.gerar_faixas_planos()` que **regera** as faixas a partir dos parâmetros
  (UPDATE/INSERT por `faixa_ordem`, **nunca DELETE** — há FK de `assinatura`). Os 3 planos de
  V012 ficam desativados, não apagados. Gerou 21 linhas: Gratuito + 20 faixas.
- **V038** — contador (`uso_tenant.competencia_vendas/qtd_vendas_mes/qtd_empresas`),
  `uso_venda_mes` (histórico mensal), `assinatura.tolerancia_vendas` (override por cliente) e a
  migração dos tenants `TRIAL` para `ATIVA` no Gratuito. O backfill roda tenant a tenant com
  `set_config('app.id_tenant', …)` porque `empresa`/`venda` têm **FORCE ROW LEVEL SECURITY** —
  nem o dono lê sem contexto — e conta só venda com `id_caixa IS NOT NULL` (venda do PDV), nunca
  a histórica da importação.
- **`LimiteVendasService`** (`plataforma.uso`) — incrementa e valida em **uma chamada só**
  (`INSERT … ON CONFLICT DO UPDATE … RETURNING`), o que trava a linha do tenant e serializa
  vendas concorrentes; checar antes e incrementar depois deixaria duas vendas simultâneas
  passarem pelo mesmo último slot. Chamado por `PdvVendaService.efetivarVenda()` **depois** de
  toda validação de negócio e antes da primeira escrita (venda que ia falhar por outro motivo
  não pode consumir cota). Sem assinatura viva ou faixa sem teto ⇒ passa: falta de dado no
  control-plane não pode impedir a loja de vender.
- **409 com números** — `LimiteVendasExcedidoException` vira Problem Details
  `urn:niner:erro:limite-de-vendas` carregando `usadas`/`limite`/`tolerancia`/`faixaRecomendada`,
  para a tela oferecer o upgrade em vez de só dizer "erro".
- **`GET /api/v1/minha-conta`** + tela **Minha Conta** (`web/src/pages/plataforma/MinhaConta.tsx`,
  rota ADMIN-only, entrada no menu de Configurações): medidor com escada NORMAL → ATENÇÃO (80%) →
  TOLERÂNCIA → BLOQUEADO, histórico de 12 meses, faixa recomendada pelo **pico** (não pela média —
  assinar pela média deixa o lojista bloqueado no primeiro mês forte) e a grade de CNPJs.
- **`POST /api/v1/empresas`** — inclusão de CNPJ pelo ADMIN (antes era SQL manual): calcula
  `codigo_empresa`, marca `matriz = false`, grava `cfg_nome_etiqueta` padrão e cria o vínculo
  `usuario_empresa`. Não replica plano de contas/carteiras/perfis fiscais (são por tenant).
- **Signup** agora nasce `ATIVA` no Gratuito, sem `trial_expira_em`; `AssinarResponse` troca
  `trialExpiraEm` por `limiteVendasMes`. Site: `/assinar`, `/bem-vindo`, CTAs e a seção de planos
  do `index` reescritos para "grátis até 100 vendas/mês, sem prazo" (a landing nova vem depois,
  a partir de `docs/site/briefing-landing.md`).
- **`CotaVendasTest`** — 12 casos verdes, incluindo: tolerância aceita, além dela **409 sem
  gravar a venda nem o incremento**, virada de mês zera e arquiva, cancelamento **não** devolve
  cota, venda gravada fora do PDV não consome (guarda contra alguém trocar o contador por
  trigger), dois CNPJs somando na mesma cota, e isolamento entre tenants.
- **Suíte completa:** 762 testes, 1 falha corrigida (`OnboardingTrialTest` → renomeado para
  `OnboardingContaGratuitaTest`, que agora espera Gratuito/ATIVA). Restam 5 erros em
  `ProdutoImagemCrudTest`, **de ambiente, não do código**: o `libwebp-imageio.dylib` não carrega
  neste Mac (`UnsatisfiedLinkError`).

**Landing nova, cobrança e funil de aquisição (mesma sessão, continuação):**

- **Landing implementada a partir do Claude Design** — o HTML voltou fiel ao briefing e virou
  `site/src/pages/index.astro` + `src/styles/landing.css`. Mudanças conscientes na portagem:
  placeholders de contato saíram do markup para `src/dados/contato.ts` (o mesmo
  `[PLACEHOLDER: número WhatsApp]` aparecia em 3 links), **link/seção sem dado real não é
  publicado** (sem WhatsApp o botão some; sem depoimento real a seção some — briefing §9), a
  tabela de faixas é preenchida em runtime por `GET /api/publico/planos`, e o alternador
  Mensal/Anual + aviso de cookies ganharam comportamento. SEO: JSON-LD (SoftwareApplication +
  Organization + **FAQPage gerado da mesma lista renderizada**, para não divergir do visível),
  OG/Twitter, `@astrojs/sitemap` e `robots.txt`.
- **Medição própria (ADR-017) implementada ponta a ponta:** `site/public/beacon.js`
  (visitante_id em cookie first-party, UTM da **primeira** visita, cliques `data-evento`,
  profundidade de leitura, `sendBeacon` com fila e falha silenciosa), **V040** (`lead`,
  `visita_site`, `evento_marketing`), `POST /api/publico/eventos` e `/leads`, e o **gerenciador
  de marketing** no control-plane: `GET /api/admin/marketing/{funil,leads,leads/{id},
  contas-perto-do-limite}` — o funil responde *quanto de MRR cada origem gerou*, que é a pergunta
  que ferramenta externa não responde (ela não conhece `plataforma.assinatura`).
- **Cobrança Mercado Pago (ADR-016):** **V039** (fatura ganha faixa/ciclo/PIX; `webhook_gateway`
  ganha retentativa e dead-letter), `GatewayCobranca` + `MercadoPagoAdapter` (Payments API v1 no
  `HttpClient` do JDK, sem SDK), `POST /api/v1/assinatura/pagamento`, webhook público idempotente
  com validação de `x-signature` (HMAC-SHA256, comparação em tempo constante) e o worker
  `CobrancaWebhookJob`/`CobrancaWebhookProcessador`. Regra central: **o webhook não decide nada** —
  o worker reconsulta o gateway, então notificação forjada não paga fatura nem promove faixa.
  No `web/`, o painel ganhou o modal de PIX (QR + copia-e-cola + consulta até confirmar).

**Três bugs encontrados pelos próprios testes (e o que cada um ensina):**

1. **Rollback silencioso no signup.** Ao chamar a medição *dentro* da transação do signup e
   capturar a exceção em Java, o Postgres já tinha abortado a transação — e `COMMIT` sobre
   transação abortada **é tratado como ROLLBACK, devolvendo sucesso**. Resultado: 201 com token
   válido e a conta inteira inexistente. Corrigido movendo o fechamento do funil para **depois do
   commit** (no `OnboardingController`): em transação separada (`REQUIRES_NEW`) também não
   funciona, porque a FK `lead.id_tenant → tenant` não enxerga o tenant ainda não commitado.
   Regressão coberta por `AquisicaoFunilTest.signupSobreviveAFalhaDeMedicao`.
2. **`@Transactional` em auto-invocação.** O worker chamava os próprios métodos transacionais
   (`this.pegarLote()`), e o proxy do Spring não intercepta isso: o `FOR UPDATE SKIP LOCKED`
   rodaria fora de transação, soltando o lock na hora e deixando dois workers pegarem o mesmo
   evento. Separado em `CobrancaWebhookJob` (agenda) × `CobrancaWebhookProcessador` (transaciona).
3. **`ON CONFLICT` sobre índice parcial** precisa repetir o predicado do índice
   (`pagamento_gateway_transacao_uk` é parcial, V007) — sem isso o Postgres não encontra o índice
   e a inserção falha.

Além deles, o teste de cobrança tinha um vício próprio: o Mercado Pago falso devolvia sempre o
mesmo `payment id`, então a idempotência de `pagamento` reaproveitava a cobrança do teste anterior
e um caso passava **por engano**. O fake passou a gerar um id por cobrança, como o gateway real.

**Suíte:** 774 testes, 0 falhas — só os 5 erros de ambiente de `ProdutoImagemCrudTest`
(`libwebp-imageio.dylib` não carrega neste Mac). Novos: `CobrancaMercadoPagoTest` (7) e
`AquisicaoFunilTest` (5).

**🔐 Credencial de gateway não entra no repositório:** o `docs/mercadopago/api.md` deixado na
árvore de trabalho contém credenciais **de produção** (`APP_USR-`) e foi adicionado ao
`.gitignore` antes de qualquer commit. Access token e segredo de webhook vivem em
`NINER_MP_ACCESS_TOKEN`/`NINER_MP_WEBHOOK_SECRET`.

**▶️ Plano combinado para amanhã (2026-08-19):** subir no VPS e abrir o **plano gratuito**;
a assinatura paga vem depois, testada com token de produção. O caminho do cliente gratuito está
completo e testado, mas **cinco bloqueadores operacionais** precisam cair antes do primeiro
cadastro real — `/api/admin/**` ainda é `permitAll` (expõe lead com dado pessoal), segredos com
valor de desenvolvimento (o JWT default permite forjar token de qualquer tenant), backup
inexistente, sem rate limit no `/api/publico/**` e sem recuperação de senha. Detalhe, motivo e
ordem de execução: **`docs/infra/checklist-producao.md`**. DNS: **`docs/infra/dns-producao.md`**
(Opção A, direto no registro.br).

**▶️ Retomar amanhã (onde exatamente paramos):**

1. **Preencher os 5 parâmetros comerciais** e regerar as faixas:
   `UPDATE plataforma.parametro_comercial SET preco_base = …, fator_faixa = …, preco_maximo = …,
   vendas_maximo = …, tolerancia_vendas = … WHERE id = 1;` seguido de
   `SELECT plataforma.gerar_faixas_planos();` — sem deploy, sem migration.
2. **Destravar o PIX no sandbox.** As credenciais que o dono do produto entregou **são de
   usuário de teste** — confirmado por `GET /users/me` (`tags: [… test_user], nickname
   TESTUSER…`); o prefixo `APP_USR-` não indica produção, é a credencial "live" de uma conta de
   sandbox (registrado no CLAUDE.md, porque induziu a conclusão errada aqui). Com elas, a API
   sobe e fala com o Mercado Pago, mas `POST /v1/payments` responde **401 `Unauthorized use of
   live credentials`**: falta **chave PIX registrada** nessa conta de teste. Caminhos: registrar
   a chave PIX na conta de teste, usar as credenciais **`TEST-`** da aplicação, ou provisionar o
   test user pelo **MCP Server do Mercado Pago** (`.mcp.json` criado; falta rodar `/mcp`) e usar
   `get_credentials`/`create_test_user`. Falta também um túnel público em
   `NINER_MP_NOTIFICATION_URL` para o webhook chegar em dev. Até lá, a cobrança está provada
   contra um gateway falso local (`CobrancaMercadoPagoTest`, 7 casos), não contra o MP real.
3. **Dados reais da landing** em `site/src/dados/contato.ts` (WhatsApp, Instagram, e-mail, CNPJ,
   domínio) + os dois prints (`[PRINT: PDV]`, `[PRINT: Emissão de NFC-e]`) e a `og.png` 1200×630.
   Enquanto estiverem vazios, a página **esconde** os links em vez de publicar `wa.me/[PLACEHOLDER]`.
4. **Backoffice `admin/`**: os endpoints do gerenciador de marketing existem e estão testados, mas
   o app React não existe — é a próxima tela grande. Enquanto isso, `/api/admin/**` segue
   `permitAll` (TODO(jwt) em `SegurancaConfig`): **não expor essa superfície fora da máquina local**.
5. Decidir as duas questões em aberto de `docs/telas/painel-assinatura.md`: aviso de cota também
   no PDV (recomendação: a partir de 90%) e tolerância global × negociável por cliente.
6. A branch `feat/modelo-comercial-assinatura` está no `origin`, **sem PR e sem merge na `main`**.

**🐛 Bug encontrado ao subir (ainda não corrigido — fiscal está com outro programador):** `FiscalContingenciaDrenoJob` explode a cada
5 minutos — `DocumentoFiscalRepositorio.java:210` compara `u.ambiente` (`smallint` 1/2 em
`cfg_uf_autorizador`, V034) com `c.ambiente` (ENUM `ambiente_fiscal`, V035): `ERROR: operator does
not exist: smallint = ambiente_fiscal`. A própria query já converte certo duas linhas acima
(`CASE c.ambiente WHEN 'PRODUCAO' THEN 1 ELSE 2 END`), só não reaproveita no JOIN. Efeito real:
**o dreno de contingência nunca drenou nada** — nota emitida offline ficaria parada.


### 2026-08-19 — Perfis Fiscais: 2 perfis padrão no signup, badge Simples/MEI na grade, motor recusa CST tributado sem alíquota

Três pedidos do dono do produto na mesma sessão, cada um fechando uma lacuna real do módulo
fiscal (`docs/telas/fiscal-perfil.md`).

**1) Perfis padrão semeados no signup** — fecha a questão que a spec já carregava desde 2026-08-17
("🔴 Semear os dois perfis padrão no signup? Recomendação é sim. Decisão do dono do produto").
Todo tenant novo já nasce com **REVENDA TRIBUTADA NORMAL** (CSOSN 102, CFOP 5102) e **REVENDA COM
SUBSTITUIÇÃO TRIBUTÁRIA (ST)** (CSOSN 500, CFOP 5405), cada um com 3 regras (CRT 1, 2 e 4 — DF37),
sempre para `uf_destino='*'`/`CONSUMIDOR_FINAL`/`VENDA_CONSUMIDOR` — o único contexto que
`VendaFiscalAssembler.buscarRegra` consulta na emissão de NFC-e do v1. PIS/COFINS sempre CST 99.
Seed em `SignupService.assinar` (mesma transação e mesmo padrão dos 6 tipos de carteira e das 76
contas do plano de contas). CST de ICMS fica de fora de propósito — a divergência do CRT 2 (§8.2
do estudo) é decisão do contador, não do ERP.

**2) Coluna "Regime" (Simples/MEI) na grade** — não é campo novo do cadastro, é **derivado** das
regras que o perfil já tem: `PerfilFiscalService.listar()` ganhou duas subqueries `EXISTS` (CRT 1
ou 2 ⇒ "Simples"; CRT 4 ⇒ "MEI"), mesmo padrão do `quantidadeRegras` que já existia. Um perfil com
regra pros três CRT — como os dois semeados — mostra os dois badges juntos.

**3) Motor recusa CST de ICMS tributado sem alíquota** — achado ao vivo pelo dono do produto
perguntando "quando está no CRT 2, não teria que colocar a alíquota de ICMS?": nada impedia salvar
uma regra com CST tributado (00/10/20/51/70/90) e alíquota em branco — a nota saía **autorizada
normalmente, com ICMS R$ 0,00**, erro só visível depois, na contabilidade (mesma classe de risco
que já motivou a recusa do CST 01 de PIS/COFINS e do CSOSN 101). Corrigido em
`MotorTributario.calcularIcms` — validação de **conteúdo tributário fica no motor**, não no CRUD
do perfil, mesmo critério já usado pro resto (CST/CSOSN inválido, CST 01). CST **60** (ICMS já
retido por substituição tributária, mesma lógica do CSOSN 500) é a exceção deliberada: alíquota
zerada ali é o caso normal, não erro.

**Testado:** os 2 perfis aplicados também no tenant de dev (via SQL direto — signups antigos não
recebem o seed automático), confirmado ao vivo no navegador (grid com os dois badges, formulário
com as 3 regras). Havia um perfil de teste mais antigo ("TRIBUTADO NORMAL", CRT 1+4 só, 1 produto
vinculado) — a pedido do usuário, excluído depois de repontar o produto pro novo perfil.
`MotorTributarioTest` ganhou 7 casos novos (CST tributado × alíquota zerada para cada código,
mais o caso de exceção do CST 60) — suíte inteira roda em milissegundos, sem Spring/Testcontainers
(o motor é puro, por desenho). `PerfilFiscalCrudTest` ganhou 2 casos confirmando o seed do signup.
**750/750 testes de backend verdes**, `tsc -b` limpo.

Detalhes completos: `docs/telas/fiscal-perfil.md` (seções "Grid — coluna Regime", "Perfis
semeados no signup", regra de negócio nº 2) e `docs/MODULOFISCAL.md` §8.2.

### 2026-08-19 — Tela nova "Dados da Empresa": fecha a lacuna de CNPJ/IE/IM/código IBGE/CNAE que a Conformidade Fiscal cobrava

Reportado ao vivo pelo dono do produto testando a Conformidade Fiscal: *"na conformidade fiscal,
na seção empresas, tem 6 pendências... mas quando mando corrigir não me aparece pra cadastrar o
CNPJ da empresa, onde a gente coloca isso??"*. Investigação confirmou: `empresa` era **só
leitura** desde sempre — `EmpresaController` só tinha `GET /api/v1/empresas`/`.../permitidas`,
zero `POST`/`PUT`, nenhuma tela editava CNPJ/Inscrição Estadual/Inscrição Municipal/código de
município IBGE/CNAE, apesar de esses campos existirem em `empresa` desde `V014` (bloco fiscal
adicionado em 2026-08-16). 4 das 6 pendências de "Empresa" nem mostravam o botão "Corrigir"
(`telaCorrecao = null`); a 5ª levava para `/fiscal/configuracao`, que trata de campos diferentes
(CRT/emissão/séries/CSC). É a **terceira vez** no projeto que a mesma causa raiz aparece — Tipo
de Carteira e Produto tiveram o mesmo problema em 2026-08-18.

**Construído:** `EmpresaService.buscarPorId`/`atualizar` (ADMIN-only, CNPJ e e-mail validados
reaproveitando `FornecedorService.cnpjValido`/`emailValido`, texto livre em maiúsculas,
`DuplicateKeyException` de CNPJ vira 409) + `GET`/`PUT /api/v1/empresas/{id}`. Front:
`EmpresaLista.tsx` (grade simples, sem paginação/busca — `EmpresaService.listar()` já documenta
"no máximo poucas dezenas") e `EmpresaForm.tsx` (**primeiro CRUD do projeto sem criar/excluir** —
sempre em modo edição; Razão Social/Código/Matriz somente leitura; CNPJ com máscara alfanumérica;
CEP autopreenche endereço via ViaCEP), rotas `/empresas` e `/empresas/:id` dentro do bloco
`RequireAdmin`, menu "Configurações › Empresas", ícone novo (`IconeEmpresa`).
`ConformidadeFiscalService.pendenciasEmpresa` passou a apontar `telaCorrecao = "identidade.empresa"`
nas 4 pendências que antes não levavam a lugar nenhum.

**Nada é obrigatório para salvar** — quem cobra o preenchimento é a Conformidade Fiscal, não este
formulário (mesmo princípio de `TipoCarteiraForm`/`FornecedorForm`).

**Testes:** `EmpresaCrudTest` novo (8 casos: sucesso, campos em branco aceitos, CNPJ/e-mail
inválidos rejeitados com 400, salvar de novo com o mesmo CNPJ não conflita consigo mesma,
OPERADOR recebe 403, empresa de outro tenant responde 404, campos estruturais não mudam) +
1 caso novo em `ConformidadeFiscalCrudTest`. **741/741 testes de backend verdes**, `tsc -b`
limpo. **Achado ao escrever os testes:** os CNPJs usados em `ConformidadeFiscalCrudTest` (via
`UPDATE` direto no banco, sem validação) não são CNPJs válidos de verdade — o único confirmado
válido no projeto é `11222333000181` (já usado em `FornecedorCrudTest`), reaproveitado aqui.

**Verificado ao vivo no navegador:** editada a Loja Dev Claudio — CNPJ, IE, IM, código IBGE,
CNAE, CEP autopreenchendo endereço. Pendências de "Empresa" na Conformidade Fiscal caíram de
**6 para 2** (só restaram as de configuração fiscal/certificado, de outra tela).

**Fora do escopo, deliberadamente:** criar/excluir empresa pela UI (segue via SQL direto) e
lookup/autocomplete de município IBGE (campo de texto puro, sem tabela de referência).

Detalhes completos: `docs/telas/empresa.md`.

### 2026-08-19 — Fechamento de Caixa redesenhado: fim da contagem "às cegas", grade "Caixas Abertos", impressão térmica

Pedido direto e detalhado do dono do produto, em 5 itens numerados, reformulando por completo a
rotina de caixa que existia desde 2026-07-30 (`docs/telas/fechamento-caixa.md`, `docs/telas/
abertura-caixa.md`):

1. **Abertura de Caixa deixou de ter tela dedicada.** `web/src/pages/caixa/AberturaCaixa.tsx`
   apagado, rota `/abertura-caixa` removida de `App.tsx` — o popup obrigatório
   (`AberturaCaixaModal.tsx`, já embutido no PDV e no Recebimento de Crediário desde que a feature
   nasceu) já cobria inteiramente o requisito ("abrir na hora de iniciar uma venda/recebimento, se
   ainda não estiver aberto"). Nenhuma mudança de backend.
2. **"Fechamento de Caixa" virou item direto do menu Frente de Loja** — o subgrupo "Caixa" (que só
   tinha Abertura + Fechamento) foi dissolvido, mesmo padrão já usado antes pro grupo
   "Reimpressões" (`web/src/lib/menu.ts`).
3. **Fim da contagem "às cegas".** A tela abre direto numa grade **"Caixas Abertos"**
   (`GET /api/v1/caixa/abertos` — OPERADOR só vê os próprios, ADMIN vê de todo mundo em qualquer
   empresa). Escolhida uma linha, cada carteira aparece com **Valor em Caixa | Valor Contado** lado
   a lado — o campo de contagem já nasce preenchido com o valor esperado. `GET /api/v1/caixa/
   fechamento?idUsuario=&data=` foi removido, substituído por `GET .../fechamento/{idCaixa}`.
4. **"Fechar Mesmo Assim".** Se a contagem não bate, a tela mostra a divergência (como sempre) mas
   agora oferece fechar de propósito com a diferença registrada em `caixa_mestre.observacoes`
   ("FECHADO COM DIVERGENCIA…") — `POST .../fechamento` ganhou `forcarComDivergencia: boolean`. Sem
   a flag, o comportamento continua idêntico ao de sempre (nada é gravado até confirmar).
5. **Impressão virou bobina térmica 80mm/42 colunas** — "mesmo formato e dimensões da venda". Era
   folha A4/96 colunas (`fechamentoCaixaImpressao.ts` reescrito, reaproveita as classes CSS da
   papeleta de venda; `@page fechamento-a4` removido de `styles.css`). O popup de impressão perdeu
   "Salvar PDF" e trocou "Fechar" por um ✕ no canto, mesmo padrão abaixo.

Reabertura de caixa fechado (ADMIN-only, já existente desde 2026-08-14) segue igual, mas como um
caixa fechado some da grade de abertos, a tela ganhou um campo "Ver Caixa Já Fechado (nº)" pra
localizá-lo — a mensagem de erro do guard "caixa fechado bloqueia desfazer" já cita esse número.

**Mesmo pedido, item 7 — limpeza dos popups de impressão.** Papeleta de Venda, Comprovante de
Pagamento de Crediário e as respectivas reimpressões perderam o botão "Salvar PDF" (o diálogo
nativo de impressão já oferece isso) e o botão "Fechar" virou um ✕ no cabeçalho — o rodapé agora
tem só "Enviar por WhatsApp" (esquerda) e "Imprimir" (direita). `lib/comprovante.ts` perdeu as
funções `gerarPdfComprovanteVenda`/`gerarPdfComprovante` (mortas), mantendo só os geradores de
Blob usados pelo compartilhamento por WhatsApp.

**Testes:** `FechamentoCaixaCrudTest` — todos os métodos que buscavam por data/usuário convertidos
pro `GET` por id, +5 testes novos (`/abertos` operador/admin, caixa some da grade ao fechar,
force-close grava a observação, 404 em caixa inexistente); `CancelamentoVendaCrudTest` (helper
interno) também dependia do endpoint removido, ajustado no mesmo lote. **732/732 testes de backend
verdes.** Verificado ao vivo no navegador ponta a ponta: abertura inline no PDV, grade de abertos,
contagem com valor visível, divergência proposital, "Fechar Mesmo Assim", impressão térmica abrindo
sozinha, caixa sumindo da grade, localizado de novo por número, reaberto, fechado limpo.

Detalhes completos: `docs/telas/fechamento-caixa.md` (seção "Redesenho 2026-08-19"),
`docs/telas/abertura-caixa.md` (seção "Revisão 2026-08-19").

### 2026-08-19 — Bug real corrigido: "duplicate key" na Importação de Estoque (produto sem grade)

Reportado ao vivo pelo dono do produto, importando `ESTOQUE.xlsx` de verdade: erro na 3ª linha,
"PreparedStatementCallback; SQL [INSERT INTO produto_barra ...]; ERROR: duplicate key value
violates unique constraint \"produto_barra_variacao_uk\"" — aparecia como erro no relatório mesmo
pedindo a importação de verdade, porque qualquer erro faz `RelatorioImportacao.concluir` recusar
confirmar (nada é gravado).

**Causa:** `EstoqueImportador` agrupava linhas por `idProduto+idCor+idTamanho` usando o texto cru
de `NOME_COR`/`NOME_TAMANHO`, sem saber ainda se o produto tinha grade real. Pra produto SEM grade
real, `ProdutoBarraService.criarParaImportacaoEmMassa` sempre força cor/tamanho pro sentinela
`id=1` — mas só DEPOIS de o grupo já ter sido formado. Duas linhas do MESMO produto sem grade com
texto de cor/tamanho diferente entre si (comum em planilha migrada — algumas linhas em branco,
outras com um texto tipo "ÚNICO") viravam DOIS grupos; o 1º criava a variação `(id_produto,1,1)`,
o 2º tentava criar a mesma de novo.

**Corrigido** dividindo o processamento em duas passadas: a 1ª só resolve `CODIGO_PRODUTO` →
`idProduto` (pra pré-buscar `id_grade` em lote); a 2ª força cor/tamanho pra `null` já na montagem
da chave do grupo quando o produto não tem grade real. Produto com grade real não muda de
comportamento.

**Teste novo:** `EstoqueImportadorCrudTest` — a Rotina de Importação de Dados não tinha nenhum
teste automatizado até então, apesar de ser uma feature grande (lacuna notada, não preenchida por
completo — só o suficiente pra cobrir esta regressão). Confirmado que o teste reproduz a mensagem
exata do usuário contra o código sem a correção, e passa depois. **732/732 testes de backend
verdes.** API reconstruída (`docker compose up -d --build api`) pra aplicar o fix no ambiente que o
usuário estava usando.

Detalhes: `docs/PROGRESSO.md` (esta entrada), memória `project_importacao_dados.md`.

### 2026-08-18 — 🔴 BUG CRÍTICO ACHADO E CORRIGIDO: NFC-e autorizada pela SEFAZ saía reportada como REJEITADA

Pedido do usuário: "posso testar a emissão da NFC-e?" — sim, e testar **contra a SEFAZ-PR real**
(não mock) foi o que expôs este bug. Configuração usada: certificado A1 real da MITRYUSCASH
(`CERTIFICADO_HOMOLOGACAO/`, o mesmo do B0) + truststore ICP-Brasil (`api/secrets/
truststore-icpbrasil.p12`, senha `changeit`) ligados na API via `NINER_FISCAL_TRUSTSTORE`/
`_SENHA` (novo no `docker-compose.yml`/`.env`, não existiam antes — só o B0 usava fora do produto).

**O bug:** `SefazTransporte.enviar()` extraía `cStat`/`xMotivo`/`nProt`/`chNFe` do **primeiro**
match no corpo inteiro da resposta. A resposta síncrona (`indSinc=1`) real da SEFAZ-PR tem **dois**
`cStat`: um no nível do **lote** (`retEnviNFe`, sempre `104` "Lote processado" quando o lote foi
aceito — não diz nada sobre a nota) e outro dentro de `protNFe/infProt`, o resultado **real** da
nota. A extração pegava o `104` do lote e reportava **REJEITADO**, mesmo quando a nota estava
`cStat 100` "Autorizado o uso da NF-e" ali dentro, com protocolo e chave corretos.
**Toda emissão síncrona real neste UF teria saído com esse diagnóstico errado** — achado só porque
esta foi a primeira vez que o produto falou com a SEFAZ de verdade fora de um script de PoC.

**Por que nenhum teste pegava isso:** todos os fixtures mockados de `RespostaSefaz` neste projeto
(`VendaFiscalEmissaoTest`, `CancelamentoNfceTest`, `DocumentoFiscalReprocessamentoTest`,
`FiscalInutilizacaoTest`, e o próprio `SefazTransporteTest`) têm **um só** `cStat` no corpo — nunca
os dois juntos como a SEFAZ manda de verdade. O caminho "pega o primeiro" nunca tinha chance de
errar contra esses fixtures.

**Correção** (`SefazTransporte.java`): extrai o bloco `<infProt>...</infProt>` primeiro (mesma
técnica do `ArquivamentoXmlService`, ignorando prefixo de namespace) e busca `cStat`/`xMotivo`/
`nProt`/`chNFe` **dentro dele** por padrão; só cai pro corpo inteiro quando o campo específico não
está lá (cobre lote rejeitado sem `protNFe` nenhum, e não quebra o fixture do
`SefazTransporteTest`, que só tem `chNFe`/`nProt` dentro de `infProt`, não `cStat`/`xMotivo`).

**Provado corrigido, ao vivo, contra a SEFAZ real:**
1. `POST .../documentos/3/consultar-sefaz` no documento com o diagnóstico errado → devolveu
   `cStat 100 "Autorizado o uso da NF-e"` corretamente (a nota real nunca esteve errada, só o
   diagnóstico).
2. Uma emissão nova do zero (venda → NFC-e) com o binário corrigido → `"situacao":"AUTORIZADO"`
   direto, sem precisar de reprocessamento.

Suíte completa depois da correção: **687/687 verdes** (só um teste específico do
`SefazTransporteTest` precisou de um `extrairComEscopo` com fallback por campo, não um switch
tudo-ou-nada, pra não quebrar o fixture simplificado dele).

**Pendência de dev, sem risco:** o documento id 3 do tenant de teste local (a primeira emissão,
antes da correção) ficou com `situacao=REJEITADO` pra sempre (o reprocessamento só aceita
`TRANSMITINDO`/`ASSINADO`, não `REJEITADO`) — é dado de teste, a nota real está autorizada na
SEFAZ (confirmado pela consulta acima), só o registro local ficou desatualizado. Sem ação
necessária; o documento id 4 (emitido já com a correção) está `AUTORIZADO` normalmente.

### 2026-08-18 — Arquivamento do XML fiscal no bucket privado (implementa o handoff no mesmo dia)

`docs/HANDOFF-ARQUIVAMENTO-XML.md` foi escrito em 2026-08-17 para entregar essa tarefa a outro
desenvolvedor — acabou implementada no dia seguinte, seguindo a spec à risca (contrato, critérios
de aceitação, definição de pronto). Fecha de vez a DF21/B8 (bucket fiscal, que tinha ficado de fora
do B8 por falta de infra — a infra chegou horas depois, ADR-014).

**O que foi feito**, novo em `fiscal.documento`:
- `ArquivamentoXmlService` — arquiva nota autorizada (monta o `nfeProc`), evento autorizado
  (cancelamento 110111) e inutilização homologada. Best-effort (`*SeAplicavel`, nunca lança) no
  **caminho quente**, chamado logo após `marcarAutorizado`/`registrarTentativaCancelamento`/
  `registrarTentativa` em `EmissaoNfceService`, `DocumentoFiscalReprocessamentoService`,
  `FiscalContingenciaDrenoJob`, `CancelamentoNfceService` e `FiscalInutilizacaoService`.
- `ArquivamentoXmlJob` — rede de segurança, `@Scheduled` a cada 10 min, mesmo padrão P8 de
  `FiscalContingenciaDrenoJob` (varredura global de tenants pendentes, `TenantContext.comTenant`
  por item).
- `V036__fiscal_arquivamento.sql` — índices parciais da fila (uma por tabela) + comentário
  documentando que `xml_hash` muda de significado depois do arquivamento (handoff §6.2).
- `ValidadorXsd.validarProcNfe` — valida o `nfeProc` contra `procNFe_v4.00.xsd` **antes** de subir
  ao bucket (F11): mais barato recusar aqui do que descobrir na guarda de 5 anos que o XML está
  errado.
- `DocumentoFiscalConsultaService.buscarXml` — `GET .../documentos/{id}/xml` passa a servir o
  `nfeProc` do bucket (com protocolo) quando já arquivado; fallback pro `xml_assinado` antes disso.

**O que o handoff avisava e se confirmou na prática:**
- `nfeProc` montado por **concatenação de texto** (nunca DOM/reserialização) — o `xml_assinado` já
  é exatamente `<NFe ...>...</NFe>` sem prólogo, colado byte a byte com o `<protNFe>` extraído por
  regex (ignorando prefixo de namespace) de `documento_fiscal.status_sefaz` (a resposta CRUA da
  SEFAZ, F9).
- Idempotência (P2): chave determinística por `{ano}/{mes}/{modelo}/{chave}.xml`; a área
  `FISCAL_XML` recusa sobrescrever (409); no 409, confere hash do já-gravado contra o que se
  tentaria gravar — igual segue, diferente para com erro (nunca "resolve" apagando).
- Arquivamento sempre **fora de transação de banco** (F2, chamada de rede).

**⚠️ Achado real ao testar (não é bug do arquivamento, é fixture de teste desatualizado):** o
fixture `<retEnviNFe><protNFe><infProt><cStat>100</cStat></infProt></protNFe></retEnviNFe>` usado
em `VendaFiscalEmissaoTest`/`CancelamentoNfceTest`/`FiscalInutilizacaoTest`/
`DocumentoFiscalReprocessamentoTest` **não é XSD-válido** — `protNFe` exige `versao="4.00"`, e
`infProt` exige `tpAmb/verAplic/chNFe/dhRecbto/cStat/xMotivo` nessa ordem (`nProt`/`digVal` são os
únicos opcionais). Nenhum teste anterior expunha isso porque nenhum reconstrói e valida o `nfeProc`
de verdade — só o novo `ArquivamentoXmlTest` faz essa prova, com fixture próprio corrigido
(os 4 testes fiscais antigos continuam passando porque o arquivamento é best-effort: a exceção de
XSD é só logada, nunca quebra o teste — mas fica pendente pro job, que também não teria bucket pra
gravar naqueles contextos).

**Testado contra infraestrutura real** (`ArquivamentoXmlTest`, novo — Postgres real via
Testcontainers **e MinIO real** via Testcontainers, buckets com Object Lock igual ao
`infra/minio/bootstrap.sh`): nota autorizada arquiva automaticamente, `nfeProc` no bucket valida
contra o XSD oficial de verdade (não só "a coluna foi preenchida"), arquivar de novo é idempotente,
`GET .../xml` serve o `nfeProc` depois de arquivado, cancelamento autorizado arquiva o evento.
4 testes novos. **Suíte completa: 687/687 verdes**, `mvn compile`/`test-compile` limpos.

**O que ficou de fora, documentado no próprio `HANDOFF-ARQUIVAMENTO-XML.md`:** ZIP do contador
(DF22, depende de decisão de produto sobre entrega), apagar `xml_assinado` do banco depois de
arquivar (trigger de imutabilidade impede hoje, decisão do Evirson se incomodar o volume).

### 2026-08-18 — Pesquisa de Vendas: detalhamento em abas + reimpressão de papeleta no popup

Pedido do dono do produto: o popup de detalhe da venda (`DetalheVendaModal.tsx`) tinha as quatro
seções (dados/produtos/caixa/parcelas) **empilhadas sem abas** desde 2026-07-30 — decisão
deliberada da época, pra comparar produtos com recebimentos ao mesmo tempo. Revertido: agora são
**abas** — Dados Gerais, Produtos Vendidos, Movimentação de Caixa e **Parcelas de Crediário**
(esta só aparece quando a venda tem crediário, `detalhe.temParcelasCredario`).

Também ganhou o botão **"Reimprimir papeleta"** no cabeçalho do popup — zero lógica nova, reaproveita
`ComprovantePapeletaModal` em modo `reimpressao` (mesmo componente da tela
`ReimpressaoPapeletaVenda.tsx`). Único cuidado: o popup de reimpressão precisou virar irmão do
`.modal-overlay` do popup de detalhe (via `Fragment`), não filho dele — aninhado, um clique no
fundo do popup de reimpressão faria bubbling até o `onClick` do overlay pai e fecharia os dois de
uma vez.

CSS novo: `.abas-nav`/`.aba-botao` em `styles.css` (barra de abas com sublinhado, primeiro uso
desse padrão no projeto — não existia tab strip nenhum antes). Testado ao vivo no navegador contra
uma venda real (crediário 6x + dinheiro + PIX): as 4 abas trocam de conteúdo corretamente, a aba de
parcelas aparece por ter crediário, e o botão de reimpressão abre a papeleta em popup por cima sem
fechar o de detalhe. `tsc -b` limpo.

### 2026-08-18 — Bug real: importação de Produtos rejeitava tamanho único ("UN") com "Grade não encontrada"

Achado testando com a planilha real do dono do produto (`PRODUTOS.xlsx`, ~3.600 linhas) — duas
linhas (produtos com um único tamanho "UN": bola de futebol, kit de meias) voltavam `404 NOT_FOUND
"Grade não encontrada."` na importação.

**Causa raiz — colisão com o sentinela reservado, não falta de auto-criação.** `TAMANHO_1 = "UN"`
faz a importação achar (por nome) o `cfg_tamanho` já existente com essa descrição — mas esse é
justamente o **tamanho PADRÃO** (`id_tamanho=1`, reservado desde 2026-08-13, ver
[[project_cor_grade_tamanho]]). A grade de conteúdo `[1]` então bate com a **grade PADRÃO**
(`id_grade=1`, "PADRÃO") — e `GradeService.buscar()` recusa devolver `id=1` de propósito (é
invisível/reservada). Resultado: a rotina "achava" a grade, mas a API bloqueava a resposta.

O próprio schema já antecipava esse caso — `cfg_grade_uk`/`cfg_tamanho_uk` são índices únicos
**parciais** (`WHERE id <> 1`), ou seja, o banco sempre permitiu um segundo "UN" com id diferente
do sentinela. Só as buscas da importação não excluíam `id=1`. Corrigido em 3 pontos (mesmo padrão
em todos: acrescentar `AND id <> 1` na busca por nome/conteúdo):

1. `GradeService.buscarIdPorSlots` (usado por `obterOuCriarPorTamanhos`) — não considera mais a
   grade `id=1` como "já existente" por conteúdo.
2. `ProdutoImportador.idTamanhoOuCriar` — não reaproveita mais o tamanho `id=1` ao buscar por nome.
3. `EstoqueImportador.idOuNulo` (cor/tamanho, mesmo helper) — mesmo ajuste; sem ele, depois da
   Importação de Produtos criar um "UN" real (`id<>1`), a Importação de Estoque passaria a achar
   **dois** registros "UN" e quebrar com resultado ambíguo (`.optional()` exige 0 ou 1).

**Testado ao vivo contra a API real** (não só compilação): reproduzi as duas linhas exatas do
arquivo do dono do produto numa planilha de teste, rodei `/api/v1/importacao/produto/processar`
com `confirmar=true` — 0 erros, os dois produtos saíram com uma grade nova e real ("GRADE UN-UN",
`id_grade=2`), distinta do sentinela. Dados de teste removidos depois. Suíte completa:
**675/675 verdes** (sem teste automatizado dedicado para Importação de Dados ainda — ver
[[project_importacao_dados]], pendência já registrada antes).

**How to apply:** qualquer nova rotina que faça "achar por nome/conteúdo" em `cfg_cor`/
`cfg_tamanho`/`cfg_grade` tem que excluir `id=1` explicitamente — o sentinela nunca deve ser
"encontrado" por essas buscas, só gravado deliberadamente quando `cfg_usa_cor_grade=false`.

### 2026-08-17 (fim do dia) — Object storage **privado**: MinIO no compose (ADR-014)

Pedido do dono do produto: *"precisamos de um bucket para guardar fotos de clientes e XML de notas
fiscais; para garantir a segurança dessas informações vamos de MinIO no docker deste projeto e
depois, caso fique grande, migramos pra VPS separado."* Escopo confirmado por pergunta explícita
antes de codificar: **só a infra** — os dois consumidores (arquivamento fiscal e foto de cliente)
são tarefas próprias —, e **as fotos de produto continuam no GCS**, porque bucket público é
requisito real dos marketplaces (URL que expira quebra anúncio).

**Por que não um segundo bucket no GCS.** Custo — a mesma razão que já tinha derrubado a proposta
de bucket para o compartilhamento de comprovantes em 2026-08-07 —, e o fato de o XML ter guarda
legal de 5 anos: é o item que só cresce, nunca decresce. MinIO auto-hospedado tem custo marginal
zero em dev e disco de VPS em produção. O SDK escolhido foi o **da AWS (S3)**, não o proprietário
do MinIO: MinIO, VPS, R2 e S3 falam o mesmo protocolo, então migrar vira troca de endpoint.

1. **ADR-014 na spec** (§6, depois do ADR-013) e `docs/infra/armazenamento-privado-minio.md`.
   Escritos **antes** do código, como manda o processo.
2. **Dois buckets, porque os ciclos de vida são opostos.** `niner-fiscal-dev`: Object Lock
   **GOVERNANCE de 1825 dias** + versionamento — apagar é impossível (F6, guarda legal).
   `niner-privado-dev`: versionado e **apagável**, porque LGPD dá ao titular direito de exclusão.
   Política de retenção no S3 é por bucket, então não dava para ser um só com dois prefixos.
3. **`docker compose up -d minio minio-init`** (portas 9300/9301 — a máquina já roda outros três
   MinIO). O `infra/minio/bootstrap.sh` cria bucket, retenção, ciclo de vida e a **credencial de
   menor privilégio** da API; é idempotente (rodado duas vezes, verificado) e é o mesmo script que
   vai provisionar o VPS — só muda endpoint.
4. **A API nunca é root do MinIO.** Verificado por comando com a credencial da aplicação: grava no
   fiscal ✅, **apaga no fiscal ❌ Access Denied**, grava/apaga no privado ✅, cria bucket ❌, leitura
   anônima ❌. Vazamento da chave da API não apaga XML fiscal.
5. **`ArmazenamentoPrivado` + `AreaPrivada` + `S3ArmazenamentoPrivado`** em `comum.armazenamento` —
   interface **separada** da pública (`ArmazenamentoDeArquivos`, GCS): não tem `urlPublica()`, e
   isso é decisão, não esquecimento. `AreaPrivada` amarra bucket + prefixo + mutabilidade num
   enum, para não existir combinação errada possível. Prefixo `tenants/{id_tenant}/` sempre do
   `TenantContext` e **conferido na leitura** — se um SELECT sem filtro de `id_tenant` (o bug que a
   auditoria de 2026-08-08 provou possível) devolver chave alheia, sai 403 em vez do XML do
   vizinho. **8 testes** (`ArmazenamentoPrivadoTest`) contra MinIO real via Testcontainers.
6. **Achado durante a verificação:** em bucket versionado, `rm` **não apaga** — cria um *delete
   marker* e a versão antiga fica guardada para sempre. Para foto de produto seria detalhe; para
   dado pessoal é o oposto do que a LGPD pede, e o `mc rm` "bem-sucedido" esconde isso. Corrigido
   com regra de ciclo de vida no bucket privado (`--noncurrent-expire-days 30
   --expire-delete-marker`): exclusão vira definitiva em 30 dias, com janela para desfazer engano.
7. **DF21 fechada** (`MODULOFISCAL.md` §11.1) — o bucket fiscal deixou de ser o bloqueio do
   Arquivamento. O que falta agora é o **consumidor**: gravar o `nfeProc` e preencher
   `documento_fiscal.xml_objeto_bucket`/`xml_hash`, colunas que existem desde a V035 e seguem
   vazias. Também corrigida a linha da DF5, que ainda dizia "certificado cifrado no bucket" —
   contradizia a própria revisão da DF21 (o `.pfx` fica cifrado **no banco**).

📄 **Handoff escrito no mesmo dia:** `docs/HANDOFF-ARQUIVAMENTO-XML.md` — o arquivamento do XML
será implementado por **outro desenvolvedor**, então a tarefa saiu especificada como spec de
entrega: o que já existe e não deve ser refeito, onde estão os ganchos no código atual, como
montar o `nfeProc` sem invalidar a assinatura (armadilha silenciosa), idempotência, o trigger de
imutabilidade que barra código errado, critérios de aceitação em Dado/Quando/Então, definição de
pronto e as 3 questões que são decisão do dono do produto, não de quem implementa.

⚠️ **Duas dívidas registradas, nenhuma resolvida:** não existe **backup** do MinIO (retenção
impede apagar, não protege contra perder o disco — é pré-requisito da migração para VPS, não
tarefa posterior) e o tráfego API↔MinIO só é HTTP porque hoje não sai da rede do Docker; no VPS
precisa de TLS.

### 2026-08-17 (continuação) — B1 a B8 do módulo fiscal fechados no mesmo dia, mais 3 melhorias pós-B8

O resto do dia que começou com o B0 (NFC-e autorizada pela SEFAZ-PR, entrada abaixo). Nesta
sequência, sem pausa: **B1 → B2 → B3 → B4 → B5 → B6 → B7 (4 partes) → B8 (3 partes) → pós-B8**.
**675/675 testes de backend verdes ao final do dia**, `tsc -b` limpo no `web/`.

**B1 — specs.** Quatro telas fiscais especificadas antes de codificar (§5 do processo
spec-driven): `fiscal.configuracao`, `fiscal.perfil`, `fiscal.certificado`, `fiscal.conformidade`.

**B2 — configuração fiscal, perfis e certificado.** `fiscal.configuracao` (por **empresa**, não
por tenant — cada loja tem IE/série/ambiente próprios), `fiscal.perfil` (CRUD de
`cfg_perfil_fiscal`/`cfg_perfil_fiscal_regra` — CST/CFOP centralizados num perfil reutilizável em
vez de espalhados por produto) e `fiscal.certificado` (upload do `.pfx`, validação de validade e
de CNPJ do titular contra o CNPJ da empresa — CPF/CNPJ alfanumérico, IN RFB 2.229/2024). **DF21
revisada nesta mesma sessão:** o certificado **não vai para bucket** (os buckets do ADR-013 são de
leitura pública) — fica **cifrado no banco** (`fiscal_certificado.arquivo_cifrado`, AES-GCM, chave
fora do banco), o que o coloca sob RLS/backup do tenant como qualquer outro dado sensível, sem
depender de infra de bucket nenhuma. **DF37 fechada nesta janela também:** o Nainer atende **só MEI
e Simples Nacional** (CRT 1/2/4) — Lucro Presumido/Real ficam fora do produto, recusados com 400
explicando que é escopo, não bug. Três telas React novas.

**B3 — Conformidade Fiscal.** Painel que lista o que falta antes de ligar a emissão (produto sem
NCM/perfil, cliente sem município IBGE, empresa sem certificado/CNAE) — bloqueio **preventivo**
(F11): a recusa de ligar `emite_nfce` aponta exatamente o que corrigir, em vez de deixar o lojista
descobrir na primeira rejeição da SEFAZ com cliente no caixa.

**B4 — motor tributário.** Puro, sem I/O, testado por tabela (§16.1 do estudo é a superfície
principal do produto, não borda — cada comprador tem um regime diferente). Calcula ICMS
(normal/ST/substituição), PIS/COFINS, e **IBS/CBS para todos os regimes desde o v1** (reforma
tributária), resolvido por perfil fiscal × contexto (CRT, UF destino, tipo de destinatário,
operação).

**B5 — montagem do XML.** `MontadorXmlNfce` gera o XML da NFC-e a partir do que o motor calculou
(o montador nunca recalcula imposto) e valida contra o **XSD oficial** antes de qualquer coisa
(F11) — três tentativas gastas com `cStat 225` até parar de adivinhar e ler o schema.

**B6 — assinatura e transporte.** XMLDSig **enveloped** (RSA-SHA1 + C14N) com **só o JDK**, sem
lib de NF-e — o `java.xml.crypto` do próprio Java 25 assina no formato que a SEFAZ exige.
Transporte via `HttpClient` do JDK com **mTLS nativo** (certificado do lojista, nunca o da
Vetor — cache de `SSLContext` por impressão digital do certificado, não por tenant, para nunca
misturar). Achado: a raiz ICP-Brasil não vem no `cacerts` padrão — truststore própria configurável.

**B7 — emissão síncrona, contingência, DANFCE (4 partes).** (1) `FiscalNumeracaoService`: número
**sem buraco** (F4), alocado sob trava numa transação curta e separada da transmissão — um número
perdido é papelada (inutilização); um número repetido é rejeição no caixa com cliente na frente.
`EmissaoNfceService` orquestra montar→assinar→validar→transmitir com contingência automática
(DF19: duas falhas de comunicação seguidas entram em contingência sozinhas). (2)
`FiscalContingenciaDrenoJob`: `@Scheduled` que transmite a fila pendente quando a SEFAZ volta. (3)
PDV liga com venda real — `POST /pdv/vendas/{id}/nfce` dispara em paralelo ao cupom (F3: a venda
nunca espera a SEFAZ). (4) DANFCE de verdade na papeleta (QR Code escaneável, protocolo, tarja de
homologação) e painel de Contingência (`/fiscal/contingencia`, estado + fila + entrar/sair manual).
**Bug real achado ao vivo:** `dhEmi` com sufixo `Z` quebrava 100% das emissões (`TDateTimeUTC`
proíbe `Z`) — normalizado para o fuso de São Paulo antes de formatar.

**B8 — cancelamento, Documentos Fiscais, inutilização (3 partes, escolhidas pelo dono do produto
via pergunta explícita — Arquivamento ficou de fora, bucket ainda não provisionado).**
(1) Cancelamento de NFC-e (evento 110111) integrado ao Cancelamento de Venda existente: "estoque/
caixa e fiscal nunca divergem — ou os dois andam, ou nenhum anda"; prazo de 30 min vindo de
`cfg_uf_autorizador` (F10, nunca hardcoded); toda tentativa fica registrada mesmo quando a SEFAZ
recusa (P3). (2) Tela `/fiscal/documentos` — lista paginada por empresa/período/modelo/situação,
ver XML, consultar situação ao vivo na SEFAZ. (3) `/fiscal/inutilizacao` — a tela **detecta os
buracos sozinha** (número alocado que nunca virou nota nem já foi inutilizado, agrupado em faixas
contíguas) em vez de exigir conferência manual de sequência; bloqueio F11 antes de chamar a SEFAZ
para número já usado ou ainda não alocado. **Bug real achado incidentalmente** (não relacionado ao
B8): o job de drenagem de contingência do B7 referenciava uma coluna (`e.uf`) que não existe em
`empresa` (é `estado`) — vinha quebrando em silêncio toda vez que rodava; corrigido.

**Pós-B8 — três melhorias que não dependem do bucket, pedidas pelo dono do produto depois de um
levantamento do que faltava.** (1) `POST /documentos/{id}/reprocessar` — destrava documento preso
em `TRANSMITINDO`/`ASSINADO`: **sempre consulta a SEFAZ antes de decidir** (F5 — a nota pode ter
sido autorizada com a resposta perdida) e só retransmite quando a consulta confirma que a nota
nunca chegou lá. (2) Link de consulta pública na lista de Documentos Fiscais (mesma URL do QR
Code, extraída do XML já assinado). (3) "Ver DANFCE" na lista, reaproveitando 100% o componente já
existente do PDV (zero lógica nova).

Detalhe técnico completo (decisões de design, bugs e o porquê de cada um, convenções de XSD/Id de
evento, etc.) fica na memória de sessão do agente — este registro é só o resumo cronológico.

### 2026-08-17 — B0 concluído: **NFC-e autorizada pela SEFAZ-PR**, e a DF7 fechada no plano B

O dia em que o módulo fiscal saiu do papel. **`cStat 100 — Autorizado o uso da NF-e`**, protocolo
`141260001531993`, chave `41260837829453000135650010000000051323005118`, homologação do Paraná.

**A DF7 pode ser fechada no plano B.** O PoC (`docs/MODULOFISCAL.md` §17.1, bloco B0) rodou com o
certificado A1 real contra a SEFAZ usando **só o JDK 25** — nenhuma lib de NF-e: abriu o `.pfx`
brasileiro (empacotado com RC2-40-CBC) no PKCS12 nativo **sem BouncyCastle**, assinou em RSA-SHA1 +
C14N com `java.xml.crypto`, montou o SOAP à mão e falou mTLS pelo `HttpClient`. A lib
`br.com.swconsultoria:java-nfe` (Java 8 + `javax` + Axis2 contra nosso Java 25 jakarta) **deixa de
ser risco de arquitetura** e vira conveniência.

**Achado com consequência de deploy: a raiz ICP-Brasil não está no `cacerts` do JDK.** A cadeia da
SEFAZ-PR é CELEPAR ← AC SOLUTI SSL EV G4 ← Raiz Brasileira v10 (ITI). Sem truststore própria toda
chamada morre com `PKIX path building failed` — mensagem que não cita ICP-Brasil e faz perder horas
suspeitando do certificado do lojista. Vira requisito de ambiente. (A raiz do PoC foi extraída do
próprio handshake, o que é circular: **antes de produção tem que vir do repositório oficial do ITI**
com fingerprint conferido.) Detalhe irmão: o OpenSSL 3.x **recusa** o `.pfx` (exige `-legacy`); o
Java lê sem reclamar.

**Duas rejeições ensinaram o que nenhuma fonte secundária sabia.** O `cStat 225` (falha de schema)
persistiu por três tentativas até os XSD oficiais chegarem; lidos, o `leiauteNFe_v4.00.xsd` mostrou
que o QR v3.00 online é `?p=<chave>|3|<tpAmb>` — **o §9.5 documentava `?p=<chave>|<versao>` e omitia
o `tpAmb`** — e que o `cIdToken` do v2 não aceita zeros à esquerda (`1`, não `000001`). Depois veio
`cStat 878`, que **entregou a resposta na própria mensagem**: a URL de consulta por chave do PR não é
o host do webservice, é `http://www.fazenda.pr.gov.br/nfce/consulta`.

**✅ Uma das quatro perguntas obrigatórias da F0 está respondida, com fonte primária.** O §9.3
perguntava como a chave de acesso (44 dígitos numéricos) acomoda um emitente com **CNPJ
alfanumérico**. O pattern do XSD é `[0-9]{6}[0-9A-Z]{12}[0-9]{16}(1|3|4|9)[0-9]{9}`: as 12 posições
de raiz+ordem **aceitam `A-Z` nativamente**, o resto é numérico. Não é preciso desenho especial.

**Também neste dia:**

- **DF36 — a alíquota de PIS/COFINS vem do regime da empresa, não do perfil do produto.** A pergunta
  do dono do produto ("posso ter empresas de regimes diferentes no mesmo tenant?") revelou que
  `cfg_perfil_fiscal_regra` só distingue por `crt`, e **CRT 3 cobre Presumido E Real** — regimes com
  PIS/COFINS diferentes (0,65%/3,00% × 1,65%/7,60%). As duas empresas casariam na mesma regra e uma
  emitiria errado, em silêncio. O motor passa a ler `fiscal_config_empresa.regime_apuracao`; o perfil
  contribui só o CST, e as colunas de alíquota da regra viram **override** para CST não-01.
- **Bloco B1 completo** — as 4 specs de tela fiscais (`fiscal-configuracao`, `fiscal-certificado`,
  `fiscal-perfil`, `fiscal-conformidade`), com um padrão estrutural novo: singleton **por empresa**
  (não por tenant, como `cfg_geral`).
- **Bloco B2 começado** — módulo `fiscal.configuracao` (Dtos/Service/Controller) + 13 testes, com o
  gate do F11 (ligar a emissão valida 7 precondições e recusa com 409 listando o que falta).
- **Dois bugs de dinheiro corrigidos**, achados por varredura de código e **verificados com controle
  negativo** (rodados com o fix revertido, para provar que o teste discrimina):
  1. **Cancelamento de Entrada deixava dinheiro órfão** — o guard checava `documento_pago = true`,
     mas a marca de baixa é `data_pagamento`; `documento_pago` é um checkbox independente que nasce
     `false`. Conta baixada de verdade passava batido, o `DELETE FROM contas_pagar` executava e o
     `caixa_detalhe` ficava órfão. Mesmo bug corrigido em `ContaPagarService.excluir()` em 08-14,
     reproduzido no outro deletador. Sem o fix: `Status expected:<409> but was:<200>`.
  2. **DRE em regime caixa somava R$ 0,00** — `COALESCE(valor_pago, valor_pagar)` nunca caía no
     fallback porque a coluna é `NOT NULL DEFAULT 0`. Baixa "cheia" (só a data) ia certa para o caixa
     e zerada para a DRE: Fluxo de Caixa e DRE divergiam sobre a mesma baixa, com lucro inflado. Sem
     o fix: `expected: -500.0 but was: 0.0`.
- **Tabelas nacionais de IBS/CBS carregadas** do IT 2025.002: `cfg_cst_ibscbs` (19) e
  `cfg_cclasstrib` (141). A V034 ganhou 3 colunas que vinham na planilha e não tinham onde morar —
  `ind_nfce`, `perc_reducao_ibs`, `perc_reducao_cbs`. Não eram opcionais: só **42 dos 141** códigos
  valem em NFC-e (sem a flag a tela ofereceria 99 códigos que a SEFAZ rejeita) e **60** têm redução
  de alíquota (sem os percentuais o motor saberia que é reduzida, mas não quanto).
  ⚠️ **Uma anomalia da fonte oficial foi preservada, não corrigida:** a planilha traz `5100002`, com
  7 dígitos, enquanto os outros 141 têm 6. Confirmado no arquivo (`<t>5100002</t>` no
  `sharedStrings.xml`; `510002` não existe nele). Provavelmente é digitação errada, mas adivinhar
  código de classificação tributária faz toda nota que o usar ser rejeitada — a linha ficou em
  comentário no fim do seed, esperando confirmação no Informe Técnico. Não urge: `ind_nfce = false`.
- **243 XSD oficiais** versionados em `api/src/main/resources/xsd/`, o que habilita a validação
  local exigida pelo §16.1 (hoje o erro só aparece depois de um round-trip na SEFAZ).
- **Dois subagentes caçadores de bug** definidos em `.claude/agents/`, carregando o catálogo dos
  bugs que de fato apareceram neste repositório. A varredura devolveu 18 achados na API e 33 no
  front — **parados, aguardando triagem**; os 2 acima são os únicos já verificados e corrigidos.
  Notícia boa: nenhum vazamento de tenant em toda a base.

**523/523 testes de backend verdes.**

### 2026-08-16 — Módulo fiscal: estudo fechado e schema no banco

Dia de **começar o módulo fiscal** (NFC-e/NF-e). Sem código Java ou React ainda — o que saiu foi o
estudo do módulo e o schema inteiro, que é o que não depende de certificado digital nem de acesso à
SEFAZ.

**O estudo (`docs/MODULOFISCAL.md`, v2.1).** O arquivo já existia como pesquisa preliminar (v1.1),
escrita **de fora para dentro**: o que a legislação exige. Foi reescrito **de dentro para fora**,
com cada afirmação conferida contra `V001`–`V033` e contra as telas que já existem.

**Escopo do v1, fechado pelo dono do produto (DF35):** **NFC-e ao consumidor final** (identificado
ou não) **+ NF-e de devolução de venda**, com o ciclo de vida completo da nota — cancelamento,
inutilização, contingência offline, arquivamento e download. As outras **13 modalidades** ficaram
catalogadas em §4.2 do estudo, cada uma com *o que já fica pronto no v1* e *o que falta* — não
foram apagadas. Também decidido: emissão **própria** direto na SEFAZ (sem provedor), **UF piloto
Paraná** (que tem autorizador próprio, `nfce.sefa.pr.gov.br` — **não** SVRS, ao contrário do que a
pesquisa inicial supunha), certificado **A1 por upload**, QR Code **v3.00** apenas, e IBS/CBS
calculados **para todos os regimes** desde o v1.

**O que a leitura do código revelou** (o motivo de o estudo ter mudado de forma):

- **A lista original de operações deixava 7 modalidades de fora.** A mais imediata: **NFC-e não
  serve para venda a contribuinte de ICMS**, e o PDV é hoje a única tela de venda do sistema. Como
  a NF-e de venda ficou para depois, o PDV vai **recusar a emissão** nesse caso (estado
  `NAO_EMITIDO`, com o motivo gravado e visível) em vez de emitir o documento errado — a venda
  continua sendo gravada normalmente.
- **`produto` não tinha nenhuma coluna de unidade comercial.** `uCom`/`uTrib` são obrigatórios em
  todo item do XML, e a única unidade do sistema era `produto_fornecedor.unidade_compra`, que é a
  unidade *do fornecedor*. Era bloqueante.
- **`tipo_carteira` não tinha `tPag`, bandeira nem CNPJ de credenciadora**, e o grupo `detPag` é
  obrigatório na NFC-e.
- **O `sku` interno nunca pode ir no `cEAN`:** é EAN-13 com prefixo 9 gerado por
  `gerar_ean13_interno()`, não registrado na GS1 — a SEFAZ valida o GTIN contra a base oficial e
  rejeita. Produto sem GTIN real leva literalmente `SEM GTIN`.
- **`cfg_produto_ncm.aliquota_ibpt` não serve** para o `vTotTrib`: é uma coluna só, e o IBPT tem
  quatro alíquotas por NCM, **por UF e por vigência**.
- **Erro conceitual da v1.1, corrigido:** depois da **ADC 49/STF** e da **LC 204/2023**, ICMS **não
  incide** na transferência entre estabelecimentos do mesmo titular — o que circula é **crédito**,
  pelo **custo**, com opção anual do Convênio 109/2024. Muda o XML inteiro daquela nota.
- **CC-e não se aplica à NFC-e** (evento 110110 é de NF-e 55), então nunca entraria num v1 de NFC-e.

**O schema (V034 + V035, mais 4 migrations editadas).** As colunas fiscais foram para as
**migrations donas** de cada tabela, seguindo a convenção do §7 do `db/migration/README.md` (banco
ainda em construção): `empresa`→V014, `cliente`→V016, `produto`→V017, `tipo_carteira`→V025. A única
exceção é `produto.id_perfil_fiscal`, que referencia uma tabela criada só em V035 e entra lá por
`ALTER TABLE`, com o motivo comentado nos dois arquivos.

**V034** traz a referência nacional (global, sem `id_tenant`, sem RLS — mesma exceção de
`cfg_produto_ncm`): autorizador por UF, CFOP, CEST, CST/CSOSN, CST e classificação do IBS/CBS,
IBPT. CST e CSOSN nascem semeados **com as flags de quais campos cada código exige e proíbe** — é o
que deixa o motor validar antes de assinar, porque campo a mais rejeita tanto quanto campo a menos.
Os endpoints de NF-e 55 do Paraná nascem **nulos de propósito**: não foram confirmados em fonte
oficial, e a emissão deve falhar explicitando isso em vez de chutar um domínio.

**V035** traz as tabelas de tenant, todas com RLS FORCE e guarda-corpo de P8. Três invariantes que
foram para o banco, não só para o serviço:

- **`documento_fiscal`, `_item`, `_evento` e `fiscal_certificado_uso` não têm `GRANT DELETE`** —
  documento fiscal nunca é apagado, nem rascunho que falhou, que é trilha de suporte. ⚠️ O caso
  precisa entrar em `PrivilegiosNinerAppTest`: `TestcontainersConfiguration` conecta a aplicação
  como superusuário e `GRANT`/`REVOKE` fica invisível para o resto da suíte.
- **Trigger `documento_fiscal_imutavel_tg`:** depois de preenchidos, `chave_acesso`,
  `numero`/`serie`, `protocolo` e o ponteiro do XML não mudam mais. A situação continua mudando
  (AUTORIZADO → CANCELADO); o que é pedra é a identidade da nota perante a SEFAZ.
- **`documento_fiscal_transporte` e `_intermediador` não foram criadas** — tabela vazia não adianta
  nada, já que não há dado para migrar depois. O que o v1 já grava são os campos do próprio
  documento (`finNFe`, `tpNF`, `indPres`, `indFinal`, `idDest`), porque o caro é remontar o XML.

**Verificação:** banco recriado do zero, **35/35 migrations aplicam limpo**, RLS FORCE nas 12
tabelas novas, grants exatamente como esperado, seeds carregados. **509/509 testes verdes** — as
4 migrations editadas não quebraram nada.

**Duas perguntas ficaram sem resposta confiável e viraram item obrigatório da F0**, antes de
qualquer código de emissão: **(1)** se IBS/CBS **compõem o `vNF`** em 2026 — muda o total impresso
no cupom, o valor cobrado do cliente e a conferência de caixa; **(2)** como fica a **devolução de
NFC-e sem consumidor identificado**, que é o caso mais frequente do balcão (venda em dinheiro, sem
CPF) e não tem destinatário para a nota de entrada. Uma terceira, menor: como a chave de acesso (44
dígitos numéricos) acomoda emitente com **CNPJ alfanumérico**.

**Próximo passo combinado:** §17.1 do estudo tem o roteiro de codificação em blocos B0–B9, com o
que depende de certificado e o que não depende.

### 2026-08-15 — As 3 pendências que a baixa de conta a pagar tinha aberto

A mudança de 08-14 (baixa de Contas a Pagar passou a gravar `caixa_detalhe`/
`conta_corrente_movimento`) deixou três telas que **leem** esse dinheiro sem acompanhar. As
próprias specs registravam as três como pendência conhecida — fechadas agora.

**1. Contas a Pagar oferece a abertura de caixa.** Pagar em dinheiro exige caixa aberto; sem ele
a tela só devolvia o 400 e o operador tinha que sair, abrir o caixa noutra tela e refazer a baixa.
Passou a abrir o mesmo `AberturaCaixaModal` do PDV/Recebimento — **mas com gatilho diferente, de
propósito**: aparece quando "Caixa da loja" é escolhido, não na entrada da tela, porque pagar pela
conta corrente (ou só editar a conta) não precisa de caixa nenhum. Por isso o `aoVoltar` daqui
**limpa a origem e devolve ao formulário** em vez de navegar pra fora — o componente não decide
isso, quem passa o `aoVoltar` decide (docstring do `AberturaCaixaModal` atualizada com os três
usos).

**2. O drill-down do Fechamento de Caixa nomeia a saída.** O débito da baixa sempre entrou no
valor esperado da conferência, mas o drill-down mostrava `origem = "—"`: nem o SELECT de
`listarLancamentosDaCarteira` lia `cd.id_conta_pagar`, nem `CaixaService.origem` a conhecia — quem
conferisse uma divergência via uma saída sem explicação nenhuma. Agora sai **"Conta a pagar nº N"**.

**3. O extrato manual de conta corrente recusa o movimento automático.** Aquela tela é editável
justamente por ser digitação sujeita a erro de captura — mas o débito gerado pela baixa não é
digitação. Editá-lo ou excluí-lo descasava o extrato da conta a pagar **em silêncio**: a baixa
continuava em `contas_pagar` e o dinheiro sumia (ou mudava de valor) no banco; e como
`id_conta_pagar` foi criado **sem FK de propósito**, o banco também não impedia nada. Agora a
resposta expõe `idContaPagar`, a grid troca os ícones de editar/excluir por um badge **"Baixa
automática"**, e `atualizar()`/`excluir()` recusam com **409** apontando a tela certa
(`exigirLancamentoManual`, mesmo espírito de `exigirCaixaAbertoParaDesfazer`).

> **Decisão registrada:** a spec dizia "bloquear **ou** avisar". Escolhido **bloquear**, seguindo o
> precedente do guard de caixa fechado — avisar deixaria o descasamento acontecer para quem
> clicasse assim mesmo. O guard é **estreito**: `ContaPagarService` apaga e regrava o movimento por
> SQL direto, sem passar por este serviço, então a própria baixa não é afetada; e lançamento
> digitado na tela continua editável e excluível.

**3 testes novos — 509/509 verdes**, `tsc -b` limpo. `AjudaDaTela` (R22) atualizada nas duas telas
afetadas. ⚠️ **A UX do item 1 não foi conferida no navegador** — testes e type-check cobrem os
itens 2 e 3; o popup abrir no momento certo e o "Voltar" limpar a origem só se confirma clicando.

### 2026-08-15 — Leitura integral das 40 specs de tela: 1 contradição e 3 ponteiros mortos

Varredura completa de `docs/telas/` (as 37 que ainda não tinham sido lidas na íntegra). O corpo
das specs continua sólido — o que apareceu foi pouco e específico:

- **Contradição real entre duas specs:** `pdv.md` dizia que o PDV resolve a empresa com
  `SELECT id_empresa … LIMIT 1`, enquanto `login-empresa.md` registra o retrofit para o claim
  `eid` e é explícita em *"nunca reintroduzir"* aquela busca. O código confirma o claim
  (`PdvVendaService.java:94`) — a spec do PDV descrevia o comportamento anterior a 2026-07-28 como
  se fosse o atual, que é o tipo de frase que faz alguém "consertar" na direção errada.
- **Três ponteiros de arquivo inexistentes:** `configuracao-geral.md` citava `Layout.tsx`/
  `NAV_ADMIN` (a árvore virou dado em `menu.ts` em 08-01); `recebimento-crediario.md` e
  `comprovante-recebimento-crediario.md` apontavam Estorno e Reimpressão para
  `web/src/pages/financeiro/`, quando o diretório é `web/src/pages/recebimentocrediario/`.

Grep dos termos trocados em `docs/` inteiro depois da correção: nenhuma outra ocorrência.

**Também registradas, sem correção nesta rodada** (as próprias specs as declaram): o
`CancelamentoDevolucaoCrudTest` não existe — a feature foi verificada só manualmente, violação
declarada de P5 —, e a `AjudaDaTela` da Configuração de Etiqueta ainda diz que a Emissão está em
"Implementações Futuras".

### 2026-08-15 — Removido o material de treinamento de leitura de NF-e

`docs/TREINAMENTO_IA_ENTRADA.md` (2.318 linhas, 307 KB) catalogava **~30 padrões de extração de
cor/tamanho por fornecedor** (A a AE), a partir de 131 XMLs reais de 42 fornecedores. Removido
porque **nunca foi implementado**: `EntradaXmlService` usa duas heurísticas genéricas
(`adivinharCor` casa contra as cores já cadastradas do tenant; `adivinharTamanho`, contra os
tamanhos ou um token numérico de 1–3 dígitos), sem roteamento por emitente nem tabela de padrões.
Nenhum código, spec, teste ou memória o referenciava, e os XMLs de origem não existem mais na
máquina. Preservado no histórico: `git show c2a0c80:docs/TREINAMENTO_IA_ENTRADA.md`.

> Se um dia a leitura de cor/tamanho do XML for melhorada por fornecedor, esse documento é o
> insumo — e recuperá-lo é o primeiro passo, não refazer a análise.

### 2026-08-15 — "Próximos passos" alinhado ao código + teste de privilégios de `niner_app`

Rescaldo da auditoria de 08-14, que varreu spec, 40 telas e 86 memórias mas **não** a seção
"Próximos passos sugeridos" deste próprio arquivo — que acumulava exatamente o erro catalogado
lá (afirmar que algo não existe quando já existe). Corrigido:

- **Galeria de fotos** deixou de ser *"nenhuma linha de Java escrita"* — implementada em 07-24
  (`ProdutoImagemController/Service`, `comum/armazenamento`, `GaleriaImagensProduto.tsx`).
- **Variação/SKU** deixou de ser *"sem domínio nem tela"*: o `ProdutoBarraService` existe desde
  08-05 e o `sku` já sai de `gerar_ean13_interno()` — falta só a tela. O aviso *"⚠️ Evirson:
  deve chamar a função"* saiu (já chama; a regra vive no `CLAUDE.md`).
- **`POST /api/v1/estoque/movimentacoes`** saiu da lista: **nunca existiu** e não deve existir —
  o ledger é alimentado pelas rotinas de negócio (PDV, Transferência, Balanço, Devolução,
  Entrada), não por um endpoint genérico.
- **Marketplaces viraram o item 1.** A lista omitia justamente o que o "Estado atual" no topo
  deste arquivo aponta como a lacuna central do produto.
- Numeração corrigida (pulava o 4: ia 1, 2, 3, 5, 6).
- **"Como subir o ambiente"** era uma segunda cópia, divergente e de outro SO (Colima, `lsof`) —
  removida em favor do `CLAUDE.md` §Build / run, fonte única.

**Teste novo — `PrivilegiosNinerAppTest` (6 testes).** Fecha o buraco de verificação registrado
em 08-12: `TestcontainersConfiguration` cria o container sem `withUsername`, então o
`@ServiceConnection` liga o datasource da aplicação ao **superusuário** do Testcontainers — e
todo bug de `GRANT`/`REVOKE`/RLS fica invisível para os outros testes (foi assim que o
Cancelamento de Entrada passou verde sem o `GRANT UPDATE` de coluna de que precisava). O teste
conecta cru como `niner_app` (mesmo padrão do `RlsIsolamentoTest`) e afirma os invariantes:
sem `BYPASSRLS`/`SUPERUSER`; `produto_movimento_mestre` imutável **exceto** as 4 colunas de
cancelamento (o grant de coluna de V024); `cfg_ean_gerador` inacessível mas
`gerar_ean13_interno()` executável (`SECURITY DEFINER`); NCM só leitura; trilha de impersonação
encerrável mas não apagável. Não semeia dado — privilégio no Postgres é checado antes das linhas,
então `DELETE` em tabela vazia e `UPDATE ... WHERE false` bastam. **Não substitui** separar as
credenciais do datasource, que continua na lista de próximos passos (item 6) por risco de quebrar
a suíte.

### 2026-08-14 — Reabertura de Caixa + os 4 achados da auditoria, corrigidos

Os quatro achados que a auditoria registrou "para decisão do dono do produto" voltaram
decididos, e a resposta de um deles obrigou a construir uma feature nova.

**Reabertura de Caixa (feature nova).** O pedido foi *"estorno de crediário, com caixa fechado,
tem que mandar reabrir o caixa"* — mas **a reabertura não existia**: `CaixaService` só tinha
`fechar()`, sem volta. Mandar "reabra o caixa" sem ter reabertura seria um beco sem saída, então
ela veio junto: `POST /api/v1/caixa/fechamento/{id}/reabrir`, **ADMIN-only e com motivo
obrigatório** (mesmo par do Cancelamento de Venda — reabrir invalida uma conferência que o
operador assinou, é decisão de gestor). Limpa o estado de fechamento, **apaga as linhas de
`caixa_fechamento_conferencia`** (foram calculadas sobre um estado que vai mudar) e **acrescenta**
— nunca sobrescreve — o rastro em `caixa_mestre.observacoes`:
`REABERTO EM dd/mm/aaaa hh:mm POR USUARIO <id>: <MOTIVO>`. Coluna existente em vez de schema
novo, de propósito. Recusa (409) se o mesmo operador já tem outro caixa aberto, senão existiriam
dois caixas abertos e o PDV não saberia em qual lançar. Na tela, o botão só aparece para ADMIN e
só em caixa fechado.

**O guard que motivou tudo.** `CaixaService.exigirCaixaAbertoParaDesfazer(VinculoCaixa, id)` —
enum com `LOTE_RECEBIMENTO` e `CONTA_PAGAR`, cada um com seu SQL em constante (nunca do cliente;
`id_tenant` escrito no texto da query, P8). Responde 409 com uma mensagem que **ensina a saída**,
não só nega. Ligado no estorno de crediário e nos três caminhos de Contas a Pagar que apagam
movimento (baixa, troca de origem, reabertura da conta). *O pedido do usuário era só sobre o
estorno; o mesmo DELETE incondicional existia em Contas a Pagar, e corrigir só um lado deixaria o
buraco aberto no outro — a extensão do escopo foi comunicada.*

**Bug: exclusão de conta a pagar deixava dinheiro órfão.** `excluir()` apagava só a linha de
`contas_pagar`; o `caixa_detalhe`/`conta_corrente_movimento` da baixa ficavam para sempre — **o
dinheiro seguia saindo do caixa e do banco sem nenhuma conta que o justificasse**. Como essas
colunas de vínculo **não têm FK** (escolha deliberada de V025/V028), o banco nunca reclamou, e o
DELETE dos movimentos só existia no caminho de POST/PUT. Corrigido; a assinatura virou
`excluir(Jwt, long)`. Lição registrada: coluna de vínculo sem FK não avisa quando o `excluir()`
esquece o outro lado — e um teste que só checa 200 no DELETE passa com o bug presente, então o
teste tem que conferir o banco.

**Bug: "Emitir Etiquetas desta Nota" passava parâmetros ignorados.** A entrada montava
`?idFornecedor=…&notaFiscal=…` desde 08-11, mas a tela de Emissão nunca leu `useSearchParams` —
o operador caía numa lista vazia e redigitava tudo. Agora a entrada manda também o
`nomeFornecedor` (o endpoint de fornecedores busca por texto, não por id — sem o nome seria
preciso uma chamada extra só pra mostrar de quem é a nota) e a tela **abre o popup sozinha no
modo Por Entradas**, com os filtros preenchidos: basta clicar em Localizar.

**Bug: PDF do vale-mercadoria fora de calibragem.** Ficou em 5pt/2,6mm enquanto papeleta e
crediário foram para 8pt/3,6mm — e o docstring afirmava "mesma largura/fonte" enquanto os números
diziam outra coisa. Só o jsPDF divergia, por isso a conferência na impressora não pegou: **PDF e
impressão térmica são caminhos separados e precisam ser olhados separadamente.**

**Datas da documentação corrigidas.** O dono do produto confirmou que a data correta é a do
relógio: **2026-08-14**. A documentação vinha datada de até **08-24** — dez dias no futuro, porque
cada sessão continuava a numeração da doc anterior em vez de olhar o relógio. O remapeamento usou
o `git log --date=short` como autoridade e foi feito **por feature** (casando o texto com a
mensagem do commit), não por subtração de dias: a deriva não era constante, e 16 dias
documentados tinham que caber em 6 dias reais de trabalho. Datas até 08-08 já estavam certas.
Datas que são **dado de exemplo** (vencimento de parcela, expiração de trial) ficaram intactas.

8 testes novos, **500/500 verdes**, `tsc -b` limpo.

**Rescaldo — dois testes frágeis por relógio, corrigidos.** Rodando a suíte às 23:58, três testes
que estavam verdes às 18h falharam sozinhos; nenhum era regressão. (a) `HorarioAcessoTest` grava a
janela em `time` puro e `(now() + 60min)::time` embrulhava pra `00:58`, violando
`hora_fim > hora_inicio` — mas a janela é **por dia da semana**, então ela realmente não pode
cruzar a meia-noite: o teste é que pedia o impossível, e agora é **pulado** com `assumeFalse` e
mensagem, nunca aprovado em falso. (b) `RecebimentoCrediarioCrudTest` gravava
`data_vencimento = now() - N days` enquanto o serviço conta atraso em domínio de data
(`CURRENT_DATE - data_vencimento::date`) — virando o dia entre o INSERT e o cálculo, o atraso ia
de N para N+1, os juros mudavam e o valor pago (fixo no corpo) deixava de bater. Corrigido de
verdade ancorando o vencimento ao **meio-dia**, sem pular nada. Fixture de teste não deve usar
`now()` quando a produção compara por data.

### 2026-08-14 — Auditoria completa da documentação (spec + 40 telas + 86 memórias)

Revisão de **toda** a documentação do projeto contra o código, a pedido do dono do produto. Três
frentes paralelas: `spec-driven-erp-varejo.md` + `CLAUDE.md` + `docs/infra/`; os 40 arquivos de
`docs/telas/`; e as 86 memórias de sessão.

**O achado principal é um padrão, não uma lista de erros avulsos.** Quatro varreduras exaustivas
sobre `docs/telas/` **não acharam nada**: todos os endpoints, os 70 arquivos `.ts/.tsx`, as 66
classes Java e todos os identificadores de tabela/coluna citados **existem**. O apodrecimento
está concentrado em **duas seções**: **"Non-goals"** e **"Questões abertas"**. Quem implementa
uma feature nunca volta pra riscar o item na spec da tela vizinha — então ~24 afirmações de
"não existe / é pendente" descreviam coisas já construídas. As mesmas frases erradas apareciam
em 2–3 arquivos ao mesmo tempo:

| Afirmação errada | Realidade | Onde aparecia |
|---|---|---|
| "não existe fechamento de caixa" | existe desde 2026-07-30 | 3 specs + 2 memórias |
| "nenhum service grava `tipo_movimento='COMPRA'`" | existe desde 2026-08-11 | 3 specs + 2 memórias |
| "o signup semeia só 3 contas" | semeia 76 desde 2026-08-14 | 3 specs + 2 memórias |
| "não existe tela de baixa de contas a pagar" | existe desde 2026-08-12 | 2 specs + 1 memória |
| "não existe reimpressão de comprovante de venda" | existe desde 2026-08-06 | 2 specs + 1 memória |

Essa é a categoria mais cara de doc errada: quem lê "isso não existe" ou **reconstrói algo
pronto**, ou **orça como trabalho novo**, ou **recusa o pedido do usuário**. Virou convenção
(memória `feedback_non_goals_apodrecem`): ao terminar qualquer feature, procurar no repo por
afirmações de inexistência sobre ela **antes** de dar a documentação por pronta; e ao ler uma
spec, tratar "Non-goals"/"Questões abertas" como as seções **menos confiáveis** do arquivo.
Ao corrigir, o non-goal **não é apagado** — vira nota de superação (`~~original~~ — superado em
<data>: <prova em arquivo:linha>`), porque às vezes a decisão original continua certa mesmo com
a feature existindo (foi o caso da DRE: carregar o grupo em código segue valendo mesmo depois de
o signup semear o plano inteiro).

**Correções na spec principal** (regra de negócio, não cosmética): `cfg_variante_linha/coluna` →
`cfg_cor`/`cfg_tamanho`/`cfg_grade`; as flags reais de `cfg_geral`; **Q5 encerrada** (o financeiro
do lojista está inteiro no v1 desde V028 — a spec ainda dizia "não entra no v1"); sequência de
migrations completada até V033 (a spec parava em V026 e o roadmap citava uma faixa "V001–V091"
que nunca existiu); paginação **por número de página**, não cursor; **saldo negativo é permitido
de propósito** (R2 revisto em 2026-07-29 — a spec ainda pedia constraint pra impedir);
`gerar_ean13_interno()` deixou de ser "nada chama"; `color-mix()` documentado como **proibido**
(quebra o PDF por captura); `ajuda_tela` marcada como **não implementada** (o texto vive no
front); §3.3 e o `docker-compose` do §3.5 marcados como desenho, com ponteiro pra fonte de
verdade real.

**Dois bugs de código achados pela auditoria e corrigidos:** as rotas `/dre` e `/fluxo-caixa`
estavam declaradas **duas vezes** (a tela pronta e um placeholder "Em construção" concorrendo
pela mesma rota, inclusive duplicando o item no menu) — placeholders removidos de `App.tsx` e
`menu.ts`; e dois textos de `AjudaDaTela` (R22) diziam ao usuário que telas existentes ainda não
tinham sido construídas.

**Achados de código registrados mas NÃO corrigidos** (precisam de decisão do dono do produto):
`ContaPagarService.excluir()` apaga só `contas_pagar` e deixa o `caixa_detalhe`/
`conta_corrente_movimento` da baixa lançados para sempre; o estorno de crediário apaga
`caixa_detalhe` sem checar caixa fechado; "Emitir Etiquetas desta Nota" passa parâmetros que a
tela de destino ignora; o PDF do vale-mercadoria não recebeu a calibragem térmica de 2026-08-14.

492/492 testes verdes, `tsc -b` limpo.

### 2026-08-14 — Papeleta de venda legível na bobina real (42 colunas, item em 2 linhas)

Primeira impressão da papeleta numa impressora térmica de verdade — e ela saiu **ilegível**. O
dono do produto mandou a foto do cupom ao lado do PDF (que estava bom) e a comparação foi o que
permitiu diagnosticar: o problema não era a fonte, eram **dois erros somados de largura**.

1. **64 colunas não cabem.** O papel tem 80mm, mas a **área de impressão é ~72mm**; descontada a
   margem sobram ~68mm, o que dá 1,06mm por caractere — fonte de ~6px. A aposta original (usar
   `Lucida Console`, desenhada pra ficar legível em corpo pequeno) não vence essa conta.
2. **O CSS declarava `width: 80mm`.** Prometer ao navegador uma faixa maior do que a impressora
   marca faz o **driver encolher tudo** pra caber — a fonte já pequena diminuía mais ~10%.

**Solução, pedida pelo dono do produto:** quebrar o item em **duas linhas** em vez de espremer a
largura. A 1ª traz código de barras + o que couber do nome (28 caracteres); a 2ª, `qtd ×
unitário` recuado e o total à direita. Com **42 colunas** — o padrão clássico de térmica 80mm e o
mesmo do comprovante de crediário/vale — cada caractere ganha ~1,6mm. O vale-mercadoria
acompanhou de graça (compartilha as funções de montagem), e o PDF subiu de ~5pt pra 8pt.

**Depois vieram três rodadas de refinamento, cada uma a partir de uma foto do cupom impresso:**
- *"as letras estão falhadas"* → **`font-weight: bold`** + `print-color-adjust: exact`. Impressão
  térmica é 1 bit: cada ponto sai preto ou não sai. Haste fina cai em meio-tom, o navegador
  dissolve em cinza e a cabeça marca só parte dos pontos — é exatamente o "picotado" da foto.
- *"o texto está deslocado pra direita e cortando"* → **erro meu na rodada anterior**: eu tinha
  posto `left: 4mm` supondo que a origem do CSS fosse a borda física do papel. Não é: a origem
  **já é o começo da área imprimível**, então os 4mm foram somados e o fim das linhas caiu fora
  ("TOTAL" saiu "TOTAI"). Voltou pra `left: 0`.
- *"sobra espaço à direita"* → alargar a caixa sozinho **não resolveria**: o texto é que não
  preenchia (a Consolas, adotada por ter haste mais grossa, é ~0,55em contra 0,6em da Lucida).
  O que usa o espaço é subir a fonte: **75mm de caixa + 11,5px**, dando 42 × 0,55 × 11,5 ≈ 70mm
  dentro dos 73mm úteis. ⚠️ O tamanho fica **calibrado pra Consolas** — nas fontes de reserva o
  mesmo valor voltaria a cortar; está avisado no CSS e na spec.

Também no pedido: o rótulo `Id. Venda..:` virou **`Nº Venda...:`** (conferido que continua com 12
caracteres, como os outros quatro rótulos do cabeçalho — é isso que mantém a coluna de valores
alinhada).

Aprovado pelo dono do produto na impressão real. `tsc -b` limpo; a tabela de layout do CSS de
impressão, com o porquê de cada valor, ficou em `docs/telas/papeleta-venda.md` como referência
para qualquer outro documento térmico do produto.

**Na sequência, o Comprovante de Pagamento de Crediário recebeu a mesma calibragem** — e serviu
de prova de que a receita é reutilizável: ele tinha exatamente os mesmos três defeitos
(`width: 80mm` maior que a área imprimível, fonte 9px sem negrito), pelo mesmo motivo — a regra
original foi escrita no papel, nunca testada numa bobina. `width: 75mm`, Consolas 11,5px em
negrito e `print-color-adjust: exact`; a pré-visualização na tela passou a usar a mesma família,
já que existe pra mostrar como sai no papel. **Não precisou mexer no layout**: ele já era 42
colunas — essa é a diferença para a papeleta, que era 64 e exigiu a reorganização em duas linhas.
Aprovado na impressão real também.

O **vale-mercadoria** foi conferido impresso na mesma data e passou **sem nenhum ajuste próprio**:
ele nunca foi tocado nesta rodada — herdou tudo por compartilhar as funções de montagem com a
papeleta. Isso valida na prática a decisão de 2026-08-07 de padronizar os dois comprovantes.
**Guia de Transferência e Fechamento de Caixa ficaram para depois** (decisão do dono do produto);
os dois são A4, então não têm o problema de bobina — o que eventualmente precisarem é outra
conversa.

### 2026-08-14 (fechamento) — as 4 pendências abertas do dia, resolvidas

**1) Signup passa a aplicar o plano de contas padrão completo.** Era a questão aberta da DRE: um
tenant novo via receita e CMV, mas **nenhuma despesa**, porque o `SignupService` semeava só 3
contas (a árvore mínima da conta de compra) e o plano de 76 contas era um script manual que quase
ninguém rodava. Agora existe **`cfg_plano_contas_padrao`** (V016) — tabela **modelo global**, sem
`id_tenant` e sem RLS, mesma exceção documentada de `cfg_produto_ncm`/`cfg_ean_gerador` — e o
signup copia o plano inteiro numa linha de SQL. Uma atualização futura do padrão vira migration,
não script solto.

**O custo escondido disso:** 62 testes quebraram de uma vez. Todos por **409 (código já existe)**
— o suite inteiro assumia plano de contas quase vazio e criava contas com códigos que agora vêm
prontos. A correção **não** foi tolerar conflito em toda parte: o próprio seed padrão **reserva ao
tenant o grupo 9 inteiro e as famílias `.90–.99` de cada grupo**, exatamente para customização, e
foi para lá que as fixtures dos testes foram movidas (`2.00.000` → `2.90.000` etc.), com o único
nível-1 livre (`9.00.000`) reservado ao teste que valida "nível 1 sem pai". Três testes ainda
assumiam plano vazio de outra forma e foram corrigidos no mérito: busca por "ALUGUEL" agora casa
com a conta padrão homônima; `itens[0]` de uma listagem não é mais a conta recém-criada (76 contas
antes dela); e um "filho" que era nível 2 (irmão, não filho) virou nível 3 de verdade.
**491/491 verdes** ao fim.

**2) PDF conferido de verdade.** Sem `pdftoppm` na máquina, servi o arquivo pelo próprio dev
server (`web/public`) e abri no Chrome, que renderiza PDF nativamente. O do Fluxo de Caixa saiu
correto: A4 retrato, tema claro, subtítulo com visão e período, "Página 1 de 1", bloco de filtros
aplicados e a grade com espaçamento legível. Arquivos de teste removidos de `web/public` depois.

**3) Contraste do tema claro medido, e um token corrigido.** A passada visual dependia de um
navegador sem Dark Reader, que eu não tenho — então medi o que dá para medir objetivamente:
contraste WCAG de 11 pares de token do tema claro. Dez passam AA (4.5:1); **`--sucesso` reprovava
com 3.45:1** e é usado em **texto de corpo** ("confere com o saldo calculado", variações positivas
da DRE). Escurecido de `#2f9e44` para `#217a33` → **5.39:1**. O verde do tema escuro já dava
7.27:1 e não mudou.

**4) `npm run build` volta a funcionar no host.** Era o binding nativo
`@rolldown/binding-win32-x64-msvc` ausente (bug conhecido do npm com dependências opcionais).
`node_modules` + `package-lock.json` apagados e reinstalados: `✓ built in 1.53s`.

### 2026-08-14 — Fluxo de Caixa (realizado + projeção) e a baixa que passou a mover dinheiro de verdade

Pedido do dono do produto logo depois da DRE: "preciso montar o relatório de fluxo de caixa, o que
você sugere?". A investigação achou um problema estrutural que **decidia o formato do relatório**,
e por isso a feature saiu em duas partes. Spec: `docs/telas/fluxo-caixa.md`.

**O problema:** um fluxo de caixa promete `saldo inicial + entradas − saídas = saldo real`. No
Nainer essa conta não fechava, porque **dar baixa numa conta a pagar não gravava movimento nenhum**
— só preenchia `contas_pagar.data_pagamento`. Venda e recebimento de crediário passavam por
`caixa_detalhe`; movimento bancário manual, por `conta_corrente_movimento`; **a saída de dinheiro,
por lugar nenhum**. Montar o DFC pelas tabelas de dinheiro daria um relatório quase sem saídas;
montá-lo por lançamento daria um que não bate com o extrato — e fluxo de caixa que não bate com o
extrato o lojista abandona na segunda semana.

**Parte 1 — a baixa passou a mover dinheiro.** Ao registrar o pagamento, o operador informa **de
onde saiu** (Caixa da loja ou Conta corrente) e o sistema grava a saída na mesma transação:
`caixa_detalhe` (`DEBITO_CAIXA`, no caixa **aberto** do usuário — mesma convenção do PDV) ou
`conta_corrente_movimento` (débito). As duas tabelas ganharam `id_conta_pagar` (nullable, **sem
FK** de propósito: excluir a conta não pode travar por causa de dinheiro que já saiu), e é esse
vínculo que permite **apagar o movimento quando a baixa é desfeita** — sem ele sobraria dinheiro
fantasma saindo do caixa. Estratégia "apaga e regrava", que cobre num só caminho baixa desfeita,
troca de origem e correção de valor.

**Exceção que dois testes existentes revelaram:** exigir a origem em *toda* edição travaria para
sempre qualquer conta paga **antes** desta mudança (mudar uma observação devolveria 400 sem saída,
porque ela nunca terá movimento vinculado). A regra final distingue **baixa nova** (exige origem)
de **conta já paga** (edição não mexe no movimento).

**Parte 2 — a tela, com duas abas** (escolha do dono do produto, para não criar duas telas
parecidas):
- **Realizado** — método direto por atividade (operacional/investimento/financiamento), lendo
  **só movimento de dinheiro**, classificado por `grupo_dfc` com fallback por `tipo_operacao` para
  o que o PDV grava sem plano de contas. **Conciliação no rodapé fixo**: saldo calculado × saldo
  real de hoje, com a diferença exposta quando não bate — é o que dá confiança no número.
- **Projeção** — parte do saldo de hoje e soma contas a receber/pagar **em aberto** por
  vencimento, agrupadas por dia/semana/mês, com saldo acumulado e **alerta da primeira data em que
  fica negativo**. Vencidos entram no primeiro balde marcados como "inclui vencidos" — jogá-los na
  data original faria o saldo mentir sobre o presente. Non-goal explícito: **não inventa venda
  futura**, só compromisso já registrado.

**Achado que mudou o desenho (pego pelo teste):** `saldoFinal` vinha **−150,00** onde o dinheiro
real era **850,00**. Causa: `caixa_mestre.saldo_inicial` é dinheiro que entra na gaveta **sem
gerar linha em `caixa_detalhe`** — o saldo acumulado já o considerava, mas faltava a contrapartida
dentro do período. Agora a abertura de caixa entra como linha de entrada operacional, e a
conciliação fecha em zero. Sem isso, a promessa central do relatório quebrava justamente no caso
mais comum: o primeiro dia de uso.

**Efeito colateral bom:** a **questão aberta nº 1 da DRE** (dupla contagem no regime de caixa)
ficou **resolvida na origem** — o pagamento passou a ter um lugar único e canônico.

**Verificação:** **491/491 testes de backend verdes** (486 + 5 do fluxo: baixa virando saída,
período sem movimento, alerta de saldo negativo, vencido no primeiro balde, isolamento entre
tenants; mais 3 em `ContaPagarCrudTest` para o ciclo baixa → movimento → desfazer). `tsc -b`
exit 0. Testado ao vivo nas duas abas com dado real: a venda de R$ 161,82 aparece como
"Recebimento de venda" em atividades operacionais e o rodapé confirma "confere com o saldo
calculado"; a projeção parte de R$ 161,82, encontra uma conta a pagar de R$ 50,00 e projeta
R$ 111,82. **Pendente:** olhar o PDF renderizado desta tela e testar com massa maior.

### 2026-08-14 — ⚠️ `tsc --noEmit` no `web/` não checava nada (e o repositório acumulava 19 erros)

Descoberto no meio da Parte 1: escrevi um `<select>` usando uma variável **inexistente** e o
`npx tsc --noEmit` passou limpo. O `web/tsconfig.json` é do tipo *solution* (`"files": []` +
`references`); com `--noEmit` o TypeScript ignora as referências e checa **zero arquivos**,
saindo sempre com sucesso. O comando que de fato checa é **`tsc -b`** (o que o `npm run build`
usa).

Consequência: entradas anteriores deste PROGRESSO que citam "`tsc --noEmit` limpo" descreviam uma
verificação vazia — e por baixo dela o repositório já acumulava **19 erros reais em 16 arquivos**,
nenhum deles introduzido nesta sessão (confirmado cruzando com `git status`):
- 12 × `TS6133` (variável/import declarado e nunca usado — quase todos um `navigate` sobrando)
- `TS2305` em `ImportacaoTabelaPage` (importava `IconeComponente` de `components/Icones`; o tipo
  vive em `lib/menu.ts`)
- `TS2345` em `PlanoContasModal` (resíduo da revisão do plano de contas de 08-13: faltavam 4
  campos que viraram parte do contrato)
- 4 × `TS2345` em `RelatorioEstoque`: a restrição genérica `T extends Record<string, unknown> &
  { qtdPorEmpresa: number[] }` **nunca aceitaria** `LinhaSintetica`/`LinhaAnalitica`, porque
  **interface não ganha assinatura de índice implícita** em TypeScript. Restrição reduzida ao que
  a função realmente usa, com cast no acesso dinâmico.

Todos corrigidos a pedido do dono do produto — **`tsc -b` agora sai com exit 0**. Convenção nova:
**usar `tsc -b`, nunca `--noEmit`**, neste repositório. Descoberto também que `npm run build`
falha **no host** (binding nativo `@rolldown/binding-win32-x64-msvc` ausente, bug conhecido do npm
com dependências opcionais) — dentro do container o bundle compila normalmente.

### 2026-08-14 — Relatório de DRE (dois regimes) — primeira demonstração de resultado do produto

Pedido do dono do produto: "preciso montar meu DRE, com regime de competência e de caixa — tem
algo mais que precisamos fazer, como o mercado faz?". A resposta útil não estava nos dois regimes
(que ele já tinha desenhado certo), e sim em **de onde vêm os números**: cinco linhas essenciais
da DRE existem no plano de contas mas **nada escreve nelas** — CMV, taxas de cartão, comissões,
descontos e devoluções. Spec completa em `docs/telas/relatorio-dre.md` (escrita e aprovada antes
do código, fluxo spec-driven).

**Decisão central — relatório híbrido.** As cinco linhas são **derivadas do movimento real** na
consulta (venda/ledger/carteira/devolução) e o resto vem somado de `contas_pagar` por
`id_plano_contas` → `grupo_dre` → `sinal`. A alternativa (gerar lançamento contábil a cada venda)
mexeria em PDV/cancelamento/devolução e criaria risco de o lançado divergir do movimento.

**Três coisas que o plano de contas já tinha certas** e que evitam os erros clássicos de DRE de
pequena empresa: `3.03.001 Compra de Mercadoria` com `inclui_dre = false` (compra é estoque, não
despesa — o custo entra como CMV quando a mercadoria sai vendida, com o custo real gravado na
linha da venda); grupo 7 (Movimentos Neutros e Sócios) mantendo distribuição de lucro fora do
resultado; e a separação `CUSTO_VARIAVEL` × `DESPESA_FIXA`, que dá a linha de margem de
contribuição de graça.

**Semântica de data por regime:** competência usa `venda.data_venda` e
`contas_pagar.data_lancamento` (o fato gerador — não o vencimento, que é data de tesouraria);
caixa usa `contas_receber.data_recebimento` e `contas_pagar.data_pagamento`. No caixa, **CMV e
comissão entram proporcionais ao recebido** (recebi 30% da venda → 30% do custo) e juros/multa
recebidos viram resultado financeiro, não receita de venda.

**Escopo v1 escolhido pelo dono do produto:** dois regimes + **AV** (% sobre a receita líquida) e
**AH** (vs período anterior ou mesmo período do ano anterior, sempre com o mesmo número de dias).
Ponto de equilíbrio, visão de 12 meses e drill-down ficaram como Non-goals — a resposta da API já
nasceu compatível com os três. **ADMIN-only** nos três níveis (menu, rota e 403 na API).

**Dois achados durante a implementação, ambos com correção no desenho:**
1. **O `SignupService` semeia só 3 contas** (a árvore mínima da conta de compra); o plano padrão
   de 76 contas é um seed à parte. A 1ª versão perguntava o grupo de DRE ao plano de contas do
   tenant e a DRE de um tenant novo veio **zerada mesmo com vendas** — o teste pegou. Agora as
   linhas derivadas carregam o próprio grupo em código, e o plano só fornece o nome quando a conta
   existe. Consequência que virou questão aberta: **despesas só aparecem se o lojista tiver contas
   de despesa cadastradas**.
2. **Dar baixa em Contas a Pagar não gera lançamento de caixa nem de conta corrente** (verificado
   no código) — somar as três fontes no regime de caixa duplicaria o pagamento. Regra adotada:
   saída sai de `contas_pagar`, e `conta_corrente_movimento` entra **só** para contas de receita,
   que o Contas a Pagar não produz. O aviso está na ajuda da tela.

**Segunda rodada no mesmo dia — padrão de tela de relatório.** A tela nasceu sem os botões que
todo relatório tem; o dono do produto apontou ("é um relatório, então precisa seguir o padrão").
Ganhou a barra `? · Filtros · Gerar PDF · ✕`, a estrutura `.relatorio-corpo-fixo`/
`.relatorio-conteudo`, e `lib/relatorioDreCaptura.ts` (captura visual, tema claro só no clone,
cabeçalho/rodapé nativos por página). Duas divergências conscientes do padrão: **PDF em retrato**
(a DRE tem poucas colunas e muitas linhas; é o formato que o contador espera) e **regime + período
no subtítulo do cabeçalho** de todas as páginas.

**Verificação:** **483/483 testes de backend verdes** (476 + 7 novos cobrindo os critérios da
spec: venda à vista, crediário nos dois regimes, venda cancelada, compra de mercadoria fora da
DRE, despesa lançada × paga, 403 para OPERADOR e o cálculo do período comparado). `tsc --noEmit`
limpo. Testado ao vivo com **venda real** (R$ 161,82, custo R$ 89,90, vendedor 5%): receita
161,82 − CMV 89,90 − comissão 8,09 = **margem 63,83**, e os dois regimes iguais porque a venda foi
à vista e recebida no mesmo dia. PDF gerado e conferido no arquivo: A4 retrato (MediaBox 595×842),
1 página, 286 KB. **Pendente:** olhar o PDF renderizado (não há `pdftoppm` nesta máquina) —
passado ao dono do produto.

**Massa de teste criada no tenant `loja-dev-claudio`** (pela API, fluxo real): 3 categorias, 2
fornecedores, 10 produtos com NCM real e variação/SKU gerada, mais cliente/funcionário/venda do
teste da DRE. Dois tropeços registrados: os NCMs "de cabeça" não existiam na base da Receita, e
`POST /api/v1/produtos` **não cria a variação** — sem `produto_barra` o produto é invisível para
a Entrada de Produtos e para o PDV.

### 2026-08-14 — Seletor de tema Claro/Escuro/Automático no cabeçalho (fecha uma pendência de §3.7)

Pergunta do dono do produto: "temos o tema dark, como criamos um light também?". A investigação
mudou a resposta — **o tema claro já existia e já rodava em produção**; faltava só o interruptor.

**O que já existia:** `styles.css` sempre teve as duas paletas (§3.7 — `:root` é a **clara**, a
media query `prefers-color-scheme: dark` é a escura), mais os overrides `:root[data-theme='light']`
e `[data-theme='dark']`. E a paleta clara não era teórica: desde 2026-08-02 **toda geração de PDF
já renderiza o app inteiro em claro**, forçando `data-theme='light'` no clone do documento antes
do `html2canvas` (6 arquivos `lib/*Captura.ts`). Ou seja, o risco de "tema novo nunca visto" era
zero. O que faltava era alguém escrever o atributo — como ninguém escrevia, o ERP seguia o SO, e
com Windows em escuro só se via o escuro. A spec §3.7 **já mandava** existir o toggle ("o toggle
do usuário vence a preferência do sistema"), então isto fecha uma pendência antiga.

**Decisões (as três perguntadas ao dono do produto):** preferência em `localStorage` por navegador
(sem migration, sem endpoint — e permite caixa claro / escritório escuro na mesma loja); **três
estados**, com `Automático` de padrão (não escreve atributo = comportamento histórico); controle
como ícone no cabeçalho, entre a busca de telas e o "Sair".

**Implementação:** `lib/tema.ts` + `components/SeletorTema.tsx` (novos), `IconeSol`/`IconeLua`/
`IconeMonitor`, CSS do menu, e um **script inline no `<head>` do `index.html`** que aplica o tema
antes da primeira pintura — sem ele, quem escolhesse "Claro" veria um flash escuro a cada F5,
porque o bundle React só roda depois do primeiro paint. Esse trecho é deliberadamente duplicado em
JS puro; as duas cópias precisam andar juntas. O ícone do gatilho reflete o tema **em uso** (com
`Automático`, sol ou lua conforme o SO, com listener de `matchMedia` pra acompanhar a troca ao
vivo). **Nenhuma tela precisou ser tocada**: toda cor do projeto sai de token, inclusive os
gráficos Recharts. Declarado também `color-scheme` nos blocos de tema, pelos controles nativos
(lista do `<select>`, scrollbar, autofill), que não leem os tokens do design system.

**Armadilha de diagnóstico (vale mais que a feature):** testando ao vivo, depois de recarregar a
página o ERP voltava a aparecer escuro com "Claro" selecionado. Cheguei a atribuir ao modo escuro
forçado do Chrome e a escrever um comentário no CSS com esse diagnóstico. Estava **errado**:
inspecionando o DOM, `data-theme` era `light` e `--ground` valia `#f5f4f0` (claro), mas o `body`
pintava `rgb(30,28,20)` — e mesmo `color-scheme: only light` inline não mudava nada. A causa era a
extensão **Dark Reader** instalada naquele Chrome, que injeta `<style class="darkreader--root-vars">`
e reescreve os tokens por cima. Não há CSS do lado da página que resolva. O comentário foi
corrigido para não deixar um diagnóstico falso no código, e o aviso está em
`docs/telas/menu-principal.md`: se um lojista disser que o tema claro "não funciona", checar
extensão de navegador **antes** de procurar bug no CSS.

**Verificação:** `tsc --noEmit` limpo. Testado ao vivo no Chrome: o seletor aparece no cabeçalho, o
menu abre com as 3 opções (a ativa destacada), e a troca para "Claro" reveste o app inteiro na
hora, sem recarregar (captura do Painel claro guardada na conversa). **A passada visual tela a
tela ficou pendente** — o Chrome usado na automação tem Dark Reader ativo, o que impede avaliar
contraste de verdade; precisa ser feita num navegador sem a extensão.

### 2026-08-14 — Entrada de Produtos: botão Fechar no popup de filtros, bug da divisão de duplicatas e o parâmetro que torna a consistência opcional

Sessão de três pedidos encadeados do dono do produto, todos na Entrada de Produtos por Compra —
o segundo é um **bug real de produto** achado testando ao vivo, e o terceiro nasceu da conversa
sobre ele.

**1) Botão "Fechar" no popup de filtros.** O popup obrigatório da listagem
(`EntradaMercadoriaLista.tsx`, convenção de 2026-08-12) só tinha "Localizar" e "＋ Nova entrada":
quem abria a tela por engano não tinha saída sem executar uma das duas ações. Ganhou um terceiro
botão, à esquerda, com `navigate(-1)` — mesma mecânica do `BotaoFecharTela` do cabeçalho e do
popup do Cancelamento de Devolução, que já tinha esse botão desde que nasceu.

**2) Bug: as duplicatas eram divididas pelo total errado.** Reproduzido pelo dono do produto —
1 unidade a R$ 150,00 em 3 duplicatas gerou **0,34 / 0,33 / 0,33** (total R$ 1,00) em vez de
50,00 cada. A conta em si (resto do arredondamento na primeira parcela) estava certa desde
2026-08-11; **o que estava errado era o momento em que ela rodava**. O `useEffect` que divide
`valorTotal` por N parava de recalcular assim que existisse qualquer parcela
(`parcelas.length > 0` na guarda) — e como `valorTotal` é recalculado a cada tecla digitada no
custo do item, o primeiro dígito ("1") já criava as 3 parcelas e travava a divisão; os dígitos
seguintes ("15", "150,00") eram ignorados. Só acontecia na ordem "Nº de Parcelas antes do custo",
que é justamente a ordem natural da tela (o campo fica na aba 1, o custo na aba 2).
Correção: a guarda `parcelas.length > 0` deu lugar a um estado explícito `parcelasEditadas`,
ligado **só** quando o operador digita ou remove um *valor* de parcela — mexer em Nº Duplicata/
Data não congela mais nada, e a divisão passa a acompanhar qualquer mudança de quantidade/custo
até essa edição manual acontecer. Mudar o Nº de Parcelas destrava de novo (é o gesto de "divida
tudo outra vez"); quando só o valor precisa ser refeito e a contagem não mudou, Nº Duplicata/Data
já preenchidos são preservados; duplicatas vindas do XML (`xmlTemDuplicatas`) seguem intocadas.

**3) Parâmetro novo: "Consistir valor das contas a pagar na entrada"
(`cfg_geral.cfg_consiste_valor_contas_pagar`).** A regra "soma das duplicatas = total dos
produtos" existia **fixa** desde 2026-08-11; o dono do produto pediu para torná-la opcional
(adiantamento, parte da nota à vista, nota parcialmente financiada são casos reais em que a
divergência é legítima). Décimo-primeiro campo de Parâmetros do Sistema, seção **Compras**:
- **Default `true`**, ao contrário das outras flags de Compras (que nascem desligadas) — preserva
  o comportamento que a tela já tinha. O fallback do serviço, quando a linha de `cfg_geral` não
  existe, também é `true` (as demais flags leves usam `false`).
- **Base de comparação: o total dos produtos SEM o rateio de frete/IPI/ICMS-ST** — é o número que
  a tela mostra em "Total dos produtos". Comparar contra o total já rateado reprovaria a nota por
  uma diferença que o operador não tem como enxergar.
- **Validada também no servidor** (`EntradaMercadoriaService.registrar`, 400 + Problem Details
  antes de qualquer INSERT — a transação inteira é revertida, não sobra movimento de estoque nem
  conta a pagar). Até aqui a regra só existia no frontend; a API não confiava só no cliente em
  nenhuma outra regra desta feature e não começaria por esta.
- Endpoint leve novo `GET /api/v1/config-geral/consiste-valor-contas-pagar` (qualquer papel,
  mesmo padrão de `/usa-cor-grade`, `/rateia-frete-entrada`, `/plano-contas-compra-mercadoria`) —
  a Entrada de Produtos é operada majoritariamente por `OPERADOR`, que não pode ler o endpoint
  completo (ADMIN-only), a mesma armadilha já documentada no fix de 2026-08-13.

**Banco:** coluna nova dentro de `V023__cfg_geral.sql` (banco em construção, migration editada em
vez de nova). Aplicada no dev com `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` +
`docker compose run --rm flyway ... repair`, sem recriar o banco e sem perder dado; **nenhum
`GRANT` novo foi preciso** — os privilégios de `cfg_geral` para `niner_app` são de tabela inteira,
não por coluna (diferente do caso de `produto_movimento_mestre` em 2026-08-12).

**Verificação:** suíte de backend **476/476 verdes** (474 + 2 novos em `EntradaMercadoriaCrudTest`:
com a flag ligada, duplicata divergente devolve 400 e não grava nem `contas_pagar` nem
`produto_movimento_mestre`; com ela desligada, a mesma entrada é aceita e a conta a pagar nasce
com o valor divergente). `ConfiguracaoGeralTest` ganhou as asserções do default `true`, do
round-trip do PUT e do endpoint leve. `tsc --noEmit` limpo. API rebuildada e testada ao vivo via
API: `GET` da flag, `PUT` desligando, endpoint leve refletindo `false`, `PUT` religando —
deixada no padrão. **O teste ao vivo na tela ficou pendente**: os 3 tenants do banco de dev estão
sem produto cadastrado (efeito da conversão de plano de contas de 08-13), então não dá para
lançar uma entrada sem antes criar massa de teste — oferecido ao dono do produto, ainda não
respondido.

### 2026-08-13 (auditoria 3) — terceira passada de "documente/memorize tudo" achou staleness DENTRO do produto

Pedido "documente/memorize tudo" pela terceira vez na mesma sessão, depois de fechar os 2
filtros de lista. Desta vez a lacuna não estava em `docs/`, estava em código user-facing:
**1)** `AjudaDaTela.tsx` da tela `financeiro.contacorrentemovimento.lista` só descrevia 2 dos 7
filtros reais (Data Inicial/Final, Empresa e Plano de Contas nem apareciam) — staleness
pré-existente, achada só por ter acabado de mexer nessa tela. **2)** o docstring do próprio
`SeletorPlanoContas.tsx` ainda dizia "substitui os `<select>` nos formulários", sem mencionar
que virou também o seletor dos 2 filtros de lista migrados poucos minutos antes, na mesma
sessão. Ambos corrigidos, junto com a seção "Seleção de plano de contas em outras telas" de
`docs/telas/plano-contas.md`, que também só descrevia o caso de formulário. Lição registrada em
`feedback_checklist_documentar_tudo` (memória): comentário/docstring de componente próprio
precisa da mesma reauditoria que uma spec em `docs/` — não é só documentação de repositório que
fica stale, é qualquer texto que descreve "onde a coisa é usada".

### 2026-08-13 — Fecha os 2 filtros de plano de contas que faltavam migrar pro SeletorPlanoContas

Gap achado na auditoria abaixo, fechado no mesmo dia a pedido do dono do produto: os filtros de
plano de contas nas listagens de Fornecedores e Movimentação de Conta Corrente também trocaram
o `<select>` nativo (`limite=100`) pelo `SeletorPlanoContas`. Precisou de uma melhoria no
componente que os usos em formulário não exigiam: apagar o texto e sair do campo (ou apertar
Enter vazio) agora limpa o valor (`onChange('')`) — num filtro, "vazio" é um estado válido
("todos"), diferente do formulário, onde o campo é sempre obrigatório. Ganhou também `ariaLabel`
(filtros não têm `<label>` visível) e `width:100%` explícito no input (a regra CSS
`.filtros-bar input { width:auto; min-width:180px }` tem mais especificidade que o `width:100%`
global, e o componente nunca tinha sido usado dentro de `.filtros-bar` antes). Testado ao vivo
nas duas telas: busca por código/nome, "limpar" voltando pro placeholder, layout correto mesmo
na tela com 7 filtros (Movimentação de Conta Corrente). `tsc --noEmit` limpo
(`noUnusedLocals`/`noUnusedParameters` confirmam que as queries antigas foram removidas por
completo, não só desconectadas da tela).

### 2026-08-13 (auditoria) — segunda passada de "documente/memorize tudo" achou mais 4 lacunas

Pedido repetido na mesma sessão ("agora documente tudo e memorize tudo" de novo) — mesmo sinal
já visto em 2026-07-31 (ver `feedback_checklist_documentar_tudo`, memória): a primeira passada
cobre bem a feature em si, mas erra referências cruzadas em specs de OUTRAS telas. `grep` do
termo trocado (`select`/`plano de contas`) em todo `docs/telas/*.md` achou: **1)**
`docs/telas/fornecedor.md` ainda descrevia o campo do formulário como `<select>` (virou
`SeletorPlanoContas`) e ainda dizia "Plano de Contas, que não tem `ativo`" (errado desde
07-31, nem era desta sessão). **2)** `docs/telas/contas-pagar.md` descrevia o workaround antigo
("select, tamanho:500 — evita o bug de dropdown truncado"). **3)** `docs/telas/
importacao-dados.md` não mencionava a busca por nome. **4)** `spec-driven-erp-varejo.md` (spec
principal) ainda mostrava `tipo_movimento_conta` com acento, removido desde 07-31. Todas
corrigidas. **Gap real encontrado e conscientemente NÃO corrigido** (documentado como Non-goal
em vez de implementado sem pedir): só os campos de **formulário** migraram pro
`SeletorPlanoContas` — os filtros de plano de contas nas listagens de Fornecedores e
Movimentação de Conta Corrente continuam `<select>` nativo (`tamanho:100`), funcional hoje mas
sujeito ao mesmo risco de corte se o tenant passar de 100 contas.

### 2026-08-13 — Plano de Contas: máscara encurtada para 9.99.999, SeletorPlanoContas (busca código/nome), remoção de Exigências/Integrações

Revisão pedida pelo dono do produto depois de olhar o cadastro em uso: `1.02.002.000` (12
caracteres) é complexidade de contabilidade formal, não de pequeno varejo. Três perguntas
guiaram a conversa — "precisa ser tão grande?", "isso vira DRE e Fluxo de Caixa mesmo?", "esses
campos de Exigências/Integrações servem pra quê?" — e as respostas viraram três mudanças.

1. **Máscara `9.99.999.999` → `9.99.999`** (4 níveis → 3: grupo.família.conta, 12 → 8
   caracteres). Opções A (só enxugar o seed)/B (`9.99` puro, 4 caracteres)/C (abolir hierarquia)
   foram apresentadas antes; o formato escolhido no fim (`9.99.999`) é intermediário — preserva
   o nível de família (subtotal de "Pessoal"/"Ocupação" etc. na DRE), só corta o último nível
   (subitem), que concentrava 249 das 336 contas do plano antigo e quase nunca era usado.
   **O motor de DRE/DFC não muda**: ele sempre leu `grupo_dre`/`grupo_dfc`/`sinal` por conta,
   nunca a máscara do código — os dois relatórios continuam saindo exatamente iguais.
   - `V016__cadastros.sql`: `nivel` (1–3) e `id_plano_contas_pai` (colunas geradas) recalculados
     pra 3 blocos; CHECK de máscara `^[1-9]\.[0-9]{2}\.[0-9]{3}$`.
   - `V032__entrada_planilha.sql` e `SignupService`: árvore mínima e `cfg_geral.id_plano_contas_
     compra_mercadoria` migrados de `3.03.001.001` pra `3.03.001`.
   - `PlanoContasService`: regex, mensagem de erro, e `calcularPai()` (pré-checagem amigável de
     hierarquia) ajustados pra 3 níveis.
   - **Banco de dev convertido ao vivo, dado nenhum perdido**: os 3 tenants existentes tinham só
     `3.03.001.001` referenciado (3 fornecedores + `cfg_geral` dos 3 tenants) — remapeado pro
     código novo **antes** de dropar as colunas geradas antigas e recriá-las já na máscara nova;
     as 336 contas antigas saíram, o seed novo (76 contas) entrou. `vw_plano_contas_arvore`
     (`SELECT *`) precisou ser derrubada e recriada na mesma transação (dependia das colunas).
   - **Seed novo** (`db/scripts/seed_plano_contas_padrao.sql`, reescrito): 76 contas (8 grupos,
     19 famílias, 49 analíticas) — plano genérico de pequeno varejo (não mais específico de
     calçados/confecções). Grupo 7 (Movimentos Neutros e Sócios) é a resposta a "vai ter conta
     que não entra em DRE nem em Fluxo de Caixa" — sangria/suprimento, transferência entre
     contas, liquidação de cartão/crediário, nenhuma delas conta como resultado nem como
     variação de caixa "de negócio" (evita duplicar o que já aparece na venda/recebimento).
   - Máscara/validação no front (`web/src/lib/masks.ts`, `mascararCodigoPlanoContas`/
     `codigoPlanoContasValido`), `AjudaDaTela.tsx` (`cadastros.planocontas.form`), formulário e
     testes de hierarquia (`PlanoContasCrudTest`) todos ajustados pra 3 níveis.

2. **`SeletorPlanoContas.tsx` (componente novo)** — pedido explícito: "poder entrar como código,
   mas também pesquisar pelo nome da conta", em todo lugar que hoje pede plano de contas.
   Combobox único: digitar dígitos/pontos busca por **prefixo de código** ("401" acha
   `4.01.xxx`); digitar texto busca **na descrição**. Carrega o plano inteiro (tamanho 500,
   ~76 contas no padrão) e filtra no cliente — não herda o bug de "select truncado por
   paginação" que telas antigas tinham. Enter escolhe a linha destacada (setas navegam,
   `stopPropagation` pra não conflitar com o Enter-como-Tab do formulário), clique escolhe
   (`onMouseDown`+`preventDefault` pra sobreviver ao blur), contas sintéticas aparecem em
   negrito. Bug pego em teste ao vivo no navegador e corrigido: sem selecionar o texto exibido
   após a escolha, digitar de novo **anexava** ao código escolhido em vez de substituí-lo.
   Substituiu os `<select>` de plano de contas em: `FornecedorForm`, `ContasPagarForm`,
   `ContaCorrenteMovimentoForm`, `ConfiguracaoGeralForm` (com a opção `apenasAnaliticas`) e
   `ImportacaoTabelaPage`. O cadastro rápido de fornecedor da Entrada de Produtos
   (`FornecedorQuickCreateModal`) continua **sem** esse campo — a conta é sempre a de compra
   configurada, atribuída sem interação do operador (decisão de 2026-08-12, mantida).

3. **Removidas por completo — tela, schema, banco** — as seções "Exigências no lançamento"
   (`exige_centro_custo`/`exige_contraparte`/`exige_documento`) e "Integrações" (`id_conta_
   contabil`/`id_plano_referencial`, de-para SPED ECD/plano referencial RFB). Antes de remover,
   auditado (`grep`) que nenhuma tela de lançamento (PDV, caixa, contas a pagar, movimentação de
   conta corrente) lia essas flags — foram desenhadas como trava/integração *futura* e nunca
   ficaram funcionais; confirmado que a remoção **não** afeta DRE/DFC (que não dependem delas).
   Índice `cfg_plano_contas_contabil_uq` removido junto. `PlanoContasDtos`/`Service`/
   `FornecedorImportador` (que criava conta pela Importação de Dados) e `web/src/lib/
   planoContas.ts`/`PlanoContasForm.tsx` ajustados — a seção "Integrações" virou uma seção
   simples só com Observação.

**Verificação:** suíte de backend **474/474 verdes** (conversão mecânica de códigos nos testes +
teste de hierarquia reescrito pra 3 níveis; nenhum teste novo, nenhum removido — mesma
contagem antes/depois das duas rodadas). `tsc --noEmit` limpo. API rebuildada duas vezes
(máscara nova, depois remoção dos campos) e testada ao vivo via curl e navegador: criação/edição/
exclusão de conta no formato novo, endpoint antigo rejeitando o formato de 12 caracteres,
resposta da API sem os 5 campos removidos, busca por código e por nome no `SeletorPlanoContas`
funcionando na tela de Fornecedor. `docs/telas/plano-contas.md` atualizado por completo (máscara,
tabela de campos, contrato de API, seed, critérios de aceitação).

### 2026-08-13 — Foco automático em 15 telas de lista + espaçamento na Exportação de Dados

Pedido direto, lista fechada de telas: `autoFocus` adicionado ao campo principal de busca/
localização de Pesquisa de Vendas e Reimpressão de Papeleta de Venda (Nº da venda), Recebimento
de Crediário e Reimpressão de Papeleta de Recebimento de Crediário (Nome do cliente — dentro do
popup de filtros, no caso da reimpressão), Transferência de Produtos e Movimentação de Conta
Corrente (Data inicial), Conta Corrente (busca por conta/descrição), Tipo de Carteira/Clientes/
Funcionários/Usuários/Configuração de Etiqueta (busca por nome), Fornecedores (busca por razão
social), Produtos (busca por descrição) e Contas a Pagar (campo Fornecedor, dentro do popup de
filtros obrigatório). Confirmado que o popup de Abertura de Caixa (Recebimento de Crediário) não
conflita com o foco — é um overlay com foco próprio, o campo de busca só recebe foco quando o
caixa já está aberto. Na Exportação de Dados, os botões de tabela ficavam colados porque o
`.form-grid` padrão do projeto não tem espaçamento vertical entre linhas (pensado pra
formulários, onde o label já cria respiro) — aplicado `gap: 14px 16px` só nessa grade, casando
com o espaçamento de `.menu-cards` da tela de Relatórios. `tsc --noEmit` limpo; sem impacto em
backend.

### 2026-08-13 — Fix: cadastro rápido de fornecedor na Entrada de Produtos não preenchia o plano de contas para papel não-ADMIN

Achado ao investigar por que o campo "plano de contas" do fornecedor, criado pelo cadastro
rápido embutido na Entrada de Produtos por Compra, às vezes não vinha preenchido com o padrão
configurado em Parâmetros do Sistema. Causa: `FornecedorQuickCreateModal.tsx` chamava
`GET /api/v1/config-geral` (o endpoint **completo** de Parâmetros do Sistema) só pra ler
`idPlanoContasCompraMercadoria` — e esse endpoint é **ADMIN-only**, checado dentro do próprio
`ConfiguracaoGeralService`. Pra qualquer usuário `OPERADOR` (o caso mais comum de quem faz
entrada de mercadoria no dia a dia), a chamada voltava 403 silencioso, a query React Query ficava
sem dado, e o `useEffect` que atribui o plano de contas por baixo dos panos nunca disparava.
O próprio `ConfiguracaoGeralService` já tinha o método certo pra isso —
`idPlanoContasCompraMercadoria()`, **sem checagem de papel**, com fallback pro código padrão —
só faltava uma rota. Adicionado `GET /api/v1/config-geral/plano-contas-compra-mercadoria` (mesmo
padrão já usado por `/usa-cor-grade`, `/rateia-frete-entrada`, `/reajusta-preco-entrada`: flags
liberadas a qualquer papel autenticado, sem passar pelo `buscar()` completo) e trocada a chamada
do modal pra esse endpoint. Reproduzido e confirmado ao vivo com um usuário `OPERADOR` real: 403
no endpoint antigo, 200 com o valor certo no novo. Suíte de backend segue **474/474 verdes**
(nenhum teste novo — a cobertura de ponta a ponta foi feita via curl/navegador, não automatizada
nesta sessão).

### 2026-08-13 — Cor/Grade padrão (sentinela código 1), dados reais de NCM, sincronismo de NCM no XML e ajustes na grid da Entrada por XML

**1) Cor/Tamanho/Grade "PADRÃO" sempre gravados internamente, nunca mostrados (pedido explícito
do dono do produto).** Quando o tenant não usa cor/grade, o sistema passou a gravar sempre
`id_cor=1` (nome vazio), `id_tamanho=1` (nome "UN") e `id_grade=1` (descrição "PADRÃO", único
tamanho "UN") em vez de valores nulos — assim, se o tenant ligar "Usa Cor/Grade" no futuro, o
dado já está estruturalmente consistente. Exigência explícita: o código tem que ser literalmente
`1`, igual em todos os tenants — não "um valor qualquer marcado como padrão". Como
`cfg_cor`/`cfg_tamanho`/`cfg_grade` usavam `GENERATED ALWAYS AS IDENTITY` (sequência única
global), a PK virou composta `(id_tenant, id_col)` com o valor calculado em Java
(`COALESCE(MAX(id)+1, 1)` por tenant) em vez de depender do gerador do Postgres —
`SignupService` passou a inserir as 3 linhas padrão pra todo tenant novo. `produto.id_grade` e
`produto_barra.id_cor`/`id_tamanho` viraram `NOT NULL DEFAULT 1`. Toda listagem/JOIN de
cor/tamanho/grade exclui `id=1` (~21 arquivos), e a API traduz `1` de volta pra `null` na
resposta — o contrato do frontend não mudou. Ver `[[project_cor_grade_tamanho]]` na memória.

**2) `cfg_produto_ncm` populada com os ~10.515 códigos NCM reais da Receita Federal**, via API
pública do Portal Único Siscomex (hierarquia de descrição reconstruída por prefixo de código, já
que o dash-count da fonte original é inconsistente no nível mais profundo). `aliquota_ibpt = 0.00`
em todas as linhas (pedido explícito — não temos essa fonte de dados ainda).

**3) NCM da Entrada por XML sincroniza com o cadastro do produto**, sempre valendo o NCM do XML
quando ele difere do já cadastrado (`EntradaMercadoriaService.efetivar`); quando o produto não é
localizado, o NCM do XML já vem preenchido na pendência/cadastro rápido — **bug corrigido**: a
1ª versão validava o NCM contra `cfg_produto_ncm` antes mesmo de mostrar, zerando qualquer NCM
real ainda ausente da base local (incompleta na época); a correção separa NCM bruto (mostrado
sempre) de NCM validado (só usado no UPDATE final).

**4) Grid da Entrada por XML/Planilha, com "Usa Cor/Grade" desligado**: a coluna Cor/Tamanho some
tanto em "Localizados" quanto em "Não Localizados", e linhas com o mesmo nome de produto (ex.:
uma grade legada de calçado, um tamanho por linha do XML) aparecem **agrupadas numa única linha**
com a quantidade somada — resolver ("Pesquisar"/"＋ Cadastrar") ou ignorar essa linha aplica à
TODAS as linhas do grupo de uma vez. Antes, cada tamanho virava uma pendência separada mesmo sem
grade em uso. Tentativa de ordenar a grid por descrição+cor+tamanho foi pedida e depois revertida
pelo dono do produto — a ordem correta é a mesma ordem em que os itens aparecem no XML.

**5) Zeragens de dados a pedido do dono do produto** (banco ainda em construção, sem preservar
dado de teste): produto/movimentação/vendas/tamanho/cor/grade; depois cliente/fornecedor/
importação_lote/caixa_mestre/contas_receber_lote (`cfg_produto_ncm` explicitamente preservada);
e por fim fornecedor/produto/movimentação/contas_pagar/código de barras — nesta última, o trigger
de estoque (`fn_atualiza_estoque_movimento`) reage ao `DELETE` de `produto_movimento_detalhe`
devolvendo saldo pra `produto_estoque`, então a ordem de exclusão teve que deletar o detalhe
ANTES do estoque (e o estoque de novo depois) pra não sobrar FK pendurada.

Suíte de backend **474/474 verdes**, `tsc --noEmit` limpo. Verificado ao vivo no navegador
(browser automation) em todos os itens acima antes de fechar a sessão.

### 2026-08-13 — Entrada de Produtos por Compra ignora "Usa Cor/Grade" desligado por completo

Pedido direto do dono do produto: com o parâmetro **"Usa Cor/Grade" desmarcado** em Parâmetros
do Sistema, a Entrada de Produtos não deve pedir nem mostrar Cor/Tamanho para nenhum produto —
nem no cadastro rápido de produto novo, nem para produtos que já tinham grade cadastrada de uma
sessão anterior (achados **3605 produtos com `id_grade` preenchido** no tenant de teste
`loja-teste-manual`, mesmo com o parâmetro já desligado no banco).

**Decisão tomada com o dono do produto antes de codificar:** produto com grade legada, com o
parâmetro desligado, passa a ser tratado como produto simples em toda a Entrada — a grade é
ignorada por completo, mesmo que o produto tenha mais de uma cor/tamanho já cadastrados (nesse
caso a entrada lança tudo numa variação "genérica" nova, cor/tamanho nulos, em vez de perguntar
qual delas). Ver `[[project_cor_grade_tamanho]]`/`[[project_entrada_mercadoria]]` na memória.

**Correções (3 arquivos):**
- `EntradaPlanilhaService.java` (fluxo Planilha) — passou a consultar
  `ConfiguracaoGeralService.usaCorGrade()` uma vez no `preview()` e zera `idGrade` internamente
  quando a flag está desligada, antes de decidir se COR/TAMANHO são obrigatórias na planilha —
  produto com grade legada some da exigência e resolve direto (variação com cor/tamanho nulos).
- `PesquisaProdutoEntradaModal.tsx` (fluxo Individual) — `usaGrade` deixou de olhar só
  `produtoEscolhido.idGrade`; agora também busca `buscarUsaCorGrade()` e só pede Cor + grade de
  tamanhos quando as duas coisas são verdadeiras. Sem grade aplicável, mostra um único campo de
  Quantidade — verificado ao vivo no navegador com um produto de grade legada.
- `EntradaMercadoriaForm.tsx` (`LinhaPendentePlanilha`, resolução de pendência do Planilha/XML) —
  **bug real de travamento corrigido**: o formulário de Cor/Tamanho aparecia pra QUALQUER
  pendência com produto achado (`temProduto`), mesmo quando o produto não tinha grade nenhuma —
  nesse caso o select de Tamanho ficava sempre vazio (grade nunca carregada) e o botão
  "Confirmar" nunca habilitava, travando a resolução. Corrigido checando
  `linha.idGradeEncontrada != null` antes de exigir/mostrar os selects.

`ProdutoQuickCreateModal.tsx` ("＋ Cadastrar Produto" embutido na Entrada) já escondia o campo
Grade corretamente quando a flag está desligada — não precisou de mudança.

Sem testes novos (comportamento já coberto pela suíte de Entrada existente,
`EntradaPlanilhaCrudTest`/`EntradaMercadoriaCrudTest`, 22 testes, todos verdes); suíte de backend
completa **472/472 verdes**, `tsc --noEmit` e `mvn compile` limpos. Servidor reiniciado
(`docker compose restart api web`) e validado ao vivo no navegador antes de fechar a sessão.

### 2026-08-12 (continuação) — Contas a Pagar / Pagas: CRUD completo sobre tabela pré-existente

Nova tela no módulo **Financeiro** (`docs/telas/contas-pagar.md`, novo), pedido direto do dono
do produto: "Desenvolver a Tela Contas a Pagar / Pagas". A tabela `contas_pagar` já existia
desde V026 (2026-07-16), mas só era escrita internamente pela Entrada de Produtos por Compra
(`com.vetor.niner.estoque.entrada.ContasPagarService`, helper de INSERT sem tela própria) — não
havia como consultar, corrigir, excluir ou dar baixa numa duplicata de fornecedor. Novo pacote
`com.vetor.niner.financeiro.contaspagar` (Controller/Service/Dtos, singular, deliberadamente
distinto do helper pré-existente) implementa o CRUD completo (`GET`/`GET {id}`/`POST`/`PUT`/
`DELETE`, `/api/v1/contas-pagar`), sem restrição de papel.

**Não existe endpoint/tela de "Pagar" separado** — pedido explícito listou só Visualizar/
Editar/Excluir na grid; editar preenchendo Data de Pagamento/Valor Pago/Documento Pago **é** a
baixa (nota explicativa visível no formulário). Popup de filtros obrigatório ao entrar na tela
(Fornecedor, Empresa, Nota Fiscal, Duplicata, período de Vencimento, período de Pagamento),
mesmo padrão da Entrada de Produtos/CRM. `contas_pagar` ganhou `criado_em`/`atualizado_em`
(edição in-place de V026, banco em construção) para satisfazer a seção de auditoria obrigatória
em toda tela de cadastro; sem `ativo` — exclusão é sempre definitiva, mesmo padrão de
`conta_corrente_movimento`, inclusive em linhas geradas por uma Entrada (`idMovimento`
preenchido — se a entrada for cancelada depois, o `DELETE` do Cancelamento de Entrada só
encontra 0 linhas).

**Dois bugs pegos só testando ao vivo no navegador** (não pelo `tsc`/testes automatizados):
1. A grid esqueceu a coluna **Duplicata**, pedida explicitamente — corrigida adicionando-a
   como coluna não ordenável (o backend não expõe `numeroDuplicata` em
   `COLUNAS_ORDENAVEIS`).
2. Selecionar um fornecedor no typeahead deixava a mensagem "Escolha o fornecedor." visível
   mesmo com o campo preenchido — o `onBlur` do campo de busca já tinha marcado o erro antes do
   clique na linha da tabela ser processado; `escolherFornecedor()` agora limpa o erro também.

Suíte de backend completa: **472/472 verdes** (11 testes novos, `ContaPagarCrudTest`).

### 2026-08-12 — Filtros da Entrada de Produtos por Compra + bug real de fuso horário

Mesma sessão de continuação, `EntradaMercadoriaLista.tsx` ganhou popup de filtros obrigatório
ao entrar na tela (Fornecedor, Empresa, Nº Nota Fiscal, Data Início/Fim), mesmo padrão do
CRM/Cancelamento de Devolução — nenhum campo obrigatório aqui (em branco lista tudo), dois
botões no popup ("Localizar" e "＋ Nova entrada", que pula a busca).

**Bug de fuso horário achado e corrigido.** A sessão do Postgres roda em UTC, mas a tela mostra
data em horário local do navegador — filtrar por `data_movimento >= 'aaaa-mm-dd 00:00 UTC'`
fazia uma entrada lançada à noite em Brasília (já virada de dia em UTC) cair no dia errado do
filtro. Corrigido com `(pmm.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date` na
comparação — primeiro uso desse padrão no projeto. Mesma causa raiz também corrigida na
gravação (`EntradaMercadoriaService.efetivar`, que gravava meia-noite UTC em vez de meia-noite
de Brasília para `dataMovimento`). **Possível problema sistêmico não corrigido:**
`CancelamentoVendaService`/`CancelamentoDevolucaoService` usam `coluna::date BETWEEN ? AND ?`
sem `AT TIME ZONE` nos próprios filtros de período — mesma classe de bug, não mexido agora (fora
do pedido), vale revisar se aparecer reclamação de filtro "perdendo" registro perto da meia-noite.

### 2026-08-12 — Cancelamento de Entrada de Produtos por Compra

Pedido direto do dono do produto: "na tela de entrada de produtos compra, na grid principal tem
a opção de visualização, coloque uma opção de exclusão tb" — esclarecido como cancelamento
auditável (P3), mesmo padrão de Cancelamento de Venda/Devolução, não exclusão física.
`POST /api/v1/estoque/entradas/{id}/cancelar`, ADMIN-only. O `produto_movimento_mestre`
original nunca é apagado nem editado — ganha `cancelado`/`data_cancelamento`/
`id_usuario_cancelamento`/`motivo_cancelamento` (editado em V019, mesmo padrão de
`venda.cancelada`); o estorno de estoque é um novo `produto_movimento_mestre` tipo
`CANCELAMENTO` com `credito_debito='D'` por item, revertido sozinho pela trigger já existente.
`contas_pagar` geradas pela entrada são deletadas — exigiu a coluna nova
`contas_pagar.id_movimento` (V026, editado in-place), já que `nota_fiscal`+`id_fornecedor`
sozinhos não garantem identificar as duplicatas de uma entrada específica. Bloqueios: já
cancelada → 409; conta a pagar já quitada → 409 (hoje inatingível na prática, sem tela de baixa
até esta sessão — ver entrada de Contas a Pagar acima). Reimportar a mesma NF-e depois de
cancelar passou a funcionar (`produto_movimento_mestre_chave_nfe_uk` ganhou `AND cancelado =
false`).

**Bug real pego só testando ao vivo, não pelos 7 testes automatizados** (`CancelamentoEntradaCrudTest`
roda com o superusuário do Testcontainers, não como `niner_app`): V024 revoga `UPDATE`/`DELETE`
de `produto_movimento_mestre` para `niner_app` inteira (imutabilidade P3); corrigido com um
`GRANT UPDATE` **de coluna** (as 4 colunas novas, não a linha toda) — grant de coluna é uma
técnica nova no projeto, mantém a imutabilidade do restante do registro intacta.

**⚠️ Incidente não resolvido:** logo depois de cancelar uma entrada legitimamente, 7 outras
entradas foram canceladas sozinhas ~90s depois, sem ação do operador — causa raiz não
identificada apesar de investigação extensa; dados corrigidos manualmente. Registrado em
memória (`feedback_incidente_cancelamentos_fantasma`) como alerta para testes futuros de
cancelamento em lote.

### 2026-08-12 — Entrada de Produtos por Compra: Fluxo XML (Fase 3), fechando a feature

Retomada de `docs/telas/entrada-mercadoria.md`, que desde 2026-08-11 tinha só os fluxos
Manual e Planilha prontos (Fase 3 — Fluxo XML — pendente). `NfeXmlParser.java` (DOM sem
namespace, XXE-safe) + `EntradaXmlService.java`: a aba "Dados Gerais" pede o **upload do XML
primeiro** (pedido explícito) — fornecedor, empresa, nota, data e parcelas só aparecem depois de
processado, nenhum é digitado à mão quando o XML já traz o dado. Fornecedor casado pelo CNPJ do
`emit`; sem match, `FornecedorQuickCreateModal` abre sozinho, pré-preenchido, atribuindo
`cfg_geral.id_plano_contas_compra_mercadoria` por baixo dos panos (não é mais campo visível em
nenhum dos 3 fluxos). Matching de item: EAN → `produto_fornecedor` aprendido (`cProd`) →
heurística de texto (só sugestão) → pendência manual. Cor/tamanho nunca são cadastrados
sozinhos no fluxo XML — sempre exigem confirmação manual, mesmo quando o palpite bate com uma
opção já existente (nesse caso vem pré-selecionado, nunca cria nada novo sozinho). Pendências
sem produto propagam `idProdutoEncontrado`/`idGradeEncontrada` para outras linhas do mesmo nome
normalizado ao cadastrar uma delas, evitando recadastro. Testado ponta a ponta com 2 NF-es reais
(Dakota Calçados, 36 itens; A. Grings S.A., `nfe-grings.xml`) — `EntradaXmlCrudTest`, 8 testes.

Com isso, a Entrada de Produtos por Compra fica **completa** nos 3 fluxos (Manual/Planilha/XML);
falta só a Fase 5 (atalho de emissão de etiqueta a partir de uma entrada), não bloqueante.

### 2026-08-11 — Horário de Acesso por Dia da Semana (Cadastro de Usuários) + aviso visual de contagem regressiva

Pedido de segurança do dono do produto: restringir quando um `OPERADOR` pode fazer login e
continuar trabalhando, com uma janela de horário por dia da semana — nunca se aplica ao ADMIN.
`usuario.controla_horario_acesso` (checkbox por usuário) + tabela nova `usuario_horario_acesso`
(V033, 7 linhas por usuário quando ligado, `dia_semana` ISO 1..7, `hora_inicio`/`hora_fim`
nulos juntos = sem acesso naquele dia). Dois pontos de bloqueio com tolerâncias diferentes —
resolve a tensão "bloquear fora do horário" × "nunca cortar uma venda no meio": login
(`SignupService.login`) com tolerância **zero**; toda chamada autenticada em `/api/v1/**`
(`HorarioAcessoFilter`, novo, registrado logo após o filtro de tenant) com **15 minutos** de
tolerância (revisado de 30 para 15 a pedido do dono do produto, ver abaixo). Único ponto de
verdade da regra: `HorarioAcessoService.podeAcessarAgora`, uma query SQL usando `now()` do
servidor Postgres — nunca o relógio do processo Java nem do cliente, requisito explícito.

**Aviso visual de contagem regressiva** (pedido numa segunda rodada, mesmo dia): `GET
/api/v1/eu` ganhou `segundosRestantesTolerancia` (preenchido só nos 15 minutos finais de
tolerância); o frontend (`HorarioAcessoGuard.tsx`, novo, montado em toda rota autenticada via
`RequireAuth.tsx`) polla a cada 30s e mostra um anel circular vermelho no canto superior
esquerdo, mm:ss contando 15:00→00:00 em tempo real, resincronizado a servidor a cada poll. Ao
zerar (local ou por um 403 do backend), aciona o mesmo mecanismo de **logoff gracioso** já
existente: novo contexto genérico "rotina crítica em andamento"
(`web/src/lib/rotinaCritica.tsx`, `RotinaCriticaProvider`) — o logoff automático só acontece
quando a flag está `false`; o único consumidor por enquanto é o PDV (`Pdv.tsx`), que liga a
flag ao abrir a forma de pagamento e desliga tanto ao cancelar quanto ao fechar o comprovante,
nunca deixando uma venda ser cortada no meio.

**Dois bugs reais encontrados na verificação manual ao vivo** (não em teste automatizado
isolado — só apareceram testando contra a API rodando de verdade):
1. `ResponseStatusException` (usada pelo login e por vários outros serviços) não virava corpo
   Problem Details sozinha, mesmo com `spring.mvc.problemdetails.enabled=true` — chegava ao
   cliente com status certo mas corpo vazio; o front caía no genérico "Ocorreu um erro.",
   escondendo o motivo real (afetava até "Credenciais inválidas." do login, não só este
   recurso). Corrigido com um `@ExceptionHandler(ResponseStatusException.class)` explícito em
   `GlobalExceptionHandler`.
2. `time + interval` do Postgres embrulha na virada da meia-noite (`23:50 + 15min` vira
   `00:05`, limite superior *menor* que o inferior) — quebrava a tolerância sempre que o fim do
   expediente caía nos últimos `toleranciaMinutos` do dia. Corrigido somando a tolerância no
   domínio de TIMESTAMP (`current_date + hora`) em vez de `time` puro; regressão coberta com
   timestamps fixos (não depende do horário real da suíte).

Também corrigido no caminho: um campo `boolean` primitivo em record de request DTO
(`controlaHorarioAcesso`) quebrava com `MismatchedInputException` sempre que o JSON não mandava
a chave — 20 testes existentes de `UsuarioCrudTest`/outros passaram a falhar; trocado para
`Boolean` (boxed), mesmo padrão já usado por `ativo` no mesmo record. Suíte de backend:
**444/444 verdes**. Detalhe completo em `docs/telas/usuario.md`, seção "Horário de acesso por
dia da semana".

### 2026-08-11 — Entrada de Produtos por Compra (Fluxo Manual + Planilha), retomando a spec pausada em 2026-07-23

Retomada da spec `docs/telas/entrada-mercadoria.md` (RASCUNHO pausado desde 2026-07-23, todas
as questões em aberto respondidas "sim" pelo dono do produto). Implementados os fluxos
**Manual** (busca de produto item a item) e **Planilha** (modelo baixável, preview que casa
cada linha por EAN → descrição+marca+referência (+ cor/tamanho da grade) → cria cor/tamanho
novos automaticamente quando a confiança é alta, linha sem match fica pendente) — confirmação
comum aos dois grava `produto_movimento_mestre` (`COMPRA`) + N `produto_movimento_detalhe`
(`C`) numa transação, saldo sobe via trigger existente (V019), sem escrita manual de estoque.
**Fluxo XML (Fase 3) e atalho de emissão de etiqueta (Fase 5) ficaram pendentes nesta rodada** —
a tela já oferece "Por XML" mas avisa que ainda não está disponível. *(Atualização: Fase 3 foi
implementada em 2026-08-12 — ver entrada "Entrada de Produtos por Compra: Fluxo XML" acima;
só a Fase 5 continua pendente.)*

Rateio de frete/IPI/ICMS-ST no custo e reajuste automático de `preco_custo`/`preco_venda` —
ambos **configuráveis** (`cfg_geral.cfg_rateia_frete_entrada`/`cfg_reajusta_preco_entrada`,
Parâmetros do Sistema, default desligado). `contas_pagar` gerado a partir de parcelas opcionais
no corpo da confirmação, usando `cfg_geral.id_plano_contas_compra_mercadoria` — **não** mais a
conta do fornecedor (`fornecedor.id_plano_contas`), que era o campo errado pra isso; V032 semeia
a árvore mínima até "3.03.001.001 Compra de Mercadoria para Revenda" pra tenants sem essa conta
ainda. Tabela nova `produto_fornecedor` (V031) aprende o código do fornecedor (cProd) por
variação a cada entrada, acelerando o match de importações futuras, sem tornar fornecedor
obrigatório no cadastro do produto. Cadastro rápido embutido de produto/fornecedor/NCM direto
na tela de conferência quando um item não casa (`ProdutoQuickCreateModal.tsx`,
`FornecedorQuickCreateModal.tsx`, `PesquisaNcmModal.tsx`). Multi-empresa: `GET
/api/v1/empresas/permitidas` (ADMIN vê todas do tenant, OPERADOR só as ligadas a ele em
`usuario_empresa`) deixa escolher em qual empresa a mercadoria entra. Correção pós-confirmação
por edição direta do item (`PUT .../itens/{idDetalhe}`, trigger já desfaz o delta antigo e
aplica o novo) — sem tabela de histórico de UPDATE/DELETE por ora. Detalhe completo,
inclusive o que ficou pendente, em `docs/telas/entrada-mercadoria.md`.

### 2026-08-11 (continuação 2) — Devolução de Produtos: restrição a produtos vendidos + config + bug de cache

Mesma sessão, pedido direto do dono do produto: "se entrar com o número da venda, já pega o
vendedor automaticamente, e só pode devolver produtos que foram vendidos" —
`docs/telas/devolucao-produtos.md`, seção "Restrição a produtos vendidos" tem o desenho
completo; aqui só a linha do tempo.

**1. Busca automática do vendedor.** Botão "Buscar Vendedor" removido — dispara sozinha ao sair
do campo (`onBlur`) ou no Enter. `GET /vendas/devolucao/vendedor` passou a responder também os
itens vendidos naquela venda (`itens: ItemVendaOrigemResponse[]`, com `qtdVendida`/
`qtdDisponivelDevolucao`), reaproveitando a mesma chamada.

**2. Restrição de itens (`RN-06`).** Com o número da venda resolvido, a tela só aceita lançar
produtos presentes nela, até a quantidade ainda não devolvida (descontando devoluções não
canceladas da mesma venda). Mecânica escolhida via `AskUserQuestion`: código de barras + bloqueio
(toast), não uma lista pra escolher — mantém a leitura livre já existente. Validado também no
servidor (`DevolucaoProdutoService.efetivar`, 400), não só na tela (P4). 4 testes novos em
`DevolucaoProdutoCrudTest`.

**3. Novo parâmetro em Parâmetros do Sistema** — "Exigir número da venda na Devolução de
Produtos" (`cfg_geral.cfg_exige_numero_venda_devolucao`, coluna nova dentro de `V023__cfg_geral.sql`,
default desligado). Ligado, o campo vira obrigatório na tela (e no servidor). Novo endpoint leve
`GET /config-geral/exige-numero-venda-devolucao` (qualquer papel, mesmo padrão de
`/permite-qtd-decimal`). 1 teste novo em `DevolucaoProdutoCrudTest` + assertions novas em
`ConfiguracaoGeralTest`; 7 arquivos de teste existentes precisaram do novo campo obrigatório no
corpo do `PUT /config-geral`.

**4. Foco inicial condicional** — obrigatório: foco vai pro "Número da Venda" ao abrir a tela (e
depois de gravar, quando o formulário reseta); opcional: foco continua no "Código de Barras",
como sempre foi.

**5. Bug real encontrado e corrigido: cache do React Query entre telas.** O usuário reportou que
o item 4 "não estava fazendo o que foi pedido" — investigação mostrou que `ConfiguracaoGeralForm.tsx`,
ao salvar, só atualizava o cache da própria query (`['config-geral']`); as 4 flags leves
(`usa-cor-grade`, `desconto-venda`, `permite-qtd-decimal`, `exige-numero-venda-devolucao`), cada
uma com sua própria query key, continuavam servindo o valor antigo em cache pra qualquer tela já
visitada nesta sessão do navegador — a navegação do sistema é toda client-side (React Router),
sem recarregar a página, então o cache sobrevive entre telas. O primeiro teste desse recurso
tinha passado por acidente: cada verificação usava navegação por URL (recarga completa,
zerando o cache) em vez de clicar no menu como um usuário real faria. Corrigido em duas frentes:
`ConfiguracaoGeralForm.tsx` invalida as 4 queries no `onSuccess` do salvar; `DevolucaoProduto.tsx`
espera `isFetching` virar `false` antes de decidir o foco, em vez de usar o primeiro valor
disponível (que podia ser cache desatualizado). Reproduzido e confirmado corrigido navegando de
verdade pelo menu lateral (sem reload) nos dois sentidos.

Suíte de backend completa: **410/410 verdes**.

### 2026-08-11 (continuação) — botão Fechar no popup de filtros + bug de largura no PDF dos comprovantes térmicos

Mesma sessão do Cancelamento de Devolução de Produtos, dois pedidos curtos depois de testar a
tela pronta.

**1. Botão "Fechar" no popup obrigatório de filtros** (`CancelamentoDevolucao.tsx`) — o popup
(`filtrosAberto`) é a única forma de entrada na tela, e cobre visualmente o ✕ da topbar
(`BotaoFecharTela`, `.modal-overlay` fica por cima); sem um botão dedicado, não havia como sair da
tela sem antes confirmar (ou forçar) os filtros. Adicionado `useNavigate` + botão ghost "Fechar"
que chama `navigate(-1)` — mesmo padrão do resto do sistema
([[project_botao_fechar_tela]]: todo `aoFechar` que navega usa histórico real, nunca rota fixa).
Testado ao vivo: volta pro Painel (última tela real do histórico), não pra uma rota hardcoded.

**2. Bug real no PDF dos comprovantes térmicos** — pedido do dono do produto pra comparar a
papeleta de venda com o vale-mercadoria de devolução (ele reportou o nome do produto "saindo da
papeleta" no vale). Investigação mostrou que o layout em si já estava correto e idêntico entre os
dois desde 2026-08-07 (mesmas funções em `comprovante.ts`) — o problema real estava em
`montarDocumentoComprovante*` (as três variantes: crediário, venda, vale), que criam a página com
`new jsPDF({ unit: 'mm', format: [80, altura] })`, `altura` calculada a partir do número de linhas
do comprovante. O jsPDF, em orientação retrato (padrão), **troca largura por altura sempre que
`largura > altura`** — a papeleta de venda quase sempre tem itens/blocos suficientes pra passar de
80mm de altura, então nunca disparava o bug; o Vale-Mercadoria, que costuma ter 1 item só, ficava
abaixo de 80mm e saía com a página de ~57mm de largura em vez de 80mm, cortando a coluna TOTAL e o
valor do vale. Corrigido impondo um piso de 80mm na altura calculada (constante `LARGURA_MM` nova,
`Math.max(LARGURA_MM, margem*2 + linhas.length*alturaLinha)`) nas três funções.

Confirmado ao vivo, comparando bytes do PDF antes/depois: gerei um vale real (1 item) com o código
antigo — página cortada, exatamente como reportado — apliquei o fix, gerei outro vale — página
correta, largura e colunas idênticas à papeleta de venda de referência. Dados de teste (2 vales,
movimentos de estoque) apagados do banco depois (`DELETE` em `produto_movimento_detalhe`/`_mestre`
+ `venda_devolucao`; a trigger `fn_atualiza_estoque_movimento` reverteu o estoque sozinha ao
apagar o detalhe, [[feedback_trigger_estoque_reage_delete]]).

Arquivos: `web/src/pages/vendas/CancelamentoDevolucao.tsx` (botão Fechar),
`web/src/lib/comprovante.ts` (fix do PDF), `docs/telas/papeleta-venda.md` (detalhe técnico do
bug), `docs/telas/devolucao-produtos.md` e `docs/telas/comprovante-recebimento-crediario.md`
(referência cruzada pro mesmo fix, já que as três variantes compartilham a função).

### 2026-08-10 — Cancelamento de Devolução de Produtos

Nova sessão, pedido direto do dono do produto: fechar a lacuna simétrica ao Cancelamento de
Venda, mas para o vale-mercadoria gerado por uma Devolução de Produtos
(`docs/telas/cancelamento-devolucao-produtos.md`, novo). Tela em
`/cancelamento-devolucao-produtos` (Frente de Loja → Cancelamentos), ADMIN e OPERADOR — diferente
do Cancelamento de Venda (ADMIN-only), mas com uma restrição própria: **OPERADOR só enxerga e
cancela devoluções da empresa em que está logado** (claim `eid`), ADMIN cancela de qualquer
empresa do tenant. Filtros (nº do vale OU intervalo de datas) ficam num **popup obrigatório ao
entrar na tela**, pedido explícito — diferente da barra de filtros inline do Cancelamento de
Venda.

Schema (`venda_devolucao`, editado dentro de `V018__vendas.sql`): ganhou
`cancelada/data_cancelamento/id_usuario_cancelamento/motivo_cancelamento`, mesmo padrão de
`venda.cancelada`. Novo valor de `tipo_movimento` (`V013`): `CANCELAMENTO_DEVOLUCAO` — dedicado,
não reaproveita `CANCELAMENTO` da venda, para o Kardex distinguir qual operação foi revertida
(precisou atualizar os 4 lugares que enumeravam `tipo_movimento` manualmente: DTO e switch de
origem do Relatório de Movimentação de Produtos, enum e rótulos no frontend). Só é permitido
cancelar um vale ainda **não usado** (`vale_usado = false`) — regra central do pedido; um vale já
resgatado numa venda só pode ser desfeito cancelando essa venda primeiro (Cancelamento de Venda,
que já reabre o vale).

Estorno de estoque segue o mesmo mecanismo de sempre: novo `produto_movimento_mestre` +
`produto_movimento_detalhe` `'D'` por item (inverso do `'C'` da devolução original), a trigger já
existente ajusta `produto_estoque` sozinha. Backend novo em
`com.vetor.niner.vendas.cancelamentodevolucao` (Controller/Service/Dtos).

**Consistência corrigida em código já existente, achada durante a implementação (não pedida
explicitamente, mas necessária):** sem isso, um vale cancelado continuaria resgatável no PDV — o
estoque já teria sido retirado de novo pelo cancelamento, então o resgate daria crédito sem
lastro. Duas checagens novas: `PdvVendaService.resolverVale` (bloqueio real no split-tender, 409)
e `DevolucaoProdutoService.buscarVale`/`FormaPagamentoModal.tsx` (a consulta que o PDV faz *antes*
de tentar pagar, pra mostrar o erro na busca em vez de só na tentativa de envio).

Testado ao vivo (API via curl + tela via navegador): vale gerado, estoque creditado, cancelado —
estoque debitado de volta a zero, segunda tentativa de cancelar 409, tentativa de resgate no PDV
409 (backend e busca do vale no frontend). Escopo por empresa testado criando um usuário OPERADOR
de teste restrito a uma filial: só via/cancelava a própria devolução, 403 na de outra empresa;
ADMIN via as duas. Todos os dados de teste (devoluções, movimentos, o usuário OPERADOR) apagados
ao final. Suíte de backend depois de tudo: **405/405 verdes** (sem teste novo — ver "Pendência"
abaixo).

**Pendência aberta:** esta feature **não tem suíte JUnit própria ainda**
(`CancelamentoDevolucaoCrudTest` não existe) — diferente de toda rotina irmã do sistema
(`CancelamentoVendaCrudTest`, `DevolucaoProdutoCrudTest`, `ValeMercadoriaCrudTest`), verificada só
manualmente até aqui. P5 da constituição pede teste automatizado por critério de aceitação antes
do merge; registrado como questão aberta em `docs/telas/cancelamento-devolucao-produtos.md`.

### 2026-08-10 (continuação 2) — dedup de Produto por CODIGO_PRODUTO, tamanho fora da grade no Estoque, 2ª rodada de otimização (7,8x) e gauge na Exportação

Mesma sessão, depois de testar as correções anteriores com os arquivos reais completos do dono do
produto (`PRODUTOS.xlsx`, `ESTOQUE.xlsx`, ~22 mil linhas cada). Mais 4 achados/pedidos:

1. **Dedup de `produto` passou a considerar `CODIGO_PRODUTO`, não só `DESCRICAO+MARCA+REFERENCIA`.**
   Achado testando a planilha real: 14 pares de linhas com a mesma descrição+marca+referência
   (coincidência de texto) mas `CODIGO_PRODUTO` diferente — são produtos DIFERENTES no sistema de
   origem, e o dedup antigo estava descartando um dos dois como "duplicata". Corrigido
   (`ProdutoImportador.produtoJaExiste`, comparação `codigo_importacao IS NOT DISTINCT FROM ?` —
   NULL-safe, então continua funcionando pra linhas sem código). Resultado: as 15 linhas que antes
   ficavam "já existem" passaram a importar (3.593/3.593, 0 ignoradas).
2. **Estoque: tamanho fora da grade do produto deixou de ser erro.** Achado real: linhas com
   `NOME_TAMANHO="UN1"` pra um produto de grade "UN" (só um tamanho) — o dono do produto confirmou
   que isso não é engano, é só uma variação que o sistema de origem não tinha na grade cadastrada.
   `ProdutoBarraService.obterOuCriar` ganhou um parâmetro `validarGrade` (a Estoque chama com
   `false`; a Emissão de Etiqueta, cadastro manual, continua exigindo). Testado com o arquivo real
   completo (22.483 linhas): 100% prontas, 0 erro.
3. **2ª rodada de otimização de performance — 7min46s → 59,7s (~7,8x) num teste justo (banco
   vazio, arquivo real de 22.483 linhas).** O fix de N+1 da sessão anterior (cache local) não
   cobria a 2ª fase do `EstoqueImportador` (criar a variação por grupo), que ainda fazia até 4
   consultas por grupo sem cache possível (cada grupo já é único). Trocado por pré-busca em lote:
   `ProdutoBarraService.buscarGradesEmLote`/`buscarVariacoesEmLote` (2 consultas totais pros
   produtos do arquivo, em vez de milhares) + `criarParaImportacaoEmMassa` (sem o SELECT de volta
   que a versão interativa faz). **Achado em aberto, não investigado:** a gravação de verdade
   (`confirmar=true`, 4min41s) ficou bem mais lenta que a simulação equivalente (59,7s, só
   ROLLBACK no final) — mesma corretude nos dois casos, suspeita de custo de commit/fsync numa
   transação desse tamanho.
4. **Gauge de progresso na tela de Exportação de Dados** (pedido do dono do produto, "como na
   tela de importação") — reaproveita `GaugeProgresso` em modo simulado (a exportação é 1 consulta
   SQL por tabela, sem loop linha a linha no backend pra reportar progresso real), alternando o
   rótulo entre "Buscando dados..." e "Gerando planilha...". Mesmo padrão já usado no CRM, sem
   mudança no backend.

Restauração de dados: os testes de performance exigiram apagar e reimportar `produto_barra`/
`produto_estoque`/movimentação (dados reais do dono do produto, não só teste) — restaurados ao
final via reimportação real do mesmo arquivo, confirmado no banco.

### 2026-08-10 (continuação) — Rotina de Importação de Dados: 3 bugs de produção corrigidos (savepoint, streaming, N+1) + progresso ao vivo + CNPJ/e-mail inválidos

Sessão inteira em cima da reformulação de mais cedo no mesmo dia, motivada por testes reais do dono do
produto com arquivos grandes de verdade (o maior: Contas a Receber, 660.479 linhas) —
`docs/telas/importacao-dados.md` ganhou 3 seções novas cobrindo tudo isto.

**1. "current transaction is aborted" ao validar Produtos — bug real de produção.** Todo
`ImportadorDeTabela.processar()` roda o arquivo inteiro numa única `@Transactional`; o
`catch (RuntimeException e)` por linha pega a exceção em Java, mas se ela vier de uma violação
real no banco (não só validação de campo), a conexão Postgres fica "abortada" (`25P02`) e toda
linha seguinte falha em cascata, mesmo válida — reproduzido de propósito (`PESO_BRUTO` estourando
`numeric(14,3)` no meio do arquivo) antes de corrigir. **Corrigido com SAVEPOINT por linha**: nova
classe `ImportacaoSavepointExecutor` (`TransactionTemplate` `PROPAGATION_NESTED`), aplicada aos
**5 importadores** (o mesmo bug latente existia em todos, só não tinha sido reproduzido ainda).
Exige `nestedTransactionAllowed=true` no `TenantAwareTransactionManager` (setado em
`TenantConfig` — `doBegin` não é re-executado numa transação `NESTED`, então `app.id_tenant` não é
reafetado no meio do arquivo, P8 intacto). Testado: arquivo de 4 linhas, a 2ª estourando o banco de
propósito → só ela rejeitada, as outras 3 importadas (antes, as 2 seguintes também quebrariam).

**2. Planilha de ~700 mil linhas falhando com "Não foi possível ler o arquivo".** Causa real
escondida pelo catch genérico: duas proteções do Apache POI contra "zip bomb" (razão mínima de
inflação + teto de array de bytes) davam falso positivo numa planilha grande e legítima.
Desligadas (`ZipSecureFile.setMinInflateRatio(0)` + `IOUtils.setByteArrayMaxOverride`) num bloco
`static` de `ImportacaoPlanilha`, coerente com a política de "sem limite de arquivo" já vigente. O
catch também passou a logar a exceção real (antes só devolvia a mensagem genérica ao usuário).

**3. Reprodução de propósito do "parado em 100% por 10+ minutos" relatado pelo dono do produto**
achou **duas causas raiz distintas**, as duas corrigidas:
   - **Leitura de `.xlsx` em DOM era muda pro progresso.** `WorkbookFactory`/`XSSFWorkbook` monta
     o arquivo inteiro em memória antes de processar a 1ª célula — pra 660 mil linhas, essa
     montagem é a maior parte do tempo, e o contador só incrementava DEPOIS. Trocado por
     **leitura em streaming (SAX)** só pra `.xlsx` (`XSSFReader`+`XSSFSheetXMLHandler`,
     `ImportacaoPlanilha.lerXlsxEmStreaming`) — processa uma linha do XML por vez, progresso real
     desde a primeira linha. `.xls` legado ficou em DOM (BIFF8 trava em 65.536 linhas por conta
     própria, nunca sofre desse problema). Peça-chave: `DataFormatter.formatRawCellContents`
     sobrescrito (`DataFormatterComDataFixa`) pra sempre normalizar data pra `dd/mm/aaaa`
     ignorando o formato de EXIBIÇÃO da célula de origem (testado com 3 formatos diferentes na
     mesma planilha — os 3 converteram certo). Detecção de tipo de arquivo (`/detectar`), que só
     lê a 1ª linha, caiu de "proporcional ao tamanho do arquivo" pra **0,28s** num arquivo de
     300 mil linhas/9MB (aborta o parse SAX logo após a 1ª linha via exceção de propósito).
   - **N+1 query real**: com a leitura já rápida, `ContasReceberImportador` ainda ficava preso —
     busca o cliente pelo CPF **numa consulta por linha**, sem cache; numa planilha repetindo o
     mesmo CPF em centenas de milhares de parcelas (caso normal), isso é uma consulta redundante
     por linha. Confirmado ao vivo via `pg_stat_activity` (como superuser — `niner_owner` sozinho
     não vê `state`/`query` de outra role — e com `SET app.id_tenant` antes, porque RLS `FORCE`
     esconde tudo até do dono da tabela): a mesma query rodando havia mais de 1 minuto, disparada
     300 mil vezes em sequência. **Corrigido com cache local por chamada** (nunca campo de
     classe — os importadores são singleton Spring) em 3 importadores com o mesmo padrão:
     `ContasReceberImportador` (cliente/empresa), `EstoqueImportador` (produto/cor/tamanho, com
     savepoint só no acerto de cache ausente), `ProdutoImportador` (tamanho da grade).
     Testado: 300 mil linhas, mesmo CPF repetido → **3min25s pra terminar** (antes: mais de 1
     minuto só na 1ª fase, sem previsão de terminar). Ainda pode valer a pena agrupar os `INSERT`s
     em lote no futuro se isso se mostrar devagar demais na prática — mudaria a granularidade do
     relato de erro por linha, então ficou como decisão em aberto, não feita sem perguntar.

**4. Progresso "ao vivo" (registro atual/total) nas 3 etapas — leitura, validação, importação.**
Pedido do dono do produto, perguntado antes de implementar (a chamada é síncrona, sem isso não
existe "atual" de verdade): total contado no NAVEGADOR ao escolher o arquivo (`read-excel-file`,
novo pacote, roda em Web Worker), atual reportado pelo BACKEND via polling
(`GET /importacao/progresso/{id}` a cada 400ms). Backend usa o mesmo padrão de `TenantContext`
(P8): `ImportacaoProgressoContext` (`ScopedValue`, Java 25) ligado só durante
`ImportacaoService.processar()`, lido por código profundo sem passar parâmetro em toda assinatura;
`ImportacaoProgressoRegistry` (mapa em memória) é o que permite a requisição de polling, numa
thread diferente, ler o progresso. `GaugeProgresso.tsx` ganhou modo de progresso real (percentual +
"X de Y registros") quando `atual`/`total` são passados, mantendo o modo simulado antigo pra quando
não há número real (ex. `/detectar`). Testado ao vivo no navegador com 15 mil linhas: gauge
saltando 2% → 33% → 63% → 85% → 100% em tempo real.

**5. Duas correções pequenas de dados, pedidas pelo dono do produto:** e-mail e CNPJ inválidos na
importação de Fornecedores deixaram de rejeitar a linha — entram em branco (mesmo espírito do
TELEFONE, 2026-08-10; `FornecedorService.emailValido`/`cnpjValido`, métodos novos expostos só pra
isso). Estoque Inicial: uma empresa já escolhida numa coluna de quantidade some das opções das
outras 4 (exclusão mútua), e a validação da tela deixou de exigir as 5 colunas preenchidas (agora
1 basta) — consequência obrigatória da exclusão mútua pra não travar tenant com menos de 5
empresas.

Todas as correções testadas ao vivo (planilhas geradas com `write-excel-file`, dados conferidos no
banco via `psql`, não só o relatório da API) — nenhum teste automatizado ainda (mesma lacuna já
registrada, feature segue sem suíte JUnit própria). `mvn compile`/`tsc --noEmit` limpos em cada
rodada. Nada commitado ao final da sessão.

### 2026-08-10 — Rotina de Importação de Dados: hub com 5 telas (substitui a tela única) + Excel nativo (.xlsx/.xls) substitui CSV

Duas reformulações grandes da mesma feature, pedidas pelo dono do produto no mesmo dia, mais duas
correções de acabamento encontradas testando a segunda — `docs/telas/importacao-dados.md` reescrita
de novo (3ª vez em 2 dias: 2026-08-06 → 2026-08-10 → 2026-08-10).

**Parte 1 — tela única multi-arquivo (2026-08-10, mais cedo no mesmo dia) virou hub + 5 telas dedicadas.** Pedido direto:
"botões separados (telas) dentro do botão Importar Dados", na ordem **Clientes → Contas a Receber →
Fornecedores → Produtos → Estoque Inicial** — ordem que, por acaso feliz, já resolve sozinha a
dependência entre tabelas (Contas a Receber precisa de Cliente, Estoque precisa de Produto, ambas
vêm depois). "Importação de Dados" virou um `NavGrupo` em `web/src/lib/menu.ts` com 5 `NavItem`
filhos — o hub em si **não precisou de nenhuma página nova**, o padrão genérico `MenuGrupo.tsx` +
rota `/menu/:grupo` (já usado por todo grupo do menu) já renderiza os cards sozinho. As 5 rotas
(`/importacao-dados/clientes`, `.../contas-receber`, `.../fornecedores`, `.../produtos`,
`.../estoque`) apontam pro mesmo componente novo, `ImportacaoTabelaPage.tsx` (substitui
`ImportacaoDadosPage.tsx`), parametrizado por `tabela`, cuidando de **um arquivo por vez**: escolher
→ escolhas prévias → Validar → Importar → "Nova importação". Toda a lógica de detecção automática
de tipo (multi-cartão) e de "pendência" entre arquivos da mesma leva **desapareceu** — deixou de
fazer sentido já que cada tabela tem sua própria tela e sua própria confirmação imediata (ver Parte
3 abaixo — parte da detecção **voltou** no mesmo dia, com outro propósito).

**Parte 2 — formato de importação trocado de CSV para Excel nativo (.xlsx/.xls).** Não foi
"adicionar Excel como opção" — foi **substituir** o CSV por completo, parser incluído. Nova
dependência `org.apache.poi:poi-ooxml` 5.5.1 no `api/pom.xml` (cobre `.xls` legado via HSSF
transitivamente — `WorkbookFactory` autodetecta o formato). `ImportacaoCsv.java` foi renomeado e
reescrito como `ImportacaoPlanilha.java`: célula tipada do Excel é normalizada de volta pro mesmo
texto que os parsers de campo (`decimal`/`data`/`inteiro`/`booleano`) já esperavam do CSV — célula
numérica formatada como data vira texto `dd/mm/aaaa`, célula numérica comum vira texto plano sem
separador de milhar (`BigDecimal.stripTrailingZeros().toPlainString()`, resolve de graça o risco de
"36" virar "36.0"). Resultado: **os 5 `*Importador.java` não mudaram nenhuma regra de negócio** —
só o find-replace mecânico `LinhaCsv`→`LinhaPlanilha`/`ImportacaoCsv.`→`ImportacaoPlanilha.` e
`modeloCsv()`→`modeloPlanilha()` (que agora gera um `.xlsx` de verdade via uma função nova,
`gerarModelo`, em vez de montar texto `;`-separado na mão). `GET /{tabela}/modelo` devolve
`Content-Type` de Excel e nome de arquivo `.xlsx`. Verificado com `mvn compile` limpo e teste real
no navegador: baixar o `.xlsx` gerado pela própria tela e reimportar esse mesmo arquivo.

**Parte 3 — duas correções pedidas depois de testar a Parte 2 (mesmo dia):**
1. **Textos ainda falavam em "arquivo CSV"** em 7 lugares (`web/src/lib/menu.ts` — descrições dos 5
   cards do hub — e `ImportacaoTabelaPage.tsx` — subtítulos das telas de Clientes/Fornecedores).
   Todos reescritos pra citar "planilha Excel (.xlsx ou .xls)" explicitamente, extensão incluída.
2. **Nada impedia escolher a planilha errada** — ex. selecionar `FORNECEDORES.xlsx` na tela de
   Importar Clientes só dava erro (sem sentido, tipo "RAZAO_SOCIAL" lido como se fosse "NOME") na
   hora de clicar "Validar", tarde demais. Corrigido reaproveitando a **mesma detecção por Jaccard**
   de 2026-08-10 (`POST /api/v1/importacao/detectar`, que tinha ficado sem chamador no frontend
   desde a Parte 1) — agora chamada assim que o usuário escolhe o arquivo
   (`onSelecionarArquivo`), comparando o `tabela` detectado com a tabela da própria tela: não bate →
   mensagem clara ("Essa planilha parece ser de 'X', não de 'Y'. Escolha o arquivo correto.") e o
   arquivo é **rejeitado ali mesmo** (nunca chega a "Arquivo selecionado"); bate → segue o fluxo
   normal. `GET /tabelas` segue sem chamador (só `/detectar` voltou a ser usado, com propósito
   diferente do original). Testado ao vivo: planilha de Fornecedores barrada na tela de Clientes com
   a mensagem certa; planilha de Clientes na tela de Clientes segue normal.

Também nesta sessão: botão nativo "Escolher ficheiro" (texto de navegador em PT-PT, não
customizável via CSS/props) trocado por um botão próprio da aplicação, "Escolher planilha" (input
real fica oculto, disparado via `ref.click()`).

### 2026-08-10 — Rotina de Importação de Dados: tela única + detecção automática + Estoque (5ª tabela) + correções

Sessão inteira dentro da Rotina de Importação de Dados (`docs/telas/importacao-dados.md`,
reescrita por completo ao final), em resposta a testes manuais reais do dono do produto com seus
próprios arquivos (`CLIENTES.csv`, `FORNECEDORES.csv`, `PRODUTOS.csv`, `CONTAS_RECEBER.csv`,
`ESTOQUES.csv`).

**Parte 1 — dois bugs pontuais de upload.** `CONTAS_RECEBER.csv` (26,6 MB) batia em `413` — o
limite `spring.servlet.multipart.max-file-size`/`max-request-size` em `application.yml` era
`10MB`, herdado do upload de foto de produto (ADR-013), baixo demais pra planilha de migração.
Achado colateral: um `413` do Tomcat é rejeitado antes do filtro de CORS adicionar seus headers,
então o navegador não consegue nem ler o corpo do erro — vira uma falha "opaca" de rede, e a tela
mostrava "Ocorreu um erro." genérico em vez do motivo real. Corrigido pra `50MB`; horas depois, a
pedido explícito e categórico do dono do produto ("não quero que você coloque limites... nem
limites de tamanho de arquivo, e nem limites do número de linhas"), o limite virou `-1`
(ilimitado) — não há teto de tamanho de arquivo/requisição, e o parser (`ImportacaoCsv.ler`) nunca
teve limite de linhas.

**Parte 2 — Fornecedores: telefone sem validação de formato.** Planilha migrada de outro sistema
traz telefone livre (ramal, número incompleto), e isso não deve rejeitar a linha inteira.
`FornecedorService.criar(FornecedorRequest)` virou uma chamada pra
`criar(req, validarTelefone=true)`; a importação (`FornecedorImportador`) passa `false` — só ela,
o cadastro manual (controller) continua validando os 10–11 dígitos como sempre.

**Parte 3 — Produtos: mudança de estrutura da planilha de origem.** O formato real que o dono do
produto ia importar mudou: em vez do "achatado" (uma linha = uma variação, com
`NOME_GRADE`/`DESCRICAO_COR`/`DESCRICAO_TAMANHO`/EAN/9 colunas de estoque, formato da sessão de
2026-08-08), a planilha real tem **uma linha por produto inteiro**, com `CODIGO_PRODUTO` (código
de ligação com uma 2ª planilha, não é o `id_produto`) e `TAMANHO_1..20` formando a grade direto
nas colunas, sem cor/EAN/estoque nenhum. `ProdutoImportador` foi reescrito: por linha, acha/cria o
produto (dedup por `DESCRICAO+MARCA+REFERENCIA`, igual antes) e resolve a grade a partir da
sequência de `TAMANHO_N` — como a planilha não traz nome de grade nenhum, "já existe" passou a ser
definido pelo **conteúdo** (a sequência ordenada de tamanhos), não por nome:
`GradeService.obterOuCriarPorTamanhos` (novo) busca por igualdade slot-a-slot
(`IS NOT DISTINCT FROM`, NULL-safe) contra `cfg_grade`, e só cria (com nome gerado, ex.
"GRADE 33-47", sufixo numérico se colidir) quando não acha. Testado com o arquivo real de 6.304
linhas: só 8 sequências de tamanho distintas, confirmando que o dedup por conteúdo evita criar uma
grade por produto. Variação (cor/tamanho), EAN e estoque inicial **saíram** desta importação —
ficam pra Entrada de Produtos (ainda não construída, decisão já registrada em 2026-08-08).

**Parte 4 — coluna nova `produto.codigo_importacao` + Estoque, a 5ª tabela.** Pra ligar a planilha
de Estoque à de Produtos, o `CODIGO_PRODUTO` de cada linha da planilha de Produtos precisou ser
guardado em algum lugar — `produto.codigo_importacao text` (editada na `V017__catalogo.sql`, banco
em construção, sem migration nova; `UNIQUE(id_tenant, codigo_importacao)` parcial, mesmo padrão de
`produto_barra.ean`), gravada fora de `ProdutoRequest`/`ProdutoService` (o cadastro manual não tem
esse conceito) direto pelo importador. Com isso, nasceu `EstoqueImportador` (5ª tabela,
`docs/telas/importacao-dados.md` §5): por linha, acha o produto pelo `codigo_importacao`, acha/cria
cor e tamanho (`NOME_COR`/`NOME_TAMANHO`), acha/cria a variação (`ProdutoBarraService.obterOuCriar`
— mesmo service da Emissão de Etiqueta/cadastro manual, já sabe forçar cor/tamanho a `NULL` quando
o produto não usa grade), atualiza o EAN se ainda não tiver, e lança quantidade por empresa
(mapeamento pedido ao usuário) como movimento `AJUSTE`, igual o formato antigo de produto fazia —
linhas duplicadas da mesma variação são agrupadas e somadas antes de lançar.

**Parte 5 — tela única, com detecção automática de tipo de arquivo.** Pedido do dono do produto:
substituir o wizard "escolher tabela → um arquivo de cada vez" por uma tela única, onde o usuário
localiza quantos arquivos quiser e o tipo de cada um é identificado sozinho. Backend:
`ImportadorDeTabela` ganhou um método `default colunasEsperadas()` (deriva as colunas esperadas do
cabeçalho de `modeloCsv()` — nunca duas listas de colunas mantidas à parte);
`POST /api/v1/importacao/detectar` (novo, `ImportacaoCsv.lerCabecalho`, só lê o cabeçalho) compara
por **similaridade de Jaccard** (interseção/união) contra cada tabela cadastrada — não contagem
bruta, pra não favorecer por acaso uma tabela de cabeçalho grande (produto, 33 colunas) sobre uma
pequena (fornecedor, 12); só considera detectado com Jaccard ≥ 0,5 e margem ≥ 0,1 sobre a 2ª
colocada, senão devolve `tabela: null` e a tela pede escolha manual. Testado com os 5 arquivos
reais: **100% identificados corretamente** (inclusive um com erro de digitação no cabeçalho,
`TG_IE` em vez de `RG_IE`, que ainda passou do limiar). Frontend: `ImportacaoDadosPage.tsx`
reescrita — um `CartaoArquivo` por arquivo adicionado (tipo detectado + escolhas prévias daquela
tabela embutidas + botão "Validar" próprio), botão global "Importar" que só libera quando todo
arquivo está validado sem erro, processando em sequência por `ORDEM_IMPORTACAO`
(cliente/fornecedor/produto antes de contas_receber/estoque, mesmo que adicionados fora de ordem).
`GaugeProgresso` (já existente) em toda etapa assíncrona (detectar/validar/importar) — pedido
explícito pra nenhuma etapa parecer travada.

**Achado de design durante o teste — dependência entre arquivos da mesma leva.** A prévia
(dry-run) nunca grava nada, então validar Estoque **antes** de Produtos ter sido confirmado de
verdade sempre mostra "produto não encontrado", mesmo os dois arquivos estando na mesma leva.
Resolvido com uma exceção: quando os ÚNICOS erros da prévia batem com o padrão esperado de
dependência (por texto da mensagem) E o arquivo da dependência também está na lista, a linha conta
como "pronta" (mostra "Pendente — depende de X, conferido na importação") — a checagem de verdade
acontece no momento certo, durante a importação sequencial real.

**Bug real encontrado testando com o arquivo grande de verdade (`ESTOQUES.csv`, 71.156 linhas).**
A 1ª versão da tela nova, ao rodar o lote, só checava se a chamada HTTP não tinha lançado exceção
— mas o sistema é "tudo ou nada" por arquivo: uma chamada com `confirmar=true` e QUALQUER linha
com erro real volta `200 OK` normalmente, só que com `confirmado:false` e **nada persistido**. A
tela mostrava "Importação concluída — 70.799 linhas importadas" sem uma linha sequer ter sido
gravada (confirmado direto no banco: `produto_barra`/`produto_estoque` zerados). Corrigido em
`importarTudo()`: agora confere `relatorio.confirmado` explicitamente e, se `false`, marca o
arquivo como falha, mostra os erros reais e para o lote ali (arquivos já confirmados antes na
mesma sequência continuam gravados — cada um é sua própria transação). No processo, descoberto que
o par de arquivos de exemplo tem **357 linhas com `CODIGO_PRODUTO` que não existe em nenhum dos
dois arquivos** — dado real do lojista (arquivos de amostra parciais), não bug do sistema; com a
regra tudo-ou-nada, o `ESTOQUES.csv` completo nunca vai importar de verdade até essas linhas serem
corrigidas ou removidas.

**Parte 6 — zerar banco + semear dados de teste (pedido à parte, mesma sessão).** Banco de dev
recriado do zero (`docker volume rm niner_pgdata` + migrations reaplicadas), tenant de teste
recriado (`loja-teste-manual`/`teste@niner.dev`/`Teste@123`, ADMIN), e semeado direto via SQL (sem
endpoint de criação — não existe hoje um `POST` de empresa na API, só `GET /api/v1/empresas`): 5
empresas (matriz + 4 filiais, CNPJs com dígito verificador válido, admin com acesso às 5 via
`usuario_empresa`), 5 funcionários/vendedores (CPFs válidos, cargo VENDEDOR, comissão 4,5–5%), e o
plano de contas padrão completo (336 contas, `db/scripts/seed_plano_contas_padrao.sql`, script já
existente desde 2026-07-31).

**Lições de ambiente registradas em memória:** o `curl` deste ambiente Windows/Git Bash é um build
nativo `mingw32` — `-F arquivo=@/tmp/...` (caminho estilo MSYS) falha silenciosamente
(`CURLE_READ_ERROR`/exit 26) ou trava a conexão; precisa de caminho Windows real
(`C:/pasta/arquivo`) tanto pro arquivo de entrada quanto pro `-o` de saída. E: **tempo decorrido
real entre chamadas de ferramenta do agente é sempre maior que a soma das esperas explícitas**
(overhead de rede/captura de tela/raciocínio do próprio agente) — subestimar isso levou a concluir
erroneamente, na 1ª rodada, que uma importação grande tinha terminado rápido; a forma confiável de
confirmar é sempre checar o estado real no banco, nunca só o texto de sucesso da tela.

Suíte de backend depois de tudo: **405/405 verdes**; `tsc --noEmit` limpo. Testado ponta-a-ponta
via navegador com os 5 arquivos reais (upload → detecção → validação → importação → confirmação no
banco), incluindo os dois cenários do bug (sucesso real e falha real). Nada commitado ao fim desta
sessão.

### 2026-08-08 — Cor + Grade (substitui variante linha/coluna) + auditoria de isolamento de tenant (P8)

**Parte 1 — modelo de variação revisado.** Pedido do dono do produto: o par genérico "variante em
linha/coluna" (rótulo livre, configurado por produto) resolvia mal segmentos como calçados e
confecções, onde a variação real é **cor × grade** — grade sendo uma curva de tamanhos nomeada e
**ordenada** (ex. "Grade 36-44" = 36,37,38…44, nessa ordem), com até ~20 tamanhos. Segmentos como
utilidades domésticas, brinquedos, cosméticos, armarinhos e óticas normalmente não variam (1
produto = 1 SKU). Variantes de voltagem (110V/220V) ficaram **fora de escopo** — como o preço
muda, viram produtos separados, não uma variação do mesmo produto.

Decisões fechadas com o dono do produto antes de codificar:
1. Geração em lote de todas as combinações cor×grade de um produto — desejada, mas **adiada**
   pra tela de Entrada de Produtos (individual ou por XML), ainda não construída.
2. Manutenção de grade fica **embutida** na tela de Produto (popup "＋ Gerenciar Grades");
   manutenção de cor fica **fora de escopo por ora**, com uma válvula de escape temporária.
3. Cor é **obrigatória** sempre que o produto tem grade (não opcional/condicional).

**Schema (dentro das migrations existentes — banco em construção, sem migration nova):**
`cfg_variante_linha`/`cfg_variante_coluna` (V017) → `cfg_cor(id_cor, id_tenant, descricao)` /
`cfg_tamanho(id_tamanho, id_tenant, descricao)`; nova `cfg_grade(id_grade, id_tenant, descricao,
id_tamanho1..id_tamanho20)` — 20 colunas fixas nullable, cada uma com FK pra `cfg_tamanho` (não
uma tabela de associação N:N solta, porque a ORDEM é o que importa e o teto é pequeno e estável).
`produto.nome_variante_linha`/`nome_variante_coluna` → `produto.id_grade` (FK `cfg_grade`).
`produto_barra.id_variante_linha`/`id_variante_coluna` → `id_cor`/`id_tamanho` (FKs);
`produto_barra_variacao_uk` virou `UNIQUE(id_produto, id_cor, id_tamanho)`. `cfg_geral`
(V023): `cfg_usa_variante_linha`/`cfg_usa_variante_coluna` (duas flags) → `cfg_usa_cor_grade`
(uma só, `DEFAULT false`). RLS (V024): array de tabelas trocou `cfg_variante_linha`/
`cfg_variante_coluna` por `cfg_cor`/`cfg_tamanho`/`cfg_grade`. Banco recriado do zero (pedido
explícito do dono do produto — "vai ajustar a rotina de importação também, aí eu importo de
novo"), sem preservar dado existente.

**Backend novo:** `CorService`/`CorController`, `TamanhoService`/`TamanhoController`,
`GradeService`/`GradeController` (`com.vetor.niner.catalogo`) — `GradeService` empacota a lista
ordenada de `idsTamanho` recebida nas 20 colunas fixas (`empacotarSlots`) e desempacota de volta
(`resolverTamanhos`), e tem `obterOuCriarPorNome` (achar-ou-criar idempotente, usado pela
importação). `ProdutoBarraService.obterOuCriar` agora resolve `produto.id_grade`, força cor/
tamanho a `null` quando o produto não usa grade, e valida (quando usa) que cor E tamanho vieram
preenchidos e que o tamanho pertence à grade do produto — não a qualquer tamanho do tenant.
`ProdutoService`/`ConfiguracaoGeralService`/`EtiquetaEmissaoService` e ~16 arquivos periféricos
(Devolução, PDV, Importação, Exportação, Configuração de Etiqueta, relatórios de Movimentação/
Estoque, Transferência, Cancelamento de Venda, Pesquisa de Vendas, Histórico do Cliente, CRM)
foram atualizados mecanicamente (renomeação de campos/parâmetros) sem mudança de comportamento
fora do próprio modelo de variação.

**Frontend:** `ProdutoForm.tsx` ganhou o select de Grade + botão "＋ Gerenciar Grades" (novo
`GradeModal.tsx` — lista/cria/edita grade, lista de tamanhos reordenável com "+ Novo tamanho"
inline); `ConfiguracaoGeralForm.tsx` reduziu duas flags a um checkbox; `SelecaoProdutosModal.tsx`
(Emissão de Etiqueta) busca a grade do produto e a lista de cores do tenant, com "+ Nova cor"
inline; `ImportacaoDadosPage.tsx` perdeu o passo de confirmar/editar rótulo de variante detectado
(não existe mais — cor/tamanho têm nome fixo agora). `npx tsc --noEmit` limpo no fim.

**Três bugs de JDBC/Postgres descobertos e corrigidos durante a implementação** (documentados em
detalhe nos comentários dos arquivos afetados, não repetidos aqui): (1) binding de `null` num
parâmetro `Long` contra uma coluna `integer` falha sem `SqlParameterValue(Types.INTEGER, ...)`
explícito; (2) `rs.getObject(coluna, Long.class)` tem o mesmo problema na leitura — a correção é
o idioma já usado no projeto, `rs.getLong(col)` + `rs.wasNull()`; (3) `Optional.map(fn)` (e
`.query(rowMapper).optional()`) colapsa pra `Optional.empty()` quando o valor mapeado é `null`,
não só quando a linha não existe — um produto sem grade (`id_grade IS NULL`) virava 404 por
engano; corrigido extraindo o campo depois de resolver a presença da linha, nunca via `.map()`
encadeado.

**Testes:** `CorGradeTamanhoCrudTest.java` novo (CRUD de cor/tamanho/grade + isolamento entre
tenants); `ProdutoCrudTest`/`ConfiguracaoGeralTest`/`EtiquetaEmissaoCrudTest`/`CrmCrudTest`/
`FechamentoCaixaCrudTest`/`RecebimentoCrediarioCrudTest`/`TransferenciaCrudTest`/`PdvCrudTest`/
`ClienteHistoricoCrudTest` atualizados para o novo modelo.

**Parte 2 — achado de segurança e auditoria de isolamento de tenant (P8).** O teste de isolamento
de `CorGradeTamanhoCrudTest` reproduziu, de forma determinística, uma linha de **outro tenant**
sendo lida/alterada por uma query que dependia só da política RLS — sem `id_tenant =
plataforma.tenant_atual()` explícito no SQL. Testado também contra `CategoriaProdutoService`
(código antigo, de 2026-07-22, não tocado nesta feature) — vazou do mesmo jeito, provando que não
era um problema do código novo, e sim uma lacuna estrutural em como o projeto vinha escrevendo
SQL. Causa raiz não totalmente diagnosticada (suspeita: cache de plano genérico do
Postgres/JDBC interagindo com a qual do RLS entre transações com valores diferentes de
`app.id_tenant`), mas corrigida de forma reproduzível com defesa em profundidade.

Comunicado o achado ao dono do produto — que respondeu com uma diretriz direta: **"o que é de um
tenant só pode ser visto no próprio tenant, não pode vazar pra outros tenant"** — interpretada
(e confirmada pelo contexto) como instrução para auditar o `api/src/main` inteiro, não só corrigir
os arquivos já tocados. Três buscas em paralelo mapearam todo `service`/`controller` do domínio;
cada achado foi corrigido com o mesmo padrão (filtro `id_tenant` explícito em `listar`/`buscar`/
`atualizar`/`excluir`, incluindo `EXISTS` de dependência e `JOIN`s entre tabelas com RLS):
`ProdutoService`, `CategoriaProdutoService`, `FuncionarioService`, `FornecedorService`,
`ClienteService`, `CategoriaClienteService`, `EuController` (lookups de `usuario`/`empresa` por
claim do JWT), e — o de maior risco — `SignupService.login()`, onde um vazamento significaria
**autenticação cross-tenant**, não só leitura indevida. Suíte de backend depois de todas as
correções: **405/405 verdes**, sem regressão. Detalhe completo, o padrão exato de correção e a
lista de arquivos: `docs/infra/isolamento-tenant-rls.md`; convenção permanente registrada em
`CLAUDE.md` ("Conventions to honor when building") para todo service novo nascer já com o filtro.

**Parte 3 — subida do ambiente + limpeza final de rótulos.** Pedido do dono do produto pra testar
manualmente: banco de dev (`niner-db`) recriado (schema desatualizado, já que as migrations
editadas neste dia não tinham sido reaplicadas nele), imagem da API reconstruída com o código da
sessão, tenant de teste recriado (`loja-teste-manual`/`teste@niner.dev`, mesmo padrão de sempre),
seeds de NCM (~51 exemplos — o CSV completo de 10.442 códigos não estava mais disponível
localmente) e Plano de Contas (336 contas) recarregados.

Ao testar a impressão de etiqueta, o dono do produto perguntou diretamente se "variação de
linha"/"variação de coluna" tinham sido substituídas por cor/tamanho na impressão — a resposta
funcional era sim (o campo `DESCRICAO_PRODUTO` já concatenava `variacaoCor`/`variacaoTamanho`
corretamente), mas a pergunta motivou uma varredura completa do repositório atrás de rótulos e
comentários esquecidos pela renomeação mecânica anterior. Achados e corrigidos: cabeçalhos de
coluna ainda dizendo "Variação Linha"/"Variação Coluna" (ou "Variação de Linha"/"Variação de
Coluna") em `RelatorioEstoque.tsx`, `DiferencasEstoque.tsx`, `ClienteHistorico.tsx`,
`ContagemEstoque.tsx`, `EfetivarBalanco.tsx` e `PesquisaProdutoModal.tsx`; três textos de ajuda
(`AjudaDaTela.tsx`, telas de CRM e Emissão de Etiqueta) com a mesma linguagem antiga; comentários
desatualizados em `EtiquetaConfigDtos.java`, `ProdutoDtos.java` (mantido, é nota histórica
correta), `package-info.java` de `crm` e `estoque.relatorioestoque`; e cinco specs
(`docs/telas/pdv.md`, `papeleta-venda.md`, `relatorio-estoque.md`, `contagem-estoque.md`,
`pesquisa-vendas.md`) com exemplos de JSON/tabelas de contrato ainda usando os nomes de campo
antigos (`variacaoLinha`/`variacaoColuna`) — não cobertos pela rodada de documentação anterior
porque essas telas não tinham sido mecanicamente renomeadas (o campo já nascia certo no backend,
só a doc é que ficou pra trás). Um achado à parte, mais sério: um teste
(`RelatorioEstoqueCrudTest.analiticoTrazUmaLinhaPorVariacaoSemTotalizarNoBackend`) verificava
`jsonPath("...variacaoLinha").doesNotExist()` — como esse nome de campo nunca existiu de verdade
(o real é `variacaoCor`/`variacaoTamanho`, sempre presentes no JSON, só `null` quando o produto
não usa grade), a asserção sempre passava sem testar nada; corrigida para checar
`.value(nullValue())` nos dois campos certos. Suíte de backend depois de tudo: **405/405 verdes**
(mesmo total); frontend `tsc --noEmit` limpo.

Nada commitado ao fim desta sessão — aguardando decisão do dono do produto sobre separar o
commit da feature (cor/grade) do commit da correção de segurança (RLS).

### 2026-08-07 — Limite de crédito no PDV + comprovante de vale padronizado com a papeleta

Terceira sessão do dia. O terminal foi fechado sem que o dono do produto pedisse pra documentar
o que tinha sido feito — mas fechar o terminal só encerra a conversa, o código já escrito
continuava intacto e não commitado no working tree. Reconstruído a partir do `git diff` e
registrado aqui a pedido do dono do produto (rodar os testes → commitar → documentar).

1. **Limite de crédito no PDV** (`PdvVendaService.validarLimiteCredito`) — `cliente.limite_credito`
   existia desde a V016 (`db/migration/V016__cadastros.sql`) como campo "pronto para quando o
   crediário existisse", mas nunca era checado. Agora, toda venda com alguma linha `CREDIARIO`
   soma o crediário já em aberto do cliente (mesmo filtro de
   `ClienteHistoricoService.buscarResumoCrediario`: `contas_receber` com `data_recebimento IS
   NULL`) com o crediário desta venda; se o cliente tiver `limite_credito > 0` (≤ 0 = sem limite,
   não bloqueia nada) e a soma passar do limite, a venda é rejeitada. Testes novos em
   `PdvCrudTest.java`.
2. **Erros de confirmação de venda viraram popup, não toast** — `AvisoModal.tsx` (componente
   novo, `role="alertdialog"`, mesmo visual de cabeçalho/✕ das telas de detalhe) substitui o
   `Toast` em `FormaPagamentoModal` para erros de regra de negócio rejeitados pelo servidor
   (limite de crédito, saldo que não fecha etc.) — mensagem importante o bastante pra exigir
   leitura, não deve sumir sozinha em 6s no meio do fluxo de pagamento.
3. **Comprovante de vale-mercadoria padronizado com a Papeleta de Venda** —
   `ComprovanteValeModal.tsx` trocou o layout próprio de 42 colunas (`.comprovante-preview`,
   fonte Courier) pela tabela de 64 colunas / fonte Lucida Console da Papeleta de Venda
   (`.papeleta-preview`/`.papeleta-imprimir`), com cabeçalho/rodapé fixos (só a
   pré-visualização rola) e o botão **"Enviar por WhatsApp"** (mesmo mecanismo do comprovante de
   crediário/papeleta — `comum.arquivocompartilhado`, ver entrada anterior). Como
   `venda_devolucao` não tem vínculo com cliente (devolução é anônima), não há telefone pra
   pré-preencher — o operador digita na hora (`telefoneInicial={null}`). Backend:
   `ItemDevolucaoResponse` ganhou `sku` e `valorTotal` (mesmas colunas de `ItemComprovanteVenda`)
   pra reaproveitar a mesma tabela de itens da papeleta.
4. **`AjudaDaTela`** ganhou a entrada `pdv.tela` — o PDV nunca tinha tido ajuda documentada
   apesar de ser a tela mais usada do sistema.
5. **Menu** ganhou itens "Em construção" (sem tela/rota real, só placeholder `EmBreve`):
   Cobrança de Crediário em Atraso, Módulo Fiscal (NFC-e/NF-e/Cancelamento/Exportação XML) e
   Cancelamento de Devolução de Produtos.
6. **Parâmetros do Sistema** — texto da seção Crediário atualizado: não é mais "Fase 2, ainda
   não implementado" (já é usado pelo Recebimento de Crediário desde 2026-07-29).

Suíte de backend rodada ponta a ponta antes do commit — verde. Commit único: `0228218`.

### 2026-08-07 — CRM: grid de resultado, popup obrigatório de filtros + gauge, botão no título

Continuação da sessão do dia, depois do envio de comprovante por WhatsApp (entrada seguinte). Duas
rodadas de pedidos do dono do produto revisando o fluxo da tela de CRM (`docs/telas/crm.md`), que
desde 2026-08-05 só filtrava e baixava direto, sem mostrar nada na tela.

1. **Grid de resultado (1ª rodada)** — pedido: botão "Localizar Clientes", grid com título fixo e
   ordenação por coluna (como o resto do sistema), total de clientes, scroll só nos dados. Sem
   mudança de backend: `web/src/lib/crm.ts` ganhou `valorOrdenacaoCrm` (valor bruto pra comparar —
   nunca o texto já formatado, que ordenaria `dd/mm/aaaa` como string) e `formatarCelulaCrm`
   (texto de cada célula). A grid usa `.table-wrap.grid-altura-fixa` (mesmo mecanismo das telas de
   Relatório — `max-height: 60vh`, cabeçalho `sticky` já é regra global, `<tfoot>` com o total
   também `sticky`) e o padrão `.th-ordenavel`/`⇅`/`▲`/`▼`/`aria-sort` de sempre, 100%
   client-side (`useMemo`, sem round-trip pra reordenar — a resposta já é o resultado inteiro do
   filtro, pensado pra exportação, sem paginação). Testado ao vivo no Chrome
   (`mcp__claude-in-chrome`): 7195 clientes reais do tenant de teste, ordenação ASC/DESC
   conferida, `overflow-y`/`position: sticky` do cabeçalho e do rodapé confirmados via DOM.
2. **Popup obrigatório + `GaugeProgresso` (2ª rodada, mesmo dia)** — pedido: ao entrar na tela,
   abrir um popup com os filtros e os dados pra geração; ao sair do popup, mostrar uma "gauge" de
   localização de dados e só depois a grid. Os filtros/checklist de colunas saíram do corpo da
   tela e viraram um único popup (`.modal-overlay`/`.modal modal-largo`, sem fechar ao clicar fora
   — só via "Localizar Clientes", de propósito, já que a ação dispara uma busca de verdade) que
   **abre sozinho ao montar a tela**. A "gauge" pedida já existia no projeto —
   `GaugeProgresso.tsx`, criado 2026-08-06 pra Rotina de Importação de Dados (anel de progresso
   indeterminado, sobe suave até ~92% enquanto espera) — reaproveitado sem nenhuma mudança.
   "Alterar Filtros e Colunas" (botão novo) reabre o mesmo popup mantendo a seleção atual.
3. **Botão "Gerar Planilha Excel" pra linha do título (pedido em seguida, mesmo dia)** — saiu da
   seção "Formato da Geração" do corpo (removida) e foi pra `topbar-acoes`, ao lado do ícone de
   ajuda — mesmo lugar de ações primárias como "＋ Novo cliente" nas telas de cadastro. Continua
   desabilitado até localizar clientes ao menos uma vez.

`AjudaDaTela.tsx` (`crm.tela`) atualizada pra descrever o fluxo novo. Sem migration, sem endpoint
novo — mudança 100% frontend. `npx tsc --noEmit` limpo a cada rodada; cada uma testada ao vivo no
Chrome antes de seguir pra próxima.

### 2026-08-07 — Envio de comprovante por WhatsApp (crediário + venda) e layout fixo dos popups

Pedido do dono do produto, explicitamente como **estudo de caso** antes de qualquer código: "na
tela de recebimento de crediário, quando aparece o comprovante de parcelas recebidas, quero uma
opção de envio deste comprovante, em PDF, por WhatsApp". A sessão passou por uma análise técnica
completa, duas rodadas de refinamento pedidas pelo próprio dono do produto (custo/segurança e
confirmação do destinatário), e por fim a extensão da mesma capacidade pra Papeleta de Venda.

1. **Análise (sem código) de 3 caminhos técnicos** — link `wa.me` (zero infra, mensagem com link,
   sem anexo de verdade), WhatsApp Cloud API oficial (anexo real, mas exige conta comercial,
   número por tenant, template aprovado pela Meta, custo por conversa) e automação não-oficial
   (Baileys/whatsapp-web.js, descartada — viola Termos de Uso, risco de banimento). O dono do
   produto escolheu **`wa.me`**, com o destinatário sempre sendo `cliente.telefone` (não
   `cliente.whatsapp`, que é um handle, não telefone).
2. **Hospedagem sem custo** — proposta inicial de bucket GCS foi rejeitada pelo dono do produto
   ("vai ter custo"); resolvido com um **cache em memória dentro da própria API**
   (`comum.arquivocompartilhado`, pacote novo): token UUID aleatório, expiração configurável (24h
   padrão, `niner.arquivo-compartilhado.expiracao-horas`), dois mecanismos de limpeza (sob
   demanda no `GET` + varredura `@Scheduled` a cada 1h — exigiu `@EnableScheduling` e um bean
   `Clock`, nenhum dos dois existia antes). PDF gerado no **navegador** (reaproveita o `jsPDF` já
   usado por "Salvar PDF", sem duplicar a montagem do layout em Java).
3. **Achado de segurança, reportado e corrigido na mesma sessão** — ao perguntar diretamente "vai
   consumir muita memória ou tem algo aberto que permite invasão?", a resposta honesta revelou um
   buraco real: nada limitava quantos uploads um tenant podia empilhar, e como o cache é
   compartilhado entre todos os tenants do processo, isso permitia um "vizinho barulhento" (um
   tenant só derrubando a API por `OutOfMemoryError` pra todos). Corrigido com **limite de 20
   arquivos por tenant** — ao chegar o 21º, o mais antigo daquele mesmo tenant é apagado. A
   contagem soma os quatro fluxos que usam o mecanismo (papeleta de venda, reimpressão de
   papeleta, comprovante de crediário, reimpressão de crediário) — não é 20 por fluxo. Detalhe
   completo (incluindo os gaps que ficaram de fora, como teto de memória total e log de auditoria):
   `docs/infra/compartilhamento-arquivo-temporario.md`.
4. **Popup de confirmação do destinatário, pedido numa rodada seguinte** — o botão nunca dispara o
   envio direto: sempre abre `EnviarWhatsAppModal.tsx` (componente genérico, reaproveitável),
   pré-preenchido com o celular do cliente mas **editável**, com validação só ao clicar "OK" (erro
   inline, mesmo padrão de `TesteImpressaoModal.tsx`). Só depois de confirmado é que o PDF é
   gerado/enviado.
5. **Estendido pra Papeleta de Venda** (mesma sessão, pedido explícito) — mesmo botão, mesmo
   popup, no `ComprovantePapeletaModal.tsx` (cobre também a Reimpressão de Papeleta de Venda, já
   que é o mesmo componente). Os dois endpoints de comprovante (`GET .../recebimento-crediario/
   {id}/comprovante` e `GET .../pdv/vendas/{id}/comprovante`) ganharam o campo `telefoneCliente`.
6. **Layout fixo nos 4 popups de comprovante** (pedido separado, mesma sessão) — título e rodapé
   de botões (Fechar/Enviar por WhatsApp/Salvar PDF/Imprimir) ficam sempre visíveis; só a
   pré-visualização rola por dentro quando o comprovante é longo. Mesmo mecanismo já usado em
   `CancelamentoVendaModal.tsx` (`.modal` em coluna flex, `overflow:hidden`; cabeçalho/rodapé
   `flexShrink:0`; miolo `overflow-y:auto`).

**Backend:** pacote novo `comum.arquivocompartilhado` (`ArquivoCompartilhadoService`/`Controller`/
`Dtos`) — `POST /api/v1/arquivos-compartilhados` (autenticado, qualquer papel) + `GET
/api/publico/arquivos-compartilhados/{token}` (público, sem JWT — o cliente final não tem conta no
sistema). `RecebimentoCrediarioDtos`/`Service` e `PdvDtos`/`PdvVendaService` ganharam
`telefoneCliente` na resposta do comprovante (mesma coluna `cliente.telefone` de sempre, nenhuma
migration nova). **8 testes novos** (`ArquivoCompartilhadoCrudTest`, incluindo os 2 do limite de
20/tenant) — suíte completa do backend verde (incluindo `RecebimentoCrediarioCrudTest`/
`PdvCrudTest`/`ValeMercadoriaCrudTest`, que exercitam os DTOs alterados).

**Frontend:** `lib/whatsapp.ts` (monta o link `wa.me`) e `lib/compartilhamento.ts` (upload +
monta a URL pública) novos; `lib/comprovante.ts` ganhou `gerarBlobComprovante`/
`gerarBlobComprovanteVenda` (mesmo motor de PDF de sempre, extraído pra também devolver Blob, sem
duplicar o layout); `components/EnviarWhatsAppModal.tsx` novo; `IconeWhatsapp` novo (avião de
papel, Heroicons outline — genérico de propósito, não o logotipo do WhatsApp, consistente com o
resto do design system). `tsc --noEmit` limpo em cada rodada.

**Verificação:** ambiente de dev reconstruído (`docker compose up -d --build api`) a cada mudança
de backend; testado com clientes reais do banco de dev (celular cadastrado) e com o próprio dono
do produto confirmando o comportamento do popup de confirmação e do limite de 20/tenant.

**Documentação:** pedido explícito "documente e memorize todas as implementações feitas" —
`docs/infra/compartilhamento-arquivo-temporario.md` (spec nova), `docs/telas/
comprovante-recebimento-crediario.md` e `docs/telas/papeleta-venda.md` (seções novas + JSON de
exemplo atualizado), `AjudaDaTela.tsx` (3 entradas atualizadas: `financeiro.
recebimentocrediario.tela`, `financeiro.reimpressaorecebimentocrediario.tela`, `vendas.
reimpressaopapeleta.tela`), este registro.

### 2026-08-06 — Reimpressão de Papeleta de Venda e de Recebimento de Crediário (3ª sessão do dia)

Fecha os dois itens que tinham entrado em Implementações Futuras horas antes, no mesmo dia
([[project_papeleta_venda]]/entrada anterior desta linha do tempo). Nenhuma das duas precisou de
endpoint novo — as duas telas são 100% frontend, reaproveitando busca já existente:

- **Reimpressão de Papeleta de Venda** — filtra por Nº da venda, ou por período + cliente
  (reaproveita `GET /api/v1/vendas/pesquisa`, o mesmo endpoint da Pesquisa de Vendas). Abre a
  papeleta com `reimpressao` ligado: título vira "REIMPRESSÃO DE PAPELETA DE VENDA" e ganha
  "Impresso em: dd/mm/aaaa hh:mm" no final.
- **Reimpressão de Recebimento de Crediário** — filtra por cliente (obrigatório, mesma regra do
  Estorno de Crediário) + período (reaproveita `GET /recebimento-crediario/estornos`, mesmo
  endpoint do Estorno, só sem a ação de estornar). Mesmo tratamento de título/rodapé no
  comprovante existente.

Ambas reaproveitam o componente de popup já existente (`ComprovantePapeletaModal`/
`ComprovanteRecebimentoModal`) com uma prop `reimpressao` nova — as funções de montagem do papel
(`montarLinhasComprovanteVenda`/`montarLinhasComprovante`, `web/src/lib/comprovante.ts`) ganharam
um segundo parâmetro opcional em vez de duplicar a lógica de layout.

Pedido seguinte, mesmo dia: os dois itens de menu foram **agregados num submenu "Reimpressões"**
(mesmo padrão de "Caixa"/"Cancelamentos" dentro de Frente de Loja) em vez de ficarem soltos.

Sem testes automatizados (typecheck limpo + teste manual com venda/recebimento reais do tenant
de teste — feature 100% frontend, sem lógica de backend nova pra testar). Commitado/pushado
(`885d114`).

### 2026-08-06 — Papeleta de Venda (PDV) + Guia de Transferência + correções na Importação de Dados

Sessão de continuação do mesmo dia. Três frentes:

**Papeleta de Venda** (`docs/telas/papeleta-venda.md`) — o PDV efetivava a venda (F5) mas nunca
teve nenhum comprovante impresso; mesma lacuna que existia no Recebimento de Crediário antes do
comprovante de 2026-07-30. Popup automático pós-F5, impressão térmica 80mm, mas **64 colunas**
(mockup exato do dono do produto), maior que as 42 já usadas no comprovante de crediário/vale —
resolvido com fonte `Lucida Console` (pedido explícito, fonte do console do Windows, desenhada
pra ficar legível em tamanho pequeno) em vez de espremer o layout; o PDF não consegue seguir essa
fonte (proprietária, sem TTF embutível) e cai pra courier ~5pt, só backup — o botão "Imprimir" é
o caminho recomendado. `venda` ganhou a coluna `id_caixa` (migration `V025`, editada) porque só
pagamento que não é CREDIARIO grava em `caixa_detalhe` — sem isso não dava pra saber o "Operador"
numa venda 100% crediário. Duas rodadas de ajuste no mesmo dia: removida a totalização de itens
da linha "TOTAL A PAGAR" (não pedida), e novo bloco "PARCELAS A VENCER DE CREDIARIO" (37 colunas,
mockup próprio) sempre que a venda tiver pagamento nessa categoria, com o rótulo "VALOR A PAGAR
EM" (não "VALOR PAGO EM") nessas linhas — dinheiro que ainda não circulou.

**Guia de Transferência de Produtos** (`docs/telas/guia-transferencia.md`) — mesma lacuna do lado
de Transferência de Produtos: transferência gravada, mas nada impresso pra acompanhar a
mercadoria. Diferente da papeleta: **folha A4** (escolha explícita do dono do produto — lista de
itens pode ser longa, documento é pra arquivar), mesmo mecanismo do Fechamento de Caixa, com
linhas de assinatura no rodapé ("Conferido por (Origem)" / "Recebido por (Destino)"), únicas no
projeto. 100% frontend — a resposta de `GET /api/v1/estoque/transferencias/{id}` já trazia tudo.
No mesmo pacote, corrigido um bug relacionado na mesma tela: o X às vezes reabria o popup "Nova
Transferência" em vez de fechar — causa raiz era o popup de escolher destino fechando com
`navigate('/estoque')` (empilha histórico) em vez de `navigate(-1)` (convenção do resto do
sistema), deixando uma entrada fantasma que o X da lista (também `navigate(-1)`) podia revisitar.

**Importação de Dados** — dois bugs reais achados por teste manual com a validação de verdade:
NCM inexistente (ou em formato inválido, ex. planilha com pontuação `6402.99.90`) travava a linha
inteira por violação de FK — agora entra como vazio, mesma filosofia de "não é motivo pra barrar
a importação inteira"; e `PRECO_OFERTA = "0"` (comum em export de outro sistema, zero em vez de
célula vazia) disparava por engano a exigência das duas datas de oferta — normalizado pra "não
preenchido" antes da validação tudo-ou-nada.

Também entraram 2 itens novos em Implementações Futuras: **Reimpressão de Papeleta de Venda** e
**Reimpressão de Recebimento de Crediário** (nenhuma das duas tem tela de "ver comprovante depois
de fechar o popup" ainda).

Sem testes automatizados em nenhuma das três frentes (compilação/typecheck limpos + testes
manuais via API real com dados reais do tenant de teste, sempre revertidos depois — não suíte
JUnit). Commitado/pushado (`488935d`).

### 2026-08-06 — Rotina de Importação de Dados + Rotina de Exportação de Dados (grupo Configurações)

Duas features novas, irmãs uma da outra, as primeiras do grupo **Configurações** voltadas pra
migração/integração de dados em vez de parametrização do sistema.

**Importação de Dados** (`docs/telas/importacao-dados.md`) — carga inicial por **CSV**, `ADMIN`-only,
cobrindo 4 tabelas: `cliente`, `fornecedor`, `produto` (com variação/SKU e saldo inicial de
estoque) e `contas_receber` (só crediário). Precedida de uma sessão longa de descoberta (estudo
do schema completo do banco pra levantar todas as tabelas candidatas, depois planilhas `.xlsx`
de exemplo geradas com `write-excel-file/node` — hoje obsoletas, o botão "Baixar modelo" da
própria tela gera o `.csv` real) e negociação linha a linha das regras de negócio com o dono do
produto. Decisões centrais: categoria/plano de contas/carteira de crediário são **uma escolha
única pro arquivo inteiro** (não uma coluna por linha); dedup de cliente/fornecedor por CPF/CNPJ
já existente (reaproveita, não duplica); produto usa formato achatado (`DESCRICAO+MARCA+REFERENCIA`
agrupa variações da mesma linha, unificação por par linha/coluna soma estoque, divergência de
dado resolvida pela linha de maior preço de venda); `contas_receber` **não exigiu nenhuma
migration** — o problema de `id_venda NOT NULL` sem importar `venda` foi resolvido agrupando
parcelas por `(cliente, empresa)` e criando uma **venda sintética** por grupo (`data_venda` =
menor vencimento), em vez da alternativa cogitada de tornar a coluna nullable; parcela já paga
nasce marcada como recebida mas **nunca** gera `caixa_detalhe` (lançamento retroativo quebraria o
Fechamento de Caixa). Prévia (dry-run) implementada sem duplicar lógica: cada importador roda o
código de verdade dentro de uma transação e lança uma exceção de propósito no fim quando
`confirmar=false`, só pra acionar rollback automático do Spring. Migration nova, `V030`, só a
tabela de auditoria `importacao_lote` (nenhuma tabela existente foi alterada). Menu movido de
"Implementações Futuras" pra "Configurações".

**Exportação de Dados** (`docs/telas/exportacao-dados.md`) — irmã "de saída", bem mais simples
(só leitura, sem escolha prévia, sem migration): 9 tabelas (Empresas, Clientes, Fornecedores,
Funcionários, Contas a Receber/Recebidas, Contas a Pagar/Pagas, Código de Barras, Plano de
Contas, Estoque), cada uma uma única consulta SQL sem parâmetro, com os próprios aliases de
coluna do `SELECT` já em português virando o cabeçalho da planilha direto (datas e booleanos já
formatados em SQL). Duas das fontes (`contas_pagar`, `produto_barra`) não tinham nenhuma tela ou
consulta no sistema até esta feature. Planilha gerada 100% no navegador com `write-excel-file`,
mesmo padrão já usado no CRM — zero dependência nova.

Ajustes de UI no mesmo dia, pós-teste manual: botões de transição de etapa da Importação
padronizados em tamanho/alinhamento (`.wizard-acoes`, `min-width` fixo) e correção de um bug em
que o subtítulo da tabela escolhida ficava "grudado" embaixo do título da tela ao clicar Voltar
(o botão trocava de etapa sem limpar o estado). Também entraram 3 itens placeholder em
"Implementações Futuras": **DRE**, **Fluxo de Caixa** e **Lucratividade**.

Sem testes automatizados ainda em nenhuma das duas features (compilação/typecheck limpos e
smoke test manual de endpoint, não suíte JUnit).

### 2026-08-05 — Emissão de Etiqueta: SKU criado na hora, obrigatoriedade por produto, Limpar Lista

Quinta sessão do dia — 3 ajustes pontuais sobre a tela de Emissão de Etiqueta de Produtos
construída na sessão anterior (ver `docs/telas/etiqueta-emissao.md`, seção "Rodada 2", pra detalhe
completo).

1. **Modo Individual deixa de exigir SKU pré-cadastrado.** Antes, só produtos que já tinham
   `produto_barra` apareciam na busca (útil pra imprimir de novo, inútil pra um produto recém
   cadastrado). Agora qualquer produto ativo aparece; ao escolher linha/coluna e clicar "Adicionar
   à Lista", o backend acha a variação se já existir ou **cria na hora** (`gerar_ean13_interno()`).
2. **Linha/coluna viram obrigatórias no modo Individual quando o PRODUTO usa aquela dimensão** —
   resolvido como configuração **por produto** (`produto.nome_variante_linha`/`nome_variante_coluna`
   não nulos), não a flag global de tenant que só controla o cadastro de Produto. Rótulo do
   seletor usa o nome real da dimensão (ex.: "COR *", "TAMANHO *").
3. **Botão "Limpar Lista"** na grade — esvazia todos os produtos selecionados de uma vez.

**Novo serviço de domínio, não específico desta tela:** `ProdutoBarraService`/`ProdutoBarraDtos`
(`com.vetor.niner.catalogo`, não `etiquetaemissao`) — o `CLAUDE.md` já antecipava esse
nome/método exato desde 2026-07-22 ("quando for construir produto_barra/o serviço de variação,
chame `gerar_ean13_interno()` explicitamente no `ProdutoBarraService.criar()`"). Endpoint novo
`GET /api/v1/etiqueta-emissao/variantes` (catálogo inteiro do tenant) substitui o antigo `GET
.../produtos/{id}/variacoes` (removido). Testado ao vivo: produto com variação exigiu os dois
seletores e gerou SKU novo (`9001000000305`) ao adicionar; produto sem variação não mostrou
seletor nenhum; Limpar Lista esvaziou a grade. `AjudaDaTela.tsx` corrigido (texto antigo dizia que
precisava de SKU pré-cadastrado). **14 testes** de `EtiquetaEmissaoCrudTest` (era 10), **386** no
backend inteiro.

### 2026-08-05 — Emissão de Etiqueta de Produtos (1ª implementação real da área)

Quarta sessão do dia. Até então só um placeholder "Em construção" desde 2026-08-04. Detalhe
completo em `docs/telas/etiqueta-emissao.md`. Resumo:

1. **3 formas de selecionar produtos**, cada uma resultando no mesmo formato de linha
   (`ProdutoEmissaoResponse`, compatível com o `ProdutoExemplo` já usado em Configuração de
   Etiqueta): **Individual** (busca 1 produto, refina por variação, digita quantidade), **Por
   Entradas** (período/fornecedor/nota fiscal — ao menos 1 obrigatório — quantidade vem da soma de
   compras) e **Por Estoques** (empresa obrigatória + categoria opcional — quantidade vem do saldo
   em estoque).
2. **Grade 100% local** (excluir item, editar quantidade) — sem endpoint próprio, sem
   persistência, só monta a sequência de impressão no clique de "Emitir Etiquetas".
3. **Escolha do modelo antes de imprimir** — reaproveita 100% os endpoints já existentes de
   Configuração de Etiqueta, zero código novo nesse passo.
4. **Impressão em lote generalizada** — o mecanismo do "Testar Impressão" (que imprime N cópias de
   UM produto) virou "imprimir 1 produto por posição, cada posição podendo ser diferente": a grade
   é achatada numa sequência (`montarSequenciaImpressao`) e distribuída pelas colunas do rolo,
   cada etiqueta renderizando com o produto daquela posição específica.

⚠️ **Achado confirmado por pesquisa antes de codificar:** nenhum service do sistema grava
`produto_movimento_mestre.tipo_movimento='COMPRA'` ainda ("Entrada de Produtos por Compra" é outra
área de Implementações Futuras, não construída) — o modo "Por Entradas" é uma consulta real contra
o schema existente, só não vai ter dado até aquela tela (ou carga manual) existir; documentado
explicitamente no código e no `AjudaDaTela` pra não parecer bug. Saiu de "Implementações Futuras"
e entrou no grupo "Relatórios" do menu (ícone `IconeEtiqueta`). Backend novo
(`com.vetor.niner.configuracao.etiquetaemissao`), sem tabela nova, **10 testes**
(`EtiquetaEmissaoCrudTest`), **382** no backend inteiro. Testado ao vivo ponta a ponta, inclusive
conferindo via JS que cada etiqueta da sequência impressa tem o SKU certo na posição certa (não só
a contagem total).

### 2026-08-05 — CRM: +3 colunas de saída (Valor Total Comprado, Ticket Médio, Dias sem Comprar)

Continuação da terceira sessão do dia, logo após a primeira implementação do CRM (ver entrada
abaixo). Pedido separado: mais 3 colunas na planilha exportada — **Valor Total Comprado**,
**Ticket Médio** (`valor_total / nº_de_compras`, com `NULLIF` no divisor) e **Nº de Dias sem
Comprar** (`current_date - última_compra`). Total de colunas de saída: 8 → 11 (ver
`docs/telas/crm.md`, Item 3, pra detalhe completo).

**Bug real achado pelos próprios testes automatizados:** `rs.getObject(coluna, Long.class)` pra
ler `dias_sem_ultima_compra` (resultado de subtração de datas, tipo `integer` no Postgres) fazia a
chamada inteira falhar com **HTTP 409** e uma mensagem de exclusão ("Registro em uso... não pode
ser excluído" — pensada pro fluxo de `DELETE`, não `GET`), porque a coerção do driver JDBC do
Postgres pra `Long` via `getObject(String, Class)` não é confiável pra `int4`. Corrigido com o
padrão já usado em `RelatorioContasReceberService.getLongOuNulo`: `rs.getLong(col)` +
`rs.wasNull()`. **15 testes** de CRM (era 14), **372** no backend inteiro (nessa época — a
contagem citada nas entradas seguintes já reflete as sessões posteriores).

### 2026-08-05 — CRM (1ª tela real da área) + EAN-13/quadro/popup na Config. de Etiqueta

Terceira sessão do dia. Dois blocos: fechamento de pendências da Configuração de Etiqueta (3
pedidos pontuais) e a primeira implementação de verdade da área **CRM** (até então só um
placeholder no menu, ver entrada de "Implementações Futuras" abaixo).

1. **Configuração de Etiqueta — EAN-13 de verdade + quadro no Teste de Impressão + popup de
   propriedades** (detalhe completo em `docs/telas/configuracao-etiqueta.md`):
   - Código de barras trocou de `CODE128` pra **`EAN13`** de verdade em `CampoEtiquetaVisual.tsx`
     — seguro porque `sku` sempre vem de `gerar_ean13_interno()` (13 dígitos, dígito verificador
     correto por construção). De quebra, achado e corrigido um bug real: o valor de exemplo
     (`VALOR_BARRA_EXEMPLO`) tinha o dígito verificador **errado** desde que foi escrito — nunca
     importava com `CODE128` (que não valida), mas quebraria (ficaria em branco) com `EAN13`
     (que valida e recusa desenhar se não bater). Corrigido de `'9000000000017'` pra
     `'9000000000018'`.
   - Cada etiqueta do **Teste de Impressão** ganhou um **quadro** (borda 1px preta) ao redor —
     guia de corte pra testar em papel comum antes do rolo de etiqueta de verdade. `box-sizing:
     border-box` (global) garante que a borda fica por dentro do tamanho configurado.
   - O **painel de propriedades do campo** (posição/tamanho/fonte/estilo) virou **popup**
     (`.modal-overlay`/`.modal`, mesmo padrão do resto do sistema) em vez de ficar fixo ao lado do
     canvas — pedido explícito pra melhor visualização e menos disputa de espaço. A sincronização
     ao vivo com o canvas/prévia do rolo continua funcionando com o popup aberto.
2. **CRM — primeira tela real da área** (`docs/telas/crm.md`): filtra clientes por perfil (nome
   inicial/final por primeira letra, gênero, idade, aniversário mês/dia, categoria, período de
   cadastro, dias sem comprar) e por produtos comprados (período, categoria, variação de linha/
   coluna — exige que todos batam na MESMA linha de venda), e exporta a lista em planilha Excel
   (`.xlsx`, dados sempre completos: nome, nascimento, gênero, e-mail, celular, primeira/última
   compra, nº de compras). Tela sem scroll (pedido explícito) — os dois grupos de filtro (13
   campos ao todo) viraram popup, só o checklist de 8 colunas de saída fica inline. Saiu de
   "Implementações Futuras" e entrou no grupo "Relatórios" do menu. Backend novo
   (`com.vetor.niner.cadastros.crm`, 14 testes) sem tabela nova — só lê schema já existente.
   Biblioteca de Excel: **`write-excel-file`**, não `xlsx`/SheetJS (vulnerabilidade alta sem
   correção disponível via npm) nem `exceljs` (moderada, transitiva, e ~100 pacotes a mais só pra
   um caso de uso write-only) — zero dependências novas no `npm audit`.

### 2026-08-05 — Revisão da Configuração de Etiqueta + correção de dois bugs reais em Produto

Sessão de ajustes finos sobre a Configuração de Etiqueta de Produtos (nasceu no dia anterior) mais
duas correções de bug encontradas testando o cadastro de Produto na prática.

1. **Configuração de Etiqueta — 6 ajustes, todos testados ao vivo no navegador** (ver
   `docs/telas/configuracao-etiqueta.md`, seção "Ajustes de 2026-08-05", pra detalhe completo):
   - Campos reduzidos de **10 pra 4**: `EAN_BARRAS`/`PRECO_OFERTA` removidos por completo (sem uso);
     `MARCA`/`REFERENCIA`/`VARIANTE_LINHA`/`VARIANTE_COLUNA` deixaram de ser campos posicionáveis
     próprios e passaram a ser **concatenados automaticamente** dentro de `DESCRICAO_PRODUTO`
     (`montarDescricaoImpressa`, `web/src/lib/etiquetaConfig.ts`) — descrição + marca + referência +
     variação de linha + variação de coluna, nessa ordem, pulando qualquer pedaço vazio ou que já
     apareça na descrição (evita "ADIDAS ADIDAS" repetido). Objetivo: um campo só pra arrastar em
     vez de cinco. Enum `campo_etiqueta` editado na própria `V029` (banco em construção, nenhuma
     config salva usava os 6 valores removidos — checado antes de editar).
   - Texto que não cabe na largura do campo agora **quebra linha** em vez de cortar com "...".
   - Prévia do rolo completo passou a **atualizar em tempo real** enquanto o usuário digita
     posição/tamanho no painel de propriedades (antes só ao arrastar com o mouse).
   - **Redimensionar por mouse** — alça no canto do campo selecionado, arrastável (antes só dava
     pra mudar tamanho digitando o número).
   - Código de barras desproporcional entre o editor grande e a prévia pequena — `viewBox` do
     `jsbarcode` tinha proporção inconsistente entre as duas escalas; corrigido com
     `preserveAspectRatio="none"`.
   - Busca de "produto de exemplo" só trazia produtos com variação/SKU cadastrado (tela que ainda
     não existe) — quase sempre vazia. Query trocada de `produto_barra` (INNER JOIN) pra `produto`
     (LEFT JOIN) — todo produto ativo aparece agora, com ou sem variação.
2. **Bug real corrigido: cadastro de Produto rejeitava margem acima de 100%** com erro genérico
   "Invalid request content." (mesma mensagem do Spring pra JSON malformado, sem detalhe por campo
   — difícil de diagnosticar). Causa: `percentualVenda` tinha `@DecimalMax("100")` em
   `ProdutoDtos.java`; um calçado com markup de 150% (custo R$125 → venda R$312,50) estourava a
   validação. Confirmado com o dono do produto que margem >100% é caso de uso real; `@DecimalMax`
   removido (coluna já é `numeric(5,2)`, sem mudança de schema). Ver `docs/telas/produto.md`,
   Particularidade 4.
3. Suíte de backend (`EtiquetaConfigCrudTest`, 3 casos ajustados pra usar `PRECO_VENDA` em vez do
   `MARCA` removido como campo de exemplo nos testes) segue verde.

### 2026-08-05 — Configuração de Etiqueta: layout compacto (3 colunas) + botão Testar Impressão

Segunda sessão do dia sobre a Configuração de Etiqueta de Produtos — dois pedidos novos do dono
do produto, cada um implementado e testado ao vivo antes do seguinte (detalhe completo em
`docs/telas/configuracao-etiqueta.md`, seção "Ajustes de 2026-08-05, rodada 2").

1. **Máximo de colunas do rolo: 6 → 4**, em 3 camadas — `CHECK` do banco (`V029`, editada no
   lugar), validação do backend (`EtiquetaConfigService`, mensagem "entre 1 e 4") e o `<select>`
   do frontend. 11 testes de `EtiquetaConfigCrudTest` seguem verdes.
2. **Cabeçalho do formulário reorganizado em 3 colunas lado a lado** ("Rolo e Etiqueta" / "Bordas"
   / "Posição das Colunas", cada uma com os campos empilhados dentro), a partir de um mockup ASCII
   do dono do produto — reduz bastante a rolagem vertical da tela. **Bug pego no teste ao vivo:**
   o cabeçalho usava `col-10` (grid de 12 colunas, §3.7) pro campo Nome, mas essa classe nunca
   existiu em `styles.css` (só ia até `col-9`, depois pulava pra `col-12`) — o campo renderizava
   com ~110px em vez da largura esperada. Corrigido adicionando `.col-10` ao grid.
3. **Botão "Testar Impressão"** (novo) — imprime N cópias do layout configurado, em escala física
   real, direto no diálogo de impressão do navegador, sem depender da futura tela de Emissão
   (que precisa de produto/estoque reais). Pede a quantidade num modal (`TesteImpressaoModal.tsx`,
   1 a 200), distribui pelas colunas configuradas uma linha por vez (`linhasParaImprimir`, mesmo
   jeito que o rolo físico avança), reaproveita `CampoEtiquetaVisual.tsx` (mesmo componente do
   editor/prévia) com escala `96px/25,4mm` (equivalência real que o navegador usa ao imprimir, não
   o zoom de tela do editor), e injeta um `@page` dinâmico (`size: ${larguraRolo}mm auto`) em
   runtime — a largura do rolo é configurável por tenant, diferente do 80mm fixo do Comprovante de
   Crediário/A4 do Fechamento de Caixa, que já usam a mesma técnica de isolamento visual
   (`styles.css`, `.etiqueta-imprimir`). Testado ao vivo interceptando `window.print` (evitar
   travar a automação): distribuição 3+3+2 pra 8 etiquetas/3 colunas confirmada, largura em px
   batendo exatamente com o mm configurado (110mm → 415,748px), limpeza do `<style>` injetado e do
   conteúdo confirmada depois do `afterprint`.

### 2026-08-04 — Tela de Configuração de Etiqueta de Produtos (editor visual de arraste)

Em cima do modelo de dados (V029, entrada anterior desta linha do tempo): a tela sai de
Implementações Futuras e entra em Configurações (ADMIN-only, junto de Usuários/Parâmetros do
Sistema). CRUD padrão (`docs/telas/configuracao-etiqueta.md`) mais um editor visual sem
precedente no projeto — o usuário digita as dimensões do cabeçalho e **arrasta** os campos pra
posicionar dentro de uma etiqueta desenhada em escala real de milímetros (`EditorEtiquetaCanvas.tsx`
— régua mm, zoom, paleta de campos, Pointer Events nativos sem biblioteca de drag-and-drop, snap
de 0,5mm, nudge por teclado, aviso visual quando um campo invade a borda configurada), painel de
propriedades (`PainelPropriedadesCampo.tsx`) e código de barras renderizado de verdade
(`jsbarcode`, dependência nova) — inclusive com um botão "Escolher produto de exemplo" que troca
os placeholders genéricos por dados reais de um produto do catálogo.

**2 bugs pegos só no teste manual ao vivo, nenhum teste automatizado cobria:** `import * as
JsBarcode from 'jsbarcode'` tipava certo no `tsc` mas quebrava em runtime no Vite (`TypeError:
JsBarcode is not a function` — trocado pra `import JsBarcode from 'jsbarcode'`); e mutações
rápidas em sequência (2 cliques rápidos na paleta, ou segurar uma seta do teclado) podiam se
perder, porque a função de mutação lia o array de campos fechado numa prop desatualizada em vez
de sempre partir do estado mais recente — corrigido trocando pra um updater funcional (padrão do
React), verificado ao vivo via console (2 cliques síncronos passaram a resultar nos 2 campos, não
mais só 1; nudge de teclado em sequência passou a somar certo, 5,5mm em vez de só 5mm).

Testado ao vivo ponta a ponta (criar com produto de exemplo real → salvar → visualizar → excluir).
Suíte de backend inteira e `tsc --noEmit` limpos. Detalhe completo em
`docs/telas/configuracao-etiqueta.md`.

### 2026-08-04 — Modelo de dados de Configuração de Etiqueta de Produtos (V029)

Início da rotina de "Configuração de Etiqueta de Produtos" (uma das 9 áreas de Implementações
Futuras, mesma sessão). Só o schema nesta primeira etapa — a tela ainda não foi construída, ver
`docs/telas/configuracao-etiqueta.md`. Três decisões de escopo confirmadas com o dono do produto
antes de desenhar as tabelas: configuração **por tenant** (não por empresa, mesma lógica de
`produto`), **várias configurações nomeadas** (cadastro completo, não singleton — bate com as 2
telas separadas do menu, Configuração e Emissão), e **posicionamento livre x/y** de cada campo
dentro da etiqueta desde a 1ª fase (não só empilhamento automático).

3 tabelas novas (`db/migration/V029__cfg_etiqueta.sql`, todas com RLS/P8): `cfg_etiqueta_config`
(cabeçalho — rolo/etiqueta/bordas em mm, `numero_colunas` `CHECK` 1 a 6), `cfg_etiqueta_coluna`
(filha — posição inicial de cada coluna no rolo físico, valor explícito por coluna, não calculado
por fórmula, pra suportar rolos com espaçamento irregular), `cfg_etiqueta_campo` (filha — qual
campo aparece, posição x/y relativa à própria etiqueta, fonte/tamanho/negrito/alinhamento,
`fundo_preto` — fundo preto/letra branca vs. padrão letra preta/sem fundo). O desenho foi
validado contra um PDF real de etiqueta impressa fornecido pelo dono do produto (nome da loja em
fundo preto no topo, descrição, variação cor/tamanho, código de barras com dígitos legíveis
embaixo, preço em destaque) — confirmou o padrão fundo-preto/fundo-branco por campo e que o
código impresso é o `SKU_BARRAS` interno (EAN-13 de 13 dígitos, bate com `gerar_ean13_interno()`).

**Novo ENUM `campo_etiqueta`** (10 valores fixos, mesmo idioma de `tipo_movimento`): mapeiam pra
colunas já existentes — `NOME_EMPRESA` (`empresa.cfg_nome_etiqueta`, existe desde V014, nunca
lido por nenhuma tela até agora), `DESCRICAO_PRODUTO`/`MARCA`/`REFERENCIA`/`PRECO_VENDA`/
`PRECO_OFERTA` (`produto`), `SKU_BARRAS`/`EAN_BARRAS` (`produto_barra`), `VARIANTE_LINHA`/
`VARIANTE_COLUNA` (`cfg_variante_linha`/`cfg_variante_coluna`). Nenhuma tabela nova para esses 7
campos que já existiam. `fonte_etiqueta` (ENUM `ARIAL`/`COURIER`/`TIMES_NEW_ROMAN`) é
**provisório** — a lista real depende de uma decisão ainda em aberto (impressora térmica
dedicada ZPL/EPL vs. impressão via navegador), fácil de estender depois via `ALTER TYPE ADD
VALUE` (banco em construção).

**Aplicada no banco de dev** (`docker compose run --rm flyway`) — precisou `flyway repair`
primeiro por causa de checksum drift em V019/V025 (editadas em sessões anteriores sem recriar o
volume, já documentado na linha do tempo dessas datas); a aplicação de V029 em si foi limpa, sem
precisar recriar o banco (migration só aditiva, tabelas novas). Próximo passo (fora desta
sessão): o dono do produto vai passar instruções pra codificar a tela de Configuração de
Etiqueta em cima deste schema.

### 2026-08-04 — Botão de fechar (✕) removido das páginas-hub de grupo do menu

Ajuste pedido pelo dono do produto: as páginas-hub de grupo/subgrupo (`MenuGrupo.tsx` —
Frente de Loja, Estoque, Financeiro, Cadastros, Configurações, Relatórios, Implementações
Futuras, e os subgrupos Caixa/Cancelamentos) não são um passo dentro de um fluxo, são a página
de entrada de cada área — não faz sentido ter um botão de "fechar" ali, só a navegação normal
pela lateral (e, pra subgrupos, a seta de "voltar" pro grupo pai, que já existia e continua).
`BotaoFecharTela` removido desse único componente; nenhuma outra tela foi tocada — o botão segue
em todas as telas de cadastro/lista/relatório/formulário como antes.

### 2026-08-04 — Implementações Futuras: 9 áreas novas no grupo

Pedido do dono do produto: listar no menu, sem construir ainda, 9 áreas já previstas pro projeto
— Configuração de Etiqueta de Produtos, Emissão de Etiqueta de Produtos, CRM, BI Dashboard,
Importação de Dados, Entrada de Produtos por Compra (o tipo de movimento `COMPRA` do Kardex,
ver abaixo), Relatório de Contas a Pagar/Pagas, Relatório de Movimentação Bancária e Integração
com Marketplace. Mesmo padrão já usado por Painel/Pedidos/Canais nesse grupo: entrada em
`web/src/lib/menu.ts` (ícone reaproveitado de um já existente, sem criar SVG novo) + rota em
`App.tsx` apontando pro componente genérico `EmBreve` (`web/src/pages/EmBreve.tsx` — só um card
"Esta área está em construção", zero lógica).

### 2026-08-04 — Correção na raiz: `produto_movimento_detalhe.preco_custo` não era gravado

Descoberto pelos testes do Relatório de Movimentação de Produtos (abaixo): nenhum dos 5 services
que gravam o ledger de estoque (`PdvVendaService`, `DevolucaoProdutoService`,
`CancelamentoVendaService`, `TransferenciaService`, `BalancoEstoqueService`) preenchia a coluna
`preco_custo` no INSERT — ficava sempre no `DEFAULT 0` do schema, silenciosamente, desde que a
tabela existe. O relatório só teria valorização zerada em praticamente 100% dos movimentos reais,
o que expôs o gap; o dono do produto pediu a correção na raiz (não só um paliativo no relatório).

Cada service passou a ler `produto.preco_custo` junto da consulta que já fazia (mesmo padrão nos
4 primeiros: thread o valor por um record `ItemResolvido`/`LinhaItem` até o INSERT) e gravar a
coluna:
- **Pdv/Devolução/Transferência:** leem o custo ATUAL do cadastro do produto no momento do
  movimento.
- **Cancelamento:** não lê o cadastro — repete o `preco_custo` já gravado na linha da **VENDA
  original** que está sendo cancelada (`produto_movimento_detalhe` da venda, não uma nova
  consulta a `produto`). É a reversão exata daquela venda; o custo do produto pode ter mudado
  entre a venda e o cancelamento, e o estorno tem que refletir o valor histórico, não o atual.
- **Balanço:** único que não threadeou o valor por Java — o `LinhaDiferenca` (DTO já público,
  usado por `DiferencasEstoque.tsx`) não ganhou campo novo; o INSERT virou um
  `INSERT ... SELECT ... FROM produto_barra JOIN produto` que busca `preco_custo` direto no SQL.

Verificado ponta a ponta em ambiente real (não só nos testes): venda de um produto com custo
cadastrado em R$ 50,00 no PDV, e o `produto_movimento_detalhe` da VENDA gravou
`preco_custo = 50.00` (antes da correção, seria `0.00`, como ainda aparece nas linhas antigas do
ledger, gravadas antes deste fix). Nenhum teste dos 9 arquivos afetados quebrou
(`PdvCrudTest`, `DevolucaoProdutoCrudTest`, `CancelamentoVendaCrudTest`, `TransferenciaCrudTest`,
`BalancoEstoqueCrudTest`, `RelatorioMovimentacaoProdutosCrudTest`, `RelatorioComissoesCrudTest`,
`RelatorioContasReceberCrudTest`, `RelatorioEstoqueCrudTest`).

**Gap remanescente, aceito de propósito:** linhas do ledger gravadas ANTES deste fix continuam
com `preco_custo = 0` pra sempre (não dá pra reconstruir custo histórico que nunca foi
capturado) — o Relatório de Movimentação de Produtos usa o custo ATUAL do cadastro como fallback
só pra essas linhas antigas (`COALESCE(NULLIF(pmd.preco_custo, 0), p.preco_custo)`).

### 2026-08-04 — Relatório de Movimentação de Produtos (Kardex) — nova tela, 5ª de Relatórios

Pedido do dono do produto, com a ressalva explícita de "se comportar como um expert em estoque e
logista": um relatório sobre o ledger transacional (`produto_movimento_mestre`/
`produto_movimento_detalhe`, §3.3.4), diferente do Relatório de Estoque (que é só a fotografia do
saldo atual). Cobre os 8 tipos de `tipo_movimento` do ENUM — `COMPRA`, `TRANSFERENCIA`,
`DEVOLUCAO`, `AJUSTE`, `VENDA`, `RESERVA`, `LIBERACAO_RESERVA`, `CANCELAMENTO` — mesmo os 3 que
ainda não têm tela própria que os gere (`COMPRA` — entrada de mercadoria por fornecedor, ver
"Implementações Futuras" acima — e `RESERVA`/`LIBERACAO_RESERVA` — integração de pedidos de
canal): o relatório já nasce preparado pros 8, só não produz linha nenhuma dos 3 até essas telas
existirem. Detalhe completo em `docs/telas/relatorio-movimentacao-produtos.md`.

Três modelos alternativos, mesmo padrão de `Totalizador` já usado no Relatório de Vendas/Estoque
(campo discriminador na resposta):
- **Analítico** — uma linha por movimento, coluna Documento contextualizada por tipo (nº da
  venda/transferência/devolução, fornecedor+NF pra compra, texto livre `origem` pros demais, ex.:
  "contagem de estoque").
- **Kardex por Produto** — uma variação + uma empresa por vez, ficha cronológica com **saldo
  corrido calculado em Java** (o schema não guarda saldo por linha de propósito, P3), somando
  **todos** os 8 tipos sem exceção — é o único jeito de bater com `produto_estoque.qtd_estoque`
  de verdade, já que a trigger `fn_atualiza_estoque_movimento` (V019) também não distingue tipo,
  só `credito_debito`. Traz saldo inicial (soma de tudo antes do período) e saldo final.
- **Sintético** — totais por tipo de movimento (quantidade e valor de entrada/saída).

`RESERVA`/`LIBERACAO_RESERVA` são tratados como **não físicos** (`movimentoFisico = false`) —
reserva de saldo (`produto_estoque.reservado`), não uma movimentação real de produto — e por isso
excluídos dos KPIs "Entrada/Saída Física" e do gráfico "Movimentação por Tipo" no Analítico/
Sintético, mas somados normalmente no saldo corrido do Kardex (que precisa bater com o sistema).
Valorização sempre por CUSTO (`preco_custo`), nunca por venda — aqui é contábil/Kardex, diferente
de Vendas/Comissões. KPI extra sem equivalente em nenhum outro relatório do sistema: "Top Ajustes
Negativos por Produto" (`AJUSTE` com `credito_debito = 'D'` ranqueado por produto) — indicador de
quebra/perda/furto.

Layout já nasceu no padrão `.grid-altura-fixa` (página rola normal, só a grid tem altura própria)
em vez do antigo `.relatorio-corpo-fixo` — lição aprendida do bug de Contas a Receber (acima):
qualquer relatório com KPIs/gráfico acima da grid colapsa com o padrão antigo. PDF por captura
visual (`html2canvas`+`jsPDF`), mesmo padrão de 2 refs (cabeçalho numa página, grid noutra) dos
demais relatórios do grupo. Arquivos novos: `RelatorioMovimentacaoProdutosDtos/Service/
Controller.java` + `package-info.java` (módulo `estoque.relatoriomovimentacao`),
`RelatorioMovimentacaoProdutosCrudTest.java` (10 testes), `lib/relatorioMovimentacaoProdutos.ts`,
`lib/relatorioMovimentacaoProdutosCaptura.ts`, `pages/relatorios/RelatorioMovimentacaoProdutos.tsx`
+ `FiltrosMovimentacaoProdutosModal.tsx` + `PesquisaVariacaoModal.tsx`, entrada em `menu.ts`/
`App.tsx`/`AjudaDaTela.tsx` (R22).

### 2026-08-04 — Relatório de Comissões e Contas a Receber: 2ª rodada de melhorias

**Comissões:** duas colunas novas na grid — Nº Vendas (`COUNT(DISTINCT v.id_venda)` por
empresa+funcionário, nova subconsulta na CTE `vendas`) e Ticket Médio (`valorLíquido ÷
quantidadeVendas`, zero quando não há venda no período — nunca divide por zero), com subtotal
por empresa e total geral recalculados a partir da soma agregada (não é média das médias de
cada linha).

**Contas a Receber:** nova seção "Recebimentos por Forma de Pagamento" — um KPI por carteira
(nome + categoria, mesmo desambiguador de `LinhaCarteiraGrafico` do Relatório de Vendas, já que
a mesma bandeira pode existir em mais de uma categoria) mostrando Vencido/A vencer (só parcelas
sem `data_recebimento` do filtro já aplicado na tela, vencida = vencimento no passado — mesmo
critério de `PesquisaVendaService`/`ClienteHistoricoService`) + um gráfico de barras (valor
líquido por carteira, reaproveitando `LinhaCarteiraGrafico`/`rotulosPorCarteira` já existentes).
Nova coluna **Valor Taxa Adm.** (o valor monetário da taxa, não só o %), logo depois de "Taxa
Adm.", totalizada no subtotal por empresa e no total geral. Espaçamento das colunas da grid
reduzido (padding/font-size só nesta tabela, via classe `.tabela-contas-receber`) pra caber sem
scroll horizontal com a coluna nova.

**Mudança de arquitetura da tela (bug pego em teste manual):** o layout antigo
(`.relatorio-corpo-fixo`, corpo da tela travado só pra grid rolar por dentro) colapsava a altura
da tabela pra 0px assim que KPIs+gráfico entraram acima dela no mesmo container flex. Trocado
pelo mesmo padrão já usado no Relatório de Vendas — página rola normal (`.lista-corpo` sem
`relatorio-corpo-fixo`), grid com altura própria via `.grid-altura-fixa` (60vh, rolagem interna)
— incluindo o ajuste equivalente em `relatorioContasReceberCaptura.ts` (mesmo mecanismo de
"desclipar `.grid-altura-fixa` no clone do html2canvas" que `relatorioVendasCaptura.ts` já tinha)
pro PDF continuar capturando a tabela inteira, não só os 60vh visíveis na tela.

### 2026-08-04 — Enter muda de campo igual Tab, em todo o sistema

Pedido do dono do produto: continuar podendo usar Tab, mas também poder usar Enter pra navegar
entre campos (até então Enter só tinha comportamento nativo do navegador, ou nada). Dois
mecanismos novos em `web/src/lib/formularios.ts`:
- **`aoTeclarEnterNoFormulario`** (telas de cadastro, dentro de `<form>`): Enter tenta focar o
  próximo campo primeiro (mesma ordem que Tab usaria, pulando botões de propósito); só no
  **último campo** do formulário é que cai no comportamento antigo — abre o popup "Salvar
  dados?" (mecanismo de 2026-07-23, preservado intacto).
- **`iniciarNavegacaoGlobalPorEnter`** (tudo fora de `<form>` — PDV, filtros de lista, telas
  financeiras sem o padrão de cadastro, popups de busca): um único listener no `document`,
  ligado uma vez em `main.tsx`, cobre o resto do sistema sem precisar tocar tela por tela.
  Escopo da busca pelo "próximo campo" é o container mais próximo (`.modal`, senão `.card`,
  senão `.lista-tela`) — nunca o documento inteiro, pra não vazar foco pra trás de um popup
  aberto.
- Campos com Enter de propósito próprio (leitor de código de barras do PDV/Contagem de
  Estoque/Transferência/Devolução de Produtos, busca por nº de venda) continuam intocados — o
  listener global respeita `e.defaultPrevented`, então só foi preciso adicionar
  `preventDefault()` a 4 handlers que ainda não chamavam (os outros ~9 já chamavam, por outros
  motivos, e já ficavam de fora de graça).
- **Ajuste no mesmo dia** (reportado após teste manual): Enter não funcionava dentro de
  `<select>` (combo) — a origem válida do Enter foi ampliada de "só `<input>` de texto" pra
  "`<input>` de texto + `<select>`". `<textarea>` continua de fora de propósito (Enter ali tem
  que quebrar linha, não mudar de campo).

### 2026-08-04 — Botão de fechar (✕) em toda tela do sistema

Pedido do dono do produto: toda tela (cadastro, lista, relatório, PDV, popup) ganhou um botão ✕
no canto superior direito — mesmo padrão visual que os popups de detalhe já usavam (ex.
`DetalheVendaModal`), agora generalizado e substituindo os antigos botões de texto
"Voltar"/"Cancelar". Componente novo `web/src/components/BotaoFecharTela.tsx` + ícone
`IconeFechar` em `Icones.tsx`; ~46 arquivos tocados (praticamente toda tela do sistema).

**Bug reportado em teste manual e corrigido no mesmo dia:** a primeira versão navegava pra uma
rota fixa (ex.: sempre `/`), o que "pulava" telas intermediárias em fluxos aninhados (Estoque →
Contagem de Estoque → Zerar Contagem de Estoque → ✕ ia direto pro painel, não pra Contagem de
Estoque). Corrigido pra usar `navigate(-1)` (volta um passo real no histórico do navegador, não
uma rota fixa) — `BotaoFecharTela` hoje só aceita um `onClick` customizado nos 2 casos que
realmente precisam: formulário de Cliente embutido (fecha via callback do componente pai) e
Tipo de Carteira (precisa re-anexar o estado de filtros/paginação da lista ao voltar).

### 2026-08-04 — Menu: Produtos movido de Estoque para Cadastros

Ajuste de navegação pedido pelo dono do produto: a tela de cadastro do catálogo (`/produtos`)
morava dentro do grupo de menu **Estoque** desde que foi criada (2026-07-22) — mudou para o grupo
**Cadastros** (junto de Clientes/Fornecedores/Funcionários), por ser um cadastro como os outros,
não uma rotina de movimentação. Só `web/src/lib/menu.ts` mudou (item saiu de `itens` de
`estoque` e entrou em `itens` de `cadastros`, no fim da lista) — a descrição do grupo Estoque foi
reescrita para não citar mais "catálogo de produtos" (`'Movimentação de quantidades entre
empresas e conferência de estoque físico.'`). Rota (`/produtos`), componente e permissões não
mudaram — só a localização no menu.

### 2026-08-04 — Relatório de Estoque (4ª tela do grupo Relatórios)

Nova tela `/relatorio-estoque` (`relatorios.estoque.tela`, `docs/telas/relatorio-estoque.md`),
qualquer papel. Uma única tela com um seletor de **Modelo** (Inventário/Sintético/Analítico, mesmo
padrão do totalizador Analítico×Agrupado do Relatório de Vendas) trocando as colunas da grid —
filtros comuns aos 3 modelos: Empresas (ADMIN multi-select, nenhuma = todas ativas; OPERADOR só a
própria), Marca e Categorias (multi-seleção, novo componente genérico `MultiSelectGenerico.tsx`,
generalização de `EmpresaMultiSelect.tsx`), Tipo de Quantidade (Todos/Diferente de Zero/Zerada) e
Situação do Produto (Ativos/Inativos/Todos, mesmo critério de `ProdutoService.listar`). Inventário
soma tudo por produto num total só + custo (unitário do cadastro × total); Sintético abre uma
coluna de quantidade por empresa selecionada (mais total); Analítico é a mesma ideia por
**variação** (linha × coluna), sem totalizador. As colunas de empresa são **dinâmicas e
posicionais** (`colunasEmpresa` + `qtdPorEmpresa` alinhados por índice, não por chave) e resolvidas
**antes** da consulta principal — uma empresa sem nenhuma movimentação em lugar nenhum ainda
aparece como coluna zerada. Endpoint novo de apoio: `GET /api/v1/produtos/marcas` (distinct,
não-vazio, ordenado) para popular o filtro de Marca. 11 testes novos (`RelatorioEstoqueCrudTest`).

### 2026-08-04 — Rotina de Contagem de Estoque (4 telas novas) + 4 correções pós-uso real

Quatro telas novas dentro do grupo **Estoque** (submenu "Contagem de Estoque"), abertas a ADMIN e
OPERADOR, sempre escopadas à empresa ativa da sessão — `docs/telas/contagem-estoque.md`:
**Contagem de Estoque** (`/estoque/contagem`, só um campo de código de barras, mesma sintaxe
"qtd*código" do PDV/Transferência, cada leitura soma na quantidade já contada), **Diferenças de
Estoque** (`/estoque/diferencas`, compara a contagem ativa com `produto_estoque`, padrão visual de
relatório), **Efetivar Balanço** (`/estoque/efetivar-balanco`, grava um `produto_movimento_mestre`
tipo `AJUSTE` — enum que já existia sem uso — com uma linha de detalhe por variação com diferença,
e zera a contagem) e **Zerar Contagem de Estoque** (`/estoque/zerar-contagem`, apaga a contagem em
andamento ou desfaz a última efetivação). `produto_balanco` (V019, editada — banco em construção)
ganhou a coluna `id_movimento` (nullable): em vez de apagar as linhas do balanço ao efetivar, elas
são marcadas com o movimento gerado — é o que permite **desfazer** depois (apaga o
`produto_movimento_detalhe` daquele movimento, a trigger reverte `produto_estoque` sozinha, e
libera as linhas de volta pro balanço ativo) sem precisar de tabela de snapshot; reversão por
delta é correta mesmo com movimentações de estoque no meio-tempo. Desenho do "desfazer" proposto
pelo assistente e aprovado sem alterações, após o dono do produto pedir sugestões explicitamente.
15 testes novos (`BalancoEstoqueCrudTest`).

**4 correções reportadas depois de uso real na tela**, mesma sessão:
1. Leitura de código de barras aparecia como falha ("Não foi possível ler o código de barras")
   mesmo gravando certinho no banco — os endpoints de escrita (void) devolviam 200/201 com corpo
   vazio, e o `api()` do frontend só tratava 204 como "sem corpo"; `res.json()` num corpo vazio
   lança `SyntaxError`. Corrigido nos dois lados: endpoints agora `@ResponseStatus(NO_CONTENT)`
   (204) e `web/src/lib/api.ts` passou a ler como texto primeiro, só fazendo `JSON.parse` se não
   estiver vazio (cobre qualquer status sem corpo, não só 204 — protege contra a mesma classe de
   bug em endpoints futuros).
2. Diferenças de Estoque não distinguia "nenhuma contagem em andamento" de "contagem bate com o
   estoque" — as duas vinham vazias com a mesma mensagem. `DiferencasResponse` ganhou
   `existeContagemAtiva`, e a tela mostra uma mensagem diferente pra cada caso.
3. Efetivar Balanço não avisava quando não havia contagem pra efetivar (só quando não havia
   diferença) — corrigido, com mensagem própria e botão desabilitado.
4. Zerar Contagem exigia só um clique de confirmação num popup — passou a exigir digitar "zerar
   estoque" no campo antes do botão habilitar (mesmo padrão adotado também em Efetivar Balanço,
   "efetiva contagem", que ganhou junto os totais contado/em estoque na confirmação).

Depois, mais 3 ajustes pedidos: **(1)** o multiplicador "qtd*código" da Contagem de Estoque ganhou
um teto de 1000 por leitura (só nesta tela, validação no frontend, não afeta PDV/Transferência);
**(2)** o PDF de Diferenças de Estoque passou a mostrar a empresa da contagem numa seção "Filtros
Aplicados" acima da grid; **(3)** já cobertos no item 4 acima (Efetivar/Zerar com confirmação por
texto).

### 2026-08-03 — Relatório de Contas a Receber / Recebidas (3ª tela do grupo Relatórios)

Nova tela `/relatorio-contas-receber` (`relatorios.contasreceber.tela`,
`docs/telas/relatorio-contas-receber.md`), qualquer papel — mesmo padrão de tela do Relatório de
Comissões (popup de filtros → grid banda clássica com subtotal por empresa + total geral), mas com
**três períodos independentes** (venda/vencimento/recebimento, pelo menos um obrigatório) em vez
de um só, mais filtros de Status da Parcela (Todos/Em Aberto/Recebidas) e Forma de Pagamento
(Todos/Crediário/Cartão Débito/Cartão Crédito). Só as categorias `CARTAO_DEBITO`, `CARTAO_CREDITO`
e `CREDIARIO` aparecem (À Vista e Vale-Mercadoria nascem sempre quitados na hora). Valor líquido
sempre com a taxa **atual** do cadastro de Tipo de Carteira (não a que vigorava na data da venda);
crediário nunca tem taxa. "01/06" (parcela/total) é sempre o total verdadeiro da linha de
pagamento (mesma venda + mesmo tipo de carteira), via subconsulta correlacionada — ignora os
filtros do WHERE externo, uma parcela "3/3" continua "3/3" mesmo filtrando só recebidas. 9 testes
novos (`RelatorioContasReceberCrudTest`).

### 2026-08-03 — Relatório de Comissões (2ª tela do grupo Relatórios)

Nova tela `/relatorio-comissoes` (`relatorios.comissoes.tela`,
`docs/telas/relatorio-comissoes.md`), qualquer papel — segunda tela do grupo Relatórios, mais
simples que o Relatório de Vendas (sem KPIs/gráficos): popup de filtros (período + empresas) →
grid com uma linha por funcionário (por empresa, se houver mais de uma) → subtotal por empresa +
total geral. `valorVenda`/`valorDevolucao` vêm direto do ledger (`produto_movimento_detalhe`,
tipos `VENDA`/`DEVOLUCAO`, combinados com `FULL OUTER JOIN` pra cobrir quem só apareceu numa
devolução); `valorLiquido = valorVenda − valorDevolucao`; `valorComissao = valorLiquido ×
percComissao/100` (`funcionario.perc_comissao`, cadastral, nunca usado até aqui). **Nenhuma
comissão é de fato paga/lançada** — é só um cálculo de consulta. Funcionário de uma devolução é o
vendedor da venda original, resolvido pela tela de Devolução de Produtos. 6 testes novos
(`RelatorioComissoesCrudTest`).

### 2026-08-03 — Devolução de Produtos + Vale-Mercadoria (resgate no PDV) + simplificação do PDV

Nova tela do grupo **Frente de Loja** (`vendas.devolucaoproduto`, `docs/telas/devolucao-produtos.md`)
e uma extensão relevante do PDV/Cancelamento de Venda pra fechar o ciclo completo do vale-mercadoria
(gerar → imprimir → resgatar → cancelar sem perder o crédito). ADMIN e OPERADOR têm acesso.

1. **Devolução de Produtos** — tela nova: número da venda opcional (só resolve o vendedor,
   `produto_movimento_detalhe.id_funcionario`, pra uma futura comissão — o número em si **não**
   é persistido em lugar nenhum) + grid de leitura de código de barras idêntica ao padrão do
   PDV/Transferência (mesmo `lib/pdv.ts`, mesmo `PesquisaProdutoModal`). Ao gravar, devolve a
   quantidade de cada item ao estoque via `produto_movimento_mestre`
   (`tipo_movimento = 'DEVOLUCAO'`, valor do enum existente desde a V013 original, nunca usado
   até aqui) + `produto_movimento_detalhe` (`credito_debito = 'C'`), mesmo mecanismo de
   estoque de sempre (trigger `fn_atualiza_estoque_movimento`, sem lógica de estoque em Java).

2. **Toda devolução gera um vale-mercadoria** (pedido do dono do produto, revisão do escopo
   original que previa "sem efeito financeiro"): usa a tabela `venda_devolucao` (existente desde
   a V018 original de vendas, nunca referenciada por nenhum código até hoje) — `id_devolucao`
   (a própria PK) é o **número do vale** impresso; `id_venda_credito` grava o número da venda
   opcional informado na devolução (redefinição desta coluna: originalmente pensada pra um
   cenário de troca). O valor do vale nunca é gravado como coluna — é sempre derivado somando
   os itens do movimento `DEVOLUCAO` vinculado (`produto_movimento_mestre.id_devolucao`, FK que
   já existia). Popup automático pós-gravação com o comprovante (número + valor + itens),
   impressão térmica 80mm + PDF — mesmo padrão do Comprovante de Recebimento de Crediário
   (`ComprovanteValeModal.tsx`, `lib/comprovante.ts`).

3. **Resgate do vale no PDV — reaproveitando uma carteira que já existia.** Em vez de inventar
   um mecanismo de pagamento novo, o vale passou a ser resgatado através do tipo de carteira
   "VALE MERCADORIA" que já era seedado em todo tenant desde o signup (ideia do dono do
   produto) — só mudou a **categoria** dela de `AVISTA` para um valor novo do enum
   `categoria_carteira`, **`VALE_MERCADORIA`** (editado direto na `V025`, banco em construção).
   No split-tender do PDV, a categoria "Vale-Mercadoria" pede o **número do vale** (não um valor
   digitado) — o servidor busca o valor de verdade e ignora qualquer valor mandado pelo cliente
   (mesmo princípio de todo o PDV: preço nunca vem do front). Paga na hora, como À Vista
   (`PdvVendaService.gerarEInserirParcelas`/`aceitaApenasUmaParcela`); ao efetivar a venda, marca
   `venda_devolucao.vale_usado = true` + `id_venda_debito` **atomicamente** (`WHERE vale_usado =
   false`, trava otimista contra resgate concorrente do mesmo vale em duas vendas simultâneas).
   Bloqueios: vale já usado → 409; vale maior que o saldo a pagar → 400 (decisão do dono do
   produto: "sem troco em vale", sem sobra parcial — o schema só guarda usado/não usado, nunca
   saldo remanescente). Como o vale já é uma carteira normal, o Fechamento de Caixa **já
   totalizava sozinho** — nenhuma mudança lá.

4. **Cancelamento de Venda reabre o vale.** Se a venda que resgatou um vale for cancelada, o
   vale volta a valer (`vale_usado = false`, `id_venda_debito = NULL`), dentro da mesma
   transação do cancelamento — senão o cliente perderia o crédito de um vale cuja venda de
   resgate foi desfeita. Sem rastro de que já tinha sido usado uma vez (mesma filosofia de
   exclusão física já usada ali pra `caixa_detalhe`/`contas_receber`).

5. **"VALE PRESENTE" removido do seed do signup** (`SignupService`) — era só um rótulo À Vista
   sem nenhuma lógica por trás, e conviver ao lado do vale de verdade (`VALE MERCADORIA`) ficaria
   confuso. Removido também das 2 empresas de teste que já tinham essa linha (sem nenhum
   lançamento em `caixa_detalhe`/`contas_receber` usando ela — checado antes de apagar).

6. **PDV simplificado** — o atalho **F5 "Devolver Produto"** (reservado desde 2026-07-28, nunca
   teve funcionalidade) foi **removido**: a tela de Devolução de Produtos cobre esse caso agora.
   **F6 "Efetiva Venda" virou F5** (só uma letra sobrando faria menos sentido que preencher o
   vão). A grid de atalhos F2–F4 (que era `grid-template-columns: repeat(4, 1fr)`, pensada pra 4
   botões) passou pra `repeat(3, 1fr)` — sem isso os 3 botões restantes ficavam à esquerda com
   um vão vazio à direita.

7. **10 testes novos** (`DevolucaoProdutoCrudTest` + `ValeMercadoriaCrudTest`, incluindo split-
   tender vale+dinheiro, vale reusado, vale maior que a venda, resgate sem número informado, e
   cancelamento reabrindo o vale) + 1 teste ajustado (seed caiu de 7 para 6 carteiras). Suíte
   completa: **295/295**. Testado manualmente ponta a ponta no navegador (gerar vale → imprimir
   → resgatar em split-tender → conferir no banco → Fechamento de Caixa totalizando sozinho).
   Nada commitado ainda ao final da sessão.

### 2026-08-03 — Menu principal: hambúrguer no topo, lateral só com grupos, hub de cards

Reformulação da navegação do ERP, pedida pelo dono do produto. Spec em
`docs/telas/menu-principal.md`. Branch `feat/menu-retratil-cards`. Nenhuma tela de domínio nem
endpoint foi tocado — a mudança é inteira no shell do `web/`.

1. **Hambúrguer no topo do menu** (padrão mobile), não mais no rodapé. Com a árvore aberta, o
   botão de recolher saía da área visível e exigia rolar a navegação inteira. Novo
   `IconeMenuHamburguer` em `Icones.tsx`; a persistência (`niner_nav_recolhido`) e a "espiada"
   no hover/foco continuam iguais. O botão **alterna de ícone e rótulo conforme a preferência
   salva** — alfinete em destaque quando travado em aberto, hambúrguer quando no modo retrátil.
   Sem isso não dava para distinguir os dois estados: a espiada do hover deixa o menu retrátil
   visualmente idêntico ao fixo, mesma largura e mesmos rótulos. O rótulo continua sendo só
   "Menu" nos dois casos — trocar o texto junto com o ícone era redundante.
2. **A lateral passou a listar só os sete grupos principais.** A árvore de sub-itens saiu: eram
   sete grupos, dois subgrupos e ~20 telas empilhados numa coluna de 200px. No modo recolhido a
   faixa de 56px agora mostra os ícones dos grupos, não mais as telas achatadas
   (`achatarFolhas()` ficou sem uso e saiu).
3. **Página-hub por grupo** — nova rota `/menu/:grupo` (`MenuGrupo.tsx`). Cada grupo abre uma
   tela de **cards** com ícone, nome e uma frase do que a tela faz. Um subgrupo (Caixa,
   Cancelamentos) é **um nível a mais**: o card mostra a contagem de telas e abre o hub do
   próprio subgrupo, que traz **seta de retorno** para o pai (`acharPai()`) e o nome dele como
   trilha. Chegou-se a testar o subgrupo com os filhos abertos dentro do card, mas ficava
   pesado e sem hierarquia clara. Prefixo `/menu/` porque as chaves de grupo colidem com rotas
   existentes (`estoque` já é a Transferência de Produtos).
4. **O menu virou dado**, em `web/src/lib/menu.ts` — estava embutido no `Layout.tsx`. `Layout` e
   `MenuGrupo` leem a mesma árvore, e cada item ganhou um campo `descricao` **obrigatório**
   (é o conteúdo do card). Acrescentar tela ao sistema continua sendo editar `MENU`.
5. **Filtro por papel também no hub.** `MenuGrupo` roda `filtrarPorPapel` antes de montar os
   cards: OPERADOR não vê o grupo Configurações nem o card de Cancelamento de Vendas, e
   `/menu/configuracoes` na unha cai em "Área não encontrada". Continua sendo só conveniência de
   UI — a autorização de verdade segue no servidor (P4).

6. **Busca de telas no cabeçalho** (`BuscaDeTelas.tsx`) — resposta direta ao custo do item
   anterior: chegar a uma tela virou dois cliques, então o campo dá o acesso em um atalho.
   **Ctrl+K** (ou ⌘K) de qualquer lugar do ERP, ↑/↓ para navegar, Enter para abrir, Esc para
   limpar/sair. Casa contra nome, trilha de grupos e descrição, **sem acento e sem caixa**
   (`normalizar()` — "crediario" acha "Crediário"), ordenado por relevância. A pontuação
   (`pontuarTela()`/`buscarTelas()`) ficou em `menu.ts`, não no componente: é lógica de dados,
   e assim pôde ser exercitada fora do React. Conferido com o menu real: 22 telas para ADMIN e
   19 para OPERADOR, e buscar "cancelamento" como OPERADOR devolve só o Estorno de Crediário.
7. **Cabeçalho em 3 colunas** — marca à esquerda, **nome da loja centralizado**, busca e Sair à
   direita. Laterais em `1fr` para a loja ficar no centro da tela, não no espaço que sobra.

**Custo assumido:** chegar a uma tela pelo menu agora são dois passos (grupo → card) em vez de
um. Troca consciente: a lateral vira um índice curto e estável, a descoberta do que existe em
cada área passa a ter explicação, e quem já sabe o nome da tela usa o Ctrl+K.

**Pendência encontrada de passagem (não corrigida, não é desta entrega):** `cd web && npm run
build` já falhava em `main` antes desta branch — `PlanoContasModal.tsx:35` chama o handler de
salvar com um objeto parcial (`TS2345`, faltam `descricaoCurta`, `natureza`, `grupoDre`,
`grupoDfc` e mais 6 campos). `npx tsc --noEmit` passa e o dev server compila, então o erro só
aparece no `tsc -b` do build de produção.

### 2026-08-02 — PDF do Relatório de Vendas sempre em tema claro + levantamento de relatórios financeiros

1. **PDF sempre com fundo branco, mesmo com o app em tema escuro** (`docs/telas/
   relatorio-vendas.md`, seção "PDF") — pedido explícito: impressão em dark gasta muito mais
   tinta. O app não tinha toggle de tema à época (dark só vinha de `prefers-color-scheme` do
   navegador; o seletor Claro/Escuro/Automático do cabeçalho só nasceu em 2026-08-14);
   `styles.css` já tinha `:root[data-theme='light']`/`[data-theme='dark']` prontos, sem nada
   nunca setar o atributo. 1ª versão forçava `data-theme="light"` na página real antes de
   capturar — causava um "flash" visível (app inteiro piscava claro/escuro). **Corrigido no
   mesmo dia:** usa `onclone` do `html2canvas` (roda só no clone fora de tela que a lib já
   monta pra rasterizar) — a página visível nunca muda, zero flash. Também ganhou **3mm de
   margem lateral** na imagem capturada (encostava nas bordas antes). `AjudaDaTela` atualizada.
2. **Levantamento de relatórios futuros, a pedido do dono do produto** — análise das tabelas do
   módulo `financeiro` (`contas_receber(_detalhe/_lote)`, `caixa_mestre/_detalhe/
   _conferencia`, `contas_pagar`, `conta_corrente(_movimento)`, `tipo_carteira`,
   `cfg_plano_contas`) e sugestão de 8 relatórios possíveis no mesmo estilo do Relatório de
   Vendas: Contas a Receber/Recebidas, Contas a Pagar, Fluxo de Caixa, DRE/DFC Gerencial
   (usando a hierarquia do Plano de Contas), Extrato de Conta Corrente, Comissão de
   Vendedores, Curva ABC de produtos/clientes, Ruptura/Giro de Estoque. **Nada implementado**
   — o dono do produto pediu para pausar justamente o de Contas a Receber/Recebidas (o
   primeiro da lista, mais próximo de implementar) para analisar melhor antes de decidir.

**Verificação:** mudança só em `web/` (sem tocar backend) — `tsc --noEmit` limpo a cada etapa.
**Não testado ao vivo no Chrome nesta sessão** (extensão Claude in Chrome não conectou); pedida
confirmação visual ao dono do produto. **Nada commitado ainda** — só editado localmente.


### 2026-08-01 — Relatório de Vendas: ordenação, cores e PDF no modelo do ERP legado

Sessão de ajustes pedidos em testes manuais sobre o Relatório de Vendas (`docs/telas/
relatorio-vendas.md`), todos em cima da entrega de 2026-07-31 ([[project_relatorio_vendas]]).
Nenhuma tela nova; só refinamento. Detalhe completo na memória canônica da feature (mesmo
arquivo, seção "2026-08-01"), resumo aqui:

1. **Ordenação cronológica corrigida** — `RelatorioVendasService.buscarLinhasBase()` não tinha
   `ORDER BY` nenhum; a lista analítica (grid "Vendas do Período" e o drill-down por totalizador)
   saía na ordem arbitrária que o `GROUP BY` do Postgres devolvia. Fix de 1 linha: `ORDER BY
   v.data_venda` na query-base.
2. **Cores nos valores e nos gráficos** — pedido explícito: "cores que fiquem em harmonia com as
   cores do projeto". Só tokens já existentes no design system (nenhuma cor nova): Descontos/
   Devoluções em `--danger`, Acréscimos em `--sucesso`, Venda Líquida/Ticket Médio/Itens Vendidos
   em `--accent`; os 7 cards de gráfico alternam `--accent`/`--info` (identidade visual entre
   cards, não semântica de ganho/perda).
3. **PDF: seção "Filtros Aplicados" só no PDF, nunca na tela** — entra no DOM só enquanto
   `gerandoPdf === true` (dentro de `topoRef`, capturada junto pelo `html2canvas`); precisou de
   2 `requestAnimationFrame` depois do `setState` pra garantir que o React já comitou o nó antes
   da captura. 1ª tentativa foi uma página de rosto em texto nativo do `jsPDF` — **rejeitada**
   ("ficou muito grande numa folha"), substituída por essa seção compacta no mesmo tamanho de
   fonte do resto da tela.
4. **PDF: cabeçalho e rodapé repetidos em toda página**, seguindo o modelo de um relatório do ERP
   legado (Mitryus, `RELATORIO_COMISSOES.PDF`) trazido pelo dono do produto — título + "Página X
   de Y" + data/hora de geração subiram do rodapé pro cabeçalho (antes só tinha a numeração,
   pequena e só na última). Rodapé ganhou empresa **logada na sessão** (`eu.empresa.nome`, fixa —
   não confundir com a empresa do filtro, que fica em "Filtros Aplicados" e pode ser "Todas as
   empresas" pro ADMIN) e `"Nainer ERP"` fixo. `desenharElementoPaginado()` passou a reservar
   16mm/10mm de cabeçalho/rodapé por página (repintados com a cor de fundo do tema por cima de
   qualquer sobra da imagem capturada) antes de desenhar o texto nativo por cima.

**`AjudaDaTela.tsx`** (`relatorios.vendas.tela`) atualizado com a ordenação cronológica e o novo
comportamento do "Gerar PDF" (ver [[feedback_checklist_documentar_tudo]] — não esquecer a ajuda
da tela, não só a spec do repositório).

**Verificação:** `RelatorioVendasCrudTest` (8 testes) e suíte completa do `api/` rodadas depois do
`ORDER BY` — **295 testes de backend, 0 falhas**. `tsc --noEmit` do `web/` limpo a cada etapa.
Testado ao vivo no Chrome (login real `loja-teste-manual`/`teste@niner.dev`/`teste1234`): troca de
período pra "Últimos 12 Meses", "Gerar PDF", PDF de 3 páginas baixado e lido (`Read` do Claude
Code) — filtros só na página 1, título/paginação/data em todas, rodapé com empresa+"Nainer ERP" em
todas, grid cronológica confirmada visualmente.

Commitado e pushado (ver commit no topo do `git log`).

### 2026-07-31 — Relatório de Vendas (1ª tela do grupo Relatórios + padrão de tela de relatório)

Pedido do dono do produto com o desenho da tela quase completo (filtros, KPIs, gráficos e grid
já especificados por ele) — usado também para **definir o padrão de tela de relatório** que as
próximas telas do grupo Relatórios (vazio até agora) vão reaproveitar. Planejado em modo de
plano (poucas perguntas fechadas de decisão de dado, ver item 2) antes de implementar.

1. **Decisões de modelagem confirmadas com o dono do produto antes de codar:**
   - **Recharts** vira a 1ª biblioteca de gráfico do projeto (nova dependência do `web/`, mesmo
     espírito de ter adicionado `jspdf` pro comprovante de crediário) — nenhuma existia até aqui.
   - **"Operador de Caixa"** não existe como campo direto em `venda` — resolvido via
     `venda → caixa_detalhe (RECEBIMENTO_VENDA) → caixa_mestre.id_usuario`.
   - **"Vendedor" é 1 por venda, não rateado por item** — na prática já é assim, porque o PDV só
     aceita um `idFuncionario` por venda inteira (campo único na efetivação, não por item).
   - **Top 10 (Marcas/Vendedores/Clientes)** ranqueados por valor líquido, não por quantidade.
   - **Gráfico "Recebimentos por Tipo de Carteira"** usa a forma de pagamento *da venda* dentro
     do período (não a data de recebimento) — crediário entra mesmo sem ter sido recebido ainda.
   - **Empresa em multi-select** pra ADMIN (`EmpresaMultiSelect.tsx`, novo componente, 1º do tipo
     no projeto); OPERADOR fixo na própria empresa, mesmo padrão de sempre.
2. **Backend** (`com.vetor.niner.vendas.relatorio`, novo pacote) — um dataset-base de 1 linha por
   venda (não cancelada, dentro do filtro), montado numa query única agrupada por venda (LEFT
   JOIN LATERAL pro operador de caixa, pra não multiplicar linha de item quando há split-tender);
   todo o resto (KPIs, composição do faturamento, `porDia`/`porHora`/`porDiaSemana`,
   Top 10 Vendedores/Clientes, totalizador) calculado em Java a partir dessa lista. Top 10 Marcas
   e "por carteira" têm granularidade diferente (item do ledger e `contas_receber`,
   respectivamente) — duas queries agregadas à parte, reaplicando os mesmos filtros.
   `GET /api/v1/relatorios/vendas` (relatório completo) + `GET /api/v1/relatorios/vendas/detalhe`
   (drill-down de um grupo do totalizador, reaplica filtros + a chave do grupo clicado).
3. **Frontend** (`web/src/pages/relatorios/RelatorioVendas.tsx`) — painel de filtros (período com
   6 presets calculados no front, mesmo precedente de `primeiroDiaDoMesISO()` da Pesquisa de
   Vendas; `EmpresaMultiSelect`; lookup de vendedor já existente; totalizador), 4 cards de KPI
   ("como se fossem botões", só informativos nesta v1), 5 blocos de composição do faturamento, 7
   gráficos Recharts (1 linha + 4 barras horizontais + 2 colunas, todos de série única — cor
   `--accent` do design system, sem legenda) e a grid final: `NAO_TOTALIZAR` já mostra a lista
   analítica de vendas direto; qualquer outro totalizador agrupa e abre
   `DrilldownTotalizadorModal.tsx` (popup, mesmo padrão de `DetalheVendaModal.tsx`) ao clicar
   numa linha. Menu (`Layout.tsx`, grupo `relatorios` deixa de ser placeholder vazio), rota, ícone
   novo (`IconeRelatorio`) e ajuda contextual (`AjudaDaTela`, chave `relatorios.vendas.tela`)
   ligados.
4. **Bug pré-existente encontrado e corrigido de passagem** (não relacionado à feature): `Layout.tsx`
   tinha um erro de tipagem (`grupo.chave` em `NavNode` genérico) que travava `tsc -b` desde antes
   desta sessão — corrigido com um cast de 1 linha (`as NavGrupo[]`), já que o arquivo estava
   sendo editado mesmo. Um segundo erro pré-existente e não relacionado, em `PlanoContasModal.tsx`
   (parece ligado à revisão do Plano de Contas Gerencial do mesmo dia), foi deixado como está —
   não bloqueia `vite build` (bundling), só `tsc -b` (checagem de tipos).
5. **Verificação:** 8 testes novos (`RelatorioVendasCrudTest`, Testcontainers) — KPIs/composição
   batem com soma manual de um cenário conhecido, venda cancelada fora de tudo, OPERADOR não
   força outra empresa, `NAO_TOTALIZAR` vs `VENDEDOR` (agrupamento + drill-down), Top 10 Marcas
   por item mesmo em venda multi-marca, período > 400 dias, RLS entre tenants — **286 testes de
   backend verdes no total**, suíte completa do `api/` sem regressão. `tsc -b`/`vite build` do
   `web/` verdes (só o erro pré-existente do item 4
   não relacionado ficou). **Testado ao vivo no Chrome** (login real contra o tenant de teste,
   `docker compose up -d --build api`): todos os filtros, os 7 gráficos com dado real, totalizador
   agrupado + drill-down e o multi-select de empresa exercitados na tela rodando de verdade — um
   bug real de CSS achado nesse teste (o `EmpresaMultiSelect` herdava `width:100%`/`min-width:180px`
   de `.filtros-bar input`, por estar aninhado dentro do mesmo container da tela que o usa) e
   corrigido com um seletor mais específico.
6. **Ajuste em seguida — "Recebimentos por Tipo de Carteira" separado por categoria.** O gráfico
   agrupava só por `nome_carteira`, então "HIPER" débito/crédito (mesma bandeira, categorias
   diferentes — já resolvido uma vez no Fechamento de Caixa) somavam numa barra só. Backend passou
   a agrupar por `(nome_carteira, categoria_carteira)` (`LinhaCarteiraGrafico`, campos separados,
   não um rótulo pronto). Rótulo no front (`rotulosPorCarteira()`, `lib/relatorioVendas.ts`):
   cartão (`CARTAO_DEBITO`/`CARTAO_CREDITO`) **sempre** mostra "NOME Débito"/"NOME Crédito" — nem
   só quando o nome se repete (1ª tentativa errada: escondia "VISA Crédito" quando só existe uma
   categoria daquela bandeira) — à vista/crediário só o nome, sem categoria.
7. **Botão "Gerar PDF", em duas rodadas.** 1ª versão gerava tabela de texto monoespaçada (mesmo
   padrão do Fechamento de Caixa) — **rejeitada pelo dono do produto**: "quero que no pdf, gere
   como está na tela, com os gráficos". Reescrita como **captura visual** da própria tela
   (`web/src/lib/relatorioVendasCaptura.ts`): `html2canvas` rasteriza o bloco de KPIs/composição/
   gráficos/grid (JPEG 92%, não PNG — em `scale:2` o PNG passava de 30-40MB) numa imagem única;
   `jsPDF` (A4 paisagem) recorta em páginas. **Achado sério:** `html2canvas` clona o `<html>`
   inteiro pra manter contexto, não só o elemento passado — qualquer CSS incompatível em
   **qualquer lugar da página** (não só na área capturada) derruba a captura inteira. O projeto
   usava `color-mix()` em ~9 regras (badges, menu ativo, telas de PDV); o Chrome devolve o valor
   computado via `getComputedStyle` como `color(srgb ...)` (CSS Color 4), que o parser mais antigo
   do `html2canvas` não entende. **`color-mix()` eliminado do projeto inteiro** (`styles.css`) —
   trocado por `rgba(var(--accent-rgb), alfa)` com trincas R,G,B novas (`--accent-rgb`,
   `--accent-ink-rgb`, `--danger-rgb`) nos 4 blocos de tema claro/escuro; mais `ignoreElements`
   no `html2canvas()` pulando cabeçalho/menu/modais por padrão, defesa extra pro futuro. `recharts`
   e `html2canvas` (novas dependências) verificados juntos: PDF final validado abrindo o arquivo
   baixado de verdade (não só checando ausência de erro) — 2 páginas A4 paisagem, ~700KB (contra
   ~38MB antes do ajuste JPEG), todos os 7 gráficos desenhados de verdade (não texto). **Revisão
   em seguida:** a grid de vendas passou a capturar separada dos gráficos (dois refs, dois
   `html2canvas` independentes) e sempre começa numa página nova (`doc.addPage()` forçado entre
   os blocos); a grid ganhou `<tfoot>` de totais (na tela também, não só no PDF); toda página do
   PDF ganhou "Página X de Y" no rodapé (`doc.getNumberOfPages()` + overlay de texto depois que
   as páginas já existem).

### 2026-07-31 — Menu lateral agrupado + revisão de UX no PDV/Cancelamento de Venda

Lote de 8 pedidos diretos do dono do produto ("Próximos passos"), todos de UI/UX — nenhuma
mudança de schema.

1. **Menu lateral reorganizado em grupos expansíveis** (`Layout.tsx`) — a lista plana de itens
   virou uma árvore de até 3 níveis: **Frente de Loja** (PDV, Pesquisa de Vendas, Recebimento de
   Crediário, **Caixa** [Abertura/Fechamento], **Cancelamentos** [Cancelamento de Vendas
   admin-only, Estorno de Crediário]), **Estoque**, **Financeiro**, **Cadastros**,
   **Configurações** (admin-only), **Relatórios** (grupo vazio de propósito — placeholder até a
   tela existir, sem seta/sem clique) e **Implementações Futuras** (Painel, Pedidos, Canais —
   saíram do destaque do menu principal mas continuam no projeto e acessíveis, só isolados no
   fim). Cada grupo abre/fecha com clique no cabeçalho (seta ↑/↓, `IconeSetaCima`/`IconeSetaBaixo`
   reaproveitados), estado persistido em `localStorage`
   (`niner_nav_grupos_abertos`) por chave de grupo, igual ao recolher do menu inteiro já
   existente. O modo **recolhido em ícones** (hover/foco expande temporariamente, já construído
   em sessão anterior) passou a achatar a árvore inteira numa lista só de ícones — a nova
   hierarquia é invisível nesse modo, preservando o comportamento de antes. Itens/grupos
   `adminOnly` somem por completo pra quem não é ADMIN (inclusive o grupo Configurações inteiro).
2. **`CancelamentoVendaModal.tsx` — cabeçalho e rodapé fixos, corpo rolável.** Três pedidos
   pontuais: botão "✕" no canto superior direito no lugar do botão "Não" (que deixou de
   existir); cabeçalho (até a linha Cliente/Vendedor) fixo sem scroll; e "Motivo do Cancelamento"
   + botão "Sim, Cancelar Venda" fixos no rodapé, também sem scroll — só a lista de itens/formas
   de pagamento/mensagem de bloqueio no meio rola (mesmo truque de `DetalheVendaModal`: flexbox
   com `overflow:hidden` no modal e `overflow-y:auto` só na div do meio). Quando a venda já está
   cancelada ou bloqueada por crediário recebido, o rodapé de ação some inteiro (o "✕" já fecha).
3. **PDV — cadastro de cliente na venda (`PesquisaClienteModal.tsx`), em duas rodadas.** Primeira
   rodada: botão "+ Novo Cliente" abrindo um formulário mínimo embutido (`ClienteRapidoModal`,
   só nome/categoria/gênero/CPF/celular). Testado ao vivo e revisado no mesmo dia a pedido do
   dono do produto — o formulário mínimo foi **substituído pela tela de cadastro de cliente
   completa** (a mesma de `/clientes/novo`): `ClienteForm.tsx` ganhou dois props opcionais,
   `aoSalvarComSucesso`/`aoCancelar` — quando fornecidos ("modo embutido"), o formulário chama
   esses callbacks em vez de navegar pra `/clientes`, escondendo também o ícone de engrenagem
   ("Configurar tela", que levaria pra fora da venda). Novo `ClienteFormModal.tsx` (em
   `pages/pdv/`) embrulha o `ClienteForm` num modal que rola por dentro (mesmo truque de altura
   fixa + flex column), sem sair da venda em andamento; ao salvar, o cliente recém-criado é
   selecionado automaticamente e os dois modais fecham. `ClienteRapidoModal.tsx` foi removido
   (superado). Ajuste de layout junto: o campo de busca "Nome, CPF/CNPJ ou Celular" e o botão
   "+ Novo Cliente" ficavam desalinhados (rótulo empurrando só o campo pra baixo) — corrigido
   colocando o rótulo numa linha própria acima, com input+botão na mesma `linha-com-botao` (igual
   ao padrão já usado pra Categoria).
4. **`FormaPagamentoModal.tsx` — regra de habilitação do "Confirmar Venda" revisada.** Antes, o
   botão só habilitava com pagamento fechado **e** cliente **e** vendedor definidos (com uma
   dica genérica no `title`). Pedido direto: o botão deve habilitar assim que a distribuição do
   pagamento fechar o valor a pagar, **independente** de cliente/vendedor já estarem definidos;
   só ao **clicar** em "Confirmar Venda" é que a falta de cliente e/ou vendedor bloqueia a
   gravação, com um aviso (`Toast`) específico do que falta ("Defina o cliente…"/"Defina o
   vendedor…"/"Defina o cliente e o vendedor…") — nunca grava a venda faltando um dos dois.
5. **Ajuste visual — altura do botão "F6 Efetiva Venda"** (`.pdv-tecla-venda` em `styles.css`)
   reduzida (`min-height` 84px→52px, padding 16px→10px 16px) — pedido direto do dono do produto
   testando a tela.

Nenhuma mudança de schema ou de contrato de API nesta sessão — só frontend (`web/`). Detalhe
funcional completo em `docs/telas/pdv.md` (seções "Cliente e vendedor da venda" e "Layout da
tela de Forma de Pagamento") e `docs/telas/cancelamento-venda.md` (seção de layout do popup);
o menu lateral não tem spec de tela própria (é o shell do `Layout.tsx`, documentado só aqui).

### 2026-07-31 — Revisão do Plano de Contas Gerencial (DRE/DFC por conta, hierarquia, seed padrão)

Pedido direto do dono do produto, com um script SQL completo já escrito (comentado, com a
racionalização de cada decisão) — revisão total de `cfg_plano_contas` (V016, era o desenho
simples de 2026-07-21: código texto livre + descrição + tipo de movimento + 2 flags soltos),
mantendo o dado já existente no banco (explicitamente pedido: "ajuste a tabela e o script, mas
não apague os dados").

1. **Schema (`db/migration/V013__dominio_tipos_enum.sql` + `V016__cadastros.sql`, editados no
   lugar — banco em construção):** `tipo_movimento_conta` perdeu os acentos
   (CRÉDITO/DÉBITO→CREDITO/DEBITO, via `ALTER TYPE ... RENAME VALUE` — dado preservado, só o
   rótulo mudou); 3 ENUMs novos (`natureza_conta`, `grupo_dre_conta`, `grupo_dfc_conta`).
   `cfg_plano_contas` ganhou máscara fixa `9.99.999.999` (conta.subconta.item.subitem), `nivel`/
   `id_plano_contas_pai` como colunas geradas, `natureza` (SINTETICA/ANALITICA), `grupo_dre`/
   `grupo_dfc` (obrigatórios só quando `inclui_dre`/`inclui_fluxo_caixa` são `true` — CHECK
   trava), `sinal`/`aceita_lancamento` (sempre derivados), exigências (centro de custo/
   contraparte/documento), `id_conta_contabil`/`id_plano_referencial` (de-para SPED/RFB),
   `padrao_sistema`, **`ativo`** (novo — tirou esta tela da única exceção sem fallback de
   inativar), `observacao`. FK de hierarquia auto-relacionada (deferrable), índices novos
   (pai/lançável/DRE/DFC/busca trigram com wrapper `unaccent_imutavel` — `unaccent()` sozinha não
   pode ir num índice de expressão, não é IMMUTABLE), trigger de guarda (bloqueia excluir conta
   padrão/com filhos) e view `vw_plano_contas_arvore` (sem consumidor na tela ainda). RLS
   continua centralizada em V024, não duplicada.
2. **Dado existente preservado:** a única conta que havia no banco inteiro (`3.1.001 — COMPRA
   MERCADORIA REVENDA`, vinculada a 1 fornecedor, `CALCADOS AZALEIA`) foi migrada — via `UPDATE`,
   nunca `DELETE` — pro código `3.03.001.001` (a conta padrão equivalente do plano novo, decisão
   confirmada com o dono do produto via pergunta direta), com o fornecedor atualizado na mesma
   operação.
3. **Novo `db/scripts/seed_plano_contas_padrao.sql`** (fora das migrations — dado de negócio por
   tenant, mesmo padrão de `seed_cfg_produto_ncm.sql`): plano padrão de ~200 contas pra varejo de
   calçados/confecções (8 grupos: Receitas/Deduções/Custos Variáveis/Custos e Despesas Fixas/
   Financeiras/Investimentos/Movimentos Neutros/Tributos sobre o Lucro), carregado pro tenant 1
   (336 contas no total, idempotente). **Não** ligado ao `SignupService` — seed continua manual
   por tenant, como o próprio script original já assumia (seção comentada de "clonar pra outro
   tenant").
4. **Backend (`PlanoContasDtos/Service/Controller`, reescritos):** mask validada no servidor
   antes de qualquer INSERT; `sinal`/`aceitaLancamento` sempre derivados (nunca aceitos do
   cliente); erro amigável quando a conta pai não existe ("Crie primeiro a conta pai X") em vez
   da violação de FK crua; exclusão ganhou o mesmo fallback-pra-inativar que Cliente/Funcionário/
   Fornecedor já tinham (conta padrão do sistema / com filhos / vinculada a fornecedor-contas a
   pagar-caixa-conta corrente).
5. **Frontend:** decisão confirmada com o dono do produto — **lista plana continua** (sem
   navegação em árvore nesta rodada), só ganhando os campos novos: máscara no código
   (`mascararCodigoPlanoContas`, novo em `lib/masks.ts`), seleção de Natureza, DRE/Fluxo de Caixa
   com grupo condicional (só aparece quando a flag correspondente está marcada), exigências,
   integrações opcionais, filtro de status (Ativos/Inativos/Todos).
6. **Testes:** `PlanoContasCrudTest` reescrito (8→19 testes: máscara inválida, hierarquia
   completa criada de cima para baixo, pai inexistente, DRE sem grupo, neutro com DRE, exclusão
   com cada um dos 3 fallbacks de inativar). `FornecedorCrudTest`/`ContaCorrenteCrudTest`/
   `ContaCorrenteMovimentoCrudTest` tiveram os códigos de teste convertidos pro formato novo
   (dependiam de um plano de contas existente pra testar suas próprias FKs). **276 testes de
   backend verdes no total.**
7. **Verificação ao vivo:** listagem com indentação hierárquica e badge 🔒 de conta padrão,
   busca pelo código migrado (`3.03.001.001` mostrando a descrição original preservada),
   criação de conta nova com o campo de Grupo da DRE aparecendo/sumindo condicionalmente,
   exclusão real (sem vínculo) e o vínculo do fornecedor migrado confirmado íntegro na tela de
   Fornecedores.

Detalhe completo (regras de negócio, contrato de API, o "porquê" de cada decisão) em
`docs/telas/plano-contas.md` — reescrito, com o desenho original de 2026-07-21 preservado numa
seção de histórico no fim do arquivo.

### 2026-07-31 — Ajustes de UI (Pesquisa de Vendas/Cancelamento de Venda) + 2 correções em Caixa (testes manuais)

Sessão de testes manuais (containers reconstruídos com o código de 2026-07-30, ainda não
commitado até este ponto). Quatro ajustes pontuais pedidos ao vivo, cada um motivado por um
teste real na tela:

1. **Pesquisa de Vendas — detalhamento da venda selecionada passou a abrir em popup.** Antes,
   o bloco de detalhe (dados da venda + 3 grids: produtos/caixa/parcelas) aparecia empilhado
   abaixo da grid de resultado, exigindo scroll da página inteira pra ver. Extraído pra
   `DetalheVendaModal.tsx` (novo, mesmo padrão `.modal-overlay`/`.modal.modal-largo` já usado
   por `CancelamentoVendaModal.tsx`/`LancamentosCarteiraModal.tsx`) — agora abre em popup que
   rola internamente (a grid de fundo não se move), fechado pelo botão "Fechar" ou clique fora.
   `PesquisaVendas.tsx` perdeu a query de detalhe e o JSX correspondente, que migraram pro modal.
2. **Cancelamento de Venda — ordem do ícone de visualizar e do badge "Bloqueada"/"Cancelada"
   invertida.** Pedido direto do dono do produto: o ícone verde de visualizar vem primeiro na
   célula de ações, o badge (quando existe) aparece à direita dele — antes era o contrário.
   Mudança de puro layout (`CancelamentoVenda.tsx`), sem alteração de comportamento.
3. **Abertura de Caixa — saldo inicial só pode ser em "Dinheiro".** Pedido direto: cartão/PIX/
   crediário não têm saldo inicial de verdade, só recebem movimento durante o dia. `CaixaService.
   listarCarteirasParaAbertura()` passou a filtrar `categoria_carteira = 'AVISTA' AND
   nome_carteira = 'DINHEIRO'` (era todo `tipo_carteira` do tenant); `validarCarteira()` reforça a
   mesma regra no `POST /caixa/abrir` (P4). `CamposAberturaCaixa.tsx` (tela dedicada + popup do
   PDV/Recebimento) trocou o select por um campo fixo mostrando "DINHEIRO". Detalhe completo em
   `docs/telas/abertura-caixa.md`, seção "Revisão 2026-07-31".
4. **Fechamento de Caixa — carteiras com o mesmo nome em categorias diferentes (ex.: "HIPER"
   débito e "HIPER" crédito) passaram a aparecer separadas na tela.** Achado ao testar: os totais
   já eram calculados corretamente por `id_carteira` (carteiras diferentes), mas a resposta só
   trazia `nomeCarteira`, então duas linhas homônimas ficavam indistinguíveis. `LinhaTotalCarteira
   Response`/`LinhaConferenciaResponse` ganharam `categoriaCarteira`; front ganhou `rotuloCarteira()`
   (`web/src/lib/caixa.ts`) montando `"HIPER — Cartão Débito"`/`"HIPER — Cartão Crédito"` — usado
   na contagem às cegas, divergência, relatório final, conferência, drill-down e impressão/PDF
   (coluna "Carteira" alargada de 30 pra 34 caracteres). Detalhe completo em `docs/telas/
   fechamento-caixa.md`, seção "Carteiras com o mesmo nome em categorias diferentes".

Todos os 4 verificados ao vivo no navegador (itens 3 e 4 com um fechamento de caixa real, revertido
depois no banco de dev pra não atrapalhar o ambiente de teste do usuário). `tsc --noEmit` limpo;
**266 testes de backend verdes** (+2 em `CaixaCrudTest` pro item 3, +1 em `FechamentoCaixaCrudTest`
pro item 4).

### 2026-07-30 — Cancelamento de Venda + Pesquisa de Vendas (novas) + Fechamento de Caixa revisado pra "às cegas" + menu lateral com hover

1. **Cancelamento de Venda (novo, `vendas.cancelamento`, docs/telas/cancelamento-venda.md, ADMIN-only).**
   Tela de busca (por nº da venda, que ignora os demais filtros, ou por período + empresa/
   cliente/vendedor) igual em espírito às demais listagens do projeto. Ao confirmar o
   cancelamento com um motivo obrigatório, `CancelamentoVendaService.cancelar()` reverte tudo
   numa única transação: devolve o estoque (novo `produto_movimento_mestre` tipo
   `CANCELAMENTO` + um `produto_movimento_detalhe` 'C' por item — a trigger já existente baixa/
   soma `produto_estoque` sozinha), apaga os lançamentos de `caixa_detalhe` e as parcelas em
   `contas_receber` geradas pela venda. Duas regras de negócio bloqueantes: **RN-03** — venda de
   crediário com qualquer parcela já recebida nunca pode ser cancelada, em nenhuma hipótese (a
   tela mostra quais parcelas foram recebidas e orienta a estornar o recebimento primeiro);
   **RN-02**, simplificada — exige o caixa de **hoje** aberto (não o caixa da data original da
   venda, que o sistema não sabe reabrir). Nenhuma migration nova — `venda` ganhou `cancelada`/
   `data_cancelamento`/`id_usuario_cancelamento`/`motivo_cancelamento` dentro da V018 (banco
   ainda em construção). **10 testes novos** (`CancelamentoVendaCrudTest`).

2. **Pesquisa de Vendas (novo, `vendas.pesquisa`, docs/telas/pesquisa-vendas.md, qualquer
   papel).** Partiu de uma especificação funcional completa que o dono do produto colou na
   conversa, escrita para um sistema de referência chamado "Mitryus" — adaptada por completo
   pro schema real do Nainer antes de implementar (seção própria "Adaptações em relação à
   especificação original" no spec, documentando cada divergência: sem `venda_item`/
   `valor_total`/`desconto` armazenados — vêm do ledger de estoque; sem "Em aberto" como estado
   de venda, só `cancelada` boolean; "vendedor" é `funcionario`; empresa do filtro segue o
   mesmo padrão de Fechamento de Caixa — ADMIN filtra livremente, OPERADOR só a própria empresa
   da sessão, reforçado no servidor). Pontos em aberto da especificação original resolvidos com
   o dono do produto via `AskUserQuestion` antes de codar: sem ação especial no duplo clique,
   sem exportação nesta v1, com filtro de situação (Ativas/Canceladas/Todas), período máximo de
   365 dias (igual ao Cancelamento de Venda). Tela **só de consulta**: filtros no topo + grid de
   resultado paginada (totalizador de quantidade/soma sempre excluindo canceladas, calculado
   sobre **todo** o filtro, não só a página atual) +, ao clicar numa linha, **quatro grids
   empilhadas** (sem abas, decisão deliberada — o usuário compara produtos com recebimentos ao
   mesmo tempo): dados da venda (incluindo `recebido`/`aReceber`, derivados de `contas_receber`
   — pagamento à vista fica quitado na hora da venda, cartão fica **pendente** até uma futura
   conciliação de cartões, ainda não implementada — achado real ao testar ao vivo, não um bug),
   produtos vendidos, movimentação de caixa (reaproveitando o mesmo critério de "origem" de
   `CaixaService`: prioriza `id_lote_recebimento` sobre `id_venda` quando os dois estão
   preenchidos) e parcelas de crediário (situação Aberta/Paga/Vencida sempre **calculada na
   hora**, nunca lida de uma coluna). Nenhuma migration nova. **10 testes novos**
   (`PesquisaVendaCrudTest`) — incluindo um bug real de Java pego pelos testes: `admin ? idEmpresa
   : idEmpresaSessao(jwt)` (ternário `Long`/`long`) forçava unboxing de um `idEmpresa` nulo pra
   ADMIN sem filtro, `NullPointerException`; corrigido pra `if/else` explícito.

3. **Fechamento de Caixa revisado pra "às cegas" (mesmo dia, revisão do que foi entregue mais
   cedo — ver a entrada logo abaixo — docs/telas/fechamento-caixa.md).** Pedido direto e
   detalhado do dono do produto: (1) mostrar todos os tipos de carteira com movimento no dia;
   (2) pedir o valor contado de cada um, sem revelar o esperado antes (contagem às cegas, sem
   viés); (3) só liberar a impressão se tudo bater; (4) se não bater, tela separada com
   carteira/esperado/contado; (5) clicar na carteira mostra analiticamente os lançamentos, pra
   conferência. Substituiu por completo o desenho anterior (um único campo "dinheiro contado"
   comparado só contra a carteira "DINHEIRO"). `POST /api/v1/caixa/fechamento` agora recebe um
   valor contado por carteira; só grava (`caixa_mestre.caixa_fechado=true` + uma linha por
   carteira na tabela nova `caixa_fechamento_conferencia`) quando **todas** as diferenças fecham
   em zero — senão devolve a divergência de cada carteira sem gravar nada, e a tela deixa
   corrigir e tentar de novo. Novo `GET /api/v1/caixa/fechamento/{idCaixa}/carteiras/
   {idCarteira}/lancamentos` monta o drill-down (inclusive uma linha sintética de "abertura de
   caixa" que não existe fisicamente em `caixa_detalhe`). Coluna antiga `valor_contado_dinheiro`
   (e `id_usuario_fechamento`, que continua em uso) **mantida sem uso, não apagada** — dado de
   caixas já fechados com a versão anterior não é descartado (regra permanente do projeto:
   nunca excluir dado de tabela numa mudança de schema sem perguntar antes). `FechamentoCaixaCrudTest`
   foi de 11 pra **14 testes**; suíte completa do projeto: **274 testes verdes** (incluindo os
   10+10 novos de Cancelamento/Pesquisa de Vendas). Verificado ao vivo no navegador com um caixa
   real de 6 carteiras (AMEX/DINHEIRO/HIPER/MASTER/PIX/VISA): contagem errada proposital →
   divergência exibida certa → drill-down mostrando abertura + venda → correção → fechamento →
   relatório com a seção de conferência → PDF sem erros.

4. **Menu lateral — expandir ao passar o mouse ou focar, recolher sozinho ao sair
   (`web/src/components/Layout.tsx`).** O botão de recolher/expandir (2026-07-28) continua
   controlando a preferência persistida (`localStorage`), mas agora, com o menu recolhido,
   passar o mouse ou levar o foco (Tab) pro `<nav>` expande temporariamente os rótulos sem
   mudar essa preferência; tirar o mouse ou o foco de dentro do `<nav>` recolhe de novo
   automaticamente. Cuidado de acessibilidade: o `onBlur` checa `e.relatedTarget` contra o
   próprio `<nav>` antes de recolher, pra tabular entre os links do menu não piscar
   expandido/recolhido a cada tecla. Nenhuma mudança de backend.

### 2026-07-30 — Fechamento de Caixa + PDV passa a lançar no caixa/contas a receber corretamente

Sessão seguinte à de Conta Corrente, mesmo dia. Pedido direto, em 5 partes: ADMIN fecha o caixa
de qualquer usuário, OPERADOR só o próprio; pedir a data do movimento; totalizar por tipo de
carteira em colunas separadas de crédito/débito; opção de impressão com pré-visualização antes de
imprimir/salvar PDF, em folha A4. Duas decisões fechadas via `AskUserQuestion` antes de codar:
ADMIN escolhe o usuário por um select + campo de data (não uma lista clicável de caixas abertos);
a rotina tem etapa de conferência de dinheiro contado (não só mostrar os totais calculados).

1. **Backend (`financeiro.caixa`, estendido):** `caixa_mestre` ganhou `valor_contado_dinheiro
   numeric(12,2)` e `id_usuario_fechamento integer` (FK composta pra `usuario` — pode ser
   diferente de `id_usuario` quando ADMIN fecha o caixa de outra pessoa), editado dentro de
   `V025__financeiro_caixa_crediario.sql` (banco em construção). `CaixaService.
   buscarParaFechamento()`/`fechar()` novos: totais por tipo de carteira recalculados na hora a
   partir de `caixa_detalhe` (nunca um campo gravado — `valorEsperado = saldoInicial +
   totalCredito − totalDebito`), permissão dono-do-caixa-ou-ADMIN (mesmo mecanismo de
   `ConfiguracaoGeralService`), 409 se o caixa já estiver fechado. `GET`/`POST
   /api/v1/caixa/fechamento` novos em `CaixaController`.

2. **Frontend:** tela nova `FechamentoCaixa.tsx` (menu ao lado de Abertura de Caixa) — select de
   usuário só pra ADMIN, campo de data, tabela de totais por carteira, campo "Valor Contado em
   Dinheiro" com diferença calculada contra a carteira "DINHEIRO", botão "Fechar Caixa".
   `FechamentoCaixaPreviewModal.tsx` + `web/src/lib/fechamentoCaixaImpressao.ts` — relatório em
   folha A4 (`@page` nomeada `fechamento-a4`, diferente do `@page` sem nome já usado pelo
   Comprovante de Crediário, 80mm — os dois nunca imprimem juntos, mas `@page` é global ao
   documento), mesmo padrão de "linhas de texto monoespaçado reusadas por tela/impressão/PDF" do
   Comprovante (`jsPDF`, fonte courier). **11 testes novos** (`FechamentoCaixaCrudTest`).

3. **Achado ao testar ao vivo no navegador — "fiz várias vendas e só aparece Dinheiro":** o PDV
   nunca lançava nada em `caixa_detalhe` (decisão antiga, documentada como "fora de escopo por
   ora" em `PdvVendaService`) — só o Recebimento de Crediário gravava lá. Corrigido, confirmado
   com o dono do produto via `AskUserQuestion` antes de codar: cada linha de pagamento **à
   vista** (`AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`) de uma venda do PDV agora lança um crédito
   em `caixa_detalhe` (`tipo_operacao = 'RECEBIMENTO_VENDA'`) no momento da venda; `CREDIARIO`
   fica de fora de propósito (senão contaria em dobro quando a parcela fosse recebida depois).
   **1 teste novo** em `PdvCrudTest`.

4. **Segunda pergunta do usuário, mesmo assunto — "cartão de débito também grava em
   `caixa_detalhe` E em `contas_receber`?"** Resposta revelou uma inconsistência: `CARTAO_DEBITO`
   nascia **quitado** em `contas_receber` junto com `AVISTA` (mesmo `pagaNaHora()` de antes),
   mas o usuário esclareceu que débito tem prazo de liquidação de **um dia** — entra no caixa na
   hora (pro fechamento), mas a parcela em `contas_receber` deve ficar **em aberto**, igual
   cartão de crédito, até uma futura tela de **conciliação de cartões** (mencionada como próximo
   passo, ainda não construída) baixar. Corrigido: `pagaNaHora()` (renomeado
   `aceitaApenasUmaParcela` — só regula "aceita 1 parcela só", não mais "já foi recebido") deixou
   de controlar `data_recebimento`/`valor_recebido`/`id_empresa_pagamento`; um novo `jaRecebido`
   local em `gerarEInserirParcelas()` é `true` só pra `AVISTA`. **1 teste novo** em `PdvCrudTest`
   (dinheiro nasce quitado, débito nasce em aberto, mas os dois já entram no caixa).

5. **Tipo de Carteira — grade mantém ordenação (pedido avulso, mesmo dia):** ao visualizar/
   editar um tipo de carteira e voltar (Salvar ou Cancelar), a grade resetava pra página 1/
   ordenação padrão — busca/página/ordenação agora viajam no `state` da navegação (`Link`
   → form → `navigate` de volta) e são restauradas ao montar `TipoCarteiraLista` de novo; um
   `useRef` evita que o `useEffect` que zera a página ao mudar filtro dispare também no restauro
   inicial.

6. **`cfg_banco` ganhou o banco 999 - CAIXA CENTRAL** (pedido avulso) — acrescentado ao seed de
   `V028__financeiro_conta_corrente.sql` + `INSERT` aplicado no banco de dev (aditivo, nenhum
   dado apagado). Colidiu com dois testes que usavam `999` como sentinela de "banco inexistente"
   (`BancoCrudTest.buscaPorCodigoInexistenteResponde404`,
   `ContaCorrenteCrudTest.bancoInexistenteEhRejeitado`) — passaram a existir de verdade e os
   testes começaram a falhar; corrigido trocando o sentinela pra `888` (confirmado fora da lista
   FEBRABAN semeada) nos dois arquivos.

**251 testes de backend verdes no total** (238 + 13 novos: 11 `FechamentoCaixaCrudTest` + 2
`PdvCrudTest`). Verificado ao vivo no navegador: venda real dividida em 4 formas de pagamento
(dinheiro/Pix/débito/crédito) apareceu certa no Fechamento de Caixa; ADMIN consultando o caixa
fechado de outro usuário via select; ordenação da grade de Tipo de Carteira preservada ao
salvar/cancelar. Commitado e pushado (`59c46be`).

### 2026-07-30 — Conta Corrente + Movimentação de Conta Corrente (fecha o módulo `financeiro`)

Sessão seguinte à do Comprovante de Pagamento, mesmo dia. Pedido com o DDL legado colado
(`db/051_CONTA_CORRENTE.txt`/`052_CONTA_CORRENTE_MOVIMENTO.txt`) e 3 pedidos diretos: criar as
tabelas, a tela de cadastro da conta e a tela de lançamento.

1. **Decisão fechada via `AskUserQuestion` antes de codar:** `id_conta_corrente` vira PK de
   negócio (o próprio número da conta, digitado e imutável — mesmo padrão de Plano de Contas),
   não um id sequencial com o número guardado num campo à parte.

2. **Schema (`V028__financeiro_conta_corrente.sql`, novo arquivo — última tabela do `financeiro`
   legado a entrar no v1, §3.3.7):** `conta_corrente` (PK de negócio, FK pra `empresa`, `ativo`)
   e `conta_corrente_movimento` (PK surrogate `localizador`, FK composta pra `conta_corrente` e
   `cfg_plano_contas`, `credito_debito` reaproveitando o ENUM de V013). Diferente de
   `caixa_detalhe`: ganhou `atualizado_em` além de `criado_em`, porque esta tela permite editar/
   excluir um lançamento já gravado (é digitação manual, não efeito colateral automático).

3. **Backend/frontend completos** (`com.vetor.niner.financeiro.contacorrente`): dois CRUDs —
   Conta Corrente (fallback de inativar quando há movimento vinculado, mesmo padrão de
   Fornecedor) e Movimentação (CRUD total). Duas telas novas no menu. 20 testes novos.

4. **Pedido separado, mesmo dia: autopreenchimento do nome do banco.** "Na tela de conta
   corrente, quando entrar com o ID do banco, mostrar o nome do banco" — mesmo padrão do NCM em
   Produtos. `id_banco` deixou de ser texto livre e virou FK de verdade pra `cfg_banco` (nova,
   global, sem RLS, seed de 34 bancos brasileiros comuns **dentro da própria migration** — não
   num script separado como `cfg_produto_ncm`, porque a coluna é `NOT NULL` e a lista precisa
   existir em qualquer ambiente que rode as migrations, inclusive Testcontainers/CI, não só no
   banco de produção carregado à mão. Endpoint `GET /api/v1/bancos/{codigo}`, campo somente-
   leitura ao lado do código no formulário, busca no `onBlur`. +3 testes.

5. **Pedido separado: filtro de Data Inicial/Final na Movimentação.** Aproveitado pra corrigir
   uma inconsistência de tipo que passou despercebida na implementação original — os parâmetros
   tinham sido feitos `OffsetDateTime`/`ISO.DATE_TIME`, diferente do padrão já usado em Estorno
   de Crediário (`LocalDate`/`ISO.DATE`, comparando `::date`). Corrigido pra bater com o padrão
   do resto do projeto. +1 teste.

6. **Pedido separado: filtros de Empresa e Plano de Contas + reordenação da barra de filtros.**
   `idEmpresa` filtra via JOIN em `conta_corrente` (a tabela de lançamento não guarda empresa
   direto). Ordem final pedida explicitamente: Data Inicial → Data Final → Empresa → Plano de
   Contas → Conta Corrente → Documento → Compensado. +1 teste.

7. **Limpeza:** os 3 últimos dumps legados removidos do git — `db/051_CONTA_CORRENTE.txt`,
   `db/052_CONTA_CORRENTE_MOVIMENTO.txt` e `db/100_GERADORES.txt` (já migrados/sem uso). `db/`
   não tem mais nenhum arquivo `.txt` de referência — a conversão do legado Firebird→Postgres
   está com todas as tabelas do `financeiro` no v1.

**238 testes de backend verdes no total** (era 213 no fim da sessão de Comprovante). Verificado
ao vivo no navegador em cada rodada de mudança (autopreenchimento de banco, ordem dos filtros).

**Documentação:** pedido explícito "documente tudo, memorize tudo" — `docs/telas/
conta-corrente.md` e `docs/telas/conta-corrente-movimento.md` (specs novas, formato §5),
`docs/PROGRESSO.md` (esta entrada).

### 2026-07-30 — Comprovante de Pagamento de Crediário (impressão térmica 80mm)

Sessão seguinte à de Abertura de Caixa, no mesmo dia. Pedido com mockup ASCII completo de como
o comprovante deveria ficar (cabeçalho, tabela de parcelas, forma de pagamento, data/
identificação).

1. **Endpoint novo** — `GET /api/v1/recebimento-crediario/{idLoteRecebimento}/comprovante`
   (`RecebimentoCrediarioService.buscarComprovante`): cabeçalho (razão social da empresa, nome
   do cliente, data do pagamento, `id_caixa`), uma linha por parcela (`multaJuros` = diferença
   entre `valor_recebido` e `valor_receber`, congelada no momento do recebimento, nunca
   recalculada) e uma linha por forma de pagamento (soma de `caixa_detalhe.valor` agrupada por
   `id_carteira`). 404 se o lote não existir.

2. **Duas decisões fechadas via `AskUserQuestion` antes de implementar:** (a) dois botões
   separados — "Imprimir" (diálogo nativo do navegador) e "Salvar PDF" (gerado direto, sem
   passar pelo diálogo) — em vez de um botão só; (b) popup abre automaticamente assim que o
   recebimento é efetivado, sem gatilho manual.

3. **Frontend:** `web/src/lib/comprovante.ts` — `montarLinhasComprovante()` monta o comprovante
   como array de linhas de texto monoespaçado, fonte única de verdade reusada pela tela
   (`<pre>`), pela impressão (`window.print()`, CSS isola só o elemento) e pelo PDF
   (`gerarPdfComprovante()`, biblioteca **`jsPDF` nova** — única dependência nova do frontend
   pra essa feature). `ComprovanteRecebimentoModal.tsx` plugado no `onSuccess` do `efetivar` em
   `RecebimentoCrediario.tsx`.

4. **Layout revisado 3 vezes depois do primeiro corte**, cada pedido curto e direto: (a) mesa de
   ~70 colunas do mockup original não cabe fisicamente numa bobina de 80mm em fonte legível
   (máx. ~42-48 colunas) — reorganizado em blocos por parcela; (b) totalizador "Total Pago"
   entra depois da lista de formas de pagamento, e "Data Pagamento"/"Identificação" viram o
   rodapé final (antes ficavam no meio, antes da lista de formas de pagamento); (c) "Valor a
   Pagar" (total geral) renomeado pra "Total a Pagar" pra não ficar homônimo do "Vlr. a Pagar"
   de cada parcela; (d) separadores `-`/`.` trocados por `—` (travessão) e `•` (marcador) —
   escolhidos por ficarem dentro do WinAnsiEncoding (CP1252) que a fonte padrão do `jsPDF`
   desenha sem precisar embutir uma fonte TTF nova (caracteres de desenho de caixa de verdade,
   tipo ─/═, ficariam quebrados no PDF).

5. **CSS de impressão pra bobina física** (pedido do usuário: "lembrando que vai sair numa
   impressora térmica de fita, 80mm") — `@page { size: 80mm auto; margin: 0; }` (a impressão sem
   isso tentava encaixar no tamanho de página padrão do sistema, A4/Carta) + fonte de impressão
   reduzida (9px) com margem de 3mm, deixando as 42 colunas com folga real dentro dos 80mm
   físicos (cálculo, ainda sem teste numa impressora térmica real).

6. **Testes:** 2 novos em `RecebimentoCrediarioCrudTest`
   (`comprovanteTrazCabecalhoParcelasEFormasDePagamento`, incluindo verificação da fórmula de
   multa/juros congelada; `comprovanteDeLoteInexistenteResponde404`). **Suíte completa: 213/213
   verdes.**

7. **Verificação ao vivo:** efetivado um recebimento de teste real via navegador (dados
   sintéticos criados/apagados no banco de dev, cliente real preservado) — achado e corrigido no
   processo: **o container da API não tinha sido reconstruído** depois do endpoint novo, dando
   404 (endpoint nem existia no jar rodando); rebuild resolveu. Aproveitado pra também corrigir
   um bug real do popup (ficava preso em "Carregando…" pra sempre se a busca falhasse, sem
   mostrar erro nenhum). "Salvar PDF" testado sem erro de console.

**Documentação:** pedido explícito "documente, memorize, faça commit e push" — `docs/PROGRESSO.md`
(esta entrada), `docs/telas/comprovante-recebimento-crediario.md` (spec nova, formato §5) e
`docs/telas/recebimento-crediario.md` (contrato de API + referência à spec nova).

### 2026-07-30 — Abertura de Caixa (tela nova + popup obrigatório no PDV/Recebimento de Crediário)

Sessão iniciada com um pedido pontual de schema, que virou o gatilho da feature completa.

1. **Remoção de `saldo_inicial`/`saldo_final` de `caixa_mestre`.** Pedido direto do dono do
   produto, sem contexto adicional na hora — editado `V025__financeiro_caixa_crediario.sql`
   (banco em construção) e aplicado com `ALTER TABLE` no banco de dev. Havia uma linha órfã de
   teste (`id_caixa=9`, sem `caixa_detalhe` vinculado, resíduo de sessão anterior) que precisou
   ser apagada antes do `ALTER COLUMN ... SET NOT NULL` conseguir rodar — `FORCE ROW LEVEL
   SECURITY` escondia a linha da sessão sem `app.id_tenant` setado, foi preciso conectar como
   `postgres` (superuser) pra enxergá-la.

2. **Pergunta de esclarecimento: "quando vai efetivar venda/receber crediário e o caixa não está
   aberto, o que faz?"** Investigação no código revelou dois comportamentos distintos e nenhum
   deles satisfatório: o PDV nunca checava `caixa_mestre` (venda não toca em caixa até hoje); o
   Recebimento de Crediário abria um caixa sozinho, em silêncio, sempre com saldo zero
   (`RecebimentoCrediarioService.idCaixaAberto`, comportamento de 2026-07-29). Resposta ao dono
   do produto documentada, sem mudança de código ainda.

3. **Pedido direto, em 3 partes: criar tela de Abertura de Caixa; PDV e Recebimento de Crediário
   passam a checar/abrir o caixa quando necessário.** Duas decisões fechadas via
   `AskUserQuestion` antes de implementar: (a) saldo inicial é **uma linha só** (moeda + valor),
   não um split-tender de várias moedas — confirma por que a coluna do item 1 tinha sido
   removida, o desenho certo era reintroduzi-la já com FK pra `tipo_carteira`; (b) o popup de
   abertura aparece **ao entrar na tela** do PDV/Recebimento (não só ao tentar efetivar).

   **Schema:** `caixa_mestre` ganha de volta `id_carteira integer NOT NULL` (FK composta pra
   `tipo_carteira`) + `saldo_inicial numeric(12,2) NOT NULL DEFAULT 0` — mesmo arquivo `V025`,
   mesma convenção de banco em construção.

   **Backend:** módulo novo `financeiro.caixa` — `CaixaService`/`CaixaController`/`CaixaDtos`,
   três endpoints (`GET /status`, `GET /carteiras`, `POST /abrir`). `PdvVendaService` e
   `RecebimentoCrediarioService` passam a chamar `CaixaService.idCaixaAbertoObrigatorio()` antes
   de gravar qualquer coisa — 400 se não houver caixa aberto hoje pro usuário/empresa.
   `RecebimentoCrediarioService.idCaixaAberto()` (a abertura automática e silenciosa) foi
   **removido** — não existe mais abertura implícita em lugar nenhum do sistema.

   **Frontend:** tela nova `AberturaCaixa.tsx` (rota `/abertura-caixa`, item de menu ao lado do
   PDV, ícone novo `IconeCaixa`) — mostra os dados da abertura de hoje se já existir, senão o
   formulário (moeda + saldo inicial, "Dinheiro" pré-selecionado por nome). Popup obrigatório
   `AberturaCaixaModal.tsx` (mesmo formulário, sem opção de fechar — só "Voltar", que sai da
   tela) reaproveitado dentro de `Pdv.tsx` e `RecebimentoCrediario.tsx`: as duas telas checam
   `GET /caixa/status` ao carregar e bloqueiam a interação (inclusive os atalhos de teclado do
   PDV) enquanto `aberto = false`. Campos do formulário fatorados em `CamposAberturaCaixa.tsx`,
   reusado pela tela dedicada e pelo popup.

   **Testes:** `CaixaCrudTest` novo (6 testes: status inicial, listagem de carteiras, abertura
   com sucesso, abertura duplicada no mesmo dia → 409, carteira inexistente → 400, isolamento
   entre tenants). Mais 2 testes novos (`vendaSemCaixaAbertoRespondeErroDeValidacaoENaoGravaNada`
   em `PdvCrudTest`, `efetivarSemCaixaAbertoRespondeErroDeValidacaoENaoGravaNada` em
   `RecebimentoCrediarioCrudTest`). Os 17 testes de venda de `PdvCrudTest` e os 8 de recebimento
   de `RecebimentoCrediarioCrudTest` que esperavam sucesso ganharam uma chamada de abertura de
   caixa no setup (helper `abrirCaixaDinheiro`, usa o tipo de carteira "DINHEIRO" semeado no
   signup) — sem isso, todos quebrariam com a checagem nova. Mais 3 arquivos de teste
   (`PlanoContasCrudTest`, `TipoCarteiraCrudTest`, `UsuarioCrudTest`) que inseriam `caixa_mestre`
   direto via SQL bruto (pra testar bloqueio de exclusão por FK) precisaram passar a informar
   `id_carteira`, agora `NOT NULL`. **Suíte completa: 211/211 testes verdes.**

   **Verificação ao vivo:** navegador confirmou o popup bloqueando PDV/Recebimento com caixa
   fechado, o formulário abrindo o caixa com sucesso, e as duas telas liberando a interação
   depois. (Nota à parte: a extensão Dark Reader do Chrome usado na verificação causou uma
   renderização visual estranha — sem `background` — no botão "Abrir Caixa" dentro do popup;
   confirmado via `getComputedStyle`/inspeção do DOM que é artefato da extensão, não bug do CSS
   da aplicação — o mesmo botão, mesma classe `.btn`, renderiza normalmente na tela dedicada.)
   Caixa de teste aberto durante a verificação foi apagado do banco de dev ao final.

**Documentação:** pedido explícito "documente e memorize" ao final da sessão — este registro,
`docs/telas/abertura-caixa.md` (spec nova, formato §5) e atualização de
`docs/telas/pdv.md`/`docs/telas/recebimento-crediario.md` com a exigência de caixa aberto.

### 2026-07-29 — Transferência de Produtos (filtros/ordenação/exclusão/popup), estoque negativo permitido em todo o sistema, e Recebimento de Crediário (módulo novo)

Sessão longa, quatro pedidos do dono do produto em sequência, cada um puxando o seguinte:

1. **Transferência de Produtos — filtros, coluna Nº, botão de excluir (primeira rodada).**
   Pedido: "colocar filtros de pesquisa (Data Inicial/Final, Nº da Transferência, Empresa
   saída/entrada), Nº na grid, ordenação em todas as colunas, opção de excluir toda a
   transferência". Antes de implementar a exclusão, levantei o conflito com a spec aprovada
   (`docs/telas/transferencia-estoque.md` documentava "sem cancelamento/estorno pela tela" como
   non-goal explícito, apoiado em P3/imutabilidade do ledger) e perguntei como deveria se
   comportar. Resposta direta: **excluir de verdade, sempre devolvendo a quantidade pra
   origem, mesmo sem saldo suficiente no destino** (saldo fica negativo). Implementado:
   `TransferenciaService.listar()` ganhou filtros dinâmicos + `COLUNAS_ORDENAVEIS` (incluindo
   `numero`, novo); `TransferenciaService.excluir()` apaga o detalhe do ledger da transferência
   (a trigger `fn_atualiza_estoque_movimento` já existente devolve/retira o saldo sozinha, sem
   checagem) e o cabeçalho `produto_transferencia` — `produto_movimento_mestre` continua
   fisicamente impossível de apagar (`REVOKE DELETE`, V024), os dois cabeçalhos ficam órfãos.
   Testado ao vivo: transferência com saldo insuficiente na origem foi aceita, e a exclusão
   reverteu certinho até com o destino ficando negativo. `docs/telas/transferencia-estoque.md`
   atualizada (seções "Exclusão de transferência" e Non-goals revisada).

2. **"Não se importe com estoque negativo" — regra geral pro sistema inteiro.** Pedido em
   caixa alta, literal: "EM QUALQUER MOVIMENTACAO DE PRODUTO, SEJA POR ENTRADA OU POR SAIDAS,
   NAO SE IMPORTE COM ESTOQUE NEGATIVO". Removida a checagem de `produto_estoque.disponivel`
   de `PdvVendaService.resolverItens()` (PDV) e `TransferenciaService.resolverItens()`
   (Transferência) — as duas rotinas de movimentação de saída existentes no sistema. Front da
   Transferência também parou de bloquear o botão "Confirmar" ou mostrar aviso quando a
   quantidade excede o estoque da origem. Dois testes que verificavam o bloqueio antigo foram
   reescritos pra verificar o comportamento novo (venda/transferência aceita, saldo fica
   negativo) em vez de removidos — mantendo a cobertura. Testado ao vivo: venda de 20 unidades
   com 12 em estoque foi aceita, saldo caiu pra -8. **179 testes verdes** depois desta rodada.

3. **Transferência de Produtos — popup de destino, título, alinhamento, totais (segunda
   rodada, mesmo dia).** Quatro ajustes pontuais testados um a um: popup (`EscolherDestinoModal.
   tsx`) pedindo a empresa de destino antes de abrir a tela de produtos, em vez do select
   embutido de antes; título "Nova Transferência · origem → destino" na mesma linha; o campo
   Código de Barras e o botão "Pesquisar Produto" estavam desalinhados verticalmente (o botão
   alinhava pelo fundo da coluna mais alta, que incluía o texto de dica abaixo do input) —
   corrigido com um rótulo invisível do mesmo tamanho do rótulo real, garantindo que os dois
   comecem exatamente na mesma linha; totais (Quantidade/Valor Total) que eram uma caixa solta
   sem relação com as larguras da tabela viraram uma linha `<tfoot>` de verdade dentro da
   própria tabela — alinhamento pixel-perfeito garantido por serem literalmente as mesmas
   colunas (mesmo padrão já usado no Histórico do Cliente). Campo Observações, que nunca tinha
   sido usado, foi removido nessa reescrita.

4. **Recebimento de Crediário — módulo novo (`financeiro.recebimentocrediario`).** O dono do
   produto colou uma spec completa por escrito (Problema/Fluxo/Layout/RN001–RN013/Tabelas
   envolvidas/Observações técnicas). Antes de implementar, investiguei o schema existente e
   achei que boa parte já estava pronta desde 2026-07-16/22 esperando por essa feature:
   `contas_receber.id_lote_recebimento`/`id_empresa_pagamento`, `contas_receber_detalhe`,
   `caixa_mestre`/`caixa_detalhe` (com `RECEBIMENTO_PARCELA_CREDIARIO` já no enum), e os quatro
   campos de juros/multa em `cfg_geral` (já editáveis em Parâmetros do Sistema, rótulos "Juros/
   Multa após (dias)" — confirmando de antemão a leitura de carência). Faltava só `tipo_
   carteira.permite_receber_crediario` (RN007). Como a fórmula exata de multa/juros e o desenho
   da forma de pagamento (uma linha só ou split-tender como o PDV) não estavam 100% explícitos
   na spec, perguntei antes de codar — confirmado: multa é percentual único após a carência
   (não cresce), juros é percentual **ao dia** após a carência (cresce), e split-tender como o
   PDV (múltiplas formas, sem a lógica de desconto/acréscimo da carteira, que não se aplica
   aqui). Implementado: DB (`tipo_carteira.permite_receber_crediario` + tabela nova `contas_
   receber_lote`, cabeçalho/gerador real de `id_lote_recebimento`, mesmo padrão de `produto_
   transferencia`); módulo backend novo com busca de cliente, listagem de parcelas (multa/juros
   recalculados a cada chamada), listagem de carteiras permitidas, e efetivação transacional
   que trava cada parcela (`FOR UPDATE`, protege contra recebimento duplo), recalcula tudo de
   novo no servidor, aloca pagamentos em FIFO por vencimento (uma parcela pode ser paga por
   mais de uma forma), abre o caixa automaticamente se não houver um aberto, e grava tudo numa
   transação só. Frontend: tela nova com busca/grid/pagamento, item no menu, ícone próprio.
   **13 testes novos** cobrindo as RNs principais — precisou também corrigir helpers de teste
   em `PdvCrudTest`/`ClienteHistoricoCrudTest` que criavam tipo de carteira sem o campo novo
   obrigatório. **193 testes verdes no total.** Verificado ao vivo no navegador com um cliente
   real do banco de dev (parcelas vencidas e a vencer) — multa/juros bateram exatos com a
   fórmula, split-tender dividiu uma parcela entre DINHEIRO e HIPER débito corretamente, caixa
   abriu sozinho; conferido linha a linha no banco depois e revertido pra não sujar o ambiente.
   Documentado em `docs/telas/recebimento-crediario.md` (spec nova, formato §5 da spec-mãe).

5. **Recebimento de Crediário — redesenho de layout em várias rodadas, mesmo dia, sessão
   seguinte (pedidos curtos e diretos do dono do produto, um puxando o outro).** (a) Grid de
   "Parcelas em Aberto" redesenhada conforme mockup ASCII: coluna **Empresa** nova (código de
   2 dígitos), "Parcela" virou **"Nº PC"** no formato `parcela/totalParcelas` (ex. "02/06" —
   o total é escopado a `(id_venda, id_carteira)`, não à venda inteira, já que uma venda pode
   ter mais de um plano de pagamento independente), **Multa** e **Juros** fundidas numa única
   coluna "Multa + Juros"; rodapé de 5 caixas virou uma **barra lateral fixa** (Selecionadas/
   Valor Selecionado/Valor Recebido) ao lado da grid — pedido explícito "scroll apenas na grid
   de parcelas a receber", resolvido com container flex de altura travada (`.recebimento-
   parcelas-layout`) e só o `.table-wrap` da grid rolando por dentro. (b) Os 3 botões de
   categoria (À Vista/Cartão Débito/Cartão Crédito) saíram da barra lateral — no lugar, um
   único botão **"Receber"**; ao clicar, abre um popup (`EscolherFormaPagamentoModal.tsx`) que
   já pede a categoria **e** deixa definir Tipo de Carteira/Valor Pago no mesmo popup (revisão
   sobre a primeira versão do popup, que só escolhia a categoria e mandava pra um formulário à
   parte na tela principal) — "Voltar" dentro do popup retorna à lista de categorias sem
   fechar; "Adicionar Pagamento" lança o pagamento e fecha. A tela principal só lista os
   lançamentos já feitos (split-tender continua igual — clicar "Receber" de novo abre outro
   pagamento se o saldo não fechou). (c) Tela inteira comprimida pra caber sem scroll de
   página (mesmo truque de `.historico-coluna-esquerda`/`.table-wrap` já usado no Histórico do
   Cliente): `.recebimento-card` vira `display:flex;flex-direction:column;height:100%` dentro
   de `.lista-corpo`, só a seção de parcelas cresce (`flex:1`), e a barra lateral ganhou um
   `min-height` reservado (220px) pra nunca cortar o botão "Receber" quando a grid encolhe.
   (d) Formas de pagamento já lançadas (lista abaixo da grid) viraram **cards em grade** em vez
   de tabela — 3 colunas primeiro, depois **4 colunas** (padding/gap menores, fonte igual, pra
   caber mais uma). (e) Botão final do topo (que efetiva o recebimento) renomeado de "Receber"
   pra **"Efetivar Recebimento"** — desambiguando do botão "Receber" da barra lateral, que só
   abre o popup; botão "Cancelar" da tela de busca (antes de escolher cliente) virou
   **"Voltar"**. (f) Botão "Trocar" redundante ao lado do nome do cliente removido (já existe
   "Trocar Cliente" no topo). **Bug real encontrado e corrigido durante a verificação ao vivo**
   (não é do código, é de higiene de dados de teste): uma parcela testada numa sessão anterior
   deste mesmo dia ficou com uma linha órfã em `contas_receber_detalhe` (chave primária
   `(id_tenant, id_conta_receber)`) porque a reversão manual da transação de teste no banco de
   dev não tinha apagado essa tabela — só `contas_receber_lote`/`caixa_mestre`/`caixa_detalhe`/
   `contas_receber`. Um teste seguinte que caiu na mesma parcela com uma carteira de cartão
   dominante bateu de novo na mesma PK e o `GlobalExceptionHandler` traduziu a violação de
   integridade genérica pro texto fixo "Registro em uso por outro cadastro — não pode ser
   excluído" (mensagem pensada pra bloqueio de exclusão, reaproveitada por engano pra qualquer
   `DataIntegrityViolationException`, inclusive em INSERT). **Lição pra qualquer reversão manual
   futura de um recebimento de teste:** apagar as 4 tabelas, não só 3 —
   `contas_receber_detalhe` também, sempre que houver pagamento de cartão na alocação. Sem
   mudança de código nesta parte (comportamento correto — só o dado de teste estava sujo);
   frontend/CSS/`EscolherFormaPagamentoModal.tsx` são os únicos artefatos alterados nesta rodada.

6. **Estorno de Recebimento de Crediário — tela irmã nova (`financeiro.recebimentocrediario`,
   mesmo módulo backend, sem package novo).** Pedido direto do dono do produto: precisa de uma
   rotina pra estornar um recebimento já efetivado. Regra de negócio central, dita
   explicitamente: um lote pode cobrir parcelas de vendas/contratos diferentes recebidas juntas
   na mesma operação — **estornar uma parcela exige estornar todas as do mesmo lote**. Antes de
   codar, usei `AskUserQuestion` (3 perguntas; o dono do produto respondeu diferente do
   recomendado em duas delas): (1) listagem por lote inteiro, não por parcela — recomendado,
   escolhido, já que a regra "estornou uma, estorna todas" fica automática por construção; (2)
   quem pode estornar — **ADMIN e OPERADOR** (não o recomendado, que era só ADMIN — mesmo nível
   de acesso da tela de Recebimento); (3) o que sobra do lote depois — **apaga fisicamente** (não
   o recomendado, que era manter com marca de estornado — mesmo padrão já usado na exclusão de
   Transferência de Produtos). Implementado: `GET /api/v1/recebimento-crediario/estornos?
   nomeCliente=&dataInicial=&dataFinal=` (nome do cliente obrigatório, 400 se vazio; lista cada
   lote com qtd. de parcelas e as formas de pagamento usadas, via `string_agg` sobre
   `caixa_detalhe`/`tipo_carteira`) e `POST /api/v1/recebimento-crediario/estornos/
   {idLoteRecebimento}` (transacional: trava lote e parcelas, apaga `contas_receber_detalhe`
   **antes** de limpar `id_lote_recebimento` — ordem importa, é o mesmo achado do item 5 —,
   reabre todas as parcelas do lote numa única `UPDATE`, apaga `caixa_detalhe` do lote e por fim
   o cabeçalho `contas_receber_lote`; nunca mexe em `caixa_mestre`, que pode ter lançamentos de
   outros lotes do mesmo dia/usuário/empresa; 409 se o lote não existir ou já tiver sido
   estornado). Tela nova `pages/recebimentocrediario/EstornoRecebimentoCrediario.tsx` — mesmo
   esqueleto de `TransferenciaLista.tsx` (filtros no topo, tabela, modal de confirmação antes da
   ação irreversível), sem paginação nem colunas ordenáveis (lista naturalmente pequena, escopo
   por cliente). Ícone novo (`IconeEstornoRecebimentoCrediario`) + ícone de ação por linha
   (`IconeEstornar`) + item de menu "Estorno de Crediário". **Pedido em seguida, mesmo dia:**
   botão "visualizar" (ícone verde, olho) ao lado do "estornar" em cada linha — abre um popup
   só-leitura com as parcelas daquele lote (Nº Venda, Nº PC, Vencimento, Valor Recebido) pra
   decidir se vale a pena estornar antes de confirmar; endpoint novo `GET /estornos/
   {idLoteRecebimento}/parcelas` (`readOnly`, não trava nada — diferente do `estornarLote`).
   **7 testes novos** (`RecebimentoCrediarioCrudTest`, agora 20 no arquivo): listar sem nome
   rejeitado, listar filtra por nome+data e mostra qtd/formas corretas, estornar reabre todas as
   parcelas de um lote de 2 vendas diferentes (não só uma) e apaga detalhe-de-cartão/caixa/lote,
   estornar lote inexistente 409, estornar duas vezes 409 na segunda, isolamento entre tenants, e
   listar parcelas de um lote pra visualização. **200 testes de backend verdes no total.**
   Verificado ao vivo com cuidado extra: o dono do produto já tinha usado a tela de Recebimento
   sozinho durante a própria sessão, criando lotes reais — em vez de estornar os dele pra testar,
   criei venda/parcela sintéticas só minhas, testei o fluxo completo (receber → visualizar →
   estornar) nelas, e apaguei depois, sem tocar nos lotes reais do usuário. Documentado em
   `docs/telas/estorno-recebimento-crediario.md` (spec nova, mesmo formato §5).

**Tudo acima commitado e pushado** (`f332aee`, pedido explícito "faca um commit e um push" —
36 arquivos, 4138 inserções).

7. **Dois ajustes pontuais de UI, pedidos em mensagens curtas separadas, sessão seguinte.** (a)
   Item de menu "Estoque" renomeado pra **"Transferência de Produtos"** (rota `/estoque` e ícone
   mantidos — só o rótulo mudou, pra refletir que hoje o menu só tem a rotina de transferência,
   não um módulo de estoque mais amplo). (b) Cadastro de **Usuário** — "Empresas com acesso"
   (checklist de `usuario_empresa`) deixou de ser uma lista vertical (`.lista-categorias`, um
   `.checkbox-linha` por linha) e virou uma **grade de quadros** (`.checklist-empresas-grid`/
   `.checklist-empresa-card`, um quadro clicável por empresa) — pedido porque, com muitas
   empresas cadastradas, a lista vertical exigia scroll dentro do formulário. Primeiro veio como
   4 colunas, depois ajustado pra **6 colunas** no mesmo pedido seguinte. **Commitado e pushado**
   (`f35527f`).

8. **Novo parâmetro "Permite quantidade decimal para produtos" (`cfg_geral.cfg_permite_qtd_decimal`)
   + revisão de todo lugar que mostra quantidade de produto.** Pedido direto: criar a
   configuração e depois "revisar em todo o projeto onde mostra a quantidade de produto... se
   não mostra quantidade decimal, sempre vai ser inteiro, ou se mostra qtd decimal, vai mostrar
   com 3 casas". Investigação prévia (agente Explore) mapeou todo o projeto: **todo campo de
   quantidade já era `BigDecimal`/`numeric(14,3)`** de ponta a ponta (schema, DTOs Java,
   validação `@DecimalMin("0.001")`) — decimal já era tecnicamente aceito em qualquer lugar,
   `TransferenciaForm.tsx` inclusive **reaproveitava a máscara de peso** (`mascararPeso`, 3
   casas) pra quantidade, sem separação semântica; só não existia uma forma de desligar isso.
   Implementado: coluna nova (`V023__cfg_geral.sql`, editada, `DEFAULT true` — preserva o
   comportamento de sempre pra quem não mexer no parâmetro); endpoint aberto a qualquer papel
   `GET /api/v1/config-geral/permite-qtd-decimal` (mesmo padrão de `/flags-variante`/
   `/desconto-venda`, PDV/Transferência/Histórico não são ADMIN); **validação também no
   servidor** (`PdvVendaService`/`TransferenciaService` rejeitam com 400 uma quantidade
   fracionária quando o parâmetro está desligado — defesa em profundidade, não só máscara de
   tela); frontend ganhou `mascararQuantidade`/`completarQuantidade`/`desmascararQuantidade`/
   `formatarQuantidade` (`web/src/lib/masks.ts`, 3 casas ou inteiro puro conforme o parâmetro) e
   todo lugar que mostra/edita quantidade passou a usar essas funções: campo de quantidade da
   Transferência (agora bloqueia a vírgula de verdade quando desligado, testado ao vivo digitando
   "2,5" e virando "25"), estoque por empresa/total na Pesquisa de Produto do PDV, "Quantidade"/
   "Qtd Itens" do PDV, `AlteraQuantidadeModal`, "Qtd Vendida" do Histórico do Cliente, e o
   detalhe/rodapé da Transferência. Checkbox novo na seção "Estoque" de Parâmetros do Sistema.
   **3 testes novos** (`PdvCrudTest`/`TransferenciaCrudTest`, quantidade fracionária rejeitada
   com o parâmetro desligado) + ajustes nos 4 arquivos de teste que montavam o corpo de
   `PUT /config-geral` na mão (`ConfiguracaoGeralTest`, `PdvCrudTest`, `ProdutoCrudTest`,
   `RecebimentoCrediarioCrudTest`) pro campo novo `@NotNull`. **203 testes de backend verdes no
   total.** Verificado ao vivo: parâmetro desligado bloqueou a vírgula na Transferência,
   estoque/quantidade mostraram inteiro em todo lugar, parâmetro religado voltou tudo a 3 casas.

9. **Sintaxe "quantidade\*código" no campo de código de barras (PDV e Transferência de
   Produtos), mesma sessão.** Pedido direto com exemplo: "5\*9001000000138" deve lançar o item
   já com quantidade 5, em vez de sempre 1 (situação real: cliente leva 5, 10 ou mais unidades
   do mesmo produto, hoje precisava ler/digitar o código repetidas vezes). Implementado:
   `interpretarCodigoBarras()` novo em `web/src/lib/pdv.ts` (regex `^(\d+)\*(.+)$` — sem "\*", o
   valor inteiro é o código e a quantidade é 1, comportamento de sempre) reaproveitado tanto no
   PDV (`Pdv.tsx`) quanto na Transferência (`TransferenciaForm.tsx`); scanear de novo o mesmo
   código (com ou sem multiplicador) continua somando na quantidade existente em vez de duplicar
   a linha. Dica discreta nova sob o campo de código de barras nas duas telas ("Dica: '5\*código'
   lança direto com quantidade 5"), pedido explícito do dono do produto. Verificado ao vivo nas
   duas telas: "5\*9001000000138" lançou quantidade 5 (R$800,00), leitura seguinte do código puro
   somou +1 (foi a 6); "10\*9001000000138" no PDV lançou quantidade 10 (R$1.600,00) — testes
   limpos sem confirmar/efetivar, sem gravar nada no banco.

**Tudo acima (itens 8 e 9) commitado e pushado.**

### 2026-07-28 — Tela de Usuários, login com escolha de empresa, único ADMIN por tenant (imutável) e Transferência de Estoque entre Empresas

Continuação da mesma sessão de PDV, depois de uma pausa — quatro pedidos diretos do dono do
produto, em sequência, cada um puxando o seguinte:

1. **Tela de Usuários (nova, `identidade.usuario`, docs/telas/usuario.md):** primeiro CRUD de
   usuário do sistema — antes só existia via signup. Restrita a `ADMIN`
   (`UsuarioService.exigirAdmin`, mesmo mecanismo de `configuracao.geral`) — diferente do resto
   de `cadastros`, onde `OPERADOR` também tem acesso. Campos: ativo, nome, e-mail (único por
   tenant, `usuario_email_uk`), senha (obrigatória ao criar, opcional ao editar — em branco
   mantém a atual), e **seleção de empresas com acesso** — pedido explícito do dono do produto
   de que um usuário pode acessar **uma ou várias** empresas (não é 1:1). Nova tabela N:N
   `usuario_empresa` (`id_tenant`/`id_usuario`/`id_empresa`, `ON DELETE CASCADE` no usuário)
   dentro de `V015__identidade_usuario.sql` (banco em construção, editada em vez de nova
   migration) + RLS em `V024`. Exclusão nunca permite apagar a própria conta (400) e cai pra
   inativar se houver `caixa_mestre` vinculado (módulo de caixa ainda sem tela própria, mas a
   FK já existe desde V025). **Bug pego pelos próprios testes:** `EmpresaService.listar()`
   inicialmente não filtrava por `id_tenant` explicitamente (só RLS) — sob Testcontainers
   (superusuário, ignora RLS mesmo com `FORCE`) isso vazava empresas de outros tenants;
   corrigido com filtro explícito, mesma defesa em profundidade do resto do projeto.
2. **Só um ADMIN por tenant, para sempre — revisão em duas voltas.** Primeiro pedido: "só um
   admin pode promover/rebaixar outro usuário" (`UsuarioService.
   exigirNaoAlterarPropriaAdministracao`, bloqueava o próprio ADMIN de mudar seu nível).
   Motivado por um incidente ao vivo: o `administrador` do usuário de teste principal
   (`teste@niner.dev`) virou `false` no banco de dev durante os testes manuais da tela nova —
   corrigido via `UPDATE` direto. Na sequência, pedido final e mais simples: **removida** a
   regra de "só outro admin pode mudar" e substituída por "o campo `administrador` nem existe
   mais no formulário/request desta tela" — `UsuarioService.criar` sempre grava `false`,
   `atualizar` nunca toca essa coluna, o único ADMIN nasce no signup e o privilégio é
   permanente (garantido também no banco: `usuario_um_admin_uk`, índice único parcial `WHERE
   administrador = true`, V015). `UsuarioForm.tsx` mostra um badge somente-leitura em vez de
   checkbox quando o usuário editado é o admin.
3. **Login com escolha de empresa (novo, docs/telas/login-empresa.md):** quando o usuário tem
   acesso a mais de uma empresa, `POST /api/publico/login` devolve `{token:null,
   escolherEmpresa:true, empresas:[...]}` em vez do token; reenviando as mesmas credenciais +
   `idEmpresa` escolhido (sempre validado contra a lista de acesso do usuário, mesmo com uma
   empresa só), vem o token. JWT ganhou o claim `eid` (empresa ativa da sessão) —
   `TokenService.emitir` passou a exigir `idEmpresa`. `SignupService.assinar` corrigido pra
   também inserir a linha em `usuario_empresa` do ADMIN recém-criado (sem isso o primeiro login
   já cairia em "usuário sem empresa vinculada"). **Todo serviço que grava `id_empresa` num
   INSERT foi retrofitado pra usar `eid` do JWT em vez de "primeira empresa do tenant"**
   (auditoria completa do código): `PdvVendaService.efetivarVenda` e
   `FuncionarioService.criar`. `GET /api/v1/eu` ganhou o campo `empresa: {idEmpresa, nome}`;
   `Layout.tsx` mostra o nome da empresa ativa no header, entre a marca e "Sair". `Login.tsx`
   ganhou uma segunda etapa (lista de botões, um por empresa) quando a API pede escolha.
4. **Transferência de Produtos Entre Empresas (nova, `estoque.transferencia`,
   docs/telas/transferencia-estoque.md) — primeira feature real do menu Estoque** (era só "Em
   breve"). Pedido direto: "a empresa de saída sempre vai ser a empresa logada, tem que pedir a
   empresa de destino". Nova tabela `produto_transferencia` (cabeçalho: origem, destino,
   usuário, data, observações) dentro de `V019__estoque.sql`, dando finalmente um uso real ao
   campo `produto_movimento_mestre.id_transferencia` que existia como placeholder desde a V019
   original ("número vindo de um gerador externo, proposital, sem tabela ainda"). Uma
   transferência grava, na mesma transação, **dois** `produto_movimento_mestre`
   (`tipo_movimento = 'TRANSFERENCIA'`, um por empresa) com o mesmo `id_transferencia`, e um
   `produto_movimento_detalhe` 'D' (sai da origem) + 'C' (entra no destino) por produto — a
   trigger `fn_atualiza_estoque_movimento` (já existente, inalterada) atualiza
   `produto_estoque` dos dois lados sozinha, nenhuma lógica de saldo nova foi escrita. Checagem
   de estoque disponível na origem antes de qualquer INSERT (mesmo padrão de
   `PdvVendaService.resolverItens`, P1) — estoque insuficiente responde 409. Aberto a `ADMIN` e
   `OPERADOR` (operação do dia a dia, não é sensível como Usuários). Frontend reaproveita
   `PesquisaProdutoModal` do PDV direto (já mostra estoque por empresa) e as máscaras de peso
   (`mascararPeso`/3 casas) pro campo de quantidade. **Dois bugs pegos ao vivo:** `credito_debito`
   é um ENUM do Postgres — passar `"D"`/`"C"` como bind param sem `::credito_debito` explícito
   dava erro de tipo (funciona como literal `'D'` fixo no `PdvVendaService` porque lá o valor
   não varia por chamada; aqui varia, então precisa do cast); `<textarea>` nunca tinha estilo
   global no projeto (só `input, select` em `styles.css`) — o campo Observações foi o primeiro
   textarea do sistema, corrigido adicionando `textarea` na mesma regra.

**179 testes verdes no total** (`UsuarioCrudTest` 11, `LoginEmpresaTest` 3,
`TransferenciaCrudTest` 6, novos — mais os ajustes nos testes existentes de config-geral que
precisaram do claim `eid` novo). Tudo verificado ao vivo no navegador (criação de usuário com
múltiplas empresas, login pedindo escolha, transferência de estoque com saldo conferido via SQL
direto e depois limpo). **Commitado e pushado** — `c200642`, junto com todo o volume
acumulado do dia inteiro (PDV completo + fusão `tipo_carteira`/`moeda` + split-tender + estas
quatro features).

### 2026-07-28 — PDV: F5/F6 renomeados, layout com Cliente/Vendedor obrigatórios, fórmula de desconto fechada de vez

Continuação da mesma sessão de PDV, em várias rodadas curtas de feedback direto do dono do
produto testando ao vivo:

1. **F6 Efetiva Venda (era F5) + F5 Devolver Produto (novo, reservado):** botão grande virou F6;
   novo botão F5 na mesma linha de F2–F4, sem funcionalidade ainda (só o atalho reservado, com
   `preventDefault` já implementado pra não deixar o F5 físico recarregar a página quando a
   função for codificada).
2. **Ajustes de UX no resumo:** fonte maior pra "Valor Total da Venda"; foco automático no campo
   `%` do Desconto Gerencial ao abrir a tela; escolher uma carteira com desconto já pré-preenche
   "Valor Pago" com o valor ajustado; o teto de valor pago passou a valer pra **qualquer**
   carteira (antes só as com desconto tinham limite).
3. **Fórmula de desconto por forma de pagamento — fechada definitivamente após 3 tentativas na
   mesma sessão** (documentado em detalhe em `docs/telas/pdv.md`, seção "Teto de valor pago...",
   pra não reabrir por engano): a fórmula final é **desconto sempre sobre o valor pago**
   (`descontoLinha = valorPago × percDesconto/100`, a mesma de antes) **com o teto calculado ao
   contrário dela** (`valorPagoMaximo = saldoRestante ÷ (1 + percDesconto/100)`, não mais por
   subtração simples). Confirmado com exemplo do dono do produto: pagar R$78 numa carteira de
   10% de desconto tem que dar R$7,80 de desconto (10% de 78), não um valor calculado sobre o
   que está sendo coberto.
4. **Bug corrigido: "Valor a Pagar" não caía conforme as formas de pagamento eram lançadas** —
   só descontava o Desconto Promocional acumulado, esquecendo de subtrair o que já tinha sido
   pago. Corrigido pra usar a mesma soma de coberturas que já existia internamente pro cálculo
   de fechamento do saldo.
5. **Layout da tela de Forma de Pagamento redesenhado** conforme mockup do dono do produto:
   resumo à esquerda + os 4 botões de categoria empilhados à direita (sempre visíveis, não somem
   mais ao escolher uma categoria); caixa abaixo com a lista de pagamentos já lançados (ou o
   formulário de uma linha em edição); rodapé fixo com Cancelar/Confirmar, sempre visível.
6. **Cliente e vendedor da venda (novo, deixou de ser non-goal):** ambos **obrigatórios** pra
   confirmar a venda. Cliente busca por nome/CPF-CNPJ/celular (endpoint novo
   `GET /api/v1/pdv/clientes`, `PdvClienteController/Service`); vendedor busca por nome
   reaproveitando o endpoint já existente de funcionários (nenhum endpoint novo). Grava em
   `venda.id_cliente` e `produto_movimento_detalhe.id_funcionario` — colunas que já existiam no
   schema desde V018/V019, só nunca eram preenchidas. **Nenhuma migration nova.**
7. **156 testes de backend verdes** no total (27 só no `PdvCrudTest`: 5 novos de cliente/
   vendedor — inexistente, busca por nome/CPF/celular, isolamento entre tenants). `tsc --noEmit`
   limpo. Verificado ao vivo ponta a ponta no navegador (venda real com cliente e vendedor reais,
   conferida no banco, depois removida por ser só teste — o trigger de estoque devolveu o
   estoque sozinho ao apagar).

Nada commitado ainda — soma com todo o volume acumulado do dia (ver entradas abaixo).

### 2026-07-28 — PDV: desconto da venda vira informado pelo operador (limitado por um máximo) + teto de valor pago em forma de pagamento com desconto

Continuação direta da sessão de split-tender (mesmo dia), a pedido do dono do produto: o
desconto da venda estava sendo aplicado automaticamente a partir de `cfg_geral.percentual_desconto_venda`
— o pedido foi trocar isso por dois campos que o operador preenche na hora (percentual **ou**
valor em R$), sempre limitados a esse percentual como **máximo**, e também limitar o quanto se
pode pagar numa forma de pagamento que já tem desconto próprio, pra não ultrapassar o saldo.

1. **Desconto da venda deixou de ser automático.** `cfg_geral.percentual_desconto_venda` (rótulo
   já dizia "Desconto máximo em venda (%)", só o PDV não respeitava isso) virou o **teto**, não
   mais aplicado sozinho. `FormaPagamentoModal.tsx` ganhou dois campos sincronizados — Desconto
   (%) e Desconto (R$) — que nascem **zerados** (nenhum desconto a menos que o operador digite
   algo) e clampam no `onBlur` contra o máximo; travados assim que o primeiro pagamento é
   lançado. Backend (`PdvVendaService`): `EfetivarVendaRequest` trocou o cálculo automático por
   um campo `descontoVenda` explícito no request, validado contra o máximo (`400` se passar,
   com tolerância de 1 centavo) antes de qualquer gravação.
2. **Teto de valor pago numa forma de pagamento com desconto próprio.** Pedido com exemplo
   numérico direto: "o saldo a pagar é de 500,00 e o desconto é de 10%, só posso pagar no
   máximo o valor − o % de desconto" (R$450). `PdvVendaService.resolverPagamentos` passou a
   processar as linhas de pagamento **na ordem em que chegam**, mantendo o saldo restante
   corrente, e rejeita (`400`) qualquer linha cujo `valorPago` passe de
   `saldoRestanteAntesDaLinha × (1 − percDesconto/100)`. No front, o campo "Valor Pago" ajusta
   sozinho pro máximo permitido quando o operador digita acima (decisão confirmada com o dono
   do produto — ajustar automaticamente, não bloquear o botão), com uma dica mostrando o valor
   máximo.
3. Campos de resposta/request renomeados de `descontoPromocional` para `descontoVenda`
   (request/response da API e tipo `VendaEfetivada` no front) — mais preciso agora que o
   desconto é decisão do operador a cada venda, não mais um percentual promocional fixo do
   tenant; seguro renomear porque nada disso está commitado ainda.
4. **151 testes verdes** no total (2 novos em `PdvCrudTest`: desconto acima do máximo é
   rejeitado sem gravar nada, valor pago acima do teto de uma forma de pagamento com desconto é
   rejeitado sem gravar nada — mais os 15 existentes ajustados para o novo formato de request).
   `tsc --noEmit` do `web/` limpo. API reconstruída no Docker (`docker compose up -d --build
   api`) com as mudanças.

Nada commitado ainda (soma com o volume das sessões anteriores do dia — ver `git status`).

### 2026-07-28 — PDV: split-tender + desconto promocional na Efetivação de Venda (F5)

Continuação direta da sessão do PDV, a pedido do dono do produto: "vamos ajustar a F5
efetivacao de venda no PDV" com a fórmula exata do resumo/saldo já especificada na mensagem.

1. **Regra pedida:** se `cfg_geral.percentual_desconto_venda > 0`, mostrar Valor Total da Venda
   → Desconto Promocional → Sub-total; senão só o total. **Saldo a Pagar** sempre visível antes
   dos botões. **4 botões** com as categorias de tipo de carteira — clicando, "você já sabe o
   que fazer" (split-tender: várias formas de pagamento cobrindo pedaços da mesma venda,
   atualizando o saldo a cada uma, "conforme já falamos anteriormente" — referência ao estudo
   de desconto/split-tender feito em sessão anterior, ver [[project_modulo_financeiro]]).
2. **Backend (`vendas`/`configuracao.geral`):** `ConfiguracaoGeralService.percentualDescontoVenda()`
   + `GET /api/v1/config-geral/desconto-venda` (aberto a qualquer papel — o PDV não é ADMIN-only).
   `EfetivarVendaRequest` trocou `idCarteira`+`numeroParcelas` únicos por `pagamentos:
   List<PagamentoRequest>`. `PdvVendaService.efetivarVenda()` reescrito: `descontoPromocional`
   vem do percentual × total dos produtos; pra cada linha de pagamento, `cobertura = valorPago +
   valorPago×percDesconto/100 − valorPago×percAcrescimo/100` (desconto faz a linha cobrir *mais*
   saldo que o valor tendido — bônus por pagar naquela forma; acréscimo cobre *menos* — a
   diferença é custo da forma de pagamento, não abate dívida); a soma das coberturas tem que
   fechar o líquido a pagar (produtos − desconto promocional) com tolerância de 1 centavo, senão
   400 sem gravar nada. Fórmula derivada e verificada por álgebra a partir do exemplo numérico
   que o dono do produto deu na sessão anterior (dinheiro R$500 com 10% de desconto cobrindo
   R$550 de saldo): a soma de `valorPago` de todas as linhas bate, por construção, exatamente com
   a soma do valor dos itens já líquido de desconto/acréscimo — por isso `contas_receber` grava
   sempre o `valorPago` literal (dinheiro real que circula) e todo o desconto/acréscimo apurado
   (promocional + de cada linha) é **rateado entre os itens vendidos** em
   `produto_movimento_detalhe.valor_desconto`/`valor_acrescimo` (proporcional ao valor de cada
   item, resto do arredondamento no último item — mesmo truque já usado pra parcela) — **nunca**
   em `contas_receber.valor_desconto`, que o dono do produto deixou explícito ser um campo
   reservado pra um recurso futuro completamente diferente (desconto ao cobrar parcela de
   crediário muito atrasada, sem relação com desconto de venda).
3. **Frontend (`FormaPagamentoModal.tsx`, reescrito por completo):** resumo + saldo a pagar +
   4 botões de categoria; clicar numa categoria com mais de um tipo de carteira cadastrado (ex.:
   HIPER débito e crédito) pede pra escolher, senão já vai direto pro campo de valor pago
   (pré-preenchido com o saldo restante) com prévia ao vivo de "cobre R$ X do saldo" (mesma
   fórmula do backend, só client-side) e prévia de parcelas; lista de pagamentos já lançados
   (removíveis); "Confirmar Venda" só libera quando o saldo fecha em zero.
4. **Verificado ao vivo, ponta a ponta:** primeiro via `curl` direto contra a API (venda com
   desconto promocional de 10% + split-tender dinheiro/débito fechando o saldo exato), depois no
   navegador de verdade — item de R$100, desconto promocional de 10% mostrado corretamente
   (Sub-total R$90), pagamento parcial em dinheiro (10% de desconto próprio, saldo caiu R$44 por
   R$40 pagos), pagamento do restante em débito, saldo virou R$0,00 (ficou verde), venda
   efetivada com sucesso. Vendas de teste removidas do banco de dev depois — nesse processo,
   descoberto que `fn_atualiza_estoque_movimento` (V019) também reage a `DELETE` em
   `produto_movimento_detalhe` e devolve o estoque sozinha — não precisa (e não deve) somar de
   volta manualmente, senão duplica o ajuste.
5. **149 testes verdes** no total (4 novos em `PdvCrudTest`: desconto promocional rateado no
   item, sem desconto quando o percentual é zero, split-tender com desconto por forma de
   pagamento fechando o saldo, pagamentos que não fecham o saldo respondem 400 sem gravar nada).

Nada commitado ainda (soma com o volume de sessões anteriores — ver `git status`).

### 2026-07-28 — Fusão de `tipo_carteira` + `moeda` (elimina `moeda_detalhe`)

Pedido do dono do produto, primeiro como **estudo** ("não quero que você mude nada, apenas
estudo esta possibilidade e me dê sugestões"), motivado por querer que o PDV pergunte a
categoria da forma de pagamento primeiro, e só depois mostre as moedas daquela categoria.

1. **Análise em várias rodadas até fechar o modelo certo:** a suposição inicial (cada moeda
   pertence a uma única categoria) foi corrigida com um CSV de exemplo do dono do produto
   mostrando a mesma bandeira ("Hiper") em Débito **e** Crédito com prazo/taxa próprios —
   levou à chave única `(nome_carteira, categoria_carteira)` em vez de só `nome_carteira`.
   Depois, um exemplo numérico detalhado de venda com múltiplas formas de pagamento e desconto
   por forma fechou a fórmula de saldo (`saldo -= valorPago + valorPago×percDesconto/100`) e
   esclareceu — depois de eu propor errado duas vezes — que **todo** desconto de venda
   (promocional + por forma de pagamento) vai rateado em `produto_movimento_detalhe.valor_desconto`,
   nunca em `contas_receber.valor_desconto` (reservado pra outra coisa, ver linha do tempo
   seguinte). Dono do produto pediu a DDL final antes de autorizar.
2. **"Perfeito. faça a junção das tabelas... pode codificar"** — autorização explícita.
   `db/migration/V025` editada (convenção de "banco em construção"): `tipo_carteira` ganhou
   `perc_desconto`/`perc_acrescimo` (`numeric(5,2)`, nullable, nunca os dois > 0 ao mesmo tempo);
   chave única virou `(id_tenant, nome_carteira, categoria_carteira)`; `moeda`/`moeda_detalhe`
   **removidas** do arquivo; `caixa_detalhe.id_moeda` renomeado pra `id_carteira`, FK
   retargetada. Aplicada no banco de dev via `ALTER`/`DROP TABLE` manual + `flyway repair` (sem
   recriar o banco).
3. **Backend:** `MoedaController/Service/Dtos` deletados; `TipoCarteiraService` ganhou
   `percDesconto`/`percAcrescimo` no `criar`/`atualizar`, herdou a validação de mutualidade do
   `MoedaService` deletado, e `excluir()` passou a checar vínculo em `contas_receber` **e**
   `caixa_detalhe`; `SignupService` semeia `tipo_carteira` direto. **136 testes verdes**
   (`TipoCarteiraCrudTest` de 11 pra 17, `MoedaCrudTest` removido).
4. **Frontend:** `pages/moeda/`, `lib/moedas.ts`, `MoedaModal.tsx` deletados; rotas/menu "Moeda"
   removidos; `TipoCarteiraForm.tsx` ganhou a seção "Desconto / Acréscimo" (mesma UX de
   mutualidade por valor que a Moeda tinha) no lugar do checklist embutido de moedas;
   `TipoCarteiraLista.tsx` trocou a coluna "Moedas" por "% Desconto"/"% Acréscimo".
5. **Verificado ao vivo:** `curl` direto (criar "HIPER"+débito → 201, "HIPER"+crédito → 201 com
   prazo/taxa independentes, recriar "HIPER"+débito → 409 com a mensagem certa) — automação de
   navegador ficou instável nesse dia (viewport redimensionado no meio do teste, perda de
   contexto da aba) e a verificação final foi por API + uma screenshot da listagem já
   funcionando. Linhas de teste removidas do banco de dev depois.
6. **Ajuste no dia seguinte (mesma migration, pedido à parte):** colunas Categoria/% Desconto/%
   Acréscimo da listagem de Tipo de Carteira não estavam ordenáveis — allowlist do backend
   (`COLUNAS_ORDENAVEIS`) e array `COLUNAS` do front só tinham as 3 colunas antigas; corrigido
   nos dois lados.

Detalhe completo da análise (fórmulas, exemplos numéricos, decisões de escopo) em
[[project_modulo_financeiro]] (memória).

### 2026-07-28 — PDV (Frente de Caixa): de rascunho de layout a feature real, ponta a ponta

Sessão longa, em etapas, todas a pedido direto do dono do produto (mockup em `C:\FIX\TELA
PDV.png`/`tela_modelo_1.png`):

1. **Ajustes finos de layout** (herdados do rascunho em Artifact de 2026-07-27): botão F5
   alinhado com "Valor Total da Venda"; depois, sem carrossel de fotos (só a foto do índice 1)
   e foto maior pra preencher o vazio antes do F5, com F2/F3/F4 crescendo proporcionalmente.
2. **Incorporada ao ERP** (`web/src/pages/pdv/Pdv.tsx`, rota `/pdv`, item "PDV" no menu) —
   pedido explícito do dono do produto de **não codificar front nem back**, só a tela: F2/F3/F4/F5
   funcionavam em cima de um catálogo de demonstração local (`catalogoDemo.ts`), sem chamada de
   API nenhuma.
3. **Rótulos renomeados** (Qtd Vendida→Quantidade, Vlr Unitário→Valor Unitário, Vlr Total do
   Produto→Total Produto) e **F2/F3/F4 codificados** (ainda em cima do catálogo demo, dono do
   produto ainda não tinha liberado o backend): F2 abre popup de pesquisa com variação de
   linha/coluna e estoque por empresa + total; F3 abre popup de alterar quantidade com stepper,
   nunca remove ao chegar em zero sem confirmar antes; F4 zera tudo e foca o campo de código.
4. **Navegação ↑/↓** nos Produtos Vendidos e **ícones nos botões F2–F5** (lupa/ajuste/reset/check,
   novos em `Icones.tsx`).
5. **Bug real corrigido:** F2/F3/F4/F5 físicas não faziam nada (ou pior — F5 recarregava a
   página) quando o foco estava no campo de código de barras (o padrão, já que ele começa
   focado). Causa: o listener global de teclado tinha `if (e.target === campoBarrasRef.current)
   return` **antes** de tratar as F-keys, então `preventDefault()` nunca rodava a tempo de
   vencer o atalho nativo do navegador. Corrigido tirando essa checagem de cima delas —
   nenhuma insere caractere, não há conflito real com a digitação.
6. **"Pode codificar o front e o back e as APIs necessárias tb"** — mudança de escopo explícita
   do dono do produto. Antes de sair codificando um módulo novo que mexe em estoque e
   financeiro (P1/P3 da constituição), investiguei o que já existia (nada — zero código Java
   pra criar venda, nenhum endpoint de leitura por código de barras) e perguntei 3 coisas: (1)
   F5 pede forma de pagamento de verdade (com parcelas) ou só à vista sem financeiro? → **com
   forma de pagamento**; (2) busca usa produtos reais cadastrados ou mantém demo? → **produtos
   reais**; (3) escreve uma spec antes (projeto é spec-driven) ou vai direto pro código? →
   **spec primeiro**. Escrevi `docs/telas/pdv.md` (regras de negócio, contrato de API,
   Dado/Quando/Então, non-goals explícitos — sem cliente/vendedor vinculado, sem multi-empresa,
   sem desconto/oferta) antes de tocar em código, então implementei o módulo `vendas` inteiro:
   busca/leitura de produto real com estoque por empresa, efetivação de venda transacional
   (baixa de estoque real via trigger já existente + parcelas em `contas_receber` calculadas a
   partir do `tipo_carteira`), removi o catálogo de demonstração do front e liguei tudo na API
   de verdade, com um modal novo de Forma de Pagamento (prévia das parcelas calculada no front,
   mesma conta do backend). **141 testes verdes** no total (11 novos, `PdvCrudTest`). Verificado
   ao vivo com produtos de teste criados no banco de dev (tênis com variação linha/coluna): uma
   venda de crediário em 3x bateu exatamente com a prévia mostrada e, conferido direto no banco,
   criou a venda, baixou o estoque só na loja certa (as outras 9 filiais de teste do tenant
   intocadas) e gravou as 3 parcelas em aberto com vencimentos a cada 30 dias. Detalhe completo
   em [[project_pdv_frente_caixa]] (memória).
7. **Menu lateral retrátil** (`Layout.tsx`) — pedido à parte, mesma sessão: botão no rodapé do
   menu alterna 200px↔56px (só ícones, com tooltip nativo via `title`), preferência em
   `localStorage`. Precisou de ícone novo pra Painel/Estoque/Pedidos/Canais, que ainda não
   tinham (senão o modo recolhido ficaria com espaços vazios).

Nada commitado ainda nesta sessão (soma com o volume de dias anteriores — ver `git status`).

### 2026-07-27 — Histórico do Cliente: layout em duas colunas (Produtos da Compra + Parcelas ao lado das Compras)

Pedido do dono do produto, com mockup ASCII de referência (layout de duas colunas, campos e
fórmula de preço já especificados). Antes de implementar, esclarecida por pergunta direta uma
ambiguidade do mockup: a lista "DINHEIRO, PIX, CARTAO DEBITO, CARTAO CREDITO, CREDIARIO" pra
"Tipo de Parcela" **não** é uma mudança na `categoria_carteira` do sistema (hoje
`AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`/`CREDIARIO`) — o dono do produto confirmou "mantenha
o tipo da parcela como está hoje no projeto", ou seja, é só exemplo do que pode aparecer na
coluna; nenhuma mudança de schema/ENUM foi feita.

1. **Layout em duas colunas** (`ClienteHistorico.tsx` + `styles.css`): Histórico de Compras à
   esquerda (altura cheia, mantendo a navegação ▲/▼ e teclado de 2026-07-24), Produtos da
   Compra e Histórico das Parcelas empilhados à direita — os dois agora **sempre** seguem a
   compra selecionada/navegada (master-detail de verdade; o botão "Ver todas as parcelas" foi
   removido, pois deixou de fazer sentido nesse layout). CSS novo: `.historico-corpo`/
   `.historico-grid`/`.historico-coluna-esquerda`/`.historico-coluna-direita`/
   `.historico-painel-produtos`/`.historico-painel-parcelas` — cada painel rola o próprio
   conteúdo (mesmo princípio de `.lista-corpo`/`.table-wrap`, adaptado pra duas colunas com
   dois painéis empilhados à direita).
2. **Produtos da Compra (painel novo):** backend ganhou `ClienteHistoricoService.
   buscarProdutos()` — junta `produto_movimento_detalhe` (linha de débito da venda) →
   `produto_barra` → `produto` (+ `cfg_variante_linha`/`cfg_variante_coluna` pro nome da
   variação, quando existir) e calcula o preço de venda líquido por unidade com a fórmula
   pedida: `((qtd_produto * preco_venda) - valor_desconto + valor_acrescimo) / qtd_produto`
   (`RoundingMode.HALF_UP`, 2 casas) — a mesma composição usada no "valor" da compra
   (2026-07-23), só dividida de volta pela quantidade pra dar o preço unitário líquido. Novo
   DTO `ItemVendaHistorico` + campo `produtos` em `ClienteHistoricoResponse`. 2 testes novos
   (`ClienteHistoricoCrudTest`: produto sem variação e com variação linha/coluna) — **130
   testes verdes** no total.
3. **Tipo de Parcela:** só o rótulo da coluna mudou, de "Tipo" pra "Tipo Parcela" — o dado
   exibido continua `nomeCarteira` (nome específico da carteira, decisão de 2026-07-24
   mantida), conforme esclarecido acima.
4. **"Id Venda" → "Nº Venda"** na grid de Compras (pedido à parte, mesma sessão).
5. Verificado ao vivo no navegador (login `teste@niner.dev`/`loja-teste-manual`): navegar entre
   compras (▲/▼) atualiza corretamente os dois painéis da direita, com dados reais do cliente
   `CLAUDIO CALIXTO` (`id_cliente=1`) semeados em 2026-07-24.

Início de sessão: `git pull` (fast-forward, `ecb1586..5a6ef72` — só `.gitignore`, sem conflito
com as mudanças locais não commitadas do dia 24).

### 2026-07-24 — Histórico do Cliente: navegação master-detail (▲/▼) + dados de teste do Claudio Calixto

Pedido do dono do produto, com vídeo (`c:\FIX\MODELO.mov`, sem ffmpeg no host — instalado via
`winget install Gyan.FFmpeg` só pra extrair frames e revisar) e imagem
(`c:\FIX\MODELO.png`) de referência do sistema legado (`mihyus.com.br`, tela "Histórico de
Estoque"): no legado, clicar numa linha da grid de entradas destaca ela e atualiza uma caixa
de texto com detalhe abaixo. Esclarecido com o dono do produto (duas perguntas antes de
implementar): (1) sem caixa de detalhe — só destacar a linha mesmo — e (2) manter o visual
do próprio Nainer (claro/escuro, §3.7), só copiando o *comportamento* de navegação, não o tema
escuro do legado. Implementado em `ClienteHistorico.tsx`:

1. **Compras → Parcelas é master-detail de verdade** (diferente do legado, onde as duas
   grids eram independentes): a compra selecionada filtra a grid de Parcelas pra mostrar só
   as parcelas daquela venda (`parcelasExibidas`, filtro por `idVenda`); título da seção
   mostra "— venda nº X"; botão "Ver todas as parcelas" sai do filtro; Total do rodapé
   recalculado sobre o conjunto filtrado.
2. **Navegação da grid de Compras:** barra ▲/▼ (`IconeSetaCima`/`IconeSetaBaixo`) no
   cabeçalho da seção, mais setas do teclado (↑/↓) com a linha focada — `linhasCompraRef`
   guarda os elementos `<tr>` pra mover o foco programaticamente; clique e Enter/Espaço
   continuam funcionando. Primeira compra já vem selecionada ao carregar (`useEffect`),
   igual ao legado.
3. Dados de teste inseridos direto via SQL (sem API de escrita pra venda/parcela ainda) pro
   cliente **CLAUDIO CALIXTO** (`id_cliente=1`, tenant 1): uma compra por tipo de carteira
   existente (AVISTA, DEBITO, CREDITO, CREDIARIO), com o **máximo de parcelas** quando a
   carteira permite mais de uma (`pc_maxima`) — CREDITO e CREDIARIO ficaram com 6 parcelas
   cada, misturando estados (paga em dia, paga com atraso, vencida em aberto, a vencer) pra
   exercitar toda a tela, inclusive o card "Resumo das Parcelas de Crediário".

### 2026-07-24 — Banco zerado (exceto empresa/usuário) para reiniciar os testes manuais

A pedido do dono do produto, depois de fechar a bateria de correções de UI do dia: `TRUNCATE
... RESTART IDENTITY CASCADE` nas 28 tabelas de negócio do tenant (cadastros, catálogo,
financeiro, estoque, vendas, canais — a mesma lista da carga de 10 registros por tabela do
dia anterior), preservando **`empresa` e `usuario`** pra não derrubar o login
(`teste@niner.dev`) que estava em uso pra testar. Confirmado com o dono do produto antes de
rodar (ação destrutiva) via pergunta direta — ele escolheu manter o login.

### 2026-07-24 — Pop-up de erro/sucesso em todo o sistema + rede de segurança contra exclusão que viola FK sem avisar

Dois pedidos encadeados do dono do produto, o segundo motivado por um bug real encontrado no
primeiro:

1. **Bug relatado:** "quando peço uma exclusão e ela viola uma chave estrangeira, o sistema
   simplesmente não exclui, mas também não avisa nada" (exceto nas telas com fallback de
   inativar). Investigação (agente `Explore`) mostrou que **Moeda** e **Tipo de Carteira** já
   funcionavam certo (409 com mensagem), mas **Plano de Contas** tinha um gap real:
   `PlanoContasService.excluir()` só checava vínculo em `fornecedor`/`contas_pagar`, esquecendo
   a terceira FK que aponta pra `cfg_plano_contas` — `caixa_detalhe.id_plano_contas` (V025).
   Sem essa checagem, o `DELETE` real batia na FK do Postgres, `DataIntegrityViolationException`
   subia sem handler no `GlobalExceptionHandler`, e caía no 500 genérico do Spring (sem
   `detail`/`title`) — o front mostrava só "Ocorreu um erro.", indistinguível de "nada
   aconteceu". Corrigido: `PlanoContasService` passou a checar as 3 FKs; `GlobalExceptionHandler`
   ganhou um handler genérico pra `DataIntegrityViolationException` → 409 ("Registro em uso por
   outro cadastro"), como rede de segurança pra qualquer FK que uma pré-checagem futura esqueça.
   2 testes novos (`PlanoContasCrudTest`/`TipoCarteiraCrudTest`) — **128 testes verdes**.
2. **Convenção de pop-up pedida:** toda mensagem de erro/alerta = pop-up vermelho/letra
   branca; toda mensagem de sucesso = pop-up verde/letra branca; nunca banner inline na
   página. `Toast.tsx` (já existia, só vermelho) ganhou a prop `tipo?: 'erro' | 'sucesso'`
   (`.toast-erro`/`.toast-sucesso` no CSS, cor de `var(--danger)`/`var(--sucesso)`). Varredura
   sistemática: as 7 listagens de cadastro trocaram o banner `aviso-banner` (removido do CSS)
   por `Toast`; `Login.tsx` (erro de autenticação) e `ConfiguracaoGeralForm.tsx` (que já usava
   `Toast`, mas sempre vermelho mesmo pro "Parâmetros salvos." — bug encontrado na varredura)
   idem.
3. **Pedido seguinte, mesma sessão:** pop-up verde também ao clicar Salvar num cadastro e dar
   certo — hoje a maioria dos formulários só navegava de volta pra lista, sem avisar nada.
   Resolvido sem duplicar estado: cada formulário (7 telas de cadastro + 4 telas de
   configuração de campos) passa `navigate(rota, { state: { toast: { texto, tipo:
   'sucesso' } } })`; a lista de destino lê `location.state?.toast` uma vez, via inicializador
   preguiçoso do `useState`, e limpa do histórico (`window.history.replaceState`) logo em
   seguida, pra um F5 ou "voltar" do navegador não repetir o popup.

### 2026-07-24 — Ambiente local religado + banco populado com 10 registros por tabela

Início de sessão: `git pull` (sem novidade), Docker Desktop não estava rodando (subido via
`Start-Process`), `docker compose up -d db fake-gcs` + `flyway` (só validou, já estava em
V027) + `docker compose up -d --build api`; `web/` precisou de `npm install` (faltava
`vite` resolvido, lockfile tinha mudado) antes do `npm run dev` funcionar.

Pedido do dono do produto: popular o banco com **10 registros em cada tabela de negócio do
tenant** (`loja-teste-manual`, tenant 1), pra ter massa de dados pra testar as telas. Escopo
acertado antes de começar (pergunta direta): só tabelas de negócio/cadastro do tenant — não
`plataforma.*` (control-plane), não config/singleton (`cfg_geral`, `cfg_tela_campo`), não
referência global (`cfg_produto_ncm`, já com os 10.442 códigos reais). Método: **SQL direto**
(não via API — mais rápido pra ~300 registros), respeitando FKs/RLS (`SET LOCAL
app.id_tenant`), enums, e as regras de negócio que importam mesmo sem passar pela API: EAN de
`produto_barra` sempre via `gerar_ean13_interno()` (nunca digitado), `produto_estoque` nunca
inserido direto — só populado pelo trigger `fn_atualiza_estoque_movimento()` a partir de
`produto_movimento_detalhe`, exatamente como o sistema real faz. Achado no processo: `docker
exec`/`docker cp` referenciando caminhos dentro do container (`/tmp/...`) precisa de
`MSYS_NO_PATHCONV=1` no Git Bash do Windows, senão o caminho é silenciosamente reescrito pro
`C:\Users\...\Temp\...` do host antes de chegar no Docker (mesma família do achado antigo do
`docker exec` sem `-i`).

### 2026-07-23 — Histórico do cliente (compras, parcelas e resumo de crediário)

Nova tela, acessada por um ícone (relógio) na linha da lista de Clientes e por um botão no
formulário: `GET /api/v1/clientes/{id}/historico` (`ClienteHistoricoController/Service/Dtos`,
novo em `cadastros.cliente`), somente leitura, devolvendo três blocos:

1. **Histórico de Compras** — por venda física (`venda`): código da empresa (`empresa.codigo_empresa`),
   id da venda, data/hora, e **valor somado do ledger de estoque**
   (`produto_movimento_detalhe`, linhas de débito ligadas ao movimento tipo `VENDA` da venda:
   `qtd_produto * preco_venda - valor_desconto + valor_acrescimo`) — `venda` não guarda total,
   por design (V018).
2. **Histórico de Parcelas** — por `contas_receber`: código da empresa da venda, id da venda,
   data/hora da venda, vencimento, pagamento, valor a pagar (`valor_receber + valor_juros -
   valor_desconto`), valor pago, **código da empresa de pagamento** (campo novo,
   `id_empresa_pagamento`, ver schema abaixo), **dias de atraso** (calculado em Java, não
   gravado: paga = dias entre vencimento e pagamento, só se positivo; em aberto = dias entre
   vencimento e hoje, só se já venceu; `null` nos outros casos) e o tipo de parcela
   (`tipo_carteira.categoria_carteira`, ver abaixo).
3. **Resumo das Parcelas de Crediário** — só `categoria_carteira = CREDIARIO`, só em aberto
   (`data_recebimento IS NULL`; parcela já paga, mesmo com atraso, não entra em nenhum dos
   três): Vencidas (`data_vencimento < hoje` — valor total, juros+multa, nº parcelas), A Vencer
   (`data_vencimento >= hoje` — valor total, nº parcelas, sem juros pois ainda não venceu) e
   Total = Vencidas + A Vencer.

**Schema (editado em V025, banco ainda em construção — sem V028 nova, ver
`feedback_db_migration_workflow`):**
- `categoria_carteira` (ENUM novo: `AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`/`CREDIARIO`) +
  coluna **obrigatória** do mesmo nome em `tipo_carteira` (antes só havia `nome_carteira` livre
  — não dava pra isolar crediário programaticamente). Refletido em `TipoCarteiraDtos/Service`
  (Java) e no formulário/lista de Tipo de Carteira (select "Categoria \*", nova coluna na
  listagem).
- `contas_receber.id_empresa_pagamento` (nova, nullable, FK composta pra `empresa`) — loja onde
  a parcela foi paga; fica `null` até existir uma "baixa de parcela" de verdade (ainda não
  implementada — mesmo caso de `data_recebimento`/`valor_recebido`, que já existiam vazios até
  a venda/pagamento acontecer).

**Decisões confirmadas pelo dono do produto** (depois de um estudo de caso apresentado sem
código, a pedido dele): categoria fixa em vez de inferir pelo nome; `id_empresa_pagamento`
direto em `contas_receber` em vez de derivar de `caixa_detalhe` (mais simples, não depende do
módulo de caixa existir); "Parcelas Total" = Vencidas + A Vencer (só em aberto); fórmula do
valor a pagar incluindo juros e descontando desconto; tela cheia (não modal) em
`/clientes/:id/historico`.

**⚠️ Ainda não existe fluxo de lançamento de venda nem de baixa de parcela** — a tela nasce
funcionalmente correta mas vazia em qualquer tenant real, até essas tabelas terem dados (seed
manual ou o módulo de vendas ser construído). Os 6 testes novos
(`ClienteHistoricoCrudTest`) semeiam `venda`/`produto_movimento_mestre/detalhe`/
`produto_barra`/`contas_receber` direto via JDBC (mesmo padrão de `criarVendaParaCliente` em
`ClienteCrudTest` — conecta como `niner_app` com `app.id_tenant` setado, RLS continua valendo),
porque não existe API ainda pra essas tabelas. **126 testes verdes** no total (113 + 6 novos de
histórico + correções em `TipoCarteiraCrudTest`/`MoedaCrudTest` pra incluir `categoriaCarteira`
nos testes existentes, já que o campo virou obrigatório).

**Aplicado no banco de dev já rodando** (sem recriar — preservando NCM importado e dados de
teste): `ALTER TYPE`/`ALTER TABLE` manuais + `flyway repair` pra reconciliar o checksum da V025
editada, depois rebuild do container da API. Achado no processo: `docker exec` sem `-i` engole
o heredoc silenciosamente (sem erro, sem aplicar nada) — ver `feedback_docker_exec_heredoc`.

### 2026-07-23 — Galeria de fotos: confirmação ao excluir, ampliar com navegação, e upload antes de salvar o produto

Três ajustes pedidos depois do primeiro corte da galeria (`GaleriaImagensProduto.tsx`):

1. **Confirmação ao excluir uma foto** — clicar na lixeira abre um modal ("Excluir foto?")
   igual ao padrão já usado no resto do sistema, em vez de excluir na hora.
2. **Lightbox com navegação** — clicar numa miniatura abre a foto ampliada em um modal próprio
   (`.modal-lightbox`), com setas ◀/▶ pra navegar entre as fotos do produto (reaproveitando
   `IconePaginaAnterior`/`IconeProximaPagina`), contador "Foto X de Y", fechar por clique fora,
   botão ✕ ou Esc, e ←/→ do teclado.
3. **Fotos antes de salvar o produto novo** — antes só dava pra anexar foto depois do produto
   já gravado (upload precisa de `idProduto` real). Agora a galeria tem um **modo de
   preparação** (sem `idProduto`): os arquivos escolhidos ficam só no navegador
   (`arquivosLocais: File[]`, estado vive no `ProdutoForm`, preview via
   `URL.createObjectURL`/`useMemo` com revogação no `useEffect`) e são enviados um a um, na
   ordem, **depois** que o produto é criado (`ProdutoForm.tsx`, `onSuccess` do `salvar`
   percorre `arquivosNovaFoto` chamando `enviarImagem`); se o produto salvar mas alguma foto
   falhar no upload, mostra aviso e leva pra tela de edição do produto (já criado) em vez de
   voltar pra lista, pra não perder o cadastro.

### 2026-07-23 — Modo dev SEM credencial para fotos de produto (fake-gcs no compose, Opção C do handoff)

O Claudio ficou travado na credencial do GCS (Opção A concedida, mas o ADC não destravou na
máquina dele) justamente na reta final da galeria de fotos. Solução: eliminar a credencial do
caminho de desenvolvimento. O docker-compose ganhou o serviço **`fake-gcs`**
(`fsouza/fake-gcs-server:1.49`, porta 4443, backend filesystem + volume `fake-gcs-data` —
imagens sobrevivem a restart), e a API a propriedade **`niner.storage.host`**
(`NINER_STORAGE_HOST`): preenchida, `ArmazenamentoConfig` constrói o cliente com
`NoCredentials` apontando pro emulador e cria o bucket sozinho; vazia (default), tudo como
antes (GCS real via ADC/chave — staging/prod intactos). A `base-url` herda o host
automaticamente (`${NINER_STORAGE_BASE_URL:${NINER_STORAGE_HOST:...}}`), então a URL pública
que a galeria exibe já sai do emulador. Receita completa na **Opção C** de
`docs/infra/armazenamento-imagens.md` §3 (2 comandos). Validado de ponta a ponta em banco
real: login → `POST /api/v1/produtos/2/imagens` (multipart) → objeto criado no emulador →
`GET http://localhost:4443/niner-erp-dev/tenants/1/produtos/2/<uuid>.webp` → **HTTP 200
`image/webp`**. O `ProdutoImagemCrudTest` já usava o mesmo emulador — o modo dev só
reaproveita em runtime o que o teste provou.

### 2026-07-23 — Galeria de fotos de produto implementada (ADR-013) + setup de credencial GCS em andamento

Pedido do dono do produto: começar a implementação do handoff de object storage
(`docs/infra/armazenamento-imagens.md`), com a manutenção de fotos ficando **dentro da tela de
Produtos** (não uma tela separada) e **máximo de 6 fotos por produto**.

1. **Backend — `comum.armazenamento` (novo):** `ArmazenamentoDeArquivos` (interface,
   `gravar`/`apagar`/`urlPublica`) + `GcsArmazenamento` (adapter de verdade,
   `com.google.cloud:google-cloud-storage` via `libraries-bom`). O cliente `Storage` é
   injetado como bean **`@Lazy`** (`ArmazenamentoConfig`) — só autentica no primeiro uso real,
   então a API sobe normalmente mesmo sem credencial de GCS configurada (era o caso aqui: sem
   `gcloud`/ADC na máquina até este ponto da sessão).
2. **Backend — `catalogo.ProdutoImagemController/Service/Dtos` (novo):**
   `POST/DELETE/PUT /api/v1/produtos/{id}/imagens...`. Valida o arquivo por **magic bytes**
   (JPEG/PNG/WebP — nunca pela extensão ou `Content-Type` que o cliente manda), normaliza
   redimensionando pro maior lado ≤ 1600px e recodificando pra **WebP de verdade** — bate com
   o contrato original do handoff (chave `.webp`). Precisou de `org.sejda.imageio:webp-imageio`
   (registra o writer WebP via SPI; ImageIO puro não grava WebP sozinho) além do
   `net.coobird:thumbnailator` — **testado e funcional dentro do container Linux** (achado
   relevante: a lib usa uma biblioteca nativa via JNI, cogitei que pudesse não funcionar fora
   do Windows, mas funcionou de primeira). **Máximo de 6 fotos por produto** (regra de produto,
   checada na aplicação — não dá pra expressar "contar irmãos" num `CHECK` sem trigger).
   Exclusão de uma foto renumera os índices restantes pra 0..n-1 (prova matemática de que dá
   pra fazer isso num único passe ascendente, sem índice temporário, no comentário do código);
   reordenação usa índices negativos temporários primeiro (a `UNIQUE` é checada por statement,
   não no commit — uma troca direta colidiria no meio da transação).
3. **Erro de storage vira mensagem clara:** sem credencial de GCS, uma tentativa de gravar
   lançava `StorageException` cru, que o Spring Security (`ExceptionTranslationFilter`)
   traduzia num 403 sem corpo — enganoso (parecia problema de autenticação da API, não do
   GCS). Agora `GcsArmazenamento` traduz pra **503 com mensagem explícita** apontando pro
   handoff (`docs/infra/armazenamento-imagens.md §3`).
4. **Testes — `fake-gcs-server` via Testcontainers (`FakeGcsConfiguration`, novo):** bean
   `Storage` de teste (`@Primary`, nome de bean diferente do `@Lazy` de produção — mesmo nome
   nos dois seria *bean definition override*, bloqueado por padrão, não resolvido só por
   `@Primary`) apontando pro container fake, bucket criado automaticamente. **6 testes novos**
   (`ProdutoImagemCrudTest`): upload com URL pública, 7ª foto rejeitada, arquivo inválido
   rejeitado (magic bytes), exclusão renumera, reordenação, isolamento de tenant. **119 testes
   verdes** no total. Nenhum teste toca o bucket real.
5. **Frontend — `GaleriaImagensProduto.tsx` (novo) embutido em `ProdutoForm.tsx`:** seção
   "Fotos (N/6)" com miniaturas + setas de reordenar + lixeira, "＋ Adicionar foto" desabilitado
   ao atingir 6 ou em modo somente-leitura, aviso "salve o produto primeiro" em produto novo.
   `lib/api.ts` ganhou `apiUpload()` (upload `multipart/form-data` — não pode forçar
   `Content-Type: application/json` como o `api()` normal faz, o navegador precisa gerar o
   boundary sozinho).
6. **Setup de credencial real (em andamento, não concluído nesta sessão):** o Evirson já
   tinha concedido ao Google do Claudio (`claudiocalixto6969@gmail.com`) `roles/storage.objectAdmin`
   no bucket `niner-erp-dev` (Opção A do handoff, ver commit `aeb6b6e`). Confirmado com o
   Evirson (repassado pelo dono do produto): Opção A cobre tudo **desde que a API rode fora do
   Docker** (`./mvnw spring-boot:run`) — dentro do container não existe `~/.config/gcloud` do
   host, aí sim precisaria da Opção B (chave de conta de serviço). Nesta sessão:
   `winget install Google.CloudSDK` instalado com sucesso; `gcloud auth application-default
   login` precisou de duas correções (sessão de terminal antiga não via o PATH novo — precisa
   caminho completo pro executável; o script `gcloud` tentava usar o `python` de um atalho
   quebrado da Microsoft Store — precisa `CLOUDSDK_PYTHON` apontando pro Python que vem junto
   do SDK, `platform/bundledpython/python.exe`). Login relatado como concluído, mas o arquivo
   `application_default_credentials.json` **não apareceu** em
   `%APPDATA%\gcloud\` — troubleshooting pausado no meio, próximo passo é repetir o login com
   `--no-launch-browser` (fluxo copiar link/colar código) pra ver a saída completa do comando.
7. **Docker/config prontos pra quando a credencial existir:** `docker-compose.yml` já monta
   `./api/secrets:/secrets:ro` e define `GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcs-niner-erp.json`
   (Opção B); `api/secrets/LEIA-ME.txt` criado localmente (gitignored) explicando as duas
   opções. `application.yml` ganhou `niner.storage.bucket/base-url` (default `niner-erp-dev`).

### 2026-07-23 — Spec de Entrada de Mercadorias iniciada (`docs/telas/entrada-mercadoria.md`, RASCUNHO)

Início do módulo `estoque` pela porta de entrada: spec da tela de **entrada de mercadorias**
(importação de XML NF-e modelo 55 + lançamento manual + fluxo de planilha modelo), escrita a
quatro mãos e **pausada no meio da discussão** — o rascunho está completo estruturalmente
(tabelas V019/V026 mapeadas, mapeamento XML→banco, contrato de API preliminar, critérios de
aceitação), com as decisões já fechadas e as pendências registradas num bloco "Registro da
discussão" no topo do próprio arquivo. Destaques do que já foi decidido: os três fluxos
convergem para a mesma conferência/confirmação (1 mestre `COMPRA` + N detalhes `C`; saldo por
trigger de V019, nunca via Java); XML idempotente pela chave de acesso (novas colunas
`chave_nfe`/`serie` no mestre + tabela `entrada_xml` para o payload bruto — aprovadas, viram
V028+); duplicatas do XML geram `contas_pagar`; rateio de frete/IPI/ST e reajuste de
custo/preço serão configuráveis; correção de entrada confirmada permite edição direta **e**
estorno, ambos com auditoria de UPDATE/DELETE (P3); haverá vínculo produto×fornecedor (match
por `cProd` + conversão de unidade) e tabela de ligação usuário↔funcionário. Pendências da
retomada (desenho físico das tabelas novas, onde mora a configuração, política de divergência
de `vNF`, detalhamento da planilha) listadas no mesmo bloco. Nenhuma linha de código ainda —
spec-driven (golden rule).

Operacional do dia: ambiente local religado (conflito de porta 5432 com `finance-v-db`
resolvido parando o outro container; `flyway repair` + `migrate` até V027 — checksums
V006–V010 divergiam por edições antigas; `npm install` no `web/`). Banco estava vazio: criada
loja de teste via `POST /api/publico/assinar` — slug `loja-teste`, `teste@teste.com` /
`teste1234` (tenant 1, trial até 2026-09-21).

### 2026-07-23 — Sexta e sétima telas de domínio: Moeda e Tipo de Carteira (módulo `financeiro`)

Pedido do dono do produto: telas de manutenção para `tipo_carteira`/`moeda`/`moeda_detalhe`
(V025, crediário/cartão antecipados da Fase 2 — Q5/ADR-010/ADR-012), que tinham schema desde
2026-07-16 mas nenhuma tela. As três tabelas têm relacionamento entre si (`moeda_detalhe` é
N:N entre `moeda` e `tipo_carteira`) — a primeira decisão foi **como distribuir isso em telas**.

1. **Desenho da tela discutido antes de codar (`AskUserQuestion` + ida e volta):** a proposta
   inicial (Claude) era 2 telas com o N:N embutido em Moeda (espelhando `produto_categoria` em
   Produto). O dono do produto sugeriu o oposto: **uma tela só**, com tudo dentro de Tipo de
   Carteira (inclusive criar Moeda ali na hora). Acordo final: **2 telas, mas o N:N mora em
   Tipo de Carteira** — Moeda precisa de tela própria porque tem campos editáveis
   (`% desconto`/`% acréscimo`) e já nasce com 7 linhas semeadas no signup (não dá pra só
   criar via modal, tem que dar pra editar depois); Tipo de Carteira nasce vazio e é o lado que
   mais cresce (cada combinação de prazo/parcela/taxa é um registro novo), então faz mais
   sentido ele "puxar" quais moedas usa, com **criação rápida de moeda embutida**
   (`MoedaModal.tsx`) se a que falta ainda não existir — mesmo papel do `PlanoContasModal` no
   Fornecedor.
2. **Backend — módulo novo `financeiro`** (pacote existia vazio desde a criação do domínio):
   `MoedaController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/moedas`) e
   `TipoCarteiraController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/tipos-carteira`, este
   último gerenciando `moeda_detalhe` embutido — apaga tudo e reinsere a cada save, sem índice
   porque a relação não tem ordem, diferente de `produto_categoria`). Sem coluna `ativo` em
   nenhuma das duas tabelas → exclusão sem fallback de inativar (409 com vínculo).
3. **Schema:** `criado_em`/`atualizado_em` adicionados em `tipo_carteira`/`moeda` (V025, não
   tinham) — mesma convenção de auditoria do resto do domínio.
4. **Bug de isolamento de tenant achado só em teste:** o ambiente de teste (Testcontainers)
   conecta como **superusuário**, que ignora RLS mesmo com `FORCE` — os dois serviços novos
   precisaram de filtro `id_tenant = plataforma.tenant_atual()` explícito em toda consulta
   (mesmo motivo já documentado em `PlanoContasService`), senão um teste que buscava "a moeda
   PIX do meu tenant" podia pegar a de outro tenant e quebrar a FK composta de `moeda_detalhe`.
5. **Frontend — `pages/moeda/` e `pages/tipocarteira/`:** mesmo padrão de cadastro (paginação,
   ordenação, 3 ícones de ação, `InfoRegistro`, confirmação de Enter); ícones próprios (cifrão
   para Moeda, carteira/wallet para Tipo de Carteira); checklist de moedas (checkbox, sem
   reordenar) dentro do formulário de Tipo de Carteira.
6. **91 testes verdes → 109** (`MoedaCrudTest` + `TipoCarteiraCrudTest`, 18 novos); `tsc -b`
   limpo; testado ao vivo (criar tipo de carteira com moedas + criação rápida de moeda embutida;
   exclusão de moeda vinculada bloqueada com 409; dados de teste limpos depois).

### 2026-07-23 — Correções de regra de negócio em Moeda/Tipo de Carteira (percentuais e taxa opcionais)

Rodada de correções pedida logo depois da entrega acima, com o dono do produto já testando a
tela:

1. **`moeda.perc_desconto`/`perc_acrescimo` e `tipo_carteira.taxa_administradora` perderam o
   `NOT NULL`** (mantém `DEFAULT 0`) — aplicado em V025 (schema ainda em construção, edição no
   próprio arquivo) + DDL manual no banco local + `flyway repair`, mesmo procedimento já
   normalizado nesta sessão.
2. **Desconto e acréscimo nunca coexistem *de verdade*** — mas a checagem é por **valor
   positivo**, não por presença: 0/0 é o estado neutro normal de toda moeda semeada no signup
   e não pode ser rejeitado; só bloqueia (400) quando os dois têm, ao mesmo tempo, valor > 0.
   No formulário, digitar um valor > 0 num campo limpa o outro automaticamente (mesmo
   comportamento no `MoedaModal.tsx` de criação rápida) — a validação de servidor é a mesma
   regra, defesa em profundidade.
3. **Percentuais (moeda) só precisam ser ≥ 0** — removido o teto de 100 que a versão anterior
   tinha (não pedido, era suposição minha); `taxa_administradora` também só valida ≥ 0 quando
   preenchida.
4. **`taxaAdministradora` virou campo opcional** no formulário de Tipo de Carteira (sem
   asterisco) — nem todo tipo de carteira cobra taxa; prazo de pagamento e taxa aceitam 0
   (já funcionava, confirmado com teste dedicado).
5. **Ícones por moeda no checklist** de Tipo de Carteira (`IconeDaMoeda`, heurística por
   palavra-chave no nome — `IconeFormaCartao` para "CART*", `IconeFormaPix` para "PIX", ícone
   genérico (`IconeMoeda`) para o resto, já que o nome da moeda é texto livre).
6. **113 testes verdes** no total (ajustados: um teste antigo assumia que `perc:150` seria
   rejeitado — agora é aceito; outro assumia `percDesconto`+`percAcrescimo` positivos juntos
   seria aceito — agora é rejeitado); `tsc -b` limpo; testado ao vivo (exclusividade mútua
   limpando o campo oposto, taxa/prazo em branco/zero salvando corretamente, ícones aparecendo
   no checklist).

### 2026-07-23 — Confirmação antes de salvar ao pressionar Enter, nas 5 telas de cadastro

Bug relatado pelo dono do produto durante o teste manual geral: na tela de Fornecedor, com o
foco num campo de texto (ex.: CEP), pressionar Enter salvava a tela direto — o Enter caía no
`<form onSubmit>` nativo do navegador e acionava o mesmo caminho do botão "Salvar", sem
nenhuma confirmação. Pedido: Enter deve pedir confirmação antes de salvar, em todas as telas
de cadastro (não só Fornecedor).

1. **`web/src/components/ConfirmarSalvarModal.tsx` (novo):** modal reutilizável — "Salvar
   dados? Deseja salvar os dados deste cadastro?" com Cancelar/Salvar — mesmo estilo visual
   dos modais de confirmação de exclusão já existentes (`.modal-overlay`/`.modal`).
2. **`web/src/lib/formularios.ts` (novo):** `aoTeclarEnterNoFormulario(e, aoConfirmar)` —
   intercepta Enter (`preventDefault`) **só quando o alvo é um `<input>` de texto** (exclui
   `checkbox`/`radio`/`button`/`submit` e, por não ser `<input>`, `<select>` também fica de
   fora) — Tab/Enter em checkbox, rádio, select e botões continua 100% nativo, sem passar pelo
   modal. Só esse caminho abre a confirmação; **clicar direto no botão "Salvar" continua
   instantâneo, sem confirmação nenhuma** (o pedido era especificamente sobre o Enter).
3. **As 5 telas de cadastro** (`ClienteForm`, `FuncionarioForm`, `PlanoContasForm`,
   `FornecedorForm`, `ProdutoForm`) ganharam o mesmo padrão: a função de submit foi dividida
   em `validarEEnviar()` (validação + `salvar.mutate()`, sem depender de `FormEvent`) e
   `submeter(e)` (só chama `e.preventDefault()` + `validarEEnviar()`, ligado ao `onSubmit` do
   `<form>` — usado pelo clique no botão "Salvar"); o `<form>` ganhou `onKeyDown` chamando
   `aoTeclarEnterNoFormulario`, que abre o modal em vez de submeter; confirmar no modal chama
   `validarEEnviar()` (mesma validação de sempre, inclusive toast de campo obrigatório se
   faltar algo). **Parâmetros do Sistema (`configuracao.geral`) e Login não foram alterados**
   — o pedido era sobre "telas de cadastro" e ambos já são explicitamente fora desse padrão.
4. **Verificação:** `tsc -b` limpo. Testado ao vivo no navegador (Fornecedor e Cliente): Enter
   no CEP abre "Salvar dados?"; Cancelar fecha sem validar nem salvar; Salvar no modal roda a
   validação normal (toast/erros se faltar campo obrigatório); clique direto no botão "Salvar"
   do topo continua sem pedir confirmação.

### 2026-07-23 — Acesso de dev ao bucket de imagens concedido ao Claudio (Opção A do handoff)

Primeira concessão de acesso pela **Opção A** de `docs/infra/armazenamento-imagens.md` §3
(ADC pessoal, sem arquivo de chave): a conta Google do Claudio Calixto
(`claudiocalixto6969@gmail.com`) recebeu `roles/storage.objectAdmin` **apenas em
`gs://niner-erp-dev`** — produção intocada, nenhuma chave privada trafegou. Feito pelo
console web (aba Permissões do bucket), já que a máquina Windows atual não tem `gcloud`.
Falta o lado dele: `gcloud auth application-default login` + teste de escrita da §7 do
handoff. Registro da concessão adicionado à própria §3 do handoff.

### 2026-07-23 — Banco recriado do zero para teste manual geral + carga da tabela oficial de NCM

Pedido do dono do produto: teste geral e manual de tudo que já foi construído até aqui.

1. **Banco recriado do zero:** volume `niner_pgdata` apagado e recriado, V001–V027 reaplicadas
   via Flyway (schema limpo, sem dado nenhum). API/web/site subidos.
2. **Tenant de teste criado** via `POST /api/publico/assinar` (fluxo público de verdade, não
   seed manual) para já ter credenciais prontas: loja `loja-teste-manual`, e-mail
   `teste@niner.dev`, papel ADMIN.
3. **Carga da tabela oficial de NCM (Receita Federal)** em `cfg_produto_ncm`, a partir de
   `C:\FIX\TABELA_NCM.csv` fornecido pelo dono do produto — substitui a massa de ~51 códigos de
   exemplo carregada em 2026-07-22 (`db/scripts/seed_cfg_produto_ncm.sql`) por uma base real de
   **10.442 códigos** (8 dígitos + descrição + alíquota IBPT). Duas particularidades do arquivo:
   Latin-1 (não UTF-8) e descrições com `;` embutido (não é um CSV de 3 colunas simples — algumas
   linhas têm até 8 campos brutos). Tratado com `awk` (recompôs a descrição a partir do 2º até o
   penúltimo campo, `codigo`=1º e `aliquota`=último, com aspas/CSV quoting) e carregado via
   `COPY ... WITH (ENCODING 'LATIN1')` numa tabela de staging temporária, depois
   `INSERT ... ON CONFLICT (codigo_ncm) DO UPDATE` em `cfg_produto_ncm` — upsert, não substitui a
   tabela inteira: os poucos códigos de exemplo que não constam na base oficial permaneceram.
   `cfg_produto_ncm` foi de 51 para **10.455 linhas**. **Só carga de dados** — nenhuma migration
   alterada, mesma tabela desde V017 (2026-07-22).
4. **Verificação:** acentuação conferida pós-carga (`REPRODUÇÃO`, `SUCEDÂNEOS`, `À` — sem
   caracteres corrompidos); `GET /api/v1/ncm/{codigo}` já reflete a base real.

### 2026-07-23 — Object storage das imagens de produto: infra provisionada (ADR-013)

Sessão de **infraestrutura, não de código**. `produto_imagem` (V017) existia desde 2026-07-16
com `imagem text` comentada como "URL/chave de object storage", mas o provedor nunca tinha
sido escolhido — a tela de Produtos (próximo item do roadmap) não teria onde pôr as fotos.
Escolhido **Firebase Storage/GCS**; infra criada e testada; **nenhuma linha de Java escrita**.

1. **ADR-013 registrado** (spec §6) + nota em §3.3.3. Decisões, com o porquê:
   - **Leitura pública** dos buckets. Não é descuido: ML/Shopee **rebuscam** a imagem por URL
     (revalidação, republicação), e signed URL V4 dura no máximo 7 dias — o anúncio quebraria
     em silêncio semanas depois. Consequência assumida: **só foto de produto entra nesses
     buckets**; documento/XML/anexo exigem bucket privado e outra decisão.
   - **Upload sempre pela API** (multipart), nunca navegador→bucket. Upload direto exigiria
     identidade Firebase paralela ao nosso JWT + Security Rules — dois sistemas de auth para a
     mesma regra, contra P8/P4.
   - **A coluna guarda a chave, não a URL** (`tenants/{id_tenant}/produtos/{id_produto}/{uuid}.webp`),
     com `id_tenant` sempre do `TenantContext` (P8) e `{uuid}` aleatório (bucket público ⇒
     caminho não pode ser enumerável). Trocar de provedor vira config, não migration de dados.

2. **Infra criada por `gcloud` e verificada:** projeto `niner-erp` (Blaze), buckets
   `niner-erp.firebasestorage.app` (prod) e `niner-erp-dev` (dev), ambos `southamerica-east1`
   (**região é imutável**), uniform bucket-level access, `allUsers`→`objectViewer`; conta de
   serviço `niner-api-storage@niner-erp.iam.gserviceaccount.com` com `objectAdmin`
   **apenas nos dois buckets**, nada no projeto. Testado: leitura anônima por `curl` → HTTP 200;
   escrita/exclusão autenticado como a conta de serviço → ok.

3. **Chave privada fora do git:** `.gitignore` ganhou `api/secrets/` e `*-service-account*.json`;
   a chave vive em `api/secrets/gcs-niner-erp.json` (modo `600`) só na máquina do Evirson.
   `docs/infra/armazenamento-imagens.md` §3 documenta as duas formas de o próximo dev obter
   acesso — a recomendada é **ADC pessoal** (`gcloud auth application-default login` + papel no
   bucket de dev), **sem arquivo de chave nenhum**.

4. **`docs/infra/armazenamento-imagens.md` criado — é o handoff.** Estado provisionado,
   credenciais, contrato (caminho, o que vai na coluna, fluxo de upload, ordem de exclusão),
   TASK-A a TASK-D com critérios `Dado/Quando/Então`, riscos e comandos de verificação.

5. **Pendências deixadas explícitas:** 🔴 **alerta de orçamento não criado** (Blaze é pós-pago
   **sem teto** — responsável: Evirson, exige permissão de faturamento); 🟡 regras do Firebase
   Storage não travadas em `if false` (cinto-e-suspensório, já que o SDK cliente não é usado);
   🟡 `uso_tenant` não conta bytes por tenant (V028+ se o plano vier a limitar espaço — R19,
   decisão de produto pendente); 🟡 dimensões/formatos exigidos por ML e Shopee **a confirmar
   na doc oficial do canal** antes de virar validação.

6. **Gatilho de revisão do ADR-013:** egress é o custo dominante deste caso de uso (servir a
   foto, não guardá-la). Se pesar na fatura, migrar para **Cloudflare R2** (egress zero) — por
   isso a TASK-A é uma interface `ArmazenamentoDeArquivos` com adapter, e por isso a coluna
   guarda a chave.

### 2026-07-22 — Gerador de código de barras interno (EAN-13) para `produto_barra.sku`

Pedido do dono do produto: `sku` (o identificador interno de cada variação, impresso como
código de barras na loja) deixa de ser texto livre e passa a ser **sempre** um EAN-13 gerado
pelo sistema, estrutura `FIIISSSSSSSSD` — F=9 fixo (código de circulação restrita, não é GS1
de verdade), III=`id_banco` (3 dígitos), SSSSSSSS=sequencial (8 dígitos), D=dígito verificador
(algoritmo EAN-13/GTIN padrão, peso 1/3 alternado).

1. **Esclarecimento importante sobre "banco":** na conversa inicial, "Id Banco" parecia
   remeter a algo por tenant (herança do legado Firebird, onde cada loja tinha seu **próprio
   arquivo** de banco). Confirmado com o dono do produto: aqui "banco" é a **instância** de
   banco de dados (hoje só existe uma, `niner_db`) — se um dia a Vetor fizer *sharding* (uma
   segunda instância de Postgres para outro grupo de tenants), essa segunda instância nasce com
   `id_banco = 2`. Dentro de uma mesma instância, o sequencial é **um contador único
   compartilhado por todos os tenants** (não por tenant) — decisão confirmada explicitamente.
2. **`db/migration/V017__catalogo.sql`:** nova tabela `cfg_ean_gerador(id_banco smallint,
   proximo_sequencial bigint)` — **GLOBAL, sem `id_tenant`/RLS** (terceira exceção do domínio,
   mesmo motivo de `cfg_produto_ncm`: não é dado de tenant). Singleton de verdade (1 linha,
   semeada com `id_banco=1, proximo_sequencial=1`). Nova função
   `gerar_ean13_interno() RETURNS text`, `SECURITY DEFINER` (roda como `niner_owner`, dono da
   tabela) — `UPDATE ... RETURNING` atômico incrementa o contador sem precisar de lock
   explícito; `niner_app` só ganha `GRANT EXECUTE` na função, **nenhum** grant na tabela (só dá
   pra gerar código pela rotina, não manipular o contador direto).
3. **`produto_barra.sku`:** comentário atualizado — sempre gerado por `gerar_ean13_interno()`,
   nunca digitado pelo usuário (reforça a intenção que já estava no comentário original,
   "imprimível como código de barras").
4. **Decisão adiada (a pedido do dono do produto):** hoje nenhum código Java chama a função —
   a tela/serviço de variação (`produto_barra`) ainda não existe. Quando for construída, o
   plano registrado é o `ProdutoBarraService.criar()` chamar a função explicitamente antes do
   `INSERT` (mesmo estilo de `plataforma.tenant_atual()` usado nos demais módulos — derivado
   explícito no Java, não escondido em `DEFAULT`/`TRIGGER` da coluna). Um gatilho `BEFORE
   INSERT` reforçando isso no nível do banco (defesa em profundidade) foi cogitado e **adiado**
   — decidir quando a tela existir.
5. **Verificação:** novo `EanGeradorTest` (2 testes: formato de 13 dígitos + dígito
   verificador válido; chamadas sucessivas geram códigos diferentes e sequenciais) — **91/91
   testes verdes**. Testado também manualmente contra o banco local: 3 códigos gerados com
   dígito verificador conferido à mão; `niner_app` consegue chamar a função mas recebe
   "permission denied" ao tentar `SELECT` na tabela direto (confirma o isolamento de
   privilégio). Aplicado no banco local sem recriar (DDL manual + `flyway repair`, mesmo
   procedimento já usado para `produto_categoria.indice`/`cfg_produto_ncm`).

### 2026-07-22 — Campo de data: texto mascarado `dd/mm/aaaa` em todo o sistema (não `<input type="date">`)

Pedido do dono do produto: ao focar um `<input type="date">`, o foco fica "separado" em
segmentos (dia → mês → ano) e não dá pra selecionar o campo inteiro e sobrescrever ao digitar.
Confirmado com o dono do produto (`AskUserQuestion`) que essa é uma limitação real do HTML (não
existe API pra isso em nenhum navegador) — a única forma de ter o comportamento pedido é trocar
por um campo de texto mascarado, perdendo o calendário nativo.

1. **`web/src/lib/masks.ts`:** `mascararData` (`dd/mm/aaaa`, mesmo `aplicarMascara` já usado
   por CEP/telefone/NCM), `dataValida` (rejeita datas de calendário impossíveis, ex.:
   "31/02/2026"), `dataParaIso`/`isoParaData` (conversão de/para o formato que a API espera).
2. **`web/src/lib/datas.ts`:** novo `hojeISO()` compartilhado (fuso local, evita o desvio de
   `toISOString()`, que é UTC) — usado por Cliente e Produto; corrige de brinde um bug latente
   no "hoje" da validação de nascimento do Cliente, que usava UTC.
3. **Três campos trocados** (`ClienteForm.tsx` nascimento; `ProdutoForm.tsx` início/final da
   oferta): viram `<input>` de texto normal com `placeholder="dd/mm/aaaa"`,
   `onFocus={(e) => e.target.select()}` (seleciona tudo, como qualquer campo de texto) e
   `onChange` aplicando `mascararData`. `lib/clientes.ts`/`lib/produtos.ts` convertem
   `dd/mm/aaaa` ↔ ISO na borda (payload da API / preenchimento a partir da resposta).
   Validações de data (`errosOferta`, nascimento) passam a checar `dataValida` antes de comparar.
4. **Verificação:** 89/89 testes do backend inalterados (o formato enviado à API continua o
   mesmo ISO de sempre — só mudou a representação em tela). Testado ao vivo: digitação contínua
   sem pular entre segmentos; clicar num campo já preenchido seleciona tudo e sobrescreve.

### 2026-07-22 — Peso bruto/líquido: digitação natural (3 casas) + peso líquido ≤ peso bruto

Dois pedidos do dono do produto: os campos de peso são `numeric(14,3)` (3 casas, diferente de
moeda/percentual que têm 2) e precisavam da mesma digitação natural; e peso líquido não pode
ser maior que peso bruto.

1. **`web/src/lib/masks.ts`:** as funções de máscara decimal (`mascararMoeda`/`mascararPercentual`,
   ver entrada abaixo) ganharam um parâmetro `casas` — `mascararPeso`/`completarPeso`/
   `desmascararPeso` reusam a mesma lógica com `casas=3`. Novo `formatarPeso` (3 casas) para
   pré-popular o campo ao editar um produto existente.
2. **`ProdutoForm.tsx`:** peso bruto/líquido usam a máscara nova (antes eram texto livre sem
   máscara nenhuma); nova validação `erroPesoLiquido` (ao sair de qualquer um dos dois campos)
   + reforço no backend (`ProdutoService.validar`, 400 "Peso líquido deve ser menor ou igual ao
   peso bruto.").
3. **Verificação:** 2 novos testes em `ProdutoCrudTest` (peso líquido > bruto rejeitado; igual
   aceito) — **89/89 testes verdes**. Testado ao vivo: `1` → `1,000` ao sair do campo; `1,5` >
   `1,000` mostra o erro; `0,8` ≤ `1,000` aceita.

### 2026-07-22 — Campos decimais: digitação natural em todo o sistema + preço de venda/% calculados ao vivo + regra da oferta

Sete pedidos do dono do produto numa única rodada — o maior deles é uma mudança de convenção
que afeta **todo campo monetário/percentual do sistema**, não só Produto.

1. **Digitação natural (item 1, `web/src/lib/masks.ts`):** convenção antiga lia os dígitos
   sempre da direita como centavos (tipo caixa eletrônico — digitar "150000" virava
   "1.500,00"). Nova convenção: o inteiro é digitado normalmente da esquerda pra direita; a
   vírgula abre até 2 casas decimais; sem vírgula nenhuma, o campo só ganha ",00" ao **sair do
   campo** (`completarMoeda`/`completarPercentual`), nunca a cada tecla (isso impediria
   continuar digitando o inteiro). Mesmas funções (`mascararMoeda`/`mascararPercentual`/
   `desmascararMoeda`/`desmascararPercentual`), só a implementação interna mudou — todo
   consumidor já existente (limite de crédito do Cliente, % comissão do Funcionário, 3
   percentuais dos Parâmetros do Sistema, 4 campos de Produto) ganhou o comportamento novo sem
   precisar trocar de função, só adicionando a chamada de `completar*` no `onBlur` de cada um
   (`ClienteForm.tsx`, `FuncionarioForm.tsx`, `ConfiguracaoGeralForm.tsx` — este último também
   ganhou a correção de `validarCampo` pra usar `desmascararPercentual` em vez de um cálculo de
   dígitos própria, que ficaria errado com o formato novo).
2. **Preço de venda automático (item 2, `ProdutoForm.tsx`):** editar Preço de Custo ou % de
   Venda recalcula `Preço de Venda = Custo × (1 + %/100)` a cada tecla (só quando há custo > 0).
3. **% de venda automático (item 3):** editar o Preço de Venda direto recalcula
   `% = ((Venda − Custo) / Custo) × 100` (só quando há custo informado).
4. **Regra da oferta — tudo ou nada (itens 4-7):** início, final e preço de oferta só valem
   juntos — preencheu um, os três viram obrigatórios; início não pode ser no passado; final não
   pode ser antes do início; preço de oferta tem que ser menor que o preço de venda (não `<=`).
   Nova função `errosOferta` (frontend, ao vivo por campo) e `ProdutoService.validarOferta`
   (backend, 400 com mensagem específica por regra) — mesma regra dos dois lados.
5. **Verificação:** 6 novos testes em `ProdutoCrudTest` (oferta incompleta, início no passado,
   final antes do início, preço de oferta ≥ venda, oferta válida com os 3 campos, mais o já
   existente de datas) — **89/89 testes verdes** (antes de somar os de peso). Testado ao vivo
   no navegador: `150` → `150,00`; custo 150 + 50% → venda 225,00; venda editada pra 180 → %
   recalculado pra 20; cada regra da oferta reproduzida e corrigida uma a uma.

### 2026-07-22 — Layout do formulário de Produto: reorganização pedida pelo dono do produto

Cinco ajustes de leiaute no `ProdutoForm.tsx`, todos usando o `LinhaGrid` existente (que já
redistribui a largura quando um campo configurável está oculto):

1. NCM inválido (código digitado que não existe em `cfg_produto_ncm`) agora **limpa o campo e
   avisa** ("Código NCM inválido — não encontrado."), em vez de só deixar a descrição em branco
   silenciosamente.
2. "Produto ativo" e "Descrição" viram uma linha só, com Descrição alinhada à direita (mesma
   borda das linhas de baixo).
3. Seção Categorias movida para logo abaixo de Identificação (antes vinha depois de Preços).
4. Preço de Custo, % de Venda, Preço de Venda, Início/Final da oferta e Preço de Oferta —
   6 campos numa linha só.
5. Nome da Variante em Linha/Coluna + Peso Bruto/Líquido — 4 campos numa linha só (as seções
   "Dimensões" e "Variantes", antes separadas, viraram uma só: "Dimensões e Variantes").

### 2026-07-22 — Consulta de NCM no formulário de produto (descrição ao lado do código)

Pedido do dono do produto: rótulo do campo por extenso e busca automática da descrição
enquanto o usuário digita o código.

1. **Backend — `GET /api/v1/ncm/{codigo}`** (novo `NcmController`/`NcmService`, módulo
   `catalogo`): só leitura, sem POST/PUT/DELETE (mesma decisão de `cfg_produto_ncm` não ter
   tela — script cuida da carga). Consulta direta por `codigo_ncm` (sem `tenant_atual()`: a
   tabela é global). 404 amigável quando o código não existe.
2. **Frontend — `ProdutoForm.tsx`:** rótulo do campo virou "NCM - Nomenclatura Comum do
   Mercosul" (também na tela de configuração de campos e nas mensagens de obrigatoriedade do
   backend). Ao sair do campo código (`onBlur`, mesmo estilo do autopreenchimento de CEP em
   Cliente/Fornecedor), busca a descrição via `lib/ncm.ts#buscarNcm` e mostra num campo
   somente-leitura ao lado (`peso: 4` código + `peso: 8` descrição, mesma linha do
   `form-grid`); 404 não vira erro/toast, só limpa a descrição. Também busca ao abrir um
   produto existente para edição.
3. **Verificação:** 14 testes em `ProdutoCrudTest` (2 novos: NCM existente devolve descrição,
   NCM inexistente devolve 404) + suíte completa verde. Testado ao vivo (container
   reconstruído): NCM cadastrado devolve descrição, NCM inexistente devolve 404.

### 2026-07-22 — `cfg_produto_ncm`: tabela global de referência de NCM

Pedido do dono do produto: uma tabela que guarde código NCM + descrição para o campo
`produto.codigo_ncm` (até então só texto livre, sem validação nem lookup).

1. **`db/migration/V017__catalogo.sql`** — nova tabela `cfg_produto_ncm(codigo_ncm PK,
   descricao_ncm NOT NULL, aliquota_ibpt NUMERIC(10,2))`. Diferente de toda outra tabela do
   domínio: **sem `id_tenant` e sem RLS** — decisão explícita do dono do produto ("tabela de
   uso geral, igual para todos os tenants"), mesma exceção de `plataforma.*` (P9), só que fora
   daquele schema. Por não ter `id_tenant`, o guarda-corpo de P8 (V024) nem a enxerga — não
   precisa de tratamento especial ali.
2. **Sem tela de manutenção** (decisão explícita): a tabela é carregada/atualizada por script.
   `niner_owner` (dono da tabela) recebeu `GRANT SELECT, INSERT, UPDATE, DELETE` explícito —
   redundante em produção (ele já é dono), mas necessário no ambiente de teste (Testcontainers),
   onde quem roda as migrations é o superusuário do container, não `niner_owner` de verdade.
   `niner_app` só tem `GRANT SELECT` — a aplicação nunca escreve nessa tabela.
3. **Vínculo com produto:** `produto.codigo_ncm` (V017) ganhou
   `REFERENCES cfg_produto_ncm (codigo_ncm)` — FK simples (não composta, diferente do resto do
   domínio: o alvo não tem `id_tenant`), continua `nullable` (produto pode não ter NCM ainda).
4. **Efeito colateral corrigido:** o `catch (DataIntegrityViolationException)` de
   `ProdutoService.criar/atualizar` tratava toda violação de FK como "categoria não existe" —
   com a nova FK de NCM isso ficaria enganoso. Novo método `erroDeVinculo` inspeciona o nome da
   constraint na causa raiz (mesmo princípio de `ClienteService.duplicidade`) e devolve "NCM
   informado não existe." quando é o caso — 400, não 500.
5. **Migration já aplicada neste ambiente local** (25h no ar): como editar uma migration já
   rodada quebra o checksum do Flyway, apliquei o `CREATE TABLE`/`GRANT`/`ALTER TABLE ADD
   CONSTRAINT` equivalente direto no Postgres local e rodei `flyway repair` para realinhar o
   checksum (mesmo procedimento já usado para o `produto_categoria.indice`, nesta mesma
   sessão) — sem recriar o banco, sem perder os tenants de teste já criados.
6. **Verificação:** 12 testes em `ProdutoCrudTest` (incluindo NCM válido e inexistente,
   inserido/consultado direto via SQL já que não há endpoint de escrita) + suíte completa —
   **81/81 testes verdes**. Testado também ao vivo contra o servidor real (container
   reconstruído): NCM cadastrado é aceito e devolvido no produto; NCM inexistente vira 400 com
   `"NCM informado não existe."`.

### 2026-07-22 — Quinta tela de domínio: Produtos (`catalogo.produto`) — primeiro corte vertical do catálogo

Primeira tela do módulo `catalogo` (docs/telas/produto.md). Maior entrega de uma vez até aqui:
CRUD completo de produto + duas particularidades estruturais que nenhuma tela anterior tinha
(N:N com ordenação; campo controlado por configuração de **outra** tela).

1. **Backend — módulo `catalogo` (novo):** `ProdutoController/Service/Dtos`
   (`GET/POST/PUT/DELETE /api/v1/produtos`, mesmo padrão de paginação/ordenação/exclusão com
   fallback do resto do domínio) + `CategoriaProdutoController/Service/Dtos`
   (`GET/POST/PUT /api/v1/categorias-produto`, criar/listar/renomear, sem exclusão — mesmo
   desenho da categoria de cliente).
2. **Categorias N:N ordenadas:** o request de produto recebe `categorias` como lista de
   `idCategoria` **na ordem escolhida pelo usuário**; o servidor deriva o `indice`
   (`produto_categoria.indice`, adicionado nesta mesma sessão — ver entrada abaixo) da posição
   na lista e substitui todas as linhas a cada save (apaga + reinsere, não faz *diff*).
   Categoria duplicada ou inexistente na lista → 400, não 500.
3. **Nome de variante controlado por `cfg_geral`:** `nomeVarianteLinha`/`nomeVarianteColuna` só
   persistem se a respectiva flag (`cfg_usa_variante_linha`/`cfg_usa_variante_coluna`,
   Parâmetros do Sistema) estiver ligada — o servidor força `null` quando desligada, mesmo que
   o cliente envie valor. Flags expostas via `ConfiguracaoGeralService.flagsVariante()`
   (método novo, sem checagem de ADMIN — diferente do resto de `cfg_geral`).
4. **Frontend — `pages/produtos/`** (lista + formulário + configuração de tela): categorias com
   setas de reordenação + botões "＋ Adicionar"/"＋ Gerenciar categorias"
   (`CategoriaProdutoModal.tsx`); seção "Variantes" condicional às flags; ícone próprio.
5. **Verificação:** 12 testes em `ProdutoCrudTest` (novo) + suíte completa — **80/80 testes
   verdes** neste ponto. Testado ao vivo: categoria criada e vinculada ao produto, ordem
   preservada.

### 2026-07-22 — `produto_categoria.indice`: ordenação da categoria dentro do produto

Campo novo pedido pelo dono do produto. Tabela `produto_categoria` ainda sem dados em
qualquer ambiente, então a coluna entrou **direto na migration V017** (que já cria a tabela),
sem `V028` nova — mesma convenção usada até aqui enquanto o banco não sobe em lugar real (ver
`docs/migration/README.md`).

1. **`db/migration/V017__catalogo.sql`:** `produto_categoria` ganhou
   `indice SMALLINT NOT NULL DEFAULT 0` + `CONSTRAINT produto_categoria_indice_uk UNIQUE
   (id_tenant, id_produto, indice)` — mesmo padrão já usado em `produto_imagem.indice` (V017,
   2026-07-16) para a galeria de imagens. `COMMENT ON COLUMN` documentando o propósito.
2. **Não houve rebuild do banco** (regra combinada: só editar + documentar; o dono do produto
   pede o rebuild/teste explicitamente quando quiser).
3. **Spec atualizada** (`spec-driven-erp-varejo.md` §3.3.3): `produto_categoria` no bloco de
   modelo de dados + nota explicando a ordenação, ao lado da nota já existente sobre
   `produto_imagem`. `db/migration/README.md` (linha da V017) também atualizado.

### 2026-07-21 — Parâmetros do Sistema (`cfg_geral`): primeira tela fora do padrão de cadastro

Pedido do dono do produto. Diferente de toda tela anterior (Cliente/Funcionário/Fornecedor/
Plano de Contas), `cfg_geral` é um **singleton por tenant** (semeado com valores padrão no
signup) — a tela é só leitura/atualização, sem listagem, criação, exclusão, paginação,
`InfoRegistro` ou modo somente-leitura. Ver `docs/telas/configuracao-geral.md` (spec completa,
com a lista de particularidades estruturais).

1. **Decisão de produto perguntada e confirmada antes de implementar:** acesso **somente
   ADMIN** (leitura e escrita — mais restrito que os cadastros, onde OPERADOR também opera) e
   os 4 campos de crediário (Fase 2/Q5, ainda sem módulo de crediário) aparecem editáveis
   desde já, com aviso na tela, em vez de ficarem ocultos.

2. **Backend — módulo novo `configuracao.geral`** (`GET/PUT /api/v1/config-geral`, sem
   POST/DELETE). Ambos os endpoints verificam ADMIN a partir do claim `roles` do JWT (mesmo
   mecanismo de `ConfiguracaoTelaService`), inclusive o `GET` — diferente da Configuração de
   Tela, onde a leitura é liberada a qualquer papel. Validação de faixa (percentuais 0–100,
   dias ≥ 0) — não há "campo obrigatório configurável" aqui, porque a tabela inteira é
   `NOT NULL` desde V023.

3. **Frontend — `pages/configuracaogeral/ConfiguracaoGeralForm.tsx`**: formulário único
   (seções Vendas/Catálogo/Crediário), sem `InfoRegistro` (a tabela não tem `criado_em` nem um
   código de registro — só "Última atualização" abaixo do título), ícone próprio
   (`IconeParametros`) deliberadamente diferente da engrenagem usada como botão "configurar
   esta tela" em cada cadastro (para não confundir as duas ideias), e item de menu
   ("Parâmetros do Sistema") que só aparece para ADMIN — a rota em si também é protegida por
   `RequireAdmin`, defesa em profundidade.

4. **Verificação:** **6 testes novos** (`ConfiguracaoGeralTest`, incluindo bloqueio de
   OPERADOR em leitura e escrita, e isolamento entre tenants) — suíte completa em **69/69
   verdes**; `tsc -b` limpo; testado ao vivo (editar desconto e uma variante, salvar,
   recarregar a página para confirmar persistência, depois restaurar os valores padrão).

### 2026-07-21 — Quarta tela de domínio: Fornecedores (com criação rápida de plano de contas embutida)

Pedido do dono do produto, na sequência natural do Plano de Contas: `fornecedor.id_plano_contas`
é `NOT NULL` desde V016, então esta tela precisava resolver a criação/escolha do plano de
contas sem forçar o usuário a sair da tela. Ver `docs/telas/fornecedor.md` (spec completa).

1. **Backend — módulo novo `cadastros.fornecedor`** (`GET/POST/PUT/DELETE
   /api/v1/fornecedores`), no padrão consolidado (paginação por página, ordenação com
   allowlist, obrigatoriedade configurável por tenant, exclusão com fallback para inativar).
   Sem mudança de schema — `fornecedor` já existia por completo desde V016/V024. Duas regras
   específicas de fornecedor (pessoa jurídica, não física): CNPJ sempre com 14 caracteres
   (CPF de 11 dígitos é rejeitado) e **telefone aceita fixo ou celular** (10–11 dígitos),
   mais frouxo que a regra de celular-obrigatório do cliente — nova função `telefoneValido`
   em `masks.ts` ao lado da `celularValido` existente. `idPlanoContas` inexistente vira 400
   amigável (a `DataIntegrityViolationException` da FK é traduzida), não 500. O JOIN da
   listagem com `cfg_plano_contas` inclui `id_tenant` na condição — a PK do plano é
   composta, então o mesmo código existe em tenants diferentes; **o mesmo motivo exigiu
   revisitar `PlanoContasService`**, que tinha o mesmo risco de vazamento cross-tenant em
   ambiente sem RLS (SELECT/UPDATE/DELETE agora filtram `id_tenant = plataforma.tenant_atual()`
   explicitamente — pego pela suíte completa, que só falha com >1 tenant usando o mesmo
   código de plano).

2. **Frontend — `web/src/pages/fornecedores/`** (lista + formulário + configuração de tela)
   no padrão de Cliente/Funcionário, com um mecanismo novo: **`PlanoContasModal.tsx`**,
   criação rápida de plano de contas embutida no formulário de fornecedor (botão "＋ Novo" ao
   lado do select de Plano de Contas) — mesmo papel do modal de categoria do cliente. A
   listagem ganhou filtro por plano de contas (select, não texto livre). Ícone próprio
   (caminhão) e item "Fornecedores" no menu, entre Clientes e Funcionários.

3. **Verificação:** **12 testes novos** (`FornecedorCrudTest`, incluindo o caso de plano de
   contas inexistente, CNPJ alfanumérico, CPF rejeitado no campo CNPJ, telefone curto e
   exclusão com fallback) — suíte completa em **63/63 verdes**; `tsc -b` limpo; testado ao
   vivo criando um fornecedor completo pelo formulário (com o modal de plano de contas) e,
   via API direta (mais confiável que repetir 10 vezes o formulário no navegador), mais **10
   fornecedores** de dados variados (diferentes estados/regiões, com e sem campos opcionais)
   — todos conferidos na listagem e reabrindo um deles para edição (select de plano de contas
   populado corretamente).

### 2026-07-21 — Terceira tela de domínio: Plano de Contas (+ `criado_em`/`atualizado_em` em `cfg_plano_contas`)

Pedido do dono do produto em duas partes: adicionar os campos de auditoria que faltavam em
`cfg_plano_contas` (a tabela nasceu em V016 sem eles) e construir a tela de cadastro. Ver
`docs/telas/plano-contas.md` (spec completa). É a tela que destrava o cadastro de
**Fornecedor** (`fornecedor.id_plano_contas` é `NOT NULL` sem seed — antes desta tela era
impossível criar um fornecedor).

1. **Schema:** `criado_em`/`atualizado_em` (`timestamptz NOT NULL DEFAULT now()`) adicionados
   **na própria V016** (banco em construção — convenção do projeto, não migration nova);
   `db/migration/README.md` atualizado. **Banco recriado do zero** (V001–V027) e massa de
   teste restaurada na sequência: os 2 tenants assinados na mesma ordem (para
   `loja-teste-manual` continuar sendo o tenant 2, que os seeds referenciam), 110 clientes,
   funcionária Maria e o cliente com CNPJ alfanumérico recriados via seed/API.

2. **Backend — módulo novo `cadastros.planocontas`** (`GET/POST/PUT/DELETE
   /api/v1/planos-contas`), no padrão consolidado, com duas adaptações estruturais que vêm do
   schema (não são escolhas): a **PK é a chave de negócio** (`id_plano_contas text`, o código
   contábil ex. "3.1.001" — digitado pelo usuário ao criar, único por tenant, **imutável**
   depois: o PUT usa o código do path e ignora o do corpo, provado por teste); e **não existe
   `ativo`**, então a exclusão não tem fallback de inativar — com vínculo em
   `fornecedor`/`contas_pagar`, responde **409** e nada muda. `tipoMovimento` validado contra
   os valores exatos do ENUM `tipo_movimento_conta` (**com acentos**: CRÉDITO/DÉBITO/NEUTRO)
   — DTO usa `String` + allowlist em vez de enum Java com identificadores acentuados. Busca
   única (`busca`) cobre código OU descrição.

3. **Sem configuração de campos para esta tela** (sem chave em `CAMPOS_POR_TELA`, sem ⚙):
   todos os campos são NOT NULL — estruturalmente obrigatórios não são configuráveis
   (docs/telas/configuracao-tela.md), não sobra nada para configurar.

4. **Frontend — `web/src/pages/planocontas/`** (lista + formulário; sem tela de configuração)
   + `lib/planoContas.ts` + ícone novo `IconePlanoContas` (prancheta) + item "Plano de Contas"
   no menu + ajuda R22. Rotas usam o próprio código (`/planos-contas/3.1.001` — pontos são
   URL-safe; `encodeURIComponent` por segurança). Na edição o Código aparece bloqueado
   (`.campo-leitura`) e o foco automático pula direto para a Descrição. `InfoRegistro` passou
   a aceitar **código texto** (prop `codigo: number | string`) — primeira tela cuja PK não é
   numérica.

5. **Verificação:** **8 testes novos** (`PlanoContasCrudTest`) — suíte completa em **51/51
   verdes** (inclui: código imutável no PUT, 409 na exclusão com fornecedor vinculado, tipo
   sem acento rejeitado); `tsc -b` limpo; testado ao vivo no navegador (criar "3.1.001 —
   RECEITA DE VENDAS — CRÉDITO — DRE" → aparece na listagem; editar com código bloqueado e
   Informações do registro preenchidas). Nota: durante o teste ao vivo, a extensão **Dark
   Reader** do navegador reestilizou a página numa navegação (cores apagadas) — não é defeito
   do app; as variáveis CSS do design system estavam intactas.

### 2026-07-21 — Campos informativos de auditoria (código/cadastrado em/última alteração), somente leitura, em toda tela de cadastro

Pedido do dono do produto logo depois da tela de Funcionário: os campos gerados pelo banco
que até então só existiam na API (e estavam explicitamente documentados como "não aparecem no
formulário") passam a ser **exibidos** nas telas, como informação — mas nunca editáveis. Vira
**convenção do projeto**, não um detalhe de duas telas: `criado_em`/`atualizado_em` são
declarados em **14 migrations**, ou seja, praticamente todo o domínio vai reutilizar isso.

1. **Componente único (`web/src/components/InfoRegistro.tsx`)**, em vez de repetir o bloco em
   cada formulário: recebe `codigo`/`criadoEm`/`atualizadoEm` e renderiza a seção
   "Informações do registro" com os três campos. Toda tela de cadastro futura cuja tabela
   tenha esses campos só precisa importar e passar os valores.

2. **Somente leitura de verdade:** `readOnly` + `tabIndex={-1}` (ficam fora da navegação por
   Tab, para não atrapalhar quem preenche o formulário no teclado) + classe nova
   `.campo-leitura` (fundo transparente, texto apagado) para diferenciar visualmente de um
   campo de entrada. Não são editáveis nem no modo de edição — só o banco os escreve.

3. **Somem ao incluir** um registro novo (`if (!codigo) return null`) — não faz sentido
   mostrar código/datas de algo que ainda não existe.

4. **Formatação de data em `lib/datas.ts`** (`formatarDataHora`): ISO 8601/`timestamptz` da
   API vira padrão brasileiro (`21/07/2026, 13:51`). Arquivo novo, genérico — primeiro
   utilitário de data do projeto.

5. **Aplicado em Cliente e Funcionário.** Verificação ao vivo: no cliente editado antes nesta
   sessão, "Cadastrado em" (08:44) e "Última alteração" (11:00) aparecem **diferentes**, o que
   confirma que os dois timestamps são reais e distintos (não um espelhando o outro); ao abrir
   "novo funcionário" a seção não aparece, como esperado.

### 2026-07-21 — Segunda tela de domínio: Funcionários (CRUD ponta a ponta, replicando o padrão de Cliente)

Primeira tela construída **inteiramente sobre o padrão consolidado em `cadastros.cliente`** —
o dono do produto pediu explicitamente para fazê-la sozinho, sem perguntas, como teste de que
o padrão está de fato estabelecido e reproduzível. Ver `docs/telas/funcionario.md` (spec
completa, escrita depois da implementação a pedido do dono do produto).

1. **Backend — módulo novo `cadastros.funcionario`:** `FuncionarioController`/`Service`/`Dtos`
   sobre a tabela `funcionario` (já existia desde V016, **sem migration nova**). Mesmo
   desenho do cliente: `GET/POST/PUT/DELETE /api/v1/funcionarios`, paginação por número de
   página + contagem total, `ORDER BY` dinâmico com allowlist de colunas, validação de
   servidor (CPF com dígito verificador, celular 11 dígitos + 3º=9, % comissão entre 0 e 100)
   e obrigatoriedade configurável por tenant (`cfg_tela_campo`, chave nova
   `cadastros.funcionario.form` registrada em `ConfiguracaoTelaService`). Exclusão com
   fallback para inativar quando há vínculo — aqui o vínculo é com
   `produto_movimento_detalhe.id_funcionario` (ledger de estoque, V019), não com `venda`.

2. **`Documentos` virou público** (era package-private em `cadastros.cliente`) para o módulo
   de funcionário reaproveitar a validação de CPF em vez de duplicar o algoritmo do dígito
   verificador. Funcionário é sempre pessoa física — a parte de CNPJ alfanumérico não se
   aplica.

3. **Diferenças deliberadas em relação ao cliente**, todas vindas do schema mais enxuto de
   `funcionario` (não são simplificações arbitrárias): sem categoria (não existe
   `cfg_categoria_funcionario`), sem CNPJ/tipo de pessoa, sem endereço/redes sociais/e-mail, e
   **CPF não é único por tenant** — decisão já registrada em V016/§3.3.9 ("o CPF deixou de ser
   único"), o oposto do cliente. Há um teste explícito provando que dois funcionários podem
   ter o mesmo CPF. `id_empresa` não aparece no formulário: é preenchido automaticamente com a
   única empresa do tenant (Q6, 1:1 no v1).

4. **Frontend — `web/src/pages/funcionarios/`:** `FuncionarioLista`/`FuncionarioForm`/
   `ConfiguracaoTelaFuncionario` + `lib/funcionarios.ts`, replicando tudo que a tela de
   cliente consolidou: shell de altura travada, cabeçalho e rodapé fixos, ordenação por
   coluna com cabeçalho em destaque, paginação em janela deslizante fixa em 50 itens, três
   ícones de ação (visualizar verde/editar azul/excluir vermelho), modo somente-leitura via
   `<fieldset disabled>`, grid compacta, maiúsculas sempre, validação por campo (blur +
   submit), ajuda contextual (R22). Ícone novo `IconeFuncionario` (maleta) à esquerda do
   título; item "Funcionários" no menu lateral.

5. **Máscara de percentual reaproveitável** (`mascararPercentual`/`formatarPercentual`/
   `desmascararPercentual` em `lib/masks.ts`) para o campo "% Comissão" — mesma técnica da
   máscara de moeda já existente (dígitos digitados contam da direita para a esquerda como
   centésimos: digitar "550" vira "5,50").

6. **Layout do formulário ajustado depois do primeiro teste visual** (dois pedidos do dono do
   produto): CPF e Celular passaram a dividir a mesma linha (6+6 colunas), e o Nome passou a
   ocupar a linha inteira (12 colunas, era 8) — deixando as três linhas do formulário
   simétricas. A seção "Contato" separada foi absorvida pela "Identificação" (com só um campo
   de contato não se justificava).

7. **Verificação:** **10 testes novos** (`FuncionarioCrudTest`) — suíte completa em **43/43
   verdes**; `tsc -b` sem erros; API recompilada. Testado ao vivo no navegador: criar
   funcionário com CPF/celular/cargo/comissão mascarados corretamente → salvou e apareceu na
   listagem; modo visualizar com campos desabilitados; ordenação por coluna; e a configuração
   de tela ponta a ponta (marcar "Cargo" como obrigatório fez o campo ganhar `*` e bloquear o
   submit vazio).

### 2026-07-21 — CPF/CNPJ: suporte ao CNPJ alfanumérico (Receita Federal, a partir de julho/2026) — convenção para toda tabela com campo CNPJ

Bug reportado pelo dono do produto: o campo CPF/CNPJ não aceitava letras quando a pessoa era
jurídica. Investigação (pesquisa na internet, ver fontes abaixo) confirmou que **não era bug do
sistema** — é uma mudança real e recente da Receita Federal: a partir de julho/2026 (Instrução
Normativa RFB 2.229/2024), o CNPJ passa a ser **alfanumérico**. Vira **convenção do projeto**,
registrada aqui para qualquer tabela futura que tenha campo de CNPJ (fornecedor, empresa/tenant
etc. — ver `docs/telas/cliente.md` para os detalhes de implementação).

1. **O que mudou no CNPJ:** 14 caracteres, como sempre. As 12 primeiras posições (raiz+ordem)
   agora podem ser dígitos `0-9` **ou** letras `A-Z` maiúsculas; os 2 dígitos verificadores
   finais (posições 13-14) continuam **sempre numéricos**. CNPJs só-numéricos emitidos antes da
   mudança continuam válidos (o cálculo é o mesmo, só a tabela de valor por caractere ficou mais
   ampla). **CPF não muda** — continua só numérico, 11 dígitos, sem alteração nenhuma.

2. **Algoritmo do dígito verificador — confirmado com exemplo oficial antes de implementar.**
   Valor de cada caractere = código ASCII menos 48 (dígitos '0'-'9' viram 0-9 — o próprio valor,
   já que '0' é ASCII 48 — e letras 'A'-'Z' viram 17-42). Pesos e módulo 11 **não mudaram**: 1º
   dígito verificador com pesos `[5,4,3,2,9,8,7,6,5,4,3,2]` sobre os 12 primeiros caracteres; 2º
   com pesos `[6,5,4,3,2,9,8,7,6,5,4,3,2]` sobre os 12 + o 1º DV; resto da divisão por 11 vira
   dígito por `resto < 2 ? 0 : 11 - resto`. Verificado manualmente com o exemplo oficial
   `12.ABC.345/01DE-35` (soma ponderada bate com os dígitos 3 e 5) antes de escrever qualquer
   código — inclusive uma verificação em Node standalone reproduzindo o exemplo.

3. **Frontend (`web/src/lib/masks.ts`):** nova função `somenteAlfanumerico()` (maiúsculas,
   mantém `0-9A-Z`, ao contrário de `somenteDigitos()` que descartava letras como se fossem
   máscara). `mascararCpfCnpj()` usa `somenteAlfanumerico` só para CNPJ (pessoa jurídica); CPF
   continua com `somenteDigitos`, sem mudança. `cnpjValido()`/`documentoValido()` passaram a
   usar `charCodeAt(0) - 48` em vez de `Number()` puro (que dava `NaN` para letras), e exigem
   que as posições 13-14 sejam dígitos. `lib/clientes.ts` (`paraRequisicao`) e `ClienteForm.tsx`
   (checagem de duplicidade ao sair do campo) também pararam de usar `somenteDigitos` no CNPJ —
   senão as letras seriam descartadas antes de chegar na API.

4. **Backend (`Documentos.java`/`ClienteService.java`):** mesma lógica em Java. Achado
   interessante: a função `digitos()` já fazia `c - '0'` — que **por coincidência já era** a
   fórmula "ASCII menos 48" —, então o cálculo do dígito verificador não precisou mudar; só
   faltava não descartar as letras antes (`somenteDigitos` → `somenteAlfanumerico` nos dois
   pontos que persistiam/filtravam `cpfCnpj`: INSERT/UPDATE em `adicionarCamposComuns` e o
   filtro de busca em `listar`, ambos agora condicionais por `fisicaJuridica`). Coluna
   `cliente.cpf_cnpj` já era `text` no banco — **sem migration necessária**.

5. **2 testes novos** (`ClienteCrudTest`): CNPJ alfanumérico válido é aceito e fica armazenado
   em maiúsculas; CNPJ alfanumérico com dígito verificador errado é rejeitado. **33/33 testes
   verdes** na suíte completa.

6. **Verificação ao vivo:** criado cliente PJ com CNPJ `12.ABC.345/01DE-35` — máscara aceitou as
   letras e formatou corretamente; salvou com sucesso; aparece na listagem com o CNPJ correto;
   tentar cadastrar o mesmo CNPJ noutro cliente acusa "CNPJ já cadastrado para outro cliente"
   (confirma que a checagem de duplicidade também passou a considerar as letras).

**Fontes consultadas:**
[Receita Federal — CNPJ alfanumérico](https://www.gov.br/receitafederal/pt-br/centrais-de-conteudo/publicacoes/perguntas-e-respostas/cnpj/cnpj-alfanumerico.pdf) ·
[Serpro — cálculo dos DVs](https://www.serpro.gov.br/menu/noticias/videos/calculodvcnpjalfanaumerico.pdf) ·
[Cobol Dicas — módulo 11 do CNPJ alfanumérico](https://coboldicas.com.br/blog/o-calculo-do-modulo-11-para-o-novo-cnpj-alfanumerico) ·
[TOTVS Espaço Legislação](https://espacolegislacao.totvs.com/cnpj-alfanumerico/) ·
[GitHub — FRACerqueira/CnpjAlfaNumerico](https://github.com/FRACerqueira/CnpjAlfaNumerico)

### 2026-07-21 — Ícone de identificação da tela (pessoa/engrenagem), à esquerda do título, 20% maior

Pedido do dono do produto: fixar visualmente a função de cada tela com um ícone à esquerda do
título — mesma ideia do `AjudaDaTela`/ícone de configuração, mas para a própria identidade da
tela.

1. **Ícone novo `IconeCliente`** (Heroicons outline "user", `components/Icones.tsx`) à esquerda
   do título em `ClienteLista.tsx` ("Clientes") e `ClienteForm.tsx` ("Cliente"). A tela de
   Configuração de campos reaproveita o `IconeEngrenagem` já existente, à esquerda de
   "Configurar tela de Cliente" — mesmo ícone do botão ⚙, mas maior e sem o círculo de fundo do
   botão.
2. **Classe nova `.titulo-tela`** (`styles.css`): `display:flex; align-items:center; gap:10px`,
   ícone na cor `--accent`.
3. **20% maiores** (pedido em seguida, mesmo dia): tamanho explícito passado via prop —
   `IconeCliente` de 28px (default) para 34px; `IconeEngrenagem` nesse contexto de 24px
   (default) para 29px. Não afeta os outros usos do `IconeEngrenagem` (botão ⚙ circular), que
   força 26px via CSS própria (`.ajuda-gatilho svg`), independente da prop.
4. **Verificação:** `tsc -b` sem erros; testado ao vivo nas 3 telas (Clientes, Cliente,
   Configurar tela de Cliente) — ícone visível e maior, inclusive com o formulário rolado
   (confirma que o cabeçalho continua fixo).

### 2026-07-21 — Cabeçalho fixo da grade: correção definitiva de dois bugs reais (não só CSS solto)

Dois bugs encontrados pelo dono do produto ao usar a tela de verdade (não apareciam nos meus
testes anteriores porque eu não tinha reproduzido as condições exatas) — a causa raiz de ambos
era arquitetural, não um ajuste cosmético de CSS.

1. **"Ainda rolo com o botão do mouse, e o título não fica fixo."** Causa: `html`/`body` não
   tinham `overflow: hidden`. Se `.app` (que usa `height: 100vh`) ficasse **1px mais alto** que
   o viewport real por qualquer motivo (zoom do navegador, escala do Windows, arredondamento),
   o **documento inteiro** passava a rolar — inclusive o menu lateral e qualquer cabeçalho
   "fixo" — porque esse scroll do documento não passa pelos containers internos
   (`.app-main`/`.lista-corpo`) que fazem o scroll controlado. Reproduzido de propósito
   (`document.body.style.zoom = '2'` via DevTools) antes de aplicar o fix, para confirmar a
   causa antes de mexer. Fix: `html, body, #root { height: 100%; overflow: hidden; }`.

2. **"O título CAMPO/VISÍVEL/OBRIGATÓRIO some ao rolar a grade de Clientes."** Causa distinta:
   `.table-wrap` tinha `overflow-x: auto` — que sozinho já força o navegador a tratar
   `overflow-y` como `auto` também (regra do próprio CSS: se um eixo não é `visible` e o outro
   é, o outro vira `auto`), criando um **contexto de rolagem próprio** nesse elemento. Como
   `.table-wrap` não tinha altura definida, esse contexto nunca chegava a rolar de verdade — quem
   rolava era o `.lista-corpo` por fora — mas o cabeçalho `position: sticky` da tabela gruda no
   contexto de rolagem mais próximo, que era o `.table-wrap` errado, não o `.lista-corpo`. Fix:
   `.table-wrap` ganhou `height: 100%` (passa a ser ele mesmo o contexto que realmente rola,
   dentro do espaço que o `.lista-corpo` já reserva). De quebra, a regra `position: sticky` do
   cabeçalho (`.table th`) — que só existia especificamente para a tabela de Configuração de
   Tela (`.table-config-campos thead th`) — foi generalizada para **toda** tabela do projeto.

3. **Verificação:** reproduzido o cenário de zoom via DevTools antes e depois do fix (documento
   parou de ter overflow); testado ao vivo rolando a grade de Clientes com a janela do navegador
   redimensionada para forçar overflow real — cabeçalho `NOME/RAZÃO SOCIAL...` permanece fixo no
   topo da tabela enquanto as linhas passam por baixo.

### 2026-07-21 — Barra de rolagem no padrão de cores da tela

Ajuste de polimento visual em todo o projeto (`web/src/styles.css`), pedido pelo dono do
produto ao notar que a barra de rolagem nativa do navegador (cinza claro) destoava do tema
escuro do ERP. Nova regra global (`*`, `scrollbar-width`/`scrollbar-color` para Firefox +
`::-webkit-scrollbar*` para Chrome/Edge) usa os mesmos tokens de cor do design system
(`--surface-2` na trilha, `--line-strong` na alça, `--ink-muted` no hover) — acompanha sozinha
o tema claro/escuro, sem regra separada por tema. Sem mudança de HTML/JSX, só CSS. Testado ao
vivo na listagem e no formulário de Clientes: barra discreta, na mesma paleta escura do resto
da tela.

### 2026-07-21 — Clientes: paginação fixa em 50, ordenação por coluna, ícone de visualizar, validação de backend reforçada, grid mais compacta

Rodada grande de 6 pedidos do dono do produto sobre a listagem/formulário de Clientes — a
mais substancial desde o CRUD original, porque o item 4 revelou (e corrigiu) uma lacuna real
de validação e um bug de isolamento entre tenants no ambiente de teste.

1. **Itens por página fixado em 50, sem seletor.** O seletor 10/20/50 (introduzido horas
   antes, ver entrada abaixo) saiu de novo — `ClienteLista.tsx` agora sempre pede
   `tamanho: 50` (constante `TAMANHO_PAGINA`), sem escolha na tela.

2. **Ordenação por coluna, com cabeçalho em destaque.** Cada cabeçalho da grade
   (Nome/Razão Social, CPF/CNPJ, Categoria, Celular, Cidade/UF, Status) agora ordena
   ASC/DESC ao ser clicado — segunda revisão do dono do produto pediu para deixar isso mais
   óbvio, então o cabeçalho ganhou fundo destacado (`--surface-2` + borda inferior mais
   grossa) e **todo** cabeçalho mostra um ícone "⇅" (não só o ordenado no momento, que vira
   "▲"/"▼" na cor de destaque) — sinaliza que a coluna é clicável antes mesmo do primeiro
   clique. Backend: `ClienteService.listar` ganhou `ordenarPor`/`direcao`, com uma
   allowlist de colunas (`COLUNAS_ORDENAVEIS`) mapeando a chave da API para a expressão SQL
   — nunca concatena o parâmetro do cliente direto na query. Desempate sempre por
   `id_cliente` (mesma direção) para a paginação continuar estável.

3. **Ícone verde "visualizar" antes de editar/excluir.** Terceiro ícone na grade
   (`.acao-visualizar`, novo token de cor `--sucesso`) leva a uma nova rota,
   `/clientes/:id/visualizar`, que reaproveita o `ClienteForm` inteiro com uma prop nova
   `somenteLeitura` — todo o formulário vira `<fieldset disabled>` (desabilita todo campo/
   botão descendente de uma vez, sem repetir em cada input) e o botão Salvar some do
   cabeçalho (só "Voltar" fica). Nenhum componente novo de "modo leitura" — o mesmo
   formulário serve para criar/editar/visualizar.

4. **Validação de servidor reforçada — só o frontend validava antes.** Auditoria pedida pelo
   dono do produto ("as validações do botão salvar estão só no frontend?") confirmou que
   sim: `ClienteService.validar` só checava gênero (PF), data de nascimento e dígito
   verificador de CPF/CNPJ — formato de e-mail, celular/WhatsApp (11 dígitos + 3º=9), CEP (8
   dígitos) e a obrigatoriedade configurável por tenant (`cfg_tela_campo`) só existiam no
   `ClienteForm.tsx`. Corrigido: `ClienteService` passou a injetar `ConfiguracaoTelaService`
   e replicar as mesmas regras no servidor (defesa em profundidade — a API nunca deve confiar
   só no frontend). **Bug real encontrado no caminho:** `ConfiguracaoTelaService.listar`
   filtrava só por `chave_tela`, dependendo inteiramente do RLS para isolar por tenant — no
   ambiente de teste (datasource conecta como superusuário, sem RLS) isso vazava
   configuração **entre tenants diferentes**, faria (e fez, nos testes novos) um campo
   marcado obrigatório por um tenant "vazar" como obrigatório para todos os outros. Corrigido
   com filtro explícito `AND id_tenant = plataforma.tenant_atual()` — defesa em profundidade
   consistente com o padrão já usado nos `INSERT` do módulo. **5 testes novos** em
   `ClienteCrudTest` (e-mail/celular/CEP inválidos rejeitados, campo obrigatório configurado
   é exigido pela API, ordenação por coluna/direção) — **31/31 testes verdes** na suíte
   completa.

5. **Grid de dados mais compacta.** Nova classe `.table-compacta` reduz o padding vertical
   das linhas (12px → 6px) — só aplicada na listagem de Clientes por ora.

6. **Formulário de cadastro com topo fixo.** Mesmo padrão já usado na listagem e na tela de
   configuração (ver entradas abaixo): `ClienteForm.tsx` passou a usar
   `.lista-tela`/`.lista-topo`/`.lista-corpo` — título "Cliente" + Cancelar/Salvar não rolam,
   só o corpo do formulário.

7. **Verificação:** `mvn test` 31/31 verde (suíte completa, Testcontainers); `tsc -b` sem
   erros; API recompilada e reiniciada. Testado ao vivo no navegador: cabeçalhos de coluna
   com fundo destacado e ícone "⇅"/"▲"/"▼"; clique em "Cidade/UF" ordena ASC depois DESC;
   ícone verde abre `/clientes/:id/visualizar` com todos os campos desabilitados e sem
   Salvar; edição normal de um cliente com CPF válido salva com sucesso (confirma que a nova
   validação de backend não quebrou o caminho feliz); grade visivelmente mais densa. Aprovado
   pelo dono do produto.

### 2026-07-21 — Configuração de tela: cabeçalho enxuto (Cancelar/Salvar no topo) + topo fixo

Mesmo tratamento já aplicado ao formulário de Cliente (ver duas entradas abaixo), agora
em `ConfiguracaoTelaCliente.tsx`: removido o "CADASTROS" acima do título (ficou só
"Configurar tela de Cliente"); botões **Cancelar**/**Salvar** subiram do rodapé para o
cabeçalho, ao lado do título; tela passou a usar `.lista-tela`/`.lista-topo`/`.lista-corpo` —
cabeçalho fixo, só a tabela de campos configuráveis rola. Sem mudança de backend. Testado ao
vivo: cabeçalho fixo confirmado rolando a lista de ~16 campos configuráveis; "Cancelar"
navega de volta para `/clientes` corretamente.

### 2026-07-21 — Clientes: paginação em janela deslizante (estilo grid legado) + ações da linha viram ícones

Duas mudanças visuais na listagem de Clientes, pedidas com uma captura de tela do sistema
legado (Firebird/Delphi, tela `P119 - Cadastro de Produtos`) como referência de estilo —
primeira vez que um mockup do sistema antigo é usado como golden file de um componente novo
(não só o `docs/padroes/` já existente).

1. **Paginação virou janela deslizante com primeira/anterior/próxima/última.** Antes: só
   "1 2 3 4 5 … 9 10" (5 primeiras fixas + últimas 2). Agora: até 7 números **centrados na
   página atual** (`JANELA_PAGINACAO = 7` em `ClienteLista.tsx`, função `paginasVisiveis`
   recalculada a cada mudança de página — sem reticências, a janela desliza sozinha) mais
   4 botões de ícone nas pontas — **primeira página** (`«`), **anterior** (`‹`), **próxima**
   (`›`) e **última página** (`»`) —, cada um desabilitado quando não faz sentido (ex.:
   "primeira"/"anterior" cinza na página 1). Ícones novos em `components/Icones.tsx`
   (`IconePrimeiraPagina`/`IconePaginaAnterior`/`IconeProximaPagina`/`IconeUltimaPagina`,
   Heroicons `chevron-double-left`/`chevron-left`/`chevron-right`/`chevron-double-right`).

2. **Ações da linha (Editar/Excluir) viram ícones coloridos, não mais texto.** Mesma
   referência do sistema legado tinha três símbolos (👁 verde/✏ azul/🗑 vermelho); o dono do
   produto pediu só os dois que fazem sentido aqui — **não existe modo "só visualizar"**
   separado de editar nesta tela, então o verde (observar) ficou de fora. Botões quadrados de
   32×32, ícone branco: **azul** (`.acao-editar`, token novo `--info`) para editar, **vermelho**
   (`.acao-excluir`, reaproveita `--danger`) para excluir — com `aria-label`/`title`
   descritivos (`"Editar {nome}"`/`"Excluir {nome}"`) já que o texto visível some. Ícones novos
   `IconeEditar` (pencil-square) e `IconeExcluir` (trash), mesmo padrão Heroicons outline dos
   demais ícones do projeto.

3. **Verificação:** `tsc -b` sem erros. Testado ao vivo no navegador com 92 clientes: ícones
   azul/vermelho aparecem nítidos na tabela; clique em "última página" pulou direto pra
   página 10 (janela reajustou para mostrar 4–10, "próxima"/"última" desabilitadas); clique em
   "primeira página" voltou pra página 1 (janela 1–7, "primeira"/"anterior" desabilitadas).
   Aprovado pelo dono do produto.

### 2026-07-21 — Cliente: cabeçalho do formulário enxuto (Cancelar/Salvar no topo), identificação numa linha, limite de crédito com máscara de moeda

Três ajustes pontuais pedidos pelo dono do produto no formulário de cliente (`ClienteForm.tsx`),
sem mudança de backend/migration — só frontend.

1. **Cabeçalho enxuto com ações no topo.** O título deixou de variar entre "Novo cliente"/
   "Editar cliente" (com "CADASTROS" acima) — agora é só **"Cliente"**. Os botões
   **Cancelar**/**Salvar**, que ficavam num `footer-bar` no fim da página (exigindo rolar até
   o final para salvar), subiram para a faixa superior, ao lado dos ícones de ajuda/
   configuração. O botão Salvar (`type="submit"`) é renderizado fora da árvore do `<form>`
   (o cabeçalho vem antes do formulário no JSX) — associado via atributo HTML padrão
   `form="form-cliente"`, então continua disparando a validação e o submit normalmente.

2. **Linha de identificação compacta.** O checkbox "Cliente ativo" e os rádios "Pessoa
   Física"/"Pessoa Jurídica", antes em duas linhas separadas, passaram a ficar lado a lado
   numa única linha (`.identificacao-linha`, novo em `web/src/styles.css`).

3. **Limite de crédito com máscara de moeda.** O campo aceitava texto livre (só convertia
   vírgula para ponto no envio); agora mascara a digitação como dinheiro — mesma convenção de
   caixa eletrônico/app de banco: os dígitos digitados são sempre os centavos, contados da
   direita para a esquerda (digitar "150000" mostra "1.500,00" em tempo real). Três funções
   novas em `web/src/lib/masks.ts`: `mascararMoeda` (aplicada no `onChange`),
   `desmascararMoeda` (desfaz para enviar à API) e `formatarMoeda` (formata o número vindo da
   API ao abrir o formulário de edição — `lib/clientes.ts:paraFormulario`).

4. **Verificação:** `tsc -b` sem erros. Testado ao vivo no navegador (cliente existente com
   `limite_credito = 3006.79`): cabeçalho mostra só "Cliente" com Cancelar/Salvar junto dos
   ícones; "Cliente ativo"/"Pessoa Física"/"Pessoa Jurídica" na mesma linha; campo de limite
   de crédito abre já formatado "3.006,79"; digitar "150000" vira "1.500,00" em tempo real;
   clique em Salvar (fora da árvore do `<form>`) disparou a validação normalmente (acusou CPF
   inválido de um registro de teste com CPF fictício, confirmando que o `form="form-cliente"`
   funciona). Aprovado pelo dono do produto.

### 2026-07-21 — Clientes: paginação numerada (1 2 3 … 9 10) + shell fixo (menu/topo/rodapé sem scroll)

Pedido do dono do produto depois de ver a listagem com 109 clientes de teste: a tela crescia
indefinidamente ("Carregar mais" ia empilhando linhas), o menu lateral/cabeçalho rolavam junto
com o conteúdo, e faltava pular direto para uma página qualquer. Duas rodadas no mesmo dia — a
primeira trocou o scroll infinito por "← Anterior/Próxima →"; o dono do produto pediu em
seguida uma grade numerada de páginas, o que forçou trocar o mecanismo de paginação por baixo.
Vira **convenção nova do shell do ERP**, não só da tela de Clientes.

1. **Backend — paginação por número de página, não mais por cursor.** `ClienteService.listar`
   trocou o cursor opaco (`WHERE (nome, id) > (?, ?)`) por **`LIMIT`/`OFFSET`** com contagem
   total (`SELECT count(*)` com os mesmos filtros) — necessário porque a navegação numerada
   pedida ("1 2 3 4 5 … 9 10") exige saber quantas páginas existem no total e permitir pular
   direto para qualquer uma, o que um cursor opaco não oferece. `ORDER BY nome, id_cliente`
   mantido (empate resolvido pelo id). `PaginaClientes` passou a devolver
   `{itens, pagina, tamanhoPagina, totalItens, totalPaginas}` em vez de `{itens, proximoCursor}`;
   `GET /api/v1/clientes` trocou o parâmetro `cursor` por `pagina` (1-based). Volume de
   clientes por tenant é pequeno o bastante para `OFFSET` não pesar — decisão registrada no
   código, não vira ADR. Sem migration — só ordenação/paginação, schema não mudou. **10 testes
   de `ClienteCrudTest` seguem verdes.**

2. **Frontend — grade de páginas numeradas.** `ClienteLista.tsx` trocou o histórico de cursores
   por um estado simples `pagina` (número, 1-based) — permite pular direto para qualquer página
   sem precisar visitar as intermediárias. Nova função `paginasVisiveis()` monta a navegação
   **"1 2 3 4 5 … (penúltima) (última) →"** (sem reticências quando cabem todas as páginas, até
   7). Trocar nome/categoria/status/tamanho de página volta automaticamente para a página 1.
   Seletor **itens por página** (10/20/50, **padrão 10**). `lib/clientes.ts` acompanhou os tipos
   (`cursor` → `pagina`; `proximoCursor` → `pagina`/`tamanhoPagina`/`totalItens`/`totalPaginas`).

3. **Busca por nome em maiúsculas.** O campo "Buscar por nome…" passou a normalizar o texto
   digitado em tempo real (`maiusculas()`, mesma função usada nos formulários) — consistente
   com a convenção de maiúsculas sempre (§3.7) e com o fato de os nomes já serem salvos em
   maiúsculas no banco (busca por `ILIKE`, então a caixa em si não afeta o resultado, mas a UI
   fica consistente com o resto da tela).

4. **Rodapé da paginação fixo, só a tabela rola.** A grade de páginas saiu de dentro de
   `.lista-corpo` (que rola) e virou uma terceira faixa, `.lista-rodape`
   (`flex-shrink: 0`, mesmo tratamento de `.lista-topo`) — agora a tela de Clientes tem três
   faixas: topo fixo (título+filtros), meio rolável (só a tabela) e rodapé fixo (contagem de
   clientes + paginação numerada).

5. **Shell do ERP com altura travada no viewport (`web/src/styles.css`).** Antes, `.app` só
   tinha `min-height: 100vh` — qualquer tela com conteúdo alto (ex.: tabela de clientes)
   crescia o `<body>` inteiro, arrastando o menu lateral (`.app-nav`) e o cabeçalho
   (`.app-header`) para fora da viewport ao rolar. Agora `.app` usa `height: 100vh` +
   `overflow: hidden`, e é `.app-main` quem ganha `overflow-y: auto` — o menu lateral e o
   cabeçalho superior do ERP nunca mais rolam, em **qualquer tela**, não só Clientes.

6. **Cabeçalho da listagem mais baixo.** Removido o texto "CADASTROS" (eyebrow) acima do
   título — ficou só **"Clientes"** — reduzindo a altura da faixa superior. A tela usa três
   classes novas (`styles.css`): `.lista-tela` (coluna, altura 100% do `.app-main`),
   `.lista-topo` e `.lista-rodape` (fixos, `flex-shrink: 0`) e `.lista-corpo` (só a tabela,
   `overflow-y: auto`). Convenção pensada para ser reaproveitada nas próximas telas de listagem
   (Fornecedor/Funcionário/Produtos).

7. **Verificação:** `mvn compile` limpo + `ClienteCrudTest` 10/10 verde; `tsc -b` sem erros; API
   recompilada e reiniciada (`docker compose build api && docker compose up -d api`) —
   `/actuator/health` OK. Testado ao vivo no navegador com 92 clientes ativos de teste: busca
   "carlos" vira "CARLOS" ao digitar e filtra corretamente; clique direto na página 9 (sem
   passar pelas intermediárias) mostra o bloco alfabético certo (TATIANE → UBIRAJARA); rodapé
   com contagem ("92 clientes") e paginação continua visível e no lugar ao rolar a tabela; "→"
   na última página (10) fica desabilitado. Aprovado pelo dono do produto.

### 2026-07-21 — Ícones maiores (ajuda/configuração) + grid que se reajusta ao ocultar campo

Dois ajustes de polimento visual, só frontend (sem mudança de backend/migration):

1. **Ícones reais (Heroicons, MIT) no lugar de texto/emoji.** `web/src/components/Icones.tsx`
   (novo) exporta `IconeAjuda` e `IconeEngrenagem` com o SVG original de
   `cog-6-tooth`/`question-mark-circle` (baixado do repositório oficial
   `tailwindlabs/heroicons`). Substituem o `?` de texto (`AjudaDaTela.tsx`) e o `⚙` de texto
   (`ClienteForm.tsx`/`ClienteLista.tsx`). O botão `.ajuda-gatilho` (`styles.css`) cresceu de
   34×34px para **46×46px**, ganhou fundo (`--surface-2`), borda e estado de hover/active
   mais visível — pedido explícito do usuário por ícones "maiores e mais visíveis".

2. **Grid se reajusta quando um campo fica oculto.** Antes, ocultar um campo pela tela de
   configuração (`/clientes/configuracao`) deixava um vão vazio na linha (ex.: ocultar RG
   fazia CPF/Nascimento/Gênero ficarem alinhados à esquerda com um buraco de 3 colunas à
   direita). Agora cada linha configurável do formulário usa o componente novo
   `LinhaGrid.tsx` + função `distribuirSpans()` (`lib/grid.ts`): os pesos relativos dos
   campos visíveis (mesma escala dos antigos `col-N`) são redistribuídos para somar sempre
   **12 colunas exatas**, via método dos maiores restos (evita erro de arredondamento). As
   6 linhas do formulário que têm campo configurável passaram a usar `LinhaGrid`:
   CPF/RG/Nascimento/Gênero · E-mail/Celular/Id. WhatsApp · Instagram/Facebook/TikTok ·
   CEP/Endereço · Número/Complemento/Bairro · Cidade/UF.

3. **Verificação:** `tsc -b` sem erros; testado ao vivo — com RG, Id. WhatsApp e Instagram
   já ocultos (configuração salva de teste anterior), os campos restantes de cada linha
   cresceram para preencher o espaço (ex.: CPF/Nascimento/Gênero cada um a 1/3 da linha em
   vez de 1/4 com vão vazio), e os ícones de ajuda/configuração aparecem maiores no canto
   superior direito com fundo circular.

### 2026-07-21 — Configuração de tela (campos visíveis/obrigatórios), reutilizável entre telas

Nova capacidade **transversal** (não é só do Cliente): o lojista (ADMIN) passa a poder
escolher, por tenant, quais campos aparecem e quais são obrigatórios em cada tela do
produto — pedido explicitamente como algo a reaproveitar nas "próximas telas" que vão ser
desenvolvidas. Ver `docs/telas/configuracao-tela.md` (spec completa).

1. **Migration nova (`V027__cfg_tela_campo.sql`)** — não mexe em nenhuma migration já
   aplicada; o dev **não recriou o banco**, só rodou `docker compose run --rm flyway` (que
   aplicou apenas a V027 em cima do schema já existente). Tabela `cfg_tela_campo`
   (`id_tenant, chave_tela, campo` → `visivel`, `obrigatorio`), RLS próprio no arquivo,
   `CHECK` impedindo campo obrigatório e oculto ao mesmo tempo.

2. **Backend — módulo novo `comum.telaconfig`** (não é `cadastros.cliente`, é
   propositalmente genérico): `ConfiguracaoTelaController`/`Service` —
   `GET/PUT /api/v1/config-tela/{chaveTela}`. O registro de quais campos são configuráveis
   em cada tela é um mapa estático no serviço (`CAMPOS_POR_TELA`); a primeira entrada é
   `cadastros.cliente.form`, com os 16 campos "de negócio" do formulário (CPF/CNPJ até
   Limite de crédito — Nome/Categoria são `NOT NULL` no banco e não entram; Data de
   nascimento/Gênero têm regra própria já fechada e também não entram). Reaproveita a
   convenção `chave_tela` já usada pelo catálogo de ajuda (R22). **Só ADMIN grava** — `403`
   para `OPERADOR`, checado a partir do claim `roles` do JWT (primeira vez que uma
   autorização por papel é de fato aplicada no projeto; R8 tinha essa intenção mas nunca
   tinha sido implementada em nenhum endpoint até agora). Qualquer usuário do tenant **lê**
   (o formulário precisa saber como se renderizar não importa o papel). **5 testes novos**
   (`ConfiguracaoTelaTest`): default sem configuração, ADMIN salva e o GET reflete, OPERADOR
   é rejeitado, campo não configurável é rejeitado, obrigatório+oculto é rejeitado.

3. **Frontend:** `ConfiguracaoTelaCliente.tsx` (`/clientes/configuracao`) — tabela com
   checkbox Visível/Obrigatório por campo (Obrigatório desabilita se Visível estiver
   desmarcado, refletindo a regra do banco). `RequireAdmin.tsx` (novo, mesmo padrão de
   `RequireAuth.tsx`) protege a rota — `OPERADOR` que acessar a URL direto volta pro Painel.
   Ícone **⚙** ao lado do `?` de ajuda (`ClienteForm` e `ClienteLista`), visível só para
   `ADMIN` (checado via `useEu()`, hook novo compartilhado — `Dashboard.tsx` também
   refatorado pra usá-lo, em vez de duplicar a query). `ClienteForm` passou a ler a
   configuração (`lib/configuracaoTela.ts`) e aplicar visibilidade/obrigatoriedade em tempo
   real: campo oculto some do formulário (e da validação); campo obrigatório ganha `*` no
   rótulo e passa a bloquear submit se vazio — em cima da validação por campo já existente
   (blur + submit) da rodada anterior.

4. **Verificação:** 26 testes de backend verdes; testado ao vivo no navegador — ocultar
   Instagram fez o campo sumir do formulário, marcar E-mail como obrigatório fez aparecer
   `*` no rótulo e bloquear o campo vazio (mensagem "Campo obrigatório." no blur e no
   submit).

### 2026-07-21 — Cliente: validações de UX (foco, tab, CEP, pop-up, campo a campo) + regras de negócio (nascimento, celular)

Duas rodadas de refinamento pedidas pelo dono do produto depois de testar a tela manualmente
(ver rodada de 2026-07-20 abaixo), sem mudança de escopo — só a tela de Cliente ficando mais
madura. Nenhum código novo de módulo; tudo em `cadastros.cliente` e `web/`.

**Rodada 1 — UX de formulário:**
1. Foco automático agora vai para o campo **Nome** (não mais o checkbox "Cliente ativo",
   que continua sendo o primeiro campo visualmente, só não recebe o foco).
2. Botão "＋ Nova categoria" saiu da ordem de tabulação (`tabIndex={-1}`) — com a categoria
   já escolhida, Tab vai direto para o CPF/CNPJ.
3. CEP: mensagem "CEP inválido." tanto para formato incompleto (menos de 8 dígitos, ao sair
   do campo) quanto para CEP não encontrado no ViaCEP (verificado assim que completa 8
   dígitos, sem esperar o blur).
4. **Pop-up de erro** (`web/src/components/Toast.tsx`, canto superior direito, fecha sozinho
   ou no clique) substitui a mensagem que ficava no rodapé da página — usado no formulário de
   cliente e no modal de categoria. Depois pedido para ficar **vermelho sólido com letras
   brancas** (antes era neutro com borda vermelha).
5. **Validação por campo**, não mais uma mensagem genérica só no rodapé: cada campo valida
   ao sair dele (`onBlur`) e de novo no submit; erro aparece embaixo do campo específico.
   `noValidate` no `<form>` para a validação customizada substituir a nativa do HTML5.

**Rodada 2 — regras de negócio + layout:**
6. **Data de nascimento**: não pode ser hoje nem no futuro (quando preenchida) — validado no
   front e no back. **Depois revisto: deixou de ser obrigatória** mesmo para pessoa física
   (só o gênero continua obrigatório) — a constraint `cliente_dados_pessoais_ck` (V016) foi
   **editada** (banco ainda em construção) para exigir só `genero IS NOT NULL`; banco
   recriado do zero para aplicar. Backend (`ClienteService.validar`) e frontend acompanham.
7. **Celular/WhatsApp**: exigem 11 dígitos com o 3º dígito = 9 (padrão de celular BR) quando
   preenchidos. Rótulo "Telefone" virou **"Celular"**; rótulo "WhatsApp" virou **"Id.
   WhatsApp"**, com máscara própria (`mascararIdWhatsapp`, prefixo `@` + dígitos, mesma
   convenção visual de Instagram/Facebook/TikTok) em vez do formato `(00) 00000-0000`.
8. **CPF/CNPJ duplicado**: ao sair do campo, verifica se já existe outro cliente com aquele
   documento (reaproveita `GET /api/v1/clientes?cpfCnpj=...&status=TODOS`, sem endpoint
   novo) e avisa; na edição, ignora o próprio registro.
9. **Layout horizontal**: `.app-main` (largura útil do conteúdo, todas as telas) foi de
   `900px` para `1600px` — o formulário de cliente reaproveita o espaço juntando Nome +
   Categoria numa linha e CPF + RG + Data de nascimento + Gênero na linha seguinte, em vez de
   empilhado.

**Verificação:** 21 testes de backend verdes (2 novos: nascimento no futuro rejeitado;
pessoa física sem nascimento mas com gênero aceita). Todo o resto (foco, tab, CEP, pop-up,
validação por campo, celular, WhatsApp, CPF duplicado, layout) testado ao vivo no navegador.

### 2026-07-20 — Primeira tela de domínio: Clientes (CRUD ponta a ponta) + convenções de UI

Primeira feature depois da fundação (Fase 0/1): CRUD completo de cliente, do spec à
implementação, testado ao vivo no navegador. Também vira o campo de prova de duas convenções
novas que passam a valer para **todas as telas futuras**.

1. **Spec da feature (`docs/telas/cliente.md`)** — sessão de perguntas e respostas com o dono
   do produto sobre a tabela `cliente`/`cfg_categoria_cliente` (V016): rótulos, máscaras,
   obrigatoriedade, exclusão (inativar quando há venda associada, decisão explícita), gestão
   de categoria embutida (modal criar+renomear), validação de dígito verificador de
   CPF/CNPJ, autopreenchimento de endereço por CEP, permissão (ADMIN e OPERADOR, sem
   restrição de R8).

2. **Backend — módulo `cadastros.cliente` (novo pacote).** `ClienteController`/`ClienteService`
   (`/api/v1/clientes`: listagem paginada por cursor com filtros nome/cpfCnpj/categoria/status,
   criar, buscar, atualizar, excluir) e `CategoriaClienteController`/`CategoriaClienteService`
   (`/api/v1/categorias-cliente`: criar, listar, renomear). `Documentos` valida CPF/CNPJ
   (algoritmo do dígito verificador). Exclusão **verifica vínculo com `venda` antes de agir**
   (não tenta `DELETE` e captura a violação de FK depois — no Postgres isso aborta o resto da
   transação; teria exigido `SAVEPOINT`). `ConflitoDadosException` (409) e handler de
   `IllegalArgumentException` (400) novos no `GlobalExceptionHandler`. **8 testes novos**
   (`ClienteCrudTest`), suíte completa em 19 testes verdes.

3. **Frontend — tela de Clientes (`web/`).** `ClienteLista`/`ClienteForm`,
   `CategoriaClienteModal`, `AjudaDaTela` (R22 — primeiro componente de ajuda contextual do
   projeto, conteúdo embutido como fallback estático até existir o catálogo `ajuda_tela` da
   API), `lib/clientes.ts`, `lib/masks.ts` (máscaras + validação de documento), `lib/viacep.ts`.
   Rotas `/clientes`, `/clientes/novo`, `/clientes/:id`.

4. **Bugs encontrados testando ao vivo no navegador (não em teste automatizado):**
   - `paraFormulario()` não reaplicava as máscaras (CPF/telefone/CEP apareciam crus ao abrir
     "Editar") — corrigido.
   - Uso de `confirm()`/`alert()` nativos do navegador para excluir cliente — trocado por um
     modal de confirmação e um banner in-app (mais consistente com o design system e evita
     diálogos que travam automação de navegador e são uma UX inferior num SPA).
   - Datasource de teste (Testcontainers `@ServiceConnection`) conecta como o superusuário do
     container, não como `niner_app` — uma listagem sem filtro via MockMvc via cross-tenant;
     ajustado o teste para filtrar por nome (o gate de isolamento real continua sendo
     `RlsIsolamentoTest`, que conecta como `niner_app` de propósito).

5. **Cinco ajustes pedidos depois do primeiro teste ao vivo — duas viram convenção do
   projeto, não só desta tela:**
   - **`cliente.complemento`** (texto, nullable, entre `numero` e `bairro`) — coluna nova.
     Banco ainda em construção: a coluna entrou **na própria migration V016** (não uma V027
     nova), exigindo recriar o banco de dev do zero (`docker volume rm niner_pgdata` +
     `flyway migrate`) para aplicar a mudança a uma migration já rodada.
   - **Texto sempre em MAIÚSCULAS, projeto todo** (nova regra em §3.7 da spec): todo campo de
     texto livre é normalizado no `onChange` do frontend (não importa o estado do teclado do
     usuário) e reforçado no backend (`toUpperCase(Locale.ROOT)`) como defesa em profundidade
     — inclusive valores vindos do autopreenchimento de CEP (ViaCEP retorna em
     capitalizado/minúsculo). Única exceção: e-mail, que mantém a caixa digitada.
   - **`Cliente ativo` virou o primeiro campo do formulário**, antes até do tipo de pessoa, e
     passou a aparecer também na criação (antes só no editar).
   - **Grid de 12 colunas do design system (§3.7) finalmente implementado** (documentado desde
     a fundação, nunca usado): campos pequenos (CPF, RG, número, complemento, UF, limite de
     crédito, telefone/WhatsApp) dividem linha com outros em vez de ocupar a largura toda.
   - **Foco automático** no primeiro campo do formulário ao abrir (`autoFocus`), tanto ao
     incluir quanto ao editar — também virou regra do design system (§3.7), não só desta tela.

6. **Verificação:** banco recriado (V001–V026, agora com `complemento`); **19 testes verdes**;
   fluxo completo testado ao vivo no navegador (Chrome, via automação) — signup → login
   (handoff) → criar categoria (criar/renomear) → criar cliente PF com CPF mascarado/validado,
   gênero/nascimento condicionais, CEP autopreenchendo endereço em maiúsculas → listagem com
   filtro de status → excluir cliente com venda associada → inativa e mostra aviso → cliente
   PJ com CNPJ inválido → erro inline correto → painel de Ajuda da Tela → grid compacto,
   `Cliente ativo` como primeiro campo com foco automático, `Complemento` entre Número e
   Bairro, tudo digitado em maiúsculas em tempo real.

7. **Documentação sincronizada:** `docs/telas/cliente.md` (campos/tamanhos/ordem atualizados;
   corrigida uma imprecisão — a exclusão sempre responde `200 OK` em JSON, nunca varia para
   `204`), `spec-driven-erp-varejo.md` (§3.3.9 `cliente.complemento`; §3.7 convenções de
   maiúsculas e foco automático), `db/migration/README.md` (V016).

### 2026-07-16 — Banco recriado do zero: V001–V026 aplicadas, bug real encontrado e corrigido

Dono do produto pediu para recriar a base de dados (primeira vez que isso roda desde as
revisões de schema desta sessão — V014–V026 nunca tinham sido testadas contra um Postgres
real do zero, só revisadas estaticamente).

1. **Recriação:** `docker compose stop api db` + `rm -f` dos containers + `docker volume rm
   niner_pgdata` + `docker compose up -d db` (saudável) + `docker compose run --rm flyway`.
   **26 migrations aplicadas com sucesso** (V001–V026), incluindo os dois guarda-corpos de RLS
   (V024 e V025) sem exceção — nenhuma tabela de tenant ficou sem RLS.
2. **Bug real encontrado no smoke test do signup:** `POST /api/publico/assinar` retornava
   erro ao inserir em `empresa` — `null value in column "codigo_empresa"` (constraint
   `NOT NULL` sem `DEFAULT`, adicionada a `empresa` mais cedo nesta mesma sessão, V014). O
   `SignupService.assinar()` só inserida `id_tenant`/`razao_social`, nunca tinha sido
   atualizado para as colunas novas. Investigação revelou que `cfg_nome_etiqueta` (também
   `NOT NULL` sem `DEFAULT`, mesma tabela) tinha o mesmo problema, só não aparecia ainda
   porque o Postgres erra na primeira coluna `NOT NULL` vazia que encontra (`codigo_empresa`
   vem antes na ordem de colunas).
3. **Fix em `SignupService.java`:** o `INSERT INTO empresa` passou a enviar
   `codigo_empresa = 1` (primeira empresa do tenant, Q6 — 1:1 no v1) e
   `cfg_nome_etiqueta = '{sku}\n{descricao}\n{preco_venda}'` (modelo padrão de etiqueta,
   o lojista personaliza depois — não existe tela para isso ainda). Imagem da API
   reconstruída (`docker compose build api`) e o smoke test repetido com sucesso
   (`201 Created`, tenant novo, token emitido).
4. **Verificação em banco real (via `psql` como `niner_owner`):**
   - As 7 linhas de `moeda` foram semeadas para o tenant de teste — mas só apareceram depois
     de `set_config('app.id_tenant', ...)` na sessão do `psql`: confirma que o `FORCE ROW
     LEVEL SECURITY` das tabelas novas (V025/V026) protege até o **dono** das tabelas, não só
     `niner_app` (P8 funcionando como desenhado).
   - RLS `ENABLE`+`FORCE` confirmado nas 8 tabelas novas (`tipo_carteira`, `moeda`,
     `moeda_detalhe`, `contas_receber`, `contas_receber_detalhe`, `caixa_mestre`,
     `caixa_detalhe`, `contas_pagar`).
   - `\d contas_pagar` confirmou `id_conta_pagar` como PK, `nota_fiscal integer` nullable,
     FKs compostas `(id_tenant, id_x)` para `empresa`/`fornecedor`/`cfg_plano_contas`.
   - `produto_movimento_mestre.nota_fiscal` confirmado como `integer` (padronização do V026
     aplicada de fato).
5. **Pendência do dia resolvida: suíte `./mvnw test` rodada sem JDK no host.** Sem
   `JAVA_HOME`/Java instalados no host, rodei a suíte dentro de um container com a mesma
   imagem do estágio de build do `Dockerfile` (`maven:3.9-eclipse-temurin-25`), montando o
   **repo inteiro** (não só `api/`) + o socket do Docker. Três problemas apareceram e foram
   contornados (documentados em `api/README.md`, seção Testes):
   - **Ryuk não conectava de volta** ao container do Maven (`--network host` não funciona
     como esperado no Docker Desktop) → `TESTCONTAINERS_RYUK_DISABLED=true`.
   - **JDBC apontava pro gateway interno do Docker** (`172.17.0.1`), inalcançável de dentro
     de outro container → `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` resolveu.
   - **`../db/migration` não existia** dentro do container porque só `api/` estava montada
     (o Flyway de teste usa esse caminho relativo) → montei o repo inteiro e rodei com
     `-w /workspace/api`.
   - **Resultado: 11 testes verdes** (`NinerApiApplicationTests` 1, `OnboardingTrialTest` 3,
     `RlsIsolamentoTest` 1, `SuperficiesPingTest` 6) — incluindo o gate P8 e o fluxo de
     signup completo (que já exercita o fix de `codigo_empresa`/`cfg_nome_etiqueta` do item
     acima e o insert de `moeda`). Containers de teste órfãos (Ryuk desabilitado) conferidos
     depois — nenhum ficou para trás.

### 2026-07-16 — V026: contas_pagar antecipada (mais uma revisão de Q5/ADR-010 — ADR-012)

Mesmo padrão do V025: dono do produto pediu análise antes de criar. DDL colado era, de novo,
o legado Firebird (`db/050_CONTAS_PAGAR.txt`) quase sem conversão.

1. **Achados corrigidos** (mesma lista de sempre): sem `id_tenant`; FKs simples e para
   `EMPRESAS`/`FORNECEDORES` (nomes errados — são `empresa`/`fornecedor`); `FLOAT` em
   `valor_pagar`/`valor_pago` (P7); `VARCHAR(1)` S/N em `documento_pago` (virou `boolean`);
   `TIMESTAMP` em vez de `TIMESTAMPTZ`; `GENERATED BY DEFAULT` em vez de `GENERATED ALWAYS AS
   IDENTITY`; `ID_PLANO_CONTAS VARCHAR(13)` precisando virar FK composta pro par
   `(id_tenant, id_plano_contas)` de `cfg_plano_contas` (V016).
2. **`nota_fiscal INTEGER DEFAULT 0`** — flagueado como inconsistente com
   `produto_movimento_mestre.nota_fiscal` (V019), que já era `text` nullable. Dono do produto
   decidiu **manter `integer`** (não `text`) — e pediu para **padronizar em todo o schema**:
   `produto_movimento_mestre.nota_fiscal` mudou de `text` para `integer` (só essa outra
   ocorrência existia). Ambas nullable, sem `DEFAULT 0` (valor mágico removido).
3. **PK renomeada:** campo `localizador` (nome herdado do legado, usado em `caixa_detalhe`/V025)
   virou **`id_conta_pagar`** nesta tabela — pedido explícito do dono do produto, quebrando de
   propósito a consistência de nome com `caixa_detalhe`.
4. **`documento_pago` ganhou `DEFAULT false`** — mesmo tratamento já dado a
   `contas_receber.documento_recebido` (V025), confirmado pelo dono do produto.
5. **Migration `V026__financeiro_contas_pagar.sql` criada** (arquivo novo, não dentro de V025)
   — `contas_pagar` com `id_tenant`, FKs compostas, RLS próprio no arquivo (mesmo motivo de
   V025: o guarda-corpo de V024 já tinha rodado antes desta tabela existir). Legado
   `db/050_CONTAS_PAGAR.txt` removido (já migrado). Documentação sincronizada:
   `db/migration/README.md`, `spec-driven-erp-varejo.md` (§2.7 Q5, §3.3.7, §3.5.1, ADR-012) e
   este arquivo. **Banco não recriado/testado nesta rodada** (convenção da sessão). Com V026,
   só `conta_corrente`/`conta_corrente_movimento` seguem fora do v1 (§3.3.7).

### 2026-07-16 — V025: crediário + caixa antecipados da Fase 2 (revisão de Q5/ADR-010 — ADR-012)

O dono do produto pediu 8 tabelas do legado financeiro (`tipo_carteira`, `moeda`,
`moeda_detalhe`, `contas_receber`/`_detalhe`, `caixa_mestre`/`_detalhe`) e pediu análise antes
de criar (não recriar/testar banco ainda — convenção da sessão).

1. **Revisão de escopo confirmada:** Q5/ADR-010 dizia que todo o `financeiro` (caixa,
   crediário, contas a pagar/receber, conta corrente) ficava fora do v1, para a Fase 2. O dono
   do produto confirmou que quer **antecipar crediário + caixa** para agora — mesmo movimento
   já feito com `cfg_plano_contas` (V016). Vira **ADR-012** (revisa ADR-010).
   `contas_pagar`/`conta_corrente(_movimento)` **continuam fora**, sem migration.
2. **Análise do DDL colado (legado Firebird quase sem conversão)** — achados corrigidos antes
   de criar: faltava `id_tenant` em todas as 8 tabelas; FKs simples e apontando para nomes de
   tabela errados (`VENDAS`/`EMPRESAS`/`USUARIOS` em vez de `venda`/`empresa`/`usuario`, que já
   existem desde V014/V015/V018); `FLOAT` em dinheiro/percentual (P7); `VARCHAR(1)` S/N em vez
   de `boolean`; `TIMESTAMP` em vez de `TIMESTAMPTZ`; `GENERATED BY DEFAULT` em vez de
   `GENERATED ALWAYS AS IDENTITY`; `CONTAS_RECEBER_DETALHE` sem PK nenhuma (virou PK composta
   `(id_tenant, id_conta_receber)`, 1:1 confirmado com o dono do produto); `MOEDAS_DETALHE`
   como PK `(id_moeda, id_carteira)` sem tenant (virou `(id_tenant, id_moeda, id_carteira)`).
3. **`caixa_detalhe.tipo_operacao`** — os códigos do legado (`RV`/`RP`/`DC`/`CC`/`TR`) não
   diziam o significado; confirmado com o dono do produto e virou ENUM `tipo_operacao_caixa`
   por extenso: `RECEBIMENTO_VENDA`, `RECEBIMENTO_PARCELA_CREDIARIO`, `DEBITO_CAIXA`,
   `CREDITO_CAIXA`, `TROCO`. `credito_debito` (`C`/`D`) **reaproveita** o ENUM já criado em
   V013 para o ledger de estoque, em vez de criar um tipo novo.
4. **`caixa_detalhe` ganhou `criado_em`** (ausente no legado) — uma sessão de caixa pode durar
   o dia todo com vários lançamentos em horários diferentes; sem timestamp por linha não dá
   pra saber "quando" cada lançamento ocorreu (P3 exige quem/quando/origem).
5. **Seed de `moeda` é por tenant, não global:** o legado insere 7 linhas fixas (DINHEIRO,
   PIX, CARTAO DEBITO/CREDITO, CREDIARIO, VALE PRESENTE, VALE MERCADORIA) sem tenant — como
   `id_tenant` é obrigatório e não existe no momento da migration, o dono do produto confirmou
   que o seed deve ser **por tenant no signup**, mesmo padrão de `cfg_geral` no
   `SignupService`. ✅ **Implementado em seguida (mesmo dia):** `SignupService.assinar()`
   insere as 7 linhas de `moeda` logo após criar `cfg_geral`, dentro da mesma transação
   atômica do signup (§3.3.2). Nenhum teste foi rodado/banco recriado nesta rodada (convenção
   da sessão — só sob pedido explícito).
6. **Migration `V025__financeiro_caixa_crediario.sql` criada** (nome pedido pelo dono do
   produto) — as 7 tabelas + ENUM `tipo_operacao_caixa`, todas com `id_tenant`, FKs compostas
   `(id_tenant, id_x)` (P8) e RLS **próprio no arquivo** (o guarda-corpo de V024 já tinha
   rodado antes destas tabelas existirem, então não as alcançaria). Documentação sincronizada:
   `db/migration/README.md`, `spec-driven-erp-varejo.md` (§2.7 Q5, §3.3.7, §3.5.1, lista de
   ADRs) e este arquivo. **Banco não recriado/testado nesta rodada** (convenção da sessão).

### 2026-07-16 — Revisão do schema de domínio: tipos, cascade, imutabilidade e bug de bootstrap

Auditoria linha a linha de `db/migration/V001–V024` + `db/bootstrap/00_roles.sql` (nenhum código de
domínio em `api/` ainda depende dessas tabelas, então deu para corrigir sem quebrar nada em produção).

1. **Bug bloqueante corrigido: `niner_owner` sem `CREATE` no schema `public`.**
   `ALTER DATABASE niner_db OWNER TO niner_owner` (bootstrap) muda o dono do **banco**, não do
   schema `public` pré-existente (dono é o superusuário de bootstrap da imagem). Como as
   migrations de domínio (V013+) criam tipos/tabelas sem prefixo de schema (→ `public`), o
   Flyway rodando como `niner_owner` via `docker compose run --rm flyway` falharia em V013 com
   `permission denied for schema public`. Não aparecia nos testes porque
   `api/src/test/resources/bootstrap-test.sql` roda o Flyway como o superusuário do container
   Testcontainers, não como `niner_owner` — o caminho real nunca tinha sido exercitado. Fix:
   `GRANT CREATE ON SCHEMA public TO niner_owner;` no bootstrap.
2. **Regressão revertida em V017 (catálogo).** Uma edição anterior tinha substituído a coluna
   `sku` de `produto_barra` por `ean_fabricante plataforma.sim_nao DEFAULT 'SIML'` — valor
   inválido para o ENUM (`'SIM'`/`'NÃO'`), o que quebraria a migration — e tornado `ean`
   obrigatório, revertendo a decisão **Q7** (sku interno obrigatório/único, ean opcional).
   Restaurado ao design da Q7.
3. **Tipos padronizados em todo o schema:** `id_tenant` sempre `SMALLINT` (raiz do isolamento,
   `plataforma.tenant.id_tenant`); demais PKs surrogate sempre `INTEGER`. Decisão consciente
   (revê a orientação anterior da spec de `BIGINT` genérico) — teto de 32.767 tenants, considerado
   suficiente para o público-alvo; revisitar se o funil comercial aproximar do limite.
4. **Sem `ON DELETE CASCADE` em nenhuma FK do domínio** — decisão do produto: apagar um registro
   com dependentes deve falhar por violação de FK, nunca apagar em cascata silenciosamente.
5. **Ledger de estoque imutável ao nível de banco.** `produto_movimento_mestre`/
   `produto_movimento_detalhe` (P3) ganharam `REVOKE UPDATE, DELETE ... FROM niner_app` em V024
   — mesmo tratamento que `plataforma.impersonacao_log` já tinha (V011). Correção de um
   lançamento é sempre um novo movimento compensatório.
6. **E-mail de login case-insensitive.** `usuario.email` (V015) passou de
   `UNIQUE(id_tenant, email)` para `UNIQUE INDEX (id_tenant, lower(email))`, igual ao padrão já
   usado em `plataforma.staff` (V010) — evita duas contas do mesmo tenant diferindo só por
   maiúscula/minúscula.
7. **`empresa.codigo_empresa`** (número sequencial por tenant, só para exibição em relatório)
   ganhou `UNIQUE(id_tenant, codigo_empresa)` + comentário. **`produto_movimento_mestre.
   id_transferencia`** documentado como proposital sem FK (vem de um gerador externo).
8. **Documentação sincronizada:** `db/migration/README.md` e `spec-driven-erp-varejo.md`
   (§3.1.1, §3.3.1–§3.3.6) atualizados para refletir os tipos/convenções reais e os marcadores
   🔴 já resolvidos pelas migrations V013–V024 (reserva/disponível, ledger, canal/anúncio/pedido,
   outbox/webhook).
9. **`cliente.data_nascimento` + `cliente.genero`.** Campos do legado (§3.3.9: `nascimento`,
   `genero` M/F/O) que tinham ficado de fora quando `V016` foi criado. Como o banco ainda está
   em fase de construção (nenhuma migration foi aplicada em ambiente real até agora), os campos
   entraram **direto na V016** em vez de uma migration nova: `data_nascimento DATE` +
   `genero genero_cliente` (ENUM `MASCULINO`/`FEMININO`/`OUTROS`, tipo definido em V013).
   Primeira versão os deixou `NOT NULL` incondicional; **ajustado no mesmo dia** para
   obrigatório **só em pessoa física** — colunas nullable + `CONSTRAINT
   cliente_dados_pessoais_ck CHECK (NOT fisica_juridica OR (data_nascimento IS NOT NULL AND
   genero IS NOT NULL))`, já que cliente pessoa jurídica não tem data de nascimento nem gênero.
   Convenção do projeto enquanto o banco não roda em ambiente real: só criar migration
   incremental (`V025+`) depois que V001–V024 forem de fato aplicadas num ambiente que importa
   preservar.
10. **`cfg_categoria_cliente` (V016) + `cliente.id_categoria_cliente` (`NOT NULL`).** Tabela do
    legado (§3.3.8) que também tinha ficado de fora — criada com o padrão já usado em
    `cfg_categoria_produto` (V017): `id_categoria_cliente integer GENERATED ALWAYS AS IDENTITY
    PK`, `id_tenant` (P8), `nome_categoria text NOT NULL`, `UNIQUE(id_tenant, nome_categoria)`.
    Entra **antes** de `cliente` no mesmo arquivo (ordem de FK). `cliente` ganhou
    `id_categoria_cliente integer NOT NULL REFERENCES cfg_categoria_cliente` + índice
    `(id_tenant, id_categoria_cliente)`; sem categoria padrão pré-cadastrada, então todo cliente
    precisa de uma categoria já criada. `cfg_categoria_cliente` entrou no array de RLS do V024.
11. **Galeria de imagens do produto.** `produto.imagem` (uma imagem só) removida; criada
    `produto_imagem` (V017) para várias imagens por produto: `id_produto_imagem integer PK`,
    `id_tenant` (P8), `id_produto integer NOT NULL REFERENCES produto` (sem cascade),
    `indice smallint NOT NULL` (ordem de exibição), `imagem text NOT NULL`,
    `UNIQUE(id_tenant, id_produto, indice)`. Entrou no array de RLS do V024.
12. **Seis ajustes pontuais pedidos pelo dono do produto** (V014/V016/V018/V019/V023) — só
    script + documentação, banco **não** recriado/testado nesta rodada (nova convenção: só
    recriar quando pedido explicitamente):
    - `funcionario` (V016): +`telefone`; `funcionario_cpf_uk` virou `UNIQUE(id_tenant,
      id_funcionario)` — como `id_funcionario` já é PK, isso não impõe nada além da PK; **CPF
      deixou de ser único por tenant**.
    - `venda` (V018): removido `id_funcionario` — vendedor/comissão por item ficam só em
      `produto_movimento_detalhe.id_funcionario`.
    - `cfg_geral` (V023): removido `moeda_devolucao`; adicionados `cfg_usa_variante_linha` e
      `cfg_usa_variante_coluna` (`boolean NOT NULL DEFAULT true`).
    - `empresa` (V014): novo campo `cfg_nome_etiqueta text NOT NULL` (texto/modelo da etiqueta
      de produto).
    - `produto_balanco` (V019): removidos `qtd_sistema` e `observacao`; `id_balanco` virou
      `bigint` — **exceção deliberada** à convenção "PKs surrogate são `integer`" (volume de
      contagens de inventário esperado maior que o das demais tabelas).
    - `produto_movimento_detalhe` (V019): removido `saldo_apos` — o ledger continua imutável
      (`REVOKE UPDATE, DELETE`, V024), mas deixa de gravar o saldo resultante por linha; esse
      saldo passa a existir só materializado em `produto_estoque`.
13. **Direção do controle de saldo de estoque em revisão.** O dono do produto avisou que a
    remoção de `saldo_apos` (item 12) é compatível com um plano futuro: recriar
    `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como **stored procedure acionada por trigger** em
    `produto_movimento_mestre`/`produto_movimento_detalhe` — o **oposto** do que está escrito
    hoje em `V019` e na spec (§3.3.1/§3.3.4: "domínio Java, não trigger"). Detalhes ainda **não
    definidos** ("vamos definir mais pra frente"); marcado 🔴 em `V019__estoque.sql` e na spec
    para não ficar como decisão esquecida. Quando fechar, vira ADR e a migration do
    trigger/procedure.
14. **Redes sociais em `cliente`.** `whatsapp`, `instagram`, `facebook`, `tiktok` — `text`
    nullable, sem validação de formato no banco (V016). `instagram`/`facebook` já existiam no
    legado (§3.3.9) e tinham ficado de fora; `whatsapp`/`tiktok` são novos, sem equivalente
    legado. Bloco de sequência de migrations (§3.5.1 da spec) reescrito nesta rodada para bater
    com os números reais V013–V024 (estava com placeholder `V013+`/`V0xx` desde antes da
    implementação).
15. **Item 13 fechado: trigger de estoque implementada.** `trg_produto_movimento_detalhe_estoque`
    (função `fn_atualiza_estoque_movimento()`, PL/pgSQL, fim do `V019__estoque.sql`) mantém
    `produto_estoque.qtd_estoque` em `INSERT`/`UPDATE`/`DELETE` de `produto_movimento_detalhe`:
    `credito_debito='C'` soma, `'D'` subtrai; `UPDATE` desfaz o efeito antigo e aplica o novo
    (cobre troca de empresa/variação/tipo/quantidade); `DELETE` desfaz o efeito. Faz **UPSERT**
    em `produto_estoque` — cria a linha na hora se não existir para o `(id_tenant, id_empresa,
    id_variacao)`. Não existe mais `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como objeto separado — a
    lógica inteira está na função de trigger.
    - **Reverte a regra de imutabilidade do item 12/13:** `produto_movimento_detalhe` saiu do
      `REVOKE UPDATE, DELETE` do `V024` (só `produto_movimento_mestre` continua imutável) —
      sem isso `niner_app` nunca conseguiria disparar as branches de `UPDATE`/`DELETE` da
      trigger (o `REVOKE` bloquearia o comando antes de chegar nela).
    - Escopo confirmado só em `qtd_estoque` — `reservado` continua fora (fluxo de reserva do
      pedido, Q2/ADR-004, não muda).
    - Roda como `niner_app` (SECURITY INVOKER, padrão): RLS de `produto_estoque` continua
      valendo dentro da trigger, sem risco de vazar saldo entre tenants.
    - Único trigger de banco do domínio até agora — todo o resto continua "sem trigger,
      auditoria/saldo no domínio Java" (decisão original, ainda válida para as outras tabelas).
16. **Achado real ao testar a trigger: FK simples não valida o tenant do registro referenciado
    (P8).** Testando isolamento entre tenants (não pedido, verificação por conta própria antes
    de dar a trigger por pronta), consegui — como tenant 2 — inserir em
    `produto_movimento_detalhe` uma linha com `id_tenant=2` mas `id_empresa`/`id_variacao`/
    `id_movimento` **do tenant 1**, e o INSERT passou. A trigger, obediente, criou uma linha em
    `produto_estoque` com `id_tenant=2` apontando pra `id_empresa`/`id_variacao` de outro
    tenant (`qtd_estoque` fabricado). Causa: FK simples (`REFERENCES tabela (id_x)`) só checa
    se o ID existe em algum lugar — **RLS não é aplicado na checagem de integridade
    referencial**. Isso não era bug da trigger; é uma lacuna em **toda** tabela de domínio com
    FK pra outra tabela de domínio (~34 constraints em ~17 tabelas: `usuario`, `cliente`,
    `funcionario`, `produto_categoria`, `produto_barra`, `produto_imagem`, `venda`,
    `venda_devolucao`, `produto_estoque`, `produto_movimento_mestre`,
    `produto_movimento_detalhe`, `produto_balanco`, `anuncio`, `pedido`, `pedido_item`,
    `webhook_recebido` — todo o schema de fato).
    - **Fix (2026-07-16, V014–V022):** toda tabela referenciada ganhou `UNIQUE (id_tenant,
      id_<pk>)` (ex.: `empresa_id_empresa_uk`), e toda FK entre tabelas de domínio virou
      **composta**: `FOREIGN KEY (id_tenant, id_x) REFERENCES tabela (id_tenant, id_x)`. FKs
      nullable (`venda.id_cliente`, `produto_barra.id_variante_linha` etc.) continuam
      funcionando — `MATCH SIMPLE` (padrão do Postgres) não checa a FK se qualquer coluna
      envolvida for `NULL`.
    - Convenção registrada no checklist de aprovação de spec (fim do documento) e em
      `db/migration/README.md`/`spec-driven-erp-varejo.md` §3.1.1: toda FK nova entre tabelas
      de domínio nasce composta, nunca simples.
17. **`cfg_plano_contas` antecipada (V016) — preparação para relatórios/DRE.** O dono do
    produto pediu a tabela de plano de contas (§3.3.7 do legado, que fazia parte do módulo
    `financeiro` deferido pra Fase 2 — Q5/ADR-010) + `fornecedor.id_plano_contas NOT NULL`.
    Só o plano de contas em si entrou — `moeda`, `caixa_*`, `contas_receber*`,
    `contas_pagar`, `conta_corrente*` continuam fora, sem migration (Fase 2). DDL ajustado
    ao padrão do projeto a partir do que foi passado:
    - PK vira **composta `(id_tenant, id_plano_contas)`** em vez de `id_plano_contas` sozinho
      — a chave de negócio (código contábil `text`, ex.: `"3.1.001"`) precisa do tenant pra
      não colidir entre lojistas; é a primeira tabela do domínio sem PK surrogate `integer`.
    - `VARCHAR(13)`/`VARCHAR(100)` → `text`; `VARCHAR(1) CHECK IN ('S','N')`
      (`inclui_dre`/`inclui_fluxo_caixa`) → `boolean` (regra de conversão §3.3.1).
    - `tipo_movimento VARCHAR(1) CHECK IN ('C','D','N')` → novo ENUM `tipo_movimento_conta`
      (V013) — distinto de `credito_debito` (ledger de estoque). Valores por extenso
      (`'CRÉDITO'`/`'DÉBITO'`/`'NEUTRO'`, ajustado logo em seguida no mesmo dia — ver item 18),
      não os códigos de uma letra do legado.
    - `fornecedor.id_plano_contas` entra como FK **composta** desde já (não simples e depois
      corrigida) — já nasce seguindo a convenção do item 16. Sem linha padrão pré-cadastrada
      em `cfg_plano_contas`, então todo fornecedor novo precisa de um plano de contas já
      criado.
    - Constraint/índice nomeados no padrão do projeto (`cfg_plano_contas_pk`,
      `..._id_tenant_ix`, `..._descricao_ix`), não no estilo Firebird (`PK_...`, `IX_...`) do
      DDL original; PK definida inline no `CREATE TABLE`, não via `ALTER TABLE` depois.
    - `cfg_plano_contas` entrou no array de RLS do V024.
18. **Ajuste do ENUM `tipo_movimento_conta` + mais 3 arquivos legados removidos.**
    `tipo_movimento_conta` (item 17) trocou de códigos de uma letra
    (`'C'`/`'D'`/`'N'`) para valores por extenso: `CREATE TYPE tipo_movimento_conta AS ENUM
    ('CRÉDITO', 'DÉBITO', 'NEUTRO')` (V013). Nada dependia do valor anterior (tabela sem
    seed, sem código em `api/`), troca segura. Removidos também `db/005_CFG_PLANO_CONTAS.txt`,
    `db/101_PROCEDURES.txt` e `db/102_TRIGGERS.txt` — dumps do legado Firebird (procedures/
    triggers, incluindo a antiga `SP_ATUALIZA_QUANTIDADE_ESTOQUE`) que não fazem mais falta
    como referência: a lógica de estoque já está na trigger real (`V019`, item 15) e o plano
    de contas já foi migrado (item 17).
19. **`venda`/`venda_devolucao` (V018) perdem campos.** `venda` sem `valor_total`,
    `observacao` e `criado_em`; `venda_devolucao` sem `criado_em`. `venda`/`venda_devolucao`
    passam a ser as **únicas** tabelas do domínio sem `criado_em` (exceção deliberada à
    convenção de auditoria — registrado em `db/migration/README.md`). Motivo do
    `criado_em`, confirmado pelo dono do produto: `data_venda`/`data_devolucao` já cumprem
    esse papel — não há fluxo de "criar rascunho hoje, confirmar depois" nessas duas
    tabelas, então `criado_em` seria sempre igual e redundante. O total da venda passa a
    ser sempre derivado somando `produto_movimento_detalhe` (tipo `VENDA`) — não fica mais
    armazenado/duplicado em `venda.valor_total`.
20. **Mais 2 arquivos legados removidos.** `db/040_VENDAS.txt` e
    `db/041_VENDAS_DEVOLUCAO.txt` (dumps Firebird de `venda`/`venda_devolucao`) — schema já
    migrado em V018 (item 19), sem falta de referência.

### 2026-07-11 — Home institucional (concorrente do Bling) + trial 60 dias (ponta a ponta)

Reforço do topo do funil (§Fase 0, ADR-011) e revisão de D2.

1. **Trial 14 → 60 dias (D2 revisto).** Decisão do dono do produto para dar mais tempo de
   ativação/aha. Aplicado **ponta a ponta**: `application.yml` (`niner.trial.dias: 60`, prod e
   teste), comentário em `OnboardingController`, e todos os textos da spec (R12, §3.3.2, tabela de
   rotas, D2), do `PLANO-DE-NEGOCIO` (§6, intro, D2) e do site. `SignupService` já parametrizava
   via `NinerProperties.Trial.dias` — nenhuma lógica mudou; os 11 testes seguem válidos (nenhum
   depende do valor de dias).
2. **Home reconstruída (`site/index.astro`).** De landing "seca" para página longa e animada,
   posicionando o Nainer como ERP multicanal moderno (sem citar o Bling nominalmente): hero com
   painel decorativo + **demo de sincronização estoque→canais**, faixa de prova com **contadores
   animados**, blocos problema→solução, "como funciona" em 3 passos com ilustrações SVG,
   6 recursos, pílulas de canais, planos (mantido o enhancement de preços da API), FAQ em
   `<details>` e CTA final. Toda CTA leva a `/assinar` ("60 dias grátis, sem cartão").
3. **Sistema visual + movimento.** Novo `site/src/styles/site.css` (portado do golden
   `docs/padroes/nainer_institucional`, rebrandeado Nainer) e `Base.astro` com navbar sticky,
   toggle de tema persistido, menu mobile e um script de **scroll-reveal** (IntersectionObserver)
   + contadores, tudo desligado sob `prefers-reduced-motion`. Sem novas dependências (P6/ADR-011).
   `/assinar` adotou `form-card`/`field` e ganhou uma coluna de reforços; `/bem-vindo` alinhado.
4. **Verificação:** `npm run build` (SSG, 3 páginas) ok; Playwright confirmou hero animado,
   reveal disparando ao rolar (opacity 0→1) e contadores, em tema claro e escuro; caminho
   reduced-motion mostra tudo estático.

### 2026-07-10 — Esqueleto da API no ar (Fase 0): 3 superfícies + contexto de tenant

Scaffolding do backend (spec §Roadmap Fase 0), **independente** das decisões
bloqueantes Q2/Q5/Q7 — entrega um esqueleto que compila, sobe e prova a arquitetura,
sem as migrations de domínio (V013+).

1. **`api/` criado** via Spring Initializr — **Spring Boot 4.0.7 / Java 25**, Maven,
   deps: webmvc, security, oauth2-resource-server, validation, actuator, postgresql,
   flyway, **data-jdbc**, testcontainers. `groupId com.vetor.niner`.

2. **Decisão de persistência: Spring Data JDBC** (não JPA/Hibernate). Mais explícito e
   previsível para o padrão RLS + `SET LOCAL` por transação. A spec foi atualizada
   (§3.1.1, §3.2, §3.3.1): removidas as menções a Hibernate `@Filter` e JPA auditing;
   timestamps via `DEFAULT now()` + preenchimento no serviço de domínio.

3. **3 superfícies (ADR-007)** — `SegurancaConfig` com `SecurityFilterChain` separados
   por `securityMatcher`: `/api/publico/**` (+ actuator, permitAll), `/api/v1/**`
   (tenant), `/api/admin/**` (staff) e um chain default que **nega** o resto. JWT ainda
   não exigido (sem emissor nesta fase) — marcado `TODO(jwt)`; validação de `aud` entra
   na fase de auth.

4. **Infra de contexto de tenant (P8, §3.1.1)** — `TenantContext` com **`ScopedValue`**
   (Java 25); `TenantFilter` lê o claim `tid` do JWT e liga o contexto na cadeia de
   `/api/v1`; `TenantAwareTransactionManager` roda
   `select set_config('app.id_tenant', :tid, true)` no início da transação, casando com
   as políticas RLS (`plataforma.tenant_atual()`, V002). A app conecta como **`niner_app`**
   (sem BYPASSRLS); **Flyway roda separado** como `niner_owner` (serviço do compose) —
   `spring.flyway.enabled=false` na app.

5. **Pacotes-módulo do monólito** criados com `package-info.java`: `plataforma`,
   `identidade`, `catalogo`, `estoque`, `pedidos`, `precos`, `canais`, `integracao`,
   `comum/{config,tenant,web}`. Domínio entra com as migrations V013+.

6. **Serviço `api` no `docker-compose.yml`** + `Dockerfile` multi-stage (Maven+JDK 25 →
   JRE 25, usuário não-root). Ordem documentada: `db` → `flyway` → `api`.

7. **Verificação de ponta a ponta (tudo verde):**
   - `./mvnw test` → **7 testes** (context loads + 3 superfícies + propagação de tenant
     `tid=42 → id_tenant=42` + rota fora → 403), com **Testcontainers Postgres 18.4** e
     **Flyway aplicando V001–V012** (bootstrap de roles via `bootstrap-test.sql`).
   - App rodando ao vivo contra o `db` do compose (como `niner_app`):
     `/actuator/health` UP; `/api/publico|v1|admin/ping` → 200; rota fora → 403.
   - ⚠️ **Colima + Testcontainers:** exige `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
     (senão o Ryuk falha ao montar o docker.sock). Anotado no README da `api/`.

8. **Gate P8 (parcial neste momento):** fecha com as tabelas de domínio + RLS — feito no
   mesmo dia (ver entrada seguinte).

### 2026-07-10 — App `web/` (ERP do lojista): login + handoff + painel

1. **`web/` criado** (React 19 + Vite + TS; React Router 7 + TanStack Query 5 — §3.2).
2. **Autenticação:** login (`slug + email + senha` → `POST /api/publico/login`) e
   **handoff SSO** do site — o botão "Ir para o sistema" do `/bem-vindo` leva o token via
   `#token=...`; o app consome, guarda e limpa a URL. Guarda de rota + logout em 401.
3. **Shell do ERP:** cabeçalho (marca + Sair) + navegação lateral (Painel, Produtos,
   Estoque, Pedidos, Canais). **Painel** real via `GET /api/v1/eu` (loja, assinatura TRIAL,
   usuário/papel + próximos passos). Áreas de domínio como placeholders "em construção".
4. **Design system §3.7** (tokens CSS, tema claro/escuro) — mesma paleta do site.
5. **Backend:** CORS já cobre a origem do web (`niner.cors.origins`).
6. **Verificação (e2e, Playwright):** login → painel "Olá, Ana / Loja Web Teste / TRIAL";
   navegação para "Produtos"; **handoff** `#token=` → entra logado e limpa o hash. **Build ok.**
   *(Durante a verificação, uma instância antiga da API presa na 8080 foi derrubada e a API
   subiu do jar atual — sem mudança de código.)*
7. Serviço `web` adicionado ao `docker-compose.yml` (perfil `fronts`).

> O loop de aquisição está fechado: **site → assinar → tenant + trial → "Ir para o sistema" → web logado**.
> Falta: `admin/` (backoffice), endpoints de domínio reais em `/api/v1`, e R22 (ajuda) nos fronts.

### 2026-07-10 — Site público (Astro/SSG) + planos + CORS: aquisição self-service no ar

1. **ADR-011 decidido: Astro (SSG)** para o `site/` (SEO/Core Web Vitals). `web`/`admin`
   seguem React+Vite.
2. **`site/` criado** (Astro): landing SEO-forte (`<title>`/description/OG/canonical, h1,
   planos renderizados no HTML estático), `/assinar` (formulário → `POST /api/publico/assinar`
   → guarda token e vai para `/bem-vindo`), `/bem-vindo` (primeiro uso: `GET /api/v1/eu`).
   Design tokens §3.7 (tema claro/escuro); base-URL da API lida em runtime (`public/config.js`).
   **Build SSG ok** (3 páginas estáticas).
3. **Backend de apoio:** `GET /api/publico/planos` (R11, catálogo público) e **CORS**
   (`niner.cors.origins`) para os fronts chamarem a API. Preflight e planos validados com
   `Origin` do site (headers `Access-Control-Allow-*` corretos). **11 testes seguem verdes.**
4. Serviço `site` adicionado ao `docker-compose.yml` (perfil `fronts`).

> Pendente no site: **R22** (ajuda de tela + vídeo), páginas de conteúdo/sitemap, e o botão
> "Ir para o sistema" ligado ao app `web/` (a criar).

### 2026-07-10 — Motor do trial self-service (R12): signup → tenant + JWT + primeiro uso

Implementado o fluxo de **assinatura-teste** (14 dias, sem cartão — D2) na superfície pública,
com autenticação JWT real protegendo o ERP.

1. **Auth JWT (HS256)** — `JwtConfig` (encoder/decoder com segredo simétrico; decoder valida
   assinatura, expiração e **`aud=tenant`**), `TokenService` (emite token com `sub`/`tid`/`aud`/`roles`),
   `BCryptPasswordEncoder`. Config em `niner.jwt.*`. `/api/v1/**` agora **exige** JWT (era permitAll);
   o `TenantFilter` passa a ler o `tid` de um token real.

2. **`POST /api/publico/assinar`** (`SignupService`, atômico) — numa única transação: cria
   `plataforma.tenant` (TRIAL), estabelece `app.id_tenant` (para o RLS deixar inserir o domínio),
   cria `assinatura` TRIAL (plano-base **Profissional**, `trial_expira_em = now()+14d`), `uso_tenant`,
   `empresa`, `cfg_geral` (configurações padrão) e o primeiro **usuário ADMIN** (senha em BCrypt).
   Devolve o **token de primeiro acesso (auto-login)** + slug + validade do trial.

3. **`POST /api/publico/login`** (slug da loja + email + senha) e **`GET /api/v1/eu`** (primeiro uso:
   com o token, o cliente já enxerga a própria conta/assinatura via RLS).

4. **Verificação (tudo verde):**
   - **11 testes** (`OnboardingTrialTest`: signup cria tenant e libera 1º uso; login; 401 sem token).
   - **Ao vivo como `niner_app`** (RLS ativo): signup de "Loja do Ze" criou `empresa=1`, `cfg_geral=1`,
     `usuario_admin=1`, `assinatura=TRIAL`, `uso.qtd_usuarios=1`; `/api/v1/eu` com o token → conta TRIAL;
     sem token → 401; login → 200.

### 2026-07-10 — Decisões Q2/Q7/Q5 fechadas + domínio do lojista (V013–V024) + gate P8 verde

1. **Decisões bloqueantes de arquitetura fechadas** (todas):
   - **Q2 (ADR-004)** — reserva no **`recebido`** + expiração por canal (§3.3.5).
   - **Q7** — **separar** `sku` interno (obrigatório/único) de `ean` (GTIN, nullable/único), EAN
     exigido só na publicação (§3.3.3).
   - **Q5 (ADR-010)** — `financeiro` do lojista **fora do v1** (Fase 2, crediário priorizado; §3.3.7).
   - ADRs renumerados: **ADR-010** = financeiro fora do v1; **ADR-011** = framework do site (SEO, em aberto).

2. **Migrations de domínio V013–V024 criadas e validadas em banco real** (Postgres 18.4),
   convertendo o legado Firebird conforme §3.3.1 (minúsculo `snake_case`, `NUMERIC` para
   dinheiro, `BOOLEAN`, `TIMESTAMPTZ`, `BIGINT IDENTITY`) e com **`id_tenant` em toda tabela**:
   - identidade (`empresa`, `usuario`, `usuario_rotina`), cadastros (`cliente`/`fornecedor`/`funcionario`),
     catálogo (`produto`, `produto_barra` com `sku`+`ean`), estoque (`produto_estoque` com
     `reservado`/`disponivel` gerado, ledger imutável, balanço), vendas, canais (`canal`/`anuncio`),
     pedidos de canal (idempotência `(canal,id_externo)`), integração (`outbox_evento`/`webhook_recebido`),
     `cfg_geral`.
   - Convenção de domínio: surrogate `id_<x>` PK + `id_tenant` FK + chaves naturais únicas **por tenant**.

3. **RLS de domínio (V024)** — arquivo único e final: `ENABLE`+`FORCE ROW LEVEL SECURITY` +
   política `USING/WITH CHECK (id_tenant = plataforma.tenant_atual())` + grants de `niner_app` em
   **todas** as tabelas de tenant, mais um **guarda-corpo** que faz a migration falhar se alguma
   tabela com `id_tenant` ficar sem RLS (P8 auto-verificável).

4. **✅ Gate P8 verde:** teste automatizado (`RlsIsolamentoTest`, Testcontainers) conectando como
   **`niner_app`** (sem BYPASSRLS) prova: T1 não lê produto de T2; `WITH CHECK` bloqueia gravar para
   outro tenant; sem contexto não vê nada. **Suíte: 8 testes verdes.**

### 2026-07-09 — Infra local no ar: Postgres 18 + migrations validadas

1. **`docker-compose.yml` criado** (raiz, spec §3.5) com dois serviços por ora:
   - `db` — `postgres:18` (banco **`niner_db`**), volume `pgdata`, healthcheck, e `db/bootstrap/` montado em `/docker-entrypoint-initdb.d` (roles criadas no primeiro init).
   - `flyway` — `flyway/flyway:11` sob profile `migrate`; roda como **`niner_owner`** e aplica `db/migration/`. Uso: `docker compose up -d db` e `docker compose run --rm flyway`.
   - Nota técnica: a imagem `postgres:18` mudou o volume de dados para `/var/lib/postgresql` (o yaml de exemplo da spec §3.5 ainda mostra o caminho antigo `/var/lib/postgresql/data`).
   - `api`/`web`/`admin`/`site` entram no compose quando forem scaffolded.

2. **Migrations V001–V012 aplicadas com sucesso em banco real** (PostgreSQL 18.4) — antes só havia validação estática. Verificado:
   - Roles `niner_owner`/`niner_app` criadas, ambas **sem `BYPASSRLS`** (P8).
   - 9 tabelas no schema `plataforma` (dono `niner_owner`); seed dos 3 planos (Essencial R$ 99 / Profissional R$ 249 / Escala R$ 599 — 🔴 provisórios, D1).
   - `plataforma.tenant_atual()` lê `app.id_tenant` corretamente.
   - `niner_app` lê `plataforma.plano` (grant V011) e **não consegue** criar objetos no schema (`permission denied`) — como esperado.

### 2026-07-08 — Pivô para SaaS multi-tenant (spec v2.0)

1. **Padrão de telas incorporado** — `docs/padroes/TELA.rar` descompactado; mockup `cadastro_fornecedor_campos_cinza.html` mantido como *golden file* de UI; RAR e pasta avulsa removidos. Design system documentado na nova **§3.7** da spec (tokens de cor, tema claro/escuro, grid de 12 colunas, componentes de campo/botão, acessibilidade).

2. **Decisão de produto: virar SaaS multi-tenant.** A spec passou a **v2.0**. O que mudou:
   - **Constituição:** novos **P8** (isolamento de tenant inviolável via Postgres RLS) e **P9** (separação control-plane × tenant).
   - **PRD:** multi-tenant/site público/trial/cobrança deixaram de ser non-goal e viraram **CORE**; novas personas C–F; requisitos **R10–R21**; métricas de funil SaaS.
   - **Plano técnico:** topologia de **uma API com 3 superfícies** (`/api/publico`, `/api/v1`, `/api/admin`) + 3 apps React (`web`/`admin`/`site`); **§3.1.1** isolamento de tenant; **§3.3.11** módulo `plataforma` (control-plane); **§3.5.1** sequência de migrations V001–V091 para `niner_db`; ADR-006 a 009.
   - **Novo `docs/PLANO-DE-NEGOCIO.md`** com o plano comercial.

3. **Decisões tomadas nesta sessão:**
   - **Topologia:** Opção A (uma API, monólito modular) — mas **API stateless** e **base-URL configurável em runtime** nos fronts, para rodar 2 servidores/2 APIs e trocar o endereço em manutenção/failover.
   - **Isolamento:** banco único + `id_tenant` + **Postgres RLS** (`FORCE`).
   - **Gateway de cobrança:** **adiado** — adapter abstrato, cobrança manual no início (D3).
   - **Trial:** **14 dias, sem cartão** (D2).
   - **Q6 fechada:** manter `id_empresa` **e** adicionar `id_tenant` (`tenant 1:N empresa`, 1:1 no v1).

4. **Migrations do control-plane criadas** (`db/migration/V001–V012` + `db/bootstrap/00_roles.sql`) — o schema `plataforma` que controla o tenant: roles `niner_owner`/`niner_app` (esta **sem BYPASSRLS**), função de contexto `plataforma.tenant_atual()`, tipos ENUM, e tabelas `tenant`, `plano`, `assinatura`, `fatura`, `pagamento`, `webhook_gateway`, `uso_tenant`, `staff`, `impersonacao_log`, grants e seed de planos (🔴 preços provisórios). Numeração **renumerada em ordem contígua**; domínio do lojista + políticas RLS ficam em V013+ (§3.5.1). Validação apenas estática (Docker daemon desligado no momento).

5. **Novo requisito R22 (ajuda por tela):** toda tela (ERP/backoffice/site) deve ter **ajuda/manual de operação contextual + acesso a vídeo explicativo** (§3.7.1: componente `AjudaDaTela`, catálogo `ajuda_tela` servido pela API, `url_video` NULL ⇒ "em breve"). Adicionado ao template de Spec de Feature (§5) e ao gate de aprovação.

### 2026-07-07 — Fundação da documentação

1. **`CLAUDE.md` criado** — documenta que o repo é spec-driven, a Constituição (P1–P7), a arquitetura pretendida (monolito modular, adapters de canal, outbox no Postgres) e o alerta de que o `db/` é legado Firebird (não carregar padrões como `FLOAT` para dinheiro).

2. **Spec atualizado para v1.1** — modelo de dados (§3.3) reescrito a partir das tabelas reais do `db/`, adaptado de Firebird para PostgreSQL:
   - Nova **§3.3.1** — regras de conversão Firebird → PostgreSQL (`FLOAT`→`NUMERIC`, `S/N`→`BOOLEAN`, `GENERATOR`→`IDENTITY`, procedures/triggers→domínio Java, `TIMESTAMP`→`TIMESTAMPTZ`, etc.).
   - Modelo reorganizado por módulo (§3.3.2–§3.3.9): `identidade`, `catalogo`, `estoque`, `pedidos`, `integracao`, `financeiro`, config e cadastros.
   - Domínio de **marketplaces** (ausente no legado) integrado: `canal`, `anuncio`, `pedido`, `pedido_item`, `outbox_evento`, `webhook_recebido`.
   - Pendências marcadas em **vermelho** (🔴) — convenção definida no topo do documento.

3. **Commit `6c65765`** — CLAUDE.md + `db/` + spec v1.1 enviados para `origin/main`.

4. **GitHub CLI instalado e autenticado** — `gh` 2.96.0 em `C:\Program Files\GitHub CLI\gh.exe` (via winget), autenticado como `evirson` (device flow; escopos `gist`, `read:org`, `repo`).

5. **Coautor convidado** — `claudiocalixto` (`claudio@vetorsistemas.com.br`) convidado com acesso **Write** via `gh api PUT .../collaborators/claudiocalixto -f permission=push`. Convite **aguardando aceite**.

6. **Commit `76922ac`** — `docs/PROGRESSO.md` enviado para `origin/main` (após rebase sobre o commit remoto `9e4fa65` "inclusao de base").

---

## Decisões bloqueantes em aberto (ver §2.7 e §3.3 da spec)

**Todas as bloqueantes de arquitetura (Q2/Q5/Q6/Q7) estão fechadas** — o domínio (V013+) está destravado. Restam só decisões de **negócio**, que não travam o schema central:

- **Decisões de negócio do SaaS (D1–D10)** — ver `docs/PLANO-DE-NEGOCIO.md`. Abertas: D1 preços, D3 gateway (adiado), D5 nome "Nainer", D6 NFS-e da assinatura, D8 dunning, D9 metas, D10 comportamento do estado `INADIMPLENTE`.
- **ADR-011 — framework do site público (SEO):** Astro × Next, "decidir depois" (não bloqueia o backend).

> ✅ **Q5 — módulo `financeiro` do lojista:** fechada em 2026-07-10 — **fora do v1**; **revisada em 2026-07-16, em duas rodadas (ADR-012)** — crediário (`tipo_carteira`/`moeda`/`contas_receber`) e caixa (`caixa_mestre`/`caixa_detalhe`) via **V025**, depois `contas_pagar` via **V026**, e **`conta_corrente`/`conta_corrente_movimento` via V028 (2026-07-30)** — módulo `financeiro` do legado **completo** no v1, nenhuma tabela fora. R9 (venda manual) segue sem ligação automática ao financeiro (schema existe, domínio Java ainda não liga venda→recebível). Ver §3.3.7.
> ✅ **Q7 — SKU vs EAN:** fechada em 2026-07-10 — **separar** `sku` interno (obrigatório, único, chave do domínio; ex-`codigo_barra`) de `ean` (GTIN real, nullable, único quando preenchido). EAN exigido só na **publicação** em canal, não no cadastro. Nas migrations V013+, as FKs que apontavam para `codigo_barra` passam a referenciar `sku`. Ver §3.3.3.
> ✅ **Q2 — estratégia de reserva:** fechada em 2026-07-10 — reservar no **`recebido`** (pedido importado já incrementa `reservado`), com expiração configurável por canal que devolve reservas não pagas. Alinha R5 + P1. Vira **ADR-004**; adicionar colunas `reservado`/`minimo` a `produto_estoque` nas migrations de domínio. Ver §3.3.5.
> ✅ **Q6 — multi-empresa/tenant:** fechada em 2026-07-08 — manter `id_empresa` **e** adicionar `id_tenant` (banco único + RLS; `tenant 1:N empresa`, 1:1 no v1).

---

## Colaboração / acesso ao repositório

- **Claudio** — username GitHub **`claudiocalixto`** (`claudio@vetorsistemas.com.br`) — é **coautor** com acesso **Write** ao repo `evirson/niner-v2`.
- **Status:** ✅ **convite enviado (2026-07-07), aguardando aceite.** Ele aceita pelo e-mail do GitHub ou em https://github.com/evirson/niner-v2/invitations.
- Após aceitar, comita com a própria identidade Git (`user.name`/`user.email`) e aparece como autor nos commits.

---

## Próximos passos sugeridos

**Feito até 2026-07-10:** ✅ decisões de arquitetura (Q2/Q5/Q6/Q7 + ADRs) · ✅ esqueleto da API + 3 superfícies + contexto de tenant · ✅ domínio V013–V024 + RLS + **gate P8** · ✅ **trial self-service** (signup atômico + JWT + login + `/eu`) · ✅ **site** (Astro/SSG) · ✅ **web** (ERP: login + handoff SSO + painel). **Loop de aquisição fechado** (site → trial → web logado).

**Feito em 2026-07-20:** ✅ **primeira tela de domínio completa** — Clientes (`cadastros.cliente`): CRUD backend + frontend, categoria embutida, `AjudaDaTela` (R22), grid de 12 colunas (§3.7) finalmente em uso, convenções novas de projeto (maiúsculas sempre, foco automático no 1º campo).

**Feito em 2026-07-21:** ✅ **padrão de tela de cadastro consolidado** (paginação por página + ordenação por coluna + ícones de ação + modo somente-leitura + configuração de campos por tenant + shell de altura travada + campos informativos de auditoria) e ✅ **segunda, terceira e quarta telas de domínio** — Funcionários (`cadastros.funcionario`), Plano de Contas (`cadastros.planocontas`, com `criado_em`/`atualizado_em` adicionados à V016) e Fornecedores (`cadastros.fornecedor`, com criação rápida de plano de contas embutida), todas construídas sobre esse padrão; ✅ **Parâmetros do Sistema** (`configuracao.geral`), primeira tela deliberadamente fora do padrão de cadastro (singleton por tenant, ADMIN-only).

**Retomar — ordem sugerida** (revisada em 2026-08-17):

0. **⭐ Módulo fiscal — B0 a B9 fechados no código (2026-08-19).** Emite NFC-e de verdade pela
   SEFAZ-PR, cancela, inutiliza numeração, entra/sai de contingência sozinho, reprocessa documento
   preso, arquiva o XML e **emite a NF-e modelo 55 de devolução** com DANFE em A4. A **DF20** que
   travava o B9 foi decidida com o contador em 2026-08-19 (devolução sem consumidor identificado
   sai contra o CNPJ da própria loja).
   **A única pendência do módulo não é código:** a **MITRYUSCASH precisa se cadastrar como responsável
   técnico no portal da SEFAZ de cada UF e obter o CSRT** — sem ele a NF-e 55 é recusada
   (hoje responde `cStat 974`, "CNPJ do responsavel tecnico diverge do cadastrado"). A NFC-e, que
   é a operação do dia a dia da loja, **não** depende disso e segue autorizando normalmente.
1. **⭐ Integração com marketplaces** — é o **coração da visão original** (P1/P2, R3–R7) e a
   lacuna central do produto hoje: `canais/`, `pedidos/`, `precos/` e `integracao/` seguem só com
   `package-info.java`, sem nenhuma implementação de domínio. O schema está pronto desde
   V020–V022 (`canal`, `anuncio`, `pedido`, `pedido_item`, `outbox_evento`, `webhook_recebido`).
   Falta o adapter `CanalDeVenda` (anti-corruption layer por marketplace), o worker do outbox
   (`@Scheduled` + `FOR UPDATE SKIP LOCKED`), a importação de pedido idempotente e o sync de
   estoque/preço.
2. **`admin/`** — backoffice da plataforma (lista/ficha de tenants R17, suspender/impersonar
   R18/R21). O app React ainda não existe.
3. **Tela de variação/SKU (`produto_barra`).** O **domínio existe** desde 2026-08-05 —
   `ProdutoBarraService.obterOuCriar`, consumido por Emissão de Etiqueta, Entrada de Produtos e
   Importação de Dados, com o `sku` sempre saindo de `gerar_ean13_interno()`
   (`ProdutoBarraService.java:239,261`). O que falta é só a **tela** de listar/editar/excluir
   variação — nenhuma existe hoje.
4. `uso_tenant.qtd_produtos` (enforcement R19).
5. **Catálogo `ajuda_tela` na API** (R22) — hoje `AjudaDaTela` (`web/`) embute o conteúdo como
   fallback estático; falta o endpoint/tabela real (§3.7.1 da spec).
6. **Credenciais do datasource nos testes** — `TestcontainersConfiguration` conecta a app como
   superusuário do container, então `GRANT`/`REVOKE` e RLS ficam invisíveis para a suíte
   (`PrivilegiosNinerAppTest` cobre os invariantes, mas não substitui a correção de fundo).
7. Decisões de negócio em aberto: D1 (preços), D3 (gateway), D5/D6/D8/D9/D10.

**Como subir o ambiente:** ver **`CLAUDE.md`, seção "Build / run"** — fonte única. Esta seção
mantinha uma cópia divergente e de outro sistema operacional (`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`
+ Colima, `lsof -ti tcp:8080`), removida em 2026-08-15.
