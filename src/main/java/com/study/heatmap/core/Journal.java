package com.study.heatmap.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 回退日志。
 *
 * 「键鼠 10 分钟无操作 = 暂离」这条规则意味着：前 10 分钟是**乐观记账**的，
 * 到第 10 分钟发现你其实早就走了，必须把「最后一次操作之后」记下的那一串拍掉。
 * 所以每拍都要留一条可回滚的凭据，只保留最近一个暂离窗口的量。
 */
public final class Journal {

    public static final class Batch {
        public final long at;
        public final String relPath;
        public final String[] keys;
        public final double dt;

        public Batch(long at, String relPath, String[] keys, double dt) {
            this.at = at;
            this.relPath = relPath;
            this.keys = keys;
            this.dt = dt;
        }
    }

    private final Deque<Batch> batches = new ArrayDeque<>();

    public void add(Batch batch) {
        batches.addLast(batch);
    }

    /**
     * 回退所有晚于 cutoff 的拍，返回被抹掉的秒数。
     * 文件总时长每拍只扣一次（行时长是多行分摊的，不能按行扣总时长）。
     */
    public double rollbackAfter(Ledger ledger, long cutoff) {
        double removed = 0;
        while (!batches.isEmpty() && batches.peekLast().at > cutoff) {
            Batch batch = batches.removeLast();
            for (String key : batch.keys) {
                if (key != null) {
                    ledger.removeLine(batch.relPath, key, batch.dt);
                }
            }
            Ledger.FileEntry entry = ledger.peek(batch.relPath);
            if (entry != null) {
                entry.totalSeconds = Math.max(0, entry.totalSeconds - batch.dt);
            }
            removed += batch.dt;
        }
        return removed;
    }

    /** 早于 cutoff 的拍已经不可能被回退（最后一次操作最多在 10 分钟前）。 */
    public void pruneBefore(long cutoff) {
        while (!batches.isEmpty() && batches.peekFirst().at < cutoff) {
            batches.removeFirst();
        }
    }

    public int size() {
        return batches.size();
    }
}
