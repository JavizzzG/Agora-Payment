package com.agora.payment.service;

import com.agora.payment.config.StripeConfig;
import com.agora.payment.dto.SubscriptionResponse;
import com.agora.payment.entity.Subscription;
import com.agora.payment.repository.SubscriptionRepository;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
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

    @Getter
    private final String webhookSecret;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionService(StripeConfig stripeConfig, SubscriptionRepository subscriptionRepository) {
        this.webhookSecret = stripeConfig.getWebhookSecret();
        this.subscriptionRepository = subscriptionRepository;
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

        Subscription subscription = new Subscription();
        subscription.setUserId(UUID.fromString(userIdStr));
        subscription.setAmount(session.getAmountTotal());
        subscription.setCurrency(session.getCurrency());
        subscription.setStripeCustomerId(session.getCustomer());
        subscription.setStripeSubscriptionId(session.getSubscription());
        subscription.setStatus(Subscription.Status.INCOMPLETE);

        subscriptionRepository.save(subscription);

        log.info("Checkout completed: subscriptionId={} amount={} {} user={}",
                subscription.getId(), subscription.getAmount(), subscription.getCurrency(), userIdStr);
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

    private void handleInvoicePaid(Event event) {
        com.stripe.model.Invoice invoice =
                (com.stripe.model.Invoice) deserializeStripeObject(event);
        if (invoice == null) return;

        // Invoice doesn't expose subscription directly in SDK v32, find by customer
        findSubscriptionByCustomer(invoice.getCustomer()).ifPresent(subscription -> {
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

            log.info("Invoice paid: subscriptionId={} amount={} {}",
                    subscription.getId(), invoice.getAmountPaid(), invoice.getCurrency());
        });
    }

    private void handleInvoicePaymentFailed(Event event) {
        com.stripe.model.Invoice invoice =
                (com.stripe.model.Invoice) deserializeStripeObject(event);
        if (invoice == null) return;

        findSubscriptionByCustomer(invoice.getCustomer()).ifPresent(subscription -> {
            subscription.setStatus(Subscription.Status.PAST_DUE);
            subscriptionRepository.save(subscription);

            log.info("Invoice payment failed: subscriptionId={}", subscription.getId());
        });
    }

    private Optional<Subscription> findSubscriptionByCustomer(String stripeCustomerId) {
        if (stripeCustomerId == null) return Optional.empty();
        return subscriptionRepository.findByStripeCustomerId(stripeCustomerId);
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

    private StripeObject deserializeStripeObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()) {
            return deserializer.getObject().get();
        }
        log.warn("Failed to deserialize event data for event: {}", event.getId());
        return null;
    }
}
