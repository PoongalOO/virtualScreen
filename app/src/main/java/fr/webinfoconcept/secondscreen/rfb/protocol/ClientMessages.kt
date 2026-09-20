package fr.webinfoconcept.secondscreen.rfb.protocol

/**
 * Construction des messages client de configuration et de requête (SS-021, SS-022, SS-027). Les deux
 * premiers sont envoyés une fois après `ServerInit` et **avant** la première `FramebufferUpdateRequest` :
 *
 * ```text
 * SetPixelFormat : U8 type = 0, 3 octets de padding, PIXEL_FORMAT (16 octets)         -> 20 octets
 * SetEncodings   : U8 type = 2, 1 octet de padding, U16 n, puis n × S32 (encodages)   -> 4 + 4n octets
 * FramebufferUpdateRequest : U8 type = 3, U8 incremental, U16 x, U16 y, U16 w, U16 h  -> 10 octets
 * ```
 *
 * `FramebufferUpdateRequest` est envoyé tout au long de la session (SS-027), pas seulement au début.
 *
 * Chaque fonction renvoie un tableau neuf, à envoyer avec un **seul** appel
 * `RfbSocket.write(message, 0, message.size)` pour ne jamais entrelacer deux messages.
 * Aucune donnée du serveur n'entre ici : ce sont des messages que le client compose.
 */
object ClientMessages {
    const val TYPE_SET_PIXEL_FORMAT = 0
    const val TYPE_SET_ENCODINGS = 2
    const val TYPE_FRAMEBUFFER_UPDATE_REQUEST = 3

    const val SET_PIXEL_FORMAT_LENGTH = 4 + PixelFormat.WIRE_SIZE
    const val FRAMEBUFFER_UPDATE_REQUEST_LENGTH = 10

    /**
     * Intervalle du message de battement, en ms (voir [keepAliveRequest]). Mesuré : jusqu'à 400 ms la latence
     * médiane tombe à ~5 ms ; à 100 ms seuls 2 % des paquets dépassent 50 ms (contre 8 à 18 % à 200-400 ms).
     */
    const val KEEP_ALIVE_INTERVAL_MS = 100L

    /** Plus grand nombre d'encodages qu'on accepte d'annoncer (le champ du protocole est un U16). */
    const val MAX_ENCODINGS = 64

    /**
     * `SetPixelFormat` : impose au serveur le format des pixels qu'il enverra. Par défaut
     * [PixelFormat.XRGB_8888_LE] (32 bits true-colour, documenté sur ce type).
     *
     * @throws IllegalArgumentException [format] incohérent : on n'envoie jamais un format invalide.
     */
    fun setPixelFormat(format: PixelFormat = PixelFormat.XRGB_8888_LE): ByteArray {
        val message = ByteArray(SET_PIXEL_FORMAT_LENGTH) // padding déjà à zéro
        message.putU8At(0, TYPE_SET_PIXEL_FORMAT)
        format.writeTo(message, 4)
        return message
    }

    /**
     * `FramebufferUpdateRequest` (SS-027) : demande au serveur d'envoyer les pixels de la zone
     * ([x], [y], [w] × [h]).
     *
     * - [incremental] = `true` : seulement ce qui a changé depuis la dernière mise à jour. Le serveur peut
     *   attendre qu'un changement se produise avant de répondre : c'est la requête normale d'un client en régime
     *   établi, à renvoyer après chaque `FramebufferUpdate` reçu.
     * - [incremental] = `false` : la zone entière, quoi qu'il en soit. À réserver au premier affichage, après un
     *   changement de format ou d'encodage, et à la reconnexion.
     *
     * Les quatre valeurs sont des U16 ; la zone doit être non vide. Elle n'est **pas** bornée à la taille de
     * l'écran ici (la taille du framebuffer distant n'est pas connue de ce message) : c'est l'appelant qui la déduit
     * de `ServerInit`. Un serveur ignore ou rogne une zone hors écran.
     *
     * @throws IllegalArgumentException coordonnée hors 0..65535 ou zone vide.
     */
    fun framebufferUpdateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int): ByteArray {
        require(w > 0 && h > 0) { "zone vide : ${w}x$h" }
        val message = ByteArray(FRAMEBUFFER_UPDATE_REQUEST_LENGTH)
        message.putU8At(0, TYPE_FRAMEBUFFER_UPDATE_REQUEST)
        message.putU8At(1, if (incremental) 1 else 0)
        message.putU16At(2, x)
        message.putU16At(4, y)
        message.putU16At(6, w)
        message.putU16At(8, h)
        return message
    }

    /**
     * Message de **battement** : une requête incrémentale d'un seul pixel en (0, 0), à envoyer toutes les
     * [KEEP_ALIVE_INTERVAL_MS] ms pour empêcher la radio Wi-Fi de la tablette de s'endormir.
     *
     * Mesuré sur la GT-P5110 (PERFORMANCE.md) : sur une liaison silencieuse, un paquet qui arrive attend
     * 0,5 à 1,9 s (médiane ~700 ms) ; un petit paquet émis toutes les 100 ms ramène cela à ~4 ms. Une requête
     * incrémentale d'un pixel est légale à tout moment ; un serveur n'y répond que si ce pixel a changé, et
     * elle n'ajoute au plus qu'un pixel à la zone demandée. Validé contre TigerVNC (PERFORMANCE.md).
     */
    fun keepAliveRequest(): ByteArray = framebufferUpdateRequest(incremental = true, x = 0, y = 0, w = 1, h = 1)

    /**
     * `SetEncodings` : liste des encodages acceptés, par ordre de préférence. Par défaut
     * [Encoding.ADVERTISED]. Les pseudo-encodages (négatifs) sont admis : ils s'écrivent en S32.
     *
     * @throws IllegalArgumentException liste vide, plus de [MAX_ENCODINGS] entrées, ou doublon.
     */
    fun setEncodings(encodings: List<Int> = Encoding.ADVERTISED): ByteArray {
        require(encodings.isNotEmpty()) { "au moins un encodage est requis" }
        require(encodings.size <= MAX_ENCODINGS) { "trop d'encodages : ${encodings.size} (max $MAX_ENCODINGS)" }
        require(encodings.toSet().size == encodings.size) { "encodage en double" }

        val message = ByteArray(4 + 4 * encodings.size)
        message.putU8At(0, TYPE_SET_ENCODINGS)
        message.putU16At(2, encodings.size)
        encodings.forEachIndexed { i, encoding -> message.putS32At(4 + 4 * i, encoding) }
        return message
    }
}
