package com.campusclaw.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class MaterialParserService {

    private static final int PREVIEW_LIMIT = 2000;

    /** 预览文本：截断到 2000 字符，供材料列表展示。 */
    public String parse(String contentType, byte[] bytes) {
        String text = parseFull(contentType, bytes);
        return text.length() > PREVIEW_LIMIT ? text.substring(0, PREVIEW_LIMIT) : text;
    }

    /** 完整解析正文（知识条目 body_text 使用），不截断。 */
    public String parseFull(String contentType, byte[] bytes) {
        if (contentType == null || (!contentType.startsWith("text/")
                && !contentType.equals("application/json")
                && !contentType.equals("application/xml"))) {
            return "";
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }
}
