# Pendências abertas do Nainer

> **O que é este arquivo.** Lista **viva e consolidada** de tudo que está aberto no produto, com
> **de quem é a bola** em cada item. É a fonte única de pendências — `docs/PROGRESSO.md` conta a
> história em ordem cronológica, este arquivo conta o que **ainda falta**.
>
> **Quando falar destas pendências:** só quando o dono do produto **pedir para ler a documentação
> e a memória** ou **perguntar diretamente o que está pendente**. Nunca despejar na abertura de uma
> conversa nem no fim de uma tarefa sem relação — ele recusou isso explicitamente em 2026-08-25.
>
> **Como apresentar:** resumido e **agrupado por dono**, não as ~27 linhas cruas — ele já reclamou
> de informação demais de uma vez (*"TA MUITO CONFUSO"*).
>
> **Última revisão: 2026-08-31.** Entrou o **módulo NFS-e** (V099–V102, sessão de outra máquina):
> S5 pronto no banco, S5.5 pela metade e o **motor do S6 construído — porém sem controller, sem
> tela e sem chamador**, e com a máquina de emissão ainda **sem nenhum teste**. Os três buracos
> estão em `docs/MODULONFSE.md` §1 e viraram os itens **72–74** aqui. No mesmo dia foi atacada a
> "bola minha" que restava: **24**, **25** e **48** ✅ **fechadas**; **19** fechada na parte que
> estava em dúvida (a partição rodou com 2.001 documentos) com o peso em memória **declarado como
> não medido**; e **22 (SVC) reclassificada** — não era só trabalho meu, existe decisão dele de
> 2026-08-24 escrita no código, e o item agora traz o dimensionamento para ele decidir.
> ⭐ Escrever a spec do Tipo de Carteira **achou um defeito real** (guard de exclusão com 2 das 5
> FKs), corrigido e provado por sabotagem.
>
> **Revisão anterior: 2026-08-30 — OITO rodadas de dois agentes** (front e back em paralelo), a
> pedido dele: *"8 rodadas de cada para ser preciso na varredura"*. ~40 correções, 11 commits,
> **1099 testes verdes** (o número do Maven; a contagem antiga, somando arquivos do Surefire,
> subestimava), migrations até **V098**.
> ⭐ **Em sete das oito rodadas o defeito mais grave era de uma correção MINHA da rodada anterior** —
> ver `docs/PROGRESSO.md` para a tabela rodada a rodada. Metade de cada rodada foi reauditoria, e
> foi de lá que veio quase todo o valor.
> 🔔 **Entraram três pendências novas** (**69**, **70**, **71**) e a **#68 foi ampliada** com o
> balanço arquivo-por-arquivo do que continua sem cobertura.
>
> **Revisão anterior:** 2026-08-29 — dia inteiro. Manhã: **cinco rodadas de dois agentes caçadores
> de bugs** (~55 correções). Tarde: as pendências que eram minha bola (**58**, **59**, **63**, parte
> do **56**). Noite: **ele decidiu as 12 pendências item a item** e mandou executar — fecharam
> **55**, **57**, **61**, **62**, **51**, **31**, **30** e **32**; entraram **60**–**62** e a **64**,
> que ele decidiu na mesma conversa (V095 — o fechamento exige a sangria).
>
> **Revisão de 2026-08-29 (fim do dia):** a NF-e 55 **passou a autorizar** — o `cStat 974` saiu da
> lista (item 1) e não há mais nada bloqueado em terceiros. No mesmo dia entrou e saiu um defeito
> novo: **NF-e 55 sem QR Code apagava a tela inteira** (papeleta e emissão) — corrigido e medido,
> ver `docs/telas/papeleta-venda.md`; sobrou dele só **confirmar pelo PDV**, listado abaixo.
>
> **Revisão de 2026-08-29 (madrugada) — a SEGUNDA leva de cinco rodadas.** A energia caiu e o micro
> reiniciou no meio da rodada 4 da primeira leva; ao retomar, ele pediu **mais cinco rodadas** dos
> dois agentes em paralelo. Deu **~75 correções**, em 6 commits. ⭐ O achado que vale mais que a
> lista: **metade do que as rodadas 3 e 5 encontraram eram defeitos das MINHAS correções das
> rodadas anteriores** — meia correção, correção que troca o sintoma de lugar, correção que piora o
> caso comum, correção que o diff prova e que não existe, e um guarda copiado sem `@Transactional`
> que passou a **liberar sempre**. Entraram quatro pendências: **#65** (impressão térmica),
> **#66** (ano da inutilização), **#67** (reimportar XML) e **#68** — o **limite conhecido**, que
> vale ler antes de confiar no resto.
>
> **Hoje:** **31 itens abertos**, e a maioria é verificação no papel/navegador que só ele pode
> fazer. Precisam de DECISÃO dele: **#28** (planos pagos, adiado por ele), **#40** e **#47**
> (segurança e LGPD, que ele mandou guardar para cobrar depois), **#66** (inutilização de exercício
> encerrado — pergunta para o contador). 🔔 E **#49** — ele prometeu as **credenciais da NFS-e para
> a segunda-feira**.

**Estado em 2026-08-31 (medido nesta data, não estimado):** **57 telas do ERP + 3 públicas**
(contagem de `docs/TELAS.md`, que declara a base) · migrations até **V102** · suíte medida pela
linha `Tests run:` do Maven, que é a autoridade (ver `feedback_exit_depois_de_pipe_mente`).

⚠️ **O dia 2026-08-31 trouxe o módulo NFS-e (V099–V102), feito em OUTRA sessão e noutra máquina** —
os commits registram *"5 erros pré-existentes do `ProdutoImagemCrudTest`, libwebp x86_64 num Mac
arm64"*, um artefato de ambiente que **não** ocorre nesta máquina. Ao comparar contagens entre
sessões, é preciso saber disso antes de chamar diferença de regressão.

⚠️ **Nota de método sobre a contagem anterior:** o 1052 registrado na revisão de 29/08 vinha de
somar os arquivos do Surefire, que **subestima**; o 1099 de 30/08 já é a linha do Maven. Os dois
números descrevem quase a mesma suíte medida de dois jeitos — não houve queda de cobertura entre
eles. ⚠️ E os "0 a 5 pulados conforme a hora" do guard de meia-noite do `HorarioAcessoTest`
continuam valendo: o número oscilar é o mecanismo funcionando, não regressão.

---

## 🔴 Bloqueadas em terceiros

*(Nenhuma nesta data — a única saiu em 2026-08-29; ver logo abaixo.)*

### ✅ 1. `cStat 974` — RESOLVIDO em 2026-08-29 (a NF-e 55 autoriza)
Medido no banco, não inferido: as NF-e **nº 24 e 25** (29/08, 16:42 e 16:44) voltaram
`cStat 100 — Autorizado o uso da NF-e`, com protocolo (`141260000529224`/`...225`). A última
rejeição por `974` foi a **nº 23**, em 27/08. O DANFE das duas abre e imprime.

⚠️ **O que resolveu NÃO foi medido.** Entre a última rejeição e a primeira autorização o
`verAplic` da SEFAZ/PR mudou de `PR-v4_10_06` para `PR-v4_10_12`, e o chamado estava aberto — mas
nada aqui prova qual das duas coisas destravou, e o cadastro do responsável técnico pode ter sido
ajustado do outro lado. Registrado como **fato observado**, não como causa estabelecida.

⛔ CSRT é segredo — nunca por chat; confere-se pelo tamanho da coluna cifrada
(`octet_length(decode(csrt_cifrado,'base64'))` = 64 ⇒ 36 caracteres em claro).

---

## 🔵 Bola dele (dono do produto)

### 5. ✅ Tela de Lucratividade — ABERTA em 2026-08-31, e tinha defeito
(O Relatório de Contas a Pagar foi aberto em 2026-08-26 e funciona.)

Aberta no navegador nesta data. ⭐ **A linha de Lucro Bruto estava invisível** — os dois cards de
seção herdavam o `flex: 1` do `.relatorio-corpo-fixo`, que silencia o `height: 100%` da
`.table-wrap`; a tabela de 5 linhas ocupava a altura de uma grade cheia e empurrava as seções
seguintes para fora. Corrigido com `.secao-fixa` (`flex: 0 0 auto` **+ `height: auto`** — só o
primeiro deixaria o `height: 100%` da classe base acordar e piorava, medido e revertido no ato).

⚠️ **Fica declarado que o resto da tela foi visto, não conferido conta a conta:** os números não
foram cruzados com a DRE nesta passagem.

### 6. Nenhum relatório foi IMPRESSO no papel depois da correção do tema do PDF (2026-08-26)
O PDF foi conferido na tela e por análise de pixels — papel é outra coisa.

### 7. O PDF com gráfico não foi conferido com a barra visível
A cor foi medida no **mecanismo** (`fill` computado: escuro → claro), mas em aba controlada por
automação o `requestAnimationFrame` é suspenso, a animação do recharts congela e a barra sai como um
tracinho — artefato do ambiente de teste, não do uso real. Basta gerar **um** PDF de um relatório
com gráfico e olhar.

### 8. Paginação de documento A4 não testada no papel
A correção de 2026-08-22 (`imprimirDocumentoA4()`) nunca foi confirmada impressa.

### 9. Horizontal da etiqueta 34×60 não confirmada no papel
Margem 3,00 / espaço 1,70 — números derivados, não medidos numa etiqueta impressa.

### 10. Papel do driver por rolo de etiqueta
Decisão dele: trocar o papel no driver a cada rolo × construir um **agente local de impressão**.
Não existe valor único que sirva a dois rolos, e a web não tem API de DEVMODE.

### 11. Cobrança da assinatura DESLIGADA
Ele tem a credencial de teste do Mercado Pago em mãos; é só ligar (`NINER_MP_ACCESS_TOKEN` /
`NINER_MP_WEBHOOK_SECRET`).

### 12. ~15 popups de TRABALHO ainda sem ✕
Filtros do Relatório, Nova cor, Forma de Pagamento… Os ~24 popups de **confirmação** ficam de fora
de propósito. Decisão dele.

### 13. "Painel" (`/`) está dentro de "Implementações Futuras" e é tela real
Decisão dele: promover ou manter.

### 14. Devolução não estorna comissão nem taxa
Decisão de negócio.


### 65. 🔵 Papeleta e comprovante de crediário podem estar cortando na impressão (rodada 1 da auditoria de 2026-08-29)
`.papeleta-imprimir` (`styles.css:412`) e `.comprovante-imprimir` (`:373`) são `position: absolute`
dentro de `.modal-overlay` (`position: fixed`). É o **mesmo mecanismo** que fez o documento A4
imprimir uma página e descartar o resto em 2026-08-22 — corrigido lá (`.documento-a4-imprimir`) e
na Guia de Transferência, e as **duas classes térmicas ficaram para trás**. Soma-se o
`html, body, #root { overflow: hidden }`, que **corta** em vez de paginar.

**O que deveria acontecer:** uma venda de **~25 itens** (cabeçalho ≈12 linhas + 2 por item + rodapé
≈12 → passa de 70 linhas) sairia no papel **sem TOTAL, sem as formas de pagamento e sem a linha de
agradecimento**. A pré-visualização na tela mostra tudo — o `<pre>` rola dentro do modal —, então
**só o papel responde**. Vale igual para o Comprovante de Crediário com muitas parcelas e para o
Fechamento de Caixa com muitas carteiras.

⛔ **Não corrigi de propósito.** A bobina está calibrada fisicamente por você (75mm, 42 colunas,
`left: 0`), e mexer em `position` numa impressão calibrada **às cegas** é o erro que a lição de
2026-08-24 manda não cometer — uma hipótese por rodada, e medindo a saída.

**O teste é seu, e é curto:** faça uma venda com ~25 itens e mande imprimir a papeleta. Se o TOTAL
sair, não há defeito nenhum e a pendência fecha. Se cortar, a correção é a mesma receita do A4
(portal para filho do `<body>`, `display: none` nos irmãos, `position: static`, destravar
`overflow`) — e aí precisamos de **uma impressão por rodada** para confirmar.
### 17. 🔔 Estorno não revoga assinatura
Dívida **conhecida e aceita**, política dele. **Pedido explícito: avisar em toda revisão/auditoria.**


### 66. 🔵 Inutilização de numeração não filtra ANO — buraco de exercício anterior "resolvido" com pedido do ano corrente
`FiscalInutilizacaoRepositorio.buracos/numerosJaConsumidos/sobrepoe` trabalham por (empresa, modelo,
série), **sem ano** — o que é coerente com `fiscal_numeracao.proximo_numero`, que é contínuo e não
reinicia por ano. Mas o pedido enviado à SEFAZ leva `ano = ano corrente no fuso da UF`.

**Cenário:** número 120 alocado e perdido em 12/2026. Em 01/2027 a tela mostra 120 como buraco; o
operador inutiliza; o XML declara `<ano>27</ano>`, faixa 120–120. Se a SEFAZ homologar, foi
homologada a inutilização de um número **de 2027** que a loja nunca vai emitir (a sequência já
passou de 120) — enquanto o buraco de **2026** continua aberto perante o fisco e **some da tela para
sempre**, porque a lista de inutilizados também não filtra ano. O lojista fica convencido de que
resolveu.

⛔ **Não corrigi**: a pergunta é de produto e fiscal, não de código — *como inutilizar buraco de
exercício já encerrado?* As saídas possíveis são (a) gravar/derivar o ano do buraco a partir da
emissão vizinha na série e mandar esse ano no pedido, (b) recusar faixa de ano anterior com uma
mensagem explicando, ou (c) aceitar a limitação e documentar. As três dependem do que o contador
diz sobre inutilização retroativa. ⚠️ E o desfecho na SEFAZ **não se prova lendo** — precisa de
transmissão em homologação, que hoje só você pode fazer.


### 68. 🔵 Nada da auditoria de 5 rodadas foi EXECUTADO — o limite conhecido
As dez varreduras (5 rodadas × back e front) foram **leitura de código**, `tsc -b` e a suíte de
testes. **Nenhuma tela foi aberta no navegador, nenhum PDF foi gerado, nada foi transmitido à
SEFAZ.** Isso não invalida os ~75 defeitos corrigidos — mas define o que a auditoria **não** podia
achar:

- **Classe CSS que não existe** — achamos três (Sangria, Relatório de Contas a Pagar, Minha Conta)
  e só porque um agente rodou um script comparando classes usadas × declaradas. As montadas por
  interpolação (`className={\`uso-${x}\`}`) escapam desse script; provavelmente há mais.
- **Correção criptográfica e conformidade de XSD** (assinatura do XML, AES-GCM das colunas
  `*_cifrado`) — não se verificam lendo, só executando.
- **Tudo que depende da resposta da SEFAZ** — o `<ano>` da inutilização (#66), os autorizadores das
  26 UFs fora do PR, e a reação real a qualquer XML montado. *Validação que confere o próprio
  resultado não prova nada.*
- **Concorrência real** — os `FOR UPDATE` (caixa, sangria, cota, OS) foram lidos e estão nos
  lugares certos, mas nenhum foi exercitado com duas transações simultâneas.
- **O contrato campo a campo entre os DTOs do TypeScript e os records do Java** — um campo renomeado
  no Java que o TS ainda declara pelo nome antigo vira `undefined` na tela **sem erro de
  compilação**, porque as interfaces de `web/src/lib/*.ts` são escritas à mão. Fechar isso pede um
  script comparando os dois lados, que não existe.
- **`AjudaDaTela.tsx`** — 1.458 linhas de texto de ajuda que **afirmam comportamento** e que ninguém
  conferiu contra o código. É exatamente a forma de defeito de "conferir a própria afirmação".

**O que fazer com isto:** não é tarefa de uma sessão. O caminho barato é abrir as telas que a
auditoria mexeu (Sangria, Minha Conta, Relatório de Contas a Pagar, Permissões, Parâmetros do
Sistema) e olhar — as três de CSS teriam sido pegas em dois minutos de navegador.

---

#### 📋 Balanço de 2026-08-30 — o que continua sem cobertura, arquivo por arquivo

As **oito** rodadas desta data também foram só leitura. ⚠️ Desta vez houve uma razão a mais e ela
precisa ser dita: **a extensão do Chrome não conectava naquela sessão** (`Browser extension is not
connected`), então nem a intenção de abrir tela existia.

> ✅ **ATUALIZAÇÃO 2026-08-31 — a extensão VOLTOU a conectar.** Foi usada no mesmo dia para validar
> a mudança da tela de Produtos: seção na posição certa, `document.activeElement` medido nos dois
> caminhos (módulo ligado × desligado) e a galeria sumindo ao marcar Serviço. ⭐ Ou seja, **o
> impedimento era da sessão, não da máquina** — antes de declarar este limite de novo, tente
> conectar. O resto do balanço abaixo continua valendo: ele descreve o que aquelas rodadas não
> cobriram, não uma impossibilidade permanente. O que dava para medir sem navegador **foi**
medido: smoke da API com token real, `psql` contra o banco, e sabotagem de cada correção importante
para ver o teste reprovar. Abaixo, o que os dois agentes declararam como não coberto:

**Não se verifica lendo (limite de método):**
- `fiscal/documento/` (37 arquivos): assinatura XML, canonicalização, XSD e **o que a SEFAZ aceita**.
  O projeto já viveu isso: passou no schema e voltou `cStat 1010`.
- **Concorrência real** — os `FOR UPDATE`/`SKIP LOCKED`/`ON CONFLICT` de caixa, sangria, cota, OS,
  vale-mercadoria e webhook foram lidos, **nenhum exercitado com duas transações**.
- **Correção criptográfica** (`SegredoCifrador`, AES-GCM) — só executando.
- **Contrato TS↔Java**: o `scripts/auditoria/contrato-ts-java.js` casa por **nome de tipo**, e na
  rodada 8 errou em 4 de 4 por colidir com `record` privado homônimo. ⏭️ Ele deveria casar por
  **endpoint**, não por nome — é a melhoria que fecharia o buraco de verdade.

**Código que ninguém leu nas oito rodadas:**
- **Back:** `configuracao/importacao/` (17 arquivos — o maior bloco descoberto; escreve dado em massa
  numa transação só), `comum/armazenamento/` (a conferência do prefixo `tenants/{id}` na leitura é
  **afirmada em doc**, nunca conferida em código), `identidade/permissao/PermissaoInterceptor`,
  `comum/telaconfig`, `comum/arquivocompartilhado`, `configuracao/exportacao`, `cadastros/crm`,
  `estoque/relatorioestoque`, `estoque/relatoriomovimentacao`, `financeiro/relatoriocontaspagar`,
  `plataforma/staff`, `plataforma/tenants`.
- **Front:** o editor visual de etiqueta inteiro (`EtiquetaConfigForm`, `EditorEtiquetaCanvas`,
  `PainelPropriedadesCampo`, `ReguaCalibragem`, `CampoEtiquetaVisual` — a maior superfície de estado
  local do produto), `ImportacaoTabelaPage` (o `setInterval` de polling: o cleanup não foi
  auditado), `ExportacaoDadosPage`, `DrilldownTotalizadorModal`, `LancamentosCarteiraModal`,
  `EscolherModeloModal`, `ProdutoExemploModal`, `GaugeProgresso`.

**Buracos de teste que a rodada 8 encontrou (🟢 minha bola):**
- Faturamento **parcial** de OS não tem teste — o comportamento ("libera a reserva inteira") está
  correto e **não está preso**, e é o tipo de decisão que alguém "conserta" errado depois.
- Nenhum teste exercita `id_usuario` em `fiscal_certificado_uso` — por isso o `ClassCastException`
  latente ali pôde ficar escondido.
- `PrivilegiosNinerAppTest`: conferir se os GRANTs da V094/V095 (sangria) ganharam caso.

**⭐ A pendência mais barata de fechar, e que pegaria três achados de uma vez:** um script que case
cada `podeConfirmar`/`disabled` com a mensagem que o explica e verifique se as duas estão sob a
**mesma condição de aba**. Três dos seis achados da rodada 8 no front eram "a mensagem certa existe,
mas em lugar nenhum que o usuário esteja olhando".

---

### 69. 🔵 A devolução não estorna o ACRÉSCIMO, e sobra comissão numa venda 100% devolvida
`RelatorioComissoesService`: a venda soma `qtd × preço − desconto **+ acréscimo**`; a devolução
subtrai `qtd × preço − desconto`, **sem o acréscimo** — e não é escolha, é que
`DevolucaoProdutoService` nem grava `valor_acrescimo` na linha de devolução.

**Medido:** carteira "CARTÃO 3×" com 10% de acréscimo, venda de R$ 100,00, vendedor com 10%.
Comissão = R$ 11,11. Cliente devolve a peça **inteira** no mesmo mês → estorno = R$ 10,00. Sobram
**R$ 1,11 de comissão** sobre uma venda que não existe mais, e a tela mostra R$ 11,11 "vendidos"
numa devolução total. É a gêmea do defeito do desconto corrigido em 29/08, com o sinal invertido.

⛔ **Não corrigi porque a pergunta é de produto:** *devolver a mercadoria devolve também o acréscimo
de parcelamento?* O vale-mercadoria hoje **não** devolve o acréscimo — então há duas verdades
possíveis e a que **não** pode ficar é a residual. Se mudar, tem de mudar junto na DRE e na
Lucratividade, que foram alinhadas ao Comissões em 29/08.

### 70. 🔵 Atribuição de campanha: um lead, uma conta — e o lojista pode abrir duas
`plataforma.lead` tem **uma linha por e-mail** e **uma** coluna `id_tenant`. Desde que existe o
"criar grupo separado" (2026-08-27), o mesmo e-mail abre duas contas, e o funil
(`count(DISTINCT l.id_tenant)`) conta **1 onde houve 2**.

Em 30/08 troquei o `EXCLUDED` por `COALESCE` (a atribuição fica com a **primeira** conversão, como
as colunas vizinhas) e **normalizei o e-mail** nos três pontos — o `UNIQUE (email)` é
case-sensitive, então `Joao@` e `joao@` viravam dois leads e o antigo ficava em `NOVO` para sempre,
na fila do comercial. ⚠️ **Mas o COALESCE não conserta a contagem**, e num caso ele piora: se a
conta abandonada for a primeira e a pagante for a segunda, a campanha recebe **R$ 0** de MRR.

⏭️ **A saída de verdade é modelagem:** uma linha por conversão (`lead_conversao`), não uma coluna em
`lead`. Muda o significado do funil, então é decisão dele. A migration do índice sobre
`lower(email)` + deduplicação também espera: escolher qual linha sobrevive quando duas têm
status/UTM diferentes é decisão de dado, não regra genérica.

### 71. 🟢 Número fiscal queimado sem registro entre reservar e gravar
Em `EmissaoNfeDevolucaoService`, `numeracao.reservar` roda **antes** de `gravarDevolucaoAssinada`, e
entre os dois correm `montador.montar`, `assinador.assinar` e `validador.validarNfe` — três pontos
que podem lançar. Se lançarem, o número está reservado e **não existe linha em `documento_fiscal`**:
número queimado, que vira buraco, que vira inutilização formal.

O projeto já trata isso na emissão da venda (item 37 da auditoria de 08-27: *"a recusa acontece
ANTES de reservar número"*). Aqui a ordem é a inversa. ⏭️ A saída provável é registrar um
`NAO_EMITIDO` (o conceito já existe, com série/número/chave nulos) quando a falha acontece depois da
reserva — mas isso é decisão fiscal, não só de código.

### 72. 🟢 O motor da NFS-e existe e não tem chamador — nem controller, nem tela, nem PDV
Medido em 2026-08-31: `grep -rln "nfse\|Nfse" api/src/main/java --include=*.java | grep -v /nfse/`
devolve **zero**, e não há `@RestController` no pacote. As 15 classes de `fiscal/nfse/` formam uma
ilha — montagem, assinatura, transporte, numeração, emissão e cancelamento prontos, e **nenhuma
venda emite**. É o padrão *"mecanismo pronto e desligado nas duas pontas"* já catalogado no
`CLAUDE.md` (o outbox do marketplace), com o agravante de que aqui o painel nem existe para mostrar
"0 pendentes" e enganar.
⏭️ O que falta: endpoints de `docs/MODULONFSE.md` §7, a chamada a partir da venda (pelo **mesmo**
`cfg_emite_fiscal_apos_venda`, DS13) e as telas do §8.

### 73. 🟢 A máquina da NFS-e não tem um único teste — e o `EmissorFalso` existe justamente para isso
`grep -rl "EmissorFalso\|NfseEmissaoService\|nfse_documento" api/src/test/` devolve **vazio**. Os 28
testes do S6 cobrem as três classes puras (`IdDps`, `MontadorXmlDps`, `RespostaSefin`); **numeração,
máquina de estados, gravação, recuperação de nota órfã e cancelamento não são exercitados**.
⚠️ O `application.yml` de teste **afirma** que o falso "faz a suíte exercitar a máquina inteira" —
promessa escrita e ainda não cumprida, que é a forma de defeito que o próprio comentário cita.
⭐ Fechar isto não depende de credencial nenhuma: é o caminho mais barato de blindar o módulo antes
que ele ganhe chamador (item 72).

### 74. 🟢 `ParametrosMunicipaisClient` está inerte, e é a peça do autoatendimento
Ninguém o chama (só uma menção em javadoc). `MunicipioNfseService.registrarConvenio` e
`registrarEnvioDeIm` — os dois métodos do assistente — também não têm chamador. Consequência: a
alíquota do ISS tem **dois donos sem conciliação** — quem monta a DPS lê `produto_servico.aliquota_iss`
(digitada), e a alíquota oficial do ADN não chega a lugar nenhum. Enquanto isso, `docs/MODULONFSE.md`
§5 item 6 descreve *"o sistema pergunta à fonte e preenche"*, que hoje é intenção, não comportamento.

### 67. ✅ Reimportar a mesma NF-e corrigida travava o arquivamento num laço a cada 10 min — RESOLVIDO 2026-08-31
O caminho do XML de entrada era `entrada/{ano}/{mes}/{chaveNfe}.xml`, e cancelar a entrada **libera a
chave** para reimportação (comportamento desejado, e a UNIQUE de `produto_movimento_mestre` é parcial
`WHERE cancelado = false` justamente por isso). XML reimportado diferente em qualquer byte →
`gravarComIdempotencia` levanta divergência → o job engole e repete a cada 10 min **para sempre**,
com o `xml_bruto` da entrada nova preso no banco.

**Corrigido:** o caminho passou a levar o `id_movimento`. **Sem migração** — objeto já gravado no
caminho antigo continua apontado pela coluna `xml_objeto_bucket` (que guarda a chave completa) e
nunca é reprocessado; área imutável não aceita mover, então o caminho antigo só deixa de ser gerado.

⚠️ **Ao corrigir apareceram mais DOIS casos da mesma família, e um foi medido:**
- **Inutilização** — o caminho era `inut-{serie}-{ini}-{fim}.xml`, sem nada que distinguisse a
  **empresa**. A numeração fiscal é por empresa e `fiscal_inutilizacao` não tem (nem pode ter)
  UNIQUE por (série, faixa): duas filiais do mesmo tenant inutilizando a mesma faixa é cenário
  legítimo. Reproduzido em teste **antes** de corrigir — `inut-1-700-705.xml diverge`, e a filial
  nunca arquivava.
- **Evento** — o caminho era `{chave}-{tipo}-{seq}.xml`, e a UNIQUE do registro inclui `tentativa`
  (V035) de propósito, porque a MESMA sequência pode ser reenviada. Duas tentativas autorizadas da
  mesma sequência teriam `dhEvento` diferente no mesmo caminho. Corrigido junto, por simetria.

O **documento** (nfeProc) ficou como estava: a chave de acesso inclui CNPJ do emitente, número,
série e cNF — é única por construção fiscal, e `{ano}/{mes}/{modelo}/{chave}.xml` é o caminho que o
contador espera.

Testes: `ArquivamentoXmlTest.entradaReimportadaComXmlDiferenteArquivaEmCaminhoProprio` e
`duasFiliaisInutilizamAMesmaFaixaESaoArquivadasSeparadamente` — os dois **sabotados** depois de
passar, cada um reproduzindo a divergência exata.

## 🟢 Bola minha

### 19. ✅ A partição da exportação de XML **RODOU acima do teto** — 2026-08-31 (resta o peso em memória)
`ExportacaoXmlLoteTest.periodoAcimaDoTetoParticionaEAsPartesCobremTudoSemRepetir` cria **2.001
documentos** (o teto é 2.000), pede as duas partes com o mesmo `ateIdDocumento` congelado e prova o
que a aritmética sozinha não provava: parte 1 com 2.000, parte 2 com 1, **sem repetição e sem
buraco** — as duas juntas cobrem os 2.001, e as pontas do período caem em partes diferentes.

⚠️ **O que fica de fora, declarado:** só **duas** notas têm XML no bucket (a primeira e a última).
Gravar 2.001 objetos no MinIO deixaria a suíte lenta sem medir a partição, que é o que estava em
dúvida. Portanto **continua sem medição o consumo de memória de um ZIP com 2.000 XMLs reais**
(~20 MB crus pela conta do javadoc) — é o número que sustenta a escolha de montar em memória em vez
de `StreamingResponseBody`, e só um período real de uso mede isso.

⚠️ E um defeito de método que o próprio teste pegou: extrair as chaves com um regex sobre o CSV
inteiro **conta errado** — na linha de um documento arquivado a chave aparece duas vezes (a coluna
e o caminho do arquivo no ZIP). A leitura é por linha.

### 22. 🔵 SVC — contingência da NF-e 55: **é decisão dele, não só trabalho meu** (dimensionado em 2026-08-31)
⚠️ **A descrição anterior ("não implementada", bola minha) escondia o fato principal:** existe
**decisão registrada do dono do produto, de 2026-08-24**, escrita em `EmissaoNfceService`:

> *"Contingência OFFLINE (tpEmis 9) é EXCLUSIVA da NFC-e. A NF-e 55 só tem SVC, que não está
> implementada — decisão do dono do produto: se a SEFAZ não responder, a venda é registrada e a
> nota fica em `NAO_EMITIDO` para reprocessar em Documentos Fiscais."*

Ou seja, hoje **há um comportamento definido e funcionando**; SVC é ampliação, não conserto.

**O tamanho real, medido no schema e no código:**

| O que muda | Por quê |
|---|---|
| **Migration nova** | `cfg_uf_autorizador` tem PK `(uf, modelo, ambiente)` — **sem dimensão de tipo de emissão**. SVC-AN e SVC-RS são autorizadores *diferentes* para a mesma UF |
| **Dado oficial** | o mapa UF → SVC-AN/SVC-RS e as URLs precisam vir **da fonte**, como as 27 UFs da V047 — nunca digitados |
| **Chave de acesso** | `tpEmis` ocupa a posição 34 da chave: 6/7 mudam a chave **e o DV**. É exatamente o ponto onde o `cStat 253` já mordeu (constante literal no lugar do campo) |
| **Máquina de estados** | o estado de contingência hoje só existe para a 65 (`FiscalContingenciaService`); a 55 precisaria de entrada, saída e dreno próprios |
| **3 chamadores** | `EmissaoNfceService`, `CancelamentoNfceService` e o dreno resolvem o autorizador por `(uf, modelo, ambiente)` |

⛔ **Por que não implementei hoje:** montagem de XML fiscal **só se valida transmitindo**, e a suíte
roda com `emite_nfe = false` — é o mesmo motivo pelo qual o item **60** foi adiado. Entregar SVC
"verde na suíte" seria entregar uma nota que ninguém viu a SEFAZ aceitar, num caminho que só é
exercitado quando a SEFAZ da UF **já caiu**, isto é, no pior momento possível para descobrir um
defeito.

🔵 **A pergunta que sobra é dele:** o comportamento atual (venda registrada + nota em `NAO_EMITIDO`
para reprocessar) é suficiente para o cliente de NF-e 55, ou vale abrir a frente de SVC junto com
uma janela de homologação?

### 23. CSOSN 500 com ST retido (`cStat 938`)
Minha, mas **depende do contador**.

### 24. ✅ **FECHADO em 2026-08-31** — Efetivar Balanço e Tipo de Carteira ganharam spec
`docs/telas/efetivar-balanco.md` e `docs/telas/tipo-carteira.md`, as duas **derivadas do código**
(cada afirmação conferida no arquivo citado), e `docs/TELAS.md` atualizado nas duas pontas.

⭐ **E escrever a segunda achou um defeito real, corrigido no mesmo dia:** o guard de exclusão de
`tipo_carteira` conhecia **2 das 5 FKs** — faltavam `caixa_mestre`, `caixa_fechamento_conferencia`
e `documento_fiscal_pagamento`. Sem elas o `DELETE` violava a restrição e o handler global
respondia o 409 genérico *"Registro em uso por outro cadastro"*, **sem dizer onde e sem caminho de
volta** (a tabela não tem `ativo`, então não há fallback de inativar). O caso mais provável era o
mais banal: a carteira **DINHEIRO**, presa por ser a carteira de **abertura** do caixa mesmo num dia
sem nenhum lançamento. Hoje a mensagem nomeia o vínculo, e os dois testes novos foram verificados
**sabotando a correção** (sem ela, reprovam).

⚠️ O teste antigo não pegava porque criava o caixa **com** lançamento — passava pelo guard e pela
FK ao mesmo tempo, sem distinguir os dois.

### 25. ✅ **FECHADO em 2026-08-31** — `db/migration/README.md` indexado até a V102
As 67 migrations que faltavam (V036–V102) entraram com uma linha cada, **reconstruídas do cabeçalho
de cada `.sql`** e com as datas do `git log --diff-filter=A` — não da memória. O índice marca
explicitamente as **V063–V070 como desfeitas pela V084** (marketplace) e traz, no fim, o aviso dos
**cinco backfills que saíram vazios** sob `FORCE RLS` (V057, V089, V096, V055→V098) com a regra que
o `MigrationQueMexeEmDadoDeTenantTest` hoje trava.

### 26. ⛔ Itens adiados da auditoria de 2026-08-21 — NÃO são bola minha (reclassificado 2026-08-31)
21+32, 25, 26+30, 27 — detalhe em `docs/PENDENCIAS-AUDITORIA-2026-08-21.md` (24 das 33 já
corrigidas em 08-22).

⚠️ **Eu tinha listado este item como "dá para fazer agora", e conferindo os quatro um a um: nenhum
é.** Os quatro estão travados fora do código:
- **21+32** (FIFO do `nItem` e NF-e de devolução ao fornecedor) — só se provam **transmitindo em
  homologação**, e a homologação na SEFAZ/PR continua pendente.
- **26+30** (impersonação auditada e gestão de staff) — **política de produto a definir**: quem é
  staff, quantos papéis, o que cada um enxerga. Não é dívida técnica, é escopo a decidir.
- **25** (alarme de nota presa em transmissão) — adiado pelo dono em 08-22, com a forma ainda por
  decidir (ele quer um **sino** no topo). ⛔ Construir só o endpoint contador seria mecanismo
  desligado nas duas pontas — o defeito que o outbox do marketplace ensinou.
- **27** — aceito e a lembrar, não é correção.

⭐ O que sobra para mim aqui é **nada**; a bola é do dono do produto (25, 26+30) e da homologação
(21+32).

## Backlog estrutural (do `PROGRESSO.md`, não é regressão)

- **Tela de variação/SKU (`produto_barra`)** — o domínio existe desde 2026-08-05
  (`ProdutoBarraService.obterOuCriar`); falta só a tela de listar/editar/excluir variação.
- **`uso_tenant.qtd_produtos`** — enforcement R19.
- **Catálogo `ajuda_tela` na API (R22)** — hoje o conteúdo é fallback estático dentro do `web/`;
  falta o endpoint/tabela real (spec §3.7.1).
- **Credenciais do datasource nos testes** — `TestcontainersConfiguration` conecta como
  superusuário do container, então `GRANT`/`REVOKE` e RLS ficam **invisíveis** para a suíte.
- **springdoc-openapi** — está na spec e nunca foi implementado; o contrato real vive nas seções
  "Contrato de API" de `docs/telas/*.md`.
- **Decisões de negócio em aberto:** D1 (preços), D3 (gateway), D5/D6/D8/D9/D10 —
  `docs/PLANO-DE-NEGOCIO.md`.

---

## Abertos em 2026-08-27 (trabalho do dia)

### 28. Planos pagos não estão definidos 🔵
A contratação vai perguntar se o cliente quer o plano grátis ou pago, mas **as faixas pagas não
existem** — nem preço, nem regra de quanto pesa cada CNPJ. Enquanto isso, a tela de contratação só
consegue oferecer o gratuito. **Bola dele.**

### 29. Tela de contratação com escolha de grupo (parte 2 do ramo) 🟢
Comparar o ramo da empresa que entra com o das empresas do tenant e, quando diferentes, oferecer
**mesmo grupo × grupo separado** explicando o impacto: mesmo grupo dá visão consolidada mas mistura
cadastros; grupo separado limpa o cadastro mas **elimina a visão de grupo para sempre**, e são duas
assinaturas. Combinado que a contratação acontece **fora do ERP**, na tela de contratação.
**Bola minha**, depende do item 28 para a parte de planos.

### 30. ✅ Ajustado por ele — **FECHADO em 2026-08-29** · SPF/DKIM/DMARC do domínio
O SMTP da Hostinger foi configurado hoje e o e-mail chega, mas sem esses registros a mensagem tende
a cair em spam nos destinatários. Ajuste no painel da Hostinger. **Bola dele.**

### 31. ✅ Conferido por ele — **FECHADO em 2026-08-29**
Um `PUT` de teste meu apagou a ficha fiscal da empresa 1 (detalhe e correção no histórico do dia).
Restaurei tudo do XML da última NFC-e autorizada e o CNAE da consulta ao CNPJ — a Conformidade
Fiscal voltou a dizer "Pronto para emitir". **Mas Inscrição Municipal, telefone e e-mail não
estavam no XML**: se algum deles estava preenchido, precisa ser digitado de novo. **Bola dele.**

### 32. ✅ Testado por ele (*"já testei e está tudo ok"*) — **FECHADO em 2026-08-29**
O fluxo tem teste automatizado ponta a ponta e a tela foi verificada com token inválido; o caminho
feliz no navegador exige clicar num link de e-mail real, **o que troca a senha da conta**. Basta
ele fazer uma vez com uma conta descartável. **Bola dele.**

### 33. ⚠️ RBAC: telas que compartilham controller — **em grande parte resolvido em 2026-08-29**

As **ações** foram separadas: Zerar Contagem, Efetivar Balanço, Diferenças e Estorno de Crediário
têm chave própria (V091 + `@Tela` de método), e conceder a tela-mãe não dá mais nenhuma delas.

O que **continua compartilhado é a LEITURA**, e agora por decisão, não por descuido: `@Tela` aceita
várias chaves e uma tela precisa ser abrível por quem recebeu qualquer uma delas — foi o beco que
a própria separação abriu (403 na abertura citando tela que o admin não liberou).

⚠️ **Sobra um caso**, e ele é coerente: quem tem **Pesquisa de Vendas** com "excluir" cancela
venda. Não há chave `cancelamento-venda` em `cfg_tela`, e cancelar É a ação destrutiva daquela
tela — não existe outro "excluir" ali. Criar chave separada é decisão de produto (migration, item
de menu, mais uma caixa na grade), não correção. 🔵

### 34. ✅ FECHADO pelo item 35 — Conceder "Usuários" permite criar usuário, não configurar permissão
Usuários saiu da lista de telas exclusivas (2026-08-27). Quem receber essa tela cria e edita
usuários da conta; configurar **permissões** segue exclusivo do administrador, checado no servidor.
**Resolvido pela decisão do item 35 (2026-08-27):** "Usuários" continua concedível, e o cadastro do
**administrador** passou a ser inalcançável para quem não é administrador — que era o risco real
por trás desta dúvida.

## 🔐 Auditoria de segurança de 2026-08-27 (front + back, por agentes)

> ✅ **Fechada no mesmo dia.** Dos 14 achados, **12 foram corrigidos** (35–46), cada um com teste
> que reprova sem a correção; **2 continuam abertos e são decisão dele** — o passo do go-live do
> item 43 (definir `NINER_FISCAL_AMBIENTE_FIXO=PRODUCAO`) e a metade LGPD do item 47.
> O item 48 (cobertura de teste) é trabalho meu, sem urgência.

> **O que já está resolvido não está aqui.** Três achados eram do 2FA escrito horas antes e foram
> corrigidos e medidos no mesmo dia (janela de horário na 2ª etapa, reenvio zerando tentativas,
> falta de teto de desafios) — estão em `docs/telas/login-duas-etapas.md`.
>
> ⭐ **O que a auditoria descartou vale tanto quanto o que achou:** P8 (isolamento de tenant) e
> injeção de SQL saíram **limpos, com medida** — 671 referências a tabela de domínio com alias,
> **todas** filtrando `id_tenant`; os 18 `ORDER BY` do cliente todos por allowlist. Também
> descartados: preço/desconto vindos do cliente no PDV (o DTO não tem campo de preço), cota do
> plano, os dois `JwtDecoder`, prefixo de tenant no object storage, recuperação de senha, webhooks,
> `state` do OAuth, jobs `@Scheduled` e segredos no repositório.
>
> ⚠️ **Nada foi executado** — é leitura de código. Os itens marcados 🧪 pedem medição antes da
> correção.

### 35. ✅ FECHADO — Operador com a tela "Usuários" tomava a conta do administrador
`UsuarioService` teve as 5 chamadas de `exigirAdmin` removidas quando o RBAC entrou (o método ficou
órfão, **medido**: 0 chamadas), e a V078 tirou `usuarios` de `admin_apenas`. O `PUT` não protege o
alvo: quem tem *Usuários: alterar* reescreve **senha e e-mail do administrador** (e agora também
desliga o 2FA dele) e entra no lugar. O `DELETE` só barra a auto-exclusão — apagar o administrador
deixa a conta **sem admin para sempre**, porque `criar` grava `administrador = false` fixo e a
restrição de um-admin-por-conta é imutável.
**Decisão dele (2026-08-27):** *"operador jamais pode acessar o cadastro do usuário
administrador"*. **Feito:** o administrador some da listagem e todos os caminhos — `GET`, `PUT`,
`DELETE` e a grade de permissões — respondem **404**, não 403 (403 confirmaria qual id é o dele).
Verificado **revertendo a trava**: sem ela, o `PUT` responde 200 e a tomada de conta acontece.
⭐ Isto **fecha também o item 34**: "Usuários" continua concedível, e conceder já não expõe o
administrador.

### 36. ✅ FECHADO — Nenhum operador conseguia vender (e mais 5 telas divergentes)
`cfg_tela.pdv` está com `tem_incluir = false` (V076), e `POST /pdv/vendas` traduz para INCLUIR —
então a permissão **não pode nem ser concedida**. Confirmado no banco. Não é brecha, é o RBAC de
ontem quebrando o caixa. Correção é um `UPDATE` em `cfg_tela` + um teste de estreia (*operador com
grade cheia efetiva uma venda*), que hoje não existe. Revisar junto `pesquisa-vendas` e
`reimpressao-recebimento-crediario`.
**Feito (V081):** as seis divergências corrigidas — `pdv` e `etiqueta-emissao` não ofereciam
"incluir" (ninguém vendia nem emitia etiqueta), `entrada-produtos-compra` não oferecia "alterar",
e `estoque`/`minha-conta` ofereciam caixas que não governavam nada.
⭐ **O conserto de verdade não é a migration, é `AcoesPorTelaConferemTest`:** ele varre os
controllers, deriva a ação de cada endpoint pela mesma regra do interceptor e compara com
`cfg_tela`. A V076 mediu pelo front e errou seis vezes; sem o teste, a próxima tela nova volta a
divergir e ninguém descobre até um operador não conseguir trabalhar.

### 37. ✅ FECHADO — Emissão de NFC-e não era idempotente
`documento_fiscal_venda_ix` é **índice, não UNIQUE**, e nada no serviço pergunta se a venda já tem
documento (confirmado). Duplo clique, retry de rede ou reimpressão geram **duas notas autorizadas
para a mesma venda** — receita e ICMS em dobro, e só se desfaz cancelando na SEFAZ dentro da janela
de 30 min.
**Feito (V082):** índice único parcial `(tenant, venda, modelo)` sobre as situações **vivas**
(autorizado, contingência, transmitindo, assinado) — rejeitada, denegada, não emitida e cancelada
ficam de fora, porque nesses casos reemitir é o certo. A recusa com mensagem legível ("esta venda já
tem a NFC-e nº X") acontece **antes** de reservar número: chegar ao índice queimaria um número da
sequência, e número queimado vira buraco, que vira inutilização formal.
⚠️ O teste confere no banco que a sequência **não** andou — validar só o 409 passaria com o defeito
presente. Decisão dele: **recusar**, não devolver a nota existente.

### 38. ✅ FECHADO — Inutilização aceitava numeração de nota em contingência
`SITUACOES_NAO_SAO_BURACO` lista só `AUTORIZADO, CANCELADO, DENEGADO` de dez situações possíveis, e
governa tanto o que a tela **sugere** quanto o que o POST **bloqueia**. SEFAZ cai, a loja emite em
contingência, e a tela oferece esses números como buraco — inutilizar homologado **não se desfaz**,
e as notas viram recusa quando o dreno transmitir. Correção: acrescentar `CONTINGENCIA`,
`TRANSMITINDO`, `ASSINADO` à constante.
**Feito.** ⚠️ Os guardas de **faixa** estavam todos corretos e não pegavam nada: o furo era a lista
de situações, uma camada abaixo deles.

### 39. ✅ FECHADO — `X-Forwarded-For` forjável anulava o limite de requisição
O filtro lê o **primeiro** elemento do cabeçalho, que é o que o cliente manda; o nginx acrescenta o
IP real no **fim**. Com `confiar-proxy=true` (produção), qualquer um cria um balde novo por
requisição. Sobra o `limit_req` do nginx — 6× o teto pretendido. Ler `X-Real-IP` (o nginx
sobrescreve) ou o último elemento. **Bola minha.**

### 40. 🟠 Login do backoffice sem teto de tentativas, e com oráculo de tempo 🧪🔵🟢
`POST /api/admin/sessao` não passa pelo limite de requisição (que cobre só `/api/publico/**`) e não
tem bloqueio de conta — é a credencial mais valiosa do sistema. E o hash de mentira que deveria dar
tempo constante tem **63 caracteres** onde o BCrypt exige 60: o `matches` recusa o formato e
**retorna sem calcular**, então e-mail existente demora ~50-300 ms e inexistente ~1 ms —
enumeração de staff medível pela internet. 🧪 Um `curl` cronometrado fecha o diagnóstico em
minutos. **Bola minha** o código; **dele** decidir se restringe o backoffice por IP (o allowlist já
está escrito e comentado no nginx).

### 41. ✅ FECHADO — Módulo fiscal não checava "empresas com acesso"
24 endpoints recebem `idEmpresa` por path/query sem conferir `usuario_empresa` — as rotas de
dinheiro usam o claim `eid` corretamente, o bloco fiscal não. Operador da filial 1 põe a **filial
2** em contingência, ou baixa o XML fiscal dela. P8 continua intacto (é dentro do mesmo tenant); o
que se atravessa é a fronteira entre empresas.
**Feito (decisão dele: operador fica preso à empresa da sessão).** `EmpresaDaSessao.exigirAcesso`
em **14 endpoints** dos 7 controllers fiscais; administrador continua alcançando todas. Responde
**403** (e não 404 como o cadastro do admin) porque a existência da outra filial não é segredo — ela
aparece no seletor do login — e a pessoa precisa entender que o caminho é trocar de empresa.

### 42. ✅ FECHADO — Devolução aceitava venda cancelada como origem
`venda.cancelada` nunca aparece no caminho da devolução: vender → cancelar (estoque volta, dinheiro
sai) → devolver a mesma venda → **vale-mercadoria integral**.
**Feito.** ⚠️ O teto de "não devolver mais do que foi vendido" existia e não pegava isto: ele mede
contra os **itens** da venda, e cancelar não os apaga. O teste confere no banco que **nenhum vale
nasceu** — validar só o 409 passaria se a recusa viesse depois da gravação.

### 43. ✅ FECHADO — Ambiente fiscal era trocável a quente (trava pronta para o go-live)
A série é protegida depois da primeira nota autorizada; o **ambiente** não. Trocar para homologação
faz as vendas saírem sem valor jurídico com o PDV dizendo "autorizada" — e as notas de teste
**consomem números da sequência de produção**, criando buracos que exigem inutilização formal.
**Decisão dele (2026-08-27):** *"a emissão das notas está agora em homologação, pois estamos
homologando junto à SEFAZ de todos os estados; mas quando o sistema estiver em produção, o sistema
de emissão de notas fiscais não deverá ter a opção homologação ou produção — sempre vai ter que
estar em produção, travado nisso"*. **Feito:** `NINER_FISCAL_AMBIENTE_FIXO`, hoje **vazia** (a
homologação precisa do ambiente 2). Com ela em `PRODUCAO`, a escolha some da tela e o `PUT`
sobrescreve o que vier.
🔵 **Fica com ele o passo do go-live:** definir a variável no compose de produção.
⚠️ **E fica aberto o irmão:** `fiscal_numeracao` não separa ambientes, então as notas de
homologação de **hoje** consomem números da sequência que valerá em produção. Com a trava, isso não
volta a acontecer depois do go-live — mas é bom saber que a numeração de produção vai começar de um
número alto.

### 44. ✅ FECHADO — Sessão não era revogável (8 h de sobrevida)
O JWT vale 8 h, sem `jti` e sem denylist. Desativar, excluir ou trocar a senha **não derruba
sessão**: demitido continua operando até o token vencer.
**Decisão dele (2026-08-27):** *"sim, corrija isso — se a senha for trocada ou o usuário
desativado, tem que efetuar o logoff do respectivo usuário o mais breve possível; algo que não
deixe o sistema lento e não consuma muitos recursos do servidor"*. **Feito sem nenhuma consulta
nova** (V080): `usuario.sessao_valida_desde` entrou na consulta que o filtro de horário de acesso
já fazia a cada requisição. O logoff acontece na requisição seguinte. Ver
`docs/telas/revogacao-de-sessao.md`.

### 45. ✅ FECHADO — Desconto do tipo de carteira sem teto
`tipo_carteira.perc_desconto` é `numeric(5,2)` sem CHECK e sem teto no serviço, enquanto
`descontoVenda` é revalidado contra `cfg_geral`. 999,99% numa forma de pagamento fecha venda de
R$ 1.000 com ~R$ 91.
**Feito (V083):** CHECK 0–100 no banco + validação no serviço.
⚠️ Havia um teste **prendendo o comportamento antigo** (`percentualAcimaDeCemEhAceito`, "sem limite
superior, herdado de moeda"). Ele foi **invertido**, não apagado — é ele que prende a regra nova
agora.

### 46. ✅ FECHADO — Sobras de coerência do RBAC
(a) **DRE** e **Lucratividade** saíram de `admin_apenas` mas mantêm `exigirAdmin` no serviço
(confirmado) — o admin concede e o operador toma 403; idem `fechamento-caixa` (aqui a decisão de
reabrir-só-admin é explícita, falta a caixa sumir da grade). (b) `CategoriaProdutoController` e
`CategoriaClienteController` fazem POST/PUT **sem `@Tela`** — qualquer autenticado de grade vazia
cria e renomeia categorias. (c) `MarketingAdminController` é o único de `/api/admin` sem
`exigirStaff`.
**Feito:** (a) os dois `exigirAdmin` removidos — quem decide é a grade, como nas outras dez telas
do commit `fa85474`; (b) os dois controllers de categoria passaram a declarar `@Tela` (Produtos e
Clientes), com o `GET` `@Livre` porque outras telas consultam a lista; (c) `MarketingAdminController`
ganhou `exigirStaff` nos 5 endpoints — lead é dado pessoal de quem se cadastrou no site.
⚠️ **`fechamento-caixa` fica como está:** reabrir caixa continua só do administrador **por decisão
dele** (2026-08-27, tela a tela), e isso está documentado — não é a mesma sobra.

### 47. 🟡 Lead grava consentimento não dado (a parte do 409 foi decidida) 🔵
`POST /assinar` responde 409 para e-mail já cadastrado e 201 para novo — um verificador de "é
cliente do Nainer?", ao contrário do 204-sempre da recuperação de senha. E o `ON CONFLICT` do lead
deixa um anônimo sobrescrever nome/telefone de um lead existente, gravando `consentimento_em` que
a pessoa não deu (justo o campo que provaria a base legal do contato — LGPD).
**Decisão dele (2026-08-27): mantém o 409** — a mensagem existe para o lojista não duplicar conta,
e o preço (confirmar que aquele e-mail já é cliente) está aceito e registrado.
⚠️ **A outra metade continua aberta e é outra coisa:** o `ON CONFLICT` de `plataforma.lead` deixa um
anônimo sobrescrever nome e telefone de um lead existente, e grava `consentimento_em` para quem
nunca consentiu — justo o campo que provaria a base legal do contato (LGPD). Não mexi porque muda o
funil de aquisição. **Decisão dele.**

### 48. ✅ **Cobertura de isolamento ampliada em 2026-08-31** (resta o privilégio)

⚠️ **Primeiro, a correção do próprio item:** ele dizia *"19 testes para ~112 classes"* e listava
como descobertos **PDV/venda, caixa, contas a pagar, crediário e transferência**. Medindo por
comportamento em vez de por nome de método, **transferência, pesquisa de vendas e contas a pagar já
tinham cobertura cross-tenant** — só não se chamavam `isolamentoEntreTenants`. Contagem real antes
desta rodada: **44 dos 117 arquivos** de teste já exercitavam dois tenants. Catálogo medido pelo
nome erra; é o mesmo defeito do `cfg_tela` da V076.

**O que entrou agora — 8 testes, priorizados por dinheiro, identidade e cadastro central:**

| Onde | O que o teste recusa |
|---|---|
| **PDV** | ler produto do vizinho **pelo SKU exato** — e o SKU vem de uma sequência **global da instância** (V017), então é o identificador mais fácil de adivinhar |
| **Cliente** | ler, listar, **alterar** e **excluir** — dado pessoal, LGPD |
| **Usuário** | ⛔ o pior caso: **trocar a senha** do usuário do vizinho e entrar na conta |
| **Produto** | inclusive amarrar o produto alheio a uma categoria própria |
| **Fornecedor** · **Funcionário** | ler, listar, alterar, excluir |
| **Tipo de Carteira** | alterar o `percDesconto` do vizinho — faria toda venda daquela loja fechar mais barato |
| **Sangria de Caixa** | ⭐ aqui o vetor é **escrever**: o caixa vem do servidor, mas a **conta de destino vem do corpo** — sangrar para a conta do vizinho move dinheiro entre lojas |
| **Vale-mercadoria** | o número do vale é sequencial pequeno; é o registro mais perto de "dinheiro ao portador" |

⭐ **Todos testam o par**: o dono **enxerga** e o vizinho **não**. Sem a asserção positiva, um
endpoint quebrado (404 para todo mundo) passaria por "isolado" —
`feedback_caso_negativo_pega_guarda_que_expulsa_todos`.

⭐ **E o mecanismo foi verificado por sabotagem:** removido o `AND id_tenant = plataforma.tenant_atual()`
do `ClienteService.buscar`, o teste **reprova com `Status expected:<404> but was:<200>`** — o vizinho
passa a ler o cliente. Restaurado em seguida.

⚠️ **Uma armadilha que este trabalho reencontrou:** a primeira versão do teste da Sangria passava
sozinha e **reprovava na suíte**. Causa: `abrirConexao` conecta com o **superusuário do container**,
que ignora RLS, então `count(*)` sem `id_tenant` explícito media o **banco inteiro** e contava a
sangria legítima de outro teste da mesma classe. Asserção de teste também precisa do filtro
explícito — `feedback_testcontainers_nao_usa_niner_app`.

✅ **E a outra metade também fechou:** `PrivilegiosNinerAppTest` (13 → **15** casos) passou a cobrir
os dois `REVOKE` que não tinham caso — `plataforma.diretorio_login` (V071: só a trigger escreve,
senão um e-mail apontaria para a conta errada e o login **entregaria a loja de outra pessoa**) e
`plataforma.codigo_login` (V079: sem `DELETE`, porque apagar o desafio zeraria o **teto de
tentativas** do código de 4 dígitos — o mesmo bypass que o rollback causou em 2026-08-27, agora
pela porta do privilégio). A sangria (V094/V095) **já tinha** caso.

⛔ **O que continua sem cobertura, e é estrutural:** o datasource da aplicação conecta como
**superusuário do container**, então um `GRANT` esquecido numa tabela **não coberta** por esse teste
segue invisível. `PrivilegiosNinerAppTest` é a rede barata, não a solução — separar as credenciais
do Testcontainers continua no backlog estrutural.

## ✅ Fechadas recentemente (para não reabrir por engano)

- **2026-08-27 (tarde)** — **RBAC completo**: permissão por tela e por ação, presa ao usuário
  (sem perfis), com a trava valendo **no servidor** — 55 controllers anotados, 8 métodos de
  desfazer classificados como "excluir" e 10 endpoints marcados como livres. 9 telas seguem
  exclusivas do administrador e não aparecem na grade.
- **2026-08-27** — **Login sem identificador** (e-mail + senha; o mesmo e-mail pode estar em várias
  contas) · **Recuperação de senha completa** (o link do e-mail apontava para uma rota que não
  existia) · **SMTP configurado** — o sistema enviou o primeiro e-mail da sua vida · **Ramo de
  atividade** (28 ramos, sugestão pelo CNAE do CNPJ) · **Cobrança por CNPJ** decidida e documentada.

- **2026-08-26** — `fiscal.download` / DF22: **Exportação de XML em Lote** (`/fiscal/exportacao-xml`),
  validada com dados reais, na versão **síncrona e particionada** (o desenho assíncrono do
  `MODULOFISCAL.md` §11.2 **não** foi feito).
- **2026-08-26** — Marketplace **M1–M7**, Relatório de Contas a Pagar/Pagas, Relatórios em
  subgrupos, PDF dos 10 relatórios fora do tema escuro.

---

## 2026-08-28 — a integração com marketplaces saiu, e nove pendências saíram com ela

Decisão do dono do produto: *"vamos mudar o projeto de integração com marketplaces, então preciso
que você remova tudo o que foi feito pra integração com o Mercado Livre; a integração vai ficar em
implementações futuras"*.

**Nove itens foram fechados por desaparecimento do assunto** — não por terem sido resolvidos.
Registrado assim de propósito: quando a integração voltar, **estes nove voltam junto**, e a lista
abaixo é o que evita redescobri-los um a um.

| # | Item | Por que voltará |
|---|---|---|
| 2 | Teste real do Mercado Livre (credenciais de test user) | nada foi validado contra o ML de verdade |
| 3 | Ligar os 3 tópicos de notificação no painel do ML | passo de painel, some com o endpoint |
| 4 | Nenhuma tela do marketplace aberta em navegador | as telas não existem mais |
| 15 | Pedido sem estoque não vira venda e fica tentando | decisão de produto, ainda não tomada |
| 16 | Um produto só alimenta UM anúncio por canal | decisão de produto, ainda não tomada |
| 18 | Taxa do canal em 0% (a DRE superestimava o lucro) | volta com a carteira do canal |
| 20 | A tela não avisava que a taxa está zerada | par do item 18 |
| 21 | Tela de preço manual do anúncio não existe | `anuncio.preco_manual` saiu junto |
| 27 | Chamada HTTP dentro da transação do lote do outbox | risco do worker, que saiu |

⚠️ **Uma pendência NÃO some com isso e merece atenção quando a integração voltar:** a regra *"quem
vende em marketplace não pode ter estoque negativo"* tinha **dois** guardas (barrar a conexão do
canal e barrar o religamento do parâmetro em Parâmetros do Sistema). Os dois saíram juntos, o que é
o certo — mas **voltar só um seria pior que não ter nenhum**, porque dá sensação de proteção sem a
proteção. Está registrado no comentário de `ConfiguracaoGeralService.atualizar` e em
`docs/MODULOMARKETPLACE.md` §13.6.

**O que a remoção mexeu:** migration **V084** (desfaz V063–V070, incluindo os dois gatilhos de
sincronização que rodavam a cada gravação de estoque e de preço), 36 classes Java, 3 telas, 14
testes, a dependência do WireMock, as variáveis `NINER_ML_*` e duas linhas de `cfg_tela` (RBAC).
Suíte depois da remoção: **1024 testes verdes, 0 pulados**.

---

## 2026-08-29 (2) — ele decidiu as 12 pendências, item a item

Ele leu a lista completa e respondeu cada uma. Cinco viraram código (`V092`–`V094`), quatro
continuam abertas **por decisão dele** (com o texto do pedido registrado) e três foram fechadas por
confirmação. As entregas estão em `docs/PROGRESSO.md`.

### ✅ Fechadas nesta rodada

- **#55** — cancelar a venda **cancela** a OS e o orçamento (V092). *"cancela a venda e tb OS e ou
  ORÇAMENTO"*. Teste com controle negativo dos dois lados.
- **#57** — criada a **Sangria de Caixa** (V094, `docs/telas/sangria-caixa.md`) e o fundo de troco
  passou a contar uma vez por operador. ⚠️ **Mas sobrou uma pergunta para ele — ver o item 64.**
- **#61** — a mensagem do beco agora **nomeia a venda** que consumiu o vale. Sem migration: a
  coluna `venda_devolucao.id_venda_debito` já existia desde a V018, e a pendência afirmava o
  contrário. (Terceira afirmação minha sobre "o que o sistema não faz" que caiu na conferência, no
  mesmo dia.)
- **#62** — confirmado, **sem mudança de código**: *"o que vale é o total pago pelo cliente"* é
  exatamente o que o sistema faz desde que a DRE e a Lucratividade foram alinhadas ao Relatório de
  Comissões nesta mesma data.
- **#51** — cinco ramos de serviço (V093), com os CNAEs carregados da API do IBGE. ⚠️ A parte **P6**
  (a cota pesar diferente em serviço) continua dependendo do **#28** e vai junto com ele.
- **#31**, **#30**, **#32** — fechadas por confirmação dele (*"ok"*, *"ok"*, *"já testei e está tudo
  ok"*).

### 🔵 Continuam abertas — decisão registrada dele

- **#28** — *"iremos definir esta questão comercial mais para frente, anote como pendência"*.
- **#40** e **#47** — *"documente e deixe como pendência, pra me cobrar mais pra frente"*. Os dois
  já estão **medidos**, não suspeitos: o hash falso do login de staff tem 63 caracteres onde o
  BCrypt exige 60, então `matches` recusa o formato e **retorna sem calcular** (e-mail existente
  ~50-300 ms × inexistente ~1 ms); e o `ON CONFLICT` do lead deixa um anônimo sobrescrever nome e
  telefone de um lead existente.
- **#49** — *"na próxima segunda-feira já terei as credenciais, e vamos testar"*. 🔔 **Cobrar na
  segunda:** sem elas o S0/S6/S7 da NFS-e não sai do papel, e faltam três fatos que não são
  dedutíveis — **regime** (MEI × Simples), **município** e o padrão que ele usa.

---

### 64. ✅ O dinheiro que fica na gaveta no fechamento — **RESOLVIDO em 2026-08-29** (V095)

Ele escolheu a **opção 1** das três apresentadas: *"o fechamento exige sangrar até o fundo — o
operador é obrigado a deixar na gaveta exatamente o que vai abrir amanhã"*.

`CaixaService.exigirExcedenteSangrado` recusa com 409 dizendo quanto sangrar e onde. É parâmetro
(`cfg_exige_sangria_fechamento`), **ligado por padrão** — a regra bloqueia o fechamento e sangria
exige conta corrente cadastrada, então a loja que não deposita em banco precisa de escape. Caixa
*abaixo* do fundo fecha normalmente. Detalhes: `docs/telas/sangria-caixa.md`.

⭐ **Com isso o modelo do Fluxo de Caixa fecha inteiro** — era o último buraco: antes da sangria o
número inflava, depois dela e sem esta regra ele encolheria.

---

## 2026-08-29 — auditoria por agentes: 5 rodadas, front e back em paralelo

Pedido dele: *"dois agentes em paralelo, 5 rodadas cada, para ser preciso na varredura; procure a
fundo o ERP, verifique tudo"* — e, da rodada 2 em diante, **varrer o ERP inteiro**.

O que foi corrigido está em `docs/PROGRESSO.md`. Aqui ficam só os itens que **dependem de decisão
dele** ou que sobraram por escolha.

### 57. ✅ Fundo de troco e a Sangria de Caixa — **FECHADO em 2026-08-29** (V094; ver o item 64)

`FluxoCaixaService.saldoAte` soma `SUM(cm.saldo_inicial)` de **todos** os caixas abertos até a data,
e nada retira esse dinheiro depois — `CaixaService.fechar` não grava saída, e não existe sangria.

**Medido no código:** loja que abre todo dia com R$ 200 de fundo de troco (a mesma cédula que dorme
na gaveta) e não vende nada tem, após 20 dias úteis, **"saldo real: R$ 4.000"** na tela — com R$ 200
na gaveta. A Projeção parte desse número, e a conciliação não denuncia porque os dois lados usam a
mesma soma inflada.

⚠️ **Não corrigi por conta própria**: `docs/telas/fluxo-caixa.md` justifica a linha considerando só
o **primeiro** dia de uso ("o teste acusou −150 onde o dinheiro real era 850"), e o caso da segunda
abertura em diante não é tratado ali. A pergunta é de produto:

- `saldo_inicial` é **dinheiro que permanece na gaveta** (fundo de troco reaproveitado)? Então só a
  primeira abertura da série deveria contar — ou o fechamento deveria abatê-lo.
- Ou é **aporte novo** a cada dia? Então o certo é um lançamento em `caixa_detalhe`, e a coluna sai
  do somatório.

### 58. ✅ Cinco travas de concorrência ausentes em rotinas de dinheiro — **FECHADO em 2026-08-29**

Todas exigiam duas requisições simultâneas, por isso não entraram na correção imediata. Fechadas
com o modelo que já existia ao lado (`DevolucaoCompraService.travarEstoque`):

1. **Limite de crédito** — `FOR UPDATE` na linha do **cliente**, antes da leitura do saldo. Travar
   `contas_receber` não serviria: o que as duas transações disputam é a linha que **ainda não
   existe**, e não se trava o que não está lá. É o desenho de `LimiteVendasService`.
2. **Devolução de Produtos** — `travarVenda()` logo no início, antes de qualquer leitura de saldo.
   Trava a venda, não as linhas do ledger, pelo mesmo motivo — e um ponto único por venda também
   elimina deadlock por ordem de travamento.
3. **Fechar caixa duas vezes** — `buscarCaixaTravado()` no `fechar` e no `reabrir`. Método separado
   de propósito: o `buscarCaixaPorIdObrigatorio` também serve consultas `readOnly`, onde
   `FOR UPDATE` não cabe.
4. **`PUT /estoque/entradas/{id}/itens/{idDetalhe}` em entrada cancelada** — agora 409, com
   `FOR UPDATE` no mestre. ⭐ **Com teste** (`naoEditaItemDeEntradaJaCancelada`), porque este é o
   único determinístico dos cinco; sabotado, responde 200 em vez de 409. O teste confere também
   que o 409 **não gravou metade**.
5. **Fuso na importação** — fechado mais cedo no mesmo dia, pelo guarda de fuso ampliado.

⚠️ **O que ficou sem teste, e por quê:** 1, 2 e 3 são corridas — teste single-thread passa com o
defeito presente e não prova nada. Provar exigiria duas conexões JDBC coordenadas, que é teste
lento e propenso a intermitência. A correção é a mesma dos casos já provados do repositório, e o
javadoc de cada uma diz o cenário exato.

### 59. ✅ Dois `exigirAdmin` mortos e dois javadocs falsos — **FECHADO em 2026-08-29**

`CancelamentoVendaService.exigirAdmin` e `EntradaMercadoriaService.exigirAdmin` foram removidos, e
o javadoc que afirmava *"ADMIN-only"* passou a dizer o que é verdade: quem governa é o
`@Acao(EXCLUIR)` do RBAC (V073–V081), o que significa que o administrador **pode conceder** a ação
a um operador. ⭐ Que eram mesmo código morto ficou **provado pela compilação**: remover método
privado usado não compila.

---

### 60. ⛔ A NF-e 55 de devolução declara o valor BRUTO 🟢

**Onde:** `api/.../fiscal/documento/DevolucaoFiscalAssembler.java` — `buscarItensDaNotaOriginal`
não lê `documento_fiscal_item.valor_desconto` (a coluna existe e é gravada, V035:409) e `somar()`
fixa `vDesc = ZERO`, com `vNF = soma dos vProd`.

**O efeito:** devolução total de uma venda com desconto emite nota de entrada de **R$ 100**
referenciando uma NFC-e cujo `vNF` é **R$ 90**. Não é regressão de 29/08 — é anterior; o que mudou
naquele dia foi que todo o resto (vale, DRE, Lucratividade, Comissões, cancelamento) passou a
trabalhar no líquido, e a nota fiscal ficou sendo o único lugar no bruto.

**Por que não foi corrigido junto:** a máquina de rateio já existe (`ratear(valor, proporcao,
total)`), então a mudança é mecânica — ler a coluna, rateá-la pela quantidade devolvida, somar em
`vDesc` e fazer `vNF = vProd − vDesc`. ⛔ **Mas montagem de XML fiscal só se valida transmitindo**,
e a suíte roda com `emite_nfe = false`: uma alteração aqui aprovada por 1.075 testes verdes não
teria sido verificada por nenhum deles. Fica para uma sessão em que dê para transmitir em
homologação e conferir o `cStat`.

**Cuidado ao pegar:** o javadoc da devolução em `DevolucaoProdutoService` já **afirma** que este
ponto continua no bruto, com a localização exata. Se corrigir, aquele comentário tem de mudar
junto — foi ele que, afirmando o contrário do que era verdade, gerou este item.

---

### 61. ✅ A mensagem NOMEIA a venda que consumiu o vale — **FECHADO em 2026-08-29** (a coluna já existia)

**O sintoma:** se o vale já foi resgatado, o cancelamento da venda diz *"cancele primeiro a
devolução"* e o cancelamento da devolução responde *"o vale já foi usado — não é possível
cancelar"*. As duas telas se apontam.

**A saída existe** — cancelar a venda que consumiu o vale devolve `vale_usado = false` — e desde
29/08 as duas mensagens **dizem esse caminho**. O que não dá para fazer é **nomear** a venda: a
tabela `venda_devolucao` só guarda o booleano `vale_usado`, sem nenhuma coluna apontando para a
venda que o gastou.

**Decisão dele:** vale a pena uma coluna nova (`venda_devolucao.id_venda_uso`, preenchida no
resgate) para a mensagem poder dizer *"cancele antes a venda nº 1234"*? É uma migration pequena e
resolve o beco de vez; sem ela, o operador precisa descobrir sozinho em qual venda o vale foi
usado.

---

### 62. ✅ Comissão sobre o TOTAL PAGO — **CONFIRMADO em 2026-08-29**, sem mudança de código

Em 29/08 três relatórios (DRE ×2 e Lucratividade) calculavam a comissão sobre
`qtd × preço − desconto`, e o **Relatório de Comissões — o número que a loja efetivamente paga** —
sobre `qtd × preço − desconto + acréscimo`. Alinhei os três **ao pagador**, porque mudar o
Comissões alteraria quanto o vendedor recebe com base num palpite meu.

**A pergunta que sobra é de produto, não de código:** *o vendedor ganha comissão sobre o
acréscimo?* Hoje o sistema responde **SIM**, de forma consistente nos quatro lugares. Se a resposta
for **não**, muda-se o Relatório de Comissões e os três atrás dele — os pontos estão marcados com
comentário citando este item.

---

### 63. ⛔ Os seis `@Scheduled` disparavam DENTRO da suíte — ✅ FECHADO em 2026-08-29

Achado ao rodar a suíte inteira depois de fechar o item 58: `FiscalInutilizacaoTest` reprovou com
*"No interactions wanted here… but found FiscalContingenciaDrenoJob.sefazRespondendo"*. Rodando o
arquivo isolado, verde. O `@EnableScheduling` vivia na classe da aplicação e valia nos testes; com
`initialDelay` de 15 s a 2 min e uma suíte de vários minutos, os jobs disparavam no meio dela.

⚠️ **O dreno era o sintoma mais visível, não o mais grave:** o `OrcamentoVencimentoJob` **altera
dados** (marca orçamento como vencido). Um job que muda linha no meio de um teste produz falha que
ninguém explica lendo o teste — e o custo real disso é gente aprendendo a ignorar build vermelho.

**Fechado assim:** `comum.AgendadoresConfig` carrega o `@EnableScheduling`, condicionado a
`niner.agendadores.ativos` (padrão **ligado** — sem a propriedade, produção continua com jobs).
`src/test/resources/application.yml` declara `false`. Os beans continuam existindo: teste que quer
exercitar um job injeta e chama o método, como `OrcamentoCrudTest` já fazia.

⭐ **O guarda mede o mecanismo, não a ausência de falha:** `AgendadoresDesligadosNaSuiteTest`
pergunta ao Spring quantas tarefas agendadas existem. "A suíte passou" não prova nada sobre defeito
de temporização — pode só não ter disparado nesta rodada. Religando a propriedade, o teste reprova
citando o caso.

---

## 2026-08-28 (3) — as lacunas da OS fechadas (item 53 resolvido)

Pedido dele: *"faça tudo o que pode ser feito, menos a emissão da NFS-e"*. **V088, V089 e V090.**
Suíte: **1065 testes verdes, 1–2 pulados** (o guard de meia-noite, que se pula sozinho perto da virada do dia).

**O item 53 fechou por inteiro**, e com mais do que ele listava:
- ✅ Via impressa da OS (bobina + A4 + WhatsApp) — a lacuna que **não** estava na lista original e
  era a maior: a OS não tinha nenhuma forma de imprimir.
- ✅ Executor por item na tela + comissão indo para ele, com o percentual do **serviço** (DS5).
- ✅ Papeleta separando serviço de produto.
- ✅ `duracao_minutos` consumida como **estimativa** (não agenda).
- ✅ OS na ficha do cliente.

**Dois defeitos achados no caminho, os dois documentados no `CLAUDE.md`:**
1. A comissão era calculada **na consulta**, então editar o percentual do funcionário reescrevia
   meses já pagos. Congelada na linha (V088).
2. Mudar o significado de `produto_movimento_detalhe.id_funcionario` quebrou **cinco** leitores que
   derivavam "o vendedor da venda" dali — a Pesquisa de Vendas mostrou o mecânico como vendedor.
   Corrigido com `venda.id_funcionario` (V089), cujo backfill saiu vazio e precisou da V090.

### 54. O que a OS deixou para depois (era o item 53, agora reduzido) ⏭️
- **Agenda / hora marcada.** A duração já vira estimativa na tela; reservar horário é feature
  própria e depende de decisões de produto ainda não tomadas (horário de funcionamento,
  disponibilidade por profissional, conflito).
- **Executor por LINHA quando a mesma variação se repete na OS.** Hoje o mapa fica com o primeiro
  executor; resolver exige a chave de linha que o PDV ainda não carrega para a OS.

### 55. ✅ Cancelar a venda CANCELA a OS e o orçamento — **FECHADO em 2026-08-29** (V092)

**Medido ao vivo em 2026-08-28**, cancelando a venda 621 que veio da OS 3:

| | Antes | Depois |
|---|---|---|
| Venda | ativa | **cancelada** ✅ |
| Estoque da peça | 2 | **3** (voltou) ✅ |
| OS | `FATURADA` → venda 621 | **`FATURADA` → venda 621** ❌ |

A OS continua afirmando que virou uma venda que não existe mais, e **não há caminho de volta**: ela
está `FATURADA` e o F5 só oferece `CONCLUIDA`. O lojista teria de abrir outra OS do zero,
redigitando serviços, peças e executor de um trabalho que já foi feito.

⚠️ **Não é regressão do módulo de serviços — o ORÇAMENTO tem exatamente o mesmo comportamento**
desde que existe (fica `VENDIDO` apontando para venda cancelada). `CancelamentoVendaService` não
conhece nem um nem outro. A diferença é o custo: um orçamento se refaz em dois minutos, uma OS
carrega o histórico da execução.

**🔵 Precisa de decisão dele**, e são três perguntas encadeadas:
1. Cancelar a venda deve **devolver a OS para `CONCLUIDA`** (podendo ser refaturada) ou deixá-la
   `FATURADA` com um aviso de que a venda caiu?
2. Se voltar para `CONCLUIDA`, as peças devem **reservar de novo**? (O estoque já voltou ao livre
   no cancelamento — reservar de novo é coerente com "a OS está de pé outra vez".)
3. Vale para o **orçamento** também, ou só para a OS?

⛔ **Não implementei por conta própria**: as três respostas mudam o comportamento de uma rotina que
mexe em dinheiro e estoque, e a segunda em particular pode tirar do balcão uma peça que o lojista
já considerava livre.

---

### 56. Lacunas menores da OS (nenhuma bloqueia a operação) 🟢

Levantadas conferindo o código em 2026-08-28, depois de fechar o item 53:
- ✅ **Exportação de Dados já inclui as OS** (2026-08-29) — décima tabela, **uma linha por ITEM**,
  não por cabeçalho: é o item que carrega o preço **congelado** na aprovação e **quem executou**,
  que é a base da comissão; um CSV por cabeçalho esconderia as duas coisas. A tela não precisou de
  nada — ela já lista as tabelas que a API oferece.
- ✅ **Relatório de OS — FEITO em 2026-08-31, e CONFERIDO NO NAVEGADOR.**
  `/relatorio-ordens-servico` (V103), em Relatórios › Faturamento, com `moduloServicos: true`
  (some para quem não vende serviço). Spec em `docs/telas/relatorio-ordem-servico.md`.

  Três decisões, cada uma presa por um teste **sabotado** depois de passar: **dois eixos de data**
  (o Movimento conta cada evento pela sua própria data; a produtividade conta pelo trabalho
  entregue), **o executor é do ITEM** (não do cabeçalho, que é quem atendeu) e **item sem executor
  vira a linha "(SEM EXECUTOR)"** em vez de ser filtrado. ⛔ Comissão não aparece ali (quem paga é
  o Relatório de Comissões) e o desconto do cabeçalho não é rateado por executor.

  ⚠️⚠️ **Abrir a tela achou DOIS defeitos que 6 testes verdes e o `tsc -b` não pegaram:**
  1. **O relatório não gerava NENHUMA vez** — `ORDER BY (valor_servicos + valor_pecas)`, dois
     **aliases dentro de uma expressão**. O Postgres aceita alias do SELECT no `ORDER BY`
     **sozinho**, não dentro de expressão: *column "valor_servicos" does not exist*. A tela abre
     ordenando por `valorTotal`, então batia sempre — e os 5 testes chamavam **sem** `ordenarPor`,
     caindo no default. Hoje há `todasAsColunasOrdenaveisGeramSqlValido`, que percorre a allowlist
     inteira nas duas direções.
  2. **A coluna "Tempo Médio" saía vazia**, e ela existia: `COALESCE(AVG(…), 0)` transformava "não
     há o que medir" em "medi, deu zero", e arredondar para **uma** casa matava toda duração abaixo
     de 3 min (as OS reais do banco levaram de 0,0047 h a 0,0594 h). Hoje o campo é **nulo** quando
     não há medida — e só o nulo vira "—" — com 4 casas, e a tela escolhe a unidade (min/h/dias).
     Preso por `tempoMedioEhNuloSoQuandoNaoHaOQueMedir`, que tem o par: nulo × quase-zero.

  ✅ **Medido na tela, com os dados reais de dev:** 4 abertas / 3 concluídas / 3 faturadas /
  1 cancelada; serviços 160+80 = **240**, peças 183,60+0 = **183,60**, total **423,60** = Valor
  Faturado; ticket 423,60/3 = **141,20**; tempo médio **2 min**. Ordenação por coluna funciona.
  ✅ **PDF conferido no arquivo, não só na tela:** A4 paisagem (`/MediaBox [0 0 841.89 595.28]`),
  `/Count 1` página, "Página 1 de 1", rodapé com a loja e "Nainer ERP", e o conteúdo capturado em
  **tema claro** (verificado interceptando o canvas que vai para o PDF).
- **Campos não configuráveis por tenant** (`cfg_tela_campo`). ⚠️ O **orçamento também não tem**,
  então a OS está consistente com a tela irmã — é padrão de tela de *documento*, não lacuna.

✅ **Conferido e correto** (não confundir com pendência): RBAC (`AcoesPorTelaConferemTest` verde),
DRE (o serviço entra na receita — medido: R$ 423,60 no dia do teste), e a ausência de ordenação por
coluna e de bloco de auditoria, que o orçamento também não tem.

---

---

## 2026-08-28 (2) — módulo de Serviços: S1, S2, S3 e S4 IMPLEMENTADOS

`V085`, `V086` e `V087`. Serviço no catálogo (tipo imutável, sem estoque), os 8 leitores filtrados
e a **Ordem de Serviço** completa, virando venda pelo F5 do PDV. Spec: `docs/telas/ordem-servico.md`.
Suíte: **1062 testes verdes, 0 pulados**.

**O item 50 fechou:** ele respondeu *"sim, por padrão o módulo de serviço vai precisar ligar ele pra
funcionar, pois as empresas de serviço são menos que as de comércio"* — `cfg_usa_servicos` nasce
**desligado** (V085), como recomendado.

**Continuam abertos:** 49 (credenciais da NFS-e — é o bloqueio real), 51 (P2 ramos de serviço e P6
efeito no preço, este dependendo do item 28).

### 53. ✅ O que o S4 deixou para depois — **FECHADO no mesmo dia** (ver seção 2026-08-28 (3)).
  O que sobrou virou os itens **54** (agenda e executor por linha) e **56** (lacunas menores).

---

## 2026-08-28 — módulo de Serviços aprovado como próximo trabalho

O estudo está em **`docs/MODULOSERVICOS.md`** (§0.1 traz as decisões dele). O que ficou pendente,
por dono:

### 49. Credenciais da empresa para homologar a NFS-e 🔵
Ele tem uma empresa que pode testar, mas **as credenciais ainda não estão em mãos**. Sem elas o
bloco **S0** (prova de conceito contra o Emissor Nacional) e o **S6/S7** (emissão e ciclo de vida)
não saem do papel. ⚠️ Faltam três fatos sobre ela, e nenhum é dedutível: **regime** (MEI × ME/EPP do
Simples — muda o que sai na nota e se a obrigatoriedade já vale), **município** (adesão é de 100%
dos entes, mas *aderir ≠ operar*, e as fontes divergem entre 392 e 1.898 operando de fato) e
**Inscrição Municipal + CNAE de serviço** no cadastro da empresa.
⚠️ Falta também **o contador** que valida alíquota, retenção e o percentual de ISS do Simples —
nada dessa parte pode ser implementado por dedução. É o candidato nº 1 a virar bloqueio de terceiro,
como o CSRT virou.

### 50. ✅ P1 — serviço nasce ligado ou desligado? **RESPONDIDO: desligado** (V085).

<sub>Texto original abaixo, mantido para o histórico da decisão.</sub>

`cfg_usa_servicos` é `DEFAULT` de migration, e inverter default depois de existir tenant é
retrabalho medido (V054 → V055). **Recomendação: desligado**, como `cfg_usa_cor_grade` — a loja de
calçados não deve ganhar um seletor "Mercadoria/Serviço" que nunca vai usar. Precisa da resposta
**antes do bloco S1**.

### 51. ✅ Cinco ramos de SERVIÇO criados (V093) — o P6 (cota) segue junto do #28
**P2:** os 28 ramos são todos de varejo; uma oficina hoje se cadastra como `AUTOPECAS` ou `OUTROS` e
o dado de segmentação nasce errado. Recomendação: acrescentar oficina, salão/barbearia, assistência
técnica, clínica veterinária e lava-rápido, com os CNAEs carregados da fonte do IBGE.
**P6:** a oficina faz 40 vendas/mês de R$ 800 e a loja de calçados 400 de R$ 80 — mesmo faturamento,
cotas diferentes. Isso barateia o produto para o público novo, mas é receita que não vem. Depende do
item 28 (planos pagos).

### 52. ✅ O que dá para construir sem as credenciais — **CONSTRUÍDO** (S1–S4 em 2026-08-28).

<sub>Restam do bloco só a NFS-e (S6/S7, item 49) e o cadastro tributário da LC 116 (S5), que só faz sentido junto com ela. Texto original abaixo.</sub>

**Blocos S1 a S5** — catálogo com `tipo_item`, venda mista no PDV, papeleta com os dois blocos,
comissão por serviço, Ordem de Serviço e o cadastro tributário (lista da LC 116 carregada da fonte
oficial, como o NCM e as 27 UFs). É o v1 operando: petshop e oficina vendendo, com comissão e OS.
⭐ **A ordem recomendada coincide com o que a falta de credencial impõe:** validar a modelagem antes
de construir a nota em cima dela. Trava mesmo só o S6/S7.
⚠️ Do **S0** dá para adiantar a leitura da documentação técnica do Emissor Nacional (os PDFs foram
localizados e **não** lidos) e montar/assinar uma DPS localmente — o certificado A1 da MITRYUSCASH já
está cifrado no banco. Mas isso é preparação, **não** é prova: *"o XSD não é o contrato da SEFAZ"*
(no B9 a nota passou no schema e voltou `cStat 1010`).
