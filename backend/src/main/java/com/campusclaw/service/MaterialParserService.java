package com.campusclaw.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class MaterialParserService {

    private static final int PREVIEW_LIMIT = 2000;

    public String parse(String contentType, byte[] bytes) {
        if (contentType == null || (!contentType.startsWith("text/")
                && !contentType.equals("application/json")
                && !contentType.equals("application/xml"))) {
            return "";
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.length() > PREVIEW_LIMIT ? text.substring(0, PREVIEW_LIMIT) : text;
    }
}
