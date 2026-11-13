package tools;

import com.study.heatmap.core.Heat;
import com.study.heatmap.core.Journal;
import com.study.heatmap.core.Ledger;

/**
 * 回退逻辑的验证。不依赖 IDE，可以直接跑：
 *   java -cp build/core-classes tools.JournalTest
 */
public final class JournalTest {

    private static int failures = 0;

    public static void main(String[] args) {
        rollbackDropsSecondsAfterLastInput();
        rollbackDoesNotDropEarlierBatches();
        fileTotalIsNotDoubleCounted();
        pruneKeepsOnlyRollbackWindow();
        heatScaleIsMonotonic();

        if (failures == 0) {
            System.out.println("ALL PASS");
        } else {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
    }

    /** 三拍各 1 秒；最后一次操作发生在第 1 拍之后 → 后两拍必须被抹掉。 */
    private static void rollbackDropsSecondsAfterLastInput() {
        Ledger ledger = new Ledger();
        Journal journal = new Journal();
        String rel = "A.java";
        for (int i = 0; i < 3; i++) {
            long at = 1000L * (i + 1);
            String[] keys = {"aaa#0", "bbb#0"};
            Ledger.FileEntry entry = ledger.entry(rel);
            for (String k : keys) {
                entry.lineSeconds.merge(k, 1.0, Double::sum);
            }
            entry.totalSeconds += 1.0;
            journal.add(new Journal.Batch(at, rel, keys, 1.0));
        }

        double removed = journal.rollbackAfter(ledger, 1500L);

        check("removed seconds == 2", removed, 2.0);
        check("line a left 1s", line(ledger, rel, "aaa#0"), 1.0);
        check("line b left 1s", line(ledger, rel, "bbb#0"), 1.0);
        check("file total charged once", ledger.peek(rel).totalSeconds, 1.0);
        check("journal trimmed at cutoff", journal.size(), 1);
    }

    /** cutoff 早于所有拍时，一行都不该动。 */
    private static void rollbackDoesNotDropEarlierBatches() {
        Ledger ledger = new Ledger();
        Journal journal = new Journal();
        String rel = "B.java";
        Ledger.FileEntry entry = ledger.entry(rel);
        entry.lineSeconds.put("x#0", 2.0);
        entry.totalSeconds = 2.0;
        journal.add(new Journal.Batch(5000L, rel, new String[]{"x#0"}, 2.0));

        double removed = journal.rollbackAfter(ledger, 9000L);

        check("nothing to roll back", removed, 0.0);
        check("line seconds kept", line(ledger, rel, "x#0"), 2.0);
    }

    /** 一屏 50 行同时计时，回退时文件总时长只能减 1 秒，不能减 50 秒。 */
    private static void fileTotalIsNotDoubleCounted() {
        Ledger ledger = new Ledger();
        Journal journal = new Journal();
        String rel = "C.java";
        String[] keys = new String[50];
        Ledger.FileEntry entry = ledger.entry(rel);
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "k" + i + "#0";
            entry.lineSeconds.put(keys[i], 1.0);
        }
        entry.totalSeconds = 1.0;
        journal.add(new Journal.Batch(7000L, rel, keys, 1.0));

        journal.rollbackAfter(ledger, 6500L);

        check("file total back to 0", entry.totalSeconds, 0.0);
        check("50 lines cleared", entry.lineSeconds.size(), 0);
    }

    private static void pruneKeepsOnlyRollbackWindow() {
        Journal journal = new Journal();
        for (int i = 0; i < 5; i++) {
            journal.add(new Journal.Batch(1000L * i, "D.java", new String[]{"z#0"}, 1.0));
        }
        journal.pruneBefore(2500L);
        check("keep 2 batches after 2500", journal.size(), 2);
    }

    private static void heatScaleIsMonotonic() {
        check("zero seconds no color", Heat.intensity(0, 100), 0.0);
        check("max maps to 1", Heat.intensity(100, 100), 1.0);
        check("log scale compresses", Heat.intensity(10, 100) > 0.5 ? 1.0 : 0.0, 1.0);
    }

    private static double line(Ledger ledger, String rel, String key) {
        Ledger.FileEntry e = ledger.peek(rel);
        if (e == null) {
            return -1;
        }
        return e.lineSeconds.getOrDefault(key, 0.0);
    }

    private static void check(String name, double actual, double expected) {
        boolean ok = Math.abs(actual - expected) < 0.001;
        if (!ok) {
            failures++;
        }
        System.out.printf("%s  %-34s actual=%.3f expected=%.3f%n", ok ? "OK  " : "FAIL", name, actual, expected);
    }
}
