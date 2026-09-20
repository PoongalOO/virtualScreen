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
 * `PointerEvent` (SS-040) : U8 type = 5, U8 masque des boutons, U16 x, U16 y -> 6 octets. Les coordonnées sont celles
 * du **framebuffer distant** (pas de l'écran de la tablette) ; le masque dit quels boutons sont enfoncés *à cet instant*
 * (bit 0 = gauche, bit 1 = milieu, bit 2 = droit, bits 3 et 4 = molette haut/bas, voir [PointerButtons]).
 *
 * Chaque fonction renvoie un tableau neuf, à envoyer avec un **seul** appel
 * `RfbSocket.write(message, 0, message.size)` pour ne jamais entrelacer deux messages.
 * Aucune donnée du serveur n'entre ici : ce sont des messages que le client compose.
 */
object ClientMessages {
    const val TYPE_SET_PIXEL_FORMAT = 0
    const val TYPE_SET_ENCODINGS = 2
    const val TYPE_FRAMEBUFFER_UPDATE_REQUEST = 3
    const val TYPE_POINTER_EVENT = 5

    const val SET_PIXEL_FORMAT_LENGTH = 4 + PixelFormat.WIRE_SIZE
    const val FRAMEBUFFER_UPDATE_REQUEST_LENGTH = 10
    const val POINTER_EVENT_LENGTH = 6

    /** Longueur de [leftClick] : trois `PointerEvent`. */
    const val LEFT_CLICK_LENGTH = 3 * POINTER_EVENT_LENGTH

    /** Longueur de [dragStart] : deux `PointerEvent`. */
    const val DRAG_START_LENGTH = 2 * POINTER_EVENT_LENGTH

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
     * `PointerEvent` (SS-040) : position du pointeur ([x], [y], en pixels du framebuffer distant) et état des
     * boutons ([buttonMask], voir [PointerButtons]). L'état est **absolu** : le serveur déduit les appuis et
     * relâchements en comparant au masque précédent. Un même message sert donc à déplacer (masque inchangé), appuyer
     * (bit à 1) et relâcher (bit à 0).
     *
     * @throws IllegalArgumentException [x] ou [y] hors 0..65535, ou [buttonMask] hors 0..255.
     */
    fun pointerEvent(buttonMask: Int, x: Int, y: Int): ByteArray {
        val message = ByteArray(POINTER_EVENT_LENGTH)
        writePointerEvent(message, 0, buttonMask, x, y)
        return message
    }

    /**
     * Clic gauche complet en **un seul message** de [LEFT_CLICK_LENGTH] octets, à envoyer avec un seul `write` :
     * ```text
     * (masque 0, x, y)   le pointeur arrive sans bouton (survol)
     * (masque 1, x, y)   appui
     * (masque 0, x, y)   relâchement
     * ```
     * Un seul tableau garantit que l'appui n'est jamais envoyé sans son relâchement (bouton coincé côté serveur) ni
     * entrelacé avec un autre message.
     */
    fun leftClick(x: Int, y: Int): ByteArray {
        val message = ByteArray(LEFT_CLICK_LENGTH)
        writePointerEvent(message, 0, 0, x, y)
        writePointerEvent(message, POINTER_EVENT_LENGTH, PointerButtons.LEFT, x, y)
        writePointerEvent(message, 2 * POINTER_EVENT_LENGTH, 0, x, y)
        return message
    }

    /**
     * Début d'un glissement (SS-042) en **un seul message** de [DRAG_START_LENGTH] octets : le pointeur arrive sans
     * bouton (survol) puis le bouton gauche est enfoncé, au même pixel. Le glissement se poursuit avec
     * `pointerEvent(PointerButtons.LEFT, x, y)` à chaque déplacement et se termine par `pointerEvent(0, x, y)`
     * (relâchement) : sans ce dernier message le serveur garderait le bouton enfoncé.
     */
    fun dragStart(x: Int, y: Int): ByteArray {
        val message = ByteArray(DRAG_START_LENGTH)
        writePointerEvent(message, 0, 0, x, y)
        writePointerEvent(message, POINTER_EVENT_LENGTH, PointerButtons.LEFT, x, y)
        return message
    }

    private fun writePointerEvent(message: ByteArray, offset: Int, buttonMask: Int, x: Int, y: Int) {
        message.putU8At(offset, TYPE_POINTER_EVENT)
        message.putU8At(offset + 1, buttonMask)
        message.putU16At(offset + 2, x)
        message.putU16At(offset + 4, y)
    }

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

/** Bits du masque de boutons d'un `PointerEvent` (SS-040) : bit N-1 = bouton N, comme X11. */
object PointerButtons {
    const val LEFT = 1
    const val MIDDLE = 2
    const val RIGHT = 4
    const val WHEEL_UP = 8
    const val WHEEL_DOWN = 16
}
