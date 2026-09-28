package com.campusclaw.dto;

import com.campusclaw.security.CampusUserPrincipal;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        String role,
        Long classId,
        String className
) {
    public static UserResponse from(CampusUserPrincipal principal) {
        return new UserResponse(
                principal.getId(),
                principal.getUsername(),
                principal.getDisplayName(),
                principal.getRole().name(),
                principal.getClassId(),
                principal.getClassName()
        );
    }
}
