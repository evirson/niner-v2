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

    @GetMapping("/reajusta-preco-entrada")
    public ReajustaPrecoEntradaResponse reajustaPrecoEntrada() {
        return new ReajustaPrecoEntradaResponse(service.reajustaPrecoEntrada());
    }

    @GetMapping("/consiste-valor-contas-pagar")
    public ConsisteValorContasPagarResponse consisteValorContasPagar() {
        return new ConsisteValorContasPagarResponse(service.consisteValorContasPagar());
    }

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
