package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 预处理只作用于临时副本：输出去除 URL/邮箱/冗余空白，输入原样不变。
 */
class TextPreprocessorTest {

    private final TextPreprocessor preprocessor = new TextPreprocessor();

    @Test
    void stripsUrlsAndEmails() {
        String raw = "参考 https://example.com/page 与联系 teacher@school.edu 哦";

        String processed = preprocessor.process(raw);

        assertThat(processed).doesNotContain("https://example.com/page");
        assertThat(processed).doesNotContain("teacher@school.edu");
        assertThat(processed).contains("参考");
    }

    @Test
    void collapsesWhitespaceButKeepsNewlines() {
        String raw = "分数   加减法\t\t教学  \r\n第二行   内容";

        String processed = preprocessor.process(raw);

        assertThat(processed).isEqualTo("分数 加减法 教学 \n第二行 内容");
    }

    @Test
    void collapsesExcessBlankLines() {
        String raw = "标题\n\n\n\n正文内容";

        assertThat(preprocessor.process(raw)).isEqualTo("标题\n\n正文内容");
    }

    @Test
    void doesNotMutateInput() {
        String raw = "访问 https://a.com  查看";
        String copy = raw;

        preprocessor.process(raw);

        assertThat(raw).isEqualTo(copy); // String 不可变，输入恒不变
    }

    @Test
    void handlesEmptyInput() {
        assertThat(preprocessor.process(null)).isEmpty();
        assertThat(preprocessor.process("")).isEmpty();
    }
}
