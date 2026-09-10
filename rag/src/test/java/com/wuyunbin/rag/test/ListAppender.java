package com.wuyunbin.rag.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试工具：为某个 Logger 挂载内存 appender，捕获其 DEBUG 及以上日志，
 * 并在 {@link #detachLogger()} 时移除并恢复原日志级别。
 */
public final class ListAppender extends AppenderBase<ILoggingEvent> {

    private static final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
    private static Logger target;
    private static Level originalLevel;
    private static ListAppender current;

    public static ListAppender attachLogger(Class<?> clazz) {
        target = (Logger) LoggerFactory.getLogger(clazz);
        originalLevel = target.getLevel();
        target.setLevel(Level.DEBUG);

        current = new ListAppender();
        current.start();
        target.addAppender(current);
        return current;
    }

    public static void detachLogger() {
        if (target != null && current != null) {
            target.detachAppender(current);
            if (originalLevel != null) {
                target.setLevel(originalLevel);
            }
            current.stop();
        }
        messages.clear();
        current = null;
        target = null;
    }

    @Override
    protected void append(ILoggingEvent event) {
        messages.add(event.getFormattedMessage());
    }

    public static List<String> getFormattedMessages() {
        return List.copyOf(messages);
    }
}