package com.xiaoyiyuan.study.ai;

/**
 * AI 请求的失败分类（spec §7.6：网络失败 / 超时 / 401 / 429 ……）。
 *
 * <p>分类的价值在于「界面能据此说一句有用的话」：重试有没有意义、要改的是密钥还是网络。
 * 因此这里的粒度是按<b>用户接下来该做什么</b>分的，不是按 HTTP 语义分的——
 * 例如所有 5xx 与 4xx 里的「服务端没接受这次请求」都归入需要重试或改请求的两类，
 * 而 401/403 单独成类，因为唯一的解法是改 api_key。
 *
 * <p>刻意<b>不含</b>「用户取消」之类的分类：中断由 {@code InterruptedException} 表达，
 * 与「请求本身不行」是两件事。
 */
public enum AiFailureKind {

    /** 连接不上（DNS 解析不了、被拒绝、TLS 握手失败、发到一半断了）。先查网络与 base_url。 */
    NETWORK,

    /** 超过配置的超时时间。服务端可能只是慢，也可能根本没在回。 */
    TIMEOUT,

    /** 密钥被拒绝（401 / 403）。要改的是 api_key。 */
    UNAUTHORIZED,

    /** 请求过于频繁（429）；其余 4xx（请求本身服务端不接受）也归这里，因为两者都「重试有可能成功」。 */
    RATE_LIMITED,

    /** 服务端出了状况（5xx）。重试通常有意义，用户无需改配置。 */
    SERVER_ERROR,

    /** 通了，但响应体不是预期的 JSON 形状：网关 HTML 错误页、choices 为空、content 为 null…… */
    MALFORMED_RESPONSE
}
