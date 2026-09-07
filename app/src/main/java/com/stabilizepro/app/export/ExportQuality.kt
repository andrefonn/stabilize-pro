package com.stabilizepro.app.export

enum class ExportQuality(
    val title: String,
    val subtitle: String,
    val maxTargetWidth: Int,
    val maxTargetHeight: Int,
    val targetBitrateBps: Int
) {
    ORIGINAL(
        title = "Original",
        subtitle = "Preserva resolução e taxa de bits nativas",
        maxTargetWidth = 3840,
        maxTargetHeight = 2160,
        targetBitrateBps = 0 // 0 = automatic derived from source
    ),
    ALTA(
        title = "Alta (1080p)",
        subtitle = "Full HD otimizado • 10-12 Mbps • Ideal para web",
        maxTargetWidth = 1920,
        maxTargetHeight = 1080,
        targetBitrateBps = 10_000_000
    ),
    MAXIMA(
        title = "Máxima (4K Pro)",
        subtitle = "Ultra resolução 4K • 20-25 Mbps • Máxima fidelidade",
        maxTargetWidth = 3840,
        maxTargetHeight = 2160,
        targetBitrateBps = 24_000_000
    )
}
