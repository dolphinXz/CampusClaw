package com.campusclaw.service;

import com.campusclaw.domain.Material;
import com.campusclaw.domain.ChunkStrategy;
import com.campusclaw.domain.UserRole;
import com.campusclaw.dto.MaterialResponse;
import com.campusclaw.repository.MaterialRepository;
import com.campusclaw.repository.UserRepository;
import com.campusclaw.security.CampusUserPrincipal;
import com.campusclaw.security.SecurityUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final MaterialStorageService storageService;
    private final MaterialParserService parserService;
    private final IndexingService indexingService;

    public MaterialService(
            MaterialRepository materialRepository,
            UserRepository userRepository,
            MaterialStorageService storageService,
            MaterialParserService parserService,
            IndexingService indexingService
    ) {
        this.materialRepository = materialRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.parserService = parserService;
        this.indexingService = indexingService;
    }

    @Transactional(readOnly = true)
    public List<MaterialResponse> list(Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        return materialRepository.findByClassRoomIdOrderByIdDesc(principal.getClassId())
                .stream()
                .map(MaterialResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MaterialResponse get(Long id, Authentication authentication) {
        return MaterialResponse.from(findAuthorized(id, authentication));
    }

    @Transactional
    public MaterialResponse upload(
            Authentication authentication,
            MultipartFile file,
            String title,
            String description
    ) throws IOException {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        if (principal.getRole() != UserRole.TEACHER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学生不能上传材料");
        }
        var uploader = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        }

        byte[] bytes = file.getBytes();
        String previewText = parserService.parse(file.getContentType(), bytes);
        MaterialStorageService.StoredFile storedFile = storageService.store(
                file.getOriginalFilename(),
                file.getContentType(),
                bytes
        );

        Material material = new Material(
                uploader.getClassRoom(),
                uploader,
                title,
                description,
                storedFile.key(),
                storedFile.fileName(),
                storedFile.contentType(),
                storedFile.sizeBytes(),
                previewText
        );

        Material saved = materialRepository.save(material);
        // 索引在材料事务提交后执行（REQUIRES_NEW 独立事务需读到已提交的材料行）。
        // 切分/嵌入/Qdrant 失败只置切片 failed，不影响上传成功响应。
        Long materialId = saved.getId();
        Long classId = principal.getClassId();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            indexingService.indexMaterial(materialId, classId);
                        } catch (RuntimeException e) {
                            // 索引失败不回滚材料，切片状态由流水线如实记录
                        }
                    }
                });
        return MaterialResponse.from(saved);
    }

    /** 教师显式重建索引：先删旧切片与旧向量，再按本次策略生成新索引。 */
    public IndexingService.IndexSummary rebuild(
            Long materialId,
            Authentication authentication,
            String strategy,
            Integer maxChars,
            Integer overlapPercent
    ) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        if (principal.getRole() != UserRole.TEACHER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有教师可以管理索引");
        }
        // findAuthorized 同时校验存在性与班级归属（跨班与不存在均 404/403 延续既有规则）
        findAuthorized(materialId, authentication);

        ChunkStrategy chunkStrategy = ChunkStrategy.from(strategy);
        ChunkingService.CustomParams params = null;
        if (chunkStrategy == ChunkStrategy.CUSTOM && (maxChars != null || overlapPercent != null)) {
            params = new ChunkingService.CustomParams(
                    maxChars == null ? ChunkingService.AUTO_MAX_CHARS : maxChars,
                    overlapPercent == null ? 0 : overlapPercent
            );
        }
        try {
            indexingService.rebuild(materialId, principal.getClassId(), chunkStrategy, params);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return indexingService.summary(materialId, principal.getClassId());
    }

    @Transactional(readOnly = true)
    public StoredContent content(Long id, Authentication authentication) {
        Material material = findAuthorized(id, authentication);
        return new StoredContent(
                storageService.resolve(material.getStorageKey()),
                material.getOriginalFileName(),
                material.getContentType()
        );
    }

    private Material findAuthorized(Long id, Authentication authentication) {
        CampusUserPrincipal principal = SecurityUtils.currentPrincipal(authentication);
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "材料不存在"));

        if (!Objects.equals(material.getClassRoom().getId(), principal.getClassId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该材料");
        }

        return material;
    }

    public record StoredContent(Path path, String fileName, String contentType) {
    }
}
