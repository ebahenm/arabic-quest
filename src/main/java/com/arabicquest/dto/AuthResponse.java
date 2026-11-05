package com.arabicquest.dto;

public class AuthResponse {
    private Long id;
    private String username;
    private String fullName;
    private String role;
    private boolean defaultPasswordWarning;

    public AuthResponse() {
    }

    public AuthResponse(Long id, String username, String fullName, String role) {
        this(id, username, fullName, role, false);
    }

    public AuthResponse(Long id, String username, String fullName, String role, boolean defaultPasswordWarning) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.defaultPasswordWarning = defaultPasswordWarning;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public boolean isDefaultPasswordWarning() {
        return defaultPasswordWarning;
    }

    public void setDefaultPasswordWarning(boolean defaultPasswordWarning) {
        this.defaultPasswordWarning = defaultPasswordWarning;
    }
}
