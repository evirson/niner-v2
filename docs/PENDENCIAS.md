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
> **Última revisão:** 2026-08-29 — dia inteiro. Manhã: **cinco rodadas de dois agentes caçadores
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
> **Hoje:** **27 itens abertos**, e a maioria é verificação no papel/navegador que só ele pode
> fazer. Precisam de DECISÃO dele: **#28** (planos pagos, adiado por ele), **#40** e **#47**
> (segurança e LGPD, que ele mandou guardar para cobrar depois). 🔔 E **#49** — ele prometeu as
> **credenciais da NFS-e para a segunda-feira**.

**Estado na data desta revisão:** 57 telas do ERP + 3 públicas · 1085 testes verdes, **de 0 a 5 pulados conforme a HORA** — o guard de meia-noite do `HorarioAcessoTest`, que se pula sozinho quando a janela pedida cruzaria a virada do dia (à meia-noite são 5 — 4 do horário de acesso e 1 do 2FA —, de manhã 0). ⚠️ Não é regressão, e o número oscilar é o mecanismo funcionando (tudo medido, não estimado; a contagem de telas é a de `docs/TELAS.md`, que declara a base).

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

### 5. Tela de Lucratividade nunca aberta em navegador
(O Relatório de Contas a Pagar foi aberto em 2026-08-26 e funciona.)

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

### 67. 🟢 Reimportar a mesma NF-e corrigida pode travar o arquivamento num laço a cada 10 min
O caminho do XML de entrada é `entrada/{ano}/{mes}/{chaveNfe}.xml`, e cancelar a entrada **libera a
chave** para reimportação (é comportamento desejado: "reimportar a mesma NF-e corrigida"). Se o XML
reimportado diferir em **qualquer byte** do já arquivado, `gravarComIdempotencia` levanta divergência,
o job engole, e tenta de novo a cada 10 minutos para sempre — com o `xml_bruto` da entrada nova nunca
saindo do banco. A correção é incluir o `id_movimento` no caminho do objeto; deixei para a próxima
rodada porque depende de decidir o que fazer com os objetos já gravados no caminho antigo.

## 🟢 Bola minha

### 19. A exportação de XML nunca rodou com período acima de 2.000 notas
A partição é validada pela aritmética e por um teste de congelamento, nunca por um período que
realmente estoure. Depende dele usar + de mim medir.

### 22. SVC — contingência da NF-e 55
Não implementada.

### 23. CSOSN 500 com ST retido (`cStat 938`)
Minha, mas **depende do contador**.

### 24. Efetivar Balanço e Tipo de Carteira não têm spec nenhuma
Duas telas em uso sem arquivo em `docs/telas/` (ver `docs/TELAS.md`).

### 25. `db/migration/README.md` parou na V035
As ~35 migrations seguintes nunca foram indexadas.

### 26. Itens adiados da auditoria de 2026-08-21
21+32, 25, 26+30, 27 — detalhe em `docs/PENDENCIAS-AUDITORIA-2026-08-21.md` (24 das 33 já
corrigidas em 08-22).

---

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

### 48. 🟢 Cobertura de teste de isolamento e privilégio 🟢
19 testes `isolamentoEntreTenants` para ~112 classes — ficam sem teste PDV/venda, caixa, contas a
pagar, crediário, transferência e os cadastros. O código está correto (foi lido), mas **nada trava
o comportamento contra a próxima edição**. E a suíte conecta como superusuário do container, então
os `GRANT/REVOKE` novos (V071, V079) não têm caso em `PrivilegiosNinerAppTest` — é o
`REVOKE INSERT/UPDATE/DELETE ON diretorio_login` que sustenta "só a trigger escreve". **Bola minha.**

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
- **Não há relatório de OS** — produtividade por mecânico, tempo médio de execução, OS abertas por
  período. Hoje só a lista com filtros.
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
