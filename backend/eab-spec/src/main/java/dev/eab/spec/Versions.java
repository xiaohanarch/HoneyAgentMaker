package dev.eab.spec;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import org.semver4j.Semver;
import org.semver4j.range.RangeList;
import org.semver4j.range.RangeListFactory;

/** 语义化版本与版本范围，范围写法与 npm 一致（^1.0、~1.2、>=1.0 <2、*） */
public final class Versions {

    private Versions() {}

    public static boolean isValid(String version) {
        return version != null && Semver.isValid(version);
    }

    public static boolean isValidRange(String range) {
        if (range == null || range.isBlank()) return false;
        try {
            RangeList list = RangeListFactory.create(range);
            return !list.get().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean satisfies(String version, String range) {
        return new Semver(version).satisfies(range);
    }

    public static int compare(String a, String b) {
        return new Semver(a).compareTo(new Semver(b));
    }

    /** 从新到旧排序用 */
    public static final Comparator<String> DESCENDING = (a, b) -> compare(b, a);

    /** 取满足范围的最高版本 */
    public static Optional<String> maxSatisfying(Collection<String> versions, String range) {
        return versions.stream()
                .filter(v -> isValid(v) && satisfies(v, range))
                .max(Versions::compare);
    }
}
