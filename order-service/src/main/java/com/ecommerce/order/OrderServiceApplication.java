package com.ecommerce.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns a user's Redis-backed cart into a durable order; see OrderService#checkout for the flow.
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
