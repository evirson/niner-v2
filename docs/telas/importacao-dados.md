# Spec: Rotina de Importação de Dados                  Status: Implementada
Autor: Claudio Calixto (dono do produto) + Claude · Data: 2026-08-06, reformulada 2026-08-10,
reformulada de novo 2026-08-10, correções e melhorias 2026-08-10 · Módulo(s): `configuracao`
(importacao) · Fase: 1 — Núcleo do ERP

## Problema

Quando um lojista contrata o Niner, às vezes já opera outro sistema (planilha ou ERP concorrente) e
precisa trazer os dados que já tem — clientes, fornecedores, catálogo, estoque inicial e o saldo
devedor de crediário — sem redigitar tudo na mão. Hoje não existe nenhum caminho de carga inicial: o
único jeito de popular o tenant é usar as telas de cadastro uma linha por vez.

## Solução proposta

Uma rotina de importação por **planilha Excel** (`.xlsx`/`.xls`), dentro do grupo **Configurações** (`ADMIN`-only — é uma
operação de risco, mistura com dado real do tenant), cobrindo **5 tabelas**: `cliente`,
`fornecedor`, `produto`, `estoque` (saldo inicial de produtos já importados) e `contas_receber` (só
crediário).

> **Reformulada em 2026-08-10 (pedido do dono do produto) — hub com um botão/tela por tabela, não
> mais uma tela única multi-arquivo.** O item "Importação de Dados" virou um grupo de menu com 5
> telas filhas, uma por tabela, **nesta ordem**: **Clientes → Contas a Receber → Fornecedores →
> Produtos → Estoque Inicial**. Cada tela cuida de **um arquivo por vez**: escolher o arquivo →
> escolhas prévias (quando a tabela exigir) → **Validar** (dry-run) → **Importar**. Não há mais
> detecção automática de tipo de arquivo nem múltiplos arquivos numa mesma tela — cada tela já sabe
> sua própria tabela. A ordem dos botões **é a própria ordem de dependência**: Contas a Receber
> precisa de Cliente já importado, Estoque Inicial precisa de Produto já importado, e ambas vêm
> depois na lista. Como cada tabela agora é confirmada na sua própria tela (não faz mais parte de um
> lote), **a lógica de "pendência"/"conferido na importação" da versão de 2026-08-10 deixou de
> existir** — validar Estoque Inicial sem ter importado Produtos antes mostra o erro real na hora
> ("Nenhum produto importado com CODIGO_PRODUTO..."), sem fingir "pendente". A versão de 2026-08-10
> (tela única com detecção automática) está preservada abaixo só como contexto histórico onde citada.

Desenhada pra crescer: cada tabela é um módulo de importação independente no backend (mesma
infraestrutura de upload/relatório/log), então liberar uma 6ª tabela no futuro não deveria exigir
reformar o que já existe — só implementar `ImportadorDeTabela`, registrar o bean e acrescentar mais
uma tela filha ao grupo do menu.

**Menu:** grupo **"Importação de Dados"** dentro do grupo **"Configurações"** (`adminOnly: true`),
hub em `/menu/importacao-dados` com 5 cards (Clientes, Contas a Receber, Fornecedores, Produtos,
Estoque Inicial), cada um levando à sua própria rota (`/importacao-dados/clientes`,
`/importacao-dados/contas-receber`, `/importacao-dados/fornecedores`, `/importacao-dados/produtos`,
`/importacao-dados/estoque`).

## User stories

- Como **ADMIN**, quero abrir a tela de uma tabela por vez (Clientes, Contas a Receber,
  Fornecedores, Produtos ou Estoque Inicial) e trazer o arquivo daquela tabela sem me preocupar com
  as outras.
- Como **ADMIN**, quero validar o arquivo antes de importar, e só liberar a importação de verdade
  quando tudo estiver certo.
- Como **ADMIN**, quero trazer meu catálogo de produtos com a grade de tamanhos de cada um, e depois
  trazer o estoque inicial de cada variação (cor/tamanho) por loja, numa tela separada.
- Como **ADMIN**, quero trazer o saldo devedor de crediário dos meus clientes, pra não perder
  cobrança em aberto na migração.
- Como **ADMIN**, quando uma linha do arquivo tem um erro (documento inválido, referência a algo
  que não existe), quero ver exatamente qual linha e por quê, corrigir só ela e reenviar — sem
  perder as linhas que já estavam certas.

## Fluxo geral da tela (2026-08-10)

Cada uma das 5 telas filhas segue o mesmo fluxo, sempre para **um arquivo por vez**:

1. **Baixar o modelo** (opcional) — botão no topo da tela baixa a planilha `.xlsx` de modelo daquela tabela.
2. **Selecionar o arquivo** — botão "Escolher planilha" (`<input type="file">` oculto por trás,
   sem `multiple`). Ao escolher, a tela chama `POST /detectar` e confere se o tipo identificado bate
   com a tabela desta tela (ver "Formato do arquivo" abaixo) — se não bater, o arquivo é rejeitado
   ali mesmo, com mensagem clara, antes de qualquer escolha prévia ou validação linha a linha.
3. **Escolhas prévias** (só quando a tabela exigir): categoria de cliente, plano de contas do
   fornecedor, carteira de crediário, ou mapeamento de coluna de estoque → empresa. `produto` não
   pede nenhuma.
4. **Validar** — dry-run (`confirmar=false`): mostra quantas linhas ficariam prontas, quantas já
   existem (ignoradas) e quantas têm erro, com o motivo linha a linha.
5. **Importar** — só libera depois de validado sem erro real; grava o arquivo de verdade
   (`confirmar=true`).
6. **Nova importação** — depois de importar (ou de uma falha), limpa a tela para trazer outro
   arquivo da mesma tabela, sem sair da tela.

Tecnicamente, os passos 4 e 5 continuam sendo a **mesma chamada de API**
(`POST /api/v1/importacao/{tabela}/processar`), só variando `confirmar=false`/`true` — evita guardar
estado de sessão no servidor (API stateless, P4).

**Implementação da simulação (`confirmar=false`):** cada `ImportadorDeTabela.processar(...)` é
`@Transactional` e roda o código de verdade (mesmo `criar()` do cadastro manual) sempre — a
diferença é que, no final, se `confirmar=false` **ou** sobrou alguma linha com erro, lança
`SimulacaoConcluidaException` (carregando o relatório já montado) só pra acionar o rollback
automático do Spring. `ImportacaoService` captura essa exceção e devolve o relatório sem que nada
tenha sido persistido.

**"Tudo ou nada" — `confirmado` pode vir `false` mesmo com `confirmar=true`:** um arquivo com
QUALQUER linha com erro real não grava NADA, mesmo pedindo `confirmar=true` — `concluir()`
(`ImportacaoDtos`) só marca `confirmado=true` quando `confirmar=true` **e** a lista de erros está
vazia. **Achado real de bug (2026-08-10, ainda válido na versão atual):** a tela precisa
checar `relatorio.confirmado` explicitamente antes de considerar a importação bem-sucedida — só
checar "a chamada não lançou exceção" não basta, porque uma chamada com `confirmar=true` e linhas
com erro retorna `200 OK` normalmente, só que com `confirmado: false` e nada persistido. A 1ª versão
da tela única tinha esse bug (mostrava "Importação concluída" com contagens de linhas mesmo sem nada
ter sido gravado, descoberto testando com o arquivo real de Estoque, que tinha 357 linhas
referenciando produtos inexistentes) — corrigido então em `importarTudo()`, e mantido na função
`importar()` de `ImportacaoTabelaPage.tsx` (2026-08-10): se `!relatorio.confirmado`, marca a tela
como `'falha'` e mostra os erros reais, sem considerar nada importado.

### Histórico (versão 2026-08-10, superada) — detecção automática pra escolher a tabela, e dependência na mesma leva

A versão de tela única (2026-08-10, substituída no mesmo dia) tinha `POST /api/v1/importacao/detectar`
(similaridade de Jaccard entre o cabeçalho do arquivo e `ImportadorDeTabela.colunasEsperadas()`) para
identificar o tipo de cada arquivo sem o usuário escolher manualmente, e uma lógica de "pendência"
para não mostrar erro quando `estoque`/`contas_receber` eram validados antes de `produto`/`cliente`
terem sido confirmados **na mesma leva de arquivos**. Nenhuma das duas existe mais: cada tabela agora
é sua própria tela, com sua própria importação confirmada imediatamente — não há mais "leva" pra
resolver, e a tela não precisa mais adivinhar qual tabela é o arquivo (ela já sabe, é a própria tela).

### `POST /detectar` voltou a ser chamado pelo frontend — agora pra validar, não pra adivinhar (2026-08-10, mesmo dia)

Corrigido um problema real relatado pelo dono do produto: nada impedia escolher, por exemplo, a
planilha de Fornecedores na tela de Importar Clientes — só na hora de "Validar" apareciam erros sem
sentido (`RAZAO_SOCIAL` interpretado como se fosse `NOME`, etc.), tarde demais pra o usuário entender
o que houve. Agora, assim que um arquivo é escolhido (`onSelecionarArquivo`,
`ImportacaoTabelaPage.tsx`), a tela chama `POST /api/v1/importacao/detectar` — o mesmo endpoint e a
mesma detecção por Jaccard de 2026-08-10 — e compara o `tabela` detectado com a tabela **da própria
tela** (não mais pra preencher um seletor, é uma checagem binária: bate ou não bate). Não bate →
mensagem clara ("Essa planilha parece ser de \"X\", não de \"Y\". Escolha o arquivo correto." ou,
se a detecção não teve confiança suficiente, um aviso genérico pra conferir as colunas), o arquivo
**não é aceito** (`arquivo` continua `null`, sem "Arquivo selecionado" nem escolhas prévias, botão
Validar continua desabilitado) — barra ali mesmo, antes de qualquer chamada de validação linha a
linha. Bate → segue o fluxo normal. `GET /tabelas` segue existindo no backend sem chamador (só
`/detectar` voltou a ser usado).

### SAVEPOINT por linha — sem isso, uma linha ruim quebrava as seguintes em cascata (2026-08-10)

**Bug real de produção, achado validando uma planilha de Produtos:** a tela mostrava "current
transaction is aborted, commands ignored until end of transaction block" (Postgres `25P02`).
Causa: todo `ImportadorDeTabela.processar()` roda o arquivo inteiro numa única `@Transactional`; o
`catch (RuntimeException e)` de cada linha pega a exceção em **Java**, mas se ela veio de uma
violação real no banco (constraint, overflow numérico etc., não só validação de campo), a conexão
Postgres fica "abortada" e **toda linha seguinte falha em cascata**, mesmo sendo válida.
Reproduzido de propósito (planilha de Produto com `PESO_BRUTO` estourando `numeric(14,3)` no meio
do arquivo) pra confirmar o diagnóstico antes de corrigir.

**Corrigido com SAVEPOINT por linha:** nova classe `ImportacaoSavepointExecutor`
(`TransactionTemplate` com `PROPAGATION_NESTED`), usada pelos **5 importadores** — não só Produto,
o mesmo bug latente existia em Cliente/Fornecedor/ContasReceber/Estoque, só não tinha sido
reproduzido ainda. Exige `nestedTransactionAllowed=true` no transaction manager (setado em
`TenantConfig` — o `TenantAwareTransactionManager` customizado de P8 não precisou de mudança:
`doBegin` não é re-executado numa transação `NESTED`/savepoint, então `app.id_tenant` não é
reafetado no meio do arquivo). Testado ao vivo: arquivo com 4 linhas, a 2ª propositalmente
estourando o banco → só ela rejeitada, as outras 3 importadas normalmente (antes do fix, as 2
seguintes também quebrariam com o erro genérico de transação abortada).

### Cache local contra consulta redundante por linha (N+1) — 2026-08-10

**Segunda causa real do "travado" em arquivo grande** (a primeira foi a leitura em DOM, ver
"Formato do arquivo" abaixo): `ContasReceberImportador` busca o cliente pelo CPF **numa consulta
por linha**, sem guardar o resultado. Numa planilha com o mesmo CPF repetido em centenas de
milhares de parcelas (o caso normal — várias parcelas da MESMA pessoa), isso vira uma consulta
redundante ao banco por linha. Confirmado ao vivo via `pg_stat_activity`: a mesma query
`SELECT id_cliente FROM cliente WHERE cpf_cnpj = $1` rodando havia mais de 1 minuto, disparada 300
mil vezes em sequência.

Corrigido com **cache local por chamada** (nunca campo de classe — os importadores são `@Service`
singleton do Spring, compartilhados entre requisições/tenants) em 3 importadores com o mesmo
padrão de valor repetido:
- `ContasReceberImportador` — cliente/empresa (`HashMap` simples).
- `EstoqueImportador` — produto/cor/tamanho (cor/tamanho só abrem `SAVEPOINT` quando a chave NÃO
  está em cache, pra não gastar savepoint à toa num acerto de cache).
- `ProdutoImportador` — tamanho da grade (`TAMANHO_1..20` se repete em quase todo produto do
  arquivo).

`ClienteImportador`/`FornecedorImportador` não precisaram — o dedup lá é pelo documento da
**própria** linha, tipicamente distinto linha a linha, sem redundância pra cachear.

**Testado ao vivo** (300 mil linhas, mesmo CPF repetido em todas): **3min25s pra terminar** (antes:
mais de 1 minuto só na primeira fase, sem previsão de terminar). Ainda não é instantâneo nessa
escala extrema — a fase de gravação em si (um `INSERT` por parcela, cada um sob seu próprio
`SAVEPOINT`) ainda pesa; se isso se mostrar devagar demais na prática, o próximo passo seria
agrupar os `INSERT`s em lote por venda (JDBC batch), mas isso muda a granularidade do relato de
erro (uma parcela ruim passaria a invalidar as outras do mesmo grupo) — decisão de produto, em
aberto, não feita sem perguntar.

### 2ª rodada de otimização — pré-busca em lote na gravação do Estoque (2026-08-10, mesmo dia)

O fix acima cobriu a 1ª fase (leitura/resolução) do `EstoqueImportador`, mas a 2ª fase (criar a
variação/código de barras por grupo produto+cor+tamanho) ainda fazia até 4 consultas **por grupo**
— `buscarIdGrade`, `buscarPorCombinacao`, `criar` (INSERT + SELECT de volta) e a checagem de EAN —
sem cache possível ali, porque cada grupo já é uma combinação única (o agrupamento da 1ª fase já
elimina repetição). Achado testando com o arquivo real do dono do produto: **22.483 linhas
levando 7min46s**, mesmo depois do fix de N+1 acima.

**Corrigido com pré-busca em lote (não cache — não há repetição pra cachear dentro do mesmo grupo,
o ganho vem de trocar milhares de idas-e-voltas por 2 consultas totais):**
- `ProdutoBarraService.buscarGradesEmLote(idsProduto)` — 1 `SELECT ... WHERE id_produto IN (...)`
  pros produtos tocados pelo arquivo (`id_grade` de cada um), em vez de 1 por grupo (o mesmo
  produto se repete em várias combinações cor/tamanho).
- `ProdutoBarraService.buscarVariacoesEmLote(idsProduto)` — idem pra variações já cadastradas
  (`produto_barra`), incluindo o `ean` já gravado — elimina também a consulta separada de
  "já tem EAN?" da 2ª fase, que agora só olha o valor já trazido no pré-fetch.
- `ProdutoBarraService.criarParaImportacaoEmMassa(...)` — cria a variação sem o SELECT de volta
  extra que `criar()` faz pra devolver o objeto completo (produto/cor/tamanho já resolvidos, a
  importação em massa não precisa disso) — 1 round-trip a menos por variação nova.
- `EstoqueImportador.processarGrupo` foi reescrito pra usar os dois mapas pré-buscados em vez de
  chamar `ProdutoBarraService.obterOuCriar` por grupo. A Emissão de Etiqueta (uso interativo,
  1 variação por vez) **não muda** — continua chamando `obterOuCriar`, que ainda existe e tem o
  mesmo comportamento de sempre.

**Testado ao vivo, comparação justa (banco vazio antes e depois, arquivo real de 22.483
linhas):**

| | Antes | Depois |
|---|---|---|
| Validar (dry-run) | 7min46s | **59,7s** (~7,8x mais rápido) |
| Importar de verdade (`confirmar=true`) | (não medido) | 4min41s |

Corretude idêntica nos dois casos (22.483/22.483, 0 erros). **Achado ainda em aberto:** a gravação
de verdade (COMMIT) ficou bem mais lenta que a simulação (que só faz ROLLBACK no final) — 4min41s
contra 59,7s no mesmo arquivo. Não investigado a fundo ainda (suspeita: custo de commit/fsync
numa transação desse tamanho, não um problema de corretude — a contagem final bateu certinho); se
isso incomodar na prática, vale investigar depois.

### Progresso "ao vivo" (registro atual/total) nas 3 etapas — leitura, validação, importação (2026-08-10)

Pedido do dono do produto: as gauges de leitura/validação/importação mostrarem também o total de
registros e o registro atual, não só um anel girando sem número.

- **Total: contado no NAVEGADOR** assim que o arquivo é escolhido, antes de clicar em
  Validar/Importar — `contarLinhasPlanilha()` em `web/src/lib/importacao.ts`, usando `readSheet` de
  `read-excel-file/browser` (novo pacote, roda em Web Worker, não trava a UI mesmo em arquivo
  grande). Exato e instantâneo — testado com planilha de 15 mil linhas, mostrou "15.000
  registro(s)" na hora.
- **Registro atual: reportado pelo BACKEND, consultado por polling** — `GET
  /api/v1/importacao/progresso/{idProgresso}` a cada 400ms enquanto `POST /processar` está em voo.
  `idProgresso` é gerado pelo frontend (`crypto.randomUUID()`) a cada clique em Validar/Importar.
- **Arquitetura do backend** — mesmo padrão de `TenantContext` (P8): `ImportacaoProgressoContext`
  usa `ScopedValue` (Java 25) ligado só durante `ImportacaoService.processar()`, lido por código
  profundo (`ImportacaoPlanilha`, cada `ImportadorDeTabela`) **sem** precisar passar o objeto por
  parâmetro em toda assinatura — só chamadas estáticas `etapa(nome, total)`/`avancar()`, no-op se
  não há progresso ligado. O `ProgressoImportacao` (mutável, `AtomicInteger`/`volatile`) também
  fica registrado num `ImportacaoProgressoRegistry` (`ConcurrentHashMap` em memória, sem
  fila/worker) sob o mesmo id — é isso que permite a requisição de POLLING (outra thread) ler o
  progresso; removido no `finally` de `processar()`.
- **3 etapas**, nesta ordem, dentro da MESMA chamada de `/processar`: `"leitura"` (dentro de
  `ImportacaoPlanilha` — total sai de graça, sem processar célula nenhuma) → `"validando"` ou
  `"importando"` (dependendo de `confirmar`, contado no loop principal de cada importador).
- `GaugeProgresso.tsx` ganhou `atual`/`total` opcionais — quando presentes (`total > 0`), mostra o
  percentual REAL + "X de Y registro(s)"; sem eles (ex. `/detectar`, que só lê o cabeçalho, não tem
  "registro" pra contar), continua no modo simulado antigo (sobe suavemente até 92%, sem fingir
  uma precisão que não existe nesse caso).
- Rótulo do gauge durante validando/importando vem da `etapa` reportada pelo backend
  (`ROTULO_ETAPA` em `ImportacaoTabelaPage.tsx`), não só do `status` local da tela — evita mostrar
  "Validando…" enquanto o backend na verdade ainda está lendo a planilha inteira.

Testado ao vivo no navegador com 15 mil linhas: gauge saltando 2% → 33% → 63% → 85% → 100% em
tempo real, "X de 15.000 registro(s)" embaixo do anel.

## Formato do arquivo

**Planilha Excel nativa — `.xlsx` ou `.xls` (2026-08-10, substituiu o formato CSV anterior)**, lida
via Apache POI (`org.apache.poi:poi-ooxml`, que também cobre `.xls` legado via HSSF). Sempre a
**primeira aba** do arquivo; cabeçalho na primeira linha. Enviar um arquivo em outro formato (ex.
um `.csv` antigo) devolve erro claro ("Não foi possível ler o arquivo — envie uma planilha Excel
(.xlsx ou .xls)."), não tenta processar.

**`.xlsx` lê em streaming (SAX), `.xls` legado lê em memória (DOM) — 2026-08-10.** Achado real
testando com a planilha de Contas a Receber do dono do produto (660.479 linhas): o modo antigo
(`WorkbookFactory`/`XSSFWorkbook`, DOM) monta o arquivo inteiro em memória ANTES de processar a
primeira célula — pra um arquivo desse tamanho, essa montagem é a maior parte do tempo gasto, e
fica "muda" pro contador de progresso (fica minutos "parado" sem feedback nenhum). `.xlsx` passou
a ler via `XSSFReader` + SAX (`XSSFSheetXMLHandler`, `ImportacaoPlanilha.lerXlsxEmStreaming`), que
processa uma linha do XML por vez — progresso real desde a primeira linha, bem mais rápido e mais
leve em memória (testado: 300 mil linhas/9MB, leitura completa em segundos; detecção do tipo de
arquivo, que só lê a 1ª linha, em 0,28s). `.xls` continua em DOM — o formato BIFF8 trava em 65.536
linhas por conta própria (limite do formato, não do Niner), então nunca sofre desse problema.
Decisão de rota é pela assinatura do arquivo (ZIP `PK\x03\x04` = `.xlsx`, senão DOM), não pela
extensão do nome. Célula com data formatada de exibição diferente de `dd/mm/aaaa` (ex. formato
americano `m/d/yyyy` na planilha de origem) continua normalizando pro formato interno correto nos
dois modos — testado com 3 formatos de exibição diferentes na mesma planilha.

Célula tipada é normalizada de volta para o mesmo texto que as regras de validação já esperavam do
CSV, então nenhuma regra de campo mudou: célula numérica formatada como data vira `dd/mm/aaaa`;
célula numérica comum vira texto plano com ponto decimal (sem separador de milhar, sem notação
científica); célula de texto com data ou número digitado também é aceita, no formato `dd/mm/aaaa`
(nunca ISO) ou decimal com **vírgula ou ponto**; célula booleana (checkbox Excel) ou texto
`SIM`/`NAO`/`S`/`N` (vazio = default da coluna). Toda validação de campo (CPF/CNPJ, e-mail, celular,
peso líquido ≤ bruto, regra da oferta tudo-ou-nada etc.) **reaproveita exatamente as mesmas regras
e mensagens** dos `Service.validar()` já existentes em cada cadastro manual — não duplica lógica.

**Sem limite de tamanho de arquivo/requisição (2026-08-10, pedido explícito do dono do produto):**
`spring.servlet.multipart.max-file-size`/`max-request-size` em `application.yml` valem `-1`
(ilimitado) — antes eram `10MB` (herdado do limite de upload de foto de produto, ADR-013), o que
rejeitava (`413`) arquivos de migração maiores, tipicamente `contas_receber`/`estoque` com dezenas
de milhares de linhas. **Achado colateral do mesmo bug:** um `413` do Tomcat é rejeitado antes de
passar pelo filtro de CORS, então o navegador nem consegue ler o corpo do erro — o front caía no
fallback genérico "Ocorreu um erro." em vez do motivo real. Não há também nenhum limite de número de
linhas no parser (`ImportacaoPlanilha.ler`) — processa o arquivo inteiro, por maior que seja
(`.xlsx` em streaming, ver "Formato do arquivo" acima — testado com centenas de milhares de linhas).

**Duas proteções do Apache POI contra "zip bomb" davam falso positivo em planilha grande e
legítima (2026-08-10):** a razão mínima de inflação (recusa se o XML descompactado for grande
demais em relação ao `.xlsx` comprimido — comum em planilha com muita célula e pouco texto por
célula) e o tamanho máximo de array de bytes ao ler uma entrada do zip. Achado real: a planilha de
Contas a Receber (~700 mil linhas) falhava com "Não foi possível ler o arquivo" — a causa real
ficava escondida pelo catch genérico. Corrigido desligando a razão mínima
(`ZipSecureFile.setMinInflateRatio(0)`) e aumentando o teto de array de bytes
(`IOUtils.setByteArrayMaxOverride`) num bloco `static` de `ImportacaoPlanilha` — coerente com a
decisão de não ter limite de arquivo/linhas. O catch genérico também passou a logar a exceção real
(`LOG.warn`), não só devolver a mensagem amigável — facilita diagnóstico se acontecer de novo.

## Especificação por tabela

### 1. `cliente`

**Colunas da planilha:** `NOME, TIPO_PESSOA, CPF_CNPJ, RG_IE, DATA_NASCIMENTO, GENERO, EMAIL, TELEFONE,
ENDERECO, NUMERO, COMPLEMENTO, BAIRRO, CIDADE, ESTADO, CEP, LIMITE_CREDITO`. Fora do escopo desta
importação: `whatsapp`, `instagram`, `facebook`, `tiktok` (ficam `NULL`; editáveis depois pela
tela normal).

**Escolha prévia:** uma **categoria** (existente, escolhida num select; ou nova, cadastrada na
hora) — aplicada a **todos** os clientes deste arquivo.

**Resolução por CPF/CNPJ (chave de ligação com a planilha de Contas a Receber):**
- `CPF_CNPJ` preenchido **e já existe** um cliente com esse documento no tenant → **não insere**;
  a linha conta como "já existia" no relatório (o cliente existente é o que vale pra ligação com
  `contas_receber`).
- `CPF_CNPJ` preenchido e **não existe** → cadastra normalmente.
- `CPF_CNPJ` vazio → sempre cadastra um cliente novo (aviso fixo na tela: reenviar o mesmo arquivo
  duplica esses clientes, já que não há como o sistema saber que "é o mesmo").

Validação de PF/PJ, gênero obrigatório pra PF, datas não-futuras etc. — mesmas regras de
`ClienteService.validar`.

### 2. `fornecedor`

**Colunas da planilha:** `RAZAO_SOCIAL, NOME_FANTASIA, CNPJ, INSCRICAO_ESTADUAL, EMAIL, TELEFONE,
ENDERECO, NUMERO, BAIRRO, CIDADE, ESTADO, CEP`.

**Escolha prévia:** um **plano de contas** (existente, escolhido via `SeletorPlanoContas` —
busca por código ou por nome, 2026-08-13; ou novo, cadastrado na hora) — aplicado a **todos** os
fornecedores deste arquivo (`fornecedor.id_plano_contas` é `NOT NULL` e o layout não tem essa
coluna, já que o sistema antigo normalmente não tem essa classificação).

**Dedup:** mesma regra do cliente, com `CNPJ` em vez de CPF/CNPJ — já existe → não insere,
reaproveita. Só compara quando o `CNPJ` da linha é, ao mesmo tempo, preenchido **e válido**
(2026-08-10) — um CNPJ inválido não entra na comparação (o valor gravado vai ser vazio mesmo, ver
abaixo), então é tratado como "sem CNPJ" pra fins de duplicidade: sempre cria novo.

**`TELEFONE` sem validação de formato (2026-08-10, pedido explícito do dono do produto):**
diferente do cadastro manual (`FornecedorService.criar`, que exige 10–11 dígitos), a importação
chama `criar(req, validarTelefone=false)` — uma sobrecarga nova em `FornecedorService` (o cadastro
manual, via controller, continua chamando `criar(req)`, que valida como sempre). Planilha migrada de
outro sistema costuma trazer telefone em formato livre (ramal, número incompleto) e isso não deve
travar a linha inteira; o valor é gravado como veio (só dígitos, mesma normalização de sempre), sem
a exigência de 10–11 dígitos.

**`EMAIL`/`CNPJ` inválidos não rejeitam a linha — entram em branco (2026-08-10, mesmo espírito do
TELEFONE acima).** Planilha migrada de outro sistema traz esses dois campos sujos/incompletos com
frequência (e-mail sem `@`, CNPJ com dígito verificador que não confere) — em vez de rejeitar a
linha inteira por causa disso, `FornecedorImportador.montarRequest` valida os dois com a mesma
regra do cadastro manual (`FornecedorService.emailValido`/`cnpjValido`, métodos `static` expostos
só pra isso, sem duplicar a regra) e grava `null` quando inválido. O usuário completa o dado depois
direto no cadastro do fornecedor, se quiser. Testado ao vivo: linha com CNPJ de dígito verificador
errado foi importada normalmente, com `cnpj: null` gravado.

### 3. `produto`

**Reformulado em 2026-08-10 (mudança de estrutura da planilha de origem) — substitui o formato
"achatado" anterior.** Agora **uma linha da planilha é um produto inteiro**, sem cor/EAN/estoque.

**Colunas da planilha:** `CODIGO_PRODUTO, MARCA, REFERENCIA, DESCRICAO, PRECO_CUSTO, PERCENTUAL_VENDA,
PRECO_VENDA, DATA_INICIO_OFERTA, DATA_FINAL_OFERTA, PRECO_OFERTA, CODIGO_NCM, TRIBUTACAO,
PESO_BRUTO, PESO_LIQUIDO, TAMANHO_1..TAMANHO_20` (até 20 colunas fixas de tamanho, em ordem — a
grade do produto).

**Sem escolha prévia nenhuma** — pula direto da etapa de arquivo para a prévia.

**Por linha:**
- Acha ou cria o produto (dedup por `DESCRICAO+MARCA+REFERENCIA` **e** `CODIGO_PRODUTO`
  — 2026-08-10, achado real testando com a planilha de verdade do dono do produto: duas linhas
  podem ter a mesma `DESCRICAO+MARCA+REFERENCIA` por coincidência de texto mas serem produtos
  DIFERENTES no sistema de origem, cada um com seu próprio `CODIGO_PRODUTO`. Só conta como "já
  existe" quando os três textos batem **e** o `codigo_importacao` também bate — comparação
  NULL-safe (`IS NOT DISTINCT FROM`), então duas linhas sem `CODIGO_PRODUTO` continuam caindo no
  critério antigo, comparando só pela descrição. Já existe → conta como "já existia", não recria,
  não atualiza.
- **`CODIGO_PRODUTO` → `produto.codigo_importacao` (coluna nova, 2026-08-10):** grava o código do
  sistema de origem direto na tabela `produto` (fora de `ProdutoRequest`/`ProdutoService.criar` — o
  cadastro manual não tem esse conceito), só quando informado. **Não é o `id_produto`** — é só a
  chave de ligação que a Importação de Estoque usa depois para achar o produto certo numa planilha
  separada. `UNIQUE (id_tenant, codigo_importacao)` parcial (só quando preenchido, mesmo padrão de
  `produto_barra.ean`). Editada só pela migration V017 (banco em construção, sem migration nova) —
  ver "Impacto no banco".
- Se o tenant usa cor/grade (`cfg_geral.cfg_usa_cor_grade`): lê `TAMANHO_1..20` (em ordem, ignorando
  colunas vazias) e acha/cria a grade correspondente, atribuindo a `produto.id_grade`.
  **"Já cadastrada" aqui é definido pelo CONTEÚDO da grade (a sequência ordenada de tamanhos), não
  por nome** — esta planilha não traz nome de grade nenhum (diferente do formato antigo, que exigia
  `NOME_GRADE`). `GradeService.obterOuCriarPorTamanhos` busca por igualdade slot-a-slot
  (`IS NOT DISTINCT FROM`, NULL-safe) contra `cfg_grade`; se não achar, cria com nome gerado a partir
  do primeiro e do último tamanho (ex. `GRADE 33-47`), com sufixo `(2)`, `(3)`... se colidir com o
  nome de outra grade de conteúdo diferente. Tamanhos novos entram em `cfg_tamanho`
  automaticamente. Sem nenhum `TAMANHO_N` preenchido → erro (`Informe ao menos um TAMANHO_N`).
- Quando o tenant **não** usa cor/grade, as colunas `TAMANHO_N` são ignoradas (produto nasce com 1
  SKU só, sem grade).

**Fora de escopo desta importação (2026-08-10) — variação, EAN e estoque inicial saíram daqui**,
ficam para a **Entrada de Produtos** (ainda não construída — é lá que a geração em lote de
combinações cor×grade nasce naturalmente, na compra, decisão já registrada em `docs/telas/produto.md`
2026-08-08). O saldo inicial de estoque tem sua própria tabela agora — ver seção 5 abaixo.

**NCM:** opcional — código que não existe em `cfg_produto_ncm` (ou vem em formato inválido) entra
como **vazio**, não rejeita a linha (2026-08-06). Antes de checar, tira toda pontuação (ex.
`6402.99.90` → `64029990`) — a tabela de referência guarda só os 8 dígitos.

**`TRIBUTACAO` → perfil fiscal do produto (2026-08-19).** Resolve por **nome** contra os perfis
fiscais padrão do tenant (`cfg_perfil_fiscal`, semeados no signup — `docs/telas/fiscal-perfil.md`),
não por código numérico fixo — ajustado no mesmo dia depois de testar com a planilha real do
usuário, que traz texto, não os códigos `1`/`2` assumidos na primeira versão:
- `NORMAL` (ou `1`) → perfil **"REVENDA TRIBUTADA NORMAL"**.
- `SUBSTITUICAO` / `SUBSTITUIÇÃO` / `ST` (ou `2`) → perfil **"REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA
  (ST)"**. Comparação sem acento/maiúscula-minúscula (`ProdutoImportador.resolverPerfilFiscal`).
- **Vazio** → perfil **"NÃO INFORMADO"** (terceiro perfil semeado no signup em 2026-08-19,
  `SignupService`, **sem regra fiscal nenhuma** de propósito — sentinela). O produto é importado
  normalmente (não rejeita a linha), mas fica sem tributação definida: a Conformidade Fiscal aponta
  ("perfil sem regra para o CRT", mecanismo que já existia) e a emissão de nota fiscal recusa
  (F11) até o usuário corrigir o perfil do produto. Um aviso agregado no relatório final lista
  quantos produtos (e em quais linhas, até 20 na amostra) ficaram sem `TRIBUTACAO` — "N produto(s)
  sem a coluna TRIBUTACAO preenchida... receberam o perfil fiscal "Não Informado" e não poderão
  emitir documento fiscal até você definir a tributação correta".
- **Qualquer outro valor** → erro de linha (`IllegalArgumentException`, cai no relatório de erro
  normal) — não é tratado como "tributação desconhecida" silenciosa.
- Se um dos 3 perfis padrão não existir no tenant (usuário editou/apagou o que o signup semeou), a
  importação **não trava**: os produtos que apontariam pra ele ficam sem perfil fiscal (`null`,
  mesmo comportamento de antes desta feature) e um aviso avisa uma vez só, no topo do relatório.
- **Testes:** `ProdutoImportadorCrudTest` (novo arquivo, 5 casos) — `NORMAL`/`SUBSTITUICAO`
  atribuem o perfil certo, minúsculo/acento/código numérico (`1`/`2`) são aceitos, vazio atribui
  "Não Informado" e gera o aviso agregado, valor fora do domínio rejeita a linha. **763/763 testes
  de backend verdes.**

**Regra da oferta** (tudo-ou-nada, `PRECO_OFERTA = "0"` tratado como "não preenchido") — mesma
lógica de antes.

### 4. `contas_receber` (só crediário)

**Colunas da planilha:** `CPF_CNPJ, EMPRESA, NUMERO_PARCELA, DATA_VENCIMENTO, DATA_RECEBIMENTO,
VALOR_RECEBER, VALOR_JUROS, VALOR_DESCONTO, VALOR_RECEBIDO`.

**Escolha prévia:** uma **carteira de crediário** (existente, do tipo `CATEGORIA_CARTEIRA =
CREDIARIO`; ou nova, cadastrada obrigatoriamente com essa categoria) — aplicada a todas as
parcelas deste arquivo.

**Resolução do cliente:** por `CPF_CNPJ`, consultando `cliente` **ao vivo no banco** — por isso a
tela de Clientes precisa ter sido usada (e confirmada) antes desta, mesmo que dias depois. CPF não
encontrado → linha **rejeitada**, cai no relatório de erro. Cliente sem CPF/CNPJ cadastrado não
tem como ter crediário importado por esta rotina (limitação conhecida, aceita).

**Resolução da empresa:** pelo `codigo_empresa` (o código curto da empresa, ex. `1`, `2`). Código
inexistente → linha rejeitada.

⚠️ **Não existe tela de cadastro de Empresas** — `EmpresaController` só expõe dois `GET`
(`/api/v1/empresas` e `/api/v1/empresas/permitidas`), consumidos pelos seletores de outras telas.
Para descobrir o código, o usuário tem duas saídas: **Rotina de Exportação de Dados** → tabela
**"Empresas"**, cuja primeira coluna é **"Código"** (`ExportacaoService.java:78-88`); ou qualquer
grid que já mostre a coluna Empresa por código — Transferência de Produtos e Recebimento de
Crediário, por exemplo.

**Venda sintética (contorna `contas_receber.id_venda NOT NULL` sem mexer no schema):** as linhas
são agrupadas por **`(cliente, empresa)`** — pra cada grupo, uma única linha é inserida em `venda`
(`tipo_operacao = 'VENDA'`, sem itens/produto associado) com `data_venda` = **menor
`DATA_VENCIMENTO`** do grupo; todas as parcelas do grupo apontam pra essa venda.

**Parcela já paga:** se `DATA_RECEBIMENTO` vier preenchida, a linha nasce com
`documento_recebido = true`; senão, `documento_recebido = false`, `valor_recebido = 0`. **Nunca**
cria `caixa_detalhe` — lançar caixa retroativo quebraria a conciliação "às cegas" do Fechamento de
Caixa.

### 5. `estoque` (novo, 2026-08-10) — tabela irmã de `produto`, sempre importada DEPOIS

**Colunas da planilha:** `CODIGO_PRODUTO, EAN_CODIGO_BARRAS, NOME_COR, NOME_TAMANHO,
QUANTIDADE_ESTOQUE_1..QUANTIDADE_ESTOQUE_5` (5 colunas fixas de quantidade, uma por empresa).

**Escolha prévia:** a qual **empresa** cada uma das 5 colunas de quantidade corresponde (select por
coluna) — igual ao mapeamento que existia no antigo formato de `produto`. **Não precisa preencher
as 5** (2026-08-10) — o backend (`EstoqueImportador.lerMapeamentoEmpresas`) já aceitava mapeamento
parcial, só a validação da tela exigia as 5 sem necessidade; agora basta **1** mapeada. Uma empresa
já escolhida numa coluna **some das opções das outras 4** (exclusão mútua no `<select>`,
`ImportacaoTabelaPage.tsx`) — impede escolher a mesma empresa duas vezes, e é justamente essa
mudança que tornou "exigir as 5" inviável pra tenant com menos de 5 empresas (não teria como
preencher todas sem repetir).

**Por linha:**
- Acha o produto pelo `CODIGO_PRODUTO`, consultando `produto.codigo_importacao` (**não** é o
  `id_produto`) — se não achar: `"Nenhum produto importado com CODIGO_PRODUTO \"X\" — importe a
  planilha de Produtos antes desta."` — a tela de Produtos precisa ter sido usada (e confirmada)
  antes desta.
- **Grade do produto decide se `NOME_COR`/`NOME_TAMANHO` são sequer lidos (2026-08-19, corrigindo
  um bug real de duplicidade — ver abaixo).** `EstoqueImportador` pré-busca em lote o
  `id_grade` de cada produto tocado pelo arquivo ANTES de olhar as colunas de cor/tamanho: se o
  produto não usa grade (`produto.id_grade = 1`, a sentinela PADRÃO; coluna `NOT NULL DEFAULT 1`,
  ver `db/migration/V017__catalogo.sql:196-199`), cor/tamanho são forçados a `null` **já na
  montagem do agrupamento**, sem sequer consultar `cfg_cor`/`cfg_tamanho` — a variação nasce
  direto com a **sentinela `1`** em ambos
  (`api/src/main/java/com/vetor/niner/catalogo/ProdutoBarraService.java:97-99`,
  `criarParaImportacaoEmMassa`). Só quando o produto TEM grade real é que `NOME_COR`/
  `NOME_TAMANHO` são lidos e viram `cfg_cor`/`cfg_tamanho` por find-or-create.
  > ⚠️ **Antes de 2026-08-19**, a força pro sentinela só acontecia DENTRO da criação da variação,
  > depois de já ter agrupado as linhas pelo texto cru de cor/tamanho — duas linhas do MESMO
  > produto sem grade com texto diferente (comum em planilha migrada: uma em branco, outra
  > "ÚNICO") formavam dois grupos que colidiam na mesma variação `(id_produto,1,1)`, batendo em
  > `duplicate key value violates unique constraint "produto_barra_variacao_uk"`. Corrigido —
  > detalhe completo em `docs/PROGRESSO.md`, entrada de 2026-08-19.
- **Não exige que o tamanho pertença à sequência da grade do produto (2026-08-10)** — achado real:
  planilha migrada trazia `NOME_TAMANHO="UN1"` numa grade que só tinha `"UN"`, e isso não é um erro
  de verdade nesse contexto, é só o sistema de origem sendo menos rígido. `ProdutoBarraService`
  ganhou um parâmetro `validarGrade` (`obterOuCriar(..., boolean validarGrade)`) — a importação
  chama com `false`; a Emissão de Etiqueta (cadastro manual, ação deliberada de quem está no
  balcão) continua exigindo que o tamanho pertença à grade, sem mudança de comportamento lá.
- Atualiza o EAN da variação se ela ainda não tiver um (não sobrescreve numa reimportação).
- Para cada coluna de quantidade mapeada com valor `> 0`, lança um **movimento de estoque tipo
  `AJUSTE`** (mesmo padrão do antigo formato de produto — nunca `INSERT` direto em
  `produto_estoque`, quem mantém esse saldo é a trigger `fn_atualiza_estoque_movimento`).

**Linhas duplicadas da mesma variação (mesmo produto/cor/tamanho) são agrupadas e as quantidades
somadas** antes de lançar o movimento — evita duplicar estoque se a mesma variação aparecer em mais
de uma linha do arquivo.

## Regras cross-cutting

- **Validação linha a linha, tudo-ou-nada por arquivo:** um arquivo com QUALQUER linha errada não
  importa NADA (2026-08-06, revisão do desenho original que importava as linhas boas e só
  rejeitava as ruins) — corrige a(s) linha(s) com erro e reenvia o arquivo inteiro.
- **Log de lote (auditoria, P3):** cada execução confirmada grava uma linha em
  `importacao_lote`: tabela, nome do arquivo, usuário, data/hora, total de linhas, linhas
  importadas, linhas rejeitadas.
- **Acesso:** `ADMIN`-only, reforçado no backend (`403` pra `OPERADOR`).

## Impacto no contrato de API

```
GET  /api/v1/importacao/tabelas                     lista as tabelas elegíveis (chave, título, descrição)
GET  /api/v1/importacao/{tabela}/modelo              baixa o .xlsx de modelo (cabeçalho + exemplo)
POST /api/v1/importacao/detectar                     multipart: só arquivo → { tabela|null, colunasArquivo }
POST /api/v1/importacao/{tabela}/processar           multipart: arquivo + escolhas prévias (JSON)
                                                      + confirmar=true|false (default false)
                                                      + idProgresso (opcional, 2026-08-10)
                                                      → relatório (linhas ok/erro/avisos); só
                                                      grava quando confirmar=true E erros vazio
GET  /api/v1/importacao/progresso/{idProgresso}      progresso "ao vivo" (2026-08-10), consultado
                                                      por polling enquanto /processar está em voo
                                                      → { etapa, atual, total }
```

**`GET /tabelas` segue existindo no backend, sem alteração, mas sem chamador no frontend** — cada
tela filha já sabe estaticamente sua própria tabela (título, ícone, ajuda), não precisa listar.
**`POST /detectar` voltou a ter chamador no frontend (2026-08-10, mesmo dia da troca pra Excel)** —
não mais pra escolher a tabela por conta do usuário (isso acabou junto com a tela única), e sim pra
**validar, assim que o arquivo é escolhido**, que a planilha bate com a tabela da própria tela (ver
seção "Formato do arquivo" acima) — barra ali, antes do usuário perder tempo com "Validar" numa
planilha errada.

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), `ADMIN`-only. Erros em Problem Details.
`POST /api/v1/importacao/produto/analise` (passo de análise de colunas de estoque do formato antigo
de produto) foi **removido em 2026-08-10** — o novo formato de produto não tem estoque nem precisa
de análise prévia nenhuma.

**Shape de `escolhas` (JSON livre, um por tabela):**
```
cliente:         { "idCategoriaCliente": 3 }  OU  { "novaCategoria": "VAREJO" }
fornecedor:      { "idPlanoContas": "3.03.001" }      OU
                 { "novoPlanoContas": { "codigo", "descricao", "tipoMovimento", "natureza" } }
contas_receber:  { "idCarteira": 5 }  OU
                 { "novaCarteira": { "nomeCarteira", "prazoPagamento", "pcMinima", "pcMaxima" } }
produto:         {}  (nenhuma escolha)
estoque:         { "mapeamentoEmpresas": { "QUANTIDADE_ESTOQUE_1": 10, ... } }
```

**Implementação:** `api/src/main/java/com/vetor/niner/configuracao/importacao/` —
`ImportacaoController`/`ImportacaoService` (dispatcher genérico + detecção, ADMIN-only checado uma
vez), `ImportacaoPlanilha` (leitura de planilha via Apache POI — `org.apache.poi:poi-ooxml`,
2026-08-10, substituiu o antigo parser de CSV; **streaming SAX pra `.xlsx` + DOM pra `.xls`,
2026-08-10**, ver "Formato do arquivo") `ImportadorDeTabela` (interface, com
`colunasEsperadas()` default) + `ClienteImportador`/`FornecedorImportador`/
`ProdutoImportador`/`ContasReceberImportador`/`EstoqueImportador` (um bean Spring por tabela,
descobertos automaticamente via `List<ImportadorDeTabela>`). **Novo em 2026-08-10:**
`ImportacaoSavepointExecutor` (savepoint por linha, ver seção própria acima),
`ImportacaoProgressoContext`/`ImportacaoProgressoRegistry`/`ProgressoImportacao` (progresso ao
vivo, ver seção própria acima). `ProdutoBarraService` (`api/.../catalogo/`, compartilhado com a
Emissão de Etiqueta) ganhou, também em 2026-08-10: `obterOuCriar(..., boolean validarGrade)`,
`buscarGradesEmLote`/`buscarVariacoesEmLote`/`criarParaImportacaoEmMassa` (pré-busca em lote, só
usados pela importação — ver "2ª rodada de otimização" acima).
Frontend (2026-08-10): grupo `importacao-dados` em `web/src/lib/menu.ts` (hub automático via
`/menu/:grupo` + `MenuGrupo.tsx`, sem página de hub própria) com 5 rotas filhas registradas em
`App.tsx`, todas renderizando `web/src/pages/importacao/ImportacaoTabelaPage.tsx` (um componente só,
parametrizado por `tabela`, um arquivo por vez) + `web/src/lib/importacao.ts` (2026-08-10 ganhou
`contarLinhasPlanilha`/`buscarProgressoImportacao`, e a dependência nova `read-excel-file`) +
`web/src/components/GaugeProgresso.tsx` (2026-08-10, ganhou modo de progresso real).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **5 `chave_tela`, uma por tela filha** (2026-08-10): `configuracao.importacao.clientes`,
  `.contasReceber`, `.fornecedores`, `.produtos`, `.estoque` — conteúdo completo em
  `AjudaDaTela.tsx`.
  - `url_video`: `NULL` por ora, nas 5.

## Impacto no banco

Migration `V030__importacao_dados.sql` (não altera nenhuma tabela existente):
```sql
importacao_lote(id_lote PK, id_tenant FK, tabela text, nome_arquivo text, id_usuario FK,
                 total_linhas integer, linhas_importadas integer, linhas_rejeitadas integer,
                 criado_em timestamptz DEFAULT now())
```
RLS por `id_tenant`, mesmo padrão de todo o domínio.

**Coluna nova em `produto` (2026-08-10, editada em `V017__catalogo.sql` — banco em construção, sem
migration própria):**
```sql
produto.codigo_importacao text  -- código do sistema de origem; NÃO é o id_produto
CREATE UNIQUE INDEX produto_codigo_importacao_uk ON produto (id_tenant, codigo_importacao)
  WHERE codigo_importacao IS NOT NULL;
```

**Upload sem limite (2026-08-10, editado em `application.yml`):**
```yaml
spring.servlet.multipart.max-file-size: -1
spring.servlet.multipart.max-request-size: -1
```

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- Importar as demais tabelas do domínio (fica pra rodadas futuras, avaliadas uma a uma).
- Editar/corrigir dados diretamente na tela de prévia (a correção é sempre no arquivo, fora do
  sistema, e reenvio).
- Importar fotos de produto.
- Marcar a venda sintética de `contas_receber` como "veio de importação" (sem campo hoje pra
  isso).
- Suporte a outro delimitador/encoding além do definido.
- Gerar variação (cor×tamanho)/EAN/estoque a partir da importação de Produtos — fica para a Entrada
  de Produtos (não construída) + a importação de Estoque (esta feature, seção 5).
- Importar mais de um arquivo da mesma tabela numa mesma visita à tela (fluxo é 1 arquivo → "Nova
  importação" → repete, sem sair da tela).
- **Testes automatizados** — a feature (incluindo as reformulações de 2026-08-10 (duas rodadas no mesmo dia)) foi
  implementada e testada manualmente (compilação limpa do backend, typecheck limpo do frontend,
  testes ponta-a-ponta via browser), mas ainda não tem suíte JUnit própria.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Um lojista com 500 clientes, 50 fornecedores, 300 produtos (com grade), o estoque inicial de cada
variação e 200 parcelas de crediário em aberto migra tudo em menos de 30 minutos, sem precisar de
suporte técnico da Vetor.
