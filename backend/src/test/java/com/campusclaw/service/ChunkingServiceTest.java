package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.domain.ChunkStrategy;
import com.campusclaw.service.ChunkingService.Chunk;
import com.campusclaw.service.ChunkingService.CustomParams;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 切分器纯单元测试：边界、重叠、标题保留、偏移一致性、确定性。
 */
class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();

    private void assertOffsetsConsistent(String text, List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            assertThat(text.substring(chunk.start(), chunk.end()))
                    .isEqualTo(chunk.text());
        }
    }

    @Test
    void autoShortTextYieldsSingleChunk() {
        String text = "分数加减法是本单元的重点。";
        List<Chunk> chunks = service.chunk(text, ChunkStrategy.AUTO, null);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(text);
        assertOffsetsConsistent(text, chunks);
    }

    @Test
    void autoSplitsLongTextWithOverlapAndStableOffsets() {
        // 构造超过 800 字、带句号断点的正文
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("第").append(i).append("节课堂内容。");
        }
        String text = sb.toString(); // 约 540 字 + 更多
        while (text.length() < 1700) {
            text += "补充知识点讲解。";
        }

        List<Chunk> chunks = service.chunk(text, ChunkStrategy.AUTO, null);

        assertThat(chunks.size()).isGreaterThan(1);
        assertOffsetsConsistent(text, chunks);
        // 索引连续、顺序稳定
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
        // 每片不超过最大长度（最后一片除外）
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).text().length()).isLessThanOrEqualTo(ChunkingService.AUTO_MAX_CHARS);
        }
        // 有重叠：下一片的起点小于上一片终点
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).start()).isLessThan(chunks.get(i - 1).end());
        }
    }

    @Test
    void autoBreaksAtSentenceWhenNoNewline() {
        // 一行超长文本无换行，只能按句号断
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 1200) {
            sb.append("这是一句完整的教学陈述句。");
        }
        String text = sb.toString();

        List<Chunk> chunks = service.chunk(text, ChunkStrategy.AUTO, null);

        assertThat(chunks.size()).isGreaterThan(1);
        assertOffsetsConsistent(text, chunks);
        // 首片应断在句号处（含句号）
        assertThat(chunks.get(0).text()).endsWith("。");
    }

    @Test
    void autoHardCutsWhenNoBreakAtAll() {
        String text = "长".repeat(2000); // 无任何断点，硬切

        List<Chunk> chunks = service.chunk(text, ChunkStrategy.AUTO, null);

        assertThat(chunks.size()).isGreaterThan(1);
        assertOffsetsConsistent(text, chunks);
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).text().length()).isEqualTo(ChunkingService.AUTO_MAX_CHARS);
        }
    }

    @Test
    void chunkingIsDeterministic() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("第").append(i).append("段教学内容。");
        }
        String text = sb.toString();

        List<Chunk> first = service.chunk(text, ChunkStrategy.AUTO, null);
        List<Chunk> second = service.chunk(text, ChunkStrategy.AUTO, null);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void customHonorsMaxCharsAndOverlapPercent() {
        String text = "教学内容。".repeat(200);

        List<Chunk> chunks = service.chunk(text, ChunkStrategy.CUSTOM, new CustomParams(200, 50));

        assertThat(chunks.size()).isGreaterThan(1);
        assertOffsetsConsistent(text, chunks);
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).text().length()).isLessThanOrEqualTo(200);
            // 50% 重叠：下一片起点 = 上一片终点 - 100
            assertThat(chunks.get(i + 1).start()).isEqualTo(chunks.get(i).end() - 100);
        }
    }

    @Test
    void customRejectsOutOfRangeParams() {
        String text = "内容";

        assertThatThrownBy(() -> service.chunk(text, ChunkStrategy.CUSTOM, new CustomParams(99, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最大长度");
        assertThatThrownBy(() -> service.chunk(text, ChunkStrategy.CUSTOM, new CustomParams(2001, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最大长度");
        assertThatThrownBy(() -> service.chunk(text, ChunkStrategy.CUSTOM, new CustomParams(200, 51)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重叠");
        assertThatThrownBy(() -> service.chunk(text, ChunkStrategy.CUSTOM, new CustomParams(200, -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重叠");
    }

    @Test
    void hierarchyKeepsHeadingsInChunks() {
        String text = """
                # 单元一 分数加减法

                这一章讲解分数的加法与减法。分数加减需要先通分。

                ## 第一节 同分母分数

                同分母分数相加，分母不变，分子相加。

                ### 练习一

                计算下列各题。三分之一加三分之一。

                # 单元二 小数乘法

                小数乘法先按整数乘，再点小数点。
                """;

        List<Chunk> chunks = service.chunk(text, ChunkStrategy.HIERARCHY, null);

        assertThat(chunks).isNotEmpty();
        assertOffsetsConsistent(text, chunks);
        // 每章标题保留在所属切片
        assertThat(chunks.get(0).text()).contains("# 单元一 分数加减法");
        String joined = String.join("", chunks.stream().map(Chunk::text).toList());
        assertThat(joined).contains("# 单元二 小数乘法");
    }

    @Test
    void hierarchySplitsLongChapterWithAutoWindow() {
        StringBuilder chapter = new StringBuilder("# 超长章节\n");
        while (chapter.length() < 2000) {
            chapter.append("本节讲解详细知识点与例题。");
        }
        String text = chapter.toString();

        List<Chunk> chunks = service.chunk(text, ChunkStrategy.HIERARCHY, null);

        assertThat(chunks.size()).isGreaterThan(1);
        assertOffsetsConsistent(text, chunks);
        // 标题仍在第一个切片
        assertThat(chunks.get(0).text()).contains("# 超长章节");
    }

    @Test
    void blankTextYieldsNoChunks() {
        assertThat(service.chunk("", ChunkStrategy.AUTO, null)).isEmpty();
        assertThat(service.chunk("   \n  ", ChunkStrategy.AUTO, null)).isEmpty();
        assertThat(service.chunk(null, ChunkStrategy.AUTO, null)).isEmpty();
    }
}
