package com.xiaoyiyuan.study.wellbeing;

/**
 * minor 清算批的自检桩（Task 3 复审 Minor-1 / F5）：一个<b>真实存在的 public 类</b>，
 * 它的父类 {@link RefusalBase} 却是包级私有的，并在那一层提供了 {@code public} 的
 * {@code haltAnyway()}。
 *
 * <p>为什么必须是独立的 public 顶层类：{@code getMethods()} 的盲区只在这种形态下出现——
 * 「非 public 子类 → <b>public 中间类</b> → 非 public 基类」时，基类那一层的 public 方法
 * 不会出现在子类的 {@code getMethods()} 里。而本机 JDK 25 上「直接父类非 public」那一档
 * {@code getMethods()} 反而看得见（详见 {@code CrisisKeywordTest} 里那条自检用例的注释），
 * 所以桩要摆成三级才能验出真盲区。
 */
public class PublicRefusalMid extends RefusalBase {

    /** 第二层拒绝式 API：声明在 public 父类上，{@code getMethods()} 看得见它。 */
    public void stopEverything() {
        // 桩：只为反射面而存在，永不被调用。
    }
}
