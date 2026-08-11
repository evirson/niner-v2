package com.vetor.niner.configuracao.geral;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralRequest;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.DescontoVendaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ExigeNumeroVendaDevolucaoResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.PermiteQtdDecimalResponse;
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
 * decimais, pra formatar/validar a entrada) e {@code /exige-numero-venda-devolucao} (Devolução
 * de Produtos precisa saber se o número da venda de origem é obrigatório antes de gravar).
 */
@RestController
@RequestMapping("/api/v1/config-geral")
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

    @GetMapping("/usa-cor-grade")
    public UsaCorGradeResponse usaCorGrade() {
        return new UsaCorGradeResponse(service.usaCorGrade());
    }

    @GetMapping("/desconto-venda")
    public DescontoVendaResponse descontoVenda() {
        return new DescontoVendaResponse(service.percentualDescontoVenda());
    }

    @GetMapping("/permite-qtd-decimal")
    public PermiteQtdDecimalResponse permiteQtdDecimal() {
        return new PermiteQtdDecimalResponse(service.permiteQtdDecimalProduto());
    }

    @GetMapping("/exige-numero-venda-devolucao")
    public ExigeNumeroVendaDevolucaoResponse exigeNumeroVendaDevolucao() {
        return new ExigeNumeroVendaDevolucaoResponse(service.exigeNumeroVendaDevolucao());
    }
}
