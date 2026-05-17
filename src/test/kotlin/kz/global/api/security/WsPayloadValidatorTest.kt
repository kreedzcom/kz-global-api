package kz.global.api.security

import kz.global.api.ws.AddRecordPayload
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WsPayloadValidatorTest {

    @Test
    fun `validateSteamId accepts valid legacy steam id`() {
        assertNull(WsPayloadValidator.validateSteamId("STEAM_0:0:12345"))
    }

    @Test
    fun `validateSteamId rejects invalid format`() {
        assertNotNull(WsPayloadValidator.validateSteamId("not-a-steam-id"))
    }

    @Test
    fun `validateRecordTime rejects zero and negative`() {
        assertNotNull(WsPayloadValidator.validateRecordTime(0))
        assertNotNull(WsPayloadValidator.validateRecordTime(-1))
    }

    @Test
    fun `validateAddRecord rejects invalid map name`() {
        val payload = AddRecordPayload("STEAM_0:0:1", "bad map!", 1000L, "uid-1", 0, 0)

        assertNotNull(WsPayloadValidator.validateAddRecord(payload))
    }

}
