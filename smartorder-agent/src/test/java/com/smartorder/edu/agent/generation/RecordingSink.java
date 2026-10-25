package com.smartorder.edu.agent.generation;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用事件出口：记录收到的所有事件与连接状态。
 */
public class RecordingSink implements AgentEventSink {

    public final List<AgentGenerationEvent> events = new ArrayList<>();

    public boolean completed;

    public boolean failed;

    public long handledSeq;

    @Override
    public void sendEvent(AgentGenerationEvent event) {
        events.add(event);
        handledSeq = event.getSeq();
    }

    @Override
    public void sendHeartbeat() {
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public void completeWithError(Throwable error) {
        failed = true;
    }

    public List<Long> seqs() {
        List<Long> seqs = new ArrayList<>();
        for (AgentGenerationEvent event : events) {
            seqs.add(event.getSeq());
        }
        return seqs;
    }

    public List<String> eventNames() {
        List<String> names = new ArrayList<>();
        for (AgentGenerationEvent event : events) {
            names.add(event.getEventName());
        }
        return names;
    }
}
