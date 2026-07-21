# Spec: Parâmetros do Sistema (cfg_geral)                Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Módulo(s): `configuracao.geral` · Fase: 1 — Núcleo do ERP

## Problema

A tabela `cfg_geral` (V023) guarda os parâmetros gerais do tenant — percentual máximo de
desconto em venda, uso de variantes de produto (linha/coluna) e as taxas de crediário (Fase
2, Q5) — e já é semeada com valores padrão no signup (`SignupService.assinar`), mas não tinha
endpoint nem tela. Sem esta tela, o lojista não tem como ajustar esses parâmetros.

## Solução proposta

Tela nova, deliberadamente **fora do padrão de `cadastros`**: `cfg_geral` é um singleton por
tenant (`id_tenant` é a própria PK, sem surrogate), sempre existe (criada no signup) e nunca é
criada/excluída pela UI — só lida e atualizada. Por isso a tela não tem listagem, paginação,
busca, modo somente-leitura nem `InfoRegistro` — é um formulário único de edição.

**Acesso: somente ADMIN** (leitura e escrita) — decisão de produto, diferente de todos os
cadastros anteriores (Cliente/Funcionário/Fornecedor/Plano de Contas, onde OPERADOR também
tem acesso total). Racional: os parâmetros afetam o tenant inteiro (taxas financeiras, regras
de venda), sensibilidade equivalente à de Configuração de Tela (`docs/telas/configuracao-tela.md`),
que já é ADMIN-only.

## Particularidades estruturais (diferenças em relação às telas de cadastro)

1. **Singleton por tenant, sem criação/exclusão pela UI** — só `GET`/`PUT`. O `PUT` num
   tenant sem linha (não deveria acontecer, já que o signup sempre insere uma) responde 404
   em vez de criar silenciosamente — mais honesto que fingir sucesso.
2. **Sem `InfoRegistro`** — a tabela não tem `criado_em` (só `atualizado_em`) nem um "código"
   de registro que faça sentido mostrar; em vez do bloco de 3 campos do componente padrão, a
   tela mostra só "Última atualização: `<data>`" abaixo do título.
3. **Todos os campos são `NOT NULL`** (V023) — não existe noção de "campo vazio nem
   obrigatório configurável"; a validação é só de **faixa** (percentual entre 0–100, dias
   ≥ 0), nunca de presença.
4. **Item de menu condicional ao papel** — diferente das telas de cadastro (sempre visíveis
   no menu, com sub-rotas ADMIN-only como "configuração de campos"), aqui a **tela inteira** é
   ADMIN-only, então o próprio item de menu ("Parâmetros do Sistema") só aparece para ADMIN
   (`Layout.tsx`, `NAV_ADMIN`) — evita mostrar um link que vai redirecionar o OPERADOR de
   volta ao Painel.
5. **Ícone próprio, deliberadamente diferente da engrenagem (⚙)** — `IconeEngrenagem` já
   significa "configurar campos desta tela" em cada cadastro; usar o mesmo ícone aqui
   confundiria as duas ideias. Ícone novo: `IconeParametros` (sliders/ajustes).

## Campos do formulário

Tabela `cfg_geral` (V023). Seções: **Vendas**, **Catálogo**, **Crediário (Fase 2)** — esta
última com um aviso de que o módulo de crediário ainda não existe, mas os valores já ficam
editáveis e prontos (evita retrabalho de UI quando a Fase 2/Q5 chegar).

| Campo (banco) | Rótulo na tela | Componente | Regra |
|---|---|---|---|
| `percentual_desconto_venda` | Desconto máximo em venda (%) | percentual (máscara) | 0–100 |
| `cfg_usa_variante_linha` | Usa variante em linha (ex.: cor) | checkbox | — |
| `cfg_usa_variante_coluna` | Usa variante em coluna (ex.: tamanho/voltagem) | checkbox | — |
| `juros_crediario_dias` | Juros após (dias) | inteiro | ≥ 0 |
| `juros_crediario` | Juros (%) | percentual (máscara) | 0–100 |
| `multa_crediario_dias` | Multa após (dias) | inteiro | ≥ 0 |
| `multa_crediario` | Multa (%) | percentual (máscara) | 0–100 |

Campos percentuais reaproveitam `mascararPercentual`/`desmascararPercentual`/`formatarPercentual`
(`web/src/lib/masks.ts`), a mesma máscara do "% Comissão" do Funcionário.

## Critérios de aceitação (viram testes)

- Dado um tenant recém-assinado, quando consulta `GET /api/v1/config-geral`, então recebe os
  valores padrão do banco (desconto 0%, variantes ligadas).
- Dado um ADMIN, quando atualiza os parâmetros, então o `GET` seguinte reflete os novos
  valores e `atualizadoEm` muda.
- Dado um OPERADOR, quando tenta ler ou gravar, então 403 nos dois casos.
- Dado um percentual fora de 0–100, quando salvo, então 400.
- Dado um número de dias negativo, quando salvo, então 400.
- Dado dois tenants distintos, quando um atualiza seus parâmetros, então o outro não é
  afetado (isolamento).

Cobertos por `ConfiguracaoGeralTest` (6 testes) — suíte completa do projeto em **69/69 verdes**.

## Impacto no contrato de API

```
GET /api/v1/config-geral    lê os parâmetros do tenant atual (ADMIN)
PUT /api/v1/config-geral    atualiza (ADMIN) — todos os campos são obrigatórios no corpo
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8); 403 (Problem Details) para papel diferente
de ADMIN, verificado a partir do claim `roles` do JWT (mesmo mecanismo de
`ConfiguracaoTelaService.exigirAdmin`).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `configuracao.geral.form`** — desconto máximo, uso de variantes,
  juros/multa de crediário; erros comuns: só ADMIN acessa, percentuais entre 0–100.
  `url_video`: NULL.

## Impacto no banco

Nenhum — `cfg_geral` já existia por completo (V023, RLS via V024), semeada no signup.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- Qualquer lógica que *use* estes parâmetros (aplicar o desconto máximo numa venda, calcular
  juros/multa de um crediário) — isso pertence aos módulos de vendas e financeiro quando
  existirem; esta tela só armazena os valores.
- Configuração por filial/empresa — o parâmetro é por tenant (`empresa` é 1:1 com tenant em
  v1, spec §3.3).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Ajuste de um parâmetro em menos de 10 segundos.
