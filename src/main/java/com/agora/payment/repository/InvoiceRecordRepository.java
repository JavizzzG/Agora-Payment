package com.agora.payment.repository;

import com.agora.payment.entity.InvoiceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRecordRepository extends JpaRepository<InvoiceRecord, UUID> {

    Optional<InvoiceRecord> findByStripeInvoiceId(String stripeInvoiceId);

    List<InvoiceRecord> findByStripeSubscriptionIdOrderByCreatedAtDesc(String stripeSubscriptionId);

    List<InvoiceRecord> findByStripeCustomerIdOrderByCreatedAtDesc(String stripeCustomerId);
}
