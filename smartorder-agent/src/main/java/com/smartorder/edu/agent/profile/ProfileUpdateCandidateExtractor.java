package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;

/**
 * Extracts candidate profile updates from the current user message only.
 */
public interface ProfileUpdateCandidateExtractor {

    UpdateStudentProfileRequest extract(String currentUserMessage);
}
