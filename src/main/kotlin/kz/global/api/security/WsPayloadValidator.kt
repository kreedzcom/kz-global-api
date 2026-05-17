package kz.global.api.security

import kz.global.api.ws.AddRecordPayload

object WsPayloadValidator {

    private val STEAM_ID_REGEX = Regex("^STEAM_[0-5]:\\d+:\\d+$")
    private val MAP_NAME_REGEX = Regex("^[a-zA-Z0-9_\\-.]+$")
    private val LOCAL_UID_REGEX = Regex("^[a-zA-Z0-9_\\-]{1,64}$")

    const val MAX_MAP_NAME_LENGTH = 255
    const val MAX_NICKNAME_LENGTH = 64
    const val MAX_RECORD_TIME_MS = 86_400_000L // 24 hours

    fun validateSteamId(steamid: String): String? {
        if (steamid.length > 32) return "Invalid steamid"
        if (!STEAM_ID_REGEX.matches(steamid)) return "Invalid steamid format"
        return null
    }

    fun validateMapName(mapName: String): String? {
        if (mapName.isBlank()) return "map_name must not be empty"
        if (mapName.length > MAX_MAP_NAME_LENGTH) return "map_name too long"
        if (!MAP_NAME_REGEX.matches(mapName)) return "Invalid map_name characters"
        return null
    }

    fun validateLocalUid(localUid: String): String? {
        if (localUid.isBlank()) return "local_uid must not be empty"
        if (!LOCAL_UID_REGEX.matches(localUid)) return "Invalid local_uid"
        return null
    }

    fun validateNickname(nickname: String): String? {
        if (nickname.isBlank()) return "nickname must not be empty"
        if (nickname.length > MAX_NICKNAME_LENGTH) return "nickname too long"
        return null
    }

    fun validateRecordTime(timeMs: Long): String? {
        if (timeMs <= 0) return "time_ms must be positive"
        if (timeMs > MAX_RECORD_TIME_MS) return "time_ms exceeds maximum"
        return null
    }

    fun validateAddRecord(payload: AddRecordPayload): String? {
        return validateSteamId(payload.steamid)
            ?: validateMapName(payload.mapName)
            ?: validateLocalUid(payload.localUid)
            ?: validateRecordTime(payload.timeMs)
    }

    fun validateAdminRecordTime(timeMs: Long): String? {
        if (timeMs <= 0) return "time_ms must be positive"
        if (timeMs > MAX_RECORD_TIME_MS) return "time_ms exceeds maximum"
        return null
    }

}
