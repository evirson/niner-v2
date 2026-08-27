package com.vetor.niner.plataforma.onboarding;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.ramo.RamoAtividadeService;
import com.vetor.niner.comum.seguranca.TokenService;
import com.vetor.niner.identidade.usuario.HorarioAcessoService;
import com.vetor.niner.plataforma.diretorio.DiretorioLogin;
import com.vetor.niner.plataforma.diretorio.DiretorioLogin.ContaCandidata;
import com.vetor.niner.plataforma.onboarding.OnboardingDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Onboarding da conta gratuita (R12, §3.3.2; ADR-015 — antes era o trial de 60 dias). Numa ÚNICA transação cria a conta assinante e
 * libera o sistema com configurações padrão, e devolve o token de primeiro acesso.
 *
 * <p>Como o tenant nasce dentro desta transação, o serviço estabelece
 * {@code app.id_tenant} logo após criá-lo (via {@code set_config(..., true)}) para que
 * o RLS de domínio (V024) permita inserir empresa/usuário/cfg_geral do novo tenant (P8).
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    /**
     * Acima disto, um mesmo e-mail em muitas contas deixa de ser o caso legítimo (o dono com duas
     * empresas) e passa a ser sinal de que algo precisa ser olhado — cada candidata custa uma
     * verificação de senha, e BCrypt é caro de propósito.
     */
    private static final int AVISO_CONTAS_POR_EMAIL = 10;

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;
    private final TokenService tokens;
    private final NinerProperties props;
    private final HorarioAcessoService horarioAcesso;
    private final DiretorioLogin diretorio;
    private final RamoAtividadeService ramos;
    private final CodigoLoginService codigos;
    private final ContaDoUsuario contas;

    public SignupService(JdbcClient jdbc, PasswordEncoder senhas, TokenService tokens, NinerProperties props,
            HorarioAcessoService horarioAcesso, DiretorioLogin diretorio, RamoAtividadeService ramos,
            CodigoLoginService codigos, ContaDoUsuario contas) {
        this.jdbc = jdbc;
        this.senhas = senhas;
        this.tokens = tokens;
        this.props = props;
        this.horarioAcesso = horarioAcesso;
        this.diretorio = diretorio;
        this.ramos = ramos;
        this.codigos = codigos;
        this.contas = contas;
    }

    @Transactional
    public AssinarResponse assinar(AssinarRequest req) {
        // 0) UMA conta por e-mail. Defeito visto na validação de produção (2026-08-19): repetir
        // o cadastro com o mesmo e-mail devolvia 201 e criava uma SEGUNDA loja — dados divididos
        // entre as duas sem o lojista perceber, duas assinaturas quando a cobrança ligar, e o
        // lead de marketing migrando para a conta nova (`converter()` faz ON CONFLICT (email) DO
        // UPDATE id_tenant), o que apagava a primeira do funil. Quem tem mais de um CNPJ
        // acrescenta EMPRESA dentro da mesma conta — é o desenho do produto (ADR-015).
        //
        // O lock consultivo é o que fecha a corrida do clique duplo, que é justamente o caso
        // real: duas requisições simultâneas passariam as duas pelo SELECT abaixo. É por
        // transação (`_xact_`), liberado no commit/rollback sem precisar de unlock.
        String emailNormalizado = req.email().trim().toLowerCase(Locale.ROOT);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
                .param("signup:" + emailNormalizado).query().listOfRows();
        boolean jaCadastrado = Boolean.TRUE.equals(jdbc.sql(
                        "SELECT exists(SELECT 1 FROM plataforma.tenant WHERE lower(email_contato) = ?)")
                .param(emailNormalizado).query(Boolean.class).single());
        // Bandeira ligada = ele já viu a pergunta na tela de contratação e escolheu grupo
        // separado, sabendo que perde a visão consolidada e ganha uma segunda assinatura.
        boolean grupoSeparado = Boolean.TRUE.equals(req.criarGrupoSeparado());
        if (jaCadastrado && !grupoSeparado) {
            throw new ContaJaExisteException(
                    "Já existe uma conta com este e-mail. Você pode acrescentar esta empresa ao grupo "
                            + "que já tem, ou criar um grupo separado.");
        }

        // 1) tenant (global, P9) — nasce ATIVA no plano Gratuito (ADR-015: não existe mais trial
        // por tempo; o que limita é volume de vendas no mês). slug único derivado do nome da loja.
        String slug = slugUnico(req.nomeLoja());
        long idTenant = jdbc.sql("""
                        INSERT INTO plataforma.tenant (nome_conta, slug, email_contato, status)
                        VALUES (?, ?, ?, 'ATIVA')
                        RETURNING id_tenant
                        """)
                .params(req.nomeLoja(), slug, req.email())
                .query(Long.class).single();

        // 2) contexto do novo tenant para as tabelas de domínio (RLS) — local à transação.
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();

        // 3) assinatura ATIVA no plano Gratuito (faixa 0, gerado por plataforma.gerar_faixas_planos()).
        // Sem trial_expira_em: a conta gratuita não expira — o que a limita é a cota de vendas
        // do mês (ADR-015). Fallback pelo menor preço ativo cobre banco sem faixa gerada.
        long idPlano = jdbc.sql("SELECT id_plano FROM plataforma.plano WHERE gratuito AND ativo ORDER BY id_plano LIMIT 1")
                .query(Long.class).optional()
                .orElseGet(() -> jdbc.sql(
                        "SELECT id_plano FROM plataforma.plano WHERE ativo ORDER BY preco_mensal LIMIT 1")
                        .query(Long.class).single());

        jdbc.sql("""
                        INSERT INTO plataforma.assinatura (id_tenant, id_plano, status)
                        VALUES (?, ?, 'ATIVA')
                        """)
                .params(idTenant, idPlano)
                .update();

        PlanoContratado plano = jdbc.sql(
                        "SELECT nome, limite_vendas_mes FROM plataforma.plano WHERE id_plano = ?")
                .param(idPlano)
                .query((rs, n) -> new PlanoContratado(rs.getString("nome"), (Integer) rs.getObject("limite_vendas_mes")))
                .single();

        // 4) uso_tenant inicial: 1 usuário (o admin criado abaixo) e 1 empresa (criada no passo 5).
        // A cota de vendas nasce zerada na competência corrente (ADR-015).
        jdbc.sql("INSERT INTO plataforma.uso_tenant (id_tenant, qtd_usuarios, qtd_empresas) VALUES (?, 1, 1)")
                .param(idTenant).update();

        // 5) empresa (1:1 no v1) + configurações padrão da loja. codigo_empresa=1 (primeira
        // empresa do tenant, Q6); cfg_nome_etiqueta recebe um modelo padrão (o lojista
        // personaliza depois) — ambas NOT NULL sem DEFAULT em `empresa` (V014).
        long idEmpresa = jdbc.sql("""
                        INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta, id_ramo)
                        VALUES (?, 1, ?, ?, ?)
                        RETURNING id_empresa
                        """)
                // Ramo de atividade (V072, 2026-08-27): perguntado no signup em vez de deduzido do
                // CNPJ, porque o signup não pede CNPJ — acrescentar esse campo ao funil de
                // aquisição custaria conversão. Dentro do sistema, onde o CNPJ existe, o ramo passa
                // a ser SUGERIDO pelo CNAE. Id inválido é tratado como não informado em vez de
                // derrubar a criação da conta: ramo é dado de segmentação, não requisito do ERP.
                .params(idTenant, req.nomeLoja(), "{sku}\n{descricao}\n{preco_venda}",
                        ramos.existe(req.idRamo()) ? req.idRamo() : null)
                .query(Long.class).single();

        // 5a) plano de contas mínimo pra Entrada de Produtos (mesma árvore de V032, replicada
        // aqui pra tenant novo nascer coerente sem depender da migration). id_plano_contas_pai/
        // nivel são colunas geradas a partir do código — nunca informadas. FK de hierarquia e
        // trigger de guarda já são DEFERRABLE INITIALLY DEFERRED (V016), então os 3 níveis
        // (máscara 9.99.999, revisão 2026-08-13) podem entrar juntos sem ordem especial.
        // Plano de contas padrão COMPLETO (76 contas), copiado do modelo global
        // `cfg_plano_contas_padrao` (V016). Até 2026-08-14 o signup semeava só 3 contas — a árvore
        // mínima da conta de compra — e o plano completo era um script manual que quase nenhum
        // tenant rodava. O efeito apareceu no Relatório de DRE: tenant novo via receita e CMV, mas
        // NENHUMA despesa, porque não tinha conta de despesa cadastrada. `sinal` e
        // `aceita_lancamento` seguem derivados aqui (CREDITO=+1/DEBITO=-1; analítica lança),
        // mesma regra que o script usava — não são colunas do modelo.
        jdbc.sql("""
                        INSERT INTO cfg_plano_contas (
                            id_tenant, id_plano_contas, descricao, tipo_movimento, natureza,
                            inclui_dre, inclui_fluxo_caixa, grupo_dre, grupo_dfc, sinal,
                            aceita_lancamento, padrao_sistema
                        )
                        SELECT ?, p.id_plano_contas, p.descricao, p.tipo_movimento,
                               (CASE WHEN p.analitica THEN 'ANALITICA' ELSE 'SINTETICA' END)::natureza_conta,
                               p.inclui_dre, p.inclui_fluxo_caixa, p.grupo_dre, p.grupo_dfc,
                               CASE p.tipo_movimento::text WHEN 'CREDITO' THEN 1 WHEN 'DEBITO' THEN -1 ELSE 0 END,
                               p.analitica, true
                        FROM cfg_plano_contas_padrao p
                        """)
                .param(idTenant)
                .update();

        jdbc.sql("INSERT INTO cfg_geral (id_tenant, id_plano_contas_compra_mercadoria) VALUES (?, '3.03.001')")
                .param(idTenant).update();

        // 5a2) cor/tamanho/grade PADRÃO (2026-08-13) — sempre id=1 (id_cor/id_tamanho/id_grade
        // não são mais IDENTITY, ver V017): usados internamente quando o tenant não usa cor/grade
        // ou um produto específico não tem variação de verdade, nunca exibidos/referenciados em
        // tela nenhuma (todo JOIN de exibição exclui id=1; CorService/TamanhoService/GradeService.
        // listar() também). Precisam nascer ANTES de qualquer outra cor/tamanho/grade do tenant,
        // pra garantir que ninguém mais ocupe o código 1.
        jdbc.sql("INSERT INTO cfg_cor (id_tenant, id_cor, descricao) VALUES (?, 1, '')").param(idTenant).update();
        jdbc.sql("INSERT INTO cfg_tamanho (id_tenant, id_tamanho, descricao) VALUES (?, 1, 'UN')").param(idTenant).update();
        jdbc.sql("INSERT INTO cfg_grade (id_tenant, id_grade, descricao, id_tamanho1) VALUES (?, 1, 'PADRÃO', 1)")
                .param(idTenant).update();

        // 5b) formas de pagamento padrão (§3.3.7/V025) — seed POR TENANT aqui, não em migration
        // global, porque id_tenant é obrigatório (P8) e não existe no momento do Flyway. Mesmo
        // conjunto do legado (db/042_MOEDAS.txt), mas agora direto em tipo_carteira — moeda foi
        // absorvida em 2026-07-28 (motivo completo no topo de V025__financeiro_caixa_crediario.sql).
        // permite_receber_crediario (2026-07-29, RN007 do Recebimento de Crediário): nasce ligado
        // nas categorias explicitamente permitidas pela spec (À Vista/Débito/Crédito) — evita uma
        // tela de recebimento vazia em tenant novo; CREDIARIO/vale seguem desligados (crediário
        // não paga crediário, vale não estava nas categorias pedidas). "VALE PRESENTE" do legado
        // (2026-08-03) foi removido do seed — era só um rótulo AVISTA sem lógica nenhuma por trás,
        // e distinguir "presente" de "mercadoria" ficaria confuso ao lado do vale de verdade
        // (VALE MERCADORIA, categoria própria, ligado à Devolução de Produtos).
        jdbc.sql("""
                        INSERT INTO tipo_carteira
                            (id_tenant, nome_carteira, categoria_carteira, prazo_pagamento, pc_minima, pc_maxima,
                             permite_receber_crediario) VALUES
                            (?, 'DINHEIRO', 'AVISTA', 0, 1, 1, true),
                            (?, 'PIX', 'AVISTA', 0, 1, 1, true),
                            (?, 'CARTAO DEBITO', 'CARTAO_DEBITO', 1, 1, 1, true),
                            (?, 'CARTAO CREDITO', 'CARTAO_CREDITO', 30, 1, 6, true),
                            (?, 'CREDIARIO', 'CREDIARIO', 30, 1, 6, false),
                            (?, 'VALE MERCADORIA', 'VALE_MERCADORIA', 0, 1, 1, false)
                        """)
                .params(idTenant, idTenant, idTenant, idTenant, idTenant, idTenant)
                .update();

        // 5c) perfis fiscais padrão (2026-08-19, docs/telas/fiscal-perfil.md "Perfis semeados no
        // signup") — sem isso o lojista liga o fiscal e não tem produto com pra onde apontar. Os
        // dois cobrem o caso mais comum do varejo de Simples Nacional/MEI (DF37: só CRT 1, 2 e 4,
        // os três semeados nos dois perfis — um perfil só "é" Simples ou MEI pelas regras que tem,
        // não por um campo próprio, ver PerfilFiscalService.listar/atende_simples/atende_mei):
        // revenda tributada normal (CSOSN 102) e revenda com ICMS já retido por substituição
        // tributária (CSOSN 500, comum em confecção/calçado). PIS/COFINS sempre CST 99 (dentro do
        // DAS, DF37); contexto CONSUMIDOR_FINAL/VENDA_CONSUMIDOR/UF '*' é o único que
        // VendaFiscalAssembler.buscarRegra consulta na emissão de NFC-e (v1, DF35). CST de ICMS
        // fica de fora de propósito — a divergência do CRT 2 (§8.2 do estudo fiscal) é decisão do
        // contador, o ERP não escolhe por ele.
        long idPerfilNormal = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome, descricao, ativo)
                        VALUES (?, 'REVENDA TRIBUTADA NORMAL',
                                'CSOSN 102 — regra padrão para revenda de mercadoria sem substituição tributária.', true)
                        RETURNING id_perfil_fiscal
                        """)
                .param(idTenant).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal_regra
                            (id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao,
                             cfop, csosn, cst_pis, cst_cofins) VALUES
                            (?, ?, 1, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5102', '102', '99', '99'),
                            (?, ?, 2, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5102', '102', '99', '99'),
                            (?, ?, 4, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5102', '102', '99', '99')
                        """)
                .params(idTenant, idPerfilNormal, idTenant, idPerfilNormal, idTenant, idPerfilNormal)
                .update();

        long idPerfilSt = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome, descricao, ativo)
                        VALUES (?, 'REVENDA COM SUBSTITUIÇÃO TRIBUTÁRIA (ST)',
                                'CSOSN 500 — mercadoria com ICMS já retido por substituição tributária (comum em confecção e calçado).', true)
                        RETURNING id_perfil_fiscal
                        """)
                .param(idTenant).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal_regra
                            (id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao,
                             cfop, csosn, cst_pis, cst_cofins) VALUES
                            (?, ?, 1, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5405', '500', '99', '99'),
                            (?, ?, 2, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5405', '500', '99', '99'),
                            (?, ?, 4, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5405', '500', '99', '99')
                        """)
                .params(idTenant, idPerfilSt, idTenant, idPerfilSt, idTenant, idPerfilSt)
                .update();

        // 5d) perfil "NÃO INFORMADO" (2026-08-19) — sentinela SEM regra nenhuma, de propósito.
        // É o que a Importação de Produtos atribui quando a coluna TRIBUTACAO vem em branco
        // (docs/telas/importacao-dados.md): melhor um produto apontar pra um perfil real que a
        // Conformidade Fiscal já sabe cobrar ("perfil sem regra para o CRT") do que ficar
        // silenciosamente sem perfil nenhum. Sem `INSERT` em cfg_perfil_fiscal_regra — é exatamente
        // a ausência de regra que faz o motor recusar emitir (F11) em vez de chutar CFOP/CSOSN.
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome, descricao, ativo)
                        VALUES (?, 'NÃO INFORMADO',
                                'Sentinela sem regra fiscal — atribuído automaticamente a produtos importados sem a tributação definida. Edite este perfil (ou troque o produto de perfil) antes de emitir nota para eles.', true)
                        """)
                .param(idTenant).update();

        // 6) primeiro usuário = ADMIN (senha em hash — nunca texto).
        long idUsuario = jdbc.sql("""
                        INSERT INTO usuario (id_tenant, id_empresa, nome_usuario, email, senha_hash, administrador)
                        VALUES (?, ?, ?, ?, ?, true)
                        RETURNING id_usuario
                        """)
                .params(idTenant, idEmpresa, req.nomeAdmin(), req.email(), senhas.encode(req.senha()))
                .query(Long.class).single();

        // 6b) acesso à empresa recém-criada (usuario_empresa, V015/2026-07-28) — sem essa
        // linha o primeiro login já cairia no caso "usuário sem empresa vinculada".
        jdbc.sql("INSERT INTO usuario_empresa (id_tenant, id_usuario, id_empresa) VALUES (?, ?, ?)")
                .params(idTenant, idUsuario, idEmpresa)
                .update();

        // O funil de aquisição (ADR-017) é fechado pelo OnboardingController, DEPOIS do commit
        // desta transação — ver o comentário lá: dentro daqui não funciona (a transação separada
        // ainda não enxerga o tenant, e a mesma transação vira armadilha de rollback silencioso).

        // 7) token de primeiro acesso (auto-login) — leva o cliente direto ao sistema, já com a
        // empresa recém-criada como empresa ativa da sessão (eid).
        String token = tokens.emitir(idUsuario, idTenant, idEmpresa, req.email(), List.of("ADMIN"));
        return new AssinarResponse(token, idTenant, slug, req.nomeLoja(), plano.nome(), plano.limiteVendasMes());
    }

    private record PlanoContratado(String nome, Integer limiteVendasMes) {
    }

    /**
     * Login por <b>e-mail e senha</b> — sem identificador de conta (2026-08-27).
     *
     * <p><b>Como a conta é descoberta.</b> O e-mail é único apenas <i>dentro</i> do tenant
     * (V015), então ele pode existir em várias contas — o caso real é o dono que vende cosméticos
     * numa conta e sapatos em outra. Quem diz onde procurar é o {@link DiretorioLogin} (V071);
     * quem diz se é a conta certa é a <b>senha</b>, conferida uma vez por candidata.
     *
     * <p><b>A lista de contas só existe depois que a senha bate.</b> Devolvê-la antes
     * transformaria o login numa consulta pública de "este e-mail é cliente de vocês?" — o mesmo
     * motivo pelo qual a recuperação de senha responde 204 para conta inexistente. Senhas
     * diferentes em contas diferentes resolvem sozinhas: só uma casa, e o usuário entra direto,
     * sem ver tela de escolha nenhuma.
     *
     * <p><b>Até duas voltas, nesta ordem:</b> {@code escolherConta} (a senha casou em mais de uma
     * conta) e depois {@code escolherEmpresa} (o usuário alcança mais de uma empresa da conta —
     * `usuario_empresa`, 2026-07-28, docs/telas/usuario.md). Cada volta reenvia as mesmas
     * credenciais acrescidas da escolha, e a escolha é <b>sempre revalidada</b>: nada é aceito só
     * porque o front mandou.
     */
    @Transactional
    public TokenResponse login(LoginRequest req, String ip) {
        List<ContaCandidata> candidatas = diretorio.porEmail(req.email());
        if (req.idTenant() != null) {
            // Segunda volta: o usuário escolheu uma conta. A senha é conferida de novo — a
            // escolha só restringe onde olhar, nunca substitui a autenticação.
            candidatas = candidatas.stream().filter(c -> c.idTenant() == req.idTenant()).toList();
        }
        if (candidatas.size() > AVISO_CONTAS_POR_EMAIL) {
            // Sem teto: truncar deixaria a última conta impossível de acessar, e em silêncio.
            // O aviso existe porque cada candidata custa uma verificação de senha (BCrypt é
            // caro de propósito) — se isto aparecer no log, o limite de requisição da superfície
            // pública é o que segura, e vale revisar o desenho.
            log.warn("E-mail com {} contas no diretório — login vai conferir a senha em todas.", candidatas.size());
        }

        List<ContaAutenticada> autenticadas = new ArrayList<>();
        for (ContaCandidata candidata : candidatas) {
            autenticar(candidata, req.email(), req.senha())
                    .ifPresent(usuario -> autenticadas.add(new ContaAutenticada(candidata, usuario)));
        }

        if (autenticadas.isEmpty()) {
            // Mensagem única para e-mail inexistente, senha errada, conta inativa e escolha
            // inválida: qualquer distinção aqui vira oráculo para quem está tentando adivinhar.
            throw new ResponseStatusException(UNAUTHORIZED, "Credenciais inválidas.");
        }
        if (autenticadas.size() > 1) {
            List<ContaOpcaoLogin> contas = autenticadas.stream()
                    .map(a -> new ContaOpcaoLogin(a.conta().idTenant(), a.conta().nomeConta()))
                    .toList();
            // idTenant/slug ficam vazios de propósito: ainda não há conta escolhida.
            return new TokenResponse(null, 0L, null, true, contas, false, List.of(), false, null, null);
        }

        ContaCandidata conta = autenticadas.get(0).conta();
        UsuarioAuth usuario = autenticadas.get(0).usuario();
        long idTenant = conta.idTenant();
        // O contexto ficou no último candidato testado — reposiciona no escolhido antes de
        // qualquer consulta de domínio (P8).
        entrarNoTenant(idTenant);

        // Horário de acesso (2026-08-11) — sem tolerância nenhuma aqui: não existe "rotina em
        // andamento" pra proteger numa sessão que ainda nem começou (a tolerância padrão só
        // vale pro filtro por requisição, HorarioAcessoFilter, que pode pegar uma venda no meio).
        if (!horarioAcesso.podeAcessarAgora(usuario.idUsuario(), 0)) {
            throw new ResponseStatusException(FORBIDDEN, HorarioAcessoService.MENSAGEM_FORA_DA_JANELA);
        }

        List<EmpresaOpcaoLogin> empresas = jdbc.sql("""
                        SELECT e.id_empresa, COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa
                        FROM usuario_empresa ue
                        JOIN empresa e ON e.id_empresa = ue.id_empresa AND e.id_tenant = ue.id_tenant
                        WHERE ue.id_usuario = ? AND ue.id_tenant = plataforma.tenant_atual()
                        ORDER BY e.codigo_empresa ASC
                        """)
                .param(usuario.idUsuario())
                .query((rs, n) -> new EmpresaOpcaoLogin(rs.getLong("id_empresa"), rs.getString("nome_empresa")))
                .list();

        if (empresas.isEmpty()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Usuário sem empresa vinculada. Contate o administrador.");
        }

        // idEmpresa informado (segunda volta ou já sabido pelo front) é sempre validado contra
        // a lista de acesso, mesmo quando o usuário só tem uma empresa — nunca aceita "de
        // graça" só porque bateu por coincidência de ser a única.
        long idEmpresa;
        if (req.idEmpresa() != null) {
            boolean permitida = empresas.stream().anyMatch(e -> e.idEmpresa() == req.idEmpresa());
            if (!permitida) {
                throw new ResponseStatusException(UNAUTHORIZED, "Empresa inválida para este usuário.");
            }
            idEmpresa = req.idEmpresa();
        } else if (empresas.size() == 1) {
            idEmpresa = empresas.get(0).idEmpresa();
        } else {
            return new TokenResponse(null, idTenant, conta.slug(), false, List.of(), true, empresas, false, null, null);
        }

        // Login em duas etapas (V079): a senha bateu e a conta/empresa estão resolvidas — é aqui,
        // no último passo antes do token, que o código entra. Pedi-lo antes da senha entregaria a
        // qualquer um a informação de que aquele e-mail existe, e mandaria e-mail à toa.
        if (usuario.exigeCodigoLogin()) {
            var desafio = codigos.criarDesafio(idTenant, usuario.idUsuario(), idEmpresa,
                    usuario.nome(), usuario.email(), ip);
            return new TokenResponse(null, idTenant, conta.slug(), false, List.of(), false, List.of(),
                    true, desafio.toString(), mascarar(usuario.email()));
        }

        return emitirToken(usuario.idUsuario(), idTenant, idEmpresa, usuario.email(),
                usuario.administrador(), conta.slug());
    }

    /**
     * Confere a senha dentro da conta candidata. {@code Optional.empty()} = não é esta conta —
     * e não distingue "não existe" de "senha errada" de "inativo", porque quem chama responde
     * a mesma coisa nos três casos.
     *
     * <p>A busca é por {@code (id_tenant, lower(email))}, a chave de negócio, e não pelo
     * {@code id_usuario} que o diretório carrega: o diretório é um índice reconstruível, então
     * ele diz <b>onde</b> procurar, nunca <b>quem</b> é o usuário.
     */
    private Optional<UsuarioAuth> autenticar(ContaCandidata conta, String email, String senha) {
        entrarNoTenant(conta.idTenant());
        // id_tenant explícito (defesa em profundidade) — ver comentário em ProdutoService.listar.
        // Aqui é o caminho de autenticação: um vazamento de RLS nesta query específica
        // significaria autenticação cross-tenant, não só leitura indevida.
        UsuarioAuth usuario = jdbc.sql("""
                        SELECT id_usuario, senha_hash, administrador, ativo,
                               nome_usuario, email, exige_codigo_login
                        FROM usuario WHERE id_tenant = ? AND lower(email) = lower(?)
                        """)
                .params(conta.idTenant(), email)
                .query((rs, n) -> new UsuarioAuth(
                        rs.getLong("id_usuario"), rs.getString("senha_hash"),
                        rs.getBoolean("administrador"), rs.getBoolean("ativo"),
                        rs.getString("nome_usuario"), rs.getString("email"),
                        rs.getBoolean("exige_codigo_login")))
                .optional().orElse(null);

        if (usuario == null || !usuario.ativo() || !senhas.matches(senha, usuario.senhaHash())) {
            return Optional.empty();
        }
        return Optional.of(usuario);
    }

    /** Estabelece o {@code app.id_tenant} da transação corrente — base do RLS de domínio (P8). */
    private void entrarNoTenant(long idTenant) {
        jdbc.sql("SELECT set_config('app.id_tenant', ?, true)")
                .param(Long.toString(idTenant)).query(String.class).single();
    }

    /**
     * Segunda etapa do login: confere o código de 4 dígitos e emite o token (V079).
     *
     * <p>⚠️ O desafio carrega tenant, usuário e empresa — nada disso vem do cliente. Aceitar
     * qualquer um dos três no corpo da requisicao deixaria quem tem um desafio válido entrar em
     * outra conta.
     */
    public TokenResponse concluirLoginComCodigo(CodigoLoginRequest req) {
        UUID idDesafio = lerDesafio(req.desafio());
        var d = codigos.conferir(idDesafio, req.codigo());
        var dados = contas.buscar(d.idTenant(), d.idUsuario());

        // Horário de acesso conferido de novo: entre a senha e o código passam até 10 minutos, e a
        // janela pode ter fechado no meio.
        // ⚠️ Via `contas`, nunca direto: este caminho é público e não tem TenantContext — o
        // serviço de horário consultado sem tenant devolve vazio e responde "pode acessar".
        if (!contas.podeAcessarAgora(d.idTenant(), d.idUsuario())) {
            throw new ResponseStatusException(FORBIDDEN, HorarioAcessoService.MENSAGEM_FORA_DA_JANELA);
        }
        return emitirToken(d.idUsuario(), d.idTenant(), d.idEmpresa(), dados.email(),
                dados.administrador(), dados.slug());
    }

    /**
     * Reenvia o código do desafio. Responde 204 mesmo para desafio inexistente — dizer "não existe"
     * transformaria o endpoint num verificador de identificadores alheios.
     */
    public void reenviarCodigoLogin(ReenviarCodigoRequest req) {
        UUID idDesafio;
        try {
            idDesafio = UUID.fromString(req.desafio());
        } catch (IllegalArgumentException e) {
            return;
        }
        var d = codigos.buscar(idDesafio);
        if (d == null || d.usado()) {
            return;
        }
        var dados = contas.buscar(d.idTenant(), d.idUsuario());
        codigos.reenviar(idDesafio, dados.nome(), dados.email());
    }

    private static UUID lerDesafio(String texto) {
        try {
            return UUID.fromString(texto);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(UNAUTHORIZED, "Código inválido ou expirado. Faça o login novamente.");
        }
    }

    /** Emite o token — o mesmo caminho para o login direto e para a segunda etapa. */
    private TokenResponse emitirToken(long idUsuario, long idTenant, long idEmpresa, String email,
            boolean administrador, String slug) {
        List<String> roles = List.of(administrador ? "ADMIN" : "OPERADOR");
        String token = tokens.emitir(idUsuario, idTenant, idEmpresa, email, roles);
        return new TokenResponse(token, idTenant, slug, false, List.of(), false, List.of(), false, null, null);
    }

    /** j***@gmail.com — confirma para a pessoa que o e-mail é o dela, sem expor o endereço. */
    static String mascarar(String email) {
        int arroba = email.indexOf('@');
        if (arroba <= 1) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(arroba);
    }

    private record UsuarioAuth(long idUsuario, String senhaHash, boolean administrador, boolean ativo,
            String nome, String email, boolean exigeCodigoLogin) {
    }

    /** Candidata do diretório cuja senha foi conferida com sucesso. */
    private record ContaAutenticada(ContaCandidata conta, UsuarioAuth usuario) {
    }

    /** Deriva um slug URL-safe do nome da loja e garante unicidade (sufixo -2, -3, …). */
    private String slugUnico(String nome) {
        String base = Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.isBlank()) {
            base = "loja";
        }
        String candidato = base;
        int sufixo = 2;
        while (Boolean.TRUE.equals(jdbc.sql("SELECT exists(SELECT 1 FROM plataforma.tenant WHERE slug = ?)")
                .param(candidato).query(Boolean.class).single())) {
            candidato = base + "-" + sufixo++;
        }
        return candidato;
    }
}
