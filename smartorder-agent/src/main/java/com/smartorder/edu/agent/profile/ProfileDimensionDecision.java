package com.smartorder.edu.agent.profile;

import lombok.Data;

@Data
public class ProfileDimensionDecision {

    private String dimensionKey;

    private String evidence;

    private Double confidence;

    private String reason;
}
