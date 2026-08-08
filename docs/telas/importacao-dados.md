# Spec: Rotina de Importação de Dados                  Status: Implementada
Autor: Claudio Calixto (dono do produto) + Claude · Data: 2026-08-06 · Módulo(s): `configuracao` (importacao) · Fase: 1 — Núcleo do ERP

## Problema

Quando um lojista contrata o Niner, às vezes já opera outro sistema (planilha ou ERP concorrente) e
precisa trazer os dados que já tem — clientes, fornecedores, produtos e o saldo devedor de
crediário — sem redigitar tudo na mão. Hoje não existe nenhum caminho de carga inicial: o único
jeito de popular o tenant é usar as telas de cadastro uma linha por vez.

## Solução proposta

Uma rotina de importação por **CSV**, dentro do grupo **Configurações** (`ADMIN`-only — é uma
operação de risco, mistura com dado real do tenant), cobrindo nesta 1ª fase **4 tabelas**:
`cliente`, `fornecedor`, `produto` (com variação/SKU e saldo inicial de estoque) e `contas_receber`
(só crediário). O usuário escolhe a tabela, baixa um modelo `.csv`, preenche fora do sistema,
sobe o arquivo, resolve algumas escolhas prévias (quando o arquivo antigo não tem uma informação
que o Niner exige), revisa uma prévia com erros/avisos linha a linha, e só então confirma.

Desenhada pra crescer: cada tabela é um módulo de importação independente (mesma infraestrutura
de upload/relatório/log), então liberar uma 5ª tabela no futuro não deveria exigir reformar o que
já existe.

**Menu:** o item **"Importação de Dados"** já existe hoje em `web/src/lib/menu.ts`, mas dentro do
grupo **"Implementações Futuras"**, apontando pra `<EmBreve>`. Esta feature move o item pro grupo
**"Configurações"** (`adminOnly: true`) e troca a rota `/importacao-dados` pela tela real.

## User stories

- Como **ADMIN**, quero trazer meus clientes de uma planilha do sistema antigo pro Niner, sem
  digitar um por um.
- Como **ADMIN**, quero trazer meu catálogo de produtos (com preço, peso, variação de cor/tamanho
  e o estoque atual por loja) de uma vez.
- Como **ADMIN**, quero trazer o saldo devedor de crediário dos meus clientes, pra não perder
  cobrança em aberto na migração.
- Como **ADMIN**, quando uma linha do arquivo tem um erro (documento inválido, referência a algo
  que não existe), quero ver exatamente qual linha e por quê, corrigir só ela e reenviar — sem
  perder as linhas que já estavam certas.

## Fluxo geral da tela

1. **Escolher a tabela** (`cliente` / `fornecedor` / `produto` / `contas_receber`).
2. **Baixar o modelo `.csv`** daquela tabela (cabeçalho + 1-2 linhas de exemplo).
3. **Enviar o arquivo preenchido.**
4. **Escolhas prévias** (só as que se aplicam à tabela — ver seção por tabela abaixo): categoria
   de cliente, plano de contas, carteira de crediário, mapeamento de coluna de estoque → empresa.
5. **Prévia (dry-run):** o servidor processa o arquivo por inteiro sem gravar nada, e devolve:
   quantas linhas serão importadas, quantas rejeitadas (com o motivo, linha a linha), e avisos que
   não bloqueiam (ex.: dado divergente entre linhas que serão unificadas).
6. **Confirmar:** grava de verdade só as linhas válidas; devolve o relatório final e registra um
   lote de importação (auditoria).

Tecnicamente, passos 5 e 6 são a **mesma chamada de API**, com um parâmetro `confirmar=false`
(default, só simula) / `confirmar=true` (grava) — evita guardar estado de sessão no servidor
(API stateless, P4): o cliente reenvia o mesmo arquivo + as mesmas escolhas prévias das duas vezes.

**Implementação da simulação (`confirmar=false`):** cada `ImportadorDeTabela.processar(...)` é
`@Transactional` e roda o código de verdade (mesmo `criar()` do cadastro manual) sempre — a
diferença é que, no final, se `confirmar=false`, lança `SimulacaoConcluidaException` (carregando
o relatório já montado) só pra acionar o rollback automático do Spring. `ImportacaoService`
captura essa exceção e devolve o relatório sem que nada tenha sido persistido. Evita ter dois
caminhos de código (um "de verdade" e um "só valida") — é sempre o mesmo, só muda se commita.

**Produto tem um passo a mais (análise prévia):** como a única escolha prévia de `produto` que
depende do CONTEÚDO do arquivo é o mapeamento de colunas de estoque, o front chama
`POST /api/v1/importacao/produto/analise` (só o arquivo, sem escolhas) antes da etapa 4 — devolve
`{ colunasEstoqueComDado }` (só as `QUANTIDADE_ESTOQUE_N` que têm algum valor `> 0` em alguma
linha). O front monta a etapa de escolhas (mapeamento coluna → empresa) a partir dessa resposta;
as outras 3 tabelas pulam direto pra etapa de escolhas. **Simplificado em 2026-08-08:** a análise
já teve um passo extra de detecção de grupos/rótulo de variante (`usaLinha`/`usaColuna`,
`rotuloLinhaSugerido`/`rotuloColunaSugerido`, editável antes de confirmar) — removido junto com o
modelo de "variante em linha/coluna com nome livre" (ver seção 3 abaixo); cor/tamanho agora têm
nome fixo no sistema, então não há mais nada pra detectar/confirmar sobre rótulo.

## Formato do arquivo

CSV delimitado por `;`, decimal com **vírgula** (`1.234,56`), datas `dd/mm/aaaa` (mesma convenção
de todo campo de data do sistema, nunca ISO), `UTF-8`. Booleanos aceitos como `SIM`/`NAO` (também
aceita `S`/`N`, vazio = default da coluna). Toda validação de campo (CPF/CNPJ, e-mail, celular,
peso líquido ≤ bruto, regra da oferta tudo-ou-nada etc.) **reaproveita exatamente as mesmas regras
e mensagens** dos `Service.validar()` já existentes em cada cadastro manual — não duplica lógica.

## Especificação por tabela

### 1. `cliente`

**Colunas do CSV:** `NOME, TIPO_PESSOA, CPF_CNPJ, RG_IE, DATA_NASCIMENTO, GENERO, EMAIL, TELEFONE,
ENDERECO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, ESTADO, CEP, LIMITE_CREDITO`. Fora do escopo desta
importação: `whatsapp`, `instagram`, `facebook`, `tiktok` (ficam `NULL`; editáveis depois pela
tela normal).

**Escolha prévia:** uma **categoria** (existente, escolhida num select; ou nova, cadastrada na
hora) — aplicada a **todos** os clientes deste arquivo.

**Resolução por CPF/CNPJ (chave de ligação com `contas_receber.csv`):**
- `CPF_CNPJ` preenchido **e já existe** um cliente com esse documento no tenant → **não insere**;
  a linha conta como "já existia" no relatório (o cliente existente é o que vale pra ligação com
  `contas_receber`).
- `CPF_CNPJ` preenchido e **não existe** → cadastra normalmente.
- `CPF_CNPJ` vazio → sempre cadastra um cliente novo (aviso fixo na tela: reenviar o mesmo arquivo
  duplica esses clientes, já que não há como o sistema saber que "é o mesmo").

Validação de PF/PJ, gênero obrigatório pra PF, datas não-futuras etc. — mesmas regras de
`ClienteService.validar`.

### 2. `fornecedor`

**Colunas do CSV:** `RAZAO_SOCIAL, NOME_FANTASIA, CNPJ, INSCRICAO_ESTADUAL, EMAIL, TELEFONE,
ENDERECO, NUMERO, BAIRRO, CIDADE, ESTADO, CEP`.

**Escolha prévia:** um **plano de contas** (existente, escolhido pelo código; ou novo, cadastrado
na hora) — aplicado a **todos** os fornecedores deste arquivo (`fornecedor.id_plano_contas` é
`NOT NULL` e o layout não tem essa coluna, já que o sistema antigo normalmente não tem essa
classificação).

**Dedup:** mesma regra do cliente, com `CNPJ` em vez de CPF/CNPJ — já existe → não insere, reaproveita.

### 3. `produto`

**Colunas do CSV:** `MARCA, REFERENCIA, DESCRICAO, PRECO_CUSTO, PERCENTUAL_VENDA, PRECO_VENDA,
DATA_INICIO_OFERTA, DATA_FINAL_OFERTA, PRECO_OFERTA, CODIGO_NCM, PESO_BRUTO, PESO_LIQUIDO,
NOME_GRADE, DESCRICAO_COR, DESCRICAO_TAMANHO, EAN_CODIGO_BARRAS,
QUANTIDADE_ESTOQUE_1..QUANTIDADE_ESTOQUE_9` (9 colunas fixas — teto de 9 empresas por arquivo).

**Formato achatado — 1 linha pode ser 1 variação de um produto maior:**
- **Agrupamento:** linhas com `DESCRICAO + MARCA + REFERENCIA` idênticos (comparação exata, já em
  MAIÚSCULAS) são o **mesmo produto**; cada linha do grupo vira uma **variação**
  (`produto_barra`) desse produto.
- **Unificação:** dentro do mesmo grupo, linhas com o **mesmo par**
  `DESCRICAO_COR`/`DESCRICAO_TAMANHO` são a **mesma variação** — as quantidades de estoque (por
  coluna 1–9) são **somadas** entre essas linhas, em vez de gerar duplicidade (que violaria a
  constraint `produto_barra_variacao_uk`).
- **EAN em linhas unificadas:** usa o primeiro `EAN_CODIGO_BARRAS` não-vazio encontrado; se houver
  dois EANs diferentes entre as linhas unificadas, gera um **aviso** (não erro) citando ambos.
- **Dado de produto divergente entre linhas do mesmo grupo** (ex.: `PESO_BRUTO`/`CODIGO_NCM`
  diferentes): vence a linha com **maior `PRECO_VENDA`**; empate decide pelo maior
  `PRECO_CUSTO`. Essa linha fornece todos os campos de nível-produto para o grupo inteiro. Gera
  aviso listando a divergência.
- **Cor/grade (revisado 2026-08-08) — substituiu o par genérico `DESCRICAO_VARIANTE_LINHA`/
  `DESCRICAO_VARIANTE_COLUNA`.** O antigo passo de "rótulo de variante detectado automaticamente,
  editável na prévia" **deixou de existir** — cor e tamanho agora têm nome fixo no sistema todo
  (não mais configurável por produto), então não há mais nada pra detectar/confirmar sobre
  rótulo. Regra atual, só quando o tenant usa cor/grade (`cfg_geral.cfg_usa_cor_grade`):
  - Toda linha com `DESCRICAO_TAMANHO` preenchida também precisa trazer `NOME_GRADE` — o nome da
    grade daquele produto. Ausente → erro ("`NOME_GRADE` é obrigatório — este tenant usa
    cor/grade."); divergente entre linhas do mesmo grupo → erro (uma única grade por produto).
  - A grade é **achada por nome** (case-insensitive) ou **criada na hora**
    (`GradeService.obterOuCriarPorNome`), com os tamanhos distintos encontrados nas linhas do
    grupo, **na ordem de primeira aparição no arquivo** (essa ordem vira a ordem da grade).
  - Todo valor novo de `DESCRICAO_COR`/`DESCRICAO_TAMANHO` que ainda não existir em
    `cfg_cor`/`cfg_tamanho` é cadastrado automaticamente durante a importação.
  - Quando o tenant **não** usa cor/grade, as três colunas (`NOME_GRADE`, `DESCRICAO_COR`,
    `DESCRICAO_TAMANHO`) são ignoradas (mesmo princípio de "campo oculto ⇒ servidor ignora, não
    rejeita" usado no resto do sistema) — cada produto nasce com 1 SKU só.
- **SKU nunca vem do arquivo:** toda variação criada chama `gerar_ean13_interno()` (via
  `ProdutoBarraService.obterOuCriar`, mesmo mecanismo do cadastro manual e da Emissão de
  Etiqueta); `EAN_CODIGO_BARRAS` só preenche a coluna `ean` (GTIN real), quando vier preenchida.
- **Estoque:** o servidor inspeciona quais das 9 colunas têm algum valor `> 0` no arquivo inteiro
  e devolve essa lista pro front pedir, por coluna, **"a qual empresa corresponde?"** (select com
  as empresas cadastradas no tenant) — é uma escolha prévia, uma vez por coluna usada, não por
  linha. Cada quantidade `> 0` mapeada vira um **movimento de estoque tipo `AJUSTE`**
  (`produto_movimento_mestre`/`produto_movimento_detalhe`, mesmo padrão de
  `BalancoEstoqueService.efetivar`) — nunca um `INSERT` direto em `produto_estoque` (quem mantém
  esse saldo é a trigger `fn_atualiza_estoque_movimento`, P1/P3).
- **NCM:** opcional, e diferente do cadastro manual — código que não existe em
  `cfg_produto_ncm` (ou vem em formato inválido) entra como **vazio**, não rejeita a linha
  (2026-08-06, pedido do dono do produto: violação de FK não é motivo pra barrar a importação
  inteira). Antes de checar, tira toda pontuação (ex. `6402.99.90` → `64029990`, formato comum
  de planilha) — a tabela de referência guarda só os 8 dígitos.
- **Regra da oferta** (tudo-ou-nada, início não-passado, fim ≥ início, preço de oferta < venda) e
  **peso líquido ≤ bruto** — reaproveitam `ProdutoService.validarOferta`/validação de peso.
  `PRECO_OFERTA = "0"` é tratado como "não preenchido" (2026-08-06) — planilha exportada de outro
  sistema costuma trazer zero em vez de célula vazia numa coluna numérica, e uma oferta de R$
  0,00 não é um caso de negócio real; sem essa normalização, "0" sozinho disparava a exigência
  das duas datas de oferta por engano.

### 4. `contas_receber` (só crediário)

**Colunas do CSV:** `CPF_CNPJ, EMPRESA, NUMERO_PARCELA, DATA_VENCIMENTO, DATA_RECEBIMENTO,
VALOR_RECEBER, VALOR_JUROS, VALOR_DESCONTO, VALOR_RECEBIDO`.

**Escolha prévia:** uma **carteira de crediário** (existente, do tipo `CATEGORIA_CARTEIRA =
CREDIARIO`; ou nova, cadastrada obrigatoriamente com essa categoria) — aplicada a todas as
parcelas deste arquivo.

**Resolução do cliente:** por `CPF_CNPJ`, consultando `cliente` **ao vivo no banco** (não depende
de `cliente.csv` ter sido importado na mesma sessão — funciona mesmo dias depois). CPF não
encontrado → linha **rejeitada**, cai no relatório de erro. Cliente sem CPF/CNPJ cadastrado não
tem como ter crediário importado por esta rotina (limitação conhecida, aceita).

**Resolução da empresa:** pelo `codigo_empresa` (o código curto da empresa, ex. `1`, `2` — visível
na tela de Empresas; 2026-08-06, trocado de nome/fantasia pra código: mais curto de digitar e sem
ambiguidade de maiúscula/acento). Código inexistente → linha rejeitada.

**Venda sintética (contorna `contas_receber.id_venda NOT NULL` sem mexer no schema):** as linhas
são agrupadas por **`(cliente, empresa)`** — pra cada grupo, uma única linha é inserida em `venda`
(`tipo_operacao = 'VENDA'`, valor default da coluna — sem itens/produto associado) com
`data_venda` = **menor `DATA_VENCIMENTO`** do grupo; todas as parcelas do grupo apontam pra essa
venda. Um cliente com dívida em 2 empresas gera 2 vendas sintéticas (uma por empresa).
> Efeito colateral aceito: essa venda aparece em Pesquisa de Vendas/Relatório de Vendas como uma
> venda comum, sem nenhum produto e valor calculado zero (não há `produto_movimento_detalhe`
> associado) — não há hoje um campo em `venda` pra marcá-la como "veio de importação".

**Parcela já paga:** se `DATA_RECEBIMENTO` vier preenchida, a linha nasce com
`documento_recebido = true`, `valor_recebido` conforme o arquivo; senão,
`documento_recebido = false`, `valor_recebido = 0`. **Em nenhum dos dois casos é criado
`caixa_detalhe`** — lançar caixa retroativo quebraria a conciliação "às cegas" do Fechamento de
Caixa em ciclos que nunca existiram no Niner. A parcela fica só historicamente marcada.

## Regras cross-cutting

- **Validação linha a linha, não tudo-ou-nada:** um arquivo com 500 linhas e 3 erradas importa as
  497 boas; as 3 erradas aparecem no relatório com o motivo, prontas pra corrigir e reenviar
  (só essas, ou o arquivo inteiro de novo — linhas já importadas por CPF/CNPJ/etc. não duplicam,
  pelas regras de dedup de cada tabela).
- **Log de lote (auditoria, P3):** cada execução confirmada grava uma linha em
  `importacao_lote` (tabela nova — ver "Impacto no banco"): tabela, nome do arquivo, usuário,
  data/hora, total de linhas, linhas importadas, linhas rejeitadas.
- **Acesso:** `ADMIN`-only, reforçado no backend (`403` pra `OPERADOR`), mesmo padrão de
  `ConfiguracaoTelaService`/`ConfiguracaoGeralController`.

## Critérios de aceitação (viram testes)

- Dado um CSV de clientes com um CPF já cadastrado no tenant, quando importado, então nenhuma
  linha nova é criada pra esse CPF (reaproveita o existente).
- Dado um CSV de clientes com um CPF inválido (dígito verificador errado), quando importado em
  modo prévia, então a linha aparece como rejeitada com o mesmo texto de erro do cadastro manual.
- Dado um CSV de fornecedores sem nenhum plano de contas escolhido na tela, quando enviado o
  arquivo, então a API rejeita a chamada (400) antes de processar qualquer linha.
- Dado um CSV de produto com 3 linhas do mesmo grupo (mesma descrição/marca/referência) e 2 delas
  com o mesmo par cor/tamanho de variação, quando importado, então nasce 1 produto com 2
  variações, e a quantidade de estoque das 2 linhas unificadas é somada.
- Dado um CSV de produto com `QUANTIDADE_ESTOQUE_1` preenchida e mapeada pra uma empresa, quando
  confirmado, então existe um `produto_movimento_mestre` tipo `AJUSTE` e `produto_estoque`
  reflete a soma via trigger (não via INSERT direto).
- Dado um CSV de contas a receber com um CPF que não existe em nenhum cliente do tenant, quando
  importado, então a linha é rejeitada e nenhuma `venda`/`contas_receber` é criada pra ela.
- Dado um CSV de contas a receber com 2 clientes diferentes na mesma empresa, quando importado,
  então nascem 2 vendas sintéticas (uma por cliente), cada uma com `data_venda` igual ao menor
  vencimento das parcelas daquele cliente.
- Dado um usuário `OPERADOR`, quando tenta acessar a rotina de importação, então recebe `403`.
- [x] caso feliz  [x] erro  [x] borda (dedup, unificação, divergência)  [x] o que NÃO deve
  acontecer (duplicar cliente com mesmo CPF; lançar caixa retroativo; gravar direto em
  `produto_estoque`)

## Impacto no contrato de API

```
GET  /api/v1/importacao/tabelas                     lista as tabelas elegíveis (chave, título, descrição)
GET  /api/v1/importacao/{tabela}/modelo              baixa o .csv de modelo (cabeçalho + exemplo)
POST /api/v1/importacao/{tabela}/processar           multipart: arquivo + escolhas prévias (JSON)
                                                      + confirmar=true|false (default false)
                                                      → relatório (linhas ok/erro/avisos); só
                                                      grava quando confirmar=true
POST /api/v1/importacao/produto/analise              só produto: multipart (só arquivo) → colunas
                                                      de estoque com dado (passo prévio ao
                                                      mapeamento coluna → empresa)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), `ADMIN`-only. Erros em Problem Details.

**Shape de `escolhas` (JSON livre, um por tabela):**
```
cliente:         { "idCategoriaCliente": 3 }  OU  { "novaCategoria": "VAREJO" }
fornecedor:      { "idPlanoContas": "3.03.001.000" }  OU
                 { "novoPlanoContas": { "codigo", "descricao", "tipoMovimento", "natureza" } }
contas_receber:  { "idCarteira": 5 }  OU
                 { "novaCarteira": { "nomeCarteira", "prazoPagamento", "pcMinima", "pcMaxima" } }
produto:         { "mapeamentoEmpresas": { "QUANTIDADE_ESTOQUE_1": 10, ... },
                   "rotulos": { "<chave do grupo>": { "linha": "Cor", "coluna": "Tamanho" } } }
```

**Implementação:** `api/src/main/java/com/vetor/niner/configuracao/importacao/` —
`ImportacaoController`/`ImportacaoService` (dispatcher genérico, ADMIN-only checado uma vez),
`ImportacaoCsv` (parser próprio, sem dependência nova), `ImportadorDeTabela` (interface) +
`ClienteImportador`/`FornecedorImportador`/`ProdutoImportador`/`ContasReceberImportador` (um bean
Spring por tabela, descobertos automaticamente via `List<ImportadorDeTabela>`).
Frontend: `web/src/pages/importacao/ImportacaoDadosPage.tsx` (wizard único pras 4 tabelas) +
`web/src/lib/importacao.ts`.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `configuracao.importacao`**
  - Objetivo: trazer cadastros e saldo de crediário de um sistema anterior pro Niner.
  - Passos: (1) escolha a tabela; (2) baixe o modelo `.csv`; (3) preencha fora do sistema; (4)
    envie o arquivo; (5) resolva as escolhas prévias pedidas; (6) revise a prévia (linhas
    ok/erro/avisos); (7) confirme.
  - Erros comuns: "linha rejeitada" → veja o motivo no relatório, corrija só aquela linha;
    "cliente duplicado" → o CPF já existia, o sistema reaproveitou em vez de duplicar.
  - `url_video`: `NULL` por ora.

## Impacto no banco

Migration nova (não altera nenhuma tabela existente — o desenho final evitou isso de propósito):
```sql
importacao_lote(id_lote PK, id_tenant FK, tabela text, nome_arquivo text, id_usuario FK,
                 total_linhas integer, linhas_importadas integer, linhas_rejeitadas integer,
                 criado_em timestamptz DEFAULT now())
```
RLS por `id_tenant`, mesmo padrão de todo o domínio.

## Impacto nas integrações

Nenhum.

## Ajustes de UI pós-implementação (2026-08-06, mesmo dia)

Revisão manual da tela apontou 2 problemas, corrigidos na hora:
- **Botões de transição de etapa com tamanho/alinhamento inconsistente** entre etapas (o texto
  variava — "Voltar"/"Continuar"/"Ver prévia"/"Confirmar importação" — e cada linha de botões
  tinha seu próprio `style` inline). Corrigido com uma classe única, `.wizard-acoes`
  (`web/src/styles.css`), com `min-width` fixo nos botões, usada em toda etapa; os cards de
  escolha de tabela (etapa 1) ganharam `.wizard-tabela-btn` pra esticar até a mesma altura na
  mesma linha do grid.
- **Subtítulo da tabela "grudado" ao voltar:** o botão "Voltar" da etapa de arquivo só trocava de
  etapa (`setEtapa('tabela')`) sem limpar a tabela escolhida — o nome dela continuava aparecendo
  embaixo de "Importação de Dados" mesmo de volta na grade de escolha. Corrigido chamando
  `reiniciar()` (limpa tudo) nesse botão específico.

## Non-goals desta feature

- Importar as demais tabelas do domínio (fica pra rodadas futuras, avaliadas uma a uma — ver
  conversa de descoberta que precedeu esta spec).
- Editar/corrigir dados diretamente na tela de prévia (a correção é sempre no arquivo, fora do
  sistema, e reenvio).
- Importar fotos de produto.
- Marcar a venda sintética de `contas_receber` como "veio de importação" (sem campo hoje pra
  isso — ver nota na seção da tabela).
- Suporte a outro delimitador/encoding além do definido (pode virar feature futura se aparecer
  demanda real).
- **Testes automatizados** — a feature foi implementada e testada manualmente (compilação limpa
  do backend, typecheck limpo do frontend, endpoints conferidos exigindo autenticação), mas ainda
  não tem suíte JUnit própria (`*ImportadorTest`) cobrindo os critérios de aceitação abaixo.

## Questões abertas

Nenhuma bloqueante — todas as decisões de negócio foram fechadas com o dono do produto antes
desta spec.

## Métrica de sucesso

Um lojista com 500 clientes, 50 fornecedores, 300 produtos (com variação) e 200 parcelas de
crediário em aberto migra tudo em menos de 30 minutos, sem precisar de suporte técnico da Vetor.
