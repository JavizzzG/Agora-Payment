package com.agora.payment.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.ProductCollection;
import com.stripe.model.checkout.Session;
import com.stripe.param.ProductListParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    @Value("${stripe.secret}")
    private String secret;

    public List<Product> getProducts() throws StripeException {
        Stripe.apiKey = secret;

        ProductListParams params = ProductListParams.builder()
                .setActive(true)
                .setLimit(100L)
                .addExpand("data.default_price")
                .build();

        ProductCollection products = Product.list(params);
        return products.getData();
    }

    public String getPaymentLink(UUID userId, String productId) throws StripeException {
        Stripe.apiKey = secret;

        log.info("Retrieving Stripe product. id={} for user {}", productId, userId);

        Product product = Product.retrieve(productId);

        if (product == null) {
            log.warn("Stripe product not found. id={} for user {}", productId, userId);
            return null;
        }

        log.info("Payment link retrieved successfully. id={} for user {}", productId, userId);

        return product.getMetadata().get("payment_link_url");
    }

    public String createCheckoutSession(UUID userId, String productId, String successUrl, String cancelUrl) throws StripeException {
        Stripe.apiKey = secret;

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

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(mode)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .putMetadata("user_id", userId.toString())
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                )
                .build();

        Session session = Session.create(params);

        log.info("Checkout session created. id={} mode={} for user {}", session.getId(), mode, userId);

        return session.getUrl();
    }
}
