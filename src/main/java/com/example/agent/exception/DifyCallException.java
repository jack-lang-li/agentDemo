package com.example.agent.exception;

public class DifyCallException extends RuntimeException {

    public DifyCallException(String message) {
        super(message);
    }

    public DifyCallException(String message, Throwable cause) {
        super(message, cause);
    }
}