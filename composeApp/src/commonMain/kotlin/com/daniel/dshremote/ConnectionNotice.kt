package com.daniel.dshremote

/** 错误分类：recoverable=true 表示「连接类」错误（hello 到达可自动清除）；false 表示业务类错误（需手动清除）。 */
data class NoticeError(val message: String, val recoverable: Boolean)

/** 统一「连接状态提示槽」：单一槽、三形态互斥展示，取代旧的 reconnecting 布尔 + errors 横幅两套独立机制。 */
sealed interface ConnectionNotice {
    /** 无提示（已恢复/未出错）。 */
    data object Hidden : ConnectionNotice
    /** 自动重连中（Loading 形态）。 */
    data class Reconnecting(val attempt: Int) : ConnectionNotice
    /** 错误形态（真实错误）。 */
    data class Error(val message: String) : ConnectionNotice
}

/** hello 到达后的错误对账：清掉「连接类」（可自动恢复）错误，保留业务类错误。 */
internal fun reconcileErrorsOnHello(errors: List<NoticeError>): List<NoticeError> =
    errors.filterNot { it.recoverable }
