package com.vetor.niner.identidade.empresa;

/** DTOs de leitura de empresa. */
public final class EmpresaDtos {

    private EmpresaDtos() {
    }

    public record EmpresaResponse(
            long idEmpresa, int codigoEmpresa, String razaoSocial, String nomeFantasia, boolean ativo) {
    }
}
