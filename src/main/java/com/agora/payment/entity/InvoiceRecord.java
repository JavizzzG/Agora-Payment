package com.agora.payment.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoices", indexes = {
        @Index(name = "idx_invoices_stripe_invoice_id", columnList = "stripeInvoiceId", unique = true),
        @Index(name = "idx_invoices_stripe_subscription_id", columnList = "stripeSubscriptionId"),
        @Index(name = "idx_invoices_stripe_customer_id", columnList = "stripeCustomerId")
})
@Getter
@Setter
@NoArgsConstructor
public class InvoiceRecord {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String stripeInvoiceId;

    private String stripeSubscriptionId;

    private String stripeCustomerId;

    private Long amountPaid;

    private Long amountDue;

    @Column(length = 3)
    private String currency;

    @Column(length = 30)
    private String status;

    @Column(length = 50)
    private String billingReason;

    private Instant paidAt;

    private Instant periodStart;

    private Instant periodEnd;

    private String invoicePdf;

    private String hostedInvoiceUrl;

    @Column(length = 50)
    private String number;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
