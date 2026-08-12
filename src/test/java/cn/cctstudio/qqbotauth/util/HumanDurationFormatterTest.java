package cn.cctstudio.qqbotauth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanDurationFormatterTest {
    @Test
    void selectsReadableChineseUnits() {
        assertEquals("45 秒", HumanDurationFormatter.seconds(45));
        assertEquals("5 分钟", HumanDurationFormatter.seconds(300));
        assertEquals("1 分钟 30 秒", HumanDurationFormatter.seconds(90));
        assertEquals("1 小时 5 分钟", HumanDurationFormatter.seconds(3_900));
    }
}
