package com.campusclaw.dto;

import com.campusclaw.domain.Material;
import java.time.Instant;

public record MaterialResponse(
        Long id,
        String title,
        String description,
        String fileName,
        String contentType,
        long sizeBytes,
        Instant createdAt,
        String uploadedByName
) {
    public static MaterialResponse from(Material material) {
        return new MaterialResponse(
                material.getId(),
                material.getTitle(),
                material.getDescription(),
                material.getOriginalFileName(),
                material.getContentType(),
                material.getSizeBytes(),
                material.getCreatedAt(),
                material.getUploadedBy().getDisplayName()
        );
    }
}
