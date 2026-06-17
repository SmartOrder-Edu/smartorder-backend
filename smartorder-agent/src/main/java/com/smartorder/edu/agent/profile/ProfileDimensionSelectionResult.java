package com.smartorder.edu.agent.profile;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProfileDimensionSelectionResult {

    private List<ProfileDimensionDecision> dimensions = new ArrayList<>();
}
