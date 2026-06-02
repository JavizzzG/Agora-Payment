package com.agora.payment.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Product;
import com.stripe.model.ProductCollection;
import com.stripe.param.ProductListParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

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
}
