package com.xiaoyiyuan.study.ui.control;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatusModel} 的护栏（Task 2）。
 *
 * <p>状态栏是「外壳」里唯一一处把 <b>studyDir / 工作模式 / 最后保存时间</b> 三件事
 * 摊开给用户看的地方。它此前只可能被人工目视检查，因此这里把三件事的<b>取值</b>抽成
 * 纯逻辑模型：不依赖 JavaFX，也不依赖文件系统。</p>
 *
 * <p>「最后保存时间」由 Task 3 的 {@code DocumentSession} 提供；Task 2 先留接口，
 * {@code null} 一律显示「尚未保存」——<b>不是</b>「未保存成功」这类会踩禁用词的写法。</p>
 */
class StatusModelTest {

    @Test
    void statusLine_namesAllThreeFacts_ofTheShell() {
        StatusModel model = new StatusModel(Path.of("C:\\示例\\study"), false);

        assertEquals("C:\\示例\\study", model.studyDirText(), "第一件事：当前 studyDir 原文");
        assertEquals("离线可用", model.modeText(), "第二件事：未配置 AI 时的中性说法");
        assertEquals("尚未保存", model.lastSaveText(), "第三件事：Task 3 之前固定是「尚未保存」");
        assertEquals("C:\\示例\\study · 离线可用 · 尚未保存", model.statusLine(),
                "状态栏三段用「 · 」分隔，顺序 = studyDir / 工作模式 / 最后保存");
    }

    @Test
    void aiConfiguredMode_isSaidPlainly() {
        StatusModel model = new StatusModel(Path.of("D:\\study"), true);

        assertEquals("已配置 AI", model.modeText(), "配置了 API Key 之后的中性说法");
        assertEquals("D:\\study · 已配置 AI · 尚未保存", model.statusLine());
    }

    @Test
    void switchingTheAiFlag_changesTheStatusLine() {
        StatusModel model = new StatusModel(Path.of("D:\\study"), false);
        String before = model.statusLine();

        model.setAiConfigured(true);

        assertEquals("D:\\study · 已配置 AI · 尚未保存", model.statusLine());
        assertTrue(!before.equals(model.statusLine()), "配置变化必须真的反映到状态栏文本上");
    }

    @Test
    void lastSaveTime_isRenderedWithoutSeconds_andIsUpdateable() {
        StatusModel model = new StatusModel(Path.of("D:\\study"), false);

        model.setLastSave(java.time.LocalDateTime.of(2026, 10, 14, 9, 5, 3));

        assertEquals("2026-10-14 09:05", model.lastSaveText(), "保存时间精确到分钟（秒对用户没有意义）");
        assertEquals("D:\\study · 离线可用 · 2026-10-14 09:05", model.statusLine());

        model.setLastSave(null);
        assertEquals("尚未保存", model.lastSaveText(), "置空必须回到「尚未保存」而不是显示 null");
    }

    @Test
    void blankOrDefaultStudyDir_fallsBackToAPlaceholderInsteadOfAnEmptySlotInTheStatusBar() {
        assertEquals("（尚未选择学习目录）", new StatusModel(Path.of(""), false).studyDirText(),
                "studyDir 为空时状态栏不能出现一个空格位——用户会以为界面坏了");
        assertEquals("（尚未选择学习目录）", new StatusModel(null, false).studyDirText(),
                "studyDir 为 null 时（配置读坏了）同样退到占位文本，绝不抛异常");
        assertEquals("（尚未选择学习目录） · 离线可用 · 尚未保存",
                new StatusModel(null, false).statusLine());
    }

    @Test
    void statusTexts_neverContainForbiddenWords() {
        java.util.List<String> forbidden =
                java.util.List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持");
        StatusModel[] models = {
                new StatusModel(Path.of("D:\\study"), false),
                new StatusModel(Path.of("D:\\study"), true),
                new StatusModel(null, false),
        };
        for (StatusModel model : models) {
            for (String text : java.util.List.of(model.statusLine(), model.modeText(),
                    model.lastSaveText(), model.studyDirText())) {
                for (String word : forbidden) {
                    assertTrue(!text.contains(word),
                            "状态栏文本里出现界面禁用词「" + word + "」：" + text);
                }
            }
        }
    }
}
