/**
 * Módulo <b>canais</b> — canais de venda e anúncios. Interface comum
 * {@code CanalDeVenda} (publicar/atualizar estoque/preço, importar pedidos, confirmar
 * envio); cada marketplace é um adapter (anti-corruption layer). Ver spec §3.1/§3.3.6.
 * Classes entram com as migrations de domínio (V013+).
 */
package com.vetor.niner.canais;
