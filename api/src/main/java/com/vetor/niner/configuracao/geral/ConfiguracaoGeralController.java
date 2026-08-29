package com.vetor.niner.configuracao.geral;

import com.vetor.niner.identidade.permissao.Livre;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralRequest;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.DiasValidadeOrcamentoResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConsisteValorContasPagarResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.DescontoVendaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.EmiteFiscalAposVendaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ExigeNumeroVendaDevolucaoResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.PermiteQtdDecimalResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.PlanoContasCompraMercadoriaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.RateiaFreteEntradaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ReajustaPrecoEntradaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.UsaCorGradeResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.UsaServicosResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Parâmetros do sistema (docs/telas/configuracao-geral.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). Somente ADMIN — verificado no serviço a partir do claim {@code roles} do JWT
 * (mesmo mecanismo de {@code ConfiguracaoTelaService}). Exceções abertas a qualquer papel:
 * {@code /usa-cor-grade} (cadastro de produto e Emissão de Etiqueta precisam saber se o campo
 * Grade aparece), {@code /desconto-venda} (PDV, F5, precisa do percentual de
 * desconto promocional pra exibir antes de efetivar a venda), {@code /permite-qtd-decimal}
 * (PDV/Transferência/Histórico do Cliente precisam saber se quantidade de produto aceita
 * decimais, pra formatar/validar a entrada), {@code /exige-numero-venda-devolucao} (Devolução
 * de Produtos precisa saber se o número da venda de origem é obrigatório antes de gravar),
 * {@code /rateia-frete-entrada}/{@code /reajusta-preco-entrada} (Entrada de Produtos por Compra
 * precisa das duas antes de confirmar uma entrada) e {@code /plano-contas-compra-mercadoria}
 * (cadastro rápido de fornecedor embutido na Entrada de Produtos por Compra, pra preencher o
 * plano de contas padrão sem exigir ADMIN).
 */
@RestController
@RequestMapping("/api/v1/config-geral")
@Tela("configuracoes-gerais")
public class ConfiguracaoGeralController {

    private final ConfiguracaoGeralService service;

    public ConfiguracaoGeralController(ConfiguracaoGeralService service) {
        this.service = service;
    }

    @GetMapping
    public ConfiguracaoGeralResponse buscar(@AuthenticationPrincipal Jwt jwt) {
        return service.buscar(jwt);
    }

    @PutMapping
    public ConfiguracaoGeralResponse atualizar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfiguracaoGeralRequest req) {
        return service.atualizar(jwt, req);
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/usa-cor-grade")
    public UsaCorGradeResponse usaCorGrade() {
        return new UsaCorGradeResponse(service.usaCorGrade());
    }

    @Livre   // mesma razão do /usa-cor-grade: quem cadastra produto não é só o ADMIN
    @GetMapping("/usa-servicos")
    public UsaServicosResponse usaServicos() {
        return new UsaServicosResponse(service.usaServicos());
    }

    // ⚠️ Este `@Livre` é do /desconto-venda, e ele já se perdeu uma vez: em 2026-08-28 o
    // /usa-servicos foi inserido ENTRE a anotação e o método que ela protegia, e o desconto ficou
    // exigindo ADMIN — o campo de desconto SUMIA da tela do caixa (o front lê 0 e esconde o
    // campo), e o admin não via nada errado porque ele passa. ⛔ Ao acrescentar endpoint aqui,
    // acrescente no FIM da classe ou confira as duas linhas vizinhas: anotação de método é
    // posicional e não avisa quando muda de dono.
    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/desconto-venda")
    public DescontoVendaResponse descontoVenda() {
        return new DescontoVendaResponse(service.percentualDescontoVenda());
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/permite-qtd-decimal")
    public PermiteQtdDecimalResponse permiteQtdDecimal() {
        return new PermiteQtdDecimalResponse(service.permiteQtdDecimalProduto());
    }

    /** Dias de validade sugeridos para o orçamento (V058) — aberto a qualquer papel: quem emite
     *  orçamento é majoritariamente OPERADOR, e o GET completo é ADMIN-only. */
    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/dias-validade-orcamento")
    public DiasValidadeOrcamentoResponse diasValidadeOrcamento() {
        return new DiasValidadeOrcamentoResponse(service.diasValidadeOrcamento());
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/exige-numero-venda-devolucao")
    public ExigeNumeroVendaDevolucaoResponse exigeNumeroVendaDevolucao() {
        return new ExigeNumeroVendaDevolucaoResponse(service.exigeNumeroVendaDevolucao());
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/rateia-frete-entrada")
    public RateiaFreteEntradaResponse rateiaFreteEntrada() {
        return new RateiaFreteEntradaResponse(service.rateiaFreteEntrada());
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC (o javadoc da classe já o listava)
    @GetMapping("/reajusta-preco-entrada")
    public ReajustaPrecoEntradaResponse reajustaPrecoEntrada() {
        return new ReajustaPrecoEntradaResponse(service.reajustaPrecoEntrada());
    }

    // ⚠️ Sem `@Livre`, o operador sem a tela de Parâmetros levava 403 e o front caía no fallback
    // `?? true`: um tenant que DESLIGOU a consistência continuava vendo "a soma das duplicatas não
    // bate com o total" barrar a entrada, sem ninguém entender — e o admin, que passa, via
    // funcionar. Achado de auditoria, 2026-08-29.
    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/consiste-valor-contas-pagar")
    public ConsisteValorContasPagarResponse consisteValorContasPagar() {
        return new ConsisteValorContasPagarResponse(service.consisteValorContasPagar());
    }

    // ⛔ Este era o pior dos três: o operador com Entrada de Produtos liberada e Parâmetros não
    // (o padrão — a grade nasce vazia) clicava em criar fornecedor, levava 403 aqui, e
    // `form.idPlanoContas` ficava vazio PARA SEMPRE — o botão "Criar" nunca habilitava, sem
    // mensagem nenhuma, e o modal não tem campo de plano de contas (é atribuído por baixo dos
    // panos). A entrada da nota travava. Reincidência exata do bug de 2026-08-14 que motivou a
    // criação deste endpoint, reaberta pela porta do RBAC.
    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/plano-contas-compra-mercadoria")
    public PlanoContasCompraMercadoriaResponse planoContasCompraMercadoria() {
        return new PlanoContasCompraMercadoriaResponse(service.idPlanoContasCompraMercadoria());
    }

    @Livre   // já era aberto a qualquer papel antes do RBAC
    @GetMapping("/emite-fiscal-apos-venda")
    public EmiteFiscalAposVendaResponse emiteFiscalAposVenda() {
        return new EmiteFiscalAposVendaResponse(service.emiteFiscalAposVenda());
    }
}
