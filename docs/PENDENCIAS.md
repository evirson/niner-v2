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
> **Última revisão:** 2026-08-27, fim do dia (registrada a pedido dele: *"deixe todas estas pendências
> documentadas, não esqueça de nada, e quando eu te perguntar as pendências você me fala"*).

**Estado na data desta revisão:** 63 telas em uso · 1145 testes verdes (medido, não estimado).

---

## 🔴 Bloqueadas em terceiros

### 1. `cStat 974` — nenhuma NF-e modelo 55 autorizada
Chamado **aberto na SEFAZ/PR** (texto em `docs/fiscal/chamado-sefaz-pr-csrt-974.md`). Encerrado do
nosso lado: todas as hipóteses foram **medidas e descartadas** — idCSRT, CNPJ, ambiente, hash e
propagação (esta derrubada por um token novo, ativado minutos antes, falhando idêntico). A NFC-e 65
do mesmo emitente autoriza, o que isola o problema no cadastro do responsável técnico.
⛔ CSRT é segredo — nunca por chat; confere-se pelo tamanho da coluna cifrada
(`octet_length(decode(csrt_cifrado,'base64'))` = 64 ⇒ 36 caracteres em claro).
**Bola:** SEFAZ/PR.

---

## 🔵 Bola dele (dono do produto)

### 2. Teste real do Mercado Livre — ADIADO
M0–M7 prontos (escopo **A+B completo**), **zero chamadas reais** ao ML: tudo verificado contra
WireMock. Espera as credenciais do **usuário de teste**, que ele vai passar.
⚠️ Quando ele disser *"vamos testar"*, repassar **todos** os passos, **poucos por vez**.
⚠️ Não existe "conta de integrador" no ML — é conta comum, do **proprietário da solução**
(MITRYUSCASH, PJ, titular validado). Aplicação em **developers.mercadolivre.com.br/devcenter**,
⛔ **não** no painel do Mercado Pago (ele já trouxe credencial do MP por engano uma vez). Só o
`client_id` vem por chat; o secret vai por variável de ambiente.
Roteiro: `docs/MODULOMARKETPLACE.md` §12.

### 3. Ligar os 3 tópicos de notificação no painel do ML
`items`, `orders_v2`, `shipments` estão **desmarcados de propósito** — o endpoint
`/api/publico/webhooks/mercadolivre` não existia quando a aplicação foi criada. **Ele existe desde
o M5**, então os tópicos podem ser ligados. Passo de painel, não de código.

### 4. Nenhuma tela do módulo de marketplace foi aberta em navegador
Canais, Vincular Anúncios e Fila de Expedição só passaram por teste automatizado.

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

### 15. Pedido de marketplace sem estoque não vira venda — e fica tentando
Consistente com o PDV, mas a venda não existe no ERP embora o dinheiro seja real. Manter ou abrir
exceção? Decisão dele.

### 16. Um produto só alimenta UM anúncio por canal
Índice na V066 fecha o caso de anunciar o mesmo produto duas vezes para ganhar alcance. Confirmar ou
liberar com regra de rateio.

### 17. 🔔 Estorno não revoga assinatura
Dívida **conhecida e aceita**, política dele. **Pedido explícito: avisar em toda revisão/auditoria.**

### 18. Taxa do canal — pôr o valor
A carteira do canal nasce com **0%**; o ML cobra 11–19%. Enquanto ficar zerada, a **DRE
superestima o lucro**. Parte dele: cadastrar a taxa. (A parte minha está no item 20.)

---

## 🟢 Bola minha

### 19. A exportação de XML nunca rodou com período acima de 2.000 notas
A partição é validada pela aritmética e por um teste de congelamento, nunca por um período que
realmente estoure. Depende dele usar + de mim medir.

### 20. A tela não avisa que a taxa do canal está zerada
Enquanto `taxa = 0%`, a DRE superestima o lucro e nada na tela diz isso. Aviso é meu (par do item 18).

### 21. Tela de preço manual do anúncio não existe
`anuncio.preco_manual` é respeitada pelo gatilho e pelo manipulador, mas **nada a liga**.

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

### 27. ⚠️ Chamada HTTP ao canal roda dentro da transação do lote do outbox
Lote de 25 × timeout de 30 s. Desenho **preexistente**; risco registrado, a medir com volume real.

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

### 30. SPF/DKIM/DMARC do domínio `nainer.com.br` 🔵
O SMTP da Hostinger foi configurado hoje e o e-mail chega, mas sem esses registros a mensagem tende
a cair em spam nos destinatários. Ajuste no painel da Hostinger. **Bola dele.**

### 31. ⚠️ Conferir 3 campos da empresa que eu não consegui recuperar 🔵
Um `PUT` de teste meu apagou a ficha fiscal da empresa 1 (detalhe e correção no histórico do dia).
Restaurei tudo do XML da última NFC-e autorizada e o CNAE da consulta ao CNPJ — a Conformidade
Fiscal voltou a dizer "Pronto para emitir". **Mas Inscrição Municipal, telefone e e-mail não
estavam no XML**: se algum deles estava preenchido, precisa ser digitado de novo. **Bola dele.**

### 32. Token válido de redefinição não foi exercitado na tela 🔵
O fluxo tem teste automatizado ponta a ponta e a tela foi verificada com token inválido; o caminho
feliz no navegador exige clicar num link de e-mail real, **o que troca a senha da conta**. Basta
ele fazer uma vez com uma conta descartável. **Bola dele.**

### 33. ⚠️ RBAC: telas que compartilham controller herdam a mesma permissão 🟢
Três simplificações conscientes, registradas em `docs/telas/usuario-permissoes.md`: quem tem
**Contagem de Estoque** alcança também Diferenças, Efetivar e Zerar (mesmo controller); quem tem
**Recebimento de Crediário** alcança Reimpressão e Estorno; quem tem **Pesquisa de Vendas** com
"excluir" pode cancelar venda. Separá-las exige anotar método a método. **Bola minha**, quando
ele achar que vale.

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
