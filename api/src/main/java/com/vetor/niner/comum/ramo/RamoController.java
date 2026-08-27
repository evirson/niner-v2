package com.vetor.niner.comum.ramo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Ramos de atividade e consulta de CNPJ.
 *
 * <p>A lista de ramos é exposta nas <b>duas</b> superfícies porque é usada nos dois momentos: na
 * contratação (site público, antes de existir conta) e dentro do ERP (cadastro de empresa).
 *
 * <p>⚠️ A <b>consulta de CNPJ</b> fica só em {@code /api/v1} — autenticada. Ela é um proxy para um
 * serviço externo; aberta na superfície pública, viraria um consultor de CNPJ de graça em nome da
 * Vetor, com nosso IP levando o bloqueio quando alguém resolvesse varrer a base.
 */
@RestController
public class RamoController {

    private final RamoAtividadeService ramos;
    private final ConsultaCnpjService consultaCnpj;

    public RamoController(RamoAtividadeService ramos, ConsultaCnpjService consultaCnpj) {
        this.ramos = ramos;
        this.consultaCnpj = consultaCnpj;
    }

    @GetMapping("/api/publico/ramos")
    public List<RamoAtividadeService.Ramo> listarPublico() {
        return ramos.listar();
    }

    @GetMapping("/api/v1/ramos")
    public List<RamoAtividadeService.Ramo> listar() {
        return ramos.listar();
    }

    /**
     * Dados públicos do CNPJ + ramo sugerido. Responde <b>204</b> quando não deu para consultar —
     * e não 404: a diferença importa para a tela, que trata "não achei" e "não consegui
     * perguntar" do mesmo jeito (abrir no preenchimento manual), sem mostrar erro a quem só está
     * cadastrando uma empresa.
     */
    @GetMapping("/api/v1/cnpj/{cnpj}")
    public ResponseEntity<ConsultaCnpjService.DadosCnpj> consultarCnpj(@PathVariable String cnpj) {
        return consultaCnpj.consultar(cnpj)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
