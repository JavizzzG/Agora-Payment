package com.agora.payment.service;

import com.agora.payment.config.StripeConfig;
import com.agora.payment.dto.SubscriptionResponse;
import com.agora.payment.entity.InvoiceRecord;
import com.agora.payment.entity.Subscription;
import com.agora.payment.repository.InvoiceRecordRepository;
import com.agora.payment.repository.SubscriptionRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private static final List<Subscription.Status> ACTIVE_SUBSCRIPTION_STATUSES = List.of(
            Subscription.Status.INCOMPLETE,
            Subscription.Status.TRIALING,
            Subscription.Status.ACTIVE,
            Subscription.Status.PAST_DUE
    );

    @Getter
    private final String webhookSecret;
    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceRecordRepository invoiceRecordRepository;

    public SubscriptionService(StripeConfig stripeConfig,
                               SubscriptionRepository subscriptionRepository,
                               InvoiceRecordRepository invoiceRecordRepository) {
        this.webhookSecret = stripeConfig.getWebhookSecret();
        this.subscriptionRepository = subscriptionRepository;
        this.invoiceRecordRepository = invoiceRecordRepository;
    }

    public void processEvent(Event event) {
        log.info("Processing event: type={} id={}", event.getType(), event.getId());

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "customer.subscription.created" -> handleSubscriptionCreated(event);
            case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
            case "invoice.paid" -> handleInvoicePaid(event);
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(event);
            case "invoice.finalized" -> handleInvoiceFinalized(event);
            case "invoice.created", "payment_intent.created", "payment_intent.succeeded" ->
                    log.debug("Ignoring event type: {}", event.getType());
            default -> log.info("Unhandled event type: {}", event.getType());
        }
    }

    public List<SubscriptionResponse> getSubscriptionsByUserId(UUID userId) {
        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(SubscriptionResponse::new)
                .toList();
    }

    private void handleCheckoutCompleted(Event event) {
        Session session = (Session) deserializeStripeObject(event);
        if (session == null) return;

        String userIdStr = session.getMetadata().get("user_id");
        if (userIdStr == null) {
            log.warn("Checkout session missing user_id metadata");
            return;
        }

        UUID userId = UUID.fromString(userIdStr);
        String stripeSubscriptionId = session.getSubscription();

        Optional<Subscription> existing = stripeSubscriptionId != null
                ? subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId)
                : Optional.empty();

        Subscription subscription;
        if (existing.isPresent()) {
            subscription = existing.get();
        } else {
            List<Subscription> activeSubscriptions = subscriptionRepository
                    .findByUserIdAndStatusIn(userId, ACTIVE_SUBSCRIPTION_STATUSES);

            if (!activeSubscriptions.isEmpty()) {
                subscription = activeSubscriptions.getFirst();
                log.info("User {} already has active subscription (id={}). Reusing it with new Stripe subscription.",
                        userId, subscription.getId());
            } else {
                subscription = new Subscription();
                subscription.setUserId(userId);
            }
        }

        subscription.setAmount(session.getAmountTotal());
        subscription.setCurrency(session.getCurrency());
        subscription.setStripeCustomerId(session.getCustomer());
        subscription.setStripeSubscriptionId(stripeSubscriptionId);
        subscription.setStatus(Subscription.Status.INCOMPLETE);

        subscriptionRepository.save(subscription);

        log.info("Checkout completed: subscriptionId={} stripeSubId={} amount={} {} user={}",
                subscription.getId(), stripeSubscriptionId,
                subscription.getAmount(), subscription.getCurrency(), userIdStr);
    }

    private void handleSubscriptionCreated(Event event) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) deserializeStripeObject(event);
        if (stripeSub == null) return;

        Optional<Subscription> existing = subscriptionRepository
                .findByStripeSubscriptionId(stripeSub.getId());

        Subscription subscription = existing.orElseGet(() -> {
            Subscription newSub = new Subscription();
            String userIdStr = stripeSub.getMetadata().get("user_id");
            if (userIdStr != null) {
                newSub.setUserId(UUID.fromString(userIdStr));
            }
            return newSub;
        });

        updateFromStripeSubscription(subscription, stripeSub);
        subscriptionRepository.save(subscription);

        log.info("Subscription created: id={} stripeId={} status={}",
                subscription.getId(), stripeSub.getId(), stripeSub.getStatus());
    }

    private void handleSubscriptionUpdated(Event event) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) deserializeStripeObject(event);
        if (stripeSub == null) return;

        subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId())
                .ifPresent(subscription -> {
                    updateFromStripeSubscription(subscription, stripeSub);
                    subscriptionRepository.save(subscription);

                    log.info("Subscription updated: id={} status={}",
                            subscription.getId(), stripeSub.getStatus());
                });
    }

    private void handleSubscriptionDeleted(Event event) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) deserializeStripeObject(event);
        if (stripeSub == null) return;

        subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId())
                .ifPresent(subscription -> {
                    subscription.setStatus(Subscription.Status.CANCELED);
                    subscription.setCanceledAt(Instant.now());
                    subscriptionRepository.save(subscription);

                    log.info("Subscription deleted: id={}", subscription.getId());
                });
    }

    private void handleInvoiceFinalized(Event event) {
        Invoice invoice = (Invoice) deserializeStripeObject(event);
        if (invoice == null) return;

        Subscription subscription = findSubscriptionByInvoice(invoice).orElseGet(() -> {
            try {
                Subscription newSub = createSubscriptionFromInvoice(invoice);
                newSub.setStatus(Subscription.Status.INCOMPLETE);
                return newSub;
            } catch (Exception e) {
                log.error("Failed to create subscription from finalized invoice: customer={} subscription={}",
                        invoice.getCustomer(), getInvoiceSubscriptionId(invoice), e);
                return null;
            }
        });

        if (subscription == null) {
            log.warn("No subscription for finalized invoice: customer={} subscription={}",
                    invoice.getCustomer(), getInvoiceSubscriptionId(invoice));
            return;
        }

        if (invoice.getPeriodStart() != null) {
            subscription.setCurrentPeriodStart(Instant.ofEpochSecond(invoice.getPeriodStart()));
        }
        if (invoice.getPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(Instant.ofEpochSecond(invoice.getPeriodEnd()));
        }
        subscriptionRepository.save(subscription);

        log.info("Invoice finalized: subscriptionId={} amount={} {}",
                subscription.getId(), invoice.getAmountDue(), invoice.getCurrency());
    }

    private void handleInvoicePaid(Event event) {
        Invoice invoice = (Invoice) deserializeStripeObject(event);
        if (invoice == null) return;

        Subscription subscription = findSubscriptionByInvoice(invoice).orElseGet(() -> {
            try {
                return createSubscriptionFromInvoice(invoice);
            } catch (Exception e) {
                log.error("Failed to create subscription from invoice: customer={} subscription={}",
                        invoice.getCustomer(), getInvoiceSubscriptionId(invoice), e);
                return null;
            }
        });

        if (subscription == null) {
            log.warn("No subscription found for invoice: customer={} subscription={}",
                    invoice.getCustomer(), getInvoiceSubscriptionId(invoice));
            return;
        }

        upsertInvoiceRecord(invoice, "paid");

        subscription.setStatus(Subscription.Status.ACTIVE);
        if (invoice.getPeriodStart() != null) {
            subscription.setCurrentPeriodStart(Instant.ofEpochSecond(invoice.getPeriodStart()));
        }
        if (invoice.getPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(Instant.ofEpochSecond(invoice.getPeriodEnd()));
        }
        if (subscription.getAmount() == null) {
            subscription.setAmount(invoice.getAmountPaid());
        }
        if (subscription.getCurrency() == null) {
            subscription.setCurrency(invoice.getCurrency());
        }
        subscriptionRepository.save(subscription);

        log.info("Invoice paid: subscriptionId={} invoiceId={} amount={} {}",
                subscription.getId(), invoice.getId(), invoice.getAmountPaid(), invoice.getCurrency());
    }

    private void handleInvoicePaymentFailed(Event event) {
        Invoice invoice = (Invoice) deserializeStripeObject(event);
        if (invoice == null) return;

        upsertInvoiceRecord(invoice, "payment_failed");

        findSubscriptionByInvoice(invoice).ifPresent(subscription -> {
            subscription.setStatus(Subscription.Status.PAST_DUE);
            subscriptionRepository.save(subscription);

            log.info("Invoice payment failed: subscriptionId={} invoiceId={}",
                    subscription.getId(), invoice.getId());
        });
    }

    private Optional<Subscription> findSubscriptionByCustomer(String stripeCustomerId) {
        if (stripeCustomerId == null) return Optional.empty();
        return subscriptionRepository.findByStripeCustomerId(stripeCustomerId);
    }

    private String getInvoiceSubscriptionId(Invoice invoice) {
        if (invoice.getParent() != null
                && invoice.getParent().getSubscriptionDetails() != null) {
            return invoice.getParent().getSubscriptionDetails().getSubscription();
        }
        return null;
    }

    private Optional<Subscription> findSubscriptionByInvoice(Invoice invoice) {
        String subscriptionId = getInvoiceSubscriptionId(invoice);
        if (subscriptionId != null) {
            Optional<Subscription> bySub = subscriptionRepository.findByStripeSubscriptionId(subscriptionId);
            if (bySub.isPresent()) return bySub;
        }
        if (invoice.getCustomer() != null) {
            return subscriptionRepository.findByStripeCustomerId(invoice.getCustomer());
        }
        return Optional.empty();
    }

    private Subscription createSubscriptionFromInvoice(Invoice invoice) throws StripeException {
        Subscription subscription = new Subscription();
        subscription.setStripeSubscriptionId(getInvoiceSubscriptionId(invoice));
        subscription.setStripeCustomerId(invoice.getCustomer());
        subscription.setStatus(Subscription.Status.ACTIVE);
        subscription.setAmount(invoice.getAmountPaid());
        subscription.setCurrency(invoice.getCurrency());
        if (invoice.getPeriodStart() != null) {
            subscription.setCurrentPeriodStart(Instant.ofEpochSecond(invoice.getPeriodStart()));
        }
        if (invoice.getPeriodEnd() != null) {
            subscription.setCurrentPeriodEnd(Instant.ofEpochSecond(invoice.getPeriodEnd()));
        }

        if (invoice.getLines() != null && !invoice.getLines().getData().isEmpty()) {
            var lineItem = invoice.getLines().getData().getFirst();
            if (lineItem.getPricing() != null
                    && lineItem.getPricing().getPriceDetails() != null) {
                subscription.setPriceId(lineItem.getPricing().getPriceDetails().getPrice());
                subscription.setProductId(lineItem.getPricing().getPriceDetails().getProduct());
            }
        }

        String subscriptionId = getInvoiceSubscriptionId(invoice);
        if (subscriptionId != null) {
            com.stripe.model.Subscription stripeSub =
                    com.stripe.model.Subscription.retrieve(subscriptionId);
            String userIdStr = stripeSub.getMetadata().get("user_id");
            if (userIdStr != null) {
                subscription.setUserId(UUID.fromString(userIdStr));
            }
        }

        if (subscription.getUserId() == null) {
            log.warn("Could not determine user_id from invoice subscription metadata: {}",
                    getInvoiceSubscriptionId(invoice));
        }

        log.info("Created subscription from invoice: subId={} customer={} user={}",
                subscription.getStripeSubscriptionId(), invoice.getCustomer(), subscription.getUserId());
        return subscription;
    }

    private void updateFromStripeSubscription(Subscription subscription,
                                               com.stripe.model.Subscription stripeSub) {
        subscription.setStripeSubscriptionId(stripeSub.getId());
        subscription.setStripeCustomerId(stripeSub.getCustomer());

        try {
            subscription.setStatus(Subscription.Status.valueOf(
                    stripeSub.getStatus().toUpperCase()));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown subscription status from Stripe: {}", stripeSub.getStatus());
        }

        if (stripeSub.getStartDate() != null) {
            subscription.setCurrentPeriodStart(Instant.ofEpochSecond(stripeSub.getStartDate()));
        }
        subscription.setCancelAtPeriodEnd(stripeSub.getCancelAtPeriodEnd());
        if (stripeSub.getCanceledAt() != null) {
            subscription.setCanceledAt(Instant.ofEpochSecond(stripeSub.getCanceledAt()));
        }
        if (stripeSub.getTrialStart() != null) {
            subscription.setTrialStart(Instant.ofEpochSecond(stripeSub.getTrialStart()));
        }
        if (stripeSub.getTrialEnd() != null) {
            subscription.setTrialEnd(Instant.ofEpochSecond(stripeSub.getTrialEnd()));
        }

        if (stripeSub.getItems() != null && !stripeSub.getItems().getData().isEmpty()) {
            var item = stripeSub.getItems().getData().getFirst();
            subscription.setPriceId(item.getPrice().getId());
            subscription.setProductId(item.getPrice().getProduct());
        }

        String userIdStr = stripeSub.getMetadata().get("user_id");
        if (userIdStr != null && subscription.getUserId() == null) {
            subscription.setUserId(UUID.fromString(userIdStr));
        }
    }

    private void upsertInvoiceRecord(Invoice invoice, String paymentStatus) {
        String stripeInvoiceId = invoice.getId();
        InvoiceRecord record = invoiceRecordRepository.findByStripeInvoiceId(stripeInvoiceId)
                .orElseGet(InvoiceRecord::new);

        record.setStripeInvoiceId(stripeInvoiceId);
        record.setStripeSubscriptionId(getInvoiceSubscriptionId(invoice));
        record.setStripeCustomerId(invoice.getCustomer());
        record.setAmountPaid(invoice.getAmountPaid());
        record.setAmountDue(invoice.getAmountDue());
        record.setCurrency(invoice.getCurrency());
        record.setStatus(paymentStatus);
        record.setBillingReason(invoice.getBillingReason());
        if (invoice.getStatusTransitions() != null && invoice.getStatusTransitions().getPaidAt() != null) {
            record.setPaidAt(Instant.ofEpochSecond(invoice.getStatusTransitions().getPaidAt()));
        }
        if (invoice.getPeriodStart() != null) {
            record.setPeriodStart(Instant.ofEpochSecond(invoice.getPeriodStart()));
        }
        if (invoice.getPeriodEnd() != null) {
            record.setPeriodEnd(Instant.ofEpochSecond(invoice.getPeriodEnd()));
        }
        record.setInvoicePdf(invoice.getInvoicePdf());
        record.setHostedInvoiceUrl(invoice.getHostedInvoiceUrl());
        record.setNumber(invoice.getNumber());

        invoiceRecordRepository.save(record);

        log.info("Invoice record saved: invoiceId={} status={} amount={} {}",
                stripeInvoiceId, paymentStatus, invoice.getAmountPaid(), invoice.getCurrency());
    }

    private StripeObject deserializeStripeObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            return deserializer.getObject().get();
        }
        log.warn("Failed to deserialize event data for event: {}", event.getId());
        return null;
    }
}
