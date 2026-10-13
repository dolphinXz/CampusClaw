package com.campusclaw.service;

import com.campusclaw.domain.ChunkStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 切分器：纯函数，同正文 + 同策略参数 → 同一切片序列（可重复验证）。
 * 切片偏移是预处理后文本中的字符区间，chunkText 恒等于 text.substring(start, end)。
 */
@Component
public class ChunkingService {

    public static final int AUTO_MAX_CHARS = 800;
    public static final int AUTO_OVERLAP = 80;
    public static final int CUSTOM_MIN_CHARS = 100;
    public static final int CUSTOM_MAX_CHARS = 2000;
    public static final int CUSTOM_MAX_OVERLAP_PERCENT = 50;

    /** 一个切片：正文文本与其在切分文本中的字符区间。 */
    public record Chunk(int index, String text, int start, int end) {
    }

    /** custom 策略参数；overlapPercent 取 0～50。 */
    public record CustomParams(int maxChars, int overlapPercent) {
    }

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?.;；]");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,3}[^#\\n]*$");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\n");

    /**
     * 按策略切分预处理后的文本。
     *
     * @param text 已预处理的正文
     * @param strategy 策略
     * @param customParams 仅 custom 策略使用，null 时按默认参数
     */
    public List<Chunk> chunk(String text, ChunkStrategy strategy, CustomParams customParams) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return switch (strategy) {
            case AUTO -> windowed(text, 0, text.length(), AUTO_MAX_CHARS, AUTO_OVERLAP);
            case CUSTOM -> {
                CustomParams params = customParams == null
                        ? new CustomParams(AUTO_MAX_CHARS, 0)
                        : customParams;
                validate(params);
                int maxChars = params.maxChars();
                int overlap = maxChars * params.overlapPercent() / 100;
                yield windowed(text, 0, text.length(), maxChars, overlap);
            }
            case HIERARCHY -> hierarchy(text);
        };
    }

    private void validate(CustomParams params) {
        if (params.maxChars() < CUSTOM_MIN_CHARS || params.maxChars() > CUSTOM_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "custom 策略最大长度必须在 " + CUSTOM_MIN_CHARS + "～" + CUSTOM_MAX_CHARS + " 字之间");
        }
        if (params.overlapPercent() < 0 || params.overlapPercent() > CUSTOM_MAX_OVERLAP_PERCENT) {
            throw new IllegalArgumentException(
                    "custom 策略重叠必须在 0%～" + CUSTOM_MAX_OVERLAP_PERCENT + "% 之间");
        }
    }

    /**
     * 滑动窗口切分：断点优先级 空行 > 换行 > 句末标点；窗口内无断点时硬切。
     */
    private List<Chunk> windowed(String text, int from, int to, int maxChars, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        int start = from;
        while (start < to) {
            int windowEnd = Math.min(start + maxChars, to);
            int end = windowEnd < to ? findBreak(text, start, windowEnd) : to;
            chunks.add(new Chunk(chunks.size(), text.substring(start, end), start, end));
            if (end >= to) {
                break;
            }
            int next = Math.max(end - overlap, start + 1);
            start = next;
        }
        return chunks;
    }

    /** 在 (start, windowEnd] 内找最优断点（断点后字符归下一片）；找不到返回 windowEnd（硬切）。 */
    private int findBreak(String text, int start, int windowEnd) {
        int blank = lastIndexOf(text, start, windowEnd, BLANK_LINE);
        if (blank >= 0) {
            return blank;
        }
        int newline = text.lastIndexOf('\n', windowEnd - 1);
        if (newline > start) {
            return newline + 1;
        }
        Matcher matcher = SENTENCE_END.matcher(text).region(start + 1, windowEnd);
        int sentence = -1;
        while (matcher.find()) {
            sentence = matcher.end();
        }
        return sentence > start ? sentence : windowEnd;
    }

    private int lastIndexOf(String text, int start, int windowEnd, Pattern pattern) {
        Matcher matcher = pattern.matcher(text).region(start + 1, windowEnd);
        int found = -1;
        while (matcher.find()) {
            found = matcher.end();
        }
        return found > start ? found : -1;
    }

    /**
     * hierarchy：按 Markdown #/##/### 分章，标题行并入所属切片；
     * 单章超过 auto 窗口长度时章内再按自动窗口切分。
     */
    private List<Chunk> hierarchy(String text) {
        List<Chunk> chunks = new ArrayList<>();
        List<int[]> sections = new ArrayList<>(); // [start, end)
        Matcher heading = MARKDOWN_HEADING.matcher(text);
        int sectionStart = 0;
        while (heading.find()) {
            if (heading.start() > sectionStart) {
                sections.add(new int[]{sectionStart, heading.start()});
            }
            sectionStart = heading.start();
        }
        sections.add(new int[]{sectionStart, text.length()});

        for (int[] section : sections) {
            int from = section[0];
            int to = section[1];
            String sectionText = text.substring(from, to);
            if (sectionText.isBlank()) {
                continue;
            }
            if (sectionText.length() <= AUTO_MAX_CHARS) {
                chunks.add(new Chunk(chunks.size(), sectionText, from, to));
            } else {
                for (Chunk part : windowed(text, from, to, AUTO_MAX_CHARS, AUTO_OVERLAP)) {
                    chunks.add(new Chunk(chunks.size(), part.text(), part.start(), part.end()));
                }
            }
        }
        return chunks;
    }
}
