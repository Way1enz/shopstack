package com.ecommerce.order.dto;

import com.ecommerce.order.payment.PaymentMethod;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        String shippingAddress,

        @NotNull(message = "is required")
        PaymentMethod paymentMethod,

        // Required only when paymentMethod = CREDIT_CARD - validated in PaymentService
        // rather than declaratively here, since "required if X" isn't something Bean
        // Validation expresses cleanly on its own.
        String cardNumber,
        String cardHolderName,
        String expiryMonth,
        String expiryYear,

        // Required only when paymentMethod = PAYPAL
        String paypalEmail
) {
}
