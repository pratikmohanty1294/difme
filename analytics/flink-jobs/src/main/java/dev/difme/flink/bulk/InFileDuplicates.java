package dev.difme.flink.bulk;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/** Keyed by external_ref: the first occurrence in the file passes, later ones are rejected (batch mode sorts by key). */
public class InFileDuplicates extends KeyedProcessFunction<String, BulkRecord, BulkRecord> {
    private transient ValueState<String> first;

    @Override
    public void open(OpenContext ctx) {
        first = getRuntimeContext().getState(new ValueStateDescriptor<>("first", String.class));
    }

    @Override
    public void processElement(BulkRecord r, Context ctx, Collector<BulkRecord> out) throws Exception {
        if (r.valid()) {
            String seen = first.value();
            if (seen == null) first.update(r.id);
            else r.reject("duplicate external_ref in this file (first at " + seen + ")");
        }
        out.collect(r);
    }
}
