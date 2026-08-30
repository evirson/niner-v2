package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.FaixaBuracoResponse;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.InutilizacaoItemResponse;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.InutilizacaoRequest;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoDtos.InutilizacaoResponse;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoService.ResultadoInutilizacao;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoRepositorio.InutilizacaoItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Inutilização de numeração (§10.4/§12, bloco B8), superfície do tenant (`/api/v1`). ADMIN-only. */
@RestController
@RequestMapping("/api/v1/fiscal/inutilizacoes")
@Tela("fiscal.inutilizacao")
public class FiscalInutilizacaoController {

    private final FiscalInutilizacaoService service;

    public FiscalInutilizacaoController(FiscalInutilizacaoService service) {
        this.service = service;
    }

    @GetMapping
    public List<InutilizacaoItemResponse> listar(@AuthenticationPrincipal Jwt jwt, @RequestParam long idEmpresa) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.listar(jwt, idEmpresa).stream().map(FiscalInutilizacaoController::mapear).toList();
    }

    @GetMapping("/buracos")
    public List<FaixaBuracoResponse> buracos(@AuthenticationPrincipal Jwt jwt, @RequestParam long idEmpresa,
                                             @RequestParam int modelo, @RequestParam int serie) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.detectarBuracos(jwt, idEmpresa, modelo, serie).stream()
                .map(f -> new FaixaBuracoResponse(f.numeroInicial(), f.numeroFinal()))
                .toList();
    }

    /**
     * ⛔ O guarda de empresa faltava JUSTAMENTE aqui — nos dois GET acima ele estava.
     *
     * <p>Este é o endpoint que <b>executa</b> o ato, e inutilização <b>não se desfaz</b>. Um
     * OPERADOR da empresa 1 com a tela concedida (ela deixou de ser ADMIN-only na V078) mandava
     * {@code idEmpresa: 2} no corpo e o serviço transmitia à SEFAZ com o <b>CNPJ, a UF e o
     * certificado da outra filial</b>, queimando a faixa dela para sempre. De brinde, a mensagem
     * {@code "o próximo a sair nesta série é %d"} devolvia a numeração fiscal corrente da empresa 2,
     * permitindo enumerá-la por tentativa.
     *
     * <p>⚠️ P8 (isolamento de tenant) seguia intacto — o repositório filtra
     * {@code tenant_atual()} em tudo. O que se atravessava é a fronteira entre <b>empresas da mesma
     * conta</b>, que é exatamente o que {@code EmpresaDaSessao} existe para governar; o javadoc
     * daquela classe cita "inutilizava numeração dela" como o cenário motivador.
     */
    @PostMapping
    public InutilizacaoResponse inutilizar(@AuthenticationPrincipal Jwt jwt, @RequestBody InutilizacaoRequest req) {
        EmpresaDaSessao.exigirAcesso(jwt, req.idEmpresa());
        ResultadoInutilizacao resultado = service.executar(jwt, req.idEmpresa(), req.modelo(), req.serie(),
                req.numeroInicial(), req.numeroFinal(), req.justificativa());
        return new InutilizacaoResponse(resultado.protocolo(), resultado.ano());
    }

    private static InutilizacaoItemResponse mapear(InutilizacaoItem item) {
        return new InutilizacaoItemResponse(item.modelo(), item.serie(), item.ano(),
                item.numeroInicial(), item.numeroFinal(), item.justificativa(), item.autorizado(),
                item.protocolo(), item.motivoSefaz(), item.criadoEm());
    }
}
