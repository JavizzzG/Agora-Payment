package com.agora.payment.dto;

import com.agora.payment.entity.Subscription;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class SubscriptionResponse {

    private final UUID id;
    private final String stripeSubscriptionId;
    private final String stripeCustomerId;
    private final UUID userId;
    private final String status;
    private final String productId;
    private final String priceId;
    private final Instant currentPeriodStart;
    private final Instant currentPeriodEnd;
    private final Boolean cancelAtPeriodEnd;
    private final Instant canceledAt;
    private final Instant trialStart;
    private final Instant trialEnd;
    private final String currency;
    private final Long amount;
    private final Instant createdAt;
    private final Instant updatedAt;

    public SubscriptionResponse(Subscription subscription) {
        this.id = subscription.getId();
        this.stripeSubscriptionId = subscription.getStripeSubscriptionId();
        this.stripeCustomerId = subscription.getStripeCustomerId();
        this.userId = subscription.getUserId();
        this.status = subscription.getStatus().name();
        this.productId = subscription.getProductId();
        this.priceId = subscription.getPriceId();
        this.currentPeriodStart = subscription.getCurrentPeriodStart();
        this.currentPeriodEnd = subscription.getCurrentPeriodEnd();
        this.cancelAtPeriodEnd = subscription.getCancelAtPeriodEnd();
        this.canceledAt = subscription.getCanceledAt();
        this.trialStart = subscription.getTrialStart();
        this.trialEnd = subscription.getTrialEnd();
        this.currency = subscription.getCurrency();
        this.amount = subscription.getAmount();
        this.createdAt = subscription.getCreatedAt();
        this.updatedAt = subscription.getUpdatedAt();
    }
}
