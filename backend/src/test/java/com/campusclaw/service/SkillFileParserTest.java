package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 技能文件解析纯函数单测：合法解析、各类非法文件、切词。 */
class SkillFileParserTest {

    private static final String VALID = """
            名称：数学解题引导
            适用条件：方程,去括号、分数；通分|面积
            资料：数学
            工具：knowledge_search
            启用：是

            先判断学生卡在哪一步，只指出下一步应该检查什么。
            不要给出最终得数。
            """;

    @Test
    void parsesValidFile() {
        SkillFileParser.ParsedSkill parsed = SkillFileParser.parse(VALID);

        assertThat(parsed.name()).isEqualTo("数学解题引导");
        assertThat(parsed.conditions()).isEqualTo("方程,去括号、分数；通分|面积");
        assertThat(parsed.keywords()).containsExactly("方程", "去括号", "分数", "通分", "面积");
        assertThat(parsed.subjectScope()).isEqualTo("数学");
        assertThat(parsed.tool()).isEqualTo("knowledge_search");
        assertThat(parsed.enabled()).isTrue();
        assertThat(parsed.body()).startsWith("先判断学生卡在哪一步");
        assertThat(parsed.rawText()).contains("名称：数学解题引导");
    }

    @Test
    void commentsAndBlankLinesInMetaIgnored() {
        // 注释行被忽略；第一个空行即分隔元数据与正文
        SkillFileParser.ParsedSkill parsed = SkillFileParser.parse(
                "# 注释\n名称：x\n适用条件：y\n启用：否\n\n正文内容");
        assertThat(parsed.name()).isEqualTo("x");
        assertThat(parsed.enabled()).isFalse();
        assertThat(parsed.body()).isEqualTo("正文内容");
    }

    @Test
    void rejectsMissingBlankLine() {
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n适用条件：y\n启用：是"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少空行分隔的说明正文");
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> SkillFileParser.parse("适用条件：y\n启用：是\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称");
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n启用：是\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("适用条件");
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n适用条件：y\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("启用");
    }

    @Test
    void rejectsDuplicateAndUnknownFields() {
        assertThatThrownBy(() -> SkillFileParser.parse("名称：a\n名称：b\n适用条件：y\n启用：是\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复字段");
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n适用条件：y\n启用：是\n等级：高\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void rejectsInvalidBooleanAndTool() {
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n适用条件：y\n启用：也许\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("启用");
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n适用条件：y\n启用：是\n工具：bash\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge_search");
    }

    @Test
    void rejectsMissingBodyAndOverlongFields() {
        assertThatThrownBy(() -> SkillFileParser.parse("名称：x\n适用条件：y\n启用：是\n\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文");

        String longName = "名".repeat(33);
        assertThatThrownBy(() -> SkillFileParser.parse(
                "名称：" + longName + "\n适用条件：y\n启用：是\n\n正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称");

        String longBody = "字".repeat(2001);
        assertThatThrownBy(() -> SkillFileParser.parse(
                "名称：x\n适用条件：y\n启用：是\n\n" + longBody))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文");

        String longFile = "x".repeat(8001);
        assertThatThrownBy(() -> SkillFileParser.parse(longFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8000");
    }

    @Test
    void splitConditionsHandlesAllSeparators() {
        assertThat(SkillFileParser.splitConditions("a，b,c、d；e;f|g"))
                .containsExactly("a", "b", "c", "d", "e", "f", "g");
        assertThat(SkillFileParser.splitConditions("  a  ， ， b "))
                .containsExactly("a", "b");
    }
}
