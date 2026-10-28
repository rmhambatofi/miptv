package com.miptv

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackErrorTest {
    private fun describe(code: Int, cause: Throwable? = null) = describePlaybackError(PlaybackException("http://secret:pw@host/stream", cause, code))

    @Test fun networkParsingAndDecodingErrorsGetDistinctFrenchMessages() {
        assertEquals("Connexion au flux impossible. Vérifiez votre réseau.", describe(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals("Flux introuvable (HTTP 404).", describe(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals("Format du flux non reconnu.", describe(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertEquals("Ce flux ne peut pas être décodé sur cet appareil.", describe(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals("Le serveur a refusé le flux.", describe(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals("Lecture indisponible. Vérifiez votre connexion.", describe(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test fun messagesNeverEchoTheStreamLocation() {
        for (code in listOf(2000, 2001, 2004, 2005, 3001, 4001, 1000)) assertFalse(describe(code).contains("host"))
    }
}
