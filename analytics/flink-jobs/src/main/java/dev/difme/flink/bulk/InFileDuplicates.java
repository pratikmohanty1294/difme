package dev.difme.flink.bulk;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Keyed by external_ref. The occurrence with the LOWEST file offset wins and every other occurrence is rejected,
 * regardless of the order the batch sort delivers them — so re-running the same file always gives the same result
 * (audit determinism). In BATCH mode the end-of-key timer fires once all records of the key have arrived, and state
 * is released key by key, so memory stays constant.
 */
public class InFileDuplicates extends KeyedProcessFunction<String, BulkRecord, BulkRecord> {
    private transient ValueState<BulkRecord> winner;
    private transient ListState<BulkRecord> others;

    @Override
    public void open(OpenContext ctx) {
        winner = getRuntimeContext().getState(new ValueStateDescriptor<>("winner", BulkRecord.class));
        others = getRuntimeContext().getListState(new ListStateDescriptor<>("others", BulkRecord.class));
    }

    @Override
    public void processElement(BulkRecord r, Context ctx, Collector<BulkRecord> out) throws Exception {
        if (!r.valid()) { out.collect(r); return; }          // keyed by its own id; nothing to compare
        BulkRecord current = winner.value();
        if (current == null) {
            winner.update(r);
            ctx.timerService().registerEventTimeTimer(Long.MAX_VALUE);
        } else if (r.offset < current.offset) {
            others.add(current);
            winner.update(r);
        } else {
            others.add(r);
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<BulkRecord> out) throws Exception {
        BulkRecord first = winner.value();
        if (first == null) return;
        out.collect(first);
        for (BulkRecord dup : others.get()) out.collect(dup.reject("duplicate external_ref in this file (first at offset " + first.offset + ")"));
        winner.clear();
        others.clear();
    }
}
