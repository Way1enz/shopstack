package com.ecommerce.notification.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// ensureConsumerGroupExists and isBusyGroup are private. Exercised directly via
// reflection to cover the cause-chain walk branches.
@ExtendWith(MockitoExtension.class)
class RedisStreamConfigTest {

    private final RedisStreamConfig config = new RedisStreamConfig();

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamOperations<String, String, String> streamOperations;

    private boolean isBusyGroup(Throwable e) throws Exception {
        Method method = RedisStreamConfig.class.getDeclaredMethod("isBusyGroup", Throwable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(config, e);
    }

    private void ensureConsumerGroupExists(StringRedisTemplate template) throws Throwable {
        Method method = RedisStreamConfig.class.getDeclaredMethod("ensureConsumerGroupExists", StringRedisTemplate.class);
        method.setAccessible(true);
        try {
            method.invoke(config, template);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // --- isBusyGroup ---

    @Test
    void isBusyGroup_topLevelMessageContainsBusyGroup_true() throws Exception {
        Exception e = new RedisSystemException("BUSYGROUP Consumer Group name already exists", null);

        assertThat(isBusyGroup(e)).isTrue();
    }

    @Test
    void isBusyGroup_causeContainsBusyGroup_true() throws Exception {
        Throwable cause = new RuntimeException("BUSYGROUP Consumer Group name already exists");
        Exception e = new RedisSystemException("Error in execution", cause);

        assertThat(isBusyGroup(e)).isTrue();
    }

    @Test
    void isBusyGroup_nestedTwoLevelsDown_true() throws Exception {
        Throwable root = new RuntimeException("BUSYGROUP Consumer Group name already exists");
        Throwable mid = new RuntimeException("wrapped", root);
        Exception e = new RedisSystemException("Error in execution", mid);

        assertThat(isBusyGroup(e)).isTrue();
    }

    @Test
    void isBusyGroup_noBusyGroupAnywhere_false() throws Exception {
        Throwable cause = new RuntimeException("connection refused");
        Exception e = new RedisSystemException("Error in execution", cause);

        assertThat(isBusyGroup(e)).isFalse();
    }

    @Test
    void isBusyGroup_nullMessageInChain_doesNotThrowAndReturnsFalse() throws Exception {
        Throwable cause = new RuntimeException((String) null);
        Exception e = new RedisSystemException("Error in execution", cause);

        assertThat(isBusyGroup(e)).isFalse();
    }

    @Test
    void isBusyGroup_singleThrowableNoCause_false() throws Exception {
        Exception e = new DataAccessResourceFailureException("connection refused");

        assertThat(isBusyGroup(e)).isFalse();
    }

    // --- ensureConsumerGroupExists ---

    @Test
    void ensureConsumerGroupExists_createSucceeds_doesNotThrow() {
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);

        assertThatCode(() -> ensureConsumerGroupExists(redisTemplate)).doesNotThrowAnyException();
        verify(streamOperations).createGroup("order-events", ReadOffset.from("0"), "notification-service-group");
    }

    @Test
    void ensureConsumerGroupExists_busyGroup_isSwallowed() {
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        doThrow(new RedisSystemException("Error in execution",
                new RuntimeException("BUSYGROUP Consumer Group name already exists")))
                .when(streamOperations).createGroup("order-events", ReadOffset.from("0"), "notification-service-group");

        assertThatCode(() -> ensureConsumerGroupExists(redisTemplate)).doesNotThrowAnyException();
    }

    @Test
    void ensureConsumerGroupExists_nonBusyGroupFailure_isRethrown() {
        when(redisTemplate.<String, String>opsForStream()).thenReturn(streamOperations);
        doThrow(new RedisSystemException("Error in execution", new RuntimeException("connection refused")))
                .when(streamOperations).createGroup("order-events", ReadOffset.from("0"), "notification-service-group");

        assertThatThrownBy(() -> ensureConsumerGroupExists(redisTemplate))
                .isInstanceOf(RedisSystemException.class);
    }
}
