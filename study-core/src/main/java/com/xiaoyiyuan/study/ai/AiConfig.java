package com.xiaoyiyuan.study.ai;

import java.time.Duration;

/**
 * AI 连接配置：{@code base_url} / {@code api_key} / {@code model} / 超时（spec §7.1）。
 *
 * <p>这是一个<b>不落盘的纯值对象</b>：配置文件的读写归 {@code config.AppConfig}。本类只负责
 * 「一份能发出去请求的配置长什么样」，因此构造时就拒绝那些「只可能产生一次坏请求」的值
 * （空 base_url / 空 api_key / 空 model / 非正超时）。理由：这些值传进来时离用户很近
 * （刚从设置界面读到），当场抛异常能定位到是哪一项填错了；若放行，失败会推迟到几秒后的
 * HTTP 层，变成一条看不出根因的网络错误，用户只能反复点「重试」。
 *
 * <p><b>api_key 逐字节照发，不做 trim</b>：凭据是服务端签发的字符串，任何一侧的「顺手清理」
 * 都可能把合法密钥改成非法密钥。{@code base_url} 相反——它是拿来做拼接的，
 * 首尾空白会让规范化的第一步就落空（拼出 {@code %20}），所以这里去掉。
 *
 * @param baseUrl OpenAI 兼容端点的基础地址，如 {@code https://api.deepseek.com}；
 *                允许带子路径；最终请求地址由 {@link AiClient#endpoint(String)} 规范化
 * @param apiKey  明文密钥（spec §7.1：不做假加密，界面如实告知）
 * @param model   模型名，如 {@code deepseek-chat}
 * @param timeout 单次请求超时；必须为正
 */
public record AiConfig(String baseUrl, String apiKey, String model, Duration timeout) {

    public AiConfig {
        baseUrl = baseUrl == null ? null : baseUrl.trim();
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException(
                    "base_url 不能为空：请先填写 AI 服务的地址（例如 https://api.deepseek.com）。");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "api_key 不能为空：请先填写 AI 服务的密钥（明文保存在本机配置里）。");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model 不能为空：请填写模型名（例如 deepseek-chat）。");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "请求超时必须为正数；收到：" + timeout + "。零或负超时会让每次请求直接失败。");
        }
    }
}
