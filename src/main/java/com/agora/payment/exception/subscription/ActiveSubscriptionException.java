package com.agora.payment.exception.subscription;

public class ActiveSubscriptionException extends RuntimeException {

    public ActiveSubscriptionException(String message) {
        super(message);
    }
}
