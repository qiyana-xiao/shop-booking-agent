package com.shopbooking;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.shopbooking.mapper")
@ConfigurationPropertiesScan("com.shopbooking.config")
public class ShopBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopBookingApplication.class, args);
    }
}
