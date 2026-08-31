# Handoff — NFS-e Nacional

**Data:** 2026-08-31 · **Para:** quem vai continuar o módulo · **Estado:** API completa, 3 de 5
telas prontas.

Este documento existe para você continuar **sem precisar perguntar nada a ninguém** — nem sobre
código, nem sobre credencial.

---

## ⭐ Você NÃO precisa de nenhuma credencial para trabalhar

Isso não é um contorno; é uma propriedade do desenho, e vale entender por quê:

1. **A NFS-e Nacional não tem API key.** A autenticação é **mTLS com o certificado A1 do próprio
   lojista**, que ele envia pela tela e fica cifrado no banco dele. Não existe chave, token ou
   conta de aplicação para compartilhar.
2. **O `EmissorFalso` é o padrão** (`niner.nfse.emissor=falso`, inclusive em produção). Toda a
   máquina — numeração, montagem, assinatura, gravação, máquina de estados, cancelamento — roda
   **sem rede**.
3. **A suíte gera certificado autoassinado** por `keytool` (ver `NfseEmissaoIntegracaoTest`). Você
   exercita a assinatura sem nenhum arquivo externo.

Para trabalhar, basta o que o `CLAUDE.md` já manda: `docker compose up -d db`,
`docker compose run --rm flyway`, `cd api && ./mvnw test`, `cd web && npm run dev`.

⚠️ **Neste Mac a suíte precisa de `TESTCONTAINERS_RYUK_DISABLED=true`** — o Testcontainers não
consegue montar o socket do Colima para subir o Ryuk. É ambiente, não código.

---

## Leia isto primeiro, nesta ordem

| # | Documento | Por quê |
|---|---|---|
| 1 | `docs/MODULONFSE.md` | **O que foi MEDIDO** contra o Sefin real. §2.6 e §2.7 são os códigos de erro e as três correções à documentação do `finance-v`. Sem isso você vai reimplementar caminhos que não existem |
| 2 | `docs/telas/nfse-configuracao.md` | a tela de configuração e o assistente |
| 3 | `docs/telas/nfse-emissao.md` | emissão, listagem, cancelamento — **e a lista do que falta** |
| 4 | `docs/MODULOSERVICOS.md` §5 | as decisões de produto (DS7–DS13). ⚠️ A §5 foi **superada** pelo `MODULONFSE.md` no que é fato técnico |

---

## ⛔ Cinco coisas que a documentação de referência erra

Estão medidas e provadas. Se você abrir o `finance-v` ou o `workshop` para consultar (e deve, é
código bom), saiba que estes cinco pontos **estão errados lá**:

1. **A chave de acesso NÃO é derivável do `Id` da DPS.** Ela leva o `nNFSe` que o Sefin atribui e um
   **código numérico aleatório de 9 posições**. Recuperar nota órfã é por `GET /dps/{id}`, nunca por
   chave calculada. *(O `IMPLEMENTACAO_FINANCE_V.md` afirma o contrário e o `OrphanRecovery` deles
   foi desligado por causa disso.)*
2. **`parametros_municipais` fica no ADN**, não no Sefin — `adn.nfse.gov.br/parametrizacao`. O
   caminho antigo devolve HTML de 404 do IIS.
3. **`E0116` não é "IM ausente".** Sai com IM, sem IM e em qualquer formato: é o CNC do município.
4. **`xNome` do tomador é obrigatório.** O `MAPA.md` marca como `[0..1]`; não é.
5. **Para ME/EPP o `indTotTrib` é proibido** (`E0712`) e o `totTrib` é exigido pelo schema
   (`E1235`) — a alíquota efetiva do Simples é **pré-requisito de emissão**.

⚠️ **A ordem de validação do Sefin** (deduzida das sondagens): encoding/declaração (`E1229`) →
schema (`E1235`) → assinatura (`E0714`) → regras de negócio. **Um erro esconde os seguintes.**

---

## O que está pronto

**Banco:** V099 (lista nacional, 334 códigos + regra de incidência) · V100 (tributação do serviço)
· V101 (configuração + parâmetros por município) · V102 (documento + itens + eventos + numeração) ·
V103 (telas no RBAC).

**API** (`api/.../fiscal/nfse/`, 14 classes): montagem, assinatura, empacotamento, transporte,
leitura da resposta, numeração, emissor (interface + real + falso), montador venda→DPS, repositório,
emissão com recuperação de órfã, cancelamento, cliente do ADN, 2 controllers.

**Front:** configuração + assistente · bloco fiscal do serviço no Produto · aba em Documentos
Fiscais.

**Testes:** 28 unitários (montador, Id, parser — com **payloads reais** do Sefin) + 7 de integração.

---

## O que falta, em ordem

| # | O quê | Onde começar |
|---|---|---|
| 1 | **Emitir no PDV** | A API já existe (`POST /nfse/vendas/{id}/emitir`). Falta o botão e o modal mostrando **N notas** e o erro de cada uma. Reusar o padrão do `ComprovantePapeletaModal` |
| 2 | **Recibo de Serviço** | Bobina 80 mm/**42 colunas** — a calibragem está em `docs/telas/papeleta-venda.md` e vale igual. ⛔ Sem chave, sem QR, sem "DANFSe" |
| 3 | **DANFSe** | ⚠️ A NT 008 descontinua a API antiga. **Confirme antes de construir** |
| 4 | **Conformidade Fiscal + Exportação em ZIP** | serviço sem código/alíquota, município não atendido; e o ZIP do contador tem de levar a NFS-e |
| 5 | `AjudaDaTela` da aba de NFS-e | a da configuração já existe, use de molde |

---

## ⚠️ O procedimento da emissão real (quando chegar a hora)

Não é `NINER_NFSE_EMISSOR=nacional` e pronto.

1. **Homologação pode estar bloqueada.** Com o CNPJ da Vetor em Curitiba, produção restrita recusa
   **tudo** com `E0116` — é o CNC do município naquele ambiente, e não há o que corrigir do nosso
   lado. Foi assim que a primeira emissão real teve de ser **em produção**.
2. **O procedimento que funcionou:** nota de **R$ 1,00**, cancelada **na mesma execução**
   (`api/scripts/EmitirNfseTeste.java`, que tem trava `--confirmo-producao`). ⚠️ Rode **cedo no
   dia**: se o cancelamento falhar, o prazo de Curitiba é 24 h.
3. **A numeração é por (CNPJ, série).** Se a loja já emitiu por outro sistema no mesmo par, use
   `NfseNumeracaoService.avancarPara()` antes — começar do 1 dá `E0014` em toda nota.

⚠️ **Dívida aberta:** a emissão de teste de 2026-08-31 consumiu o `nDPS 2001000` no CNPJ da Vetor,
série 1, que o `finance-v` também usa. A sequência deles precisa ser empurrada:
`SELECT setval('nfse_rps_vetor_a_seq', 2001000, true);`

---

## O que eu faria diferente, se recomeçasse

Escrito para você não repetir:

1. **Conferir o `styles.css` antes de escrever o JSX.** Inventei classes CSS inexistentes em
   **três** telas seguidas. O `tsc` passa limpo e a tela sai quebrada — ele não checa nome de classe.
2. **Escrever a spec de tela antes.** As duas specs deste módulo nasceram depois do código, e isso
   é dívida: quem chega depois tem de fazer engenharia reversa das decisões.
3. **Toda data em código novo passa por fuso explícito.** Escorreguei duas vezes
   (`LocalDate.now()` e `OffsetDateTime.now().getOffset()`); o `ComparacaoDeDataNoFusoCertoTest`
   pegou uma, eu peguei a outra. O `TZ` do container só existe em produção — não reproduz em dev.
