package com.campusclaw.service;

import com.campusclaw.domain.Material;
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

    public MaterialService(
            MaterialRepository materialRepository,
            UserRepository userRepository,
            MaterialStorageService storageService,
            MaterialParserService parserService
    ) {
        this.materialRepository = materialRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.parserService = parserService;
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

        return MaterialResponse.from(materialRepository.save(material));
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
