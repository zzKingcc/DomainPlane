package com.zzkingcc.domainplane.server.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;

/**
 * 控制台日志的级别配色：蓝 → 黄 → 红，逐级递增。
 * @author zzkingcc
 */
public class LevelColorConverter extends ForegroundCompositeConverterBase<ILoggingEvent> {

    @Override
    protected String getForegroundColorCode(ILoggingEvent event) {
        Level level = event.getLevel();
        return switch (level.toInt()) {
            case Level.INFO_INT -> "34";
            case Level.WARN_INT -> "33";
            case Level.ERROR_INT -> "31";
            default -> "39";
        };
    }
}
