package kz.global.api.domain.maps

object MapMetadata {

    private val TYPE_BY_NAME =
        mapOf(
            "bhop" to 0,
            "climb" to 1,
            "slide" to 2,
            "mix" to 3,
            "special" to 4,
        )

    fun parseMapType(value: String?): Int? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { numeric ->
            return if (numeric in 0..4) numeric else null
        }
        return TYPE_BY_NAME[trimmed.lowercase()]
    }

    fun validateLengthTier(value: Int?): Int? = value?.takeIf { it in 0..4 }

    fun validateDifficulty(value: Int?): Int? = value?.takeIf { it in 0..9 }

}
