package com.ddd.d3.judge.application;

public interface JudgeEventPublisher {
    void publish(PendingJudgeEvent event);
}
