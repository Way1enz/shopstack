package com.ecommerce.order.payment;

import com.ecommerce.order.dto.CheckoutRequest;
import com.ecommerce.order.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;

// No real payment gateway. Validates the details and simulates authorization. Malformed
// input (bad card, failed Luhn check, expired) is a 400; a valid card that's declined is a 402.
@Service
public class PaymentService {

    @Value("${payment.decline-above-amount:1000.00}")
    private BigDecimal declineAboveAmount;

    /** Returns a short human-readable summary to store on the order, e.g. "Credit card ending 1111". */
    public String validateAndProcess(PaymentMethod method, BigDecimal amount, CheckoutRequest request) {
        return switch (method) {
            case CREDIT_CARD -> processCreditCard(request, amount);
            case PAYPAL -> processPayPal(request);
            case CASH -> "Cash";
        };
    }

    private String processCreditCard(CheckoutRequest request, BigDecimal amount) {
        if (request.cardHolderName() == null || request.cardHolderName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cardHolderName is required for CREDIT_CARD payments");
        }

        String digitsOnly = request.cardNumber() == null ? "" : request.cardNumber().replaceAll("\\s+", "");
        if (!digitsOnly.matches("\\d+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cardNumber must contain digits only");
        }
        if (digitsOnly.length() < 13 || digitsOnly.length() > 19) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cardNumber must be 13-19 digits");
        }
        if (!luhnCheck(digitsOnly)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid card number");
        }

        validateExpiry(request.expiryMonth(), request.expiryYear());

        // Deterministic decline above a configurable threshold (default $1000), so the
        // decline path is testable.
        if (amount.compareTo(declineAboveAmount) > 0) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED,
                    "Card declined: amount $" + amount + " exceeds the simulated authorization limit of $" + declineAboveAmount);
        }

        String last4 = digitsOnly.substring(digitsOnly.length() - 4);
        return "Credit card ending " + last4;
    }

    private void validateExpiry(String expiryMonth, String expiryYear) {
        if (expiryMonth == null || expiryYear == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expiryMonth and expiryYear are required for CREDIT_CARD payments");
        }

        int month;
        int year;
        try {
            month = Integer.parseInt(expiryMonth);
            year = Integer.parseInt(expiryYear);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expiryMonth and expiryYear must be numeric");
        }

        if (month < 1 || month > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expiryMonth must be between 1 and 12");
        }

        int fullYear = year < 100 ? 2000 + year : year;
        YearMonth expiry = YearMonth.of(fullYear, month);
        if (expiry.isBefore(YearMonth.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Card has expired");
        }
    }

    private String processPayPal(CheckoutRequest request) {
        String email = request.paypalEmail();
        if (email == null || !email.contains("@")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A valid paypalEmail is required for PAYPAL payments");
        }
        return "PayPal (" + email + ")";
    }

    // Luhn algorithm: doubles every second digit from the right (subtracting 9 if
    // that exceeds 9); a valid number's digits sum to a multiple of 10.
    private boolean luhnCheck(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
