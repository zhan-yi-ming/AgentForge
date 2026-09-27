package com.agentforge.core.agent.application;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Client-selectable task mode; model destinations remain deployment-owned. */
public enum AgentTaskType {
    FORMAT, REWRITE, PLAN, REVIEW, ANSWER;

    @JsonCreator
    public static AgentTaskType fromJson(String value) {
        return valueOf(value);
    }
}
