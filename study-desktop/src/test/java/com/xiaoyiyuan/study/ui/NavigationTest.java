package com.xiaoyiyuan.study.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.xiaoyiyuan.study.ui.view.Page;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Navigation} 与 {@link Page} 的护栏（Task 2）。
 *
 * <p>导航是纯逻辑，不碰 JavaFX：{@code Navigation} 只维护「当前页 + 监听者」，
 * 内容区的更换由 {@code AppShell} 订阅完成——这样「切页」这件事本身可以在没有图形栈的
 * 环境里被断言，而不是只能靠人工点一遍。</p>
 *
 * <p>本类里两条与 spec 对齐的断言是<b>故意分开的</b>：一条钉住计划书里逐字抄下来的
 * 侧栏清单，一条只钉住 spec §8.1 的<b>相对顺序</b>（过滤掉 spec 里没有的项）。理由见
 * {@link #spec81_sidebarRelativeOrder_isPreserved()} 的说明。</p>
 */
class NavigationTest {

    // ================================================================================
    // 侧栏清单与顺序
    // ================================================================================

    @Test
    void sidebarItems_followsSpecOrder() {
        assertEquals(
                List.of(Page.OVERVIEW, Page.GOALS, Page.COURSES, Page.WEEK_CHECK, Page.WEEK_LOG,
                        Page.EMOTION, Page.EMOTION_TREND, Page.ASSISTANT, Page.SETTINGS),
                new Navigation(Page.OVERVIEW).sidebarItems(),
                "侧栏顺序即 spec §8.1 的页面顺序 + 设置页收尾（本计划裁定 A2：情绪拆成记录/趋势两页）");
        assertEquals(9, new Navigation(Page.OVERVIEW).sidebarItems().size(),
                "侧栏恰好 9 项（spec §8.1 的 8 页去掉「情绪」、加上「情绪趋势」与「设置」）");
    }

    /**
     * spec §8.1 的那 8 个页面在侧栏里的<b>相对顺序</b>必须与 spec 表格逐行一致。
     *
     * <p><b>为什么不把 plan 里那条逐字列表当成「spec 顺序」的判据</b>：逐字核对
     * {@code docs/superpowers/specs/2026-10-10-study-assistant-design.md} §8.1 的表格后，
     * spec 的页面顺序是「总览 → 目标 → 课程 → 周检查 → 周记录 → 情绪 → <b>助手</b> → 设置」，
     * 而任务简报与主计划给的 9 项清单把「情绪趋势」插在「情绪」与「助手」之间。
     * 也就是说：简报的清单与 spec 表格在<b>助手</b>这一项的位置上并不逐字一致
     * （「情绪趋势」是 spec 里根本没有的第 9 项，它的落点只能由简报决定）。
     * 因此这里分成两条断言——本任务<b>按简报的逐字清单</b>实现（上一测试），
     * 同时用本断言保证「spec 有的那 8 项的相对顺序」没有被写乱。两者同时为真时，
     * 无论将来把「情绪趋势」挪到哪一侧，都只有一条会红——冲突立刻可见，而不是被静默吸收。</p>
     */
    @Test
    void spec81_sidebarRelativeOrder_isPreserved() {
        List<String> specOrder = spec81PageNames();
        assertEquals(
                List.of("总览", "目标", "课程", "周检查", "周记录", "情绪", "助手", "设置"),
                specOrder,
                "spec §8.1 的页面清单必须逐字是这个顺序（本断言直接解析 spec 文档，"
                        + "措辞被改动时会红——注意 spec 写的是「周检查」而不是「周计划」）");

        List<String> sidebarInSpecOrder = new Navigation(Page.OVERVIEW).sidebarItems().stream()
                .map(Page::label)
                .filter(specOrder::contains)
                .toList();
        assertEquals(specOrder, sidebarInSpecOrder,
                "侧栏里的 8 个 spec 页面必须保持 spec §8.1 的相对顺序（允许插入 spec 之外的第 9 项）");
    }

    @Test
    void spec_isTheSourceOfWording_weekCheckNotWeekPlan() {
        assertTrue(spec81PageNames().contains("周检查"),
                "spec §8.1 写的是「周检查」；计划书里 MASTER.md 的「周计划」措辞不采用（裁定 A2）");
        assertEquals("周检查", Page.WEEK_CHECK.label(), "页面取名必须取 spec 措辞");
    }

    @Test
    void crisisPage_isReachableButNotInSidebar() {
        List<Page> sidebar = new Navigation(Page.OVERVIEW).sidebarItems();
        assertFalse(sidebar.contains(Page.CRISIS),
                "求助资源是覆盖层（Task 10），侧栏里不该有它：" + sidebar);

        // 可达：go(CRISIS) 必须成功，且侧栏不变
        Navigation navigation = new Navigation(Page.OVERVIEW);
        navigation.go(Page.CRISIS);
        assertEquals(Page.CRISIS, navigation.current(), "求助资源页仍必须可达");
        assertEquals(List.of(Page.OVERVIEW, Page.GOALS, Page.COURSES, Page.WEEK_CHECK, Page.WEEK_LOG,
                        Page.EMOTION, Page.EMOTION_TREND, Page.ASSISTANT, Page.SETTINGS),
                navigation.sidebarItems(),
                "切到求助资源页不该改变侧栏内容");
    }

    @Test
    void everyEnumConstantIsEitherSidebarOrExplicitlyOffSidebar() {
        List<Page> sidebar = new Navigation(Page.OVERVIEW).sidebarItems();
        List<Page> offSidebar = new ArrayList<>();
        for (Page page : Page.values()) {
            if (page.inSidebar()) {
                assertTrue(sidebar.contains(page),
                        page + " 声明 inSidebar()=true，就必须出现在 sidebarItems() 里：" + sidebar);
            } else {
                offSidebar.add(page);
                assertFalse(sidebar.contains(page),
                        page + " 声明 inSidebar()=false，就不该出现在侧栏里：" + sidebar);
            }
        }
        assertEquals(List.of(Page.CRISIS), offSidebar,
                "本计划只允许一个非侧栏页（求助资源）；新增非侧栏页必须同时改这条断言");
    }

    // ================================================================================
    // 状态与通知
    // ================================================================================

    @Test
    void go_notifiesListenersExactlyOnce() {
        Navigation navigation = new Navigation(Page.OVERVIEW);
        List<Page> notified = new ArrayList<>();
        navigation.addListener(notified::add);

        navigation.go(Page.GOALS);

        assertEquals(List.of(Page.GOALS), notified,
                "切页必须恰好通知一次（重复通知会让内容区重建两次、监听者被调用多次）");
        assertEquals(Page.GOALS, navigation.current(), "current() 必须跟着变");
    }

    @Test
    void go_samePageTwice_doesNotNotifyTwice() {
        Navigation navigation = new Navigation(Page.OVERVIEW);
        List<Page> notified = new ArrayList<>();
        navigation.addListener(notified::add);

        navigation.go(Page.OVERVIEW);
        navigation.go(Page.OVERVIEW);

        assertTrue(notified.isEmpty(),
                "重复切到当前页不该通知（每次通知都会重建内容区、抢焦点、丢掉正在编辑的内容）；实际 " + notified);
    }

    @Test
    void go_notifiesEveryListener_andLaterListenersStillGetCalled() {
        Navigation navigation = new Navigation(Page.OVERVIEW);
        List<String> calls = new ArrayList<>();
        navigation.addListener(p -> calls.add("first:" + p));
        navigation.addListener(p -> calls.add("second:" + p));

        navigation.go(Page.EMOTION);

        assertEquals(List.of("first:" + Page.EMOTION, "second:" + Page.EMOTION), calls,
                "两个监听者都必须被通知到，且按注册顺序");
    }

    @Test
    void constructorRejectsNull_andGoRejectsNull() {
        assertThrows(NullPointerException.class, () -> new Navigation(null),
                "初始页为 null 必须在构造时就拒绝，而不是等到切页时炸在监听者里");
        Navigation navigation = new Navigation(Page.OVERVIEW);
        assertThrows(NullPointerException.class, () -> navigation.go(null), "go(null) 必须拒绝");
        assertThrows(NullPointerException.class, () -> navigation.addListener(null), "addListener(null) 必须拒绝");
    }

    @Test
    void listenerAddedDuringNotification_doesNotBreakTheCurrentRound() {
        Navigation navigation = new Navigation(Page.OVERVIEW);
        List<Page> notified = new ArrayList<>();
        navigation.addListener(page -> {
            notified.add(page);
            // 监听者在通知过程中又注册一个新的——不得抛 ConcurrentModificationException
            navigation.addListener(ignored -> { });
        });

        navigation.go(Page.COURSES);

        assertEquals(List.of(Page.COURSES), notified,
                "通知过程中新增监听者不该破坏本轮通知，也不该让新监听者收到「历史」通知");
    }

    @Test
    void labels_areTheSpecWording_andAreDistinct() {
        assertEquals("总览", Page.OVERVIEW.label());
        assertEquals("目标", Page.GOALS.label());
        assertEquals("课程", Page.COURSES.label());
        assertEquals("周检查", Page.WEEK_CHECK.label());
        assertEquals("周记录", Page.WEEK_LOG.label());
        assertEquals("情绪", Page.EMOTION.label());
        assertEquals("情绪趋势", Page.EMOTION_TREND.label());
        assertEquals("助手", Page.ASSISTANT.label());
        assertEquals("设置", Page.SETTINGS.label());
        assertEquals("求助资源", Page.CRISIS.label());

        List<String> labels = new ArrayList<>();
        for (Page page : Page.values()) {
            assertFalse(page.label().isBlank(), page + " 的标签不能为空");
            assertFalse(labels.contains(page.label()), page + " 的标签与前面的页面重复：" + page.label());
            labels.add(page.label());
        }
        assertEquals(Page.values().length, labels.size(),
                "10 个页面必须有 10 个互不相同的标签，实际 " + labels);
    }

    @Test
    void toString_isTheLabel_notTheEnumName() {
        assertEquals("周检查", Page.WEEK_CHECK.toString(),
                "toString() 用枚举名会让「标题里出现 WEEK_CHECK」这种英文字样溜到界面上");
        assertNotEquals(Page.WEEK_CHECK.name(), Page.WEEK_CHECK.toString());
    }

    // ================================================================================
    // 直接从 spec 文档读取 §8.1 的页面清单（措辞不可再被转述走样）
    // ================================================================================

    /** spec 里 §8.1 的 8 个页面名，按表格行序。读不到文档时给出明确失败。 */
    static List<String> spec81PageNames() {
        List<String> specOrder = new ArrayList<>();
        for (String line : specLines()) {
            if (line.startsWith("| **") && line.contains("** |")) {
                String name = line.substring(line.indexOf("**") + 2, line.indexOf("** |"));
                if (!name.isBlank()) {
                    specOrder.add(name);
                }
            }
        }
        return specOrder;
    }

    /** 只取 `### 8.1` 与下一个 `###` 之间的正文行（行首空白已去掉）。 */
    static List<String> specLines() {
        String text = specText();
        List<String> out = new ArrayList<>();
        boolean inside = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("### 8.1")) {
                inside = true;
                continue;
            }
            if (inside && line.startsWith("### ")) {
                break;
            }
            if (inside) {
                out.add(line);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("没有在 spec 里找到 §8.1 一节");
        }
        return out;
    }

    static String specText() {
        Path path = Path.of("..", "docs", "superpowers", "specs",
                "2026-10-10-study-assistant-design.md");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("找不到 spec 文档：" + path.toAbsolutePath());
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
