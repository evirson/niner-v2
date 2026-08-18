package com.vetor.niner.identidade.empresa;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** DTOs de empresa. */
public final class EmpresaDtos {

    private EmpresaDtos() {
    }

    public record EmpresaResponse(
            long idEmpresa, int codigoEmpresa, String razaoSocial, String nomeFantasia, boolean ativo) {
    }

    /**
     * Ficha completa de uma empresa (2026-08-19) — inclui os dados fiscais do emitente
     * (`cnpj`/`inscricaoEstadual`/`inscricaoMunicipal`/`codigoMunicipioIbge`/`cnae`) que a
     * Conformidade Fiscal cobra mas que, até então, não tinham tela nenhuma pra preencher.
     */
    public record EmpresaDetalheResponse(
            long idEmpresa, int codigoEmpresa, String razaoSocial, String nomeFantasia, boolean matriz,
            String cnpj, String inscricaoEstadual, String inscricaoMunicipal, Integer codigoMunicipioIbge, String cnae,
            String endereco, String numero, String complemento, String bairro, String cidade, String estado, String cep,
            String telefone, String email, boolean ativo, OffsetDateTime criadoEm, OffsetDateTime atualizadoEm) {
    }

    /**
     * Atualização dos dados de identificação/endereço/fiscal de uma empresa — ADMIN-only
     * (`EmpresaService.atualizar`). Tudo opcional/nullable de propósito: quem cobra o
     * preenchimento antes de emitir é a Conformidade Fiscal, não este formulário (mesmo
     * princípio de `TipoCarteiraForm`/`FornecedorForm`). {@code razaoSocial}/{@code codigoEmpresa}/
     * {@code matriz} não entram aqui — são estruturais, não editáveis por esta tela.
     */
    public record AtualizarEmpresaRequest(
            @Size(max = 200) String nomeFantasia,
            @Size(max = 14) String cnpj,
            @Size(max = 20) String inscricaoEstadual,
            @Size(max = 20) String inscricaoMunicipal,
            Integer codigoMunicipioIbge,
            @Size(max = 10) String cnae,
            @Size(max = 200) String endereco,
            @Size(max = 20) String numero,
            @Size(max = 100) String complemento,
            @Size(max = 100) String bairro,
            @Size(max = 100) String cidade,
            @Size(max = 2) String estado,
            @Size(max = 9) String cep,
            @Size(max = 20) String telefone,
            @Size(max = 200) String email) {
    }
}
