package com.xiaoyiyuan.study.wellbeing;

/**
 * 危机求助资源的一条渠道：显示文案 + 号码（spec §6.5、附录 A）。
 *
 * <p>它是<b>配置层的值类型</b>：{@code AppConfig.crisisResources()} 的五条内置默认、
 * 用户在设置页增删改的自定义渠道、以及危机面板渲染时列出的每一行，用的都是它。
 * 之所以放在 {@code wellbeing} 包而不是 {@code config}：它的归属是「危机兜底」域
 * （spec §6.5），配置只是恰好存了它，而不是反过来——所以由配置层依赖本包，而非本包依赖配置。
 *
 * <p><b>null 字段归一化为空串</b>：界面上「先填号码、后补名字」是常见顺序，
 * 归一化让「没填」只有一种表示（空串），回写与比较都少一个分支；否则 null 会一路漂到
 * 渲染层，变成字面的 {@code "null"} 或一处难以定位的 NPE。
 *
 * <p>本记录<b>不校验号码格式</b>：号码的形态五花八门（12356 / 010-82951332 / 800-810-1117），
 * 「像不像电话号码」的猜测只会在将来误伤合法号码；是否可用交给用户判断，界面如实展示。
 *
 * <p><b>public 是硬要求</b>：{@code config} 包与计划 3 的界面都在别的包里消费它。
 *
 * @param label  显示文案；{@code null} 归一化为空串（例如「校医院三楼」）
 * @param number 号码；{@code null} 归一化为空串（号码才是身份，见 {@code AppConfig} 的删除保护）
 */
public record CrisisResource(String label, String number) {

    /** 紧凑构造器：任何构造路径都绕不过这次归一化。 */
    public CrisisResource {
        label = label == null ? "" : label;
        number = number == null ? "" : number;
    }
}
