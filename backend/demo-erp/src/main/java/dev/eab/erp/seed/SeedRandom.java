package dev.eab.erp.seed;

import java.util.List;

/**
 * 固定种子的伪随机数（mulberry32），同一个种子每次生成相同的演示数据。
 * 算法与 TS 版逐位一致：用 int 的溢出乘法对应 Math.imul，用无符号右移对应 &gt;&gt;&gt;。
 */
public final class SeedRandom {

    private int state;

    public SeedRandom(long seed) {
        this.state = (int) seed;
    }

    public double next() {
        state = state + 0x6d2b79f5;
        int t = state;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }

    /** [min, max] 之间的整数 */
    public int nextInt(int min, int max) {
        return min + (int) Math.floor(next() * (max - min + 1));
    }

    public <T> T pick(List<T> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("不能从空列表里取值");
        return items.get(nextInt(0, items.size() - 1));
    }

    public boolean chance(double probability) {
        return next() < probability;
    }

    /** 保留两位小数的金额，舍入方式与 JS 的 Math.round 一致 */
    public double amount(double min, double max) {
        return Math.round((min + next() * (max - min)) * 100) / 100.0;
    }
}
