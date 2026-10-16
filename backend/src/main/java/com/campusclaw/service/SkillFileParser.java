package com.campusclaw.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能文件解析（纯函数）：元数据区 + 第一个空行后的说明正文。
 * 面向教师的可读错误以 IllegalArgumentException 携带中文原因，控制器转 400。
 */
public final class SkillFileParser {

    public static final int MAX_TOTAL = 8000;
    public static final int MAX_NAME = 32;
    public static final int MAX_CONDITIONS = 120;
    public static final int MAX_SUBJECT = 32;
    public static final int MAX_BODY = 2000;
    public static final String ALLOWED_TOOL = "knowledge_search";

    private static final Set<String> KNOWN_KEYS = Set.of("名称", "适用条件", "资料", "工具", "启用");

    /** 解析结果。keywords 为适用条件切词（路由用），conditions 为原文。 */
    public record ParsedSkill(
            String name,
            String conditions,
            List<String> keywords,
            String subjectScope,
            String tool,
            boolean enabled,
            String body,
            String rawText
    ) {
    }

    private SkillFileParser() {
    }

    public static ParsedSkill parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("技能文件不能为空");
        }
        if (rawText.length() > MAX_TOTAL) {
            throw new IllegalArgumentException("技能文件总长度不能超过 " + MAX_TOTAL + " 字（当前 "
                    + rawText.length() + " 字）");
        }
        // 规范化换行后按第一个空行切分元数据与正文
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        int split = indexOfBlankLine(normalized);
        if (split < 0) {
            throw new IllegalArgumentException("缺少空行分隔的说明正文：元数据区之后须有一个空行，空行后是技能说明正文");
        }
        String metaBlock = normalized.substring(0, split);
        String body = normalized.substring(split).replaceFirst("^\n+", "").stripTrailing();

        Map<String, String> meta = parseMeta(metaBlock);

        for (String required : new String[]{"名称", "适用条件", "启用"}) {
            if (!meta.containsKey(required)) {
                throw new IllegalArgumentException("缺少必填字段「" + required + "」");
            }
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("缺少空行后的说明正文");
        }
        if (body.length() > MAX_BODY) {
            throw new IllegalArgumentException("说明正文不能超过 " + MAX_BODY + " 字（当前 " + body.length() + " 字）");
        }

        String name = meta.get("名称").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("「名称」不能为空");
        }
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("「名称」不能超过 " + MAX_NAME + " 字");
        }

        String conditions = meta.get("适用条件").trim();
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("「适用条件」不能为空（没有关键词的技能永远不会被路由到）");
        }
        if (conditions.length() > MAX_CONDITIONS) {
            throw new IllegalArgumentException("「适用条件」不能超过 " + MAX_CONDITIONS + " 字");
        }

        String subjectScope = meta.getOrDefault("资料", "").trim();
        if (subjectScope.length() > MAX_SUBJECT) {
            throw new IllegalArgumentException("「资料」不能超过 " + MAX_SUBJECT + " 字");
        }

        String tool = meta.getOrDefault("工具", ALLOWED_TOOL).trim();
        if (tool.isEmpty()) {
            tool = ALLOWED_TOOL;
        }
        if (!ALLOWED_TOOL.equals(tool)) {
            throw new IllegalArgumentException("「工具」只允许 " + ALLOWED_TOOL + "，当前为「" + tool + "」");
        }

        boolean enabled = parseBoolean(meta.get("启用").trim());

        return new ParsedSkill(name, conditions, splitConditions(conditions), subjectScope,
                tool, enabled, body, normalized);
    }

    /** 元数据行：键：值；忽略 # 注释行与空行；重复键与未知键报错。 */
    private static Map<String, String> parseMeta(String metaBlock) {
        Map<String, String> meta = new HashMap<>();
        for (String line : metaBlock.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int idx = stripped.indexOf('：');
            if (idx < 0) {
                idx = stripped.indexOf(':');
            }
            if (idx <= 0) {
                throw new IllegalArgumentException("无法解析的元数据行「" + stripped + "」（期望「键：值」格式）");
            }
            String key = stripped.substring(0, idx).trim();
            String value = stripped.substring(idx + 1).trim();
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("未知字段「" + key + "」，允许的字段：名称、适用条件、资料、工具、启用");
            }
            if (meta.containsKey(key)) {
                throw new IllegalArgumentException("重复字段「" + key + "」");
            }
            meta.put(key, value);
        }
        return meta;
    }

    private static boolean parseBoolean(String value) {
        return switch (value.toLowerCase()) {
            case "是", "true", "1", "yes" -> true;
            case "否", "false", "0", "no" -> false;
            default -> throw new IllegalArgumentException("「启用」的值非法：「" + value + "」（允许 是/否/true/false/1/0）");
        };
    }

    /** 按中英文逗号、顿号、分号、竖线切词，去空白去空串（手工扫描，避免正则元字符陷阱）。 */
    public static List<String> splitConditions(String conditions) {
        List<String> keywords = new ArrayList<>();
        if (conditions == null) {
            return keywords;
        }
        Set<String> seps = Set.of("，", ",", "、", "；", ";", "|");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < conditions.length(); ) {
            String ch = String.valueOf(conditions.charAt(i));
            // 处理可能的多字符分隔符（当前均为单字符）
            if (seps.contains(ch)) {
                addKeyword(keywords, current);
                current.setLength(0);
                i += 1;
            } else {
                current.append(conditions.charAt(i));
                i += 1;
            }
        }
        addKeyword(keywords, current);
        return keywords;
    }

    private static void addKeyword(List<String> keywords, StringBuilder current) {
        String kw = current.toString().strip();
        if (!kw.isEmpty()) {
            keywords.add(kw);
        }
    }

    /** 第一个「空行」（连续两个换行）的起始下标；找不到返回 -1。 */
    private static int indexOfBlankLine(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                return i + 1;
            }
        }
        return -1;
    }
}
