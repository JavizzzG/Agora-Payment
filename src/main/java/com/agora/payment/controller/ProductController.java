package com.agora.payment.controller;

import com.agora.payment.service.ProductService;
import com.stripe.exception.StripeException;
import com.stripe.model.Product;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payment")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService){
        this.productService = productService;
    }

    @GetMapping("/products")
    public ResponseEntity<List<Product>> getProducts() throws StripeException {
        return ResponseEntity.status(HttpStatus.OK).body(productService.getProducts());
    }

    @GetMapping("/pay-product/{productId}")
    public ResponseEntity<String> getPaymentLink(@RequestHeader("X-User-Id") UUID userId, @PathVariable String productId) throws StripeException {
        return ResponseEntity.status(HttpStatus.OK).body(productService.getPaymentLink(userId, productId));
    }

}
