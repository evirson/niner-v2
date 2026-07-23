# Leitura de XML de NF-e — Mapeamento de Campos

## Objetivo

Ler arquivos XML de NF-e (nota fiscal eletrônica) e extrair dados de **fornecedor** e **produtos** para uso no sistema.

Arquivos de teste utilizados até agora (131 arquivos, 42 fornecedores — todos processáveis —, 3261 itens no total):
- `NFe-28260307414643000201550000003241321000141393.xml` + 2 arquivos da pasta `XML_SELECIONADO` (`DAKOTA_CALCADOS_S_A_2025-04-10_NFe-...4751.XML`, `DAKOTA_CALCADOS_S_A_2026-02-18_NFe-...4220.XML`) — DAKOTA CALCADOS S/A (115 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`DAKOTA_NORDESTE_S_A_2024-03-07_NFe-...9833.XML`, `DAKOTA_NORDESTE_S_A_2026-03-02_NFe-...0841.XML`) — DAKOTA NORDESTE S/A (mesma marca/padrão da DAKOTA CALCADOS, CNPJ distinto, 55 itens)
- `NFe-23240600954394000117550040028149621117942360.xml` + 3 arquivos da pasta `XML_SELECIONADO` (`VULCABRAS___CE_CALCADOS_E_ARTIGOS_ESPORTIVOS_S_A_2024-03-04_NFe-...8242.XML`, `VULCABRAS___CE_CALCADOS_E_ARTIGOS_ESPORTIVOS_S_A_2025-01-15_NFe-...0060.XML`, `VULCABRAS___CE_CALCADOS_E_ARTIGOS_ESPORTIVOS_S_A_2026-02-19_NFe-...4809.XML`) — VULCABRAS (23 itens; os 3 arquivos novos revelaram que `COR` no Padrão B nem sempre é um nome de cor legível — pode ser um código híbrido cor+número, `ROSA46`, ou opaco, `ALGPSC`, dependendo da marca/linha)
- `NFe-50250996261607000447550000007325301622303850.xml`, `NFe-50251196261607000447550000007347271518870823.xml`, `NFe-50251196261607000447550000007347351330207037.xml` + 3 arquivos da pasta `XML_SELECIONADO` (`KIDY_BIRIGUI_CALCADOS_INDUSTRIA_E_COMERCIO_LTDA_FIL_MS_2026-03-06_NFe-...3328.XML`, `KIDY_BIRIGUI_CALCADOS_INDUSTRIA_E_COMERCIO_LTDA_FIL_MS_2026-03-06_NFe-...3602.XML`, `KIDY_BIRIGUI_CALCADOS_INDUSTRIA_E_COMERCIO_LTDA_FIL_MS_2026-03-06_NFe-...8301.XML`) — KIDY BIRIGUI CALCADOS (99 itens, novo sub-caso de cor com qualificador de 2 palavras — linha `HAPPY`)
- `NFe-43260388379771003602550040007906441790644458.xml`, `NFe-43260288379771001588550040004523031452303414.xml`, `NFe-43251288379771003602550040007319711731971425.xml`, `NFe-43250988379771004170550050005844951584495571.xml`, `NFe-43250288379771000697550030006702241670224300.xml`, `NFe-43240888379771004170550050001706841170684590.xml` + 4 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_BEIRA_RIO_S_A_2024-01-10_NFe-...1407.XML`, `CALCADOS_BEIRA_RIO_S_A_2025-02-07_NFe-...3472.XML`, `CALCADOS_BEIRA_RIO_S_A_2026-01-22_NFe-...9326.XML`, `CALCADOS_BEIRA_RIO_S_A_2026-01-22_NFe-...4323.XML`) — CALCADOS BEIRA RIO S/A (176 itens)
- `NFe-43250688887021000111550070008416861304619353.xml` + 3 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_MARTE_LTDA_2024-01-29_NFe-...3062.XML`, `CALCADOS_MARTE_LTDA_2025-03-06_NFe-...0638.XML`, `CALCADOS_MARTE_LTDA_2026-02-27_NFe-...7307.XML`) — CALCADOS MARTE LTDA. (24 itens)
- 9 arquivos da pasta `XML_SELECIONADO` (`A_GRINGS_SA_2024-03-01_*.XML` ×3, `A_GRINGS_SA_2025-03-27_*.XML` ×3, `A_GRINGS_SA_2026-03-16_*.XML` ×3) — A. GRINGS S.A. (325 itens)
- 4 arquivos da pasta `XML_SELECIONADO` (`ALPARGATAS_S_A_2024-01-23_NFe-...2103.XML`, `ALPARGATAS_S_A_2025-07-05_NFe-...7932.XML`, `ALPARGATAS_S_A_2026-06-25_NFe-...1035.XML`, `ALPARGATAS_S_A_2026-06-25_NFe-...7943.XML`) — ALPARGATAS S/A / Havaianas (50 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`BR_COMERCIO_IMPORTACAO_E_EXPORTACAO_LTDA_2024-05-27_NFe-...0148.XML`, `BR_COMERCIO_IMPORTACAO_E_EXPORTACAO_LTDA_2024-06-28_NFe-...9247.XML`, `BR_COMERCIO_IMPORTACAO_E_EXPORTACAO_LTDA_2024-06-28_NFe-...4224.XML`) — BR8 COMERCIO IMPORTACAO E EXPORTACAO LTDA (mochilas, 8 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_BEBECE_LTDA_2024-03-26_NFe-...0116.XML`, `CALCADOS_BEBECE_LTDA_2025-02-28_NFe-...7011.XML`, `CALCADOS_BEBECE_LTDA_2026-03-02_NFe-...1223.XML`) — CALCADOS BEBECE LTDA (Padrão J, sem TAMANHO no XML — decisão do usuário: processar mesmo assim, 25 itens)
- 4 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_BIBI_LTDA_2024-09-10_NFe-...9369.XML`, `CALCADOS_BIBI_LTDA_FILIAL_BA_2025-04-23_NFe-...2479.XML`, `CALCADOS_BIBI_LTDA_FILIAL_BA_2026-04-08_NFe-...0865.XML`, `CALCADOS_BIBI_LTDA_FILIAL_BA_2026-04-08_NFe-...1733.XML`) — CALCADOS BIBI LTDA (marca infantil, 135 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_BOTTERO_LTDA_2024-04-04_NFe-...7682.XML`, `CALCADOS_BOTTERO_LTDA_2025-09-11_NFe-...6343.XML`, `CALCADOS_BOTTERO_LTDA_2026-03-19_NFe-...6162.XML`) — CALCADOS BOTTERO LTDA (72 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_FERRACINI_LTDA_2024-02-23_NFe-...8100.XML`, `CALCADOS_FERRACINI_LTDA_2025-02-24_NFe-...9411.XML`, `CALCADOS_FERRACINI_LTDA_2025-12-04_NFe-...4916.XML`) — CALCADOS FERRACINI LTDA (Padrão I, sem COR no XML, 53 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`CALCADOS_PEGADA_NORDESTE_LTDA_2024-03-15_NFe-...0810.XML`, `CALCADOS_PEGADA_NORDESTE_LTDA_FL__2025-04-07_NFe-...9438.XML`, `CALCADOS_PEGADA_NORDESTE_LTDA_FL__2026-03-06_NFe-...8866.XML`) — CALCADOS PEGADA NORDESTE LTDA (Padrão K, sem COR no XML, TAMANHO no infAdProd, 26 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`CAMBUCI_SA____SR_2025-09-23_NFe-...3063.XML`, `CAMBUCI_SA___BA_2024-07-05_NFe-...7687.XML`, `CAMBUCI_SA___BA_2026-05-25_NFe-...2941.XML`) — CAMBUCI S.A. / Penalty (acessórios esportivos + material de PDV, não-calçado, 42 itens)
- 1 arquivo da pasta `XML_SELECIONADO` (`CLASSE_INDUSTRIA_E_ARTEF_DE_COURO_LTDA_2024-11-28_NFe-...3916.XML`) — CLASSE INDÚSTRIA E ARTEF. DE COURO LTDA (bolsas de couro, não-calçado, 25 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`CONDE_DUCK_IND_DE_MEIAS_LTDA_2024-06-07_NFe-...0026.XML`, `CONDE_DUCK_IND_DE_MEIAS_LTDA_2025-07-31_NFe-...9844.XML`, `CONDE_DUCK_IND_DE_MEIAS_LTDA_2026-04-30_NFe-...1348.XML`) — CONDE DUCK IND DE MEIAS LTDA (meias, não-calçado, 281 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`COOPERSHOES_COOPTRABINDCALCJOANETENSE_LTDA_2024-03-07_NFe-...5385.XML`, `COOPERSHOES_COOPTRABINDCALCJOANETENSE_LTDA_2025-02-28_NFe-...2149.XML`, `COOPERSHOES_COOPTRABINDCALCJOANETENSE_LTDA_2026-05-08_NFe-...6290.XML`) — COOPERSHOES (Chuck Taylor All Star, Padrão B com sub-caso de modelo fixo, 106 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`DEMOCRATA_CALCADOS_E_ARTEFATOS_DE_COURO_LTDA_2024-03-15_NFe-...7248.XML`, `DEMOCRATA_CALCADOS_E_ARTEFATOS_DE_COURO_LTDA_2025-10-24_NFe-...6509.XML`, `DEMOCRATA_CALCADOS_E_ARTEFATOS_DE_COURO_LTDA_2026-03-06_NFe-...9653.XML`) — DEMOCRATA CALCADOS E ARTEFATOS DE COURO LTDA (Padrão O, cor truncada no xProd, 69 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`DILLY_NORDESTE_INDUSTRIA_DE_CALCADOS_LTDA_2024-09-11_NFe-...9404.XML`, `DILLY_NORDESTE_INDUSTRIA_DE_CALCADOS_LTDA_2025-04-24_NFe-...8539.XML`, `DILLY_NORDESTE_INDUSTRIA_DE_CALCADOS_LTDA_2025-04-24_NFe-...8584.XML`) — DILLY NORDESTE INDUSTRIA DE CALCADOS LTDA (Padrão H, sub-caso marcador `WC-\d+`, 54 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`FILA_BRASIL_LTDA_2024-03-07_NFe-...3041.XML`, `FILA_BRASIL_LTDA_2025-02-14_NFe-...0773.XML`, `FILA_BRASIL_LTDA_2026-03-17_NFe-...7552.XML`) — FILA BRASIL LTDA (Padrão P, campos delimitados por `-`, marcador `Tam:`, 48 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`FISIA_COMERCIO_DE_PRODUTOS_ESPORTIVOS_SA_2024-01-06_NFe-...4617.XML`, `FISIA_COMERCIO_DE_PRODUTOS_ESPORTIVOS_SA_2025-05-15_NFe-...5236.XML`) — FISIA COMERCIO DE PRODUTOS ESPORTIVOS SA (distribuidora Nike, Padrão Q, TAMANHO vem do cProd, 22 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`GRENDENE_S_A___FILIAL__CRA_2024-06-27_NFe-...9000.XML`, `GRENDENE_S_A___FILIAL__CRA_2025-06-27_NFe-...2544.XML`, `GRENDENE_S_A___MATRIZ_SOB_2026-04-27_NFe-...3070.XML`) — GRENDENE S/A (chinelos MORMAII/RIDER, Padrão R, cor truncada sem correção, sem TAMANHO — decisão do usuário, 93 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`IND_E_COM_DE_CALC_TRENTO_LTDA_2024-09-02_NFe-...3026.XML`, `IND_E_COM_DE_CALC_TRENTO_LTDA_2024-09-02_NFe-...5369.XML`) — IND E COM DE CALC TRENTO LTDA (Padrão S, sem COR nem TAMANHO — decisão do usuário, 23 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`INDITEC_INDUSTRIA_TEXTIL_E_CONFECCOES_EIRELI_2024-06-06_NFe-...4194.XML`, `INDITEC_INDUSTRIA_TEXTIL_E_CONFECCOES_EIRELI_2024-06-06_NFe-...4582.XML`) — INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI (bonés, não-calçado, Padrão T, TAMANHO = "UN" — decisão do usuário, 2 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`INDUSTRIA_DE_CALCADOS_GONCALVES_LTDA_2025-02-26_NFe-...6388.XML`, `INDUSTRIA_DE_CALCADOS_GONCALVES_LTDA_2025-04-24_NFe-...9299.XML`) — INDUSTRIA DE CALCADOS GONCALVES LTDA. (Padrão U, prefixo duplicado + bloco de cor multi-segmento, 46 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`INDUSTRIA_E_COM_DE_CALC_SYG_STAR_LTDA_2025-03-31_NFe-...8310.XML`, `INDUSTRIA_E_COM_DE_CALC_SYG_STAR_LTDA_2025-04-01_NFe-...5274.XML`) — INDUSTRIA E COM DE CALC SYG STAR LTDA (Padrão V, `cProd` delimitado por `.`, marcador `EM` opcional, modificador de cor prefixado `TODO`, 65 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`INDUSTRIA_E_COMERCIO_LEJON_LTDA_2024-02-29_NFe-...6595.XML`, `INDUSTRIA_E_COMERCIO_LEJON_LTDA_2025-10-16_NFe-...1985.XML`) — INDUSTRIA E COMERCIO LEJON LTDA (Padrão W, `COD_MODELO` do `cProd` repetido literalmente dentro do `xProd`, usado como âncora, 134 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`KL_INDUSTRIA_E_COMERCIO_LTDA_2025-06-10_NFe-...7287.XML`, `KL_INDUSTRIA_E_COMERCIO_LTDA_2026-02-05_NFe-...0910.XML`, `KL_INDUSTRIA_E_COMERCIO_LTDA_2026-02-05_NFe-...4598.XML`) — KL INDUSTRIA E COMERCIO LTDA (Padrão B, marca Redikal, `cProd` delimitado por `.`, roteamento especial para não colidir com Padrão D, 125 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`LONG_FEET___LTDA_2025-06-27_NFe-...2500.XML`, `LONG_FEET___LTDA_2025-07-02_NFe-...2507.XML`) — LONG FEET - LTDA (Padrão X, produtos de cuidado para calçados, marcadores explícitos `TAM`/`COR` no `xProd`, 28 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`MACBOOT_INDUSTRIA_E_COMERCIO_DE_CALCADOS_2024-05-29_NFe-...4906.XML`, `MACBOOT_INDUSTRIA_E_COMERCIO_DE_CALCADOS_2024-05-29_NFe-...5006.XML`, `MACBOOT_INDUSTRIA_E_COMERCIO_DE_CALCADOS_2024-08-21_NFe-...4540.XML`) — MACBOOT INDUSTRIA E COMERCIO DE CALCADOS (Padrão Y, `cProd` = `cEAN` completo, código SKU interno + marcador `N.` para o tamanho, 110 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`NEW_BRASIL_ARTIGOS_ESPORTIVOS_LTDA_2024-02-21_NFe-...5103.XML`, `NEW_BRASIL_ARTIGOS_ESPORTIVOS_LTDA_2026-03-13_NFe-...4833.XML`, `NEW_BRASIL_ARTIGOS_ESPORTIVOS_LTDA_2026-03-13_NFe-...4839.XML`) — NEW BRASIL ARTIGOS ESPORTIVOS LTDA (Padrão P, distribuidora New Balance, confirma o gatilho `Tam:` como estrutural — 2º fornecedor no mesmo padrão da FILA, 53 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`NOVOPE_CALCADOS_LTDA_2024-02-08_NFe-...0023.XML`, `NOVOPE_CALCADOS_LTDA_2024-02-08_NFe-...0044.XML`, `NOVOPE_CALCADOS_LTDA_2024-02-08_NFe-...0065.XML`) — NOVOPE CALCADOS LTDA (tênis infantil, Padrão Z novo — prefixo `COD_MODELO_COD_COR` descartável, marca própria/venda em grade sem TAMANHO no XML, decisão do usuário, 23 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`PUMA_SPORTS_LTDA_2024-02-07_NFe-...1490.XML`, `PUMA_SPORTS_LTDA_2025-02-19_NFe-...2067.XML`, `PUMA_SPORTS_LTDA_2026-02-27_NFe-...4256.XML`) — PUMA SPORTS LTDA (Padrão AA novo — prefixo literal `CALCADOS-`, cor separada por `-` dentro do próprio `xProd`, nomes de cor com prefixo de marca `Puma`/`PUMA`, TAMANHO sempre confere com o sufixo do `cProd` mas este nunca é cortado por não ter `-`, 34 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`SAPATARIA_BERTELLI_IND_E_COMERCIO_2024-02-27_NFe-...1021.XML`, `SAPATARIA_BERTELLI_IND_E_COMERCIO_2025-11-12_NFe-...4766.XML`) — SAPATARIA BERTELLI IND. E COMERCIO (Padrão AB novo — COR = última palavra do `xProd`, venda em grade sem TAMANHO no XML, decisão do usuário, `REFERENCIA_PRODUTO` não distingue cor, 23 itens)
- 2 arquivos da pasta `XML_SELECIONADO` (`SKECHERS_DO_BRASIL_CALCADOS_LTDA_2025-11-06_NFe-...1572.XML`, `SKECHERS_DO_BRASIL_CALCADOS_LTDA_2026-06-30_NFe-...1142.XML`) — SKECHERS DO BRASIL CALCADOS LTDA (Padrão AC novo — `xProd` só tem nome de linha/modelo, `TAMANHO` e código de cor vêm do `cProd` em 4 segmentos, `COR` sempre vazia por falta de nome legível, 87 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`STAMPA_ARTEFATOS_DE_COURO_LTDA_2026-04-20_NFe-...4430.XML`, `STAMPA_ARTEFATOS_DE_COURO_LTDA_2026-05-08_NFe-...7732.XML`, `STAMPA_ARTEFATOS_DE_COURO_LTDA_2026-06-26_NFe-...3687.XML`) — STAMPA ARTEFATOS DE COURO LTDA (calçados validam o Padrão H existente + novo sub-caso de cor com 4 segmentos; bolsas/mochilas usam o novo Padrão AE, `TAMANHO` sempre o literal `UNI`, 101 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`VIA_VIP_CALCADOS_LTDA_2024-04-16_NFe-...2866.XML`, `VIA_VIP_CALCADOS_LTDA_2025-09-19_NFe-...3997.XML`, `VIA_VIP_CALCADOS_LTDA_2026-06-25_NFe-...6679.XML`) — VIA VIP CALCADOS LTDA (Padrão AD novo — sufixo `REF [cProd]` descartável no fim do `xProd`, `cProd` delimitado por `.`; colide com o gatilho do sub-caso CALCADOS MARTE, 236 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`NOVAPELLI_IND_COM_IMP_EXP_LTDA_2024-03-26_NFe-...3349.XML`, `NOVAPELLI_IND_COM_IMP_EXP_LTDA_2025-05-05_NFe-...8960.XML`, `NOVAPELLI_IND_COM_IMP_EXP_LTDA_2026-06-30_NFe-...2482.XML`) — NOVAPELLI IND. COM. IMP. EXP. LTDA (Padrão AF novo — `xProd` truncado em largura fixa, `CINTO` com TAMANHO só recuperável do `cProd` (`.Tnnn`) e `CARTEIRA` sem TAMANHO nenhum (`= "UN"`, confirmado pelo usuário); colide com o gatilho do Padrão F (token isolado `TAM`), 148 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`ZETI_COMERCIO_E_IMPORTACAO_E_ART_DO_VEST_LTDA_2024-12-11_NFe-...7700.XML`, `ZETI_COMERCIO_E_IMPORTACAO_E_ART_DO_VEST_LTDA_2025-04-28_NFe-...1968.XML`, `ZETI_COMERCIO_E_IMPORTACAO_E_ART_DO_VEST_LTDA_2026-03-13_NFe-...9182.XML`) — ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA (Padrão AG novo — marca "Zeti" com marcador de coleção `ZT`/`CA`, `BOLSA`/`CARTEIRA`/`BOLSA-CARTEIRA` sempre `TAMANHO = "UN"` (confirmado pelo usuário), 1 item isolado de `MALA` sem marcador nenhum (`TAMANHO = "UN"` também, decisão do usuário nesta rodada), 68 itens)
- 3 arquivos da pasta `XML_SELECIONADO` (`LUPO_NORDESTE_LTDA_2024-03-29_NFe-...7700.XML`, `LUPO_NORDESTE_LTDA_2025-04-17_NFe-...4513.XML`, `LUPO_S_A_2026-05-30_NFe-...4409.XML`) — LUPO NORDESTE LTDA. e LUPO S/A (2 CNPJs distintos do mesmo grupo; valida o Padrão N existente (meias, `TAMANHO = "UN"` reconfirmado pelo usuário), gatilho generalizado para não diferenciar caixa (`Meia` além de `MEIA`); 1 item isolado de `CUECA` também usa `TAMANHO = "UN"` por decisão do usuário nesta rodada, 29 itens)

Esses arquivos foram escolhidos propositalmente porque revelaram **padrões diferentes** de `NOME_PRODUTO`/`REFERENCIA_PRODUTO` entre fornecedores — a lógica de extração precisou detectar automaticamente qual padrão está em uso (ver seção "Detecção automática do padrão" abaixo). Ao todo já foram identificados **33 padrões distintos** (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, AA, AB, AC, AD, AE, AF e AG), além de 5 sub-casos (CALCADOS MARTE, COOPERSHOES e KIDY BIRIGUI no Padrão B, DILLY NORDESTE e STAMPA no Padrão H).

A partir desta rodada, a pasta padrão de leitura de novos lotes de XML passou a ser `C:\VETOR\PROJETO_ENTRADA_XML\XML_SELECIONADO`.

## Mapeamento de Campos

### Fornecedor (`<emit>`)

| Tag XML | Campo do sistema |
|---|---|
| `<CNPJ>` | CNPJ_FORNECEDOR |
| `<xNome>` | NOME_FORNECEDOR |

```xml
<emit>
   <CNPJ>07414643000201</CNPJ>           = CNPJ_FORNECEDOR
   <xNome>DAKOTA CALCADOS S/A</xNome>    = NOME_FORNECEDOR
</emit>
```

### Produto (`<det><prod>`)

Cada nota pode conter **múltiplas** tags `<det>`, uma por item/produto.

| Tag XML | Campo do sistema |
|---|---|
| `<cProd>` | REFERENCIA_PRODUTO (**com o sufixo de tamanho removido** — ver seção abaixo) |
| `<cEAN>` | CODIGO_BARRAS |
| `<xProd>` | NOME_PRODUTO (**sem cor, código de cor e tamanho** — ver seção abaixo) |
| `<xProd>` | **NOME_PRODUTO_ORIGINAL** — cópia do `xProd` **sem nenhuma limpeza**, para auditoria/conferência |
| `<NCM>` | CODIGO_NCM |
| `<qCom>` | QUANTIDADE |
| `<vProd>` | VALOR_PRODUTO |
| `<vDesc>` | VALOR_PRODUTO_DESCONTO (**opcional** — ver observação abaixo) |

⚠️ `<vDesc>` nem sempre existe no XML (a VULCABRAS, por exemplo, não envia essa tag quando não há desconto). Quando ausente, `VALOR_PRODUTO_DESCONTO` deve ser tratado como `0.00`, não como erro.

```xml
<prod>
   <cProd>J1523-0003-34</cProd>                           = REFERENCIA_PRODUTO (bruto, ainda com tamanho)
   <cEAN>7900282887111</cEAN>                             = CODIGO_BARRAS
   <xProd>SAPATO MISSISSIPI MIRAI PRETO 224 34</xProd>    = NOME_PRODUTO (bruto, ainda com cor/código/tamanho)
   <NCM>64029990</NCM>                                    = CODIGO_NCM
   <qCom>1.0000</qCom>                                    = QUANTIDADE
   <vProd>77.42</vProd>                                   = VALOR_PRODUTO
   <vDesc>5.42</vDesc>                                    = VALOR_PRODUTO_DESCONTO
</prod>
```

### REFERENCIA_PRODUTO: remoção do tamanho embutido (regra corrigida)

O `cProd` de alguns fornecedores vem com o tamanho embutido no último segmento (separado por `-`), ex.: `J1523-0003-34` → o `34` é o tamanho, não faz parte da referência do modelo/cor. **Mas isso não é universal** — a CALCADOS MARTE e a CALCADOS BEIRA RIO usam `cProd` como um código de referência "puro" (numérico ou alfanumérico), sem nenhum tamanho embutido (ex.: `27639725`, `90911760AFDJ011`).

**Regra corrigida (evita cortar cProd errado):** só remover o último segmento de `cProd` (split por `-`) se **os dois critérios abaixo forem verdadeiros**:
1. `cProd` contém pelo menos um `-`;
2. o segmento após o último `-` é **igual ao TAMANHO já extraído do produto** (validação cruzada, não corte cego).

Caso contrário, `REFERENCIA_PRODUTO` = `cProd` inalterado.

| cProd (bruto) | TAMANHO extraído | REFERENCIA_PRODUTO (final) | Motivo |
|---|---|---|---|
| `J1523-0003-34` | `34` | `J1523-0003` | Termina em `-34`, que bate com o tamanho → corta |
| `J1581-0005-39` | `39` | `J1581-0005` | Idem |
| `90911760AFDJ011` | `34` | `90911760AFDJ011` | Não tem `-` → mantém como está |
| `27639725` | `33` | `27639725` | Não tem `-` → mantém como está |

**Efeito colateral esperado (quando há corte):** como o mesmo modelo/cor aparece em vários tamanhos na nota, o `REFERENCIA_PRODUTO` final se repete entre linhas — ele passa a identificar o modelo+cor, não mais o item exato (essa distinção fica por conta do campo TAMANHO).

⚠️ **Bug encontrado e corrigido nesta rodada:** a versão anterior sempre cortava o último segmento de `cProd`, sem validar se ele de fato era o tamanho. Isso corrompia a referência de produto da CALCADOS MARTE e da CALCADOS BEIRA RIO (cortaria um pedaço válido do código, não um tamanho).

## Campos derivados do NOME_PRODUTO: COR e TAMANHO (e limpeza do próprio NOME_PRODUTO)

O campo `xProd` (bruto) não é texto livre: ele embute **cor** e **tamanho** no final da string. Testando com dois fornecedores diferentes, descobrimos que **o formato do final da string muda de fornecedor para fornecedor**:

### Padrão A — com código de cor (ex.: DAKOTA)

```
[TIPO] [LINHA] [MODELO] [COR] [CODIGO_COR] [TAMANHO]
```

Exemplo: `BOTA MISSISSIPI GALBANI TAN 126 39`

| Posição (a partir do fim) | Valor | Significado |
|---|---|---|
| Última palavra | `39` | TAMANHO |
| Penúltima palavra | `126` | CÓDIGO_COR (numérico) — descartado |
| Antepenúltima palavra | `TAN` | COR |
| Restante | `BOTA MISSISSIPI GALBANI` | NOME_PRODUTO final |

### Padrão B — sem código de cor (ex.: VULCABRAS, KIDY, CALCADOS MARTE)

```
[TIPO] [MARCA] [MODELO...] [COR] [TAMANHO]
```

Exemplo: `TENIS OLYMPIKUS INDEX 3 PRETO 37`

| Posição (a partir do fim) | Valor | Significado |
|---|---|---|
| Última palavra | `37` | TAMANHO |
| Penúltima palavra | `PRETO` | COR (não numérica — **não existe código de cor aqui**) |
| Restante | `TENIS OLYMPIKUS INDEX 3` | NOME_PRODUTO final (o `3` faz parte do nome do modelo, não é código de cor) |

A COR neste padrão pode ser composta por várias cores separadas por `/`, sem espaço (ex.: `CAFE/MARINHO/CARAMELO`) — isso não quebra a extração, porque `/` não é espaço.

⚠️ **Sub-caso CALCADOS MARTE — marcador `EM [MATERIAL]`:** quando `xProd` contém o token literal `EM` (ex.: `TENIS 225-003-05 EM SINTE MARFIM I23/BEGE/MIL 34`), a regra genérica acima ("COR = penúltima palavra") erra — `I23/BEGE/MIL` é um **código de referência de cor do fornecedor**, descartável, não a `COR` (que é `MARFIM`, a palavra logo após o `MATERIAL`). Ver seção "Fornecedor 4 — CALCADOS MARTE" mais abaixo para o algoritmo completo desse sub-caso (corrigido nesta rodada — a versão anterior deste documento tinha isso invertido).

⚠️ **Sub-caso COOPERSHOES — marcador de modelo fixo `CHUCK TAYLOR ALL STAR`:** aqui a `COR` pode ter **mais de uma palavra por segmento**, colada por `/` sem espaço, às vezes com um código numérico embutido no meio de um segmento — ex.: `TENIS CT04500006 CHUCK TAYLOR ALL STAR NUDE BEGE CLARO/CAQUI FOSCO 02/BRANCO 33` (três cores: `NUDE BEGE CLARO`, `CAQUI FOSCO` [código `02` descartado], `BRANCO`). A regra genérica ("COR = penúltima palavra") erraria feio aqui, capturando só `02/BRANCO`. Como a COOPERSHOES vende exclusivamente o modelo **Chuck Taylor All Star** da Converse, o nome do modelo é uma frase fixa e conhecida (`CHUCK TAYLOR ALL STAR`, às vezes com o sufixo `LIFT`) — usada como marcador de fronteira: **tudo depois dessa frase (e do `LIFT`, se presente), até o `TAMANHO` (última palavra), é o bloco de cor bruto.** Esse bloco é então dividido por `/` em segmentos (cada um pode ter 1 ou mais palavras); em cada segmento, se a última palavra for numérica, é um código de cor descartável (mesma regra do Padrão A/C). Ver seção "Fornecedor 17 — COOPERSHOES" mais abaixo.

⚠️ **Regra estendida — qualificador de cor com 2 palavras sem marcador fixo (descoberto na KIDY BIRIGUI, linha `HAPPY`):** a regra genérica ("COR = penúltima palavra") também assume que a `COR` cabe sempre em 1 palavra (ou em várias coladas por `/`, sem espaço). Isso quebra quando o **último segmento de cor** tem 2 palavras separadas por espaço, sem `/` entre elas — ex.: `TENIS KIDY HAPPY MARINHO/AZUL STONE 22` (`AZUL STONE` é a 2ª cor, um qualificador de 2 palavras, não `STONE` sozinho). **Correção aplicada ao Padrão B (geral, não só KIDY):** depois de remover o `TAMANHO` (última palavra), olhar a palavra imediatamente anterior — se ela **não contiver `/`**, é um qualificador que pertence ao último segmento de cor: juntar essa palavra com a palavra anterior a ela (que deve conter `/`, ou ser a única cor) para formar o bloco de `COR` bruto completo (ex.: `MARINHO/AZUL` + `STONE` = `MARINHO/AZUL STONE`, dividido em `COR_1 = MARINHO`, `COR_2 = AZUL STONE`). Se a palavra imediatamente anterior ao `TAMANHO` já contiver `/`, nada muda (comportamento original, ex.: `CAFE/MARINHO/CARAMELO`, `BRANCO/ROSA/LILAS`, `PRETO/PINK`). Ver seção "Fornecedor 3 — KIDY BIRIGUI CALCADOS" mais abaixo.

### Padrão C — cor/tamanho fora do NOME_PRODUTO, em `<infAdProd>` (ex.: CALCADOS BEIRA RIO)

Descoberto ao testar a BEIRA RIO: aqui o `xProd` **não tem cor nem tamanho** — é uma descrição fiscal genérica, geralmente truncada em ~40-50 caracteres:

```xml
<xProd>BOTA FEM. DE USO COMUM C/ SOLA SINT. CABEDAL SINT.</xProd>
...
<infAdProd>PRETO 01 tam: 33 7082.207.29276.15745-NAPA FLOATHER ZURIQUE/PELO - RSF 13/12 N.FCI: ...</infAdProd>
```

Cor e tamanho vêm de `<infAdProd>`, no formato `[COR] [CÓDIGO_COR][/COR2 CÓDIGO_COR2...] tam: [TAMANHO] [detalhes técnicos]`. Regra aplicada:

1. Capturar tudo antes de `" tam: "` como bloco de cor bruto, e o número logo após `"tam:"` como TAMANHO (regex: `(.+?)\s+tam:\s*(\d+)`).
2. Separar o bloco de cor por `/` (caso tenha mais de uma cor — produtos bicolor). **Se não houver nenhum `/` no bloco, mas houver `-` colando palavras sem espaço** (ex.: `CRISTAL-PRATA-AURORA BOREAL`), usar `-` como separador alternativo de cores — variante mais rara do mesmo fornecedor, vista pela primeira vez em `CALCADOS_BEIRA_RIO_S_A_2024-01-10_NFe-...1407.XML` (ver exemplo abaixo).

   ⚠️ **Correção aplicada em 2026-07-17 (regra generalizada de separador duplo):** confirmado no arquivo `CALCADOS_BEIRA_RIO_S_A_2024-02-12_NFe-...901276990427.XML` que `/` e `-` **podem aparecer juntos no mesmo bloco** (o que a versão anterior deste documento dizia nunca ter ocorrido). Nesse caso, os dois separadores são aplicados **simultaneamente** — o bloco é dividido em pedaços por qualquer ocorrência de `/` OU `-` (regex `[/\-]`), não só um dos dois. Essa regra generalizada **inclui e substitui** o passo 2 original: quando só `/` aparece, ou só `-` aparece, o resultado é idêntico ao comportamento anterior (nenhuma regressão nos exemplos já validados) — a mudança só passa a ter efeito quando os dois aparecem no mesmo bloco.
   - Exemplo (bicolor, só `-`, sem código na 2ª cor): `BRANCO OFF 526-CRISTAL` → pedaços `["BRANCO OFF 526", "CRISTAL"]` → `BRANCO OFF/CRISTAL`.
   - Exemplo (tricolor, `-` e `/` juntos): `PRETO-CRISTAL/PRETO 01` → pedaços `["PRETO", "CRISTAL", "PRETO 01"]` (o `-` separa `PRETO` de `CRISTAL`, o `/` separa `CRISTAL` de `PRETO 01`) → `PRETO/CRISTAL/PRETO`.
   - Exemplo (tricolor, `-` e `/` juntos): `BEGE 435-CRISTAL/NUDE 658` → `BEGE/CRISTAL/NUDE`.
   - Exemplo (4 cores no bloco bruto, só as 3 primeiras mantidas — regra geral já existente): `CRISTAL-SILVER/BLACK DIAMOND/PRETO 01` → pedaços `["CRISTAL", "SILVER", "BLACK DIAMOND", "PRETO 01"]` → `CRISTAL/SILVER/BLACK DIAMOND` (o `PRETO` do 4º pedaço é descartado, mesma convenção dos "COR_1/COR_2/COR_3" com 4+ cores).
3. Em cada pedaço, se a última palavra for numérica, é o código da cor → descartar, mantendo só o nome.
4. **Aplicar uma lista de cores válidas (whitelist):** percorrer as palavras restantes de trás pra frente e manter só as que são cor ou modificador de cor conhecido (ex.: "OFF", "NEON"), incluindo nomes de cor compostos por 2 palavras já catalogados (ex.: "AURORA BOREAL"). Isso remove o resto de descrição fiscal truncada que às vezes vaza para o início do bloco de cor (ver seção de correção abaixo).
5. Rejuntar os pedaços com `/` (mesmo quando o separador original no XML era `-`, o `COR` final gravado usa sempre `/` — formato único de armazenamento, consistente com a divisão em `COR_1`/`COR_2`/`COR_3`).

Exemplos de limpeza:

| infAdProd (trecho antes de "tam:") | COR (limpa) |
|---|---|
| `PRETO 01` | `PRETO` |
| `CARAMELO 1170` | `CARAMELO` |
| `BRANCO 99/BRANCO OFF 526/CINZA 1235` | `BRANCO/BRANCO OFF/CINZA` |
| `TICO CABEDAL TEXTIL NUDE 658` | `NUDE` (o passo 4 remove o "TICO CABEDAL TEXTIL", que não é cor) |
| `EXTIL CRISTAL-PRATA-AURORA BOREAL` | `CRISTAL/PRATA/AURORA BOREAL` (separador `-`, não `/` — ver passo 2; `EXTIL` é resto truncado de "TEXTIL", descartado pela whitelist) |
| `EXTIL BRANCO OFF 526-CRISTAL` | `BRANCO OFF/CRISTAL` (separador `-` só, bicolor — ver correção do passo 2 em 2026-07-17) |
| `EXTIL PRETO-CRISTAL/PRETO 01` | `PRETO/CRISTAL/PRETO` (separadores `-` e `/` juntos no mesmo bloco, tricolor) |
| `INT. BEGE 435-CRISTAL/NUDE 658` | `BEGE/CRISTAL/NUDE` (separadores `-` e `/` juntos, tricolor; `INT.` é resto truncado de "VESTIL"/"TEXTIL", descartado pela whitelist) |
| `EXTIL CRISTAL-SILVER/BLACK DIAMOND/PRETO 01` | `CRISTAL/SILVER/BLACK DIAMOND` (separadores `-` e `/` juntos, 4 cores no bloco bruto — só as 3 primeiras mantidas) |

Neste padrão, `NOME_PRODUTO` **não é alterado** (fica igual ao `xProd` bruto), porque a descrição fiscal genérica não contém cor/tamanho para remover.

**Novas entradas na whitelist de cores, confirmadas pelo usuário em 2026-07-17 (arquivo `CALCADOS_BEIRA_RIO_S_A_2024-02-12_NFe-...901276990427.XML`):** `SILVER` (cor em inglês, "prateado" — mantida como veio, sem traduzir para `PRATA`, mesma cautela já aplicada a `BROWN`/`PTO`) e o composto de 2 palavras `BLACK DIAMOND` (nome comercial de colorway, análogo a `DARK BROWN`/`AURORA BOREAL` já catalogados).

### Padrão D — marcador `SOLA` no meio do `xProd` (ex.: A. GRINGS)

Descoberto testando a A. GRINGS S.A.: aqui `xProd` tem cor e tamanho no final (como A/B), mas com uma descrição técnica do solado no meio, sinalizada pela palavra literal `SOLA`, e um sufixo técnico opcional (`SLT FORRADO`) entre a cor e o tamanho:

```
[TIPO] [COD_MODELO] [descrição do cabedal...] SOLA [MATERIAL_SOLA] [COR] [SLT FORRADO]? [TAMANHO]
```

Exemplo: `SAPATO 654046 NAP STR SOF PTO/NAP LUX LNH PTO/NAP PTO SOLA PVC EXP PRETO SLT FORRADO 34`

| Elemento | Valor | Significado |
|---|---|---|
| Última palavra | `34` | TAMANHO |
| `SLT FORRADO` (se presente, logo antes do tamanho) | — | ruído técnico ("solado forrado") — descartado, igual ao `CODIGO_COR` do Padrão A |
| Palavra seguinte (voltando) | `PRETO` | COR |
| Entre `SOLA` e a `COR` | `PVC EXP` | material do solado — **descartado**, não existe campo para isso no schema atual |
| Tudo antes de `SOLA` | `SAPATO 654046 NAP STR SOF PTO/NAP LUX LNH PTO/NAP PTO` | NOME_PRODUTO final (bloco `SOLA ...` inteiro é descartado do nome, por decisão confirmada) |

**Algoritmo de extração (Padrão D):**
1. Localizar a última ocorrência do token `SOLA` em `xProd` (separado por espaço).
2. Tudo depois de `SOLA` = `tail`. A última palavra de `tail` é o `TAMANHO` (sempre numérica, validado).
3. Se as duas palavras anteriores ao `TAMANHO` forem exatamente `SLT FORRADO`, descartá-las.
4. O que sobrar de `tail` (normalmente 1 palavra) é a `COR`. **Se não sobrar nada** (fornecedor mandou o trecho depois de `SOLA` vazio no XML — ver correção abaixo), aplicar o passo 4b antes de desistir.
4b. **Correção aplicada (fallback de COR quando o bloco depois de `SOLA` vem vazio):** ao invés de deixar `COR` em branco, usar a **última palavra imediatamente antes** do token `SOLA` (a última palavra do `NOME_PRODUTO`, passo 5) como `COR`. Essa palavra costuma ser a mesma abreviação de cor que aparece redundantemente antes de `SOLA` nos casos normais (ex.: `PTO` = `PRETO`), só que aqui é a **única** fonte de cor disponível, porque o trecho `SOLA [material] [COR] ...` foi mandado vazio pelo fornecedor. Só fica `COR` em branco (com `COR_PRECISA_REVISAO = true`) se também não houver nenhuma palavra antes de `SOLA`.
5. `NOME_PRODUTO` = tudo de `xProd` **antes** do token `SOLA` (o bloco `SOLA [material] [COR] [SLT FORRADO]? [TAMANHO]` inteiro é removido do nome). **Não é alterado** pelo fallback do passo 4b — a última palavra usada como `COR` de fallback continua também dentro do `NOME_PRODUTO` (mesma duplicação que já ocorre nos casos normais, onde a abreviação antes de `SOLA` coexiste com o nome completo da cor depois).

⚠️ **Gap de dados real do fornecedor, corrigido nesta rodada (não é mais tratado como cor irrecuperável):** nos arquivos testados, 3 SKUs (`14730400000001`, `14730500000008`, `14730800000006` — modelos 147304/147305/147308) vêm consistentemente com `"... SOLA  34"` (espaço duplo, material e cor vazios **depois** de `SOLA`) em **todos** os XMLs onde aparecem, inclusive em notas de datas diferentes. O próprio ERP da A. GRINGS não preenche esse trecho para esses modelos — **mas a cor real (abreviada) está disponível na palavra imediatamente antes de `SOLA`** (ex.: `SAPATO 147308 NAP LUX LNH PTO/NAP STR SOF PTO SOLA  34` → `COR = PTO`, a abreviação de `PRETO`, a mesma já catalogada na whitelist do Padrão C/H). Aplicando o passo 4b, esses itens deixam de precisar de `COR_PRECISA_REVISAO = true`.

⚠️ **Segunda confirmação (arquivo `A_GRINGS_SA_2024-03-01_NFe-...5851310416737.XML`, modelo `147304`, `cProd=14730400000001`):** o mesmo gap aparece aqui como `SAPATO 147304 NAP STR SOF PONT OF WHI/VNZ ARE SOLA  34` → fallback do passo 4b captura `COR = ARE`. Como `ARE` não estava na whitelist de cores (diferente de `PTO`, já catalogado), a linha foi marcada com `COR_PRECISA_REVISAO = true` até confirmação — **usuário confirmou em 2026-07-17: `ARE` = abreviação de `AREIA`**. `ARE` foi adicionado à whitelist de cores (ver lista abaixo), mantido como veio (não normalizado para `AREIA`, mesma convenção já aplicada a `PTO`/`PRETO`) — `COR_PRECISA_REVISAO` volta a `false` para esses itens.

**Validação:** 325 itens, 9 arquivos, 1 fornecedor (A. GRINGS). Tamanho extraído bateu 100% com o sufixo de `cProd` (0 divergências) em todos os itens. Após a correção do passo 4b, os 46 itens (14%) que antes ficavam com `COR_PRECISA_REVISAO = true` agora têm `COR = PTO` (fallback a partir da palavra antes de `SOLA`) — `COR_PRECISA_REVISAO` só permanece `true` se também não houver nenhuma palavra antes de `SOLA` (não observado em nenhum item testado até agora). Cores encontradas: `PRETO` (231), `BEGE` (24), `FENDI` (18), `CAPUCCINO` (6), `PTO` (46, via fallback) — `FENDI` e `CAPUCCINO` são cores novas, ainda não estavam na whitelist do Padrão C (não é necessário adicioná-las lá, pois o Padrão D não usa whitelist — a posição da cor é sempre determinística, logo após o material do solado, ou via fallback antes de `SOLA`).

### Padrão E — sem TAMANHO no XML, marca Havaianas (ex.: ALPARGATAS S/A)

Descoberto testando a ALPARGATAS S/A (fabricante da Havaianas): aqui o `xProd` **nunca termina em número** — o fornecedor simplesmente não informa o TAMANHO em lugar nenhum do XML (nem no `xProd`, nem no `infAdProd`, que só traz um GUID de referência fiscal — `nFCI`). O `cProd` também não tem `-`, e os últimos dígitos não são o tamanho literal (testado: para o mesmo produto, os finais de `cProd` variam em incrementos irregulares — `34→56→78→90`, ou seja +22/+22/+12 — compatíveis com uma numeração sequencial interna do fornecedor por grade, não com o número do calçado, que nunca seria "56" ou "78"). **Conclusão: TAMANHO não é extraível deste XML para este fornecedor — decisão confirmada: gerar a linha do produto normalmente, apenas com `TAMANHO` em branco.** Isso não é um erro de parsing, é uma limitação real do dado de origem.

**Limpeza prévia do `xProd` (lixo duplicado):** na primeira linha de cada "grade"/lote de pedido dentro da mesma nota, o fornecedor às vezes gruda um prefixo truncado (código de lote + início da descrição cortada) antes da descrição completa e correta, que aparece de novo, inteira, logo em seguida. Exemplo:

```
xProd = "7015483 70154830154SANDALIAS HAVAIANAS SLIM SPAR F0 SANDALIAS HAVAIANAS SLIM SPARKLE ME FC AREIA"
```

Regra: pegar o texto **a partir da última ocorrência da palavra `SANDALIAS`** — descarta o prefixo de lote e mantém só `SANDALIAS HAVAIANAS SLIM SPARKLE ME FC AREIA`. Nas demais linhas do mesmo produto (que não são a primeira do lote) o `xProd` já vem limpo, sem prefixo.

**COR — dois casos:**

1. **Com marcador `FC`:** quando o `xProd` (já limpo) contém `" FC "`, tudo depois de `FC` é o bloco de cor bruto; tudo antes é `NOME_PRODUTO`.
   - Exemplo: `SANDALIAS HAVAIANAS SLIM SPARKLE ME FC AREIA` → NOME_PRODUTO = `SANDALIAS HAVAIANAS SLIM SPARKLE ME`, COR = `AREIA`.
   - Exemplo com cor composta: `SANDALIAS HAVAIANAS SLIM POINT FC ROSE GOLD/BEGE PALHA` → COR = `ROSE GOLD/BEGE PALHA`.
2. **Sem marcador `FC`:** usar uma **lista de modelos Havaianas conhecidos** (mesma ideia da whitelist de cor do Padrão C, só que do lado do nome do produto). Depois de `SANDALIAS HAVAIANAS`, percorrer as palavras da esquerda pra direita enquanto baterem com a lista de modelos; a primeira palavra que não bater marca o início do bloco de `COR`.
   - Lista de modelos válida até agora: `SLIM, SPARKLE, POWER, POINT, DUAL, TRACK, WAVES, 2.0, ME, BRASIL, COLOR, ESSENTIAL, GLOSS` (os 4 últimos confirmados pelo usuário em 2026-07-17, arquivo `ALPARGATAS_S_A_2025-11-06_NFe-...9071299462923.XML` — sem eles, o algoritmo cortaria o nome do modelo errado, tratando `COLOR ESSENTIAL`/`GLOSS`/`BRASIL` como se fossem cor).
   - Exemplo: `SANDALIAS HAVAIANAS DUAL CINZA ACO/PRETO` → `DUAL` bate na lista de modelos, `CINZA` não bate → NOME_PRODUTO = `SANDALIAS HAVAIANAS DUAL`, COR = `CINZA ACO/PRETO`.
   - Exemplo: `SANDALIAS HAVAIANAS TRACK WAVES AZUL NAVAL` → `TRACK` e `WAVES` batem, `AZUL` não bate → NOME_PRODUTO = `SANDALIAS HAVAIANAS TRACK WAVES`, COR = `AZUL NAVAL`.
   - Exemplo (modelo de 2 palavras, novo): `SANDALIAS HAVAIANAS COLOR ESSENTIAL AZUL INDIGO/AZUL` → `COLOR` e `ESSENTIAL` batem na lista → NOME_PRODUTO = `SANDALIAS HAVAIANAS COLOR ESSENTIAL`, COR = `AZUL INDIGO/AZUL`. Confirmado por 2 colorways do mesmo modelo no mesmo arquivo (`AZUL INDIGO/AZUL` e `PRETO/PRETO`).
   - Exemplo (modelo composto por palavra já conhecida + palavra nova): `SANDALIAS HAVAIANAS SLIM GLOSS CINZA/CINZA GELO` → `SLIM` já era conhecido, `GLOSS` é novo mas também bate agora → NOME_PRODUTO = `SANDALIAS HAVAIANAS SLIM GLOSS`, COR = `CINZA/CINZA GELO`. Confirmado por 2 colorways (`CINZA/CINZA GELO`, `PRETO/PRATA/PRETO`).
   - Exemplo (modelo de 1 palavra, novo): `SANDALIAS HAVAIANAS BRASIL BRANCO` → `BRASIL` bate na lista → NOME_PRODUTO = `SANDALIAS HAVAIANAS BRASIL`, COR = `BRANCO`.

Em ambos os casos, o bloco de `COR` bruto é dividido por `/` em `COR_1`/`COR_2`/`COR_3` como nos demais padrões — cada segmento pode ter mais de uma palavra (ex.: `CINZA ACO/PRETO` → `COR_1 = CINZA ACO`, `COR_2 = PRETO`, `COR_3 = PRETO` repetido).

`REFERENCIA_PRODUTO` = `cProd` inalterado (nunca tem `-`, então a regra já existente de corte não se aplica — mesmo caso da CALCADOS MARTE). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe no XML — mesmo caso da VULCABRAS).

**Detecção do Padrão E:** o gatilho prático é a palavra `HAVAIANAS` presente no `xProd` (ou, de forma mais geral, `NOME_FORNECEDOR = ALPARGATAS S/A`) — a lógica de limpeza de prefixo, o marcador `FC` e a whitelist de modelo são específicos dessa marca.

**Validação:** 76 itens, 5 arquivos, 1 fornecedor (ALPARGATAS) — 50 itens/4 arquivos do lote original + 26 itens/1 arquivo novo (`ALPARGATAS_S_A_2025-11-06_NFe-...9071299462923.XML`). `TAMANHO` em branco em 100% dos itens (esperado, não é falha). Cores encontradas: `PRETO`, `AREIA`, `ROSE GOLD`, `BEGE PALHA`, `CINZA ACO`, `FERRUGEM`, `SMOKE GREEN`, `ROSA GUM`, `CORAL TROPICAL`, `AZUL NAVAL`, `BLOSSOM`, `AZUL INDIGO/AZUL`, `PRETO/PRETO`, `CINZA/CINZA GELO`, `PRETO/PRATA/PRETO`, `BRANCO` — várias com 2 palavras, nenhuma delas usa a whitelist do Padrão C (a extração aqui não depende de whitelist de cor, só da whitelist de modelo quando falta `FC`). Modelos novos confirmados nesta rodada: `SQUARE JELLY` (com marcador `FC`, sem ambiguidade), `COLOR ESSENTIAL`, `SLIM GLOSS`, `BRASIL` (sem marcador `FC`, resolvidos via extensão da whitelist de modelo).

### Padrão F — marcador `TAM` no meio/fim do `xProd`, tamanho não numérico (ex.: BR8 COMERCIO — mochilas)

Descoberto testando a BR8 COMERCIO IMPORTACAO E EXPORTACAO LTDA — primeiro fornecedor de um produto não-calçado (mochilas) visto no projeto. Aqui `xProd` tem uma descrição de composição têxtil no meio, sinalizada pelo token literal `TAM`, com a estrutura:

```
[TIPO] [MODELO] [PERCENTUAL]% [MATERIAL] [COR] TAM [TAMANHO]
```

Exemplo: `MOCHILA THERMO 100% POLIESTER ROSA TAM UNICO`

| Elemento | Valor | Significado |
|---|---|---|
| Token `TAM` | — | marcador literal, sempre logo antes do tamanho |
| Palavra seguinte a `TAM` | `UNICO` | TAMANHO — **não numérico** (tamanho único, comum em mochilas/bolsas) |
| Palavra anterior a `TAM` | `ROSA` | COR |
| Token terminado em `%` (ex.: `100%`) até a COR (exclusive) | `100% POLIESTER` | composição têxtil — **descartada**, não existe campo para isso no schema atual (mesmo tratamento do material do solado no Padrão D) |
| Tudo antes do token `%` | `MOCHILA THERMO` | NOME_PRODUTO final |

**Algoritmo de extração (Padrão F):**
1. Localizar o token `TAM` em `xProd` (separado por espaço, igual ao literal, maiúsculo).
2. `TAMANHO` = palavra imediatamente depois de `TAM`. Ao contrário de todos os outros padrões, aqui o TAMANHO **pode ser texto** (`UNICO`) e não precisa ser validado como número.
3. `COR` = palavra imediatamente antes de `TAM`.
4. Localizar, antes da COR, o token que termina em `%` (marcador de composição, ex.: `100%`). Tudo entre esse token (inclusive) e a COR (exclusive) é a descrição do material — descartado.
5. `NOME_PRODUTO` = tudo de `xProd` **antes** do token `%`. Se não houver token `%` (fallback), `NOME_PRODUTO` = tudo antes da COR.

⚠️ **Cuidado na detecção:** o token deve ser exatamente `TAM` (palavra isolada, cercada por espaços) para não colidir com palavras que começam com esse prefixo (ex.: `TAMANCO`, que aparece como TIPO de produto em outros fornecedores) — a checagem é por palavra inteira, não por substring.

`REFERENCIA_PRODUTO` = `cProd` inalterado (`EV000130`, `EV000134` etc. — nunca tem `-`, mesma regra da CALCADOS MARTE/ALPARGATAS/A. GRINGS sem tamanho embutido). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe no XML deste fornecedor — mesmo caso da VULCABRAS/ALPARGATAS).

**Detecção do Padrão F:** o gatilho prático é o token isolado `TAM` presente em `xProd`. Checar **antes** da regra de "última palavra numérica" (Padrões A/B), já que `TAM UNICO` não termina em número e cairia incorretamente no Padrão C (busca em `infAdProd`, que este fornecedor não usa).

**Validação:** 3 arquivos, 8 itens (6 produtos únicos — `EV000130` e `EV000134` se repetem em notas diferentes, com a mesma descrição). `TAMANHO = UNICO` em 100% dos itens. Cores encontradas: `ROSA`, `AZUL`, `CINZA`, `PRETO` — todas cor única (sem `/`), então `COR_1 = COR_2 = COR_3`. Materiais descartados: `POLIESTER`, `NYLON`.

⚠️ **Observação (não é bug):** dois modelos com nomes parecidos mas `cProd` e cor diferentes apareceram nas notas — `MOCHILA SKT` (`EV000131`, CINZA) e `MOCHILA SKOOL` (`EV000132`, PRETO). Podem ser modelos distintos de fato (nomes abreviados de forma diferente pelo fornecedor) — mantido como veio do XML, sem tentar unificar.

### Padrão G — prefixo numérico de modelo + palavra de material antes da cor (ex.: CALCADOS BIBI)

Descoberto testando a CALCADOS BIBI LTDA (marca infantil): aqui `xProd` começa com um **código numérico de modelo/cor** (diferente do `cProd` fiscal) e a palavra literal `CALCADOS`, seguidos do nome do modelo, uma **palavra de material** (sempre 1 palavra, ex.: `TECIDO`, `LYCRA`) logo antes do bloco de cor, e o tamanho no final:

```
[COD_MODELO_NUMERICO] CALCADOS [MODELO...] [MATERIAL] [COR1[/COR2[/COR3]]] [TAMANHO]
```

Exemplos: `1243005 CALCADOS SKATENIS TECIDO MARINHO/PRETO/FIRE 30`, `1155236 CALCADOS ROLLER 2.0 LYCRA NAVAL/SUGAR 23`, `1155015 CALCADOS ROLLER 2.0 LYCRA PRETO 24`

| Elemento | Valor | Significado |
|---|---|---|
| Primeira palavra | `1243005` / `1155236` | código numérico interno do fornecedor para modelo+cor — **descartado**, não é o mesmo código do `cProd` (`cProd=533754` no primeiro exemplo, sem relação direta) e não existe campo para isso no schema |
| Última palavra | `30` / `23` | TAMANHO |
| Penúltima palavra | `MARINHO/PRETO/FIRE` / `NAVAL/SUGAR` | COR (já vem com `/` interno quando bicolor/tricolor — sem espaços, é 1 só "palavra" para fins de split) |
| Antepenúltima palavra | `TECIDO` / `LYCRA` | MATERIAL — **descartado**, não existe campo para isso no schema (mesmo tratamento do material do solado no Padrão D e da composição têxtil no Padrão F) |
| Entre `CALCADOS` e o MATERIAL | `SKATENIS` / `ROLLER 2.0` | NOME_PRODUTO final (mantido junto com o `CALCADOS` literal — ver observação abaixo) |

**Algoritmo de extração (Padrão G):**
1. Se a primeira palavra de `xProd` for puramente numérica **e** a segunda palavra for `CALCADOS`, aplicar este padrão. Descartar a primeira palavra (código de modelo/cor do fornecedor).
2. Nas palavras restantes, separadas por espaço: a última é `TAMANHO` (sempre numérica, validado); a penúltima é `COR` (bloco já unificado, dividir por `/` internamente para `COR_1`/`COR_2`/`COR_3` como nos demais padrões); a antepenúltima é `MATERIAL` — descartada.
3. `NOME_PRODUTO` = tudo entre `CALCADOS` (inclusive) e o `MATERIAL` (exclusive). Note que `2.0` em `ROLLER 2.0` é preservado corretamente porque está **antes** do material (`LYCRA`), não é confundido com tamanho ou código.

⚠️ **`CALCADOS` é mantido no `NOME_PRODUTO`, não descartado:** embora seja uma palavra genérica (repete em 100% dos itens deste fornecedor e não distingue tipo de calçado), a extração não inventa remoção de palavras fora das regras estruturais já documentadas — segue a mesma cautela aplicada em outros padrões (só remove o que tem uma posição/marcador claro e confirmado).

`REFERENCIA_PRODUTO` = `cProd` inalterado (`533754`, `528708` etc. — sempre numérico, sem `-`, mesma regra dos demais fornecedores sem tamanho embutido no `cProd`). `CODIGO_BARRAS` = `cEAN` (código de barras real e presente em todos os itens testados, diferente da BEBECE). `VALOR_PRODUTO_DESCONTO` = `vDesc` (presente em todos os itens testados, valor real, não `0.00`).

**Detecção do Padrão G:** o gatilho é estrutural — `xProd` começa com um token puramente numérico seguido da palavra literal `CALCADOS`. Precisa ser checado **antes** da regra genérica de "última palavra numérica" (Padrões A/B), pois sem essa checagem específica o algoritmo genérico já classificaria como Padrão B, mas deixaria o código numérico e a palavra de material sem remover do `NOME_PRODUTO`.

**Validação:** 4 arquivos, 135 itens. `cProd` nunca teve `-`. Materiais descartados: `TECIDO`, `LYCRA`. Cores encontradas: `PRETO`, `MARINHO`, `NAVAL`, `SUGAR`, `IOGURTE`, `FIRE`, `DENIM`, `CARAMEL` (grafia em inglês, diferente de `CARAMELO` já catalogada em outro padrão), `TOMATE` — várias cores novas específicas dessa marca infantil, sem necessidade de whitelist (posição sempre determinística, mesmo raciocínio do Padrão D/F).

### Padrão H — prefixo numérico de modelo + material de tamanho variável + cor via whitelist (ex.: CALCADOS BOTTERO)

Descoberto testando a CALCADOS BOTTERO LTDA: também tem um **código numérico de modelo** no início do `xProd` (como o Padrão G da BIBI), mas a segunda palavra é o `TIPO` do calçado (`BOTA`, `SANDALIA`), não o literal `CALCADOS`, e o bloco de material antes da cor **não tem tamanho fixo** (pode ser 1, 2 ou mais palavras: `BURNISH`, `ATANADO`, `NAPA RAVENA/SINTETICO RAVENA`) — sem nenhum marcador literal (como o `SOLA` do Padrão D) para separar material de cor.

```
[COD_MODELO_NUMERICO] [TIPO] [MODELO_DESC...] [MATERIAL... (pode conter '/')] [COR1[/COR2]] [CODIGO_COR]? [TAMANHO]
```

Exemplos:
- `346903 BOTA CANO LONGO COURO BURNISH PRETO 34` — cor simples, sem código.
- `336801 SANDALIA RASTEIRA COURO ATANADO CARAMELO 15 34` — cor simples, com código numérico (`15`, descartado — mesma lógica do Padrão A).
- `361801 BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA PTO/PTO 33` — cor bicolor, sem código, colada por `/` sem espaço.
- `361801 BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA DARK BROWN 2011/DARK BROWN 2011 34` — cor bicolor de **2 palavras cada lado** (`DARK BROWN`), cada uma com seu próprio código numérico (`2011`), colada por `/` sem espaço — o caso mais difícil encontrado no projeto até agora.

⚠️ **Por que este caso quebra a regra simples dos Padrões A/B:** nos Padrões A/B, `COR` é sempre 1 palavra (mais um código numérico opcional). Aqui `COR` pode ter **2 palavras por lado** (`DARK BROWN`), e o material antes dela (`COURO NAPA RAVENA/SINTETICO RAVENA`) **também contém `/`** — então não dá para simplesmente "pegar a penúltima palavra" nem "dividir tudo por `/`". A extração de `COR` aqui usa **a mesma whitelist de cores válidas do Padrão C**, mas aplicada diretamente sobre o `xProd` (não sobre o `infAdProd`).

**Algoritmo de extração (Padrão H):**
1. Remover o primeiro token de `xProd` se for puramente numérico (código de modelo do fornecedor — descartado, sem relação com `cProd`).
2. Remover o último token (TAMANHO, sempre numérico).
3. No texto restante (`resto`), localizar a **última ocorrência de `/`** (se houver):
   - `direita` = texto depois dessa `/`, dividido em palavras → `K` palavras.
   - `esquerda` = as `K` últimas palavras de `resto` **antes** dessa `/` (mesma quantidade de palavras de `direita` — os dois lados costumam ser espelhados, ex.: `DARK BROWN 2011` dos dois lados).
   - Bloco de cor bruto = `esquerda/direita`. Remover do `resto` para obter o que sobra (material + nome do modelo).
   - Em cada lado (`esquerda`, `direita`), se a última palavra for numérica → é `CODIGO_COR`, descartar.
   - `COR` = os dois lados (já sem código), rejuntados com `/`.
4. Se **não houver `/`** em `resto`: aplicar a regra já existente dos Padrões A/B — última palavra numérica → é `CODIGO_COR` (descartar), penúltima palavra = `COR`; senão, última palavra = `COR` diretamente.
5. `NOME_PRODUTO` = o que sobrar de `resto` depois de remover o bloco de cor bruto (passos 3/4) — **inclui o material sem limpeza** (`COURO BURNISH`, `COURO NAPA RAVENA/SINTETICO RAVENA` continuam no nome), porque não existe marcador estrutural confiável para separar material de nome de modelo neste fornecedor (mesma cautela do restante do documento: só remove o que tem posição/marcador comprovado).

Exemplos de aplicação:

| xProd (sem código inicial nem tamanho) | COR extraída | NOME_PRODUTO |
|---|---|---|
| `BOTA CANO LONGO COURO BURNISH PRETO` | `PRETO` | `BOTA CANO LONGO COURO BURNISH` |
| `SANDALIA RASTEIRA COURO ATANADO CARAMELO 15` | `CARAMELO` | `SANDALIA RASTEIRA COURO ATANADO` |
| `BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA PTO/PTO` | `PTO/PTO` | `BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA` |
| `BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA DARK BROWN 2011/DARK BROWN 2011` | `DARK BROWN/DARK BROWN` | `BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA` |

`REFERENCIA_PRODUTO` = `cProd` inalterado (`550170`, `551072` etc. — sempre numérico, sem `-`). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente em todos os itens testados).

**Cores novas adicionadas à whitelist (usada também pelo Padrão C):** `PTO` (abreviação de `PRETO`, mantida como está — não normalizada para `PRETO`), `CONHAQUE`, e o composto de 2 palavras `DARK BROWN`.

⚠️ **Limitação assumida (documentada, não testada em mais casos):** o passo 3 assume que os dois lados de uma cor bicolor multi-palavra são **espelhados em quantidade de palavras** (`K` de cada lado). Não foi testado um caso onde os dois lados têm número de palavras diferente — se aparecer, a extração vai falhar em encontrar a fronteira correta e precisará de ajuste.

**Detecção do Padrão H:** gatilho primário por fornecedor — `NOME_FORNECEDOR = CALCADOS BOTTERO LTDA`. Sinal estrutural de apoio: `xProd` começa com token numérico **e** a segunda palavra não é `CALCADOS` (o que descartaria o Padrão G) — geralmente é um `TIPO` de calçado (`BOTA`, `SANDALIA`).

**Validação:** 3 arquivos, 72 itens. Tipos encontrados: `BOTA CANO LONGO`, `BOTA CANO BAIXO`, `BOTA COTURNO`, `SANDALIA RASTEIRA`. Materiais (mantidos no nome, não extraídos como campo): `COURO BURNISH`, `COURO ATANADO`, `COURO NAPA RAVENA/SINTETICO RAVENA`. Cores: `PRETO`, `CARAMELO`, `MARROM`, `CONHAQUE`, `PTO/PTO`, `DARK BROWN/DARK BROWN`.

⚠️ **Sub-caso DILLY NORDESTE — marcador de código interno `WC-\d+`:** mesmo esqueleto geral do Padrão H (código numérico de modelo no início, `TIPO` de calçado, material antes da cor, tamanho no fim), mas aqui existe um **marcador estrutural explícito** que separa de forma confiável o material/códigos internos do bloco de cor — bem mais simples que a técnica de espelhamento usada na BOTTERO. Estrutura:

```
[COD_MODELO] TENIS [MATERIAL] [COD_COR_NUM] WC-[COD_INTERNO] [COR1[ COR1b][/COR2[ COR2b]/...]] [TAMANHO]
```

Exemplo: `313013 TENIS COURO 01 WC-34 BLACK/LIGHT GREY/GREY 38` → `COD_MODELO=313013` (descartado), `TIPO+MATERIAL=TENIS COURO` (vira `NOME_PRODUTO`), `COD_COR_NUM=01` (descartado, mesma lógica do código de cor do Padrão A/H), `WC-34` (código interno do fornecedor, descartado), bloco de cor bruto `BLACK/LIGHT GREY/GREY` → dividido por `/` em segmentos de **1 ou mais palavras cada** (`BLACK`, `LIGHT GREY`, `GREY`), `TAMANHO=38`.

**Algoritmo (sub-caso DILLY):**
1. Remover o primeiro token de `xProd` se for puramente numérico (`COD_MODELO`, descartado) — igual ao passo 1 do Padrão H.
2. Remover o último token (`TAMANHO`).
3. Localizar o token que casa com `^WC-\d+$` no texto restante — é sempre único.
4. O token imediatamente **antes** do marcador `WC-\d+`, se for puramente numérico, é `COD_COR_NUM` — descartado (mesma regra do código de cor descartável usada em outros padrões).
5. `NOME_PRODUTO` = tudo **antes** do `COD_COR_NUM` (isto é, `TIPO` + `MATERIAL`, ex.: `TENIS COURO`).
6. Tudo **depois** do marcador `WC-\d+` até o `TAMANHO` é o bloco de cor bruto → dividir por `/` em segmentos (cada um pode ter 1+ palavras, ex.: `FAST COFFEE BEAN/COFFEE BEAN/BROWN` → 3 segmentos) → `COR` = segmentos rejuntados com `/`, `COR_1`/`COR_2`/`COR_3` como nos demais padrões.

Exemplos adicionais: `313071 TENIS COURO 01 WC-187 FAST BLACK/BLACK/CREAM 38` → `COR = FAST BLACK/BLACK/CREAM`; `313083 TENIS COURO 02 WC-205 FORCE BLACK/CASTLEROCK/CREAM 37` → `COR = FORCE BLACK/CASTLEROCK/CREAM`.

`REFERENCIA_PRODUTO` = `cProd` inalterado (`111322`, `111327` etc. — sempre numérico, sem `-`, muda por combinação modelo+cor). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não aparece nos itens testados). `infAdProd` é só texto fiscal fixo (resolução do Senado + FCI), não usado na extração.

**Detecção do sub-caso DILLY:** gatilho primário por fornecedor — `NOME_FORNECEDOR = DILLY NORDESTE INDUSTRIA DE CALCADOS LTDA`. Sinal estrutural de apoio: `xProd` contém um token que casa com `WC-\d+`.

**Validação:** 3 arquivos, 54 itens (12 + 12 + 30). Cores/qualificadores em inglês vistos: `BLACK`, `LIGHT GREY`, `GREY`, `COFFEE`, `SUNSET WHITE`, `WHITE`, `FAST BLACK`, `CREAM`, `FAST COFFEE BEAN`, `COFFEE BEAN`, `BROWN`, `FORCE BLACK`, `CASTLEROCK` — nomes comerciais em inglês, alguns com qualificador (`FAST`, `FORCE`, `SUNSET`) grudado à primeira cor do segmento; gravados como vieram, sem tradução.

⚠️ **Sub-caso STAMPA — bloco de cor com mais de 2 segmentos (SCARPIN/TENIS), técnica diferente da mirror de 2 lados da BOTTERO:** testando a STAMPA ARTEFATOS DE COURO LTDA (calçados: `BOTA`, `SCARPIN`, `SLINGBACK`, `TENIS`), a maioria dos itens se encaixa perfeitamente no algoritmo genérico do Padrão H (`xProd` começa com token numérico + `TIPO` ≠ `CALCADOS`): sem `/` no `resto` → última palavra = `COR` (ex.: `56970006 BOTA MONTENAPOLEONE AMBAR 34` → `COR = AMBAR`); com exatamente 1 `/` na `COR` (bicolor simples, sem material com `/`) → mirror de 1 segmento por lado funciona (ex.: `52951500 SCARPIN SAARA/SAARA PRETO/PRETO 33` → `COR = PRETO/PRETO`, `NOME_PRODUTO = SCARPIN SAARA/SAARA`). Mas em alguns itens `TENIS` o bloco de cor tem **4 segmentos** (não 2), o que quebra a técnica de mirror por palavra do passo 3 original (que só extrai exatamente 2 segmentos via a última ocorrência de `/`): `58650400 TENIS KNIT/GORGORAO/SAARA/GORGORAO PRETO/PRETO-BEGE/PRETO/PRETO 34`.

**Técnica alternativa usada quando há 3+ segmentos de cor (validada só para a STAMPA até agora):**
1. Remover o primeiro token (`COD_MODELO`, numérico, descartado) e o último token (`TAMANHO`), como no passo 1-2 do Padrão H.
2. Dividir o `resto` por **espaço** (não por `/`) → `tokens`. Cada `token` pode ele mesmo conter `/` (um segmento de cor/material) ou ser uma palavra solta (qualificador de um segmento anterior).
3. `último_token` = `tokens[-1]`. Se ele **contiver `/`** → o bloco de `COR` bruto é só esse token. Senão (palavra solta, sem `/`) → o bloco de `COR` bruto é `tokens[-2] + " " + tokens[-1]` (junta com o token anterior, que deve conter `/` — mesma lógica já usada na regra estendida do Padrão B/KIDY, generalizada aqui para blocos de N segmentos em vez de só 1 qualificador).
4. `NOME_PRODUTO` = os `tokens` restantes antes do bloco de `COR`, rejuntados com espaço (inclui `TIPO` e qualquer material com `/`, ex.: `KNIT/GORGORAO/SAARA/GORGORAO`, sem tentar separá-lo — mesma cautela do Padrão H de manter material no nome).
5. `COR` = bloco de cor bruto, dividido por `/` em `COR_1`/`COR_2`/`COR_3` (usa só os 3 primeiros segmentos se houver 4+, mesma convenção geral do documento). Um segmento pode conter `-` internamente (ex.: `PRETO-BEGE`) — mantido como está, sem dividir mais (mesma cautela do Padrão H com `PTO/PTO`, aqui aplicada a um segmento bicolor grudado por `-`).

Exemplo: `TENIS KNIT/GORGORAO/SAARA/GORGORAO PRETO/PRETO-BEGE/PRETO/PRETO` → `tokens = [TENIS, KNIT/GORGORAO/SAARA/GORGORAO, PRETO/PRETO-BEGE/PRETO/PRETO]`. Último token contém `/` → `COR` bruta = `PRETO/PRETO-BEGE/PRETO/PRETO` → `COR_1=PRETO`, `COR_2=PRETO-BEGE`, `COR_3=PRETO` (4º `PRETO` descartado). `NOME_PRODUTO = TENIS KNIT/GORGORAO/SAARA/GORGORAO`.

⚠️ **Validado só em 1 modelo/2 colorways (`58650400`, cores `PRETO/PRETO-BEGE/PRETO/PRETO` e `TRUFA/TRUFA/TRUFA/TRUFA`) — amostra mínima.** Diferente da técnica original da BOTTERO (mirror de palavras dos dois lados de uma única `/`, usada para `DARK BROWN 2011/DARK BROWN 2011`), que continua a técnica preferencial quando o bloco de cor tem só 2 segmentos. Esta técnica alternativa (segmentação por espaço) só é necessária quando sobra mais de 1 `/` fora do último segmento de cor — sinal de que há 3+ segmentos a resolver.

**Validação (STAMPA, calçados):** 3 arquivos, 98 itens (86 + 6 + 6, dos 101 itens totais do fornecedor — os 9 restantes são bolsas/mochilas, ver Padrão AE abaixo). Modelos: `BOTA SAARA`, `BOTA ATACAMA`, `BOTA MONTENAPOLEONE`, `SCARPIN SAARA/SAARA`, `SCARPIN ATACAMA/ATACAMA`, `SCARPIN ATACAMA`, `SLINGBACK SAARA/SAARA`, `SLINGBACK ATACAMA/ATACAMA`, `SLINGBACK PELICA`, `TENIS KNIT/GORGORAO/SAARA/GORGORAO`, `TENIS KNIT/GORGORAO/ATACAMA/GORGORAO`. Cores: `PRETO`, `AMBAR`, `TRUFA`, `PANNA`, `PRETO/PRETO-BEGE/PRETO/PRETO`. `CODIGO_BARRAS` real, `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` ausente) em 100% dos itens.

### Padrão I — descrição fiscal fixa, sem COR no XML (ex.: CALCADOS FERRACINI)

Descoberto testando a CALCADOS FERRACINI LTDA: `xProd` é uma descrição fiscal **genérica e fixa**, igual para todos os tamanhos/cores do mesmo modelo, com o `cEAN` repetido dentro do próprio texto e o `TAMANHO` no final marcado por um `-` à **direita** (não à esquerda como nos demais padrões):

```
[DESCRICAO_FISCAL_FIXA] [EAN repetido]   [TAMANHO]-
```

Exemplo: `SAPATENIS MASCULINO DE COURO C/ SOLADO DE BORRACHA 7909611537369   37-`

| Elemento | Valor | Significado |
|---|---|---|
| `SAPATENIS MASCULINO DE COURO C/ SOLADO DE BORRACHA` | — | descrição fiscal genérica, vira `NOME_PRODUTO` sem alteração (mesmo espírito do Padrão C) |
| `7909611537369` | — | o próprio `cEAN` repetido dentro do `xProd` — ruído redundante, descartado |
| `37-` | `37` | TAMANHO, marcado por um `-` **depois** do número (não antes) |

**Algoritmo de extração (Padrão I):**
1. `TAMANHO` = número imediatamente antes do `-` final de `xProd` (regex: `(\d+)-\s*$`).
2. Localizar o valor de `cEAN` dentro do próprio `xProd` e remover esse trecho em diante (o `cEAN` repetido + os espaços + o `TAMANHO-`).
3. `NOME_PRODUTO` = o que sobrar antes do `cEAN` embutido, com espaços nas pontas removidos.
4. `COR` = vazio (não existe em nenhuma posição do XML deste fornecedor — nem `xProd`, nem `infAdProd`, que nem existe nesses itens). `COR_PRECISA_REVISAO = true` (regra geral, ver seção abaixo).

`REFERENCIA_PRODUTO` = `cProd` inalterado — tem um sufixo de letra (`7052-267A`, `2075A`, `2075B`, `1062A`, `1063C`) que provavelmente codifica a cor internamente no ERP do fornecedor, mas é um código opaco sem tabela de tradução no XML; **não se tenta inferir nome de cor a partir dele**. `CODIGO_BARRAS` = `cEAN` (real, presente). `VALOR_PRODUTO_DESCONTO` = `vDesc` real.

**Detecção do Padrão I:** gatilho primário por fornecedor — `NOME_FORNECEDOR = CALCADOS FERRACINI LTDA`. Sinal estrutural de apoio: `xProd` termina em `\s+\d+-$` (número seguido de `-` no fim absoluto da string) **e** contém o valor de `cEAN` embutido no meio do texto.

**Validação:** 3 arquivos, 53 itens. Tipos encontrados: `SAPATENIS MASCULINO DE COURO C/ SOLADO DE BORRACHA`, `SAPATO MASCULINO DE COURO C/SOLADO DE BORRACHA` (nota: com e sem espaço depois de `C/`, mantido como veio). 100% dos itens com `COR_PRECISA_REVISAO = true` (nenhuma cor disponível em nenhum dos 3 arquivos).

### Padrão J — descrição truncada multi-componente, sem TAMANHO no XML (ex.: CALCADOS BEBECE)

Descoberto testando a CALCADOS BEBECE LTDA (ver "Caso resolvido" na seção seguinte para o histórico da decisão). `xProd` é uma descrição técnica multi-componente truncada em ~120 caracteres, listando o modelo e depois cada parte do calçado (cabedal, forro, sola etc.) separada por `" / "`, sem nenhum tamanho — e não existe `<cEAN>` real (`SEM GTIN`):

```
[TIPO] [...] [CODIGO_MODELO_COR] / [MATERIAL_1] [COR] / [MATERIAL_2] [COR] / ... (truncado)
```

Exemplo: `BOTA OVER KNEE T4414-230 / CAMURCA STRETCH PRETO / NAPA BRUMAS PRETO / FORRO CACHAREL PRETO / FORRO CACHAREL PRETO / CAM`

**Algoritmo de extração (Padrão J):**
1. Separar `xProd` por `" / "` → `segmentos`.
2. `segmentos[0]` = cabeçalho do modelo (ex.: `BOTA OVER KNEE T4414-230`). Se a última palavra bater com um padrão de código de modelo/cor do fornecedor (contém `-`, dígitos nos dois lados, ex.: `T4414-230`) → descartar essa palavra (código interno do fornecedor, sem relação com `cProd`). `NOME_PRODUTO` = o restante de `segmentos[0]`. Se a última palavra não bater com esse padrão, mantém `segmentos[0]` inteiro.
3. `COR` = **última palavra de `segmentos[1]`** (o primeiro componente logo após o cabeçalho — cor do cabedal/parte principal, mesmo quando outras partes do calçado têm cor diferente). Funciona mesmo quando `segmentos[1]` começa com `_` (placeholder de material não informado, ex.: `_ PRETO` → `COR = PRETO`).
4. `TAMANHO` = **sempre vazio** (decisão confirmada com o usuário — não decodificar os pares tamanho/quantidade do `infAdProd`).
5. **Caso de segurança:** se `xProd` não contiver nenhum `/` → `COR` fica em branco e a linha é marcada com `COR_PRECISA_REVISAO = true`.

`REFERENCIA_PRODUTO` = `cProd` inalterado (numérico, sem `-`). `CODIGO_BARRAS` = vazio (`cEAN = SEM GTIN`, regra geral). `QUANTIDADE` = `qCom` e `VALOR_PRODUTO` = `vProd` **tal como vêm no `<det>`** (sem tentativa de desdobrar por tamanho, já que `TAMANHO` fica em branco — cada `<det>` gera exatamente 1 linha, como em qualquer outro padrão).

**Detecção do Padrão J:** gatilho estrutural — `infAdProd` contendo 2 ou mais pares no formato `\d{2}/\d+` (indício de que o fornecedor pensa em "grade", mas essa informação não é usada) **e/ou** `NOME_FORNECEDOR = CALCADOS BEBECE LTDA`.

**Validação:** 3 arquivos, 25 itens (1 por `<det>`, sem expansão). Cores encontradas: `PRETO` (maioria), `CHERRY`, `CHOCOLATE`, `NUDE`, `SCARLET`, `PECAN`, `EXPRESSO`, `FIGO`. Tipos: `BOTA` (+ variações), `CANOINHA`, `CHANEL`, `SLINGBACK`, `SCARPIN`.

### Padrão K — xProd genérico sem COR, TAMANHO dentro do infAdProd (ex.: CALCADOS PEGADA NORDESTE)

Descoberto testando a CALCADOS PEGADA NORDESTE LTDA: `xProd` é só um código de modelo + descrição genérica fixa, **sem cor nem tamanho**. O tamanho fica escondido dentro do `infAdProd`, num trecho de formato fixo `NN/Q GTIN:...` (onde `Q` é a quantidade, igual ao `qCom` do item — já que aqui cada `<det>` é 1 tamanho só, não uma grade a expandir). Não há cor em nenhuma posição do XML:

```xml
<cProd>282008-0534</cProd>
<xProd>282008-05 BOTA FEMININA</xProd>
<infAdProd>Numero FCI 363EC70C-AB18-4400-9C7A-6F9D352D6A14 - 34/1 GTIN: 7909940224039</infAdProd>
```

| Elemento | Valor | Significado |
|---|---|---|
| `xProd` | `282008-05 BOTA FEMININA` | `NOME_PRODUTO` sem alteração (código de modelo + descrição genérica, sem cor/tamanho para remover) |
| `infAdProd` | `... 34/1 GTIN: ...` | `34` = TAMANHO, `1` = quantidade (confere com `qCom`) |

⚠️ **O prefixo antes do trecho `NN/Q GTIN:` varia entre lotes** — às vezes é `Numero FCI [GUID] - 34/1 GTIN: ...`, às vezes o `infAdProd` começa direto em `34/1 GTIN: ...` (sem o prefixo do FCI). A extração não depende desse prefixo, só localiza o padrão fixo `\d{2}/\d+\s*GTIN` em qualquer lugar do `infAdProd`.

**Algoritmo de extração (Padrão K):**
1. `NOME_PRODUTO` = `xProd` sem alteração.
2. `TAMANHO` = número de 2 dígitos localizado pelo padrão `(\d{2})/\d+\s*GTIN` dentro do `infAdProd`, independente do que vem antes.
3. Validação cruzada: o número depois da `/` nesse trecho deve bater com `qCom` do `<prod>` (checagem de consistência, não afeta a extração).
4. `COR` = **sempre vazio** (não existe em nenhuma posição do XML deste fornecedor). `COR_PRECISA_REVISAO = true` (regra geral, ver seção abaixo).

`REFERENCIA_PRODUTO` = `cProd` **inalterado**, mesmo quando parece ter o tamanho embutido no sufixo (ex.: `282008-0534`) — o segmento final (`0534`) não bate exatamente com o `TAMANHO` extraído (`34`, faltam os dois dígitos `05` na frente), então a regra de corte já existente (só corta se bater exatamente) corretamente **não corta**. ⚠️ Confirmado também que essa relação cProd↔tamanho **não é confiável entre lotes**: no arquivo de 2024 o sufixo de `cProd` varia por tamanho (`0534`, `0535`, `0536`...), mas no arquivo de 2025 o mesmo `cProd` (`281413-02`) se repete **igual** em todos os tamanhos (34 a 38) — por isso o `TAMANHO` deste padrão vem sempre do `infAdProd`, nunca do `cProd`.

**Detecção do Padrão K:** gatilho primário por fornecedor — `NOME_FORNECEDOR = CALCADOS PEGADA NORDESTE LTDA`. Sinal estrutural de apoio: `xProd` termina em `BOTA FEMININA` (ou descrição genérica similar) sem mais nenhuma palavra, e `infAdProd` contém o padrão `\d{2}/\d+\s*GTIN`.

**Validação:** 3 arquivos, 26 itens. Tipo encontrado: `BOTA FEMININA` (único, em todos os itens testados). 100% dos itens com `COR_PRECISA_REVISAO = true`.

### Padrão L — categoria não-calçado (acessórios esportivos), tamanho em faixa, cor em sigla (ex.: CAMBUCI S.A. / Penalty)

Descoberto testando a CAMBUCI S.A. — fabricante da marca **Penalty**, segunda categoria de produto não-calçado do projeto (a primeira foi mochilas, BR8/Padrão F). Aqui os itens são **meias, caneleiras, joelheiras, calções** e também **material de ponto de venda** (cubos de papelão, arquibancada, bandeja de vitrine, shoetags). Duas rupturas em relação a todos os padrões anteriores, ambas resolvidas com decisão do usuário:

1. **TAMANHO pode vir como faixa** (ex.: `39-44`), não um número único de calçado — porque o acessório serve várias numerações. **Decisão do usuário: gravar a faixa como veio** (texto literal, ex. `"39-44"`), sem tentar desdobrar em várias linhas.
2. **COR vem como sigla de 2 letras** (`PT`, `BC`, `AZ`, `CH`, `CO`...), às vezes bicolor colada por `-` (`PT-BC`). **Decisão do usuário: traduzir as siglas conhecidas com confiança** (`PT`→`PRETO`, `BC`→`BRANCO`, `AZ`→`AZUL`) e **manter as desconhecidas como vieram** (`CH`, `CO`), marcando a linha com `COR_PRECISA_REVISAO = true`.

Três sub-estruturas de `xProd` foram observadas:

```
Sub-caso 1 (meia/meião):    [TIPO] [MODELO...] [FAIXA_TAMANHO] [COR]? UN
Sub-caso 2 (caneleira/joelheira/calção): [TIPO] [MODELO...] [COR]? T [-]?[COD_TAMANHO]
Sub-caso 3 (material de PDV): [TIPO] [MODELO...]   (sem cor nem tamanho)
```

Exemplos: `MEIA PERF SETE 787 39-44 PT UN`, `MEIAO FUT MATIS 770 39-44 PT-BC UN`, `CANELEIRA MATIS X AZ T -U`, `JOELHEIRA ELASTICA XXI PT-CH T -P`, `CALCAO PENALTY FUTEBOL 303 PT T GG`, `ARQUIBANCADA 3 PES` (sem cor/tamanho).

**Algoritmo de extração (Padrão L):**
1. Se `xProd` terminar em ` UN` (espaço + literal `UN`, fim absoluto) → remover esse sufixo (marcador de unidade, descartado).
2. Tentar casar, no final do que sobrou, o padrão de **faixa de tamanho**: `(\d{2}-\d{2})(?: ([A-Z]{2}(?:-[A-Z]{2})?))?$`.
   - Se casar → `TAMANHO` = a faixa inteira, como veio (ex. `39-44`). O grupo de cor (se capturado) = `COR` bruta. Remover o trecho casado; `NOME_PRODUTO` = o restante.
3. Senão, tentar casar o padrão **`T [-]?(\w+)$`** (literal `T`, opcionalmente seguido de `-`, seguido do código de tamanho: `U`, `P`, `M`, `G` ou `GG`) no final.
   - Se casar → `TAMANHO` = o código capturado (sem o `-`, ex. `U`, `P`). Remover esse trecho. Depois, olhar a **última palavra restante**: se bater com o formato de código de cor (1 ou 2 siglas de 2-3 letras maiúsculas, com ou sem `-` entre elas) → `COR` bruta = essa palavra, removida do texto. Senão (ex. `PAPELAO`, palavra longa demais para ser sigla) → `COR` = vazio, nada mais é removido.
4. Senão (nenhum dos dois padrões bateu): `NOME_PRODUTO` = `xProd` inteiro, `COR` = vazio. Para `TAMANHO`, aplicar a **regra geral 4** ("Regra geral para COR/TAMANHO ausentes"): se `xProd` começar com um `TIPO` de item vestível/utilizável de tamanho único (`MEIA`, `MEIAO`, `CANELEIRA`, `JOELHEIRA`, `CALCAO` — categorias equivalentes a meia/bolsa/carteira/bola) → `TAMANHO = "UN"`. Senão (item de PDV genuíno, sem noção alguma de tamanho — `CUBO`, `ARQUIBANCADA`, `BANDEJA`, `SHOETAGS`, `SUPORTE`) → `TAMANHO` = vazio.
5. **Tradução da COR bruta:** dividir por `-` (se bicolor), traduzir cada sigla reconhecida (`PT`→`PRETO`, `BC`→`BRANCO`, `AZ`→`AZUL`), manter as demais como vieram. Rejuntar com `/` (não `-`) para armazenamento — mesma convenção usada em todo o resto do documento. Se pelo menos uma sigla não for reconhecida, `COR_PRECISA_REVISAO = true`.

| xProd | NOME_PRODUTO | COR (traduzida) | TAMANHO |
|---|---|---|---|
| `MEIA PERF SETE 787 39-44 PT UN` | `MEIA PERF SETE 787` | `PRETO` | `39-44` |
| `MEIAO FUT MATIS 770 39-44 PT-BC UN` | `MEIAO FUT MATIS 770` | `PRETO/BRANCO` | `39-44` |
| `MEIAO FUT MATIS 770 39-44 CO UN` | `MEIAO FUT MATIS 770` | `CO` (não reconhecida) | `39-44` |
| `CANELEIRA MATIS X AZ T -U` | `CANELEIRA MATIS X` | `AZUL` | `U` |
| `JOELHEIRA ELASTICA XXI PT-CH T -P` | `JOELHEIRA ELASTICA XXI` | `PRETO/CH` (CH não reconhecida) | `P` |
| `CALCAO PENALTY FUTEBOL 303 PT T GG` | `CALCAO PENALTY FUTEBOL 303` | `PRETO` | `GG` |
| `CUBO FUN KIDS PAPELAO T -U` | `CUBO FUN KIDS PAPELAO` | (vazio) | `U` |
| `ARQUIBANCADA 3 PES` | `ARQUIBANCADA 3 PES` | (vazio) | (vazio) |

`REFERENCIA_PRODUTO` = `cProd`, aplicando a regra já existente (só corta o último segmento se tiver `-` **e** bater exatamente com o `TAMANHO` extraído) — sem nenhuma regra nova. Funciona corretamente nos três sub-casos: não corta quando o `TAMANHO` é uma faixa (`39-44` nunca bate com o sufixo do `cProd`), corta quando bate exatamente (`6540499121-P` com `TAMANHO=P` → vira `6540499121`), e não corta quando não há `-` no `cProd` mesmo com sufixo de tamanho colado (`3233039000GG`, sem hífen antes de `GG`).

⚠️ **Itens de material de ponto de venda (PDV) processados normalmente, sem filtro** — decisão do usuário: cubos de papelão, arquibancada, bandeja de vitrine, shoetags e suportes entram como qualquer outro item, com `COR` vazia e `TAMANHO` vazio quando não houver (não existe um flag de "isto não é mercadoria revendível" no schema atual — esses itens não se enquadram na regra geral 4 porque não têm noção alguma de tamanho, nem "único", diferente de meia/bolsa/carteira/bola).

⚠️ **Limitação assumida:** o teste "é código de cor" no passo 3 (1-2 siglas de 2-3 letras maiúsculas) pode, em teoria, confundir uma abreviação de modelo com uma cor se algum modelo futuro terminar em algo parecido — não ocorreu nos itens testados, mas é um risco a monitorar.

**Detecção do Padrão L:** gatilho primário por fornecedor — `NOME_FORNECEDOR` contém `CAMBUCI` (a razão social varia por filial, ex. `CAMBUCI SA - BA30`, `CAMBUCI SA -  SR11`). Sinal estrutural de apoio: `xProd` termina em ` UN` precedido de uma faixa `\d{2}-\d{2}`, ou contém o padrão `T [-]?(U|P|M|G|GG)$`.

**Validação:** 3 arquivos, 42 itens (6 material de PDV + 36 acessórios vestíveis: meias, meiões, caneleiras, joelheiras, calções). Cores conhecidas traduzidas: `PRETO`, `BRANCO`, `AZUL`. Siglas não reconhecidas (mantidas + sinalizadas): `CH`, `CO`. Esta é a segunda categoria não-calçado do projeto (depois da BR8/mochilas) — reforça a decisão já registrada de reaproveitar os mesmos campos do schema (COR/TAMANHO/REFERENCIA) entre categorias, sem um schema derivado por categoria (ver "Próximos passos").

### Padrão M — código longo duplicado + " - " + descrição, categoria bolsas de couro (ex.: CLASSE INDÚSTRIA E ARTEF. DE COURO)

Descoberto testando a CLASSE INDÚSTRIA E ARTEF. DE COURO LTDA — terceira categoria não-calçado do projeto (depois de mochilas/BR8 e acessórios esportivos/CAMBUCI). Aqui os produtos são **bolsas de couro** (crossbody, tote, shopping bag, tiracolo, camera bag; NCM `42022100`). `xProd` começa com um **código longo redundante** (compactação do `cProd`, sem espaços) seguido de `" - "` (espaço-hífen-espaço) e a descrição real:

```
[CODIGO_LONGO] - [TIPO] [MODELO_NUM] [CODIGO_COR] [COR] [TAMANHO]
```

Exemplo: `2050028701I - CROOSBODY 2870 1I PRETO 38`

| Elemento | Valor | Significado |
|---|---|---|
| `2050028701I` | — | código redundante = `cProd` compactado sem `-` (`cProd = 2870-1INS` → modelo `2870` + código de cor `1I`, com um prefixo fixo `205` e sufixo `NS` descartados) — **descartado**, sem campo próprio, não vira `REFERENCIA_PRODUTO` (que já vem de `cProd`) |
| `CROOSBODY` | — | TIPO, mantido no `NOME_PRODUTO` |
| `2870` | — | número do modelo, mantido no `NOME_PRODUTO` |
| `1I` | — | `CODIGO_COR` — **descartado**, alfanumérico misto (dígito+letra), posição fixa logo antes da `COR` |
| `PRETO` | — | `COR` |
| `38` | — | `TAMANHO` |

⚠️ **A posição do `CODIGO_COR` aqui é diferente do Padrão A:** no Padrão A (DAKOTA), a ordem é `[COR] [CODIGO_COR] [TAMANHO]` (código depois da cor). Aqui é `[CODIGO_COR] [COR] [TAMANHO]` (código **antes** da cor). O sinal usado para reconhecer o código é o mesmo já usado no Padrão H (BOTTERO): **token alfanumérico misto** (começa com 1-2 dígitos, seguido de 1-2 letras, ex. `1I`, `2G`, `10D`, `1I2B`, `1K1Y`, `1A2F`) — nunca um `TIPO`/`MODELO` (só letras) nem um `TAMANHO` (só dígitos ou faixa).

**Algoritmo de extração (Padrão M):**
1. Localizar a primeira ocorrência de `" - "` (espaço-hífen-espaço) em `xProd`. Descartar tudo antes dela (código longo redundante).
2. No restante, `partes` = split por espaço. `TAMANHO` = `partes[-1]` (numérico, ou uma faixa `NN/NN` — ex. `38/72`, visto em bolsas com alça ajustável; gravado como veio, mesma decisão já tomada no Padrão L para faixas).
3. `COR` = `partes[-2]` (sempre uma palavra, nunca precisou de tradução — nomes como `PRETO`, `NOCCIOLA`, `CREME`, `TAN`, `RUBY`).
4. Se `partes[-3]` for alfanumérico misto (dígito+letra) → é `CODIGO_COR`, descartado. `NOME_PRODUTO` = `partes[0..-4]`. Senão, `NOME_PRODUTO` = `partes[0..-3]` (mesma cautela de não cortar às cegas já usada em outros padrões).

`REFERENCIA_PRODUTO` = `cProd` inalterado (regra já existente — só corta se o último segmento tiver `-` e bater exatamente com o `TAMANHO`; aqui o `cProd` sempre termina em código de cor tipo `-1INS`, que nunca bate com o `TAMANHO` numérico, então nunca corta). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `vDesc` real.

⚠️ **Regra geral 4 (produto "tamanho único") aplicável aqui:** nos 25 itens testados, `TAMANHO` sempre veio preenchido (numérico ou faixa `NN/NN`), então o passo 2 do algoritmo nunca precisou desse caso. Mas `BOLSA` é uma das categorias citadas explicitamente na regra 4 — se aparecer, em lotes futuros, um item deste fornecedor (ou de outro fornecedor de bolsas/carteiras) sem nenhum `TAMANHO` extraível de `xProd`/`infAdProd`, gravar `TAMANHO = "UN"` diretamente, sem perguntar ao usuário.

**Detecção do Padrão M:** gatilho primário por fornecedor — `NOME_FORNECEDOR` contém `CLASSE INDUSTRIA` (ou `CLASSE COURO`). Sinal estrutural de apoio: `xProd` casa com `^[A-Z0-9]+ - ` (código alfanumérico seguido de `" - "` logo no início).

**Validação:** 1 arquivo, 25 itens. Tipos encontrados: `CROOSBODY`, `SHOPPING BAG`, `TIRACOLO`, `TOTE`, `CAMERA BAG`. Cores encontradas: `PRETO`, `NOCCIOLA`, `CREME`, `TAN`, `RUBY` — nomes de cor em italiano/inglês, comuns em bolsas de couro; nenhuma exigiu tradução (já vêm como palavra completa, diferente do Padrão L). `TAMANHO` em faixa (`38/72`) apareceu em 5 dos 25 itens, provavelmente representando o comprimento ajustável da alça — gravado como veio.

### Padrão N — meias sem COR nem TAMANHO confiáveis no xProd (ex.: CONDE DUCK)

Descoberto testando a CONDE DUCK IND DE MEIAS LTDA — fabricante de meias, quarta categoria não-calçado do projeto. Este é o `xProd` mais ruidoso encontrado até agora: não há **nenhuma** posição confiável de cor (só códigos numéricos opacos — `001`, `308`, `461`, `086`... — ou o literal `SORTIDAS`, que significa "sem cor específica", nunca uma cor real), e o indicador de tamanho (letras `P`/`M`/`G`/`GG`/`PP`/`U`/`0`, tamanhos de vestuário) aparece **colado de forma inconsistente** a um código numérico — às vezes como prefixo (`GG054`, `M054`), às vezes como sufixo (`398054PP`, `1025002M`), às vezes isolado entre hifens (`- M -`), e às vezes ausente mesmo dentro da mesma família de produto (`KIT DE MEIAS BEBE O-3PR 3980540` sem letra, junto de `398054PP` com letra, no mesmo lote):

```
MEIA BEBE MENINO - SORTIDAS 0-054
KIT DE MEIAS ESPORT. MASC 3 P - 001
MEIA SAP. INV. SCARPINA 1025002M
MEIA SAPATILHA DUCK KIDS-O GG054
```

⚠️ **Decisão do usuário, hoje regra geral:** meia é um produto elástico, sem numeração de calçado bem definida — em vez de tentar decodificar essas letras com uma regex frágil (risco real de acerto parcial, dado o quão inconsistente é a posição), **`TAMANHO` é sempre gravado como o literal `"UN"`**, independente do que aparecer no `xProd`. Não se tenta extrair `P`/`M`/`G`/`GG`/`PP`/`U`/`0` em nenhum caso. Essa decisão, tomada originalmente só para este fornecedor, foi **generalizada como regra 4** da seção "Regra geral para COR/TAMANHO ausentes" — vale para qualquer fornecedor de produto "tamanho único" (meia, bolsa, carteira, bola, boné...), não só a CONDE DUCK.

**Algoritmo de extração (Padrão N):**
1. `NOME_PRODUTO` = `xProd` sem alteração (dado o ruído, não há uma regra confiável para separar cor/tamanho do nome — mesmo espírito do Padrão C/K).
2. `COR` = **sempre vazio** (não existe cor real em nenhuma posição do XML deste fornecedor). `COR_PRECISA_REVISAO = true`.
3. `TAMANHO` = **sempre `"UN"`** (regra geral 4 — produto "tamanho único").

`REFERENCIA_PRODUTO` = `cProd` inalterado (sempre puramente numérico, ex. `0030005403`, sem `-` — a regra de corte nunca se aplica). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não aparece nos itens testados).

**Detecção do Padrão N:** gatilho primário por fornecedor — `NOME_FORNECEDOR = CONDE DUCK IND DE MEIAS LTDA` (ou, a partir desta rodada, `NOME_FORNECEDOR` contém `LUPO`, ver abaixo). Sinal estrutural de apoio: `xProd` começa com `MEIA`/`KIT DE MEIAS`, **verificado sem diferenciar maiúsculas/minúsculas** (`Meia Lupo...` também casa, não só `MEIA` em caixa alta).

**Validação:** 3 arquivos, 281 itens (CONDE DUCK). 100% dos itens com `COR_PRECISA_REVISAO = true` e `TAMANHO = "UN"`.

⚠️ **Extensão do padrão — LUPO NORDESTE LTDA. e LUPO S/A (2 CNPJs do mesmo grupo):** `xProd` = `Meia Lupo [código de tamanho/gênero: AU/AM/AF/KM] [LINHA...] [Kit N]?` (ex.: `Meia Lupo AU Sport Kit3`, `Meia Lupo AF Basic Kit2`, `Meia 3/4 Lupo AF Basic`) — sem cor nem tamanho numérico em nenhuma posição, mesmo espírito da CONDE DUCK, mas com `xProd` em texto capitalizado normal (`Meia`, não `MEIA`), o que exige checar o gatilho estrutural **sem diferenciar caixa**. Os códigos `AU`/`AM`/`AF`/`KM` parecem indicar gênero+faixa etária (Adulto Único/Masculino/Feminino, Kids Masculino), não um tamanho de fato — **não decodificados**, mesma cautela já aplicada às letras da CONDE DUCK (`P`/`M`/`G`/`GG`...). `cProd` (ex. `03225-0891400931`) tem `-`, mas **nunca é cortado** — o segmento após o `-` nunca bate com `TAMANHO` (que é sempre `"UN"`, não numérico), então a regra de corte existente não se aplica. `CODIGO_BARRAS` = `cEAN` real (diferente da CONDE DUCK, mas mesma regra geral do padrão).

⚠️ **Item isolado sem o prefixo `MEIA`/`Meia` — `CUECA` (LUPO S/A):** 1 dos 29 itens LUPO é `Cueca Lupo AM Boxer Microf.` (cueca, não meia) — sem `TAMANHO` em nenhuma posição do XML (nem `xProd`, nem `infAdProd`, que só traz texto fiscal de IPI). Diferente de meia, cueca normalmente tem tamanhos reais (P/M/G/GG), então não se enquadra automaticamente na regra geral 4. Perguntado ao usuário como proceder: **sim, `TAMANHO = "UN"` também** — decisão pontual para este item/fornecedor (mesmo espírito da decisão da `MALA` isolada da ZETI/Padrão AG), não uma generalização da categoria "cueca" para a regra geral 4. Tratado com o mesmo algoritmo do Padrão N (`NOME_PRODUTO` = `xProd` sem alteração, `COR` vazia, `COR_PRECISA_REVISAO = true`, `TAMANHO = "UN"`), coberto pelo mesmo gatilho de fornecedor (`NOME_FORNECEDOR` contém `LUPO`) — não pelo gatilho estrutural do prefixo `MEIA`, que este item não tem.

**Validação (LUPO):** 3 arquivos, 29 itens (4 + 13 + 12) — 28 `Meia` + 1 `Cueca`. Linhas/modelos vistos: `Sport`, `Sap. Invisivel`, `Basic`, `Bambu`, `Sap. Santa Monica`, `Boxer Microf.`. Códigos de gênero/faixa: `AU`, `AM`, `AF`, `KM`. `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens. `TAMANHO = "UN"` e `COR_PRECISA_REVISAO = true` em 100% dos 29 itens.

### Caso resolvido: LUPO NORDESTE LTDA. / LUPO S/A — decisão do usuário: `MEIA` reconfirmada, `CUECA` isolada usa `TAMANHO = "UN"`

O usuário reconfirmou, de forma proativa, a regra já estabelecida para meia (**"se o produto for meia, lembre-se meia coloque o tamanho 'UN'"**) — mesma regra geral 4 já aplicada à CONDE DUCK, agora validada em um 2º fornecedor (2 CNPJs do mesmo grupo). Além disso, apareceu 1 item de `CUECA` sem `TAMANHO` em nenhuma posição do XML, categoria não coberta pela regra geral 4 (cueca costuma ter tamanhos reais). Perguntado ao usuário: **sim, `TAMANHO = "UN"` também para este item** — decisão pontual, não uma nova categoria "tamanho único" geral (se aparecer tamanho real em outra posição do XML de um lote futuro, esse valor prevalece).

### Padrão O — cor truncada no `xProd`, cor completa + tamanho no `infAdProd` (ex.: DEMOCRATA)

Descoberto testando a DEMOCRATA CALCADOS E ARTEFATOS DE COURO LTDA. Aqui o `xProd` é uma descrição fiscal semi-genérica que **contém uma cor**, mas essa cor pode vir **truncada** (campo de tamanho fixo no ERP do fornecedor) e nunca inclui uma 2ª cor mesmo em produtos bicolores — não dá para confiar nela. O tamanho **nunca** aparece no `xProd`:

```
SAPATOS MASC PRETO, COURO BOVINO , SOLADO SINTETICO
SAPATENIS MASC CONHAQUE, COURO BOVINO , SOLADO SINTETICO
SAPATENIS MASC PRETO INTE, COURO CAPRINO+COURO BOVINO , SOLADO SINTETICO   ← cor truncada ("PRETO INTE" = "PRETO INTENSO" cortado em 10 caracteres)
```

A cor completa e o tamanho vêm do `<infAdProd>`, num formato fixo e simples: `[COR]['/'COR2...] - [TAMANHO]#` (sempre termina em `#`):

```
PRETO - 37#
CONHAQUE - 38#
PRETO INTENSO/PRETO - 38#     ← bicolor: cor real completa (o xProd truncado mostrava só "PRETO INTE")
```

**Algoritmo de extração (Padrão O):**
1. Capturar `infAdProd` com a regex `^(.+?)\s*-\s*(\d+)#$` → grupo 1 = bloco de cor bruto, grupo 2 = `TAMANHO`.
2. Separar o bloco de cor por `/` (produtos bicolores, ex.: `PRETO INTENSO/PRETO`) para `COR_1`/`COR_2`/`COR_3`, mesma regra dos demais padrões.
3. `NOME_PRODUTO` = `xProd` **sem alteração** — a cor embutida no `xProd` é redundante e não confiável (truncada, sem a 2ª cor), então não se tenta removê-la nem usá-la; mesmo espírito do Padrão C/N (descrição fiscal ruidosa, não vale a pena parsear).
4. `REFERENCIA_PRODUTO` = `cProd` inalterado — aqui `cProd` (ex.: `448027-003`, `240701-001`) nunca embute o tamanho (o mesmo `cProd` se repete para todos os tamanhos de um modelo/cor), então a regra de corte nunca se aplica.
5. `CODIGO_BARRAS` = `cEAN` (sempre um GTIN real nos itens testados).

**Detecção do Padrão O:** gatilho primário por fornecedor — `NOME_FORNECEDOR = DEMOCRATA CALCADOS E ARTEFATOS DE COURO LTDA`. Sinal estrutural de apoio: `infAdProd` casa com `.+ - \d+#$`.

**Validação:** 3 arquivos, 69 itens (30 + 29 + 10). Cores vistas: `PRETO`, `TAN`, `CONHAQUE`, `PRETO INTENSO` (bicolor com `PRETO`). Nenhum item com `vDesc`/desconto nos 3 arquivos testados (`VALOR_PRODUTO_DESCONTO = 0.00`).

### Padrão P — campos delimitados por `-`, marcador literal `Tam:` (ex.: FILA BRASIL, NEW BRASIL/New Balance)

Descoberto testando a FILA BRASIL LTDA: aqui o `xProd` é um registro estruturado, com campos separados por `-` (hífen) e um marcador literal `Tam:` colado (sem espaço) ao número do tamanho — o formato mais "tabular" encontrado no projeto até agora, sem nenhuma ambiguidade posicional:

```
[COD_MODELO]-TENIS [MARCA] [MODELO...] [SEXO]-[COR1[/COR2[/COR3]]]-[CODIGO_COR]-Tam:[TAMANHO]
```

Exemplos:
- `11U335X-TENIS FILA EURO JOGGER SPORT MASCULINO-PTO/GRF-972-Tam:37`
- `51J728X-TENIS FILA RECOVERY FEMININO-PTO/SLLP/CMBL-6011-Tam:33`
- `51J728X-TENIS FILA RECOVERY FEMININO-CAST/M AV/BEGE-7762-Tam:33` (segmento de cor com 2 palavras: `M AV`)
- `GM500-TENIS NB 500V2 MASCULINO-PTO/VRME-GM500BA2-Tam:39` (NEW BRASIL, distribuidora New Balance — `CODIGO_COR` **alfanumérico**, `GM500BA2`, não puramente numérico como na FILA)
- `WARIS-TENIS NB FRESH FOAM ARISHIV4 FEMININO-AZUL/MRNH/AZLC-WARIS3PC-Tam:34` (tricolor)

**Algoritmo de extração (Padrão P):**
1. Dividir `xProd` por `-` → sempre 5 campos: `[COD_MODELO, NOME_PRODUTO, COR_bruta, CODIGO_COR, "Tam:TAMANHO"]`.
2. `COD_MODELO` (1º campo, ex.: `11U335X`, `F01TR00015`, `GM500`, `WARIS`) — descartado, sem relação com `cProd`.
3. `NOME_PRODUTO` = 2º campo, sem alteração (ex.: `TENIS FILA EURO JOGGER SPORT MASCULINO`, `TENIS NB 500V2 MASCULINO` — inclui tipo, marca, modelo e sexo; nenhum marcador confiável para separar o sexo do resto, então fica tudo junto, mesma cautela dos demais padrões).
4. `COR` = 3º campo, dividido por `/` em segmentos (cada um pode ter 1 ou mais palavras, ex.: `CAST/M AV/BEGE` → 3 segmentos) → `COR_1`/`COR_2`/`COR_3` como nos demais padrões. Gravada como veio (sigla curta, sem tradução — `PTO`, `GRF`, `BCO`, `CZ`, `PTA`, `BEGE`, `CAST`, `FLSC`, `SLLP`, `CMBL`, `M AV`, `VRME`, `GRFE`, `RSAC`, `DORD`, `AZLC`, `MRNH`, `MROM` — mesma cautela do Padrão H com `PTO/PTO`: não inventar tradução sem confirmação).
5. `CODIGO_COR` = 4º campo — descartado, mesma convenção dos demais padrões. **Pode ser puramente numérico (FILA, ex.: `972`) ou alfanumérico (NEW BRASIL, ex.: `GM500BA2`, `M460ZK4`)** — em ambos os casos é um código de referência interno do fornecedor (estilo+colorway), sem relação com `cProd`, sempre descartado independente do formato.
6. `TAMANHO` = número depois do literal `Tam:` no 5º campo (regex `Tam:(\d+)`).

`REFERENCIA_PRODUTO` = `cProd` inalterado (`1071422`, `1099534`, `1115522`, `1271082` etc. — sempre numérico, sem `-`; o `cProd` real não tem relação com o `COD_MODELO` alfanumérico do `xProd`). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `0.00` nos itens testados (campo `vDesc` existe no XML mas sempre zerado, diferente do Padrão J/K onde o campo nem aparece).

**Detecção do Padrão P:** gatilho estrutural — `xProd` casa com `Tam:\d+$` no fim (não depende do nome do fornecedor; confirmado em 2 fornecedores distintos — FILA BRASIL e NEW BRASIL — com o mesmo esqueleto de 5 campos).

**Validação:** 6 arquivos, 101 itens. FILA BRASIL: 3 arquivos, 48 itens (13 + 21 + 14). Modelos vistos: `EURO JOGGER SPORT`, `RECOVERY`, `EFECTO`, `ORIGINAL FITNESS BOLD`. Cores/siglas vistas: `PTO`, `GRF`, `BCO`, `CZ`, `PTA`, `BEGE`, `CAST`, `FLSC`, `SLLP`, `CMBL`, `M AV`. NEW BRASIL: 3 arquivos, 53 itens (27 + 12 + 14). Modelos vistos: `500V2`, `515V2`, `460 V4`, `520V8`, `FRESH FOAM ARISHIV4`. Cores/siglas vistas: `PTO`, `VRME`, `GRFE`, `RSAC`, `DORD`, `PRE`, `LILS`, `AZLC`, `GRF`, `MROM`, `BEGE`, `AZUL`, `MRNH` — sem whitelist nem tradução em nenhum dos dois fornecedores, extração 100% posicional/determinística (não precisa de `COR_PRECISA_REVISAO`).

### Padrão Q — TAMANHO vem do `cProd` (não do `xProd`), COR sempre ausente (ex.: FISIA/Nike)

Descoberto testando a FISIA COMERCIO DE PRODUTOS ESPORTIVOS SA (distribuidora oficial da Nike no Brasil): aqui o `xProd` é só o nome genérico do modelo, **sem cor nem tamanho em nenhuma posição**, e **não existe `<infAdProd>`** (a tag nem aparece no XML). A única fonte de `TAMANHO` é o próprio `cProd`, que segue a convenção Nike "estilo-cor-tamanho":

```
xProd = TENIS [W] NIKE [MODELO...]
cProd = [COD_ESTILO]-[COD_COR_NUM]-[TAMANHO]
```

Exemplo: `xProd = "TENIS W NIKE COURT VISION LO NN"`, `cProd = "DH3158-101-8.5"`.

**Algoritmo de extração (Padrão Q):**
1. `NOME_PRODUTO` = `xProd` sem alteração (não há cor/tamanho para remover; o prefixo `W` quando presente significa "women's" — mantido no nome, sem campo próprio no schema, mesma cautela do Padrão G com `CALCADOS`).
2. Dividir `cProd` por `-` → último segmento = `TAMANHO`. **Diferente de todos os padrões anteriores, aqui o `TAMANHO` pode ser um número decimal** (ex.: `8.5`, `5.5`, `9.5`) — a numeração americana da Nike inclui meios-números. Validar como numérico (inteiro ou decimal), não só inteiro.
3. `REFERENCIA_PRODUTO` = `cProd` sem o último segmento (aplicação direta da regra de corte já existente — o último segmento bate com o `TAMANHO` extraído). Resultado: `COD_ESTILO-COD_COR_NUM` (ex.: `DH3158-101`) — o código de cor numérico da Nike **não é descartado** como nos demais padrões, porque ele é a única forma de distinguir colorways diferentes do mesmo estilo (não existe nome de cor legível em lugar nenhum do XML).
4. `COR` = **sempre vazio** (não existe em nenhuma posição — nem `xProd`, nem `infAdProd`, que não existe). `COR_PRECISA_REVISAO = true`.

`CODIGO_BARRAS` = `cEAN` real (GTIN Nike, 14 dígitos). `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente e não-zero em todos os itens testados, diferente de outros padrões onde é sempre `0.00`).

**Detecção do Padrão Q:** gatilho primário por fornecedor — `NOME_FORNECEDOR = FISIA COMERCIO DE PRODUTOS ESPORTIVOS SA`. Sinal estrutural de apoio: `cProd` casa com `^[A-Z]{2}\d{4}-\d{3}-\d+(\.\d+)?$` e não existe `<infAdProd>` no XML. **Precisa ser checado antes da regra genérica de "não numérica → Padrão C"**, porque sem essa checagem específica o algoritmo cairia no Padrão C e tentaria buscar cor/tamanho em `infAdProd`, que neste fornecedor não existe — resultando em falha silenciosa.

**Validação:** 2 arquivos, 22 itens (21 + 1). Modelos vistos: `NIKE COURT VISION LO NN`, `NIKE SB CHRON 2`, `NIKE SB CHRON 2 CNVS`, `NIKE AIR MAX INFINITY 2`.

### Padrão R — cor truncada sem correção, TAMANHO sempre ausente (ex.: GRENDENE)

Descoberto testando a GRENDENE S/A (chinelos MORMAII, RIDER, babuches, galochas): o `xProd` é uma descrição fiscal truncada com a cor **embutida e cortada** (mesmo problema do Padrão O/DEMOCRATA), mas aqui **não existe `<infAdProd>`** para trazer a cor completa nem o tamanho — então, diferente do Padrão O, não há como corrigir a cor nem recuperar o tamanho de nenhuma fonte:

```
[TIPO] [MARCA] [MODELO...]  [COR truncada, às vezes com espaço duplo antes quando falta um campo intermediário]
```

Exemplos: `CHINELO MORMAII TAI DEDO  CINZA/PRET` (cor truncada: `CINZA/PRETO` → `CINZA/PRET`), `CHINELO RIDER GRID SLIDE APRETO/PRET`, `BABUCH SONIC SPEED INF    AZUL/AMARE`, `GALOCHA BARBIE FIZZY INF  ROSA CLARO`.

⚠️ **Decisão do usuário:** perguntado se deveria processar mesmo sem `TAMANHO` extraível (nem no `xProd`, nem no `infAdProd` — que não existe —, nem de forma confiável a partir do `cProd`, cujos últimos 4 dígitos repetem os mesmos valores entre modelos com contagens de tamanho diferentes, ex. `0378` aparece tanto num modelo de 4 tamanhos quanto num de 6): **sim, processar sem TAMANHO**, mantendo a `COR` truncada como veio.

**Algoritmo de extração (Padrão R):**
1. `NOME_PRODUTO` = `xProd` sem alteração.
2. `COR` = **não extraída separadamente** — fica embutida no próprio `NOME_PRODUTO` (não há marcador confiável para separar `TIPO MARCA MODELO` do trecho de cor truncado no fim, ao contrário do Padrão O onde a cor real vem do `infAdProd`). `COR_PRECISA_REVISAO = true`.
3. `TAMANHO` = **sempre vazio** (decisão do usuário — ver acima).

`REFERENCIA_PRODUTO` = `cProd` inalterado (`12258BA5000378` etc. — sempre numérico/alfanumérico longo, sem `-`, sem sufixo de tamanho decifrável). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente e não-zero nos itens testados).

**Detecção do Padrão R:** gatilho primário por fornecedor — `NOME_FORNECEDOR` contém `GRENDENE`. Mesmo CNPJ raiz (`89850341`) entre a filial CRA e a matriz SOB — mesma empresa, unidades diferentes.

**Validação:** 3 arquivos, 93 itens (24 + 7 + 62). Modelos vistos: `CHINELO MORMAII TAI DEDO`, `CHINELO RIDER GRID SLIDE`, `CHINELO MORMAII WAVE III`, `BABUCH SONIC SPEED INF`, `GALOCHA BARBIE FIZZY INF`. `TAMANHO` vazio e `COR_PRECISA_REVISAO = true` em 100% dos itens.

### Padrão S — sem COR nem TAMANHO no XML (ex.: TRENTO)

Descoberto testando a IND E COM DE CALC TRENTO LTDA (marca LIA LINE): `xProd` é uma descrição fiscal técnica genérica, sem cor nem tamanho em nenhuma posição, terminando num código de referência numérico do fornecedor:

```
[TIPO] [SOLA_DESCRICAO] [MATERIAL] [COD_REFERENCIA]
```

Exemplo: `SANDALIA FEMININA COM SOLA EXTERIOR SINTETICA,PARTE SUPERIOR  COURO NATURAL  71832`.

⚠️ **Cuidado na detecção:** este `xProd` contém o token `SOLA` (`COM SOLA EXTERIOR SINTETICA`), que colidiria com o gatilho do Padrão D se checado na ordem errada — por isso a detecção da TRENTO precisa ser verificada **antes** do passo do Padrão D na lista de prioridade (ver "Detecção automática do padrão" abaixo).

⚠️ **Decisão do usuário:** perguntado se deveria processar mesmo sem `COR` nem `TAMANHO` (nenhum dos dois existe em nenhuma posição do XML — `xProd` não tem cor, não tem tamanho, `cEAN` é sempre o literal `SEM GTIN`, e não existe `<infAdProd>`): **sim, processar sem COR e sem TAMANHO**.

**Algoritmo de extração (Padrão S):**
1. `NOME_PRODUTO` = `xProd` sem alteração (descrição fiscal genérica, mesmo espírito do Padrão C/N — não há posição confiável para remover nada).
2. `COR` = **sempre vazio**. `COR_PRECISA_REVISAO = true`.
3. `TAMANHO` = **sempre vazio** (decisão do usuário — ver acima).

`REFERENCIA_PRODUTO` = `cProd` inalterado (`2347.71832`, `2318V25.71676` etc. — tem `.` mas nunca `-`, então a regra de corte não se aplica). `CODIGO_BARRAS` = **sempre vazio** (`cEAN` é sempre o literal `SEM GTIN`). `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente e não-zero nos itens testados).

**Detecção do Padrão S:** gatilho primário por fornecedor — `NOME_FORNECEDOR = IND E COM DE CALC TRENTO LTDA`. Precisa ser checado **antes** do gatilho do Padrão D (token `SOLA`), já que o `xProd` deste fornecedor contém esse token incidentalmente, sem ser o marcador de material do Padrão D.

**Validação:** 2 arquivos, 23 itens (17 + 6). Tipos vistos: `SANDALIA FEMININA`, `SAPATO FEMININO`. `COR` e `TAMANHO` sempre vazios, `COR_PRECISA_REVISAO = true` e `CODIGO_BARRAS` vazio em 100% dos itens.

### Padrão T — produto a granel sem variação, TAMANHO fixo `"UN"` (ex.: INDITEC — bonés)

Descoberto testando a INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI — fabricante de **bonés**, quinta categoria não-calçado do projeto (depois de mochilas, acessórios esportivos, bolsas de couro e meias). `xProd` é sempre literalmente a palavra `BONE`, sem nenhuma variação de cor, modelo ou tamanho — o produto é vendido a granel (quantidades como `24.0000`, `48.0000` unidades por linha), `cEAN` é sempre `SEM GTIN`, e não existe `<infAdProd>`:

```
xProd = "BONE"   (sempre, sem exceção)
```

⚠️ **Decisão do usuário, hoje regra geral:** mesmo raciocínio já aplicado às meias da CONDE DUCK (Padrão N) — boné é um produto de tamanho único, então em vez de deixar `TAMANHO` em branco, **`TAMANHO` é sempre gravado como o literal `"UN"`** (regra 4 da seção "Regra geral para COR/TAMANHO ausentes").

**Algoritmo de extração (Padrão T):**
1. `NOME_PRODUTO` = `xProd` sem alteração (sempre `"BONE"`).
2. `COR` = **sempre vazio** (não existe em nenhuma posição do XML). `COR_PRECISA_REVISAO = true`.
3. `TAMANHO` = **sempre `"UN"`** (regra geral 4 — produto "tamanho único").

`REFERENCIA_PRODUTO` = `cProd` inalterado (`0400200400153` — mesmo código em todas as notas testadas, sem `-`). `CODIGO_BARRAS` = **sempre vazio** (`cEAN` é sempre `SEM GTIN`). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não aparece no XML deste fornecedor).

**Detecção do Padrão T:** gatilho primário por fornecedor — `NOME_FORNECEDOR = INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI`. Sinal estrutural de apoio: `xProd` é exatamente a palavra `BONE`.

**Validação:** 2 arquivos, 2 itens (1 item por arquivo — cada nota tem só uma linha de produto, em quantidades diferentes: 24 e 48 unidades). Categoria nova, sem histórico de variação testado — se aparecerem outros produtos além de `BONE` (ou cores/tamanhos reais) em lotes futuros, o padrão precisa ser revisto.

### Padrão U — prefixo duplicado + bloco de cor multi-segmento sem marcador (ex.: INDUSTRIA DE CALCADOS GONCALVES)

Descoberto testando a INDUSTRIA DE CALCADOS GONCALVES LTDA.: o `xProd` estruturado sempre começa com o literal `TENIS` e termina no `TAMANHO`; tudo entre os dois é o bloco de cor bruto, **sem nenhuma palavra de nome de modelo ou material separada** — cada segmento (separado por `/`) já mistura material+cor como um nome comercial único (ex.: `NAPA SOFT BRANCO`, `CAMURCAO ALVEJADA`), o que torna a extração mais simples que o Padrão H/DILLY: não precisa de marcador nem whitelist, porque não existe nada para descartar entre `TENIS` e o bloco de cor.

Em algumas linhas (normalmente a primeira de cada modelo/lote), o fornecedor gruda um **prefixo redundante** antes da descrição estruturada — nome comercial do modelo + descrição de cor em texto livre, sem espaço antes do `TENIS` que reinicia a descrição real (mesma técnica de limpeza já usada no Padrão E/Havaianas, trocando o marcador `SANDALIAS` por `TENIS`):

```
xProd (com prefixo)    = "Tenis Thais 1573 Camurcao Alvejado/Couro Branco/PretoTENIS CAMURCAO ALVEJADA/NAPA SOFT BRANCO/PRETO 34"
xProd (sem prefixo)    = "TENIS COURO NAPA SOFT PRETO/BRANCO/CAMURCAO PRETO 34"
```

**Algoritmo de extração (Padrão U):**
1. **Limpeza de prefixo:** se a palavra `TENIS` (maiúscula) aparecer mais de uma vez em `xProd`, descartar tudo antes da **última** ocorrência — mesma técnica do Padrão E com `SANDALIAS`. Nas linhas sem prefixo duplicado, `xProd` já vem limpo.
2. Depois da limpeza, separar por espaço: primeira palavra = `TENIS` → vira `NOME_PRODUTO` sozinho (não há nome de modelo separado — o resto da descrição é todo cor/material, misturados). Última palavra = `TAMANHO` (sempre numérica).
3. Tudo entre `TENIS` e `TAMANHO` é o bloco de cor bruto → dividir por `/` em segmentos (cada um pode ter 1 a 4 palavras, ex.: `COURO NAPA SOFT PRETO/BRANCO` → segmentos `COURO NAPA SOFT PRETO` e `BRANCO`) → `COR` = segmentos rejuntados com `/`, `COR_1`/`COR_2`/`COR_3` como nos demais padrões.

`REFERENCIA_PRODUTO` = `cProd` sem o último segmento, **usando `_` (underscore) como delimitador em vez de `-`** — variante do formato já visto (`1471CC_20_34` → corte do último segmento `34`, que bate com o `TAMANHO` extraído → `REFERENCIA_PRODUTO = 1471CC_20`, mantendo o código de cor do fornecedor no meio, mesma lógica do Padrão Q). `CODIGO_BARRAS` = `cEAN` real. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não aparece no XML deste fornecedor).

**Detecção do Padrão U:** gatilho primário por fornecedor — `NOME_FORNECEDOR = INDUSTRIA DE CALCADOS GONCALVES LTDA.`. Sinal estrutural de apoio: `cProd` casa com `_\d+_\d+$` (underscore como separador) e `xProd` contém a palavra `TENIS` pelo menos uma vez.

**Validação:** 2 arquivos, 46 itens (40 + 6). Modelos vistos (só na parte descartada do prefixo, quando presente): `Thais 1573`, `Monique 1606`. Cores/materiais compostos vistos: `COURO NAPA SOFT BURGUNDY`, `OFF WHITE`, `CAMURCAO BURGUNDY`, `CAMURCAO NUDE`, `NAPA SOFT OFF WHITE`, `ARMY`, `CAMURCAO ALVEJADA` — nomes comerciais que misturam material e cor, catalogados como vieram, sem tentar separar os dois.

### Padrão V — `cProd` delimitado por `.`, marcador `EM [MATERIAL]` opcional, cor via whitelist com modificador prefixado `TODO` (ex.: INDUSTRIA E COM DE CALC SYG STAR LTDA)

Descoberto testando a INDUSTRIA E COM DE CALC SYG STAR LTDA: `xProd` começa com um **código numérico de modelo** seguido de `" - "` (espaço-hífen-espaço, mesmo separador do Padrão M) e a palavra literal `TENIS`. `cProd` é o primeiro caso do projeto com **`.` (ponto) como delimitador**, em vez de `-` ou `_`:

```
xProd = [COD_MODELO] - TENIS [FAIXA_ETARIA] [EM [MATERIAL]]? [COR bruta] [TAMANHO]
cProd = REF.[COD_MODELO].[COD_COR].[TAMANHO]
```

Exemplos:
- `4013 - TENIS ADULTO EM CAMURCA PRETO 34` (`cProd = REF.4013.04.34`) — com marcador `EM`, cor simples.
- `4019 - TENIS ADULTO EM CAMURCA TODO PRETO 33` (`cProd = REF.4019.55.33`) — com marcador `EM`, cor com modificador **prefixado** (`TODO`, "todo preto" = totalmente preto — colorway diferente do modelo `4019` cor `04`, que é só `PRETO`).
- `4025 - TENIS CASUAL ADULTO PRETO / BRANCO 33` (`cProd = REF.4025.12.33`) — **sem** marcador `EM`, cor bicolor separada por `/` **com espaços** ao redor (diferente de todos os padrões anteriores, que usam `/` colado sem espaço).
- `413 - TENIS INFANTIL EM LONA PRETO 27` (`cProd = REF.413.04.27`) — com marcador `EM`, modelo infantil.

⚠️ **Por que não reaproveitar o sub-caso `EM [MATERIAL]` da CALCADOS MARTE:** a regra da MARTE assume que a `COR` é sempre a **única palavra logo depois do `MATERIAL`** — aqui isso quebra em dois casos: (1) cor com modificador **antes** dela (`TODO PRETO`, onde a palavra logo após `CAMURCA` é `TODO`, não a cor) e (2) o item sem marcador `EM` (`4025`), que a regra da MARTE nem tentaria tratar (não há `MATERIAL` para ancorar). Por isso este é um padrão novo, não uma variação do sub-caso MARTE.

**Algoritmo de extração (Padrão V):**
1. Localizar a primeira ocorrência de `" - "` (espaço-hífen-espaço) em `xProd`. Descartar tudo antes dela (`COD_MODELO`, mesmo valor do 2º segmento do `cProd` — sem campo próprio).
2. Última palavra do restante = `TAMANHO` (sempre numérica).
3. Se o token isolado `EM` estiver presente → `MATERIAL` = palavra imediatamente depois de `EM` (mantida no `NOME_PRODUTO`, ex.: `CAMURCA`, `LONA`). Ponto de partida da busca de `COR` = logo após o `MATERIAL`. Senão → ponto de partida = logo após `TENIS [FAIXA_ETARIA]` (sem âncora fixa — usa só a whitelist do passo 4).
4. A partir do ponto de partida até o `TAMANHO` (exclusive), aplicar a **mesma whitelist de cores válidas do Padrão C/H**, percorrendo as palavras **de trás para frente**: enquanto a palavra bater com a whitelist (cor ou modificador conhecido) ou fizer parte de um bloco `/` (aqui **com espaços** ao redor — `PRETO / BRANCO`, diferente da convenção sem espaço dos demais padrões), inclui no bloco de `COR` bruto. Para no primeiro token não reconhecido.
5. **Modificador prefixado `TODO`:** depois do passo 4, se a palavra imediatamente **antes** do início do bloco de `COR` encontrado for `TODO`, incluí-la também no bloco de `COR` (ex.: `TODO PRETO`) — este é, junto com o modificador `OIL` do Padrão Y, um dos únicos casos do projeto onde um modificador de cor vem **antes** da cor, não depois (os demais — `OFF`, `NEON`, `CLARO`, `ESCURO` — vêm depois).
6. `NOME_PRODUTO` = tudo entre o fim do passo 1 e o início do bloco de `COR` (inclui `TENIS`, faixa etária, e `EM MATERIAL` quando presente).
7. Bloco de `COR` bruto dividido por `/` (com ou sem espaço ao redor) em `COR_1`/`COR_2`/`COR_3`, mesma regra dos demais padrões.

`REFERENCIA_PRODUTO` = `cProd`, **estendendo a regra de corte a um novo delimitador (`.`, ponto)** — corta o último segmento **apenas se bater exatamente com o `TAMANHO` extraído** (mesma cautela já aplicada aos delimitadores `-` e `_`). Resultado: `REF.[COD_MODELO].[COD_COR]` (ex.: `REF.4013.04`) — o código de cor numérico é **mantido**, não descartado, mesma decisão já tomada no Padrão Q (FISIA/Nike): é a única forma de diferenciar colorways via `cProd`, mesmo já havendo o nome da cor disponível no `xProd` aqui (mantido por consistência, sem redesenhar o campo). `CODIGO_BARRAS` = **sempre vazio** (`cEAN` é sempre o literal `SEM GTIN`). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` deste fornecedor — só aparece nos totais da nota, `ICMSTot`/`cobr`). Não existe `<infAdProd>` no XML deste fornecedor.

| xProd | NOME_PRODUTO | COR | TAMANHO | cProd | REFERENCIA_PRODUTO |
|---|---|---|---|---|---|
| `4013 - TENIS ADULTO EM CAMURCA PRETO 34` | `TENIS ADULTO EM CAMURCA` | `PRETO` | `34` | `REF.4013.04.34` | `REF.4013.04` |
| `4019 - TENIS ADULTO EM CAMURCA TODO PRETO 33` | `TENIS ADULTO EM CAMURCA` | `TODO PRETO` | `33` | `REF.4019.55.33` | `REF.4019.55` |
| `4025 - TENIS CASUAL ADULTO PRETO / BRANCO 33` | `TENIS CASUAL ADULTO` | `PRETO/BRANCO` | `33` | `REF.4025.12.33` | `REF.4025.12` |
| `413 - TENIS INFANTIL EM LONA PRETO 27` | `TENIS INFANTIL EM LONA` | `PRETO` | `27` | `REF.413.04.27` | `REF.413.04` |

**Nova entrada na whitelist de cores (Padrão C/H/V):** `TODO` — não é uma cor, é um modificador de "totalmente" (`TODO PRETO` = todo preto/all-black), com a peculiaridade de vir **antes** da cor, não depois (ver passo 5 do algoritmo).

**Detecção do Padrão V:** gatilho primário por fornecedor — `NOME_FORNECEDOR = INDUSTRIA E COM DE CALC SYG STAR LTDA`. Sinal estrutural de apoio: `cProd` casa com `^REF\.\d+\.\d+\.\d+$` (delimitador `.`, nunca visto antes) e `xProd` começa com um token numérico seguido de `" - TENIS"`. **Precisa ser checado antes do passo do marcador `EM` genérico (sub-caso CALCADOS MARTE)**, já que o `xProd` deste fornecedor também contém o token isolado `EM`, mas com uma estrutura de cor incompatível com a regra da MARTE (cor de 1+ palavras antes do modificador, e casos sem `EM` nenhum).

**Validação:** 2 arquivos, 65 itens (29 + 36). Modelos vistos: `4013`, `4019` (duas colorways: `PRETO` e `TODO PRETO`), `4025`, `413`. Cores: `PRETO` (maioria), `TODO PRETO`, `PRETO/BRANCO` — nenhuma cor nova fora da whitelist já existente (só o modificador `TODO`, novo). `CODIGO_BARRAS` vazio e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens.

### Padrão W — `COD_MODELO` como âncora dentro do `xProd` (ex.: INDUSTRIA E COMERCIO LEJON LTDA)

Descoberto testando a INDUSTRIA E COMERCIO LEJON LTDA: `xProd` é uma descrição técnica com marcadores `EM [MATERIAL]` (cabedal) e `SOLA`/`SOLADO [MATERIAL]` (mesmo token do Padrão D), seguida de um marcador de referência variável (`ORD`, `REF`, ou `LEJON REF`) e do **próprio código de modelo do `cProd`** (1º segmento, delimitado por `.` — mesmo delimitador do Padrão V), repetido literalmente dentro do `xProd`:

```
xProd = TENIS [FAIXA_ETARIA] EM [MATERIAL_CABEDAL] SOLA|SOLADO [MATERIAL_SOLA] [ORD|REF|LEJON REF] [COD_MODELO] [COR] [TAMANHO]
cProd = [COD_MODELO].[COD_COR].[TAMANHO]
```

Exemplos:
- `TENIS INF EM LONA SOLA BORRACHA ORD LJIF0010 PRETO/BRANCO 28` (`cProd = LJIF0010.98.28`) — infantil, marcador `ORD`.
- `TENIS AD CAMURCA SOLA BORRACHA LEJON REF LJVU0248 PRETO/BRANCO 34` (`cProd = LJVU0248.98.34`) — adulto, marcador `LEJON REF`.
- `TENIS AD LONA SOLADO BORRACHA ORD LJVU0253 PRETO/PRETO 34` (`cProd = LJVU0253.96.34`) — variante `SOLADO` em vez de `SOLA`.
- `TENIS AD SINTETICO SOLA BORRACHA LEJON REF LJSC0009 GRAFITE/PRETO 35` (`cProd = LJSC0009.78.35`).

⚠️ **Por que não reaproveitar o Padrão D (marcador `SOLA`) nem o Padrão V:** o `xProd` deste fornecedor contém `SOLA`/`SOLADO` (colidiria com o gatilho do Padrão D) e `cProd` usa o mesmo delimitador `.` do Padrão V, mas a estrutura é diferente das duas: não há um marcador único e fixo logo antes da `COR` (o texto entre `SOLA [material]` e a `COR` varia — `ORD`, `REF`, `LEJON REF` — sem padrão fixo de quantas palavras). A âncora confiável aqui não é uma palavra-marcador, é o **próprio código de modelo**, que sempre aparece de forma literal e idêntica tanto no `cProd` (1º segmento) quanto embutido no meio do `xProd`.

**Algoritmo de extração (Padrão W):**
1. `COD_MODELO` = 1º segmento do `cProd` (split por `.`, ex.: `LJIF0010`, `LJVU0248`). Localizar essa string literal dentro de `xProd`.
2. Última palavra de `xProd` = `TAMANHO` (sempre numérica). Validação cruzada: deve bater com o 3º segmento do `cProd`.
3. Bloco de `COR` bruto = tudo entre o fim do `COD_MODELO` (achado no passo 1) e o `TAMANHO` (exclusive) — sempre 1 token colado por `/`, sem espaço (ex.: `PRETO/BRANCO`).
4. `NOME_PRODUTO` = tudo em `xProd` **antes** do `COD_MODELO` — inclui `TENIS`, faixa etária, `EM MATERIAL_CABEDAL`, `SOLA`/`SOLADO MATERIAL_SOLA` e o marcador de referência (`ORD`/`REF`/`LEJON REF`), sem tentar separá-los (mesma cautela do Padrão H: só remove o que tem posição/marcador comprovado — aqui só o `COD_MODELO` tem essa garantia).
5. Bloco de `COR` dividido por `/` em `COR_1`/`COR_2`, mesma regra dos demais padrões (só vistos casos de exatamente 2 cores até agora, nunca 3+).

`REFERENCIA_PRODUTO` = `cProd`, cortando o último segmento (delimitador `.`) **apenas se bater com o `TAMANHO` extraído** (mesma extensão do Padrão V). Resultado: `COD_MODELO.COD_COR` (ex.: `LJIF0010.98`) — o código de cor numérico é **mantido**, mesma decisão do Padrão Q/V. `CODIGO_BARRAS` = `cEAN` real na maioria dos itens, mas **vazio** nos SKUs onde `cEAN = SEM GTIN` (ex.: modelo `LJVU0252` no arquivo de 2024 — regra geral). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` deste fornecedor — só nos totais da nota). Não existe `<infAdProd>` no XML deste fornecedor.

| xProd | NOME_PRODUTO | COR | TAMANHO | cProd | REFERENCIA_PRODUTO |
|---|---|---|---|---|---|
| `TENIS INF EM LONA SOLA BORRACHA ORD LJIF0010 PRETO/BRANCO 28` | `TENIS INF EM LONA SOLA BORRACHA ORD` | `PRETO/BRANCO` | `28` | `LJIF0010.98.28` | `LJIF0010.98` |
| `TENIS AD CAMURCA SOLA BORRACHA LEJON REF LJVU0248 PRETO/BRANCO 34` | `TENIS AD CAMURCA SOLA BORRACHA LEJON REF` | `PRETO/BRANCO` | `34` | `LJVU0248.98.34` | `LJVU0248.98` |
| `TENIS AD LONA SOLADO BORRACHA ORD LJVU0253 PRETO/PRETO 34` | `TENIS AD LONA SOLADO BORRACHA ORD` | `PRETO/PRETO` | `34` | `LJVU0253.96.34` | `LJVU0253.96` |

**Detecção do Padrão W:** gatilho primário por fornecedor — `NOME_FORNECEDOR = INDUSTRIA E COMERCIO LEJON LTDA`. Sinal estrutural de apoio: o 1º segmento do `cProd` (delimitado por `.`) aparece literalmente dentro de `xProd`. **Precisa ser checado antes do gatilho do Padrão D (token `SOLA`)**, já que o `xProd` deste fornecedor contém `SOLA`/`SOLADO` incidentalmente (mesma cautela já aplicada à TRENTO no Padrão S).

**Validação:** 2 arquivos, 134 itens (83 + 51). Modelos vistos: `LJIF0010`, `LJIF0013`, `LJSC0009`, `LJVU0248`, `LJVU0252`, `LJVU0253`, `LJVU0254`, `LJVU0255`, `LJVU0266`. Cores (sempre 2, sem cor nova fora da whitelist já existente): `PRETO/BRANCO`, `BRANCO/PRETO`, `PRETO/PRETO`, `PRETO/GUM`, `BEGE/BRANCO`, `GRAFITE/PRETO`. `CODIGO_BARRAS` vazio só nos itens do modelo `LJVU0252` (2024) — os demais têm GTIN real.

### Padrão X — marcadores explícitos `TAM` e `COR` no próprio `xProd`, categoria de produtos de cuidado para calçados (ex.: LONG FEET)

Descoberto testando a LONG FEET - LTDA — sétima categoria não-calçado do projeto (depois de mochilas/BR8, acessórios esportivos/CAMBUCI, bolsas de couro/CLASSE, meias/CONDE DUCK, bonés/INDITEC): aqui os produtos são **acessórios e produtos de cuidado para calçados** (esponjas, calcanheiras, palmilhas, sprays de tratamento). Diferente de todos os padrões anteriores, o próprio `xProd` já traz os rótulos literais `TAM` e `COR` **explícitos** antes do valor, sem precisar inferir posição:

```
xProd = [DESCRICAO...] [TAM [TAMANHO]]? [DESCRICAO...] [COR [COR]]?
```

Exemplos:
- `ESPONJA MÁGICA PARA CALÇADOS QUADRADA` — sem `TAM` nem `COR` (produto sem variação).
- `CALCANHEIRA ANTI IMPACTO ESPORTIVA TAM P FLEX UNISSEX` — só `TAM` (`P`), sem `COR`.
- `PALMILHA DE AJUSTE TAM M COR AREIA` — os dois marcadores presentes.
- `INIBIDOR DE ODORES 100 ML SPRAY` — sem `TAM` nem `COR`.

**Algoritmo de extração (Padrão X):**
1. Se `xProd` contiver o token isolado `TAM` → `TAMANHO` = palavra imediatamente depois (código de tamanho de vestuário, ex.: `P`/`M`/`G` — não numérico nos itens testados). Remover o par `TAM [TAMANHO]` do texto.
2. Se `xProd` contiver o token isolado `COR` → `COR` = palavra(s) imediatamente depois, até o fim da string (nos itens testados, sempre 1 palavra, ex.: `AREIA`). Remover o par `COR [cor]` do texto.
3. `NOME_PRODUTO` = `xProd` sem os marcadores removidos nos passos 1/2 (o resto do texto ao redor é mantido intacto, ex.: `FLEX UNISSEX` continua depois de remover `TAM P`).
4. Se o marcador `TAM` **não** existir: aplicar a **regra geral 4** ("Regra geral para COR/TAMANHO ausentes") — se `xProd` começar com `MEIA` (categoria "tamanho único") → `TAMANHO = "UN"`. Senão (esponja, spray, palmilha sem `TAM`) → `TAMANHO` = vazio, **não é falha de extração**, é um produto genuinamente sem essa dimensão, mesmo espírito do material de PDV no Padrão L.
5. Se o marcador `COR` não existir → `COR` fica vazia. `COR_PRECISA_REVISAO` = **sempre `false`** neste padrão (ao contrário dos Padrões I/K/N/Q/R/S/T, aqui a ausência de `COR` é uma característica real do produto, não uma limitação de dado).

| xProd | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| `ESPONJA MÁGICA PARA CALÇADOS QUADRADA` | `ESPONJA MÁGICA PARA CALÇADOS QUADRADA` | (vazio) | (vazio) |
| `CALCANHEIRA ANTI IMPACTO ESPORTIVA TAM P FLEX UNISSEX` | `CALCANHEIRA ANTI IMPACTO ESPORTIVA FLEX UNISSEX` | (vazio) | `P` |
| `PALMILHA DE AJUSTE TAM M COR AREIA` | `PALMILHA DE AJUSTE` | `AREIA` | `M` |
| `MEIA ANTI RACHADURA EM SILICONE` | `MEIA ANTI RACHADURA EM SILICONE` | (vazio) | `UN` |
| `INIBIDOR DE ODORES 100 ML SPRAY` | `INIBIDOR DE ODORES 100 ML SPRAY` | (vazio) | (vazio) |

`REFERENCIA_PRODUTO` = `cProd` inalterado (sempre um código numérico curto de 3 dígitos, ex.: `121`, `180`, `146` — sem `-`/`_`/`.`, então a regra de corte nunca se aplica; cada variação de tamanho já tem seu próprio `cProd`, ex.: `180`/`181`/`182` para `P`/`M`/`G` da mesma calcanheira). `CODIGO_BARRAS` = `cEAN` real (GTIN) em 100% dos itens testados. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` deste fornecedor). Não existe `<infAdProd>` no XML deste fornecedor.

**Detecção do Padrão X:** gatilho primário por fornecedor — `NOME_FORNECEDOR` contém `LONG FEET`. **Precisa ser checado antes do gatilho do Padrão F (token isolado `TAM`)**, já que vários itens deste fornecedor também contêm esse token — sem essa checagem, cairiam incorretamente no Padrão F, que assume uma estrutura de `%MATERIAL COR TAM TAMANHO` que não existe aqui.

**Validação:** 2 arquivos, 28 itens (14 + 14 — os 2 arquivos trazem exatamente os mesmos 14 produtos, mesma nota reemitida/complementar, mantida sem deduplicar). Tipos: esponjas (2, `TAMANHO` vazio), calcanheiras (3, `TAMANHO` = `P`/`M`/`G` via marcador `TAM`), meias de silicone/gel (2, `xProd` começa com `MEIA` mas sem marcador `TAM` → `TAMANHO = "UN"` pela regra geral 4), palmilhas (3, 1 com `COR AREIA` via marcador `COR`, as outras 2 sem `TAM` nem `COR` → ambos vazios, palmilha não é uma das categorias "tamanho único"), sprays de tratamento (4, `TAMANHO`/`COR` vazios). Único item com `COR` preenchida: `PALMILHA DE AJUSTE TAM M COR AREIA`.

### Padrão Y — código SKU interno + marcador `N.` para o tamanho, `cProd` = `cEAN` completo (ex.: MACBOOT)

Descoberto testando a MACBOOT INDUSTRIA E COMERCIO DE CALCADOS (botas): aqui `cProd` **é sempre idêntico ao `cEAN`** (o próprio código de barras completo, GTIN-13) — diferente de todos os padrões anteriores, onde `cProd` é um código de referência interno do fornecedor. O `xProd` termina com um código SKU interno (modelo+cor, sem relação com `cProd`) e um marcador literal `N.` seguido do tamanho:

```
xProd = [COD_MODELO/DESC] [MATERIAL...] [COR] - [SKU_INTERNO] - N. [TAMANHO]
```

Exemplos:
- `CA00 01-UIRAPURU EMBORRACHADO GRAFITE - CA0001-EB06 - N. 38` — código de modelo "quebrado" com espaço estranho (`CA00 01-`, mantido como veio).
- `CASTANHEIRA 02 - CARAJAS NOBUCK CAFE - CAST02-NO41 - N. 37` — cor simples (`CAFE`).
- `CUMARU 02 - ARAXA C/HORSE BROWN - CUMA02-CH36 - N. 38` — `C/HORSE` (abreviação de "Crazy Horse", tipo de couro) não é cor, fica no `NOME_PRODUTO`; só `BROWN` é `COR`.
- `UIRAPURU 06-PATAXOS NOBUCK OIL BROWN - UIRA06-OI36 - N. 38` — cor com modificador **prefixado** `OIL` (`OIL BROWN`), mesma posição do `TODO` no Padrão V.
- `PACHON 02 - ARAXA NOBUCK OIL CAFÉ - PACH02-OI41 - N. 43` — confirma que `OIL` é um modificador genérico (funciona com `BROWN` e `CAFÉ`), não uma cor composta fixa.
- `PDV INSTITUCIONAL 2024 C.A 2024 - PDV1-CA01 - N. 1` — item de ponto de venda, sem cor.

**Algoritmo de extração (Padrão Y):**
1. `TAMANHO` = número capturado pela regex `N\.\s*(\d+)\s*$` no fim de `xProd`. Remover esse trecho (incluindo o `" - "` antes dele).
2. O segmento imediatamente anterior (também delimitado por `" - "`) é o `SKU_INTERNO` (ex.: `CA0001-EB06`, `CAST02-NO41`) — sempre um token alfanumérico misto, sem relação com `cProd`. Descartado, sem campo próprio.
3. No texto restante, aplicar a **mesma whitelist de cores válidas do Padrão C/H/V**, percorrendo as palavras de trás para frente: a última palavra reconhecida (ou as duas últimas, se a penúltima for o modificador `OIL`, que aqui sempre vem **antes** da cor, mesma posição do `TODO` no Padrão V) formam a `COR`.
4. `NOME_PRODUTO` = o que sobrar antes da `COR` — mantém o código de modelo (mesmo "quebrado" com espaço estranho) e a descrição de material/linha (`EMBORRACHADO`, `CARAJAS NOBUCK`, `ARAXA C/HORSE`, `PATAXOS NOBUCK`), sem tentar limpá-los (mesma cautela do Padrão H/W).
5. Se nenhuma palavra bater com a whitelist (ex.: item de PDV institucional) → `COR` vazia, `COR_PRECISA_REVISAO = false` (mesmo espírito do material de PDV no Padrão L — não é falha, é um item sem cor de verdade).

| xProd | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| `CA00 01-UIRAPURU EMBORRACHADO GRAFITE - CA0001-EB06 - N. 38` | `CA00 01-UIRAPURU EMBORRACHADO` | `GRAFITE` | `38` |
| `CUMARU 02 - ARAXA C/HORSE BROWN - CUMA02-CH36 - N. 38` | `CUMARU 02 - ARAXA C/HORSE` | `BROWN` | `38` |
| `UIRAPURU 06-PATAXOS NOBUCK OIL BROWN - UIRA06-OI36 - N. 38` | `UIRAPURU 06-PATAXOS NOBUCK` | `OIL BROWN` | `38` |
| `PDV INSTITUCIONAL 2024 C.A 2024 - PDV1-CA01 - N. 1` | `PDV INSTITUCIONAL 2024 C.A 2024` | (vazio) | `1` |

`REFERENCIA_PRODUTO` = `cProd` inalterado — nunca é cortado, porque `cProd` é sempre idêntico ao `cEAN` (GTIN completo), não um código de modelo+cor. ⚠️ **Efeito colateral, diferente de quase todos os outros padrões:** aqui `REFERENCIA_PRODUTO` **nunca se repete** entre tamanhos do mesmo modelo/cor — cada linha tem um valor único (idêntico ao `CODIGO_BARRAS`), porque o verdadeiro código de modelo+cor (`CA0001-EB06` etc.) fica só dentro do `xProd`, descartado como `SKU_INTERNO` no passo 2. `CODIGO_BARRAS` = `cEAN` real em 100% dos itens. `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente e não-zero nos itens testados). `<infAdProd>` existe e traz o par `TAMANHO/QUANTIDADE` (ex.: `38/1`), útil só como validação cruzada — o `TAMANHO` já vem confiável do marcador `N.` no `xProd`.

**Novas entradas na whitelist de cores:** `BROWN`, `ANDIROBA`, `BABACU` (tons de madeira/nozes, comuns em botas de couro) + modificador prefixado `OIL` (mesma posição do `TODO` do Padrão V — sempre antes da cor, não depois).

**Detecção do Padrão Y:** gatilho primário por fornecedor — `NOME_FORNECEDOR = MACBOOT INDUSTRIA E COMERCIO DE CALCADOS`. Sinal estrutural de apoio: `xProd` casa com `- \S+-\S+ - N\.\s*\d+$` no fim. **Precisa ser checado antes do fallback genérico**, porque o `xProd` deste fornecedor termina em número (o `TAMANHO` do marcador `N.`), o que cairia incorretamente no Padrão A/B genérico, capturando `COR = "N."`.

**Validação:** 3 arquivos, 110 itens (47 + 62 + 1). Modelos vistos: `UIRAPURU` (2 sub-modelos com códigos diferentes), `CASTANHEIRA`, `CUMARU`, `IMERI`, `MONJOLO`, `ONIX` (2 cores), `XDEZ`, `PACHON`, além de 2 itens de PDV institucional. Cores: `GRAFITE`, `CAFE`/`CAFÉ`, `BROWN`, `ANDIROBA`, `BABACU`, `PRETO`, `OIL BROWN`, `OIL CAFÉ`.

### Padrão Z — prefixo `COD_MODELO_COD_COR` descartável, venda em grade sem TAMANHO no XML (ex.: NOVOPE CALCADOS LTDA)

Descoberto testando a NOVOPE CALCADOS LTDA (marca própria, tênis infantil): `xProd` começa com um **código composto `COD_MODELO_COD_COR`** (delimitado por `_`, sem nenhuma relação com o `cProd` real), seguido de um marcador de acabamento opcional (`C/BORDADO`) e/ou o nome da marca própria (`NOVOPE`), da frase fixa `TENIS INFANTIL`, de uma palavra de `MATERIAL` (`TEXTIL`, `SINTETICO`), e do bloco de `COR` até o fim da string — **sem nenhum TAMANHO em lugar nenhum do XML**:

```
[COD_MODELO_COD_COR] [C/BORDADO]? [NOVOPE]? TENIS INFANTIL [MATERIAL] [COR1[/COR2[/COR3]]]
```

Exemplos:
- `32001170_1702 TENIS INFANTIL TEXTIL SONIC/FUME`
- `80001290_3 C/BORDADO TENIS INFANTIL TEXTIL PRETO/PRETO`
- `80001290_1710 C/BORDADO TENIS INFANTIL TEXTIL PRETO/PINK FLUOR/OFF WHITE` (3 cores)
- `50001022_1309 NOVOPE TENIS INFANTIL SINTETICO BRANCO/ROSA BB` (marca própria explícita, cor com qualificador de 2 palavras `ROSA BB`)
- `32001170_2957 TENIS INFANTIL TEXTIL ARANHA/TEIA CHUMBO/FUME` (segmento de cor com 2 palavras no meio: `TEIA CHUMBO`)

⚠️ **Sem marcador de TAMANHO em nenhuma posição:** `xProd` nunca termina em número, não existe `<infAdProd>` preenchido em nenhum item testado, e `cProd` (`9759`, `10174`, `10181`...) é um código curto puramente numérico, sem `-`/`_`/`.` e sem relação com o prefixo composto do `xProd`. O `qCom` de cada `<det>` é sempre 12 ou 15 (uma grade/caixa fechada de pares, mistura de tamanhos), não uma unidade — mesmo cenário já visto na CALCADOS BEBECE (Padrão J). **Decisão do usuário: processar mesmo assim, sem TAMANHO** (ver "Caso resolvido" abaixo).

⚠️ **Cores incluem nomes de estampa/tema, não só cores literais** (ex.: `SONIC` — personagem, `UNICORNIO` — estampa infantil, `ARANHA/TEIA` — estampa "aranha e teia"): como no Padrão B, este padrão **não usa whitelist** — o bloco depois do `MATERIAL` é gravado como veio, sem tentar validar se cada segmento é uma cor "de verdade". Mesma cautela já aplicada a nomes comerciais compostos no Padrão U (GONCALVES).

**Algoritmo de extração (Padrão Z):**
1. Localizar a primeira ocorrência de `_` em `xProd` — se o token antes dele for puramente numérico, é o início do prefixo `COD_MODELO_COD_COR`. Descartar esse primeiro token inteiro (até o próximo espaço), sem relação com `cProd`.
2. Localizar a frase fixa `TENIS INFANTIL` no restante do texto. Tudo entre o fim do prefixo (passo 1) e o início dessa frase — normalmente `C/BORDADO` e/ou o nome da marca própria (`NOVOPE`) — é mantido no `NOME_PRODUTO` (mesma cautela de outros padrões: só remove o que tem posição/marcador comprovado).
3. Localizar a palavra de `MATERIAL` logo após `TENIS INFANTIL` (`TEXTIL`, `SINTETICO`) — mantida no `NOME_PRODUTO`.
4. `NOME_PRODUTO` = tudo entre o fim do prefixo e o fim do `MATERIAL` (inclusive).
5. `COR` = tudo que sobrar depois do `MATERIAL` até o fim de `xProd` — dividido por `/` em segmentos (cada um pode ter 1 ou mais palavras, ex.: `PRETO/PINK FLUOR/OFF WHITE` → 3 segmentos) → `COR_1`/`COR_2`/`COR_3` como nos demais padrões.
6. `TAMANHO` = **sempre vazio** (decisão do usuário — ver acima).

| xProd | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 |
|---|---|---|---|---|---|
| `32001170_1702 TENIS INFANTIL TEXTIL SONIC/FUME` | `TENIS INFANTIL TEXTIL` | `SONIC/FUME` | `SONIC` | `FUME` | `FUME` |
| `80001290_3 C/BORDADO TENIS INFANTIL TEXTIL PRETO/PRETO` | `C/BORDADO TENIS INFANTIL TEXTIL` | `PRETO/PRETO` | `PRETO` | `PRETO` | `PRETO` |
| `80001290_1710 C/BORDADO TENIS INFANTIL TEXTIL PRETO/PINK FLUOR/OFF WHITE` | `C/BORDADO TENIS INFANTIL TEXTIL` | `PRETO/PINK FLUOR/OFF WHITE` | `PRETO` | `PINK FLUOR` | `OFF WHITE` |
| `50001022_1309 NOVOPE TENIS INFANTIL SINTETICO BRANCO/ROSA BB` | `NOVOPE TENIS INFANTIL SINTETICO` | `BRANCO/ROSA BB` | `BRANCO` | `ROSA BB` | `ROSA BB` |
| `32001170_2957 TENIS INFANTIL TEXTIL ARANHA/TEIA CHUMBO/FUME` | `TENIS INFANTIL TEXTIL` | `ARANHA/TEIA CHUMBO/FUME` | `ARANHA` | `TEIA CHUMBO` | `FUME` |

`REFERENCIA_PRODUTO` = `cProd` inalterado (`9759`, `10174`, `10303` etc. — sempre numérico curto, sem `-`, então a regra de corte nunca se aplica; também não há `TAMANHO` extraído para validar cruzado, então a questão fica sem efeito). `CODIGO_BARRAS` = **sempre vazio** (`cEAN` é sempre o literal `SEM GTIN` em todos os itens testados). `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente e não-zero em 100% dos itens testados). `COR_PRECISA_REVISAO` = **sempre `false`** (posição sempre determinística — mesma lógica dos Padrões B/G/U/W, que também não usam whitelist).

⚠️ **`" 2024"` grudado ao fim de um segmento de cor** (`30001272_2615 TENIS INFANTIL TEXTIL PRETO/LIMAO 2024` → `COR_2 = "LIMAO 2024"`): sem outra fonte no XML para confirmar se é parte do nome comercial da cor (ex. uma coleção/estação "Limão 2024") ou ruído, foi mantido dentro do bloco de cor por aplicação literal do algoritmo (passo 5 — tudo depois do `MATERIAL` até o fim é `COR`, sem exceção). Monitorar em lotes futuros se esse sufixo se repete em outras cores/anos (o que confirmaria ser parte do nome da cor) ou se aparece isolado (o que sugeriria ruído a ser tratado à parte).

**Detecção do Padrão Z:** gatilho primário por fornecedor — `NOME_FORNECEDOR = NOVOPE CALCADOS LTDA`. Sinal estrutural de apoio: `xProd` contém a frase fixa `TENIS INFANTIL`, `cEAN` é sempre `SEM GTIN` e não existe `<infAdProd>` preenchido. **Precisa ser checado antes do fallback genérico (passo 35)**, porque, sem TAMANHO no fim de `xProd`, a última palavra nunca é numérica — cairia incorretamente no Padrão C e falharia por não haver `infAdProd` para buscar (mesmo risco já descrito para o Padrão Q/FISIA).

**Validação:** 3 arquivos (mesmo LOTE/ORDEM COMPRA do fornecedor, mas `PEDIDO`/`nNF` distintos — não são notas reemitidas, são 3 pedidos diferentes do mesmo lote de fábrica), 23 itens (8 + 8 + 7 — o 3º arquivo não repete o item `50001022_1309`, mantido como veio, sem tentar completar). Modelos vistos: `32001170` (3 colorways), `80001290` (2 colorways, com bordado), `50001022`, `30001272` (2 colorways). Cores/estampas: `SONIC`, `FUME`, `PRETO`, `PINK FLUOR`, `OFF WHITE`, `BRANCO`, `ROSA BB`, `UNICORNIO`, `LILAS`, `LIMAO 2024`, `ARANHA`, `TEIA CHUMBO`, `CHUMBO`. `TAMANHO` vazio em 100% dos itens (esperado, decisão do usuário) e `COR_PRECISA_REVISAO = false` em 100% dos itens.

### Padrão AA — prefixo literal `CALCADOS-`, cor separada por `-` dentro do `xProd`, nomes de cor com prefixo de marca (ex.: PUMA SPORTS LTDA)

Descoberto testando a PUMA SPORTS LTDA: `xProd` começa com o literal `CALCADOS-` (hífen colado, sem espaço) seguido do nome do modelo e de um bloco de cor com **2 ou 3 segmentos separados por `-`** (hífen, não `/` como na maioria dos outros padrões) — cada segmento é um nome de colorway oficial da marca, geralmente prefixado por `Puma`/`PUMA` (ex.: `Puma Black`, `PUMA White`, `PUMA Gold`), mas nem sempre (ex.: `Cool Light Gray`, `Frosted Ivory`). O `TAMANHO` é a última palavra, sempre numérica:

```
CALCADOS-[MODELO...] [BDP]? [COR1(com ou sem prefixo Puma/PUMA)]-[COR2][-COR3] [TAMANHO]
```

Exemplos:
- `CALCADOS-CARINA BDP Puma Black-Puma Black-Puma Silver 34` (marcador `BDP` presente, 3 cores)
- `CALCADOS-CARINA L BDP Puma White-Puma White-Puma Silver 34` (idem, variante `L`)
- `CALCADOS-PUMA CLUB II PUMA Black-PUMA White-PUMA Gold 36` (sem `BDP` — modelo já contém a palavra `PUMA` no próprio nome comercial)
- `CALCADOS-SHUFFLE DOWNTOWN PUMA White-PUMA White-PUMA Gold 40` (sem `BDP`, 3 cores)
- `CALCADOS-PARK LIFESTYLE EASY SD BDP Cool Light Gray-PUMA White 40` (marcador `BDP` presente, mas a 1ª cor **não** tem prefixo `Puma`/`PUMA`, 2 cores)
- `CALCADOS-PARK LIFESTYLE EASY SD BDP PUMA Black-Frosted Ivory 37` (mesmo modelo do exemplo anterior, mas com a 1ª cor prefixada e a 2ª sem prefixo — confirma que a posição do prefixo de marca não é confiável por si só)

⚠️ **Por que não existe um separador confiável entre o nome do modelo e a 1ª cor:** ao contrário de quase todos os outros padrões, aqui **não há hífen nem marcador fixo entre o fim do modelo e o início da 1ª cor** — só um espaço, igual ao resto do texto. Os hífens só aparecem **entre as cores** (separando `COR_1` de `COR_2`, e `COR_2` de `COR_3`), nunca entre modelo e `COR_1`. Isso significa que o 1º segmento obtido ao dividir por `-` sempre mistura `[MODELO...] [COR_1]` grudados, exigindo uma âncora à parte para separar os dois.

**Algoritmo de extração (Padrão AA):**
1. Remover o prefixo literal `CALCADOS-` do início de `xProd`.
2. Última palavra do restante = `TAMANHO` (sempre numérica). Validação cruzada: bate com os 2 últimos dígitos do `cProd` em 100% dos itens testados — mas **`REFERENCIA_PRODUTO` nunca é cortado**, porque a regra de corte existente exige um `-` no `cProd`, e aqui o `cProd` é sempre um código numérico contínuo, sem `-` (ex.: `3755640134`), mesmo already contendo o tamanho embutido no final.
3. Dividir o que sobrou por `-` (hífen) → `segmentos`. O(s) hífen(s) delimitam exclusivamente as cores entre si (nunca o modelo da 1ª cor).
4. No **1º segmento** (que mistura `[MODELO...] [COR_1]`), localizar o ponto de corte, nesta ordem:
   - Se a palavra isolada `BDP` estiver presente → tudo **depois** dela é `COR_1`; tudo até `BDP` (inclusive) é `NOME_PRODUTO`.
   - Senão, localizar a **última** ocorrência de `Puma`/`PUMA` (case-insensitive) → tudo a partir dela é `COR_1`; tudo antes é `NOME_PRODUTO`.
5. Os segmentos seguintes (`segmentos[1]`, e `segmentos[2]` se houver) já vêm limpos, sem mistura com o nome do modelo → viram `COR_2`/`COR_3` diretamente, sem processamento adicional.
6. `COR` = `COR_1/COR_2[/COR_3]` (rejuntado com `/`, mesma convenção de armazenamento do resto do documento, mesmo o separador original no XML sendo `-`). Os prefixos `Puma`/`PUMA` **são mantidos** dentro de cada cor (não descartados) — são o nome oficial do colorway da marca (`Puma Black` é um nome de cor específico do catálogo Puma, não "preto" genérico + código descartável), mesma cautela de manter texto descritivo sem marcador estrutural que justifique removê-lo.

| xProd (sem `CALCADOS-` nem TAMANHO) | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 |
|---|---|---|---|---|---|
| `CARINA BDP Puma Black-Puma Black-Puma Silver` | `CARINA BDP` | `Puma Black/Puma Black/Puma Silver` | `Puma Black` | `Puma Black` | `Puma Silver` |
| `PUMA CLUB II PUMA Black-PUMA White-PUMA Gold` | `PUMA CLUB II` | `PUMA Black/PUMA White/PUMA Gold` | `PUMA Black` | `PUMA White` | `PUMA Gold` |
| `SHUFFLE DOWNTOWN PUMA White-PUMA White-PUMA Gold` | `SHUFFLE DOWNTOWN` | `PUMA White/PUMA White/PUMA Gold` | `PUMA White` | `PUMA White` | `PUMA Gold` |
| `PARK LIFESTYLE EASY SD BDP Cool Light Gray-PUMA White` | `PARK LIFESTYLE EASY SD BDP` | `Cool Light Gray/PUMA White` | `Cool Light Gray` | `PUMA White` | `PUMA White` |
| `PARK LIFESTYLE EASY SD BDP PUMA Black-Frosted Ivory` | `PARK LIFESTYLE EASY SD BDP` | `PUMA Black/Frosted Ivory` | `PUMA Black` | `Frosted Ivory` | `Frosted Ivory` |

`REFERENCIA_PRODUTO` = `cProd` inalterado (`3755640134`, `3974440136`, `4042280237` etc. — sempre numérico contínuo, sem `-`, apesar de terminar visivelmente no `TAMANHO`; a regra de corte não se aplica por falta de delimitador, mesmo comportamento já visto na CALCADOS MARTE/BEIRA RIO — cProd "puro" sem hífen nunca é cortado, mesmo quando o sufixo bate com o TAMANHO). `CODIGO_BARRAS` = `cEAN` real (GTIN-13) em 100% dos itens testados. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `<infAdProd>` existe só no 1º arquivo (2024), trazendo apenas texto fiscal fixo (Resolução do Senado + FCI) — não usado na extração; nos outros 2 arquivos (2025/2026) a tag nem aparece. `COR_PRECISA_REVISAO` = **sempre `false`** (posição sempre determinística via `BDP`/`Puma`, sem whitelist).

⚠️ **Limitação assumida:** o algoritmo depende de pelo menos um dos dois marcadores (`BDP` ou `Puma`/`PUMA`) estar presente no 1º segmento para localizar a fronteira modelo↔cor. Não foi testado nenhum item onde os dois estejam ausentes — se aparecer, a extração não teria como separar `NOME_PRODUTO` de `COR_1` com segurança (ficaria tudo junto no `NOME_PRODUTO`, sem tentar adivinhar, mesma cautela do resto do documento).

**Detecção do Padrão AA:** gatilho primário por fornecedor — `NOME_FORNECEDOR = PUMA SPORTS LTDA`. Sinal estrutural de apoio: `xProd` começa com o literal `CALCADOS-` (hífen colado ao nome do modelo, sem espaço). **Precisa ser checado antes do fallback genérico (passo mais abaixo)**, porque `xProd` termina em número (o `TAMANHO`) — sem essa checagem específica, o algoritmo genérico separaria por espaço e capturaria só a última palavra antes do tamanho (`Silver`, `Gold`...) como `COR`, perdendo o restante do bloco de cor e cortando o `NOME_PRODUTO` no lugar errado (os hífens internos ficariam presos ao "nome" incorretamente).

**Validação:** 3 arquivos, 34 itens (11 + 8 + 15). Duas filiais do mesmo fornecedor (`CNPJ` raiz `05406034`, filiais `002300` e `005163`, ambas `xNome = PUMA SPORTS LTDA`). Modelos vistos: `CARINA BDP`, `CARINA L BDP`, `PUMA CLUB II`, `SHUFFLE DOWNTOWN`, `PARK LIFESTYLE EASY SD BDP` (2 colorways). Cores: `Puma Black`, `Puma White`, `Puma Silver`, `PUMA Black`, `PUMA White`, `PUMA Gold`, `Cool Light Gray`, `Frosted Ivory` (grafia `Puma`/`PUMA` inconsistente entre arquivos — mantida como veio, sem normalizar capitalização). `TAMANHO` bateu 100% com o sufixo do `cProd` nos 34 itens (0 divergências), mesmo sem usar essa relação para extração (o `TAMANHO` real vem sempre da última palavra do `xProd`). `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens.

### Padrão AB — COR = última palavra, venda em grade sem TAMANHO no XML, `cProd` não distingue cor (ex.: SAPATARIA BERTELLI IND. E COMERCIO)

Descoberto testando a SAPATARIA BERTELLI IND. E COMERCIO: `xProd` é o mais simples encontrado até agora entre os fornecedores com COR extraível — sempre começa com o literal fixo `SAPATO MASC`, seguido de um código de modelo no formato `NN.NNN` (às vezes com sufixo alfanumérico colado por `-`, ex. `-SP`, `-SA`, `-SB`), um marcador `INF` opcional (linha infantil), o nome da linha/coleção (`CONFORT` ou `TECH LINE`) e a `COR` — sempre a **última palavra** da string, sem exceção:

```
SAPATO MASC [COD_MODELO][-SUFIXO]? [INF]? [LINHA...] [COR]
```

Exemplos:
- `SAPATO MASC 80.010 CONFORT PRETO`
- `SAPATO MASC 47.000-SP CONFORT WHISKY` (sufixo `-SP` colado ao código de modelo)
- `SAPATO MASC 70.104 INF CONFORT PRETO` (linha infantil, marcador `INF`)
- `SAPATO MASC 75.026 TECH LINE IMBUIA` (linha de 2 palavras, `TECH LINE`)
- `SAPATO MASC 20.009-SB TECH LINE PRETO`

⚠️ **Sem TAMANHO em nenhuma posição do `xProd`, e o `<infAdProd>` traz a grade inteira, não um tamanho único:** cada `<det>` representa uma caixa fechada com vários tamanhos (`qCom` = soma exata dos pares do `infAdProd`, ex. `xProd` com `qCom=12` e `infAdProd = "38/1    39/2    40/3    41/3    42/2    43/1"`, onde `1+2+3+3+2+1=12`). Mesmo cenário já visto na CALCADOS BEBECE (Padrão J) e na NOVOPE (Padrão Z). **Decisão do usuário: processar mesmo assim, sem TAMANHO** (ver "Caso resolvido" abaixo).

⚠️ **Colisão com o gatilho estrutural do Padrão J:** o `infAdProd` desta fornecedora (`"38/1    39/2..."`) casa com o mesmo padrão `\d{2}/\d+` usado como sinal estrutural de apoio do Padrão J (CALCADOS BEBECE) — mas o algoritmo do Padrão J espera `xProd` dividido por `" / "` (barra com espaços), que **não existe** no `xProd` desta fornecedora. Sem um gatilho próprio checado antes do Padrão J, a extração cairia no "caso de segurança" do Padrão J (`xProd` sem nenhum `/` → `COR` vazia, `COR_PRECISA_REVISAO = true`), perdendo a `COR`, que aqui é perfeitamente extraível pela última palavra.

⚠️ **`cProd` não distingue cor, apenas o modelo base:** ao contrário de quase todos os outros padrões, aqui o mesmo `cProd` pode se repetir em linhas com `COR` diferente (ex.: `cProd=70199` aparece com `COR=CAFE` e, em outro item da mesma nota, com `COR=PRETO`; o mesmo ocorre com `cProd=75026` e `cProd=20009`, cada um em `PRETO` e `IMBUIA`). Isso acontece porque `cProd` reflete só o código do modelo/forma (`70.199`, `75.026`...), sem incorporar a cor — diferente do padrão usual onde `cProd` costuma variar por colorway. Além disso, `cProd` "perde" o sufixo alfanumérico do `xProd` (`47.000-SP` no `xProd` vira `cProd=47000`, sem o `-SP`).

**Algoritmo de extração (Padrão AB):**
1. `COR` = última palavra de `xProd`.
2. `NOME_PRODUTO` = `xProd` sem a última palavra (mantém `SAPATO MASC`, código de modelo com sufixo se houver, `INF` quando presente, e o nome da linha — nenhum desses é removido, mesma cautela do resto do documento: só se remove o que tem posição/marcador comprovado).
3. `TAMANHO` = **sempre vazio** (decisão do usuário — ver acima).

| xProd | NOME_PRODUTO | COR |
|---|---|---|
| `SAPATO MASC 80.010 CONFORT PRETO` | `SAPATO MASC 80.010 CONFORT` | `PRETO` |
| `SAPATO MASC 47.000-SP CONFORT WHISKY` | `SAPATO MASC 47.000-SP CONFORT` | `WHISKY` |
| `SAPATO MASC 70.104 INF CONFORT PRETO` | `SAPATO MASC 70.104 INF CONFORT` | `PRETO` |
| `SAPATO MASC 75.026 TECH LINE IMBUIA` | `SAPATO MASC 75.026 TECH LINE` | `IMBUIA` |

`REFERENCIA_PRODUTO` = `cProd` inalterado (`80010`, `47000`, `70104` etc. — sempre numérico curto, sem `-`, então a regra de corte nunca se aplica; não há TAMANHO extraído para validar cruzado de qualquer forma). `CODIGO_BARRAS` = **sempre vazio** (`cEAN` é sempre o literal `SEM GTIN` em 100% dos itens testados). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `COR_PRECISA_REVISAO` = **sempre `false`** (posição sempre determinística — última palavra, sem whitelist, mesma lógica do Padrão B/G/U/Z).

**Detecção do Padrão AB:** gatilho primário por fornecedor — `NOME_FORNECEDOR = SAPATARIA BERTELLI IND. E COMERCIO`. Sinal estrutural de apoio: `xProd` começa com o literal `SAPATO MASC` e não contém nenhum `/`. **Precisa ser checado antes do Padrão J**, porque o `infAdProd` desta fornecedora também casa com o gatilho estrutural de apoio do Padrão J (`\d{2}/\d+`), o que produziria uma extração incorreta (ver observação acima).

**Validação:** 2 arquivos, 23 itens (11 + 12). Modelos vistos: `80.010`, `80.001`, `80.024`, `90.100`, `90.127`, `70.183`, `70.018`, `70.199`, `47.000-SP`, `47.026-SA`, `70.104 INF`, `75.026`, `20.009-SB`. Linhas: `CONFORT` (maioria), `TECH LINE` (2 modelos, ambos com 2 colorways cada). Cores: `PRETO` (maioria), `CAFE`, `WHISKY`, `IMBUIA`. `TAMANHO` vazio e `COR_PRECISA_REVISAO = false` em 100% dos itens. Somatório dos pares `tamanho/quantidade` do `infAdProd` bateu 100% com `qCom` nos 23 itens (validação de consistência, sem efeito na extração).

### Caso resolvido: SAPATARIA BERTELLI IND. E COMERCIO — decisão do usuário: processar sem TAMANHO

`xProd` da BERTELLI não contém `TAMANHO` em nenhuma posição — o `<infAdProd>` traz a grade inteira em pares `tamanho/quantidade` (ex. `38/1 39/2 40/3 41/3 42/2 43/1`, somando exatamente o `qCom` do item), não um tamanho único por linha, mesmo cenário da CALCADOS BEBECE e da NOVOPE. Perguntado ao usuário se deveria processar mesmo assim sem `TAMANHO`: **sim, processar sem TAMANHO**. Ver Padrão AB acima.

### Padrão AC — TAMANHO e código de cor vêm do `cProd` em 4 segmentos, sem COR legível em lugar nenhum (ex.: SKECHERS DO BRASIL CALCADOS LTDA)

Descoberto testando a SKECHERS DO BRASIL CALCADOS LTDA: `xProd` traz só o nome da linha/coleção do tênis, **sem cor nem tamanho em nenhuma posição** — às vezes com um submodelo depois de `" - "` (espaço-hífen-espaço), às vezes sem (nome único). Todo o resto da informação (estilo, cor, tamanho) fica codificado no `cProd`, sempre em **4 segmentos delimitados por `-`**, e **não existe `<infAdProd>`** em nenhum item testado (mesmo cenário estrutural da FISIA/Nike — Padrão Q — mas com um segmento a mais):

```
xProd = [LINHA/COLECAO] [- SUBMODELO]?
cProd = [PREFIXO_LINHA]-[COD_ESTILO]-[COD_COR]-[TAMANHO]
```

Exemplos:
- `xProd = "BOBS B LOVE - TRUE DELIGHT"`, `cProd = "BOS-117617-LTPK-34"` (com submodelo)
- `xProd = "GO WALK FLEX"`, `cProd = "GOM-894343BR-BBK-38"` (sem submodelo, `COD_ESTILO` com sufixo `BR`)
- `xProd = "ARCH FIT 2.0 - BIG LEAGUE"`, `cProd = "R-150051BR-BKRG-34"` (`PREFIXO_LINHA` de 1 letra só)
- `xProd = "GO RUN CONSISTENT 2.0 - AYRAH"`, `cProd = "GTW-128649BR-LTBL-35"`

**Algoritmo de extração (Padrão AC):**
1. `NOME_PRODUTO` = `xProd` sem alteração (não há cor/tamanho para remover em nenhuma posição — mesmo espírito do Padrão Q/C/K/N).
2. Dividir `cProd` por `-` → sempre 4 segmentos: `[PREFIXO_LINHA, COD_ESTILO, COD_COR, TAMANHO]`. O `PREFIXO_LINHA` varia em tamanho (`BOS`, `GOM`, `GTW`, `R`...) e o `COD_ESTILO` às vezes traz um sufixo `BR` colado sem hífen (`894343BR`) — nenhum dos dois afeta a contagem de segmentos, que é sempre 4.
3. `TAMANHO` = último segmento (sempre numérico nos itens testados, faixa `34`-`43`).
4. `REFERENCIA_PRODUTO` = `cProd` sem o último segmento (aplicação direta da regra de corte já existente — o último segmento sempre bate com o `TAMANHO` extraído, porque foi extraído dali mesmo) → `PREFIXO_LINHA-COD_ESTILO-COD_COR` (ex.: `BOS-117617-LTPK`). O `COD_COR` é **mantido**, não descartado — mesma decisão já tomada nos Padrões Q/V/W: é a única forma de diferenciar colorways do mesmo estilo.
5. `COR` = **sempre vazia**. Os códigos vistos (`LTPK`, `WHT`, `BKW`, `BBK`, `LTBL`, `BKRG`, `BLK`, `NVY`, `OLV`, `BKCC`, `BLPK`, `LGMT`, `NTGY`, `TPE`) são abreviações internas do fornecedor — alguns parecem óbvios (`BLK`≈preto, `WHT`≈branco, `NVY`≈marinho), mas outros não têm leitura confiável (`BKCC`, `LGMT`, `NTGY`, `TPE`) e não existe uma tabela de tradução no XML. Mesma cautela do Padrão Q: não traduzir parcialmente sem uma fonte confiável para todos os códigos — `COR_PRECISA_REVISAO = true`.

`CODIGO_BARRAS` = `cEAN` real (GTIN/UPC, 12-13 dígitos) em 100% dos itens testados. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `<infAdProd>` não existe em nenhum item testado.

**Detecção do Padrão AC:** gatilho primário por fornecedor — `NOME_FORNECEDOR = SKECHERS DO BRASIL CALCADOS LTDA`. Sinal estrutural de apoio: `cProd` sempre tem exatamente 4 segmentos delimitados por `-`, o último puramente numérico, e não existe `<infAdProd>`. **Precisa ser checado antes do fallback genérico**, porque `xProd` nunca termina em número — sem essa checagem específica cairia no Padrão C e falharia por não haver `infAdProd` para buscar, mesmo risco já descrito para o Padrão Q/FISIA e o Padrão Z/NOVOPE.

⚠️ **Diferença estrutural em relação ao Padrão Q (FISIA/Nike):** lá o `cProd` tem 3 segmentos (`COD_ESTILO-COD_COR_NUM-TAMANHO`, código de cor sempre numérico); aqui são 4 segmentos (`PREFIXO_LINHA-COD_ESTILO-COD_COR-TAMANHO`, código de cor sempre alfabético/alfanumérico, nunca puramente numérico) — padrões parecidos na intenção (marca internacional, TAMANHO só no `cProd`, sem COR legível), mas com formato de `cProd` diferente o suficiente para não reaproveitar o mesmo algoritmo sem ajuste.

**Validação:** 2 arquivos, 87 itens (60 + 27). Modelos/linhas vistos: `BOBS B LOVE - TRUE DELIGHT`, `GO WALK 8 - DAY`, `GO WALK FLEX`, `GO RUN CONSISTENT 2.0 - AYRAH`, `GO RUN ELEVATE 2.0`, `ARCH FIT 2.0`, `ARCH FIT 2.0 - BIG LEAGUE`, `GLIDE-STEP ATLUS`, `SKECH CLOUD`, `ULTRA FLEX 2.0`. `TAMANHO` sempre inteiro (`34` a `43`) nos itens testados — nenhum decimal visto ainda (diferente da Nike/Padrão Q, que tem meios-números). `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens. `COR` vazia e `COR_PRECISA_REVISAO = true` em 100% dos itens.

### Padrão AD — sufixo `REF [cProd]` descartável no fim do `xProd`, `cProd` delimitado por `.` (ex.: VIA VIP CALCADOS LTDA)

Descoberto testando a VIA VIP CALCADOS LTDA (calçados infantis/bebê, marcador `BB`): `xProd` é o mais "auto-documentado" encontrado no projeto até agora — segue a estrutura clássica `TIPO MODELO EM MATERIAL [descrição técnica] COR TAMANHO`, mas termina sempre repetindo o próprio `cProd` por extenso, precedido do literal `REF`:

```
TENIS [MODELO] BB EM [MATERIAL] [DESCRICAO_TECNICA...] [COR1[/COR2[/COR3]]] [TAMANHO] REF [cProd]
```

Exemplos:
- `TENIS STREET BB EM SINTETICO C/ VELCRO E SILK ESTRELA BRANCO/LILAS 20 REF VV2800.BL.20`
- `TENIS STREET BB EM TECIDO C/ VELCRO VV2602 PRETO/BRANCO 19 REF VV2602.038.19` (fragmento do `cProd`, `VV2602`, aparece também dentro da descrição técnica — coincidência sem função estrutural, mantido no `NOME_PRODUTO`)
- `TENIS STREET BB EM SINTETICO COM VELCRO E CORACAO LATERAL BRANCO/PRATA 19 REF VV2822.BP.19` (`COM` em vez de `C/`)
- `TENIS JOGGING BB EM SINTETICO C/CORACAO E VELCRO PRETO/PINK 19 REF VV2519.105.19` (modelo `JOGGING`, não só `STREET`)

**Algoritmo de extração (Padrão AD):**
1. Remover o sufixo ` REF [cProd]` do fim de `xProd` (o valor depois de `REF` sempre bate exatamente com o `<cProd>` do item — descartável, redundante).
2. No que sobrar, última palavra = `TAMANHO` (sempre numérica). Validação cruzada: bate 100% com o último segmento do `cProd` (delimitado por `.`).
3. Penúltima palavra = `COR` (pode ter `/` interno para bi/tricolor, aplicando a regra estendida de qualificador de 2 palavras quando necessário, mesma lógica do sub-caso KIDY/Padrão B — não visto nos itens testados, mas a regra já existe caso apareça).
4. `NOME_PRODUTO` = o que sobrar (inclui `TENIS`, modelo, `BB`, `EM MATERIAL` e toda a descrição técnica — sem tentar separá-los, mesma cautela do resto do documento).

`REFERENCIA_PRODUTO` = `cProd`, estendendo a regra de corte já existente ao delimitador `.` (mesmo delimitador dos Padrões V/W) — corta o último segmento porque ele sempre bate com o `TAMANHO` extraído (100% dos itens testados) → resultado `[MODELO_COD].[COD_COR]` (ex.: `VV2800.BL`, removendo `.20`). `CODIGO_BARRAS` = `cEAN` real em 100% dos itens. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `<infAdProd>` existe mas só traz texto fiscal fixo (Resolução do Senado + FCI) — não usado na extração.

| xProd | NOME_PRODUTO | COR | TAMANHO | cProd | REFERENCIA_PRODUTO |
|---|---|---|---|---|---|
| `TENIS STREET BB EM SINTETICO C/ VELCRO E SILK ESTRELA BRANCO/LILAS 20 REF VV2800.BL.20` | `TENIS STREET BB EM SINTETICO C/ VELCRO E SILK ESTRELA` | `BRANCO/LILAS` | `20` | `VV2800.BL.20` | `VV2800.BL` |
| `TENIS STREET BB EM TECIDO C/ VELCRO VV2602 PRETO/BRANCO 19 REF VV2602.038.19` | `TENIS STREET BB EM TECIDO C/ VELCRO VV2602` | `PRETO/BRANCO` | `19` | `VV2602.038.19` | `VV2602.038` |
| `TENIS JOGGING BB EM SINTETICO C/CORACAO E VELCRO PRETO/PINK 19 REF VV2519.105.19` | `TENIS JOGGING BB EM SINTETICO C/CORACAO E VELCRO` | `PRETO/PINK` | `19` | `VV2519.105.19` | `VV2519.105` |

⚠️ **Colisão crítica com o gatilho do sub-caso CALCADOS MARTE:** **100% dos itens deste fornecedor contêm o token isolado `EM`** (marcador `EM SINTETICO`/`EM TECIDO`). Sem um gatilho próprio checado **antes** do sub-caso MARTE, todos os 236 itens cairiam incorretamente nesse sub-caso — que assumiria `MATERIAL` = palavra após `EM` (`SINTETICO`, correto por coincidência), mas `COR` = palavra **seguinte** ao `MATERIAL` (capturaria o literal `C/` ou `COM`, errado), e `TAMANHO` = última palavra de `xProd` (que seria o próprio `REF [cProd]`, ex. `VV2800.BL.20`, não o número `20` real) — uma falha silenciosa que corromperia `COR` e `TAMANHO` em toda a base do fornecedor.

**Detecção do Padrão AD:** gatilho primário por fornecedor — `NOME_FORNECEDOR = VIA VIP CALCADOS LTDA`. Sinal estrutural de apoio: `xProd` termina no padrão ` REF [valor]$`, onde `[valor]` bate exatamente com o `cProd` do item. **Precisa ser checado antes do sub-caso CALCADOS MARTE** (ver colisão acima).

**Validação:** 3 arquivos, 236 itens (102 + 76 + 58). Modelos: `TENIS STREET BB`, `TENIS JOGGING BB`. Materiais: `SINTETICO`, `TECIDO`. Cores (1 a 3 segmentos, sempre 1 palavra por segmento — nenhum qualificador de 2 palavras visto): `BRANCO/LILAS`, `BRANCO/VERM/PTO`, `BRANCO/PINK`, `BRANCO/PRATA`, `BRANCO/PRETO`, `BRANCO/PRETO/VERMELHO`, `BRANCO/VERDE`, `CINZA/BRANCO`, `MARINHO/CASTOR`, `MARINHO/PINK`, `MARINHO/ROYAL/PRETO`, `PRETO/BCO/VERM`, `PRETO/BRANCO`, `PRETO/LARANJA/PTO`, `PRETO/PINK`, `PRETO/RUBI`, `PRETO/VERMELHO`, `ROSE`. `TAMANHO` bateu 100% com o último segmento do `cProd` e o marcador `REF [cProd]` bateu 100% com o `cProd` real em todos os 236 itens — nenhuma divergência, nenhum item sem o marcador `EM`.

### Padrão AE — categoria bolsas/mochilas, `TAMANHO` sempre o literal `UNI`, bloco de cor via última posição espaço-separada (ex.: STAMPA ARTEFATOS DE COURO LTDA)

Descoberto testando a STAMPA ARTEFATOS DE COURO LTDA: além dos itens de calçado (que validam o Padrão H existente — ver sub-caso acima), o mesmo fornecedor também vende **bolsas e mochilas de couro** (oitava categoria não-calçado do projeto), com uma estrutura de `xProd` totalmente diferente: nenhum tamanho numérico — o marcador de tamanho é sempre o literal `UNI` (peça de tamanho único) — e um bloco de material e um bloco de cor, cada um dividido internamente por `/`, sempre com o **mesmo número de segmentos** nos dois lados (mesma ideia de "espelhamento" já vista em outros padrões, aqui aplicada a segmentos inteiros, não só palavras):

```
[COD_MODELO] [BOLSAS|MOCHILA FEMININA] [MATERIAL_1[/MATERIAL_2[/...]]] [COR_1[/COR_2[/...]]] UNI
```

Exemplos:
- `10004098 BOLSAS MADRAS/MADRAS/VITELLO/LAMINATI OURO/LUNA/CAMEL/OURO UNI` (4 materiais / 4 cores)
- `10005324 BOLSAS NEW RIDGE PRETO UNI` (1 material de 2 palavras / 1 cor de 1 palavra)
- `10006111 BOLSAS NEW RIDGE/NEW RIDGE/SAARA PRETO/PRETO/AMENDOA UNI` (3 materiais / 3 cores)
- `2000549 BOLSAS MONOGRAMA LL/CALF MOCCA/COHIBA I UNI` (2 materiais / 2 cores, última cor com qualificador de 2 palavras `COHIBA I`)
- `MC-1018NAC MOCHILA FEMININA MONOGRAMA LL/CALF MOCCA/COHIBA I UNI` (`COD_MODELO` alfanumérico com `-`, `TIPO` de 2 palavras)

**Algoritmo de extração (Padrão AE):**
1. Remover o primeiro token (`COD_MODELO` — numérico como `10004098` ou alfanumérico com `-` como `MC-1018NAC` — sempre descartado, sem relação com `cProd`).
2. Remover o literal `UNI` do fim → `TAMANHO = "UNI"` (marcador explícito do próprio XML, mantido como veio — não é o `"UN"` construído pela regra geral 4, é o literal real encontrado no `xProd`, mesma distinção já aplicada ao `"UNICO"` do Padrão F).
3. No que sobrar, dividir por **espaço** → `tokens`. `TIPO` (`BOLSAS` ou `MOCHILA FEMININA`, reconhecido por vocabulário fixo) permanece como parte do `NOME_PRODUTO`, sem remoção.
4. `último_token = tokens[-1]`. Se contiver `/` → bloco de `COR` bruto = esse token sozinho. Senão (palavra solta, ex. `PRETO`, `AMENDOA`) → bloco de `COR` bruto = `tokens[-1]` sozinho **se `tokens[-2]` não contiver `/`** (caso simples, 1 material + 1 cor, ambos de 1+ palavras sem `/` em lugar nenhum); **senão**, junta com `tokens[-2]` (que deve conter `/`) → bloco de `COR` bruto = `tokens[-2] + " " + tokens[-1]` (mesma lógica do qualificador de 2 palavras do Padrão B/KIDY, generalizada).
5. `NOME_PRODUTO` = `TIPO` + os `tokens` restantes antes do bloco de `COR` (inclui o(s) `MATERIAL`(is), sem tentar separá-los individualmente — mesma cautela do Padrão H/W).
6. `COR` = bloco de cor bruto, dividido por `/` em `COR_1`/`COR_2`/`COR_3` (só os 3 primeiros segmentos se houver 4+, mesma convenção geral do documento).

| xProd | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| `10004098 BOLSAS MADRAS/MADRAS/VITELLO/LAMINATI OURO/LUNA/CAMEL/OURO UNI` | `BOLSAS MADRAS/MADRAS/VITELLO/LAMINATI` | `OURO/LUNA/CAMEL/OURO` | `OURO` | `LUNA` | `CAMEL` | `UNI` |
| `10005324 BOLSAS NEW RIDGE PRETO UNI` | `BOLSAS NEW RIDGE` | `PRETO` | `PRETO` | `PRETO` | `PRETO` | `UNI` |
| `10006111 BOLSAS NEW RIDGE/NEW RIDGE/SAARA PRETO/PRETO/AMENDOA UNI` | `BOLSAS NEW RIDGE/NEW RIDGE/SAARA` | `PRETO/PRETO/AMENDOA` | `PRETO` | `PRETO` | `AMENDOA` | `UNI` |
| `2000549 BOLSAS MONOGRAMA LL/CALF MOCCA/COHIBA I UNI` | `BOLSAS MONOGRAMA LL/CALF` | `MOCCA/COHIBA I` | `MOCCA` | `COHIBA I` | `COHIBA I` | `UNI` |
| `MC-1018NAC MOCHILA FEMININA MONOGRAMA LL/CALF MOCCA/COHIBA I UNI` | `MOCHILA FEMININA MONOGRAMA LL/CALF` | `MOCCA/COHIBA I` | `MOCCA` | `COHIBA I` | `COHIBA I` | `UNI` |

`REFERENCIA_PRODUTO` = `cProd` inalterado (`23801`, `24793`, `77795` etc. — sempre numérico, sem `-`/`_`/`.`, então a regra de corte nunca se aplica; sem relação com o `COD_MODELO` do `xProd`, que às vezes é alfanumérico com `-` tipo `MC-1018NAC`). `CODIGO_BARRAS` = `cEAN` real em 100% dos itens. `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `<infAdProd>` existe, traz só texto fiscal fixo (Resolução do Senado + FCI, com acentuação corrompida no XML de origem — `ResoluA A o`, `nA `, `NA mero` — mantido como veio, não usado na extração).

⚠️ **`COR_PRECISA_REVISAO` não se aplica aqui** (`= false` sempre) — a extração é posicional/determinística (última posição espaço-separada, com a extensão de 1 palavra já validada em todos os casos testados), sem depender de whitelist.

**Detecção do Padrão AE:** gatilho primário por fornecedor — `NOME_FORNECEDOR = STAMPA ARTEFATOS DE COURO LTDA`. Sinal estrutural de apoio: `xProd` termina no literal ` UNI` (espaço + `UNI`, fim absoluto). **Precisa ser checado antes do Padrão H**, porque vários itens deste fornecedor têm `COD_MODELO` puramente numérico no início (ex. `10004098`), o que casaria com o gatilho estrutural genérico do Padrão H (`token numérico + TIPO ≠ CALCADOS`, já que `BOLSAS`/`MOCHILA` não são `CALCADOS`) — e o algoritmo de H espera que o último token seja sempre numérico (`TAMANHO`), quebrando ao encontrar `UNI` no lugar.

**Validação:** 1 arquivo, 9 itens. Tipos: `BOLSAS`, `MOCHILA FEMININA`. Modelos: `MADRAS`, `NEW RIDGE`, `MONOGRAMA LL`, `CALF`. Cores: `OURO`, `LUNA`, `CAMEL`, `PRETO`, `AMENDOA`, `MOCCA`, `COHIBA I`. `TAMANHO = "UNI"` e `CODIGO_BARRAS` real em 100% dos itens.

### Padrão AF — `xProd` truncado em largura fixa (ERP legado), duas sub-estruturas por `TIPO` (ex.: NOVAPELLI IND. COM. IMP. EXP. LTDA)

Descoberto testando a NOVAPELLI IND. COM. IMP. EXP. LTDA (cintos e carteiras de couro): o `xProd` vem de um export de campos de **largura fixa** (cada campo é preenchido com espaços à direita até completar um número fixo de caracteres, sem trim), e a string inteira é **truncada em um comprimento total fixo** — o que corta a informação de tamanho fora do `xProd` em alguns casos. Dois sub-tipos de produto, com estruturas diferentes:

**Sub-caso CINTO:**

```
CINTO [LARGURA_MM] [LINHA] [COR] [MODELO] [ruído truncado: PCA / PCA TAM]
cProd = [COD_MODELO].T[TAMANHO]
```

Exemplos:
- `CINTO 35 SOC PRETO   AFRO  PCA TAM` (`cProd = G029019751.T090`) — termina no literal `TAM`, mas **sem nenhum valor depois** (truncado antes do número).
- `CINTO 32 SOC CAFE    CAPRE PCA` (`cProd = ...`) — aqui a truncagem corta ainda mais cedo, nem a palavra `TAM` sobrevive.
- `CINTO 35 REV PTO/CAF CAPRE PCA` — cor bicolor abreviada (`PTO/CAF`), sem tradução.

⚠️ **`TAMANHO` nunca está disponível no `xProd`** (mesmo quando a palavra `TAM` aparece, não há número depois dela — o campo foi cortado antes do valor). A única fonte confiável é o próprio `cProd`, que sempre termina em `.T` seguido dos dígitos do tamanho (ex.: `.T090`, `.T095`, `.T100`, `.T105`, `.T110`, `.T115` — numeração de cinto em cm).

**Algoritmo de extração (sub-caso CINTO):**
1. Dividir `xProd` por espaço, **colapsando sequências de espaços múltiplos em um só separador** (o preenchimento de largura fixa deixa 1 a 3+ espaços entre campos, sem significado próprio) → `tokens`.
2. Por posição, sempre a partir do início (a cauda é imprevisível pela truncagem, então nunca se usa posição a partir do fim aqui): `tokens[0] = "CINTO"`, `tokens[1] = LARGURA_MM`, `tokens[2] = LINHA` (`SOC`/`ESP`/`REV`), `tokens[3] = COR` (pode ter `/` interno para bicolor, ex. `PTO/CAF`), `tokens[4] = MODELO` (padrão/textura do couro, ex. `AFRO`, `CAPRE`, `VENEZ`, `SMALB`, `ELAST`).
3. Tudo que sobrar depois de `tokens[4]` (`PCA`, ou `PCA TAM`) é ruído de truncamento — sempre descartado, nunca carrega um `TAMANHO` real, mesmo quando termina no literal `TAM`.
4. `NOME_PRODUTO` = `CINTO` + `LARGURA_MM` + `LINHA` + `MODELO` (tudo exceto `COR` e o ruído final).
5. `TAMANHO` = capturado do `cProd` via `\.T(\d+)$` (dígitos depois do `T`) — nunca do `xProd`.

**Sub-caso CARTEIRA:**

```
CARTEIRA [SEXO] [COR]? [MODELO] [COD_COLECAO]
```

Exemplos:
- `CARTEIRA MAS PRETO   CAPRE C12` — cor preenchida.
- `CARTEIRA MAS         CAPRE C12` — campo de cor **vazio** (só espaços de preenchimento, sem nenhum valor).
- `CARTEIRA MAS CAFE    CA/SK C12`, `CARTEIRA MAS PRETA   NSKYN C12`, `CARTEIRA MAS PRETO   CAP/S C12` — variações de material (`CAPRE`, `CA/SK`, `NSKYN`, `CAP/S`), grafia de cor não normalizada (`PRETO`/`PRETA`, mantidas como vieram, sem padronizar concordância de gênero).

**Algoritmo de extração (sub-caso CARTEIRA):**
1. Dividir `xProd` por espaço (colapsando espaços múltiplos) → `tokens`.
2. `tokens[0] = "CARTEIRA"`, `tokens[1] = SEXO` (`MAS` em 100% dos itens testados), `tokens[-1] = COD_COLECAO` (`C12` em 100% dos itens testados), `tokens[-2] = MODELO` (material do couro).
3. `COR` = `tokens[2:-2]` (fatia entre o `SEXO` e o `MODELO`) — normalmente 0 ou 1 token. Quando o campo de cor vem vazio no XML (só espaços), a fatia é vazia e `COR` fica em branco — **não é falha de extração**, é uma característica real do item (nem todo SKU tem colorway definida), `COR_PRECISA_REVISAO = false`.
4. `NOME_PRODUTO` = `CARTEIRA` + `SEXO` + `MODELO` + `COD_COLECAO` (tudo exceto `COR`).
5. `TAMANHO` = **sempre o literal `"UN"`** — carteira não tem tamanho em nenhuma posição do XML (nem `xProd`, nem `cProd`, que aqui nunca tem sufixo `.Tnnn`), categoria "tamanho único" já coberta pela regra geral 4 (**decisão do usuário reconfirmada nesta rodada**: "carteira não tem tamanho também, então define como UN").

`REFERENCIA_PRODUTO` = `cProd` inalterado nos dois sub-casos: no `CINTO`, o segmento final é `T090` (com o prefixo `T`), que **não bate exatamente** com o `TAMANHO` extraído (`090`, sem o `T`) — a regra de corte existente exige igualdade exata, então **não corta**; na `CARTEIRA`, o `cProd` (ex. `H060143751C`) não tem `-`/`_`/`.`, então a regra de corte nunca se aplica de qualquer forma. `CODIGO_BARRAS` = `cEAN` real em 100% dos itens testados (ambos os sub-casos). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `<infAdProd>` não existe em nenhum item testado. `COR_PRECISA_REVISAO` = sempre `false` (posição sempre determinística, sem whitelist, mesmo quando o resultado é vazio).

⚠️ **Colisão crítica com o gatilho do Padrão F:** 88 dos 127 itens `CINTO` terminam no token isolado `TAM` — o mesmo gatilho estrutural usado pelo Padrão F (BR8/mochilas, "tamanho pode ser texto"). Sem uma checagem específica antes do Padrão F, esses itens cairiam no algoritmo de F, que tenta ler a palavra **depois** de `TAM` como `TAMANHO` — não há nada depois (fim da string), o que quebraria a extração.

**Detecção do Padrão AF:** gatilho primário por fornecedor — `NOME_FORNECEDOR = NOVAPELLI IND. COM. IMP. EXP. LTDA`. Dentro do padrão, o `TIPO` (1º token de `xProd`, `CINTO` ou `CARTEIRA`) decide qual dos dois sub-casos aplicar. **Precisa ser checado antes do passo do Padrão F (token isolado `TAM`)**, por causa da colisão descrita acima.

**Validação:** 3 arquivos, 148 itens (127 `CINTO` + 21 `CARTEIRA`). `CINTO` — larguras: `30`, `32`, `35`, `40` (mm); linhas: `SOC`, `ESP`, `REV`; cores: `PRETO`, `CAFE`, `CONHAQ`, `TOST`, `PTO/CAF`; modelos: `CAPRE`, `AFRO`, `VENEZ`, `SMALB`, `ELAST`; tamanhos (via `cProd`): `090`, `095`, `100`, `105`, `110`, `115`. `CARTEIRA` — sexo sempre `MAS`; cores: `PRETO`, `PRETA`, `CAFE`, ou vazio (8 dos 21 itens); modelos: `CAPRE`, `CA/SK`, `NSKYN`, `CAP/S`; `COD_COLECAO` sempre `C12`. `NCM` predominante `42033000` (cinto) e `42023100` (carteira) — 1 item `CINTO` isolado com `NCM = 62171000` (acessório têxtil, não investigado a fundo). `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos 148 itens.

### Caso resolvido: NOVAPELLI IND. COM. IMP. EXP. LTDA — decisão do usuário: `CARTEIRA` usa `TAMANHO = "UN"`

`CARTEIRA` não tem `TAMANHO` em nenhuma posição do XML. O usuário confirmou, de forma proativa, que carteira se enquadra na mesma categoria "tamanho único" já coberta pela **regra geral 4** (que já cita `MEIA`, `BOLSA`, `CARTEIRA`, `BOLA` desde a formulação original da regra): **"carteira não tem tamanho também, então define como 'UN'"**. Esta é a primeira vez que a categoria `CARTEIRA` aparece de fato em um lote de XMLs — reconfirma na prática o que já estava previsto na regra geral 4 desde antes de qualquer exemplo real.

### Caso resolvido: ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA — decisão do usuário: `BOLSA` usa `TAMANHO = "UN"`, e `MALA` também

`BOLSA` (62 itens) e `BOLSA/CARTEIRA` (2 itens) não têm `TAMANHO` em nenhuma posição do XML. O usuário reconfirmou, de forma proativa, que bolsa se enquadra na regra geral 4 (**"bolsa também é TAMANHO 'UN'"**) — mesma categoria já citada na formulação original da regra, agora com o primeiro exemplo real de `BOLSA`. Além disso, apareceu 1 item de `MALA` (mala de viagem), categoria **não** coberta pela lista original da regra geral 4 (que cobre peças sem numeração real, não malas — que normalmente têm tamanhos reais como P/M/G ou litros). Perguntado ao usuário como proceder, já que não há `TAMANHO` em nenhuma posição do XML deste item específico: **sim, `TAMANHO = "UN"` também** — decisão pontual para este item/fornecedor, não uma generalização automática da categoria "mala" (se aparecer outro fornecedor de malas com tamanhos reais disponíveis em outra posição do XML, esse valor real prevalece, mesma exceção (b) já documentada na regra geral 4). Ver Padrão AG acima.

### Padrão AG — marca com marcador de coleção (`ZT`/`CA`), categoria "tamanho único" (ex.: ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA)

Descoberto testando a ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA (bolsas, carteiras e malas femininas): `xProd` segue uma estrutura simples e determinística — `TIPO` (+ `Feminina` quando presente) + nome da marca `Zeti` + um marcador de 2 letras (`ZT` para bolsas, `CA` para carteiras) + um código numérico de 4 dígitos + `COR`, sempre **sem nenhum TAMANHO** em nenhuma posição do XML (categoria "tamanho único", já coberta pela regra geral 4):

```
[TIPO] [Feminina]? Zeti [ZT|CA] [COD_MODELO] [COR...]
```

Exemplos:
- `Bolsa Feminina Zeti  ZT 1950 Preto` — cor simples.
- `Bolsa Feminina Zeti  ZT 1931 Preto Nylon` / `Bolsa Feminina Zeti  ZT 1931 Caqui Nylon` — mesmo `COD_MODELO` (`1931`), duas colorways (`Preto`, `Caqui`), ambas com o sufixo `Nylon` colado (material do modelo, não uma cor de 2 palavras — confirmado porque o sufixo se repete idêntico nas duas cores do mesmo modelo).
- `Carteira Feminina Zeti CA 1901 Preta` — mesmo esquema, marcador `CA` em vez de `ZT`.
- `Bolsa/Carteira Zeti  ZT 1958 Caqui` — `TIPO` composto (`Bolsa/Carteira`, kit combinado), sem `Feminina`.
- `Bolsa Feminina Zeti  ZT 2005 Prata Velho` — cor genuína de 2 palavras (`Prata Velho`, "prata velho" = tom de prata envelhecido), sem material colado.

⚠️ **`COR` pode ter 1 ou 2 palavras, e a extração não tenta separar material de cor quando há 2 palavras** (ex.: `Preto Nylon`, `Caqui Nylon`, `Preto PU`, `Mauve Nylon`) — mesma cautela do resto do documento: sem um marcador estrutural confiável para diferenciar "cor de 2 palavras genuína" (`Prata Velho`) de "cor de 1 palavra + material" (`Preto Nylon`), o valor inteiro depois do `COD_MODELO` é gravado como `COR`, sem dividir.

**Algoritmo de extração (Padrão AG):**
1. Localizar o marcador `ZT` ou `CA` em `xProd` (sempre seguido de um código numérico de 4 dígitos).
2. `COD_MODELO` = os 4 dígitos logo após o marcador.
3. `COR` = tudo que sobrar depois do `COD_MODELO` (1 ou 2 palavras, ver observação acima) — dividido por `/` em `COR_1`/`COR_2`/`COR_3` se algum dia aparecer bicolor (não visto nos itens testados).
4. `NOME_PRODUTO` = tudo antes da `COR` (`TIPO`, `Feminina` quando presente, `Zeti`, marcador, `COD_MODELO`).
5. `TAMANHO` = **sempre `"UN"`** (regra geral 4 — `BOLSA`/`CARTEIRA` são categorias "tamanho único" já cobertas pela regra; reconfirmado pelo usuário nesta rodada especificamente para `BOLSA`).

⚠️ **Item isolado sem marcador `ZT`/`CA` — `MALA`:** 1 dos 68 itens (`Mala de bordo c/ rodinhas ABS 8079`) é uma mala de viagem, categoria estruturalmente diferente (sem "Zeti", sem marcador, sem cor em nenhuma posição — `ABS` é o material da carcaça, não uma cor). **Decisão do usuário: `TAMANHO = "UN"` também**, mesmo mala não estando na lista original da regra geral 4 (que cobre tipicamente peças sem numeração real) e mesmo não havendo nenhuma fonte de tamanho no XML — decisão específica para este item/fornecedor, não uma generalização automática da regra 4 para a categoria "mala" como um todo. `NOME_PRODUTO` = `xProd` sem alteração (não há posição segura para remover nada). `COR` = vazia, `COR_PRECISA_REVISAO = true` (ausência não confirmada como característica real do item, diferente do caso `BOLSA`/`CARTEIRA` sem cor que não ocorreu aqui).

`REFERENCIA_PRODUTO` = `cProd` inalterado (sempre `P` + dígitos, ex. `P2086`, sem `-`/`_`/`.` — a regra de corte nunca se aplica; sem relação numérica com o `COD_MODELO` do `xProd`). `CODIGO_BARRAS` = **sempre vazio** (`cEAN` é sempre o literal `SEM GTIN` em 100% dos itens testados). `VALOR_PRODUTO_DESCONTO` = `0.00` (`<vDesc>` não existe em nenhum `<det><prod>` testado). `<infAdProd>` não existe em nenhum item testado. `COR_PRECISA_REVISAO` = `false` para `BOLSA`/`CARTEIRA`/`BOLSA-CARTEIRA` (posição sempre determinística), `true` só para o item `MALA` isolado (ver acima).

**Detecção do Padrão AG:** gatilho primário por fornecedor — `NOME_FORNECEDOR = ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA`. Sinal estrutural de apoio: `xProd` contém o marcador `ZT`/`CA` seguido de 4 dígitos, ou (para o item `MALA`) simplesmente o fato de não haver `<infAdProd>` e o `xProd` não terminar de forma padronizada. **Precisa ser checado antes do fallback genérico**, porque nenhum item deste fornecedor tem `<infAdProd>` — os itens `BOLSA`/`CARTEIRA` (terminam em palavra, não número) cairiam no Padrão C e falhariam por falta de `infAdProd`; o item `MALA` (termina em número, `8079`) cairia incorretamente no Padrão B genérico, capturando `COR = "ABS"` (material, não cor) e `TAMANHO = "8079"` (código de modelo, não tamanho).

**Validação:** 3 arquivos, 68 itens (32 + 21 + 15) — 62 `BOLSA`, 3 `CARTEIRA`, 2 `BOLSA/CARTEIRA`, 1 `MALA`. Cores vistas: `Preto`, `Preta`, `Dourado`, `Caqui`, `Marrom`, `Gray`, `Mauve`, `Bege`, `Militar`, `Navy`, `Taupe`, `Prata Velho`, `Caqui Nylon`, `Preto Nylon`, `Mauve Nylon`, `Preto PU` — inglês, português e nomes compostos misturados, gravados como vieram, sem tradução/normalização. `CODIGO_BARRAS` vazio e `TAMANHO = "UN"` em 100% dos 68 itens. NCM por categoria: `42022210` (`BOLSA`/`BOLSA-CARTEIRA`), `42023200` (`CARTEIRA`), `42021210` (`MALA`).

## Regra geral para COR/TAMANHO ausentes

Regras gerais, válidas para **qualquer fornecedor**, aplicadas antes de tentar detectar um dos padrões A-Y:

1. **`CODIGO_BARRAS` vazio quando não há GTIN real.** Sempre que `<cEAN>` vier com o literal `SEM GTIN` (ou, de forma mais geral, qualquer valor que não seja um código de barras válido), `CODIGO_BARRAS` é gravado **vazio** — nunca a string `"SEM GTIN"` literal.
2. **Falta de COR não é motivo de rejeição.** Se `TAMANHO` for extraível mas `COR` não (nenhuma posição padronizada traz essa informação), o item **é processado normalmente** — `COR` (e `COR_1`/`COR_2`/`COR_3`) ficam vazios e a linha é marcada com `COR_PRECISA_REVISAO = true`. ⚠️ Decisão revista nesta rodada — antes disso, falta de COR também levava à rejeição do fornecedor inteiro (ver Padrão I acima, CALCADOS FERRACINI, reclassificado de "rejeitado" para processável).
3. **Falta de TAMANHO exige decisão do usuário, caso a caso — exceto para as categorias cobertas pela regra 4.** Se não houver nenhuma posição padronizada com `TAMANHO` (nem `xProd`, nem `infAdProd`) e o produto **não** se enquadrar na regra 4 abaixo, a extração **não decide sozinha** se deve processar sem tamanho ou rejeitar o fornecedor — é preciso perguntar ao usuário e registrar a decisão tomada para aquele fornecedor especificamente. Não se tenta inferir tamanho por vias indiretas fora das regras já documentadas (ex.: agrupamentos de quantidade, texto de resolução fiscal).
4. **Produtos "tamanho único" (MEIA, BOLSA, CARTEIRA, BOLA e categorias equivalentes) sempre usam `TAMANHO = "UN"` quando o tamanho não é informado.** ⚠️ **Regra geral confirmada pelo usuário, e reconfirmada explicitamente em 2026-07-10** — deixa de ser uma decisão caso a caso por fornecedor (como era antes, ver "Casos resolvidos" da CONDE DUCK e INDITEC abaixo, que continuam documentados por valor histórico) e passa a ser aplicada **a qualquer fornecedor, de forma automática e sem perguntar ao usuário**, sempre que o produto for identificável como uma dessas categorias (pelo `TIPO` no início do `xProd`, ex. `MEIA`, `BOLSA`, `CARTEIRA`, `BOLA`, ou por uma categoria já reconhecida como equivalente, ex. `BONE`) **e** nenhuma posição do XML (`xProd`, `infAdProd`) trouxer um `TAMANHO` real. Diferença importante em relação à regra 3: aqui não é preciso perguntar ao usuário — o padrão já é `"UN"` diretamente, mesmo que `CARTEIRA`/`BOLA` (ou outra categoria nova claramente equivalente a "peça vestível/utilizável sem numeração", no mesmo espírito de MEIA/BOLSA/BONE) ainda não tenham aparecido em nenhum arquivo testado até agora — a regra vale **preventivamente** para a categoria, não só para os exemplos já vistos. Isso **não se aplica** a: (a) itens de ponto de venda/material de PDV sem noção alguma de tamanho (ex. `ARQUIBANCADA`, `CUBO`, `BANDEJA DE VITRINE` no Padrão L), que continuam com `TAMANHO` vazio (não são um "tamanho único", são um item sem essa dimensão); (b) produtos onde um `TAMANHO` real já foi extraído de alguma posição do XML (nesse caso, o valor real prevalece, `"UN"` só entra quando não há nada).

### Caso resolvido: CALCADOS BEBECE LTDA — decisão do usuário: processar sem TAMANHO

`<xProd>` da BEBECE não contém `TAMANHO` em nenhuma posição padronizada — é uma descrição técnica truncada em ~120 caracteres (ex.: `BOTA OVER KNEE T4414-230 / CAMURCA STRETCH PRETO / NAPA BRUMAS PRETO / FORRO CACHAREL PRETO / FORRO CACHAREL PRETO / CAM`), e o `<infAdProd>` traz pares que parecem tamanho/quantidade (`33/1 34/1 35/1 36/2 37/2 38/1 39/1`), mas sem separador claro de "isto é tamanho" — decisão: não interpretar essa lista. Perguntado ao usuário se deveria processar mesmo assim sem `TAMANHO`: **sim, processar sem TAMANHO**. Ver Padrão J abaixo, que documenta a extração resultante (COR normalmente extraída, TAMANHO sempre em branco).

### Caso resolvido: CONDE DUCK IND DE MEIAS LTDA — decisão do usuário: TAMANHO = "UN"

O `xProd` da CONDE DUCK tem um indicador de tamanho de vestuário (`P`/`M`/`G`/`GG`/`PP`/`U`/`0`) grudado de forma inconsistente a um código numérico — sem posição fixa (prefixo, sufixo, ou isolado entre hifens) e às vezes ausente mesmo dentro da mesma família de produto. Perguntado ao usuário como proceder: **"quando for meia, não tem tamanho definido, aí coloca o tamanho 'UN'"** — ou seja, não tentar decodificar essas letras; gravar sempre o literal `"UN"`. Ver Padrão N abaixo. ⚠️ Essa decisão, inicialmente específica da CONDE DUCK, foi **generalizada nesta rodada** como regra 4 da seção acima — vale para qualquer fornecedor de meia (e categorias equivalentes: bolsa, carteira, bola), não só este.

### Caso resolvido: GRENDENE S/A — decisão do usuário: processar sem TAMANHO

`TAMANHO` não existe em nenhuma posição do XML (o `xProd` é uma descrição fiscal truncada, sem número no fim; não existe `<infAdProd>`; e o `cProd` não tem um sufixo de tamanho confiável — os mesmos códigos de 4 dígitos se repetem entre modelos com contagens de tamanho diferentes). Perguntado ao usuário se deveria processar mesmo assim: **sim, processar sem TAMANHO** (mantendo a `COR` truncada como veio). Ver Padrão R abaixo.

### Caso resolvido: IND E COM DE CALC TRENTO LTDA — decisão do usuário: processar sem COR e sem TAMANHO

Nem `COR` nem `TAMANHO` existem em nenhuma posição do XML (descrição fiscal genérica, `cEAN` sempre `SEM GTIN`, sem `<infAdProd>`). Perguntado ao usuário se deveria processar mesmo assim: **sim, processar sem COR e sem TAMANHO**. Ver Padrão S abaixo.

### Caso resolvido: INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI — decisão do usuário: TAMANHO = "UN"

`xProd` é sempre literalmente a palavra `BONE` (boné), vendido a granel, sem cor nem tamanho em nenhuma posição. Perguntado ao usuário como proceder, usando o mesmo raciocínio já aplicado às meias da CONDE DUCK (produto de tamanho único): **sim, `TAMANHO = "UN"`**. Ver Padrão T abaixo. ⚠️ Confirma o mesmo padrão da CONDE DUCK — boné também se encaixa na regra geral 4 (categoria "tamanho único"), reforçando que a regra vale além de meia/bolsa/carteira/bola.

### Caso resolvido: NOVOPE CALCADOS LTDA — decisão do usuário: processar sem TAMANHO

`xProd` da NOVOPE não contém `TAMANHO` em nenhuma posição (não termina em número, não existe `<infAdProd>` preenchido, e `cProd` é um código curto sem relação com tamanho). Cada `<det>` representa uma grade/caixa fechada (`qCom` = 12 ou 15 pares), mesmo cenário da CALCADOS BEBECE. Perguntado ao usuário se deveria processar mesmo assim sem `TAMANHO`: **sim, processar sem TAMANHO**. Ver Padrão Z acima.

### Detecção automática do padrão

A extração decide qual padrão usar seguindo esta ordem:

1. **Se `NOME_FORNECEDOR` indicar Havaianas/ALPARGATAS (ou o `xProd` contiver a palavra `HAVAIANAS`)** → Padrão E (ver regras acima — sem TAMANHO, limpeza de prefixo, `FC` ou whitelist de modelo para achar a COR).
2. **Senão, se `NOME_FORNECEDOR = IND E COM DE CALC TRENTO LTDA`** → Padrão S (ver regras acima — sem COR nem TAMANHO. **Precisa ser checado antes do passo seguinte**, porque o `xProd` deste fornecedor contém o token `SOLA` incidentalmente, o que colidiria com o gatilho do Padrão D).
3. **Senão, se `NOME_FORNECEDOR = INDUSTRIA E COMERCIO LEJON LTDA`** (ou, estruturalmente, o 1º segmento do `cProd` — delimitado por `.` — aparece literalmente dentro de `xProd`) → Padrão W (ver regras acima — `COD_MODELO` como âncora dentro do `xProd`, bloco de cor bruto entre o `COD_MODELO` e o `TAMANHO`. **Precisa ser checado antes do passo seguinte**, porque o `xProd` deste fornecedor também contém o token `SOLA`/`SOLADO` incidentalmente, o que colidiria com o gatilho do Padrão D).
4. **Senão, se `NOME_FORNECEDOR = KL INDUSTRIA E COMERCIO LTDA`** → aplicar diretamente a regra genérica do Padrão B (passo 35 abaixo), **não** o Padrão D — ver "Fornecedor 29 — KL INDUSTRIA E COMERCIO LTDA" mais abaixo. Alguns itens deste fornecedor têm o token isolado `SOLA` (ex.: `SOLA TR`) incidentalmente, o que colidiria com o gatilho do passo mais abaixo; a `COR` continua confiável na posição padrão do Padrão B (penúltimo token antes do `TAMANHO`), então não há necessidade de um algoritmo próprio.
5. **Senão, se `NOME_FORNECEDOR` contiver `LONG FEET`** → Padrão X (ver regras acima — marcadores explícitos `TAM [tamanho]` e `COR [cor]` no próprio `xProd`, categoria de produtos de cuidado para calçados. **Precisa ser checado antes do passo seguinte**, porque vários itens deste fornecedor contêm o token isolado `TAM`, o que colidiria com o gatilho do Padrão F).
6. **Senão, se `xProd` contiver o token `SOLA`** → Padrão D (ver regras acima).
7. **Senão, se `NOME_FORNECEDOR = NOVAPELLI IND. COM. IMP. EXP. LTDA`** → Padrão AF (ver regras acima — `xProd` truncado em largura fixa; sub-caso `CINTO` (TAMANHO só no `cProd`, via `.Tnnn`) ou `CARTEIRA` (TAMANHO sempre `"UN"`), decidido pelo 1º token de `xProd`. **Precisa ser checado antes do passo seguinte (Padrão F)**, porque a maioria dos itens `CINTO` deste fornecedor termina no token isolado `TAM` (sem valor depois, truncado) — o mesmo gatilho do Padrão F, cujo algoritmo tentaria ler uma palavra inexistente depois de `TAM` e quebraria).
8. **Senão, se `xProd` contiver o token isolado `TAM`** → Padrão F (ver regras acima — tamanho pode ser texto, ex. `UNICO`).
9. **Senão, se `xProd` casar com `Tam:\d+$` no fim (marcador colado, sem espaço, diferente do `TAM` isolado do Padrão F)** → Padrão P (ver regras acima — campos delimitados por `-`, `COR` e `CODIGO_COR` em posição fixa).
10. **Senão, se `xProd` começar com um token numérico seguido da palavra `CALCADOS`** → Padrão G (ver regras acima — descarta o código numérico e a palavra de material antes da cor).
11. **Senão, se `xProd` começar com token numérico seguido de um `TIPO` de calçado que não seja `CALCADOS`, e contiver um token que casa com `WC-\d+`** → sub-caso do Padrão H com marcador de código interno (ver "Sub-caso DILLY NORDESTE" acima — tudo depois do `WC-\d+` até o `TAMANHO` é o bloco de cor bruto, dividido por `/` em segmentos que podem ter mais de uma palavra cada; o token numérico logo antes do `WC-\d+` é `CODIGO_COR`, descartado).
12. **Senão, se `NOME_FORNECEDOR = STAMPA ARTEFATOS DE COURO LTDA`** (ou, estruturalmente, `xProd` termina no literal ` UNI`, espaço + `UNI` no fim absoluto) → Padrão AE (ver regras acima — categoria bolsas/mochilas, `TAMANHO` é sempre o literal `UNI`, bloco de cor via última posição espaço-separada. **Precisa ser checado antes do passo seguinte (Padrão H)**, porque vários itens deste fornecedor têm `COD_MODELO` puramente numérico no início — o mesmo gatilho estrutural do Padrão H (`token numérico + TIPO ≠ CALCADOS`) capturaria esses itens incorretamente, e o algoritmo de H espera que o último token seja sempre numérico, o que quebra ao encontrar `UNI`).
13. **Senão, se `NOME_FORNECEDOR = CALCADOS BOTTERO LTDA`** (ou, estruturalmente, `xProd` começa com token numérico seguido de um `TIPO` de calçado que não seja `CALCADOS`) → Padrão H (ver regras acima — cor via whitelist, pode ter múltiplas palavras por lado do `/`; ver também "Sub-caso STAMPA" acima para blocos de cor com 3+ segmentos).
14. **Senão, se `xProd` terminar em `\s+\d+-$` (número seguido de `-` no fim absoluto) e contiver o `cEAN` embutido no meio do texto** → Padrão I (ver regras acima — descrição fiscal fixa, sem COR).
15. **Senão, se `NOME_FORNECEDOR = SAPATARIA BERTELLI IND. E COMERCIO`** (ou, estruturalmente, `xProd` começa com o literal `SAPATO MASC` e não contém nenhum `/`) → Padrão AB (ver regras acima — `COR` = última palavra de `xProd`, sem TAMANHO. **Precisa ser checado antes do passo seguinte (Padrão J)**, porque o `infAdProd` deste fornecedor também casa com o padrão estrutural `\d{2}/\d+` usado como gatilho do Padrão J — sem essa checagem específica, cairia no algoritmo do Padrão J, que espera `xProd` dividido por `" / "` (inexistente aqui) e devolveria `COR` vazia com `COR_PRECISA_REVISAO = true` incorretamente).
16. **Senão, se `infAdProd` contiver 2+ pares `\d{2}/\d+` e/ou `NOME_FORNECEDOR = CALCADOS BEBECE LTDA`** → Padrão J (ver regras acima — sem TAMANHO, COR pela posição fixa em `segmentos[1]`).
17. **Senão, se `NOME_FORNECEDOR = CALCADOS PEGADA NORDESTE LTDA`** (ou, estruturalmente, `xProd` termina em `BOTA FEMININA`/descrição genérica sem cor, e `infAdProd` contém o padrão `\d{2}/\d+\s*GTIN`) → Padrão K (ver regras acima — TAMANHO vem do `infAdProd`, COR sempre vazia).
18. **Senão, se `NOME_FORNECEDOR` contiver `CAMBUCI`** (ou, estruturalmente, `xProd` termina em ` UN` precedido de uma faixa `\d{2}-\d{2}`, ou contém o padrão `T [-]?(U|P|M|G|GG)$`) → Padrão L (ver regras acima — categoria não-calçado, tamanho pode ser faixa, cor em sigla traduzida quando reconhecida).
19. **Senão, se `NOME_FORNECEDOR` contiver `CLASSE INDUSTRIA`** (ou, estruturalmente, `xProd` casa com `^[A-Z0-9]+ - `) → Padrão M (ver regras acima — código longo redundante no início, `CODIGO_COR` alfanumérico misto **antes** da COR).
20. **Senão, se `NOME_FORNECEDOR = CONDE DUCK IND DE MEIAS LTDA` ou `NOME_FORNECEDOR` contém `LUPO`** (ou, estruturalmente, `xProd` começa com `MEIA`/`KIT DE MEIAS`, **sem diferenciar maiúsculas/minúsculas**) → Padrão N (ver regras acima — COR sempre vazia, TAMANHO sempre `"UN"`, `xProd` mantido sem alteração no `NOME_PRODUTO`. Para a LUPO, o gatilho por `NOME_FORNECEDOR` também cobre o item isolado `CUECA`, que não tem o prefixo `MEIA`).
21. **Senão, se `NOME_FORNECEDOR = DEMOCRATA CALCADOS E ARTEFATOS DE COURO LTDA`** (ou, estruturalmente, `infAdProd` casa com `.+ - \d+#$`) → Padrão O (ver regras acima — COR completa e TAMANHO vêm do `infAdProd`, `xProd` mantido sem alteração no `NOME_PRODUTO` porque a cor embutida nele pode vir truncada).
22. **Senão, se `NOME_FORNECEDOR = FISIA COMERCIO DE PRODUTOS ESPORTIVOS SA`** (ou, estruturalmente, `cProd` casa com `^[A-Z]{2}\d{4}-\d{3}-\d+(\.\d+)?$` e não existe `<infAdProd>`) → Padrão Q (ver regras acima — `TAMANHO` vem do `cProd`, pode ser decimal, `COR` sempre vazia. **Precisa ser checado antes do passo 35 (fallback genérico)**, senão cairia no Padrão C e falharia por não haver `infAdProd`).
23. **Senão, se `NOME_FORNECEDOR` contiver `GRENDENE`** → Padrão R (ver regras acima — `COR` truncada sem correção possível, `TAMANHO` sempre vazio, decisão do usuário).
24. **Senão, se `NOME_FORNECEDOR = INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI`** (ou, estruturalmente, `xProd` é exatamente a palavra `BONE`) → Padrão T (ver regras acima — produto a granel sem variação, `TAMANHO = "UN"`, `COR` sempre vazia).
25. **Senão, se `NOME_FORNECEDOR = INDUSTRIA DE CALCADOS GONCALVES LTDA.`** (ou, estruturalmente, `cProd` casa com `_\d+_\d+$` e `xProd` contém a palavra `TENIS`) → Padrão U (ver regras acima — limpeza de prefixo duplicado a partir da última ocorrência de `TENIS`, bloco de cor bruto entre `TENIS` e `TAMANHO` sem marcador nem whitelist, `NOME_PRODUTO` = só `TENIS`. **Precisa ser checado antes do passo 35**, senão o `xProd` (que termina em número) cairia incorretamente no Padrão A/B genérico, perdendo parte do bloco de cor).
26. **Senão, se `NOME_FORNECEDOR = INDUSTRIA E COM DE CALC SYG STAR LTDA`** (ou, estruturalmente, `cProd` casa com `^REF\.\d+\.\d+\.\d+$` e `xProd` começa com token numérico seguido de `" - TENIS"`) → Padrão V (ver regras acima — `cProd` delimitado por `.`, marcador `EM` opcional, cor via whitelist com modificador prefixado `TODO`. **Precisa ser checado antes do passo seguinte**, porque o `xProd` deste fornecedor também contém o token isolado `EM`, o que colidiria com o gatilho do sub-caso CALCADOS MARTE).
27. **Senão, se `NOME_FORNECEDOR = MACBOOT INDUSTRIA E COMERCIO DE CALCADOS`** (ou, estruturalmente, `xProd` casa com `- \S+-\S+ - N\.\s*\d+$` no fim) → Padrão Y (ver regras acima — código SKU interno + marcador `N.` para o TAMANHO, cor via whitelist com modificador prefixado `OIL`. **Precisa ser checado antes do passo 35 (fallback genérico)**, porque o `xProd` deste fornecedor termina em número (o `TAMANHO` do marcador `N.`), o que cairia incorretamente no Padrão A/B genérico, capturando `COR = "N."`).
28. **Senão, se `NOME_FORNECEDOR = VIA VIP CALCADOS LTDA`** (ou, estruturalmente, `xProd` termina no padrão ` REF [valor]$`, onde `[valor]` bate exatamente com o `cProd` do item) → Padrão AD (ver regras acima — sufixo `REF [cProd]` descartável, `TAMANHO` = penúltima palavra antes do sufixo, `COR` = antepenúltima. **Precisa ser checado antes do passo seguinte (sub-caso CALCADOS MARTE)**, porque **100% dos itens deste fornecedor contêm o token isolado `EM`** — sem essa checagem específica, cairiam no sub-caso MARTE, que capturaria `COR = "C/"` ou `"COM"` e `TAMANHO` = o próprio código `REF [cProd]`, corrompendo os dois campos).
29. **Senão, se `xProd` contiver o token isolado `EM`** → sub-caso do Padrão B com marcador de material (ver "Fornecedor 4 — CALCADOS MARTE" — `MATERIAL` = palavra após `EM`, `COR` = palavra após o `MATERIAL`, tudo entre `COR` e `TAMANHO` é `CODIGO_COR` descartado).
30. **Senão, se `xProd` contiver a frase fixa `CHUCK TAYLOR ALL STAR`** → sub-caso do Padrão B com marcador de modelo fixo (ver "Fornecedor 17 — COOPERSHOES" — tudo depois da frase (+ `LIFT`, se presente) até o `TAMANHO` é o bloco de cor bruto, dividido por `/` em segmentos que podem ter mais de uma palavra cada).
31. **Senão, se `NOME_FORNECEDOR = NOVOPE CALCADOS LTDA`** (ou, estruturalmente, `xProd` contém a frase fixa `TENIS INFANTIL`, `cEAN` é sempre `SEM GTIN` e não existe `<infAdProd>` preenchido) → Padrão Z (ver regras acima — prefixo `COD_MODELO_COD_COR` descartável, `COR` = tudo depois do `MATERIAL` até o fim de `xProd`, sem TAMANHO. **Precisa ser checado antes do passo seguinte (fallback genérico)**, porque `xProd` nunca termina em número — sem essa checagem específica cairia no Padrão C e falharia por não haver `infAdProd`, mesmo risco já descrito para o Padrão Q/FISIA).
32. **Senão, se `NOME_FORNECEDOR = PUMA SPORTS LTDA`** (ou, estruturalmente, `xProd` começa com o literal `CALCADOS-`, hífen colado ao nome do modelo, sem espaço) → Padrão AA (ver regras acima — cor separada por `-` dentro do próprio `xProd`, ponto de corte entre modelo e 1ª cor via marcador `BDP` ou última ocorrência de `Puma`/`PUMA`. **Precisa ser checado antes do passo seguinte (fallback genérico)**, porque `xProd` termina em número — sem essa checagem específica, o algoritmo genérico separaria por espaço e cortaria o bloco de cor multi-hífen no lugar errado).
33. **Senão, se `NOME_FORNECEDOR = SKECHERS DO BRASIL CALCADOS LTDA`** (ou, estruturalmente, `cProd` casa com 4 segmentos delimitados por `-`, o último puramente numérico, e não existe `<infAdProd>`) → Padrão AC (ver regras acima — `TAMANHO` e `COD_COR` vêm do `cProd`, `COR` sempre vazia por falta de nome legível. **Precisa ser checado antes do passo seguinte (fallback genérico)**, porque `xProd` nunca termina em número — sem essa checagem específica cairia no Padrão C e falharia por não haver `infAdProd`, mesmo risco já descrito para os Padrões Q/FISIA e Z/NOVOPE).
34. **Senão, se `NOME_FORNECEDOR = ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA`** (ou, estruturalmente, `xProd` contém o marcador `ZT`/`CA` seguido de 4 dígitos) → Padrão AG (ver regras acima — `COR` = tudo depois do `COD_MODELO`, `TAMANHO` sempre `"UN"`. **Precisa ser checado antes do passo seguinte (fallback genérico)**, porque nenhum item deste fornecedor tem `<infAdProd>` — os itens `BOLSA`/`CARTEIRA` cairiam no Padrão C e falhariam por falta de `infAdProd`, e o item `MALA` (único que termina em número) cairia no Padrão B genérico, capturando `COR = "ABS"` e `TAMANHO` = o código de modelo).
35. Senão, separe `xProd` por espaço → `partes`. Veja `partes[-1]` (última palavra):
    - **Se for numérica** → o tamanho está no próprio `xProd` (Padrão A ou B). `TAMANHO` = `partes[-1]`.
      - Veja `partes[-2]` (penúltima palavra): se também for numérica → Padrão A (`COR` = `partes[-3]`, código de cor descartado, `NOME_PRODUTO` = `partes[0..-4]`). Senão → Padrão B (`COR` = `partes[-2]` — aplicando a regra estendida de qualificador de 2 palavras quando necessário, ver sub-caso KIDY acima —, `NOME_PRODUTO` = `partes[0..-3]`).
    - **Se não for numérica** → não há cor/tamanho no `xProd` (Padrão C). Buscar em `<infAdProd>` com a regra descrita acima. `NOME_PRODUTO` = `xProd` sem alteração.
36. `CODIGO_COR`, quando existir (Padrão A, dentro do Padrão C, o material do solado no Padrão D, o código de modelo/material no Padrão G, os códigos por segmento de cor no Padrão H, o código alfanumérico antes da COR no Padrão M, o código numérico no 4º campo do Padrão P, o código de cor numérico no Padrão Q (mantido em `REFERENCIA_PRODUTO`, não descartado), o código de cor numérico no Padrão V (mantido em `REFERENCIA_PRODUTO`, não descartado), o código de cor numérico no Padrão W (mantido em `REFERENCIA_PRODUTO`, não descartado), o código SKU interno no Padrão Y (descartado, sem relação com `cProd`), o código de cor alfabético no Padrão AC (mantido em `REFERENCIA_PRODUTO`, não descartado), ou os códigos por segmento de cor nos sub-casos COOPERSHOES, DILLY NORDESTE e STAMPA), é sempre descartado — não vira campo em nenhum dos trinta e três padrões.

Campos finais extraídos de cada produto:

| Campo | Origem |
|---|---|
| REFERENCIA_PRODUTO | `cProd`, sem o último segmento **apenas se ele bater com o TAMANHO extraído** (ver seção acima) — delimitador é `-` na maioria dos padrões, `_` no Padrão U, `.` nos Padrões V, W e AD. **No Padrão Y, `cProd` é sempre idêntico ao `cEAN`** (GTIN completo), então nunca é cortado e nunca se repete entre tamanhos do mesmo modelo/cor — único padrão com esse comportamento. **No Padrão Z, `cProd` é um código curto sem `-`/`_`/`.` e sem relação com o prefixo composto do `xProd`**, então nunca é cortado (e não há TAMANHO para validar cruzado, já que este padrão não extrai TAMANHO). **No Padrão AA, `cProd` é um código numérico contínuo sem `-`**, então também nunca é cortado, **mesmo batendo 100% com o TAMANHO nos 2 últimos dígitos** — a regra de corte exige o delimitador `-`, que não existe aqui. **No Padrão AB, `cProd` também é um código numérico curto sem `-`** (e sem TAMANHO extraído para validar cruzado), mas com uma particularidade única: **não distingue cor** — o mesmo `cProd` pode se repetir em linhas de cores diferentes do mesmo modelo (ver "Padrão AB" acima). **No Padrão AC, `cProd` tem sempre 4 segmentos delimitados por `-`**, e o `TAMANHO` extraído dali mesmo (último segmento) sempre bate, então o corte **sempre** se aplica → resultado `PREFIXO_LINHA-COD_ESTILO-COD_COR` (código de cor alfabético mantido, não descartado, mesma decisão do Padrão Q/V/W). **No Padrão AD, `cProd` usa `.` como delimitador e sempre é cortado** (o `TAMANHO` extraído do `xProd` sempre bate com o último segmento) → resultado `[MODELO_COD].[COD_COR]`. **No Padrão AE, `cProd` é sempre numérico curto (ou o `COD_MODELO` do `xProd` é alfanumérico com `-`, mas isso não afeta o `cProd` real), sem `-`/`_`/`.`**, então nunca é cortado — sem relação com o `COD_MODELO` do `xProd`. **No Padrão AF, `cProd` nunca é cortado em nenhum dos dois sub-casos**: no `CINTO`, o último segmento (`T090`) não bate exatamente com o `TAMANHO` extraído (`090`, sem o `T`), então a regra de corte não se aplica; na `CARTEIRA`, o `cProd` não tem `-`/`_`/`.`. **No Padrão AG, `cProd` é sempre `P` + dígitos, sem delimitador**, então nunca é cortado — sem relação numérica com o `COD_MODELO` do `xProd` |
| CODIGO_BARRAS | `cEAN` — **vazio quando `cEAN` não é um GTIN real** (ex.: literal `SEM GTIN`), regra geral aplicada a qualquer fornecedor (ver "Regra geral para COR/TAMANHO ausentes") — **sempre vazio nos Padrões Z, AB e AG** (100% dos itens testados vêm com `SEM GTIN`); **sempre real (GTIN-13) nos Padrões AA, AC, AD, AE e AF** |
| **NOME_PRODUTO** | `xProd` sem cor/código de cor/tamanho (Padrões A/B), `xProd` sem alteração (Padrões C, K, N, O, R, S e AC — em todos porque a cor que aparece no `xProd` (quando existe) não é confiável, não há posição segura para separar nada, ou simplesmente não existe cor/tamanho nenhum no `xProd`), `xProd` sem o bloco `SOLA [material] [COR] [SLT FORRADO]? [TAMANHO]` (Padrão D), `xProd` (já limpo do prefixo duplicado) sem o bloco de COR identificado via `FC`/whitelist de modelo (Padrão E), `xProd` sem o bloco `[PERCENTUAL]% [MATERIAL] [COR] TAM [TAMANHO]` (Padrão F), `xProd` sem o código numérico inicial e sem o `[MATERIAL] [COR] [TAMANHO]` final (Padrão G), `xProd` sem o código numérico inicial e sem o bloco de cor identificado via whitelist (Padrão H — material permanece no nome), `xProd` sem o `cEAN` embutido e o `TAMANHO-` final (Padrão I), `segmentos[0]` sem o código de modelo/cor (Padrão J), `xProd` sem a faixa/código de tamanho e sem a sigla de cor (Padrão L), `xProd` sem o código longo inicial e sem `[CODIGO_COR] [COR] [TAMANHO]` (Padrão M), o 2º campo (delimitado por `-`) sem alteração (Padrão P), `xProd` sem alteração (Padrão Q — não há cor/tamanho pra remover, tamanho vem do `cProd`), sempre o literal `"BONE"` (Padrão T), só a palavra `TENIS` isolada, depois de limpar o prefixo duplicado e o bloco de cor (Padrão U), `xProd` sem o código de modelo inicial e sem o bloco de cor identificado via whitelist, mantendo `EM [MATERIAL]` quando presente (Padrão V), `xProd` sem o `COD_MODELO` (âncora vinda do `cProd`) e sem o bloco de cor final, mantendo todo o texto técnico intermediário (`EM MATERIAL`, `SOLA/SOLADO MATERIAL`, marcador de referência) sem tentar separá-lo (Padrão W), `xProd` sem os marcadores explícitos `TAM [tamanho]`/`COR [cor]` quando presentes, senão sem alteração (Padrão X), `xProd` sem o `SKU_INTERNO` e sem o bloco de cor identificado via whitelist, mantendo o código de modelo (mesmo "quebrado") e a descrição de material (Padrão Y), `xProd` sem o prefixo `COD_MODELO_COD_COR` e sem o bloco de cor final, mantendo `C/BORDADO`/marca própria e o `MATERIAL` quando presentes (Padrão Z), `xProd` sem o prefixo `CALCADOS-` e sem o bloco de cor identificado via marcador `BDP`/última ocorrência de `Puma`/`PUMA` (Padrão AA), `xProd` sem a última palavra (Padrão AB — mantém `SAPATO MASC`, código de modelo, `INF` e a linha/coleção), `xProd` sem o sufixo `REF [cProd]` e sem a `COR` (penúltima palavra), mantendo `EM MATERIAL` e toda a descrição técnica (Padrão AD), `xProd` sem o `COD_MODELO` inicial e sem o bloco de cor final, mantendo `TIPO` e o(s) `MATERIAL`(is) com `/` (Padrão AE), `xProd` sem a `COR` (posição fixa, ver Padrão AF), mantendo o resto dos campos de largura fixa (`LARGURA`/`LINHA`/`MODELO` no `CINTO`, `SEXO`/`MODELO`/`COD_COLECAO` na `CARTEIRA`), ou `xProd` sem a `COR` (tudo depois do `COD_MODELO` que segue o marcador `ZT`/`CA`), mantendo `TIPO`, `Feminina`, `Zeti` e o marcador+código (Padrão AG) |
| **NOME_PRODUTO_ORIGINAL** | `xProd` **bruto, tal como veio no XML**, sem nenhuma limpeza — mantido lado a lado com o `NOME_PRODUTO` limpo para conferência/auditoria |
| **COR** | conforme o padrão detectado (`xProd` nos padrões A/B/D/E/F/G/H/J/L/M/P/U/V/W/X/Y/Z/AA/AB/AD/AE/AF/AG, `infAdProd` + whitelist de cores no padrão C, `infAdProd` diretamente no Padrão O) — mantida como referência bruta, junto com `COR_1`/`COR_2`/`COR_3`. **Sempre vazia nos Padrões I, K, N, Q, R, S, T e AC** (não existe em nenhuma posição do XML desses fornecedores, ou — no Padrão R — existe mas está truncada demais para separar do nome; no Padrão AC, existe só como código abreviado sem tabela de tradução confiável). **No Padrão L, siglas conhecidas são traduzidas** (`PT`→`PRETO`, `BC`→`BRANCO`, `AZ`→`AZUL`), as demais mantidas como vieram. **No Padrão P, siglas (`PTO`, `GRF`, `BCO`...) nunca são traduzidas** — gravadas como vieram, posição sempre confiável. **Nos Padrões V e Y, um modificador (`TODO`/`OIL`) pode vir antes da cor**, únicos padrões com modificador prefixado. **No Padrão X, `COR` vem de um marcador literal explícito no `xProd` (`COR [valor]`)**, único padrão onde a origem é um rótulo de campo, não uma posição inferida — quando ausente, fica vazia e não é falha (produto sem variação de cor). **No Padrão Z, `COR` pode incluir nomes de estampa/tema** (ex. `SONIC`, `UNICORNIO`, `ARANHA/TEIA`), não só cores literais — gravada como veio, sem whitelist, mesma cautela do Padrão B/U. **No Padrão AA, os segmentos são separados por `-` no XML** (não `/`, único padrão além do caso raro do Padrão C onde isso acontece) e os nomes de cor **mantêm o prefixo de marca** (`Puma Black`, `PUMA White`) quando presente, sem tentar isolar só o nome da cor. **No Padrão AB, `COR` é sempre 1 única palavra** (última posição de `xProd`, nunca composta por `/`). **No Padrão AD, `COR` vem da posição padrão do Padrão B** (penúltima palavra antes do sufixo `REF [cProd]`), sempre confiável (marcador `REF` como âncora). **No Padrão AE, `COR` pode ter mais de um segmento pareado 1:1 com o bloco de material** (mesmo número de segmentos dos dois lados), sem whitelist. **No Padrão AF, `COR` vem de um campo de largura fixa que pode vir genuinamente vazio** (`CARTEIRA` sem colorway definida) — sem falha, `COR_PRECISA_REVISAO = false` mesmo quando vazia — e pode conter siglas abreviadas não traduzidas (`PTO/CAF` no `CINTO`). **No Padrão AG, `COR` pode ter 1 ou 2 palavras sem tentar separar material colado à cor** (ex. `Preto Nylon`) — exceto no item isolado `MALA`, onde `COR` fica vazia com `COR_PRECISA_REVISAO = true` |
| **COR_1, COR_2, COR_3** | `COR` separada por `/` e distribuída nas 3 colunas de cor do sistema (ver seção "Divisão em COR_1/COR_2/COR_3" abaixo) — no Padrão E cada segmento pode ter mais de uma palavra (ex.: `CINZA ACO`), assim como no Padrão P (ex.: `M AV`), no Padrão U (ex.: `NAPA SOFT BRANCO`), no Padrão Z (ex.: `TEIA CHUMBO`, `ROSA BB`) e no Padrão AA (ex.: `Cool Light Gray`, `Frosted Ivory`); no Padrão W sempre 2 segmentos de 1 palavra cada até agora; nos Padrões X, Y e AB sempre 1 cor (sem `/`) quando presente; no Padrão AC sempre vazias (sem `COR` para dividir); no Padrão AE cada segmento pode ter mais de uma palavra (ex.: `COHIBA I`), até 4 segmentos observados (só os 3 primeiros usados); no Padrão AF sempre 1 cor (sem `/`) no sub-caso `CARTEIRA`, podendo ter 2 no sub-caso `CINTO` (ex. `PTO/CAF`); no Padrão AG sempre 1 cor (sem `/`, nenhum caso bicolor visto ainda) |
| **COR_PRECISA_REVISAO** | `true`/`false` — existe no Padrão C (nenhuma palavra do bloco de cor bateu com a lista de cores válidas), no Padrão D (fornecedor mandou o trecho de material/cor vazio no XML), **sempre `true` nos Padrões I, K, N, Q, R, S, T e AC** (COR nunca disponível ou não confiável), no Padrão J quando `xProd` não tem nenhum `/` (caso de segurança), no Padrão L quando pelo menos uma sigla de cor não foi reconhecida (ex.: `CH`, `CO`), e no Padrão AG só no item isolado `MALA` (sem marcador `ZT`/`CA`, sem cor identificável). **Nunca `true` nos Padrões P, U, V, W, X, Y, Z, AA, AB, AD, AE e AF, e nunca nos itens `BOLSA`/`CARTEIRA` do Padrão AG** (posição/marcador sempre determinístico — nos Padrões X, Y e AF, ausência de `COR` é uma característica real do produto, não uma limitação de dado) |
| **TAMANHO** | conforme o padrão detectado — **fica em branco no Padrão E** (ALPARGATAS/Havaianas não informa tamanho no XML; comportamento esperado, não é falha de extração); **pode ser texto não numérico no Padrão F** (ex.: `UNICO`); **sempre vazio nos Padrões J, Z e AB** (decisão do usuário — ver "Regra geral para COR/TAMANHO ausentes"); **vem do `infAdProd`, não do `xProd`, no Padrão K**; **pode ser uma faixa (`39-44`) ou um código de tamanho de vestuário (`U`/`P`/`M`/`G`/`GG`) no Padrão L**, sempre gravado como veio (ou `"UN"` pela regra geral 4 quando o item é vestível de tamanho único sem marcador de tamanho); **pode ser uma faixa (`38/72`) no Padrão M** (comprimento de alça ajustável), também gravada como veio (ou `"UN"` pela regra geral 4, se algum dia faltar); **sempre `"UN"` no Padrão N** (regra geral 4 — meia é produto "tamanho único"); **vem do marcador `Tam:` colado ao número no Padrão P**; **vem do `cProd` (não do `xProd`) no Padrão Q, podendo ser decimal** (ex.: `8.5`); **sempre vazio nos Padrões R e S** (decisão do usuário — dado ausente em qualquer posição); **sempre `"UN"` no Padrão T** (regra geral 4 — boné é produto "tamanho único"); **vem do `xProd`, última palavra, nos Padrões V e W**; **vem de um marcador literal explícito no `xProd` (`TAM [valor]`) no Padrão X, ou `"UN"` pela regra geral 4 quando o item começa com `MEIA` e não tem esse marcador**; **vem do marcador `N.` no Padrão Y**, com validação cruzada disponível via `infAdProd`; **vem do `xProd`, última palavra, no Padrão AA**, sempre batendo com os 2 últimos dígitos do `cProd` (validação cruzada, sem efeito em `REFERENCIA_PRODUTO`); **vem do `cProd` (último dos 4 segmentos), não do `xProd`, no Padrão AC** — sempre inteiro nos itens testados, sem decimais vistos ainda (diferente do Padrão Q/Nike); **vem do `xProd`, palavra imediatamente antes do sufixo `REF [cProd]`, no Padrão AD** — sempre bate com o último segmento do `cProd`; **é sempre o literal `"UNI"` no Padrão AE** (marcador explícito do XML, diferente do `"UN"` construído pela regra geral 4); **no Padrão AF, vem do `cProd` (via `.Tnnn`) no sub-caso `CINTO`, nunca do `xProd`** (truncado antes do valor), **e é sempre `"UN"` (regra geral 4) no sub-caso `CARTEIRA`** (categoria "tamanho único" confirmada pelo usuário); **sempre `"UN"` no Padrão AG** (regra geral 4 — `BOLSA`/`CARTEIRA` reconfirmados pelo usuário, e o item isolado `MALA` por decisão pontual do usuário, ver "Caso resolvido" acima). **Regra geral 4** (ver "Regra geral para COR/TAMANHO ausentes"): para qualquer padrão, se o produto for identificável como categoria "tamanho único" (`MEIA`, `BOLSA`, `CARTEIRA`, `BOLA`, `BONE`...) e nenhuma posição do XML trouxer um `TAMANHO` real, gravar `"UN"` em vez de deixar vazio |
| CODIGO_NCM | `NCM` |
| QUANTIDADE | `qCom` |
| VALOR_PRODUTO | `vProd` |
| VALOR_PRODUTO_DESCONTO | `vDesc`, ou `0.00` se a tag não existir |

**Por que manter as duas colunas:** `NOME_PRODUTO` sozinho não deixa claro o que foi removido (cor, código, tamanho) nem serve para auditar erros de extração — principalmente no Padrão C, onde o ruído descrito na seção de limitações fica mais fácil de investigar comparando `NOME_PRODUTO_ORIGINAL` (o `xProd` bruto) com o `COR` extraído do `infAdProd`.

**Validação (tamanho x cProd):** comparado o tamanho extraído com o último segmento do `cProd` (quando aplicável) em **12 arquivos, 4 fornecedores, 253 itens** — nenhuma divergência restante após a correção da regra de corte do `REFERENCIA_PRODUTO`.

Cores encontradas até agora: `PRETO`, `TAN`, `CAFE`, `CARAMELO`, `BRANCO`, `NUDE`, `CREME`, `DOURADO`, `CAMEL`, `MARINHO`, `ROSA`, `AMARELO`, `AZUL`, `GRAFITE`, `LARANJA`, `MOSTARDA`, `CHOCOLATE`, `AREIA`, `BEGE`, `MARFIM`, `OURO`, `NATURAL`, `PINK`, `VINHO`, entre outras.

## Correção aplicada — limpeza do COR da CALCADOS BEIRA RIO (Padrão C) via lista de cores válidas

O problema descrito originalmente (87,7% dos 114 itens da BEIRA RIO com um resto de palavra técnica truncada grudado na frente da cor — ex.: `xProd` corta no meio de "SINTÉTICO"/"TEXTIL" e o `infAdProd` continua sem separador claro) foi **confirmado e corrigido** após revisão manual de um caso real:

> Arquivo `NFe-43251288379771003602550040007319711731971425.xml`, item com `cEAN=7890694633355` (`cProd=9127320`): `infAdProd = "TICO CABEDAL TEXTIL NUDE 658 tam: 39 ..."`. `"TICO CABEDAL TEXTIL"` é resto da descrição fiscal truncada (não é cor); só `NUDE` é cor de verdade. A versão anterior da extração devolvia `COR = "TICO CABEDAL TEXTIL NUDE"`, errado.

**Regra corrigida — aplicada apenas ao Padrão C:** depois de remover o código numérico de cada segmento (como antes), percorrer as palavras restantes **de trás pra frente** e manter só as que batem com uma **lista de cores válidas** (ou um modificador de cor, como "OFF"/"NEON"/"CLARO"/"ESCURO"). Assim que uma palavra não reconhecida aparece, para de incluir — tudo o que sobrar antes dela (o resto da descrição fiscal truncada) é descartado.

Lista de cores válidas usada (pode/deve crescer conforme mais fornecedores forem testados):
`PRETO, BRANCO, AZUL, VERDE, VERMELHO, AMARELO, ROSA, ROXO, LARANJA, MARROM, CINZA, BEGE, DOURADO, PRATA, VINHO, BORDO, CARAMELO, CAFE, CAFÉ, CREME, NUDE, CAMEL, MARINHO, GRAFITE, MOSTARDA, CHOCOLATE, AREIA, MARFIM, OURO, NATURAL, PINK, TAN, COBRE, BRONZE, TURQUESA, LILAS, SALMAO, CORAL, FUCSIA, MAGENTA, JEANS, CRU, GELO, PALHA, OSSO, CAQUI, METALICO, ONCA, PRATEADO, DOURADA, AZULADO, CRISTAL, PTO, CONHAQUE, BROWN, ANDIROBA, BABACU, ARE, SILVER` (`ARE` = abreviação de `AREIA`, confirmada pelo usuário em 2026-07-17 no Padrão D/A. GRINGS, modelo `147304`; `SILVER` = "prateado" em inglês, confirmada pelo usuário em 2026-07-17 no Padrão C/CALCADOS BEIRA RIO — ambas mantidas como vieram, sem tradução, mesma convenção de `PTO`/`PRETO`/`BROWN`) + modificadores `OFF, NEON, CLARO, ESCURO` (sempre depois da cor) e `TODO`/`OIL` (sempre **antes** da cor — `TODO` visto na INDUSTRIA E COM DE CALC SYG STAR LTDA/Padrão V, ex.: `TODO PRETO`; `OIL` visto na MACBOOT/Padrão Y, ex.: `OIL BROWN`, `OIL CAFÉ`) + nomes compostos de 2 palavras `AURORA BOREAL` (efeito glitter iridescente — visto pela primeira vez no arquivo `CALCADOS_BEIRA_RIO_S_A_2024-01-10_NFe-...1407.XML`), `DARK BROWN` (inglês, visto na CALCADOS BOTTERO — Padrão H), `BLACK DIAMOND` (inglês, nome comercial de colorway, confirmado pelo usuário em 2026-07-17 no Padrão C/CALCADOS BEIRA RIO).

⚠️ **A partir do Padrão H, essa whitelist também é usada para extrair `COR` diretamente do `xProd`** (não só do `infAdProd` como no Padrão C) — mesma lista, múltiplos pontos de uso (Padrões H, V e Y).

⚠️ **Importante — essa regra é só para o Padrão C.** Nos Padrões A/B a cor já vem confiável da posição fixa no `xProd` (não do `infAdProd` ruidoso), então **não** se aplica a whitelist ali — senão cores compostas legítimas como `CAFE/MARINHO/CARAMELO` (KIDY) poderiam ser cortadas incorretamente. (A CALCADOS MARTE, que antes era citada aqui como exemplo de cor composta com `I23/BEGE/MIL`, na verdade usa esse bloco como `CODIGO_COR` — um código de referência do fornecedor, sempre descartado, não a `COR` — ver correção no sub-caso do marcador `EM` em "Fornecedor 4".)

Resultado após a correção, nos **114 itens de Padrão C** (BEIRA RIO): **100% com cor limpa, 0 precisando de revisão manual.**

**Atualização em 2026-07-17 (arquivo `CALCADOS_BEIRA_RIO_S_A_2024-02-12_NFe-...901276990427.XML`, 30 itens novos):** confirmou a regra generalizada de separador duplo (`/` e `-` juntos no mesmo bloco, ver passo 2 do Padrão C acima) e as 2 novas entradas de whitelist (`SILVER`, `BLACK DIAMOND`). Total acumulado: **144 itens de Padrão C** (114 + 30), **100% com cor limpa, 0 com `COR_PRECISA_REVISAO = true`**.

| infAdProd (antes de "tam:") | COR antes da correção | COR depois da correção |
|---|---|---|
| `TICO CABEDAL TEXTIL NUDE 658` | `TICO CABEDAL TEXTIL NUDE` | `NUDE` |
| `ABEDAL SINTETICO PRETO 01/CAMEL 1165` | `ABEDAL SINTETICO PRETO/CAMEL` | `PRETO/CAMEL` |
| `DAL SINT. BRANCO 99/BRANCO OFF 526/CINZA 1235` | `DAL SINT. BRANCO/BRANCO OFF/CINZA` | `BRANCO/BRANCO OFF/CINZA` |
| `T. PRETO 01` | `T. PRETO` | `PRETO` |
| `TICO CABEDAL TEXTIL MARINHO/PINK NEON` | `TICO CABEDAL TEXTIL MARINHO/PINK NEON` | `MARINHO/PINK NEON` |

**Coluna de segurança adicionada: `COR_PRECISA_REVISAO`** (`true`/`false`) — quando nenhuma palavra do segmento bate com a lista de cores válidas, a extração **não inventa** uma cor: mantém o texto bruto e marca essa linha para revisão manual, em vez de arriscar um corte errado silenciosamente. Nos 114 itens testados, essa coluna deu `false` em 100% dos casos, mas a lógica está preparada para sinalizar fornecedores/cores novas que ainda não estão na lista.

## Divisão em COR_1 / COR_2 / COR_3 (colunas do sistema)

O sistema trabalha com **3 colunas de cor por produto** (`COR 1`, `COR 2`, `COR 3`). Como o campo `COR` extraído pode conter de 1 a 3 cores separadas por `/` (produtos bi/tricolor), ele é distribuído nessas 3 colunas com a seguinte regra:

| Quantidade de cores em `COR` | COR_1 | COR_2 | COR_3 |
|---|---|---|---|
| 1 cor (`PRETO`) | `PRETO` | `PRETO` (repete a COR_1) | `PRETO` (repete a COR_1) |
| 2 cores (`PRETO/CAMEL`) | `PRETO` | `CAMEL` | `CAMEL` (repete a COR_2) |
| 3 cores (`BRANCO/BRANCO OFF/CINZA`) | `BRANCO` | `BRANCO OFF` | `CINZA` |

Ou seja: a 1ª cor sempre vai para `COR_1`; se não houver 2ª ou 3ª cor, repete-se a última cor conhecida (a 2ª repete a 1ª quando só existe 1 cor; a 3ª repete a 2ª quando existem só 2 cores).

**Verificação feita:** nos 253 itens já processados (12 arquivos, 5 fornecedores) — antes da entrada da BR8, cujos itens têm sempre 1 cor —, o campo `COR` nunca teve mais de 3 valores separados por `/` — a distribuição das quantidades foi 89 itens com 1 cor, 75 com 2 cores e 32 com 3 cores (mais os 57 itens de DAKOTA/VULCABRAS, que são sempre de 1 cor). Não foi necessário definir uma regra para 4+ cores porque esse caso não apareceu; se aparecer no futuro, hoje a lógica usa apenas as 3 primeiras cores encontradas.

O campo `COR` original (com todas as cores juntas, separadas por `/`) **é mantido no CSV** ao lado de `COR_1`/`COR_2`/`COR_3`, para auditoria.

## Exemplo de Extração (arquivos de teste)

### Fornecedor 1 — DAKOTA CALCADOS S/A (Padrão A, com código de cor)

| Campo | Valor |
|---|---|
| CNPJ_FORNECEDOR | 07414643000201 (também visto: 07414643000120 — mesma empresa, filial diferente) |
| NOME_FORNECEDOR | DAKOTA CALCADOS S/A |

3 arquivos (1 original + 2 da pasta `XML_SELECIONADO`: `DAKOTA_CALCADOS_S_A_2025-04-10_NFe-...4751.XML`, `DAKOTA_CALCADOS_S_A_2026-02-18_NFe-...4220.XML`), **115 itens**. Trecho da listagem final:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO | CODIGO_NCM | QUANTIDADE | VALOR_PRODUTO | VALOR_DESCONTO |
|---|---|---|---|---|---|---|---|---|---|
| J1523-0003 | 7900282887111 | SAPATO MISSISSIPI MIRAI | SAPATO MISSISSIPI MIRAI PRETO 224 34 | PRETO | 34 | 64029990 | 1.0000 | 77.42 | 5.42 |
| J1523-0003 | 7900282664262 | SAPATO MISSISSIPI MIRAI | SAPATO MISSISSIPI MIRAI PRETO 224 35 | PRETO | 35 | 64029990 | 2.0000 | 154.84 | 10.84 |
| J1581-0005 | 7900282753287 | BOTA MISSISSIPI GALBANI | BOTA MISSISSIPI GALBANI TAN 126 39 | TAN | 39 | 64029190 | 1.0000 | 94.62 | 6.62 |
| J1441-0002 | 7900282883007 | BOTA MISSISSIPI GALBANI | BOTA MISSISSIPI GALBANI CAFE 125 33 | CAFE | 33 | 64029190 | 1.0000 | 103.22 | 7.23 |
| ME123-0001 | 7900132908485 | BOTA MISSISSIPI BERTELI | BOTA MISSISSIPI BERTELI PRETO 220 33 | PRETO | 33 | 64029190 | 1.0000 | 116.12 | 8.13 |
| J0781-0001 | 7900282671000 | BOTA MISSISSIPI BERTELI | BOTA MISSISSIPI BERTELI CACAU 125 34 | CACAU | 34 | 64029190 | 1.0000 | ... | ... |
| ... | ... | SAPATO MISSISSIPI MIRAI | SAPATO MISSISSIPI MIRAI RUBY 225 34 | RUBY | 34 | ... | ... | ... | ... |
| ... | ... | ... | ... | ... | ... | ... | ... | ... | ... |

Note que `REFERENCIA_PRODUTO` se repete entre linhas que variam só no tamanho (ex.: `J1523-0003` aparece 6 vezes, uma por tamanho de 34 a 39) — comportamento esperado após a remoção do sufixo de tamanho. Nos 2 arquivos novos, `cProd` segue o mesmo esquema (`ME123-0001-33`, `J0781-0001-34` etc. — sempre termina no TAMANHO, sempre cortado). Cores novas encontradas: `CACAU`, `RUBY`, `MARFIM`.

### Fornecedor 2 — VULCABRAS (Padrão B, sem código de cor)

3 arquivos novos da pasta `XML_SELECIONADO` (`VULCABRAS___CE_CALCADOS_E_ARTIGOS_ESPORTIVOS_S_A_2024-03-04_NFe-...8242.XML`, `VULCABRAS___CE_CALCADOS_E_ARTIGOS_ESPORTIVOS_S_A_2025-01-15_NFe-...0060.XML`, `VULCABRAS___CE_CALCADOS_E_ARTIGOS_ESPORTIVOS_S_A_2026-02-19_NFe-...4809.XML`) somados ao arquivo original — **4 arquivos, 23 itens** (8 + 5 + 4 + 6).

| Campo | Valor |
|---|---|
| CNPJ_FORNECEDOR | 00954394000117 |
| NOME_FORNECEDOR | VULCABRAS - CE CALCADOS E ARTIGOS ESPORTIVOS S/A |

**8 itens do arquivo original** — listagem completa:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO | CODIGO_NCM | QUANTIDADE | VALOR_PRODUTO | VALOR_DESCONTO |
|---|---|---|---|---|---|---|---|---|---|
| 43611218-PRETO | 7894929096144 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 37 | PRETO | 37 | 64041100 | 1.0000 | 85.10 | 0.00 |
| 43611218-PRETO | 7894929096151 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 38 | PRETO | 38 | 64041100 | 1.0000 | 85.10 | 0.00 |
| 43611218-PRETO | 7894929096168 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 39 | PRETO | 39 | 64041100 | 2.0000 | 170.20 | 0.00 |
| 43611218-PRETO | 7894929096175 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 40 | PRETO | 40 | 64041100 | 2.0000 | 170.20 | 0.00 |
| 43611218-PRETO | 7894929096182 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 41 | PRETO | 41 | 64041100 | 2.0000 | 170.20 | 0.00 |
| 43611218-PRETO | 7894929096199 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 42 | PRETO | 42 | 64041100 | 2.0000 | 170.20 | 0.00 |
| 43611218-PRETO | 7894929096205 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 43 | PRETO | 43 | 64041100 | 1.0000 | 85.10 | 0.00 |
| 43611218-PRETO | 7894929096212 | TENIS OLYMPIKUS INDEX 3 | TENIS OLYMPIKUS INDEX 3 PRETO 44 | PRETO | 44 | 64041100 | 1.0000 | 85.10 | 0.00 |

**Arquivo exportado (DAKOTA + VULCABRAS):** `C:\VETOR\PROJETO_ENTRADA_XML\SOURCE\produtos_com_cor_tamanho.csv` (separador `;`, encoding UTF-8) — 57 itens, já com `COR_1`/`COR_2`/`COR_3` (todas iguais nesses dois fornecedores, pois só têm 1 cor por item).

⚠️ **Descoberta nos 3 arquivos novos — `COR` (posição padrão do Padrão B) nem sempre é um nome de cor legível:** diferente do arquivo original (sempre `PRETO`, uma cor limpa), os novos arquivos trazem marcas distribuídas pela VULCABRAS (MIZUNO) e uma linha OLYMPIKUS diferente (`RUSH`) onde a posição da `COR` é ocupada por um **código híbrido cor+número colado sem espaço** (`ROSA46`) ou por um **código totalmente opaco, sem leitura de cor nenhuma** (`ALGPSC`):

| xProd | cProd | COR extraída | Observação |
|---|---|---|---|
| `TENIS OLYMPIKUS ASFALTO PRETO 38` | `43203271-PRETO-38` | `PRETO` | cor limpa, mesmo padrão do arquivo original |
| `TENIS MIZUNO HAWK 6 ROSA46 34` | `101033033-ROSA46-34` | `ROSA46` | cor (`ROSA`) + código numérico (`46`) colados, sem separador — mantido inteiro, sem tentar dividir |
| `TENIS OLYMPIKUS RUSH ALGPSC 34` | `43667419-ALGPSC-34` | `ALGPSC` | código interno opaco, sem nome de cor legível em nenhuma posição |

Em ambos os casos novos, o valor extraído **bate exatamente com o segmento do meio do `cProd`** (`101033033-ROSA46-34`, `43667419-ALGPSC-34`) — ou seja, não é um erro de extração, é o próprio dado de origem do fornecedor. **Decisão: manter o algoritmo do Padrão B inalterado** (COR = penúltima palavra, sem whitelist, mesma regra de sempre) — não se tenta separar `ROSA46` em `ROSA`+`46` nem decodificar `ALGPSC`, pela mesma cautela já aplicada em todo o documento (só se corta/decompõe com marcador comprovado, e aqui não há nenhum). `REFERENCIA_PRODUTO` continua sendo cortado normalmente (`101033033-ROSA46`, `43667419-ALGPSC`) — o corte funciona porque o último segmento do `cProd` sempre bate com o `TAMANHO`, independente do conteúdo do segmento de cor.

**Validação (3 arquivos novos):** 15 itens (5 + 4 + 6). Modelos: `TENIS OLYMPIKUS ASFALTO` (cor `PRETO`, limpa), `TENIS MIZUNO HAWK 6` (cor `ROSA46`, híbrida), `TENIS OLYMPIKUS RUSH` (cor `ALGPSC`, opaca). `CODIGO_BARRAS` real, `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` ausente) em 100% dos itens.

### Fornecedor 3 — KIDY BIRIGUI CALCADOS (Padrão B, cor pode ser composta)

6 arquivos (3 originais + 3 da pasta `XML_SELECIONADO`: `KIDY_BIRIGUI_CALCADOS_INDUSTRIA_E_COMERCIO_LTDA_FIL_MS_2026-03-06_NFe-...3328.XML`, `KIDY_BIRIGUI_CALCADOS_INDUSTRIA_E_COMERCIO_LTDA_FIL_MS_2026-03-06_NFe-...3602.XML`, `KIDY_BIRIGUI_CALCADOS_INDUSTRIA_E_COMERCIO_LTDA_FIL_MS_2026-03-06_NFe-...8301.XML`), **99 itens** (76 + 23). Exemplo (produto tricolor, mostra o split em COR_1/COR_2/COR_3):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 00110389951 | SANDALIA KIDY BABY EQUILIBRIO | CAFE/MARINHO/CARAMELO | CAFE | MARINHO | CARAMELO | 18 |

`cProd` bruto era `00110389951-18` — como o segmento final (`18`) bate com o TAMANHO extraído, foi cortado corretamente.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 96261607000447 | KIDY BIRIGUI CALCADOS INDUSTRIA E COMERCIO LTDA. FIL.4 (MS) |

⚠️ **Novo sub-caso descoberto nos 3 arquivos de 2026-03-06 (linha `HAPPY`) — cor com qualificador de 2 palavras:**

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 31310522590 | TENIS KIDY HAPPY | MARINHO/AZUL STONE | MARINHO | AZUL STONE | AZUL STONE | 22 |
| 31300570610 | TENIS KIDY HAPPY | BRANCO/ROSA/LILAS | BRANCO | ROSA | LILAS | 22 |
| 31300570306 | TENIS KIDY HAPPY | PRETO/PINK | PRETO | PINK | PINK | 23 |

`xProd` bruto do primeiro exemplo: `TENIS KIDY HAPPY MARINHO/AZUL STONE 22` — a regra genérica do Padrão B (`COR = penúltima palavra`) capturaria só `STONE`, errado; a regra estendida (ver seção do Padrão B acima) reconhece que `STONE` não contém `/`, então junta com a palavra anterior (`MARINHO/AZUL`, que contém `/`) para formar o bloco de cor completo `MARINHO/AZUL STONE`. `cProd` (`31310522590-22` etc.) segue a regra de corte já existente (segmento final bate com o TAMANHO → cortado). Um dos 3 arquivos (`...3328.XML` e `...8301.XML`) trouxe exatamente os mesmos 6 itens do modelo `31310522590` (tamanhos 22 a 27) — provável nota complementar/reemitida, mantida como veio, sem deduplicar. `VALOR_PRODUTO_DESCONTO` real e não-zero em todos os itens testados deste lote (diferente da maioria dos outros fornecedores, onde costuma ser `0.00`).

### Fornecedor 4 — CALCADOS MARTE LTDA. (Padrão B, cProd sem tamanho embutido)

4 arquivos (1 original + 3 da pasta `XML_SELECIONADO`: `CALCADOS_MARTE_LTDA_2024-01-29_NFe-...3062.XML`, `CALCADOS_MARTE_LTDA_2025-03-06_NFe-...0638.XML`, `CALCADOS_MARTE_LTDA_2026-02-27_NFe-...7307.XML`), **24 itens**.

⚠️ **Correção aplicada nesta rodada — marcador `EM [MATERIAL]` define onde a COR começa.** A versão anterior deste exemplo estava com `COR` e `NOME_PRODUTO` invertidos, porque tratou o código de referência de cor do fornecedor (`I23/BEGE/MIL`) como se fosse a `COR`, e a cor real (`MARFIM`) como se fizesse parte do nome. Testando mais 3 arquivos, ficou claro que o `xProd` da CALCADOS MARTE segue:

```
[TIPO] [COD_MODELO] EM [MATERIAL] [COR] [CODIGO_COR]? [TAMANHO]
```

Onde `CODIGO_COR` (opcional, sempre descartado) é **tudo que sobra entre a COR e o TAMANHO**, não importa quantos tokens ocupe nem se contém `/` — pode não existir (0 tokens), pode ser 1 token (`I23`), ou mais de 1 (`I23/MARFIM I`).

**Algoritmo (aplicado a partir de agora para este fornecedor, dentro do Padrão B):**
1. Localizar o token literal `EM` em `xProd`.
2. `MATERIAL` = palavra imediatamente depois de `EM` (ex.: `SINTE`) — mantida no `NOME_PRODUTO`, sem campo próprio.
3. `COR` = palavra imediatamente depois do `MATERIAL` (pode ter `/` interno para bicolor, ex.: `PRETO/MARFIM`).
4. `TAMANHO` = última palavra de `xProd`.
5. Tudo entre `COR` e `TAMANHO` (0, 1 ou mais tokens) = `CODIGO_COR`, sempre descartado.
6. `NOME_PRODUTO` = tudo antes da `COR` (inclui `TIPO`, código de modelo, `EM` e `MATERIAL`).

| xProd | NOME_PRODUTO | COR | CODIGO_COR (descartado) | TAMANHO |
|---|---|---|---|---|
| `TENIS 065-001-01 EM SINTE PRETO 34` | `TENIS 065-001-01 EM SINTE` | `PRETO` | (nenhum) | 34 |
| `TENIS 233-004-06 EM SINTE PRETO/MARFIM I23 34` | `TENIS 233-004-06 EM SINTE` | `PRETO/MARFIM` | `I23` | 34 |
| `TENIS 225-003-05 EM SINTE MARFIM I23/BEGE/MIL 34` | `TENIS 225-003-05 EM SINTE` | `MARFIM` | `I23/BEGE/MIL` | 34 |
| `TENIS 225-009-14 EM SINTE MARFIM I23/MARFIM I 34` | `TENIS 225-009-14 EM SINTE` | `MARFIM` | `I23/MARFIM I` | 34 |

Exemplo final (com REFERENCIA_PRODUTO e split de cor):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 90911760AFDJ011 | TENIS 225-003-05 EM SINTE | MARFIM | MARFIM | MARFIM | MARFIM | 34 |
| 90911860AEQ0011 | TENIS 233-004-06 EM SINTE | PRETO/MARFIM | PRETO | MARFIM | MARFIM | 34 |

`cProd` (`90911760AFDJ011`, `90911860AEQ0011` etc.) não tem `-`, então **não é cortado** — fica igual ao original.

⚠️ **Escopo da correção:** este marcador `EM [MATERIAL]` só foi confirmado na CALCADOS MARTE — VULCABRAS e KIDY (outros fornecedores de Padrão B) não têm esse token no `xProd`, então continuam usando a regra genérica original (`COR` = penúltima palavra). A presença do token `EM` isolado é o gatilho para aplicar esta variante mais precisa dentro do Padrão B.

### Fornecedor 5 — CALCADOS BEIRA RIO S/A (Padrão C, cor/tamanho no infAdProd)

10 arquivos (6 do lote original + 4 da pasta `XML_SELECIONADO`: `CALCADOS_BEIRA_RIO_S_A_2024-01-10_NFe-...1407.XML`, `CALCADOS_BEIRA_RIO_S_A_2025-02-07_NFe-...3472.XML`, `CALCADOS_BEIRA_RIO_S_A_2026-01-22_NFe-...9326.XML`, `CALCADOS_BEIRA_RIO_S_A_2026-01-22_NFe-...4323.XML`), **176 itens**. Exemplo (caso limpo, sem ruído de truncamento). Note que neste padrão `NOME_PRODUTO` e `NOME_PRODUTO_ORIGINAL` são **iguais** (a descrição fiscal genérica não é alterada — só COR e TAMANHO vêm de outro lugar, o `infAdProd`):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|
| 27639725 | BOTA FEM. DE USO COMUM C/ SOLA SINT. CABEDAL SINT. | BOTA FEM. DE USO COMUM C/ SOLA SINT. CABEDAL SINT. | PRETO | 33 |

Exemplo que antes tinha ruído de truncamento e já foi corrigido pela whitelist de cores (ver seção de correção acima), agora já com o split em COR_1/COR_2/COR_3:

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR (já limpa) | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 24207420 | TAMANCO FEMININA DE USO COMUM COM SOLA SINTETICO C | PRETO/CAMEL | PRETO | CAMEL | CAMEL | 34 |
| 9127320 | SAPATO CASUAL FEMININA DE USO COMUM COM SOLA SINTE | NUDE | NUDE | NUDE | NUDE | 39 |

A segunda linha é o caso reportado e conferido manualmente: `cEAN=7890694633355`, arquivo `NFe-43251288379771003602550040007319711731971425.xml` — antes da correção o `COR` saía como `TICO CABEDAL TEXTIL NUDE`; depois da whitelist, `COR = NUDE`, e como só há 1 cor, `COR_1=COR_2=COR_3=NUDE`.

**Arquivo exportado (lote de 10 arquivos, 196 itens):** `C:\VETOR\PROJETO_ENTRADA_XML\SOURCE\produtos_lote_10_arquivos.csv` (separador `;`, encoding UTF-8), com colunas extras `COR_1`, `COR_2`, `COR_3`, `COR_PRECISA_REVISAO` e `PADRAO_DETECTADO` (A/B/C) para rastrear qual regra foi aplicada em cada linha.

**Novo caso descoberto — separador de cor por `-` em vez de `/`:** no arquivo `CALCADOS_BEIRA_RIO_S_A_2024-01-10_NFe-...1407.XML`, os 6 itens trazem `infAdProd = "EXTIL CRISTAL-PRATA-AURORA BOREAL tam: 34 ..."` (e variações de tamanho 34-39, mesmo bloco de cor em todos). Diferente dos exemplos anteriores (que usavam `/` entre cores e um código numérico por cor, ex. `BRANCO 99/BRANCO OFF 526`), aqui não há código numérico nenhum e o separador entre as 3 cores é `-`:

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR (já limpa) | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 23961283 | SANDALIA FEM. DE USO COMUM C/ SOLA SINT. CABEDAL T | CRISTAL/PRATA/AURORA BOREAL | CRISTAL | PRATA | AURORA BOREAL | 34 |

Aplicado o passo 2 (revisado) do algoritmo do Padrão C: como não há `/` no bloco, `-` é usado como separador → `EXTIL CRISTAL`, `PRATA`, `AURORA BOREAL`. A whitelist então descarta `EXTIL` (resto truncado de "TEXTIL") do primeiro segmento, mantendo `CRISTAL`. `CRISTAL` e `AURORA BOREAL` são cores novas, adicionadas à lista de cores válidas (ver seção de correção acima).

**Contadores atualizados após este lote:** 10 arquivos, 176 itens (114 do lote original + 62 destes 4 arquivos novos), 0 itens com `COR_PRECISA_REVISAO = true`.

### Fornecedor 6 — A. GRINGS S.A. (Padrão D, marcador `SOLA` no meio do `xProd`)

9 arquivos (pasta `XML_SELECIONADO`), **325 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 97755177002426 | A. GRINGS S.A. |

Exemplo (caso limpo, com cor preenchida):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO | COR_PRECISA_REVISAO |
|---|---|---|---|---|---|
| 65404600000006 | SAPATO 654046 NAP STR SOF PTO/NAP LUX LNH PTO/NAP PTO | SAPATO 654046 NAP STR SOF PTO/NAP LUX LNH PTO/NAP PTO SOLA PVC EXP PRETO SLT FORRADO 34 | PRETO | 34 | false |

Exemplo do gap de dados do fornecedor (bloco depois de `SOLA` vazio — corrigido nesta rodada com o fallback do passo 4b: `COR` vem da última palavra **antes** de `SOLA`):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO | COR_PRECISA_REVISAO |
|---|---|---|---|---|---|
| 14730800000006 | SAPATO 147308 NAP LUX LNH PTO/NAP STR SOF PTO | SAPATO 147308 NAP LUX LNH PTO/NAP STR SOF PTO SOLA  34 | PTO | 34 | false |

`cProd` bruto era `65404600000006-34` — como o segmento final (`34`) bate com o TAMANHO extraído, foi cortado corretamente (mesma regra dos demais padrões).

### Fornecedor 7 — ALPARGATAS S/A / Havaianas (Padrão E, sem TAMANHO no XML)

4 arquivos (pasta `XML_SELECIONADO`), **50 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 61079117016443 | ALPARGATAS S/A |

Exemplo com marcador `FC` (cor composta):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_1 | COR_2 | TAMANHO |
|---|---|---|---|---|---|---|
| 41495846842334 | SANDALIAS HAVAIANAS SLIM POINT ME | SANDALIAS HAVAIANAS SLIM POINT FC ROSE GOLD/BEGE PALHA | ROSE GOLD/BEGE PALHA | ROSE GOLD | BEGE PALHA | (vazio) |

Exemplo sem marcador `FC` (whitelist de modelo), primeira linha do lote com prefixo duplicado já limpo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL (bruto, com prefixo de lote) | COR | COR_1 | COR_2 | TAMANHO |
|---|---|---|---|---|---|---|
| 41456026808378 | SANDALIAS HAVAIANAS DUAL | SANDALIAS HAVAIANAS DUAL CINZA ACO/PRETO | CINZA ACO/PRETO | CINZA ACO | PRETO | (vazio) |

`cProd` (`41495846842334`, `41456026808378`) não tem `-`, então fica igual ao original (mesma regra da CALCADOS MARTE/A. GRINGS sem tamanho embutido). `TAMANHO` em branco em todas as linhas — decisão confirmada com o usuário, já que o fornecedor não informa esse dado em nenhum campo do XML.

### Fornecedor 8 — BR8 COMERCIO IMPORTACAO E EXPORTACAO LTDA (Padrão F, mochilas, marcador `TAM`)

3 arquivos (pasta `XML_SELECIONADO`), **8 itens** (6 produtos únicos). Primeiro fornecedor de produto não-calçado (mochilas) testado no projeto.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 42261427000193 | BR8 COMERCIO IMPORTACAO E EXPORTACAO LTDA |

Exemplo:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO | CODIGO_NCM | QUANTIDADE | VALOR_PRODUTO | VALOR_DESCONTO |
|---|---|---|---|---|---|---|---|---|---|
| EV000130 | 4790020160018 | MOCHILA THERMO | MOCHILA THERMO 100% POLIESTER ROSA TAM UNICO | ROSA | UNICO | 42029200 | 1.0000 | 54.90 | 0.00 |
| EV000136 | 4790030267455 | MOCHILA STUD | MOCHILA STUD 100% POLIESTER AZUL TAM UNICO | AZUL | UNICO | 42029200 | 1.0000 | 63.90 | 0.00 |
| EV000096 | 4790020216791 | MOCHILA STRAP | MOCHILA STRAP 100% NYLON AZUL TAM UNICO | AZUL | UNICO | 42029200 | 1.0000 | 70.90 | 0.00 |
| EV000131 | 4790010159992 | MOCHILA SKT | MOCHILA SKT 100% POLIESTER CINZA TAM UNICO | CINZA | UNICO | 42029200 | 1.0000 | 54.90 | 0.00 |
| EV000134 | 4790010267451 | MOCHILA STUD | MOCHILA STUD 100% POLIESTER PRETO TAM UNICO | PRETO | UNICO | 42029200 | 1.0000 / 2.0000 | 63.90 / 127.79 | 0.00 |
| EV000132 | 4790010159305 | MOCHILA SKOOL | MOCHILA SKOOL 100% POLIESTER PRETO TAM UNICO | PRETO | UNICO | 42029200 | 1.0000 | 56.90 | 0.00 |

`cProd` (`EV000130` etc.) não tem `-`, então fica igual ao original (mesma regra da CALCADOS MARTE/ALPARGATAS/A. GRINGS sem tamanho embutido). `TAMANHO = UNICO` em todas as linhas (tamanho único de mochila, não numérico — comportamento esperado do Padrão F, diferente do "em branco" do Padrão E).

### Fornecedor 9 — CALCADOS BEBECE LTDA (Padrão J, sem TAMANHO no XML)

3 arquivos (pasta `XML_SELECIONADO`: `CALCADOS_BEBECE_LTDA_2024-03-26_NFe-...0116.XML`, `CALCADOS_BEBECE_LTDA_2025-02-28_NFe-...7011.XML`, `CALCADOS_BEBECE_LTDA_2026-03-02_NFe-...1223.XML`), **25 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 90445206000118 | CALCADOS BEBECE LTDA |

Exemplo:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO | CODIGO_NCM | QUANTIDADE | VALOR_PRODUTO | VALOR_DESCONTO |
|---|---|---|---|---|---|---|---|---|---|
| 842290 | (vazio) | BOTA OVER KNEE | BOTA OVER KNEE T4414-230 / CAMURCA STRETCH PRETO / NAPA BRUMAS PRETO / FORRO CACHAREL PRETO / FORRO CACHAREL PRETO / CAM | PRETO | (vazio) | 64041900 | 9.0000 | 1204.47 | 0.00 |

`QUANTIDADE`, `VALOR_PRODUTO` e `VALOR_PRODUTO_DESCONTO` vêm direto do `<det>` (`qCom`, `vProd`, `vDesc`), sem tentativa de desdobrar por tamanho — cada `<det>` continua gerando exatamente 1 linha, só que com `TAMANHO` sempre vazio (decisão do usuário).

### Fornecedor 10 — CALCADOS BIBI LTDA (Padrão G, marca infantil)

4 arquivos (pasta `XML_SELECIONADO`: `CALCADOS_BIBI_LTDA_2024-09-10_NFe-...9369.XML`, `CALCADOS_BIBI_LTDA_FILIAL_BA_2025-04-23_NFe-...2479.XML`, `CALCADOS_BIBI_LTDA_FILIAL_BA_2026-04-08_NFe-...0865.XML`, `CALCADOS_BIBI_LTDA_FILIAL_BA_2026-04-08_NFe-...1733.XML`), **135 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 97748958000105 | CALCADOS BIBI LTDA |
| 97748958003031 | CALCADOS BIBI LTDA. FILIAL BA |

Exemplo (cor tricolor e cor única):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|---|---|
| 533754 | 7909670896674 | CALCADOS SKATENIS | 1243005 CALCADOS SKATENIS TECIDO MARINHO/PRETO/FIRE 30 | MARINHO/PRETO/FIRE | MARINHO | PRETO | FIRE | 30 |
| 504821 | 7909670150028 | CALCADOS ROLLER 2.0 | 1155015 CALCADOS ROLLER 2.0 LYCRA PRETO 24 | PRETO | PRETO | PRETO | PRETO | 24 |

Note que `2.0` (parte do nome do modelo `ROLLER 2.0`) é preservado corretamente no `NOME_PRODUTO`, pois vem **antes** da palavra de material (`LYCRA`), não é confundido com código ou tamanho. `cProd` (`533754`, `504821` etc.) nunca tem `-`, então fica igual ao original. `VALOR_PRODUTO_DESCONTO` = `vDesc` real (presente em 100% dos itens, ao contrário de outros fornecedores sem desconto no XML).

### Fornecedor 11 — CALCADOS BOTTERO LTDA (Padrão H, cor via whitelist no xProd)

3 arquivos (pasta `XML_SELECIONADO`: `CALCADOS_BOTTERO_LTDA_2024-04-04_NFe-...7682.XML`, `CALCADOS_BOTTERO_LTDA_2025-09-11_NFe-...6343.XML`, `CALCADOS_BOTTERO_LTDA_2026-03-19_NFe-...6162.XML`), **72 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 90312133000439 | CALCADOS BOTTERO LTDA |

Exemplo (cor simples, cor com código numérico, cor bicolor de 2 palavras por lado):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_1 | COR_2 | TAMANHO |
|---|---|---|---|---|---|---|
| 550170 | BOTA CANO LONGO COURO BURNISH | 346903 BOTA CANO LONGO COURO BURNISH PRETO 34 | PRETO | PRETO | PRETO | 34 |
| — | SANDALIA RASTEIRA COURO ATANADO | 336801 SANDALIA RASTEIRA COURO ATANADO CARAMELO 15 34 | CARAMELO | CARAMELO | CARAMELO | 34 |
| 551072 | BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA | 361801 BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA PTO/PTO 33 | PTO/PTO | PTO | PTO | 33 |
| 551191 | BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA | 361801 BOTA CANO BAIXO COURO NAPA RAVENA/SINTETICO RAVENA DARK BROWN 2011/DARK BROWN 2011 34 | DARK BROWN/DARK BROWN | DARK BROWN | DARK BROWN | 34 |

O `cProd` (`550170`, `551072`, `551191`) é diferente do código numérico que abre o `xProd` (`346903`, `361801` — este último, aliás, se repete entre as duas colorways `PTO/PTO` e `DARK BROWN/DARK BROWN` do mesmo modelo `361801`, confirmando que é um código de **modelo**, não de modelo+cor). O material (`COURO BURNISH`, `COURO NAPA RAVENA/SINTETICO RAVENA`) permanece no `NOME_PRODUTO`, sem tentativa de removê-lo (sem marcador estrutural confiável para isso neste fornecedor).

### Fornecedor 12 — CALCADOS FERRACINI LTDA (Padrão I, sem COR no XML)

3 arquivos (pasta `XML_SELECIONADO`: `CALCADOS_FERRACINI_LTDA_2024-02-23_NFe-...8100.XML`, `CALCADOS_FERRACINI_LTDA_2025-02-24_NFe-...9411.XML`, `CALCADOS_FERRACINI_LTDA_2025-12-04_NFe-...4916.XML`), **53 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 53577383001608 | CALCADOS FERRACINI LTDA |
| 53577383000121 | CALCADOS FERRACINI LTDA |

Exemplo:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_PRECISA_REVISAO | TAMANHO |
|---|---|---|---|---|---|---|
| 7052-267A | 7909611537369 | SAPATENIS MASCULINO DE COURO C/ SOLADO DE BORRACHA | SAPATENIS MASCULINO DE COURO C/ SOLADO DE BORRACHA 7909611537369   37- | (vazio) | true | 37 |

Todos os 53 itens testados ficaram com `COR_PRECISA_REVISAO = true`, já que a cor não está disponível em nenhuma posição do XML deste fornecedor.

### Fornecedor 13 — CALCADOS PEGADA NORDESTE LTDA (Padrão K, TAMANHO no infAdProd)

3 arquivos (pasta `XML_SELECIONADO`: `CALCADOS_PEGADA_NORDESTE_LTDA_2024-03-15_NFe-...0810.XML`, `CALCADOS_PEGADA_NORDESTE_LTDA_FL__2025-04-07_NFe-...9438.XML`, `CALCADOS_PEGADA_NORDESTE_LTDA_FL__2026-03-06_NFe-...8866.XML`), **26 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 06269953000136 | CALCADOS PEGADA NORDESTE LTDA |
| 06269953000802 | CALCADOS PEGADA NORDESTE LTDA |

Exemplo:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_PRECISA_REVISAO | TAMANHO |
|---|---|---|---|---|---|---|
| 282008-0534 | 7909940224039 | 282008-05 BOTA FEMININA | 282008-05 BOTA FEMININA | (vazio) | true | 34 |
| 281413-02 | 7909940071343 | 281413-02 BOTA FEMININA | 281413-02 BOTA FEMININA | (vazio) | true | 34 |

`TAMANHO` vem do `infAdProd` (`... 34/1 GTIN: ...`), nunca do `cProd` ou do `xProd`. Note que `REFERENCIA_PRODUTO` **não é cortado** mesmo quando parece ter o tamanho embutido (`282008-0534` → `34` bate com o tamanho, mas o segmento inteiro é `0534`, que não bate exatamente — regra de corte não se aplica). No segundo exemplo, `cProd` (`281413-02`) nem varia por tamanho — é o mesmo valor nas 5 linhas do modelo (34 a 38 no arquivo de 2025).

### Fornecedor 14 — CAMBUCI S.A. / Penalty (Padrão L, categoria não-calçado)

3 arquivos (pasta `XML_SELECIONADO`: `CAMBUCI_SA____SR_2025-09-23_NFe-...3063.XML`, `CAMBUCI_SA___BA_2024-07-05_NFe-...7687.XML`, `CAMBUCI_SA___BA_2026-05-25_NFe-...2941.XML`), **42 itens**. Segunda categoria não-calçado do projeto (a primeira foi a BR8/mochilas, Padrão F) — aqui são meias, caneleiras, joelheiras, calções e material de ponto de venda.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 61088894000884 | CAMBUCI SA -  SR11 |
| 61088894002909 | CAMBUCI SA - BA30 |

Exemplo (tamanho em faixa, tamanho em código de vestuário, cor desconhecida, material de PDV):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_PRECISA_REVISAO | TAMANHO |
|---|---|---|---|---|
| 4107879000UN | MEIA PERF SETE 787 | PRETO | false | 39-44 |
| 4107709800UN | MEIAO FUT MATIS 770 | PRETO/BRANCO | false | 39-44 |
| 4116459800UN | MEIAO FUT MATIS 645 | CO | true | 33-38 |
| 6101436000 | CANELEIRA MATIS X | AZUL | false | U |
| 6540499121 | JOELHEIRA ELASTICA XXI | PRETO/CH | true | P |
| 3233039000GG | CALCAO PENALTY FUTEBOL 303 | PRETO | false | GG |
| 4001301000 | CUBO FUN KIDS PAPELAO | (vazio) | false | U |
| 4001001000-U | ARQUIBANCADA 3 PES | (vazio) | false | (vazio) |

`TAMANHO` fica gravado como veio, seja faixa (`39-44`) ou código de vestuário (`U`/`P`/`M`/`G`/`GG`) — decisão do usuário, sem tentar desdobrar em várias linhas ou normalizar. `COR` traduz `PT`→`PRETO`, `BC`→`BRANCO`, `AZ`→`AZUL` quando reconhecidas; siglas desconhecidas (`CH`, `CO`) ficam como vieram, com `COR_PRECISA_REVISAO = true`. Itens de material de ponto de venda (`CUBO`, `ARQUIBANCADA`, `BANDEJA/BASE DE VITRINE`, `SHOETAGS DIVERSOS`, `SUPORTE BOLA PARA BALCAO`) são processados normalmente, sem filtro — decisão do usuário.

### Fornecedor 15 — CLASSE INDÚSTRIA E ARTEF. DE COURO LTDA (Padrão M, bolsas de couro)

1 arquivo (pasta `XML_SELECIONADO`: `CLASSE_INDUSTRIA_E_ARTEF_DE_COURO_LTDA_2024-11-28_NFe-...3916.XML`), **25 itens**. Terceira categoria não-calçado do projeto (depois de mochilas/BR8 e acessórios esportivos/CAMBUCI) — aqui são bolsas de couro (crossbody, tote, shopping bag, tiracolo, camera bag).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 04623865000165 | CLASSE INDUSTRIA E ARTEF. DE COURO LTDA |

Exemplo (tamanho numérico simples e tamanho em faixa/alça ajustável):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|
| 2870-1INS | CROOSBODY 2870 | 2050028701I - CROOSBODY 2870 1I PRETO 38 | PRETO | 38 |
| 3151-1KNS | CROOSBODY 3151 | 2050031511K - CROOSBODY 3151 1K NOCCIOLA 38 | NOCCIOLA | 38 |
| 3263-1A2FNS | TOTE 3263 | 2050032631A2F - TOTE 3263 1A2F TAN 38/72 | TAN | 38/72 |
| 17512-1INS | CROOSBODY 1751-2 | 2050175121I - CROOSBODY 1751-2 1I PRETO 38 | PRETO | 38 |

O código longo no início do `xProd` (`2050028701I` etc.) é só uma compactação do próprio `cProd` (sem o `-`) — descartado, sem campo próprio. O `CODIGO_COR` (`1I`, `1K`, `1A2F`...) fica **antes** da `COR` aqui (diferente do Padrão A, onde vem depois) — reconhecido pelo mesmo sinal já usado no Padrão H: token alfanumérico misto (dígito+letra). `TAMANHO` em faixa (`38/72`) apareceu em 5 dos 25 itens — provavelmente o comprimento ajustável da alça — gravado como veio, mesma decisão já tomada no Padrão L.

### Fornecedor 16 — CONDE DUCK IND DE MEIAS LTDA (Padrão N, meias)

3 arquivos (pasta `XML_SELECIONADO`: `CONDE_DUCK_IND_DE_MEIAS_LTDA_2024-06-07_NFe-...0026.XML`, `CONDE_DUCK_IND_DE_MEIAS_LTDA_2025-07-31_NFe-...9844.XML`, `CONDE_DUCK_IND_DE_MEIAS_LTDA_2026-04-30_NFe-...1348.XML`), **281 itens**. Quarta categoria não-calçado do projeto — meias.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 00807623000170 | CONDE DUCK IND DE MEIAS LTDA |

Exemplo (xProd mantido sem alteração, dado o ruído — sem cor, tamanho sempre "UN"):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_PRECISA_REVISAO | TAMANHO |
|---|---|---|---|---|
| 0030005403 | MEIA BEBE MENINO - SORTIDAS 0-054 | (vazio) | true | UN |
| 0100800103 | KIT DE MEIAS ESPORT. MASC 3 P - 001 | (vazio) | true | UN |
| 0102500202 | MEIA SAP. INV. SCARPINA 1025002M | (vazio) | true | UN |

Este é o fornecedor com `xProd` mais ruidoso do projeto até agora — o indicador de tamanho de vestuário (`P`/`M`/`G`/`GG`/`PP`/`U`/`0`) aparece colado a um código numérico em posições inconsistentes (prefixo, sufixo, isolado entre hifens, ou ausente), e cor nunca é uma palavra real (só código numérico opaco ou o literal `SORTIDAS`). Perguntado ao usuário como tratar o tamanho: **decisão — gravar sempre o literal `"UN"`**, sem tentar decodificar essas letras (ver "Caso resolvido" na seção da regra geral). `cProd` é sempre puramente numérico (sem `-`), então `REFERENCIA_PRODUTO` nunca é cortado.

### Fornecedor 17 — COOPERSHOES COOP.TRAB.IND.CALC.JOANETENSE LTDA (Padrão B, sub-caso Chuck Taylor All Star)

3 arquivos (pasta `XML_SELECIONADO`: `COOPERSHOES_COOPTRABINDCALCJOANETENSE_LTDA_2024-03-07_NFe-...5385.XML`, `COOPERSHOES_COOPTRABINDCALCJOANETENSE_LTDA_2025-02-28_NFe-...2149.XML`, `COOPERSHOES_COOPTRABINDCALCJOANETENSE_LTDA_2026-05-08_NFe-...6290.XML`), **106 itens**. Licenciada da Converse no Brasil — vende exclusivamente o modelo Chuck Taylor All Star.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 02675611000750 | COOPERSHOES COOP.TRAB.IND.CALC.JOANETENSE LTDA |

Exemplo (caso simples e caso com cor multi-palavra + código embutido):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 120953 | TENIS CT00010001 CHUCK TAYLOR ALL STAR | BRANCO/BRANCO/MARINHO | BRANCO | BRANCO | MARINHO | 33 |
| 164863 | TENIS CT09830002 CHUCK TAYLOR ALL STAR LIFT | PRETO/BRANCO/PRETO | PRETO | BRANCO | PRETO | 33 |
| — | TENIS CT04500006 CHUCK TAYLOR ALL STAR | NUDE BEGE CLARO/CAQUI FOSCO/BRANCO | NUDE BEGE CLARO | CAQUI FOSCO | BRANCO | 33 |
| — | TENIS CT33430003 CHUCK TAYLOR ALL STAR | CAFE COM LEITE/AMENDOA/CAFE COM LEITE | CAFE COM LEITE | AMENDOA | CAFE COM LEITE | 33 |

No terceiro exemplo, o bloco de cor bruto era `NUDE BEGE CLARO/CAQUI FOSCO 02/BRANCO` — o `02` (código de cor do segmento `CAQUI FOSCO`) foi descartado, mesma regra já usada no Padrão A/C. `cProd` (`120953`, `164863`...) é sempre puramente numérico, sem `-`, então `REFERENCIA_PRODUTO` nunca é cortado. Cores novas encontradas: `AMENDOA`, `CAFE COM LEITE`, `NUDE BEGE CLARO`, `CAQUI FOSCO` — nomes comerciais compostos, comuns em calçados de moda.

### Fornecedor 18 — DAKOTA NORDESTE S/A (Padrão A, mesma estrutura da DAKOTA CALCADOS)

2 arquivos da pasta `XML_SELECIONADO` (`DAKOTA_NORDESTE_S_A_2024-03-07_NFe-...9833.XML`, `DAKOTA_NORDESTE_S_A_2026-03-02_NFe-...0841.XML`), **55 itens**. Empresa distinta da DAKOTA CALCADOS S/A (CNPJ raiz diferente: `00465813`), mas mesma marca/família de produto — `xProd` segue exatamente o mesmo esquema do Padrão A, só trocando a palavra de linha (`DAKOTA` no lugar de `MISSISSIPI`):

```
[TIPO] DAKOTA [MODELO] [COR] [CODIGO_COR] [TAMANHO]
```

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 00465813000157 | DAKOTA NORDESTE S/A |
| 00465813000238 | DAKOTA NORDESTE S/A (filial diferente) |

Exemplo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| G9563-0001 | BOTA DAKOTA ALMERIA | PRETO | 34 |
| G9563-0001 | BOTA DAKOTA MORINO | PRETO | 33 |
| D2182-0001 | BOTA DAKOTA VERTUO | PRETO | 34 |
| D2182-0001 | BOTA DAKOTA MORINO | CAFE | 34 |
| — | BOTA DAKOTA MORINO | CARAMELO | 34 |

Nenhum padrão novo nem sub-caso necessário — a detecção genérica do passo 17 (penúltima palavra numérica → Padrão A) já cobre este fornecedor. Cores novas encontradas: nenhuma (todas já catalogadas — `PRETO`, `CAFE`, `CARAMELO`).

### Fornecedor 19 — DEMOCRATA CALCADOS E ARTEFATOS DE COURO LTDA (Padrão O)

3 arquivos da pasta `XML_SELECIONADO` (`DEMOCRATA_CALCADOS_E_ARTEFATOS_DE_COURO_LTDA_2024-03-15_NFe-...7248.XML`, `DEMOCRATA_CALCADOS_E_ARTEFATOS_DE_COURO_LTDA_2025-10-24_NFe-...6509.XML`, `DEMOCRATA_CALCADOS_E_ARTEFATOS_DE_COURO_LTDA_2026-03-06_NFe-...9653.XML`), **69 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 52241635002128 | DEMOCRATA CALCADOS E ARTEFATOS DE COURO LTDA |

Exemplo (caso simples e caso bicolor com cor truncada no `xProd`):

| REFERENCIA_PRODUTO | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_1 | COR_2 | TAMANHO |
|---|---|---|---|---|---|---|
| 448027-003 | SAPATOS MASC PRETO, COURO BOVINO , SOLADO SINTETICO | SAPATOS MASC PRETO, COURO BOVINO , SOLADO SINTETICO | PRETO | PRETO | PRETO | 37 |
| 273101-002 | SAPATOS MASC TAN, COURO BOVINO , SOLADO SINTETICO | SAPATOS MASC TAN, COURO BOVINO , SOLADO SINTETICO | TAN | TAN | TAN | 38 |
| 240701-001 | SAPATENIS MASC PRETO INTE, COURO CAPRINO+COURO BOVINO , SOLADO SINTETICO | SAPATENIS MASC PRETO INTE, COURO CAPRINO+COURO BOVINO , SOLADO SINTETICO | PRETO INTENSO/PRETO | PRETO INTENSO | PRETO | 38 |

No terceiro exemplo, o `xProd` trazia a cor truncada em 10 caracteres (`PRETO INTE`), mas a `COR` gravada veio inteira do `infAdProd` (`PRETO INTENSO/PRETO - 38#`) — por isso `NOME_PRODUTO` é mantido igual ao `xProd` bruto, sem tentar remover/corrigir esse trecho de cor não confiável. `cProd` (`448027-003`, `273101-002`, `240701-001`) nunca embute o tamanho — o mesmo código se repete para os 5-8 tamanhos de cada modelo/cor — então `REFERENCIA_PRODUTO` nunca é cortado. Cores novas encontradas: `TAN`, `CONHAQUE`, `PRETO INTENSO`.

### Fornecedor 20 — DILLY NORDESTE INDUSTRIA DE CALCADOS LTDA (Padrão H, sub-caso marcador `WC-\d+`)

3 arquivos da pasta `XML_SELECIONADO` (`DILLY_NORDESTE_INDUSTRIA_DE_CALCADOS_LTDA_2024-09-11_NFe-...9404.XML`, `DILLY_NORDESTE_INDUSTRIA_DE_CALCADOS_LTDA_2025-04-24_NFe-...8539.XML`, `DILLY_NORDESTE_INDUSTRIA_DE_CALCADOS_LTDA_2025-04-24_NFe-...8584.XML`), **54 itens**. Fabricante de tênis (marca `DILLY SPORTS`), cores nomeadas em inglês.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 15836348000190 | DILLY NORDESTE INDUSTRIA DE CALCADOS LTDA |

Exemplo (caso simples, caso com qualificador colado à cor, e caso de segmentos com número de palavras diferente):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 111322 | TENIS COURO | BLACK/LIGHT GREY/GREY | BLACK | LIGHT GREY | GREY | 38 |
| 111327 | TENIS COURO | COFFEE/LIGHT GREY/GREY | COFFEE | LIGHT GREY | GREY | 37 |
| — | TENIS SINT | SUNSET WHITE/BLACK/WHITE | SUNSET WHITE | BLACK | WHITE | 38 |
| — | TENIS COURO | FAST COFFEE BEAN/COFFEE BEAN/BROWN | FAST COFFEE BEAN | COFFEE BEAN | BROWN | 38 |

No último exemplo (`xProd` bruto: `313071 TENIS COURO 03 WC-187 FAST COFFEE BEAN/COFFEE BEAN/BROWN 38`), os 3 segmentos separados por `/` têm quantidades de palavras diferentes entre si (3, 2 e 1) — diferente da "técnica do espelhamento" do Padrão H genérico (que assume os dois lados com a mesma contagem de palavras), aqui isso não é um problema porque o marcador `WC-187` já define com certeza onde o bloco de cor começa; o `/` só serve para separar os segmentos entre si, não para localizar a fronteira. `cProd` (`111322`, `111327`...) é sempre numérico, sem `-`, muda por combinação modelo+cor — `REFERENCIA_PRODUTO` nunca é cortado.

### Fornecedor 21 — FILA BRASIL LTDA (Padrão P)

3 arquivos da pasta `XML_SELECIONADO` (`FILA_BRASIL_LTDA_2024-03-07_NFe-...3041.XML`, `FILA_BRASIL_LTDA_2025-02-14_NFe-...0773.XML`, `FILA_BRASIL_LTDA_2026-03-17_NFe-...7552.XML`), **48 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 41923935000208 | FILA BRASIL LTDA |

Exemplo (caso simples, tricolor, e segmento de cor com 2 palavras):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 1071422 | TENIS FILA EURO JOGGER SPORT MASCULINO | PTO/GRF | PTO | GRF | GRF | 37 |
| — | TENIS FILA RECOVERY FEMININO | PTO/SLLP/CMBL | PTO | SLLP | CMBL | 33 |
| — | TENIS FILA RECOVERY FEMININO | CAST/M AV/BEGE | CAST | M AV | BEGE | 33 |

`xProd` bruto do primeiro exemplo: `11U335X-TENIS FILA EURO JOGGER SPORT MASCULINO-PTO/GRF-972-Tam:37` — o 1º campo (`11U335X`) e o 4º campo (`972`, `CODIGO_COR`) são descartados; `cProd` (`1071422`) é sempre numérico, sem `-`, sem relação com o `COD_MODELO` alfanumérico do `xProd` — `REFERENCIA_PRODUTO` nunca é cortado. Siglas de cor gravadas como vieram, sem tradução (`PTO`, `GRF`, `SLLP`, `CMBL`, `CAST`, `BEGE`).

### Fornecedor 22 — FISIA COMERCIO DE PRODUTOS ESPORTIVOS SA (Padrão Q)

2 arquivos da pasta `XML_SELECIONADO` (`FISIA_COMERCIO_DE_PRODUTOS_ESPORTIVOS_SA_2024-01-06_NFe-...4617.XML`, `FISIA_COMERCIO_DE_PRODUTOS_ESPORTIVOS_SA_2025-05-15_NFe-...5236.XML`), **22 itens**. Distribuidora oficial da Nike no Brasil.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 59546515000487 | FISIA COMERCIO DE PRODUTOS ESPORTIVOS SA |

Exemplo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| DH3158-101 | TENIS W NIKE COURT VISION LO NN | (vazio) | 8.5 |
| DM3494-001 | TENIS NIKE SB CHRON 2 CNVS | (vazio) | 5.5 |
| CU9452-003 | TENIS NIKE AIR MAX INFINITY 2 | (vazio) | 10 |

`cProd` bruto do primeiro exemplo: `DH3158-101-8.5` — `8.5` é o `TAMANHO` (numeração americana, com meio-número), `101` é o código numérico de cor da Nike (mantido em `REFERENCIA_PRODUTO`, não descartado, porque é a única forma de diferenciar colorways sem nome de cor legível). Não existe `<infAdProd>` no XML deste fornecedor. `COR` sempre vazia, `COR_PRECISA_REVISAO = true` em 100% dos itens. `VALOR_PRODUTO_DESCONTO` é real e não-zero (diferente da maioria dos outros fornecedores).

### Fornecedor 23 — GRENDENE S/A (Padrão R)

3 arquivos da pasta `XML_SELECIONADO` (`GRENDENE_S_A___FILIAL__CRA_2024-06-27_NFe-...9000.XML`, `GRENDENE_S_A___FILIAL__CRA_2025-06-27_NFe-...2544.XML`, `GRENDENE_S_A___MATRIZ_SOB_2026-04-27_NFe-...3070.XML`), **93 itens**. Mesma empresa (CNPJ raiz `89850341`), filial CRA e matriz SOB.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 89850341001646 | GRENDENE S/A - FILIAL 6 (CRA) |
| 89850341000160 | GRENDENE S/A - MATRIZ (SOB) |

Exemplo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | TAMANHO |
|---|---|---|
| 12258BA5000378 | CHINELO MORMAII TAI DEDO  CINZA/PRET | (vazio) |
| 12321AW3650378 | CHINELO RIDER GRID SLIDE APRETO/PRET | (vazio) |
| — | BABUCH SONIC SPEED INF    AZUL/AMARE | (vazio) |

`COR` truncada permanece dentro do `NOME_PRODUTO` (não há marcador para separar, e não existe `<infAdProd>` para corrigi-la, ao contrário do Padrão O/DEMOCRATA). `TAMANHO` vazio em 100% dos itens — decisão do usuário (ver "Caso resolvido" acima). `COR_PRECISA_REVISAO = true` em 100% dos itens.

### Fornecedor 24 — IND E COM DE CALC TRENTO LTDA (Padrão S)

2 arquivos da pasta `XML_SELECIONADO` (`IND_E_COM_DE_CALC_TRENTO_LTDA_2024-09-02_NFe-...3026.XML`, `IND_E_COM_DE_CALC_TRENTO_LTDA_2024-09-02_NFe-...5369.XML`), **23 itens**. Marca `LIA LINE`.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 08106801000229 | IND E COM DE CALC TRENTO LTDA |

Exemplo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | CODIGO_BARRAS | COR | TAMANHO |
|---|---|---|---|---|
| 2347.71832 | SANDALIA FEMININA COM SOLA EXTERIOR SINTETICA,PARTE SUPERIOR  COURO NATURAL  71832 | (vazio) | (vazio) | (vazio) |

`cEAN` é sempre o literal `SEM GTIN` → `CODIGO_BARRAS` vazio (regra geral). `COR` e `TAMANHO` sempre vazios — decisão do usuário (ver "Caso resolvido" acima). `xProd` contém o token `SOLA`, mas isso é parte da descrição fiscal ("COM SOLA EXTERIOR SINTETICA"), não o marcador do Padrão D — por isso a detecção deste fornecedor precisa ser checada antes do gatilho do Padrão D.

### Fornecedor 25 — INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI (Padrão T)

2 arquivos da pasta `XML_SELECIONADO` (`INDITEC_INDUSTRIA_TEXTIL_E_CONFECCOES_EIRELI_2024-06-06_NFe-...4194.XML`, `INDITEC_INDUSTRIA_TEXTIL_E_CONFECCOES_EIRELI_2024-06-06_NFe-...4582.XML`), **2 itens** (1 por arquivo). Bonés, quinta categoria não-calçado do projeto.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 39838341000176 | INDITEC INDUSTRIA TEXTIL E CONFECCOES EIRELI |

Exemplo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | CODIGO_BARRAS | COR | TAMANHO | QUANTIDADE |
|---|---|---|---|---|---|
| 0400200400153 | BONE | (vazio) | (vazio) | UN | 24.0000 |

`xProd` é sempre exatamente `"BONE"`, vendido a granel — não há nenhuma variação de cor/modelo/tamanho nos itens testados. `TAMANHO = "UN"` — decisão do usuário, mesmo raciocínio das meias da CONDE DUCK (ver "Caso resolvido" acima).

### Fornecedor 26 — INDUSTRIA DE CALCADOS GONCALVES LTDA. (Padrão U)

2 arquivos da pasta `XML_SELECIONADO` (`INDUSTRIA_DE_CALCADOS_GONCALVES_LTDA_2025-02-26_NFe-...6388.XML`, `INDUSTRIA_DE_CALCADOS_GONCALVES_LTDA_2025-04-24_NFe-...9299.XML`), **46 itens**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 07836977000192 | INDUSTRIA DE CALCADOS GONCALVES LTDA. |

Exemplo (caso simples e caso com prefixo duplicado):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 1471CC_20 | TENIS | COURO NAPA SOFT BURGUNDY/OFF WHITE/CAMURCAO BURGUNDY | COURO NAPA SOFT BURGUNDY | OFF WHITE | CAMURCAO BURGUNDY | 34 |
| 1606CC_7 | TENIS | COURO NAPA SOFT PRETO/BRANCO | COURO NAPA SOFT PRETO | BRANCO | BRANCO | 34 |
| 1606CC_8 | TENIS | COURO NAPA SOFT BRANCO/PRETO | COURO NAPA SOFT BRANCO | PRETO | PRETO | 34 |

No terceiro exemplo, o `xProd` bruto era `Tenis Monique 1606 Couro Branco/PretoTENIS COURO NAPA SOFT BRANCO/PRETO 34` — o prefixo redundante (`Tenis Monique 1606 Couro Branco/Preto`) foi descartado a partir da última ocorrência de `TENIS`. `cProd` (`1606CC_8_34`) usa `_` como delimitador — o último segmento (`34`) bate com o `TAMANHO` extraído, então é cortado, restando `1606CC_8`. Cada segmento de cor mistura material e cor num único nome comercial (`COURO NAPA SOFT BRANCO`, `CAMURCAO ALVEJADA`) — gravado como veio, sem tentar separar os dois.

### Fornecedor 27 — INDUSTRIA E COM DE CALC SYG STAR LTDA (Padrão V)

2 arquivos da pasta `XML_SELECIONADO` (`INDUSTRIA_E_COM_DE_CALC_SYG_STAR_LTDA_2025-03-31_NFe-...8310.XML`, `INDUSTRIA_E_COM_DE_CALC_SYG_STAR_LTDA_2025-04-01_NFe-...5274.XML`), **65 itens** (29 + 36). Primeiro fornecedor do projeto com `cProd` delimitado por `.` (ponto).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 24138791000189 | INDUSTRIA E COM DE CALC SYG STAR LTDA |

Exemplo (cor simples com marcador `EM`, cor com modificador prefixado `TODO`, cor bicolor sem marcador `EM` e com `/` espaçado):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | TAMANHO |
|---|---|---|---|---|---|
| REF.4013.04 | TENIS ADULTO EM CAMURCA | PRETO | PRETO | PRETO | 34 |
| REF.4019.55 | TENIS ADULTO EM CAMURCA | TODO PRETO | TODO PRETO | TODO PRETO | 33 |
| REF.4025.12 | TENIS CASUAL ADULTO | PRETO/BRANCO | PRETO | BRANCO | 33 |
| REF.413.04 | TENIS INFANTIL EM LONA | PRETO | PRETO | PRETO | 27 |

`cProd` bruto do primeiro exemplo: `REF.4013.04.34` — `34` é o `TAMANHO` (bate com o sufixo, então é cortado), `04` é o `CODIGO_COR` (mantido em `REFERENCIA_PRODUTO`, mesma decisão do Padrão Q/FISIA, já que aqui o `cProd` nunca usou `-` nem `_` como delimitador antes). `CODIGO_BARRAS` sempre vazio (`cEAN = SEM GTIN` em 100% dos itens). `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens (`<vDesc>` não existe em nenhum `<det><prod>`, só nos totais da nota). Não existe `<infAdProd>` no XML deste fornecedor.

### Fornecedor 28 — INDUSTRIA E COMERCIO LEJON LTDA (Padrão W)

2 arquivos da pasta `XML_SELECIONADO` (`INDUSTRIA_E_COMERCIO_LEJON_LTDA_2024-02-29_NFe-...6595.XML`, `INDUSTRIA_E_COMERCIO_LEJON_LTDA_2025-10-16_NFe-...1985.XML`), **134 itens** (83 + 51).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 04738157000170 | INDUSTRIA E COMERCIO LEJON LTDA |

Exemplo (marcador `ORD` infantil, marcador `LEJON REF` adulto, variante `SOLADO`):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | TAMANHO |
|---|---|---|---|---|---|
| LJIF0010.98 | TENIS INF EM LONA SOLA BORRACHA ORD | PRETO/BRANCO | PRETO | BRANCO | 28 |
| LJVU0248.98 | TENIS AD CAMURCA SOLA BORRACHA LEJON REF | PRETO/BRANCO | PRETO | BRANCO | 34 |
| LJVU0253.96 | TENIS AD LONA SOLADO BORRACHA ORD | PRETO/PRETO | PRETO | PRETO | 34 |
| LJSC0009.78 | TENIS AD SINTETICO SOLA BORRACHA LEJON REF | GRAFITE/PRETO | GRAFITE | PRETO | 35 |

`cProd` bruto do primeiro exemplo: `LJIF0010.98.28` — `LJIF0010` é o `COD_MODELO` (aparece literalmente dentro do `xProd`, logo depois de `ORD`), `98` é o `CODIGO_COR` (mantido em `REFERENCIA_PRODUTO`, mesma decisão do Padrão V/Q), `28` é o `TAMANHO` (bate com o sufixo, cortado). `CODIGO_BARRAS` real (GTIN) na maioria dos itens, vazio só nos SKUs do modelo `LJVU0252` (2024, `cEAN = SEM GTIN`). `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens (`<vDesc>` não existe em nenhum `<det><prod>`, só nos totais da nota). Não existe `<infAdProd>` no XML deste fornecedor.

### Fornecedor 29 — KL INDUSTRIA E COMERCIO LTDA (Padrão B, marca REDIKAL, `cProd` delimitado por `.`)

3 arquivos da pasta `XML_SELECIONADO` (`KL_INDUSTRIA_E_COMERCIO_LTDA_2025-06-10_NFe-...7287.XML`, `KL_INDUSTRIA_E_COMERCIO_LTDA_2026-02-05_NFe-...0910.XML`, `KL_INDUSTRIA_E_COMERCIO_LTDA_2026-02-05_NFe-...4598.XML`), **125 itens** (51 + 41 + 33). Tênis de skate da marca **Redikal**.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 42819383000174 | KL INDUSTRIA E COMERCIO LTDA |

`xProd` segue a estrutura genérica do Padrão B (`[TIPO] [MARCA] [MODELO...] [COR] [TAMANHO]`, sem código de cor) — a única particularidade é que `cProd` usa `.` como delimitador (`[COD_MODELO].[COD_COR].[TAMANHO]`, mesmo esquema dos Padrões V/W) e que o nome do modelo (`REDIKAL`) e o código interno do fornecedor (`RKT406`, `RKT520` etc., que bate com o 1º segmento do `cProd`) ficam **entre** o material do solado e a `COR`, sem tentar separá-los do `NOME_PRODUTO` (mesma cautela do Padrão H/W — só remove o que tem posição/marcador comprovado, e aqui não há nenhum):

| xProd | NOME_PRODUTO | COR | TAMANHO | cProd | REFERENCIA_PRODUTO |
|---|---|---|---|---|---|
| `TENIS AD MASC SKATE SINTETICO SOLA TR RKT406 REDIKAL PRETO/PRETO 34` | `TENIS AD MASC SKATE SINTETICO SOLA TR RKT406 REDIKAL` | `PRETO/PRETO` | `34` | `RKT406.03.34` | `RKT406.03` |
| `TENIS AD MASC SKATE SINTETICO SOLADO BORRACHA RKT496 REDIKAL PRETO/BRANCO 34` | `TENIS AD MASC SKATE SINTETICO SOLADO BORRACHA RKT496 REDIKAL` | `PRETO/BRANCO` | `34` | `RKT496.09.34` | `RKT496.09` |
| `TENIS AD MASC SKATE SINTETICO SOLADO BORRACHA RKT520 BRANCO/CINZA/GRAFITE 34` | `TENIS AD MASC SKATE SINTETICO SOLADO BORRACHA RKT520` | `BRANCO/CINZA/GRAFITE` | `34` | `RKT520.06.34` | `RKT520.06` |
| `TENIS AD MASC SKATE SINTETICO SOLADO BORRACHA RKT570 PRETO/REFLETIVO 35` | `TENIS AD MASC SKATE SINTETICO SOLADO BORRACHA RKT570` | `PRETO/REFLETIVO` | `35` | `RKT570.01.35` | `RKT570.01` |

⚠️ **A marca `REDIKAL` aparece só em alguns modelos** (`RKT406`, `RKT496`), entre o código do fornecedor e a `COR` — não há marcador que a distinga da `COR`, então ela fica no `NOME_PRODUTO` (a regra genérica do Padrão B já resolve corretamente, pois `COR` é sempre o último token antes do `TAMANHO`, e `REDIKAL` nunca é esse último token).

⚠️ **Roteamento especial na detecção:** os itens do modelo `RKT406` têm o token isolado `SOLA` (`SOLA TR`) — se não fosse tratado como exceção, colidiria com o gatilho do Padrão D (que tentaria cortar a partir de `SOLA`, produzindo um bloco de cor errado com 4+ palavras). Como a `COR` aqui já está confiável na posição padrão do Padrão B, a extração usa `NOME_FORNECEDOR = KL INDUSTRIA E COMERCIO LTDA` como gatilho para pular direto para a regra genérica do Padrão B (mesma cautela já usada para IND E COM DE CALC TRENTO LTDA e INDUSTRIA E COMERCIO LEJON LTDA, que têm o mesmo problema).

`CODIGO_BARRAS` = `cEAN` real (GTIN) em 100% dos itens testados. `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens (`<vDesc>` não existe em nenhum `<det><prod>`, só nos totais da nota). `<infAdProd>` existe mas só traz texto fiscal fixo (Resolução do Senado + número da FCI), não usado na extração — mesmo caso da DILLY NORDESTE. Cores encontradas: `PRETO/PRETO`, `PRETO/LATEX`, `BRANCO/CINZA/GRAFITE`, `PRETO/CARAMELO`, `CINZA/MARINHO/BORDO`, `PRETO/BRANCO`, `PRETO/REFLETIVO`, `CARAMELO/PRETO` — `LATEX` e `REFLETIVO` são cores/acabamentos novos, sem exigir whitelist (Padrão B não usa whitelist).

### Fornecedor 30 — LONG FEET - LTDA (Padrão X, produtos de cuidado para calçados)

2 arquivos da pasta `XML_SELECIONADO` (`LONG_FEET___LTDA_2025-06-27_NFe-...2500.XML`, `LONG_FEET___LTDA_2025-07-02_NFe-...2507.XML`), **28 itens** (14 + 14, mesmos produtos repetidos nos 2 arquivos). Sétima categoria não-calçado do projeto — esponjas, calcanheiras, palmilhas e sprays de tratamento para calçados (NCM `96162000`, `64069020`, `3809...`).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 21679438000118 | LONG FEET - LTDA |

Exemplo (produto sem variação, calcanheira com `TAM`, palmilha com `TAM` e `COR`, meia sem `TAM` → regra geral 4):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| 121 | ESPONJA MÁGICA PARA CALÇADOS QUADRADA | (vazio) | (vazio) |
| 180 | CALCANHEIRA ANTI IMPACTO ESPORTIVA FLEX UNISSEX | (vazio) | P |
| 146 | PALMILHA DE AJUSTE | AREIA | M |
| 176 | MEIA ANTI RACHADURA EM SILICONE | (vazio) | UN |

`cProd` (`121`, `180`, `146`, `176` etc.) é sempre um código numérico curto sem `-`/`_`/`.`, então nunca é cortado — cada tamanho de calcanheira já tem seu próprio código (`180`=P, `181`=M, `182`=G). `CODIGO_BARRAS` = `cEAN` real em 100% dos itens. `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` não existe no XML). Não existe `<infAdProd>`. Único item com `COR` preenchida no lote: `PALMILHA DE AJUSTE TAM M COR AREIA` (`COR = AREIA`). Os 2 itens `MEIA...` (`176`, `122`) não têm marcador `TAM` no `xProd`, mas por começarem com `MEIA` recebem `TAMANHO = "UN"` pela regra geral 4 — a `PALMILHA ANTIBACTERIANA RECORTAVEL` e a `PALMILHA MASSAGEADORA 2 EM 1 FLEX RECORTÁVEL` (que também não têm `TAM`) ficam com `TAMANHO` vazio, porque `PALMILHA` não é uma categoria "tamanho único" coberta pela regra.

### Fornecedor 31 — MACBOOT INDUSTRIA E COMERCIO DE CALCADOS (Padrão Y)

3 arquivos da pasta `XML_SELECIONADO` (`MACBOOT_INDUSTRIA_E_COMERCIO_DE_CALCADOS_2024-05-29_NFe-...4906.XML`, `MACBOOT_INDUSTRIA_E_COMERCIO_DE_CALCADOS_2024-05-29_NFe-...5006.XML`, `MACBOOT_INDUSTRIA_E_COMERCIO_DE_CALCADOS_2024-08-21_NFe-...4540.XML`), **110 itens** (47 + 62 + 1). Botas de couro/nobuck.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 68403583000135 | MACBOOT INDUSTRIA E COMERCIO DE CALCADOS |

Exemplo (código de modelo "quebrado", cor com material não-cor no meio, cor com modificador prefixado `OIL`, item de PDV):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| 7894484459637 | CA00 01-UIRAPURU EMBORRACHADO | GRAFITE | 38 |
| 7894484692362 | CASTANHEIRA 02 - CARAJAS NOBUCK | CAFE | 37 |
| 7894484661719 | CUMARU 02 - ARAXA C/HORSE | BROWN | 38 |
| 7894484638605 | UIRAPURU 06-PATAXOS NOBUCK | OIL BROWN | 38 |
| 7894484710646 | PDV INSTITUCIONAL 2024 C.A 2024 | (vazio) | 1 |

`cProd` é sempre idêntico ao `cEAN` (GTIN completo) — por isso `REFERENCIA_PRODUTO` **nunca se repete** entre tamanhos do mesmo modelo/cor neste fornecedor (diferente de quase todos os outros padrões), já que o verdadeiro código de modelo+cor (ex.: `CA0001-EB06`) fica só dentro do `xProd`, descartado como `SKU_INTERNO`. `VALOR_PRODUTO_DESCONTO` real e não-zero em todos os itens testados. `<infAdProd>` traz o par `TAMANHO/QUANTIDADE` (ex.: `38/1`), usado só como validação cruzada — bateu 100% com o `TAMANHO` do marcador `N.` e o `qCom` de cada item nos 110 itens testados.

### Fornecedor 32 — NEW BRASIL ARTIGOS ESPORTIVOS LTDA (Padrão P, distribuidora New Balance)

3 arquivos da pasta `XML_SELECIONADO` (`NEW_BRASIL_ARTIGOS_ESPORTIVOS_LTDA_2024-02-21_NFe-...5103.XML`, `NEW_BRASIL_ARTIGOS_ESPORTIVOS_LTDA_2026-03-13_NFe-...4833.XML`, `NEW_BRASIL_ARTIGOS_ESPORTIVOS_LTDA_2026-03-13_NFe-...4839.XML`), **53 itens** (27 + 12 + 14). Distribuidora oficial da New Balance no Brasil — segundo fornecedor a usar exatamente a estrutura do Padrão P (depois da FILA BRASIL), confirmando que o gatilho de detecção (marcador `Tam:` no fim do `xProd`) é mesmo estrutural, não específico de um fornecedor.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 45075049000303 | NEW BRASIL ARTIGOS ESPORTIVOS LTDA |

Exemplo (cor simples, bicolor, tricolor, `CODIGO_COR` alfanumérico):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|
| 1271082 | TENIS NB 460 V4 MASCULINO | PTO | PTO | PTO | PTO | 38 |
| 1115522 | TENIS NB 500V2 MASCULINO | PTO/VRME | PTO | VRME | VRME | 39 |
| 1350281 | TENIS NB FRESH FOAM ARISHIV4 FEMININO | AZUL/MRNH/AZLC | AZUL | MRNH | AZLC | 34 |

`xProd` bruto do segundo exemplo: `GM500-TENIS NB 500V2 MASCULINO-PTO/VRME-GM500BA2-Tam:39` — o 1º campo (`GM500`) e o 4º campo (`GM500BA2`, `CODIGO_COR`) são descartados; `cProd` (`1115522`) é sempre numérico, sem `-`, sem relação com o `COD_MODELO` alfanumérico do `xProd` — `REFERENCIA_PRODUTO` nunca é cortado. Diferente da FILA, o `CODIGO_COR` aqui é alfanumérico (`GM500BA2`, `M460ZK4`, `MARISZ4D`, `WARIS3PC`), não puramente numérico — não muda o algoritmo, só confirma que o campo é descartado independente do formato. `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens (`<vDesc>` não aparece nos itens, só nos totais da nota). `<infAdProd>` só traz texto fiscal fixo (Resolução do Senado + FCI), não usado na extração.

### Fornecedor 33 — NOVOPE CALCADOS LTDA (Padrão Z, tênis infantil marca própria, sem TAMANHO no XML)

3 arquivos da pasta `XML_SELECIONADO` (`NOVOPE_CALCADOS_LTDA_2024-02-08_NFe-...0023.XML`, `NOVOPE_CALCADOS_LTDA_2024-02-08_NFe-...0044.XML`, `NOVOPE_CALCADOS_LTDA_2024-02-08_NFe-...0065.XML`), **23 itens** (8 + 8 + 7).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 01688276000177 | NOVOPE CALCADOS LTDA |

Os 3 arquivos têm a mesma data de emissão (2024-02-08), o mesmo `LOTE`/`ORDEM COMPRA` no `infAdic` (`LOTE: 24060101`, `ORDEM COMPRA: 95`), mas `PEDIDO`/`nNF` distintos (`89752`/pedido `53318`, `89754`/pedido `53314`, `89756`/pedido `53326`) — são 3 notas fiscais diferentes do mesmo lote de fábrica, não uma nota reemitida. Por coincidência, 7 dos 8 itens se repetem idênticos (mesmo `cProd`, `xProd`, `qCom`, `vProd`) nos 3 arquivos; o 3º arquivo não traz o item `50001022_1309` (mantido como veio, sem tentar completar).

Exemplo (produto tricolor e cor com qualificador de 2 palavras):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|---|---|
| 10181 | (vazio) | C/BORDADO TENIS INFANTIL TEXTIL | 80001290_1710 C/BORDADO TENIS INFANTIL TEXTIL PRETO/PINK FLUOR/OFF WHITE | PRETO/PINK FLUOR/OFF WHITE | PRETO | PINK FLUOR | OFF WHITE | (vazio) |
| 10303 | (vazio) | NOVOPE TENIS INFANTIL SINTETICO | 50001022_1309 NOVOPE TENIS INFANTIL SINTETICO BRANCO/ROSA BB | BRANCO/ROSA BB | BRANCO | ROSA BB | ROSA BB | (vazio) |

`cProd` (`9759`, `10174`, `10181`, `10303`, `11328`, `11539`, `11552`, `12309`) nunca tem `-`/`_`/`.`, então fica igual ao original — sem relação com o prefixo `COD_MODELO_COD_COR` do `xProd` (`32001170_1702`, `80001290_3` etc.), que é sempre descartado. `CODIGO_BARRAS` vazio em 100% dos itens (`cEAN = SEM GTIN`). `VALOR_PRODUTO_DESCONTO` real e não-zero em todos os itens testados. `TAMANHO` vazio em 100% dos itens — decisão do usuário (venda em grade, ver "Caso resolvido" acima).

### Fornecedor 34 — PUMA SPORTS LTDA (Padrão AA, cor separada por `-` no xProd)

3 arquivos da pasta `XML_SELECIONADO` (`PUMA_SPORTS_LTDA_2024-02-07_NFe-...1490.XML`, `PUMA_SPORTS_LTDA_2025-02-19_NFe-...2067.XML`, `PUMA_SPORTS_LTDA_2026-02-27_NFe-...4256.XML`), **34 itens** (11 + 8 + 15).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 05406034002300 | PUMA SPORTS LTDA |
| 05406034005163 | PUMA SPORTS LTDA (filial diferente, mesma raiz `05406034`) |

Exemplo (3 cores, com marcador `BDP`; e 2 cores, mesmo modelo em duas colorways diferentes — uma com prefixo de marca na 1ª cor, outra na 2ª):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | COR_1 | COR_2 | COR_3 | TAMANHO |
|---|---|---|---|---|---|---|---|---|
| 3755640134 | 4063696124128 | CARINA BDP | CALCADOS-CARINA BDP Puma Black-Puma Black-Puma Silver 34 | Puma Black/Puma Black/Puma Silver | Puma Black | Puma Black | Puma Silver | 34 |
| 4042280237 | 4067984993120 | PARK LIFESTYLE EASY SD BDP | CALCADOS-PARK LIFESTYLE EASY SD BDP Cool Light Gray-PUMA White 37 | Cool Light Gray/PUMA White | Cool Light Gray | PUMA White | PUMA White | 37 |
| 4042285137 | 4067984992246 | PARK LIFESTYLE EASY SD BDP | CALCADOS-PARK LIFESTYLE EASY SD BDP PUMA Black-Frosted Ivory 37 | PUMA Black/Frosted Ivory | PUMA Black | Frosted Ivory | Frosted Ivory | 37 |

`cProd` (`3755640134`, `4042280237`, `4042285137` etc.) nunca tem `-`, então fica igual ao original — apesar de os 2 últimos dígitos baterem exatamente com o `TAMANHO` em 100% dos 34 itens (mesmo comportamento "quase corta mas não corta" documentado para o Padrão AA acima). `CODIGO_BARRAS` real (GTIN-13) e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens.

### Fornecedor 35 — SAPATARIA BERTELLI IND. E COMERCIO (Padrão AB, COR = última palavra, sem TAMANHO)

2 arquivos da pasta `XML_SELECIONADO` (`SAPATARIA_BERTELLI_IND_E_COMERCIO_2024-02-27_NFe-...1021.XML`, `SAPATARIA_BERTELLI_IND_E_COMERCIO_2025-11-12_NFe-...4766.XML`), **23 itens** (11 + 12).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 17943122000114 | SAPATARIA BERTELLI IND. E COMERCIO |

Exemplo (mesmo `cProd` em cores diferentes, sufixo alfanumérico no código de modelo):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|---|
| 70199 | (vazio) | SAPATO MASC 70.199 CONFORT | SAPATO MASC 70.199 CONFORT CAFE | CAFE | (vazio) |
| 70199 | (vazio) | SAPATO MASC 70.199 CONFORT | SAPATO MASC 70.199 CONFORT PRETO | PRETO | (vazio) |
| 47000 | (vazio) | SAPATO MASC 47.000-SP CONFORT | SAPATO MASC 47.000-SP CONFORT WHISKY | WHISKY | (vazio) |

`cProd=70199` se repete nas duas primeiras linhas com `COR` diferente (`CAFE` e `PRETO`) — comportamento esperado deste fornecedor (`cProd` reflete só o modelo, não a cor, ver "Padrão AB" acima). `CODIGO_BARRAS` vazio em 100% dos itens (`cEAN = SEM GTIN`). `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` não existe no XML). `TAMANHO` vazio em 100% dos itens — decisão do usuário (venda em grade, ver "Caso resolvido" acima).

### Fornecedor 36 — SKECHERS DO BRASIL CALCADOS LTDA (Padrão AC, TAMANHO/cor via cProd de 4 segmentos)

2 arquivos da pasta `XML_SELECIONADO` (`SKECHERS_DO_BRASIL_CALCADOS_LTDA_2025-11-06_NFe-...1572.XML`, `SKECHERS_DO_BRASIL_CALCADOS_LTDA_2026-06-30_NFe-...1142.XML`), **87 itens** (60 + 27).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 08562929000469 | SKECHERS DO BRASIL CALCADOS LTDA |

Exemplo (com e sem submodelo no `xProd`):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|---|
| BOS-117617-LTPK | 198376868963 | BOBS B LOVE - TRUE DELIGHT | BOBS B LOVE - TRUE DELIGHT | (vazio) | 34 |
| GOM-894343BR-BBK | 197976717787 | GO WALK FLEX | GO WALK FLEX | (vazio) | 38 |
| R-150051BR-BKRG | 198376979874 | ARCH FIT 2.0 - BIG LEAGUE | ARCH FIT 2.0 - BIG LEAGUE | (vazio) | 34 |

`cProd` (`BOS-117617-LTPK-34`, `GOM-894343BR-BBK-38`, `R-150051BR-BKRG-34` etc.) sempre tem 4 segmentos delimitados por `-`; o último (`TAMANHO`) é sempre cortado para formar `REFERENCIA_PRODUTO`, já que foi extraído dali mesmo (sempre bate, por definição). `CODIGO_BARRAS` real (GTIN/UPC) em 100% dos itens. `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` não existe no XML). `COR` vazia e `COR_PRECISA_REVISAO = true` em 100% dos itens — não há nome de cor legível em nenhuma posição do XML, só o código abreviado (`LTPK`, `BBK`, `BKRG`...) mantido dentro de `REFERENCIA_PRODUTO`.

### Fornecedor 37 — STAMPA ARTEFATOS DE COURO LTDA (Padrão H + sub-caso STAMPA, e Padrão AE)

3 arquivos da pasta `XML_SELECIONADO` (`STAMPA_ARTEFATOS_DE_COURO_LTDA_2026-04-20_NFe-...4430.XML`, `STAMPA_ARTEFATOS_DE_COURO_LTDA_2026-05-08_NFe-...7732.XML`, `STAMPA_ARTEFATOS_DE_COURO_LTDA_2026-06-26_NFe-...3687.XML`), **101 itens** (86 + 6 + 9) — dois padrões diferentes no mesmo fornecedor, dependendo da categoria do produto.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 73795486000507 | STAMPA ARTEFATOS DE COURO LTDA |

Calçados (Padrão H, 92 itens — 86 + 6):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| 22227 | BOTA SAARA | PRETO | 34 |
| 22231 | SCARPIN SAARA/SAARA | PRETO/PRETO | 33 |
| 89646 | SCARPIN ATACAMA | CACAU | 34 |
| 56970006 | BOTA MONTENAPOLEONE | AMBAR | 34 |

Bolsas/mochilas (Padrão AE, 9 itens):

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| 23801 | BOLSAS NEW RIDGE | PRETO | UNI |
| 24793 | BOLSAS NEW RIDGE/NEW RIDGE/SAARA | PRETO/PRETO/AMENDOA | UNI |
| 77795 | MOCHILA FEMININA MONOGRAMA LL/CALF | MOCCA/COHIBA I | UNI |

`cProd` (`22227`, `89646`, `23801` etc.) nunca tem `-`/`_`/`.`, então fica igual ao original em ambos os sub-grupos. `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` ausente) em 100% dos 101 itens.

### Fornecedor 38 — VIA VIP CALCADOS LTDA (Padrão AD, sufixo REF descartável)

3 arquivos da pasta `XML_SELECIONADO` (`VIA_VIP_CALCADOS_LTDA_2024-04-16_NFe-...2866.XML`, `VIA_VIP_CALCADOS_LTDA_2025-09-19_NFe-...3997.XML`, `VIA_VIP_CALCADOS_LTDA_2026-06-25_NFe-...6679.XML`), **236 itens** (102 + 76 + 58).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 01119204000109 | VIA VIP CALCADOS LTDA |

Exemplo:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|---|
| VV2800.BL | 7908591125481 | TENIS STREET BB EM SINTETICO C/ VELCRO E SILK ESTRELA | TENIS STREET BB EM SINTETICO C/ VELCRO E SILK ESTRELA BRANCO/LILAS 20 REF VV2800.BL.20 | BRANCO/LILAS | 20 |
| VV2822.BP | 7908761749943 | TENIS STREET BB EM SINTETICO COM VELCRO E CORACAO LATERAL | TENIS STREET BB EM SINTETICO COM VELCRO E CORACAO LATERAL BRANCO/PRATA 19 REF VV2822.BP.19 | BRANCO/PRATA | 19 |
| VV2519.105 | 7901057631212 | TENIS JOGGING BB EM SINTETICO C/CORACAO E VELCRO | TENIS JOGGING BB EM SINTETICO C/CORACAO E VELCRO PRETO/PINK 19 REF VV2519.105.19 | PRETO/PINK | 19 |

`cProd` (`VV2800.BL.20` etc.) sempre é cortado no último segmento (delimitador `.`), que sempre bate com o `TAMANHO` extraído. `CODIGO_BARRAS` real, `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens. Todos os 236 itens contêm o token isolado `EM` — sem o gatilho específico deste padrão checado antes do sub-caso CALCADOS MARTE, a extração teria falhado silenciosamente em toda a base (ver "Padrão AD" acima).

### Fornecedor 39 — NOVAPELLI IND. COM. IMP. EXP. LTDA (Padrão AF, xProd truncado em largura fixa)

3 arquivos da pasta `XML_SELECIONADO` (`NOVAPELLI_IND_COM_IMP_EXP_LTDA_2024-03-26_NFe-...3349.XML`, `NOVAPELLI_IND_COM_IMP_EXP_LTDA_2025-05-05_NFe-...8960.XML`, `NOVAPELLI_IND_COM_IMP_EXP_LTDA_2026-06-30_NFe-...2482.XML`), **148 itens** (56 + 34 + 58) — 127 `CINTO` + 21 `CARTEIRA`.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 00121821000186 | NOVAPELLI IND. COM. IMP. EXP. LTDA |

Exemplo `CINTO` (TAMANHO só recuperável do `cProd`):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|---|
| G029019751.T090 | 7909190020917 | CINTO 35 SOC AFRO | CINTO 35 SOC PRETO   AFRO  PCA TAM | PRETO | 090 |
| G216374751.T105 | 7890259075194 | CINTO 40 ESP SMALB | CINTO 40 ESP PRETO   SMALB PCA TAM | PRETO | 105 |
| G029019751.T115 (arquivo 2026) | 7909190032873 | CINTO 35 REV CAPRE | CINTO 35 REV PTO/CAF CAPRE PCA | PTO/CAF | 115 |

Exemplo `CARTEIRA` (TAMANHO sempre `"UN"`, decisão do usuário):

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | NOME_PRODUTO_ORIGINAL | COR | TAMANHO |
|---|---|---|---|---|---|
| H060143751C | 7909190937666 | CARTEIRA MAS CAPRE C12 | CARTEIRA MAS PRETO   CAPRE C12 | PRETO | UN |
| H013143101C | 7909190013254 | CARTEIRA MAS CAPRE C12 | CARTEIRA MAS         CAPRE C12 | (vazio) | UN |
| H062229101C | 7892577076266 | CARTEIRA MAS CA/SK C12 | CARTEIRA MAS CAFE    CA/SK C12 | CAFE | UN |

`cProd` nunca é cortado em nenhum dos dois sub-casos (ver "Padrão AF" acima). `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` ausente) em 100% dos 148 itens.

### Fornecedor 40 — ZETI COMERCIO E IMPORTACAO E ART DO VEST LTDA (Padrão AG, marca com marcador de coleção)

3 arquivos da pasta `XML_SELECIONADO` (`ZETI_COMERCIO_E_IMPORTACAO_E_ART_DO_VEST_LTDA_2024-12-11_NFe-...7700.XML`, `ZETI_COMERCIO_E_IMPORTACAO_E_ART_DO_VEST_LTDA_2025-04-28_NFe-...1968.XML`, `ZETI_COMERCIO_E_IMPORTACAO_E_ART_DO_VEST_LTDA_2026-03-13_NFe-...9182.XML`), **68 itens** (32 + 21 + 15).

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 11969736000199 | Zeti Comercio e Importacao e Art do Vest LTDA |

Exemplo:

| REFERENCIA_PRODUTO | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|
| P2086 | Bolsa Feminina Zeti ZT 1950 | Preto | UN |
| P1997 | Bolsa Feminina Zeti ZT 1931 | Preto Nylon | UN |
| P2179 | Carteira Feminina Zeti CA 1903 | Preto | UN |
| P2105 | Bolsa/Carteira Zeti ZT 1958 | Caqui | UN |
| P2041 | Mala de bordo c/ rodinhas ABS 8079 | (vazio) | UN |

`cProd` (`P2086`, `P1997`, `P2041` etc.) sempre `P` + dígitos, sem delimitador, então fica igual ao original — sem relação numérica com o `COD_MODELO` do `xProd`. `CODIGO_BARRAS` vazio em 100% dos itens (`cEAN = SEM GTIN`). `VALOR_PRODUTO_DESCONTO = 0.00` (`<vDesc>` não existe no XML). `TAMANHO = "UN"` em 100% dos itens — decisão do usuário (`BOLSA`/`CARTEIRA` pela regra geral 4 reconfirmada, `MALA` por decisão pontual).

### Fornecedor 41 — LUPO NORDESTE LTDA. / LUPO S/A (Padrão N estendido, meias e 1 cueca isolada)

3 arquivos da pasta `XML_SELECIONADO` (`LUPO_NORDESTE_LTDA_2024-03-29_NFe-...7700.XML`, `LUPO_NORDESTE_LTDA_2025-04-17_NFe-...4513.XML`, `LUPO_S_A_2026-05-30_NFe-...4409.XML`), **29 itens** (4 + 13 + 12) — 2 CNPJs distintos do mesmo grupo.

| CNPJ_FORNECEDOR | NOME_FORNECEDOR |
|---|---|
| 01933349000572 | LUPO NORDESTE LTDA. |
| 43948405000169 | LUPO S/A |

Exemplo:

| REFERENCIA_PRODUTO | CODIGO_BARRAS | NOME_PRODUTO | COR | TAMANHO |
|---|---|---|---|---|
| 03225-0891400931 | 7909392899809 | Meia Lupo AU Sport Kit3 | (vazio) | UN |
| 01450-0061707570 | 7900373053999 | Meia Lupo AU Bambu | (vazio) | UN |
| 04450-0121709990 | 7891186498780 | Meia 3/4 Lupo AF Basic | (vazio) | UN |
| 00436-0021309950 | 7909392776148 | Cueca Lupo AM Boxer Microf. | (vazio) | UN |

`cProd` (`03225-0891400931` etc.) tem `-`, mas nunca é cortado (o segmento final nunca bate com `TAMANHO`, que é sempre `"UN"`). `CODIGO_BARRAS` real e `VALOR_PRODUTO_DESCONTO = 0.00` em 100% dos itens. `TAMANHO = "UN"` e `COR_PRECISA_REVISAO = true` em 100% dos 29 itens — 28 `Meia` pela regra geral 4 reconfirmada pelo usuário, 1 `Cueca` por decisão pontual do usuário nesta rodada.

## Observação técnica

O arquivo XML fica em uma única linha muito longa (~69 mil caracteres), o que dificulta a leitura manual linha a linha. A extração confiável foi feita via parsing XML nativo (`[xml]` do PowerShell / equivalente em outras linguagens), navegando pelos nós `//emit` e `//det/prod` com o namespace `http://www.portalfiscal.inf.br/nfe`.

## Próximos passos em aberto

- ~~Tratar o ruído no COR da BEIRA RIO~~ — **resolvido** com a lista de cores válidas (whitelist), aplicada só ao Padrão C.
- ~~Separar COR em 3 colunas do sistema~~ — **resolvido**: `COR_1`/`COR_2`/`COR_3` adicionadas nos dois CSVs, com a regra de repetição quando há menos de 3 cores.
- Manter a lista de cores válidas atualizada conforme novos fornecedores/cores aparecerem (hoje cobre as cores vistas nos 5 fornecedores testados; monitorar a coluna `COR_PRECISA_REVISAO` em novos lotes para saber quando precisa crescer).
- Decidir o que fazer se aparecer um item com mais de 3 cores separadas por `/` (hoje a lógica simplesmente ignora a 4ª cor em diante — nunca ocorreu nos 253 itens testados).
- ~~Avaliar o caso do Padrão B com "falsas cores" no meio de um código de referência (ex.: `I23/BEGE/MIL` da CALCADOS MARTE)~~ — **resolvido**: descoberto o marcador `EM [MATERIAL]` no `xProd` da CALCADOS MARTE, que separa de forma confiável `COR` (real) de `CODIGO_COR` (código de referência descartável) — ver "Fornecedor 4 — CALCADOS MARTE". O exemplo antigo do documento estava com `COR`/`NOME_PRODUTO` invertidos e foi corrigido.
- Definir se o processamento será em lote para todos os XMLs da pasta `XML_SELECIONADO/`.
- Continuar validando os 33 padrões em mais arquivos/fornecedores (42 fornecedores, todos processáveis, 131 arquivos, 3261 itens testados até agora; podem existir outros padrões ainda não vistos, inclusive de outras categorias de produto além de calçados/mochilas/acessórios esportivos/bolsas de couro/meias/bonés/cuidados para calçados).
- **Padrão N estendido (LUPO):** só testado em 3 arquivos/29 itens até agora. Validar em lotes futuros se os códigos `AU`/`AM`/`AF`/`KM` (gênero/faixa etária, não decodificados) se mantêm consistentes, e se aparecem outros `TIPO` além de `Meia`/`Cueca` para este fornecedor (o gatilho hoje é por `NOME_FORNECEDOR`, então qualquer produto novo do grupo LUPO cairia no mesmo Padrão N — vale conferir se isso é sempre correto ou se algum dia aparece um produto LUPO com tamanho real disponível no XML, que deveria prevalecer sobre o `"UN"`).
- **Padrão AG (ZETI):** só testado em 3 arquivos/68 itens até agora (mesmo fornecedor). Validar em lotes futuros se aparece uma cor bicolor (com `/`), um `TIPO` além de `Bolsa`/`Carteira`/`Bolsa-Carteira`/`Mala`, ou outro item sem marcador `ZT`/`CA` (o algoritmo hoje só tem uma regra genérica de fallback para o caso `MALA` específico, sem generalizar). A decisão de `TAMANHO = "UN"` para `MALA` foi pontual para este item — se aparecer outro fornecedor de malas com tamanho real disponível em alguma posição do XML, esse valor prevalece (não é uma nova categoria "tamanho único" da regra geral 4).
- **Padrão AF (NOVAPELLI):** só testado em 3 arquivos/148 itens até agora (mesmo fornecedor). Validar em lotes futuros se aparecem outros `TIPO` além de `CINTO`/`CARTEIRA` (o algoritmo hoje só reconhece esses dois), se `CARTEIRA` alguma vez traz `SEXO = FEM` (só `MAS` visto até agora), e se a truncagem de largura fixa do `CINTO` algum dia preserva o valor completo depois de `TAM` (o que tornaria o `cProd` desnecessário para o `TAMANHO`, mas não quebraria nada se continuar assim). O item isolado com `NCM = 62171000` (fora do padrão `42033000`/`42023100`) não foi investigado a fundo — monitorar se se repete.
- **Padrão B / VULCABRAS — `COR` opaca ou híbrida:** 3 arquivos novos (15 itens) revelaram que a posição de `COR` no Padrão B pode trazer um código não-legível (`ALGPSC`) ou uma cor colada a um número (`ROSA46`), dependendo da marca/linha do produto (MIZUNO, OLYMPIKUS RUSH), sem que isso seja um erro de extração — o valor bate exatamente com o segmento do `cProd`. Hoje gravado como veio, sem tentar decompor nem sinalizar `COR_PRECISA_REVISAO` (Padrão B nunca usou essa flag). Se esses códigos se tornarem comuns em lotes futuros, avaliar com o usuário se vale a pena introduzir uma sinalização (`COR_PRECISA_REVISAO`) quando a `COR` do Padrão B contiver dígitos ou não parecer um nome de cor legível.
- **Padrão AD (VIA VIP):** validado em 3 arquivos/236 itens, todos com o marcador `REF [cProd]` e o token `EM` presentes — amostra grande e consistente (0 divergências). Monitorar em lotes futuros se aparece uma cor com qualificador de 2 palavras (regra já prevista no algoritmo, mas nunca exercitada aqui) ou algum item sem o sufixo `REF [cProd]` no fim (o que quebraria a detecção).
- **Padrão AE (STAMPA, bolsas/mochilas):** só testado em 1 arquivo/9 itens até agora — amostra mínima. Validar em lotes futuros se a técnica de "último token define a cor, com extensão de 1 palavra quando necessário" continua suficiente (ex.: um qualificador de 2+ palavras, ou um bloco de material/cor com contagem de segmentos diferente dos dois lados quebraria o algoritmo atual). Confirmar se `TIPO` sempre se limita a `BOLSAS`/`MOCHILA FEMININA`, ou se aparecem outras categorias (carteira, necessaire) que exigiriam ampliar o vocabulário fixo.
- **Sub-caso STAMPA (Padrão H):** técnica de "segmentação por espaço" para blocos de cor com 3+ segmentos validada só em 1 modelo/2 colorways (`TENIS KNIT/GORGORAO/.../GORGORAO`). Monitorar se aparece um qualificador de 2+ palavras no último segmento (a técnica atual só suporta 1 palavra extra) ou um bloco de material que também termine em palavra solta sem `/` (ambiguidade não resolvida, ver limitação documentada na seção do sub-caso).
- **Padrão AC (SKECHERS):** só testado em 2 arquivos/87 itens até agora, sempre com `cProd` em exatamente 4 segmentos e `TAMANHO` sempre inteiro (34-43). Validar em lotes futuros se aparece um `cProd` com número diferente de 4 segmentos (o que quebraria a extração do `TAMANHO`), um `TAMANHO` decimal/meio-número (mesmo caso já visto na Nike/Padrão Q), ou algum item com `<infAdProd>` preenchido. Avaliar também se vale a pena montar, no futuro, uma tabela de tradução para os códigos de cor mais óbvios (`BLK`→preto, `WHT`→branco, `NVY`→marinho) — hoje `COR` fica sempre vazia por decisão de não traduzir parcialmente sem cobrir todos os códigos vistos (`BKCC`, `LGMT`, `NTGY`, `TPE` não têm leitura confiável).
- **Padrão Z (NOVOPE):** só testado em 3 arquivos/23 itens até agora (mesmo lote de fábrica). Validar em lotes futuros se o marcador fixo `TENIS INFANTIL` se mantém (ou se aparecem outros `TIPO`/`FAIXA_ETARIA`, ex. `TENIS ADULTO`, que exigiriam generalizar o marcador), se sempre falta TAMANHO (ou se algum lote futuro traz `infAdProd` preenchido com o dado), e se o sufixo `" 2024"` grudado a uma cor (`LIMAO 2024`) se repete (confirmando ser parte do nome comercial da cor) ou aparece isolado (sugerindo ruído a tratar à parte). Confirmar também se o `cProd` continua sempre curto/numérico e sem relação com o prefixo `COD_MODELO_COD_COR` do `xProd` em lotes futuros.
- **Padrão AA (PUMA SPORTS):** só testado em 3 arquivos/34 itens até agora (2 filiais, 6 combinações modelo+cor). Validar em lotes futuros se aparece algum item **sem** o marcador `BDP` **e sem** `Puma`/`PUMA` no 1º segmento (cenário não coberto — hoje o algoritmo não teria como separar `NOME_PRODUTO` de `COR_1` com segurança nesse caso) e se aparece um bloco de cor com 4+ segmentos separados por `-` (só vistos casos de 2 e 3 até agora). Monitorar também se a grafia `Puma`/`PUMA` (maiúscula/minúscula inconsistente entre os 3 arquivos) seria melhor normalizada no futuro, ou se deve continuar gravada como veio (decisão atual: manter como veio, mesma cautela do resto do documento).
- **Padrão AB (SAPATARIA BERTELLI):** só testado em 2 arquivos/23 itens até agora, mesmo fornecedor, mesma estrutura de `xProd` em 100% dos itens (`SAPATO MASC ...`). Validar em lotes futuros se aparece uma `COR` composta por mais de 1 palavra (o algoritmo hoje assume sempre 1 única palavra na última posição), um `xProd` que não comece com `SAPATO MASC` (ex. linha feminina/infantil com prefixo diferente), ou um `infAdProd` cujo somatório de pares `tamanho/quantidade` não bata com `qCom` (só validado 100% nos 23 itens testados). `TAMANHO = vazio` é decisão deliberada do usuário — se um dia for necessário desdobrar a grade por tamanho individual, será preciso revisitar essa decisão (mesma ressalva já feita para BEBECE/NOVOPE).
- **Padrão Y (MACBOOT):** só testado em 3 arquivos/110 itens até agora. Validar em lotes futuros se aparece uma cor de 3+ palavras, algum modificador prefixado além de `OIL`, ou algum `xProd` sem o marcador `N.` no fim (hoje o gatilho estrutural de apoio depende dele). Confirmar se `cProd` continua sempre idêntico ao `cEAN` em lotes futuros.
- **Padrão X (LONG FEET):** só testado em 2 arquivos/28 itens até agora (ambos com os mesmos 14 produtos). Validar em lotes futuros se aparece um item com `COR` composta (`/`) ou com `TAMANHO` numérico (hoje só letras `P`/`M`/`G`), e se o marcador `COR` sempre vem depois do `TAM` (não antes) quando os dois aparecem juntos.
- **KL INDUSTRIA E COMERCIO LTDA (Padrão B, marca Redikal):** só testado em 3 arquivos/125 itens até agora, todos com `xProd` no formato `TENIS AD MASC SKATE...`. Validar em lotes futuros se aparece um item feminino/infantil (`AD` pode não ser sempre `MASC`), e se a marca `REDIKAL` sempre aparece nessa posição fixa (entre o código do fornecedor e a COR) ou se pode faltar/variar de posição.
- **Sub-caso KIDY BIRIGUI (Padrão B, linha `HAPPY`):** a regra de "juntar a palavra final não-`/` ao segmento de cor anterior" só foi testada em 1 lote/3 arquivos (23 itens, todos da linha `HAPPY`). Validar em lotes futuros se aparece um qualificador de 3+ palavras (hoje só suporta 1 palavra extra), ou se outras linhas da KIDY (além de `HAPPY`) também usam esse formato.
- **Padrão V (SYG STAR):** só testado em 2 arquivos/65 itens até agora. Validar em lotes futuros se sempre há o marcador `" - TENIS"` logo após o `COD_MODELO`, se o modificador `TODO` continua sendo o único que vem antes da cor (em vez de depois), e se aparece algum item com 3 cores separadas por `/` (só vistos casos de 1 e 2 cores até agora). Confirmar também se `cProd` sempre segue exatamente `REF.[modelo].[cor].[tamanho]` (3 segmentos após `REF`) ou se pode variar.
- **Padrão W (LEJON):** só testado em 2 arquivos/134 itens até agora. Validar em lotes futuros se o marcador de referência antes do `COD_MODELO` sempre é `ORD`, `REF` ou `LEJON REF` (hoje mantido dentro do `NOME_PRODUTO` sem tentar removê-lo — se surgir uma 4ª variante isso não quebra a extração, só muda o que fica no nome), e se aparece algum item com mais de 2 cores separadas por `/` (só vistos casos de exatamente 2 cores até agora). Confirmar se `CODIGO_BARRAS` vazio continua restrito ao modelo `LJVU0252` ou se aparece em outros modelos/lotes.
- **Padrão Q (FISIA/Nike):** só testado em 2 arquivos/22 itens até agora, sempre com o formato `[2 letras][4 dígitos]-[3 dígitos]-[tamanho]` no `cProd`. Validar em lotes futuros se aparece um `cProd` fora desse formato (ex.: mais de 3 segmentos, ou código de estilo com formato diferente).
- **Padrão R (GRENDENE):** `TAMANHO` sempre vazio é uma decisão deliberada do usuário (dado realmente ausente), não uma tentativa de extração — se um dia surgir uma tabela de tamanhos da GRENDENE (catálogo, outro sistema) que relacione os sufixos numéricos do `cProd` ao tamanho real, avaliar se vale a pena revisitar essa decisão. Só testado em 3 arquivos/93 itens até agora.
- **Padrão S (TRENTO):** só testado em 2 arquivos/23 itens até agora, ambos da mesma nota fiscal (mesma data de emissão). Validar em lotes futuros de datas diferentes para confirmar que o padrão se mantém (sem COR nem TAMANHO, `cEAN` sempre `SEM GTIN`).
- **Padrão T (INDITEC — bonés):** só testado em 2 arquivos/2 itens até agora (uma linha de produto por nota, vendida a granel) — a amostra é mínima. Se aparecerem outros produtos além de `BONE`, ou cores/tamanhos reais em lotes futuros, o padrão precisa ser revisto.
- **Padrão U (GONCALVES):** só testado em 2 arquivos/46 itens até agora, sempre com `TIPO=TENIS` e `cProd` delimitado por `_`. Validar em lotes futuros se aparece um `TIPO` diferente de `TENIS`, ou um item com mais de 3 segmentos de cor separados por `/`.
- **Padrão P (FILA BRASIL + NEW BRASIL/New Balance):** já validado em 2 fornecedores distintos (6 arquivos/101 itens), confirmando que o gatilho de detecção (`Tam:\d+$`) é estrutural, não específico da FILA. Nenhuma sigla foi traduzida em nenhum dos dois (decisão consciente, mesma cautela do Padrão H com `PTO/PTO`) — se o usuário quiser traduzir siglas conhecidas (como foi feito no Padrão L da CAMBUCI), será preciso perguntar/definir a tabela de tradução. Validar em lotes futuros se aparece item com mais de 3 cores separadas por `/`, ou algum `xProd` com menos/mais de 5 campos delimitados por `-`.
- **Padrão O (DEMOCRATA):** só testado em 3 arquivos/69 itens até agora, todos com o mesmo formato de `infAdProd` (`COR - TAMANHO#`) e no máximo 2 cores por item. Validar em lotes futuros se aparece um item com 3 cores separadas por `/`, ou algum `infAdProd` que fuja do formato fixo `- \d+#` no fim (ex.: sem o `#`, ou com texto extra depois dele).
- **Sub-caso DILLY NORDESTE (Padrão H):** marcador `WC-\d+` testado só nos 3 arquivos/54 itens da DILLY até agora, sempre com `TIPO=TENIS` e `MATERIAL` de 1 palavra (`COURO`/`SINT`). Validar em lotes futuros se aparece um `TIPO` diferente de `TENIS`, um `MATERIAL` de mais de 1 palavra, ou um item com mais de 3 segmentos de cor separados por `/`.
- **Sub-caso COOPERSHOES (Padrão B):** marcador fixo `CHUCK TAYLOR ALL STAR` (± `LIFT`) testado só nos 3 arquivos/106 itens da COOPERSHOES até agora; se aparecer outro fornecedor de calçados licenciados com modelo fixo diferente, será preciso generalizar o marcador (hoje é uma frase hardcoded). Também não foi visto ainda nenhum segmento de cor com mais de 3 partes separadas por `/`.
- **Padrão M (CLASSE COURO):** só testado em 1 arquivo/25 itens até agora — validar em mais lotes se o `CODIGO_COR` sempre vem imediatamente antes da `COR` (posição fixa) e se o teste "alfanumérico misto" continua suficiente para reconhecê-lo sem colidir com números de modelo. Confirmar se `38/72` é mesmo comprimento de alça ajustável ou outra medida, se surgir alguma fonte de confirmação (catálogo do fornecedor).
- **Padrão N (CONDE DUCK):** `TAMANHO = "UN"` é uma simplificação deliberada (decisão do usuário), não uma tentativa de extração — se um dia surgir necessidade de saber o tamanho real (P/M/G/GG/PP/U) para separação de estoque, será preciso revisitar essa decisão com uma regra mais elaborada (ou aceitar a perda dessa informação). `COR` nunca é preenchida — se surgir uma tabela de tradução dos códigos numéricos de cor do fornecedor no futuro, avaliar se vale a pena usá-la.
- **Padrão K (PEGADA NORDESTE):** validar se o tipo genérico é sempre `BOTA FEMININA` em lotes futuros, ou se aparecem outros tipos (`SAPATO`, `SANDALIA` etc.) sem cor também — hoje o gatilho estrutural de apoio depende de `BOTA FEMININA` especificamente, mas o gatilho primário (nome do fornecedor) não tem essa limitação. Se um dia surgir uma tabela de cores do fornecedor (catálogo, outro sistema), avaliar se vale a pena preencher `COR` a partir dela.
- **CALCADOS MARTE (sub-caso `EM`):** validar em mais lotes se o `CODIGO_COR` (tudo entre `COR` e `TAMANHO`) sempre segue o padrão observado (0-2 tokens, às vezes reaproveitando palavras de cor dentro do próprio código, ex.: `I23/MARFIM I`) — hoje a regra descarta esse bloco inteiro sem tentar interpretá-lo, então mudanças na forma desse código não quebrariam a extração, só mudariam o que é descartado.
- ~~**Padrão C — separador `-` entre cores:** só confirmado em 1 arquivo/6 itens da BEIRA RIO até agora (`CRISTAL-PRATA-AURORA BOREAL`). Monitorar se aparece em mais lotes e se algum dia coexiste com `/` no mesmo bloco de cor~~ — **resolvido em 2026-07-17:** confirmado no arquivo `CALCADOS_BEIRA_RIO_S_A_2024-02-12_NFe-...901276990427.XML` que os dois separadores podem coexistir no mesmo bloco (30 itens, ex. `PRETO-CRISTAL/PRETO 01`, `CRISTAL-SILVER/BLACK DIAMOND/PRETO 01`). Regra generalizada: dividir por `[/\-]` simultaneamente (ver passo 2 do Padrão C) — compatível com todos os casos anteriores (só `/` ou só `-`). Novas cores confirmadas: `SILVER`, `BLACK DIAMOND`.
- Definir o formato/mecanismo de registro das linhas com `COR_PRECISA_REVISAO = true` (log, tabela de exceções, notificação para revisão manual — hoje só documentado que existe a coluna, mas o fluxo de revisão em si ainda não foi definido).
- ~~Regra geral 4 (produtos "tamanho único"): confirmar se aplicar a `CARTEIRA`/`BOLA` sem exemplo testado~~ — **resolvido/reconfirmado em 2026-07-10:** `MEIA`, `BOLSA`, `CARTEIRA`, `BOLA` (e categorias equivalentes, ex. `BONE`) usam `TAMANHO = "UN"` sempre que o tamanho não for informado, **de forma automática e sem perguntar ao usuário**, mesmo sem exemplo real ainda testado no XML — não é mais uma pendência de validação, é regra fixa a aplicar preventivamente pelo `TIPO` no início do `xProd` (mesma técnica já usada para `MEIA` no Padrão L/X e `BONE` no Padrão T). Quando `CARTEIRA`/`BOLA` aparecerem de fato em um lote, só resta confirmar que não há um `TAMANHO` real escondido em outra posição do XML antes de aplicar `"UN"` (checagem pontual, não uma decisão a se tomar de novo).
- Avaliar se cores compostas por mais de uma palavra sem `/` (ex.: "AZUL MARINHO" sem barra) aparecem em algum fornecedor — isso ainda quebraria a extração por posição fixa nos Padrões A/B/D (no Padrão E isso já é esperado e tratado via whitelist de modelo).
- Definir formato de saída (CSV, Excel, gravação direta em banco/FDB).
- ~~**Padrão D (A. GRINGS):** monitorar se outros SKUs além de `147304`/`147305`/`147308` também vêm com o trecho de material/cor vazio no XML~~ — **resolvido nesta rodada:** adicionado o fallback do passo 4b (usar a última palavra antes de `SOLA` como `COR` quando o bloco depois de `SOLA` vier vazio) — os 46 itens (14%) que antes ficavam com `COR_PRECISA_REVISAO = true` agora têm `COR = PTO`. Continua valendo monitorar se aparece algum SKU futuro sem **nenhuma** palavra antes de `SOLA` (único caso em que `COR` ainda ficaria vazia).
- Adicionar `FENDI` e `CAPUCCINO` a alguma whitelist de cores no futuro, caso um novo fornecedor de Padrão C use essas cores (hoje não é necessário — só aparecem no Padrão D, que não usa whitelist).
- **Padrão E (ALPARGATAS/Havaianas):** manter a lista de modelos (`SLIM, SPARKLE, POWER, POINT, DUAL, TRACK, WAVES, 2.0, ME`) atualizada conforme novos modelos Havaianas aparecerem em lotes futuros sem o marcador `FC`. Se um dia surgir uma fonte confiável para decodificar o tamanho a partir do `cProd` do Alpargatas (tabela interna, outro sistema), revisar a decisão de deixar `TAMANHO` em branco.
- **Padrão F (BR8):** só validado com `TAM UNICO` até agora — confirmar em lotes futuros se este fornecedor (ou outro que use o mesmo marcador `TAM`) também emite tamanhos numéricos nesse formato (ex.: `TAM 38`), e se a regra "TAMANHO pode ser texto" continua suficiente. Avaliar também se o campo de composição têxtil (`% MATERIAL`, hoje descartado) deveria virar uma coluna no futuro, já que é um dado limpo e estruturado (diferente do material do solado no Padrão D, que é mais livre).
- ~~Avaliar se o projeto vai tratar categorias de produto não-calçado (mochilas, e possivelmente outras) com os mesmos campos do schema atual~~ — **decisão confirmada com a segunda categoria (CAMBUCI/Penalty, Padrão L):** reaproveitar os mesmos campos (COR/TAMANHO/REFERENCIA), sem schema derivado por categoria. `TAMANHO` aceita texto livre quando necessário (faixa `39-44`, código de vestuário `P`/`M`/`G`/`GG`), não só números de calçado.
- **Padrão L (CAMBUCI):** monitorar novos lotes para descobrir o significado de `CH` e `CO` (hoje mantidos como vieram, com `COR_PRECISA_REVISAO = true`) e ampliar a tabela de tradução de siglas de cor conforme aparecerem (hoje só `PT`, `BC`, `AZ`). Validar se o teste "é sigla de cor" (1-2 códigos de 2-3 letras maiúsculas) não colide com abreviações de modelo em lotes futuros.
- **Padrão G (BIBI):** validar se o código numérico inicial (`1243005` etc.) e a palavra `CALCADOS` são realmente constantes em outros lotes futuros dessa marca (base do gatilho de detecção). Avaliar se cores em inglês (`FIRE`, `SUGAR`, `CARAMEL`, `TOMATE`, `IOGURTE`, `DENIM`) devem ser normalizadas/traduzidas no futuro ou mantidas como vieram (hoje mantidas como veio do XML, sem tradução).
- **Padrão H (BOTTERO):** a suposição de que os dois lados de uma cor bicolor multi-palavra são espelhados em quantidade de palavras (`DARK BROWN 2011/DARK BROWN 2011`) só foi testada em 1 caso — monitorar se aparece um caso com quantidade de palavras diferente de cada lado, o que quebraria o algoritmo atual. Avaliar se `PTO` deveria ser normalizado para `PRETO` no futuro (hoje mantido como abreviação, sem normalização, mesma decisão já tomada para não traduzir/normalizar cores em outros padrões). Confirmar se o material (`COURO BURNISH`, `COURO NAPA RAVENA/SINTETICO RAVENA`), hoje deixado dentro do `NOME_PRODUTO`, deveria virar um campo próprio no futuro — não há marcador estrutural para extraí-lo com segurança hoje.
- **Padrão I (FERRACINI):** o sufixo de letra no `cProd` (`267A`, `2075A`, `2075B`, `1062A`, `1063C`) provavelmente codifica a cor internamente no ERP do fornecedor. Se um dia surgir uma tabela de tradução desses códigos (ex.: catálogo do fornecedor, outro sistema), avaliar se vale a pena preencher `COR` a partir dela — hoje `COR` fica vazia e `COR_PRECISA_REVISAO = true` em 100% dos itens, por falta desse mapeamento no XML.
- **Padrão J (BEBECE):** `TAMANHO` fica sempre vazio por decisão do usuário — se um dia surgir uma fonte confiável para decodificar os pares tamanho/quantidade do `infAdProd` (ou outra forma de obter o tamanho), revisar essa decisão. Cada `<det>` gera 1 linha (grade inteira, não por tamanho) — `QUANTIDADE`/`VALOR_PRODUTO` refletem a grade agregada, não uma unidade individual.
