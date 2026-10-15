package dev.eab.spec.schema;

import java.util.ArrayList;
import java.util.List;

/** 校验过程中收集的问题；路径是字段名（String）和数组下标（Integer）组成的列表 */
public final class Issues {

    public record Issue(String code, String message, List<Object> path) {
        public Issue {
            path = List.copyOf(path);
        }
    }

    private final List<Issue> items = new ArrayList<>();

    public void add(String code, String message, List<Object> path) {
        items.add(new Issue(code, message, path));
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public List<Issue> list() {
        return List.copyOf(items);
    }

    /** 把路径格式化成 spec.tools[2].mcp.source 的形式 */
    public static String formatPath(List<Object> path) {
        StringBuilder out = new StringBuilder();
        for (Object key : path) {
            if (key instanceof Integer index) {
                out.append('[').append(index).append(']');
            } else {
                if (!out.isEmpty()) out.append('.');
                out.append(key);
            }
        }
        return out.toString();
    }

    static List<Object> append(List<Object> path, Object key) {
        List<Object> next = new ArrayList<>(path);
        next.add(key);
        return next;
    }
}
