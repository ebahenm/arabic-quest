// Login response DTO including JWT token and mustChangePassword state.
package com.arabicquest.dto;

public class LoginResponse {

    private String token;
    private String username;
    private String fullName;
    private String role;
    private boolean mustChangePassword;

    public LoginResponse() {
    }

    public LoginResponse(String token, String username, String fullName, String role) {
        this(token, username, fullName, role, false);
    }

    public LoginResponse(String token, String username, String fullName, String role, boolean mustChangePassword) {
        this.token = token;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }
}
