package uk.flickpay.flickpaypos

object AccountHostNormalizer {

    fun normalize(raw: String?, defaultHost: String = "app.flickpay.co.uk"): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return defaultHost

        val withoutScheme = text
            .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('/')
        val hostOnly = withoutScheme.substringBefore('/').substringBefore('?').trim().lowercase()
        if (hostOnly.isBlank()) return defaultHost

        val legacyMapped = if (hostOnly == "devtests.flickpay.co.uk") defaultHost else hostOnly
        return if (!legacyMapped.contains(".")) "$legacyMapped.flickpay.co.uk" else legacyMapped
    }
}
