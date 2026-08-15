package com.ecommerce.order.payment;

import com.ecommerce.order.dto.CheckoutRequest;
import com.ecommerce.order.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest {

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService();
        // @Value fields aren't populated outside a Spring context - set it directly,
        // matching the application.yml default (payment.decline-above-amount: 1000.00).
        ReflectionTestUtils.setField(paymentService, "declineAboveAmount", new BigDecimal("1000.00"));
    }

    private CheckoutRequest creditCard(String cardNumber, String holder, String month, String year) {
        return new CheckoutRequest(null, PaymentMethod.CREDIT_CARD, cardNumber, holder, month, year, null);
    }

    @Test
    void validCreditCard_underThreshold_returnsSummaryWithLast4() {
        CheckoutRequest request = creditCard("4111111111111111", "Alice Smith", "12", "30");

        String summary = paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request);

        assertThat(summary).isEqualTo("Credit card ending 1111");
    }

    @Test
    void creditCard_spacesInCardNumber_areStripped() {
        CheckoutRequest request = creditCard("4111 1111 1111 1111", "Alice Smith", "12", "30");

        String summary = paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request);

        assertThat(summary).isEqualTo("Credit card ending 1111");
    }

    @Test
    void creditCard_failsLuhnCheck_throwsBadRequest() {
        // Last digit changed from 1 to 2, breaking the checksum of an otherwise valid number.
        CheckoutRequest request = creditCard("4111111111111112", "Alice Smith", "12", "30");

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creditCard_nonDigitCharacters_throwsBadRequest() {
        // Only whitespace gets stripped, not dashes - a dash-formatted number is rejected as-is.
        CheckoutRequest request = creditCard("4111-1111-1111-1111", "Alice Smith", "12", "30");

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creditCard_tooShort_throwsBadRequest() {
        CheckoutRequest request = creditCard("411111", "Alice Smith", "12", "30");

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void creditCard_missingCardHolderName_throwsBadRequest() {
        CheckoutRequest request = creditCard("4111111111111111", null, "12", "30");

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creditCard_missingExpiry_throwsBadRequest() {
        CheckoutRequest request = creditCard("4111111111111111", "Alice Smith", null, null);

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void creditCard_invalidMonth_throwsBadRequest() {
        CheckoutRequest request = creditCard("4111111111111111", "Alice Smith", "13", "30");

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void creditCard_expiredCard_throwsBadRequest() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        CheckoutRequest request = creditCard("4111111111111111", "Alice Smith",
                String.valueOf(lastMonth.getMonthValue()), String.valueOf(lastMonth.getYear() % 100));

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void creditCard_amountOverThreshold_declinesWithPaymentRequired() {
        CheckoutRequest request = creditCard("4111111111111111", "Alice Smith", "12", "30");

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("1000.01"), request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void creditCard_amountExactlyAtThreshold_stillApproved() {
        CheckoutRequest request = creditCard("4111111111111111", "Alice Smith", "12", "30");

        String summary = paymentService.validateAndProcess(PaymentMethod.CREDIT_CARD, new BigDecimal("1000.00"), request);

        assertThat(summary).isEqualTo("Credit card ending 1111");
    }

    @Test
    void payPal_validEmail_returnsSummary() {
        CheckoutRequest request = new CheckoutRequest(null, PaymentMethod.PAYPAL, null, null, null, null, "alice@example.com");

        String summary = paymentService.validateAndProcess(PaymentMethod.PAYPAL, new BigDecimal("50.00"), request);

        assertThat(summary).isEqualTo("PayPal (alice@example.com)");
    }

    @Test
    void payPal_missingEmail_throwsBadRequest() {
        CheckoutRequest request = new CheckoutRequest(null, PaymentMethod.PAYPAL, null, null, null, null, null);

        assertThatThrownBy(() -> paymentService.validateAndProcess(PaymentMethod.PAYPAL, new BigDecimal("50.00"), request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cash_alwaysApproved() {
        CheckoutRequest request = new CheckoutRequest(null, PaymentMethod.CASH, null, null, null, null, null);

        String summary = paymentService.validateAndProcess(PaymentMethod.CASH, new BigDecimal("999999.00"), request);

        assertThat(summary).isEqualTo("Cash");
    }
}
