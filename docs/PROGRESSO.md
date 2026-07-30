# Progresso do Projeto — niner-v2

Registro cronológico das decisões e entregas. Atualizar a cada marco relevante.
**Última atualização:** 2026-07-30

---

## Estado atual

Projeto **spec-driven** em fase de fundação, com **seis telas de cadastro completas e
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
visualizar/editar um registro e voltar, em vez de resetar.

| Artefato | Situação |
|---|---|
| `spec-driven-erp-varejo.md` | **v2.0 — pivô SaaS multi-tenant** (Constituição P1–P9 + PRD R1–R21 + plano técnico + control-plane + migrations) |
| `docs/PLANO-DE-NEGOCIO.md` | **Novo** — plano de negócio (planos/preços, trial, funil, métricas SaaS, roadmap, decisões D1–D10) |
| `docs/padroes/` | Mockup de referência de UI (golden file, §3.7) — `TELA.rar` descompactado e removido |
| `docs/infra/armazenamento-imagens.md` | Object storage das fotos de produto (ADR-013). Infra no GCP **provisionada e testada**; **código Java implementado em 2026-07-23** (`comum.armazenamento` + `catalogo.ProdutoImagemService`, ver linha do tempo) — só falta credencial real (Opção A, ADC pessoal do Claudio) pra upload funcionar de ponta a ponta fora dos testes |
| `db/*.txt` | Schema **legado (Firebird)** versionado como referência (31 tabelas + generators, procedures, triggers) |
| `CLAUDE.md` | Guia do repositório — atualizado para o SaaS multi-tenant (P8/P9, plataforma, `id_tenant`+RLS) |
| `docker-compose.yml` | Infra local de dev: `db` (postgres:18, `niner_db`) + `flyway` (profile `migrate`) + **`api`** (Spring Boot, porta 8080, conecta como `niner_app`); **V001–V026 aplicadas e validadas em banco real** (control-plane + domínio do lojista + financeiro parcial + RLS) — banco **recriado do zero em 2026-07-16** (volume `niner_pgdata` apagado e refeito) |
| `db/migration/V013–V024` | Domínio do lojista (identidade, cadastros, catálogo com `sku`+`ean`, estoque com `reservado`/`disponivel`, vendas, canais, pedidos, integração/outbox, cfg_geral) + **RLS de domínio** (`FORCE` + política por `id_tenant`). **Gate P8 verde** (teste de isolamento cross-tenant automatizado). **Revisado em 2026-07-16** (ver linha do tempo): tipos padronizados (`id_tenant SMALLINT`, demais PKs `INTEGER`), sem `ON DELETE CASCADE`, ledger de estoque imutável via `REVOKE`, e-mail case-insensitive, fix de bootstrap (`GRANT CREATE ON SCHEMA public`) |
| `db/migration/V025` | **`financeiro` parcial (revisão de Q5/ADR-010, ADR-012):** crediário (`tipo_carteira`, `contas_receber`/`contas_receber_detalhe`) + caixa (`caixa_mestre`/`caixa_detalhe`). RLS próprio no arquivo (V024 já tinha rodado). **Editada em 2026-07-23** (histórico do cliente): `categoria_carteira` (ENUM + coluna obrigatória em `tipo_carteira`) e `id_empresa_pagamento` (coluna nullable + FK em `contas_receber`) — aplicadas no banco de dev já rodando via `ALTER` manual + `flyway repair` (sem recriar o banco). **Editada de novo em 2026-07-28 (fusão `tipo_carteira`+`moeda`):** `moeda`/`moeda_detalhe` **removidas** do arquivo; `tipo_carteira` ganhou `perc_desconto`/`perc_acrescimo` (vieram de `moeda`) e a chave única virou `(id_tenant, nome_carteira, categoria_carteira)` — a mesma bandeira pode existir uma vez por categoria (ex.: "HIPER" em débito e crédito, prazo/taxa próprios de cada um); `caixa_detalhe.id_moeda` renomeado pra `id_carteira`, FK retargetada pra `tipo_carteira`. Aplicada no banco de dev via `ALTER`/`DROP TABLE` manual + `flyway repair` (sem recriar o banco, mesma convenção de sempre "banco ainda em construção"). **Editada de novo em 2026-07-29 (Recebimento de Crediário):** `tipo_carteira` ganhou `permite_receber_crediario` (boolean, RN007) e nasceu a tabela `contas_receber_lote` (cabeçalho/gerador real de `id_lote_recebimento`, mesmo padrão de `produto_transferencia`/`id_transferencia`, sem FK de `contas_receber`/`caixa_detalhe` pra ela, proposital) — RLS próprio no mesmo arquivo. Aplicada no banco de dev via `ALTER`/`CREATE TABLE` manual + `flyway repair` (mesma convenção). |
| `db/migration/V026` | **`contas_pagar`** (mais uma revisão de Q5/ADR-010/ADR-012): PK `id_conta_pagar` (renomeada de `localizador`), `nota_fiscal integer` nullable sem `DEFAULT 0` (padronização que também corrigiu `produto_movimento_mestre.nota_fiscal`, V019, de `text` para `integer`). RLS próprio no arquivo. Só `conta_corrente*` segue fora do v1. **Aplicada e validada em banco real em 2026-07-16** (schema/FKs/RLS conferidos via `psql`) |
| `db/migration/V027` | **`cfg_tela_campo`** (novo, 2026-07-21) — configuração por tenant de campos visíveis/obrigatórios por tela (`chave_tela`), reutilizável para qualquer tela futura. RLS próprio no arquivo. **Migration aditiva** — aplicada sem recriar o banco (`docker compose run --rm flyway`, só essa migration rodou). |
| `api/` | Spring Boot 4.0.7 / Java 25 (Maven). 3 superfícies com `SecurityFilterChain` separados; `TenantContext` (`ScopedValue`) + `TenantAwareTransactionManager`; **auth JWT HS256** (login/signup emitem, `/api/v1` valida `aud=tenant`); **trial self-service** (`POST /api/publico/assinar` → tenant+configs+moedas+ADMIN+assinatura TRIAL + token), `POST /api/publico/login`, `GET /api/v1/eu`. **Módulo `cadastros.cliente` (2026-07-20/21):** CRUD completo de `GET/POST/PUT/DELETE /api/v1/clientes` + `GET/POST/PUT /api/v1/categorias-cliente`, validação de CPF/CNPJ (dígito verificador + duplicidade — **CNPJ alfanumérico desde 2026-07-21**, ver linha do tempo), normalização de texto para maiúsculas, celular/WhatsApp (11 dígitos + 3º=9), nascimento opcional (só não pode ser futuro), exclusão com fallback para inativar quando há venda associada. **Listagem ordenada por `nome` (ou pela coluna pedida), paginação por número de página** (2026-07-21, era por `id_cliente`/cursor) — `GET /api/v1/clientes?pagina=&limite=&ordenarPor=&direcao=` com `LIMIT/OFFSET` + contagem total + `ORDER BY` dinâmico (allowlist de colunas). **Validação de servidor reforçada (2026-07-21):** além do dígito verificador de CPF/CNPJ, agora também formato de e-mail/celular/WhatsApp/CEP e a obrigatoriedade configurável por tenant (`cfg_tela_campo`) — antes só o frontend checava isso. **Módulo `cadastros.funcionario` (novo, 2026-07-21):** CRUD completo de `GET/POST/PUT/DELETE /api/v1/funcionarios` replicando o padrão do cliente (paginação por página, ordenação com allowlist, validação de CPF/celular/% comissão, obrigatoriedade configurável, exclusão com fallback para inativar quando há movimentação de estoque vinculada). Reaproveita `Documentos` (tornado público) para o dígito verificador do CPF; **CPF não é único** aqui (decisão de V016/§3.3.9, oposto do cliente). **Módulo `cadastros.planocontas` (novo, 2026-07-21):** CRUD de `GET/POST/PUT/DELETE /api/v1/planos-contas` — PK de negócio `text` (código contábil digitado pelo usuário, imutável após criar), exclusão **sem** fallback de inativar (tabela sem `ativo` — 409 com vínculo em fornecedor/contas_pagar), `tipoMovimento` validado contra o ENUM acentuado (CRÉDITO/DÉBITO/NEUTRO), busca única código-ou-descrição; sem registro em `cfg_tela_campo` (todos os campos NOT NULL, nada configurável). **Módulo `cadastros.fornecedor` (novo, 2026-07-21):** CRUD de `GET/POST/PUT/DELETE /api/v1/fornecedores` — mesmo padrão de Cliente/Funcionário (obrigatoriedade configurável, exclusão com fallback para inativar quando há movimentação de estoque ou conta a pagar vinculada); CNPJ sempre 14 caracteres (pessoa jurídica — CPF é rejeitado), telefone aceita fixo ou celular (10–11 dígitos, mais frouxo que a regra do cliente); `idPlanoContas` inexistente vira 400 amigável em vez de 500; listagem com filtro por plano de contas. Sem alteração de schema (tabela completa desde V016/V024). **Módulo `configuracao.geral` (novo, 2026-07-21):** `GET/PUT /api/v1/config-geral` sobre o singleton `cfg_geral` (V023, semeado no signup) — sem POST/DELETE; **somente ADMIN, inclusive na leitura** (diferente de `comum.telaconfig`, onde qualquer papel lê); validação só de faixa (percentuais 0–100, dias ≥ 0), já que a tabela inteira é NOT NULL. **Módulo `comum.telaconfig` (novo, 2026-07-21):** `GET/PUT /api/v1/config-tela/{chaveTela}` — quais campos aparecem/são obrigatórios por tenant, reutilizável entre telas (já com 3 telas registradas: `cadastros.cliente.form`, `cadastros.funcionario.form` e `cadastros.fornecedor.form`); só ADMIN grava (403 para OPERADOR, checado a partir do claim `roles` do JWT); leitura filtrada por `id_tenant` explicitamente (defesa em profundidade, além do RLS). **69 testes verdes** (Testcontainers: `ClienteCrudTest` 17, `FornecedorCrudTest` 12, `FuncionarioCrudTest` 10, `PlanoContasCrudTest` 8, `ConfiguracaoGeralTest` 6, `ConfiguracaoTelaTest` 5, + suíte anterior) + fluxo **verificado ao vivo**. Persistência: **Spring Data JDBC**. **Módulo `catalogo.produto` (novo, 2026-07-22 — docs/telas/produto.md):** CRUD de `GET/POST/PUT/DELETE /api/v1/produtos` — categorias N:N com ordenação (`produto_categoria.indice` derivado da posição da lista recebida, substituição total a cada save), `nomeVarianteLinha`/`nomeVarianteColuna` forçados a `null` quando a flag correspondente de `cfg_geral` está desligada, regra da oferta tudo-ou-nada (início/final/preço, com data no passado/ordem/preço `< venda` validados), peso líquido ≤ peso bruto, exclusão com fallback para inativar quando há variação/imagem vinculada. **Módulo `catalogo` auxiliar:** `GET/POST/PUT /api/v1/categorias-produto` (mesmo padrão de categoria de cliente); `GET /api/v1/ncm/{codigo}` (consulta de `cfg_produto_ncm`, tabela global sem RLS, 404 amigável); `GET /api/v1/config-geral/flags-variante` (aberto a qualquer papel, diferente do resto de `cfg_geral`). **Gerador de EAN-13 interno (novo, 2026-07-22):** função SQL `gerar_ean13_interno()` + tabela de controle `cfg_ean_gerador` (GLOBAL, sem `id_tenant`/RLS — sequencial único compartilhado por todos os tenants desta instância de banco; `id_banco` distingue instâncias diferentes se houver *sharding* no futuro); vai popular `produto_barra.sku` **sempre** (nunca digitado) quando o serviço de variação for construído — hoje só a rotina existe, nada chama automaticamente ainda (decisão de acionamento — Java explícito vs. trigger — adiada a pedido do dono do produto). **91 testes verdes** no total (`ProdutoCrudTest` 20 + `EanGeradorTest` 2, novos). Falta: domínio de variação/SKU (`produto_barra`), imagens (`produto_imagem`) e estoque/pedido. **Módulo `financeiro` (novo, 2026-07-23):** `MoedaController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/moedas`, sem `ativo`, exclusão bloqueia com 409 se vinculada a `moeda_detalhe`/`caixa_detalhe`; `percDesconto`/`percAcrescimo` opcionais — nunca os dois com valor positivo ao mesmo tempo, checagem por valor > 0 não por presença) e `TipoCarteiraController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/tipos-carteira`, gerencia embutido o N:N `moeda_detalhe` sem índice — apaga e reinsere a cada save; `taxaAdministradora` opcional, prazo/taxa aceitam 0). Filtro `id_tenant` explícito em toda consulta dos dois serviços (ambiente de teste conecta como superusuário, RLS não filtra sozinho ali). **113 testes verdes** no total (`MoedaCrudTest` 11 + `TipoCarteiraCrudTest` 11, novos). **Módulo `comum.armazenamento` + galeria de fotos de produto (novo, 2026-07-23 — ADR-013):** `ArmazenamentoDeArquivos` (interface) + `GcsArmazenamento` (adapter GCS, cliente `Storage` `@Lazy` — API sobe sem credencial de GCS configurada); `ProdutoImagemController/Service` (`POST/DELETE/PUT /api/v1/produtos/{id}/imagens...`) valida por **magic bytes** (nunca extensão/Content-Type do cliente), normaliza pra **WebP de verdade** (`org.sejda.imageio:webp-imageio`, redimensiona ≤1600px), aplica o **máximo de 6 fotos por produto** (regra de produto), exclusão renumera índices, reordenação; falha de storage sem credencial vira 503 com mensagem clara. **119 testes verdes** no total (`ProdutoImagemCrudTest` 6, novos, contra `fake-gcs-server` via Testcontainers — nenhum teste toca o bucket real). **Categoria de tipo de carteira + histórico do cliente (novo, 2026-07-23):** `tipo_carteira` ganhou `categoria_carteira` (ENUM `AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`/`CREDIARIO`, obrigatório) e `contas_receber` ganhou `id_empresa_pagamento` (nullable — loja do pagamento, editado em V025); novo `ClienteHistoricoController/Service/Dtos` (`GET /api/v1/clientes/{id}/historico`) devolve compras (valor somado do ledger de estoque), parcelas (com dias de atraso calculado) e resumo de crediário em aberto (vencidas/a vencer/total) — só leitura, sem fluxo de venda/baixa de parcela ainda. **126 testes verdes** no total (`ClienteHistoricoCrudTest` 6, novos). **Rede de segurança contra exclusão que viola FK sem avisar (novo, 2026-07-24):** `GlobalExceptionHandler` ganhou handler pra `DataIntegrityViolationException` → 409 genérico ("Registro em uso por outro cadastro"), cobrindo qualquer FK que uma pré-checagem manual de serviço esqueça de enumerar (achado real: `PlanoContasService.excluir()` checava só `fornecedor`/`contas_pagar`, esquecendo `caixa_detalhe` — o DELETE real caía num 500 genérico sem `detail`, indistinguível de "nada aconteceu" no front); corrigido também o `PlanoContasService` pra checar as 3 FKs. **Histórico do Cliente ganhou `qtdProdutos`** em `VendaHistorico` (soma de `qtd_produto` do ledger, mesma origem do `valor`) **e `numeroParcela`** em `ParcelaHistorico` (já existia no banco, nunca exposto) — pedido pra bater com um modelo de planilha (`HISTORICO_DE_COMPRAS.xlsx`/`HISTORICO_DE_PAGAMENTOS.xlsx`) que tinha essas colunas + linha de total. **128 testes verdes** no total (2 novos: `PlanoContasCrudTest`/`TipoCarteiraCrudTest` ganharam teste de exclusão bloqueada por vínculo que faltava cobertura). **Produtos da Compra no Histórico do Cliente (novo, 2026-07-27, pedido do dono do produto com mockup de layout):** `ClienteHistoricoService.buscarProdutos()` — junta `produto_movimento_detalhe` (débito de venda) → `produto_barra` → `produto` (+ `cfg_variante_linha`/`cfg_variante_coluna` pro nome da variação, quando existir) e calcula o preço de venda líquido por unidade com a fórmula pedida (`((qtd_produto * preco_venda) - valor_desconto + valor_acrescimo) / qtd_produto`, `RoundingMode.HALF_UP`, 2 casas — mesma composição do "valor" da compra de 2026-07-23, só dividida de volta pela quantidade); novo DTO `ItemVendaHistorico` + campo `produtos` em `ClienteHistoricoResponse`. Esclarecido com o dono do produto antes de implementar: a lista "DINHEIRO, PIX, CARTAO DEBITO, CARTAO CREDITO, CREDIARIO" do mockup era só exemplo de nomes de carteira, **não** uma mudança na `categoria_carteira` (que segue `AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`/`CREDIARIO` — nenhuma alteração de schema). **130 testes verdes** no total (2 novos: produto sem variação e com variação linha/coluna). **Módulo novo `vendas` — PDV/Frente de Caixa (2026-07-28, docs/telas/pdv.md, spec escrita antes do código a pedido do dono do produto):** `GET /api/v1/pdv/produtos?busca=` (busca por descrição, cada variação de `produto_barra` uma linha, com estoque por empresa — `CROSS JOIN empresa` + `LEFT JOIN produto_estoque` — e total; filtra e faz `LIMIT 20` **antes** de expandir por empresa via CTE, senão o limite cortaria linhas no meio de uma variação) e `GET /api/v1/pdv/produtos/codigo/{codigo}` (leitura por sku ou ean, 404 amigável) — `PdvProdutoController/Service`. `POST /api/v1/pdv/vendas` (`PdvVendaController/Service`) efetiva a venda numa única transação: resolve a única empresa do tenant automaticamente (`SELECT ... LIMIT 1`, tenant 1:1 empresa hoje), valida estoque disponível de **todos** os itens antes de gravar qualquer coisa (`produto_estoque` não tem CHECK contra saldo negativo — a garantia de nunca vender no negativo é só essa validação de serviço, P1), grava `venda` + `produto_movimento_mestre/detalhe` (débito — a trigger `fn_atualiza_estoque_movimento` já existente baixa o estoque sozinha) e a(s) parcela(s) em `contas_receber` a partir do `tipo_carteira` escolhido: categoria `AVISTA`/`CARTAO_DEBITO` só aceita 1 parcela e já nasce paga (`data_recebimento`/`valor_recebido` preenchidos); `CARTAO_CREDITO`/`CREDIARIO` nascem em aberto, vencimento `data_venda + prazoPagamento×N` dias, valor dividido igualmente com o resto do arredondamento absorvido pela última parcela (soma sempre bate exata). Preço/variação de cada item são sempre resolvidos no servidor a partir do `idVariacao` — a tela nunca envia preço. v1 sem cliente/vendedor vinculado, sem seleção de empresa, sem desconto/oferta (não existe vínculo usuário↔funcionário ainda; ver non-goals na spec). **141 testes verdes** no total (`PdvCrudTest` 11, novos: busca com estoque por empresa, produto inativo não aparece, leitura por sku, venda à vista paga na hora e baixa estoque de verdade, crediário com 3 parcelas de soma exata, estoque insuficiente bloqueia com 409 e não grava nada, validações de parcela/carteira, isolamento entre tenants). **Fusão `tipo_carteira`+`moeda` (2026-07-28):** `MoedaController/Service/Dtos` **removidos** (`rm -f`); `TipoCarteiraDtos` ganhou `percDesconto`/`percAcrescimo`; `TipoCarteiraService.validar()` herdou a checagem de mutualidade do `MoedaService` deletado (por valor > 0, não presença — 0/0 é neutro) e `excluir()` passou a checar vínculo em `contas_receber` **e** `caixa_detalhe`; `SignupService` semeia `tipo_carteira` direto (sem passar por `moeda`). **136 testes verdes** no total (`TipoCarteiraCrudTest` foi de 11 pra 17, `MoedaCrudTest` removido). **PDV — split-tender + desconto promocional (2026-07-28, mesma sessão, pedido literal do dono do produto com a fórmula do resumo/saldo a pagar):** `ConfiguracaoGeralService` ganhou `percentualDescontoVenda()` + endpoint `GET /api/v1/config-geral/desconto-venda` (aberto a qualquer papel, mesmo padrão de `/flags-variante` — o PDV precisa do percentual sem ser ADMIN). `EfetivarVendaRequest` trocou `idCarteira`+`numeroParcelas` únicos por `pagamentos: List<PagamentoRequest>` (um `idCarteira`+`valorPago`+`numeroParcelas` por linha). `PdvVendaService.efetivarVenda()` reescrito: calcula `descontoPromocional` = produtos × `cfg_geral.percentual_desconto_venda`; pra cada linha de pagamento, `cobertura = valorPago + valorPago×percDesconto/100 − valorPago×percAcrescimo/100` (desconto faz a linha cobrir *mais* saldo que o valor tendido — é um bônus por pagar naquela forma; acréscimo cobre *menos* — a diferença é custo da forma de pagamento); a soma das coberturas tem que fechar exatamente o líquido a pagar (tolerância de 1 centavo) ou 400 sem gravar nada. Todo o desconto/acréscimo apurado (promocional + de cada linha) é **rateado entre os itens vendidos** em `produto_movimento_detalhe.valor_desconto`/`valor_acrescimo` (proporcional ao valor de cada item, resto do arredondamento no último — mesmo truque da parcela) — **nunca** em `contas_receber.valor_desconto`, reservado pra um recurso futuro e sem relação (desconto ao cobrar parcela de crediário muito atrasada); `contas_receber` sempre grava o `valorPago` literal de cada linha, e por construção a soma bate exatamente com o valor dos itens já líquido de desconto/acréscimo (verificado por derivação matemática a partir do exemplo numérico dado pelo dono do produto, não só por teste). **149 testes verdes** no total (4 novos em `PdvCrudTest`: desconto promocional rateado no item, sem desconto quando o percentual é zero, split-tender com desconto por forma de pagamento fechando o saldo, pagamentos que não fecham o saldo respondem 400 e não gravam nada). **Desconto da venda vira informado pelo operador + teto de valor pago (2026-07-28, mesmo dia, sessão seguinte):** `cfg_geral.percentual_desconto_venda` deixou de ser aplicado automaticamente — passou a ser só o **máximo** permitido; `EfetivarVendaRequest` ganhou `descontoVenda` (BigDecimal explícito, `@NotNull @DecimalMin("0")`), validado em `PdvVendaService.efetivarVenda()` contra `valorTotalProdutos × percentualMaximoDesconto/100` (400 se passar, tolerância de 1 centavo, antes de qualquer INSERT). `resolverPagamentos()` passou a processar as linhas de pagamento **na ordem do request**, mantendo um `saldoRestante` corrente — quando o `tipo_carteira` da linha tem `percDesconto > 0`, o `valorPago` não pode passar de `saldoRestante × (1 − percDesconto/100)` (400 se passar), pedido com exemplo numérico direto do dono do produto ("saldo de 500, desconto de 10%, só posso pagar no máximo o valor − o % de desconto" = R$450). Campos de request/response renomeados de `descontoPromocional` para `descontoVenda` (nada estava commitado, seguro renomear pra ficar mais preciso). **151 testes verdes** no total (2 novos: desconto acima do máximo rejeitado sem gravar nada, valor pago acima do teto de uma forma de pagamento com desconto rejeitado sem gravar nada). **PDV — F5/F6 renomeados, teto de valor pago generalizado, fórmula de desconto fechada, Cliente/Vendedor obrigatórios (2026-07-28, mesma sessão):** `PdvVendaService.resolverPagamentos()` mudou o teto de valor pago de uma linha com desconto de subtração simples pra `saldoRestante ÷ (1 + percDesconto/100)`, mantendo `descontoLinha = valorPago × percDesconto/100` (a fórmula foi tentada de 3 jeitos diferentes na mesma sessão até fechar nesta — detalhe em `docs/telas/pdv.md`). Novo módulo `PdvClienteController/Service` (`GET /api/v1/pdv/clientes?busca=`, nome/CPF-CNPJ/celular, só ativo). `EfetivarVendaRequest` ganhou `idCliente`/`idFuncionario` (`@NotNull`, validados contra o tenant e `ativo=true`, 400 se inválidos); `efetivarVenda()` passou a gravar `venda.id_cliente` e `produto_movimento_detalhe.id_funcionario` (mesmo vendedor em todas as linhas de item da venda) — colunas que já existiam desde V018/V019, sem migration nova. **156 testes verdes** no total (`PdvCrudTest` foi de 22 pra 27). **Módulo `identidade.usuario` (novo, 2026-07-28 — docs/telas/usuario.md):** CRUD de `GET/POST/PUT/DELETE /api/v1/usuarios`, ADMIN-only; gerencia embutido o N:N `usuario_empresa` (apaga e reinsere a cada save, mínimo uma empresa); exclusão nunca permite a própria conta, cai pra inativar se houver `caixa_mestre` vinculado. **Só um ADMIN por tenant, para sempre** (revisão no mesmo dia): `UsuarioRequest` não tem campo `administrador` — `criar` sempre grava `false`, `atualizar` nunca toca a coluna; garantia dupla com `usuario_um_admin_uk` (índice único parcial `WHERE administrador = true`, V015). Novo `identidade.empresa` (`GET /api/v1/empresas`, leitura simples, qualquer papel). **Login com escolha de empresa (novo, docs/telas/login-empresa.md):** `POST /api/publico/login` em até duas voltas quando o usuário acessa mais de uma empresa (`usuario_empresa`) — sem `idEmpresa` devolve a lista pra escolher, com `idEmpresa` (sempre validado contra a lista de acesso) devolve o token; `TokenService` ganhou o claim `eid` (empresa ativa da sessão); `PdvVendaService`/`FuncionarioService` retrofitados pra gravar `id_empresa` a partir desse claim, não mais "a primeira empresa do tenant"; `GET /api/v1/eu` ganhou o campo `empresa`. **Módulo `estoque.transferencia` (novo, 2026-07-28 — docs/telas/transferencia-estoque.md), primeira classe real do pacote `estoque`:** `GET/POST /api/v1/estoque/transferencias` — empresa de origem sempre do claim `eid`, só a de destino vem do request; grava dois `produto_movimento_mestre` (`tipo_movimento='TRANSFERENCIA'`, um por empresa, mesmo `id_transferencia` — nova tabela `produto_transferencia`, cabeçalho) com detalhe 'D'/'C' por produto, reaproveitando a trigger `fn_atualiza_estoque_movimento` já existente sem nenhuma lógica de saldo nova; checa estoque disponível na origem antes de gravar (409 se insuficiente, mesmo padrão do PDV). **179 testes verdes** no total (`UsuarioCrudTest` 11, `LoginEmpresaTest` 3, `TransferenciaCrudTest` 6, novos). **Transferência de Produtos — filtros, ordenação, exclusão e popup de destino (novo, 2026-07-29):** `GET /api/v1/estoque/transferencias` ganhou `idTransferencia`/`idEmpresaOrigem`/`idEmpresaDestino`/`dataInicial`/`dataFinal` (filtro) e `ordenarPor`/`direcao` cobrindo todas as colunas (incluindo `numero`, novo); `DELETE /api/v1/estoque/transferencias/{id}` reverte o estoque nos dois lados apagando o detalhe do ledger (`produto_movimento_mestre` continua imutável por grant — os cabeçalhos ficam órfãos), sem checar saldo no destino. **Estoque negativo permitido em qualquer movimentação (2026-07-29, pedido direto do dono do produto):** `TransferenciaService`/`PdvVendaService` pararam de checar `produto_estoque.disponivel` antes de gravar — venda/transferência sempre é aceita, mesmo deixando saldo negativo; `SignupService` não muda (sem CHECK no banco, nunca existiu). **Módulo novo `financeiro.recebimentocrediario` (2026-07-29, docs/telas/recebimento-crediario.md, spec RN001–RN013 fornecida pelo dono do produto):** `GET /api/v1/recebimento-crediario/clientes` (busca por nome/CPF/celular, 400 se nenhum filtro), `GET /api/v1/recebimento-crediario/parcelas?idCliente=` (só categoria CREDIARIO em aberto, multa/juros recalculados a cada chamada a partir de `cfg_geral.multa_crediario`/`juros_crediario`/carências — percentual único pra multa após a carência, percentual ao dia pra juros), `GET /api/v1/recebimento-crediario/carteiras` (só `permite_receber_crediario = true` nas categorias AVISTA/CARTAO_DEBITO/CARTAO_CREDITO), `POST /api/v1/recebimento-crediario` efetiva numa única transação: trava cada parcela (`FOR UPDATE`, protege contra corrida de recebimento duplo — 409 se já recebida), recalcula multa/juros de novo no servidor (nunca confia no valor da listagem), valida a(s) carteira(s) escolhida(s), aloca os pagamentos nas parcelas em FIFO por vencimento (permite uma parcela ser paga por mais de uma forma — cada fatia vira um lançamento de `caixa_detalhe` com o `id_venda` certo), abre `caixa_mestre` automaticamente se não houver um pra esse usuário/empresa/dia, grava `contas_receber` (baixa) + `contas_receber_detalhe` (quando a carteira dominante da parcela é de cartão) + `contas_receber_lote` (tabela nova, cabeçalho/gerador real de `id_lote_recebimento` — antes só um "gerador externo" placeholder, mesmo padrão que `produto_transferencia` deu a `id_transferencia`). **Schema (V025, editada):** `tipo_carteira.permite_receber_crediario` (boolean, novo — controla quais carteiras aparecem na tela; tenants novos já nascem com DINHEIRO/PIX/CARTAO DEBITO/CARTAO CREDITO marcados `true`, CREDIARIO/vales `false`) + `contas_receber_lote` (tabela nova, RLS próprio). `TipoCarteiraDtos/Service` ganharam o campo. **193 testes verdes no total** (`RecebimentoCrediarioCrudTest` 13, novos, mais os ajustes em `PdvCrudTest`/`ClienteHistoricoCrudTest`/`TipoCarteiraCrudTest`/`TransferenciaCrudTest` que precisaram do campo novo obrigatório ou do comportamento de estoque negativo). Verificado ao vivo no navegador com dados reais de um cliente com parcelas vencidas — cálculo de multa/juros bateu exato com a fórmula, split-tender dividiu uma parcela entre duas formas de pagamento corretamente, caixa abriu sozinho. **Estorno de Recebimento de Crediário (novo, mesmo dia, mesmo módulo `financeiro.recebimentocrediario`):** `GET /api/v1/recebimento-crediario/estornos?nomeCliente=&dataInicial=&dataFinal=` (nome do cliente obrigatório, 400 se vazio; lista cada lote com qtd. de parcelas e formas de pagamento via `string_agg`), `GET /api/v1/recebimento-crediario/estornos/{idLoteRecebimento}/parcelas` (`readOnly`, só visualização) e `POST /api/v1/recebimento-crediario/estornos/{idLoteRecebimento}` (transacional: trava lote+parcelas, apaga `contas_receber_detalhe` **antes** de limpar `id_lote_recebimento`, reabre todas as parcelas do lote numa `UPDATE` só, apaga `caixa_detalhe` do lote e o cabeçalho `contas_receber_lote` — nunca mexe em `caixa_mestre`; 409 se o lote não existir ou já tiver sido estornado). Regra central: um lote pode cobrir parcelas de vendas/contratos diferentes recebidas juntas — estornar uma exige estornar todas as do mesmo lote, garantido por desenho (a unidade de ação é sempre o lote, nunca a parcela). Decisões via `AskUserQuestion`: ADMIN e OPERADOR podem estornar (não só ADMIN) e o lote é apagado fisicamente após o estorno (não mantido com marca) — mesmo padrão da exclusão de Transferência. **7 testes novos** (`RecebimentoCrediarioCrudTest`, 20 no arquivo). **200 testes de backend verdes no total.** **Parâmetro "Permite quantidade decimal para produtos" (novo, 2026-07-29 — docs/telas/configuracao-geral.md):** `cfg_geral` ganhou `cfg_permite_qtd_decimal boolean NOT NULL DEFAULT true` (coluna nova dentro de `V023__cfg_geral.sql`, banco em construção — editada em vez de nova migration); `ConfiguracaoGeralService` ganhou `permiteQtdDecimalProduto()` + `GET /api/v1/config-geral/permite-qtd-decimal` (aberto a qualquer papel, mesmo padrão de `/flags-variante`/`/desconto-venda` — PDV e Transferência precisam do valor sem ser ADMIN). `PdvVendaService`/`TransferenciaService` passaram a validar cada item antes de gravar qualquer coisa: quando o parâmetro está desligado, quantidade com parte decimal (`temParteDecimal()`, novo helper igual nos dois serviços) responde 400 sem gravar nada — antes a coluna `numeric(14,3)` aceitava fração em qualquer situação, sem checagem nenhuma. **203 testes verdes no total** (`ConfiguracaoGeralTest` foi de 6 pra 8, `PdvCrudTest`/`TransferenciaCrudTest` ganharam 1 teste cada de rejeição). **Multiplicador de código de barras "qtd*código" (novo, 2026-07-29):** feature só de frontend (`web/`) — nenhuma mudança de backend, já que a validação de quantidade decimal/inteira acima cobre qualquer valor que a tela envie. |
| `site/` | Site público (Astro/SSG, ADR-011). **Home institucional "matadora"** (posicionamento concorrente do Bling): hero com painel animado + demo de sincronização, faixa de stats com contadores, contraste problema→solução, 3 passos, 6 recursos, canais (ML/Shopee/Amazon/balcão), planos (preços via `/api/publico/planos`), FAQ e CTA — tudo em CSS/SVG puro com **scroll-reveal** e **prefers-reduced-motion** (sem novas deps). Sistema visual em `src/styles/site.css` portado do golden `nainer_institucional`. `/assinar` (form → `POST /api/publico/assinar` → auto-login → `/bem-vindo`) e `/bem-vindo` mantidos. **Trial 60 dias** em toda a copy. Tema claro/escuro persistido. **Build SSG ok**; hero/reveal/contadores verificados via Playwright |
| `web/` | ERP do lojista (React 19 + Vite + TS). Auth JWT (login slug+email+senha; **handoff SSO** do site via `#token=`), shell (nav Painel/Produtos/Estoque/Pedidos/Canais/**Clientes** + Sair), **Painel** real (`GET /api/v1/eu` via TanStack Query). **Tela de Clientes completa** (2026-07-20/21): ícone de identificação (pessoa) à esquerda do título, listagem com busca em maiúsculas/filtro/**paginação fixa em 50 itens, sem seletor** (janela deslizante de páginas com primeira/anterior/próxima/última, estilo inspirado no sistema legado)/**ordenação por coluna** (cabeçalho em destaque, ícone "⇅"/"▲"/"▼" em cada uma), **três ícones de ação por linha** (visualizar verde/editar azul/excluir vermelho, sem texto — visualizar abre `/clientes/:id/visualizar` em modo somente-leitura), grid mais compacta, formulário com cabeçalho enxuto ("Cliente" + Cancelar/Salvar no topo, topo fixo/só o corpo rola) e grid de 12 colunas (§3.7) largura total (`.app-main` 1600px), máscaras com validação de dígito verificador/formato/duplicidade (inclusive **CNPJ alfanumérico** e **limite de crédito em moeda**), validação por campo (blur + submit, replicada no backend — 2026-07-21), pop-up de erro vermelho (`Toast.tsx`), autopreenchimento de endereço via ViaCEP, modal embutido de categoria, exclusão com confirmação em modal próprio, `AjudaDaTela` (R22), convenções de **maiúsculas sempre** e **foco automático**. **Tela de configuração de campos** (`ConfiguracaoTelaCliente.tsx`, `/clientes/configuracao`, só `ADMIN` — `RequireAdmin.tsx`): cabeçalho enxuto com Cancelar/Salvar no topo (fixo, 2026-07-21), acessível pelo ícone ⚙ ao lado da ajuda; o formulário de Cliente lê essa config (`lib/configuracaoTela.ts`) e ajusta visibilidade/obrigatoriedade dos campos em tempo real. **Tela de Funcionários completa (nova, 2026-07-21):** `pages/funcionarios/` (lista + formulário + configuração de tela), replicando integralmente o padrão do cliente — ícone próprio (maleta), ordenação por coluna, paginação fixa em 50, três ícones de ação, modo somente-leitura, máscara de percentual para "% Comissão". **Tela de Plano de Contas completa (nova, 2026-07-21):** `pages/planocontas/` (lista + formulário; sem tela de configuração — nada configurável), código contábil como PK de negócio nas rotas (`/planos-contas/3.1.001`), Código bloqueado na edição, sem filtro de status (tabela sem `ativo`), ícone próprio (prancheta). **Tela de Fornecedores completa (nova, 2026-07-21):** `pages/fornecedores/` (lista + formulário + configuração de tela), com um mecanismo novo — `PlanoContasModal.tsx`, criação rápida de plano de contas embutida no formulário (botão "＋ Novo" ao lado do select de Plano de Contas, mesmo papel do modal de categoria do cliente); filtro por plano de contas na listagem; ícone próprio (caminhão). **Parâmetros do Sistema (nova, 2026-07-21):** `pages/configuracaogeral/ConfiguracaoGeralForm.tsx` — primeira tela **fora** do padrão de cadastro: sem listagem/paginação/busca/modo somente-leitura/`InfoRegistro` (a tabela `cfg_geral` é singleton por tenant, sem `criado_em`); item de menu ("Parâmetros do Sistema") e a própria rota só aparecem/funcionam para ADMIN; ícone próprio (`IconeParametros`), deliberadamente diferente da engrenagem (⚙) usada como atalho de "configurar campos" em cada cadastro. **Campos informativos de auditoria** (`InfoRegistro.tsx` + `lib/datas.ts`, 2026-07-21): Código/Cadastrado em/Última alteração, somente leitura, no fim de todo formulário de cadastro (em Cliente, Funcionário, Plano de Contas e Fornecedor; o `codigo` aceita PK numérica ou texto). Demais áreas (Produtos/Estoque/Pedidos/Canais) ainda placeholder. **Shell do ERP com altura travada no viewport** (2026-07-21, convenção nova — `Layout.tsx`/`styles.css`): menu lateral e cabeçalho fixos, sem scroll de página inteira; `html`/`body`/`#root` com `overflow: hidden` (2026-07-21 — sem isso, qualquer 1px de folga faz o documento inteiro rolar) e `.app-main`/`.table-wrap` com altura própria fazendo o scroll de verdade, para o cabeçalho `position: sticky` das tabelas grudar no lugar certo; a tela de Clientes usa `.lista-tela`/`.lista-topo`/`.lista-corpo`/`.lista-rodape` para travar também a barra de filtros e o rodapé de paginação, deixando só a tabela com scroll próprio. Barra de rolagem no padrão de cores do tema (claro/escuro). Design tokens §3.7. **Build ok**; fluxo **e2e verificado no navegador**. **Tela de Produtos completa (nova, 2026-07-22 — `pages/produtos/`):** lista + formulário + configuração de tela, mesmo padrão; categorias com setas ▲/▼ de reordenação + modal de gestão embutida (`CategoriaProdutoModal.tsx`); seção "Dimensões e Variantes" só mostra nome de variante quando a flag correspondente de Parâmetros do Sistema está ligada; NCM com máscara `9999.99.99` e busca automática de descrição ao lado; preço de venda/% de venda recalculados ao vivo um a partir do outro; layout final: "Produto ativo" em linha própria, Descrição+Marca juntos, Referência+NCM+descrição do NCM juntos, os 6 campos de Preços numa única linha, Peso Bruto/Líquido+variantes numa única linha. **Convenções novas que valem pro sistema inteiro (2026-07-22):** campo decimal (moeda/percentual/peso) com digitação natural (`lib/masks.ts#mascararMoeda/mascararPercentual/mascararPeso` + `completar*` no `onBlur`) substituindo a antiga leitura de dígitos da direita; campo de data como texto mascarado `dd/mm/aaaa` (`mascararData`, `onFocus` com `.select()`) substituindo `<input type="date">` em Cliente (nascimento) e Produto (início/final de oferta) — o nativo não permite "selecionar tudo e sobrescrever ao digitar" em nenhum navegador. Ícone próprio (caixa/pacote). **Confirmação antes de salvar via Enter (2026-07-23, `ConfirmarSalvarModal.tsx` + `lib/formularios.ts`):** nas telas de cadastro, Enter num campo de texto abre "Salvar dados?" em vez de submeter direto; clique no botão "Salvar" continua instantâneo. **Telas de Moeda e Tipo de Carteira completas (novas, 2026-07-23 — `pages/moeda/`, `pages/tipocarteira/`):** mesmo padrão de cadastro; Tipo de Carteira gerencia embutido o checklist de moedas (`moeda_detalhe`, sem reordenar) com criação rápida de moeda (`MoedaModal.tsx`, mesmo papel do `PlanoContasModal`) e ícones por moeda no checklist (`IconeDaMoeda`, heurística por palavra-chave — cartão/PIX/genérico); % Desconto/% Acréscimo de Moeda são mutuamente exclusivos (digitar valor > 0 num limpa o outro); Taxa Administradora de Tipo de Carteira é opcional. Ícones próprios (cifrão/carteira) e itens no menu. **Galeria de fotos em Produtos (nova, 2026-07-23 — ADR-013, `GaleriaImagensProduto.tsx`):** seção "Fotos (N/6)" no formulário — miniaturas com setas de reordenar + lixeira de excluir, botão "＋ Adicionar foto" (desabilita ao atingir 6), oculta no modo somente-leitura, aviso "salve o produto primeiro" em produto novo (upload precisa de `idProduto` de verdade). Upload é `multipart/form-data` — `lib/api.ts` ganhou `apiUpload()` (nunca define `Content-Type` manualmente, o navegador precisa gerar o boundary sozinho). **Galeria de fotos — confirmação, lightbox e upload antes de salvar (2026-07-23):** excluir foto pede confirmação em modal; clicar numa miniatura abre lightbox (`.modal-lightbox`) com navegação ◀/▶/teclado entre as fotos do produto; produto novo (ainda sem `idProduto`) já permite escolher fotos — ficam em preview local (`arquivosLocais`, `URL.createObjectURL`) e são enviadas em ordem só depois que o produto é criado. **Tipo de Carteira** ganhou o campo "Categoria \*" (select) no formulário e coluna própria na listagem. **Tela de Histórico do Cliente (nova, `pages/clientes/ClienteHistorico.tsx`, rota `/clientes/:id/historico`):** acessível por ícone (relógio) na lista de Clientes e botão no formulário — 3 seções (Histórico de Compras, Histórico de Parcelas, Resumo das Parcelas de Crediário em cards), só leitura, sem paginação. **Pop-up de erro/sucesso em todo o sistema (2026-07-24):** `Toast.tsx` ganhou a prop `tipo?: 'erro' | 'sucesso'` (vermelho/verde, sempre letra branca); as 7 listagens de cadastro (Cliente/Fornecedor/Funcionário/Produto/Moeda/Tipo de Carteira/Plano de Contas) trocaram o banner inline (`aviso-banner`, removido do CSS) por `Toast`; `Login.tsx` e `ConfiguracaoGeralForm.tsx` idem. **Pop-up de sucesso ao clicar Salvar (novo):** os 7 formulários de cadastro e as 4 telas de configuração de campos, que antes só navegavam de volta pra lista sem avisar nada, agora passam `navigate(rota, { state: { toast } })` — a lista lê `location.state` uma vez no primeiro render (`useState` com inicializador preguiçoso) e limpa do histórico (`window.history.replaceState`) pra um F5/voltar não repetir o popup. **Histórico do Cliente — grid navegável e master-detail (2026-07-24, vídeo/imagem de referência do dono do produto, `docs/PROGRESSO.md` linha do tempo):** linhas de Compras e Parcelas ficam destacadas ao clicar/Enter/Espaço (`.linha-selecionada`, cor `--accent`); Compras ganhou barra de navegação ▲/▼ (mouse e teclado ↑/↓) que troca a compra selecionada — a primeira já vem selecionada ao carregar; Histórico de Parcelas **filtra automaticamente pelas parcelas da compra selecionada** (master-detail de verdade, com botão "Ver todas as parcelas" pra sair do filtro); colunas novas: Qtd Produtos (compras) e Nº Parcela (parcelas); Tipo passou a mostrar `nomeCarteira` (nome específico da carteira) em vez da categoria genérica; linha de Total no rodapé das duas grids (recalculada sobre o conjunto filtrado, no caso de parcelas). Decisão confirmada pelo dono do produto: manter o visual do próprio Niner (claro/escuro), só copiando o *comportamento* de navegação do sistema legado — sem caixa de detalhe de texto abaixo da grid (só destaque de linha). **Histórico do Cliente — layout em duas colunas (2026-07-27, pedido do dono do produto com mockup ASCII de referência):** reorganizado pra Histórico de Compras à esquerda (altura cheia, mantendo navegação ▲/▼ e teclado) e, à direita, **Produtos da Compra** (novo painel — Descrição do Produto/Variação de Linha/Variação de Coluna/Qtd Vendida/Preço de Venda) empilhado sobre o Histórico das Parcelas, os dois sempre filtrados pela compra selecionada/navegada (master-detail — o botão "Ver todas as parcelas" foi removido, deixou de fazer sentido no layout novo). CSS novo (`.historico-corpo`/`.historico-grid`/`.historico-coluna-esquerda`/`.historico-coluna-direita`/`.historico-painel-produtos`/`.historico-painel-parcelas`) — cada painel rola o próprio conteúdo, mesmo princípio de `.lista-corpo`/`.table-wrap` adaptado pra duas colunas. Coluna "Tipo" da grid de Parcelas renomeada pra "Tipo Parcela" (o dado exibido continua `nomeCarteira`, decisão de 2026-07-24 mantida — só o rótulo mudou) e "Id Venda" da grid de Compras renomeada pra "Nº Venda" (pedido à parte, mesma sessão). **Tela de PDV — Frente de Caixa (nova, 2026-07-28 — `pages/pdv/`):** nasceu como rascunho de layout num Artifact (mockup `TELA PDV.png`/`tela_modelo_1.png` do dono do produto) e foi incorporada ao ERP em etapas na mesma sessão — primeiro só a tela (F2/F3/F4/F5 com catálogo de demonstração local, sem API), depois renomeação de rótulos (Quantidade/Valor Unitário/Total Produto), F2 (`PesquisaProdutoModal.tsx`) e F3 (`AlteraQuantidadeModal.tsx`, com confirmação antes de remover item ao chegar em zero) funcionais, navegação ↑/↓ nos Produtos Vendidos (funciona mesmo com foco no campo de código de barras — nenhuma das teclas usadas insere caractere), ícones nos botões F2–F5 (`IconeLupa`/`IconeAjustar`/`IconeLimpar`/`IconeConfirmar`, novos em `Icones.tsx`) e um bug real corrigido: o listener global de teclado tinha um `return` por causa do foco no campo de código de barras **antes** de tratar F2–F5, então o `preventDefault` nunca rodava e o navegador vencia (F5 recarregava a página de verdade) — corrigido tirando essa checagem de cima das F-keys. Por fim (2026-07-28), a tela virou real: `lib/pdv.ts` substituiu o catálogo de demonstração (`catalogoDemo.ts`, removido) por chamadas de verdade (`buscarProdutosPdv`/`buscarProdutoPorCodigo`/`efetivarVenda`); `FormaPagamentoModal.tsx` (novo) escolhe o Tipo de Carteira e o número de parcelas, mostra a prévia calculada no front (mesma conta do backend, só pra exibir antes de confirmar) e efetiva a venda de verdade no F5. Verificado ao vivo com produtos reais cadastrados no banco de dev: venda de crediário em 3x bateu exatamente com a prévia e, conferido direto no banco, gravou a venda, baixou o estoque só na loja certa e criou as 3 parcelas em aberto. **Menu lateral retrátil (novo, 2026-07-28 — `Layout.tsx`):** botão no rodapé do menu alterna entre 200px (com rótulos) e 56px (só ícones, com `title` como tooltip), preferência em `localStorage`; ícone novo pra todo item que ainda não tinha (Painel, Estoque, Pedidos, Canais — necessário pro modo recolhido não ficar vazio). **Tela de Moeda removida / Tipo de Carteira absorveu o cadastro (2026-07-28 — fusão `tipo_carteira`+`moeda`):** `pages/moeda/`, `lib/moedas.ts` e `MoedaModal.tsx` **deletados**; rotas `/moedas*` e item de menu "Moeda" removidos de `App.tsx`/`Layout.tsx`; `TipoCarteiraForm.tsx` perdeu o checklist de moedas embutido e ganhou a seção "Desconto / Acréscimo" (% Desconto/% Acréscimo, mesma UX de mutualidade por valor que a Moeda tinha) direto no formulário, com nota explicando que a mesma bandeira pode ter um cadastro por categoria; `TipoCarteiraLista.tsx` trocou a coluna "Moedas" por "% Desconto"/"% Acréscimo" (e as três agora são ordenáveis por coluna, pedido à parte no dia seguinte). **F5 — Forma de Pagamento virou split-tender + desconto promocional (2026-07-28, mesma sessão do dia seguinte, pedido literal do dono do produto com a fórmula do resumo/saldo a pagar):** `FormaPagamentoModal.tsx` reescrito por completo. Resumo no topo: Valor Total da Venda sempre; Desconto Promocional + Sub-total só aparecem quando `cfg_geral.percentual_desconto_venda > 0` (`lib/configuracaoGeral.ts` ganhou `buscarDescontoVenda()`, endpoint aberto a qualquer papel). **Saldo a Pagar** sempre visível antes dos botões, fica verde quando fecha em zero. **4 botões de categoria** (À Vista/Cartão Débito/Cartão Crédito/Crediário, ordem fixa) — desabilitado se não houver tipo de carteira cadastrado na categoria; ao clicar, se houver mais de um tipo de carteira na categoria (ex.: HIPER débito e crédito) pede pra escolher, senão já vai direto pro valor pago (pré-preenchido com o saldo restante, `formatarMoeda`) + prévia de quanto aquela linha cobre do saldo (mesma fórmula do backend, só client-side) + prévia de parcelas. Dá pra combinar várias formas de pagamento na mesma venda — lista embaixo do resumo, cada uma removível — e só libera "Confirmar Venda" quando a soma fecha o saldo. `lib/pdv.ts` trocou `EfetivarVendaRequest.idCarteira/numeroParcelas` por `pagamentos: PagamentoRequest[]` e `VendaEfetivada` ganhou `valorTotalProdutos`/`descontoPromocional`/`valorLiquido`/`pagamentos[].parcelas`. CSS novo (`.pdv-resumo-pagamento`/`.pdv-saldo-pagar`/`.pdv-categorias-pagamento`/`.pdv-edicao-pagamento`). Verificado ao vivo no navegador: venda de R$100 com 10% de desconto promocional + dois pagamentos (dinheiro com 10% de desconto próprio + débito) fechando o saldo em R$0,00 exato, efetivada com sucesso (venda registrada, depois removida do banco de dev por ser só teste). **`FormaPagamentoModal.tsx` — desconto vira campo do operador + teto de valor pago (2026-07-28, mesmo dia, sessão seguinte):** o resumo perdeu o cálculo automático de desconto e ganhou dois campos sincronizados — **Desconto (%)** e **Desconto (R$)** (`mascararPercentual`/`mascararMoeda`, convenção de sempre) — que nascem zerados, clampam no `onBlur` contra o máximo de `cfg_geral.percentual_desconto_venda` e ficam travados assim que o primeiro pagamento é lançado; Sub-total só aparece quando o desconto informado é `> 0`. O campo "Valor Pago" de uma linha cuja forma de pagamento tem `percDesconto` próprio agora mostra o valor máximo permitido e **ajusta sozinho** pro teto se o operador digitar acima (`calcularValorPagoMaximo`, mesma fórmula do backend). `lib/pdv.ts` — `EfetivarVendaRequest` ganhou `descontoVenda`; `VendaEfetivada.descontoPromocional` renomeado pra `descontoVenda`. **F6 Efetiva Venda (era F5) + F5 Devolver Produto reservado + Cliente/Vendedor obrigatorios (2026-07-28, mesma sessao):** botao grande renomeado pra F6; novo botao F5 na linha F2-F4, sem funcionalidade ainda (so o atalho reservado). Resumo reorganizado: fonte maior em "Valor Total da Venda", foco automatico no campo % do Desconto Gerencial, prefill de "Valor Pago" ja com o valor ajustado ao escolher uma carteira com desconto, teto de valor pago valendo pra qualquer carteira (nao so as com desconto). Layout dos botoes de categoria mudou de grade horizontal pra coluna ao lado do resumo, sempre visiveis; area de pagamentos (lista ou edicao de linha) numa caixa propria abaixo; rodape Cancelar/Confirmar sempre visivel. Bug corrigido: "Valor a Pagar" nao caia conforme as formas de pagamento eram lancadas (so descontava o Desconto Promocional, esquecendo o que ja tinha sido pago). **Cliente e Vendedor da venda (novo):** dois modais novos (`PesquisaClienteModal.tsx` — nome/CPF-CNPJ/celular — e `PesquisaVendedorModal.tsx` — nome, reaproveitando `listarFuncionarios` ja existente), campos obrigatorios no topo da tela ("Cliente *"/"Vendedor *"), "Confirmar Venda" exige os dois selecionados alem do saldo fechado. `lib/pdv.ts` ganhou `PdvCliente`/`buscarClientesPdv`; `EfetivarVendaRequest` ganhou `idCliente`/`idFuncionario`. **Tela de Usuários completa (nova, 2026-07-28 — `pages/usuarios/`):** lista + formulário, ADMIN-only (`RequireAdmin` na rota + item de menu só visível pra ADMIN), sem `cfg_tela_campo`; formulário com checklist "Empresas com acesso" (`GET /api/v1/empresas`); campo "Administrador" **não é um checkbox** — quando o usuário editado é o admin, aparece um badge somente-leitura com explicação de que o privilégio é permanente e definido só no signup. **Login com escolha de empresa (`Login.tsx` reescrito):** formulário normal vira uma segunda etapa (lista de botões, um por empresa) quando a API responde pedindo escolha; "Voltar" descarta sem perder as credenciais já digitadas. `lib/eu.ts` ganhou o campo `empresa`; `Layout.tsx` mostra o nome da empresa ativa no header. **Tela de Transferência de Estoque (nova, 2026-07-28 — `pages/estoque/`), primeiro conteúdo real do menu Estoque (era `EmBreve`):** `TransferenciaLista` (`/estoque`, histórico com paginação), `TransferenciaForm` (`/estoque/nova` — Empresa de Origem sempre somente-leitura com o nome da empresa ativa, Empresa de Destino em select excluindo a origem, produtos adicionados via `PesquisaProdutoModal` do PDV reaproveitado direto, quantidade com a máscara de peso de 3 casas) e `TransferenciaDetalhe` (`/estoque/:id`, somente-leitura, sem editar/excluir — registro permanente como uma venda). Achado ao vivo: `<textarea>` nunca tinha estilo global (`styles.css` só tinha `input, select`) — corrigido na mesma regra. **Transferência de Produtos — filtros, ordenação, exclusão, popup de destino, alinhamento e totais na grid (novo, 2026-07-29, três rodadas de pedidos do dono do produto):** barra de filtros reordenada (Data Inicial/Final, Nº da Transferência, Empresa de Saída/Entrada) com coluna "Nº" nova e ordenável em todas as colunas (`th-ordenavel`, mesmo padrão do resto do projeto); ícone vermelho de excluir por linha com modal de confirmação avisando que o estoque volta pra origem mesmo sem saldo no destino. `Nova Transferência` reescrita: `EscolherDestinoModal.tsx` novo — popup que mostra a origem e pede o destino antes de liberar a tela de produtos (a antiga seção "Empresas" com select embutido saiu do corpo do formulário); título passou a mostrar "Nova Transferência · {origem} → {destino}" na mesma linha; campo Observações removido (nunca foi usado); alinhamento do campo Código de Barras com o botão "Pesquisar Produto" corrigido (rótulo invisível do mesmo tamanho do real, garantindo que os dois comecem na mesma linha, em vez de alinhar pelo fundo da coluna mais alta); totais (Quantidade/Valor Total) viraram uma linha `<tfoot>` dentro da própria tabela em vez de uma caixa solta — garante alinhamento pixel-perfeito com as colunas Quantidade/Valor Total da grid (mesmo padrão já usado no Histórico do Cliente). **Tipo de Carteira** ganhou o checkbox "Permite receber parcelas de crediário" (`permiteReceberCrediario`, RN007). **Tela nova `financeiro.recebimentocrediario` (`pages/recebimentocrediario/RecebimentoCrediario.tsx`, 2026-07-29):** busca de cliente (3 campos, resultado em tabela clicável), grid de parcelas selecionável/navegável por teclado com rodapé de 5 caixas de totais (`.recebimento-rodape-totais`, CSS novo), área de forma de pagamento reaproveitando o visual de categoria→carteira→valor do `FormaPagamentoModal` do PDV (sem a lógica de desconto/acréscimo da carteira, que não se aplica aqui) — split-tender testado ao vivo dividindo uma parcela entre duas formas de pagamento. Item novo no menu lateral (`IconeRecebimentoCrediario`, recibo com check) e entrada em `AjudaDaTela.tsx` (`financeiro.recebimentocrediario.tela`). **Redesenho de layout (mesmo dia, sessão seguinte):** grid ganhou coluna Empresa e "Nº PC" (`parcela/totalParcelas`, escopado por venda+carteira), Multa e Juros fundidas numa coluna; rodapé de totais virou barra lateral fixa com altura mínima reservada e só a grid rola por dentro (`.recebimento-parcelas-layout`/`.recebimento-parcelas-sidebar`, CSS novo); os 3 botões de categoria saíram da lateral — um único botão "Receber" abre `EscolherFormaPagamentoModal.tsx`, que já resolve categoria **e** Tipo de Carteira/Valor Pago no mesmo popup; formas de pagamento lançadas viraram cards em grade de 4 colunas; botão final renomeado "Efetivar Recebimento", "Cancelar" da busca virou "Voltar", botão "Trocar" redundante ao lado do nome do cliente removido. **Estorno de Recebimento de Crediário (tela nova, mesmo dia — `EstornoRecebimentoCrediario.tsx`):** busca por nome do cliente (obrigatório) + intervalo de data de recebimento, lista cada lote já efetivado (data, cliente, qtd. de parcelas, valor total, formas de pagamento usadas) com um ícone verde "visualizar" (abre popup só-leitura com as parcelas do lote) e um ícone vermelho "estornar" (reverte o lote inteiro — nunca uma parcela isolada — com modal de confirmação); mesmo esqueleto de `TransferenciaLista.tsx`, sem paginação. Item de menu "Estorno de Crediário", ícones próprios (`IconeEstornoRecebimentoCrediario`/`IconeEstornar`). **Menu "Estoque" renomeado para "Transferência de Produtos" (2026-07-29):** só o rótulo em `Layout.tsx` — rota/ícone inalterados. **"Empresas com acesso" (Usuários) em grade de 6 colunas (2026-07-29):** `UsuarioForm.tsx` trocou a lista vertical de checkboxes por um cartão por empresa em grade de 6 colunas, eliminando o scroll vertical do checklist. **Parâmetro "Permite quantidade decimal para produtos" (novo, 2026-07-29 — `cfg_geral.cfg_permite_qtd_decimal`):** checkbox na seção "Estoque" de Parâmetros do Sistema (`ConfiguracaoGeralForm.tsx`), lido via `buscarPermiteQtdDecimal()`/`GET /permite-qtd-decimal` (qualquer papel) em todo lugar que mostra ou digita quantidade de produto — PDV (`Pdv.tsx`, `PesquisaProdutoModal.tsx`, `AlteraQuantidadeModal.tsx`), Transferência de Produtos (`TransferenciaForm.tsx`, `TransferenciaDetalhe.tsx`) e Histórico do Cliente (`ClienteHistorico.tsx`) — quando ligado (padrão) aceita/mostra 3 casas decimais (`mascararQuantidade`/`completarQuantidade`/`desmascararQuantidade`/`formatarQuantidade`, novas em `lib/masks.ts`, delegando às funções de peso já existentes), quando desligado força inteiro; validado também no servidor (`PdvVendaService`/`TransferenciaService` rejeitam com 400 quantidade fracionária quando desligado — P4, o front nunca é a única barreira). **Multiplicador de código de barras "qtd*código" (novo, 2026-07-29):** `interpretarCodigoBarras()` (nova em `lib/pdv.ts`) reconhece a sintaxe `5*9001000000138` (quantidade 5, código `9001000000138`) na leitura de código de barras do PDV (`Pdv.tsx`) e da Transferência de Produtos (`TransferenciaForm.tsx`) — sem o `*` funciona como sempre (quantidade 1); dica discreta ("Dica: '5*código' lança direto com quantidade 5.") exibida abaixo do campo de código de barras nas duas telas. |
| `admin/` | Ainda não criado (backoffice React 19 + Vite) |

**Stack alvo:** Java 25 + Spring Boot 4.x · PostgreSQL 18 (Docker, banco **`niner_db`**) · React 19 + Vite (3 apps) · Flyway · JWT. **SaaS multi-tenant** (banco único + `id_tenant` + Postgres RLS).

---

## Linha do tempo

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
   dado apagado).

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
do próprio Niner (claro/escuro, §3.7), só copiando o *comportamento* de navegação, não o tema
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
   posicionando o Niner como ERP multicanal moderno (sem citar o Bling nominalmente): hero com
   painel decorativo + **demo de sincronização estoque→canais**, faixa de prova com **contadores
   animados**, blocos problema→solução, "como funciona" em 3 passos com ilustrações SVG,
   6 recursos, pílulas de canais, planos (mantido o enhancement de preços da API), FAQ em
   `<details>` e CTA final. Toda CTA leva a `/assinar` ("60 dias grátis, sem cartão").
3. **Sistema visual + movimento.** Novo `site/src/styles/site.css` (portado do golden
   `docs/padroes/nainer_institucional`, rebrandeado Niner) e `Base.astro` com navbar sticky,
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

- **Decisões de negócio do SaaS (D1–D10)** — ver `docs/PLANO-DE-NEGOCIO.md`. Abertas: D1 preços, D3 gateway (adiado), D5 nome "Niner", D6 NFS-e da assinatura, D8 dunning, D9 metas, D10 comportamento do estado `INADIMPLENTE`.
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

**Retomar — ordem sugerida:**

1. **⭐ Completar o vertical slice de Produtos.** O CRUD de `produto` foi entregue em
   2026-07-22 (`catalogo.produto`, ver linha do tempo); `docs/telas/produto.md` deixou
   **variação e imagens explicitamente fora de escopo**. Falta:
   - **Galeria de fotos (`produto_imagem`)** — object storage já **decidido e provisionado**
     (ADR-013: Firebase/GCS, buckets criados e testados), mas **nenhuma linha de Java escrita**.
     Ler `docs/infra/armazenamento-imagens.md` **antes de começar** — é o handoff, com TASK-A a
     TASK-D e critérios de aceitação. Atenção à seção de credenciais: **a chave não vem pelo
     git**, o próximo dev precisa de acesso concedido (o caminho recomendado dispensa arquivo
     de chave).
   - **Variação/SKU (`produto_barra`)** — schema pronto desde a V017, sem domínio nem tela.
     **⚠️ Evirson:** `sku` **não** é campo digitado — é sempre gerado por
     `gerar_ean13_interno()` (função SQL já pronta em V017, 2026-07-22, testada em
     `EanGeradorTest`); `ProdutoBarraService.criar()` deve chamá-la explicitamente antes do
     `INSERT`, sem criar gerador novo. Ver linha do tempo de 2026-07-22 e `CLAUDE.md` §Convenções.
   - `uso_tenant.qtd_produtos` (enforcement R19).
2. **Estoque:** `produto_estoque` (saldo/reserva) + movimentações (`POST /api/v1/estoque/movimentacoes`) → tela de estoque.
3. **`admin/`** — backoffice da plataforma (lista/ficha de tenants R17, suspender/impersonar R18/R21).
5. **Catálogo `ajuda_tela` na API** (R22) — hoje `AjudaDaTela` (`web/`) embute o conteúdo como fallback estático; falta o endpoint/tabela real (§3.3.10/§3.7.1 da spec).
6. Decisões de negócio em aberto: D1 (preços), D3 (gateway), D5/D6/D8/D9/D10.

**Como subir o ambiente:** `docker compose up -d db && docker compose run --rm flyway` · API: `cd api && ./mvnw spring-boot:run` (ou `java -jar target/*.jar`) · fronts: `cd site && npm run dev` / `cd web && npm run dev`. Testes da API: `cd api && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test` (Colima). ⚠️ Se a API der 401 no `/api/publico/**`, há instância velha presa na 8080 → `lsof -ti tcp:8080 | xargs kill -9`.
