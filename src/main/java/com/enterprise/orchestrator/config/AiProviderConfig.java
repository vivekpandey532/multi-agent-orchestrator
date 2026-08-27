package com.enterprise.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configures the {@link ChatClient.Builder} bean based on the
 * {@code orchestrator.ai.provider} property.
 * <p>
 * Supported values: {@code openai}, {@code ollama}, {@code anthropic}.
 * Only one provider is active at a time — Spring Boot auto-configures the
 * matching {@code ChatModel} bean, and this class wires it into a
 * {@code ChatClient.Builder} that all agents share.
 */
@Configuration
public class AiProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(AiProviderConfig.class);

    @Bean
    @Primary
    @ConditionalOnProperty(name = "orchestrator.ai.provider", havingValue = "openai", matchIfMissing = true)
    public ChatModel primaryOpenAiChatModel(OpenAiChatModel chatModel) {
        return chatModel;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "orchestrator.ai.provider", havingValue = "ollama")
    public ChatModel primaryOllamaChatModel(OllamaChatModel chatModel) {
        return chatModel;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "orchestrator.ai.provider", havingValue = "anthropic")
    public ChatModel primaryAnthropicChatModel(AnthropicChatModel chatModel) {
        return chatModel;
    }

    @Bean
    @ConditionalOnProperty(name = "orchestrator.ai.provider", havingValue = "openai", matchIfMissing = true)
    public ChatClient.Builder openAiChatClientBuilder(OpenAiChatModel chatModel) {
        log.info("AI provider: OpenAI");
        return ChatClient.builder(chatModel);
    }

    @Bean
    @ConditionalOnProperty(name = "orchestrator.ai.provider", havingValue = "ollama")
    public ChatClient.Builder ollamaChatClientBuilder(OllamaChatModel chatModel) {
        log.info("AI provider: Ollama");
        return ChatClient.builder(chatModel);
    }

    @Bean
    @ConditionalOnProperty(name = "orchestrator.ai.provider", havingValue = "anthropic")
    public ChatClient.Builder anthropicChatClientBuilder(AnthropicChatModel chatModel) {
        log.info("AI provider: Anthropic");
        return ChatClient.builder(chatModel);
    }
}
