-- V099 — a lista nacional de serviços da NFS-e (bloco S5 de docs/MODULONFSE.md)
--
-- É a tabela que responde "qual é o código do serviço que eu vendo?" — o `cTribNac` de 6 dígitos
-- que vai no XML da DPS. Sem ela, o lojista teria de descobrir sozinho que banho e tosa é
-- `050801`, e isso é exatamente o tipo de pergunta que vira chamado no suporte.
--
-- ⛔ NADA AQUI FOI DIGITADO. Os 334 códigos são DERIVADOS do anexo oficial, pelo
-- `db/scripts/gerar_lista_nacional_servicos.py` — mesma regra do NCM (V017), das 27 UFs (V047) e
-- do mapa CNAE→ramo (V093), e pelo mesmo motivo: em 2026-08-29 eu ia escrever de memória o código
-- da manutenção de motocicletas e o da lista oficial era outro. Fonte:
--   aba "RN MUN.INCID  INFO.SERV." de AnexoI-LeiautesRN_DPS_NFSe-SNNFSe_v1.01.00-homologacao.xlsx
--   (gov.br/nfse → Documentação Técnica)
--
-- ⭐ O ACHADO QUE VALE MAIS QUE A LISTA: o anexo traz a REGRA DE INCIDÊNCIA DO ISS de cada código.
-- O `docs/MODULOSERVICOS.md` §5.4 registrou *"não consegui listar as 25 exceções do art. 3º da
-- LC 116 em fonte confiável única — e não vou inventá-las"*, e propunha um campo
-- `local_incidencia` preenchido pelo lojista com default PRESTADOR. Não é mais preciso: a fonte
-- oficial diz, código a código, se o ISS é devido no estabelecimento do prestador, no local da
-- prestação ou no do tomador. Medido nesta carga: 271 PRESTADOR · 47 PRESTACAO · 10 TOMADOR ·
-- 5 ESPECIAL · 1 SEM_INCIDENCIA. ⭐ Ou seja: o lojista NÃO responde essa pergunta — o código que
-- ele escolheu já a responde.
--
-- ⚠️ GLOBAL, sem `id_tenant` e sem RLS — mesma exceção documentada de `cfg_produto_ncm` e
-- `cfg_uf_autorizador`: é tabela de referência nacional, igual para todos os tenants, mantida por
-- carga e sem tela de manutenção. O guarda-corpo do P8 não se aplica a tabela sem `id_tenant`.

-- ---------------------------------------------------------------------------------------------
-- 1. Onde o ISS é devido
-- ---------------------------------------------------------------------------------------------

CREATE TYPE local_incidencia_iss AS ENUM (
  'PRESTADOR',       -- EDP: estabelecimento/domicílio do prestador (a regra geral do art. 3º)
  'PRESTACAO',       -- LP:  local da prestação (obra, limpeza, vigilância, transporte municipal…)
  'TOMADOR',         -- EDT: estabelecimento/domicílio do tomador (cessão de mão de obra, planos…)
  'ESPECIAL',        -- apuração no MAN (ferrovia/rodovia/dutos) ou trecho de concessão rodoviária
  'SEM_INCIDENCIA'   -- 990101: serviços sem incidência de ISSQN nem de ICMS
);

COMMENT ON TYPE local_incidencia_iss IS
  'Município onde o ISS é devido, conforme o art. 3º da LC 116/2003 modelado no Sistema Nacional '
  'NFS-e. Vem da fonte oficial por código de serviço — não é escolha do lojista.';

-- ---------------------------------------------------------------------------------------------
-- 2. A lista
-- ---------------------------------------------------------------------------------------------

CREATE TABLE cfg_servico_lc116 (
  codigo           char(6)              PRIMARY KEY,
  descricao        text                 NOT NULL,
  local_incidencia local_incidencia_iss NOT NULL,
  grupo_dps        text,
  CONSTRAINT cfg_servico_lc116_codigo_ck CHECK (codigo ~ '^[0-9]{6}$')
);

COMMENT ON TABLE cfg_servico_lc116 IS
  'Lista Nacional de Serviços da NFS-e (cTribNac). GLOBAL — igual para todos os tenants, sem '
  'id_tenant e sem RLS, mesma exceção de cfg_produto_ncm. Mantida por carga derivada do anexo '
  'oficial (db/scripts/gerar_lista_nacional_servicos.py), sem tela de manutenção.';

COMMENT ON COLUMN cfg_servico_lc116.codigo IS
  'cTribNac, 6 dígitos = item(2) + subitem(2) + desdobro nacional(2). ⚠️ É char(6) COM zero à '
  'esquerda: "010101", nunca 10101 — o Excel do anexo come o zero e o XML da DPS não perdoa. '
  '⚠️ E a API de parâmetros municipais do ADN pede o mesmo código em OUTRO formato, pontuado e '
  'com o desdobro municipal ("01.01.01.000") — ver docs/MODULONFSE.md.';

COMMENT ON COLUMN cfg_servico_lc116.local_incidencia IS
  'Onde o ISS é devido, direto do anexo oficial. Substitui a pergunta que o estudo pretendia fazer '
  'ao lojista.';

COMMENT ON COLUMN cfg_servico_lc116.grupo_dps IS
  'Bloco extra que o leiaute da DPS exige para este serviço (obra, atvEvento, lsadppu, explRod). '
  'NULL = nenhum, que é o caso de 301 dos 334 códigos. ⛔ Os serviços com grupo preenchido estão '
  'FORA do escopo do v1 (docs/MODULONFSE.md §4) — a coluna existe para que a tela possa RECUSAR '
  'o código com uma mensagem honesta, em vez de montar um XML incompleto.';

-- Busca por texto: o lojista digita "tosa", "conserto", "cabeleireiro" — não o código.
-- pg_trgm e unaccent_imutavel já existem desde a V016 (busca de cliente).
CREATE INDEX cfg_servico_lc116_descricao_ix
  ON cfg_servico_lc116 USING gin (unaccent_imutavel(lower(descricao)) gin_trgm_ops);

GRANT SELECT ON cfg_servico_lc116 TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_servico_lc116 TO niner_owner;

-- 334 códigos, derivados de AnexoI-LeiautesRN_DPS_NFSe-SNNFSe_v1.01.00-homologacao.xlsx
INSERT INTO cfg_servico_lc116 (codigo, descricao, local_incidencia, grupo_dps) VALUES
  ('010101', 'Análise e desenvolvimento de sistemas.', 'PRESTADOR', NULL),
  ('010201', 'Programação.', 'PRESTADOR', NULL),
  ('010301', 'Processamento de dados, textos, imagens, vídeos, páginas eletrônicas, aplicativos e sistemas de informação, entre outros formatos, e congêneres.', 'PRESTADOR', NULL),
  ('010302', 'Armazenamento ou hospedagem de dados, textos, imagens, vídeos, páginas eletrônicas, aplicativos e sistemas de informação, entre outros formatos, e congêneres.', 'PRESTADOR', NULL),
  ('010401', 'Elaboração de programas de computadores, inclusive de jogos eletrônicos, independentemente da arquitetura construtiva da máquina em que o programa será executado, incluindo tablets, smartphones e congêneres.', 'PRESTADOR', NULL),
  ('010501', 'Licenciamento ou cessão de direito de uso de programas de computação.', 'PRESTADOR', NULL),
  ('010601', 'Assessoria e consultoria em informática.', 'PRESTADOR', NULL),
  ('010701', 'Suporte técnico em informática, inclusive instalação, configuração e manutenção de programas de computação e bancos de dados.', 'PRESTADOR', NULL),
  ('010801', 'Planejamento, confecção, manutenção e atualização de páginas eletrônicas.', 'PRESTADOR', NULL),
  ('010901', 'Disponibilização, sem cessão definitiva, de conteúdos de áudio por meio da internet (exceto a distribuição de conteúdos pelas prestadoras de Serviço de Acesso Condicionado, de que trata a Lei nº 12.485, de 12 de setembro de 2011, sujeita ao ICMS).', 'PRESTADOR', NULL),
  ('010902', 'Disponibilização, sem cessão definitiva, de conteúdos de vídeo, imagem e texto por meio da internet, respeitada a imunidade de livros, jornais e periódicos (exceto a distribuição de conteúdos pelas prestadoras de Serviço de Acesso Condicionado, de que trata a Lei nº 12.485, de 12 de setembro de 2011, sujeita ao ICMS).', 'PRESTADOR', NULL),
  ('020101', 'Serviços de pesquisas e desenvolvimento de qualquer natureza.', 'PRESTADOR', NULL),
  ('030201', 'Cessão de direito de uso de marcas e de sinais de propaganda.', 'PRESTADOR', NULL),
  ('030301', 'Exploração de salões de festas, centro de convenções, stands e congêneres, para realização de eventos ou negócios de qualquer natureza.', 'PRESTADOR', NULL),
  ('030302', 'Exploração de escritórios virtuais e congêneres, para realização de eventos ou negócios de qualquer natureza.', 'PRESTADOR', NULL),
  ('030303', 'Exploração de quadras esportivas, estádios, ginásios, canchas e congêneres, para realização de eventos ou negócios de qualquer natureza.', 'PRESTADOR', NULL),
  ('030304', 'Exploração de auditórios, casas de espetáculos e congêneres, para realização de eventos ou negócios de qualquer natureza.', 'PRESTADOR', 'lsadppu'),
  ('030305', 'Exploração de parques de diversões e congêneres, para realização de eventos ou negócios de qualquer natureza.', 'PRESTADOR', NULL),
  ('030401', 'Locação, sublocação, arrendamento, direito de passagem ou permissão de uso, compartilhado ou não, de ferrovia.', 'ESPECIAL', NULL),
  ('030402', 'Locação, sublocação, arrendamento, direito de passagem ou permissão de uso, compartilhado ou não, de rodovia.', 'ESPECIAL', NULL),
  ('030403', 'Locação, sublocação, arrendamento, direito de passagem ou permissão de uso, compartilhado ou não, de postes, cabos, dutos e condutos de qualquer natureza.', 'ESPECIAL', NULL),
  ('030501', 'Cessão de andaimes, palcos, coberturas e outras estruturas de uso temporário.', 'PRESTACAO', NULL),
  ('040101', 'Medicina.', 'PRESTADOR', NULL),
  ('040102', 'Biomedicina.', 'PRESTADOR', NULL),
  ('040201', 'Análises clínicas e congêneres.', 'PRESTADOR', NULL),
  ('040202', 'Patologia e congêneres.', 'PRESTADOR', NULL),
  ('040203', 'Eletricidade médica (eletroestimulação de nervos e musculos, cardioversão, etc) e congêneres.', 'PRESTADOR', NULL),
  ('040204', 'Radioterapia, quimioterapia e congêneres.', 'PRESTADOR', NULL),
  ('040205', 'Ultra-sonografia, ressonância magnética, radiologia, tomografia e congêneres.', 'PRESTADOR', NULL),
  ('040301', 'Hospitais e congêneres.', 'PRESTADOR', NULL),
  ('040302', 'Laboratórios e congêneres.', 'PRESTADOR', NULL),
  ('040303', 'Clínicas, sanatórios, manicômios, casas de saúde, prontos-socorros, ambulatórios e congêneres.', 'PRESTADOR', NULL),
  ('040401', 'Instrumentação cirúrgica.', 'PRESTADOR', NULL),
  ('040501', 'Acupuntura.', 'PRESTADOR', NULL),
  ('040601', 'Enfermagem, inclusive serviços auxiliares.', 'PRESTADOR', NULL),
  ('040701', 'Serviços farmacêuticos.', 'PRESTADOR', NULL),
  ('040801', 'Terapia ocupacional.', 'PRESTADOR', NULL),
  ('040802', 'Fisioterapia.', 'PRESTADOR', NULL),
  ('040803', 'Fonoaudiologia.', 'PRESTADOR', NULL),
  ('040901', 'Terapias de qualquer espécie destinadas ao tratamento físico, orgânico e mental.', 'PRESTADOR', NULL),
  ('041001', 'Nutrição.', 'PRESTADOR', NULL),
  ('041101', 'Obstetrícia.', 'PRESTADOR', NULL),
  ('041201', 'Odontologia.', 'PRESTADOR', NULL),
  ('041301', 'Ortóptica.', 'PRESTADOR', NULL),
  ('041401', 'Próteses sob encomenda.', 'PRESTADOR', NULL),
  ('041501', 'Psicanálise.', 'PRESTADOR', NULL),
  ('041601', 'Psicologia.', 'PRESTADOR', NULL),
  ('041701', 'Casas de repouso e congêneres.', 'PRESTADOR', NULL),
  ('041702', 'Casas de recuperação e congêneres.', 'PRESTADOR', NULL),
  ('041703', 'Creches e congêneres.', 'PRESTADOR', NULL),
  ('041704', 'Asilos e congêneres.', 'PRESTADOR', NULL),
  ('041801', 'Inseminação artificial, fertilização in vitro e congêneres.', 'PRESTADOR', NULL),
  ('041901', 'Bancos de sangue, leite, pele, olhos, óvulos, sêmen e congêneres.', 'PRESTADOR', NULL),
  ('042001', 'Coleta de sangue, leite, tecidos, sêmen, órgãos e materiais biológicos de qualquer espécie.', 'PRESTADOR', NULL),
  ('042101', 'Unidade de atendimento, assistência ou tratamento móvel e congêneres.', 'PRESTADOR', NULL),
  ('042201', 'Planos de medicina de grupo ou individual e convênios para prestação de assistência médica, hospitalar, odontológica e congêneres.', 'TOMADOR', NULL),
  ('042301', 'Outros planos de saúde que se cumpram através de serviços de terceiros contratados, credenciados, cooperados ou apenas pagos pelo operador do plano mediante indicação do beneficiário.', 'TOMADOR', NULL),
  ('050101', 'Medicina veterinária', 'PRESTADOR', NULL),
  ('050102', 'Zootecnia.', 'PRESTADOR', NULL),
  ('050201', 'Hospitais e congêneres, na área veterinária.', 'PRESTADOR', NULL),
  ('050202', 'Clínicas, ambulatórios, prontos-socorros e congêneres, na área veterinária.', 'PRESTADOR', NULL),
  ('050301', 'Laboratórios de análise na área veterinária.', 'PRESTADOR', NULL),
  ('050401', 'Inseminação artificial, fertilização in vitro e congêneres.', 'PRESTADOR', NULL),
  ('050501', 'Bancos de sangue e de órgãos e congêneres.', 'PRESTADOR', NULL),
  ('050601', 'Coleta de sangue, leite, tecidos, sêmen, órgãos e materiais biológicos de qualquer espécie.', 'PRESTADOR', NULL),
  ('050701', 'Unidade de atendimento, assistência ou tratamento móvel e congêneres.', 'PRESTADOR', NULL),
  ('050801', 'Guarda, tratamento, amestramento, embelezamento, alojamento e congêneres.', 'PRESTADOR', NULL),
  ('050901', 'Planos de atendimento e assistência médico-veterinária.', 'TOMADOR', NULL),
  ('060101', 'Barbearia, cabeleireiros, manicuros, pedicuros e congêneres.', 'PRESTADOR', NULL),
  ('060201', 'Esteticistas, tratamento de pele, depilação e congêneres.', 'PRESTADOR', NULL),
  ('060301', 'Banhos, duchas, sauna, massagens e congêneres.', 'PRESTADOR', NULL),
  ('060401', 'Ginástica, dança, esportes, natação, artes marciais e demais atividades físicas.', 'PRESTADOR', NULL),
  ('060501', 'Centros de emagrecimento, spa e congêneres.', 'PRESTADOR', NULL),
  ('060601', 'Aplicação de tatuagens, piercings e congêneres.', 'PRESTADOR', NULL),
  ('070101', 'Engenharia e congêneres.', 'PRESTADOR', NULL),
  ('070102', 'Agronomia e congêneres.', 'PRESTADOR', NULL),
  ('070103', 'Agrimensura e congêneres.', 'PRESTADOR', NULL),
  ('070104', 'Arquitetura, urbanismo e congêneres.', 'PRESTADOR', NULL),
  ('070105', 'Geologia e congêneres.', 'PRESTADOR', NULL),
  ('070106', 'Paisagismo e congêneres.', 'PRESTADOR', NULL),
  ('070201', 'Execução, por administração, de obras de construção civil, hidráulica ou elétrica e de outras obras semelhantes, inclusive sondagem, perfuração de poços, escavação, drenagem e irrigação, terraplanagem, pavimentação, concretagem e a instalação e montagem de produtos, peças e equipamentos (exceto o fornecimento de mercadorias produzidas pelo prestador de serviços fora do local da prestação dos serviços, que fica sujeito ao ICMS).', 'PRESTACAO', 'obra'),
  ('070202', 'Execução, por empreitada ou subempreitada, de obras de construção civil, hidráulica ou elétrica e de outras obras semelhantes, inclusive sondagem, perfuração de poços, escavação, drenagem e irrigação, terraplanagem, pavimentação, concretagem e a instalação e montagem de produtos, peças e equipamentos (exceto o fornecimento de mercadorias produzidas pelo prestador de serviços fora do local da prestação dos serviços, que fica sujeito ao ICMS).', 'PRESTACAO', 'obra'),
  ('070301', 'Elaboração de planos diretores, estudos de viabilidade, estudos organizacionais e outros, relacionados com obras e serviços de engenharia.', 'PRESTADOR', NULL),
  ('070302', 'Elaboração de anteprojetos, projetos básicos e projetos executivos para trabalhos de engenharia.', 'PRESTADOR', NULL),
  ('070401', 'Demolição.', 'PRESTACAO', 'obra'),
  ('070501', 'Reparação, conservação e reforma de edifícios e congêneres (exceto o fornecimento de mercadorias produzidas pelo prestador dos serviços, fora do local da prestação dos serviços, que fica sujeito ao ICMS).', 'PRESTACAO', 'obra'),
  ('070502', 'Reparação, conservação e reforma de estradas, pontes, portos e congêneres (exceto o fornecimento de mercadorias produzidas pelo prestador dos serviços, fora do local da prestação dos serviços, que fica sujeito ao ICMS).', 'PRESTACAO', 'obra'),
  ('070601', 'Colocação e instalação de tapetes, carpetes, cortinas e congêneres, com material fornecido pelo tomador do serviço.', 'PRESTADOR', 'obra'),
  ('070602', 'Colocação e instalação de assoalhos, revestimentos de parede, vidros, divisórias, placas de gesso e congêneres, com material fornecido pelo tomador do serviço.', 'PRESTADOR', 'obra'),
  ('070701', 'Recuperação, raspagem, polimento e lustração de pisos e congêneres.', 'PRESTADOR', 'obra'),
  ('070801', 'Calafetação.', 'PRESTADOR', 'obra'),
  ('070901', 'Varrição, coleta e remoção de lixo, rejeitos e outros resíduos quaisquer.', 'PRESTACAO', NULL),
  ('070902', 'Incineração, tratamento, reciclagem, separação e destinação final de lixo, rejeitos e outros resíduos quaisquer.', 'PRESTACAO', NULL),
  ('071001', 'Limpeza, manutenção e conservação de vias e logradouros públicos, parques, jardins e congêneres.', 'PRESTACAO', NULL),
  ('071002', 'Limpeza, manutenção e conservação de imóveis, chaminés, piscinas e congêneres.', 'PRESTACAO', NULL),
  ('071101', 'Decoração.', 'PRESTACAO', NULL),
  ('071102', 'Jardinagem, inclusive corte e poda de árvores.', 'PRESTACAO', NULL),
  ('071201', 'Controle e tratamento de efluentes de qualquer natureza e de agentes físicos, químicos e biológicos.', 'PRESTACAO', NULL),
  ('071301', 'Dedetização, desinfecção, desinsetização, imunização, higienização, desratização, pulverização e congêneres.', 'PRESTADOR', NULL),
  ('071601', 'Florestamento, reflorestamento, semeadura, adubação, reparação de solo, plantio, silagem, colheita, corte e descascamento de árvores, silvicultura, exploração florestal e dos serviços congêneres indissociáveis da formação, manutenção e colheita de florestas, para quaisquer fins e por quaisquer meios.', 'PRESTACAO', NULL),
  ('071701', 'Escoramento, contenção de encostas e serviços congêneres.', 'PRESTACAO', 'obra'),
  ('071801', 'Limpeza e dragagem de rios, portos, canais, baías, lagos, lagoas, represas, açudes e congêneres.', 'PRESTACAO', NULL),
  ('071901', 'Acompanhamento e fiscalização da execução de obras de engenharia, arquitetura e urbanismo.', 'PRESTACAO', 'obra'),
  ('072001', 'Aerofotogrametria (inclusive interpretação), cartografia, mapeamento e congêneres.', 'PRESTADOR', NULL),
  ('072002', 'Levantamentos batimétricos, geográficos, geodésicos, geológicos, geofísicos e congêneres.', 'PRESTADOR', NULL),
  ('072003', 'Levantamentos topográficos e congêneres.', 'PRESTADOR', NULL),
  ('072101', 'Pesquisa, perfuração, cimentação, mergulho, perfilagem, concretação, testemunhagem, pescaria, estimulação e outros serviços relacionados com a exploração e explotação de petróleo, gás natural e de outros recursos minerais.', 'PRESTADOR', NULL),
  ('072201', 'Nucleação e bombardeamento de nuvens e congêneres.', 'PRESTADOR', NULL),
  ('080101', 'Ensino regular pré-escolar, fundamental e médio.', 'PRESTADOR', NULL),
  ('080102', 'Ensino regular superior.', 'PRESTADOR', NULL),
  ('080201', 'Instrução, treinamento, orientação pedagógica e educacional, avaliação de conhecimentos de qualquer natureza.', 'PRESTADOR', NULL),
  ('090101', 'Hospedagem em hotéis, hotelaria marítima e congêneres (o valor da alimentação e gorjeta, quando incluído no preço da diária, fica sujeito ao Imposto Sobre Serviços).', 'PRESTADOR', NULL),
  ('090102', 'Hospedagem em pensões, albergues, pousadas, hospedarias, ocupação por temporada com fornecimento de serviços e congêneres (o valor da alimentação e gorjeta, quando incluído no preço da diária, fica sujeito ao Imposto Sobre Serviços).', 'PRESTADOR', NULL),
  ('090103', 'Hospedagem em motéis e congêneres (o valor da alimentação e gorjeta, quando incluído no preço da diária, fica sujeito ao Imposto Sobre Serviços).', 'PRESTADOR', NULL),
  ('090104', 'Hospedagem em apart-service condominiais, flat, apart-hotéis, hotéis residência, residence-service, suite service e congêneres (o valor da alimentação e gorjeta, quando incluído no preço da diária, fica sujeito ao Imposto Sobre Serviços).', 'PRESTADOR', NULL),
  ('090201', 'Agenciamento e intermediação de programas de turismo, passeios, viagens, excursões, hospedagens e congêneres.', 'PRESTADOR', NULL),
  ('090202', 'Organização, promoção e execução de programas de turismo, passeios, viagens, excursões, hospedagens e congêneres.', 'PRESTADOR', NULL),
  ('090301', 'Guias de turismo.', 'PRESTADOR', NULL),
  ('100101', 'Agenciamento, corretagem ou intermediação de câmbio.', 'PRESTADOR', NULL),
  ('100102', 'Agenciamento, corretagem ou intermediação de seguros.', 'PRESTADOR', NULL),
  ('100103', 'Agenciamento, corretagem ou intermediação de cartões de crédito.', 'PRESTADOR', NULL),
  ('100104', 'Agenciamento, corretagem ou intermediação de planos de saúde.', 'PRESTADOR', NULL),
  ('100105', 'Agenciamento, corretagem ou intermediação de planos de previdência privada.', 'PRESTADOR', NULL),
  ('100201', 'Agenciamento, corretagem ou intermediação de títulos em geral e valores mobiliários.', 'PRESTADOR', NULL),
  ('100202', 'Agenciamento, corretagem ou intermediação de contratos quaisquer.', 'PRESTADOR', NULL),
  ('100301', 'Agenciamento, corretagem ou intermediação de direitos de propriedade industrial, artística ou literária.', 'PRESTADOR', NULL),
  ('100401', 'Agenciamento, corretagem ou intermediação de contratos de arrendamento mercantil (leasing).', 'PRESTADOR', NULL),
  ('100402', 'Agenciamento, corretagem ou intermediação de contratos de franquia (franchising).', 'PRESTADOR', NULL),
  ('100403', 'Agenciamento, corretagem ou intermediação de faturização (factoring).', 'PRESTADOR', NULL),
  ('100501', 'Agenciamento, corretagem ou intermediação de bens móveis ou imóveis, não abrangidos em outros itens ou subitens, por quaisquer meios.', 'PRESTADOR', NULL),
  ('100502', 'Agenciamento, corretagem ou intermediação de bens móveis ou imóveis realizados no âmbito de Bolsas de Mercadorias e Futuros, por quaisquer meios.', 'PRESTADOR', NULL),
  ('100601', 'Agenciamento marítimo.', 'PRESTADOR', NULL),
  ('100701', 'Agenciamento de notícias.', 'PRESTADOR', NULL),
  ('100801', 'Agenciamento de publicidade e propaganda, inclusive o agenciamento de veiculação por quaisquer meios.', 'PRESTADOR', NULL),
  ('100901', 'Representação de qualquer natureza, inclusive comercial.', 'PRESTADOR', NULL),
  ('101001', 'Distribuição de bens de terceiros.', 'PRESTADOR', NULL),
  ('110101', 'Guarda e estacionamento de veículos terrestres automotores.', 'PRESTACAO', NULL),
  ('110102', 'Guarda e estacionamento de aeronaves e de embarcações.', 'PRESTACAO', NULL),
  ('110201', 'Vigilância, segurança ou monitoramento de bens, pessoas e semoventes.', 'PRESTACAO', NULL),
  ('110301', 'Escolta, inclusive de veículos e cargas.', 'PRESTADOR', NULL),
  ('110401', 'Armazenamento, depósito, guarda de bens de qualquer espécie.', 'PRESTACAO', NULL),
  ('110402', 'Carga, descarga, arrumação de bens de qualquer espécie.', 'PRESTACAO', NULL),
  ('110501', 'Serviços relacionados ao monitoramento e rastreamento a distância, em qualquer via ou local, de veículos, cargas, pessoas e semoventes em circulação ou movimento, realizados por meio de telefonia móvel, transmissão de satélites, rádio ou qualquer outro meio, inclusive pelas empresas de Tecnologia da Informação Veicular, independentemente de o prestador de serviços ser proprietário ou não da infraestrutura de telecomunicações que utiliza.', 'PRESTADOR', NULL),
  ('120101', 'Espetáculos teatrais.', 'PRESTACAO', 'atvEvento'),
  ('120201', 'Exibições cinematográficas.', 'PRESTACAO', 'atvEvento'),
  ('120301', 'Espetáculos circenses.', 'PRESTACAO', 'atvEvento'),
  ('120401', 'Programas de auditório.', 'PRESTACAO', 'atvEvento'),
  ('120501', 'Parques de diversões, centros de lazer e congêneres.', 'PRESTACAO', 'atvEvento'),
  ('120601', 'Boates, taxi-dancing e congêneres.', 'PRESTACAO', 'atvEvento'),
  ('120701', 'Shows, ballet, danças, desfiles, bailes, óperas, concertos, recitais, festivais e congêneres.', 'PRESTACAO', 'atvEvento'),
  ('120801', 'Feiras, exposições, congressos e congêneres.', 'PRESTACAO', 'atvEvento'),
  ('120901', 'Bilhares.', 'PRESTACAO', 'atvEvento'),
  ('120902', 'Boliches.', 'PRESTACAO', 'atvEvento'),
  ('120903', 'Diversões eletrônicas ou não.', 'PRESTACAO', 'atvEvento'),
  ('121001', 'Corridas e competições de animais.', 'PRESTACAO', 'atvEvento'),
  ('121101', 'Competições esportivas ou de destreza física ou intelectual, com ou sem a participação do espectador.', 'PRESTACAO', 'atvEvento'),
  ('121201', 'Execução de música.', 'PRESTACAO', 'atvEvento'),
  ('121301', 'Produção, mediante ou sem encomenda prévia, de eventos, espetáculos, entrevistas, shows, ballet, danças, desfiles, bailes, teatros, óperas, concertos, recitais, festivais e congêneres.', 'PRESTADOR', 'atvEvento'),
  ('121401', 'Fornecimento de música para ambientes fechados ou não, mediante transmissão por qualquer processo.', 'PRESTACAO', 'atvEvento'),
  ('121501', 'Desfiles de blocos carnavalescos ou folclóricos, trios elétricos e congêneres.', 'PRESTACAO', 'atvEvento'),
  ('121601', 'Exibição de filmes, entrevistas, musicais, espetáculos, shows, concertos, desfiles, óperas, competições esportivas, de destreza intelectual ou congêneres.', 'PRESTACAO', 'atvEvento'),
  ('121701', 'Recreação e animação, inclusive em festas e eventos de qualquer natureza.', 'PRESTACAO', 'atvEvento'),
  ('130201', 'Fonografia ou gravação de sons, inclusive trucagem, dublagem, mixagem e congêneres.', 'PRESTADOR', NULL),
  ('130301', 'Fotografia e cinematografia, inclusive revelação, ampliação, cópia, reprodução, trucagem e congêneres.', 'PRESTADOR', NULL),
  ('130401', 'Reprografia, microfilmagem e digitalização.', 'PRESTADOR', NULL),
  ('130501', 'Composição gráfica, inclusive confecção de impressos gráficos, fotocomposição, clicheria, zincografia, litografia e fotolitografia, exceto se destinados a posterior operação de comercialização ou industrialização, ainda que incorporados, de qualquer forma, a outra mercadoria que deva ser objeto de posterior circulação, tais como bulas, rótulos, etiquetas, caixas, cartuchos, embalagens e manuais técnicos e de instrução, quando ficarão sujeitos ao ICMS.', 'PRESTADOR', NULL),
  ('140101', 'Lubrificação, limpeza, lustração, revisão, carga e recarga, conserto, restauração, blindagem, manutenção e conservação de máquinas, veículos, aparelhos, equipamentos, motores, elevadores ou de qualquer objeto (exceto peças e partes empregadas, que ficam sujeitas ao ICMS).', 'PRESTADOR', NULL),
  ('140201', 'Assistência técnica.', 'PRESTADOR', NULL),
  ('140301', 'Recondicionamento de motores (exceto peças e partes empregadas, que ficam sujeitas ao ICMS).', 'PRESTADOR', NULL),
  ('140401', 'Recauchutagem ou regeneração de pneus.', 'PRESTADOR', NULL),
  ('140501', 'Restauração, recondicionamento, acondicionamento, pintura, beneficiamento, lavagem, secagem, tingimento, galvanoplastia, anodização, corte, recorte, plastificação, costura, acabamento, polimento e congêneres de objetos quaisquer.', 'PRESTADOR', NULL),
  ('140601', 'Instalação e montagem de aparelhos, máquinas e equipamentos, inclusive montagem industrial, prestados ao usuário final, exclusivamente com material por ele fornecido.', 'PRESTADOR', NULL),
  ('140701', 'Colocação de molduras e congêneres.', 'PRESTADOR', NULL),
  ('140801', 'Encadernação, gravação e douração de livros, revistas e congêneres.', 'PRESTADOR', NULL),
  ('140901', 'Alfaiataria e costura, quando o material for fornecido pelo usuário final, exceto aviamento.', 'PRESTADOR', NULL),
  ('141001', 'Tinturaria e lavanderia.', 'PRESTADOR', NULL),
  ('141101', 'Tapeçaria e reforma de estofamentos em geral.', 'PRESTADOR', NULL),
  ('141201', 'Funilaria e lanternagem.', 'PRESTADOR', NULL),
  ('141301', 'Carpintaria.', 'PRESTADOR', NULL),
  ('141302', 'Serralheria.', 'PRESTADOR', NULL),
  ('141401', 'Guincho intramunicipal.', 'PRESTADOR', NULL),
  ('141402', 'Guindaste e içamento.', 'PRESTADOR', NULL),
  ('150101', 'Administração de fundos quaisquer e congêneres.', 'TOMADOR', NULL),
  ('150102', 'Administração de consórcio e congêneres.', 'TOMADOR', NULL),
  ('150103', 'Administração de cartão de crédito ou débito e congêneres.', 'TOMADOR', NULL),
  ('150104', 'Administração de carteira de clientes e congêneres.', 'TOMADOR', NULL),
  ('150105', 'Administração de cheques pré-datados e congêneres.', 'TOMADOR', NULL),
  ('150201', 'Abertura de conta-corrente no País, bem como a manutenção da referida conta ativa e inativa.', 'PRESTADOR', NULL),
  ('150202', 'Abertura de conta-corrente no exterior, bem como a manutenção da referida conta ativa e inativa.', 'PRESTADOR', NULL),
  ('150203', 'Abertura de conta de investimentos e aplicação no País, bem como a manutenção da referida conta ativa e inativa.', 'PRESTADOR', NULL),
  ('150204', 'Abertura de conta de investimentos e aplicação no exterior, bem como a manutenção da referida conta ativa e inativa.', 'PRESTADOR', NULL),
  ('150205', 'Abertura de caderneta de poupança no País, bem como a manutenção da referida conta ativa e inativa.', 'PRESTADOR', NULL),
  ('150206', 'Abertura de caderneta de poupança no exterior, bem como a manutenção da referida conta ativa e inativa.', 'PRESTADOR', NULL),
  ('150207', 'Abertura de contas em geral no País, não abrangida em outro subitem, bem como a manutenção das referidas contas ativas e inativas.', 'PRESTADOR', NULL),
  ('150208', 'Abertura de contas em geral no exterior, não abrangida em outro subitem, bem como a manutenção das referidas contas ativas e inativas.', 'PRESTADOR', NULL),
  ('150301', 'Locação de cofres particulares.', 'PRESTADOR', NULL),
  ('150302', 'Manutenção de cofres particulares.', 'PRESTADOR', NULL),
  ('150303', 'Locação de terminais eletrônicos.', 'PRESTADOR', NULL),
  ('150304', 'Manutenção de terminais eletrônicos.', 'PRESTADOR', NULL),
  ('150305', 'Locação de terminais de atendimento.', 'PRESTADOR', NULL),
  ('150306', 'Manutenção de terminais de atendimento.', 'PRESTADOR', NULL),
  ('150307', 'Locação de bens e equipamentos em geral.', 'PRESTADOR', NULL),
  ('150308', 'Manutenção de bens e equipamentos em geral.', 'PRESTADOR', NULL),
  ('150401', 'Fornecimento ou emissão de atestados em geral, inclusive atestado de idoneidade, atestado de capacidade financeira e congêneres.', 'PRESTADOR', NULL),
  ('150501', 'Cadastro, elaboração de ficha cadastral, renovação cadastral e congêneres.', 'PRESTADOR', NULL),
  ('150502', 'Inclusão no Cadastro de Emitentes de Cheques sem Fundos - CCF.', 'PRESTADOR', NULL),
  ('150503', 'Exclusão no Cadastro de Emitentes de Cheques sem Fundos - CCF.', 'PRESTADOR', NULL),
  ('150504', 'Inclusão em quaisquer outros bancos cadastrais.', 'PRESTADOR', NULL),
  ('150505', 'Exclusão em quaisquer outros bancos cadastrais.', 'PRESTADOR', NULL),
  ('150601', 'Emissão, reemissão e fornecimento de avisos, comprovantes e documentos em geral', 'PRESTADOR', NULL),
  ('150602', 'Abono de firmas.', 'PRESTADOR', NULL),
  ('150603', 'Coleta e entrega de documentos, bens e valores.', 'PRESTADOR', NULL),
  ('150604', 'Comunicação com outra agência ou com a administração central.', 'PRESTADOR', NULL),
  ('150605', 'Licenciamento eletrônico de veículos.', 'PRESTADOR', NULL),
  ('150606', 'Transferência de veículos.', 'PRESTADOR', NULL),
  ('150607', 'Agenciamento fiduciário ou depositário.', 'PRESTADOR', NULL),
  ('150608', 'Devolução de bens em custódia.', 'PRESTADOR', NULL),
  ('150701', 'Acesso, movimentação, atendimento e consulta a contas em geral, por qualquer meio ou processo, inclusive por telefone, fac-símile, internet e telex.', 'PRESTADOR', NULL),
  ('150702', 'Acesso a terminais de atendimento, inclusive vinte e quatro horas.', 'PRESTADOR', NULL),
  ('150703', 'Acesso a outro banco e à rede compartilhada.', 'PRESTADOR', NULL),
  ('150704', 'Fornecimento de saldo, extrato e demais informações relativas a contas em geral, por qualquer meio ou processo.', 'PRESTADOR', NULL),
  ('150801', 'Emissão, reemissão, alteração, cessão, substituição, cancelamento e registro de contrato de crédito.', 'PRESTADOR', NULL),
  ('150802', 'Estudo, análise e avaliação de operações de crédito.', 'PRESTADOR', NULL),
  ('150803', 'Emissão, concessão, alteração ou contratação de aval, fiança, anuência e congêneres.', 'PRESTADOR', NULL),
  ('150804', 'Serviços relativos à abertura de crédito, para quaisquer fins.', 'PRESTADOR', NULL),
  ('150901', 'Arrendamento mercantil (leasing) de quaisquer bens, inclusive cessão de direitos e obrigações, substituição de garantia, alteração, cancelamento e registro de contrato, e demais serviços relacionados ao arrendamento mercantil (leasing).', 'TOMADOR', NULL),
  ('151001', 'Serviços relacionados a cobranças em geral, de títulos quaisquer, de contas ou carnês, de câmbio, de tributos e por conta de terceiros, inclusive os efetuados por meio eletrônico, automático ou por máquinas de atendimento.', 'PRESTADOR', NULL),
  ('151002', 'Serviços relacionados a recebimentos em geral, de títulos quaisquer, de contas ou carnês, de câmbio, de tributos e por conta de terceiros, inclusive os efetuados por meio eletrônico, automático ou por máquinas de atendimento.', 'PRESTADOR', NULL),
  ('151003', 'Serviços relacionados a pagamentos em geral, de títulos quaisquer, de contas ou carnês, de câmbio, de tributos e por conta de terceiros, inclusive os efetuados por meio eletrônico, automático ou por máquinas de atendimento.', 'PRESTADOR', NULL),
  ('151004', 'Serviços relacionados a fornecimento de posição de cobrança, recebimento ou pagamento.', 'PRESTADOR', NULL),
  ('151005', 'Serviços relacionados a emissão de carnês, fichas de compensação, impressos e documentos em geral.', 'PRESTADOR', NULL),
  ('151101', 'Devolução de títulos, protesto de títulos, sustação de protesto, manutenção de títulos, reapresentação de títulos, e demais serviços a eles relacionados.', 'PRESTADOR', NULL),
  ('151201', 'Custódia em geral, inclusive de títulos e valores mobiliários.', 'PRESTADOR', NULL),
  ('151301', 'Serviços relacionados a operações de câmbio em geral, edição, alteração, prorrogação, cancelamento e baixa de contrato de câmbio.', 'PRESTADOR', NULL),
  ('151302', 'Serviços relacionados a emissão de registro de exportação ou de crédito.', 'PRESTADOR', NULL),
  ('151303', 'Serviços relacionados a cobrança ou depósito no exterior.', 'PRESTADOR', NULL),
  ('151304', 'Serviços relacionados a emissão, fornecimento e cancelamento de cheques de viagem.', 'PRESTADOR', NULL),
  ('151305', 'Serviços relacionados a fornecimento, transferência, cancelamento e demais serviços relativos a carta de crédito de importação, exportação e garantias recebidas.', 'PRESTADOR', NULL),
  ('151306', 'Serviços relacionados a envio e recebimento de mensagens em geral relacionadas a operações de câmbio.', 'PRESTADOR', NULL),
  ('151401', 'Fornecimento, emissão, reemissão de cartão magnético, cartão de crédito, cartão de débito, cartão salário e congêneres.', 'PRESTADOR', NULL),
  ('151402', 'Renovação de cartão magnético, cartão de crédito, cartão de débito, cartão salário e congêneres.', 'PRESTADOR', NULL),
  ('151403', 'Manutenção de cartão magnético, cartão de crédito, cartão de débito, cartão salário e congêneres.', 'PRESTADOR', NULL),
  ('151501', 'Compensação de cheques e títulos quaisquer.', 'PRESTADOR', NULL),
  ('151502', 'Serviços relacionados a depósito, inclusive depósito identificado, a saque de contas quaisquer, por qualquer meio ou processo, inclusive em terminais eletrônicos e de atendimento.', 'PRESTADOR', NULL),
  ('151601', 'Emissão, reemissão, liquidação, alteração, cancelamento e baixa de ordens de pagamento, ordens de crédito e similares, por qualquer meio ou processo.', 'PRESTADOR', NULL),
  ('151602', 'Serviços relacionados à transferência de valores, dados, fundos, pagamentos e similares, inclusive entre contas em geral.', 'PRESTADOR', NULL),
  ('151701', 'Emissão e fornecimento de cheques quaisquer, avulso ou por talão.', 'PRESTADOR', NULL),
  ('151702', 'Devolução de cheques quaisquer, avulso ou por talão.', 'PRESTADOR', NULL),
  ('151703', 'Sustação, cancelamento e oposição de cheques quaisquer, avulso ou por talão.', 'PRESTADOR', NULL),
  ('151801', 'Serviços relacionados a crédito imobiliário, de avaliação e vistoria de imóvel ou obra.', 'PRESTADOR', NULL),
  ('151802', 'Serviços relacionados a crédito imobiliário, de análise técnica e jurídica.', 'PRESTADOR', NULL),
  ('151803', 'Serviços relacionados a crédito imobiliário, de emissão, reemissão, alteração, transferência e renegociação de contrato.', 'PRESTADOR', NULL),
  ('151804', 'Serviços relacionados a crédito imobiliário, de emissão e reemissão do termo de quitação.', 'PRESTADOR', NULL),
  ('151805', 'Demais serviços relacionados a crédito imobiliário.', 'PRESTADOR', NULL),
  ('160101', 'Serviços de transporte coletivo municipal rodoviário de passageiros.', 'PRESTADOR', NULL),
  ('160102', 'Serviços de transporte coletivo municipal metroviário de passageiros.', 'PRESTACAO', NULL),
  ('160103', 'Serviços de transporte coletivo municipal ferroviário de passageiros.', 'PRESTACAO', NULL),
  ('160104', 'Serviços de transporte coletivo municipal aquaviário de passageiros.', 'PRESTACAO', NULL),
  ('160201', 'Outros serviços de transporte de natureza municipal.', 'PRESTACAO', NULL),
  ('170101', 'Assessoria ou consultoria de qualquer natureza, não contida em outros itens desta lista.', 'PRESTADOR', NULL),
  ('170102', 'Análise, exame, pesquisa, coleta, compilação e fornecimento de dados e informações de qualquer natureza, inclusive cadastro e similares.', 'PRESTADOR', NULL),
  ('170201', 'Datilografia, digitação, estenografia e congêneres.', 'PRESTADOR', NULL),
  ('170202', 'Expediente, secretaria em geral, apoio e infra-estrutura administrativa e congêneres.', 'PRESTADOR', NULL),
  ('170203', 'Resposta audível e congêneres.', 'PRESTADOR', NULL),
  ('170204', 'Redação, edição, revisão e congêneres.', 'PRESTADOR', NULL),
  ('170205', 'Interpretação, tradução e congêneres.', 'PRESTADOR', NULL),
  ('170301', 'Planejamento, coordenação, programação ou organização técnica.', 'PRESTADOR', NULL),
  ('170302', 'Planejamento, coordenação, programação ou organização financeira.', 'PRESTADOR', NULL),
  ('170303', 'Planejamento, coordenação, programação ou organização administrativa.', 'PRESTADOR', NULL),
  ('170401', 'Recrutamento, agenciamento, seleção e colocação de mão-de-obra.', 'PRESTADOR', NULL),
  ('170501', 'Fornecimento de mão-de-obra, mesmo em caráter temporário, inclusive de empregados ou trabalhadores, avulsos ou temporários, contratados pelo prestador de serviço.', 'TOMADOR', NULL),
  ('170601', 'Propaganda e publicidade, inclusive promoção de vendas, planejamento de campanhas ou sistemas de publicidade, elaboração de desenhos, textos e demais materiais publicitários.', 'PRESTADOR', NULL),
  ('170801', 'Franquia (franchising).', 'PRESTADOR', NULL),
  ('170901', 'Perícias, laudos, exames técnicos e análises técnicas.', 'PRESTADOR', NULL),
  ('171001', 'Planejamento, organização e administração de feiras, exposições, e congêneres.', 'PRESTACAO', NULL),
  ('171002', 'Planejamento, organização e administração de congressos e congêneres.', 'PRESTACAO', NULL),
  ('171101', 'Organização de festas e recepções.', 'PRESTADOR', NULL),
  ('171102', 'Bufê (exceto o fornecimento de alimentação e bebidas, que fica sujeito ao ICMS).', 'PRESTADOR', NULL),
  ('171201', 'Administração em geral, inclusive de bens e negócios de terceiros.', 'PRESTADOR', NULL),
  ('171301', 'Leilão e congêneres.', 'PRESTADOR', NULL),
  ('171401', 'Advocacia', 'PRESTADOR', NULL),
  ('171501', 'Arbitragem de qualquer espécie, inclusive jurídica.', 'PRESTADOR', NULL),
  ('171601', 'Auditoria.', 'PRESTADOR', NULL),
  ('171701', 'Análise de Organização e Métodos.', 'PRESTADOR', NULL),
  ('171801', 'Atuária e cálculos técnicos de qualquer natureza.', 'PRESTADOR', NULL),
  ('171901', 'Contabilidade, inclusive serviços técnicos e auxiliares.', 'PRESTADOR', NULL),
  ('172001', 'Consultoria e assessoria econômica ou financeira.', 'PRESTADOR', NULL),
  ('172101', 'Estatística.', 'PRESTADOR', NULL),
  ('172201', 'Cobrança em geral.', 'PRESTADOR', NULL),
  ('172301', 'Assessoria, análise, avaliação, atendimento, consulta, cadastro, seleção, gerenciamento de informações, administração de contas a receber ou a pagar e em geral, relacionados a operações de faturização (factoring).', 'PRESTADOR', NULL),
  ('172401', 'Apresentação de palestras, conferências, seminários e congêneres.', 'PRESTADOR', NULL),
  ('172501', 'Inserção de textos, desenhos e outros materiais de propaganda e publicidade, em qualquer meio (exceto em livros, jornais, periódicos e nas modalidades de serviços de radiodifusão sonora e de sons e imagens de recepção livre e gratuita).', 'PRESTADOR', NULL),
  ('180101', 'Serviços de regulação de sinistros vinculados a contratos de seguros e congêneres.', 'PRESTADOR', NULL),
  ('180102', 'Serviços de inspeção e avaliação de riscos para cobertura de contratos de seguros e congêneres.', 'PRESTADOR', NULL),
  ('180103', 'Serviços de prevenção e gerência de riscos seguráveis e congêneres.', 'PRESTADOR', NULL),
  ('190101', 'Serviços de distribuição e venda de bilhetes e demais produtos de loteria, cartões, pules ou cupons de apostas, sorteios, prêmios, inclusive os decorrentes de títulos de capitalização e congêneres.', 'PRESTADOR', NULL),
  ('190102', 'Serviços de distribuição e venda de bingos e congêneres.', 'PRESTADOR', NULL),
  ('200101', 'Serviços portuários, ferroportuários, utilização de porto, movimentação de passageiros, reboque de embarcações, rebocador escoteiro, atracação, desatracação, serviços de praticagem, capatazia, armazenagem de qualquer natureza, serviços acessórios, movimentação de mercadorias, serviços de apoio marítimo, de movimentação ao largo, serviços de armadores, estiva, conferência, logística e congêneres.', 'ESPECIAL', NULL),
  ('200301', 'Serviços de terminais rodoviários, ferroviários, metroviários, movimentação de passageiros, mercadorias, inclusive suas operações, logística e congêneres.', 'PRESTACAO', NULL),
  ('210101', 'Serviços de registros públicos, cartorários e notariais.', 'PRESTADOR', NULL),
  ('220101', 'Serviços de exploração de rodovia mediante cobrança de preço ou pedágio dos usuários, envolvendo execução de serviços de conservação, manutenção, melhoramentos para adequação de capacidade e segurança de trânsito, operação, monitoração, assistência aos usuários e outros serviços definidos em contratos, atos de concessão ou de permissão ou em normas oficiais.', 'ESPECIAL', 'explRod'),
  ('230101', 'Serviços de programação e comunicação visual e congêneres.', 'PRESTADOR', NULL),
  ('230102', 'Serviços de desenho industrial e congêneres.', 'PRESTADOR', NULL),
  ('240101', 'Serviços de chaveiros, confecção de carimbos e congêneres.', 'PRESTADOR', NULL),
  ('240102', 'Serviços de placas, sinalização visual, banners, adesivos e congêneres.', 'PRESTADOR', NULL),
  ('250101', 'Funerais, inclusive fornecimento de caixão, urna ou esquifes; aluguel de capela; transporte do corpo cadavérico; fornecimento de flores, coroas e outros paramentos; desembaraço de certidão de óbito; fornecimento de véu, essa e outros adornos; embalsamento, embelezamento, conservação ou restauração de cadáveres.', 'PRESTADOR', NULL),
  ('250201', 'Translado intramunicipal de corpos e partes de corpos cadavéricos.', 'PRESTADOR', NULL),
  ('250202', 'Cremação de corpos e partes de corpos cadavéricos.', 'PRESTADOR', NULL),
  ('250301', 'Planos ou convênio funerários.', 'PRESTADOR', NULL),
  ('250401', 'Manutenção e conservação de jazigos e cemitérios.', 'PRESTADOR', NULL),
  ('250501', 'Cessão de uso de espaços em cemitérios para sepultamento.', 'PRESTADOR', NULL),
  ('260101', 'Serviços de coleta, remessa ou entrega de correspondências, documentos, objetos, bens ou valores, inclusive pelos correios e suas agências franqueadas.', 'PRESTADOR', NULL),
  ('260102', 'Serviços de courrier e congêneres.', 'PRESTADOR', NULL),
  ('270101', 'Serviços de assistência social.', 'PRESTADOR', NULL),
  ('280101', 'Serviços de avaliação de bens e serviços de qualquer natureza.', 'PRESTADOR', NULL),
  ('290101', 'Serviços de biblioteconomia.', 'PRESTADOR', NULL),
  ('300101', 'Serviços de biologia e biotecnologia.', 'PRESTADOR', NULL),
  ('300102', 'Serviços de química.', 'PRESTADOR', NULL),
  ('310101', 'Serviços técnicos em edificações e congêneres.', 'PRESTADOR', NULL),
  ('310102', 'Serviços técnicos em eletrônica, eletrotécnica e congêneres.', 'PRESTADOR', NULL),
  ('310103', 'Serviços técnicos em mecânica e congêneres.', 'PRESTADOR', NULL),
  ('310104', 'Serviços técnicos em telecomunicações e congêneres.', 'PRESTADOR', NULL),
  ('320101', 'Serviços de desenhos técnicos.', 'PRESTADOR', NULL),
  ('330101', 'Serviços de desembaraço aduaneiro, comissários, despachantes e congêneres.', 'PRESTADOR', NULL),
  ('340101', 'Serviços de investigações particulares, detetives e congêneres.', 'PRESTADOR', NULL),
  ('350101', 'Serviços de reportagem e jornalismo.', 'PRESTADOR', NULL),
  ('350102', 'Serviços de assessoria de imprensa.', 'PRESTADOR', NULL),
  ('350103', 'Serviços de relações públicas.', 'PRESTADOR', NULL),
  ('360101', 'Serviços de meteorologia.', 'PRESTADOR', NULL),
  ('370101', 'Serviços de artistas, atletas, modelos e manequins.', 'PRESTADOR', NULL),
  ('380101', 'Serviços de museologia.', 'PRESTADOR', NULL),
  ('390101', 'Serviços de ourivesaria e lapidação (quando o material for fornecido pelo tomador do serviço).', 'PRESTADOR', NULL),
  ('400101', 'Obras de arte sob encomenda.', 'PRESTADOR', NULL),
  ('990101', 'Serviços sem a incidência de ISSQN e ICMS', 'SEM_INCIDENCIA', 'lsadppu / obra / atvEvento / explRod');

-- ---------------------------------------------------------------------------------------------
-- 3. O atalho por ramo — escolher entre 5, não entre 334
-- ---------------------------------------------------------------------------------------------
-- Decisão do dono do produto (2026-08-29): *"o que conseguirmos automatizar e entregar mastigado
-- ao cliente é melhor, e como definimos os ramos de atuação fica mais restrito e certeiro"*.
--
-- A V093 acabou de criar os cinco ramos de serviço (29–33). Cada um aponta para os códigos que a
-- atividade daquele ramo de fato usa, na ordem em que a tela os oferece. O lojista de petshop vê
-- cinco linhas com o texto oficial e escolhe; a lista inteira continua atrás da busca.
--
-- ⚠️ ESTE MAPA É CURADORIA, NÃO FONTE OFICIAL — e a diferença precisa estar escrita, porque o
-- resto desta migration é o contrário disso. Não existe mapa oficial ramo→cTribNac; o que existe
-- é o texto de cada código, e foi por ele que a seleção foi feita (o `grep` está reproduzido no
-- comentário de cada bloco). Consequências que a tela tem de respeitar:
--   (a) é SUGESTÃO — nenhum código é aplicado sozinho, o lojista confirma;
--   (b) a tela diz "confirme com seu contador", porque enquadramento é decisão fiscal;
--   (c) não sugerir é melhor que sugerir errado: ramo sem linha aqui simplesmente cai na busca.
-- ⛔ Mesma postura da V093, que deixou CNAE genérico fora do mapa: *"sugerir a partir de código
-- que serve a dezenas de atividades é chutar com cara de certeza"*.

CREATE TABLE cfg_ramo_servico_lc116 (
  id_ramo smallint NOT NULL REFERENCES cfg_ramo_atividade (id_ramo),
  codigo  char(6)  NOT NULL REFERENCES cfg_servico_lc116 (codigo),
  ordem   smallint NOT NULL,
  CONSTRAINT cfg_ramo_servico_lc116_pk PRIMARY KEY (id_ramo, codigo)
);

COMMENT ON TABLE cfg_ramo_servico_lc116 IS
  'Sugestão de códigos de serviço por ramo de atividade — GLOBAL, sem RLS, como cfg_ramo_cnae. '
  '⚠️ Curadoria a partir do texto oficial de cada código, NÃO um mapa oficial: a tela oferece, o '
  'lojista confirma. Ver o cabeçalho da seção 3 da V099.';

COMMENT ON COLUMN cfg_ramo_servico_lc116.ordem IS
  'Ordem de exibição dentro do ramo — o primeiro é o caso típico da atividade, não o alfabético.';

INSERT INTO cfg_ramo_servico_lc116 (id_ramo, codigo, ordem) VALUES
  -- 29 · Assistência técnica e consertos
  (29, '140201', 1),  -- Assistência técnica.
  (29, '140101', 2),  -- Lubrificação, limpeza, ... conserto, restauração, ... de máquinas, veículos,
                      -- aparelhos, equipamentos, motores ... (exceto peças, que ficam no ICMS)
  (29, '140601', 3),  -- Instalação e montagem de aparelhos, máquinas e equipamentos ...

  -- 30 · Clínica veterinária  (e o petshop, que é o mesmo item 5 da LC 116)
  (30, '050202', 1),  -- Clínicas, ambulatórios, prontos-socorros e congêneres, na área veterinária.
  (30, '050801', 2),  -- ⭐ Guarda, tratamento, amestramento, EMBELEZAMENTO, alojamento e congêneres.
                      -- É o banho e tosa. Não existe subitem "banho e tosa" na lista nacional —
                      -- procurei por "tosa", "banho", "higiene" e "pet" e a fonte não tem nenhum;
                      -- quem cobre a atividade é o "embelezamento" do 05.08. Registrado aqui
                      -- porque é exatamente o tipo de coisa que alguém "corrige" depois.
  (30, '050101', 3),  -- Medicina veterinária
  (30, '050301', 4),  -- Laboratórios de análise na área veterinária.
  (30, '050201', 5),  -- Hospitais e congêneres, na área veterinária.

  -- 31 · Lava-rápido e estética automotiva
  (31, '140101', 1),  -- Lubrificação, LIMPEZA, LUSTRAÇÃO ... de máquinas, VEÍCULOS ...
  (31, '140501', 2),  -- Restauração, ... LAVAGEM, secagem, ... POLIMENTO e congêneres de objetos
                      -- quaisquer.

  -- 32 · Oficina mecânica e funilaria
  (32, '140101', 1),  -- ... revisão, conserto, restauração, MANUTENÇÃO E CONSERVAÇÃO de máquinas,
                      -- VEÍCULOS ... ⭐ e o parêntese que importa para a venda mista do Nainer:
                      -- "(exceto peças e partes empregadas, que ficam sujeitas ao ICMS)" — é a
                      -- própria LC 116 dizendo que a peça sai na NFC-e e a mão de obra na NFS-e.
  (32, '140501', 2),  -- Restauração, recondicionamento, ... PINTURA, ... polimento (funilaria)
  (32, '140601', 3),  -- Instalação e montagem de aparelhos, máquinas e equipamentos (acessórios)

  -- 33 · Salão de beleza e barbearia
  (33, '060101', 1),  -- Barbearia, cabeleireiros, manicuros, pedicuros e congêneres.
  (33, '060201', 2),  -- Esteticistas, tratamento de pele, depilação e congêneres.
  (33, '060501', 3);  -- Centros de emagrecimento, spa e congêneres.

GRANT SELECT ON cfg_ramo_servico_lc116 TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_ramo_servico_lc116 TO niner_owner;
