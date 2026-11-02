package com.sprout.agent.config;

import com.sprout.agent.service.AgentTaskManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class AgentTaskRedisConfiguration {

    @Bean
    RedisMessageListenerContainer agentTaskStopListener(RedisConnectionFactory connectionFactory,
                                                         AgentTaskManager manager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(manager, new ChannelTopic(AgentTaskManager.STOP_CHANNEL));
        return container;
    }
}
