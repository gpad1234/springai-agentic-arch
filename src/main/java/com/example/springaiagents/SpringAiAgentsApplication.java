package com.example.springaiagents;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SpringAiAgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiAgentsApplication.class, args);
    }

    @Bean
    static BeanPostProcessor omitAnthropicTemperatureForCurrentModel() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AnthropicChatProperties properties) {
                    properties.getOptions().setTemperature(null);
                }
                return bean;
            }
        };
    }
}