package io.freemytv;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Ordered user choices, with seven readable tiles per dock page. No total-app limit. */
final class ShelfPolicy {
    static final int PAGE_SIZE = 7;
    private ShelfPolicy() {}
    static List<String> packages(String csv) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : csv.split(",")) {
            String pkg = value.trim();
            if (pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) unique.add(pkg);
        }
        return new ArrayList<>(unique);
    }
    static List<String> defaults(String csv) { return packages(csv); }
    static int editorRow(int appIndex) { return appIndex < 0 ? 0 : appIndex + 1; }
    static List<String> toggle(List<String> current, String pkg) {
        ArrayList<String> next = new ArrayList<>(current);
        if (!packages(pkg).contains(pkg)) return next;
        if (!next.remove(pkg)) next.add(pkg);
        return next;
    }
    static List<String> move(List<String> current, String pkg, int direction) {
        ArrayList<String> next = new ArrayList<>(current);
        int at = next.indexOf(pkg), to = at + direction;
        if (at < 0 || (direction != -1 && direction != 1) || to < 0 || to >= next.size()) return next;
        next.remove(at);
        next.add(to, pkg);
        return next;
    }
    static int start(int index) { return Math.max(0, index) / PAGE_SIZE * PAGE_SIZE; }
    static int visibleCount(int first, int total) { return Math.max(0, Math.min(PAGE_SIZE, total - first)); }
    static boolean accessible(int index, int first, int total) {
        return index >= 0 && index < total && index >= first - 1 && index <= first + PAGE_SIZE;
    }
}
