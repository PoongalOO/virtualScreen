package fr.webinfoconcept.secondscreen.ui

import android.content.Context
import fr.webinfoconcept.secondscreen.R
import fr.webinfoconcept.secondscreen.profile.FieldError
import fr.webinfoconcept.secondscreen.session.ConnectionFailure
import fr.webinfoconcept.secondscreen.session.FailureKind

/**
 * Messages destinés à l'utilisateur (SS-053) : une phrase qui dit ce qui s'est passé **et** ce qu'on peut faire. Le
 * `when` est exhaustif : ajouter un [FailureKind] sans message ne compile pas.
 */
object FailureMessages {

    fun messageRes(kind: FailureKind): Int = when (kind) {
        FailureKind.UNKNOWN_HOST -> R.string.failure_unknown_host
        FailureKind.CONNECT_TIMEOUT -> R.string.failure_connect_timeout
        FailureKind.CONNECTION_REFUSED -> R.string.failure_connection_refused
        FailureKind.NETWORK_UNREACHABLE -> R.string.failure_network_unreachable
        FailureKind.NOT_A_VNC_SERVER -> R.string.failure_not_a_vnc_server
        FailureKind.UNSUPPORTED_VERSION -> R.string.failure_unsupported_version
        FailureKind.SERVER_REJECTED -> R.string.failure_server_rejected
        FailureKind.PASSWORD_REQUIRED -> R.string.failure_password_required
        FailureKind.PASSWORD_INVALID -> R.string.failure_password_invalid
        FailureKind.NO_COMPATIBLE_SECURITY -> R.string.failure_no_compatible_security
        FailureKind.AUTH_FAILED -> R.string.failure_auth_failed
        FailureKind.HANDSHAKE_TIMEOUT -> R.string.failure_handshake_timeout
        FailureKind.UNSUPPORTED_SERVER_SIZE -> R.string.failure_unsupported_server_size
        FailureKind.CONNECTION_LOST -> R.string.failure_connection_lost
        FailureKind.NETWORK_LOST -> R.string.failure_network_lost
        FailureKind.PROTOCOL_ERROR -> R.string.failure_protocol_error
        FailureKind.LOCAL_ERROR -> R.string.failure_local_error
    }

    /**
     * Texte complet d'un échec. Pour un refus du serveur, sa raison (déjà assainie par la couche protocole : ASCII
     * imprimable, longueur bornée) est ajoutée si elle existe.
     */
    fun text(context: Context, failure: ConnectionFailure): String =
        if (failure.kind == FailureKind.SERVER_REJECTED && failure.serverReason.isNotEmpty()) {
            context.getString(R.string.failure_server_rejected_reason, failure.serverReason)
        } else {
            context.getString(messageRes(failure.kind))
        }

    fun fieldErrorRes(error: FieldError): Int = when (error) {
        FieldError.NAME_TOO_LONG -> R.string.error_name_too_long
        FieldError.NAME_INVALID -> R.string.error_name_invalid
        FieldError.HOST_EMPTY -> R.string.error_host_empty
        FieldError.HOST_INVALID -> R.string.error_host_invalid
        FieldError.PORT_INVALID -> R.string.error_port_invalid
    }
}
