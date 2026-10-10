package com.xiaoyiyuan.study.storage;

import java.io.IOException;

/**
 * 文件不是合法 UTF-8 时抛出。
 *
 * <p>为什么必须拒绝而不是替换：{@code new String(bytes, UTF_8)} 会把无法解码的字节
 * 静默替换成 U+FFFD，用户随后一保存，文件里那些字节就被永久改写了——这是静默的数据损坏
 * （中文 Windows 上常见的 GBK 文件正好会踩中）。P1「不修改就写回字节相同」因此收窄为
 * <b>所有合法 UTF-8 输入</b>；非法输入一律拒绝，绝不返回有损字符串。
 *
 * <p>消息里带上文件路径与首个非法字节的偏移，用户才知道该去处理哪个文件的哪个位置。
 */
public class NotUtf8Exception extends IOException {

    public NotUtf8Exception(String message) {
        super(message);
    }

    public NotUtf8Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
