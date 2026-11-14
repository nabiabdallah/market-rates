package io.github.bot.marketrates.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/** Captures everything logged (message, arguments, throwable class and message) while open. */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture() {
        appender.start();
        root.addAppender(appender);
    }

    public static LogCapture start() {
        return new LogCapture();
    }

    /** All captured events rendered as one string (level, formatted message, throwable chain). */
    public String text() {
        return appender.list.stream().map(LogCapture::render).collect(Collectors.joining("\n"));
    }

    /** Only WARN-level events. */
    public String warnText() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(LogCapture::render)
                .collect(Collectors.joining("\n"));
    }

    public long warnCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    private static String render(ILoggingEvent e) {
        var sb = new StringBuilder();
        sb.append(e.getLevel()).append(' ').append(e.getFormattedMessage());
        for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
            sb.append(' ').append(t.getClassName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
