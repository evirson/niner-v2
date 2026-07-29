package com.vetor.niner.configuracao.geral;

import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralRequest;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.ConfiguracaoGeralResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.DescontoVendaResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralDtos.PermiteQtdDecimalResponse;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService.FlagsVariante;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Parâmetros do sistema (docs/telas/configuracao-geral.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). Somente ADMIN — verificado no serviço a partir do claim {@code roles} do JWT
 * (mesmo mecanismo de {@code ConfiguracaoTelaService}). Exceções abertas a qualquer papel:
 * {@code /flags-variante} (cadastro de produto precisa saber se os campos de nome de variante
 * aparecem no formulário), {@code /desconto-venda} (PDV, F5, precisa do percentual de
 * desconto promocional pra exibir antes de efetivar a venda) e {@code /permite-qtd-decimal}
 * (PDV/Transferência/Histórico do Cliente precisam saber se quantidade de produto aceita
 * decimais, pra formatar/validar a entrada).
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

    @GetMapping("/flags-variante")
    public FlagsVariante flagsVariante() {
        return service.flagsVariante();
    }

    @GetMapping("/desconto-venda")
    public DescontoVendaResponse descontoVenda() {
        return new DescontoVendaResponse(service.percentualDescontoVenda());
    }

    @GetMapping("/permite-qtd-decimal")
    public PermiteQtdDecimalResponse permiteQtdDecimal() {
        return new PermiteQtdDecimalResponse(service.permiteQtdDecimalProduto());
    }
}
