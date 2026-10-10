package com.xiaoyiyuan.study.plan;

/**
 * §一 我的现状里的一项（spec §4.4；表：项目 / 内容 的一行）。
 *
 * <p>键是「项目」列（如「年级 / 学期」「本学期课程」），值是「内容」列。
 * 键是这一行的身份：{@link PlanDocument#setProfileItem(int, String)} 只改值，不改键——
 * 改键等于换了一个字段，那种改动应当是删一行再加一行，而不是在这里悄悄替换。
 *
 * @param key   项目名（这一行的身份）
 * @param value 内容
 */
public record ProfileItem(String key, String value) {
}
