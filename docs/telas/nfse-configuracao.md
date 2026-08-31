# Spec: Configuração da NFS-e (fiscal_config_nfse)      Status: Implementada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-31 · Módulo: `fiscal.nfse` · Bloco: S5.5

> ⚠️ **Esta spec foi escrita DEPOIS da implementação, e isso é uma dívida, não o processo.** A regra
> do projeto é spec → código. Aqui a ordem se inverteu porque a medição contra o Sefin real (que
> definiu metade das decisões) só existiu no meio do caminho. O que está escrito abaixo descreve o
> que **está no código**, conferido linha a linha — não o que eu gostaria que estivesse.

## Problema

Emitir NFS-e exige dados que o lojista não tem na cabeça e que hoje só o suporte saberia buscar: a
alíquota do ISS do município para aquele serviço, se o município opera no Emissor Nacional, se a
Inscrição Municipal vai ou não na nota. Sem uma tela que resolva isso sozinha, cada loja nova vira
um chamado — e o objetivo declarado do produto é o contrário.

## Solução

Tela **singleton por EMPRESA** (não por tenant), no molde de `docs/telas/fiscal-configuracao.md`:
seletor de empresa no topo, a linha pode não existir (`configurado: false`), o primeiro `PUT` cria.

⭐ **O que a diferencia de um formulário comum é o assistente.** Em vez de aceitar tudo e falhar na
primeira nota, o botão **Verificar configuração** roda as checagens em sequência e devolve cada
pendência **com o link da tela que a resolve**.

**Acesso: ADMIN**, leitura e escrita. Rota `/fiscal/nfse`, item de menu em Configurações → Fiscal.

## Campos

### Emissão

| Campo | Regra |
|---|---|
| `emite_nfse` | O gate do F12, **desligado por padrão**. Ligar passa pela conferência (ver *Regra de ativação*) |
| `ambiente` | HOMOLOGACAO (default) · PRODUCAO |
| `serie` | 1 a 99999. ⛔ **Campo vazio NÃO vira 1 em silêncio** — o botão Salvar desabilita |

⚠️ **Aviso obrigatório quando `emite_nfse` está desligado.** Não emitir é caminho legítimo e
permanente (DS10) — MEI atendendo pessoa física está legalmente servido com a papeleta. **Mas desde
01/11/2026 ME/EPP do Simples é obrigada ao Emissor Nacional**, e escolher "só papeleta" ali é estar
irregular. A escolha precisa ser **informada**, não silenciosa.

⚠️ **Aviso em homologação, além da tarja padrão:** alguns municípios não têm a Inscrição Municipal
cadastrada no CNC do ambiente de teste e recusam **toda** emissão ali (`E0116`). Foi o que
aconteceu com Curitiba na nossa medição. Nesses casos a primeira nota válida sai **direto em
produção** — e a tela diz isso, em vez de deixar o lojista achar que configurou errado.

### Simples Nacional

| Campo | Regra |
|---|---|
| `simples_anexo` | III ou V |
| `aliquota_simples_efetiva` | 0 a 33. ⛔ **PRÉ-REQUISITO de emissão para optante** |
| `rbt12` | Opcional, só para conferência com o contador |

⛔ **Por que a alíquota efetiva é obrigatória, e isso foi medido:** para ME/EPP o Sefin **proíbe** o
`indTotTrib` (`E0712`) e o schema **exige** o bloco `totTrib` (`E1235`). Não existe emissão de
optante do Simples sem esse percentual. Ele vem do extrato do PGDAS-D do mês anterior — o ERP não
tem como derivá-lo (o ADR-015 já recusou tirar de `uso_venda_mes`: é venda no ERP, não receita da
empresa). **É a fronteira real do "configurar sem suporte"**, junto com o cadastro no CNC.

## Regra de ativação (F11)

Ligar `emite_nfse` é recusado com **409** e a lista do que falta quando: a empresa não tem CNPJ
válido, não tem código de município (IBGE), não há certificado A1 ativo, ou falta a alíquota do
Simples. A mensagem do servidor é exibida **como veio** — trocá-la por um genérico apagaria o
trabalho de escrevê-la.

## O assistente — `PUT /{idEmpresa}/verificar`

Seis itens, em ordem: **CNPJ → município → certificado → alíquota do Simples → convênio do
município no ADN → conexão**. Cada um devolve `{item, ok, detalhe, telaParaResolver}`.

⭐ Dois itens merecem destaque:

- **Certificado com CNPJ diferente do da empresa** é reprovado. Não é erro visível de outra forma:
  a nota **seria autorizada**, no CNPJ errado.
- **Convênio do município** responde a DS8 **por município, pela fonte oficial**
  (`aderenteEmissorNacional` do ADN). Município que não opera no Emissor Nacional não é atendido —
  e isso é **limite de escopo**, não configuração faltando; a tela diz assim.

## Testar conexão — `PUT /{idEmpresa}/testar-conexao`

`GET` de uma chave inexistente. ⭐ **A resposta esperada é HTTP 404 com `E2401`** — é ela que prova
que o mTLS autenticou e a requisição chegou à aplicação. Um teste que esperasse 200 nunca passaria.
O resultado fica gravado (`ultimo_teste_*`) e aparece na tela.

## Contrato de API

```
GET  /api/v1/fiscal/nfse/{idEmpresa}                       200 sempre (configurado: false se não há linha)
PUT  /api/v1/fiscal/nfse/{idEmpresa}                       409 quando não dá para ligar (com a lista)
PUT  /api/v1/fiscal/nfse/{idEmpresa}/testar-conexao
PUT  /api/v1/fiscal/nfse/{idEmpresa}/verificar
GET  /api/v1/fiscal/nfse/{idEmpresa}/aliquota-sugerida?cTribNac=&cTribMun=
```

⚠️ **`PUT` e não `POST` em "testar" e "verificar"**, de propósito: no RBAC deste projeto o verbo
decide a ação, e os dois **gravam** resultado — são *alterar*, não *incluir*. Declará-los `POST`
faria o `AcoesPorTelaConferemTest` exigir "incluir" numa tela que não inclui nada.

## Armadilhas honradas (achados de auditoria de outras telas)

1. **Trocar de empresa limpa o formulário na hora.** Sem isso, o ADMIN abre a Matriz, troca para a
   Filial e salva nela a série e o **ambiente** da Matriz — série duplicada entre empresas e
   produção onde deveria ser homologação, sem nada indicar o erro até a primeira nota.
2. **`erroConfig` no `disabled` do Salvar.** Com a carga em falha o `<form>` não renderiza e o
   submit apontaria para um id inexistente: clicar não produziria nada — o "botão morto".

## Non-goals

- Substituição de NFS-e (evento 105102), manifestação do tomador, Distribuição DF-e do ADN.
- Cálculo da alíquota efetiva a partir do RBT12 — o campo existe para conferência; calcular exigiria
  carregar as tabelas dos Anexos III/V **da lei**, e isso é trabalho próprio.

## Ajuda da tela (R22)

Registrada em `AjudaDaTela.tsx` sob `fiscal.nfse-configuracao.form`, com os cinco erros comuns —
inclusive o do município que não opera e o da homologação bloqueada.
