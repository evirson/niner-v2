package com.vetor.niner.identidade.empresa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** DTOs de empresa. */
public final class EmpresaDtos {

    private EmpresaDtos() {
    }

    public record EmpresaResponse(
            long idEmpresa, int codigoEmpresa, String razaoSocial, String nomeFantasia, Integer idRamo, boolean ativo) {
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
            String telefone, String email, Integer idRamo, boolean ativo, OffsetDateTime criadoEm, OffsetDateTime atualizadoEm) {
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
            @Size(max = 200) String email,
            /** Ramo de atividade (V072). Null = não informado; id inexistente é recusado. */
            Integer idRamo) {
    }

    /**
     * Inclusão de empresa/CNPJ pelo próprio ADMIN (2026-08-18, ADR-015 — CNPJ é ilimitado em
     * todos os planos, e a cota de vendas soma todos eles). Só a razão social é obrigatória: o
     * resto da ficha se preenche depois em Dados da Empresa, e quem cobra preenchimento antes de
     * emitir nota é a Conformidade Fiscal.
     */
    public record CriarEmpresaRequest(
            @NotBlank @Size(max = 200) String razaoSocial,
            @Size(max = 200) String nomeFantasia,
            @Size(max = 14) String cnpj,
            /** Ramo de atividade (V072) — na tela ele vem SUGERIDO pelo CNAE do CNPJ consultado. */
            Integer idRamo) {
    }
}
