package com.campusclaw.web;

import com.campusclaw.dto.MaterialResponse;
import com.campusclaw.service.MaterialService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping
    public List<MaterialResponse> list(Authentication authentication) {
        return materialService.list(authentication);
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasRole('TEACHER')")
    public MaterialResponse upload(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description
    ) throws IOException {
        return materialService.upload(authentication, file, title, description);
    }

    @GetMapping("/{id}")
    public MaterialResponse get(@PathVariable Long id, Authentication authentication) {
        return materialService.get(id, authentication);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id, Authentication authentication) throws IOException {
        MaterialService.StoredContent storedContent = materialService.content(id, authentication);
        byte[] bytes = java.nio.file.Files.readAllBytes(storedContent.path());
        MediaType mediaType = storedContent.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(storedContent.contentType());

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(storedContent.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .contentLength(bytes.length)
                .body(bytes);
    }

    /** 教师显式重建索引：先删旧切片与旧向量，再按本次策略生成新索引。 */
    @PostMapping("/{id}/reindex")
    @PreAuthorize("hasRole('TEACHER')")
    public ReindexResponse reindex(
            @PathVariable Long id,
            @RequestParam(value = "strategy", required = false) String strategy,
            @RequestParam(value = "maxChars", required = false) Integer maxChars,
            @RequestParam(value = "overlapPercent", required = false) Integer overlapPercent,
            Authentication authentication
    ) {
        var summary = materialService.rebuild(id, authentication, strategy, maxChars, overlapPercent);
        return new ReindexResponse(
                summary.materialId(), summary.total(), summary.ready(), summary.failed());
    }

    public record ReindexResponse(Long materialId, int total, long ready, long failed) {
    }
}
