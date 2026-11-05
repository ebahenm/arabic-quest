package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;

public class GoogleAuthRequest {

    @NotBlank(message = "Google credential token is required")
    private String credential;

    public GoogleAuthRequest() {
    }

    public GoogleAuthRequest(String credential) {
        this.credential = credential;
    }

    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }
}
