package fr.webinfoconcept.secondscreen.rfb.protocol

/**
 * Construction des messages client de configuration (SS-021, SS-022), envoyés une fois après
 * `ServerInit` et **avant** la première `FramebufferUpdateRequest` :
 *
 * ```text
 * SetPixelFormat : U8 type = 0, 3 octets de padding, PIXEL_FORMAT (16 octets)         -> 20 octets
 * SetEncodings   : U8 type = 2, 1 octet de padding, U16 n, puis n × S32 (encodages)   -> 4 + 4n octets
 * ```
 *
 * Chaque fonction renvoie un tableau neuf, à envoyer avec un **seul** appel
 * `RfbSocket.write(message, 0, message.size)` pour ne jamais entrelacer deux messages.
 * Aucune donnée du serveur n'entre ici : ce sont des messages que le client compose.
 */
object ClientMessages {
    const val TYPE_SET_PIXEL_FORMAT = 0
    const val TYPE_SET_ENCODINGS = 2

    const val SET_PIXEL_FORMAT_LENGTH = 4 + PixelFormat.WIRE_SIZE

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
