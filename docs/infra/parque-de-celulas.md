# Parque de células — vocabulário

> **Status:** vocabulário **aprovado pelo dono do produto em 2026-08-27**. O desenho (login,
> roteamento, cota) ainda está sendo decidido — este arquivo cresce conforme cada pedaço for
> fechado. Enquanto isso, ele vale só como **dicionário**: é o vocabulário obrigatório em código,
> documento, tela e conversa sobre o assunto.

## A hierarquia

```
parque      = conjunto de células
  célula    = conjunto de tenants      — 1 banco de dados, até ~50 tenants
    tenant  = conjunto de empresas     — a conta assinante
      empresa = 1 CNPJ
```

E, **fora da pirâmide**:

**catálogo** — um banco de dados à parte, que não pertence a nenhuma célula. Guarda o índice de
quem está onde (em qual célula mora cada tenant). Não contém empresa nenhuma.

## Nomes no código e na infra

| Conceito | No código | Banco físico |
|---|---|---|
| célula | `id_celula`, `plataforma.celula`, `CelulaContext` | `niner_c01`, `niner_c02`, … |
| catálogo | — | `niner_catalogo` |

⚠️ **Não chame a célula de "banco".** Neste ERP *banco* já é instituição financeira — `cfg_banco`,
`conta_corrente.id_banco` são o Banco do Brasil, o Itaú. Usar a mesma palavra para a instância de
dados faria `id_banco` significar duas coisas no mesmo código.

⚠️ **Não diga "loja"** — a palavra do produto é **empresa** (decisão dele, 2026-08-27). Vale para
mensagem de tela, documento e comentário de código escritos daqui para a frente.

⚠️ **A célula 1 é o `niner_db` de hoje.** `cfg_ean_gerador.id_banco` (V017) já é, na prática, o
identificador da célula: o EAN-13 interno tem a forma `9 + III + SSSSSSSS + D`, e os códigos já
emitidos carregam `001`. Quando o campo for renomeado para `id_celula`, a numeração tem de casar
com o que já está impresso em etiqueta — célula 1 = banco atual —, e as 3 casas do formato limitam
o parque a 999 células.

## O que ainda não está decidido

- Como a tela de login descobre a célula sem o usuário informar nada.
- Onde mora o contador da cota de vendas do plano, hoje travado na mesma transação da venda
  (`LimiteVendasService`, ADR-015).
- Como o Flyway é aplicado em todas as células do parque.
