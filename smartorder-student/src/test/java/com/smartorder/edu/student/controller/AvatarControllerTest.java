package com.smartorder.edu.student.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarControllerTest {

    @Test
    void disabledAvatarDoesNotExposeAuthInfo() {
        AvatarController controller = new AvatarController();
        ReflectionTestUtils.setField(controller, "enabled", false);

        assertFalse(controller.getAvatarConfig().getData().get("enabled"));
        assertNull(controller.getAvatarAuthInfo().getData());
    }

    @Test
    void enabledAvatarIsVisibleInConfig() {
        AvatarController controller = new AvatarController();
        ReflectionTestUtils.setField(controller, "enabled", true);

        assertTrue(controller.getAvatarConfig().getData().get("enabled"));
    }
}
