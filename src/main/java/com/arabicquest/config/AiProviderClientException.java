// Exception carrying the upstream HTTP status code when an AI provider rejects a request.
package com.arabicquest.config;

public class AiProviderClientException extends IllegalArgumentException {

    private final int statusCode;

    public AiProviderClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
