package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.wellbeing.EmotionLog;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 情绪记录文件的界面侧门面：编辑走 {@link EmotionLog}，落盘走 {@link DocumentSession}。
 *
 * <p><b>没有第二套脏标记</b>：{@link EmotionLog} 不暴露 {@code dirty()}，而「有没有改动」这件事
 * 只能由 {@link DocumentSession} 用「渲染结果 vs 基线文本」判定。所以本类每次编辑后都把
 * {@code log.render()} 交给 session，绝不自己记「我改过」。
 *
 * <p>空文件（新建）走 {@link EmotionLog#empty()}：这样第一条记录写下去时会带上文件头，
 * 而不是得到一份「只有一条记录、没有标题栏」的半成品文件。
 */
public final class EmotionService {

    private final DocumentSession session;
    private EmotionLog log;

    /**
     * @param session 这份情绪记录文件的唯一 session（由 {@code AppShell} 持有并注入）
     */
    public EmotionService(DocumentSession session) {
        this.session = Objects.requireNonNull(session, "session");
        String text = session.currentText();
        this.log = text.isEmpty() ? EmotionLog.empty() : EmotionLog.parse(text);
    }

    /** 当前的情绪记录模型。 */
    public EmotionLog log() {
        return log;
    }

    /**
     * 对记录做一次改动，并把渲染结果交给 session。
     *
     * <p>只改内存，不碰磁盘；真正落盘由 {@link #save} 决定。
     */
    public void mutate(Consumer<EmotionLog> mutation) {
        Objects.requireNonNull(mutation, "mutation").accept(log);
        session.update(log.render());
    }

    /** 保存：语义与 {@link DocumentSession#save} 完全一致（本类不另设写路径）。 */
    public SaveResult save(ExternalChangePolicy policy) throws IOException {
        return session.save(policy);
    }
}
