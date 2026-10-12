package com.xiaoyiyuan.study.ai;

/**
 * 流式回复的收取方（spec §7.1 的「流式输出」）。
 *
 * <p>调用方通过它<b>边收边显示</b>：正文一到就 {@link #onDelta(String)} 追加一次，
 * 正常结束时 {@link #onComplete(String)} 拿到全文。之所以是「推」而不是返回一个
 * {@code Iterator<String>}，是因为服务端的节奏不受我们控制——注释行心跳、几百毫秒的空档都是常态，
 * 由收取方在回调里决定怎么刷新界面，比让调用方自己轮询简单。
 *
 * <p><b>这个接口只传字符串</b>：AI 的输出在落盘之前只能以纯文本存在（spec §7.5）。
 * 接口里没有、也不许有 {@code Path} / 文件 / 输出流——「写进文件」是界面拿到用户确认后的另一件事。
 *
 * <p><b>中断不是「不完整」的同义词</b>：流被取消或断连时不会调用 {@link #onComplete(String)}，
 * 调用方手里只有已经收到的增量。界面据此标记「<b>回复不完整</b>」（spec §7.6 的措辞）。
 */
public interface AiStreamListener {

    /**
     * 收到一段增量正文。
     *
     * <p>回调发生在<b>读取流的那条线程</b>上，调用方若要更新界面需自行切回界面线程。
     * 每个增量都保证是完整的字符串：UTF-8 多字节字符被网络分块切断时会先在内部攒齐再回调，
     * 因此这里绝不会出现半个汉字或替换字符 U+FFFD。
     *
     * @param text 增量正文；不会是 {@code null}，也不会是空串
     */
    void onDelta(String text);

    /**
     * 正常结束（收到 {@code [DONE]} 或流自然收尾），且<b>整个回复只调用一次</b>。
     *
     * <p>流中断（取消 / 断连 / 超时）时不会调用本方法——那时调用方应当用已收到的增量。
     *
     * @param fullText 拼接好的全文，等于按顺序收到过的所有增量之和
     */
    void onComplete(String fullText);
}
