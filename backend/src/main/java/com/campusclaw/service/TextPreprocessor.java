package com.campusclaw.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 切分前预处理：只作用于送入切分器的临时副本，
 * 原文件与 knowledge_entries.body_text 一律不改写。
 * 预处理改变文本后，切片偏移表达的是预处理后文本的位置，不是原文件下标。
 */
@Component
public class TextPreprocessor {

    private static final Pattern URL = Pattern.compile("https?://\\S+|www\\.\\S+");
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\S+");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    public String process(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = URL.matcher(raw).replaceAll(" ");
        text = EMAIL.matcher(text).replaceAll(" ");
        // 合并空白：行内空白折叠为单个空格，换行保留（断点识别依赖换行），多余空行压缩
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text;
    }
}
