package com.campusclaw.domain;

/** 切片索引状态：pending → ready / failed。failed 切片不参与任何检索。 */
public enum ChunkIndexStatus {
    PENDING,
    READY,
    FAILED
}
