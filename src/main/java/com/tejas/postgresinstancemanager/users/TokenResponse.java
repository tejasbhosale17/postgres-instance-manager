package com.tejas.postgresinstancemanager.users;

public record TokenResponse(String tokenType, String accessToken, long expiresIn) {
}
