package com.agora.payment.controller;

import com.agora.payment.service.ProductService;
import com.stripe.exception.StripeException;
import com.stripe.model.Product;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

}
