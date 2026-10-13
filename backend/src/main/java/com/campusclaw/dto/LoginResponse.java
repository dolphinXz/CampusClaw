package com.campusclaw.dto;

import com.campusclaw.security.CampusUserPrincipal;

public record LoginResponse(String token, UserResponse user) {

    public static LoginResponse from(String token, CampusUserPrincipal principal) {
        return new LoginResponse(token, UserResponse.from(principal));
    }
}
