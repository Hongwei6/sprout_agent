package com.sprout.agent.agents.ppt;

/** The next phase to execute; SUCCESS and FAILED are terminal/result states. */
public enum PptInstStatus {
    INIT, REQUIREMENT, SEARCH, TEMPLATE, OUTLINE, SCHEMA, RENDER, SUCCESS, FAILED
}
