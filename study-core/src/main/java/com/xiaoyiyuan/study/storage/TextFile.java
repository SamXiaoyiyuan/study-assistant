package com.xiaoyiyuan.study.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取一份 UTF-8 文本的逻辑内容，同时记录它的字节层特征。
 *
 * @param content  文本内容（按 UTF-8 解码，BOM 若存在会作为 \uFEFF 留在串首）
 * @param hasBom   原文件是否以 UTF-8 BOM（EF BB BF）开头
 * @param hasCrlf  原文件内容是否含 CRLF
 */
record TextFile(String content, boolean hasBom, boolean hasCrlf) {
}
