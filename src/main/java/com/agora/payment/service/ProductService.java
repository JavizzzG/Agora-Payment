package com.agora.payment.service;

import com.agora.payment.entity.Subscription;
import com.agora.payment.exception.product.ProductNotFoundException;
import com.agora.payment.exception.subscription.ActiveSubscriptionException;
import com.agora.payment.repository.SubscriptionRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.ProductCollection;
import com.stripe.model.checkout.Session;
import com.stripe.param.ProductListParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private static final List<Subscription.Status> ACTIVE_SUBSCRIPTION_STATUSES = List.of(
            Subscription.Status.INCOMPLETE,
            Subscription.Status.TRIALING,
            Subscription.Status.ACTIVE,
            Subscription.Status.PAST_DUE
    );

    private final SubscriptionRepository subscriptionRepository;

    public ProductService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    public List<Product> getProducts() throws StripeException {

        ProductListParams params = ProductListParams.builder()
                .setActive(true)
                .setLimit(100L)
                .addExpand("data.default_price")
                .build();

        ProductCollection products = Product.list(params);
        return products.getData();
    }

    public String getPaymentLink(UUID userId, String productId) throws StripeException {

        log.info("Retrieving Stripe product. id={} for user {}", productId, userId);

        Product product = Product.retrieve(productId);

        if (product == null) {
            log.warn("Stripe product not found. id={} for user {}", productId, userId);
            throw new ProductNotFoundException("Product not found with id: " + productId);
        }

        log.info("Payment link retrieved successfully. id={} for user {}", productId, userId);

        return product.getMetadata().get("payment_link_url");
    }

    public String createCheckoutSession(UUID userId, String productId, String successUrl, String cancelUrl) throws StripeException {

        log.info("Creating checkout session. productId={} for user {}", productId, userId);

        Product product = Product.retrieve(productId);

        if (product == null) {
            log.warn("Product not found. id={} for user {}", productId, userId);
            return null;
        }

        String priceId = product.getDefaultPrice();

        if (priceId == null) {
            log.warn("Product has no default price. id={} for user {}", productId, userId);
            return null;
        }

        Price price = Price.retrieve(priceId);

        SessionCreateParams.Mode mode = price.getRecurring() != null
                ? SessionCreateParams.Mode.SUBSCRIPTION
                : SessionCreateParams.Mode.PAYMENT;

        if (mode == SessionCreateParams.Mode.SUBSCRIPTION) {
            List<Subscription> activeSubscriptions = subscriptionRepository
                    .findByUserIdAndStatusIn(userId, ACTIVE_SUBSCRIPTION_STATUSES);

            if (!activeSubscriptions.isEmpty()) {
                log.warn("User {} already has an active subscription. subscriptionId={}",
                        userId, activeSubscriptions.getFirst().getId());
                throw new ActiveSubscriptionException(
                        "User already has an active subscription. Complete or cancel it before creating a new one."
                );
            }
        }

        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(mode)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .putMetadata("user_id", userId.toString())
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                );

        if (mode == SessionCreateParams.Mode.SUBSCRIPTION) {
            paramsBuilder.setSubscriptionData(
                    SessionCreateParams.SubscriptionData.builder()
                            .putMetadata("user_id", userId.toString())
                            .build()
            );
        }

        SessionCreateParams params = paramsBuilder.build();

        Session session = Session.create(params);

        log.info("Checkout session created. id={} mode={} for user {}", session.getId(), mode, userId);

        return session.getUrl();
    }
}
