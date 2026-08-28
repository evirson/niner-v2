# Módulo de Serviços — mão de obra, ordem de serviço e NFS-e

**Produto:** Nainer (Vetor Sistemas) · **Banco:** `niner_db` · **Versão do documento:** 1.0
**Data:** 2026-08-28 · **Status:** ⛔ **ESTUDO — nada implementado, nenhuma linha de código escrita.**

> **Como este documento nasceu.** O dono do produto pediu, literalmente:
>
> > *"Hoje no Nainer não temos a possibilidade de atendimento de usuários que tenham SERVIÇOS em
> > seu portfólio... atendemos uma petshop para venda de produtos, mas o serviço de banho e tosa,
> > ou consulta veterinária, não está coberto. Ou atende uma oficina mecânica — a parte de peças o
> > ERP cobre, mas a mão de obra não cobre. Preciso de um estudo sobre implementar isso no ERP,
> > com a emissão do documento fiscal de serviço também."*
>
> E foi explícito: *"não quero que você faça nada, estamos apenas estudando o caso"*.

**Relação com os outros documentos.** Este é um **documento de módulo**, no mesmo lugar hierárquico
do `docs/MODULOFISCAL.md`. Complementa (não substitui) o `spec-driven-erp-varejo.md` (v2.0) e o
`docs/PLANO-DE-NEGOCIO.md`. Cada tela que sair daqui ganha arquivo próprio em `docs/telas/`; cada
decisão de arquitetura vira ADR no formato §6 da spec. Os **Anexos A e B** já saem nos templates
§5 (Spec de Feature) e §6 (ADR), prontos para virar a primeira spec aprovada.

**Convenções deste documento** (as mesmas do `MODULOFISCAL.md`):

- ✅ = decidido · 🔴 = pendente (dono do produto) · ⏭️ = adiado de propósito
- ⚠️ = fato de **fonte secundária**, ou armadilha medida neste repositório
- ⛔ = não faça · ⭐ = o ponto que muda a decisão
- **Toda afirmação sobre legislação ou concorrente carrega URL + data de consulta** (Anexo C). Onde
  a pesquisa não fechou, está escrito **"não confirmado"** — em vez de um número plausível.

---

## 0. Sumário das decisões propostas

Todas 🔴 até ele decidir. A coluna "Recomendação" é minha, com o porquê na seção indicada.

| # | Decisão | Recomendação | Onde |
|---|---|---|---|
| **DS1** | Como o serviço entra no catálogo | ✅ **Híbrido:** `produto.tipo_item` (`MERCADORIA`/`SERVICO`) + tabela de extensão 1:1 `produto_servico`. **Nem tabela separada, nem só uma flag** | §3.4 |
| **DS2** | Como o serviço não mexe no estoque | ✅ **Curto-circuito na TRIGGER** `fn_atualiza_estoque_movimento`, não nos serviços Java — mesma decisão da V054 e pelo mesmo motivo | §3.5 |
| **DS3** | O módulo é opt-in? | ✅ **Sim** — `cfg_geral.cfg_usa_servicos`, **desligado por padrão**. Loja de calçados não pode ganhar campo novo por causa da petshop (irmão do F12) | §3.7 / 🔴 pergunta P1 |
| **DS4** | Onde a mão de obra é vendida | ✅ **No PDV, como mais um item da mesma venda.** A Ordem de Serviço é um **orçamento com estado**, que vira venda pelo mesmo caminho do F5 | §4.3 |
| **DS5** | Comissão de serviço | ✅ **Percentual por serviço** (`produto_servico.perc_comissao`), que **sobrepõe** o do funcionário quando preenchido. Congelado na linha do movimento | §4.5 |
| **DS6** | Agenda / recorrência | ⏭️ **Fora do v1.** É onde os verticais (SimplesVet, Sispet) ganham e o Nainer não tem como ganhar agora | §4.6 |
| **DS7** | NFS-e: própria ou provedor? | ✅ **Própria, exclusivamente contra o Emissor Nacional (API do Sefin Nacional)** — atrás de uma interface `EmissorDeNfse`, para que trocar por provedor seja uma classe, não um projeto | §5.7 |
| **DS8** | Municípios com padrão próprio | ⛔ **Não atendidos, e isso é escopo de produto** — não funcionalidade faltando. É a irmã exata da DF37 | §5.7 |
| **DS9** | NFS-e mora em `documento_fiscal`? | ⛔ **Não.** Tabelas próprias (`nfse_*`). `documento_fiscal` é NF-e/NFC-e da carcaça até o osso | §5.1 |
| **DS10** | O v1 pode sair sem NFS-e? | ⚠️ **Meio-termo, decidido por ele em 2026-08-28:** o **produto** tem de oferecer a emissão (S5–S7 no escopo), mas **emitir é opção do lojista** — quem não emite opera com a papeleta, e isso é caminho permanente, não ponte. Mesmo desenho do F12 no fiscal. Ver §0.1 | §5.9 / §0.1 |
| **DS11** | Serviço conta na cota do plano? | ✅ **Conta a VENDA, como hoje** — não o item, não a nota. Nenhuma dimensão nova (ADR-015 / P6) | §7.6 |
| **DS13** | Quando a NFS-e é emitida | ✅ **Depois da papeleta, por decisão do usuário** — reusa `cfg_emite_fiscal_apos_venda`, o mesmo parâmetro da NFC-e. Decidido por ele em 2026-08-28 | §0.1 |
| **DS12** | Custo do serviço | ✅ **Campo do cadastro, preenchido pelo lojista**, default 0 — mas com **aviso na Lucratividade** quando zerado, senão serviço vira margem 100% | §7.5 |

**A decisão que muda tudo é a DS7**, e ela sai de um fato que só apareceu na pesquisa: **o Nainer
atende só MEI e Simples Nacional (DF37), e esses dois são exatamente as populações que a lei
obriga a emitir pelo Emissor Nacional** — um endereço federal, não 5.570 prefeituras. O motivo pelo
qual a NFS-e é *non-goal permanente* no `MODULOFISCAL.md` §4.4 (*"~5.500 regras municipais"*)
**deixou de valer para o público deste produto**. Ver §5.3.

---

---

## 0.1 ⭐ Decisões do dono do produto — 2026-08-28

Respondidas na mesma sessão em que o estudo ficou pronto. **Estas três fecham; o resto do documento
continua sendo recomendação minha.**

| # | Pergunta | Resposta dele |
|---|---|---|
| **P3** | A Ordem de Serviço entra no v1? | ✅ **Sim** |
| **P5** | Dá para entregar sem NFS-e? | ⚠️ **O produto tem de OFERECER a emissão — mas o lojista pode escolher só a papeleta.** Ver a leitura abaixo |
| **P4** | Existe CNPJ com Inscrição Municipal para testar? | ✅ **Sim, ele tem uma empresa que pode testar** |

### ⚠️ P5 — a leitura correta, depois do esclarecimento dele

A primeira versão desta seção registrou *"NÃO, a emissão é requisito do v1"* e estava **mais dura
que a decisão**. Ele esclareceu na sequência, com estas palavras:

> *"tem que ter a opção de emissão de NFS-e, mas talvez o cliente possa apenas emitir a papeleta de
> venda"*

**O que isso quer dizer, e é diferente das duas alternativas que eu tinha oferecido:**

| Nível | Quem decide | Decisão |
|---|---|---|
| **Produto** | a Vetor | A NFS-e **existe** no módulo. Não é "fica para a v2" — S5, S6 e S7 estão no escopo |
| **Instalação** | o lojista | Emitir é **opção dele**. Quem não emite opera com a papeleta/recibo, e isso é caminho **legítimo e permanente** — não remendo, não período de transição |

⭐ **Isto não é desenho novo: é exatamente o que o módulo fiscal já faz.** `fiscal_config_empresa`
tem `emite_nfce`/`emite_nfe`, `cfg_geral` tem `cfg_emite_fiscal_apos_venda`, e o princípio **F12**
diz que *"fiscal desligado não muda o ERP"*. A petshop que vende banho a pessoa física imprime a
papeleta e pronto — do mesmo jeito que hoje existe loja usando o PDV sem emitir NFC-e nenhuma.
A NFS-e de serviço herda esse desenho inteiro, incluindo o **F3** (a venda nunca some porque a nota
falhou) e o **F11** (bloqueio preventivo: a tela diz o que falta no cadastro antes de deixar tentar).

**Consequências, em ordem de importância:**

1. ✅ **A DS10 fica no meio, não revertida.** O Recibo de Serviço **entra** — e com papel
   permanente, não de ponte: é o documento de quem não emite e o que sai quando a emissão falha.
   O que **não** vale é a parte da minha recomendação que dizia *"entregar o módulo sem NFS-e
   nenhuma e deixá-la para depois"*.
2. ⚠️ **O S0 continua sendo o gate — mas o gate de outra coisa.** Ele não decide mais se *existe* v1;
   decide se a **opção** de emitir existe. Se a emissão contra o Emissor Nacional não fechar, o
   módulo ainda entrega petshop e oficina operando com papeleta — mas entrega **quebrado em
   relação ao que foi decidido**, e isso teria de voltar a ele, não ser absorvido em silêncio.
3. ⚠️ **A ordem de construção volta a ser livre, e eu mantenho a recomendação: S1–S4 primeiro.**
   Validar catálogo, venda, comissão e OS antes de construir a nota em cima. Entregar tudo junto
   **não obriga a construir tudo junto** — e a NFS-e é a parte que depende de terceiro.
4. ⚠️ **01/11/2026 continua sendo prazo, e agora com um recorte claro:** ME/EPP do Simples é
   obrigada ao Emissor Nacional a partir dessa data. Um lojista **ME** que escolher "só papeleta"
   depois dela está irregular — então a escolha de não emitir precisa ser **informada**, não
   silenciosa: a tela tem de dizer para quem a nota é devida. Para **MEI atendendo pessoa física**,
   a escolha é legítima e definitiva.
5. ⛔ **A regra que não muda:** o Recibo **não pode parecer nota** — sem chave, sem QR, sem
   "DANFE"/"DANFSe", sem número que imite numeração fiscal, e com o aviso de que não é documento
   fiscal (§5.9).


### ✅ DS13 — a papeleta sai primeiro; emitir é decisão do usuário (2026-08-28)

Definido por ele, com estas palavras:

> *"como padrão, como já acontece com a nota de venda de produtos: primeiro emite a papeleta, e o
> usuário decide se emite ou não a nota fiscal. Vamos fazer isso com a nota de serviço também."*

⭐ **Isso não cria regra nova — reusa a que já existe.** `cfg_geral.cfg_emite_fiscal_apos_venda` é
exatamente esse comportamento no PDV para a NFC-e (ver `docs/telas/configuracao-geral.md` e
`docs/telas/pdv.md`): a venda fecha, a papeleta imprime, e a emissão é um passo separado que o
operador aciona. A NFS-e entra **no mesmo parâmetro e na mesma tela**, alcançando um segundo
documento — não num parâmetro paralelo.

⚠️ **Por que isso importa mais do que parece:** é o que mantém o **F3** de pé (*a venda nunca some
porque a nota falhou*). Se a emissão fosse pré-requisito do fechamento, uma indisponibilidade do
Emissor Nacional pararia o balcão da petshop — e o balcão é onde o lojista sente o sistema.

⚠️ **A consequência de tela:** como a emissão é um passo à parte, a NFS-e **pendente** precisa ser
visível e reprocessável, como a NFC-e já é em Documentos Fiscais. Nota que ninguém emitiu por
esquecimento é pior que nota que falhou, porque não aparece em lugar nenhum.

### O que a resposta de P4 destrava, e o que ainda falta saber

A empresa de teste responde a metade que **não** se resolve por pesquisa. Mas o S0 precisa de três
fatos sobre ela antes de começar, e nenhum é dedutível:

- **Regime** — MEI ou ME/EPP do Simples? Muda o que sai na nota (§5.5) e se a obrigatoriedade já
  vale hoje ou só em 01/11/2026.
- **Município** — é ele que decide se dá para homologar: a adesão é de 100% dos entes, mas *aderir ≠
  operar*, e as fontes divergem sobre quantos operam de fato (§5.3, não confirmado).
- **Inscrição Municipal e CNAE de serviço** — sem os dois, o S0 não transmite. É o campo que a tela
  de Empresa já tem e que quase ninguém preenche (foi um dos três que se perderam no `PUT` de teste
  de 27/08).

⚠️ **P1, P2 e P6 continuam abertas** e não bloqueiam o S0 — mas P1 (nasce ligado ou desligado)
precisa ser respondida antes do **S1**, porque é `DEFAULT` de migration, e inverter default depois
de existir tenant é retrabalho medido (V054 → V055).

---

## 1. Por que este estudo existe agora

### 1.1 O `MODULOFISCAL.md` §4.4 já previa esta conversa

A tabela de **non-goals permanentes** do módulo fiscal tem uma linha que é o gatilho deste
documento:

| Item | Por que fica fora | Quando revisar |
|---|---|---|
| **NFS-e (serviço)** | Outro documento, outro layout, ~5.500 regras municipais. Não é o negócio do lojista de varejo | **Só se o ICP mudar** |

O ICP está mudando: é o dono do produto pedindo para atender petshop com banho e tosa e oficina com
mão de obra. Então a linha se cumpre — não se apaga (`feedback_non_goals_apodrecem`), vira
`~~texto original~~ — superado em <data>`.

⭐ **Mas o motivo escrito ali envelheceu por conta própria, independentemente do ICP.** "~5.500
regras municipais" era verdade quando o documento foi escrito. Hoje, para MEI e para ME/EPP do
Simples Nacional — que por DF37 são **100% da base do Nainer** — a emissão é **obrigatoriamente
pelo Emissor Nacional**, que é uma API só (§5.3). O non-goal caiu por dois lados ao mesmo tempo.

### 1.2 O que o produto perde hoje, em dinheiro

Dos **28 ramos de atividade** cadastrados na V072 (`cfg_ramo_atividade`), **os 28 são varejo de
mercadoria**. Não existe "oficina mecânica", "salão de beleza", "assistência técnica" nem "clínica
veterinária". E dois dos 28 são exatamente a **metade-varejo** dos dois exemplos que ele deu:

- `PET_SHOP` — *Pet shop* (a loja vende ração; o banho e tosa não cabe)
- `AUTOPECAS` — *Autopeças* (a loja vende a peça; a mão de obra não cabe)

⭐ Ou seja: **o produto já vende para essas duas lojas, e as atende pela metade.** Não é um mercado
novo a conquistar — é um mercado já contratado saindo pela porta quando descobre que a outra metade
do faturamento dele não entra no sistema. Uma oficina fatura tipicamente mais em mão de obra do que
em peça; uma petshop de bairro vive do banho semanal.

### 1.3 O relógio da NFS-e já está correndo — e é mais curto que o do IBS/CBS

O `MODULOFISCAL.md` §2.3 registrou que o IBS/CBS vira obrigatório para 100% da base em
**04/01/2027**. A NFS-e chega **antes**:

| Quem | Obrigatório desde | Base |
|---|---|---|
| **MEI** prestando serviço a **pessoa jurídica** | **01/09/2023** | Res. CGSN 169/2022 + Res. CGNFS-e 3/2023 |
| **MEI** prestando a **pessoa física** | facultativo (sem data) | regra geral do MEI |
| **ME/EPP do Simples Nacional** prestadoras de serviço | **01/11/2026** | **Res. CGSN 191/2026** (adiou da Res. CGSN 189/2026, que marcava 01/09/2026) |

**Faltam ~2 meses** para a segunda linha. Isso não significa "corra" — significa que **qualquer
promessa de NFS-e feita ao mercado antes de a coisa existir vira reclamação em novembro**. Ver §7.7
(riscos) e a pergunta 🔴 P5.

---

## 2. O problema, nas duas lojas que ele citou

Concretamente, o que hoje **não dá para fazer**. Cada linha aponta a tabela, a tela ou a coluna.

### 2.1 Petshop — banho e tosa

| O que a loja precisa | O que o Nainer faz hoje | Onde trava |
|---|---|---|
| Cadastrar "BANHO E TOSA — PORTE MÉDIO" com preço | Só existe `produto`, que é mercadoria da primeira à última coluna | `produto` não tem discriminador de tipo. Tem `peso_bruto`, `peso_liquido`, `origem_mercadoria`, `fator_conversao_tributavel`, `id_grade`, `cest`, `ex_tipi` — **todos NOT NULL com default**, todos sem sentido para um banho |
| Vender o banho no caixa | O PDV só aceita `idVariacao` (`PdvDtos.ItemVendaRequest`), que é FK para `produto_barra` | O serviço teria que ganhar uma variação e **queimar um EAN-13** do gerador global (`gerar_ean13_interno()`, `cfg_ean_gerador`) para algo que nunca terá etiqueta |
| Não controlar estoque de banho | A trigger `fn_atualiza_estoque_movimento` debita `produto_estoque` em **todo** `INSERT` em `produto_movimento_detalhe` | O Relatório de Estoque passaria a listar *"BANHO E TOSA: −412 UN"*, e a **Contagem de Estoque** pediria para o lojista contar banhos |
| Saber quem tosou, para pagar comissão | `produto_movimento_detalhe.id_funcionario` **existe** e é preenchido | ⭐ Esta é a boa notícia: o vínculo já é **por linha**, não por venda — o PDV é que grava o mesmo funcionário em todas |
| Comissão diferente do tosador × do balconista | Só existe `funcionario.perc_comissao` — **um** percentual global por pessoa | Não há onde morar uma comissão por serviço |
| Dar nota do banho | O módulo fiscal emite NFC-e 65 e NF-e 55 | `documento_fiscal.modelo` tem `CHECK (modelo IN (55, 65))`. Banho não é mercadoria: **não vai em NFC-e** |

### 2.2 Oficina mecânica — peça + mão de obra na mesma conta

Tudo acima, mais três coisas que só a oficina tem:

| O que a oficina precisa | Onde trava |
|---|---|
| Uma **ordem de serviço** que junte a peça e a mão de obra, aberta hoje e fechada em três dias | O que mais se aproxima é o **Orçamento** (V058) — mas ele é **imutável** (R1), não tem estado de execução, e `orcamento_item.id_variacao` é NOT NULL para `produto_barra` |
| **Dois documentos fiscais** para a mesma conta: NFC-e (ou NF-e 55) da peça + NFS-e da mão de obra | O índice `documento_fiscal_uma_por_venda_uk` é por `(tenant, venda, modelo)`, então dois documentos de modelos diferentes **já coexistem** hoje. Mas não há NFS-e nenhuma |
| Saber a margem real da OS | DRE e Lucratividade tiram CMV de `produto_movimento_detalhe.preco_custo`. Mão de obra com custo `0` vira **margem 100%** — o mesmo defeito que a V059 documentou nas vendas sintéticas da importação |

### 2.3 ⭐ O fato central: **a venda não tem tabela de itens**

Este é o ponto que decide a modelagem inteira, e ele não aparece em nenhuma prosa do projeto — só
lendo o schema.

**Não existe `venda_item`.** `venda` (V018) tem 11 colunas e nenhuma de valor:

```
venda: id_venda · id_tenant · id_empresa · id_cliente · data_venda · tipo_operacao
       cancelada · data_cancelamento · id_usuario_cancelamento · motivo_cancelamento
       id_caixa (V025) · origem (V059) · importado_em (V059)
```

O comentário da própria migration é explícito: *"total é derivado do ledger"*. **O item da venda
É a linha do ledger de estoque:**

```
venda ──(id_venda)── produto_movimento_mestre ──── produto_movimento_detalhe
                     tipo_movimento = 'VENDA'      id_variacao → produto_barra
                                                   credito_debito = 'D'
                                                   preco_venda, preco_custo, id_funcionario
```

E é dessa tabela que **33 arquivos Java** leem — medido com `grep`, não estimado. Entre eles:

| Quem lê | Para quê |
|---|---|
| `DreService` | receita, CMV e comissão do DRE |
| `LucratividadeService` | venda, custo, comissão |
| `RelatorioComissoesService` | a comissão inteira |
| `RelatorioVendasService`, `PesquisaVendaService` | o relatório e o hub de vendas |
| `VendaFiscalAssembler` | **os itens da NFC-e** |
| `ClienteHistoricoService`, `CrmService` | histórico e segmentação |
| `DevolucaoProdutoService`, `CancelamentoVendaService` | devolução e cancelamento |
| `EntradaMercadoriaService`, `TransferenciaService`, `BalancoEstoqueService`, `DevolucaoCompraService` | os outros movimentos |

⛔ **A consequência é dura e precisa ser dita antes de qualquer alternativa:** *se um item de venda
não gerar linha no ledger, ele não existe para o DRE, para a Lucratividade, para as Comissões, para
o Relatório de Vendas, para a papeleta, para o histórico do cliente e para o CRM.* Não com erro —
**em silêncio**, que é a forma que este repositório já provou ser a mais cara (V059, o caixa às 21h,
o job de contingência).

---

## 3. As três alternativas de modelagem

O pedido pede prós e contras honestos das três. Vão aqui com o custo por área, e a recomendação no
fim.

### 3.1 Alternativa (a) — uma flag `tipo_item` em `produto`

Uma coluna `tipo_item` (`MERCADORIA` | `SERVICO`) em `produto`, reaproveitando `produto_barra`,
`produto_movimento_*`, o PDV e todos os relatórios.

**A favor:**

- ⭐ **Todo o caminho de venda funciona no primeiro dia, sem tocar em 33 leitores.** DRE,
  Lucratividade, Comissões, Relatório de Vendas, papeleta, histórico do cliente: nada muda.
- Nenhuma coluna de `produto` é obstáculo real: `codigo_ncm` já é *nullable*; `peso_bruto`,
  `peso_liquido`, `origem_mercadoria`, `fator_conversao_tributavel` e `id_grade` são `NOT NULL`
  **com DEFAULT**, então o `INSERT` passa. O problema deles é semântico (aparecem na tela), não
  estrutural.
- O `unidade_comercial` **já existe** (`NOT NULL DEFAULT 'UN'`, V017 pós-fiscal) — e é a única
  coluna que a NFS-e também usa.

**Contra:**

- O serviço precisa de linha em `produto_barra` só para existir um `id_variacao`, e o `sku` é
  `NOT NULL` gerado por `gerar_ean13_interno()`. **Queima um EAN-13 do sequencial global** por
  serviço cadastrado. Não é grave (o sequencial tem 8 dígitos), mas é sujeira.
- A trigger de estoque dispara igual, e `produto_estoque` ganha uma linha que só desce. Sem
  tratamento, contamina Relatório de Estoque, Contagem de Estoque, Kardex e Exportação.
- Não há onde guardar o que **só serviço tem**: código da LC 116, alíquota de ISS, duração,
  comissão própria. Enfiar isso em `produto` daria ~8 colunas nulas para 100% dos produtos de
  quem nunca vende serviço.
- ⚠️ **`VendaFiscalAssembler` passaria a montar a NFC-e com a mão de obra dentro** — item de
  serviço com `<prod>` e NCM. Rejeição na SEFAZ, ou pior, autorização de uma nota errada. Este é
  o defeito mais perigoso da alternativa e **precisa de correção explícita**, não sai de graça.

### 3.2 Alternativa (b) — tabela `servico` separada, PDV aceitando os dois

Um `servico` novo, um `venda_servico_item` novo, e o PDV somando duas listas.

**A favor:**

- Conceitualmente limpo. Serviço não é produto, e a modelagem diria isso.
- Nenhuma coluna estranha em `produto`, nenhuma linha estranha em `produto_estoque`, nenhum EAN
  queimado.
- A tabela de item de serviço nasceria já com o que serviço precisa (profissional, duração,
  código LC 116 congelado, comissão da linha).

**Contra — e é aqui que ela morre:**

- ⛔ **Cria a tabela de itens de venda que o sistema decidiu não ter.** A partir daí, "os itens da
  venda" passam a estar em dois lugares, e **cada um dos 33 leitores vira um `UNION`**. Não é
  refatoração: é reescrever a receita do DRE, do CMV, da comissão e do relatório de vendas em duas
  versões que precisam concordar. O `CLAUDE.md` já registra o custo desse padrão em outro contexto
  (o gatilho de preço que **não** foi escrito em plpgsql, justamente para não haver "duas
  implementações da mesma regra de dinheiro, que divergem no dia em que só uma for corrigida").
- Enquanto os `UNION` não existirem, **serviço é invisível** para todos eles — e invisível sem
  erro. Um mês inteiro de banho e tosa não aparece na DRE e ninguém recebe uma mensagem.
- O PDV teria dois caminhos de item, duas validações, dois DTOs. A papeleta térmica (42 colunas)
  teria duas fontes.
- Custo de tela: a Configuração de Tela (`cfg_tela_campo`), a Importação, a Exportação, o RBAC
  (`cfg_tela`) e a Conformidade Fiscal ganhariam uma segunda entidade cada.

### 3.3 Alternativa (c) — híbrido: flag + extensão 1:1 + curto-circuito na trigger

`produto.tipo_item` (como em (a)) **mais** uma tabela `produto_servico` 1:1, que só existe para as
linhas `tipo_item = 'SERVICO'`, carregando o que é exclusivo de serviço.

```
produto (tipo_item = 'SERVICO')
   └── produto_servico  (1:1, PK = id_produto)
         codigo_tributacao_nacional   -- cTribNac, 6 dígitos (LC 116 + desdobro)
         codigo_tributacao_municipal  -- 3 dígitos, opcional (só se o município exigir)
         aliquota_iss                 -- numeric(5,2), informada pelo contador
         iss_retido_padrao            -- boolean
         local_incidencia             -- PRESTADOR | TOMADOR  (art. 3º da LC 116)
         duracao_minutos              -- smallint, opcional (insumo da agenda futura)
         perc_comissao                -- numeric(5,2) NULL = usa a do funcionário
         custo_referencia             -- numeric(12,2), DS12
```

**Por que é a resposta certa:**

- Herda **todo** o "a favor" da (a): zero mudança nos 33 leitores, PDV inalterado no essencial,
  relatórios funcionando no primeiro dia.
- Resolve o "contra" que era estrutural na (a): o que só serviço tem mora numa tabela que só
  serviço tem. **Nenhuma coluna nula em `produto` para quem não vende serviço.**
- É a **mesma forma** que o projeto já usa três vezes: `produto` + `produto_imagem`, `produto` +
  `produto_categoria`, `empresa` + `id_ramo`. Não inventa padrão.
- O problema da trigger vira **uma linha** (§3.5), e no lugar certo.

**O que ela custa, dito com honestidade:**

- Ainda queima o EAN-13 por serviço (a variação continua sendo o que o item de venda referencia).
  ⏭️ Dá para tratar depois: `gerar_ean13_interno()` só é chamada de `ProdutoBarraService`, linhas
  239 e 261 — um `sku` de faixa própria para serviço é mudança local. **Não vale fazer no v1.**
- Precisa de **duas** correções que não saem de graça, e ambas são "silenciosas se esquecidas":
  o `VendaFiscalAssembler` tem de excluir o item de serviço da NFC-e, e a Conformidade Fiscal tem
  de parar de reprovar serviço por "sem NCM".

### 3.4 ✅ Recomendação DS1 — híbrido (c)

| Área | (a) flag só | (b) tabela separada | **(c) híbrido** |
|---|---|---|---|
| **PDV** | inalterado | 2 caminhos de item, 2 DTOs, papeleta com 2 fontes | inalterado |
| **Ledger de estoque** | polui `produto_estoque` | limpo | limpo, via trigger (§3.5) |
| **DRE / CMV** | funciona | ⛔ reescrever em `UNION`, ou serviço some | funciona |
| **Lucratividade** | funciona (mas custo 0 = margem 100%) | ⛔ idem | funciona (com DS12) |
| **Comissões** | funciona, sem comissão própria | ⛔ idem | funciona, com comissão própria |
| **Relatório de Vendas / Pesquisa** | funciona | ⛔ idem | funciona |
| **Histórico do cliente / CRM** | funciona | ⛔ idem | funciona |
| **Importação / Exportação** | 1 coluna nova na planilha | 2 planilhas novas | 1 planilha nova (serviços) |
| **Etiqueta** | ⚠️ serviço apareceria na busca | não aparece | filtro por `tipo_item` |
| **Módulo fiscal (NFC-e)** | ⚠️ **serviço entraria na nota** | não entra | filtro explícito + teste |
| **Módulo fiscal (NFS-e)** | sem lugar para LC 116/ISS | tem lugar | `produto_servico` |
| **Migrations** | 1 | 3–4 + backfill | 2 |
| **Superfície de regressão** | média | **alta** | **baixa** |

⭐ O critério que decide não é elegância, é **quantos lugares silenciosos podem ficar errados**. A
(b) é a mais bonita no diagrama e a que mais produz erro invisível neste sistema específico, porque
este sistema derivou tudo do ledger. A (c) paga um pouco de feiura ("serviço mora em `produto`")
para não pagar 33 `UNION`.

> **Para quem ler isto daqui a um ano e achar estranho:** sim, `produto` passar a significar
> "coisa vendável" em vez de "mercadoria" é uma torção do vocabulário. A alternativa era criar a
> tabela de itens de venda que o projeto conscientemente não tem, e refazer a apuração de dinheiro
> em dois lugares. Entre um nome torto e duas contas de lucro, o nome torto é mais barato — e é
> reversível: renomear `produto` para `item` é um `ALTER TABLE ... RENAME` mais um sweep, no dia em
> que valer a pena.

### 3.5 ✅ DS2 — a regra de "não mexe no estoque" mora na TRIGGER

É a mesma decisão da **V054** (`cfg_permite_estoque_negativo`) e da falecida **V067** (gatilho de
sincronização), e pelo mesmo motivo, escrito no `CLAUDE.md`:

> *"Mexem em estoque **oito rotinas** — PDV, transferência, devolução ao fornecedor, devolução de
> venda, cancelamento de entrada (de que ninguém lembra), cancelamento de devolução, balanço e
> importação — mais o que vier. Espalhar o aviso por serviço garante **matematicamente** que uma
> fique de fora."*

`produto_movimento_detalhe` é o **único** caminho por onde o saldo se mexe. Então:

```sql
-- dentro de fn_atualiza_estoque_movimento(), ANTES de aplicar o delta
IF fn_item_e_servico(NEW.id_tenant, NEW.id_variacao) THEN
    RETURN NEW;   -- serviço não tem saldo: nem cria linha em produto_estoque
END IF;
```

⚠️ **Três armadilhas que a V054 já mediu e que valem aqui igual:**

1. **O caminho comum não pode pagar por isso.** A V054 registra que `saldo >= 0` sai na primeira
   linha *"senão uma importação de 10 mil linhas pagaria por isso"*. A consulta de `tipo_item`
   precisa ser um `EXISTS` indexado, e idealmente sair antes por um caminho barato — ou, melhor,
   **denormalizar `tipo_item` para `produto_barra`**, que é a tabela que a trigger já tem em mãos
   pelo `id_variacao`. É uma coluna a mais e uma consulta a menos no caminho mais quente do
   sistema.
2. **O `UPDATE` e o `DELETE` também passam por ali**, e o curto-circuito tem de valer nos três
   ramos — senão apagar um item de serviço de uma venda cancelada **credita estoque de um serviço**.
3. A trigger é `AFTER ... FOR EACH ROW`; devolver `NEW` sem tocar em nada é seguro, mas o teste
   tem de **conferir o banco** (`SELECT count(*) FROM produto_estoque WHERE id_variacao = ?` = 0),
   não o status HTTP.

### 3.6 O que mais precisa de filtro por `tipo_item` (a lista, não uma amostra)

Achado varrendo os leitores. Cada linha é um lugar onde esquecer o filtro produz erro **silencioso**:

| Onde | O que acontece se esquecer |
|---|---|
| `VendaFiscalAssembler` (montagem dos itens da NFC-e/NF-e) | ⛔ **A mão de obra vai dentro da NFC-e**, com NCM e CFOP de mercadoria. O pior caso não é rejeitar — é **autorizar** |
| `ConformidadeFiscalService` (`CASE WHEN p.codigo_ncm IS NULL THEN 'sem NCM'`) | Todo serviço vira pendência permanente na tela que existe para dizer que está tudo certo |
| `RelatorioEstoqueService`, Kardex, Contagem de Estoque | O lojista é convidado a contar banhos |
| `EtiquetaEmissaoService` | Etiqueta de código de barras de "CONSULTA VETERINÁRIA" |
| `ExportacaoService` (planilha `codigo_barras` e `estoque`) | Serviço na planilha de estoque |
| `EstoqueImportador` | Planilha de estoque inicial referenciando serviço |
| `ProdutoService.excluir()` (fallback de inativação por ter variação) | serviço nunca apagaria de verdade — provavelmente aceitável, mas tem de ser decidido, não descoberto |
| Busca de produto do PDV (F2) | ⭐ Aqui o filtro é o **contrário**: o serviço **precisa** aparecer, e o operador precisa distinguir. Ícone/coluna na lista |

⭐ **E o conserto durável não é o filtro, é o teste** — mesmo raciocínio do `AcoesPorTelaConferemTest`
(V081): um teste que varre `api/src/main` procurando consulta a `produto`/`produto_barra` **sem**
predicado de `tipo_item` nos contextos de estoque e fiscal. Sem isso, a próxima tela nova volta a
divergir e ninguém descobre até um serviço aparecer numa nota fiscal.

### 3.7 ✅ DS3 — o ramo de atividade ajuda, mas quem liga é um parâmetro

O `ramo_atividade` (V072) **não influencia tela nem menu** hoje — o próprio SQL diz: *"Nada no ERP
depende dele para funcionar."* Duas formas de usá-lo aqui:

**⛔ O que não fazer:** derivar a visibilidade do módulo do ramo. Petshop com ramo `PET_SHOP` que
só revende ração ganharia campos que não usa; oficina cadastrada como `OUTROS` não ganharia nada.
Ramo é **sugestão**, e a decisão dele sobre ramo foi literal: *"dar a sugestão, mas o usuário
define"*.

**✅ O que fazer:** um parâmetro em `cfg_geral`, no padrão de `cfg_usa_cor_grade`:

```
cfg_geral.cfg_usa_servicos  boolean NOT NULL DEFAULT false
```

Desligado, **nada muda** para quem não vende serviço — é o irmão exato do **F12** do módulo fiscal
(*"fiscal desligado não muda o ERP"*). Ligado, aparecem: o tipo no cadastro de produto, o filtro na
listagem, a aba de dados de serviço, a coluna de serviço nos relatórios e (mais tarde) a Ordem de
Serviço no menu.

E o ramo entra onde ele serve de verdade: **sugerir ligar**. Se `empresa.id_ramo` for `PET_SHOP` ou
`AUTOPECAS`, a tela de Parâmetros do Sistema sugere o parâmetro. Sugere — não liga.

⏭️ **Consequência lateral:** a lista de 28 ramos vai precisar crescer com ramos de serviço (oficina
mecânica, salão e barbearia, assistência técnica, clínica veterinária, lava-rápido…). Isso é seed
novo, não schema — `cfg_ramo_atividade` é global e o `id_ramo` é fixo. Ver pergunta 🔴 P2.

---

## 4. O que muda na operação

Serviço tem seis coisas que mercadoria não tem. Nem as seis cabem num v1, e o produto tem **P6 —
simplicidade primeiro**. A tabela diz o que entra e por quê.

| # | O que serviço tem | v1? | Porquê |
|---|---|---|---|
| 1 | **Profissional que executa** | ✅ **Sim** | A coluna já existe (`produto_movimento_detalhe.id_funcionario`). É a mudança mais barata do estudo inteiro |
| 2 | **Comissão diferente da de produto** | ✅ **Sim** | Sem ela o relatório de comissões mente, e comissão é o motivo de o tosador conferir o sistema |
| 3 | **Ordem de serviço** (peça + mão de obra, com estado) | ✅ **Sim, mínima** | É o caso da oficina inteiro. Sem ela, o Nainer atende petshop e não atende oficina |
| 4 | **Duração / tempo** | 🟡 **campo, sem uso** | `produto_servico.duracao_minutos` nasce porque a agenda vai precisar; o v1 só o exibe |
| 5 | **Agendamento** | ⏭️ **Não** | §4.6 |
| 6 | **Recorrência** (banho mensal, revisão dos 10 mil km) | ⏭️ **Não** | §4.6 |

### 4.1 O profissional que executa — já está pronto e ninguém sabe

`produto_movimento_detalhe.id_funcionario` é **por linha**, com FK composta para `funcionario`, e o
comentário da própria V018 registra a intenção: *"Funcionário/comissão por item ficam em
`produto_movimento_detalhe.id_funcionario`, não aqui"*.

O PDV hoje grava **o mesmo** `idFuncionario` em todas as linhas, porque
`EfetivarVendaRequest.idFuncionario` é único por venda. Para serviço, basta o `ItemVendaRequest`
aceitar um `idFuncionario` opcional por linha:

- linha **sem** funcionário → herda o da venda (comportamento de hoje, inalterado);
- linha **com** funcionário → é quem executou aquele serviço.

⭐ Isso resolve o caso real da petshop: **a atendente vende, o tosador executa**. Uma venda, dois
funcionários, cada um com sua comissão. E resolve sem tabela nova, sem migration de dado e sem
tocar em nenhum dos 33 leitores — o `RelatorioComissoesService` já agrupa por
`(v.id_empresa, pmd.id_funcionario)`.

⚠️ **Guarda de contrato:** `idFuncionario` de linha tem de ser validado contra `funcionario` do
mesmo tenant **e ativo**, com o mesmo `SELECT` que `PdvVendaService` já faz na linha 817. Não é
"mais um campo do DTO": é uma chave que vai virar dinheiro no bolso de alguém.

### 4.2 A Ordem de Serviço — o que ela é, e o que ela **não** é

⭐ **Ela não é uma tela nova do zero: é o Orçamento com estado de execução.** O Orçamento (V058) já
tem cliente, funcionário, itens com preço congelado, validade, cinco estados, conversão em venda
pelo F5 do PDV e o `CHECK` que amarra situação × `id_venda`. Falta-lhe exatamente três coisas para
virar OS:

| Falta | Por quê |
|---|---|
| **Ser mutável** | R1 do Orçamento diz *"o orçamento é imutável"*, e está certo para orçamento. Uma OS **muda**: o mecânico abre o motor e acha mais serviço |
| **Estado de execução** | `ABERTO → APROVADO → EM_EXECUCAO → CONCLUIDO → FATURADO` — separado da situação comercial |
| **Objeto do serviço** | O veículo (placa) ou o animal (nome). Sem isso a oficina não acha a OS de ontem |

Duas formas de fazer, e a recomendação:

- **(i) Estender `orcamento` com `tipo = ORCAMENTO | ORDEM_SERVICO`.** Reaproveita a conversão em
  venda, a numeração, o RLS, a tela de busca. ⚠️ Mas quebra a R1 para metade das linhas, e
  "imutável quando é uma coisa, mutável quando é outra" é a espécie de regra que produz o bug de
  2029.
- **✅ (ii) Tabela `ordem_servico` própria, que reaproveita o *mecanismo* de conversão.** Copia a
  forma do orçamento (é o padrão de tela que funcionou), mas com estados e mutabilidade próprios,
  e desemboca no **mesmo** `PdvVendaService.efetivarVenda` — a OS aprovada abre no PDV como o
  orçamento abre, e vira venda pelo caminho já testado.

⭐ Com (ii), **o dinheiro continua entrando por uma porta só**. Nenhuma rotina nova grava
`caixa_detalhe`, `contas_receber`, o ledger ou o documento fiscal. É o **F1** do módulo fiscal
aplicado ao módulo de serviços: *a OS é consequência, a venda é a causa.*

**O que a OS do v1 NÃO faz** (non-goals explícitos, para não crescer sozinha): não agenda, não
manda WhatsApp, não tem checklist com foto, não tem aprovação digital do cliente, não tem
apontamento de horas, não reserva peça em estoque (mesmo raciocínio da R8 do orçamento).

### 4.3 ✅ DS4 — onde a mão de obra é vendida

**No PDV, como mais um item da mesma venda.** Não numa tela de "faturar OS".

Motivo: o PDV é onde estão o caixa aberto obrigatório, o split-tender, o desconto máximo, o limite
de crédito do crediário, o vale-mercadoria, a cota do plano, a papeleta e a emissão fiscal. Uma
segunda porta de faturamento teria de reimplementar as nove — e a memória
`feedback_teto_com_porta_ao_lado` é exatamente sobre isso: *"o limite existe num caminho e o caminho
vizinho não passa por ele"*.

### 4.4 A venda mista, e os dois documentos

A oficina fecha **uma** venda com peça e mão de obra. Fiscalmente são **dois** documentos:

```
venda 4711
 ├── item: PASTILHA DE FREIO   (tipo_item = MERCADORIA) ──▶ NFC-e 65  (ou NF-e 55 se PJ)
 └── item: TROCA DE PASTILHAS  (tipo_item = SERVICO)    ──▶ NFS-e
```

Isso **já cabe** no schema: `documento_fiscal_uma_por_venda_uk` é por `(tenant, venda, modelo)`, e
a NFS-e nem vai nessa tabela (DS9). O que precisa mudar:

1. `VendaFiscalAssembler` filtra `tipo_item = 'MERCADORIA'` — e se **não sobrar item nenhum**
   (venda 100% serviço), **não emite NFC-e**, gravando `NAO_EMITIDO` com motivo legível, em vez de
   montar uma nota com zero itens.
2. O `cfg_emite_fiscal_apos_venda` passa a governar as duas emissões.
3. A papeleta térmica mostra os dois blocos e os dois números de documento.

⚠️ **A NFC-e "conjugada"** (produto + serviço na mesma nota), que o Vhsys oferece condicionada ao
município ([ajuda.vhsys.com.br](https://ajuda.vhsys.com.br/categorias/servicos/nota-fiscal-de-servico-nfs-e), consultado 2026-08-28), **fica fora**: depende de regra municipal específica, dobra a
complexidade do montador na área que este projeto já provou ser a mais cara de acertar (`cStat`
253, 733, 974, 1010…), e o ganho é uma nota em vez de duas.

### 4.5 ✅ DS5 — comissão de serviço

Hoje existe **um** percentual, em `funcionario.perc_comissao`, e o
`RelatorioComissoesService` calcula `valorLiquido × perc_comissao / 100` **na consulta, sem nada
persistido**. Isso é elegante e tem um efeito colateral que ninguém notou: **mudar o percentual do
funcionário reescreve a comissão de todos os meses passados.**

Para serviço, a prática de mercado é comissão **por serviço**, não por pessoa — o tosador ganha 20%
do banho e 10% da tosa. Confirmado no Sispet (comissão em R$ fixo ou % **por serviço**, calculada ao
vincular o colaborador, [sispet.com.br](https://sispet.com.br), consultado 2026-08-28) e no
GestãoClick (comissão *"por venda, por serviço e por produto"*,
[gestaoclick.com.br](https://www.gestaoclick.com.br/controle-de-comissao-de-vendedores/), consultado
2026-08-28).

**Recomendação, em duas partes:**

- `produto_servico.perc_comissao numeric(5,2) NULL` — **NULL significa "usa a do funcionário"**.
  Preenchido, sobrepõe. Regra de uma linha, sem tela nova.
- ⭐ **Congelar o percentual na linha do movimento.** Uma coluna
  `produto_movimento_detalhe.perc_comissao` que grava o percentual **aplicado naquele dia**, e o
  relatório passa a somar dela, com `COALESCE` para o histórico. Isso conserta de lambuja o defeito
  descrito acima, alinha com o **F9** do módulo fiscal (*"todo cálculo é auditável e reproduzível"*)
  e com o **P3** (auditabilidade).

⚠️ **Isto muda o comportamento de um relatório existente**, então tem de vir com o par de testes
que a memória `feedback_caso_negativo_pega_guarda_que_expulsa_todos` cobra: um provando que a
comissão nova é a congelada, e outro provando que **venda antiga não muda de valor** quando o
percentual do funcionário é editado.

⛔ **Comissão continua sendo só cálculo, nunca lançamento** — a decisão de escopo nº 1 do
`relatorio-comissoes.md` (*"não existe registro de comissão paga/apurada"*) permanece. Não criar
`comissao_a_pagar` neste módulo.

### 4.6 ⏭️ O que fica de fora do v1, e por quê

**Agenda.** É onde os verticais ganham, e ganham com folga: o SimplesVet tem agenda em **todos** os
planos, a partir de R$ 157/mês ([simples.vet/precos](https://simples.vet/precos/), consultado
2026-08-28); o Sispet tem "Super Agenda" com **recorrência configurável até 52×/ano**
([sispet.com.br](https://sispet.com.br), consultado 2026-08-28). Uma agenda que não seja boa é pior
que nenhuma — o lojista continua no caderno **e** desconfia do resto do sistema. E agenda de verdade
puxa lembrete por WhatsApp, no-show, encaixe, bloqueio de horário e visão por profissional. É um
produto, não uma tela.

**Recorrência.** Depende da agenda. Sem ela, "banho mensal" é um lembrete, e lembrete sem canal de
envio é uma lista que ninguém abre.

**Ficha do pet / do veículo.** ⭐ Aqui vale um meio-termo barato: a OS ganha um campo de texto
**"objeto do serviço"** (a placa, o nome do animal), **pesquisável**. Não é ficha, não é prontuário,
não tem foto — mas resolve *"cadê a OS do Gol prata da semana passada?"*, que é 90% do valor com 5%
do custo. Ficha estruturada com histórico fica para depois.

---

## 5. O documento fiscal de serviço

Esta é a seção mais delicada, e é onde a pesquisa mudou minha resposta no meio do caminho.

### 5.1 ⛔ DS9 — NFS-e **não** é "mais um modelo" em `documento_fiscal`

A tentação óbvia é acrescentar um valor ao `CHECK (modelo IN (55, 65))` e seguir. Não dá, e a razão
está no schema, coluna por coluna:

| `documento_fiscal` tem | A NFS-e tem |
|---|---|
| `chave_acesso char(44)` | chave própria, formato diferente ⚠️ (não confirmado o tamanho exato no layout nacional) |
| `codigo_numerico char(8)` + `digito_verificador` (`cNF`/`cDV` da NF-e) | não existe |
| `serie` + `numero` por `(empresa, modelo, série)` | numeração da DPS, com regra própria |
| `tipo_nf` (0 entrada / 1 saída), `indicador_presenca`, `indicador_final`, `indicador_destino` | não existem |
| `valor_icms`, `valor_icms_st`, `valor_fcp` | **ISS**, que não tem coluna |
| `tipo_operacao_fiscal` com 11 valores, todos de mercadoria | nenhum serve |
| `tipo_emissao = 9` (contingência offline da NFC-e) | não há contingência offline equivalente |

E `documento_fiscal_item` é pior: 60+ colunas de ICMS/ST/FCP/PIS/COFINS, `codigo_ncm`, `cfop`,
`cest`, `ex_tipi`, `origem_mercadoria` — e **zero** de ISS, retenção ou código de serviço.

⛔ Forçar a NFS-e ali produziria uma tabela em que **metade das colunas é sempre nula**, mais um
`CHECK` condicional por modelo para cada uma. É o oposto do que o `MODULOFISCAL.md` §7.4 chama de
"o erro clássico de módulo fiscal".

**Recomendação:** tabelas próprias — `nfse_documento`, `nfse_item`, `nfse_evento` —, com a mesma
**forma** de `documento_fiscal` (mesma máquina de estados, mesma guarda de XML imutável no MinIO
por `AreaPrivada.FISCAL_XML`, mesmo `id_venda` de origem, mesmo `F1`), mas com as colunas certas.
✅ E o que **é** compartilhado, compartilha de verdade: certificado (`fiscal_certificado`),
ambiente, `EmpresaDaSessao.exigirAcesso`, exportação em ZIP para o contador, e a tela de Documentos
Fiscais, que ganha uma aba.

### 5.2 O que o Padrão Nacional da NFS-e é hoje

| Peça | O que é |
|---|---|
| **Convênio NFS-e** | Acordo Receita Federal + municípios (ABRASF/CNM) que substitui os layouts municipais por um modelo de dados único |
| **ADN — Ambiente de Dados Nacional** | O repositório central para onde converge a NFS-e de todos os conveniados |
| **Sefin Nacional** | O módulo que recebe e **valida** a DPS do contribuinte, como uma "Secretaria de Finanças nacional" |
| **DPS — Declaração de Prestação de Serviço** | O documento que o contribuinte envia. **É o que substituiu o RPS** dos padrões municipais. Validada, vira a NFS-e |
| **Emissor Público Nacional** | Web, app e **API**, em `nfse.gov.br` |
| **DANFSe** | A representação gráfica, em migração para novo padrão nacional (NT 008, ago/2026) ⚠️ |

Fonte: [gov.br/nfse](https://www.gov.br/nfse/pt-br) e
[Documentação Técnica](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica), consultados
em 2026-08-28. O manual da API é o
[Manual do Contribuinte — Emissor Público API, v1.2, out/2025](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/documentacao-atual/manual-contribuintes-emissor-publico-api-sistema-nacional-nfs-e-v1-2-out2025.pdf).

**Versionamento do layout:** não por "v1.00/v1.01", e sim por **Nota Técnica** — o mesmo modelo da
SEFAZ, o que é uma boa notícia para quem já opera assim:

| NT | Data | O que trouxe |
|---|---|---|
| **NT 004 v2.00** | 10/12/2025 | Grupo **`IBSCBS`** no layout (validação inicialmente desligada) |
| **NT 007 v1.0** | 07/02/2026 | Mais campos/regras de IBS/CBS; Anexo VII (indicadores da operação); ajustes de PIS/COFINS |
| **NT 008** | ago/2026 ⚠️ | Novo DANFSe; a API antiga de DANFSe está sendo descontinuada |

⚠️ As NTs 004 v2.00 e 007 vêm de fontes secundárias que citam os PDFs oficiais; a NT 008 e a data de
descontinuação do DANFSe têm **inconsistência entre fontes** (julho × agosto de 2026) e **não foram
confirmadas** no documento oficial. Ver Anexo C.

**Como a API se comunica** ⚠️ (fonte secundária — este é o item que **mais precisa** de conferência
antes de virar código): certificado **ICP-Brasil A1 ou A3** com **autenticação mútua (mTLS)**, XML
assinado em **XMLDSIG**, rotas em **JSON** com o XML em **GZip + Base64**. APIs de **Produção
Restrita** e **Produção** liberadas em **01/10/2025**; ambiente restrito em
`producaorestrita.nfse.gov.br`.

⭐ **Se isso se confirmar, o Nainer já tem quase tudo:** certificado A1 cifrado no banco
(`fiscal_certificado.arquivo_cifrado`, DF5/DF21), assinatura XMLDSIG e transporte HTTPS com
keystore em memória — construídos na F0/B0 do módulo fiscal. O que é novo é o **layout** e as
**regras de negócio do ISS**, não a criptografia nem o transporte.

### 5.3 ⭐ A adesão — e por que ela deixou de ser o argumento

Aqui está o fato que inverte o non-goal do `MODULOFISCAL.md` §4.4.

**Adesão (fonte primária, oficial):** o painel *Monitoramento das Adesões à NFS-e* do próprio
`gov.br/nfse` informa que **os 5.571 entes federativos aderiram** à plataforma nacional —
correspondendo a 100% da população e 100% da arrecadação nacional de ISS. A planilha de municípios
aderentes referenciada na página é `municipios-aderentes-20260819.xlsx`, de **19/08/2026**.
([gov.br/nfse — Monitoramento das Adesões](https://www.gov.br/nfse/pt-br/municipios/monitoramento-adesoes), consultado 2026-08-28.)

⚠️ **Aderir ≠ operar, e as fontes divergem feio nesse ponto.** Uma matéria de 2026 fala em **392
municípios** emitindo efetivamente no modelo nacional
([contabeis.com.br](https://www.contabeis.com.br/artigos/73364/reforma-tributaria-nfs-e-nacional-com-baixa-adesao-municipal/), consultado 2026-08-28); outra fonte cita 1.898 operando na
plataforma. **Não consegui fechar esse número em fonte oficial** — o painel do gov.br publica a
adesão, não a operação. Registre-se como não confirmado.

**Base legal da adesão:** a **LC 214/2025** obriga todos os municípios a estarem integrados ao
padrão nacional desde **01/01/2026**, sob pena de perder transferências voluntárias da União ⚠️
(fonte secundária: CNM, via matérias de out/2025).

**⭐ E a obrigatoriedade do lado do emitente é o que decide:**

| Emitente | Obrigado a emitir pelo **Emissor Nacional** | Norma |
|---|---|---|
| **MEI** → tomador **PJ** | desde **01/09/2023** | Res. CGSN 169/2022 + Res. CGNFS-e 3/2023 |
| **MEI** → tomador **PF** | facultativo | regra geral do MEI |
| **ME/EPP do Simples Nacional** | desde **01/11/2026** | **Res. CGSN 191/2026** (04/08/2026), que adiou o prazo de 01/09/2026 fixado pela Res. CGSN 189/2026 |
| Lucro Real / Presumido | **sem data federal única** — depende do município | — |

Fontes primárias: [gov.br/nfse — prorrogação](https://www.gov.br/nfse/pt-br/noticias/comite-gestor-do-simples-nacional-prorroga-a-obrigatoriedade-de-emissao-de-notas-fiscais-de-servico-pelo-emissor-nacional-da-nfs-e) e
[Receita Federal](https://www.gov.br/receitafederal/pt-br/assuntos/noticias/2026/agosto/simples-nacional-nfs-e-nacional-sera-obrigatoria-para-me-e-epp-a-partir-de-1o-de-novembro-de-2026), ambas consultadas em 2026-08-28. Texto literal do gov.br/nfse: *"a exigência de emissão da
NFS-e por meio do sistema nacional, que passaria a vigorar em 1º de setembro de 2026, foi adiada
para 1º de novembro de 2026"*.

> ⭐⭐ **A conclusão que muda a decisão DS7.** O `MODULOFISCAL.md` recusou a NFS-e por causa de
> ~5.500 padrões municipais. Mas a **DF37** já tinha decidido que o Nainer atende **só MEI e Simples
> Nacional** — e são exatamente esses dois que a lei obriga a usar o **Emissor Nacional**. O
> problema dos 5.570 municípios é um problema de **Lucro Real e Presumido**, regimes que o produto
> **já recusa por escopo**.
>
> Traduzindo: **o Nainer não precisa falar com 5.570 prefeituras. Precisa falar com uma API
> federal** — a mesma quantidade de contrapartes que ele já enfrenta na NF-e (27 UFs, resolvidas
> pela V047).

⚠️ **A ressalva honesta:** a obrigação é sobre **qual emissor usar** quando a nota é devida. Se um
município específico ainda não estiver operando no ADN, o efeito prático para o contribuinte do
Simples naquela cidade **não está claro** nas fontes que consultei. É uma pergunta para o contador
(🔴 P4), não para inferência minha.

### 5.4 O ISS — o que o Nainer teria de cadastrar

**A lista de serviços da LC 116/2003** tem **200 subitens** em 40 grupos (`01.01`, `07.02`, `14.01`,
`17.05`…). Mas o que entra na nota **não é o subitem da lei**: é o **`cTribNac` — Código de
Tributação Nacional**, de **6 dígitos** (`item` 2 + `subitem` 2 + `desdobro nacional` 2), com
**338 códigos** que desdobram os 200 subitens ⚠️ (fonte secundária: notaapi.com.br, consultado
2026-08-28 — **conferir contra o Anexo oficial antes de semear**).

⭐ **Isso é bom para o Nainer:** é uma **tabela nacional fechada**, exatamente como o `cfg_produto_ncm`
(~10.515 códigos, carregado da Receita) e as 27 UFs de `cfg_uf_autorizador`. Vira `cfg_servico_lc116`
— **global, sem `id_tenant`, sem RLS**, mesma exceção documentada. E vale a regra do projeto:
**dado oficial se carrega da fonte, não se lembra**.

O `cTribMun` — código municipal, 3 dígitos — é **opcional**, preenchido só quando a prefeitura exige.
Vira coluna em `produto_servico`, nunca obrigatória.

**Alíquota:** mínimo **2%**, máximo **5%**, definida por **lei de cada município** dentro dessa
faixa. ⛔ **Não existe tabela nacional de alíquota por serviço**, e o Nainer não tem como saber
5.570 × 338 combinações. Então:

> **`produto_servico.aliquota_iss` é informada pelo lojista/contador**, com a mesma postura do
> **F11** (bloqueio preventivo): a Conformidade Fiscal lista o serviço sem alíquota **antes** de a
> nota ser emitida, não no balcão. É honesto e é o que os concorrentes fazem — a ContaAzul tem um
> bloco inteiro de "impostos municipais (ISS/INSS) por cidade" no cadastro do serviço
> ([ajuda.contaazul.com](https://ajuda.contaazul.com/hc/pt-br/articles/7538793724301), consultado 2026-08-28).

**Local de incidência (art. 3º da LC 116/2003).** Regra geral: **no estabelecimento do prestador**.
Exceções (incisos I a XXV) jogam o ISS para o **município do tomador ou da execução** — as três que
consegui confirmar em detalhe:

| Inciso | Caso | ISS devido em |
|---|---|---|
| I | tomador/intermediário responsável (§1º do art. 1º) | estabelecimento do tomador |
| III | execução de obra (subitens **7.02** e **7.19**) | local da obra |
| XX | **cessão de mão de obra** (subitem **17.05**) | estabelecimento do tomador |

⚠️ **Não consegui listar as 25 exceções em fonte confiável única** — e não vou inventá-las. Para o
ICP (banho e tosa, tosa, consulta veterinária, mão de obra de oficina) a regra que vale é a **geral**:
tudo executado no estabelecimento do prestador. Por isso o v1 pode nascer com
`produto_servico.local_incidencia` **default `PRESTADOR`**, e o campo existir para quem precisar.

**Retenção na fonte pelo tomador:** há indicador próprio na DPS. Na esmagadora maioria dos casos do
ICP **não há retenção** (tomador pessoa física não retém). Optante do Simples **também pode** sofrer
retenção quando cai numa hipótese do art. 3º ⚠️.

### 5.5 MEI e Simples — o que sai na nota

**Simples Nacional (Anexos III/IV/V).** A alíquota efetiva sai de
`[(RBT12 × alíquota nominal) − parcela a deduzir] ÷ RBT12`, e o **percentual de ISS dentro dela** é
o que vai ao campo de alíquota da nota (`pAliqAplic`) ⚠️.

⭐ **Isso é um problema de produto, não de código, e é grande:** esse percentual **depende da receita
bruta dos últimos 12 meses da empresa** — um número que o Nainer **não tem** (ele conhece as vendas
feitas *nele*, não o faturamento declarado da empresa). Três saídas, nenhuma indolor:

1. **Campo no cadastro fiscal da empresa**, atualizado pelo contador mensalmente. Simples, honesto,
   e depende de disciplina humana — erra em silêncio quando o contador esquece.
2. **Derivar de `plataforma.uso_venda_mes`.** ⛔ **Não.** É venda no ERP, não receita da empresa, e
   ainda atravessaria a fronteira domínio→plataforma que o ADR-015 restringe a **um** ponto.
3. **Deixar o Emissor Nacional calcular**, se o layout permitir informar só o regime ⚠️ **(não
   confirmado)**. Seria a melhor saída. É item obrigatório da prova de conceito (§7.1, bloco S0).

⚠️ **A rejeição que prova que o campo importa:** `E0617 — "Alíquota não permitida para não optante do
Simples Nacional"` acontece quando se informa `pAliqAplic` para prestador **não** optante
([Tecnospeed](https://atendimento.tecnospeed.com.br/hc/pt-br/articles/36180311455511), consultado
2026-08-28). Ou seja: o campo é governado pelo **regime**, exatamente como o motor tributário do
Nainer já governa CSOSN por CRT (§8.2 do `MODULOFISCAL.md`). O padrão existe; muda o tributo.

**MEI.** ISS é **valor fixo mensal** no DAS-MEI (5% do salário mínimo + R$ 5,00 de ISS para
atividade de serviço). Não há imposto proporcional por nota. ⚠️ **Não achei fonte que diga
literalmente que `pAliqAplic` sai vazio para MEI** — é inferência a partir do mecanismo fixo, e está
marcada como não confirmada. Vai para a prova de conceito.

⚠️ E há um caso que quebra a regra geral: alguns serviços recolhem **ISS fixo mensal** por lei
municipal (o exemplo clássico é escritório de contabilidade), e nesse caso o percentual de ISS é
**excluído** do cálculo do Simples. Fora do ICP, mas registrado para não ser descoberto por um
cliente.

### 5.6 IBS/CBS em serviço — o que já está pronto e o que muda

✅ **A boa notícia primeiro:** o layout nacional da NFS-e **já tem o grupo `IBSCBS`** desde a NT 004
v2.00 (10/12/2025), refinado pela NT 007 (07/02/2026). Uma implementação começada hoje **já nasce
pronta para a reforma** — não há retrabalho previsto em 2027.

E o Nainer já calcula IBS/CBS **para todos os regimes desde o v1** (DF4): o motor, o schema e o
`cClassTrib` existem. As alíquotas de teste de 2026 (**CBS 0,9% + IBS 0,1%**, compensadas, carga
efetiva zero) são as mesmas.

**O cronograma de destaque obrigatório de IBS/CBS em documento fiscal** — Ato Conjunto RFB/CGIBS
nº 4, de 30/07/2026 ⚠️ (fonte secundária, escritórios tributários; **conferir no ato oficial**):

| Data | Quem |
|---|---|
| 03/08/2026 | NF-e, NFC-e e demais DF-e estruturados (já aconteceu) |
| **01/10/2026** | **a maioria dos serviços sujeitos a ISS** (LC 116) |
| **01/12/2026** | plataformas digitais, software/licenciamento, streaming, transporte coletivo municipal, locação de imóveis, condomínios, intangíveis |
| **01/01/2027** | **contribuintes do Simples Nacional** |

⭐ **A data que importa para o Nainer é 01/01/2027** — a mesma do IBS/CBS na NF-e (04/01/2027, art.
348 da LC 214/2025). Ou seja: **um prazo só para o produto inteiro**, o que é uma coincidência
felizmente conveniente para planejamento.

**O que muda no motor:** o serviço não tem NCM, tem `cTribNac`; então o `cClassTrib` do IBS/CBS de
serviço **não** se deriva da mesma fonte que o de mercadoria. É trabalho novo no motor, não
reaproveitamento.

**O ISS acaba:** extinção progressiva entre **2029 e 2033**, substituído pelo IBS ⚠️ (EC 132/2023 +
LC 214/2025, via fontes secundárias). Isso é relevante para a decisão: **o cadastro de ISS que se
construir agora tem prazo de validade conhecido de ~7 anos**, e o de IBS/CBS não.

### 5.7 ✅ DS7/DS8 — própria contra o Emissor Nacional, atrás de uma interface

Esta é a recomendação principal da seção, e ela contraria o que eu teria dito antes da pesquisa.

**Por que própria, e não provedor:**

1. ⭐ **O motivo do "provedor" evaporou para este ICP.** A tese "5.570 prefeituras, é impossível
   sozinho" vale para quem atende Lucro Real e Presumido. O Nainer não atende (DF37). Para MEI e
   Simples, o destino é **uma** API federal — a mesma ordem de grandeza da SEFAZ, que a **DF1** já
   decidiu enfrentar direto.
2. **A infraestrutura cara já está construída e paga.** Certificado A1 cifrado no banco, keystore em
   memória, assinatura XMLDSIG, transporte HTTPS, máquina de estados de documento, guarda imutável
   no MinIO (`AreaPrivada.FISCAL_XML`, WORM 5 anos), ZIP para o contador, tela de Documentos
   Fiscais. Um provedor **não reaproveita nada disso** — e ainda pediria o **mesmo certificado A1**,
   que já está no nosso banco.
3. **Custo por nota × modelo de receita.** O ADR-015 cobra por **volume de vendas/mês**, e a
   revisão de 2026-08-27 cobra **por CNPJ**. Custo por nota entra direto na margem de cada venda —
   e a Focus NFe publica R$ 89,90/mês por CNPJ no plano Solo, ou R$ 548/mês com CNPJ ilimitado e
   4.000 notas ([focusnfe.com.br/precos](https://focusnfe.com.br/precos/), consultado 2026-08-28).
   Com **cota gratuita de 100 vendas/mês**, o Nainer estaria pagando por nota de cliente que não
   paga nada.
4. ⭐ **Provedor é dependência que pode desligar — e há prova recente.** A **Nuvem Fiscal anunciou
   em 22/04/2026 a desativação do serviço em 31/07/2026** ([Fórum Projeto ACBr](https://www.projetoacbr.com.br/forum/topic/91922-comunicado-de-desativa%C3%A7%C3%A3o-do-servi%C3%A7o-nuvem-fiscal-22042026/), consultado 2026-08-28). Um ERP que tivesse apostado nela teria **90 dias** para
   reescrever a emissão fiscal de toda a base. Isso não é hipótese de risco: aconteceu este ano.

**Por que atrás de uma interface, e não direto:**

⛔ Nada acima é certeza. A API do Emissor Nacional **não foi testada por mim** — os PDFs de
especificação não foram lidos, e o mTLS/GZip/Base64 vem de fonte secundária. Então o desenho é o do
**ADR-008** (adapter de gateway de cobrança, com implementação manual primeiro) — o mesmo formato
que a interface `CanalDeVenda` tinha, aliás, antes de sair do repositório com a V084:

```java
interface EmissorDeNfse {
    RetornoNfse emitir(DeclaracaoServico dps);
    RetornoNfse consultar(String chave);
    RetornoNfse cancelar(String chave, String motivo);
}
```

Primeira e única implementação: `EmissorNacionalNfse`. Se a prova de conceito (bloco **S0**) mostrar
que a API não está madura, ou se a lacuna "aderiu mas não opera" doer em campo, **plugar a Focus NFe
é uma classe**, não um projeto. E é a Focus NFe que eu escolheria: é o único dos pesquisados que
reúne preço público e granular, plano com **CNPJ ilimitado** (o único formato que serve a um SaaS
multi-tenant), suporte documentado ao padrão nacional, e **credencial flexível** (certificado A1
**ou** usuário/senha da prefeitura, conforme o município). ⚠️ A eNotas cobra por CNPJ
(inviabiliza multi-tenant); o PlugNotas parece exigir certificado A1 sempre — atrito real para MEI
sem certificado; a Arquivei/Qive **não é emissor**, é gestão de documentos.

**✅ DS8 — o limite honesto, escrito na tela.** Município que **não** opera no padrão nacional
**não é atendido**. Isso é **escopo de produto**, exatamente como a DF37 recusa Lucro Real — não é
funcionalidade faltando. E o sistema tem de **dizer isso antes**, não no balcão: a Conformidade
Fiscal checa o município da empresa contra a lista de aderentes/operantes e avisa na implantação.

### 5.8 O que a NFS-e ainda tem que a NF-e não tem

Três coisas que mudam o desenho e que é melhor saber agora:

1. **Não há contingência offline.** A NFC-e tem `tpEmis = 9` porque o caixa não pode parar. A NFS-e
   não tem equivalente ⚠️ — e nem precisa: serviço não é vendido em fila de supermercado. A
   consequência é boa (menos código) e precisa ser dita para ninguém procurar.
2. **O prazo de cancelamento não é nacional.** Existe o mecanismo (evento de cancelamento e o
   **Cancelamento por Substituição**, em que uma nova DPS referencia a chave da anterior, cancela e
   substitui), mas o **prazo** continua, ao menos em parte, sob competência municipal ⚠️: as fontes
   citam 5 dias úteis no padrão nacional, 5 dias corridos em Brasília, **72 horas em Curitiba**,
   e "até 30 dias ou o dia 10 do mês seguinte" em outros. ⭐ **Isso é exatamente o F10** do módulo
   fiscal (*"a UF é dado, nunca `if`"*) aplicado ao município — vai para tabela, chaveada por
   município, não para constante.
3. **O DANFSe está em migração** (NT 008, ago/2026), com a API antiga sendo descontinuada ⚠️. Não
   construir nada em cima da API antiga.

### 5.9 ⚠️ DS10 — o Recibo fica, mas a NFS-e não fica para depois

> ⚠️ **Decisão dele, 2026-08-28 (§0.1):** *"tem que ter a opção de emissão de NFS-e, mas talvez o
> cliente possa apenas emitir a papeleta de venda"*. Ou seja: **o produto oferece a NFS-e** (S5–S7
> entram no escopo, não ficam para uma v2), e **emitir é escolha do lojista** — exatamente como
> `fiscal_config_empresa.emite_nfce` e o princípio F12 já funcionam no módulo fiscal.
>
> O que continua valendo do texto abaixo é **tudo sobre o Recibo**; o que **não** vale é a parte da
> recomendação que dizia entregar o módulo sem NFS-e nenhuma e deixá-la para depois.
>
> ⭐ **O que sobrevive desta seção:** o **Recibo de Serviço** continua no produto, com outro papel —
> ele é o que se imprime quando a NFS-e **não é devida** (MEI atendendo pessoa física, o caso comum
> do balcão de petshop) e quando a emissão falha (**F3**: a venda nunca some porque a nota falhou).
> As regras de como ele **não pode** parecer nota valem igual.

**O Recibo de Serviço, que fica.** Um **Recibo de Serviço** não fiscal, impresso na mesma bobina de 80 mm/42 colunas
da papeleta (a calibragem de `docs/telas/papeleta-venda.md` vale igual), entrega valor real:

- ⭐ **Banho, tosa e mão de obra de oficina são vendidos majoritariamente a pessoa física** — e para
  o **MEI**, nota ao tomador **PF é facultativa**. Um MEI de petshop atendido só com recibo está
  **legalmente servido**, não remendado.
- Registra a receita no DRE, no caixa, no crediário e na comissão — que é onde o lojista sente falta
  todo dia, muito mais do que na nota.
- Permite validar a modelagem, a OS e a comissão **antes** de assumir o compromisso permanente de um
  segundo calendário normativo.

⛔ **Mas com o limite escrito, nunca disfarçado** (F11 + a memória
`feedback_front_engole_mensagem_de_erro_do_back`): a tela precisa dizer *"este recibo não é
documento fiscal; a NFS-e ainda não é emitida pelo Nainer"*. ⛔ E o Recibo **não pode** parecer nota:
sem chave, sem QR, sem "DANFE"/"DANFSe", sem número que imite numeração fiscal.

⚠️ **E ME/EPP do Simples não fica servida assim.** Para elas a nota é devida e, desde 01/11/2026,
pelo Emissor Nacional. O recibo é **ponte**, não destino — e a data de fim da ponte precisa ser
combinada com ele (🔴 P5).

---

## 6. O que a concorrência faz

⚠️ **Leia o Anexo C antes de usar qualquer número daqui em decisão comercial.** Vários nomes da
lista original **não foram encontrados como produtos existentes**, e preços de fontes secundárias
divergem entre si.

### 6.1 Tabela

| Produto | Serviço separado | OS mistura peça+m.o. | Estado da OS | Aprovação do cliente | Agenda | NFS-e | Preço piso | Comissão |
|---|---|---|---|---|---|---|---|---|
| **Bling** | sim | não confirmado | não confirmado | não confirmado | não confirmado | **700+ municípios, incluída em todos os planos** | R$ 60/mês | sim (campo na OS) |
| **Tiny / Olist** | sim | não confirmado | não confirmado | não confirmado | não confirmado | "centenas de municípios" | não publicado | não confirmado |
| **Omie** | sim | **sim** | **sim (kanban)** | não confirmado | só de faturamento | sim | sob consulta (Fit grátis p/ MEI) | não confirmado |
| **ContaAzul** | **sim, com bloco fiscal municipal** | não confirmado | **sim (4 situações)** | **sim, dentro da OS** | não confirmado | **nacional + prefeitura** | ~R$ 119,90/mês ⚠️ | não confirmado |
| **Granatum O.S.** | não confirmado | não confirmado | não confirmado | não confirmado | não confirmado | **não incluída no básico** | R$ 79,90/mês (sem fiscal) | não confirmado |
| **GestãoClick** | sim | **sim** | **sim** | não confirmado | **sim (plano Ouro)** | sim (todos menos o Bronze) | R$ 119/mês (R$ 289 p/ OS+agenda) | **sim, por serviço** |
| **Vhsys** | sim | sim | não confirmado | não confirmado | **sim, vinculada à OS** | sim, **inclusive NFC-e conjugada** | ~R$ 139/mês ⚠️ | não confirmado |
| **Nex** | **parcial — serviço é cadastrado como produto** | sim (Pro+) | não confirmado | não confirmado | sim (módulo próprio) | sim | R$ 39/mês (OS só no Pro) | não confirmado |
| **SimplesVet** (pet) | sim | n/a | não confirmado | não confirmado | **sim, todos os planos** | **80/mês no básico; ilimitado no avançado; add-on R$ 153 no Clínica** | R$ 157/mês | só nos planos altos |
| **Sispet** (pet) | sim | n/a | não confirmado | não confirmado | **sim, recorrência até 52×/ano** | **add-on R$ 99 / 300 emissões** | R$ 159/mês | **sim, R$ ou %, por serviço** |
| **Wüst** (oficina) | sim | **sim, com baixa de peça na aprovação** | não confirmado | **sim, via WhatsApp, com fotos** | não confirmado | **incluída, sem custo por nota** | **R$ 79,90/mês** | não confirmado |
| **OficinaSoft** | **sim, com markup próprio** | não confirmado | sim | sim | não confirmado | sim | não publicado | não confirmado |
| **AutoGest / AutoGestor** | sim | sim | **sim (4 status)** | não confirmado | não confirmado | sim | não publicado / licença avulsa | não confirmado |

### 6.2 O piso de mercado — o que praticamente todos têm

1. **Cadastro de serviço separado, com dados fiscais próprios** (código de tributação municipal,
   ISS). Confirmado em Bling, Omie, ContaAzul, GestãoClick, Vhsys, OficinaSoft.
2. **OS com algum estado** (aberto → em andamento → concluído → faturado). Omie, ContaAzul,
   GestãoClick, AutoGestor.
3. **NFS-e integrada ao fluxo de serviço.** ⭐ **Em todos os 13 produtos com informação suficiente,
   sem exceção.** A variação é só *como cobram*.
4. **Dependência de provedor por causa da fragmentação municipal** — nenhum concorrente afirma
   cobrir os 5.570; todos vendem "X municípios" como métrica.
5. **Alguma comissão de profissional**, ainda que só nos planos altos.

⛔ **O item 3 é o achado que mais importa para a decisão de escopo:** *não existe concorrente que
venda módulo de serviço sem NFS-e.* Isso não obriga o Nainer a ter NFS-e no primeiro dia — mas
obriga a **não anunciar** "atendemos serviços" enquanto ela não existir.

### 6.3 ⭐ Os três achados que valem copiar

**1. NFS-e incluída na mensalidade, sem pacote de emissões.**
O padrão nos verticais é vender emissão como add-on por lote: **Sispet R$ 99/300 emissões**;
**SimplesVet R$ 153/mês** no plano Clínica (e só **80 emissões/mês** no plano básico). A Wüst
(oficina) faz o oposto — **NFS-e incluída nos R$ 79,90/mês, sem custo por nota** — e o Bling também
inclui em todos os planos. ⭐ Isso **casa perfeitamente com o ADR-015**, que já decidiu *"paga-se
volume, não funcionalidade"* e que fiscal, canais, usuários e telas são idênticos no grátis e no
pago. **É diferencial de graça: o Nainer só precisa não mudar de ideia.** E é o argumento mais forte
a favor da emissão própria (§5.7): quem paga por nota ao provedor não consegue prometer isso.

**2. Aprovação do orçamento pelo cliente antes de virar OS.**
ContaAzul faz dentro da OS (situação *"Fazer Orçamento" → "Realizar serviço"*); Wüst faz **por
WhatsApp, com fotos**. É o caso de uso literal das duas lojas: o tutor aprova o serviço extra, o
dono do carro aprova o item que apareceu na revisão. ⭐ E o Nainer tem **duas peças prontas**: o
Orçamento com preço congelado que vira venda pelo F5, e o **compartilhamento de arquivo temporário
por WhatsApp** (`docs/infra/compartilhamento-arquivo-temporario.md`, cache em memória, token 24 h).
"Mandar a OS por WhatsApp para o cliente aprovar" é montar duas peças existentes — ⏭️ mas depois do
v1, porque a aprovação de volta (o cliente responder) é que é o trabalho.

**3. Comissão por serviço, vinculada a quem executou.**
Sispet calcula ao vincular o colaborador; GestãoClick separa comissão *"por venda, por serviço e por
produto"*. É a DS5, e o Nainer já tem `id_funcionario` **por linha** — o que quase nenhum
concorrente tem de graça.

### 6.4 ⛔ Os três que não valem copiar

**1. Integração municipal prefeitura a prefeitura** (o caminho dos 700+ do Bling e dos 2.000+ dos
provedores). Manutenção sem fim, cada prefeitura mudando regra por conta própria. Para MEI/Simples
o padrão nacional resolve; para os outros, o Nainer não os atende.

**2. Multi-loja com painel consolidado em tempo real por unidade** (Vetus, redes de petshop). É
recurso de franquia. O ICP do Nainer é 1 CNPJ, 1–15 pessoas — e o produto já tem multi-empresa
suficiente.

**3. NFC-e conjugada** (produto + serviço na mesma nota, Vhsys). Depende de regra municipal
específica, dobra a complexidade do montador na área mais cara de acertar deste projeto, e o ganho é
uma nota em vez de duas.

### 6.5 O que a pesquisa não achou (e o que isso significa)

**Nenhum dos concorrentes pesquisados publica "agendamento com recorrência" como recurso de ERP
horizontal.** Agenda de verdade aparece só nos **verticais** (SimplesVet, Sispet, Vhsys, Nex via
produto separado). Isso confirma a DS6: **agenda é território de vertical**, e um ERP horizontal que
tenta entrar nele compete de igual com quem só faz aquilo. O Nainer ganha em **estoque + fiscal +
financeiro na mesma base** — que é justamente o que o vertical de petshop não tem.

---

## 7. Proposta

### 7.1 Os blocos — no formato B0–B9 que funcionou no fiscal

Cada bloco entrega algo verificável e depende só dos anteriores.

| Bloco | Entrega | Depende de | Como se sabe que acabou |
|---|---|---|---|
| **S0** — Prova de conceito da NFS-e | **Fora do produto.** Emitir **uma** DPS em Produção Restrita com o certificado A1 já cifrado no banco, por script. Responder por fonte primária: (a) mTLS/assinatura/formato, (b) como o `pAliqAplic` do Simples é informado ou calculado, (c) o que sai para MEI, (d) o formato da chave e da numeração, (e) o evento de cancelamento e substituição | certificado A1, CNPJ com Inscrição Municipal e CNAE de serviço | **Uma NFS-e autorizada em Produção Restrita**, e as 5 respostas escritas com fonte oficial |
| **S1** — O item que não é mercadoria | `produto.tipo_item` + `produto_barra.tipo_item` (denormalizado) + `produto_servico` + `cfg_geral.cfg_usa_servicos` + curto-circuito na trigger + os 8 filtros da §3.6 + cadastro na tela de Produtos + Conformidade Fiscal parando de reprovar serviço | — | Serviço cadastrado **não** aparece em Relatório de Estoque, Contagem, Kardex, etiqueta, exportação de estoque; e **não** cria linha em `produto_estoque` (conferido no banco) |
| **S2** — Vender serviço | Serviço no F2 do PDV com marca visual; venda mista; papeleta com os dois blocos; `VendaFiscalAssembler` filtrando serviço; venda 100% serviço não tenta NFC-e; Recibo de Serviço na bobina | S1 | Venda de peça + mão de obra: NFC-e sai **só com a peça**, o recibo sai com as duas, DRE e Lucratividade fecham |
| **S3** — Quem executou, e a comissão | `idFuncionario` por linha no PDV; `produto_servico.perc_comissao`; `produto_movimento_detalhe.perc_comissao` congelado; Relatório de Comissões somando do congelado | S2 | Venda com atendente + tosador gera **duas** linhas de comissão, com percentuais diferentes; e editar o percentual do funcionário **não** muda venda passada |
| **S4** — Ordem de Serviço | `ordem_servico` + `ordem_servico_item`, estados, objeto do serviço pesquisável, abertura no PDV pelo mesmo caminho do F5 | S3 | OS aberta com peça + mão de obra, alterada, aprovada e faturada, virando **uma** venda pelo caminho existente |
| **S5** — Cadastro tributário de serviço | `cfg_servico_lc116` (global, carregado da fonte oficial); alíquota, retenção, local de incidência em `produto_servico`; Conformidade Fiscal listando serviço sem código/alíquota | S1 + S0 | A Conformidade aponta pendência real de um tenant de teste; nenhum código digitado de memória |
| **S6** — Emissão da NFS-e | `EmissorDeNfse` + `EmissorNacionalNfse`; `nfse_documento`/`nfse_item`; máquina de estados; guarda do XML no MinIO; DANFSe | S5 + S0 | Uma NFS-e autorizada **em produção** para uma loja piloto, e o contador dela aceitando o arquivo |
| **S7** — Ciclo de vida | Cancelamento e substituição; prazo por município em tabela (F10); NFS-e na exportação em ZIP; aba em Documentos Fiscais | S6 | Cancelar dentro e fora do prazo, com mensagem certa nos dois casos |
| **S8** — ⏭️ Futuras | Agenda, recorrência, aprovação por WhatsApp, ficha do pet/veículo, comissão em R$ fixo, tabela de tempo de mão de obra | — | cada uma com spec própria |

⚠️ **O escopo do v1 vai de S1 a S7** — decisão dele em 2026-08-28 (§0.1): o produto tem de **oferecer**
a emissão de NFS-e, ainda que o lojista escolha operar só com a papeleta. S5–S7 **não** ficam para
uma v2.

⭐ **Mas a ORDEM de construção continua sendo S1→S4 primeiro, e é o que eu recomendo.** Não é sobre
escopo, é sobre construir o caro (a nota, que depende de terceiro) **em cima de modelagem já
validada** — catálogo, venda mista, comissão e OS funcionando antes. Entregar tudo junto não obriga
a construir tudo junto.

⚠️ **E o S0 continua sendo gate — de quê, mudou.** Ele não decide mais se *existe* v1 (existe:
S1–S4 + papeleta já operam petshop e oficina); decide se a **opção de emitir** existe. Se ele não
fechar, isso volta a ele como decisão, não é absorvido em silêncio.

### 7.2 Impacto no banco

Próxima migration livre: **V085** (a V084 removeu o marketplace em 2026-08-28).

| Migration | O quê | Risco |
|---|---|---|
| **V085** | `CREATE TYPE tipo_item AS ENUM ('MERCADORIA','SERVICO')`; `produto.tipo_item NOT NULL DEFAULT 'MERCADORIA'`; `produto_barra.tipo_item` (denormalizada); `produto_servico`; `cfg_geral.cfg_usa_servicos` | **baixo** — só DDL e default. ⚠️ **`GRANT` por coluna:** `empresa` tem privilégio coluna a coluna (V072 tropeçou nisso) — conferir se `produto`/`produto_barra` também têm, senão a coluna nova nasce inacessível para `niner_app` e **a suíte não vê**, porque o Testcontainers conecta como superusuário |
| **V086** | `CREATE OR REPLACE FUNCTION fn_atualiza_estoque_movimento()` com o curto-circuito | ⚠️ **alto** — é o caminho mais quente do sistema. Medir com importação de 10 mil linhas antes e depois |
| **V087** | `produto_movimento_detalhe.perc_comissao numeric(5,2)` | baixo — nullable, `COALESCE` no relatório |
| **V088** | `ordem_servico`, `ordem_servico_item`, `CREATE TYPE situacao_ordem_servico`, RLS + política + `GRANT` | médio — ⚠️ **RLS é obrigatório e não automático** (a `orcamento.md` registra que quase passou) |
| **V089** | `cfg_servico_lc116` (global, sem `id_tenant`, sem RLS — mesma exceção de `cfg_produto_ncm`) + seed **carregado da fonte oficial** | baixo — ⚠️ **não digitar de memória**, é a regra do NCM, das 27 UFs e dos 28 ramos |
| **V090** | `nfse_documento`, `nfse_item`, `nfse_evento`, `nfse_numeracao`, `cfg_municipio_nfse` (prazo de cancelamento por município — F10) | médio |
| **V0xx** | `INSERT INTO cfg_tela` das telas novas | ⚠️ **`AcoesPorTelaConferemTest` reprova o build** se a tela for catalogada sem controller, ou se a ação declarada divergir do que o interceptor deriva |

⛔ **Nenhuma coluna nova é `NOT NULL` sobre dado existente sem DEFAULT**, e nenhuma tela existente
passa a exigir campo novo enquanto `cfg_usa_servicos` estiver falso. É o **F12** do fiscal, aplicado
aqui.

### 7.3 Impacto no contrato de API

Novos e alterados, no padrão de `docs/telas/*.md` (⚠️ lembrando que **não há OpenAPI gerado** — o
contrato é a seção "Contrato de API" da spec da tela):

```
# Catálogo
GET    /api/v1/produtos?tipoItem=SERVICO|MERCADORIA          (filtro novo, opcional)
POST   /api/v1/produtos                                      + tipoItem, + bloco servico{}
PUT    /api/v1/produtos/{id}                                 idem
GET    /api/v1/servicos/lc116?busca=                         lista nacional (global)

# PDV  — ALTERADO, e é o único ponto sensível
POST   /api/v1/pdv/vendas
       ItemVendaRequest { idVariacao, qtd, doOrcamento, + idFuncionario? }
       ⚠️ idFuncionario OPCIONAL: ausente = herda o da venda (comportamento de hoje)

# Ordem de Serviço  (S4)
GET    /api/v1/ordens-servico?situacao=&objeto=&pagina=&limite=
POST   /api/v1/ordens-servico
PUT    /api/v1/ordens-servico/{id}                            só nas situações mutáveis
POST   /api/v1/ordens-servico/{id}/situacao                   transição de estado
GET    /api/v1/ordens-servico/{numero}/para-venda             espelho de /orcamentos/{n}/para-venda

# NFS-e  (S6/S7)
POST   /api/v1/nfse/vendas/{idVenda}/emitir
GET    /api/v1/nfse/documentos?...
POST   /api/v1/nfse/documentos/{id}/cancelar
GET    /api/v1/nfse/documentos/{id}/danfse
```

⚠️ **`ItemVendaRequest` é a única mudança em contrato existente**, e é aditiva. Todo o resto é
endpoint novo. Erros continuam em Problem Details (RFC 9457) e a paginação continua por número de
página.

### 7.4 Impacto nas telas

| Tela | O que acontece |
|---|---|
| **Produtos** (`catalogo.produto`) | ⚠️ **altera** — seletor "Mercadoria / Serviço" no topo do formulário; com `SERVICO`, some o bloco de estoque/peso/grade/NCM e aparece a aba de dados de serviço. Filtro por tipo na listagem. Requer entrada em `ConfiguracaoTelaService.CAMPOS_POR_TELA` |
| **PDV** | ⚠️ **altera** — marca visual do item de serviço na grid e no F2; coluna/ação de "quem executou" quando `cfg_usa_servicos` |
| **Papeleta de venda** | ⚠️ **altera** — dois blocos (produtos, serviços) e dois números de documento. ⛔ **Cabe em 42 colunas ou é reorganizado, nunca espremido** |
| **Recibo de Serviço** | 🆕 nova, bobina 80 mm, com o aviso de "não é documento fiscal" |
| **Ordem de Serviço** (lista + form) | 🆕 nova, em Frente de Loja, no padrão do Orçamento |
| **Serviços — cadastro tributário** | 🆕 nova (ou aba do Produto — a decidir na spec), com `cTribNac`, alíquota, retenção |
| **Conformidade Fiscal** | ⚠️ **altera** — para de reprovar serviço por NCM; passa a reprovar serviço sem `cTribNac`/alíquota, e empresa em município não atendido |
| **Documentos Fiscais** | ⚠️ **altera** — aba/filtro de NFS-e |
| **Exportação de XML em Lote** | ⚠️ **altera** — o ZIP do contador precisa levar a NFS-e junto |
| **Parâmetros do Sistema** | ⚠️ **altera** — `cfg_usa_servicos`, com a sugestão vinda do ramo |
| **Importação / Exportação de Dados** | ⚠️ **altera** — planilha de serviços (importação); coluna de tipo nas de produto |
| **Menu** | ⚠️ Ordem de Serviço em Frente de Loja. ⛔ E se algum item nascer como placeholder em "Implementações Futuras", **apagar a rota e o item** ao ligar a tela — o erro que aconteceu **duas vezes** em 2026-08-26 |

⚠️ **Toda tela nova precisa de `AjudaDaTela` (R22)** — é obrigatório em toda tela do produto, e é o
item que a memória `feedback_checklist_documentar_tudo` registra como o mais esquecido.

### 7.5 Impacto nos relatórios existentes

⭐ Com a DS1, **nenhum relatório quebra** — todos continuam lendo o mesmo ledger. O que muda é o
significado, e isso precisa aparecer na tela:

| Relatório | O que muda |
|---|---|
| **DRE** | Serviço entra na receita automaticamente. ⚠️ **CMV de serviço é `preco_custo`, que nasce 0** — a linha de CMV passa a subestimar o custo. Recomendação: **linha separada** "Receita de serviços" e uma conta de custo própria no plano de contas |
| **Lucratividade** | ⚠️ **O defeito mais provável do módulo inteiro:** serviço com custo 0 vira **margem 100%**, e o lucro do mês sobe sem que nada esteja errado no código. É o irmão exato do que a V059 documenta nas vendas sintéticas da importação. **DS12:** `produto_servico.custo_referencia` preenchido pelo lojista, e **aviso na tela quando zerado** — o mesmo par de itens 18/20 das pendências (o valor é dele, o aviso é meu) |
| **Comissões** | Passa a somar do percentual **congelado** (DS5). ⚠️ Mudança de comportamento em relatório existente: exige o par de testes |
| **Relatório de Vendas** | Ganha corte por tipo de item. Sem ele, mão de obra e mercadoria somam numa linha só e o lojista não sabe de onde veio o faturamento |
| **Contas a Receber / Fluxo de Caixa / Caixa** | **nada muda** — dinheiro é dinheiro, e entra pela mesma porta |
| **Estoque / Kardex / Contagem** | ⚠️ **precisam do filtro** (§3.6), senão passam a listar serviço |

### 7.6 ✅ DS11 — impacto no modelo comercial

**Recomendação: serviço conta na cota como qualquer venda — e nada mais muda.**

O ADR-015 é explícito: *"uma única dimensão medida"*, e `LimiteVendasService.registrarVenda()` é
chamado **uma vez por `efetivarVenda`**, seja qual for o conteúdo da venda. Isso continua valendo
por si — não é preciso escrever código nenhum para que serviço conte.

**Os dois erros a evitar:**

1. ⛔ **Não criar uma segunda dimensão** ("cobra por NFS-e emitida", "cobra por OS"). Fere o ADR-015,
   fere o P6, e reabre a decisão que o plano de negócio fechou em 2026-08-18.
2. ⚠️ **Mas a Ordem de Serviço abre uma porta ao lado da cota** — e a memória
   `feedback_teto_com_porta_ao_lado` é sobre exatamente isso. Se algum dia a OS ganhar faturamento
   próprio sem passar pelo `PdvVendaService`, ela **tem** de chamar o mesmo serviço de cota. Com a
   DS4 (a OS desemboca no PDV), o problema não existe — e é mais um motivo para a DS4.

⭐ **E há um efeito comercial de graça que vale registrar:** a oficina faz **uma** venda com dez
itens onde o varejo faz dez vendas. Medir por *venda emitida* já normaliza isso — uma oficina que
fatura o mesmo que uma loja de calçados **paga menos**. Isso é bom (o produto fica barato para o
novo público) e precisa ser **decidido de propósito**, não descoberto (🔴 P6).

⚠️ **O item 28 das pendências continua bloqueando:** *"os planos pagos não estão definidos"*. Não dá
para dimensionar o impacto de nada disso na receita enquanto `preco_base` e `fator_faixa` forem 🔴.

### 7.7 Riscos — com honestidade

**R1 — ⚠️ O módulo fiscal de serviço é muito maior do que parece, e essa é a maior chance de o
estudo estar errado.** A tentação é achar que "já emitimos NF-e, NFS-e é parecido". Não é: layout
novo, tributo novo (ISS não é ICMS), calendário normativo **novo e independente** (CGNFS-e, não
SEFAZ), DANFSe novo, cadastro nacional novo. Ordem de grandeza honesta: **comparável a F0–F4 do
módulo de NF-e**, não a uma fase. O que **é** reaproveitado é real e valioso (certificado,
assinatura, transporte, guarda, exportação), mas é a infraestrutura — não o domínio.

**R2 — ⚠️ Compromisso permanente em dobro.** O `MODULOFISCAL.md` §2.2 diz: *"Quem emite direto
assume manter o layout em dia para sempre, sob pena de todos os tenants pararem de vender no mesmo
dia."* A DS7 **dobra** esse compromisso: passam a ser dois emissores de documento fiscal, dois
conjuntos de Notas Técnicas, duas homologações. Isso é custo de operação da Vetor, não boa vontade.

**R3 — ⚠️ Alvo em movimento com prazo curto.** A obrigatoriedade para ME/EPP é **01/11/2026** e o
layout mudou três vezes em nove meses (NT 004 v2.00 em 12/2025, NT 007 em 02/2026, NT 008 em
08/2026, com a API do DANFSe sendo descontinuada). Construir contra isso agora é construir contra
alvo móvel. ⭐ **É o principal argumento para S0 ser um gate de verdade**, e não uma formalidade.

**R4 — 🔴 Bloqueio em terceiro, e há precedente vivo.** A NFS-e exige **Inscrição Municipal** e CNAE
de serviço. A empresa de testes atual é de varejo. Sem um CNPJ com IM e atividade de serviço, **S0
não roda** — e o item 1 das pendências (`cStat 974`, aberto na SEFAZ/PR há dias) é a lembrança de
que "bloqueado em terceiro" é uma categoria real neste projeto, não um risco teórico.

**R5 — ⚠️ A trigger é o caminho mais quente do sistema.** A V054 registra que o caminho comum *"não
custa nada"* porque `saldo >= 0` sai na primeira linha. Acrescentar uma consulta ali sem medir é
repetir o erro que a V067 quase cometeu. **Medir com importação real de 10 mil linhas, antes e
depois** — não estimar.

**R6 — ⚠️ Serviço vira margem 100% e ninguém percebe.** Já explicado (§7.5). O que torna o risco
grave é que o número **é plausível**: R$ 80 de banho com custo R$ 0 dá lucro de R$ 80, e nada na
tela grita. O critério de aceitação precisa ser desenhado para **gritar** — no estilo do teste da
Lucratividade, que lança R$ 5.000 contra uma venda de R$ 100 para transformar lucro em prejuízo.

**R7 — ⚠️ Escopo escorrega para agenda.** Assim que a OS existir, o próximo pedido é "e agendar?".
Deixar a DS6 escrita **antes** é o que impede a conversa de virar um produto de agendamento.

**R8 — ⛔ Prometer NFS-e antes de ela existir.** Nenhum concorrente vende serviço sem NFS-e (§6.2).
A tentação de anunciar "agora atendemos serviços" com S1–S4 prontos é grande, e em novembro cada
cliente ME/EPP que entrar por essa porta vira um chamado. **O texto do site é parte do escopo do
módulo.**

**R9 — ⚠️ `produto` deixa de significar "mercadoria".** Aceito conscientemente (§3.4), mas produz
confusão para quem chegar depois. Mitigação: comentário na tabela, `package-info.java` e uma linha
no `CLAUDE.md`.

---

## 8. 🔴 Perguntas para o dono do produto

Poucas, e só as que **não** se respondem lendo código nem pesquisando. Cada uma com a recomendação
já dita e o custo de cada resposta.

> ✅ **P3, P4 e P5 foram respondidas em 2026-08-28** — ver **§0.1**, que também registra o que a
> resposta de P5 mudou no desenho. **P1, P2 e P6 seguem abertas**; P1 precisa ser respondida antes
> do bloco S1, porque é `DEFAULT` de migration.

**P1 — Serviço nasce ligado ou desligado?**
*Recomendação:* **desligado** (`cfg_usa_servicos = false`), como `cfg_usa_cor_grade`.
*Se ligado:* toda loja de calçados ganha um seletor "Mercadoria/Serviço" que nunca vai usar, e a
tela de Produtos fica mais poluída para 100% da base por causa de uma minoria.
*Se desligado:* quem quer serviço tem de achar o parâmetro — mitigado pela sugestão vinda do ramo.
⚠️ A pergunta é sobre o **comportamento concreto**, não sobre o estado da flag: *"quando uma
petshop nova assina hoje, ela já vê o campo de serviço, ou tem de ligar?"*

**P2 — A lista de 28 ramos ganha ramos de serviço?**
*Recomendação:* **sim** — oficina mecânica, salão e barbearia, assistência técnica, clínica
veterinária, lava-rápido. Hoje uma oficina se cadastra como `AUTOPECAS` ou `OUTROS`, e o dado de
segmentação da Vetor nasce errado.
*Custo:* seed novo (`cfg_ramo_atividade` é global, `id_ramo` fixo) + mapa CNAE, **carregado da
tabela do IBGE, não digitado**. Não é schema.

**P3 — ✅ RESPONDIDA (2026-08-28): SIM, a Ordem de Serviço entra no v1.**
*Recomendação:* **entra** (bloco S4), porque sem ela o Nainer atende petshop e **não** atende
oficina — e a oficina é metade do pedido.
*Se ficar de fora:* o v1 sai antes e mais barato, e o produto pode ser oferecido a petshop, salão e
assistência técnica simples; oficina espera.
*Se entrar:* +1 tela de lista, +1 de formulário, +2 tabelas, e a tentação de agenda começa.

**P4 — ✅ PARCIALMENTE RESPONDIDA (2026-08-28): ele tem uma empresa que pode testar.** Faltam três fatos sobre ela (regime, município, IM+CNAE de serviço) e o nome do contador — ver §0.1.
*Sem recomendação — é fato, não opinião.* Nada da parte fiscal deste estudo pode ser implementado
por dedução: alíquota, código de tributação, retenção e o percentual de ISS do Simples são decisões
de contador. E **S0 não roda** sem um CNPJ com IM e CNAE de serviço.
⚠️ Isto é o análogo direto do CSRT: **é o item mais provável de virar bloqueio**, e o mais barato de
resolver cedo.

**P5 — ⚠️ RESPONDIDA (2026-08-28), e a pergunta estava mal formulada por mim.** Eu perguntei
"entregar SEM NFS-e?", como se fosse tudo ou nada. A resposta dele foi um terceiro caminho: *"tem
que ter a opção de emissão de NFS-e, mas talvez o cliente possa apenas emitir a papeleta de venda"*
— o **produto** oferece (S5–S7 no escopo do v1), o **lojista** escolhe. Detalhe em §0.1.
*Recomendação:* **sim, entregar** — é a única forma de validar a modelagem antes de construir o caro
em cima dela; e para **MEI atendendo pessoa física** a nota é facultativa, então é entrega legítima,
não remendo.
⛔ *Mas o anúncio é a parte perigosa:* nenhum concorrente vende serviço sem NFS-e, e desde
**01/11/2026** ME/EPP do Simples é obrigada ao Emissor Nacional. Dizer "atendemos serviços" sem
dizer "sem emissão fiscal por enquanto" produz churn em novembro.
*A pergunta prática:* **quem é o cliente-piloto do S1–S4?** Se for MEI, o recibo basta. Se for ME,
S6 é pré-requisito comercial.

**P6 — A oficina vai pagar menos que a loja de calçados. Tudo bem?**
*Recomendação:* **tudo bem, e é de propósito.* Uma oficina faz 40 vendas/mês de R$ 800; uma loja faz
400 de R$ 80. Mesmo faturamento, cotas diferentes. Isso torna o produto **barato para o público
novo**, o que ajuda a entrar — mas é receita que não vem.
*Se não estiver tudo bem:* a alternativa é uma segunda dimensão de cobrança, que **fere o ADR-015** e
reabre a decisão de 2026-08-18. Eu não recomendaria.
⚠️ Depende do item 28 das pendências (planos pagos), que continua **bola dele**.

---

## Anexo A — Spec de Feature (template §5), pronta para aprovação

```markdown
# Spec: Serviços no catálogo e na venda (blocos S1–S2)   Status: Rascunho
Autor: · Data: 2026-08-28 · Módulo(s): catalogo, vendas, estoque, fiscal · Fase: S1–S2

## Problema
Petshops e oficinas já são clientes do Nainer pela metade: o ERP cobra a ração e a peça, e não
tem onde registrar o banho e a mão de obra — que costumam ser a maior parte do faturamento
delas. Hoje o lojista lança serviço como se fosse mercadoria (e o estoque fica negativo para
sempre) ou não lança (e a DRE mente). Não há caminho certo.

## Solução proposta (o quê, não o como)
O cadastro de produto passa a aceitar dois tipos: Mercadoria e Serviço. Serviço não tem estoque,
não tem peso, não tem NCM e não entra na NFC-e — mas é vendido no PDV como qualquer item, entra
na receita, na comissão e no recibo. Tudo isso só aparece para quem ligar o parâmetro
"Trabalha com serviços" nos Parâmetros do Sistema.

## User stories
- Como dono de petshop, quero cadastrar "Banho e tosa — porte médio" com preço, para vendê-lo
  no caixa junto com a ração.
- Como operadora de caixa, quero lançar o banho na mesma venda da ração, para o cliente pagar
  uma vez só.
- Como dono da loja, quero que o banho não apareça no relatório de estoque, para não ter de
  contá-lo no inventário.
- Como lojista que só vende mercadoria, quero que nada mude na minha tela.

## Critérios de aceitação (viram testes)
- Dado um produto tipo SERVICO, quando ele é vendido no PDV, então NÃO existe linha em
  produto_estoque para a variação dele (conferido no banco, não pelo status HTTP).
- Dado uma venda com uma mercadoria e um serviço, quando a NFC-e é emitida, então ela sai com
  UM item — a mercadoria — e o valor total da nota é o da mercadoria.
- Dado uma venda 100% de serviço, quando a emissão é acionada, então nenhuma NFC-e é emitida e
  o documento fica NAO_EMITIDO com motivo legível — e a sequência de numeração NÃO andou.
- Dado um serviço, quando o Relatório de Estoque / Contagem / Kardex / Emissão de Etiqueta são
  consultados, então ele não aparece em nenhum.
- Dado cfg_usa_servicos = false, quando a tela de Produtos é aberta, então o seletor de tipo
  não existe e o formulário é idêntico ao de hoje.
- Dado um serviço vendido, quando a DRE e a Lucratividade são consultadas, então a receita dele
  aparece.
- [x] caso feliz  [x] erro  [x] borda (venda 100% serviço)  [x] o que NÃO deve acontecer
      (serviço na NFC-e; serviço em produto_estoque; tela mudando com o parâmetro desligado)

## Impacto no contrato de API
GET /api/v1/produtos?tipoItem=  · POST/PUT /api/v1/produtos (+tipoItem, +servico{})
POST /api/v1/pdv/vendas — ItemVendaRequest ganha idFuncionario opcional (aditivo)

## Ajuda da tela (R22 / §3.7.1) — obrigatório
- catalogo.produto (alterada): explicar o que muda ao escolher Serviço, e por que estoque some.
- configuracao.geral (alterada): o que "Trabalha com serviços" liga.
- vendas.pdv (alterada): como o item de serviço aparece e como escolher quem executou.
- vendas.recibo-servico (nova): objetivo, quando usar, e o aviso de que não é documento fiscal.
- url_video: a gravar.

## Impacto no banco
V085 (tipo_item, produto_servico, cfg_usa_servicos) · V086 (trigger) — ver §7.2.

## Impacto nas integrações
Nenhuma. (A integração com marketplaces voltou a Implementações Futuras em 2026-08-28, V084.)

## Non-goals desta feature
Ordem de serviço (S4) · NFS-e (S6) · agenda · recorrência · comissão em R$ fixo ·
ficha do pet/veículo · NFC-e conjugada.

## Questões abertas
P1 (padrão do parâmetro) — dono do produto, BLOQUEANTE para a tela.
P4 (contador + CNPJ com IM) — dono do produto, bloqueante só para S5/S6.

## Métrica de sucesso
Uma petshop piloto operando um mês inteiro com banho e tosa lançados no PDV, com o Relatório
de Estoque limpo e a DRE fechando com o caixa.
```

## Anexo B — ADRs propostos (template §6)

```markdown
# ADR-018: Serviço mora em `produto`, com extensão 1:1   Status: Proposto
Data: 2026-08-28 · Decisores: Claudio Calixto (dono do produto)

## Contexto
O ERP precisa vender mão de obra. Mas `venda` não tem tabela de itens: o item da venda É a linha
do ledger de estoque (`produto_movimento_detalhe`), e 33 arquivos Java leem dela — incluindo
DRE, Lucratividade, Comissões, Relatório de Vendas, o histórico do cliente e o montador da
NFC-e. Um item que não gere linha no ledger é invisível para todos eles, sem erro.

## Decisão
Serviço é uma linha de `produto` com `tipo_item = 'SERVICO'`, acompanhada de uma linha 1:1 em
`produto_servico` com o que só serviço tem. O item de serviço percorre o mesmo ledger; quem o
impede de mexer no saldo é a trigger `fn_atualiza_estoque_movimento`, não os serviços Java.

## Alternativas consideradas
1. Tabela `servico` + `venda_servico_item` separadas — conceitualmente mais limpa; obriga a
   criar a tabela de itens de venda que o sistema decidiu não ter, e transforma cada um dos 33
   leitores num UNION. Enquanto não virarem, serviço é invisível em silêncio.
2. Só a flag em `produto`, sem extensão — mais barata; não tem onde guardar LC 116, alíquota de
   ISS, duração e comissão própria, e produziria ~8 colunas nulas para quem não vende serviço.

## Consequências
Positivas: nenhum dos 33 leitores muda; DRE/Lucratividade/Comissões/Vendas funcionam no
primeiro dia; a regra de "não mexe em estoque" fica num lugar só, como a V054.
Negativas/dívidas: `produto` deixa de significar "mercadoria"; serviço queima um EAN-13 do
gerador global; 8 consultas passam a precisar de filtro por `tipo_item`, e esquecer um deles é
silencioso — mitigado por um teste que varre `api/src/main`, no espírito do
`AcoesPorTelaConferemTest`.
Gatilho de revisão: se o custo dos filtros passar de ~10 lugares, ou se serviço passar a
precisar de atributos que não cabem numa extensão 1:1.
```

```markdown
# ADR-019: NFS-e própria, exclusivamente contra o Emissor Nacional   Status: Proposto
Data: 2026-08-28 · Decisores: Claudio Calixto (dono do produto)

## Contexto
O `MODULOFISCAL.md` §4.4 lista NFS-e como non-goal permanente por causa de "~5.500 regras
municipais". Mas a DF37 já limitou o produto a MEI e Simples Nacional — e são exatamente esses
dois que a lei obriga a emitir pelo Emissor Nacional: MEI a tomador PJ desde 01/09/2023
(Res. CGSN 169/2022) e ME/EPP do Simples desde 01/11/2026 (Res. CGSN 191/2026). Os 5.571 entes
federativos aderiram à plataforma nacional (gov.br/nfse, planilha de 19/08/2026). O problema
dos milhares de padrões municipais pertence a Lucro Real e Presumido, que o produto já recusa.

## Decisão
Emissão própria da NFS-e, exclusivamente contra a API do Emissor Nacional, atrás da interface
`EmissorDeNfse`. Município que não opere no padrão nacional não é atendido — escopo de produto,
não funcionalidade faltando, e informado ao lojista na Conformidade Fiscal, antes do balcão.

## Alternativas consideradas
1. Provedor de NFS-e por API (Focus NFe, eNotas, PlugNotas, NFE.io, WebmaniaBR) — cobertura
   municipal ampla e integração pronta; custo por nota contra um modelo de receita que cobra por
   volume de vendas e oferece 100 vendas/mês de graça; pediria o mesmo certificado A1 que já
   está cifrado no nosso banco; e é dependência que pode desligar — a Nuvem Fiscal anunciou em
   22/04/2026 a desativação em 31/07/2026. Fica como plano B, plugável em uma classe: a escolha
   seria a Focus NFe (preço público, plano com CNPJ ilimitado, credencial flexível).
2. Integração município a município (o caminho do Bling, 700+) — manutenção sem fim, e
   desnecessária para este ICP.

## Consequências
Positivas: reaproveita certificado, assinatura XMLDSIG, transporte, guarda no MinIO e exportação
ao contador; nenhum custo por nota, o que preserva "paga-se volume, não funcionalidade"
(ADR-015) e permite oferecer emissão ilimitada — coisa que os verticais de petshop cobram como
add-on; e o layout nacional já traz o grupo IBSCBS, então nasce pronto para a reforma.
Negativas/dívidas: dobra o compromisso permanente do §2.2 do MODULOFISCAL (dois emissores, dois
calendários de Nota Técnica); a API não foi testada por nós e o layout mudou três vezes em nove
meses; e municípios fora do padrão nacional ficam sem atendimento.
Gatilho de revisão: se o bloco S0 (PoC em Produção Restrita) não fechar, ou se a lacuna entre
"aderiu" e "opera" atingir clientes reais.
```

---

## Anexo C — Fontes, e o que **não** foi confirmado

### C.1 Legislação e padrão nacional (consultadas em 2026-08-28)

| Fato | Fonte | Confiança |
|---|---|---|
| **5.571 entes federativos aderiram** à plataforma nacional; planilha `municipios-aderentes-20260819.xlsx` | [gov.br/nfse — Monitoramento das Adesões](https://www.gov.br/nfse/pt-br/municipios/monitoramento-adesoes) | ✅ **oficial** |
| ME/EPP do Simples: obrigatório **01/11/2026**, Res. CGSN 191/2026 (adiou de 01/09/2026) | [gov.br/nfse](https://www.gov.br/nfse/pt-br/noticias/comite-gestor-do-simples-nacional-prorroga-a-obrigatoriedade-de-emissao-de-notas-fiscais-de-servico-pelo-emissor-nacional-da-nfs-e) · [Receita Federal](https://www.gov.br/receitafederal/pt-br/assuntos/noticias/2026/agosto/simples-nacional-nfs-e-nacional-sera-obrigatoria-para-me-e-epp-a-partir-de-1o-de-novembro-de-2026) | ✅ **oficial** |
| MEI → PJ obrigatório desde **01/09/2023** (Res. CGSN 169/2022 + Res. CGNFS-e 3/2023) | [gov.br/nfse — MEI](https://www.gov.br/nfse/pt-br/mei-prestadores-de-servico-de-todo-o-pais-estao-obrigados-a-emitir-nfs-e) | ✅ oficial |
| Manual da API do Emissor Público, v1.2, out/2025 | [gov.br/nfse — Manual](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/documentacao-atual/manual-contribuintes-emissor-publico-api-sistema-nacional-nfs-e-v1-2-out2025.pdf) | ✅ oficial (**não lido** — só localizado) |
| Documentação técnica, NTs, XSDs, APIs de Produção Restrita e Produção | [gov.br/nfse — Documentação Técnica](https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica) | ✅ oficial |
| **392 municípios** emitindo efetivamente no modelo nacional | [contabeis.com.br](https://www.contabeis.com.br/artigos/73364/reforma-tributaria-nfs-e-nacional-com-baixa-adesao-municipal/) | ⚠️ secundária, **conflita** com o painel oficial de adesão |
| NT 004 v2.00 (10/12/2025) traz o grupo `IBSCBS`; NT 007 v1.0 (07/02/2026) | [TOTVS](https://www.totvs.com/blog/fiscal-clientes/nfs-e-nacional-liberacao-do-ambiente-de-testes-novo-layout-do-ibs-cbs-e-atualizacao-da-nota-tecnica-004/) · [MRS Advogados](https://mrsadvogados.com/nota-tecnica-se-cgnfs-e-no-007-2026-versao-1-0-atualizacoes-do-layout-da-nfs-e/) | ⚠️ secundária (citam os PDFs oficiais) |
| Cronograma de destaque IBS/CBS: 01/10/2026 serviços, 01/12/2026 plataformas, 01/01/2027 Simples — Ato Conjunto RFB/CGIBS nº 4/2026 | [reformatributaria.com](https://www.reformatributaria.com/documentos-fiscais/receita-e-comite-gestor-publicam-cronograma-em-3-lotes-para-inicio-da-obrigatoriedade-de-destaques-de-ibs-cbs-leia/) · [MRS Advogados](https://mrsadvogados.com/publicado-ato-conjunto-rfb-cgibs-no-4-2026-definido-o-cronograma-de-obrigatoriedade-dos-documentos-fiscais-eletronicos-do-ibs-e-da-cbs/) | ⚠️ secundária |
| API exige mTLS + certificado ICP-Brasil A1/A3, XMLDSIG, JSON com XML GZip+Base64 | notagateway.com.br · ndd.tech (via busca) | ⚠️ **secundária — é o item mais crítico a confirmar (bloco S0)** |
| `cTribNac` 6 dígitos, 338 códigos; `cTribMun` 3 dígitos opcional | [notaapi.com.br](https://notaapi.com.br/codigos-tributacao-nacional) | ⚠️ secundária |
| Rejeição **E0617** (`pAliqAplic` só para optante do Simples) | [Tecnospeed](https://atendimento.tecnospeed.com.br/hc/pt-br/articles/36180311455511) | ⚠️ secundária |
| Nuvem Fiscal: desativação anunciada em 22/04/2026, efetiva 31/07/2026 | [Fórum Projeto ACBr](https://www.projetoacbr.com.br/forum/topic/91922-comunicado-de-desativa%C3%A7%C3%A3o-do-servi%C3%A7o-nuvem-fiscal-22042026/) | ⚠️ secundária, mas consistente em duas fontes |

### C.2 ⛔ O que NÃO foi confirmado

1. **O número de municípios efetivamente OPERANDO no ADN.** O painel oficial publica **adesão**
   (5.571 = 100%); as fontes de operação divergem (392 × 1.898) e nenhuma é oficial.
2. **O contrato exato da API do Emissor Nacional.** Os PDFs foram localizados, **não lidos**.
   Autenticação, formato e endpoints vêm de fonte secundária.
3. **Como `pAliqAplic` é preenchido para MEI e para Simples.** A inferência (MEI vazio, Simples com
   percentual efetivo) é lógica e **não** foi confirmada em fonte primária. É item obrigatório do S0.
4. **O efeito prático para o contribuinte do Simples num município que aderiu mas não opera.**
5. **A lista completa das 25 exceções do art. 3º da LC 116/2003** — só os incisos I, III e XX foram
   confirmados em detalhe.
6. **O prazo-padrão do evento de cancelamento simples** no ADN (as fontes divergem: 5 dias úteis
   nacional × 72 h em Curitiba × 30 dias em outros).
7. **A data de descontinuação da API antiga do DANFSe** (julho × agosto de 2026).
8. **Formato e tamanho da chave de acesso da NFS-e nacional.**

### C.3 Concorrentes (consultados em 2026-08-28)

Bling ([serviço](https://www.bling.com.br/servico), [OS](https://www.bling.com.br/funcionalidades/ordem-servico), [NFS-e](https://www.bling.com.br/funcionalidades/nota-fiscal-servico-eletronica)) ·
Olist/Tiny ([sistema-erp](https://olist.com/sistema-erp/)) ·
Omie ([serviços](https://ajuda.omie.com.br/pt-BR/articles/498954), [OS](https://ajuda.omie.com.br/pt-BR/articles/498949)) ·
ContaAzul ([cadastro de serviço](https://ajuda.contaazul.com/hc/pt-br/articles/7538793724301), [OS](https://contaazul.com/blog/ordem-de-servico/), [notas](https://contaazul.com/funcionalidades/emissao-de-nota-fiscal/)) ·
Granatum O.S. ([static.granatum.com.br/os](https://static.granatum.com.br/os/)) ·
GestãoClick ([planos](https://www.gestaoclick.com.br/planos-erp/), [agendamento](https://www.gestaoclick.com.br/programa-de-agendamento-de-servicos/), [comissão](https://www.gestaoclick.com.br/controle-de-comissao-de-vendedores/)) ·
Vhsys ([NFS-e](https://ajuda.vhsys.com.br/categorias/servicos/nota-fiscal-de-servico-nfs-e)) ·
Nex ([OS](https://ajuda.nextar.com.br/tutorial/ordem-de-servico-pelo-nex)) ·
SimplesVet ([preços](https://simples.vet/precos/)) · Sispet (sispet.com.br) ·
Wüst (wustsoftware.com.br — ⚠️ página bloqueou leitura direta, HTTP 403; dados vieram do resumo de busca) ·
OficinaSoft (oficinasoft.com.br) · AutoGest (autogestapp.com.br).

**Provedores de NFS-e:** Focus NFe ([preços](https://focusnfe.com.br/precos/), [municípios](https://focusnfe.com.br/cidades-integradas-nfse/)) ·
NFE.io ([preços](https://nfe.io/precos/emissao-nfse/)) ·
PlugNotas/Tecnospeed ([doc. padrão nacional](https://atendimento.tecnospeed.com.br/hc/pt-br/articles/38360053945367-Documenta%C3%A7%C3%A3o-T%C3%A9cnica-Padr%C3%A3o-NFS-e-Nacional)) ·
WebmaniaBR ([municípios](https://ajuda.webmania.com.br/pt-BR/articles/12680670-municipios-homologados-nota-fiscal-de-servico-webmania-nfs-e), [SDK Java](https://github.com/webmaniabr/NFe-Java)) ·
eNotas ([NFS-e nacional](https://atendimento.enotas.com.br/hc/pt-br/articles/35774188189965)).

⛔ **Nomes da lista original que a pesquisa NÃO localizou como produtos existentes** — não assuma
que a busca falhou; provavelmente não existem com esse nome: *ClickVet* (é telemedicina veterinária
domiciliar, não ERP), *Vet Smart*, *Bichos e Cia*, *Pet Manager*, *Simples Pet* (existe "Pet
Simples", nome próximo), *Mecânica Online*, *MyGarage*, *Rotina Mecânica*, e *"Oficina Digital"*
como marca própria (é termo genérico da categoria).

⚠️ **Preços de ContaAzul e Vhsys divergem entre fontes secundárias.** Antes de usar em decisão de
precificação, conferir no checkout oficial.

---

## Anexo D — O que este estudo tocou no repositório

**Nada.** Nenhuma migration, nenhuma classe Java, nenhum `.tsx`, nenhuma alteração em documento
existente. O único arquivo criado é este.

⚠️ **Duas coisas terão de ser atualizadas quando (e se) isto for aprovado**, e é melhor registrar
agora do que descobrir depois:

1. `docs/MODULOFISCAL.md` §4.4 — a linha de NFS-e vira `~~texto original~~ — superado em <data>`,
   nunca apagada (`feedback_non_goals_apodrecem`).
2. `docs/PLANO-DE-NEGOCIO.md` §3 — o ICP diz *"micro e pequeno varejista"*. Petshop com banho e
   tosa e oficina com mão de obra não são só varejo, e o Anexo A do plano precisa acompanhar.
