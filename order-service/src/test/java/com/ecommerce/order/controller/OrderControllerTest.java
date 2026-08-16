package com.ecommerce.order.controller;

import com.ecommerce.order.dto.CheckoutRequest;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    private Order sampleOrder() {
        return Order.builder()
                .id(1L)
                .userId(1L)
                .status(OrderStatus.PAID)
                .totalAmount(new BigDecimal("39.98"))
                .paymentSummary("Cash")
                .items(new ArrayList<>())
                .build();
    }

    @Test
    void checkout_missingUserIdHeader_returns400() throws Exception {
        String body = "{\"paymentMethod\": \"CASH\"}";

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkout_missingPaymentMethod_returns400() throws Exception {
        String body = "{\"shippingAddress\": \"123 Main St\"}";

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkout_validRequest_returns201WithOrderDetails() throws Exception {
        when(orderService.checkout(eq(1L), any(CheckoutRequest.class))).thenReturn(sampleOrder());
        String body = "{\"shippingAddress\": \"123 Main St\", \"paymentMethod\": \"CASH\"}";

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentSummary").value("Cash"))
                .andExpect(jsonPath("$.totalAmount").value(39.98));
    }

    @Test
    void listMyOrders_returnsOrdersForHeaderUser() throws Exception {
        when(orderService.listForUser(1L)).thenReturn(List.of(sampleOrder()));

        mockMvc.perform(get("/api/orders").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("PAID"));
    }

    @Test
    void getById_returnsOrder() throws Exception {
        when(orderService.getById(1L, 1L)).thenReturn(sampleOrder());

        mockMvc.perform(get("/api/orders/1").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void cancel_returnsCancelledOrder() throws Exception {
        Order cancelled = sampleOrder();
        cancelled.setStatus(OrderStatus.CANCELLED);
        when(orderService.cancel(1L, 1L)).thenReturn(cancelled);

        mockMvc.perform(post("/api/orders/1/cancel").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
