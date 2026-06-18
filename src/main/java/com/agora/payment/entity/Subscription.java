package com.agora.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
        @Index(name = "idx_subscriptions_user_id", columnList = "userId"),
        @Index(name = "idx_subscriptions_stripe_subscription_id", columnList = "stripeSubscriptionId", unique = true),
        @Index(name = "idx_subscriptions_stripe_customer_id", columnList = "stripeCustomerId")
})
@Getter
@Setter
@NoArgsConstructor
public class Subscription {

    public enum Status {
        INCOMPLETE,
        INCOMPLETE_EXPIRED,
        TRIALING,
        ACTIVE,
        PAST_DUE,
        CANCELED,
        UNPAID
    }

    @Id
    private UUID id;

    private String stripeSubscriptionId;

    private String stripeCustomerId;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.INCOMPLETE;

    private String productId;

    private String priceId;

    private Instant currentPeriodStart;

    private Instant currentPeriodEnd;

    private Boolean cancelAtPeriodEnd = false;

    private Instant canceledAt;

    private Instant trialStart;

    private Instant trialEnd;

    @Column(length = 3)
    private String currency;

    private Long amount;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
