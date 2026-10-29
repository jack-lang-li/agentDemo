package com.example.agent.business.integration.dify;

public class DifyCallException extends RuntimeException {

    public DifyCallException(String message) {
        super(message);
    }

    public DifyCallException(String message, Throwable cause) {
        super(message, cause);
    }
}