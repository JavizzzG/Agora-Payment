package com.agora.payment.service;

import com.agora.payment.exception.product.ProductNotFoundException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Product;
import com.stripe.model.ProductCollection;
import com.stripe.param.ProductListParams;
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
            throw new ProductNotFoundException("Product not found with id: " + productId);
        }

        log.info("Payment link retrieved successfully. id={} for user {}", productId, userId);

        return product.getMetadata().get("payment_link_url");
    }
}
