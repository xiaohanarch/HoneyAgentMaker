package dev.eab.erp.bo;

import dev.eab.common.IsoTime;
import java.util.Map;

/** 列表接口统计摘要的共用口径：比率取整与逾期天数的算法在这里只写一份 */
final class Summaries {

    private Summaries() {}

    /** 一位小数的百分比，与演示剧本 CEL 的取整口径一致（向下截断到一位小数） */
    static double percent1(double part, double total) {
        return total <= 0 ? 0.0 : Math.floor(part * 1000.0 / total) / 10.0;
    }

    /** 交付逾期的自然日差：已交付按实际交付日期，未交付按今天；提前交付记 0（口径与语义包 overdue_days 一致） */
    static long deliveryOverdueDays(Map<String, Object> delivery, String today) {
        String actual = (String) delivery.get("actualDate");
        String end = actual == null ? today : actual;
        return Math.max(0, IsoTime.daysBetween((String) delivery.get("plannedDate"), end));
    }
}
